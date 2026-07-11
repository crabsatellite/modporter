package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Restores the removed mutable RecipeWrapper surface through its proven modifiable backing handler. */
internal class LegacyMutableRecipeWrapperMigration {
    private data class SourceUnit(
        val file: Path,
        val source: String,
        val compilationUnit: CompilationUnit,
        val lineOffsets: IntArray
    )

    private data class Edit(val start: Int, val endExclusive: Int, val replacement: String)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val candidates = Files.walk(sourceRoot)
            .filter { it.extension == "java" }
            .filter { !sourceRoot.relativize(it).toString().replace('\\', '/').startsWith("com/modporter/generated/") }
            .filter { file ->
                val source = file.readText()
                source.contains("RecipeWrapper") || source.contains(".setItem(")
            }
            .toList()
        if (candidates.isEmpty()) return emptyList()

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val units = candidates.map { file ->
            val source = file.readText()
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful) {
                throw IllegalStateException(
                    "Cannot parse $file while migrating mutable RecipeWrapper usage: " +
                        parsed.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            SourceUnit(file, source, parsed.result.orElseThrow(), lineOffsets(source))
        }

        val directVariables = linkedMapOf<SourceUnit, MutableList<VariableDeclarator>>()
        val mutableSubclasses = linkedSetOf<String>()
        units.forEach { unit ->
            unit.compilationUnit.findAll(VariableDeclarator::class.java)
                .filter { isRecipeWrapperType(unit.compilationUnit, it.type) }
                .forEach { variable ->
                    val creation = variable.initializer.orElse(null) as? ObjectCreationExpr
                    if (creation == null) {
                        if (hasSetItemCallForVariable(unit.compilationUnit, variable.nameAsString)) {
                            throw IllegalStateException(
                                "RecipeWrapper ${variable.nameAsString} in ${unit.file} calls setItem but its backing handler is not proven modifiable"
                            )
                        }
                        return@forEach
                    }
                    if (!isRecipeWrapperType(unit.compilationUnit, creation.type)) return@forEach
                    if (creation.arguments.size != 1 || !isProvenModifiableHandlerCreation(creation.arguments.single())) {
                        if (hasSetItemCallForVariable(unit.compilationUnit, variable.nameAsString)) {
                            throw IllegalStateException(
                                "RecipeWrapper ${variable.nameAsString} in ${unit.file} calls setItem but its backing handler is not proven modifiable"
                            )
                        }
                        return@forEach
                    }
                    directVariables.getOrPut(unit) { mutableListOf() } += variable
                }

            unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { declaration -> declaration.extendedTypes.any { isRecipeWrapperType(unit.compilationUnit, it) } }
                .forEach { declaration ->
                    val hasExternalSetItemUse = units.any { candidate ->
                        candidate.compilationUnit.findAll(VariableDeclarator::class.java).any {
                            normalizeType(it.type.asString()) == declaration.nameAsString &&
                                hasSetItemCallForVariable(candidate.compilationUnit, it.nameAsString)
                        }
                    }
                    if (!hasExternalSetItemUse) return@forEach
                    if (!constructorsHaveProvenModifiableBacking(declaration)) {
                        throw IllegalStateException(
                            "RecipeWrapper subclass ${declaration.nameAsString} is mutated through setItem but its super backing is not proven modifiable"
                        )
                    }
                    mutableSubclasses += declaration.nameAsString
                }
        }

        val editsByUnit = linkedMapOf<SourceUnit, MutableList<Edit>>()
        directVariables.forEach { (unit, variables) ->
            variables.forEach { variable ->
                val creation = variable.initializer.orElseThrow() as ObjectCreationExpr
                editsByUnit.getOrPut(unit) { mutableListOf() } += replaceNode(unit, variable.type, GENERATED_TYPE)
                editsByUnit.getValue(unit) += replaceNode(unit, creation.type, GENERATED_TYPE)
            }
        }
        units.forEach { unit ->
            unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { it.nameAsString in mutableSubclasses }
                .forEach { declaration ->
                    declaration.extendedTypes
                        .filter { isRecipeWrapperType(unit.compilationUnit, it) }
                        .forEach { editsByUnit.getOrPut(unit) { mutableListOf() } += replaceNode(unit, it, GENERATED_TYPE) }
                    declaration.findAll(MethodCallExpr::class.java)
                        .filter { call ->
                            call.nameAsString == "setStackInSlot" &&
                                call.arguments.size == 2 &&
                                (call.scope.orElse(null) as? NameExpr)?.nameAsString == "inv" &&
                                call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) === declaration
                        }
                        .forEach { call ->
                            editsByUnit.getOrPut(unit) { mutableListOf() } += replaceNode(unit, call.scope.get(), "this")
                            editsByUnit.getValue(unit) += replaceNode(unit, call.name, "setItem")
                        }
                }

        }

        if (editsByUnit.isEmpty()) return emptyList()
        val changes = mutableListOf<Change>()
        editsByUnit.forEach { (unit, rawEdits) ->
            val edits = rawEdits.distinct()
                .sortedByDescending { it.start }
            require(edits.zipWithNext().none { (later, earlier) -> earlier.endExclusive > later.start }) {
                "Overlapping mutable RecipeWrapper edits in ${unit.file}"
            }
            var migrated = unit.source
            edits.forEach { edit ->
                migrated = migrated.substring(0, edit.start) + edit.replacement + migrated.substring(edit.endExclusive)
            }
            // The generated adapter intentionally retains the legacy method name and delegates its behavior.
            if (!dryRun) unit.file.writeText(migrated)
            changes += Change(
                file = unit.file,
                line = 1,
                description = "Migrate mutable RecipeWrapper usage to a modifiable-handler-backed adapter",
                before = "RecipeWrapper.setItem on a proven ItemStackHandler backing",
                after = "MutableRecipeWrapper.setItem delegating to IItemHandlerModifiable.setStackInSlot",
                confidence = Confidence.HIGH,
                ruleId = "struct-mutable-recipe-wrapper"
            )
        }

        val generatedFile = sourceRoot.resolve("com/modporter/generated/compat/MutableRecipeWrapper.java")
        if (!dryRun) {
            generatedFile.parent.createDirectories()
            generatedFile.writeText(GENERATED_SOURCE)
        }
        changes += Change(
            file = generatedFile,
            line = 1,
            description = "Generate behavior-preserving mutable RecipeWrapper adapter",
            before = "removed RecipeWrapper mutation surface",
            after = "typed IItemHandlerModifiable delegation",
            confidence = Confidence.HIGH,
            ruleId = "struct-mutable-recipe-wrapper-adapter"
        )
        return changes
    }

    private fun constructorsHaveProvenModifiableBacking(declaration: ClassOrInterfaceDeclaration): Boolean {
        if (declaration.constructors.isEmpty()) return false
        return declaration.constructors.all { constructor ->
            constructor.body.statements
                .filterIsInstance<ExplicitConstructorInvocationStmt>()
                .firstOrNull { !it.isThis }
                ?.arguments
                ?.singleOrNull()
                ?.let(::isProvenModifiableHandlerCreation) == true
        }
    }

    private fun isProvenModifiableHandlerCreation(node: Node): Boolean {
        val creation = node as? ObjectCreationExpr ?: return false
        return creation.type.nameAsString == "ItemStackHandler"
    }

    private fun hasSetItemCallForVariable(cu: CompilationUnit, name: String): Boolean =
        cu.findAll(MethodCallExpr::class.java).any {
            it.nameAsString == "setItem" && (it.scope.orElse(null) as? NameExpr)?.nameAsString == name
        }

    private fun isRecipeWrapperType(cu: CompilationUnit, type: ClassOrInterfaceType): Boolean {
        val normalized = normalizeType(type.asString())
        if (normalized == "net.neoforged.neoforge.items.wrapper.RecipeWrapper") return true
        if (normalized != "RecipeWrapper") return false
        return cu.imports.any {
            !it.isStatic && it.nameAsString == "net.neoforged.neoforge.items.wrapper.RecipeWrapper"
        }
    }

    private fun isRecipeWrapperType(cu: CompilationUnit, type: com.github.javaparser.ast.type.Type): Boolean =
        type is ClassOrInterfaceType && isRecipeWrapperType(cu, type)

    private fun replaceNode(unit: SourceUnit, node: Node, replacement: String): Edit {
        val range = node.range.orElseThrow()
        val start = offset(unit, range.begin)
        return Edit(start, offset(unit, range.end) + 1, replacement)
    }

    private fun normalizeType(type: String): String = type.replace(Regex("\\s+"), "")

    private fun offset(unit: SourceUnit, position: Position): Int =
        unit.lineOffsets[position.line - 1] + position.column - 1

    private fun lineOffsets(source: String): IntArray {
        val result = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') result += index + 1 }
        return result.toIntArray()
    }

    private companion object {
        const val GENERATED_TYPE = "com.modporter.generated.compat.MutableRecipeWrapper"
        val GENERATED_SOURCE = """
            package com.modporter.generated.compat;

            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.items.IItemHandlerModifiable;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

            public class MutableRecipeWrapper extends RecipeWrapper {
                private final IItemHandlerModifiable mutableHandler;

                public MutableRecipeWrapper(IItemHandlerModifiable handler) {
                    super(handler);
                    this.mutableHandler = handler;
                }

                public void setItem(int slot, ItemStack stack) {
                    mutableHandler.setStackInSlot(slot, stack);
                }
            }
        """.trimIndent() + "\n"
    }
}
