package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Moves exact default MODIFIERS tooltip flags into the constructor's attribute component. */
class LegacyDefaultTooltipFlagsMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val changes = mutableListOf<Change>()
        val migratedSources = linkedMapOf<Path, String>()
        Files.walk(srcDir).use { stream -> stream.filter { it.extension == "java" }.toList() }.forEach { file ->
            val source = file.readText()
            if (!source.contains("getDefaultTooltipHideFlags") || !source.contains("TooltipPart.MODIFIERS")) return@forEach
            val parsed = parser.parse(source)
            val cu = parsed.result.orElseThrow {
                IllegalStateException("Cannot parse default tooltip flags source $file: ${parsed.problems.joinToString()}")
            }
            LexicalPreservingPrinter.setup(cu)
            var migratedMethods = 0

            cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach classLoop@{ type ->
                val methods = type.methods.filter { it.nameAsString == "getDefaultTooltipHideFlags" }
                if (methods.isEmpty()) return@classLoop
                if (methods.size != 1) {
                    throw IllegalStateException("Multiple getDefaultTooltipHideFlags methods in $file")
                }
                val method = methods.single()
                requireExactModifiersFlag(method, cu, file)
                val constructors = type.constructors
                if (constructors.isEmpty()) {
                    throw IllegalStateException(
                        "Default MODIFIERS tooltip flag owner '${type.nameAsString}' has no source constructor in $file"
                    )
                }
                var directSuperConstructors = 0
                var constructorWrites = 0
                constructors.forEach { constructor ->
                    val superCall = constructor.body.statements.firstOrNull() as? ExplicitConstructorInvocationStmt
                        ?: throw IllegalStateException(
                            "Default MODIFIERS tooltip constructor has no explicit source-proven delegation in $file"
                        )
                    if (!superCall.isThis) {
                        directSuperConstructors++
                        val writes = migrateConstructorAttributes(type, constructor, superCall, cu, file)
                        if (writes != 1) {
                            throw IllegalStateException(
                                "Default MODIFIERS tooltip constructor has no source-proven Properties.attributes migration in $file"
                            )
                        }
                        constructorWrites += writes
                    }
                }
                if (directSuperConstructors == 0 || constructorWrites != directSuperConstructors) {
                    throw IllegalStateException(
                        "Default MODIFIERS tooltip flag owner '${type.nameAsString}' has no complete constructor path in $file"
                    )
                }
                method.remove()
                migratedMethods++
            }

            if (migratedMethods == 0) return@forEach
            cu.imports.filter {
                !it.isStatic && it.nameAsString.endsWith("ItemStack.TooltipPart")
            }.toList().forEach { it.remove() }
            val migrated = LexicalPreservingPrinter.print(cu)
            if (migrated.contains("getDefaultTooltipHideFlags") || migrated.contains("TooltipPart.MODIFIERS")) {
                throw IllegalStateException("Legacy default tooltip flags remain in $file")
            }
            migratedSources[file] = migrated
            changes += Change(
                file = file,
                line = 1,
                description = "Preserve default item attributes while hiding their tooltip through the 1.21 component",
                before = "getDefaultTooltipHideFlags() -> TooltipPart.MODIFIERS",
                after = "Properties.attributes(attributes.withTooltip(false))",
                confidence = Confidence.HIGH,
                ruleId = "struct-default-tooltip-modifiers-component"
            )
        }
        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changes
    }

    private fun requireExactModifiersFlag(method: MethodDeclaration, cu: CompilationUnit, file: Path) {
        if (method.typeAsString != "int" || method.parameters.size != 1 ||
            !isExactImportedType(
                method.parameters.single().typeAsString,
                "ItemStack",
                "net.minecraft.world.item.ItemStack",
                cu
            )) {
            throw IllegalStateException("Unsupported getDefaultTooltipHideFlags signature in $file")
        }
        val statements = method.body.orElse(null)?.statements
            ?: throw IllegalStateException("Default tooltip flags method has no body in $file")
        if (statements.size != 1) {
            throw IllegalStateException("Default tooltip flags method has nontrivial control flow in $file")
        }
        val expression = (statements.single() as? ReturnStmt)?.expression?.orElse(null)?.toString()
        val exactBareTooltipPart = expression == "TooltipPart.MODIFIERS.getMask()" && cu.imports.any {
            !it.isStatic && it.nameAsString == "net.minecraft.world.item.ItemStack.TooltipPart"
        }
        val exactQualifiedTooltipPart = expression == "ItemStack.TooltipPart.MODIFIERS.getMask()" &&
            isExactImportedType("ItemStack", "ItemStack", "net.minecraft.world.item.ItemStack", cu)
        if (!exactBareTooltipPart && !exactQualifiedTooltipPart) {
            throw IllegalStateException("Default tooltip flags are not exactly MODIFIERS in $file: $expression")
        }
    }

    private fun migrateConstructorAttributes(
        owner: ClassOrInterfaceDeclaration,
        constructor: ConstructorDeclaration,
        superCall: ExplicitConstructorInvocationStmt,
        cu: CompilationUnit,
        file: Path
    ): Int {
        val propertyParameters = constructor.parameters.filter {
            isMinecraftItemProperties(it.typeAsString, owner, cu)
        }.map { it.nameAsString }.toSet()
        if (propertyParameters.isEmpty()) return 0
        val attributesCalls = superCall.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == "attributes" && call.arguments.size == 1 &&
                (call.scope.orElse(null) as? NameExpr)?.nameAsString in propertyParameters
        }
        if (attributesCalls.size > 1) {
            throw IllegalStateException("Constructor has multiple Properties.attributes calls in $file")
        }
        val call = attributesCalls.singleOrNull()
        if (call == null) {
            val isSword = isMinecraftSword(owner, cu)
            val propertyArgument = superCall.arguments.getOrNull(3) as? NameExpr
            if (!isSword || superCall.arguments.size != 4 || propertyArgument == null ||
                propertyArgument.nameAsString !in propertyParameters) {
                return 0
            }
            val tier = superCall.arguments[0].clone()
            val damage = superCall.arguments[1].clone()
            val speed = superCall.arguments[2].clone()
            val createAttributes = MethodCallExpr(NameExpr("SwordItem"), "createAttributes")
                .addArgument(tier.clone())
                .addArgument(damage)
                .addArgument(speed)
            val hiddenAttributes = MethodCallExpr(createAttributes, "withTooltip").addArgument("false")
            val properties = MethodCallExpr(propertyArgument.clone(), "attributes").addArgument(hiddenAttributes)
            superCall.arguments.clear()
            superCall.addArgument(tier)
            superCall.addArgument(properties)
            return 1
        }
        val attributes = call.arguments.single()
        if (attributes is MethodCallExpr && attributes.nameAsString == "withTooltip") {
            throw IllegalStateException("Constructor attributes already configure tooltip visibility in $file")
        }
        call.setArgument(0, MethodCallExpr(attributes.clone(), "withTooltip").addArgument("false"))
        return 1
    }

    private fun isMinecraftSword(owner: ClassOrInterfaceDeclaration, cu: CompilationUnit): Boolean {
        val type = owner.extendedTypes.singleOrNull()?.nameWithScope ?: return false
        return isExactImportedType(type, "SwordItem", "net.minecraft.world.item.SwordItem", cu)
    }

    private fun isMinecraftItemProperties(
        typeName: String,
        owner: ClassOrInterfaceDeclaration,
        cu: CompilationUnit
    ): Boolean = when (typeName) {
        "net.minecraft.world.item.Item.Properties" -> true
        "Item.Properties" -> isExactImportedType("Item", "Item", "net.minecraft.world.item.Item", cu)
        "Properties" -> cu.imports.any {
            !it.isStatic && it.nameAsString == "net.minecraft.world.item.Item.Properties"
        } || isMinecraftSword(owner, cu)
        else -> false
    }

    private fun isExactImportedType(
        typeName: String,
        simpleName: String,
        qualifiedName: String,
        cu: CompilationUnit
    ): Boolean = typeName == qualifiedName || typeName == simpleName && cu.imports.any {
        !it.isStatic && it.nameAsString == qualifiedName
    }
}
