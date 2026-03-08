package com.modporter.resources

import com.modporter.core.pipeline.*
import com.modporter.mapping.MappingDatabase
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Pass 5: Resource file migration.
 * Handles data folder renames, mods.toml migration, and pack format updates.
 */
class ResourceMigrationPass(
    private val mappingDb: MappingDatabase
) : Pass {
    override val name = "Resource Migration"
    override val order = 5

    override fun analyze(projectDir: Path): PassResult = processResources(projectDir, dryRun = true)
    override fun apply(projectDir: Path): PassResult = processResources(projectDir, dryRun = false)

    private fun processResources(projectDir: Path, dryRun: Boolean): PassResult {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val renames = mappingDb.getAllResourceRenames()

        // Find resource directories
        val resourceDirs = findResourceDirs(projectDir)

        for (resourceDir in resourceDirs) {
            // Handle mods.toml rename
            val modsToml = resourceDir.resolve("META-INF/mods.toml")
            if (modsToml.exists()) {
                val target = resourceDir.resolve("META-INF/neoforge.mods.toml")
                changes.add(Change(
                    file = modsToml, line = 0,
                    description = "Rename mods.toml -> neoforge.mods.toml",
                    before = "META-INF/mods.toml",
                    after = "META-INF/neoforge.mods.toml",
                    confidence = Confidence.HIGH,
                    ruleId = "res-mods-toml"
                ))
                if (!dryRun) {
                    Files.move(modsToml, target, StandardCopyOption.REPLACE_EXISTING)
                    transformModsToml(target)
                }
            }

            // Collect all data folder renames BEFORE executing any
            // (to avoid stale directory issues during walk)
            val dataDir = resourceDir.resolve("data")
            if (dataDir.exists()) {
                val folderRenames = renames.filter { it.key != "META-INF/mods.toml" }
                val pendingMoves = mutableListOf<Pair<Path, Path>>()

                // Snapshot all directories once
                val allDirs = Files.walk(dataDir, 10)
                    .filter { Files.isDirectory(it) }
                    .toList()

                for ((from, to) in folderRenames) {
                    // Extract just the last segment of `to` for renaming
                    // e.g., from="tags/items" to="tags/item" → lastSegment="item"
                    val toLastSegment = to.substringAfterLast("/")
                    for (dir in allDirs) {
                        val relative = dataDir.relativize(dir).toString().replace('\\', '/')
                        if (relative.endsWith("/$from") || relative == from) {
                            val targetPath = dir.parent.resolve(toLastSegment)
                            changes.add(Change(
                                file = dir, line = 0,
                                description = "Rename data folder: $from -> $to",
                                before = dir.toString(),
                                after = targetPath.toString(),
                                confidence = Confidence.HIGH,
                                ruleId = "res-folder-rename"
                            ))
                            pendingMoves.add(dir to targetPath)
                        }
                    }
                }

                // Execute all renames after collection
                if (!dryRun) {
                    for ((source, target) in pendingMoves) {
                        if (source.exists() && !target.exists()) {
                            try {
                                Files.move(source, target)
                            } catch (e: Exception) {
                                errors.add("Failed to rename $source: ${e.message}")
                            }
                        }
                    }
                }
            }

            // Migrate data/forge/ -> split between data/c/ (tags) and data/neoforge/ (non-tags)
            val forgeDataDir = resourceDir.resolve("data/forge")
            if (forgeDataDir.exists() && !dryRun) {
                migrateForgeDataDir(forgeDataDir, resourceDir, changes, errors)
            } else if (forgeDataDir.exists()) {
                changes.add(Change(
                    file = forgeDataDir, line = 0,
                    description = "Migrate data/forge/ -> data/c/ (tags) + data/neoforge/ (non-tags)",
                    before = "data/forge/",
                    after = "data/c/ + data/neoforge/",
                    confidence = Confidence.HIGH,
                    ruleId = "res-forge-namespace"
                ))
            }

            // Transform JSON data files: conditions, recipe format, namespace
            if (dataDir.exists() && !dryRun) {
                transformDataJsonFiles(dataDir, changes, errors)
            }

            // Update pack.mcmeta
            val packMcmeta = resourceDir.resolve("pack.mcmeta")
            if (packMcmeta.exists()) {
                changes.add(Change(
                    file = packMcmeta, line = 0,
                    description = "Update pack_format: 15 -> 48 (data pack) with supported_formats 34-48",
                    before = "\"pack_format\": 15",
                    after = "\"pack_format\": 48",
                    confidence = Confidence.HIGH,
                    ruleId = "res-pack-format"
                ))
                if (!dryRun) {
                    updatePackFormat(packMcmeta)
                }
            }
        }

        // Handle template mods.toml files (used by Groovy template expansion)
        migrateTemplateFiles(projectDir, changes, errors, dryRun)

        return PassResult(name, changes, errors)
    }

    private fun findResourceDirs(projectDir: Path): List<Path> {
        val dirs = mutableListOf<Path>()
        val mainResources = projectDir.resolve("src/main/resources")
        if (mainResources.exists()) dirs.add(mainResources)
        val genResources = projectDir.resolve("src/generated/resources")
        if (genResources.exists()) dirs.add(genResources)
        return dirs
    }

    /**
     * Find and transform template mods.toml files (used by Groovy template expansion in build.gradle).
     * These need the same transformations as regular mods.toml plus template variable renames.
     */
    private fun migrateTemplateFiles(projectDir: Path, changes: MutableList<Change>, errors: MutableList<String>, dryRun: Boolean) {
        val templateDirs = listOf(
            projectDir.resolve("src/main/templates"),
            projectDir.resolve("src/generated/templates"),
        )

        for (templateDir in templateDirs) {
            val modsToml = templateDir.resolve("META-INF/mods.toml")
            if (!modsToml.exists()) continue

            val target = templateDir.resolve("META-INF/neoforge.mods.toml")
            changes.add(Change(
                file = modsToml, line = 0,
                description = "Rename template mods.toml -> neoforge.mods.toml",
                before = "templates/META-INF/mods.toml",
                after = "templates/META-INF/neoforge.mods.toml",
                confidence = Confidence.HIGH,
                ruleId = "res-template-mods-toml"
            ))

            if (!dryRun) {
                Files.move(modsToml, target, StandardCopyOption.REPLACE_EXISTING)
                // Fix template variable references BEFORE transformModsToml
                // (so that ${forge_version_range} becomes ${neoforge_version_range}
                // instead of being hardcoded by updateDependencyVersionRanges)
                transformTemplateVariables(target)
                transformModsToml(target)
            }
        }
    }

    /**
     * Fix Groovy template variable references in template mods.toml files.
     * e.g., ${forge_version_range} -> ${neoforge_version_range}
     *        ${forge_version} -> ${neo_forge_version}
     */
    private fun transformTemplateVariables(file: Path) {
        var content = file.readText()
        content = content.replace("\${forge_version_range}", "\${neoforge_version_range}")
        content = content.replace("\${forge_version}", "\${neo_forge_version}")
        file.writeText(content)
    }

    /**
     * Full mods.toml → neoforge.mods.toml content transformation.
     *
     * Changes applied:
     * 1. loaderVersion: [47,) → [1,)
     * 2. modId="forge" → modId="neoforge" in dependencies
     * 3. mandatory=true → type="required", mandatory=false → type="optional"
     * 4. Forge dependency versionRange → [21.1,)
     * 5. Minecraft dependency versionRange → [1.21.1,1.22)
     * 6. Remove displayTest field
     * 7. Remove clientSideOnly field
     */
    internal fun transformModsToml(file: Path) {
        var content = file.readText()

        // 1. Update loader version range: [47,) or similar Forge ranges → [1,) (official NeoForge MDK value)
        content = content.replace(
            Regex("""loaderVersion\s*=\s*"\[[\d,.]+\)""""),
            """loaderVersion="[1,)""""
        )

        // 2. Update Forge dependency modId to NeoForge (multiple spacing variants)
        content = content.replace(
            Regex("""modId\s*=\s*"forge""""),
            """modId="neoforge""""
        )

        // 3. Replace mandatory=true/false with type="required"/"optional"
        content = content.replace(
            Regex("""mandatory\s*=\s*true"""),
            """type="required""""
        )
        content = content.replace(
            Regex("""mandatory\s*=\s*false"""),
            """type="optional""""
        )

        // 4. Update NeoForge dependency versionRange
        // 5. Update Minecraft dependency versionRange
        // Process dependency blocks: find modId lines and update nearby versionRange
        content = updateDependencyVersionRanges(content)

        // 6. Remove displayTest line (removed in NeoForge)
        content = content.replace(
            Regex("""^\s*displayTest\s*=\s*"[^"]*"\s*$""", RegexOption.MULTILINE),
            ""
        )

        // 7. Remove clientSideOnly line (removed in NeoForge)
        content = content.replace(
            Regex("""^\s*clientSideOnly\s*=\s*(true|false)\s*$""", RegexOption.MULTILINE),
            ""
        )

        // 8. Fix Groovy template variable references
        content = content.replace("\${forge_version_range}", "\${neoforge_version_range}")
        content = content.replace("\${forge_version}", "\${neo_forge_version}")

        // Clean up any double blank lines left from removals
        content = content.replace(Regex("""\n{3,}"""), "\n\n")

        file.writeText(content)
    }

    /**
     * Update versionRange in dependency blocks based on modId.
     * Scans line-by-line to identify which dependency block we're in,
     * then adjusts versionRange accordingly.
     */
    private fun updateDependencyVersionRanges(content: String): String {
        val lines = content.lines().toMutableList()
        var currentDepModId: String? = null

        for (i in lines.indices) {
            val line = lines[i]

            // Detect dependency modId
            val modIdMatch = Regex("""modId\s*=\s*"(\w+)"""").find(line)
            if (modIdMatch != null) {
                currentDepModId = modIdMatch.groupValues[1]
            }

            // Reset on new section header
            if (line.trimStart().startsWith("[[")) {
                // Reset if entering a new section that isn't a continuation
                if (!line.contains("dependencies")) {
                    currentDepModId = null
                }
            }

            // Update versionRange based on which dependency we're in
            // Skip lines with template variables (${...}) — they use Groovy expansion
            if (currentDepModId != null && line.contains("versionRange") && !line.contains("\${")) {
                when (currentDepModId) {
                    "neoforge" -> {
                        lines[i] = line.replace(
                            Regex("""versionRange\s*=\s*"[^"]*""""),
                            """versionRange="[21.1,)""""
                        )
                    }
                    "minecraft" -> {
                        lines[i] = line.replace(
                            Regex("""versionRange\s*=\s*"\[1\.20[^"]*""""),
                            """versionRange="[1.21.1,1.22)""""
                        )
                    }
                }
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Split data/forge/ into data/c/ (tags) and data/neoforge/ (everything else).
     * In NeoForge 1.21.1, forge: tags moved to the unified c: (common) namespace,
     * while non-tag data (loot modifiers, biome modifiers) uses neoforge: namespace.
     */
    private fun migrateForgeDataDir(forgeDataDir: Path, resourceDir: Path, changes: MutableList<Change>, errors: MutableList<String>) {
        val tagsDir = forgeDataDir.resolve("tags")
        val cDataDir = resourceDir.resolve("data/c")
        val neoforgeDataDir = resourceDir.resolve("data/neoforge")

        try {
            // Move tags/ -> data/c/tags/
            if (tagsDir.exists()) {
                cDataDir.resolve("tags").createDirectories()
                Files.walk(tagsDir).filter { Files.isRegularFile(it) }.forEach { file ->
                    val relative = tagsDir.relativize(file)
                    val target = cDataDir.resolve("tags").resolve(relative)
                    target.parent.createDirectories()
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
                }
                changes.add(Change(
                    file = tagsDir, line = 0,
                    description = "Migrate data/forge/tags/ -> data/c/tags/ (common tag namespace)",
                    before = "data/forge/tags/", after = "data/c/tags/",
                    confidence = Confidence.HIGH, ruleId = "res-forge-to-c-tags"
                ))
            }

            // Move remaining non-tag content -> data/neoforge/
            val remaining = Files.walk(forgeDataDir)
                .filter { Files.isRegularFile(it) }
                .toList()
            if (remaining.isNotEmpty()) {
                neoforgeDataDir.createDirectories()
                for (file in remaining) {
                    val relative = forgeDataDir.relativize(file)
                    val target = neoforgeDataDir.resolve(relative)
                    target.parent.createDirectories()
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
                }
                changes.add(Change(
                    file = forgeDataDir, line = 0,
                    description = "Migrate data/forge/ (non-tags) -> data/neoforge/",
                    before = "data/forge/", after = "data/neoforge/",
                    confidence = Confidence.HIGH, ruleId = "res-forge-to-neoforge"
                ))
            }

            // Clean up empty forge directory
            Files.walk(forgeDataDir)
                .sorted(Comparator.reverseOrder())
                .filter { Files.isDirectory(it) }
                .forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
        } catch (e: Exception) {
            errors.add("Failed to migrate data/forge: ${e.message}")
        }
    }

    /**
     * Transform JSON data files for NeoForge 1.21.1 compatibility:
     * - Recipe results: "item" key → "id" key
     * - Smelting results: plain string → {"id": "..."} object
     * - Forge conditions: "conditions" → "neoforge:conditions"
     * - Condition types: "forge:" prefix → "neoforge:" prefix
     */
    internal fun transformDataJsonFiles(dataDir: Path, changes: MutableList<Change>, errors: MutableList<String>) {
        Files.walk(dataDir)
            .filter { it.toString().endsWith(".json") && Files.isRegularFile(it) }
            .forEach { file ->
                try {
                    var content = file.readText()
                    var modified = false

                    // Recipe result: "item" → "id" in result objects
                    // Match "result": {"item": "..." pattern and change to "result": {"id": "..."
                    if (content.contains("\"result\"") && content.contains("\"type\"")) {
                        val newContent = content.replace(
                            Regex("""("result"\s*:\s*\{[^}]*)"item"(\s*:)"""),
                            """$1"id"$2"""
                        )
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Recipe result: \"item\" -> \"id\"",
                                before = "\"item\":", after = "\"id\":",
                                confidence = Confidence.HIGH,
                                ruleId = "res-recipe-result-id"
                            ))
                        }
                    }

                    // Forge recipe/loot conditions → NeoForge conditions
                    // Only rename "conditions" in non-advancement files (advancements use "conditions" for triggers)
                    val filePath = file.toString().replace("\\", "/")
                    val isAdvancementFile = filePath.contains("/advancement/")
                    if (!isAdvancementFile && content.contains("\"conditions\"")) {
                        val newContent = content
                            .replace("\"conditions\"", "\"neoforge:conditions\"")
                            .replace("\"forge:", "\"neoforge:")
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Forge conditions -> NeoForge conditions",
                                before = "\"conditions\"", after = "\"neoforge:conditions\"",
                                confidence = Confidence.HIGH,
                                ruleId = "res-conditions-namespace"
                            ))
                        }
                    }

                    // Advancement trigger: "neoforge:conditions" → "conditions", "tag" → "items" with # prefix
                    if (isAdvancementFile) {
                        // Fix over-renamed conditions (from package rename pass): "neoforge:conditions" back to "conditions"
                        if (content.contains("\"neoforge:conditions\"")) {
                            val newContent = content.replace("\"neoforge:conditions\"", "\"conditions\"")
                            if (newContent != content) {
                                content = newContent
                                modified = true
                                changes.add(Change(
                                    file = file, line = 0,
                                    description = "Advancement trigger: restore \"conditions\" key",
                                    before = "\"neoforge:conditions\"", after = "\"conditions\"",
                                    confidence = Confidence.HIGH,
                                    ruleId = "res-advancement-conditions-fix"
                                ))
                            }
                        }
                        // Tag syntax in item predicates: "tag": "xxx" → "items": "#xxx"
                        val tagPattern = Regex(""""tag"\s*:\s*"([^"]+)"""")
                        if (tagPattern.containsMatchIn(content)) {
                            val newContent = tagPattern.replace(content) { match ->
                                """"items": "#${match.groupValues[1]}""""
                            }
                            if (newContent != content) {
                                content = newContent
                                modified = true
                                changes.add(Change(
                                    file = file, line = 0,
                                    description = "Advancement item predicate: \"tag\" -> \"items\" with # prefix",
                                    before = "\"tag\": \"...\"", after = "\"items\": \"#...\"",
                                    confidence = Confidence.HIGH,
                                    ruleId = "res-advancement-tag-to-items"
                                ))
                            }
                        }
                    }

                    // forge: namespace in condition types (without touching conditions key)
                    if (content.contains("\"forge:")) {
                        val newContent = content.replace("\"forge:", "\"neoforge:")
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "forge: namespace -> neoforge: in JSON",
                                before = "\"forge:", after = "\"neoforge:",
                                confidence = Confidence.HIGH,
                                ruleId = "res-forge-namespace-json"
                            ))
                        }
                    }

                    // Loot function renames: set_nbt -> set_custom_data, copy_nbt -> copy_custom_data
                    if (content.contains("set_nbt") || content.contains("copy_nbt")) {
                        val newContent = content
                            .replace("\"minecraft:set_nbt\"", "\"minecraft:set_custom_data\"")
                            .replace("\"minecraft:copy_nbt\"", "\"minecraft:copy_custom_data\"")
                            .replace("\"set_nbt\"", "\"set_custom_data\"")
                            .replace("\"copy_nbt\"", "\"copy_custom_data\"")
                        if (newContent != content) {
                            content = newContent
                            modified = true
                            changes.add(Change(
                                file = file, line = 0,
                                description = "Loot function: set_nbt/copy_nbt -> set_custom_data/copy_custom_data",
                                before = "set_nbt/copy_nbt", after = "set_custom_data/copy_custom_data",
                                confidence = Confidence.HIGH,
                                ruleId = "res-loot-nbt-rename"
                            ))
                        }
                    }

                    if (modified) {
                        file.writeText(content)
                    }
                } catch (e: Exception) {
                    errors.add("Failed to transform ${file.fileName}: ${e.message}")
                }
            }
    }

    private fun updatePackFormat(file: Path) {
        var content = file.readText()
        // Data pack format = 48, resource pack format = 34 for MC 1.21.1
        // Use 48 as primary (higher value) with supported_formats range
        content = content.replace(
            Regex(""""pack_format"\s*:\s*\d+"""),
            "\"pack_format\": 48"
        )
        // Add supported_formats range if not already present
        if (!content.contains("supported_formats")) {
            content = content.replace(
                "\"pack_format\": 48",
                "\"pack_format\": 48, \"supported_formats\": {\"min_inclusive\": 34, \"max_inclusive\": 48}"
            )
        }
        file.writeText(content)
    }
}
