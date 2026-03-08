package com.modporter.core.transforms.build

import com.modporter.core.pipeline.*
import mu.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Pass 4: Build system migration.
 * Transforms build.gradle/build.gradle.kts from Forge 1.20.1 MDK to NeoForge 1.21.1.
 *
 * Key transformations:
 * - Forge Gradle plugin → NeoForge ModDev plugin
 * - net.minecraftforge:forge dependency → neoForge { version = "..." }
 * - Repository URLs
 * - Mappings configuration
 * - Run configurations
 * - settings.gradle plugin repositories
 */
class BuildSystemPass(
    val offlineMode: Boolean = true,
    val mappingsPrefix: String = "/mappings/forge2neo"
) : Pass {
    override val name = "Build System"
    override val order = 4

    override fun analyze(projectDir: Path): PassResult = processBuildFiles(projectDir, dryRun = true)
    override fun apply(projectDir: Path): PassResult = processBuildFiles(projectDir, dryRun = false)

    private fun processBuildFiles(projectDir: Path, dryRun: Boolean): PassResult {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()

        // Handle build.gradle (Groovy)
        val buildGradle = projectDir.resolve("build.gradle")
        if (buildGradle.exists()) {
            try {
                val result = transformBuildGradle(buildGradle, dryRun)
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform build.gradle: ${e.message}")
            }
        }

        // Handle build.gradle.kts (Kotlin DSL)
        val buildGradleKts = projectDir.resolve("build.gradle.kts")
        if (buildGradleKts.exists()) {
            try {
                val result = transformBuildGradle(buildGradleKts, dryRun)
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform build.gradle.kts: ${e.message}")
            }
        }

        // Handle settings.gradle / settings.gradle.kts
        var hasSettings = false
        for (settingsFile in listOf("settings.gradle", "settings.gradle.kts")) {
            val path = projectDir.resolve(settingsFile)
            if (path.exists()) {
                hasSettings = true
                try {
                    val result = transformSettingsGradle(path, dryRun)
                    changes.addAll(result.first)
                    errors.addAll(result.second)
                } catch (e: Exception) {
                    errors.add("Failed to transform $settingsFile: ${e.message}")
                }
            }
        }
        // Create settings.gradle if missing (required for standalone project with plugins { } block)
        val hasBuildFile = buildGradle.exists() || buildGradleKts.exists()
        if (!hasSettings && hasBuildFile) {
            val settingsPath = projectDir.resolve("settings.gradle")
            val projectName = projectDir.fileName.toString()
            changes.add(Change(
                file = settingsPath, line = 0,
                description = "Create settings.gradle for NeoForge ModDev plugin resolution",
                before = "(missing)",
                after = "settings.gradle with NeoForge plugin repository",
                confidence = Confidence.HIGH,
                ruleId = "build-create-settings"
            ))
            if (!dryRun) {
                settingsPath.writeText(SETTINGS_GRADLE.replace("%%PROJECT_NAME%%", projectName))
            }
        }

        // Update Gradle wrapper if too old (ModDevGradle requires Gradle 8.5+, Java 21 requires 8.5+)
        val wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (wrapperProps.exists()) {
            val wrapperContent = wrapperProps.readText()
            val versionMatch = Regex("""gradle-(\d+)\.(\d+)""").find(wrapperContent)
            val majorVersion = versionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minorVersion = versionMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val needsUpdate = majorVersion < 8 || (majorVersion == 8 && minorVersion < 5)
            if (needsUpdate) {
                changes.add(Change(
                    file = wrapperProps, line = 0,
                    description = "Update Gradle wrapper from $majorVersion.$minorVersion to 8.14.4 (ModDevGradle + Java 21 require 8.5+)",
                    before = "gradle-${majorVersion}.${minorVersion}.x",
                    after = "gradle-8.14.4",
                    confidence = Confidence.HIGH,
                    ruleId = "build-gradle-wrapper"
                ))
                if (!dryRun) {
                    wrapperProps.writeText(
                        wrapperContent.replace(
                            Regex("""gradle-[\d.]+-bin\.zip"""),
                            "gradle-8.14.4-bin.zip"
                        )
                    )
                }
            }
        }

        // Cleanup: remove references to excluded classes from remaining Java files
        try {
            val cleanupResult = cleanupExcludedReferences(projectDir, dryRun)
            changes.addAll(cleanupResult.first)
            errors.addAll(cleanupResult.second)
        } catch (e: Exception) {
            errors.add("Failed to cleanup excluded references: ${e.message}")
        }

        // Handle gradle.properties
        val gradleProperties = projectDir.resolve("gradle.properties")
        if (gradleProperties.exists()) {
            try {
                val result = transformGradleProperties(gradleProperties, dryRun)
                changes.addAll(result.first)
                errors.addAll(result.second)
            } catch (e: Exception) {
                errors.add("Failed to transform gradle.properties: ${e.message}")
            }
        }

        return PassResult(name, changes, errors)
    }

    private fun transformBuildGradle(
        file: Path, dryRun: Boolean
    ): Pair<List<Change>, List<String>> {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        var content = file.readText()
        val original = content

        // 1. Handle buildscript { } + apply plugin pattern (old-style ForgeGradle)
        val hasBuildscript = content.contains("buildscript")
        val hasApplyForgeGradle = Regex("""apply\s+plugin:\s*['"]net\.minecraftforge\.gradle['"]""").containsMatchIn(content)
        val hasPluginsBlock = Regex("""^plugins\s*\{""", RegexOption.MULTILINE).containsMatchIn(content)

        if (hasBuildscript && hasApplyForgeGradle && !hasPluginsBlock) {
            // Old-style build: remove buildscript block, remove apply plugin lines, add plugins block
            content = migrateOldStyleBuild(content, file, changes)
        } else {
            // Modern plugins { } style: just replace the plugin ID
            val forgeGradlePatterns = listOf(
                Regex("""id\s*\(?\s*['"]net\.minecraftforge\.gradle['"].*?\)?\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s+['"]net\.minecraftforge\.gradle['"].*"""),
                Regex("""apply\s+plugin:\s*['"]net\.minecraftforge\.gradle['"]"""),
                Regex("""id\s+['"]net\.neoforged\.moddev\.legacyforge['"]\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s*\(\s*['"]net\.neoforged\.moddev\.legacyforge['"]\s*\)\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s+['"]net\.neoforged\.gradle['"]\s*version\s*['"][^'"]+['"]"""),
                Regex("""id\s*\(\s*['"]net\.neoforged\.gradle['"]\s*\)\s*version\s*['"][^'"]+['"]"""),
            )
            for (pattern in forgeGradlePatterns) {
                if (pattern.containsMatchIn(content)) {
                    val match = pattern.find(content)!!
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Replace ForgeGradle/legacyForge plugin with NeoForge ModDev",
                        before = match.value,
                        after = "id(\"net.neoforged.moddev\") version \"2.0.140\"",
                        confidence = Confidence.HIGH,
                        ruleId = "build-plugin"
                    ))
                    content = content.replace(match.value, "id(\"net.neoforged.moddev\") version \"2.0.140\"")
                }
            }

            // Remove Forge-specific plugins that are incompatible with NeoForge ModDev
            val forgeOnlyPlugins = listOf(
                Regex("""^\s*id\s+['"]org\.parchmentmc\.librarian\.forgegradle['"]\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s*\(\s*['"]org\.parchmentmc\.librarian\.forgegradle['"]\s*\)\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s+['"]org\.spongepowered\.mixin['"]\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s*\(\s*['"]org\.spongepowered\.mixin['"]\s*\)\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s+['"]net\.neoforged\.gradle['"]\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
                Regex("""^\s*id\s*\(\s*['"]net\.neoforged\.gradle['"]\s*\)\s*version\s*['"][^'"]+['"]\s*$""", RegexOption.MULTILINE),
            )
            for (pluginPattern in forgeOnlyPlugins) {
                val match = pluginPattern.find(content)
                if (match != null) {
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Remove Forge-specific plugin: ${match.value.trim()}",
                        before = match.value.trim(),
                        after = "// Removed: handled by NeoForge ModDev",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-forge-plugin"
                    ))
                    content = content.replace(match.value, "")
                }
            }
        }

        // 2. Replace Forge dependency with neoForge block
        val forgeDependencyPatterns = listOf(
            Regex("""minecraft[^\S\r\n]*\(?[^\S\r\n]*['"]net\.minecraftforge:forge:[^'"]+['"][^\S\r\n]*\)?"""),
            Regex("""implementation\s+['"]net\.minecraftforge:forge:[^'"]+['"]"""),
            Regex("""implementation[^\S\r\n]*\([^\S\r\n]*['"]net\.minecraftforge:forge:[^'"]+['"][^\S\r\n]*\)"""),
        )
        for (pattern in forgeDependencyPatterns) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Replace Forge dependency with NeoForge configuration",
                    before = match.value,
                    after = "// NeoForge dependency is now configured via neoForge { } block",
                    confidence = Confidence.HIGH,
                    ruleId = "build-dependency"
                ))
                content = content.replace(match.value,
                    "// NeoForge dependency is now configured via neoForge { } block")
            }
        }

        // 3. Replace mappings channel configuration
        val mappingsPatterns = listOf(
            Regex("""mappings\s*\{[^}]*\}""", RegexOption.DOT_MATCHES_ALL),
            Regex("""mappings\s+channel:\s*['"][^'"]+['"],\s*version:\s*['"][^'"]+['"]"""),
        )
        for (pattern in mappingsPatterns) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Remove old mappings config (now handled by neoForge block)",
                    before = match.value,
                    after = "// Mappings now configured in neoForge { parchment { } }",
                    confidence = Confidence.HIGH,
                    ruleId = "build-mappings"
                ))
                content = content.replace(match.value,
                    "// Mappings now configured in neoForge { parchment { } }")
            }
        }

        // 4. Replace minecraft { } or legacyForge { } block with neoForge { }
        // Use a brace-counting approach to find the full block
        for (blockName in listOf("legacyForge", "minecraft")) {
            val blockStart = Regex("""(?:^|\n)\s*$blockName\s*\{""").find(content)
            if (blockStart != null) {
                val braceStart = content.indexOf('{', blockStart.range.first)
                val blockEnd = findMatchingBrace(content, braceStart)
                if (blockEnd > braceStart) {
                    val fullBlock = content.substring(blockStart.range.first, blockEnd + 1).trimStart('\n')
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(blockStart.range.first),
                        description = "Replace $blockName { } block with neoForge { } configuration",
                        before = fullBlock.take(80) + "...",
                        after = "neoForge { version = \"21.1.+\" ... }",
                        confidence = Confidence.HIGH,
                        ruleId = "build-minecraft-block"
                    ))
                    content = content.substring(0, blockStart.range.first) + "\n" + NEOFORGE_BLOCK + content.substring(blockEnd + 1)
                    break  // Only replace one block
                }
            }
        }

        // 4b. Remove legacyForge-specific blocks that don't exist in moddev
        for (removableBlock in listOf("obfuscation", "mixin")) {
            val blockStart = Regex("""(?:^|\n)\s*$removableBlock\s*\{""").find(content)
            if (blockStart != null) {
                val braceStart = content.indexOf('{', blockStart.range.first)
                val blockEnd = findMatchingBrace(content, braceStart)
                if (blockEnd > braceStart) {
                    val fullBlock = content.substring(blockStart.range.first, blockEnd + 1).trimStart('\n')
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(blockStart.range.first),
                        description = "Remove $removableBlock { } block (legacyForge-specific)",
                        before = fullBlock.take(60) + "...",
                        after = "// Removed: $removableBlock block not needed with moddev",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-legacy-block"
                    ))
                    content = content.substring(0, blockStart.range.first) + "\n// Removed: $removableBlock block not needed with moddev" + content.substring(blockEnd + 1)
                }
            }
        }

        // 4c. Replace legacyForge references in other parts of build.gradle
        content = content.replace("legacyForge.", "neoForge.")
        content = content.replace("legacyForge {", "neoForge {")

        // 5. Replace maven repository URLs
        val forgeRepoPatterns = listOf(
            Pair(
                Regex("""maven\s*\{\s*url\s*=?\s*['"]https?://maven\.minecraftforge\.net/?['"].*?\}""",
                    RegexOption.DOT_MATCHES_ALL),
                """maven { url = "https://maven.neoforged.net/releases" }"""
            ),
            Pair(
                Regex("""maven\s*\{\s*url\s*['"]https?://maven\.minecraftforge\.net/?['"].*?\}""",
                    RegexOption.DOT_MATCHES_ALL),
                """maven { url = "https://maven.neoforged.net/releases" }"""
            ),
        )
        for ((pattern, replacement) in forgeRepoPatterns) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Replace Forge Maven URL with NeoForge Maven",
                    before = match.value,
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "build-maven-url"
                ))
                content = content.replace(match.value, replacement)
            }
        }

        // Also do simple string replacements for inline maven URLs
        if (content.contains("maven.minecraftforge.net")) {
            changes.add(Change(
                file = file, line = 0,
                description = "Replace remaining Forge Maven URL references",
                before = "maven.minecraftforge.net",
                after = "maven.neoforged.net/releases",
                confidence = Confidence.HIGH,
                ruleId = "build-maven-url-inline"
            ))
            content = content.replace("maven.minecraftforge.net", "maven.neoforged.net/releases")
        }

        // 6. Update Java toolchain from 17 to 21
        val java17Pattern = Regex("""JavaLanguageVersion\.of\s*\(\s*17\s*\)""")
        if (java17Pattern.containsMatchIn(content)) {
            changes.add(Change(
                file = file, line = 0,
                description = "Update Java toolchain from 17 to 21 (NeoForge 1.21.1 requires Java 21)",
                before = "JavaLanguageVersion.of(17)",
                after = "JavaLanguageVersion.of(21)",
                confidence = Confidence.HIGH,
                ruleId = "build-java-version"
            ))
            content = java17Pattern.replace(content, "JavaLanguageVersion.of(21)")
        }

        // 7. Replace forge version property references (careful not to double-prefix)
        // Handle forge_version_range -> neoforge_version_range first (more specific)
        content = content.replace("forge_version_range", "neoforge_version_range")
        // Then forge_version -> neo_forge_version (avoid matching neo_forge_version again)
        content = content.replace(Regex("""(?<!neo_)\bforge_version\b"""), "neo_forge_version")
        content = content.replace(Regex("""\bforgeVersion\b"""), "neoForgeVersion")
        // Also replace property key neoforge_version -> neo_forge_version in maps/references
        content = content.replace("neoforge_version            : neoforge_version", "neo_forge_version            : neo_forge_version")

        // 8. Remove buildscript { } block if still present (hybrid builds have both buildscript and plugins)
        val buildscriptMatch = Regex("""(?:^|\n)\s*buildscript\s*\{""").find(content)
        if (buildscriptMatch != null) {
            val braceStart = content.indexOf('{', buildscriptMatch.range.first)
            val blockEnd = findMatchingBrace(content, braceStart)
            if (blockEnd > braceStart) {
                changes.add(Change(
                    file = file, line = content.lineNumberAt(buildscriptMatch.range.first),
                    description = "Remove buildscript { } block (not needed with plugins { } block)",
                    before = "buildscript { ... }",
                    after = "// Removed",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-buildscript"
                ))
                content = content.substring(0, buildscriptMatch.range.first) + content.substring(blockEnd + 1)
            }
        }

        // 9. Handle map-style Forge dependency: minecraft([ group: "net.minecraftforge", ... ])
        val mcDepMap = Regex("""\bminecraft\s*\(\s*\[""").find(content)
        if (mcDepMap != null) {
            val bracketStart = content.indexOf('[', mcDepMap.range.first)
            val bracketEnd = findClosing(content, bracketStart, '[', ']')
            if (bracketEnd > bracketStart) {
                val closeParen = content.indexOf(')', bracketEnd + 1)
                if (closeParen > bracketEnd) {
                    val fullMatch = content.substring(mcDepMap.range.first, closeParen + 1)
                    if (fullMatch.contains("net.minecraftforge")) {
                        changes.add(Change(
                            file = file, line = content.lineNumberAt(mcDepMap.range.first),
                            description = "Replace map-style Forge dependency",
                            before = fullMatch.take(60) + "...",
                            after = "// NeoForge dependency is now configured via neoForge { } block",
                            confidence = Confidence.HIGH,
                            ruleId = "build-dependency-map"
                        ))
                        content = content.replace(fullMatch, "// NeoForge dependency is now configured via neoForge { } block")
                    }
                }
            }
        }

        // 10. Remove fg.deobf() wrappers (handle both single-line and multi-line)
        var fgMatch = Regex("""fg\.deobf\(""").find(content)
        while (fgMatch != null) {
            val openParen = fgMatch.range.last
            val closeParen = findClosing(content, openParen, '(', ')')
            if (closeParen > openParen) {
                val inner = content.substring(openParen + 1, closeParen)
                content = content.substring(0, fgMatch.range.first) + inner + content.substring(closeParen + 1)
            } else break
            fgMatch = Regex("""fg\.deobf\(""").find(content)
        }

        // 10b. Resolve third-party dependencies: check for NeoForge 1.21.1 versions
        val resolver = DependencyResolver(offlineMode = offlineMode, mappingsPrefix = mappingsPrefix)
        val resolvedPrefixes = mutableSetOf<String>()
        val newMavenRepos = mutableSetOf<String>()
        content = resolveDependencies(content, resolver, resolvedPrefixes, newMavenRepos, changes, file)

        // 10c. Add maven repositories for resolved dependencies
        if (newMavenRepos.isNotEmpty()) {
            content = addMavenRepositories(content, newMavenRepos, changes, file)
        }

        // 11. Comment out dependencies referencing old MC version that won't resolve
        content = commentOutOldDeps(content, resolvedPrefixes)

        // 12. Exclude integration source packages with unavailable dependencies
        if (!content.contains("sourceSets.main.java {")) {
            val integrationExclusions = detectUnavailableIntegrations(file.parent, content)
            if (integrationExclusions.isNotEmpty()) {
                val exclusionBlock = buildString {
                    append("\n// Exclude optional integration modules whose dependencies are not yet available for NeoForge 1.21\n")
                    append("sourceSets.main.java {\n")
                    for (pkg in integrationExclusions) {
                        append("    exclude '${pkg}'\n")
                    }
                    append("}\n")
                }
                val insertPoint = content.indexOf("dependencies {")
                if (insertPoint > 0) {
                    content = content.substring(0, insertPoint) + exclusionBlock + "\n" + content.substring(insertPoint)
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(insertPoint),
                        description = "Exclude integration packages: ${integrationExclusions.joinToString(", ")}",
                        before = "(no exclusions)",
                        after = "sourceSets.main.java { exclude ... }",
                        confidence = Confidence.HIGH,
                        ruleId = "build-exclude-integrations"
                    ))
                }
            }
        }

        // 13. Remove reobfJar references and related comments
        content = content.replace(Regex("""^.*[Rr]eobf.*\n?""", RegexOption.MULTILINE), "")

        // 14. Replace old property references in build.gradle body
        content = content.replace(Regex("""\bmcversion\b"""), "minecraft_version")
        content = content.replace(Regex("""\bmcVersion\b"""), "minecraft_version")
        content = content.replace(Regex("""(?<!neo_)\bforgeversion\b"""), "neo_forge_version")

        // 15. Remove MixinConfigs from jar manifest
        content = content.replace(Regex("""^\s*"MixinConfigs"\s*:.*$""", RegexOption.MULTILINE), "")

        // 16. Deduplicate sourceSets.main.resources blocks
        val srcSetPattern = Regex("""sourceSets\.main\.resources\s*\{[^}]*\}\s*\n?""")
        val srcSetMatches = srcSetPattern.findAll(content).toList()
        if (srcSetMatches.size > 1) {
            // Keep only the first occurrence
            for (m in srcSetMatches.drop(1).reversed()) {
                content = content.removeRange(m.range)
            }
        }

        // 17. Update processResources filesMatching to use neoforge.mods.toml
        val modsTomlPattern = Regex("""META-INF/mods\.toml""")
        if (modsTomlPattern.containsMatchIn(content)) {
            changes.add(Change(
                file = file, line = content.lineNumberAt(modsTomlPattern.find(content)!!.range.first),
                description = "Update filesMatching to use neoforge.mods.toml",
                before = "META-INF/mods.toml",
                after = "META-INF/neoforge.mods.toml",
                confidence = Confidence.HIGH,
                ruleId = "build-mods-toml-reference"
            ))
            content = modsTomlPattern.replace(content, "META-INF/neoforge.mods.toml")
        }

        // 18. Clean up excessive blank lines
        content = content.replace(Regex("""\n{3,}"""), "\n\n")

        if (content != original && !dryRun) {
            file.writeText(content)
        }

        return changes to errors
    }

    private fun transformSettingsGradle(
        file: Path, dryRun: Boolean
    ): Pair<List<Change>, List<String>> {
        val changes = mutableListOf<Change>()
        var content = file.readText()
        val original = content

        // Replace plugin repository for ForgeGradle
        val forgeGradleRepo = Regex(
            """maven\s*\{\s*url\s*=?\s*['"]https?://maven\.minecraftforge\.net/?['"].*?\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        if (forgeGradleRepo.containsMatchIn(content)) {
            val match = forgeGradleRepo.find(content)!!
            changes.add(Change(
                file = file, line = content.lineNumberAt(match.range.first),
                description = "Replace ForgeGradle plugin repo with NeoForge",
                before = match.value,
                after = """maven { url = "https://maven.neoforged.net/releases" }""",
                confidence = Confidence.HIGH,
                ruleId = "build-settings-repo"
            ))
            content = content.replace(match.value,
                """maven { url = "https://maven.neoforged.net/releases" }""")
        }

        // Replace ForgeGradle plugin marker
        if (content.contains("net.minecraftforge.gradle")) {
            changes.add(Change(
                file = file, line = 0,
                description = "Replace ForgeGradle plugin ID in settings",
                before = "net.minecraftforge.gradle",
                after = "net.neoforged.moddev",
                confidence = Confidence.HIGH,
                ruleId = "build-settings-plugin"
            ))
            content = content.replace("net.minecraftforge.gradle", "net.neoforged.moddev")
        }

        if (content.contains("maven.minecraftforge.net")) {
            content = content.replace("maven.minecraftforge.net", "maven.neoforged.net/releases")
            changes.add(Change(
                file = file, line = 0,
                description = "Replace Forge Maven URL in settings",
                before = "maven.minecraftforge.net",
                after = "maven.neoforged.net/releases",
                confidence = Confidence.HIGH,
                ruleId = "build-settings-maven"
            ))
        }

        if (content != original && !dryRun) {
            file.writeText(content)
        }

        return changes to emptyList()
    }

    private fun transformGradleProperties(
        file: Path, dryRun: Boolean
    ): Pair<List<Change>, List<String>> {
        val changes = mutableListOf<Change>()
        var content = file.readText()
        val original = content

        // Replace forge version property (various naming conventions)
        var foundForgeVersion = false
        for (pattern in listOf(
            Regex("""forge_version\s*=\s*.+"""),
            Regex("""neo_forge_version\s*=\s*.+"""),
            Regex("""neoforge_version\s*=\s*.+"""),
            Regex("""forgeversion\s*=\s*.+"""),
            Regex("""forgeVersion\s*=\s*.+"""),
        )) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Replace forge/neoforge version with neo_forge_version=21.1.219",
                    before = match.value,
                    after = "neo_forge_version=21.1.219",
                    confidence = Confidence.HIGH,
                    ruleId = "build-props-version"
                ))
                content = content.replace(match.value, "neo_forge_version=21.1.219")
                foundForgeVersion = true
                break  // Only replace first match
            }
        }
        // Ensure neo_forge_version exists even if no forge version property was found
        if (!foundForgeVersion && !content.contains("neo_forge_version")) {
            content += "\n# Added by modporter\nneo_forge_version=21.1.219\n"
            changes.add(Change(
                file = file, line = content.lines().size,
                description = "Add neo_forge_version property (required by neoForge block)",
                before = "(missing)",
                after = "neo_forge_version=21.1.219",
                confidence = Confidence.HIGH,
                ruleId = "build-props-version-add"
            ))
        }

        // Replace Minecraft version (handles various naming conventions)
        var foundMcVersion = false
        for (mcProp in listOf(
            Regex("""minecraft_version\s*=\s*1\.20\.\d+"""),
            Regex("""mc_version\s*=\s*1\.20\.\d+"""),
            Regex("""mcversion\s*=\s*1\.20\.\d+"""),
            Regex("""mcVersion\s*=\s*1\.20\.\d+"""),
        )) {
            if (mcProp.containsMatchIn(content)) {
                val match = mcProp.find(content)!!
                val propName = match.value.substringBefore("=").trim()
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Update Minecraft version to 1.21.1",
                    before = match.value,
                    after = "minecraft_version=1.21.1",
                    confidence = Confidence.HIGH,
                    ruleId = "build-props-mc-version"
                ))
                // Normalize the property name to minecraft_version
                content = content.replace(match.value, "minecraft_version=1.21.1")
                foundMcVersion = true
                break
            }
        }
        // Ensure minecraft_version exists
        if (!foundMcVersion && !content.contains("minecraft_version")) {
            content += "minecraft_version=1.21.1\n"
            changes.add(Change(
                file = file, line = content.lines().size,
                description = "Add minecraft_version property",
                before = "(missing)",
                after = "minecraft_version=1.21.1",
                confidence = Confidence.HIGH,
                ruleId = "build-props-mc-version-add"
            ))
        }

        // Update version ranges
        val rangeReplacements = listOf(
            Regex("""minecraft_version_range\s*=\s*.+""") to "minecraft_version_range=[1.21.1,1.22)",
            Regex("""forge_version_range\s*=\s*.+""") to "neoforge_version_range=[21.1,)",
            Regex("""neoforge_version_range\s*=\s*\[47[^)\r\n]*\)""") to "neoforge_version_range=[21.1,)",
            Regex("""loader_version_range\s*=\s*.+""") to "loader_version_range=[1,)",
        )
        for ((pattern, replacement) in rangeReplacements) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                if (match.value.trim() != replacement) {
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Update version range: ${match.value.trim()} -> $replacement",
                        before = match.value.trim(),
                        after = replacement,
                        confidence = Confidence.HIGH,
                        ruleId = "build-props-range"
                    ))
                    content = content.replace(match.value, replacement)
                }
            }
        }

        // Update parchment mappings version
        val parchmentMappings = Regex("""parchment_mappings_version\s*=\s*.+""")
        if (parchmentMappings.containsMatchIn(content)) {
            val match = parchmentMappings.find(content)!!
            content = content.replace(match.value, "parchment_mappings_version=2024.11.17")
        }
        val parchmentMc = Regex("""parchment_minecraft_version\s*=\s*.+""")
        if (parchmentMc.containsMatchIn(content)) {
            val match = parchmentMc.find(content)!!
            content = content.replace(match.value, "parchment_minecraft_version=1.21.1")
        }

        // Replace forge_group or similar
        if (content.contains("net.minecraftforge")) {
            changes.add(Change(
                file = file, line = 0,
                description = "Replace Forge references in gradle.properties",
                before = "net.minecraftforge",
                after = "net.neoforged",
                confidence = Confidence.MEDIUM,
                ruleId = "build-props-forge-ref"
            ))
            content = content.replace("net.minecraftforge", "net.neoforged")
        }

        // Add mod_id property if missing (needed by neoForge { mods { } } block)
        // Also check for "modid" (no underscore) and remap it to "mod_id"
        if (!content.contains("mod_id=") && !content.contains("mod_id =")) {
            val modIdFromProps = Regex("""^modid\s*=\s*(\S+)""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)
            val modId = modIdFromProps ?: detectModId(file.parent)
            if (modId != null) {
                content += "\n# Added by modporter\nmod_id=$modId\n"
                changes.add(Change(
                    file = file, line = content.lines().size,
                    description = "Add mod_id property for NeoForge configuration",
                    before = "(missing)",
                    after = "mod_id=$modId",
                    confidence = Confidence.HIGH,
                    ruleId = "build-props-mod-id"
                ))
            }
        }

        if (content != original && !dryRun) {
            file.writeText(content)
        }

        return changes to emptyList()
    }

    /**
     * Detect mod ID from @Mod annotation in Java source files.
     */
    private fun detectModId(projectDir: Path): String? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null
        try {
            val javaFiles = java.nio.file.Files.walk(srcDir)
                .filter { it.toString().endsWith(".java") }
                .toList()
            for (file in javaFiles) {
                val text = file.toFile().readText()
                // Match @Mod("modid") directly
                val directMatch = Regex("""@Mod\s*\(\s*"(\w+)"\s*\)""").find(text)
                if (directMatch != null) return directMatch.groupValues[1]
                // Match @Mod(ClassName.CONST) and resolve the constant
                val constRef = Regex("""@Mod\s*\(\s*(\w+)\.(\w+)\s*\)""").find(text)
                if (constRef != null) {
                    val constName = constRef.groupValues[2]
                    val constVal = Regex("$constName\\s*=\\s*\"(\\w+)\"").find(text)
                    if (constVal != null) return constVal.groupValues[1]
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Migrate old-style buildscript { } + apply plugin: builds to modern plugins { } style.
     * This handles the pattern used by many Forge mods that pre-date the MDK 1.20.1 template.
     */
    private fun migrateOldStyleBuild(
        input: String, file: Path, changes: MutableList<Change>
    ): String {
        var content = input

        // 1. Remove the buildscript { } block
        val buildscriptStart = Regex("""(?:^|\n)\s*buildscript\s*\{""").find(content)
        if (buildscriptStart != null) {
            val braceStart = content.indexOf('{', buildscriptStart.range.first)
            val blockEnd = findMatchingBrace(content, braceStart)
            if (blockEnd > braceStart) {
                changes.add(Change(
                    file = file, line = content.lineNumberAt(buildscriptStart.range.first),
                    description = "Remove buildscript { } block (ForgeGradle classpath deps no longer needed)",
                    before = "buildscript { ... }",
                    after = "// Removed: buildscript block replaced by plugins { }",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-buildscript"
                ))
                content = content.substring(0, buildscriptStart.range.first) + content.substring(blockEnd + 1)
            }
        }

        // 2. Remove apply plugin: lines for forge-related plugins
        val applyPluginPatterns = listOf(
            Regex("""^\s*apply\s+plugin:\s*['"]net\.minecraftforge\.gradle['"]\s*$""", RegexOption.MULTILINE),
            Regex("""^\s*apply\s+plugin:\s*['"]org\.parchmentmc\.librarian\.forgegradle['"]\s*$""", RegexOption.MULTILINE),
            Regex("""^\s*apply\s+plugin:\s*['"]org\.spongepowered\.mixin['"]\s*$""", RegexOption.MULTILINE),
        )
        for (pattern in applyPluginPatterns) {
            if (pattern.containsMatchIn(content)) {
                content = pattern.replace(content, "")
            }
        }

        // 3. Remove mixin { } block (NeoForge has built-in mixin support)
        val mixinBlock = Regex("""(?:^|\n)\s*mixin\s*\{[^}]*\}""").find(content)
        if (mixinBlock != null) {
            content = content.replace(mixinBlock.value, "")
        }

        // 4. Add plugins { } block at the top, with Java 21 toolchain
        val pluginsBlock = """plugins {
    id 'java-library'
    id 'eclipse'
    id 'maven-publish'
    id("net.neoforged.moddev") version "2.0.140"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
"""
        changes.add(Change(
            file = file, line = 1,
            description = "Add modern plugins { } block with NeoForge ModDev",
            before = "buildscript { ... } + apply plugin: ...",
            after = "plugins { id(\"net.neoforged.moddev\") ... }",
            confidence = Confidence.HIGH,
            ruleId = "build-add-plugins-block"
        ))
        content = pluginsBlock + content.trimStart()

        // 5. Remove remaining apply plugin lines that are already in plugins { }
        for (pluginId in listOf("eclipse", "maven-publish", "java-library", "java")) {
            content = content.replace(Regex("""^\s*apply\s+plugin:\s*['"]$pluginId['"]\s*$""", RegexOption.MULTILINE), "")
        }

        // Steps 5b-8 (fg.deobf, dep commenting, reobfJar, cleanup) are now handled
        // by shared cleanup in transformBuildGradle() to cover both old-style and hybrid builds.

        return content
    }

    /**
     * Resolve third-party dependencies: find NeoForge 1.21.1 versions and rewrite coordinates.
     * Dependencies that can be resolved are rewritten in-place; unresolvable ones are left for commentOutOldDeps.
     */
    private fun resolveDependencies(
        content: String,
        resolver: DependencyResolver,
        resolvedPrefixes: MutableSet<String>,
        newMavenRepos: MutableSet<String>,
        changes: MutableList<Change>,
        file: Path
    ): String {
        val depKeywords = listOf("compileOnly", "runtimeOnly", "implementation", "annotationProcessor", "def ")
        val lines = content.lines().toMutableList()
        val emittedCoords = mutableSetOf<String>() // Track already-emitted NeoForge coords to avoid duplicates
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || depKeywords.none { trimmed.startsWith(it) }) {
                i++
                continue
            }
            // Accumulate multi-line dependency
            val blockStart = i
            var depth = 0
            var j = i
            do {
                for (ch in lines[j]) {
                    when (ch) { '(', '[' -> depth++; ')', ']' -> depth-- }
                }
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")

            // Try to resolve this dependency
            val resolution = resolver.resolve(blockText)
            if (resolution is DepResolution.Resolved) {
                // Skip if already resolved (idempotency: dep already contains a NeoForge coord)
                val alreadyResolved = resolution.coords.any { blockText.contains(it.coord) }
                if (alreadyResolved) {
                    resolvedPrefixes.addAll(resolution.coords.map { it.coord.substringBefore(":") })
                    emittedCoords.addAll(resolution.coords.map { it.coord })
                    i = j
                    continue
                }

                // Filter out coords already emitted (e.g., multiple JEI deps → single resolved set)
                val newCoords = resolution.coords.filter { it.coord !in emittedCoords }

                val indent = lines[blockStart].takeWhile { it == ' ' || it == '\t' }
                if (newCoords.isNotEmpty()) {
                    // Replace the entire dep block with resolved NeoForge coordinates
                    val replacementLines = newCoords.map { coord ->
                        val transitiveSuffix = if (!coord.transitive) " { isTransitive = false }" else ""
                        "${indent}${coord.config} \"${coord.coord}\"$transitiveSuffix"
                    }
                    for (k in (j - 1) downTo blockStart) lines.removeAt(k)
                    for ((idx, line) in replacementLines.withIndex()) {
                        lines.add(blockStart + idx, line)
                    }
                    changes.add(Change(
                        file = file, line = blockStart + 1,
                        description = "Resolved dependency to NeoForge 1.21.1: ${resolution.notes}",
                        before = blockText.trim(),
                        after = replacementLines.joinToString("\n").trim(),
                        confidence = Confidence.HIGH,
                        ruleId = "build-resolve-dep"
                    ))
                    i = blockStart + replacementLines.size
                } else {
                    // All coords already emitted — just remove the duplicate Forge dep
                    for (k in (j - 1) downTo blockStart) lines.removeAt(k)
                    i = blockStart
                }

                // Track what was resolved
                resolvedPrefixes.addAll(resolution.coords.map { it.coord.substringBefore(":") })
                emittedCoords.addAll(resolution.coords.map { it.coord })
                resolution.mavenUrl?.let { newMavenRepos.add(it) }
            } else {
                i = j
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Add maven repositories for resolved NeoForge dependencies.
     */
    private fun addMavenRepositories(
        content: String,
        repos: Set<String>,
        changes: MutableList<Change>,
        file: Path
    ): String {
        var result = content
        val repoBlock = Regex("""repositories\s*\{""")
        val repoMatch = repoBlock.find(result)
        if (repoMatch != null) {
            val insertPos = repoMatch.range.last + 1
            val newRepoLines = repos
                .filter { url -> !result.contains(url) }
                .joinToString("\n") { url ->
                    val repoName = when {
                        url.contains("modrinth") -> "\n    maven {\n        name = \"Modrinth\"\n        url = \"$url\"\n        content { includeGroup \"maven.modrinth\" }\n    }"
                        else -> "\n    maven { url = \"$url\" }"
                    }
                    repoName
                }
            if (newRepoLines.isNotBlank()) {
                result = result.substring(0, insertPos) + newRepoLines + result.substring(insertPos)
                changes.add(Change(
                    file = file, line = result.lineNumberAt(insertPos),
                    description = "Add maven repositories for resolved NeoForge dependencies",
                    before = "(no additional repos)",
                    after = repos.joinToString(", "),
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-maven-repos"
                ))
            }
        }
        return result
    }

    /**
     * Comment out dependency declarations that reference old MC version libraries.
     * Handles both single-line and multi-line (map-style) dependency declarations.
     */
    private fun commentOutOldDeps(content: String, skipPrefixes: Set<String> = emptySet()): String {
        val allDepPrefixes = listOf("mezz.jei:", "squeek.appleskin:", "com.blamejared.crafttweaker:",
            "Crafttweaker_Annotation_Processors", "org.spongepowered:mixin:", "vazkii.botania",
            "org.valkyrienskies", "maven.modrinth:", "curse.maven:", "top.theillusivec4:",
            "com.github.", "de.ellpeck.", "mcjty.", "com.tterrag.",
            "com.simibubi.create", "net.createmod.ponder", "dev.engine-room.flywheel",
            "io.github.llamalad7:mixinextras")
        // Filter out prefixes that were already resolved to NeoForge versions
        val depPrefixes = allDepPrefixes.filter { prefix ->
            skipPrefixes.none { skip -> prefix.startsWith(skip) || skip.startsWith(prefix.trimEnd(':')) }
        }
        val depKeywords = listOf("compileOnly", "runtimeOnly", "implementation", "annotationProcessor", "def ")

        val lines = content.lines().toMutableList()
        val commentedVars = mutableSetOf<String>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || depKeywords.none { trimmed.startsWith(it) }) {
                i++
                continue
            }
            // Accumulate multi-line dependency (track bracket/paren depth)
            val blockStart = i
            var depth = 0
            var j = i
            do {
                for (ch in lines[j]) {
                    when (ch) { '(', '[' -> depth++; ')', ']' -> depth-- }
                }
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")
            if (depPrefixes.any { blockText.contains(it) }) {
                val defMatch = Regex("""def\s+(\w+)\s*=""").find(lines[blockStart].trim())
                if (defMatch != null) commentedVars.add(defMatch.groupValues[1])
                for (k in blockStart until j) {
                    lines[k] = "    // TODO: Update for NeoForge 1.21.1 — ${lines[k].trim()}"
                }
            }
            i = j
        }
        // Second pass: comment out lines referencing variables from commented def lines
        if (commentedVars.isNotEmpty()) {
            for (idx in lines.indices) {
                val trimmed = lines[idx].trim()
                if (!trimmed.startsWith("//") && commentedVars.any { v -> Regex("""\b$v\b""").containsMatchIn(trimmed) } &&
                    depKeywords.any { trimmed.startsWith(it) }) {
                    lines[idx] = "    // TODO: Update for NeoForge 1.21.1 — ${lines[idx].trim()}"
                }
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Detect integration source packages that depend on unavailable external libraries.
     * Scans src/main/java for "integration" subdirectories whose dependencies were commented out.
     * Returns package paths suitable for sourceSets exclude (e.g., "com/example/integration/jei").
     */
    private fun detectUnavailableIntegrations(projectDir: Path, buildContent: String = ""): List<String> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        // Map of dependency group prefixes to their expected integration package names
        val depToIntegration = mapOf(
            "mezz.jei" to "jei",
            "com.blamejared.crafttweaker" to "crafttweaker",
            "moze_intel.projecte" to "projecte",
            "com.refinedmods" to "refinedstorage",
            "appeng" to "ae2",
            "mekanism" to "mekanism",
            "top.theillusivec4.curios" to "curios",
            "vazkii.botania" to "botania",
            "org.valkyrienskies" to "valkyrienskies",
            "maven.modrinth:valkyrien" to "valkyrienskies",
        )

        // Map of dependency identifiers to their Java import package prefixes
        val depImportPrefixes = mapOf(
            "vazkii.botania" to "vazkii.botania",
            "mezz.jei" to "mezz.jei",
            "org.valkyrienskies" to "org.valkyrienskies",
            "maven.modrinth:valkyrien" to "org.valkyrienskies",
            "top.theillusivec4:" to "top.theillusivec4",
            "com.github." to "com.github.",
            "curse.maven:playeranimator" to "dev.kosmx.playerAnim",
            "curse.maven:scorched-guns" to "top.ribs.scguns",
            "curse.maven:geckolib" to "software.bernie.geckolib",
            "curse.maven:framework" to "net.minecraftforge.fml",
            "curse.maven:curios" to "top.theillusivec4.curios",
            "com.simibubi.create" to "com.simibubi.create",
            "net.createmod.ponder" to "net.createmod.ponder",
            "dev.engine-room.flywheel" to "dev.engine_room.flywheel",
        )

        // Use in-memory content if provided, otherwise read from disk
        val effectiveContent = if (buildContent.isNotEmpty()) buildContent else {
            val buildGradle = projectDir.resolve("build.gradle")
            if (buildGradle.exists()) buildGradle.readText() else ""
        }

        val allKnownDeps = (depToIntegration.keys + depImportPrefixes.keys).distinct()
        val commentedDeps = allKnownDeps.filter { dep ->
            // Check if the dependency line is commented out (starts with //)
            effectiveContent.lines().any { line ->
                val trimmed = line.trim()
                trimmed.startsWith("//") && trimmed.contains(dep)
            }
        }

        // Find actual integration directories in the source tree
        val exclusions = mutableListOf<String>()
        try {
            // Check both "integration", "integrations", and "compat" directory names
            if (commentedDeps.isNotEmpty()) java.nio.file.Files.walk(srcDir)
                .filter { java.nio.file.Files.isDirectory(it) &&
                    (it.fileName.toString() == "integration" || it.fileName.toString() == "integrations" || it.fileName.toString() == "compat") }
                .forEach { integrationDir ->
                    for (dep in commentedDeps) {
                        val integrationName = depToIntegration[dep] ?: continue
                        val subDir = integrationDir.resolve(integrationName)
                        if (subDir.exists() && java.nio.file.Files.isDirectory(subDir)) {
                            val relativePath = srcDir.relativize(subDir).toString().replace('\\', '/')
                            exclusions.add("$relativePath/**")
                        }
                    }
                }

            // Also find individual files that import commented-out dependency packages
            // (for cases like HandlerBotania.java not in an integration directory)
            if (commentedDeps.isNotEmpty()) {
                for (dep in commentedDeps) {
                    val importPrefix = depImportPrefixes[dep] ?: continue
                    java.nio.file.Files.walk(srcDir)
                        .filter { it.toString().endsWith(".java") }
                        .forEach { javaFile ->
                            val text = javaFile.toFile().readText()
                            // Check for both raw and commented-out imports (text-replacement may have commented them)
                            if (text.contains("import $importPrefix") ||
                                text.contains("// [forge2neo] import $importPrefix")) {
                                val relativePath = srcDir.relativize(javaFile).toString().replace('\\', '/')
                                if (exclusions.any { relativePath == it || relativePath.startsWith(it.removeSuffix("/**") + "/") }) return@forEach
                                // Only exclude small files (< 30 lines) that are clearly integration-only
                                // Larger files get their references cleaned up instead of being excluded
                                val lineCount = text.lines().size
                                val depRefCount = Regex("""\b${Regex.escape(importPrefix.substringAfterLast('.'))}\b""").findAll(text).count()
                                if (lineCount < 50 || depRefCount >= 4) {
                                    exclusions.add(relativePath)
                                }
                            }
                        }
                }
            }

            // Exclude files that use removed/rewritten APIs that can't be fixed by text replacement
            val removedApiPatterns = listOf(
                "Capabilities.ITEM_HANDLER",       // Forge capability API removed
                "LazyOptional<IItemHandler>",       // Capability wrapping removed
                "ICapabilityProvider",              // Capability interface removed
                "ItemStack.of(CompoundTag",         // Changed to codec-based in 1.21
                "ItemStack::of",                    // Method reference variant
                "ContainerHelper.saveAllItems",     // Signature changed in 1.21
                "ContainerHelper.loadAllItems",     // Signature changed in 1.21
                "HandlerCapability",                // References excluded capability handler
            )
            // Also exclude data gen files (they reference changed RecipeProvider/ItemModelProvider APIs
            // and pre-generated resources already exist in src/generated/resources)
            val dataGenPatterns = listOf(
                "extends RecipeProvider",
                "extends ItemModelProvider",
                "extends BlockStateProvider",
                "GatherDataEvent",
            )
            java.nio.file.Files.walk(srcDir)
                .filter { it.toString().endsWith(".java") }
                .forEach { javaFile ->
                    val text = javaFile.toFile().readText()
                    if (removedApiPatterns.any { pattern -> text.contains(pattern) } ||
                        dataGenPatterns.any { pattern -> text.contains(pattern) }) {
                        val relativePath = srcDir.relativize(javaFile).toString().replace('\\', '/')
                        if (exclusions.none { relativePath.startsWith(it.removeSuffix("/**")) }) {
                            exclusions.add(relativePath)
                        }
                    }
                }

            // Cascade: find files that import classes from excluded files
            // This handles interfaces/classes defined in excluded files but referenced by non-excluded files
            var changed = true
            while (changed) {
                changed = false
                // Collect simple class names of all excluded files
                val excludedClassNames = exclusions.mapNotNull { path ->
                    if (path.endsWith("/**")) null
                    else path.substringAfterLast('/').removeSuffix(".java")
                }.toSet()
                if (excludedClassNames.isEmpty()) break

                java.nio.file.Files.walk(srcDir)
                    .filter { it.toString().endsWith(".java") }
                    .forEach { javaFile ->
                        val relativePath = srcDir.relativize(javaFile).toString().replace('\\', '/')
                        if (exclusions.any { relativePath == it || relativePath.startsWith(it.removeSuffix("/**") + "/") }) return@forEach
                        val text = javaFile.toFile().readText()
                        // Check if this file references an excluded class (via import or same-package usage)
                        for (className in excludedClassNames) {
                            val hasImport = text.contains(".$className;")
                            val hasSamePackageRef = text.contains(className)
                            if (hasImport || hasSamePackageRef) {
                                val lineCount = text.lines().size
                                // Count references to determine coupling strength
                                val refCount = Regex("""\b${Regex.escape(className)}\b""").findAll(text).count()
                                // Exclude thin wrappers (< 20 lines) or heavily coupled files (5+ refs)
                                if (lineCount < 20 || refCount >= 5) {
                                    exclusions.add(relativePath)
                                    changed = true
                                    break
                                }
                            }
                        }
                    }
            }
        } catch (_: Exception) {}

        return exclusions
    }

    /**
     * After determining excluded source files, clean up references to those files from remaining code.
     * This handles:
     * - Commenting out imports of excluded classes
     * - Removing 'implements ExcludedInterface' from class declarations
     * - Commenting out method calls to excluded classes (e.g., ExcludedClass.method())
     * - Removing @Override methods that implement excluded interfaces
     */
    private fun cleanupExcludedReferences(projectDir: Path, dryRun: Boolean): Pair<List<Change>, List<String>> {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes to errors

        // Parse exclusions from build.gradle
        val buildGradle = projectDir.resolve("build.gradle")
        val buildContent = if (buildGradle.exists()) buildGradle.readText() else ""
        val excludePattern = Regex("""exclude\s+'([^']+)'""")
        val excludedPaths = excludePattern.findAll(buildContent).map { it.groupValues[1] }.toList()

        // Collect simple class names of excluded files
        val excludedClassNames = excludedPaths.mapNotNull { path ->
            if (path.endsWith("/**")) null
            else path.substringAfterLast('/').removeSuffix(".java")
        }.toMutableSet()

        // Also collect class names from commented-out third-party imports
        // These are classes from unavailable deps that were commented by text-replacement rules
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val text = javaFile.toFile().readText()
                val commentedImportPattern = Regex("""// \[forge2neo\] import [\w.]+\.(\w+);.*(?:re-enable|unavailable)""")
                commentedImportPattern.findAll(text).forEach { match ->
                    excludedClassNames.add(match.groupValues[1])
                }
            }

        if (excludedClassNames.isEmpty()) return changes to errors

        // Scan remaining Java files for references to excluded classes
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val relativePath = srcDir.relativize(javaFile).toString().replace('\\', '/')
                // Skip excluded files themselves
                if (excludedPaths.any { relativePath == it || relativePath.startsWith(it.removeSuffix("/**") + "/") }) return@forEach

                val text = javaFile.toFile().readText()
                var modified = text

                for (className in excludedClassNames) {
                    if (!modified.contains(className)) continue

                    // 1. Comment out import lines for excluded classes
                    modified = modified.replace(
                        Regex("""^(import\s+[\w.]+\.$className\s*;)""", RegexOption.MULTILINE),
                        "// [forge2neo] $1 // excluded"
                    )

                    // 2. Remove 'implements ExcludedInterface' or ', ExcludedInterface' from class declarations
                    // Handle "implements ExcludedClass" (only interface)
                    modified = modified.replace(
                        Regex("""([^\S\r\n]+)implements\s+$className\s*(?=\{)"""),
                        "$1"
                    )
                    // Handle "implements Other, ExcludedClass" or "implements ExcludedClass, Other"
                    modified = modified.replace(
                        Regex(""",\s*$className"""),
                        ""
                    )
                    modified = modified.replace(
                        Regex("""$className\s*,\s*"""),
                        ""
                    )

                    // 3. Comment out standalone method calls: ExcludedClass.method(...)
                    modified = modified.replace(
                        Regex("""^(\s*)($className\.\w+\([^)\r\n]*\)\s*;)""", RegexOption.MULTILINE),
                        "$1// [forge2neo] $2 // excluded"
                    )
                    // Also handle fully-qualified references: com.package.ExcludedClass.method(...)
                    modified = modified.replace(
                        Regex("""^(\s*)([\w.]+\.$className\.\w+\([^)\r\n]*\)\s*;)""", RegexOption.MULTILINE),
                        "$1// [forge2neo] $2 // excluded"
                    )

                    // 3b. Handle if-blocks that contain excluded class references:
                    // Comment out ALL body lines in the if-block (cascading deps make partial commenting unsafe).
                    // If the body had a return and there's an else branch, comment the whole if+else structure
                    // and promote the else-body to unconditional code.
                    val ifBlockPattern = Regex("""^(\s*)if\s*\(.*?\)[^\S\r\n]*\{""", RegexOption.MULTILINE)
                    var ifMatch = ifBlockPattern.find(modified)
                    while (ifMatch != null) {
                        val braceStart = modified.indexOf('{', ifMatch.range.first)
                        if (braceStart < 0) break
                        val braceEnd = findClosing(modified, braceStart, '{', '}')
                        if (braceEnd < 0) break
                        val ifBody = modified.substring(braceStart + 1, braceEnd)
                        val classRef = Regex("""\b${Regex.escape(className)}\b""")
                        if (classRef.containsMatchIn(ifBody)) {
                            val indent = ifMatch.groupValues[1]
                            val bodyHasReturn = ifBody.lines().any { it.trim().startsWith("return ") || it.trim() == "return;" }

                            // Check for else branch after the if-block closing brace
                            val afterIfBlock = modified.substring(braceEnd + 1)
                            val elseMatch = Regex("""^\s*else\s*\{""").find(afterIfBlock)

                            if (bodyHasReturn && elseMatch != null) {
                                // Comment out the entire if + promote the else body
                                val elseBraceStart = braceEnd + 1 + afterIfBlock.indexOf('{', elseMatch.range.first)
                                val elseBraceEnd = findClosing(modified, elseBraceStart, '{', '}')
                                if (elseBraceEnd > elseBraceStart) {
                                    val elseBody = modified.substring(elseBraceStart + 1, elseBraceEnd)
                                    // Comment out the if-condition + body, keep else body as unconditional
                                    val ifCondLine = modified.substring(ifMatch.range.first, braceStart + 1)
                                    val commentedIf = ifCondLine.lines().joinToString("\n") { line ->
                                        val trimmed = line.trimStart()
                                        if (trimmed.isEmpty()) line
                                        else "${line.substringBefore(trimmed)}// [forge2neo] $trimmed // excluded: $className unavailable"
                                    }
                                    val commentedBody = ifBody.lines().joinToString("\n") { line ->
                                        val trimmed = line.trimStart()
                                        if (trimmed.isEmpty() || trimmed.startsWith("//")) line
                                        else "${line.substringBefore(trimmed)}// [forge2neo] $trimmed // excluded: $className unavailable"
                                    }
                                    // Replace: if(...){body} else {elseBody} → commented-if + elseBody (unconditional)
                                    modified = modified.substring(0, ifMatch.range.first) +
                                        commentedIf + commentedBody + "\n${indent}// [forge2neo] } else { // excluded: $className unavailable" +
                                        elseBody + "\n${indent}// [forge2neo] } // excluded: else block promoted to unconditional" +
                                        modified.substring(elseBraceEnd + 1)
                                    ifMatch = ifBlockPattern.find(modified, ifMatch.range.first + 1)
                                    continue
                                }
                            }

                            // Default: just comment out the if-body lines
                            val bodyLines = ifBody.lines()
                            val commentedBody = bodyLines.joinToString("\n") { line ->
                                val trimmed = line.trimStart()
                                if (trimmed.isEmpty() || trimmed.startsWith("//")) line
                                else "${line.substringBefore(trimmed)}// [forge2neo] $trimmed // excluded: $className unavailable"
                            }
                            // If body had a return, add a fallback to prevent compilation errors
                            val fallback = if (bodyHasReturn) {
                                "\n${indent}    // [forge2neo] fallback for excluded return path\n${indent}    return null;"
                            } else ""
                            modified = modified.substring(0, braceStart + 1) + commentedBody + fallback + modified.substring(braceEnd)
                        }
                        ifMatch = ifBlockPattern.find(modified, braceEnd.coerceAtLeast(ifMatch.range.last + 1))
                    }

                    // 3c. Comment out standalone assignment: Type var = ExcludedClass.method(...)
                    modified = modified.replace(
                        Regex("""^(\s*)([\w.<>\[\]]+\s+\w+\s*=\s*$className\.[^;\r\n]+;)""", RegexOption.MULTILINE),
                        "$1// [forge2neo] $2 // excluded"
                    )

                    // 3d. Comment out variable declarations with excluded types: List<ExcludedClass> var = ...
                    // Use [^;\r\n] to stay within a single line (not cross line boundaries)
                    modified = modified.replace(
                        Regex("""^(\s*)(.*\b$className\b[^;\r\n]*;)""", RegexOption.MULTILINE)
                    ) { match ->
                        val indent = match.groupValues[1]
                        val code = match.groupValues[2]
                        // Skip already commented lines
                        if (code.trimStart().startsWith("//")) match.value
                        else "$indent// [forge2neo] $code // excluded: $className unavailable"
                    }

                    // 3e. Comment out for-loops with excluded type + their body
                    val forPattern = Regex("""^(\s*)for\s*\([^)\r\n]*\b$className\b[^)\r\n]*\)\s*\{""", RegexOption.MULTILINE)
                    var forMatch = forPattern.find(modified)
                    while (forMatch != null) {
                        val braceStart = modified.indexOf('{', forMatch.range.first)
                        if (braceStart < 0) break
                        val braceEnd = findClosing(modified, braceStart, '{', '}')
                        if (braceEnd < 0) break
                        val indent = forMatch.groupValues[1]
                        val fullBlock = modified.substring(forMatch.range.first, braceEnd + 1)
                        val commented = fullBlock.lines().joinToString("\n") { line ->
                            if (line.isBlank()) line
                            else "$indent// [forge2neo] ${line.trimStart()}"
                        }
                        modified = modified.substring(0, forMatch.range.first) +
                            "$indent// [forge2neo] Commented for-block: $className unavailable\n" +
                            commented + modified.substring(braceEnd + 1)
                        forMatch = forPattern.find(modified, forMatch.range.first + commented.length)
                    }

                    // 4. Comment out import of the excluded class itself
                    modified = modified.replace(
                        Regex("""^(import\s+[\w.]*$className\s*;)""", RegexOption.MULTILINE),
                        "// [forge2neo] $1 // excluded"
                    )

                    // 4b. Remove methods that override excluded interface methods
                    // Must run BEFORE the final sweep, which would comment only the signature line
                    val methodPattern = Regex(
                        """([^\S\r\n]*@Override[^\S\r\n]*\r?\n[^\S\r\n]*public\s+\w+\s+\w+\s*\([^)\r\n]*\b${Regex.escape(className)}\b[^)\r\n]*\)\s*\{)""",
                        RegexOption.MULTILINE
                    )
                    val overrideMatch = methodPattern.find(modified)
                    if (overrideMatch != null) {
                        val braceStart = modified.indexOf('{', overrideMatch.range.first)
                        if (braceStart >= 0) {
                            val braceEnd = findClosing(modified, braceStart, '{', '}')
                            if (braceEnd > 0) {
                                val methodBlock = modified.substring(overrideMatch.range.first, braceEnd + 1)
                                modified = modified.replace(methodBlock,
                                    "\n    // [forge2neo] Removed method referencing excluded class $className")
                            }
                        }
                    }

                    // 5. Final sweep: comment out ALL remaining lines that reference the excluded class
                    // Also comment out orphaned blocks that follow commented-out for/variable declarations
                    val classRef = Regex("""\b${Regex.escape(className)}\b""")
                    val lines = modified.lines().toMutableList()
                    var changed = false
                    var idx = 0
                    while (idx < lines.size) {
                        val line = lines[idx]
                        val trimmed = line.trimStart()
                        // Skip already-commented lines and blank lines
                        if (trimmed.startsWith("//") || trimmed.isEmpty()) {
                            // Check if this is a commented-out for-loop followed by an orphaned block
                            if (trimmed.startsWith("// [forge2neo]") && trimmed.contains("for(") && trimmed.contains(className)) {
                                // Find and comment out the following block { ... }
                                var nextIdx = idx + 1
                                while (nextIdx < lines.size && lines[nextIdx].isBlank()) nextIdx++
                                if (nextIdx < lines.size && lines[nextIdx].trimStart() == "{") {
                                    val blockText = lines.subList(nextIdx, lines.size).joinToString("\n")
                                    val braceEnd = findClosing(blockText, 0, '{', '}')
                                    if (braceEnd > 0) {
                                        val blockLines = blockText.substring(0, braceEnd + 1).lines().size
                                        val indent = line.substringBefore(line.trimStart())
                                        for (bi in nextIdx until nextIdx + blockLines) {
                                            if (bi < lines.size) {
                                                val bLine = lines[bi].trimStart()
                                                if (!bLine.startsWith("//")) {
                                                    lines[bi] = "$indent// [forge2neo] $bLine"
                                                    changed = true
                                                }
                                            }
                                        }
                                        idx = nextIdx + blockLines
                                        continue
                                    }
                                }
                            }
                            idx++
                            continue
                        }
                        if (classRef.containsMatchIn(line)) {
                            val indent = line.substringBefore(trimmed)

                            // If this is a method/block signature (ends with {), comment the entire method
                            if (trimmed.trimEnd().endsWith("{")) {
                                // Use original text (before commenting) to find matching brace
                                val origBlockText = lines.subList(idx, lines.size).joinToString("\n")
                                val bracePos = origBlockText.indexOf('{')
                                if (bracePos >= 0) {
                                    val braceEnd = findClosing(origBlockText, bracePos, '{', '}')
                                    if (braceEnd > 0) {
                                        val blockLines = origBlockText.substring(0, braceEnd + 1).lines().size
                                        // Comment ALL lines in the method (signature + body)
                                        for (bi in idx until (idx + blockLines).coerceAtMost(lines.size)) {
                                            val bLine = lines[bi].trimStart()
                                            if (bLine.isNotEmpty() && !bLine.startsWith("//")) {
                                                lines[bi] = "$indent// [forge2neo] $bLine // excluded: $className unavailable"
                                                changed = true
                                            }
                                        }
                                        // Also comment @Override on previous line if present
                                        if (idx > 0) {
                                            val prevTrimmed = lines[idx - 1].trimStart().trimEnd()
                                            if (prevTrimmed == "@Override" || prevTrimmed == "@Override\r") {
                                                lines[idx - 1] = "$indent// [forge2neo] @Override // excluded: $className unavailable"
                                                changed = true
                                            }
                                        }
                                        idx += blockLines
                                        continue
                                    }
                                }
                            }

                            // Default: comment this line and any continuation lines (multi-line statements)
                            lines[idx] = "$indent// [forge2neo] $trimmed // excluded: $className unavailable"
                            changed = true

                            // If this line has unbalanced parens, comment continuation lines until balanced
                            var openParens = trimmed.count { it == '(' } - trimmed.count { it == ')' }
                            if (openParens > 0) {
                                var contIdx = idx + 1
                                while (contIdx < lines.size && openParens > 0) {
                                    val contLine = lines[contIdx].trimStart()
                                    if (contLine.isEmpty() || contLine.startsWith("//")) {
                                        contIdx++
                                        continue
                                    }
                                    openParens += contLine.count { it == '(' } - contLine.count { it == ')' }
                                    lines[contIdx] = "$indent// [forge2neo] $contLine // excluded: $className unavailable (continuation)"
                                    changed = true
                                    contIdx++
                                }
                                idx = contIdx
                                continue
                            }
                        }
                        idx++
                    }
                    if (changed) {
                        modified = lines.joinToString("\n")
                    }
                }

                if (modified != text) {
                    if (!dryRun) {
                        javaFile.toFile().writeText(modified)
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Cleanup references to excluded classes in ${javaFile.fileName}",
                        before = "(references to excluded classes)",
                        after = "(references commented out / removed)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-cleanup-excluded-refs"
                    ))
                }
            }

        return changes to errors
    }

    companion object {
        val NEOFORGE_BLOCK = """
neoForge {
    version = project.neo_forge_version

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    runs {
        client {
            client()
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
        data {
            data()
            programArguments.addAll '--mod', project.mod_id, '--all', '--output', file('src/generated/resources/').getAbsolutePath(), '--existing', file('src/main/resources/').getAbsolutePath()
        }
    }

    mods {
        "MOD_ID_PLACEHOLDER" {
            sourceSet(sourceSets.main)
        }
    }
}

sourceSets.main.resources {
    srcDir 'src/generated/resources'
}""".trimIndent().replace("MOD_ID_PLACEHOLDER", "\${project.mod_id}")

        val SETTINGS_GRADLE = """pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = "https://maven.neoforged.net/releases" }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
}

rootProject.name = '%%PROJECT_NAME%%'
""".trimIndent()

        /**
         * Find the index of the closing brace that matches the opening brace at [openIndex].
         */
        fun findMatchingBrace(content: String, openIndex: Int): Int =
            findClosing(content, openIndex, '{', '}')

        /**
         * Generic bracket matcher: find the closing delimiter matching the opener at [openIndex].
         */
        fun findClosing(content: String, openIndex: Int, openChar: Char, closeChar: Char): Int {
            var depth = 0
            for (i in openIndex until content.length) {
                when (content[i]) {
                    openChar -> depth++
                    closeChar -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        /**
         * Find line number at a given character offset in a string.
         */
        fun String.lineNumberAt(offset: Int): Int {
            return this.substring(0, offset.coerceAtMost(this.length)).count { it == '\n' } + 1
        }
    }
}
