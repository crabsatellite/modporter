package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Preserves custom trigger dispatch logic while migrating the 1.20 criterion instance contract. */
class LegacyCriterionTriggerHierarchyMigration {
    private data class ParsedSource(
        val file: Path,
        val source: String,
        val compilationUnit: CompilationUnit
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val candidateFiles = Files.walk(srcDir)
            .filter { it.extension == "java" }
            .toList()
        val directUnits = candidateFiles.mapNotNull { file ->
            val source = file.readText()
            if (!source.contains("CriterionTrigger")) null else parse(parser, file, source)
        }
        val bases = directUnits.mapNotNull { unit ->
            unit.compilationUnit.types
                .filterIsInstance<ClassOrInterfaceDeclaration>()
                .singleOrNull { isLegacyGenericBase(it) }
                ?.let { unit to it.nameAsString }
        }
        if (bases.isEmpty()) return emptyList()
        val directFiles = directUnits.mapTo(hashSetOf()) { it.file }
        val baseNames = bases.map { it.second }.toSet()
        val childPattern = Regex("""\bextends\s+(?:[A-Za-z_$][\w$]*\.)*(?:${baseNames.joinToString("|") { Regex.escape(it) }})\s*<""")
        val childUnits = candidateFiles.asSequence()
            .filterNot { it in directFiles }
            .mapNotNull { file ->
                val source = file.readText()
                if (!childPattern.containsMatchIn(source)) null else parse(parser, file, source)
            }
            .toList()
        val units = directUnits + childUnits

        val changes = mutableListOf<Change>()
        for ((baseUnit, baseName) in bases) {
            migrateBase(baseUnit, baseName, dryRun)?.let(changes::add)
            units.filter { it.file != baseUnit.file }.forEach { unit ->
                val child = unit.compilationUnit.types
                    .filterIsInstance<ClassOrInterfaceDeclaration>()
                    .singleOrNull { declaration ->
                        declaration.extendedTypes.any { it.nameAsString == baseName }
                    }
                    ?: return@forEach
                migrateStatelessChild(unit, child, dryRun)?.let(changes::add)
            }
        }
        return changes
    }

    private fun parse(parser: JavaParser, file: Path, source: String): ParsedSource {
        val result = parser.parse(source)
        val cu = result.result.orElseThrow {
            IllegalStateException("Cannot parse criterion trigger source $file: ${result.problems.joinToString()}")
        }
        return ParsedSource(file, source, cu)
    }

    private fun isLegacyGenericBase(declaration: ClassOrInterfaceDeclaration): Boolean {
        if (!declaration.isAbstract || declaration.typeParameters.size != 1) return false
        val parameter = declaration.typeParameters.single()
        val expectedBound = "${declaration.nameAsString}.Instance"
        if (parameter.typeBound.none { it.toString() == expectedBound }) return false
        if (declaration.implementedTypes.none { it.nameAsString == "CriterionTrigger" }) return false
        val instance = nestedInstance(declaration) ?: return false
        return instance.extendedTypes.any {
            it.nameAsString in setOf("AbstractCriterionTriggerInstance", "SimpleInstance") ||
                it.toString() == "SimpleCriterionTrigger.SimpleInstance"
        }
    }

    private fun migrateBase(unit: ParsedSource, baseName: String, dryRun: Boolean): Change? {
        val cu = unit.compilationUnit
        val declaration = cu.types.filterIsInstance<ClassOrInterfaceDeclaration>()
            .single { it.nameAsString == baseName }
        LexicalPreservingPrinter.setup(cu)

        declaration.findAll(MethodCallExpr::class.java)
            .filter { it.nameAsString == "getTriggerInstance" }
            .forEach { it.setName("trigger") }
        declaration.methods
            .filter { it.nameAsString == "getId" && it.typeAsString == "ResourceLocation" }
            .forEach { method -> method.annotations.filter { it.nameAsString == "Override" }.forEach { it.remove() } }

        val instance = nestedInstance(declaration)
            ?: throw IllegalStateException("Missing nested Instance in legacy criterion base $baseName")
        val constructor = instance.constructors.singleOrNull { isLegacyInstanceConstructor(it) }
            ?: throw IllegalStateException(
                "Legacy criterion base $baseName must have one Instance constructor that only delegates id and predicate"
            )
        constructor.remove()
        instance.extendedTypes.clear()
        instance.addImplementedType("SimpleInstance")
        cu.addImport("net.minecraft.advancements.critereon.SimpleCriterionTrigger.SimpleInstance")
        removeImport(cu, "net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance")
        removeImport(cu, "net.minecraft.advancements.critereon.SimpleCriterionTrigger")
        removeImportIfUnused(cu, "net.minecraft.advancements.critereon.ContextAwarePredicate")

        return writeChange(
            unit,
            LexicalPreservingPrinter.print(cu),
            dryRun,
            "Migrate generic criterion trigger hierarchy without replacing dispatch logic",
            "AbstractCriterionTriggerInstance + Listener.getTriggerInstance()",
            "SimpleInstance + Listener.trigger()",
            "struct-criterion-trigger-generic-hierarchy"
        )
    }

    private fun migrateStatelessChild(
        unit: ParsedSource,
        declaration: ClassOrInterfaceDeclaration,
        dryRun: Boolean
    ): Change? {
        val cu = unit.compilationUnit
        LexicalPreservingPrinter.setup(cu)
        val instance = nestedInstance(declaration)
            ?: throw IllegalStateException("Criterion trigger child ${declaration.nameAsString} has no nested Instance")
        val legacyConstructor = instance.constructors.singleOrNull { isStatelessChildConstructor(it) }
            ?: throw IllegalStateException(
                "Criterion trigger child ${declaration.nameAsString} has state that cannot be migrated as a stateless codec"
            )
        val createInstance = declaration.methods.singleOrNull { isStatelessCreateInstance(it) }
            ?: throw IllegalStateException(
                "Criterion trigger child ${declaration.nameAsString} lacks an exact stateless createInstance method"
            )

        createInstance.remove()
        legacyConstructor.remove()
        declaration.methods.filter { it.nameAsString == "instance" }.forEach { method ->
            method.findAll(ObjectCreationExpr::class.java)
                .filter { it.type.nameAsString == "Instance" && it.arguments.size == 1 }
                .forEach { it.arguments.clear() }
        }

        val codecMethod = StaticJavaParser.parseBodyDeclaration(
            """
            @Override
            public Codec<Instance> codec() {
                return Instance.CODEC;
            }
            """.trimIndent()
        ).asMethodDeclaration()
        val nestedIndex = declaration.members.indexOf(instance)
        declaration.members.add(nestedIndex.coerceAtLeast(0), codecMethod)

        instance.members.add(0, StaticJavaParser.parseBodyDeclaration(
            "private static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf(\"player\").forGetter(Instance::player)).apply(instance, Instance::new));"
        ))
        instance.members.add(1, StaticJavaParser.parseBodyDeclaration("private final Optional<ContextAwarePredicate> player;"))
        instance.members.add(2, StaticJavaParser.parseBodyDeclaration(
            "public Instance() { this(Optional.empty()); }"
        ).asConstructorDeclaration())
        instance.members.add(3, StaticJavaParser.parseBodyDeclaration(
            "public Instance(Optional<ContextAwarePredicate> player) { this.player = player; }"
        ).asConstructorDeclaration())
        instance.addMember(StaticJavaParser.parseBodyDeclaration(
            "@Override public Optional<ContextAwarePredicate> player() { return player; }"
        ).asMethodDeclaration())

        cu.addImport("java.util.Optional")
        cu.addImport("com.mojang.serialization.Codec")
        cu.addImport("com.mojang.serialization.codecs.RecordCodecBuilder")
        cu.addImport("net.minecraft.advancements.critereon.EntityPredicate")
        removeImport(cu, "com.google.gson.JsonObject")
        removeImport(cu, "net.minecraft.advancements.critereon.DeserializationContext")
        removeImportIfUnused(cu, "net.minecraft.resources.ResourceLocation")

        return writeChange(
            unit,
            LexicalPreservingPrinter.print(cu),
            dryRun,
            "Migrate stateless criterion trigger child to an exact player-predicate codec",
            "createInstance(JsonObject, DeserializationContext) + inherited predicate constructor",
            "Codec<Instance> + Optional<ContextAwarePredicate>",
            "struct-criterion-trigger-stateless-child"
        )
    }

    private fun nestedInstance(declaration: ClassOrInterfaceDeclaration): ClassOrInterfaceDeclaration? =
        declaration.members.filterIsInstance<ClassOrInterfaceDeclaration>()
            .singleOrNull { it.nameAsString == "Instance" }

    private fun isLegacyInstanceConstructor(constructor: ConstructorDeclaration): Boolean {
        if (constructor.parameters.size != 2 || constructor.body.statements.size != 1) return false
        val invocation = constructor.body.statements[0] as? ExplicitConstructorInvocationStmt ?: return false
        return !invocation.isThis && invocation.arguments.size == 2
    }

    private fun isStatelessChildConstructor(constructor: ConstructorDeclaration): Boolean {
        if (constructor.parameters.size != 1 || constructor.body.statements.size != 1) return false
        if (constructor.parameters.single().typeAsString.substringAfterLast('.') != "ResourceLocation") return false
        val invocation = constructor.body.statements[0] as? ExplicitConstructorInvocationStmt ?: return false
        return !invocation.isThis && invocation.arguments.size == 2 &&
            invocation.arguments[1].toString().endsWith("ContextAwarePredicate.ANY")
    }

    private fun isStatelessCreateInstance(method: MethodDeclaration): Boolean {
        if (method.nameAsString != "createInstance" || method.parameters.size != 2) return false
        val returned = method.body.orElse(null)?.statements?.singleOrNull()?.asReturnStmt()?.expression?.orElse(null)
            as? ObjectCreationExpr ?: return false
        if (returned.type.nameAsString != "Instance" || returned.arguments.size != 1) return false
        val idCall = returned.arguments.single() as? MethodCallExpr ?: return false
        return idCall.scope.isEmpty && idCall.nameAsString == "getId" && idCall.arguments.isEmpty()
    }

    private fun removeImport(cu: CompilationUnit, qualifiedName: String) {
        cu.imports.filter { !it.isStatic && it.nameAsString == qualifiedName }.forEach { it.remove() }
    }

    private fun removeImportIfUnused(cu: CompilationUnit, qualifiedName: String) {
        val simple = qualifiedName.substringAfterLast('.')
        val used = cu.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType::class.java)
            .any { it.nameAsString == simple }
        if (!used) removeImport(cu, qualifiedName)
    }

    private fun writeChange(
        unit: ParsedSource,
        migrated: String,
        dryRun: Boolean,
        description: String,
        before: String,
        after: String,
        ruleId: String
    ): Change? {
        if (migrated == unit.source) return null
        if (!dryRun) unit.file.writeText(migrated)
        return Change(unit.file, 1, description, before, after, Confidence.HIGH, ruleId)
    }
}
