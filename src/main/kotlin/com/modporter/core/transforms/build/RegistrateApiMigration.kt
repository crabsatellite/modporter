package com.modporter.core.transforms.build

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.ast.type.WildcardType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class RegistrateApiMigration {
    private data class ParsedJava(
        val file: Path,
        val source: String,
        val unit: CompilationUnit,
        val lineStarts: IntArray
    )

    private data class Replacement(val start: Int, val endExclusive: Int, val text: String)

    private val registryEntryOwner = "com.tterrag.registrate.util.entry.RegistryEntry"
    private val itemProviderEntryOwner = "com.tterrag.registrate.util.entry.ItemProviderEntry"
    private val removedFunctionalInterfaces = mapOf(
        "net.neoforged.neoforge.common.util.NonNullSupplier" to "java.util.function.Supplier",
        "net.neoforged.neoforge.common.util.NonNullPredicate" to "java.util.function.Predicate",
        "net.neoforged.neoforge.common.util.NonNullConsumer" to "java.util.function.Consumer"
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = Files.walk(srcDir).use { files ->
            files.iterator().asSequence()
                .filter { it.extension == "java" }
                .mapNotNull { file -> parseRelevantJava(parser, file) }
                .toList()
        }
        if (parsed.isEmpty()) return emptyList()

        val methodBasesByOwnerAndName = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        val methodBasesByName = mutableMapOf<String, MutableSet<String>>()
        parsed.forEach { java ->
            if (!java.hasImport(registryEntryOwner)) return@forEach
            java.unit.findAll(MethodDeclaration::class.java).forEach { method ->
                val base = inferRegistryBaseFromMethod(method) ?: return@forEach
                val owner = ownerKey(method, java.unit)
                methodBasesByOwnerAndName.getOrPut(owner to method.nameAsString, ::linkedSetOf).add(base)
                methodBasesByName.getOrPut(method.nameAsString, ::linkedSetOf).add(base)
            }
        }

        val changes = mutableListOf<Change>()
        parsed.forEach { java ->
            val replacements = mutableListOf<Replacement>()
            var migratedRegistryEntries = 0
            var migratedItemProviderEntries = 0
            var migratedFunctionalInterfaces = 0

            val removedImports = removedFunctionalInterfaces.filterKeys { owner -> java.hasImport(owner) }
            removedImports.forEach { (oldOwner, newOwner) ->
                val oldSimpleName = oldOwner.substringAfterLast('.')
                val newSimpleName = newOwner.substringAfterLast('.')
                java.unit.imports
                    .filter { !it.isStatic && !it.isAsterisk && it.nameAsString == oldOwner }
                    .forEach { importDeclaration ->
                        replacements += replacementFor(java, importDeclaration, "import $newOwner;")
                    }
                java.unit.findAll(ClassOrInterfaceType::class.java)
                    .filter { it.nameAsString == oldSimpleName }
                    .forEach { type ->
                        replacements += replacementFor(java, type, type.toString().replaceFirst(oldSimpleName, newSimpleName))
                        migratedFunctionalInterfaces++
                    }
            }

            if (java.hasImport(registryEntryOwner)) {
                java.unit.findAll(ClassOrInterfaceType::class.java)
                    .filter { it.nameAsString == "RegistryEntry" }
                    .forEach { type ->
                        val arguments = type.typeArguments.orElse(null) ?: return@forEach
                        if (arguments.size != 1) return@forEach
                        val argument = arguments.single()
                        val base = inferBaseFromTypeArgument(argument, type)
                            ?: inferBaseFromInitializer(type, java.unit, methodBasesByOwnerAndName, methodBasesByName)
                            ?: inferBaseFromEnhancedFor(type)
                            ?: error(
                                "Registrate RegistryEntry migration requires an explicit registry base for " +
                                    "${type} in ${java.file}"
                            )
                        val original = java.text(type)
                        val openAngle = original.indexOf('<')
                        if (openAngle < 0) error("Malformed RegistryEntry type '$original' in ${java.file}")
                        val replacement = original.substring(0, openAngle + 1) + base + ", " +
                            original.substring(openAngle + 1)
                        replacements += replacementFor(java, type, replacement)
                        migratedRegistryEntries++
                    }
            }

            if (java.hasImport(itemProviderEntryOwner)) {
                java.unit.findAll(ClassOrInterfaceType::class.java)
                    .filter { it.nameAsString == "ItemProviderEntry" }
                    .forEach { type ->
                        val arguments = type.typeArguments.orElse(null) ?: return@forEach
                        if (arguments.size != 1) return@forEach
                        val argument = arguments.single()
                        if (argument is WildcardType && argument.superType.isPresent) {
                            error("Registrate ItemProviderEntry migration cannot preserve a super wildcard in ${java.file}: $type")
                        }
                        val original = java.text(type)
                        val openAngle = original.indexOf('<')
                        if (openAngle < 0) error("Malformed ItemProviderEntry type '$original' in ${java.file}")
                        val argumentSource = original.substring(openAngle + 1, original.lastIndexOf('>')).trim()
                        val replacement = original.substring(0, openAngle + 1) + argumentSource + ", " +
                            argumentSource + ">"
                        replacements += replacementFor(java, type, replacement)
                        migratedItemProviderEntries++
                    }
            }

            if (replacements.isEmpty()) return@forEach
            val migrated = applyReplacements(java.source, replacements)
            if (!dryRun) java.file.writeText(migrated)
            if (migratedRegistryEntries > 0) {
                changes += Change(
                    file = java.file,
                    line = 1,
                    description = "Migrate Registrate RegistryEntry to explicit registry-base/value generic parameters",
                    before = "RegistryEntry<T>",
                    after = "RegistryEntry<R, T>",
                    confidence = Confidence.HIGH,
                    ruleId = "build-registrate-registryentry-generics"
                )
            }
            if (migratedItemProviderEntries > 0) {
                changes += Change(
                    file = java.file,
                    line = 1,
                    description = "Migrate Registrate ItemProviderEntry to explicit provider/value generic parameters",
                    before = "ItemProviderEntry<T>",
                    after = "ItemProviderEntry<R, T>",
                    confidence = Confidence.HIGH,
                    ruleId = "build-registrate-itemproviderentry-generics"
                )
            }
            if (migratedFunctionalInterfaces > 0) {
                changes += Change(
                    file = java.file,
                    line = 1,
                    description = "Migrate removed NeoForge non-null functional interfaces to JDK functional interfaces",
                    before = "NonNullSupplier/NonNullPredicate/NonNullConsumer",
                    after = "Supplier/Predicate/Consumer",
                    confidence = Confidence.HIGH,
                    ruleId = "build-neoforge-functional-interfaces"
                )
            }
        }
        return changes
    }

    private fun parseRelevantJava(parser: JavaParser, file: Path): ParsedJava? {
        val source = file.readText()
        val relevant = source.contains(registryEntryOwner) ||
            source.contains(itemProviderEntryOwner) ||
            removedFunctionalInterfaces.keys.any(source::contains)
        if (!relevant) return null
        val result = parser.parse(source)
        if (!result.isSuccessful) {
            error("Cannot parse relevant Java source $file: ${result.problems.joinToString("; ") { it.message }}")
        }
        val unit = result.result.orElseThrow()
        return ParsedJava(file, source, unit, lineStarts(source))
    }

    private fun ParsedJava.hasImport(owner: String): Boolean =
        unit.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == owner }

    private fun Type.asRegistryEntryType(): ClassOrInterfaceType? =
        (this as? ClassOrInterfaceType)?.takeIf { it.nameAsString == "RegistryEntry" }

    private fun inferRegistryBaseFromMethod(method: MethodDeclaration): String? {
        val returnType = method.type as? ClassOrInterfaceType ?: return null
        if (returnType.nameAsString == "RegistryEntry") {
            val argument = returnType.typeArguments.orElse(null)?.singleOrNull() ?: return null
            return inferBaseFromTypeArgument(argument, returnType)
        }
        if (!returnType.nameAsString.endsWith("Builder")) return null
        val arguments = returnType.typeArguments.orElse(null) ?: return null
        if (arguments.size < 2) return null
        val valueType = arguments[1] as? ClassOrInterfaceType ?: return null
        val declaredBase = resolveTypeVariableBound(method, valueType.nameAsString) ?: return null
        return arguments[0].toString().takeIf { it == declaredBase }
    }

    private fun inferBaseFromTypeArgument(argument: Type, context: Node): String? = when (argument) {
        is WildcardType -> when {
            argument.extendedType.isPresent -> argument.extendedType.get().toString()
            !argument.superType.isPresent -> "?"
            else -> null
        }
        is ClassOrInterfaceType -> resolveTypeVariableBound(context, argument.nameAsString)
        else -> null
    }

    private fun resolveTypeVariableBound(context: Node, typeVariableName: String): String? {
        var current: Node? = context
        while (current != null) {
            val parameters = when (current) {
                is MethodDeclaration -> current.typeParameters
                is ConstructorDeclaration -> current.typeParameters
                is ClassOrInterfaceDeclaration -> current.typeParameters
                is RecordDeclaration -> current.typeParameters
                else -> null
            }
            val parameter = parameters?.singleOrNull { it.nameAsString == typeVariableName }
            if (parameter != null) return singleTypeBound(parameter)
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun singleTypeBound(parameter: TypeParameter): String? =
        parameter.typeBound.singleOrNull()?.toString()

    private fun inferBaseFromInitializer(
        type: ClassOrInterfaceType,
        unit: CompilationUnit,
        methodBasesByOwnerAndName: Map<Pair<String, String>, Set<String>>,
        methodBasesByName: Map<String, Set<String>>
    ): String? {
        val variable = type.findAncestor(VariableDeclarator::class.java).orElse(null) ?: return null
        val initializer = variable.initializer.orElse(null) ?: return null
        val localOwner = ownerKey(type, unit)
        val candidates = linkedSetOf<String>()
        initializer.findAll(MethodCallExpr::class.java).forEach { call ->
            methodBasesByOwnerAndName[localOwner to call.nameAsString]
                ?.singleOrNull()
                ?.let(candidates::add)
            methodBasesByName[call.nameAsString]
                ?.singleOrNull()
                ?.let(candidates::add)
        }
        return candidates.singleOrNull()
    }

    private fun inferBaseFromEnhancedFor(type: ClassOrInterfaceType): String? {
        val forEach = type.findAncestor(ForEachStmt::class.java).orElse(null) ?: return null
        val registryName = Regex("""\bRegistries\.([A-Z][A-Z0-9_]*)\b""")
            .find(forEach.iterable.toString())
            ?.groupValues
            ?.get(1)
            ?: return null
        return when (registryName) {
            "BLOCK" -> "Block"
            "ITEM" -> "Item"
            else -> null
        }
    }

    private fun ownerKey(node: Node, unit: CompilationUnit): String {
        val names = generateSequence(node.parentNode.orElse(null)) { it.parentNode.orElse(null) }
            .filterIsInstance<ClassOrInterfaceDeclaration>()
            .map { it.nameAsString }
            .toList()
            .asReversed()
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        return (listOf(packageName).filter(String::isNotBlank) + names).joinToString(".")
    }

    private fun replacementFor(java: ParsedJava, node: Node, text: String): Replacement {
        val range = node.range.orElseThrow { IllegalStateException("Missing source range for $node in ${java.file}") }
        return Replacement(
            start = java.offset(range.begin),
            endExclusive = java.offset(range.end) + 1,
            text = text
        )
    }

    private fun ParsedJava.text(node: Node): String {
        val replacement = replacementFor(this, node, "")
        return source.substring(replacement.start, replacement.endExclusive)
    }

    private fun ParsedJava.offset(position: Position): Int =
        lineStarts[position.line - 1] + position.column - 1

    private fun lineStarts(source: String): IntArray {
        val starts = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun applyReplacements(source: String, replacements: List<Replacement>): String {
        val ordered = replacements.distinct().sortedByDescending(Replacement::start)
        ordered.zipWithNext().forEach { (later, earlier) ->
            if (earlier.endExclusive > later.start) {
                error("Overlapping Registrate API replacements at ${earlier.start} and ${later.start}")
            }
        }
        var result = source
        ordered.forEach { replacement ->
            result = result.replaceRange(replacement.start, replacement.endExclusive, replacement.text)
        }
        return result
    }
}
