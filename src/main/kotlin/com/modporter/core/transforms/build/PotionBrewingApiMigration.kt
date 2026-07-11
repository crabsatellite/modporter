package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

internal class PotionBrewingApiMigration {
    private data class JavaType(
        val file: Path,
        val packageName: String,
        val simpleName: String,
        val fqn: String,
        val imports: Map<String, String>,
        val superName: String?,
        val source: String
    )

    private data class CacheShape(
        val listType: String,
        val listField: String,
        val createMethod: String,
        val mapType: String,
        val mapField: String,
        val sortMethod: String
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val types = Files.walk(srcDir).filter { it.extension == "java" }.toList().mapNotNull(::parseJavaType)
        val byFqn = types.associateBy(JavaType::fqn)
        val candidates = types.filter { type ->
            val executable = maskJavaCommentsAndLiterals(type.source)
            executable.contains("PotionBrewing.ALLOWED_CONTAINER") &&
                executable.contains("PotionBrewing.POTION_MIXES") &&
                executable.contains("PotionBrewing.CONTAINER_MIXES")
        }
        if (candidates.isEmpty()) return emptyList()
        require(candidates.size == 1) {
            "Expected one source owner for the legacy static PotionBrewing tables; found ${candidates.size}"
        }
        val owner = candidates.single()
        val shape = parseCacheShape(owner.source)
        val migratedOwner = rewriteOwner(owner.source, shape)
        val changes = mutableListOf<Change>()
        changes += Change(
            file = owner.file,
            line = 1,
            description = "Migrate static PotionBrewing tables to the level-scoped 1.21 brewing state",
            before = "static PotionBrewing ALLOWED_CONTAINER/POTION_MIXES/CONTAINER_MIXES",
            after = "Level.potionBrewing instance fields and public recipe registry",
            confidence = Confidence.HIGH,
            ruleId = "build-potion-brewing-level-state"
        )
        if (!dryRun) owner.file.writeText(migratedOwner)

        for (type in types) {
            if (type.file == owner.file) continue
            val original = type.source
            val executable = maskJavaCommentsAndLiterals(original)
            val allToken = "${owner.simpleName}.${shape.listField}"
            val byItemToken = "${owner.simpleName}.${shape.mapField}"
            if (!executable.contains(allToken) && !executable.contains(byItemToken)) continue
            val levelExpression = deriveLevelExpression(type, byFqn)
            var modified = replaceExecutableLiteral(
                original,
                allToken,
                "${owner.simpleName}.${shape.createMethod}($levelExpression)"
            )
            modified = replaceExecutableLiteral(
                modified,
                byItemToken,
                "${owner.simpleName}.${shape.sortMethod}($levelExpression)"
            )
            require(modified != original) { "Failed to rewrite PotionBrewing cache call site in ${type.fqn}" }
            changes += Change(
                file = type.file,
                line = 1,
                description = "Pass a statically proven Level to level-scoped PotionBrewing recipe caches",
                before = "$allToken/$byItemToken",
                after = "${shape.createMethod}/${shape.sortMethod}($levelExpression)",
                confidence = Confidence.HIGH,
                ruleId = "build-potion-brewing-level-callsite"
            )
            if (!dryRun) type.file.writeText(modified)
        }

        val resultingSources = types.associateWith { type ->
            when {
                type.file == owner.file -> migratedOwner
                dryRun -> {
                    val executable = maskJavaCommentsAndLiterals(type.source)
                    if (executable.contains("${owner.simpleName}.${shape.listField}") ||
                        executable.contains("${owner.simpleName}.${shape.mapField}")) {
                        val level = deriveLevelExpression(type, byFqn)
                        replaceExecutableLiteral(
                            replaceExecutableLiteral(
                                type.source,
                                "${owner.simpleName}.${shape.listField}",
                                "${owner.simpleName}.${shape.createMethod}($level)"
                            ),
                            "${owner.simpleName}.${shape.mapField}",
                            "${owner.simpleName}.${shape.sortMethod}($level)"
                        )
                    } else type.source
                }
                else -> type.file.readText()
            }
        }
        val forbiddenTokens = listOf(
            "PotionBrewing.ALLOWED_CONTAINER",
            "PotionBrewing.POTION_MIXES",
            "PotionBrewing.CONTAINER_MIXES",
            "${owner.simpleName}.${shape.listField}",
            "${owner.simpleName}.${shape.mapField}"
        )
        val remaining = resultingSources.entries.firstOrNull { (_, source) ->
            val executable = maskJavaCommentsAndLiterals(source)
            forbiddenTokens.any(executable::contains)
        }
        require(remaining == null) {
            "Legacy static PotionBrewing flow remains after migration in ${remaining?.key?.fqn}"
        }

        changes += rewriteAccessTransformer(projectDir, dryRun)
        return changes
    }

    private fun parseCacheShape(source: String): CacheShape {
        val executable = maskJavaCommentsAndLiterals(source)
        val list = Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+(.+?)\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\(\s*\)\s*;"""
        ).findAll(executable).firstOrNull { it.groupValues[1].trim().startsWith("List<") }
            ?: error("Cannot resolve the eager PotionBrewing recipe list cache")
        val listField = list.groupValues[2]
        val map = Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+(.+?)\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\(\s*${Regex.escape(listField)}\s*\)\s*;"""
        ).find(executable) ?: error("Cannot resolve the eager PotionBrewing item map cache")
        return CacheShape(
            list.groupValues[1].trim(),
            listField,
            list.groupValues[3],
            map.groupValues[1].trim(),
            map.groupValues[2],
            map.groupValues[3]
        )
    }

    private fun rewriteOwner(source: String, shape: CacheShape): String {
        var result = source
        result = Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+${Regex.escape(shape.listType)}\s+${Regex.escape(shape.listField)}\s*=\s*${Regex.escape(shape.createMethod)}\(\s*\)\s*;"""
        ).replace(result, "\tprivate static ${shape.listType} ${shape.listField};")
        result = Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+${Regex.escape(shape.mapType)}\s+${Regex.escape(shape.mapField)}\s*=\s*${Regex.escape(shape.sortMethod)}\(\s*${Regex.escape(shape.listField)}\s*\)\s*;"""
        ).replace(result, "\tprivate static ${shape.mapType} ${shape.mapField};")

        val executable = maskJavaCommentsAndLiterals(result)
        val method = Regex(
            """(?m)^([ \t]*)private\s+static\s+${Regex.escape(shape.listType)}\s+${Regex.escape(shape.createMethod)}\s*\(\s*\)\s*\{"""
        ).find(executable) ?: error("Cannot resolve the legacy PotionBrewing recipe factory")
        val openBrace = executable.indexOf('{', method.range.first)
        val closeBrace = findMatching(executable, openBrace, '{', '}')
        require(closeBrace > openBrace) { "Unbalanced PotionBrewing recipe factory" }
        val indent = method.groupValues[1]
        val originalMethod = result.substring(method.range.first, closeBrace + 1)
        var migratedMethod = originalMethod.replaceRange(
            method.range.first - method.range.first until method.range.last - method.range.first + 1,
            "${indent}private static ${shape.listType} ${shape.createMethod}Impl(Level level) {"
        )
        val bodyStart = migratedMethod.indexOf('{') + 1
        migratedMethod = migratedMethod.replaceRange(
            bodyStart until bodyStart,
            "\n${indent}\tPotionBrewing potionBrewing = level.potionBrewing();"
        )
        migratedMethod = replaceExecutableRegex(
            migratedMethod,
            Regex("""PotionBrewing\.ALLOWED_CONTAINER\.test\(([^()]+)\)""")
        ) { match -> "potionBrewing.isContainer(${match.groupValues[1]})" }
        migratedMethod = replaceExecutableLiteral(migratedMethod, "PotionBrewing.POTION_MIXES", "potionBrewing.potionMixes")
        migratedMethod = replaceExecutableLiteral(migratedMethod, "PotionBrewing.CONTAINER_MIXES", "potionBrewing.containerMixes")
        migratedMethod = replaceExecutableLiteral(migratedMethod, "BrewingRecipeRegistry.getRecipes()", "potionBrewing.getRecipes()")
        val mixVariables = Regex("""\bPotionBrewing\.Mix\s*<[^;={}]+>\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(maskJavaCommentsAndLiterals(migratedMethod))
            .map { it.groupValues[1] }
            .toSet()
        for (variable in mixVariables) {
            for (member in listOf("from", "to", "ingredient")) {
                migratedMethod = replaceExecutableRegex(
                    migratedMethod,
                    Regex("""\b${Regex.escape(variable)}\.${member}\b(?!\s*\()""")
                ) { "$variable.$member()" }
            }
        }
        val wrappers = buildString {
            appendLine("${indent}public static ${shape.listType} ${shape.createMethod}(Level level) {")
            appendLine("${indent}\tif (${shape.listField} == null)")
            appendLine("${indent}\t\t${shape.listField} = ${shape.createMethod}Impl(level);")
            appendLine("${indent}\treturn ${shape.listField};")
            appendLine("${indent}}")
            appendLine()
            appendLine("${indent}public static ${shape.mapType} ${shape.sortMethod}(Level level) {")
            appendLine("${indent}\tif (${shape.mapField} == null)")
            appendLine("${indent}\t\t${shape.mapField} = ${shape.sortMethod}(${shape.createMethod}(level));")
            appendLine("${indent}\treturn ${shape.mapField};")
            appendLine("${indent}}")
            appendLine()
        }
        result = result.replaceRange(method.range.first..closeBrace, wrappers + migratedMethod)
        result = ensureImport(result, "net.minecraft.world.level.Level")
        result = removeImportIfUnused(result, "net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry")
        result = removeImportIfUnused(result, "net.minecraftforge.common.brewing.BrewingRecipeRegistry")
        return result
    }

    private fun deriveLevelExpression(type: JavaType, byFqn: Map<String, JavaType>): String {
        val candidates = linkedSetOf<String>()
        if (isBlockEntitySubclass(type, byFqn, mutableSetOf())) candidates += "level"
        val executable = maskJavaCommentsAndLiterals(type.source)
        if (type.imports["Minecraft"] == "net.minecraft.client.Minecraft" &&
            executable.contains("Minecraft.getInstance()")) {
            candidates += "Minecraft.getInstance().level"
        }
        require(candidates.size == 1) {
            "PotionBrewing call site ${type.fqn} needs exactly one structurally proven Level provider; found $candidates"
        }
        return candidates.single()
    }

    private fun isBlockEntitySubclass(
        type: JavaType,
        byFqn: Map<String, JavaType>,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(type.fqn)) return false
        val superName = type.superName ?: return false
        val superFqn = resolveType(type, superName, byFqn) ?: return false
        if (superFqn == "net.minecraft.world.level.block.entity.BlockEntity") return true
        return byFqn[superFqn]?.let { isBlockEntitySubclass(it, byFqn, visited) } ?: false
    }

    private fun parseJavaType(file: Path): JavaType? {
        val source = file.readText()
        val executable = maskJavaCommentsAndLiterals(source)
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)\s*;""")
            .find(executable)?.groupValues?.get(1) ?: return null
        val declaration = Regex(
            """\bpublic\s+(?:(?:abstract|final)\s+)*class\s+([A-Za-z_$][\w$]*)(?:\s+extends\s+([A-Za-z_$][\w$.]*))?"""
        ).find(executable) ?: return null
        val simpleName = declaration.groupValues[1]
        val imports = Regex("""(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$.]*)\s*;""")
            .findAll(executable)
            .associate { it.groupValues[1].substringAfterLast('.') to it.groupValues[1] }
        return JavaType(
            file,
            packageName,
            simpleName,
            "$packageName.$simpleName",
            imports,
            declaration.groupValues[2].ifBlank { null },
            source
        )
    }

    private fun resolveType(type: JavaType, reference: String, byFqn: Map<String, JavaType>): String? {
        if ('.' in reference) return reference
        type.imports[reference]?.let { return it }
        val samePackage = "${type.packageName}.$reference"
        return samePackage.takeIf(byFqn::containsKey)
    }

    private fun rewriteAccessTransformer(projectDir: Path, dryRun: Boolean): List<Change> {
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        if (!atFile.exists()) return emptyList()
        val original = atFile.readText()
        val obsolete = setOf(
            "public net.minecraft.world.item.alchemy.PotionBrewing f_43494_",
            "public net.minecraft.world.item.alchemy.PotionBrewing f_43495_",
            "public net.minecraft.world.item.alchemy.PotionBrewing f_43497_",
            "public net.minecraft.world.item.alchemy.PotionBrewing POTION_MIXES",
            "public net.minecraft.world.item.alchemy.PotionBrewing CONTAINER_MIXES",
            "public net.minecraft.world.item.alchemy.PotionBrewing ALLOWED_CONTAINER"
        )
        val output = original.lines().filterNot { line ->
            line.substringBefore('#').trim().replace(Regex("""\s+"""), " ") in obsolete
        }.toMutableList()
        val required = listOf(
            "public net.minecraft.world.item.alchemy.PotionBrewing potionMixes",
            "public net.minecraft.world.item.alchemy.PotionBrewing containerMixes",
            "public net.minecraft.world.item.alchemy.PotionBrewing isContainer(Lnet/minecraft/world/item/ItemStack;)Z"
        )
        val normalized = output.map { it.substringBefore('#').trim().replace(Regex("""\s+"""), " ") }.toMutableSet()
        required.forEach { entry -> if (normalized.add(entry)) output += entry }
        val modified = output.joinToString(System.lineSeparator()).trimEnd() + System.lineSeparator()
        if (modified == original) return emptyList()
        if (!dryRun) atFile.writeText(modified)
        return listOf(Change(
            file = atFile,
            line = 1,
            description = "Retarget legacy static PotionBrewing AT entries to the 1.21 level-scoped instance state",
            before = "POTION_MIXES/CONTAINER_MIXES/ALLOWED_CONTAINER",
            after = "potionMixes/containerMixes/isContainer",
            confidence = Confidence.HIGH,
            ruleId = "build-potion-brewing-instance-at"
        ))
    }

    private fun ensureImport(source: String, importName: String): String {
        if (Regex("""(?m)^\s*import\s+${Regex.escape(importName)}\s*;""").containsMatchIn(source)) return source
        val packageLine = Regex("""(?m)^\s*package\s+[^;]+;""").find(source) ?: return source
        return source.substring(0, packageLine.range.last + 1) +
            System.lineSeparator() + System.lineSeparator() + "import $importName;" +
            source.substring(packageLine.range.last + 1)
    }

    private fun removeImportIfUnused(source: String, importName: String): String {
        val pattern = Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)}\s*;[ \t]*(?:\r?\n)?""")
        val without = pattern.replace(source, "")
        val simple = importName.substringAfterLast('.')
        return if (Regex("""\b${Regex.escape(simple)}\b""")
                .containsMatchIn(maskJavaCommentsAndLiterals(without))) source else without
    }

    private fun replaceExecutableLiteral(source: String, from: String, to: String): String {
        val matches = Regex(Regex.escape(from)).findAll(maskJavaCommentsAndLiterals(source)).toList()
        var result = source
        matches.asReversed().forEach { result = result.replaceRange(it.range, to) }
        return result
    }

    private fun replaceExecutableRegex(source: String, pattern: Regex, replacement: (MatchResult) -> String): String {
        val matches = pattern.findAll(maskJavaCommentsAndLiterals(source)).toList()
        var result = source
        matches.asReversed().forEach { result = result.replaceRange(it.range, replacement(it)) }
        return result
    }

    private fun findMatching(source: String, openIndex: Int, open: Char, close: Char): Int {
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                open -> depth++
                close -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        fun mask(start: Int, end: Int) {
            for (position in start until end.coerceAtMost(chars.size)) {
                if (chars[position] != '\r' && chars[position] != '\n') chars[position] = ' '
            }
        }
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                    mask(index, end); index = end
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                    mask(index, end); index = end
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    var end = index + 1
                    var escaped = false
                    while (end < source.length) {
                        val char = source[end]
                        if (char == quote && !escaped) { end++; break }
                        escaped = char == '\\' && !escaped
                        if (char != '\\') escaped = false
                        end++
                    }
                    mask(index, end); index = end
                }
                else -> index++
            }
        }
        return String(chars)
    }
}
