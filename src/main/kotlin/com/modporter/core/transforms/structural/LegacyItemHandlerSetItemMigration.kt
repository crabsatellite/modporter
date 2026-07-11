package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Maps legacy Container mutation to the exact IItemHandlerModifiable method through project type flow. */
internal class LegacyItemHandlerSetItemMigration {
    private data class SourceUnit(
        val file: Path,
        val source: String,
        val compilationUnit: CompilationUnit,
        val lineOffsets: IntArray
    )

    private data class ClassInfo(
        val qualifiedName: String,
        val simpleName: String,
        val parents: Set<String>,
        val declaration: ClassOrInterfaceDeclaration
    )

    private data class MethodKey(val owner: String, val name: String, val arity: Int)
    private data class Edit(val start: Int, val endExclusive: Int, val replacement: String)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val sources = Files.walk(sourceRoot)
            .filter { it.extension == "java" }
            .filter { !sourceRoot.relativize(it).toString().replace('\\', '/').startsWith("com/modporter/generated/") }
            .toList()
            .associateWith { it.readText() }
        if (sources.values.none { it.contains(".setItem(") }) return emptyList()

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = linkedMapOf<Path, SourceUnit>()
        fun parse(file: Path): SourceUnit {
            parsed[file]?.let { return it }
            val source = sources.getValue(file)
            val result = parser.parse(source)
            if (!result.isSuccessful) {
                throw IllegalStateException(
                    "Cannot parse $file while migrating item-handler setItem calls: " +
                        result.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            return SourceUnit(file, source, result.result.orElseThrow(), lineOffsets(source))
                .also { parsed[file] = it }
        }

        sources.filterValues { it.contains("IItemHandlerModifiable") }.keys.forEach(::parse)
        val classInfos = linkedMapOf<String, ClassInfo>()
        fun index(unit: SourceUnit) {
            unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { !it.isInterface }
                .forEach { declaration ->
                    val info = ClassInfo(
                        qualifiedName = qualifiedName(unit.compilationUnit, declaration),
                        simpleName = declaration.nameAsString,
                        parents = (declaration.extendedTypes + declaration.implementedTypes)
                            .map { it.nameWithScope }
                            .toSet(),
                        declaration = declaration
                    )
                    classInfos[info.qualifiedName] = info
                }
        }
        parsed.values.forEach(::index)

        val mutableClasses = linkedSetOf<String>()
        classInfos.values.filter { info ->
            info.parents.any { it == "IItemHandlerModifiable" || it.endsWith(".IItemHandlerModifiable") }
        }.forEach { mutableClasses += it.qualifiedName }

        var discovered: Boolean
        do {
            discovered = false
            val mutableSimpleNames = mutableClasses.mapNotNull { classInfos[it]?.simpleName }.toSet() + "ItemStackHandler"
            sources.forEach { (file, source) ->
                if (file in parsed || mutableSimpleNames.none { source.contains("extends $it") }) return@forEach
                val unit = parse(file)
                index(unit)
                discovered = true
            }
            classInfos.values.filter { it.qualifiedName !in mutableClasses }.forEach { info ->
                if (info.parents.any { parent ->
                        parent == "ItemStackHandler" || parent.endsWith(".ItemStackHandler") ||
                            classInfos.values.any { candidate ->
                                candidate.qualifiedName in mutableClasses &&
                                    (parent == candidate.simpleName || parent == candidate.qualifiedName)
                            }
                    }) {
                    mutableClasses += info.qualifiedName
                    discovered = true
                }
            }
        } while (discovered)
        if (mutableClasses.isEmpty()) return emptyList()

        val mutableBySimple = mutableClasses.mapNotNull { classInfos[it] }.groupBy { it.simpleName }
        val mutableMethods = linkedMapOf<MethodKey, MutableList<MethodDeclaration>>()
        parsed.values.forEach { unit ->
            unit.compilationUnit.findAll(MethodDeclaration::class.java).forEach { method ->
                val returnSimple = method.type.asString().substringAfterLast('.')
                if (mutableBySimple[returnSimple]?.size != 1) return@forEach
                val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: return@forEach
                val key = MethodKey(qualifiedName(unit.compilationUnit, owner), method.nameAsString, method.parameters.size)
                mutableMethods.getOrPut(key) { mutableListOf() } += method
            }
        }
        if (mutableMethods.isEmpty()) return emptyList()

        sources.filterValues { it.contains(".setItem(") }.keys.forEach(::parse)
        val classBySimple = classInfos.values.groupBy { it.simpleName }
        val edits = linkedMapOf<SourceUnit, MutableList<Edit>>()
        parsed.values.forEach { unit ->
            unit.compilationUnit.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == "setItem" && it.arguments.size == 2 }
                .forEach { mutation ->
                    val getter = mutation.scope.orElse(null) as? MethodCallExpr ?: return@forEach
                    val receiver = getter.scope.orElse(null) as? NameExpr ?: return@forEach
                    val lambda = mutation.findAncestor(LambdaExpr::class.java).orElse(null) ?: return@forEach
                    if (lambda.parameters.none { it.nameAsString == receiver.nameAsString }) return@forEach
                    val factoryCall = lambda.findAncestor(MethodCallExpr::class.java).orElse(null) ?: return@forEach
                    if (factoryCall.arguments.none { it === lambda }) return@forEach
                    val ownerClass = factoryCall.arguments
                        .filterIsInstance<ClassExpr>()
                        .map { it.type.asString().substringAfterLast('.') }
                        .mapNotNull { simple -> classBySimple[simple]?.singleOrNull() }
                        .singleOrNull()
                        ?: return@forEach
                    val methodKey = MethodKey(ownerClass.qualifiedName, getter.nameAsString, getter.arguments.size)
                    val declarations = mutableMethods[methodKey].orEmpty()
                    if (declarations.size != 1) {
                        if (declarations.isNotEmpty()) {
                            throw IllegalStateException(
                                "Ambiguous mutable item-handler return ${ownerClass.qualifiedName}.${getter.nameAsString}/${getter.arguments.size}"
                            )
                        }
                        return@forEach
                    }
                    edits.getOrPut(unit) { mutableListOf() } += replaceNode(unit, mutation.name, "setStackInSlot")
                }
        }
        if (edits.isEmpty()) return emptyList()

        return edits.map { (unit, fileEdits) ->
            var migrated = unit.source
            fileEdits.distinct().sortedByDescending { it.start }.forEach { edit ->
                migrated = migrated.substring(0, edit.start) + edit.replacement + migrated.substring(edit.endExclusive)
            }
            if (!dryRun) unit.file.writeText(migrated)
            Change(
                file = unit.file,
                line = 1,
                description = "Migrate legacy setItem call through a proven IItemHandlerModifiable return type",
                before = "typed mutable inventory .setItem(slot, stack)",
                after = "typed mutable inventory .setStackInSlot(slot, stack)",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-handler-set-stack-in-slot"
            )
        }
    }

    private fun qualifiedName(cu: CompilationUnit, declaration: ClassOrInterfaceDeclaration): String {
        val names = mutableListOf<String>()
        var current: Node? = declaration
        while (current != null) {
            if (current is ClassOrInterfaceDeclaration) names.add(0, current.nameAsString)
            current = current.parentNode.orElse(null)
        }
        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        return (listOf(packageName).filter { it.isNotBlank() } + names).joinToString(".")
    }

    private fun replaceNode(unit: SourceUnit, node: Node, replacement: String): Edit {
        val range = node.range.orElseThrow()
        return Edit(offset(unit, range.begin), offset(unit, range.end) + 1, replacement)
    }

    private fun offset(unit: SourceUnit, position: Position): Int =
        unit.lineOffsets[position.line - 1] + position.column - 1

    private fun lineOffsets(source: String): IntArray {
        val result = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') result += index + 1 }
        return result.toIntArray()
    }
}
