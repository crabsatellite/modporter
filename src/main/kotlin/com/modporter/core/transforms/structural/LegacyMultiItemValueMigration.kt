package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Moves removed MultiItemValue checks to the owning Ingredient's CompoundIngredient identity. */
class LegacyMultiItemValueMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val migratedSources = linkedMapOf<Path, String>()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir).use { stream -> stream.filter { it.extension == "java" }.toList() }.forEach { file ->
            val source = file.readText()
            if (!source.contains("MultiItemValue")) return@forEach
            val cu = parse(parser, file, source)
            if (!hasExactImport(cu, "net.neoforged.neoforge.common.crafting.MultiItemValue")) {
                throw IllegalStateException("MultiItemValue usage has no exact NeoForge owner import in $file")
            }
            listOf(
                "net.minecraft.world.item.crafting.Ingredient",
                "net.minecraft.world.item.crafting.Ingredient.Value"
            ).forEach { owner ->
                if (!hasExactImport(cu, owner)) {
                    throw IllegalStateException("MultiItemValue migration lacks exact owner import $owner in $file")
                }
            }
            LexicalPreservingPrinter.setup(cu)
            val checks = cu.findAll(InstanceOfExpr::class.java).filter { it.typeAsString == "MultiItemValue" }
            if (checks.size != 1) {
                throw IllegalStateException("MultiItemValue migration requires exactly one executable type check in $file")
            }
            migrateCheck(cu, checks.single(), file)
            cu.imports.filter {
                !it.isStatic && !it.isAsterisk &&
                    it.nameAsString == "net.neoforged.neoforge.common.crafting.MultiItemValue"
            }.toList().forEach { it.remove() }
            cu.addImport("net.neoforged.neoforge.common.crafting.CompoundIngredient")
            val migrated = LexicalPreservingPrinter.print(cu)
            if (migrated.contains("MultiItemValue")) {
                throw IllegalStateException("MultiItemValue remains after exact CompoundIngredient migration in $file")
            }
            migratedSources[file] = migrated
            changes += Change(
                file = file,
                line = 1,
                description = "Preserve compound ingredient identity after MultiItemValue removal",
                before = "value instanceof MultiItemValue",
                after = "ingredient.getCustomIngredient() instanceof CompoundIngredient",
                confidence = Confidence.HIGH,
                ruleId = "struct-multi-item-value-compound-ingredient"
            )
        }

        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changes
    }

    private fun migrateCheck(cu: CompilationUnit, check: InstanceOfExpr, file: Path) {
        val value = check.expression as? NameExpr
            ?: throw IllegalStateException("MultiItemValue check must inspect a named Value parameter in $file")
        val ifStatement = check.parentNode.orElse(null) as? IfStmt
            ?: throw IllegalStateException("MultiItemValue check must be the complete if condition in $file")
        if (ifStatement.condition !== check) {
            throw IllegalStateException("MultiItemValue check is combined with unsupported conditions in $file")
        }
        val helper = check.findAncestor(MethodDeclaration::class.java).orElseThrow {
            IllegalStateException("MultiItemValue check is outside a method in $file")
        }
        if (!helper.isPrivate || !helper.isStatic || !helper.body.isPresent) {
            throw IllegalStateException("MultiItemValue helper must be a private static implementation method in $file")
        }
        val valueParameters = helper.parameters.withIndex().filter {
            it.value.nameAsString == value.nameAsString && it.value.typeAsString == "Value"
        }
        if (valueParameters.size != 1) {
            throw IllegalStateException("MultiItemValue check has no unique exact Value parameter in $file")
        }
        val valueParameterIndex = valueParameters.single().index
        val owner = helper.findAncestor(ClassOrInterfaceDeclaration::class.java).orElseThrow {
            IllegalStateException("MultiItemValue helper is outside a class in $file")
        }
        if (owner.methods.count { it.nameAsString == helper.nameAsString } != 1) {
            throw IllegalStateException("MultiItemValue helper name is overloaded in $file")
        }
        val oldArgumentCount = helper.parameters.size
        val calls = owner.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == helper.nameAsString && call.arguments.size == oldArgumentCount &&
                (!call.scope.isPresent || call.scope.get().toString() == owner.nameAsString) &&
                call.findAncestor(MethodDeclaration::class.java).orElse(null)?.parentNode?.orElse(null) === owner
        }
        if (calls.isEmpty()) {
            throw IllegalStateException("MultiItemValue helper has no exact internal calls in $file")
        }

        val flagName = uniqueParameterName(helper, "isCompoundIngredient")
        val arguments = calls.map { call ->
            val ingredient = resolveOwningIngredient(call, call.arguments[valueParameterIndex], file)
            call to compoundCheck(ingredient)
        }
        helper.addParameter("boolean", flagName)
        check.replace(NameExpr(flagName))
        arguments.forEach { (call, argument) -> call.addArgument(argument) }
        cu.addImport("net.neoforged.neoforge.common.crafting.CompoundIngredient")
    }

    private fun resolveOwningIngredient(call: MethodCallExpr, argument: Expression, file: Path): NameExpr {
        val access = argument as? ArrayAccessExpr
            ?: throw IllegalStateException("MultiItemValue helper argument is not an exact Ingredient values element in $file")
        val valuesName = access.name as? NameExpr
            ?: throw IllegalStateException("MultiItemValue helper argument has no named Ingredient values array in $file")
        val caller = call.findAncestor(MethodDeclaration::class.java).orElseThrow {
            IllegalStateException("MultiItemValue helper call is outside a method in $file")
        }
        val arrays = caller.findAll(VariableDeclarator::class.java).filter { variable ->
            variable.nameAsString == valuesName.nameAsString && variable.typeAsString == "Value[]" &&
                variable.initializer.map { initializer ->
                    initializer is FieldAccessExpr && initializer.nameAsString == "values" &&
                        initializer.scope is NameExpr
                }.orElse(false)
        }
        if (arrays.size != 1) {
            throw IllegalStateException("MultiItemValue helper argument has no unique Value[] derived from Ingredient.values in $file")
        }
        val initializer = arrays.single().initializer.get() as FieldAccessExpr
        val ingredientName = (initializer.scope as NameExpr).nameAsString
        val ingredients = caller.parameters.filter {
            it.nameAsString == ingredientName && it.typeAsString == "Ingredient"
        }
        if (ingredients.size != 1) {
            throw IllegalStateException("MultiItemValue values array has no exact Ingredient parameter owner in $file")
        }
        return NameExpr(ingredientName)
    }

    private fun compoundCheck(ingredient: NameExpr): InstanceOfExpr {
        val custom = MethodCallExpr(ingredient, "getCustomIngredient")
        return InstanceOfExpr(custom, StaticJavaParser.parseClassOrInterfaceType("CompoundIngredient"))
    }

    private fun uniqueParameterName(method: MethodDeclaration, base: String): String {
        val used = method.parameters.map { it.nameAsString }.toMutableSet()
        used += method.findAll(NameExpr::class.java).map { it.nameAsString }
        if (base !in used) return base
        var suffix = 2
        while ("$base$suffix" in used) suffix++
        return "$base$suffix"
    }

    private fun hasExactImport(cu: CompilationUnit, owner: String): Boolean =
        cu.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == owner }

    private fun parse(parser: JavaParser, file: Path, source: String): CompilationUnit {
        val result = parser.parse(source)
        return result.result.orElseThrow {
            IllegalStateException("Cannot parse MultiItemValue source $file: ${result.problems.joinToString()}")
        }
    }
}
