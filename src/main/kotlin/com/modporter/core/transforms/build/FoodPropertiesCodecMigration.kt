package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class FoodPropertiesCodecMigration {
    private data class Edit(val range: IntRange, val fieldName: String, val before: String)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "java" }.forEach { file ->
                val original = file.readText()
                val executable = maskJavaCommentsAndLiterals(original)
                val edits = collectEdits(original, executable)
                if (edits.isEmpty()) return@forEach

                var migrated = original
                for (edit in edits.sortedByDescending { it.range.first }) {
                    migrated = migrated.replaceRange(edit.range, "FoodProperties.DIRECT_CODEC")
                    changes.add(
                        Change(
                            file = file,
                            line = original.lineNumberAt(edit.range.first),
                            description = "Replace complete legacy FoodProperties codec ${edit.fieldName} with the target standard codec",
                            before = edit.before,
                            after = "FoodProperties.DIRECT_CODEC",
                            confidence = Confidence.HIGH,
                            ruleId = "build-food-properties-standard-codec"
                        )
                    )
                }
                if (!dryRun) file.writeText(migrated)
            }
        }
        return changes
    }

    private fun collectEdits(source: String, executable: String): List<Edit> {
        val declaration = Regex(
            """\b(?:public|protected|private)?\s*static\s+final\s+(?:com\.mojang\.serialization\.)?Codec\s*<\s*(?:net\.minecraft\.world\.food\.)?FoodProperties\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*RecordCodecBuilder\.create\s*\("""
        )
        return declaration.findAll(executable).mapNotNull { match ->
            val openParen = match.range.last
            val closeParen = findMatchingParen(executable, openParen)
            if (closeParen < 0) return@mapNotNull null
            val initializerStart = executable.indexOf("RecordCodecBuilder.create", match.range.first)
            if (initializerStart < 0) return@mapNotNull null
            val initializer = executable.substring(initializerStart, closeParen + 1)
            if (!isCompleteLegacyCodec(initializer)) return@mapNotNull null
            Edit(
                range = initializerStart..closeParen,
                fieldName = match.groupValues[1],
                before = source.substring(initializerStart, closeParen + 1)
            )
        }.toList()
    }

    private fun isCompleteLegacyCodec(initializer: String): Boolean {
        val required = listOf(
            Regex("""FoodProperties::(?:getNutrition|nutrition)\b"""),
            Regex("""FoodProperties::(?:getSaturationModifier|saturation)\b"""),
            Regex("""FoodProperties::(?:canAlwaysEat|canAlwaysEat)\b"""),
            Regex("""new\s+FoodProperties\.Builder\s*\("""),
            Regex("""\.build\s*\(\s*\)""")
        )
        val effectsRoundTrip = initializer.contains("getEffects()") ||
            initializer.contains(".effects()") ||
            initializer.contains("FoodProperties::effects")
        return effectsRoundTrip && required.all { it.containsMatchIn(initializer) }
    }

    private fun findMatchingParen(source: String, openParen: Int): Int {
        if (openParen !in source.indices || source[openParen] != '(') return -1
        var depth = 0
        for (index in openParen until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val result = source.toCharArray()
        var index = 0
        var state = 0
        while (index < source.length) {
            val next = source.getOrNull(index + 1)
            when (state) {
                0 -> when {
                    source[index] == '/' && next == '/' -> {
                        result[index] = ' '
                        result[index + 1] = ' '
                        index += 2
                        state = 1
                    }
                    source[index] == '/' && next == '*' -> {
                        result[index] = ' '
                        result[index + 1] = ' '
                        index += 2
                        state = 2
                    }
                    source.startsWith("\"\"\"", index) -> {
                        repeat(3) { result[index + it] = ' ' }
                        index += 3
                        state = 5
                    }
                    source[index] == '"' -> {
                        result[index++] = ' '
                        state = 3
                    }
                    source[index] == '\'' -> {
                        result[index++] = ' '
                        state = 4
                    }
                    else -> index++
                }
                1 -> {
                    if (source[index] == '\n' || source[index] == '\r') state = 0 else result[index] = ' '
                    index++
                }
                2 -> {
                    result[index] = if (source[index] == '\n' || source[index] == '\r') source[index] else ' '
                    if (source[index] == '*' && next == '/') {
                        result[index + 1] = ' '
                        index += 2
                        state = 0
                    } else index++
                }
                3, 4 -> {
                    val delimiter = if (state == 3) '"' else '\''
                    result[index] = if (source[index] == '\n' || source[index] == '\r') source[index] else ' '
                    if (source[index] == '\\' && index + 1 < source.length) {
                        result[index + 1] = ' '
                        index += 2
                    } else if (source[index++] == delimiter) {
                        state = 0
                    }
                }
                5 -> {
                    if (source.startsWith("\"\"\"", index)) {
                        repeat(3) { result[index + it] = ' ' }
                        index += 3
                        state = 0
                    } else {
                        result[index] = if (source[index] == '\n' || source[index] == '\r') source[index] else ' '
                        index++
                    }
                }
            }
        }
        return result.concatToString()
    }

    private fun String.lineNumberAt(offset: Int): Int =
        take(offset.coerceAtMost(length)).count { it == '\n' } + 1
}
