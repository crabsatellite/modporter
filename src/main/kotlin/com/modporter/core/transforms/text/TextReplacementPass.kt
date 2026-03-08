package com.modporter.core.transforms.text

import com.modporter.core.pipeline.*
import com.modporter.mapping.MappingDatabase
import com.modporter.mapping.TextReplacement
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Pass 1: Text-based find-and-replace transformations.
 * Handles package renames, class renames, and other simple substitutions.
 * Order of replacements matters (more specific patterns first).
 */
class TextReplacementPass(
    private val mappingDb: MappingDatabase
) : Pass {
    override val name = "Text Replacement"
    override val order = 1

    override fun analyze(projectDir: Path): PassResult {
        return processFiles(projectDir, dryRun = true)
    }

    override fun apply(projectDir: Path): PassResult {
        return processFiles(projectDir, dryRun = false)
    }

    private fun processFiles(projectDir: Path, dryRun: Boolean): PassResult {
        // Combine explicit text replacements with auto-generated rules from class-renames
        val rules = buildRuleList()
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()

        val javaFiles = findJavaFiles(projectDir)
        logger.info { "Found ${javaFiles.size} Java files to process" }

        for (file in javaFiles) {
            try {
                val result = processFile(file, rules, dryRun)
                changes.addAll(result)
            } catch (e: Exception) {
                errors.add("Error processing ${file}: ${e.message}")
                logger.error(e) { "Error processing $file" }
            }
        }

        return PassResult(name, changes, errors)
    }

    private fun processFile(file: Path, rules: List<TextReplacement>, dryRun: Boolean): List<Change> {
        val originalContent = file.readText()
        var content = originalContent
        val changes = mutableListOf<Change>()

        for (rule in rules) {
            val pattern = if (rule.isRegex) Regex(rule.pattern) else null
            val lines = content.lines()

            for ((lineIdx, line) in lines.withIndex()) {
                val matches = if (rule.isRegex) {
                    pattern!!.containsMatchIn(line)
                } else {
                    line.contains(rule.pattern)
                }

                if (matches) {
                    val newLine = if (rule.isRegex) {
                        pattern!!.replace(line, rule.replacement)
                    } else {
                        line.replace(rule.pattern, rule.replacement)
                    }

                    if (newLine != line) {
                        changes.add(
                            Change(
                                file = file,
                                line = lineIdx + 1,
                                description = rule.description,
                                before = line.trim(),
                                after = newLine.trim(),
                                confidence = Confidence.HIGH,
                                ruleId = rule.id
                            )
                        )
                    }
                }
            }

            // Apply replacement to full content
            content = if (rule.isRegex) {
                pattern!!.replace(content, rule.replacement)
            } else {
                content.replace(rule.pattern, rule.replacement)
            }
        }

        // Post-processing: clean up imports after all replacements
        content = cleanupImports(content, changes, file)

        if (!dryRun && content != originalContent) {
            file.writeText(content)
        }

        return changes
    }

    /**
     * Post-replacement import cleanup:
     * - Remove stale imports for classes that no longer exist in NeoForge
     * - Remove duplicate imports
     * - Add missing imports for classes introduced by replacements
     */
    private fun cleanupImports(content: String, changes: MutableList<Change>, file: Path): String {
        var result = content

        // Remove stale imports that reference classes removed or renamed in NeoForge
        val staleImports = listOf(
            "import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;",
            "import net.neoforged.neoforge.registries.ForgeRegistries;",
            "import net.neoforged.neoforge.registries.RegistryObject;",
            "import net.neoforged.neoforge.common.MinecraftForge;",
            "import net.neoforged.neoforge.network.simple.SimpleChannel;",
            "import net.neoforged.neoforge.network.NetworkRegistry;",
            "import net.neoforged.neoforge.network.NetworkDirection;",
            "import net.neoforged.neoforge.network.NetworkEvent;",
            "import net.neoforged.fml.common.Mod.EventBusSubscriber;",
        )
        for (stale in staleImports) {
            if (result.contains(stale)) {
                result = result.replace(stale + "\n", "")
                result = result.replace(stale, "")
            }
        }

        // Add missing imports based on what symbols are used in the code
        val missingImports = mutableListOf<String>()

        if (result.contains("BuiltInRegistries.") && !result.contains("import net.minecraft.core.registries.BuiltInRegistries;")) {
            missingImports.add("import net.minecraft.core.registries.BuiltInRegistries;")
        }
        if (result.contains("NeoForgeRegistries.") && !result.contains("import net.neoforged.neoforge.registries.NeoForgeRegistries;")) {
            missingImports.add("import net.neoforged.neoforge.registries.NeoForgeRegistries;")
        }
        // ModContainer import is added by the AST pass when modifying @Mod constructors
        if (result.contains("DeferredHolder") && !result.contains("import net.neoforged.neoforge.registries.DeferredHolder;")) {
            missingImports.add("import net.neoforged.neoforge.registries.DeferredHolder;")
        }
        if (result.contains("@EventBusSubscriber") && !result.contains("import net.neoforged.fml.common.EventBusSubscriber;")) {
            missingImports.add("import net.neoforged.fml.common.EventBusSubscriber;")
        }
        if (result.contains("IPayloadContext") && !result.contains("import net.neoforged.neoforge.network.handling.IPayloadContext;")) {
            missingImports.add("import net.neoforged.neoforge.network.handling.IPayloadContext;")
        }
        if (result.contains("PacketDistributor.send") && !result.contains("import net.neoforged.neoforge.network.PacketDistributor;")) {
            missingImports.add("import net.neoforged.neoforge.network.PacketDistributor;")
        }
        if (result.contains("(ServerPlayer)") && !result.contains("import net.minecraft.server.level.ServerPlayer;")) {
            missingImports.add("import net.minecraft.server.level.ServerPlayer;")
        }
        if (result.contains("CustomPacketPayload") && !result.contains("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;")) {
            missingImports.add("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;")
        }
        if (result.contains("RenderGuiLayerEvent") && !result.contains("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;")) {
            missingImports.add("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;")
        }
        if (result.contains("Registries.BIOME") && !result.contains("import net.minecraft.core.registries.Registries;")) {
            missingImports.add("import net.minecraft.core.registries.Registries;")
        }


        if (missingImports.isNotEmpty()) {
            // Insert after the last existing import line
            val lines = result.lines().toMutableList()
            val lastImportIdx = lines.indexOfLast { it.trimStart().startsWith("import ") }
            if (lastImportIdx >= 0) {
                for ((i, imp) in missingImports.withIndex()) {
                    lines.add(lastImportIdx + 1 + i, imp)
                }
                result = lines.joinToString("\n")
            }
        }

        return result
    }

    /**
     * Build the complete rule list by combining explicit text-replacements.json rules
     * with auto-generated regex rules from class-renames.json.
     * Explicit rules come first (they include ordered package renames),
     * then class rename rules are appended.
     */
    private fun buildRuleList(): List<TextReplacement> {
        val explicitRules = mappingDb.getTextReplacements()
        val classRenameRules = mappingDb.getAllClassMappings()
            .filter { (forge, mapping) ->
                // Skip entries already covered by explicit text rules
                explicitRules.none { it.pattern.contains(forge) }
            }
            .map { (forge, mapping) ->
                TextReplacement(
                    id = "cls-auto-$forge",
                    pattern = "\\b${Regex.escape(forge)}\\b",
                    replacement = mapping.neoForgeClass,
                    description = "Class rename: $forge -> ${mapping.neoForgeClass}",
                    isRegex = true
                )
            }
        return explicitRules + classRenameRules
    }

    private fun findJavaFiles(projectDir: Path): List<Path> {
        return Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/")
            }}
            .toList()
    }
}
