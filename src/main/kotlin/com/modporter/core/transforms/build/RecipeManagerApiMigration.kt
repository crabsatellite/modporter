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

internal class RecipeManagerApiMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val javaFiles = Files.walk(srcDir)
            .filter { it.extension == "java" }
            .toList()
        val changes = mutableListOf<Change>()
        var migratedFlows = 0

        for (file in javaFiles) {
            val original = file.readText()
            val rewrite = rewriteTypedRecipeMapFlows(original)
            if (rewrite.second == 0) continue
            migratedFlows += rewrite.second
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate direct RecipeManager recipe maps to typed RecipeHolder queries",
                before = "RecipeManager.recipes.get(type) map flow",
                after = "RecipeManager.getAllRecipesFor(type) holder flow",
                confidence = Confidence.HIGH,
                ruleId = "build-recipe-manager-public-query-api"
            )
            if (!dryRun) file.writeText(rewrite.first)
        }
        if (migratedFlows == 0) return changes

        val resultingSources = javaFiles.associateWith { file ->
            if (dryRun) rewriteTypedRecipeMapFlows(file.readText()).first else file.readText()
        }
        val remaining = resultingSources.entries.firstOrNull { (_, source) ->
            containsDirectRecipeManagerMapAccess(source)
        }
        require(remaining == null) {
            "Direct RecipeManager recipe map access remains after migration in ${remaining?.key}"
        }

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        if (atFile.exists()) {
            val originalAt = atFile.readText()
            val removed = originalAt.lines().filterNot { line ->
                val entry = line.substringBefore('#').trim().replace(Regex("""\s+"""), " ")
                entry == "public net.minecraft.world.item.crafting.RecipeManager f_44007_" ||
                    entry == "public net.minecraft.world.item.crafting.RecipeManager recipes"
            }
            val modifiedAt = removed.joinToString(System.lineSeparator()).trimEnd() + System.lineSeparator()
            if (modifiedAt != originalAt) {
                changes += Change(
                    file = atFile,
                    line = 1,
                    description = "Remove obsolete RecipeManager field AT after all uses moved to its public query API",
                    before = "RecipeManager f_44007_/recipes AT",
                    after = "getAllRecipesFor public API",
                    confidence = Confidence.HIGH,
                    ruleId = "build-recipe-manager-obsolete-at"
                )
                if (!dryRun) atFile.writeText(modifiedAt)
            }
        }
        return changes
    }

    private fun rewriteTypedRecipeMapFlows(source: String): Pair<String, Int> {
        var result = source
        var count = 0
        val declarationPattern = Regex(
            """Map\s*<\s*ResourceLocation\s*,\s*Recipe\s*<\s*\?\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*([\s\S]*?)\.recipes\.get\(\s*([^;]+?)\s*\)\s*;"""
        )
        while (true) {
            val executable = maskJavaCommentsAndLiterals(result)
            val declaration = declarationPattern.find(executable) ?: break
            val mapVariable = declaration.groupValues[1]
            val managerExpression = result.substring(declaration.groups[2]!!.range).trim()
            val typeExpression = result.substring(declaration.groups[3]!!.range).trim()
            val afterDeclaration = declaration.range.last + 1
            val tail = executable.substring(afterDeclaration)
            val usePattern = Regex(
                """^\s*if\s*\(\s*${Regex.escape(mapVariable)}\s*!=\s*null\s*\)\s*${Regex.escape(mapVariable)}\.values\(\)\.forEach\(\s*([A-Za-z_$][\w$]*)\s*->\s*([^;]+)\s*\)\s*;"""
            )
            val use = usePattern.find(tail)
                ?: error("RecipeManager map $mapVariable has an unsupported downstream flow")
            require(use.range.first == 0)
            val recipeVariable = use.groupValues[1]
            val bodyRange = use.groups[2]!!.range
            val body = result.substring(
                afterDeclaration + bodyRange.first,
                afterDeclaration + bodyRange.last + 1
            ).trim()
            val executableBody = maskJavaCommentsAndLiterals(body)
            require(Regex("""\b${Regex.escape(recipeVariable)}\b""").containsMatchIn(executableBody)) {
                "RecipeManager map lambda does not consume its recipe value"
            }
            val holderVariable = uniqueIdentifier(result, "modporterRecipeHolder")
            val migratedBody = replaceExecutableIdentifier(body, recipeVariable, "$holderVariable.value()")
            val indentation = result.substring(
                result.lastIndexOf('\n', declaration.range.first - 1).let { it + 1 },
                declaration.range.first
            )
            val replacement = buildString {
                append(managerExpression)
                append(".getAllRecipesFor((RecipeType) ")
                append(typeExpression)
                append(")\n")
                append(indentation).append("\t.forEach(").append(holderVariable).append(" -> ")
                append(migratedBody).append(");")
            }
            val end = afterDeclaration + use.range.last
            result = result.replaceRange(declaration.range.first..end, replacement)
            count++
        }
        if (count > 0) {
            result = removeImportIfUnused(result, "java.util.Map")
            result = removeImportIfUnused(result, "net.minecraft.resources.ResourceLocation")
        }
        return result to count
    }

    private fun containsDirectRecipeManagerMapAccess(source: String): Boolean {
        val executable = maskJavaCommentsAndLiterals(source)
        if (Regex("""\.getRecipeManager\s*\(\s*\)\s*\.\s*recipes\b""").containsMatchIn(executable)) {
            return true
        }
        val variables = Regex("""\bRecipeManager\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(executable)
            .map { it.groupValues[1] }
        return variables.any { variable ->
            Regex("""\b${Regex.escape(variable)}\s*\.\s*recipes\b""").containsMatchIn(executable)
        }
    }

    private fun replaceExecutableIdentifier(source: String, identifier: String, replacement: String): String {
        val matches = Regex("""\b${Regex.escape(identifier)}\b""")
            .findAll(maskJavaCommentsAndLiterals(source))
            .toList()
        var result = source
        matches.asReversed().forEach { match -> result = result.replaceRange(match.range, replacement) }
        return result
    }

    private fun uniqueIdentifier(source: String, base: String): String {
        val executable = maskJavaCommentsAndLiterals(source)
        if (!Regex("""\b${Regex.escape(base)}\b""").containsMatchIn(executable)) return base
        var suffix = 2
        while (Regex("""\b${Regex.escape(base + suffix)}\b""").containsMatchIn(executable)) suffix++
        return base + suffix
    }

    private fun removeImportIfUnused(source: String, importName: String): String {
        val simpleName = importName.substringAfterLast('.')
        val importPattern = Regex(
            """(?m)^[ \t]*import\s+${Regex.escape(importName)}\s*;[ \t]*(?:\r?\n)?"""
        )
        val withoutImport = importPattern.replace(source, "")
        return if (Regex("""\b${Regex.escape(simpleName)}\b""")
                .containsMatchIn(maskJavaCommentsAndLiterals(withoutImport))) source else withoutImport
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
                    mask(index, end)
                    index = end
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                    mask(index, end)
                    index = end
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    var end = index + 1
                    var escaped = false
                    while (end < source.length) {
                        val char = source[end]
                        if (char == quote && !escaped) {
                            end++
                            break
                        }
                        escaped = char == '\\' && !escaped
                        if (char != '\\') escaped = false
                        end++
                    }
                    mask(index, end)
                    index = end
                }
                else -> index++
            }
        }
        return String(chars)
    }
}
