package com.modporter.core.transforms.build

import com.modporter.core.pipeline.*
import mu.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.streams.toList

private val logger = KotlinLogging.logger {}

/**
 * Pass 4: Build system migration.
 * Transforms build.gradle/build.gradle.kts from Forge 1.20.1 MDK to NeoForge 1.21.1.
 *
 * Key transformations:
 * - Forge Gradle plugin -> NeoForge ModDev plugin
 * - net.minecraftforge:forge dependency -> neoForge { version = "..." }
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

    private val targetNeoForgeVersion = "21.1.230"

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

        // Update Gradle wrapper if too old (ModDevGradle 2.x requires Gradle 8.8+).
        val wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (wrapperProps.exists()) {
            val wrapperContent = wrapperProps.readText()
            val versionMatch = Regex("""gradle-(\d+)\.(\d+)""").find(wrapperContent)
            val majorVersion = versionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minorVersion = versionMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val needsUpdate = majorVersion < 8 || (majorVersion == 8 && minorVersion < 8)
            if (needsUpdate) {
                changes.add(Change(
                    file = wrapperProps, line = 0,
                    description = "Update Gradle wrapper from $majorVersion.$minorVersion to 8.14.4 (NeoForge ModDev 2.x requires Gradle 8.8+)",
                    before = "gradle-${majorVersion}.${minorVersion}.x",
                    after = "gradle-8.14.4",
                    confidence = Confidence.HIGH,
                    ruleId = "build-gradle-wrapper"
                ))
                if (!dryRun) {
                    wrapperProps.writeText(
                        wrapperContent.replace(
                            Regex("""gradle-[\d.]+-(?:bin|all)\.zip"""),
                            "gradle-8.14.4-bin.zip"
                        )
                    )
                }
            }
        }

        try {
            changes.addAll(rewriteLegacyTreeGrowers(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to rewrite legacy tree growers: ${e.message}")
        }

        try {
            changes.addAll(rewriteLegacyArmorMaterials(projectDir, dryRun, errors))
        } catch (e: Exception) {
            errors.add("Failed to rewrite legacy armor materials: ${e.message}")
        }

        try {
            changes.addAll(migrateDataGenerationApis(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate data generation APIs: ${e.message}")
        }

        try {
            changes.addAll(migrateCustomStatRegistration(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate custom stat registration: ${e.message}")
        }

        try {
            changes.addAll(migrateRegisterEventResourceLocations(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate RegisterEvent resource locations: ${e.message}")
        }

        try {
            changes.addAll(migrateWorldCarverRegisterEvents(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate world carver RegisterEvent registration: ${e.message}")
        }

        try {
            changes.addAll(rewriteLegacyCapabilityHooks(projectDir, dryRun))
            changes.addAll(addLegacyCapabilityShims(projectDir, dryRun, errors))
        } catch (e: Exception) {
            errors.add("Failed to add legacy capability compatibility shims: ${e.message}")
        }

        try {
            changes.addAll(addLegacyMmlibShims(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to add legacy MMLib compatibility shims: ${e.message}")
        }

        try {
            changes.addAll(cleanupDuplicateOverrides(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to cleanup duplicate overrides: ${e.message}")
        }

        try {
            changes.addAll(addMissingEmptyGameTestStructures(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to add empty GameTest structures: ${e.message}")
        }

        try {
            changes.addAll(migrateAnimalSpawnPlacements(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate animal spawn placements: ${e.message}")
        }

        try {
            changes.addAll(cleanupSplitTickPhaseChecks(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to cleanup split tick phase checks: ${e.message}")
        }

        try {
            changes.addAll(migrateStructureTemplatePoolReflectionFields(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate structure template pool reflection fields: ${e.message}")
        }

        try {
            changes.addAll(migratePendingBlockEntityReflectionFields(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate pending block entity reflection fields: ${e.message}")
        }

        try {
            changes.addAll(migrateEntityVisibilityReflectionHooks(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate entity visibility reflection hooks: ${e.message}")
        }

        try {
            changes.addAll(migrateCreativeModeInventorySelectedTabReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate creative inventory selected tab reflection: ${e.message}")
        }

        try {
            changes.addAll(migrateEntityRenderersAddLayersReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate entity renderer add-layers reflection: ${e.message}")
        }

        try {
            changes.addAll(migrateObfuscationReflectionMethodHandles(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate obfuscation reflection method handles: ${e.message}")
        }

        try {
            changes.addAll(migrateClassForNameReflection(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate Class.forName reflection: ${e.message}")
        }

        try {
            changes.addAll(rewriteDeferredHolderReflectionCollectors(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to rewrite DeferredHolder reflection collectors: ${e.message}")
        }

        try {
            changes.addAll(migrateClientEventPackageTargets(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate client event package targets: ${e.message}")
        }

        try {
            changes.addAll(guardClientOnlyEventRegistrations(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to guard client-only event registrations: ${e.message}")
        }

        try {
            changes.addAll(restoreNonItemStackGetTagCalls(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to restore non-ItemStack getTag calls: ${e.message}")
        }

        try {
            changes.addAll(migrateModifyBakingResultModelLocations(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate model baking result locations: ${e.message}")
        }

        try {
            changes.addAll(migrateBlockPropertiesNoParticlesOnBreak(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate noParticlesOnBreak block properties: ${e.message}")
        }

        try {
            changes.addAll(migrateRemovedTitleScreenAccessors(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate removed TitleScreen accessor surfaces: ${e.message}")
        }

        try {
            changes.addAll(migrateAccessTransformers(projectDir, dryRun))
            changes.addAll(configureAccessTransformers(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate access transformers: ${e.message}")
        }

        try {
            changes.addAll(migrateCoremodScripts(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to migrate coremod scripts: ${e.message}")
        }

        try {
            changes.addAll(registerExistingMixinConfigs(projectDir, dryRun))
        } catch (e: Exception) {
            errors.add("Failed to register existing mixin configs: ${e.message}")
        }

        try {
            errors.addAll(detectForbiddenReflection(projectDir))
            if (!dryRun) {
                errors.addAll(detectLegacyCoremodApiReferences(projectDir))
            }
        } catch (e: Exception) {
            errors.add("Failed to scan hard gates: ${e.message}")
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

        val forgeGradleImportPattern = Regex("""(?m)^[ \t]*import\s+net\.minecraftforge\.gradle\.[^\r\n]+(?:\r?\n)?""")
        val forgeGradleImport = forgeGradleImportPattern.find(content)
        if (forgeGradleImport != null) {
            changes.add(Change(
                file = file,
                line = content.lineNumberAt(forgeGradleImport.range.first),
                description = "Remove ForgeGradle task import after ModDev migration",
                before = forgeGradleImport.value.trim(),
                after = "(removed)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-forgegradle-import"
            ))
            content = forgeGradleImportPattern.replace(content, "")
        }

        val jarJarTaskTypePattern = Regex("""tasks\.named\((\s*['"]jarJar['"]\s*),\s*JarJar\s*\)\.configure""")
        val jarJarTaskType = jarJarTaskTypePattern.find(content)
        if (jarJarTaskType != null) {
            changes.add(Change(
                file = file,
                line = content.lineNumberAt(jarJarTaskType.range.first),
                description = "Remove ForgeGradle JarJar task type reference",
                before = jarJarTaskType.value,
                after = "tasks.named(${jarJarTaskType.groupValues[1]}).configure",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-forgegradle-jarjar-type"
            ))
            content = jarJarTaskTypePattern.replace(content) { match ->
                "tasks.named(${match.groupValues[1]}).configure"
            }
        }
        content = removeUnsupportedJarJarTaskClassifier(content, changes, file)
        content = normalizeJarJarPublicationReferences(content, changes, file)

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

            val moddingXForgeGradlePlugins = listOf(
                Regex("""(?m)^[ \t]*id\s*(?:\(\s*)?['"]org\.moddingx\.modgradle\.mapping['"]\s*(?:\))?\s*version\s*['"][^'"]+['"](?:\s*apply\s+false)?\s*$"""),
                Regex("""(?m)^[ \t]*id\s*(?:\(\s*)?['"]org\.moddingx\.modgradle\.sourcejar['"]\s*(?:\))?\s*version\s*['"][^'"]+['"](?:\s*apply\s+false)?\s*$"""),
            )
            for (pluginPattern in moddingXForgeGradlePlugins) {
                val matches = pluginPattern.findAll(content).toList()
                for (match in matches) {
                    changes.add(Change(
                        file = file,
                        line = content.lineNumberAt(match.range.first),
                        description = "Remove ForgeGradle companion plugin: ${match.value.trim()}",
                        before = match.value.trim(),
                        after = "(removed; handled by ModDev or local Gradle task)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-forgegradle-companion-plugin"
                    ))
                }
                content = pluginPattern.replace(content, "")
            }

            val moddingXApplyPlugin = Regex("""(?m)^[ \t]*apply\s+plugin:\s*['"]org\.moddingx\.modgradle\.(?:mapping|sourcejar)['"]\s*(?:\r?\n)?""")
            val applyPluginMatches = moddingXApplyPlugin.findAll(content).toList()
            for (match in applyPluginMatches) {
                changes.add(Change(
                    file = file,
                    line = content.lineNumberAt(match.range.first),
                    description = "Remove ForgeGradle companion apply plugin line",
                    before = match.value.trim(),
                    after = "(removed; sourceJar task is generated when needed)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-forgegradle-companion-apply"
                ))
            }
            content = moddingXApplyPlugin.replace(content, "")
        }

        // 2. Replace Forge dependency with neoForge block
        val forgeDependencyPatterns = listOf(
            Regex("""minecraft[^\S\r\n]*\(?[^\S\r\n]*['"]net\.(?:minecraftforge|neoforged):forge:[^'"]+['"][^\S\r\n]*\)?"""),
            Regex("""implementation\s+['"]net\.minecraftforge:forge:[^'"]+['"]"""),
            Regex("""implementation[^\S\r\n]*\([^\S\r\n]*['"]net\.minecraftforge:forge:[^'"]+['"][^\S\r\n]*\)"""),
        )
        for (pattern in forgeDependencyPatterns) {
            if (pattern.containsMatchIn(content)) {
                val matches = pattern.findAll(content).toList()
                for (match in matches) {
                    changes.add(Change(
                        file = file, line = content.lineNumberAt(match.range.first),
                        description = "Replace Forge dependency with NeoForge configuration",
                        before = match.value,
                        after = "// NeoForge dependency is now configured via neoForge { } block",
                        confidence = Confidence.HIGH,
                        ruleId = "build-dependency"
                    ))
                }
                content = pattern.replace(content, "// NeoForge dependency is now configured via neoForge { } block")
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

        content = addMavenRepositoryContentFilters(content, file, changes)

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
        content = content.replace(Regex("""\bneoforge_version\b"""), "neo_forge_version")
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

        // 10a. ForgeGradle contributes mod* dependency configurations; ModDev does not.
        val forgeModConfigurations = mapOf(
            "modCompileOnly" to "compileOnly",
            "modRuntimeOnly" to "runtimeOnly",
            "modImplementation" to "implementation",
            "modApi" to "api",
        )
        for ((fromConfig, toConfig) in forgeModConfigurations) {
            val configPattern = Regex("""^(\s*)${Regex.escape(fromConfig)}\b""", RegexOption.MULTILINE)
            val configMatch = configPattern.find(content)
            if (configMatch != null) {
                changes.add(Change(
                    file = file, line = content.lineNumberAt(configMatch.range.first),
                    description = "Replace ForgeGradle dependency configuration $fromConfig",
                    before = fromConfig,
                    after = toConfig,
                    confidence = Confidence.HIGH,
                    ruleId = "build-mod-dependency-configuration"
                ))
                content = configPattern.replace(content) { match ->
                    "${match.groupValues[1]}$toConfig"
                }
            }
        }

        // 10b. Resolve third-party dependencies: check for NeoForge 1.21.1 versions
        val resolver = DependencyResolver(offlineMode = offlineMode, mappingsPrefix = mappingsPrefix)
        val resolvedPrefixes = mutableSetOf<String>()
        val newMavenRepos = mutableSetOf<String>()
        content = resolveDependencies(content, resolver, resolvedPrefixes, newMavenRepos, changes, file)
        content = addReflectedOptionalApiDependencies(
            content,
            file.parent,
            resolver,
            resolvedPrefixes,
            newMavenRepos,
            changes,
            file
        )

        // 10c. Add maven repositories for resolved dependencies
        if (newMavenRepos.isNotEmpty()) {
            content = addMavenRepositories(content, newMavenRepos, changes, file)
        }

        // 10d. NeoForge bundles Mixin/MixinExtras; old standalone processors can break Mojmap remapping.
        content = removeBundledMixinDependencies(content, changes, file)
        content = normalizeJarJarRangePinDsl(content, changes, file)

        // 11. Keep unresolved dependencies active while removing ForgeGradle-only wrappers.
        content = normalizeOldDependencyWrappers(content)

        // 11b. Guard optional run-preparation hooks whose task may not exist under ModDev
        val prepareGameTestTaskPattern = Regex("""tasks\.named\(\s*(['"])prepareGameTestServerRun\1\s*\)\.configure\s*\{""")
        val prepareGameTestTaskMatch = prepareGameTestTaskPattern.find(content)
        if (prepareGameTestTaskMatch != null) {
            val quote = prepareGameTestTaskMatch.groupValues[1]
            val replacement = "tasks.matching { it.name == ${quote}prepareGameTestServerRun${quote} }.configureEach {"
            changes.add(Change(
                file = file, line = content.lineNumberAt(prepareGameTestTaskMatch.range.first),
                description = "Guard prepareGameTestServerRun hook when the task is absent",
                before = prepareGameTestTaskMatch.value,
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "build-guard-optional-run-task"
            ))
            content = prepareGameTestTaskPattern.replace(content, replacement)
        }

        // 13. Remove reobfJar references and related comments
        content = content.replace(Regex("""^.*[Rr]eobf.*\n?""", RegexOption.MULTILINE), "")

        // 14. Replace old property references in build.gradle body
        content = content.replace(Regex("""\bproject\.mc_version\b"""), "project.minecraft_version")
        content = content.replace(Regex("""\bmc_version\b"""), "minecraft_version")
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
        content = ensureSourceJarTask(content, changes, file)
        content = content.replace(Regex("""\n{3,}"""), "\n\n")

        if (content != original && !dryRun) {
            file.writeText(content)
        }

        return changes to errors
    }

    private fun removeUnsupportedJarJarTaskClassifier(
        content: String,
        changes: MutableList<Change>,
        file: Path
    ): String {
        var result = content
        val wholeBlockPattern = Regex(
            """(?ms)^[ \t]*tasks\.named\(\s*['"]jarJar['"]\s*\)\.configure\s*\{\s*archiveClassifier\s*=\s*['"][^'"]*['"]\s*\}\s*"""
        )
        val wholeBlockMatches = wholeBlockPattern.findAll(result).toList()
        for (match in wholeBlockMatches) {
            changes.add(Change(
                file = file,
                line = result.lineNumberAt(match.range.first),
                description = "Remove ForgeGradle JarJar archiveClassifier task configuration",
                before = match.value.trim(),
                after = "(removed; ModDev jarJar is not a Jar task)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-jarjar-archive-classifier"
            ))
        }
        result = wholeBlockPattern.replace(result, "")

        val linePattern = Regex("""(?m)^[ \t]*archiveClassifier\s*=\s*['"][^'"]*['"]\s*\r?\n?""")
        var searchFrom = 0
        while (true) {
            val blockMatch = Regex("""tasks\.named\(\s*['"]jarJar['"]\s*\)\.configure\s*\{""").find(result, searchFrom)
                ?: break
            val openBrace = result.indexOf('{', blockMatch.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (closeBrace <= openBrace) break
            val body = result.substring(openBrace + 1, closeBrace)
            val lineMatches = linePattern.findAll(body).toList()
            if (lineMatches.isNotEmpty()) {
                val before = result.substring(blockMatch.range.first, closeBrace + 1)
                val cleanedBody = linePattern.replace(body, "")
                result = result.substring(0, openBrace + 1) + cleanedBody + result.substring(closeBrace)
                changes.add(Change(
                    file = file,
                    line = result.lineNumberAt(blockMatch.range.first),
                    description = "Remove ForgeGradle JarJar archiveClassifier task configuration",
                    before = before.trim(),
                    after = result.substring(blockMatch.range.first, result.indexOf('}', openBrace) + 1).trim(),
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-jarjar-archive-classifier"
                ))
                searchFrom = openBrace + cleanedBody.length + 1
            } else {
                searchFrom = closeBrace + 1
            }
        }
        return result
    }

    private fun normalizeJarJarPublicationReferences(
        content: String,
        changes: MutableList<Change>,
        file: Path
    ): String {
        var result = content
        val replacements = listOf(
            Regex("""\bartifact\s+project\.tasks\.jarJar\b""") to "artifact tasks.named('jar')",
            Regex("""\bartifact\s+tasks\.jarJar\b""") to "artifact tasks.named('jar')",
            Regex("""\bmainArtifact\(\s*tasks\.jarJar\s*\)""") to "mainArtifact(tasks.jar)",
            Regex("""\buploadFile\s*=\s*tasks\.jarJar\b""") to "uploadFile = tasks.jar"
        )
        for ((pattern, replacement) in replacements) {
            val matches = pattern.findAll(result).toList()
            for (match in matches) {
                changes.add(Change(
                    file = file,
                    line = result.lineNumberAt(match.range.first),
                    description = "Publish ModDev archive jar instead of non-archive jarJar task",
                    before = match.value,
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "build-jarjar-publication-archive"
                ))
            }
            result = pattern.replace(result, replacement)
        }
        return result
    }

    private fun ensureSourceJarTask(content: String, changes: MutableList<Change>, file: Path): String {
        if (!Regex("""\bsourceJar\b""").containsMatchIn(content)) return content
        if (Regex("""\b(?:tasks\.(?:register|create)\(\s*['"]sourceJar['"]|task\s+sourceJar\b)""")
                .containsMatchIn(content)) {
            return content
        }

        val task = """
tasks.register('sourceJar', Jar) {
    archiveClassifier = 'sources'
    from sourceSets.main.allSource
}

""".trimIndent() + System.lineSeparator()
        val insertAt = Regex("""(?m)^publishing\s*\{""").find(content)?.range?.first
            ?: Regex("""(?m)^curseforge\s*\{""").find(content)?.range?.first
            ?: content.length
        changes.add(Change(
            file = file,
            line = content.lineNumberAt(insertAt.coerceAtMost(content.length)),
            description = "Create sourceJar task after removing ForgeGradle sourcejar plugin",
            before = "org.moddingx.modgradle.sourcejar plugin",
            after = "tasks.register('sourceJar', Jar) from sourceSets.main.allSource",
            confidence = Confidence.HIGH,
            ruleId = "build-sourcejar-task"
        ))
        return content.substring(0, insertAt) + task + content.substring(insertAt)
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

    private fun addMissingEmptyGameTestStructures(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val declaredTemplates = mutableSetOf<String>()
        srcDir.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                val content = file.readText()
                if (!content.contains("@GameTest")) return@forEach

                val constants = Regex("""(?:private|public|protected)?\s*(?:static\s+)?final\s+String\s+(\w+)\s*=\s*"([^"]+)"""")
                    .findAll(content)
                    .associate { it.groupValues[1] to it.groupValues[2] }

                Regex("""@GameTest\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(content)
                    .forEach { match ->
                        val args = match.groupValues[1]
                        val templateValue = Regex("""template\s*=\s*("([^"]+)"|(\w+))""").find(args) ?: return@forEach
                        val literal = templateValue.groupValues[2]
                        val constantName = templateValue.groupValues[3]
                        val template = literal.ifBlank { constants[constantName].orEmpty() }
                        if (template.isNotBlank()) declaredTemplates.add(template)
                    }
            }

        val emptyTemplates = declaredTemplates
            .filter { template ->
                val normalized = template.lowercase()
                "empty" in normalized || Regex("""(?:^|_)1x1(?:_|$)""").containsMatchIn(normalized)
            }
            .toSortedSet()
        if (emptyTemplates.isEmpty()) return emptyList()

        val structuresDir = projectDir.resolve("src/main/resources/gameteststructures")
        val changes = mutableListOf<Change>()
        for (template in emptyTemplates) {
            if (gameTestStructureExists(projectDir, template)) continue
            val target = structuresDir.resolve("$template.snbt")
            changes.add(Change(
                file = target,
                line = 0,
                description = "Create missing empty GameTest structure '$template'",
                before = "(missing)",
                after = "1x1 empty GameTest SNBT structure",
                confidence = Confidence.HIGH,
                ruleId = "build-gametest-empty-structure"
            ))
            if (!dryRun) {
                target.parent.createDirectories()
                target.writeText(EMPTY_GAMETEST_STRUCTURE_SNBT)
            }
        }
        return changes
    }

    private fun gameTestStructureExists(projectDir: Path, template: String): Boolean {
        val relative = template.replace('.', '/')
        val resourceRoots = listOf(
            projectDir.resolve("src/main/resources/gameteststructures/$relative.snbt"),
            projectDir.resolve("src/main/resources/gameteststructures/$template.snbt"),
            projectDir.resolve("src/main/resources/data"),
            projectDir.resolve("src/generated/resources/data")
        )
        if (resourceRoots.take(2).any { it.exists() }) return true

        for (dataRoot in resourceRoots.drop(2)) {
            if (!dataRoot.exists()) continue
            dataRoot.listDirectoryEntries().forEach { namespaceDir ->
                if (namespaceDir.resolve("structures/$relative.nbt").exists()) return true
                if (namespaceDir.resolve("structure/$relative.nbt").exists()) return true
            }
        }
        return false
    }

    private fun migrateStructureTemplatePoolReflectionFields(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val codeWithStringArguments = maskJavaCommentsAndTextBlocks(original)
                if (!codeWithStringArguments.contains("StructureTemplatePool.class") ||
                    (!codeWithStringArguments.contains("\"f_210560_\"") && !codeWithStringArguments.contains("\"f_210559_\""))) {
                    return@forEach
                }

                var modified = original
                modified = replaceStructurePoolFieldAccess(modified, "templatesField", "f_210560_", "templates")
                modified = replaceStructurePoolFieldAccess(modified, "rawTemplatesField", "f_210559_", "rawTemplates")
                val executableModified = maskJavaCommentsAndLiterals(modified)
                if (!executableModified.contains("ObfuscationReflectionHelper.findField(")) {
                    modified = modified.replace(
                        Regex("""(?m)^[ \t]*import\s+net\.neoforged\.fml\.util\.ObfuscationReflectionHelper;\s*\r?\n"""),
                        ""
                    )
                }
                if (!Regex("""\bField\s+\w+""").containsMatchIn(maskJavaCommentsAndLiterals(modified))) {
                    modified = removeJavaImport(modified, "java.lang.reflect.Field")
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "StructureTemplatePool internals: reflection -> access-transformer-backed direct fields",
                        before = "ObfuscationReflectionHelper.findField(... f_210560_/f_210559_)",
                        after = "pool.templates/pool.rawTemplates plus access transformer entries",
                        confidence = Confidence.HIGH,
                        ruleId = "build-structure-pool-at-direct-fields"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                    changes.addAll(ensureAccessTransformerEntries(
                        projectDir,
                        listOf(
                            "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates",
                            "public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"
                        ),
                        dryRun,
                        "build-structure-pool-at-entries",
                        "Expose StructureTemplatePool fields through Access Transformers instead of reflection"
                    ))
                }
            }
        return changes
    }

    private fun replaceStructurePoolFieldAccess(
        content: String,
        variableName: String,
        oldFieldName: String,
        newFieldName: String
    ): String {
        var result = content
        result = replaceExecutableRegex(result, Regex("""\(\s*[^()]+?\s*\)\s*$variableName\.get\(([^()]+)\)""")) { match ->
            "${match.groupValues[1].trim()}.$newFieldName"
        }
        result = replaceExecutableRegex(result, Regex("""\b$variableName\.get\(([^()]+)\)""")) { match ->
            "${match.groupValues[1].trim()}.$newFieldName"
        }
        result = replaceExecutableRegex(result, Regex("""\b$variableName\.set\(\s*([^,()]+)\s*,\s*([^;]+?)\s*\)\s*;""")) { match ->
            "${match.groupValues[1].trim()}.$newFieldName = ${match.groupValues[2].trim()};"
        }

        val multiline = Regex(
            """(?m)^([ \t]*)Field\s+$variableName\s*=\s*ObfuscationReflectionHelper\.findField\(\s*\r?\n[ \t]*StructureTemplatePool\.class,\s*"${Regex.escape(oldFieldName)}"\);\s*(?://[^\r\n]*)?"""
        )
        result = replaceCommentAndTextBlockMaskedRegex(result, multiline) { "" }

        val singleLine = Regex(
            """(?m)^([ \t]*)Field\s+$variableName\s*=\s*ObfuscationReflectionHelper\.findField\(\s*StructureTemplatePool\.class,\s*"${Regex.escape(oldFieldName)}"\s*\);\s*(?://[^\r\n]*)?"""
        )
        result = replaceCommentAndTextBlockMaskedRegex(result, singleLine) { "" }
        result = replaceExecutableRegex(result, Regex("""(?m)^[ \t]*$variableName\.setAccessible\(true\);\s*\r?\n""")) { "" }
        return result
    }

    private fun migratePendingBlockEntityReflectionFields(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("LevelChunk.class.getDeclaredFields()") ||
                    !executableOriginal.contains("pendingBlockEntitiesField") ||
                    !executableOriginal.contains("clearPendingBlockEntities")) {
                    return@forEach
                }

                var modified = original
                modified = removeJavaImport(modified, "java.lang.reflect.Field")
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""(?m)^[ \t]*private\s+static\s+Field\s+pendingBlockEntitiesField\s*=\s*null\s*;\s*\r?\n""")
                ) { "" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""(?m)^[ \t]*private\s+static\s+boolean\s+pendingBEFieldInitialized\s*=\s*false\s*;\s*\r?\n""")
                ) { "" }

                val executableModified = maskJavaCommentsAndLiterals(modified)
                val methodMatch = Regex(
                    """private\s+static\s+void\s+clearPendingBlockEntities\s*\(\s*LevelChunk\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
                ).find(executableModified) ?: return@forEach
                val chunkParam = methodMatch.groupValues[1]
                val openBrace = executableModified.indexOf('{', methodMatch.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(executableModified, openBrace) else -1
                if (closeBrace <= openBrace) return@forEach

                val methodBody = executableModified.substring(openBrace + 1, closeBrace)
                val logger = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.LOGGER)\.(?:trace|debug|info)\s*\(""")
                    .find(methodBody)
                    ?.groupValues
                    ?.get(1)
                    ?: "LOGGER"
                val replacement = """
private static void clearPendingBlockEntities(LevelChunk $chunkParam) {
        Map<BlockPos, CompoundTag> pendingBlockEntities = $chunkParam.pendingBlockEntities;
        if (!pendingBlockEntities.isEmpty()) {
            int count = pendingBlockEntities.size();
            pendingBlockEntities.clear();
            if (count > 0) {
                $logger.debug("Cleared {} pending block entities from chunk", count);
            }
        }
    }
""".trimIndent()
                modified = modified.substring(0, methodMatch.range.first) +
                    replacement +
                    modified.substring(closeBrace + 1)
                modified = ensureJavaImport(modified, "java.util.Map")
                modified = ensureJavaImport(modified, "net.minecraft.core.BlockPos")
                modified = ensureJavaImport(modified, "net.minecraft.nbt.CompoundTag")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "LevelChunk pending block entities: reflection -> access-transformer-backed direct field",
                        before = "LevelChunk.class.getDeclaredFields() + Field#setAccessible",
                        after = "chunk.pendingBlockEntities.clear() plus access transformer entry",
                        confidence = Confidence.HIGH,
                        ruleId = "build-levelchunk-pending-blockentities-at"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                    changes.addAll(ensureAccessTransformerEntries(
                        projectDir,
                        listOf("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities"),
                        dryRun,
                        "build-levelchunk-pending-blockentities-at-entry",
                        "Expose ChunkAccess pendingBlockEntities through Access Transformers instead of reflection"
                    ))
                }
            }
        return changes
    }

    private fun migrateEntityVisibilityReflectionHooks(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                val codeWithStringArguments = maskJavaCommentsAndTextBlocks(original)
                if (!codeWithStringArguments.contains("declaresVisibilityHook") ||
                    !executableOriginal.contains(".getMethod(") ||
                    !codeWithStringArguments.contains("startSeenByPlayer") ||
                    !codeWithStringArguments.contains("stopSeenByPlayer")) {
                    return@forEach
                }

                var modified = original
                val hookMethod = Regex(
                    """private\s+static\s+boolean\s+hasNativePlayerVisibilityHook\s*\(\s*Entity\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
                ).find(executableOriginal) ?: return@forEach
                val entityParam = hookMethod.groupValues[1]
                val hookOpenBrace = executableOriginal.indexOf('{', hookMethod.range.first)
                val hookCloseBrace = if (hookOpenBrace >= 0) findMatchingBrace(executableOriginal, hookOpenBrace) else -1
                if (hookCloseBrace <= hookOpenBrace) return@forEach

                val executableHookBody = executableOriginal.substring(hookOpenBrace + 1, hookCloseBrace)
                val hookBodyWithStringArguments = codeWithStringArguments.substring(hookOpenBrace + 1, hookCloseBrace)
                if (!executableHookBody.contains("declaresVisibilityHook") ||
                    !hookBodyWithStringArguments.contains("startSeenByPlayer") ||
                    !hookBodyWithStringArguments.contains("stopSeenByPlayer")) {
                    return@forEach
                }

                val replacementHook = """
private static boolean hasNativePlayerVisibilityHook(Entity $entityParam) {
        return $entityParam instanceof WitherBoss;
    }
""".trimIndent()
                modified = modified.substring(0, hookMethod.range.first) +
                    replacementHook +
                    modified.substring(hookCloseBrace + 1)

                val declaresMethod = Regex(
                    """private\s+static\s+boolean\s+declaresVisibilityHook\s*\(\s*Class<\?>\s+[A-Za-z_$][\w$]*\s*,\s*String\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
                ).find(maskJavaCommentsAndLiterals(modified))
                if (declaresMethod != null) {
                    val executableModified = maskJavaCommentsAndLiterals(modified)
                    val openBrace = executableModified.indexOf('{', declaresMethod.range.first)
                    val closeBrace = if (openBrace >= 0) findMatchingBrace(executableModified, openBrace) else -1
                    if (closeBrace > openBrace) {
                        modified = modified.substring(0, declaresMethod.range.first).trimEnd() +
                            "\n\n" +
                            modified.substring(closeBrace + 1).trimStart()
                    }
                }

                modified = removeJavaImport(modified, "java.lang.reflect.Method")
                val modifiedWithoutImports = Regex("""(?m)^[ \t]*import\s+[^;]+;\s*\r?\n""").replace(modified, "")
                if (!Regex("""\bServerPlayer\b""").containsMatchIn(modifiedWithoutImports)) {
                    modified = removeJavaImport(modified, "net.minecraft.server.level.ServerPlayer")
                }
                modified = ensureJavaImport(modified, "net.minecraft.world.entity.boss.wither.WitherBoss")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Entity visibility hook reflection -> explicit vanilla boss visibility API check",
                        before = "Class#getMethod(... startSeenByPlayer/stopSeenByPlayer)",
                        after = "entity instanceof WitherBoss",
                        confidence = Confidence.HIGH,
                        ruleId = "build-entity-visibility-hook-no-reflection"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun migrateClientEventPackageTargets(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("@Mixin(") || !executableOriginal.contains(".client.ColorHandler")) {
                    return@forEach
                }

                val importPattern = Regex("""(?m)^([ \t]*import\s+)([a-zA-Z_$][\w$]*(?:\.[a-zA-Z_$][\w$]*)*)\.client\.ColorHandler;\s*$""")
                val modified = replaceExecutableRegex(original, importPattern) { match ->
                    "${match.groupValues[1]}${match.groupValues[2]}.client.event.ColorHandler;"
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Retarget client ColorHandler mixin import to event package",
                        before = "<mod>.client.ColorHandler",
                        after = "<mod>.client.event.ColorHandler",
                        confidence = Confidence.HIGH,
                        ruleId = "build-client-event-colorhandler-target"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private data class JavaClassInfo(
        val path: Path,
        val packageName: String?,
        val className: String,
        val qualifiedName: String,
        val source: String,
        val isClientOnly: Boolean
    )

    private fun guardClientOnlyEventRegistrations(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        if (javaFiles.isEmpty()) return emptyList()

        val classes = javaFiles.map { javaFile ->
            val source = javaFile.readText()
            val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
            val className = javaFile.fileName.toString().removeSuffix(".java")
            JavaClassInfo(
                path = javaFile,
                packageName = packageName,
                className = className,
                qualifiedName = if (packageName.isNullOrBlank()) className else "$packageName.$className",
                source = source,
                isClientOnly = isClientOnlyJavaSource(source)
            )
        }
        val classesBySimpleName = classes.groupBy { it.className }
        val classesByQualifiedName = classes.associateBy { it.qualifiedName }
        val clientQualifiedNames = classes.filter { it.isClientOnly }.map { it.qualifiedName }.toSet()
        val changes = mutableListOf<Change>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            var modified = original
            val guarded = guardClientOnlyListenerReferences(
                modified,
                clientQualifiedNames,
                classesBySimpleName,
                classesByQualifiedName
            )
            if (guarded != modified) {
                modified = guarded
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Guard client-only event listener method references from dedicated-server class loading",
                    before = "eventBus.addListener(ClientOnlyClass::handler)",
                    after = "FMLLoader Dist.CLIENT guard around the original listener registration",
                    confidence = Confidence.HIGH,
                    ruleId = "build-client-only-listener-dist-guard"
                ))
            }

            val annotated = addClientDistToEventBusSubscribers(modified)
            if (annotated != modified) {
                modified = annotated
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Mark EventBusSubscriber classes that handle client lifecycle/events as Dist.CLIENT",
                    before = "@EventBusSubscriber without value = Dist.CLIENT",
                    after = "@EventBusSubscriber(..., value = Dist.CLIENT)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-client-eventbus-subscriber-dist"
                ))
            }

            if (modified != original && !dryRun) {
                javaFile.writeText(modified)
            }
        }

        return changes
    }

    private fun isClientOnlyJavaSource(source: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        return code.contains("net.minecraft.client.") ||
            code.contains("net.neoforged.neoforge.client.event.") ||
            Regex("""(?m)^\s*package\s+.*\.client(?:\.|;)""").containsMatchIn(code) ||
            Regex("""@OnlyIn\s*\(\s*(?:Dist\.)?CLIENT\s*\)""").containsMatchIn(code) ||
            Regex("""@(?:Mod\.)?EventBusSubscriber\s*\([\s\S]*?\bvalue\s*=\s*(?:net\.neoforged\.api\.distmarker\.)?Dist\.CLIENT""")
                .containsMatchIn(code)
    }

    private fun guardClientOnlyListenerReferences(
        source: String,
        clientQualifiedNames: Set<String>,
        classesBySimpleName: Map<String, List<JavaClassInfo>>,
        classesByQualifiedName: Map<String, JavaClassInfo>
    ): String {
        val listenerPattern = Regex(
            """(?m)^([ \t]*)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.addListener\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)::([A-Za-z_$][\w$]*)\s*\)\s*;\s*(?://[^\r\n]*)?$"""
        )
        var result = source
        for (match in listenerPattern.findAll(source).toList().asReversed()) {
            val targetRef = match.groupValues[3]
            val methodName = match.groupValues[4]
            if (!shouldGuardClientOnlyListenerReference(
                    targetRef,
                    methodName,
                    source,
                    clientQualifiedNames,
                    classesBySimpleName,
                    classesByQualifiedName
                )
            ) {
                continue
            }
            if (isWithinDistClientGuard(result, match.range.first)) {
                continue
            }
            val indent = match.groupValues[1]
            val originalLine = match.value.trim()
            val replacement = buildString {
                append(indent)
                append("if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {")
                append(System.lineSeparator())
                append(indent)
                append("    ")
                append(originalLine)
                append(System.lineSeparator())
                append(indent)
                append("}")
            }
            result = result.replaceRange(match.range, replacement)
        }
        return result
    }

    private fun shouldGuardClientOnlyListenerReference(
        targetRef: String,
        methodName: String,
        source: String,
        clientQualifiedNames: Set<String>,
        classesBySimpleName: Map<String, List<JavaClassInfo>>,
        classesByQualifiedName: Map<String, JavaClassInfo>
    ): Boolean {
        val targetClass = resolveListenerTargetClass(targetRef, source, classesBySimpleName, classesByQualifiedName)
        if (targetClass != null) {
            return shouldGuardClientOnlyListenerMethod(targetClass, methodName)
        }

        return if (targetRef.contains(".")) {
            targetRef in clientQualifiedNames
        } else {
            val imported = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(targetRef)})\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
            if (imported != null) {
                imported in clientQualifiedNames
            } else {
                val localMatches = classesBySimpleName[targetRef].orEmpty()
                localMatches.size == 1 && localMatches.single().isClientOnly
            }
        }
    }

    private fun resolveListenerTargetClass(
        targetRef: String,
        source: String,
        classesBySimpleName: Map<String, List<JavaClassInfo>>,
        classesByQualifiedName: Map<String, JavaClassInfo>
    ): JavaClassInfo? {
        if (targetRef.contains(".")) {
            classesByQualifiedName[targetRef]?.let { return it }
        }

        val imported = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.${Regex.escape(targetRef)})\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
        if (imported != null) {
            classesByQualifiedName[imported]?.let { return it }
        }

        val currentPackage = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
        val localMatches = classesBySimpleName[targetRef].orEmpty()
        if (currentPackage != null) {
            localMatches.singleOrNull { it.packageName == currentPackage }?.let { return it }
        }
        return localMatches.singleOrNull()
    }

    private fun shouldGuardClientOnlyListenerMethod(targetClass: JavaClassInfo, methodName: String): Boolean {
        val methodSource = findJavaMethodSource(targetClass.source, methodName)
            ?: return targetClass.isClientOnly
        return javaMethodContainsClientOnlyApis(methodSource, targetClass.source)
    }

    private fun findJavaMethodSource(source: String, methodName: String): String? {
        val methodPattern = Regex(
            """(?m)(?:^|[\r\n])([ \t]*(?:@[^\r\n]+\s*)*(?:(?:public|protected|private|static|final|synchronized|native|abstract|strictfp)\s+)*(?:<[^>{};]+>\s*)?(?:[A-Za-z_$][\w$]*(?:\s*<[^>{};]+>)?(?:\s*\[\s*])?(?:\s*,\s*)?|\?|extends|super|&|\.)+(?:\s+)+${Regex.escape(methodName)}\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\s*\{)"""
        )
        val match = methodPattern.find(source) ?: return null
        val openBrace = source.indexOf('{', match.range.first)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace <= openBrace) return null
        return source.substring(match.range.first, closeBrace + 1)
    }

    private fun javaMethodContainsClientOnlyApis(methodSource: String, classSource: String): Boolean {
        val methodCode = maskJavaCommentsAndLiterals(methodSource)
        val classCode = maskJavaCommentsAndLiterals(classSource)
        if (
            methodCode.contains("net.minecraft.client.") ||
            methodCode.contains("net.neoforged.neoforge.client.event.")
        ) {
            return true
        }

        val clientImportedNames = Regex(
            """(?m)^\s*import\s+(net\.minecraft\.client\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*|net\.neoforged\.neoforge\.client\.event\.[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;"""
        ).findAll(classCode).map { it.groupValues[1].substringAfterLast('.') }.toSet()
        val knownClientEventNames = setOf(
            "FMLClientSetupEvent",
            "ClientTickEvent",
            "CustomizeGuiOverlayEvent",
            "EntityRenderersEvent",
            "InputEvent",
            "ModelEvent",
            "RegisterClientCommandsEvent",
            "RegisterClientExtensionsEvent",
            "RegisterClientReloadListenersEvent",
            "RegisterClientTooltipComponentFactoriesEvent",
            "RegisterColorHandlersEvent",
            "RegisterDimensionSpecialEffectsEvent",
            "RegisterGuiLayersEvent",
            "RegisterKeyMappingsEvent",
            "RegisterMenuScreensEvent",
            "RegisterNamedRenderTypesEvent",
            "RegisterParticleProvidersEvent",
            "RegisterPresetEditorsEvent",
            "RegisterRecipeBookCategoriesEvent",
            "RegisterShadersEvent",
            "RenderGuiEvent",
            "RenderLevelStageEvent",
            "RenderLivingEvent",
            "RenderNameTagEvent",
            "RenderPlayerEvent",
            "ScreenEvent",
            "TextureAtlasStitchedEvent",
            "ViewportEvent"
        )
        return (clientImportedNames + knownClientEventNames)
            .any { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(methodCode) }
    }

    private fun isWithinDistClientGuard(source: String, offset: Int): Boolean {
        val guardPattern = Regex(
            """if\s*\(\s*(?:net\.neoforged\.fml\.loading\.)?(?:FMLLoader\.getDist\(\)|FMLEnvironment\.dist)\s*==\s*(?:net\.neoforged\.api\.distmarker\.)?Dist\.CLIENT\s*\)\s*\{"""
        )
        return guardPattern.findAll(source.substring(0, offset.coerceAtMost(source.length)))
            .any { match ->
                val openBrace = source.indexOf('{', match.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
                closeBrace > offset
            }
    }

    private fun addClientDistToEventBusSubscribers(source: String): String {
        val annotationPattern = Regex("""@(?:Mod\.)?EventBusSubscriber\s*\(""")
        var result = source
        for (match in annotationPattern.findAll(source).toList().asReversed()) {
            val openParen = result.indexOf('(', match.range.first)
            val closeParen = if (openParen >= 0) findClosing(result, openParen, '(', ')') else -1
            if (closeParen <= openParen) continue
            val annotation = result.substring(match.range.first, closeParen + 1)
            if (Regex("""\bvalue\s*=""").containsMatchIn(annotation)) continue

            val classMatch = Regex(
                """\s*(?:public\s+|protected\s+|private\s+|static\s+|final\s+|abstract\s+)*class\s+([A-Za-z_$][\w$]*)\b"""
            ).find(result, closeParen + 1) ?: continue
            if (classMatch.range.first - closeParen > 240) continue
            val openBrace = result.indexOf('{', classMatch.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (closeBrace <= openBrace) continue
            val classBody = result.substring(openBrace + 1, closeBrace)
            if (!classBodyContainsClientOnlyApis(classBody)) continue

            result = result.replaceRange(
                match.range.first,
                closeParen + 1,
                addDistClientValueToEventBusSubscriberAnnotation(annotation)
            )
        }
        return result
    }

    private fun classBodyContainsClientOnlyApis(body: String): Boolean {
        val code = maskJavaCommentsAndLiterals(body)
        return code.contains("net.minecraft.client.") ||
            code.contains("net.neoforged.neoforge.client.event.") ||
            Regex("""\bFMLClientSetupEvent\b""").containsMatchIn(code) ||
            Regex("""\b(?:EntityRenderersEvent|ModelEvent|RegisterColorHandlersEvent|RegisterParticleProvidersEvent|RegisterKeyMappingsEvent|RegisterShadersEvent)\b""")
                .containsMatchIn(code)
    }

    private fun addDistClientValueToEventBusSubscriberAnnotation(annotation: String): String {
        val closeParen = annotation.lastIndexOf(')')
        if (closeParen < 0) return annotation
        val beforeClose = annotation.substring(0, closeParen).trimEnd()
        val separator = if (beforeClose.endsWith("(")) "" else ", "
        return if (!annotation.contains('\n')) {
            beforeClose + separator + "value = net.neoforged.api.distmarker.Dist.CLIENT" + annotation.substring(closeParen)
        } else {
            val closeLineIndent = annotation.substringBeforeLast(")")
                .substringAfterLast('\n', "")
                .takeWhile { it == ' ' || it == '\t' }
            val multilineSeparator = if (beforeClose.endsWith("(") || beforeClose.endsWith(",")) "" else ","
            beforeClose + multilineSeparator +
                System.lineSeparator() +
                closeLineIndent +
                "    value = net.neoforged.api.distmarker.Dist.CLIENT" +
                annotation.substring(closeParen)
        }
    }

    private fun migrateCreativeModeInventorySelectedTabReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val methodMatch = Regex(
                    """private\s+static\s+CreativeModeTab\s+getSelectedTab\s*\(\s*\)\s*\{"""
                ).find(original) ?: return@forEach
                val openBrace = original.indexOf('{', methodMatch.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(original, openBrace) else -1
                if (closeBrace <= openBrace) return@forEach
                val methodSource = original.substring(methodMatch.range.first, closeBrace + 1)
                if (!containsCreativeSelectedTabReflection(methodSource)) return@forEach

                val replacement = """
private static CreativeModeTab getSelectedTab() {
    CreativeModeTab selectedTab = CreativeModeInventoryScreen.selectedTab;
    return selectedTab != null ? selectedTab : CreativeModeTabs.getDefaultTab();
  }
""".trimIndent()
                var modified = original.substring(0, methodMatch.range.first) +
                    replacement +
                    original.substring(closeBrace + 1)
                if (!Regex("""\bjava\.lang\.reflect\.""").containsMatchIn(modified)) {
                    modified = removeJavaImport(modified, "java.lang.reflect.Field")
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "CreativeModeInventoryScreen selectedTab: reflection -> access-transformer-backed direct field",
                        before = "CreativeModeInventoryScreen.class.getDeclaredField(\"selectedTab\")",
                        after = "CreativeModeInventoryScreen.selectedTab plus access transformer entry",
                        confidence = Confidence.HIGH,
                        ruleId = "build-creative-selectedtab-at"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                    changes.addAll(ensureAccessTransformerEntries(
                        projectDir,
                        listOf("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab"),
                        dryRun,
                        "build-creative-selectedtab-at-entry",
                        "Expose CreativeModeInventoryScreen selectedTab through Access Transformers instead of reflection"
                    ))
                }
            }
        return changes
    }

    private fun containsCreativeSelectedTabReflection(methodSource: String): Boolean {
        val code = maskJavaComments(methodSource)
        val fieldMatch = Regex(
            """\b(?:java\.lang\.reflect\.)?Field\s+([A-Za-z_$][\w$]*)\s*=\s*(?:net\.minecraft\.client\.gui\.screens\.inventory\.)?CreativeModeInventoryScreen\.class\.getDeclaredField\s*\(\s*"selectedTab"\s*\)"""
        ).find(code) ?: return false
        val fieldName = fieldMatch.groupValues[1]
        return Regex("""\b${Regex.escape(fieldName)}\s*\.\s*setAccessible\s*\(\s*true\s*\)""").containsMatchIn(code) &&
            Regex("""\b${Regex.escape(fieldName)}\s*\.\s*get\s*\(\s*null\s*\)""").containsMatchIn(code)
    }

    private fun migrateEntityRenderersAddLayersReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val methodMatch = Regex(
                    """public\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*EntityRenderersEvent\.AddLayers\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
                ).find(original) ?: return@forEach
                val methodName = methodMatch.groupValues[1]
                val eventParam = methodMatch.groupValues[2]
                val openBrace = original.indexOf('{', methodMatch.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(original, openBrace) else -1
                if (closeBrace <= openBrace) return@forEach

                val methodSource = original.substring(methodMatch.range.first, closeBrace + 1)
                if (!containsEntityRenderersAddLayersReflection(methodSource, eventParam)) return@forEach

                val body = original.substring(openBrace + 1, closeBrace)
                val skinBlockStart = body.indexOf("$eventParam.getSkins().forEach")
                    .takeIf { it >= 0 }
                    ?: return@forEach
                val skinBlockEnd = findJavaStatementEnd(body, skinBlockStart)
                    .takeIf { it > skinBlockStart }
                    ?: return@forEach
                val skinBlock = body.substring(skinBlockStart, skinBlockEnd + 1).trim()
                val rendererStream = Regex(
                    """(?s)\(\(Map<[^;]+>\)\s*[A-Za-z_$][\w$]*\.get\(\s*${Regex.escape(eventParam)}\s*\)\)\.values\(\)\.stream\(\)\s*\.(.*?)\s*;"""
                ).find(body) ?: return@forEach
                val streamTail = rendererStream.groupValues[1].trim()
                val replacementMethod = """
public static void $methodName(EntityRenderersEvent.AddLayers $eventParam) {
        $skinBlock
        $eventParam.getEntityTypes().stream().map($eventParam::getRenderer).
                $streamTail;
    }
""".trimIndent()

                var modified = original.substring(0, methodMatch.range.first) +
                    replacementMethod +
                    original.substring(closeBrace + 1)
                modified = removeJavaImport(modified, "java.lang.reflect.Field")
                modified = Regex("""(?m)^[ \t]*private\s+static\s+Field\s+[A-Za-z_$][\w$]*\s*;\s*\r?\n""")
                    .replace(modified, "")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "EntityRenderersEvent.AddLayers renderers: reflection -> public renderer lookup API",
                        before = "EntityRenderersEvent.AddLayers.class.getDeclaredField(\"renderers\")",
                        after = "event.getEntityTypes().stream().map(event::getRenderer)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-entityrenderers-addlayers-api"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun containsEntityRenderersAddLayersReflection(methodSource: String, eventParam: String): Boolean {
        val code = maskJavaComments(methodSource)
        val fieldAssignments = Regex(
            """\b([A-Za-z_$][\w$]*)\s*=\s*(?:net\.neoforged\.neoforge\.client\.event\.)?EntityRenderersEvent\.AddLayers\.class\.getDeclaredField\s*\(\s*"renderers"\s*\)"""
        ).findAll(code)
        return fieldAssignments.any { match ->
            val fieldName = match.groupValues[1]
            Regex("""\b${Regex.escape(fieldName)}\s*\.\s*setAccessible\s*\(\s*true\s*\)""").containsMatchIn(code) &&
                Regex("""\b${Regex.escape(fieldName)}\s*\.\s*get\s*\(\s*${Regex.escape(eventParam)}\s*\)""").containsMatchIn(code)
        }
    }

    private fun migrateObfuscationReflectionMethodHandles(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val bindings = collectObfuscationMethodHandleBindings(original)
                if (bindings.isEmpty()) return@forEach

                var modified = original
                var touched = false
                val invokerImports = linkedSetOf<String>()
                bindings.firstOrNull {
                    it.targetClassSimple == "LivingEntity" &&
                        it.obfuscatedMethodName == "m_5592_" &&
                        it.parameterTypeSimples.isEmpty()
                }?.let { binding ->
                    val replaced = replaceLivingEntityDeathSoundMethodHandle(modified, binding.handleFieldName)
                    if (replaced == modified) return@let
                    modified = replaced
                    val packageName = javaPackageName(original) ?: return@forEach
                    invokerImports.add("${generatedMixinPackage(packageName)}.ModPorterLivingEntityInvoker")
                    changes.addAll(ensureMixinInvoker(
                        projectDir = projectDir,
                        sourceFile = javaFile,
                        packageName = packageName,
                        invokerName = "ModPorterLivingEntityInvoker",
                        targetClassName = "LivingEntity",
                        targetImport = "net.minecraft.world.entity.LivingEntity",
                        extraImports = listOf("net.minecraft.sounds.SoundEvent"),
                        methodSource = """
                            @Invoker("getDeathSound")
                            SoundEvent modporter${'$'}getDeathSound();
                        """.trimIndent(),
                        dryRun = dryRun
                    ))
                    touched = true
                }
                bindings.firstOrNull {
                    it.targetClassSimple == "HangingEntity" &&
                        it.obfuscatedMethodName == "m_6022_" &&
                        it.parameterTypeSimples == listOf("Direction")
                }?.let { binding ->
                    val replaced = replaceHangingEntitySetDirectionMethodHandle(modified, binding.handleFieldName)
                    if (replaced == modified) return@let
                    modified = replaced
                    val packageName = javaPackageName(original) ?: return@forEach
                    invokerImports.add("${generatedMixinPackage(packageName)}.ModPorterHangingEntityInvoker")
                    changes.addAll(ensureMixinInvoker(
                        projectDir = projectDir,
                        sourceFile = javaFile,
                        packageName = packageName,
                        invokerName = "ModPorterHangingEntityInvoker",
                        targetClassName = "HangingEntity",
                        targetImport = "net.minecraft.world.entity.decoration.HangingEntity",
                        extraImports = listOf("net.minecraft.core.Direction"),
                        methodSource = """
                            @Invoker("setDirection")
                            void modporter${'$'}setDirection(Direction direction);
                        """.trimIndent(),
                        dryRun = dryRun
                    ))
                    touched = true
                }
                if (!touched) return@forEach

                modified = removeObfuscationMethodHandleScaffolding(modified)
                modified = removeJavaImport(modified, "net.neoforged.fml.util.ObfuscationReflectionHelper")
                modified = removeJavaImport(modified, "net.minecraftforge.fml.util.ObfuscationReflectionHelper")
                modified = removeJavaImport(modified, "java.lang.invoke.MethodHandle")
                modified = removeJavaImport(modified, "java.lang.invoke.MethodHandles")
                modified = removeJavaImport(modified, "java.lang.reflect.Method")
                invokerImports.forEach { importName ->
                    modified = addJavaImportIfMissing(modified, importName)
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "ObfuscationReflectionHelper method handles -> mixin invoker method calls",
                        before = "ObfuscationReflectionHelper.findMethod(...) + MethodHandle.invoke",
                        after = "generated @Invoker interfaces and direct invoker calls",
                        confidence = Confidence.HIGH,
                        ruleId = "build-obfuscation-methodhandle-mixin-invoker"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private data class ObfuscationMethodHandleBinding(
        val targetClassSimple: String,
        val obfuscatedMethodName: String,
        val parameterTypeSimples: List<String>,
        val methodFieldName: String,
        val handleFieldName: String
    )

    private fun collectObfuscationMethodHandleBindings(source: String): List<ObfuscationMethodHandleBinding> {
        val code = maskJavaComments(source)
        val declaredHandleFields = Regex(
            """(?m)\b(?:private|protected|public)?\s*static\s+final\s+MethodHandle\s+([A-Za-z_$][\w$]*)\s*;"""
        ).findAll(code).map { it.groupValues[1] }.toSet()
        if (declaredHandleFields.isEmpty()) return emptyList()

        val methodFields = Regex(
            """(?m)\b(?:private|protected|public)?\s*static\s+final\s+Method\s+([A-Za-z_$][\w$]*)\s*=\s*(?:ObfuscationReflectionHelper|net\.neoforged\.fml\.util\.ObfuscationReflectionHelper|net\.minecraftforge\.fml\.util\.ObfuscationReflectionHelper)\.findMethod\s*\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class\s*,\s*"([^"]+)"([^;]*)\)\s*;"""
        ).findAll(code)

        return methodFields.flatMap { methodMatch ->
            val methodFieldName = methodMatch.groupValues[1]
            val targetClassSimple = methodMatch.groupValues[2].substringAfterLast('.')
            val obfuscatedMethodName = methodMatch.groupValues[3]
            val parameterTypeSimples = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class""")
                .findAll(methodMatch.groupValues[4])
                .map { it.groupValues[1].substringAfterLast('.') }
                .toList()
            methodHandleFieldsForUnreflectedMethod(code, methodFieldName, declaredHandleFields)
                .map { handleFieldName ->
                    ObfuscationMethodHandleBinding(
                        targetClassSimple = targetClassSimple,
                        obfuscatedMethodName = obfuscatedMethodName,
                        parameterTypeSimples = parameterTypeSimples,
                        methodFieldName = methodFieldName,
                        handleFieldName = handleFieldName
                    )
                }
        }.toList()
    }

    private fun methodHandleFieldsForUnreflectedMethod(
        code: String,
        methodFieldName: String,
        declaredHandleFields: Set<String>
    ): Set<String> {
        val unreflectedVariables = Regex(
            """\b([A-Za-z_$][\w$]*)\s*=\s*(?:[A-Za-z_$][\w$]*|MethodHandles\.lookup\(\))\.unreflect\s*\(\s*${Regex.escape(methodFieldName)}\s*\)"""
        ).findAll(code).map { it.groupValues[1] }.toSet()

        return unreflectedVariables.flatMap { variable ->
            buildList {
                if (variable in declaredHandleFields) add(variable)
                Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(variable)}\s*;""")
                    .findAll(code)
                    .map { it.groupValues[1] }
                    .filter { it in declaredHandleFields }
                    .forEach { add(it) }
            }
        }.toSet()
    }

    private fun replaceLivingEntityDeathSoundMethodHandle(source: String, handleName: String): String {
        var searchStart = 0
        while (true) {
            val code = maskJavaComments(source)
            val methodMatch = Regex(
                """(?m)(?:^[ \t]*(?:@[^\r\n]+)\r?\n)*[ \t]*(?:public|protected|private)\s+static\s+(?:@Nullable\s+)?SoundEvent\s+[A-Za-z_$][\w$]*\s*\(\s*LivingEntity\s+([A-Za-z_$][\w$]*)\s*\)\s*(?:throws\s+[^{;]+)?\{"""
            ).find(code, searchStart) ?: return source
            val param = methodMatch.groupValues[1]
            val openBrace = code.indexOf('{', methodMatch.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
            if (closeBrace <= openBrace) return source
            val body = code.substring(openBrace + 1, closeBrace)
            val invokesBoundHandle = Regex(
                """\b${Regex.escape(handleName)}\s*\.\s*(?:invokeExact|invoke)\s*\(\s*${Regex.escape(param)}\s*\)"""
            ).containsMatchIn(body)
            if (!invokesBoundHandle) {
                searchStart = closeBrace + 1
                continue
            }
            val header = source.substring(methodMatch.range.first, openBrace + 1).trimEnd()
            val replacement = """
$header
        return ((ModPorterLivingEntityInvoker) $param).modporter${'$'}getDeathSound();
    }
""".trimIndent()
            return source.substring(0, methodMatch.range.first) +
                replacement +
                source.substring(closeBrace + 1)
        }
    }

    private fun replaceHangingEntitySetDirectionMethodHandle(source: String, handleName: String): String {
        var result = source
        var searchStart = 0
        while (true) {
            val code = maskJavaComments(result)
            val match = Regex(
                """(?s)try\s*\{\s*${Regex.escape(handleName)}\s*\.\s*(?:invokeExact|invoke)\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\);\s*\}\s*catch\s*\(\s*Throwable\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*[A-Za-z_$][\w$]*\.printStackTrace\(\);\s*\}"""
            ).find(code, searchStart) ?: return result
            val entityArg = match.groupValues[1]
            val directionArg = match.groupValues[2]
            val enclosingMethod = findJavaMethodSourceContaining(code, match.range.first)
            val hasTypedArguments = enclosingMethod != null &&
                javaMethodDeclaresAssignableType(
                    enclosingMethod,
                    entityArg,
                    setOf("HangingEntity", "Painting", "ItemFrame", "GlowItemFrame")
                ) &&
                javaMethodDeclaresAssignableType(enclosingMethod, directionArg, setOf("Direction"))
            if (!hasTypedArguments) {
                searchStart = match.range.last + 1
                continue
            }
            val replacement = "((ModPorterHangingEntityInvoker) $entityArg).modporter${'$'}setDirection($directionArg);"
            result = result.substring(0, match.range.first) +
                replacement +
                result.substring(match.range.last + 1)
            searchStart = match.range.first + replacement.length
        }
    }

    private fun findJavaMethodSourceContaining(source: String, offset: Int): String? {
        val methodPattern = Regex(
            """(?m)(?:^|[\r\n])([ \t]*(?:@[^\r\n]+\s*)*(?:(?:public|protected|private|static|final|synchronized|native|abstract|strictfp)\s+)*(?:<[^>{};]+>\s*)?(?:[A-Za-z_$][\w$]*(?:\s*<[^>{};]+>)?(?:\s*\[\s*])?(?:\s*,\s*)?|\?|extends|super|&|\.)+(?:\s+)+[A-Za-z_$][\w$]*\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\s*\{)"""
        )
        return methodPattern.findAll(source)
            .mapNotNull { match ->
                val openBrace = source.indexOf('{', match.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
                if (closeBrace > openBrace && offset in openBrace..closeBrace) {
                    source.substring(match.range.first, closeBrace + 1)
                } else {
                    null
                }
            }
            .lastOrNull()
    }

    private fun javaMethodHeaderDeclaresParameter(methodSource: String, typeSimpleName: String, variableName: String): Boolean {
        val header = methodSource.substringBefore("{")
        return Regex(
            """(?:^|[,(]\s*)(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(typeSimpleName)}\s+${Regex.escape(variableName)}\b"""
        ).containsMatchIn(header)
    }

    private fun javaMethodDeclaresAssignableType(
        methodSource: String,
        variableName: String,
        assignableSimpleTypes: Set<String>
    ): Boolean {
        return assignableSimpleTypes.any { typeSimpleName ->
            javaMethodHeaderDeclaresParameter(methodSource, typeSimpleName, variableName) ||
                Regex(
                    """(?:^|[;{}\r\n]\s*)(?:final\s+)?(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(typeSimpleName)}\s+${Regex.escape(variableName)}\b"""
                ).containsMatchIn(methodSource)
        }
    }

    private fun removeObfuscationMethodHandleScaffolding(source: String): String {
        var result = source
        result = Regex("""(?m)^[ \t]*private\s+static\s+final\s+MethodHandles\.Lookup\s+[A-Za-z_$][\w$]*\s*=.*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*private\s+static\s+final\s+Method\s+[A-Za-z_$][\w$]*\s*=.*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*private\s+static\s+final\s+MethodHandle\s+[A-Za-z_$][\w$]*\s*;\s*\r?\n""")
            .replace(result, "")
        while (true) {
            val staticMatch = Regex("""(?m)^[ \t]*static\s*\{""").find(result) ?: break
            val openBrace = result.indexOf('{', staticMatch.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(result, openBrace) else -1
            if (closeBrace <= openBrace) break
            val block = result.substring(staticMatch.range.first, closeBrace + 1)
            if (!block.contains("MethodHandle") || !block.contains("unreflect(")) break
            result = result.substring(0, staticMatch.range.first).trimEnd() +
                System.lineSeparator() +
                result.substring(closeBrace + 1).trimStart()
        }
        return result
    }

    private fun restoreNonItemStackGetTagCalls(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val replacement = ".getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"
        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains(replacement) ||
                    !executableOriginal.contains("TagKey<") ||
                    !executableOriginal.contains("getTag()")) {
                    return@forEach
                }
                val tagGetterOwners = Regex("""\bclass\s+([A-Za-z_$][\w$]*)\b""")
                    .findAll(executableOriginal)
                    .mapNotNull { match ->
                        val openBrace = executableOriginal.indexOf('{', match.range.last)
                        val closeBrace = if (openBrace >= 0) findMatchingBrace(executableOriginal, openBrace) else -1
                        if (closeBrace <= openBrace) return@mapNotNull null
                        val body = executableOriginal.substring(openBrace + 1, closeBrace)
                        if (Regex("""public\s+TagKey\s*<[^>]+>\s+getTag\s*\(\s*\)\s*\{""").containsMatchIn(body)) {
                            match.groupValues[1]
                        } else {
                            null
                        }
                    }
                    .toSet()
                if (tagGetterOwners.isEmpty()) return@forEach

                var modified = original
                for (owner in tagGetterOwners) {
                    val variables = Regex("""\b${Regex.escape(owner)}\s+([A-Za-z_$][\w$]*)\b""")
                        .findAll(executableOriginal)
                        .map { it.groupValues[1] }
                        .toSet()
                    for (variable in variables) {
                        modified = replaceExecutableRegex(
                            modified,
                            Regex("""\b${Regex.escape(variable)}${Regex.escape(replacement)}""")
                        ) { "$variable.getTag()" }
                    }
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Restore getTag calls on local TagKey holder types after ItemStack NBT migration",
                        before = "holder.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()",
                        after = "holder.getTag()",
                        confidence = Confidence.HIGH,
                        ruleId = "build-restore-non-itemstack-gettag"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun migrateModifyBakingResultModelLocations(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                if (!executableOriginal.contains("ModelEvent.ModifyBakingResult") ||
                    !executableOriginal.contains("event.getModels().replaceAll")) {
                    return@forEach
                }

                val lambdaMatch = Regex("""replaceAll\s*\(\s*\(\s*([A-Za-z_$][\w$]*)\s*,\s*[A-Za-z_$][\w$]*\s*\)\s*->""")
                    .find(executableOriginal)
                    ?: return@forEach
                val locationVar = lambdaMatch.groupValues[1]
                val callPattern = Regex("""(\.[A-Za-z_$][\w$]*\(\s*)${Regex.escape(locationVar)}(\s*\))""")
                val modified = replaceExecutableRegex(original, callPattern) { match ->
                    "${match.groupValues[1]}$locationVar.id()${match.groupValues[2]}"
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Pass ResourceLocation id() from ModelResourceLocation keys in ModifyBakingResult",
                        before = "model predicate receives ModelResourceLocation record",
                        after = "model predicate receives location.id() ResourceLocation",
                        confidence = Confidence.HIGH,
                        ruleId = "build-modelresource-location-id"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun migrateClassForNameReflection(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("Class.forName(")) return@forEach

                var modified = original
                modified = removeRedundantClassForNameOnClassObjects(modified)
                modified = rewriteClassForNamePresenceChecksWithoutReflection(modified)
                modified = rewriteStringApiVerificationWithoutReflection(modified)
                modified = rewriteClassForNameIsInstanceChecks(modified)
                modified = rewriteSeasonStateReflectionWithoutReflection(modified)
                val enumRewrite = rewriteClassForNameEnumValueOf(projectDir, javaFile, modified, dryRun)
                modified = enumRewrite.first
                changes.addAll(enumRewrite.second)

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Class.forName reflection -> static class/API checks",
                        before = "Class.forName(...)",
                        after = "direct Class<?> registration, ModList loaded check, or direct enum reference",
                        confidence = Confidence.HIGH,
                        ruleId = "build-class-forname-no-reflection"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        return changes
    }

    private fun rewriteClassForNameIsInstanceChecks(source: String): String {
        val code = maskJavaComments(source)
        val forNameAssignments = Regex("""\b([A-Za-z_$][\w$]*)\s*=\s*Class\.forName\(\s*"([^"]+)"\s*\)\s*;""")
            .findAll(code)

        for (forNameAssignment in forNameAssignments) {
            val classVariable = forNameAssignment.groupValues[1]
            val binaryName = forNameAssignment.groupValues[2]
            val tryStart = findEnclosingTryStartForStatement(code, forNameAssignment.range.first)
            if (tryStart < 0) continue
            val openBrace = code.indexOf('{', tryStart)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
            if (closeBrace <= openBrace) continue
            val tryBody = code.substring(openBrace + 1, closeBrace)
            val searchFrom = (forNameAssignment.range.last - openBrace).coerceAtLeast(0)
            val returnPattern = Regex(
                """return\s+${Regex.escape(classVariable)}\s*!=\s*null\s*&&\s*${Regex.escape(classVariable)}\.isInstance\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;"""
            )
            val returnMatch = returnPattern.find(tryBody, searchFrom) ?: continue
            val valueExpression = returnMatch.groupValues[1]
            val catchEnd = findFollowingCatchBlockEnd(code, closeBrace + 1)
            if (catchEnd <= closeBrace) continue

            var result = source.substring(0, tryStart) +
                "return modporterRuntimeInstanceOf($valueExpression, \"$binaryName\");" +
                source.substring(catchEnd + 1)

            result = Regex("""(?m)^[ \t]*private\s+static\s+Class<\?>\s+${Regex.escape(classVariable)}\s*=\s*null\s*;\s*\r?\n""")
                .replace(result, "")
            result = Regex("""(?m)^[ \t]*private\s+static\s+boolean\s+[A-Za-z_$][\w$]*Resolved\s*=\s*false\s*;\s*\r?\n""")
                .replace(result, "")

            return ensureRuntimeInstanceHelper(result)
        }
        return source
    }

    private fun findEnclosingTryStartForStatement(code: String, offset: Int): Int {
        var searchFrom = offset
        while (searchFrom >= 0) {
            val tryStart = code.lastIndexOf("try", searchFrom)
            if (tryStart < 0) return -1
            val before = code.getOrNull(tryStart - 1)
            val after = code.getOrNull(tryStart + 3)
            if ((before != null && Character.isJavaIdentifierPart(before)) ||
                (after != null && Character.isJavaIdentifierPart(after))) {
                searchFrom = tryStart - 1
                continue
            }
            val openBrace = code.indexOf('{', tryStart)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
            if (openBrace in 0 until offset && closeBrace >= offset) return tryStart
            searchFrom = tryStart - 1
        }
        return -1
    }

    private fun ensureRuntimeInstanceHelper(source: String): String {
        if (source.contains("modporterRuntimeInstanceOf(") &&
            Regex("""private\s+static\s+boolean\s+modporterRuntimeInstanceOf\s*\(""").containsMatchIn(source)) {
            return source
        }
        val insertAt = source.lastIndexOf('}')
        if (insertAt < 0) return source
        val helper = """

    private static boolean modporterRuntimeInstanceOf(Object value, String binaryClassName) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        while (type != null) {
            if (modporterRuntimeTypeMatches(type, binaryClassName)) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean modporterRuntimeTypeMatches(Class<?> type, String binaryClassName) {
        if (binaryClassName.equals(type.getName())) return true;
        for (Class<?> iface : type.getInterfaces()) {
            if (modporterRuntimeTypeMatches(iface, binaryClassName)) return true;
        }
        return false;
    }
""".trimEnd()
        return source.substring(0, insertAt).trimEnd() + helper + System.lineSeparator() + source.substring(insertAt)
    }

    private fun findFollowingCatchBlockEnd(source: String, searchFrom: Int): Int {
        val catchMatch = Regex("""\G\s*catch\s*\([^)]*\)\s*\{""")
            .find(source, searchFrom)
            ?: Regex("""\s*catch\s*\([^)]*\)\s*\{""").find(source, searchFrom)
            ?: return -1
        val openBrace = source.indexOf('{', catchMatch.range.first)
        return if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
    }

    private fun rewriteSeasonStateReflectionWithoutReflection(source: String): String {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        if (!code.contains("getSeasonState") ||
            !code.contains("getSubSeason") ||
            !executableCode.contains("Class.forName(") ||
            !executableCode.contains(".getMethod(")) {
            return source
        }

        val resolveMethodMatch = Regex("""private\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{""")
            .findAll(code)
            .firstOrNull { match ->
                val openBrace = code.indexOf('{', match.range.first)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
                if (closeBrace <= openBrace) return@firstOrNull false
                val methodBody = code.substring(openBrace + 1, closeBrace)
                val executableMethodBody = executableCode.substring(openBrace + 1, closeBrace)
                methodBody.contains("getSeasonState") &&
                    methodBody.contains("getSubSeason") &&
                    executableMethodBody.contains("Class.forName(") &&
                    executableMethodBody.contains(".getMethod(")
            }
            ?: return source
        val resolveOpenBrace = code.indexOf('{', resolveMethodMatch.range.first)
        val resolveCloseBrace = if (resolveOpenBrace >= 0) findMatchingBrace(code, resolveOpenBrace) else -1
        if (resolveCloseBrace <= resolveOpenBrace) return source
        val resolveMethodName = resolveMethodMatch.groupValues[1]
        val resolveBody = code.substring(resolveOpenBrace + 1, resolveCloseBrace)
        val executableResolveBody = executableCode.substring(resolveOpenBrace + 1, resolveCloseBrace)

        fun MatchResult.hasExecutableClassForNameGetMethod(): Boolean {
            val executableSegment = executableResolveBody.substring(range.first, range.last + 1)
            return executableSegment.contains("Class.forName(") &&
                executableSegment.contains(".getMethod(")
        }

        val helperMatch = Regex("""Class<\?>\s+[A-Za-z_$][\w$]*\s*=\s*Class\.forName\(\s*"([^"]+)"\s*\)\s*;\s*([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.getMethod\(\s*"getSeasonState"\s*,\s*Level\.class\s*\)""")
            .findAll(resolveBody)
            .firstOrNull { it.hasExecutableClassForNameGetMethod() }
            ?: return source
        val helperClass = helperMatch.groupValues[1]
        val helperMethodField = helperMatch.groupValues[2]
        val stateMatch = Regex("""Class<\?>\s+[A-Za-z_$][\w$]*\s*=\s*Class\.forName\(\s*"([^"]+)"\s*\)\s*;\s*([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.getMethod\(\s*"getSubSeason"\s*\)""")
            .findAll(resolveBody)
            .firstOrNull { it.hasExecutableClassForNameGetMethod() }
            ?: return source
        val stateClass = stateMatch.groupValues[1]
        val stateMethodField = stateMatch.groupValues[2]
        val subSeasonMatch = Regex("""Class<\?>\s+[A-Za-z_$][\w$]*\s*=\s*Class\.forName\(\s*"([^"]+\$[A-Za-z_$][\w$]*)"\s*\)\s*;\s*([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.getMethod\(\s*"name"\s*\)""")
            .findAll(resolveBody)
            .firstOrNull { it.hasExecutableClassForNameGetMethod() }
            ?: return source
        val subSeasonBinary = subSeasonMatch.groupValues[1]
        val subSeasonNameField = subSeasonMatch.groupValues[2]

        val helperSimple = helperClass.substringAfterLast('.')
        val stateSimple = stateClass.substringAfterLast('.')
        val subSeasonType = subSeasonBinary.substringAfter('$')

        var result = source.substring(0, resolveMethodMatch.range.first).trimEnd() +
            System.lineSeparator() + System.lineSeparator() +
            source.substring(resolveCloseBrace + 1).trimStart()

        result = Regex("""(?m)^[ \t]*private\s+static\s+boolean\s+[A-Za-z_$][\w$]*Resolved\s*=\s*false\s*;\s*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*private\s+static\s+Method\s+[A-Za-z_$][\w$]*\s*;\s*\r?\n""")
            .replace(result, "")
        result = Regex("""(?m)^[ \t]*${Regex.escape(resolveMethodName)}\(\);\s*\r?\n""").replace(result, "")
        result = Regex("""(?m)^[ \t]*if\s*\(\s*${Regex.escape(helperMethodField)}\s*==\s*null\s*\)\s*return\s+[A-Za-z_$][\w$]*\.[A-Za-z_$][\w$]*;\s*\r?\n""")
            .replace(result, "")
        result = Regex("""Object\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(helperMethodField)}\.invoke\(\s*null\s*,\s*([A-Za-z_$][\w$]*)\s*\)\s*;""")
            .replace(result) { match -> "$stateSimple ${match.groupValues[1]} = $helperSimple.getSeasonState(${match.groupValues[2]});" }
        result = Regex("""Object\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(stateMethodField)}\.invoke\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;""")
            .replace(result) { match -> "$subSeasonType ${match.groupValues[1]} = ${match.groupValues[2]}.getSubSeason();" }
        result = Regex("""String\s+([A-Za-z_$][\w$]*)\s*=\s*\(String\)\s*${Regex.escape(subSeasonNameField)}\.invoke\(\s*([A-Za-z_$][\w$]*)\s*\)\s*;""")
            .replace(result) { match -> "String ${match.groupValues[1]} = ${match.groupValues[2]}.name();" }

        result = removeJavaImport(result, "java.lang.reflect.Method")
        result = ensureJavaImport(result, helperClass)
        result = ensureJavaImport(result, stateClass)
        result = ensureJavaImport(result, subSeasonBinary.replace('$', '.'))
        return result
    }

    private fun removeRedundantClassForNameOnClassObjects(source: String): String {
        val code = maskJavaCommentsAndLiterals(source)
        val pattern = Regex("""(?m)^[ \t]*Class\.forName\(\s*([A-Za-z_$][\w$]*)\.getName\(\)\s*,\s*true\s*,\s*\1\.getClassLoader\(\)\s*\);\s*\r?\n""")
        val matches = pattern.findAll(code).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            result = result.substring(0, match.range.first) + result.substring(match.range.last + 1)
        }
        return result
    }

    private fun rewriteClassForNamePresenceChecksWithoutReflection(source: String): String {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val pattern = Regex(
            """try\s*\{\s*Class\.forName\(\s*"([^"]+)"(?:\s*,\s*false\s*,\s*.*?)?\s*\)\s*;\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*=\s*true\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*(?:\2\s*=\s*false\s*;)?\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val executablePattern = Regex(
            """try\s*\{\s*Class\.forName\(\s*(?:,\s*false\s*,\s*.*?)?\)\s*;\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*=\s*true\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{\s*(?:\1\s*=\s*false\s*;)?\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val matches = pattern.findAll(code)
            .filter { match ->
                val executableMatch = executablePattern.matchEntire(
                    executableCode.substring(match.range.first, match.range.last + 1)
                )
                executableMatch?.groupValues?.get(1) == match.groupValues[2]
            }
            .toList()
        if (matches.isEmpty()) return source

        var rewritten = source
        for (match in matches.asReversed()) {
            val replacement = """${match.groupValues[2]} = modporterClassResourcePresent("${match.groupValues[1]}");"""
            rewritten = rewritten.substring(0, match.range.first) +
                replacement +
                rewritten.substring(match.range.last + 1)
        }
        return ensureClassResourcePresenceHelper(rewritten)
    }

    private fun ensureClassResourcePresenceHelper(source: String): String {
        if (Regex("""private\s+static\s+boolean\s+modporterClassResourcePresent\s*\(""").containsMatchIn(source)) {
            return source
        }
        val insertAt = source.lastIndexOf('}')
        if (insertAt < 0) return source
        val helper = """

    private static boolean modporterClassResourcePresent(String binaryClassName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = ClassLoader.getSystemClassLoader();
        return classLoader != null && classLoader.getResource(binaryClassName.replace('.', '/') + ".class") != null;
    }
""".trimEnd()
        return source.substring(0, insertAt).trimEnd() + helper + System.lineSeparator() + source.substring(insertAt)
    }

    private fun rewriteStringApiVerificationWithoutReflection(source: String): String {
        val code = maskJavaCommentsAndLiterals(source)
        val methodMatch = Regex(
            """public\s+static\s+void\s+verifyApiClasses\s*\(\s*String\s+([A-Za-z_$][\w$]*)\s*,\s*String\.\.\.\s+([A-Za-z_$][\w$]*)\s*\)\s*\{"""
        ).find(code) ?: return source

        val openBrace = code.indexOf('{', methodMatch.range.first)
        val closeBrace = if (openBrace >= 0) findMatchingBrace(code, openBrace) else -1
        if (closeBrace <= openBrace) return source
        val body = code.substring(openBrace + 1, closeBrace)
        if (!body.contains("Class.forName(")) return source

        val modIdParam = methodMatch.groupValues[1]
        val classNamesParam = methodMatch.groupValues[2]
        val replacement = """
public static void verifyApiClasses(String $modIdParam, String... $classNamesParam) {
        if (!ModList.get().isLoaded($modIdParam)) {
            throw new RuntimeException("API verification failed for mod '" + $modIdParam + "': mod is not loaded.");
        }
        LOGGER.debug("API verification for '{}' will be enforced by static linkage during compat initialization ({} declared classes).", $modIdParam, $classNamesParam.length);
    }
""".trimIndent()

        return ensureJavaImport(
            source.substring(0, methodMatch.range.first) + replacement + source.substring(closeBrace + 1),
            "net.neoforged.fml.ModList"
        )
    }

    private fun rewriteClassForNameEnumValueOf(
        projectDir: Path,
        javaFile: Path,
        source: String,
        dryRun: Boolean
    ): Pair<String, List<Change>> {
        var result = source
        val changes = mutableListOf<Change>()
        val packageName = Regex("""(?m)^package\s+([^;]+);""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: return source to emptyList()
        val tryPattern = Regex(
            """try\s*\{\s*Class<\?>\s+([A-Za-z_$][\w$]*)\s*=\s*Class\.forName\(\s*"([^"]+\$([A-Za-z_$][\w$]*))"\s*\)\s*;\s*return\s+Enum\.valueOf\(\s*\(Class<\? extends Enum>\)\s*\1\.asSubclass\(Enum\.class\)\s*,\s*"([A-Za-z_$][\w$]*)"\s*\)\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{[^{}]*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val executablePattern = Regex(
            """try\s*\{\s*Class<\?>\s+([A-Za-z_$][\w$]*)\s*=\s*Class\.forName\(\s*\)\s*;\s*return\s+Enum\.valueOf\(\s*\(Class<\? extends Enum>\)\s*\1\.asSubclass\(Enum\.class\)\s*,\s*\)\s*;\s*\}\s*catch\s*\(\s*ClassNotFoundException\s+[A-Za-z_$][\w$]*\s*\)\s*\{[^{}]*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val matches = tryPattern.findAll(code)
            .filter { match ->
                val executableMatch = executablePattern.matchEntire(
                    executableCode.substring(match.range.first, match.range.last + 1)
                )
                executableMatch?.groupValues?.get(1) == match.groupValues[1]
            }
            .toList()
        if (matches.isEmpty()) return source to emptyList()
        val accessorImports = linkedSetOf<String>()

        for (match in matches.asReversed()) {
            val binaryName = match.groupValues[2]
            val simpleNestedName = match.groupValues[3]
            val enumConstant = match.groupValues[4]
            val accessorName = "ModPorter${simpleNestedName}Accessor"
            accessorImports.add("${generatedMixinPackage(packageName)}.$accessorName")
            result = result.substring(0, match.range.first) +
                "return $accessorName.modporter${'$'}valueOf(\"$enumConstant\");" +
                result.substring(match.range.last + 1)
            changes.addAll(ensureNestedEnumMixinInvoker(
                projectDir = projectDir,
                sourceFile = javaFile,
                packageName = packageName,
                accessorName = accessorName,
                targetBinaryName = binaryName,
                dryRun = dryRun
            ))
        }

        accessorImports.forEach { importName ->
            result = addJavaImportIfMissing(result, importName)
        }
        if (!result.contains("Class<? extends Enum>") && !result.contains("Enum.valueOf(")) {
            result = Regex("""(?m)^[ \t]*@SuppressWarnings\(\s*\{?\s*"rawtypes"\s*,\s*"unchecked"\s*\}?\s*\)\s*\r?\n""")
                .replace(result, "")
        }
        return result to changes
    }

    private fun rewriteDeferredHolderReflectionCollectors(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val filesByClassName = javaFiles.associateBy { it.fileName.toString().removeSuffix(".java") }
        val changes = mutableListOf<Change>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (!original.contains("getDeclaredFields()") ||
                !original.contains("DeferredHolder.class.isAssignableFrom") ||
                !original.contains("java.lang.reflect.Field")) {
                continue
            }

            var modified = original
            val methodPattern = Regex(
                """(?m)^([ \t]*)private\s+static\s+List\s*<\s*DeferredHolder\s*<\s*([^>]+?)\s*>\s*>\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{"""
            )
            for (methodMatch in methodPattern.findAll(original).toList().asReversed()) {
                val openBrace = modified.indexOf('{', methodMatch.range.last)
                val closeBrace = if (openBrace >= 0) findMatchingBrace(modified, openBrace) else -1
                if (closeBrace <= openBrace) continue
                val body = modified.substring(openBrace + 1, closeBrace)
                val registryClass = Regex(
                    """for\s*\(\s*Field\s+[A-Za-z_$][\w$]*\s*:\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.class\.getDeclaredFields\(\)\s*\)"""
                ).find(body)?.groupValues?.get(1) ?: continue
                val registrySimpleName = registryClass.substringAfterLast('.')
                val registryFile = filesByClassName[registrySimpleName] ?: continue
                val deferredHolderFields = collectPublicStaticDeferredHolderFields(registryFile.readText())
                if (deferredHolderFields.isEmpty()) continue

                val indent = methodMatch.groupValues[1]
                val generic = methodMatch.groupValues[2].trim()
                val methodName = methodMatch.groupValues[3]
                val listType = "List<DeferredHolder<$generic>>"
                val addLines = deferredHolderFields.joinToString(System.lineSeparator()) { fieldName ->
                    "$indent    out.add($registryClass.$fieldName);"
                }
                val replacement = buildString {
                    append(indent)
                    append("private static ")
                    append(listType)
                    append(" ")
                    append(methodName)
                    append("() {")
                    append(System.lineSeparator())
                    append(indent)
                    append("    ")
                    append(listType)
                    append(" out = new ArrayList<>();")
                    append(System.lineSeparator())
                    append(addLines)
                    append(System.lineSeparator())
                    append(indent)
                    append("    return out;")
                    append(System.lineSeparator())
                    append(indent)
                    append("}")
                }
                modified = modified.substring(0, methodMatch.range.first) +
                    replacement +
                    modified.substring(closeBrace + 1)
            }

            if (modified != original) {
                modified = removeJavaImport(modified, "java.lang.reflect.Field")
                modified = removeJavaImport(modified, "java.lang.reflect.Modifier")
                if (!modified.contains("new ArrayList<>()")) {
                    modified = removeJavaImport(modified, "java.util.ArrayList")
                }
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Rewrite DeferredHolder registry field reflection collector to explicit source-derived list",
                    before = "Registry.class.getDeclaredFields() + java.lang.reflect.Field",
                    after = "explicit registry DeferredHolder field list",
                    confidence = Confidence.HIGH,
                    ruleId = "build-deferredholder-reflection-collector"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        return changes
    }

    private fun collectPublicStaticDeferredHolderFields(source: String): List<String> =
        Regex(
            """(?m)^[ \t]*public\s+static\s+final\s+DeferredHolder\s*<[^;=]+>\s+([A-Za-z_$][\w$]*)\s*="""
        ).findAll(source)
            .map { it.groupValues[1] }
            .toList()

    private fun ensureNestedEnumMixinInvoker(
        projectDir: Path,
        sourceFile: Path,
        packageName: String,
        accessorName: String,
        targetBinaryName: String,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val generatedPackageName = generatedMixinPackage(packageName)
        val accessorFile = sourceFile.parent.resolve("modporter").resolve("mixin").resolve("$accessorName.java")
        val accessorSource = """
package $generatedPackageName;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "$targetBinaryName", remap = false)
public interface $accessorName {
    @Invoker(value = "valueOf", remap = false)
    static Object modporter${'$'}valueOf(String name) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
""".trimIndent()

        requireMixinConfigRegistrationTarget(projectDir, generatedPackageName, accessorName)
        if (!accessorFile.exists() || accessorFile.readText() != accessorSource) {
            changes.add(Change(
                file = accessorFile,
                line = 1,
                description = "Generate mixin invoker for package-private nested enum access",
                before = "(missing invoker)",
                after = "$accessorName targets $targetBinaryName",
                confidence = Confidence.HIGH,
                ruleId = "build-class-forname-enum-mixin-invoker"
            ))
            if (!dryRun) {
                accessorFile.parent.createDirectories()
                accessorFile.writeText(accessorSource)
            }
        }

        changes.addAll(ensureMixinConfigEntry(projectDir, generatedPackageName, accessorName, dryRun))
        return changes
    }

    private fun javaPackageName(source: String): String? =
        Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)

    private fun generatedMixinPackage(packageName: String): String =
        "$packageName.modporter.mixin"

    private fun ensureMixinInvoker(
        projectDir: Path,
        sourceFile: Path,
        packageName: String,
        invokerName: String,
        targetClassName: String,
        targetImport: String,
        extraImports: List<String>,
        methodSource: String,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val generatedPackageName = generatedMixinPackage(packageName)
        val invokerFile = sourceFile.parent.resolve("modporter").resolve("mixin").resolve("$invokerName.java")
        val imports = (extraImports + targetImport + listOf(
            "org.spongepowered.asm.mixin.Mixin",
            "org.spongepowered.asm.mixin.gen.Invoker"
        )).distinct().sorted().joinToString(System.lineSeparator()) { "import $it;" }
        val invokerSource = """
package $generatedPackageName;

$imports

@Mixin($targetClassName.class)
public interface $invokerName {
    ${methodSource.prependIndent("    ").trimStart()}
}
""".trimIndent()

        requireMixinConfigRegistrationTarget(projectDir, generatedPackageName, invokerName)
        if (!invokerFile.exists() || invokerFile.readText() != invokerSource) {
            changes.add(Change(
                file = invokerFile,
                line = 1,
                description = "Generate mixin invoker for protected vanilla method access",
                before = "(missing invoker)",
                after = "$invokerName targets $targetImport",
                confidence = Confidence.HIGH,
                ruleId = "build-protected-method-mixin-invoker"
            ))
            if (!dryRun) {
                invokerFile.parent.createDirectories()
                invokerFile.writeText(invokerSource)
            }
        }

        changes.addAll(ensureMixinConfigEntry(projectDir, generatedPackageName, invokerName, dryRun))
        return changes
    }

    private fun requireMixinConfigRegistrationTarget(
        projectDir: Path,
        packageName: String,
        mixinClassName: String
    ) {
        if (hasMixinConfigRegistrationTarget(projectDir, packageName, mixinClassName)) return
        generatedMixinConfigName(projectDir)
    }

    private fun hasMixinConfigRegistrationTarget(
        projectDir: Path,
        packageName: String,
        mixinClassName: String
    ): Boolean {
        val resourcesDir = projectDir.resolve("src/main/resources")
        if (!resourcesDir.exists()) return false
        return java.nio.file.Files.walk(resourcesDir).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".mixins.json") }
                .anyMatch { mixinConfig ->
                    val source = mixinConfig.readText()
                    val configPackage = Regex(""""package"\s*:\s*"([^"]+)"""")
                        .find(source)
                        ?.groupValues
                        ?.get(1)
                        ?: return@anyMatch false
                    relativeMixinNameForConfigPackage(packageName, mixinClassName, configPackage) != null &&
                        (packageName == configPackage || Regex(""""plugin"\s*:""").containsMatchIn(source))
                }
        }
    }

    private fun ensureMixinConfigEntry(
        projectDir: Path,
        packageName: String,
        mixinClassName: String,
        dryRun: Boolean
    ): List<Change> {
        val resourcesDir = projectDir.resolve("src/main/resources")
        if (!resourcesDir.exists()) {
            if (dryRun) return emptyList()
            resourcesDir.createDirectories()
        }

        val changes = mutableListOf<Change>()
        var registeredInExistingConfig = false
        val candidates = mutableListOf<MixinConfigCandidate>()
        java.nio.file.Files.walk(resourcesDir)
            .filter { it.fileName.toString().endsWith(".mixins.json") }
            .forEach { mixinConfig ->
                val original = mixinConfig.readText()
                val configPackage = Regex(""""package"\s*:\s*"([^"]+)"""")
                    .find(original)
                    ?.groupValues
                    ?.get(1)
                    ?: return@forEach
                val relativeMixinName = relativeMixinNameForConfigPackage(packageName, mixinClassName, configPackage)
                    ?: return@forEach
                if (packageName != configPackage && !Regex(""""plugin"\s*:""").containsMatchIn(original)) {
                    return@forEach
                }
                candidates.add(MixinConfigCandidate(mixinConfig, original, configPackage, relativeMixinName))
            }

        val selected = candidates.maxWithOrNull(
            compareBy<MixinConfigCandidate> { it.configPackage.length }
                .thenBy { if (it.configPackage == packageName) 1 else 0 }
        )
        if (selected != null) {
            val modified = if (Regex(""""${Regex.escape(selected.relativeMixinName)}"""").containsMatchIn(selected.source)) {
                selected.source
            } else {
                addMixinClassToConfig(selected.source, selected.configPackage, selected.relativeMixinName) ?: selected.source
            }
            if (modified != selected.source) {
                changes.add(Change(
                    file = selected.file,
                    line = selected.source.lineNumberAt(selected.source.indexOf(""""mixins"""").coerceAtLeast(0)),
                    description = "Register generated mixin invoker in mixin config",
                    before = "mixins array without ${selected.relativeMixinName}",
                    after = "mixins array includes ${selected.relativeMixinName}",
                    confidence = Confidence.HIGH,
                    ruleId = "build-register-generated-mixin-invoker"
                ))
                if (!dryRun) selected.file.writeText(modified)
            }
            registeredInExistingConfig = true
            candidates
                .filter { it.file != selected.file }
                .forEach { stale ->
                    val cleaned = removeMixinClassFromConfig(stale.source, stale.relativeMixinName)
                    if (cleaned != stale.source) {
                        changes.add(Change(
                            file = stale.file,
                            line = stale.source.lineNumberAt(stale.source.indexOf(stale.relativeMixinName).coerceAtLeast(0)),
                            description = "Remove generated mixin invoker from less-specific mixin config",
                            before = "mixins array includes ${stale.relativeMixinName}",
                            after = "mixins array omits ${stale.relativeMixinName}",
                            confidence = Confidence.HIGH,
                            ruleId = "build-prune-generated-mixin-invoker"
                        ))
                        if (!dryRun) stale.file.writeText(cleaned)
                    }
                }
        }
        if (!registeredInExistingConfig) {
            val configName = generatedMixinConfigName(projectDir)
            val mixinConfig = resourcesDir.resolve(configName)
            val original = if (mixinConfig.exists()) mixinConfig.readText() else ""
            val modified = if (original.isBlank()) {
                """
{
  "required": true,
  "minVersion": "0.8",
  "package": "$packageName",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "$mixinClassName"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
""".trimIndent() + System.lineSeparator()
            } else {
                addMixinClassToConfig(original, packageName, mixinClassName) ?: original
            }
            if (modified != original) {
                changes.add(Change(
                    file = mixinConfig,
                    line = 1,
                    description = "Create or update generated mixin config",
                    before = "(missing mixin config entry)",
                    after = "$configName includes $mixinClassName",
                    confidence = Confidence.HIGH,
                    ruleId = "build-generated-mixin-config"
                ))
                if (!dryRun) mixinConfig.writeText(modified)
            }
            changes.addAll(ensureNeoForgeModsTomlMixinEntry(projectDir, configName, dryRun))
        }
        return changes
    }

    private data class MixinConfigCandidate(
        val file: Path,
        val source: String,
        val configPackage: String,
        val relativeMixinName: String
    )

    private fun relativeMixinNameForConfigPackage(
        generatedPackageName: String,
        mixinClassName: String,
        configPackageName: String
    ): String? {
        return when {
            generatedPackageName == configPackageName -> mixinClassName
            generatedPackageName.startsWith("$configPackageName.") ->
                generatedPackageName.removePrefix("$configPackageName.") + "." + mixinClassName
            else -> null
        }
    }

    private fun addMixinClassToConfig(source: String, packageName: String, mixinClassName: String): String? {
        if (!Regex(""""package"\s*:\s*"${Regex.escape(packageName)}"""").containsMatchIn(source)) return null
        if (Regex(""""${Regex.escape(mixinClassName)}"""").containsMatchIn(source)) return source
        val mixinsMatch = Regex(""""mixins"\s*:\s*\[""").find(source) ?: return null
        val openBracket = source.indexOf('[', mixinsMatch.range.first)
        val closeBracket = if (openBracket >= 0) findClosing(source, openBracket, '[', ']') else -1
        if (closeBracket <= openBracket) return null
        val inside = source.substring(openBracket + 1, closeBracket)
        val insertion = if (inside.trim().isEmpty()) {
            "\n    \"$mixinClassName\"\n  "
        } else {
            ",\n    \"$mixinClassName\""
        }
        return source.substring(0, closeBracket) + insertion + source.substring(closeBracket)
    }

    private fun removeMixinClassFromConfig(source: String, mixinClassName: String): String {
        val mixinsMatch = Regex(""""mixins"\s*:\s*\[""").find(source) ?: return source
        val openBracket = source.indexOf('[', mixinsMatch.range.first)
        val closeBracket = if (openBracket >= 0) findClosing(source, openBracket, '[', ']') else -1
        if (closeBracket <= openBracket) return source
        val inside = source.substring(openBracket + 1, closeBracket)
        val entries = Regex(""""([^"]+)"""")
            .findAll(inside)
            .map { it.groupValues[1] }
            .toList()
        if (mixinClassName !in entries) return source
        val remaining = entries.filterNot { it == mixinClassName }
        val indent = Regex("\\r?\\n([ \\t]*)\"").find(inside)?.groupValues?.get(1) ?: "    "
        val replacement = if (remaining.isEmpty()) {
            "\n  "
        } else {
            "\n" + remaining.joinToString(",\n") { "$indent\"$it\"" } + "\n  "
        }
        return source.substring(0, openBracket + 1) + replacement + source.substring(closeBracket)
    }

    private fun generatedMixinConfigName(projectDir: Path): String {
        val modId = detectUniqueProjectModId(projectDir)
            ?: error("Cannot derive generated mixin config name: missing or ambiguous @Mod annotation and mod metadata mod id")
        return "$modId.modporter.mixins.json"
    }

    private fun registerExistingMixinConfigs(projectDir: Path, dryRun: Boolean): List<Change> {
        val resourcesDir = projectDir.resolve("src/main/resources")
        if (!resourcesDir.exists()) return emptyList()

        val configNames = java.nio.file.Files.walk(resourcesDir).use { paths ->
            paths
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".mixins.json") }
                .map { mixinConfig ->
                    val source = mixinConfig.readText()
                    val isConfig = Regex(""""package"\s*:\s*"[^"]+"""").containsMatchIn(source) &&
                        Regex(""""(?:mixins|client|server)"\s*:\s*\[""").containsMatchIn(source)
                    if (!isConfig) {
                        null
                    } else {
                        resourcesDir.relativize(mixinConfig).joinToString("/")
                    }
                }
                .filter { it != null }
                .map { it!! }
                .distinct()
                .sorted()
                .toList()
        }
        if (configNames.isEmpty()) return emptyList()

        return ensureNeoForgeModsTomlMixinEntries(
            projectDir = projectDir,
            configNames = configNames,
            dryRun = dryRun,
            description = "Register existing mixin config in NeoForge mods metadata",
            ruleId = "build-register-existing-mixin-config"
        )
    }

    private fun ensureNeoForgeModsTomlMixinEntry(projectDir: Path, configName: String, dryRun: Boolean): List<Change> {
        return ensureNeoForgeModsTomlMixinEntries(
            projectDir = projectDir,
            configNames = listOf(configName),
            dryRun = dryRun,
            description = "Register generated mixin config in NeoForge mods metadata",
            ruleId = "build-register-generated-mixin-config"
        )
    }

    private fun ensureNeoForgeModsTomlMixinEntries(
        projectDir: Path,
        configNames: List<String>,
        dryRun: Boolean,
        description: String,
        ruleId: String
    ): List<Change> {
        val modsToml = listOf(
            projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
            projectDir.resolve("src/main/resources/META-INF/mods.toml")
        ).firstOrNull { it.exists() } ?: return emptyList()
        val original = modsToml.readText()
        val missing = configNames
            .distinct()
            .filterNot { configName ->
                Regex("""(?m)^\s*config\s*=\s*["']${Regex.escape(configName)}["']""").containsMatchIn(original)
            }
        if (missing.isEmpty()) {
            return emptyList()
        }
        val entries = missing.joinToString(System.lineSeparator() + System.lineSeparator()) { configName ->
            """
[[mixins]]
config="$configName"
""".trimIndent()
        } + System.lineSeparator()
        val modified = original.trimEnd() + System.lineSeparator() + System.lineSeparator() + entries
        if (!dryRun) modsToml.writeText(modified)
        return missing.map { configName ->
            Change(
                file = modsToml,
                line = original.lines().size,
                description = description,
                before = "neoforge.mods.toml without [[mixins]] $configName",
                after = "[[mixins]] config=\"$configName\"",
                confidence = Confidence.HIGH,
                ruleId = ruleId
            )
        }
    }

    private fun cleanupSplitTickPhaseChecks(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                var modified = original
                val executableOriginal = maskJavaCommentsAndLiterals(original)
                val hadStartPhase = executableOriginal.contains("event.phase == TickEvent.Phase.START") ||
                    executableOriginal.contains("event.phase != TickEvent.Phase.START")

                if (hadStartPhase) {
                    modified = replaceExecutableRegex(
                        modified,
                        Regex("""\bRenderFrameEvent\.Post\b""")
                    ) { "RenderFrameEvent.Pre" }
                }

                modified = replaceExecutableRegex(
                    modified,
                    Regex("""(?m)^[ \t]*if\s*\(\s*event\.phase\s*!=\s*TickEvent\.Phase\.END\s*\)\s*return;\s*\r?\n""")
                ) { "" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""(?m)^[ \t]*if\s*\(\s*event\.phase\s*!=\s*TickEvent\.Phase\.START\s*\)\s*return;\s*\r?\n""")
                ) { "" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""if\s*\(\s*event\.phase\s*!=\s*TickEvent\.Phase\.END\s*\|\|\s*([^{}\r\n;]+?)\s*\)\s*\{""")
                ) { match -> "if (${match.groupValues[1].trim()}) {" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""if\s*\(\s*([^{}\r\n;]+?)\s*\|\|\s*event\.phase\s*!=\s*TickEvent\.Phase\.END\s*\)\s*\{""")
                ) { match -> "if (${match.groupValues[1].trim()}) {" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""event\.phase\s*==\s*TickEvent\.Phase\.END\s*&&\s*""")
                ) { "" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""\s*&&\s*event\.phase\s*==\s*TickEvent\.Phase\.END""")
                ) { "" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""if\s*\(\s*event\.phase\s*==\s*TickEvent\.Phase\.START\s*\)\s*\{""")
                ) { "{" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""event\.phase\s*==\s*TickEvent\.Phase\.START\s*&&\s*""")
                ) { "" }
                modified = replaceExecutableRegex(
                    modified,
                    Regex("""\s*&&\s*event\.phase\s*==\s*TickEvent\.Phase\.START""")
                ) { "" }

                val executableModified = maskJavaCommentsAndLiterals(modified)
                if (!executableModified.contains("TickEvent.Phase") &&
                    (containsJavaIdentifier(executableModified, "TickEvent") || hasTickEventImportLine(executableModified))) {
                    modified = removeJavaImport(modified, "net.minecraftforge.event.TickEvent")
                    modified = removeJavaImport(modified, "net.neoforged.neoforge.event.TickEvent")
                    modified = removeJavaImport(modified, "net.neoforged.neoforge.event.tick.TickEvent")
                    modified = modified.lines()
                        .filterNot { line ->
                            line.trim() == "import net.minecraftforge.event.TickEvent;" ||
                                line.trim() == "import net.neoforged.neoforge.event.TickEvent;" ||
                                line.trim() == "import net.neoforged.neoforge.event.tick.TickEvent;"
                        }
                        .joinToString(System.lineSeparator())
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Remove legacy TickEvent phase checks after NeoForge split tick events",
                        before = "event.phase ==/!= TickEvent.Phase.*",
                        after = "split Pre/Post event handlers without phase checks",
                        confidence = Confidence.HIGH,
                        ruleId = "build-cleanup-split-tick-phase"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        return changes
    }

    private fun containsJavaIdentifier(source: String, identifier: String): Boolean {
        var index = source.indexOf(identifier)
        while (index >= 0) {
            val before = source.getOrNull(index - 1)
            val after = source.getOrNull(index + identifier.length)
            if ((before == null || !Character.isJavaIdentifierPart(before)) &&
                (after == null || !Character.isJavaIdentifierPart(after))) {
                return true
            }
            index = source.indexOf(identifier, index + identifier.length)
        }
        return false
    }

    private fun hasTickEventImportLine(executableSource: String): Boolean =
        executableSource.lineSequence().any { line ->
            when (line.trim()) {
                "import net.minecraftforge.event.TickEvent;",
                "import net.neoforged.neoforge.event.TickEvent;",
                "import net.neoforged.neoforge.event.tick.TickEvent;" -> true
                else -> false
            }
        }

    private fun migrateRemovedTitleScreenAccessors(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val changes = mutableListOf<Change>()
        val removedMethods = linkedSetOf<String>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (!original.contains("@Mixin(TitleScreen.class)") || !original.contains("@Accessor")) continue

            val (withoutAccessors, methods) = removeRemovedTitleScreenAccessorMethods(original)
            if (withoutAccessors == original) continue

            val modified = cleanupTitleScreenRemovedAccessorImports(withoutAccessors)
            if (modified != original) {
                removedMethods.addAll(methods)
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Remove Mixin accessors for TitleScreen fields and inner classes removed in 1.21",
                    before = methods.joinToString(", "),
                    after = "removed accessors for non-existent TitleScreen targets",
                    confidence = Confidence.HIGH,
                    ruleId = "build-title-screen-removed-accessors"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        if (removedMethods.isNotEmpty()) {
            for (javaFile in javaFiles) {
                val original = javaFile.readText()
                val modified = cleanupTitleScreenRemovedAccessorImports(
                    removeRemovedTitleScreenAccessorCallStatements(original, removedMethods)
                )
                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Remove direct calls that only target removed TitleScreen accessor methods",
                        before = removedMethods.joinToString(", "),
                        after = "removed direct obsolete accessor call statements",
                        confidence = Confidence.HIGH,
                        ruleId = "build-title-screen-removed-accessor-calls"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }
        }

        changes.addAll(removeUnusedTitleScreenUpdateIndicatorClasses(javaFiles, removedMethods, dryRun))
        return changes
    }

    private fun removeRemovedTitleScreenAccessorMethods(source: String): Pair<String, Set<String>> {
        val removed = linkedSetOf<String>()
        val methodPattern = Regex(
            """(?ms)^([ \t]*(?:@[^\r\n]+(?:\r?\n|[ \t]+))*[ \t]*(?:[\w.$<>\[\], ?]+\s+)+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*;\s*)"""
        )
        var modified = methodPattern.replace(source) { match ->
            val block = match.groupValues[1]
            val methodName = match.groupValues[2]
            val isRemovedTitleScreenAccessor = block.contains("@Accessor") && (
                block.contains("TitleScreen.WarningLabel") ||
                    block.contains("TitleScreenModUpdateIndicator") ||
                    Regex("""@Accessor\s*\(\s*(?:value\s*=\s*)?"panorama"""").containsMatchIn(block)
                )
            if (isRemovedTitleScreenAccessor) {
                removed.add(methodName)
                ""
            } else {
                match.value
            }
        }
        val removedTypeAccessorPatterns = listOf(
            Regex("""(?ms)^[ \t]*(?:@[^\r\n]+\r?\n)+[ \t]*TitleScreenModUpdateIndicator\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*;\s*"""),
            Regex("""(?ms)^[ \t]*(?:@[^\r\n]+\r?\n)+[ \t]*void\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*TitleScreenModUpdateIndicator[^;{}]*\)\s*;\s*"""),
            Regex("""(?ms)^[ \t]*(?:@[^\r\n]+\r?\n)+[ \t]*TitleScreen\.WarningLabel\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*;\s*""")
        )
        for (pattern in removedTypeAccessorPatterns) {
            modified = pattern.replace(modified) { match ->
                removed.add(match.groupValues[1])
                ""
            }
        }
        return modified to removed
    }

    private fun removeRemovedTitleScreenAccessorCallStatements(source: String, removedMethods: Set<String>): String {
        var result = source
        for (method in removedMethods) {
            val callStatement = Regex(
                """(?m)^[ \t]*[^\r\n]*\.${Regex.escape(method)}\s*\([^\r\n]*\)[^\r\n]*;\s*(?:\r?\n)?"""
            )
            result = callStatement.replace(result, "")
        }
        return result
    }

    private fun removeUnusedTitleScreenUpdateIndicatorClasses(
        javaFiles: List<Path>,
        removedMethods: Set<String>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val currentFiles = javaFiles.filter { it.exists() }
        val sources = currentFiles.associateWith {
            removeRemovedTitleScreenAccessorCallStatements(it.readText(), removedMethods)
        }
        val updateIndicatorClasses = sources.mapNotNull { (file, source) ->
            val executableCode = maskJavaCommentsAndLiterals(source)
            val className = Regex(
                """\bclass\s+([A-Za-z_$][\w$]*)\b[\s\S]*?\bextends\s+(?:[A-Za-z_$][\w$]*\.)*TitleScreenModUpdateIndicator\b"""
            )
                .find(executableCode)
                ?.groupValues
                ?.get(1)
                ?: return@mapNotNull null
            file to className
        }

        for ((file, className) in updateIndicatorClasses) {
            val hasExternalReference = sources.any { (otherFile, source) ->
                otherFile != file && Regex("""\b${Regex.escape(className)}\b""").containsMatchIn(source)
            }
            if (hasExternalReference) continue

            changes.add(Change(
                file = file,
                line = 1,
                description = "Remove unused TitleScreenModUpdateIndicator subclass after the NeoForge title-screen update widget was removed",
                before = className,
                after = "(deleted unused removed-API subclass)",
                confidence = Confidence.HIGH,
                ruleId = "build-title-screen-update-indicator-class"
            ))
            if (!dryRun) file.deleteIfExists()
        }

        return changes
    }

    private fun cleanupTitleScreenRemovedAccessorImports(source: String): String {
        var result = source
        result = removeJavaImportIfSimpleNameUnused(result, "net.neoforged.neoforge.client.gui.TitleScreenModUpdateIndicator")
        result = removeJavaImportIfSimpleNameUnused(result, "net.minecraft.client.renderer.PanoramaRenderer")
        return result
    }

    private fun migrateAccessTransformers(projectDir: Path, dryRun: Boolean): List<Change> {
        val requiredEntries = collectRequiredAccessTransformerEntries(projectDir)
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        if (!atFile.exists() && requiredEntries.isEmpty()) return emptyList()

        val changes = mutableListOf<Change>()
        val original = if (atFile.exists()) atFile.readText() else ""
        val normalizedLines = original.lines().map { originalLine ->
            val migrated = migrateAccessTransformerLine(originalLine)
            originalLine to migrated
        }
        val entries = linkedSetOf<String>()
        val output = mutableListOf<String>()
        var lineChanged = false

        for ((originalLine, line) in normalizedLines) {
            if (line != originalLine) {
                lineChanged = true
            }
            val entry = normalizeAccessTransformerEntry(line)
            if (entry != null) {
                if (entries.add(entry)) {
                    output.add(line)
                } else {
                    lineChanged = true
                }
            } else {
                output.add(line)
            }
        }

        for (entry in requiredEntries) {
            if (entries.add(entry)) {
                if (output.isNotEmpty() && output.last().isNotBlank()) output.add("")
                output.add(entry)
            }
        }

        val modified = output.joinToString(System.lineSeparator()).trimEnd() +
            if (output.isNotEmpty()) System.lineSeparator() else ""
        if (modified != original || lineChanged) {
            changes.add(Change(
                file = atFile,
                line = 1,
                description = "Migrate and complete Access Transformer entries for 1.21 named members",
                before = "1.20 SRG access transformer entries or missing AT file",
                after = "1.21 named access transformer entries",
                confidence = Confidence.HIGH,
                ruleId = "build-access-transformer-entries-121"
            ))
            if (!dryRun) {
                atFile.parent.createDirectories()
                atFile.writeText(modified)
            }
        }

        return changes
    }

    private fun migrateAccessTransformerLine(line: String): String {
        val entry = normalizeAccessTransformerEntry(line) ?: return line
        val migrated = when (entry) {
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73379_" ->
                "protected net.minecraft.world.level.levelgen.structure.StructurePiece rotation"
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73378_" ->
                "protected net.minecraft.world.level.levelgen.structure.StructurePiece mirror"
            "protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73377_" ->
                "protected net.minecraft.world.level.levelgen.structure.StructurePiece orientation"
            "public net.minecraft.world.entity.LivingEntity m_21275_(Lnet/minecraft/world/damagesource/DamageSource;)Z" ->
                "public net.minecraft.world.entity.LivingEntity isDamageSourceBlocked(Lnet/minecraft/world/damagesource/DamageSource;)Z"
            "protected net.minecraft.world.entity.animal.Sheep m_29823_(Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/animal/Animal;)Lnet/minecraft/world/item/DyeColor;" ->
                "protected net.minecraft.world.entity.animal.Sheep getOffspringColor(Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/animal/Animal;)Lnet/minecraft/world/item/DyeColor;"
            "public net.minecraft.world.entity.LivingEntity m_21278_(Lnet/minecraft/world/item/ItemStack;)V" ->
                "public net.minecraft.world.entity.LivingEntity breakItem(Lnet/minecraft/world/item/ItemStack;)V"
            "public net.minecraft.client.model.HumanoidModel m_102875_(Lnet/minecraft/world/entity/LivingEntity;)V" ->
                "public net.minecraft.client.model.HumanoidModel poseRightArm(Lnet/minecraft/world/entity/LivingEntity;)V"
            "public net.minecraft.client.model.HumanoidModel m_102878_(Lnet/minecraft/world/entity/LivingEntity;)V" ->
                "public net.minecraft.client.model.HumanoidModel poseLeftArm(Lnet/minecraft/world/entity/LivingEntity;)V"
            "public net.minecraft.client.model.HumanoidModel m_102856_(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/entity/HumanoidArm;" ->
                "public net.minecraft.client.model.HumanoidModel getAttackArm(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/entity/HumanoidArm;"
            "public net.minecraft.world.entity.ai.goal.GoalSelector f_25345_" ->
                "public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals"
            "public net.minecraft.world.level.block.state.BlockBehaviour f_60442_" ->
                "public net.minecraft.world.level.block.state.BlockBehaviour material"
            "public net.minecraft.world.level.block.FireBlock m_53444_(Lnet/minecraft/world/level/block/Block;II)V" ->
                "public net.minecraft.world.level.block.FireBlock setFlammable(Lnet/minecraft/world/level/block/Block;II)V"
            "public net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77894_" ->
                "public net.minecraft.world.level.saveddata.maps.MapItemSavedData decorations"
            "public net.minecraft.client.multiplayer.ClientAdvancements f_104390_" ->
                "public net.minecraft.client.multiplayer.ClientAdvancements progress"
            "public net.minecraft.world.entity.Entity f_19815_" ->
                "public net.minecraft.world.entity.Entity dimensions"
            "public net.minecraft.world.entity.Mob m_21424_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V" ->
                "public net.minecraft.world.entity.Mob maybeDisableShield(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"
            "public net.minecraft.client.gui.Gui f_92980_" ->
                "public net.minecraft.client.gui.Gui vignetteBrightness"
            "public net.minecraft.world.entity.ai.control.MoveControl f_24981_" ->
                "public net.minecraft.world.entity.ai.control.MoveControl operation"
            "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70263_" ->
                "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer baseHeight"
            "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70264_" ->
                "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer heightRandA"
            "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70265_" ->
                "public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer heightRandB"
            "public net.minecraft.world.entity.animal.Parrot f_29358_" ->
                "public net.minecraft.world.entity.animal.Parrot MOB_SOUND_MAP"
            "public net.minecraft.world.level.block.ComposterBlock m_51920_(FLnet/minecraft/world/level/ItemLike;)V" ->
                "public net.minecraft.world.level.block.ComposterBlock add(FLnet/minecraft/world/level/ItemLike;)V"
            "public-f net.minecraft.world.item.AxeItem f_150683_" ->
                "public-f net.minecraft.world.item.AxeItem STRIPPABLES"
            "public-f net.minecraft.world.entity.player.Inventory f_35978_" ->
                "public-f net.minecraft.world.entity.player.Inventory player"
            "public net.minecraft.world.level.BaseSpawner f_45451_" ->
                "public net.minecraft.world.level.BaseSpawner maxNearbyEntities"
            "public net.minecraft.world.level.BaseSpawner f_45449_" ->
                "public net.minecraft.world.level.BaseSpawner spawnCount"
            "public net.minecraft.world.level.BaseSpawner f_45453_" ->
                "public net.minecraft.world.level.BaseSpawner spawnRange"
            "public net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration f_159157_" ->
                "public net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration floorLevel"
            "public net.minecraft.world.level.block.FlowerPotBlock m_153267_()Z" ->
                "public net.minecraft.world.level.block.FlowerPotBlock isEmpty()Z"
            "public net.minecraft.server.level.ChunkMap m_183719_()Lnet/minecraft/world/level/chunk/ChunkGenerator;" ->
                "public net.minecraft.server.level.ChunkMap generator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            "public net.minecraft.world.level.biome.Biome f_47435_" ->
                "public net.minecraft.world.level.biome.Biome TEMPERATURE_NOISE"
            "public net.minecraft.world.entity.decoration.Painting m_218891_(Lnet/minecraft/core/Holder;)V" ->
                "public net.minecraft.world.entity.decoration.Painting setVariant(Lnet/minecraft/core/Holder;)V"
            "public net.minecraft.world.level.block.DispenserBlock f_52661_" ->
                "public net.minecraft.world.level.block.DispenserBlock DISPENSER_REGISTRY"
            "public net.minecraft.client.gui.Gui m_93024_(Lnet/minecraft/world/phys/HitResult;)Z" ->
                "public net.minecraft.client.gui.Gui canRenderCrosshairForSpectator(Lnet/minecraft/world/phys/HitResult;)Z"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77885_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerX"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77886_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerZ"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77897_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData bannerMarkers"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77898_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData frameMarkers"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_181308_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData trackedDecorationCount"
            "public net.minecraft.world.level.levelgen.feature.TreeFeature m_225251_(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Ljava/util/Set;Ljava/util/Set;Ljava/util/Set;)Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape;" ->
                "public net.minecraft.world.level.levelgen.feature.TreeFeature updateLeaves(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Ljava/util/Set;Ljava/util/Set;Ljava/util/Set;)Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape;"
            "public net.minecraft.world.level.levelgen.Heightmap m_64245_(III)V" ->
                "public net.minecraft.world.level.levelgen.Heightmap setHeight(III)V"
            "public net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator f_64316_" ->
                "public net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator defaultBlock"
            "public net.minecraft.world.level.levelgen.carver.WorldCarver m_159423_(Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;)Z" ->
                "public net.minecraft.world.level.levelgen.carver.WorldCarver isDebugEnabled(Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;)Z"
            "public net.minecraft.world.level.levelgen.carver.WorldCarver m_159418_(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/Aquifer;)Lnet/minecraft/world/level/block/state/BlockState;" ->
                "public net.minecraft.world.level.levelgen.carver.WorldCarver getCarveState(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/Aquifer;)Lnet/minecraft/world/level/block/state/BlockState;"
            "protected net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor f_74075_" ->
                "protected net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor integrity"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_164290_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise mainNoise"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_164289_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise maxLimitNoise"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_164288_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise minLimitNoise"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_192799_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise xzScale"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_192800_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise yScale"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_230458_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise xzFactor"
            "public net.minecraft.world.level.levelgen.synth.BlendedNoise f_230459_" ->
                "public net.minecraft.world.level.levelgen.synth.BlendedNoise yFactor"
            "public net.minecraft.server.level.ServerLevel m_143288_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;" ->
                "public net.minecraft.server.level.ServerLevel findLightningTargetAround(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
            "public net.minecraft.client.renderer.LevelRenderer f_109450_" ->
                "public net.minecraft.client.renderer.LevelRenderer rainSoundTime"
            "public net.minecraft.client.renderer.LevelRenderer f_109472_" ->
                "public net.minecraft.client.renderer.LevelRenderer skyBuffer"
            "public net.minecraft.client.renderer.LevelRenderer f_109473_" ->
                "public net.minecraft.client.renderer.LevelRenderer darkBuffer"
            "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210560_" ->
                "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"
            "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210559_" ->
                "public net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"
            "public net.minecraft.client.resources.model.ModelBakery f_119234_" ->
                "public net.minecraft.client.resources.model.ModelBakery UNREFERENCED_TEXTURES"
            "public net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder f_192778_" ->
                "public net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder pieces"
            "public net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator\$Context f_226046_" ->
                "public net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator\$Context decorationSetter"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_256718_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerX"
            "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_256789_" ->
                "public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerZ"
            "public net.minecraft.world.effect.MobEffectInstance f_19504_" ->
                "public net.minecraft.world.effect.MobEffectInstance amplifier"
            "public net.minecraft.world.level.block.entity.SignBlockEntity f_276598_" ->
                "public net.minecraft.world.level.block.entity.SignBlockEntity frontText"
            "public net.minecraft.world.level.biome.BiomeManager f_47863_" ->
                "public net.minecraft.world.level.biome.BiomeManager biomeZoomSeed"
            "public net.minecraft.data.models.ItemModelGenerators f_265952_" ->
                "public net.minecraft.data.models.ItemModelGenerators GENERATED_TRIM_MODELS"
            "public net.minecraft.world.level.chunk.ChunkGenerator m_223138_(Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/levelgen/RandomState;)Ljava/util/List;" ->
                "public net.minecraft.world.level.chunk.ChunkGenerator getPlacementsForStructure(Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/levelgen/RandomState;)Ljava/util/List;"
            "public net.minecraft.world.level.chunk.ChunkGenerator m_223104_(Lnet/minecraft/world/level/levelgen/structure/StructureSet\$StructureSelectionEntry;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/SectionPos;)Z" ->
                "public net.minecraft.world.level.chunk.ChunkGenerator tryGenerateStructure(Lnet/minecraft/world/level/levelgen/structure/StructureSet\$StructureSelectionEntry;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/SectionPos;)Z"
            "public net.minecraft.world.level.storage.loot.LootTable m_230924_(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V" ->
                "public net.minecraft.world.level.storage.loot.LootTable shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V"
            else -> entry
        }
        val finalized = finalizeAccessTransformerEntry(migrated) ?: return ""
        if (finalized == entry && migrated == entry) return line

        val comment = line.substringAfter("#", "").trim()
        return if (comment.isNotEmpty()) "$finalized # $comment" else finalized
    }

    private fun finalizeAccessTransformerEntry(entry: String): String? {
        val descriptorAdjusted = entry
            .replace(
                "net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/Codec;)V",
                "net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/MapCodec;)V"
            )
            .replace(
                "net.minecraft.world.level.storage.loot.LootTable shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V",
                "net.minecraft.world.level.storage.loot.LootTable shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V"
            )

        return if (shouldDropMigratedAccessTransformerEntry(descriptorAdjusted)) null else descriptorAdjusted
    }

    private fun shouldDropMigratedAccessTransformerEntry(entry: String): Boolean =
            entry.contains("net.minecraft.world.entity.Mob maybeDisableShield(") ||
            entry.contains("net.minecraft.world.level.block.state.BlockBehaviour material") ||
            entry.contains("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator defaultBlock") ||
            entry.contains("net.minecraft.world.level.chunk.ChunkGenerator getPlacementsForStructure(") ||
            entry.contains("net.minecraft.client.resources.model.ModelBakery UNREFERENCED_TEXTURES") ||
            entry.contains("net.minecraft.world.item.crafting.SimpleCookingSerializer\$CookieBaker") ||
            entry.contains("net.minecraft.client.gui.screens.TitleScreen\$WarningLabel") ||
            entry.contains("net.minecraft.world.entity.LivingEntity getDeathSound()") ||
            entry.contains("net.minecraft.world.entity.decoration.HangingEntity setDirection(") ||
            entry.contains("net.neoforged.neoforge.client.event.EntityRenderersEvent\$AddLayers renderers") ||
            entry.contains("net.minecraft.client.renderer.WeatherEffectRenderer rainSoundTime")

    private fun normalizeAccessTransformerEntry(line: String): String? {
        val withoutComment = line.substringBefore("#").trim()
        return withoutComment.ifBlank { null }?.replace(Regex("""\s+"""), " ")
    }

    private fun collectRequiredAccessTransformerEntries(projectDir: Path): Set<String> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptySet()

        val entries = linkedSetOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val source = javaFile.readText()
                if (containsGoalSelectorAvailableGoalsAccess(source)) {
                    entries.add("public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals")
                }
                if (containsServerLevelFindLightningTargetCall(source)) {
                    entries.add("public net.minecraft.server.level.ServerLevel findLightningTargetAround(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;")
                }
                if (containsStructureTemplatePoolFieldAccess(source, "templates")) {
                    entries.add("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates")
                }
                if (containsStructureTemplatePoolFieldAccess(source, "rawTemplates")) {
                    entries.add("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates")
                }
                if (containsChunkPendingBlockEntitiesAccess(source)) {
                    entries.add("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities")
                }
                if (containsAbstractArrowFieldAccess(source, "firedFromWeapon")) {
                    entries.add("public net.minecraft.world.entity.projectile.AbstractArrow firedFromWeapon")
                }
                if (containsAbstractArrowMethodCall(source, "setPierceLevel")) {
                    entries.add("public net.minecraft.world.entity.projectile.AbstractArrow setPierceLevel(B)V")
                }
                if (containsCreativeModeInventorySelectedTabAccess(source)) {
                    entries.add("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab")
                }
                if (containsLevelRendererFieldAccess(source, "skyBuffer")) {
                    entries.add("public net.minecraft.client.renderer.LevelRenderer skyBuffer")
                }
                if (containsLevelRendererFieldAccess(source, "darkBuffer")) {
                    entries.add("public net.minecraft.client.renderer.LevelRenderer darkBuffer")
                }
                if (containsLevelRendererFieldAccess(source, "rainSoundTime")) {
                    entries.add("public net.minecraft.client.renderer.LevelRenderer rainSoundTime")
                }
            }
        return entries
    }

    private fun containsGoalSelectorAvailableGoalsAccess(source: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.entity\.ai\.goal\.)?GoalSelector""",
            "availableGoals"
        )
    }

    private fun containsServerLevelFindLightningTargetCall(source: String): Boolean {
        return containsTypedJavaMethodCall(
            source,
            """(?:net\.minecraft\.server\.level\.)?ServerLevel""",
            "findLightningTargetAround"
        )
    }

    private fun containsStructureTemplatePoolFieldAccess(source: String, fieldName: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.level\.levelgen\.structure\.pools\.)?StructureTemplatePool""",
            fieldName
        )
    }

    private fun containsChunkPendingBlockEntitiesAccess(source: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.level\.chunk\.)?(?:LevelChunk|ChunkAccess)""",
            "pendingBlockEntities"
        )
    }

    private fun containsAbstractArrowFieldAccess(source: String, fieldName: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.world\.entity\.projectile\.)?AbstractArrow""",
            fieldName
        )
    }

    private fun containsAbstractArrowMethodCall(source: String, methodName: String): Boolean {
        return containsTypedJavaMethodCall(
            source,
            """(?:net\.minecraft\.world\.entity\.projectile\.)?AbstractArrow""",
            methodName
        )
    }

    private fun containsCreativeModeInventorySelectedTabAccess(source: String): Boolean {
        return containsJavaStaticFieldAccess(
            source,
            """(?:net\.minecraft\.client\.gui\.screens\.inventory\.)?CreativeModeInventoryScreen""",
            "selectedTab"
        )
    }

    private fun containsLevelRendererFieldAccess(source: String, fieldName: String): Boolean {
        return containsTypedJavaFieldAccess(
            source,
            """(?:net\.minecraft\.client\.renderer\.)?LevelRenderer""",
            fieldName
        )
    }

    private fun containsTypedJavaFieldAccess(source: String, typePattern: String, fieldName: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        val variables = collectTypedJavaVariables(code, typePattern)
        return variables.any { variable ->
            Regex("""\b${Regex.escape(variable)}\s*\.\s*${Regex.escape(fieldName)}\b""").containsMatchIn(code)
        }
    }

    private fun containsTypedJavaMethodCall(source: String, typePattern: String, methodName: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        val variables = collectTypedJavaVariables(code, typePattern)
        return variables.any { variable ->
            Regex("""\b${Regex.escape(variable)}\s*\.\s*${Regex.escape(methodName)}\s*\(""").containsMatchIn(code)
        }
    }

    private fun containsJavaStaticFieldAccess(source: String, ownerTypePattern: String, fieldName: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        return Regex("""\b(?:$ownerTypePattern)\s*\.\s*${Regex.escape(fieldName)}\b""").containsMatchIn(code)
    }

    private fun collectTypedJavaVariables(code: String, typePattern: String): Set<String> {
        return Regex("""\b(?:$typePattern)\s+([A-Za-z_$][\w$]*)\b""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun maskJavaComments(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        chars[index++] = ' '
                    }
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private fun maskJavaCommentsAndTextBlocks(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        chars[index++] = ' '
                    }
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                    }
                }
                index + 2 < chars.size && chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    chars[index + 2] = ' '
                    index += 3
                    while (index + 2 < chars.size && !(chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 2 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        chars[index + 2] = ' '
                        index += 3
                    }
                }
                chars[index] == '"' || chars[index] == '\'' -> {
                    val quote = chars[index++]
                    var escaped = false
                    while (index < chars.size) {
                        val current = chars[index++]
                        if (escaped) {
                            escaped = false
                        } else if (current == '\\') {
                            escaped = true
                        } else if (current == quote) {
                            break
                        }
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        while (index < chars.size) {
            when {
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '/' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index < chars.size && chars[index] != '\n' && chars[index] != '\r') {
                        chars[index++] = ' '
                    }
                }
                index + 1 < chars.size && chars[index] == '/' && chars[index + 1] == '*' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    index += 2
                    while (index + 1 < chars.size && !(chars[index] == '*' && chars[index + 1] == '/')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 1 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        index += 2
                    }
                }
                index + 2 < chars.size && chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"' -> {
                    chars[index] = ' '
                    chars[index + 1] = ' '
                    chars[index + 2] = ' '
                    index += 3
                    while (index + 2 < chars.size && !(chars[index] == '"' && chars[index + 1] == '"' && chars[index + 2] == '"')) {
                        if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                        index++
                    }
                    if (index + 2 < chars.size) {
                        chars[index] = ' '
                        chars[index + 1] = ' '
                        chars[index + 2] = ' '
                        index += 3
                    }
                }
                chars[index] == '"' || chars[index] == '\'' -> {
                    val quote = chars[index]
                    chars[index++] = ' '
                    var escaped = false
                    while (index < chars.size) {
                        val current = chars[index]
                        if (current != '\n' && current != '\r') chars[index] = ' '
                        index++
                        if (escaped) {
                            escaped = false
                        } else if (current == '\\') {
                            escaped = true
                        } else if (current == quote) {
                            break
                        }
                    }
                }
                else -> index++
            }
        }
        return String(chars)
    }

    private data class ExecutableRegexMatch(
        val groupValues: List<String>
    )

    private fun replaceExecutableRegex(
        source: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String = replaceMaskedRegex(source, maskJavaCommentsAndLiterals(source), pattern, replacement)

    private fun replaceCommentAndTextBlockMaskedRegex(
        source: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String = replaceMaskedRegex(source, maskJavaCommentsAndTextBlocks(source), pattern, replacement)

    private fun replaceMaskedRegex(
        source: String,
        maskedSource: String,
        pattern: Regex,
        replacement: (ExecutableRegexMatch) -> String
    ): String {
        val matches = pattern.findAll(maskedSource).toList()
        if (matches.isEmpty()) return source

        var result = source
        for (match in matches.asReversed()) {
            val groupValues = (0 until match.groups.size).map { index ->
                match.groups[index]?.range?.let { range ->
                    source.substring(range.first, range.last + 1)
                } ?: ""
            }
            result = result.replaceRange(
                match.range,
                replacement(ExecutableRegexMatch(groupValues))
            )
        }
        return result
    }

    private fun ensureAccessTransformerEntries(
        projectDir: Path,
        requiredEntries: List<String>,
        dryRun: Boolean,
        ruleId: String,
        description: String
    ): List<Change> {
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        val original = if (atFile.exists()) atFile.readText() else ""
        val existing = original.lines().mapNotNull(::normalizeAccessTransformerEntry).toMutableSet()
        val missing = requiredEntries.filter { existing.add(it) }
        if (missing.isEmpty()) return emptyList()

        val separator = System.lineSeparator()
        val modified = buildString {
            append(original.trimEnd())
            if (isNotEmpty()) append(separator).append(separator)
            append(missing.joinToString(separator))
            append(separator)
        }
        if (!dryRun) {
            atFile.parent.createDirectories()
            atFile.writeText(modified)
        }
        return listOf(Change(
            file = atFile,
            line = 1,
            description = description,
            before = "missing access transformer entries",
            after = missing.joinToString(", "),
            confidence = Confidence.HIGH,
            ruleId = ruleId
        ))
    }

    private fun migrateBlockPropertiesNoParticlesOnBreak(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val changedFiles = mutableListOf<Path>()
        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            val executableOriginal = maskJavaCommentsAndLiterals(original)
            if (!executableOriginal.contains(".noParticlesOnBreak()")) continue
            val migrated = rewriteNoParticlesOnBreakCalls(original)
            if (migrated != original) {
                changedFiles.add(javaFile)
                if (!dryRun) {
                    javaFile.writeText(migrated)
                }
            }
        }
        if (changedFiles.isEmpty()) return emptyList()

        val changes = mutableListOf<Change>()
        val helperFile = projectDir.resolve("src/main/java/com/modporter/compat/ModPorterBlockProperties.java")
        if (!helperFile.exists()) {
            changes.add(Change(
                file = helperFile,
                line = 1,
                description = "Add Access-Transformer-backed block properties helper for removed noParticlesOnBreak builder API",
                before = "BlockBehaviour.Properties.noParticlesOnBreak()",
                after = "ModPorterBlockProperties.noParticlesOnBreak(properties)",
                confidence = Confidence.HIGH,
                ruleId = "build-block-properties-no-particles-helper"
            ))
            if (!dryRun) {
                helperFile.parent.createDirectories()
                helperFile.writeText(blockPropertiesHelperSource())
            }
        }
        changes.add(Change(
            file = changedFiles.first(),
            line = 1,
            description = "Rewrite removed noParticlesOnBreak builder calls to an AT-backed helper",
            before = ".noParticlesOnBreak()",
            after = "ModPorterBlockProperties.noParticlesOnBreak(properties)",
            confidence = Confidence.HIGH,
            ruleId = "build-block-properties-no-particles"
        ))
        changes.addAll(ensureAccessTransformerEntries(
            projectDir = projectDir,
            requiredEntries = listOf("public net.minecraft.world.level.block.state.BlockBehaviour\$Properties spawnTerrainParticles"),
            dryRun = dryRun,
            ruleId = "build-block-properties-no-particles-at",
            description = "Expose BlockBehaviour.Properties spawnTerrainParticles through Access Transformers instead of reflection"
        ))
        return changes
    }

    private fun rewriteNoParticlesOnBreakCalls(source: String): String {
        val token = ".noParticlesOnBreak()"
        val executableSource = maskJavaCommentsAndLiterals(source)
        val result = StringBuilder()
        var cursor = 0
        while (cursor < source.length) {
            val tokenIndex = executableSource.indexOf(token, cursor)
            if (tokenIndex < 0) break
            val receiverStart = findFluentReceiverStart(executableSource, tokenIndex)
            if (receiverStart < 0 || receiverStart >= tokenIndex) {
                result.append(source, cursor, tokenIndex + token.length)
                cursor = tokenIndex + token.length
                continue
            }
            val receiver = source.substring(receiverStart, tokenIndex).trim()
            val leading = source.substring(receiverStart, tokenIndex).takeWhile { it.isWhitespace() }
            result.append(source, cursor, receiverStart)
            result.append(leading)
            result.append("com.modporter.compat.ModPorterBlockProperties.noParticlesOnBreak($receiver)")
            cursor = tokenIndex + token.length
        }
        if (cursor == 0) return source
        result.append(source, cursor, source.length)
        return result.toString()
    }

    private fun findFluentReceiverStart(source: String, tokenIndex: Int): Int {
        var index = tokenIndex - 1
        var parenDepth = 0
        var bracketDepth = 0
        while (index >= 0) {
            val char = source[index]
            when (char) {
                ')' -> parenDepth++
                '(' -> {
                    if (parenDepth == 0) return index + 1
                    parenDepth--
                }
                ']' -> bracketDepth++
                '[' -> {
                    if (bracketDepth == 0) return index + 1
                    bracketDepth--
                }
                ',', ';', '{', '}', '=' -> if (parenDepth == 0 && bracketDepth == 0) return index + 1
                '\n', '\r' -> {
                    if (parenDepth == 0 && bracketDepth == 0) {
                        val linePrefix = source.substring(index + 1, tokenIndex)
                        if (!linePrefix.trimStart().startsWith(".")) return index + 1
                    }
                }
            }
            index--
        }
        return 0
    }

    private fun blockPropertiesHelperSource(): String = """
        package com.modporter.compat;

        import net.minecraft.world.level.block.state.BlockBehaviour;

        public final class ModPorterBlockProperties {
            private ModPorterBlockProperties() {
            }

            public static BlockBehaviour.Properties noParticlesOnBreak(BlockBehaviour.Properties properties) {
                properties.spawnTerrainParticles = false;
                return properties;
            }
        }
    """.trimIndent()

    private fun configureAccessTransformers(projectDir: Path, dryRun: Boolean): List<Change> {
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        if (!atFile.exists()) return emptyList()

        val buildFiles = listOf(projectDir.resolve("build.gradle"), projectDir.resolve("build.gradle.kts"))
            .filter { it.exists() }
        val changes = mutableListOf<Change>()
        for (buildFile in buildFiles) {
            val original = buildFile.readText()
            if (original.contains("accessTransformers")) continue

            val isKts = buildFile.fileName.toString().endsWith(".kts")
            val insertion = if (isKts) {
                """
    validateAccessTransformers = true
    accessTransformers.files.setFrom("src/main/resources/META-INF/accesstransformer.cfg")

                """.trimIndent()
            } else {
                """
    validateAccessTransformers = true
    accessTransformers {
        file('src/main/resources/META-INF/accesstransformer.cfg')
    }

                """.trimIndent()
            }

            val neoForgeMatch = Regex("""(?m)^(\s*neoForge\s*\{\s*\r?\n)""").find(original)
            val modified = if (neoForgeMatch != null) {
                val insertAt = neoForgeMatch.range.last + 1
                original.substring(0, insertAt) + insertion + original.substring(insertAt)
            } else {
                val block = if (isKts) {
                    """

neoForge {
$insertion}
                    """.trimIndent()
                } else {
                    """

neoForge {
$insertion}
                    """.trimIndent()
                }
                original.trimEnd() + System.lineSeparator() + block + System.lineSeparator()
            }
            if (modified != original) {
                changes.add(Change(
                    file = buildFile,
                    line = 1,
                    description = "Wire Access Transformers into ModDevGradle",
                    before = "neoForge block without accessTransformers",
                    after = "validateAccessTransformers plus accessTransformers cfg",
                    confidence = Confidence.HIGH,
                    ruleId = "build-access-transformer-config"
                ))
                if (!dryRun) buildFile.writeText(modified)
            }
        }
        return changes
    }

    private fun detectForbiddenReflection(projectDir: Path): List<String> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val forbiddenPatterns = listOf(
            "ObfuscationReflectionHelper" to Regex("""\bObfuscationReflectionHelper\b"""),
            "getDeclaredField" to Regex("""\bgetDeclaredField\s*\("""),
            "getDeclaredMethod" to Regex("""\bgetDeclaredMethod\s*\("""),
            "getDeclaredConstructor" to Regex("""\bgetDeclaredConstructor\s*\("""),
            "getMethod" to Regex("""\.getMethod\s*\("""),
            "setAccessible" to Regex("""\bsetAccessible\s*\("""),
            "Class.forName" to Regex("""\bClass\.forName\s*\("""),
            "MethodHandle" to Regex("""\bMethodHandle\b"""),
            "MethodHandles" to Regex("""\bMethodHandles\b"""),
            "java.lang.reflect member access" to Regex("""\bjava\.lang\.reflect\.(?:Field|Method|Constructor)\b"""),
            "unreflect" to Regex("""\bunreflect(?:Getter|Setter)?\s*\(""")
        )
        val errors = mutableListOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val code = maskJavaCommentsAndLiterals(javaFile.readText())
                code.lines().forEachIndexed { index, line ->
                    for ((label, pattern) in forbiddenPatterns) {
                        if (pattern.containsMatchIn(line)) {
                            val relative = projectDir.relativize(javaFile).toString().replace('\\', '/')
                            errors.add("Forbidden reflection in $relative:${index + 1}: $label")
                        }
                    }
                }
            }
        return errors
    }

    private fun migrateCoremodScripts(projectDir: Path, dryRun: Boolean): List<Change> {
        val asmDir = projectDir.resolve("src/main/resources/META-INF/asm")
        if (!asmDir.exists()) return emptyList()

        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(asmDir)
            .filter { it.toString().endsWith(".js") }
            .forEach { script ->
                val original = script.readText()
                val modified = original.replace(
                    "net.minecraftforge.coremod.api.ASMAPI",
                    "net.neoforged.coremod.api.ASMAPI"
                )
                if (modified != original) {
                    changes.add(Change(
                        file = script,
                        line = 1,
                        description = "Migrate Forge coremod ASMAPI scripts to NeoForge coremods API",
                        before = "net.minecraftforge.coremod.api.ASMAPI",
                        after = "net.neoforged.coremod.api.ASMAPI",
                        confidence = Confidence.HIGH,
                        ruleId = "build-coremod-asmapi-neoforge"
                    ))
                    if (!dryRun) script.writeText(modified)
                }
            }
        return changes
    }

    private fun detectLegacyCoremodApiReferences(projectDir: Path): List<String> {
        val asmDir = projectDir.resolve("src/main/resources/META-INF/asm")
        if (!asmDir.exists()) return emptyList()

        val errors = mutableListOf<String>()
        java.nio.file.Files.walk(asmDir)
            .filter { it.toString().endsWith(".js") }
            .forEach { script ->
                script.readLines().forEachIndexed { index, line ->
                    if (line.contains("net.minecraftforge.coremod.api.ASMAPI")) {
                        val relative = projectDir.relativize(script).toString().replace('\\', '/')
                        errors.add("Legacy Forge coremod ASMAPI reference in $relative:${index + 1}")
                    }
                }
            }
        return errors
    }

    private data class AnimalSpawnPlacementTarget(val fieldName: String, val entityClass: String)

    private fun migrateAnimalSpawnPlacements(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()

        val changes = mutableListOf<Change>()
        val registries = mutableListOf<String>()

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (original.contains("RegisterSpawnPlacementsEvent") &&
                Regex("""\bstatic\s+void\s+registerSpawnPlacements\s*\(""").containsMatchIn(original)
            ) {
                registries.add(fullyQualifiedJavaClassName(javaFile, original))
                continue
            }
            if (!original.contains("EntityType.Builder.of") || !original.contains("MobCategory.CREATURE")) continue
            if (original.contains("registerSpawnPlacements(")) continue

            val targets = findAnimalSpawnPlacementTargets(original, javaFiles)
            if (targets.isEmpty()) continue

            var modified = original
            modified = ensureJavaImport(modified, "net.minecraft.world.entity.SpawnPlacementTypes")
            modified = ensureJavaImport(modified, "net.minecraft.world.entity.animal.Animal")
            modified = ensureJavaImport(modified, "net.minecraft.world.level.levelgen.Heightmap")
            modified = ensureJavaImport(modified, "net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent")

            val body = targets.joinToString("\n") { target ->
                "        event.register(${target.fieldName}.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Animal::checkAnimalSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);"
            }
            val method = """

    public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
$body
    }
""".trimEnd()

            val insertAt = modified.lastIndexOf('}')
            if (insertAt < 0) continue
            modified = modified.substring(0, insertAt) + method + "\n" + modified.substring(insertAt)

            val registryName = fullyQualifiedJavaClassName(javaFile, modified)
            registries.add(registryName)
            changes.add(Change(
                file = javaFile,
                line = 0,
                description = "Register animal spawn placement predicates on the NeoForge mod bus",
                before = "MobCategory.CREATURE entity without RegisterSpawnPlacementsEvent handler",
                after = "RegisterSpawnPlacementsEvent with Animal::checkAnimalSpawnRules",
                confidence = Confidence.HIGH,
                ruleId = "build-entity-animal-spawn-placement"
            ))
            if (!dryRun) javaFile.writeText(modified)
        }

        if (registries.isEmpty()) return changes
        val modFiles = javaFiles.filter { file ->
            val text = file.readText()
            text.contains("@Mod(") && text.contains("IEventBus")
        }
        for (modFile in modFiles) {
            var modified = modFile.readText()
            val original = modified
            val eventBusNames = Regex("""\bIEventBus\s+([A-Za-z_$][\w$]*)\s*=""")
                .findAll(modified)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            for (registryName in registries.distinct()) {
                if (modified.contains("$registryName::registerSpawnPlacements")) continue
                for (eventBusName in eventBusNames) {
                    val listenerLine = "$eventBusName.addListener($registryName::registerSpawnPlacements);"
                    val inserted = insertModBusLineAfterBusDeclaration(modified, eventBusName, listenerLine)
                    if (inserted != null) {
                        modified = inserted
                        break
                    }
                }
            }
            if (modified != original) {
                changes.add(Change(
                    file = modFile,
                    line = 0,
                    description = "Register animal spawn placement listener on the mod event bus",
                    before = "@Mod constructor without registerSpawnPlacements listener",
                    after = "modEventBus.addListener(EntityRegistry::registerSpawnPlacements)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-modbus-animal-spawn-placement-listener"
                ))
                if (!dryRun) modFile.writeText(modified)
            }
        }

        return changes
    }

    private fun findAnimalSpawnPlacementTargets(registrySource: String, javaFiles: List<Path>): List<AnimalSpawnPlacementTarget> {
        val pattern = Regex(
            """public\s+static\s+final\s+DeferredHolder[\s\S]*?\s+([A-Za-z_$][\w$]*)\s*=\s*[A-Za-z_$][\w$]*\.register\(\s*"[^"]+"\s*,\s*\(\)\s*->\s*EntityType\.Builder\.of\(\s*([A-Za-z_$][\w$]*)::new\s*,\s*MobCategory\.CREATURE\s*\)""",
            setOf(RegexOption.MULTILINE)
        )
        return pattern.findAll(registrySource)
            .mapNotNull { match ->
                val fieldName = match.groupValues[1]
                val entityClass = match.groupValues[2]
                if (javaClassExtends(javaFiles, entityClass, "Animal")) {
                    AnimalSpawnPlacementTarget(fieldName, entityClass)
                } else {
                    null
                }
            }
            .toList()
    }

    private fun javaClassExtends(javaFiles: List<Path>, className: String, parentName: String): Boolean {
        return javaFiles.any { file ->
            file.fileName.toString() == "$className.java" &&
                Regex("""\bclass\s+${Regex.escape(className)}\s+extends\s+${Regex.escape(parentName)}\b""")
                    .containsMatchIn(file.readText())
        }
    }

    private fun fullyQualifiedJavaClassName(file: Path, source: String): String {
        val className = file.fileName.toString().removeSuffix(".java")
        val packageName = Regex("""(?m)^package\s+([\w.]+);""").find(source)?.groupValues?.get(1)
        return if (packageName.isNullOrBlank()) className else "$packageName.$className"
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
            Regex("""(?m)^forge_version\s*=\s*.+$"""),
            Regex("""(?m)^neo_forge_version\s*=\s*.+$"""),
            Regex("""(?m)^neoforge_version\s*=\s*.+$"""),
            Regex("""(?m)^neoneo_forge_version\s*=\s*.+$"""),
            Regex("""(?m)^forgeversion\s*=\s*.+$"""),
            Regex("""(?m)^forgeVersion\s*=\s*.+$"""),
        )) {
            if (pattern.containsMatchIn(content)) {
                val match = pattern.find(content)!!
                val replacement = "neo_forge_version=$targetNeoForgeVersion"
                changes.add(Change(
                    file = file, line = content.lineNumberAt(match.range.first),
                    description = "Replace forge/neoforge version with $replacement",
                    before = match.value,
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "build-props-version"
                ))
                content = content.replace(match.value, replacement)
                foundForgeVersion = true
                break  // Only replace first match
            }
        }
        // Ensure neo_forge_version exists even if no forge version property was found
        if (!foundForgeVersion && !Regex("""(?m)^neo_forge_version\s*=""").containsMatchIn(content)) {
            val replacement = "neo_forge_version=$targetNeoForgeVersion"
            content += "\n# Added by modporter\n$replacement\n"
            changes.add(Change(
                file = file, line = content.lines().size,
                description = "Add neo_forge_version property (required by neoForge block)",
                before = "(missing)",
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "build-props-version-add"
            ))
        }

        // Replace Minecraft version (handles various naming conventions)
        var foundMcVersion = false
        for (mcProp in listOf(
            Regex("""(?m)^minecraft_version\s*=\s*1\.20\.\d+\s*$"""),
            Regex("""(?m)^mc_version\s*=\s*1\.20\.\d+\s*$"""),
            Regex("""(?m)^mcversion\s*=\s*1\.20\.\d+\s*$"""),
            Regex("""(?m)^mcVersion\s*=\s*1\.20\.\d+\s*$"""),
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
        if (!foundMcVersion && !Regex("""(?m)^minecraft_version\s*=""").containsMatchIn(content)) {
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

        val dependencyVersionProperties = DependencyResolver(
            offlineMode = true,
            mappingsPrefix = mappingsPrefix
        ).targetVersionProperties()
        for (versionProperty in dependencyVersionProperties) {
            val pattern = Regex("""(?m)^${Regex.escape(versionProperty.name)}\s*=\s*.+$""")
            val match = pattern.find(content) ?: continue
            val replacement = "${versionProperty.name}=${versionProperty.value}"
            if (match.value.trim() == replacement) continue
            changes.add(Change(
                file = file,
                line = content.lineNumberAt(match.range.first),
                description = "Update dependency version property ${versionProperty.name} for NeoForge 1.21.1",
                before = match.value,
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "build-props-dependency-version"
            ))
            content = content.replaceRange(match.range, replacement)
        }

        val baseMcVersion = Regex("""(?m)^base_minecraft_version\s*=\s*1\.20(?:\.\d+)?\s*$""").find(content)
        if (baseMcVersion != null) {
            changes.add(Change(
                file = file, line = content.lineNumberAt(baseMcVersion.range.first),
                description = "Update base Minecraft version to 1.21 for derived runtime dependency coordinates",
                before = baseMcVersion.value,
                after = "base_minecraft_version=1.21",
                confidence = Confidence.HIGH,
                ruleId = "build-props-base-mc-version"
            ))
            content = content.replaceRange(baseMcVersion.range, "base_minecraft_version=1.21")
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
                confidence = Confidence.HIGH,
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
     * Detect a unique concrete mod ID from executable @Mod evidence in Java source files.
     */
    private fun detectModId(projectDir: Path): String? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null
        val candidates = linkedSetOf<String>()
        try {
            val javaFiles = java.nio.file.Files.walk(srcDir)
                .filter { it.toString().endsWith(".java") }
                .toList()
            for (file in javaFiles) {
                val text = file.toFile().readText()
                val code = maskJavaComments(text)
                val executableCode = maskJavaCommentsAndLiterals(text)

                Regex("""@Mod\s*\(\s*"([A-Za-z0-9_.-]+)"\s*\)""")
                    .findAll(code)
                    .filter { match ->
                        executableCode
                            .substring(match.range.first, match.range.last + 1)
                            .contains("@Mod")
                    }
                    .forEach { candidates.add(it.groupValues[1]) }

                Regex("""@Mod\s*\(\s*(?:[A-Za-z_$][\w$]*\.)?([A-Za-z_$][\w$]*)\s*\)""")
                    .findAll(code)
                    .filter { match ->
                        executableCode
                            .substring(match.range.first, match.range.last + 1)
                            .contains("@Mod")
                    }
                    .forEach { match ->
                        findJavaStringConstant(text, match.groupValues[1])?.let(candidates::add)
                    }
            }
        } catch (_: Exception) {}
        return candidates.singleOrNull()
    }

    private fun addMavenRepositoryContentFilters(
        input: String,
        file: Path,
        changes: MutableList<Change>
    ): String {
        var content = input
        var searchStart = 0
        while (true) {
            val match = Regex("""(?m)^[ \t]*maven\s*\{""").find(content, searchStart) ?: break
            val openBrace = content.indexOf('{', match.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(content, openBrace) else -1
            if (closeBrace <= openBrace) break

            val block = content.substring(match.range.first, closeBrace + 1)
            val needsTamaizedFilter = block.contains("maven.tamaized.com/releases") &&
                !Regex("""\bcontent\s*\{""").containsMatchIn(block)
            if (needsTamaizedFilter) {
                val baseIndent = content.substring(match.range.first, openBrace).takeWhile { it == ' ' || it == '\t' }
                val childIndent = "$baseIndent    "
                val filter = "\n${childIndent}content {\n${childIndent}    includeGroup \"tamaized\"\n$childIndent}"
                content = content.substring(0, closeBrace) + filter + content.substring(closeBrace)
                changes.add(Change(
                    file = file,
                    line = content.lineNumberAt(match.range.first),
                    description = "Restrict Tamaized Maven repository to the tamaized group so CurseMaven dependencies resolve from CurseMaven",
                    before = "maven.tamaized.com/releases without content filter",
                    after = "content { includeGroup \"tamaized\" }",
                    confidence = Confidence.HIGH,
                    ruleId = "build-repository-content-filter"
                ))
                searchStart = closeBrace + filter.length + 1
            } else {
                searchStart = closeBrace + 1
            }
        }
        return content
    }

    private fun addJavaImportIfMissing(source: String, importName: String): String {
        if (source.contains("import $importName;")) return source
        val importLine = "import $importName;\n"
        val lastImport = Regex("""^import\s+[^;]+;""", RegexOption.MULTILINE)
            .findAll(source)
            .lastOrNull()
        if (lastImport != null) {
            val insertPos = lastImport.range.last + 1
            return source.substring(0, insertPos) + "\n" + importLine + source.substring(insertPos)
        }

        val packageDecl = Regex("""^package\s+[^;]+;""", RegexOption.MULTILINE).find(source)
        if (packageDecl != null) {
            val insertPos = packageDecl.range.last + 1
            return source.substring(0, insertPos) + "\n\n" + importLine + source.substring(insertPos)
        }

        return importLine + source
    }

    private fun ensureJavaImport(source: String, importName: String): String =
        addJavaImportIfMissing(source, importName)

    private fun removeJavaImport(source: String, importName: String): String =
        source.replace(Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n"""), "")

    private fun removeJavaImportIfSimpleNameUnused(source: String, importName: String): String {
        val withoutImport = removeJavaImport(source, importName)
        if (withoutImport == source) return source
        val simpleName = importName.substringAfterLast('.')
        return if (usesJavaSimpleNameOutsideImports(withoutImport, simpleName)) source else withoutImport
    }

    private fun usesJavaSimpleNameOutsideImports(source: String, simpleName: String): Boolean {
        val withoutImports = Regex("""(?m)^[ \t]*import\s+[^;]+;\s*\r?\n""").replace(source, "")
        return Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(withoutImports)
    }

    private fun detectWorldCarverModIdExpression(source: String, projectDir: Path, javaFile: Path): String? {
        Regex("""@(?:Mod\.)?EventBusSubscriber\s*\([^)]*\bmodid\s*=\s*([^,)]+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val namespaces = extractWorldCarverRegisterIdExpressions(source)
            .mapNotNull { idExpression ->
                resolveResourceLocationNamespaceExpression(idExpression, source, projectDir, javaFile)
            }
            .distinct()

        return namespaces.singleOrNull()
    }

    private fun extractWorldCarverRegisterIdExpressions(source: String): List<String> {
        val expressions = mutableListOf<String>()
        var searchStart = 0
        while (searchStart < source.length) {
            val callStart = source.indexOf(".register(", searchStart)
            if (callStart < 0) break
            val openParen = source.indexOf('(', callStart)
            val closeParen = findMatchingParen(source, openParen)
            if (closeParen > openParen) {
                val args = splitTopLevel(source.substring(openParen + 1, closeParen), ',')
                    .map { it.trim() }
                if (args.size >= 2 && Regex("""[A-Za-z_$][\w$]*""").matches(args[1])) {
                    expressions.add(args[0])
                }
                searchStart = callStart + ".register(".length
            } else {
                searchStart = callStart + ".register(".length
            }
        }
        return expressions
    }

    private fun resolveResourceLocationNamespaceExpression(
        idExpression: String,
        source: String,
        projectDir: Path,
        javaFile: Path
    ): String? {
        resourceLocationFactoryNamespace(idExpression)?.let { return it }

        val call = parseResourceLocationFactoryCall(idExpression) ?: return null
        val ownerSource = resolveJavaSourceForType(call.owner, source, projectDir, javaFile) ?: return null
        val methodBody = findStaticResourceLocationMethodBody(ownerSource.source, call.methodName) ?: return null
        val namespace = resourceLocationFactoryNamespaceFromReturn(methodBody) ?: return null
        return qualifyFactoryNamespaceExpression(namespace, ownerSource.reference, ownerSource.source)
    }

    private data class JavaSourceReference(
        val source: String,
        val reference: String
    )

    private data class ResourceLocationFactoryCall(
        val owner: String,
        val methodName: String
    )

    private fun parseResourceLocationFactoryCall(idExpression: String): ResourceLocationFactoryCall? {
        val openParen = idExpression.indexOf('(')
        if (openParen <= 0 || findMatchingParen(idExpression, openParen) != idExpression.length - 1) return null
        val callee = idExpression.substring(0, openParen).trim()
        val owner = callee.substringBeforeLast('.', missingDelimiterValue = "")
        val methodName = callee.substringAfterLast('.')
        if (owner.isBlank() ||
            !Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*""").matches(owner) ||
            !Regex("""[A-Za-z_$][\w$]*""").matches(methodName)
        ) {
            return null
        }
        return ResourceLocationFactoryCall(owner, methodName)
    }

    private fun resourceLocationFactoryNamespace(idExpression: String): String? {
        val trimmed = idExpression.trim()
        val knownFactories = listOf(
            "ResourceLocation.fromNamespaceAndPath",
            "ResourceLocation.tryBuild"
        )
        for (factory in knownFactories) {
            val prefix = "$factory("
            if (!trimmed.startsWith(prefix) || !trimmed.endsWith(")")) continue
            val openParen = trimmed.indexOf('(')
            val closeParen = findMatchingParen(trimmed, openParen)
            if (closeParen != trimmed.length - 1) continue
            val args = splitTopLevel(trimmed.substring(openParen + 1, closeParen), ',')
                .map { it.trim() }
            return args.firstOrNull()?.takeIf { it.isNotBlank() }
        }

        if (trimmed.startsWith("new ResourceLocation(") && trimmed.endsWith(")")) {
            val openParen = trimmed.indexOf('(')
            val closeParen = findMatchingParen(trimmed, openParen)
            if (closeParen == trimmed.length - 1) {
                return splitTopLevel(trimmed.substring(openParen + 1, closeParen), ',')
                    .map { it.trim() }
                    .firstOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private fun resourceLocationFactoryNamespaceFromReturn(methodBody: String): String? {
        val namespaces = mutableListOf<String>()
        var searchStart = 0
        while (searchStart < methodBody.length) {
            val returnStart = methodBody.indexOf("return", searchStart)
            if (returnStart < 0) break
            val semicolon = methodBody.indexOf(';', returnStart)
            if (semicolon < 0) break
            resourceLocationFactoryNamespace(methodBody.substring(returnStart + "return".length, semicolon).trim())
                ?.let(namespaces::add)
            searchStart = semicolon + 1
        }
        return namespaces.distinct().singleOrNull()
    }

    private fun resolveJavaSourceForType(
        owner: String,
        currentSource: String,
        projectDir: Path,
        currentFile: Path
    ): JavaSourceReference? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null

        val currentPackage = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(maskJavaCommentsAndLiterals(currentSource))
            ?.groupValues
            ?.get(1)
        val imports = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .findAll(maskJavaCommentsAndLiterals(currentSource))
            .map { it.groupValues[1] }
            .toList()

        val candidates = linkedSetOf<Pair<String, String>>()
        if (owner.contains(".")) {
            val firstSegment = owner.substringBefore('.')
            if (firstSegment.firstOrNull()?.isLowerCase() == true) {
                candidates.add(owner to owner)
            } else {
                imports.firstOrNull { it.substringAfterLast('.') == firstSegment }?.let { imported ->
                    candidates.add(imported to firstSegment)
                }
                currentPackage?.let { candidates.add("$it.$firstSegment" to firstSegment) }
            }
        } else {
            imports.firstOrNull { it.substringAfterLast('.') == owner }?.let { imported ->
                candidates.add(imported to owner)
            }
            currentPackage?.let { candidates.add("$it.$owner" to owner) }
        }

        val matches = candidates.mapNotNull { (qualifiedName, reference) ->
            val path = srcDir.resolve(qualifiedName.replace('.', '/') + ".java")
            path.takeIf { it.exists() }?.let { JavaSourceReference(it.readText(), reference) }
        }
        return matches.singleOrNull()
            ?: currentFile.takeIf { owner == it.fileName.toString().removeSuffix(".java") && it.exists() }
                ?.let { JavaSourceReference(it.readText(), owner) }
    }

    private fun findStaticResourceLocationMethodBody(source: String, methodName: String): String? {
        val executableSource = maskJavaCommentsAndLiterals(source)
        val candidates = mutableListOf<String>()
        Regex("""\b${Regex.escape(methodName)}\s*\(""")
            .findAll(executableSource)
            .forEach { match ->
                val lineStart = executableSource.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
                val openParen = executableSource.indexOf('(', match.range.first)
                val closeParen = findMatchingParen(executableSource, openParen)
                if (closeParen < 0) return@forEach
                val openBrace = executableSource.indexOf('{', closeParen)
                if (openBrace < 0) return@forEach
                val signature = executableSource.substring(lineStart, openBrace)
                if (!Regex("""\bstatic\b""").containsMatchIn(signature) ||
                    !Regex("""\bResourceLocation\b""").containsMatchIn(signature)
                ) {
                    return@forEach
                }
                val closeBrace = findMatchingBrace(executableSource, openBrace)
                if (closeBrace > openBrace) {
                    candidates.add(source.substring(openBrace + 1, closeBrace))
                }
            }
        return candidates.distinct().singleOrNull()
    }

    private fun qualifyFactoryNamespaceExpression(namespace: String, ownerReference: String, ownerSource: String): String? {
        val trimmed = namespace.trim()
        if (Regex(""""[^"]*"""").matches(trimmed)) return trimmed
        if (Regex("""[A-Za-z_$][\w$]*""").matches(trimmed)) {
            findJavaStringConstant(ownerSource, trimmed) ?: return null
            return "$ownerReference.$trimmed"
        }
        if (Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+""").matches(trimmed)) return trimmed
        return null
    }

    private fun camelOrConstantToRegistryPath(name: String): String {
        val raw = if (name.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
            name.lowercase()
        } else {
            name.replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2").lowercase()
        }
        return raw.replace("__", "_").trim('_')
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
     * Dependencies that can be resolved are rewritten in-place; unresolved ones stay active.
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

                // Filter out coords already emitted (e.g., multiple JEI deps -> single resolved set)
                val newCoords = resolution.coords.filter { it.coord !in emittedCoords }

                val indent = lines[blockStart].takeWhile { it == ' ' || it == '\t' }
                if (newCoords.isNotEmpty()) {
                    // Replace the entire dep block with resolved NeoForge coordinates
                    val replacementLines = newCoords.map { coord -> renderResolvedDependency(indent, coord) }
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
                    // All coords already emitted �?just remove the duplicate Forge dep
                    for (k in (j - 1) downTo blockStart) lines.removeAt(k)
                    i = blockStart
                }

                // Track what was resolved
                resolvedPrefixes.addAll(resolution.coords.map { it.coord.substringBefore(":") })
                emittedCoords.addAll(resolution.coords.map { it.coord })
                resolution.mavenUrl?.let { newMavenRepos.add(it) }
            } else if (resolution is DepResolution.Remove) {
                for (k in (j - 1) downTo blockStart) lines.removeAt(k)
                changes.add(Change(
                    file = file, line = blockStart + 1,
                    description = "Removed dependency with no NeoForge 1.21.1 runtime target: ${resolution.reason}",
                    before = blockText.trim(),
                    after = "",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-dep"
                ))
                i = blockStart
            } else {
                i = j
            }
        }
        return lines.joinToString("\n")
    }

    private fun addReflectedOptionalApiDependencies(
        content: String,
        projectDir: Path,
        resolver: DependencyResolver,
        resolvedPrefixes: MutableSet<String>,
        newMavenRepos: MutableSet<String>,
        changes: MutableList<Change>,
        file: Path
    ): String {
        val referencedClasses = reflectedBinaryClassNames(projectDir)
        if (referencedClasses.isEmpty()) return content

        val coords = linkedSetOf<NeoForgeCoord>()
        for (binaryName in referencedClasses) {
            val resolution = resolver.resolveReferencedClass(binaryName)
            if (resolution !is DepResolution.Resolved) continue
            resolution.coords
                .filter { coord -> coord.config != "runtimeOnly" }
                .map { coord -> if (coord.config == "compileOnly") coord else coord.copy(config = "compileOnly") }
                .forEach { coord ->
                    coords.add(coord)
                    coord.mavenRepositoryUrl()?.let(newMavenRepos::add)
                }
            resolution.mavenUrl?.let(newMavenRepos::add)
        }
        val missing = coords.filter { coord -> !content.contains(coord.coord) }
        if (missing.isEmpty()) return content

        val modified = insertDependencies(content, missing)
        changes.add(Change(
            file = file,
            line = 1,
            description = "Add compileOnly dependencies for statically migrated optional API references",
            before = "Class.forName optional API references without compile classpath",
            after = missing.joinToString(", ") { it.coord },
            confidence = Confidence.HIGH,
            ruleId = "build-reflected-optional-api-dependencies"
        ))
        resolvedPrefixes.addAll(missing.map { it.coord.substringBefore(":") })
        return modified
    }

    private fun reflectedBinaryClassNames(projectDir: Path): Set<String> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptySet()

        val pattern = Regex("""Class\.forName\(\s*"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+(?:\$[A-Za-z_$][\w$]*)?)"\s*\)""")
        val names = linkedSetOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val source = javaFile.readText()
                val code = maskJavaComments(source)
                val executableCode = maskJavaCommentsAndLiterals(source)
                pattern.findAll(code)
                    .filter { match ->
                        executableCode.substring(match.range.first, match.range.last + 1)
                            .contains("Class.forName(")
                    }
                    .forEach { match ->
                        names.add(match.groupValues[1])
                    }
            }
        return names
    }

    private fun insertDependencies(content: String, coords: List<NeoForgeCoord>): String {
        val dependencyLines = coords.joinToString(System.lineSeparator()) { coord ->
            renderResolvedDependency("    ", coord)
        }
        val dependenciesMatch = Regex("""dependencies\s*\{""").find(content)
        if (dependenciesMatch != null) {
            val insertPos = dependenciesMatch.range.last + 1
            return content.substring(0, insertPos) +
                System.lineSeparator() +
                dependencyLines +
                content.substring(insertPos)
        }

        val insertion = "dependencies {" +
            System.lineSeparator() +
            dependencyLines +
            System.lineSeparator() +
            "}" +
            System.lineSeparator() +
            System.lineSeparator()
        val insertAt = Regex("""(?m)^tasks\.""").find(content)?.range?.first ?: content.length
        return content.substring(0, insertAt) + insertion + content.substring(insertAt)
    }

    private fun NeoForgeCoord.mavenRepositoryUrl(): String? =
        when {
            coord.startsWith("curse.maven:") -> "https://www.cursemaven.com"
            coord.startsWith("maven.modrinth:") -> "https://api.modrinth.com/maven"
            else -> null
        }

    private fun renderResolvedDependency(indent: String, coord: NeoForgeCoord): String {
        val dependency = coord.coord.trim()
        return if (dependency.startsWith("files(")) {
            "${indent}${coord.config} $dependency"
        } else if (!coord.transitive) {
            "${indent}${coord.config}(\"${coord.coord}\") { transitive = false }"
        } else {
            "${indent}${coord.config} \"${coord.coord}\""
        }
    }

    private fun removeBundledMixinDependencies(
        content: String,
        changes: MutableList<Change>,
        file: Path
    ): String {
        val depKeywords = listOf("compileOnly", "runtimeOnly", "implementation", "annotationProcessor", "testAnnotationProcessor", "testImplementation", "jarJar")
        val bundledPrefixes = listOf("org.spongepowered:mixin", "io.github.llamalad7:mixinextras")
        val lines = content.lines().toMutableList()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || depKeywords.none { trimmed.startsWith(it) }) {
                i++
                continue
            }

            val blockStart = i
            var depth = 0
            var j = i
            do {
                depth += delimiterDepthDeltaOutsideStrings(lines[j])
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")
            if (bundledPrefixes.any { prefix -> blockText.contains(prefix) }) {
                changes.add(Change(
                    file = file,
                    line = blockStart + 1,
                    description = "Remove standalone Mixin/MixinExtras dependency bundled by NeoForge",
                    before = blockText.trim(),
                    after = "(dependency removed; NeoForge provides Mixin runtime)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-remove-bundled-mixin-dependency"
                ))
                for (k in (j - 1) downTo blockStart) {
                    lines.removeAt(k)
                }
                i = blockStart
            } else {
                i = j
            }
        }
        return lines.joinToString("\n")
    }

    private fun normalizeJarJarRangePinDsl(
        content: String,
        changes: MutableList<Change>,
        file: Path
    ): String {
        val lines = content.lines().toMutableList()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || !trimmed.startsWith("jarJar") || !trimmed.contains("{")) {
                i++
                continue
            }

            val blockStart = i
            var depth = 0
            var j = i
            do {
                depth += braceDepthDeltaOutsideStrings(lines[j])
                j++
            } while (j < lines.size && depth > 0)

            val blockText = lines.subList(blockStart, j).joinToString("\n")
            if (!blockText.contains("jarJar.ranged(") && !blockText.contains("jarJar.pin(")) {
                i = j
                continue
            }

            val bodyLines = lines.subList(blockStart + 1, (j - 1).coerceAtLeast(blockStart + 1))
            val unsupportedOnly = bodyLines.all { bodyLine ->
                val bodyTrimmed = bodyLine.trim()
                bodyTrimmed.isEmpty() ||
                    bodyTrimmed.startsWith("jarJar.ranged(") ||
                    bodyTrimmed.startsWith("jarJar.pin(")
            }
            val replacementLines = if (unsupportedOnly) {
                val closureStart = indexOfOutsideStrings(lines[blockStart], '{')
                listOf(if (closureStart >= 0) lines[blockStart].substring(0, closureStart).trimEnd() else lines[blockStart])
            } else {
                lines.subList(blockStart, j).filterNot { bodyLine ->
                    val bodyTrimmed = bodyLine.trim()
                    bodyTrimmed.startsWith("jarJar.ranged(") || bodyTrimmed.startsWith("jarJar.pin(")
                }
            }

            for (k in (j - 1) downTo blockStart) lines.removeAt(k)
            for ((offset, replacementLine) in replacementLines.withIndex()) {
                lines.add(blockStart + offset, replacementLine)
            }
            changes.add(Change(
                file = file,
                line = blockStart + 1,
                description = "Remove ForgeGradle JarJar range/pin DSL unsupported by ModDev",
                before = blockText.trim(),
                after = replacementLines.joinToString("\n").trim(),
                confidence = Confidence.HIGH,
                ruleId = "build-normalize-jarjar-range-pin"
            ))
            i = blockStart + replacementLines.size
        }
        return lines.joinToString("\n")
    }

    private fun delimiterDepthDeltaOutsideStrings(line: String): Int {
        var depth = 0
        scanOutsideGradleStrings(line) { ch ->
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return depth
    }

    private fun braceDepthDeltaOutsideStrings(line: String): Int {
        var depth = 0
        scanOutsideGradleStrings(line) { ch ->
            when (ch) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    private fun indexOfOutsideStrings(line: String, target: Char): Int {
        var found = -1
        scanOutsideGradleStrings(line) { ch, index ->
            if (found < 0 && ch == target) found = index
        }
        return found
    }

    private fun scanOutsideGradleStrings(line: String, visit: (Char) -> Unit) {
        scanOutsideGradleStrings(line) { ch, _ -> visit(ch) }
    }

    private fun scanOutsideGradleStrings(line: String, visit: (Char, Int) -> Unit) {
        var inSingle = false
        var inDouble = false
        var escaped = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            val next = line.getOrNull(i + 1)
            if (!inSingle && !inDouble && ch == '/' && next == '/') break
            if (inSingle || inDouble) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (inSingle && ch == '\'') {
                    inSingle = false
                } else if (inDouble && ch == '"') {
                    inDouble = false
                }
                i++
                continue
            }
            when (ch) {
                '\'' -> inSingle = true
                '"' -> inDouble = true
                else -> visit(ch, i)
            }
            i++
        }
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
        fun renderRepo(url: String): String =
            when {
                url.contains("modrinth") -> "    maven {\n        name = \"Modrinth\"\n        url = \"$url\"\n        content { includeGroup \"maven.modrinth\" }\n    }"
                url.contains("cursemaven") -> "    maven {\n        name = \"CurseMaven\"\n        url = \"$url\"\n        content { includeGroup \"curse.maven\" }\n    }"
                else -> "    maven { url = \"$url\" }"
            }
        if (repoMatch != null) {
            val insertPos = repoMatch.range.last + 1
            val newRepoLines = repos
                .filter { url -> !result.contains(url) }
                .joinToString("\n") { url -> "\n${renderRepo(url)}" }
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
        } else {
            val newRepoLines = repos
                .filter { url -> !result.contains(url) }
                .joinToString("\n") { renderRepo(it) }
            if (newRepoLines.isNotBlank()) {
                val repoSection = "repositories {\n$newRepoLines\n}\n\n"
                val insertPos = result.indexOf("dependencies {").takeIf { it >= 0 } ?: result.length
                result = result.substring(0, insertPos) + repoSection + result.substring(insertPos)
                changes.add(Change(
                    file = file, line = result.lineNumberAt(insertPos),
                    description = "Create maven repositories block for resolved NeoForge dependencies",
                    before = "(no repositories block)",
                    after = repos.joinToString(", "),
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-maven-repos"
                ))
            }
        }
        return result
    }

    /**
     * Keep third-party dependencies active while removing ForgeGradle-only wrappers.
     * Dependency incompatibility must surface as a real resolution/compile failure.
     */
    private fun normalizeOldDependencyWrappers(content: String): String {
        val depKeywords = listOf("compileOnly", "runtimeOnly", "implementation", "annotationProcessor", "def ")

        val lines = content.lines().toMutableList()
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
            if (blockText.contains("fg.deobf")) {
                val replacementLines = blockText.lines().map { removeFgDeobfWrapper(it) }
                for (k in blockStart until j) {
                    lines[k] = replacementLines[k - blockStart]
                }
            }
            i = j
        }
        return lines.joinToString("\n")
    }

    private fun removeFgDeobfWrapper(line: String): String =
        line
            .replace(
                Regex("""\b([A-Za-z_$][\w$]*)\s+fg\.deobf\s*\(\s*([^)]+?)\s*\)"""),
                "$1 $2"
            )
            .replace(
                Regex("""\b([A-Za-z_$][\w$]*)\s+fg\.deobf\s+("[^"]+"|'[^']+'|[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)"""),
                "$1 $2"
            )

    private data class LegacyTreeGrower(
        val className: String,
        val path: Path
    )

    private data class LegacyArmorMaterial(
        val enumName: String,
        val path: Path,
        val packageName: String,
        val modIdExpr: String,
        val modImport: String?,
        val extraImports: List<String>,
        val constants: List<LegacyArmorMaterialConstant>
    )

    private data class LegacyArmorMaterialConstant(
        val fieldName: String,
        val registryName: String,
        val textureName: String,
        val protections: List<String>,
        val enchantmentValue: String,
        val equipSound: String,
        val repairIngredient: String,
        val toughness: String,
        val knockbackResistance: String,
        val bodyProtection: String? = null
    )

    private data class CustomStatDeclaration(
        val fieldName: String,
        val modIdExpr: String,
        val pathName: String
    )

    private data class JavaTopLevelTypeOpening(
        val name: String,
        val range: IntRange
    )

    private fun rewriteLegacyArmorMaterials(
        projectDir: Path,
        dryRun: Boolean,
        errors: MutableList<String>
    ): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val materials = mutableListOf<LegacyArmorMaterial>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val material = parseLegacyArmorMaterial(javaFile, original, projectDir, errors) ?: return@forEach
                val replacement = renderArmorMaterialRegistry(material)
                if (replacement != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite legacy ArmorMaterial enum as 1.21 registry-backed ArmorMaterial holder class",
                        before = "enum ${material.enumName} implements ArmorMaterial",
                        after = "${material.enumName}.ARMOR_MATERIALS DeferredRegister",
                        confidence = Confidence.HIGH,
                        ruleId = "build-legacy-armor-material-registry"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(replacement)
                    }
                }
                materials.add(material)
            }

        if (materials.isEmpty()) return changes

        changes.addAll(rewriteLegacyArmorMaterialConsumers(srcDir, materials, dryRun))
        changes.addAll(registerLegacyArmorMaterials(srcDir, materials, dryRun))
        return changes
    }

    private fun migrateDataGenerationApis(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val recipeProviders = linkedSetOf<String>()
        val abstractRecipeProviders = linkedSetOf<String>()
        val abstractRecipeProviderModIds = mutableMapOf<String, String>()
        val lootTableProviders = linkedSetOf<String>()
        val lootModifierProviders = linkedSetOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val content = javaFile.readText()
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+RecipeProvider\b""")
                    .find(content)
                    ?.let { recipeProviders.add(it.groupValues[1]) }
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+AbstractRecipeProvider\b""")
                    .find(content)
                    ?.let {
                        val providerClass = it.groupValues[1]
                        abstractRecipeProviders.add(providerClass)
                        inferModIdExpression(content, projectDir)?.let { modIdExpr ->
                            abstractRecipeProviderModIds[providerClass] = modIdExpr
                        }
                    }
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+LootTableProvider\b""")
                    .find(content)
                    ?.let { lootTableProviders.add(it.groupValues[1]) }
                Regex("""class\s+([A-Za-z_$][\w$]*)\s+extends\s+GlobalLootModifierProvider\b""")
                    .find(content)
                    ?.let { lootModifierProviders.add(it.groupValues[1]) }
            }
        if (recipeProviders.isEmpty() &&
            abstractRecipeProviders.isEmpty() &&
            lootTableProviders.isEmpty() &&
            lootModifierProviders.isEmpty()) return changes

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                var modified = original

                for (providerClass in recipeProviders) {
                    val constructorPattern = Regex(
                        """(?m)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\(\s*([A-Za-z_$][\w$]*)\s*\);\s*}"""
                    )
                    modified = constructorPattern.replace(modified) { match ->
                        val indent = match.groupValues[1]
                        val packOutputParam = match.groupValues[2]
                        val superArg = match.groupValues[3]
                        if (packOutputParam != superArg) {
                            match.value
                        } else {
                            "$indent" +
                                "public $providerClass(PackOutput $packOutputParam, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n" +
                                "$indent    super($packOutputParam, lookupProvider);\n" +
                                "$indent}"
                        }
                    }

                    val constructorCall = Regex("""new\s+${Regex.escape(providerClass)}\s*\(\s*packOutput\s*\)""")
                    modified = constructorCall.replace(modified, "new $providerClass(packOutput, event.getLookupProvider())")
                }

                val lookupProviderExpr = Regex("""CompletableFuture\s*<\s*HolderLookup\.Provider\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*event\.getLookupProvider\(\)""")
                    .find(modified)
                    ?.groupValues
                    ?.get(1)
                    ?: "event.getLookupProvider()"

                for (providerClass in abstractRecipeProviders) {
                    val constructorPattern = Regex(
                        """(?m)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\(\s*([A-Za-z_$][\w$]*)\s*\);\s*}"""
                    )
                    val constructorCall = Regex("""new\s+${Regex.escape(providerClass)}\s*\(\s*packOutput\s*\)""")
                    val providerModIdExpr = abstractRecipeProviderModIds[providerClass]
                    if (providerModIdExpr == null) {
                        if (constructorPattern.containsMatchIn(modified) || constructorCall.containsMatchIn(modified)) {
                            throw IllegalStateException(
                                "Cannot derive mod id for AbstractRecipeProvider $providerClass from Java source, Gradle properties, or mod metadata"
                            )
                        }
                        continue
                    }
                    modified = constructorPattern.replace(modified) { match ->
                        val indent = match.groupValues[1]
                        val packOutputParam = match.groupValues[2]
                        val superArg = match.groupValues[3]
                        if (packOutputParam != superArg) {
                            match.value
                        } else {
                            "$indent" +
                                "public $providerClass(PackOutput $packOutputParam, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n" +
                                "$indent    super($packOutputParam, $providerModIdExpr, lookupProvider);\n" +
                                "$indent}"
                        }
                    }

                    modified = constructorCall.replace(modified, "new $providerClass(packOutput, $lookupProviderExpr)")
                }

                for (providerClass in lootTableProviders) {
                    modified = migrateLootTableProviderConstructor(modified, providerClass)

                    val constructorCall = Regex("""new\s+${Regex.escape(providerClass)}\s*\(\s*packOutput\s*\)""")
                    modified = constructorCall.replace(modified, "new $providerClass(packOutput, $lookupProviderExpr)")
                }

                for (providerClass in lootModifierProviders) {
                    val constructorPattern = Regex(
                        """(?m)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*,\s*String\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\(\s*([A-Za-z_$][\w$]*)\s*,\s*([A-Za-z_$][\w$]*)\s*\);\s*}"""
                    )
                    modified = constructorPattern.replace(modified) { match ->
                        val indent = match.groupValues[1]
                        val outputParam = match.groupValues[2]
                        val modidParam = match.groupValues[3]
                        val superOutput = match.groupValues[4]
                        val superModid = match.groupValues[5]
                        if (outputParam != superOutput || modidParam != superModid) {
                            match.value
                        } else {
                            "$indent" +
                                "public $providerClass(PackOutput $outputParam, CompletableFuture<HolderLookup.Provider> lookupProvider, String $modidParam) {\n" +
                                "$indent    super($outputParam, lookupProvider, $modidParam);\n" +
                                "$indent}"
                        }
                    }

                    val constructorCall = Regex("""new\s+${Regex.escape(providerClass)}\s*\(\s*packOutput\s*,\s*([^,()]+(?:\([^()]*\))?)\s*\)""")
                    modified = constructorCall.replace(modified) { match ->
                        val existingSecondArg = match.groupValues[1].trim()
                        if (existingSecondArg == lookupProviderExpr || existingSecondArg.endsWith(".getLookupProvider()")) {
                            match.value
                        } else {
                            "new $providerClass(packOutput, $lookupProviderExpr, $existingSecondArg)"
                        }
                    }
                }

                if (modified.contains("extends RecipeProvider")) {
                    modified = Regex(
                        """private\s+void\s+specialRecipe\s*\(\s*RecipeOutput\s+consumer\s*,\s*SimpleCraftingRecipeSerializer<[^>]+>\s+serializer\s*\)"""
                    ).replace(
                        modified,
                        "private void specialRecipe(RecipeOutput consumer, java.util.function.Function<net.minecraft.world.item.crafting.CraftingBookCategory, net.minecraft.world.item.crafting.Recipe<?>> recipeFactory, ResourceLocation name)"
                    )
                    modified = modified.replace(
                        Regex("""(?m)^[ \t]*ResourceLocation\s+name\s*=\s*(?:BuiltInRegistries|Registries)\.RECIPE_SERIALIZER\.getKey\(serializer\);\s*\r?\n"""),
                        ""
                    )
                    modified = modified.replace("SpecialRecipeBuilder.special(serializer)", "SpecialRecipeBuilder.special(recipeFactory)")
                    modified = Regex(
                        """specialRecipe\(([^,\r\n]+),\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.SERIALIZER\s*\)"""
                    ).replace(modified) { match ->
                        "specialRecipe(${match.groupValues[1]}, ${match.groupValues[2]}::new, BuiltInRegistries.RECIPE_SERIALIZER.getKey(${match.groupValues[2]}.SERIALIZER))"
                    }
                }

                if (modified != original) {
                    if (modified.contains("CompletableFuture<HolderLookup.Provider>")) {
                        modified = ensureJavaImport(modified, "java.util.concurrent.CompletableFuture")
                        modified = ensureJavaImport(modified, "net.minecraft.core.HolderLookup")
                    }
                    if (modified.contains("BuiltInRegistries.")) {
                        modified = ensureJavaImport(modified, "net.minecraft.core.registries.BuiltInRegistries")
                    }
                    if (!dryRun) javaFile.writeText(modified)
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Migrate 1.21 data generation provider APIs",
                        before = "(legacy datagen provider API)",
                        after = "(lookup-provider aware datagen API)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-datagen-api-121"
                    ))
                }
            }

        return changes
    }

    private fun inferModIdExpression(source: String, projectDir: Path): String? {
        modIdExpressionFromModClass(source, className = null, packageName = null)?.let { return it }

        return projectModIdExpression(projectDir)
    }

    private fun projectModIdExpression(projectDir: Path): String? =
        projectModAnnotationExpression(projectDir)
            ?: detectUniqueProjectModId(projectDir)?.let(::javaStringLiteral)

    private fun projectModAnnotationExpression(projectDir: Path): String? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null
        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val expressions = javaFiles
            .mapNotNull { file ->
                val source = file.readText()
                val executableSource = maskJavaCommentsAndLiterals(source)
                val className = file.fileName.toString().removeSuffix(".java")
                val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                    .find(executableSource)
                    ?.groupValues
                    ?.get(1)
                modIdExpressionFromModClass(source, className, packageName)
            }
            .distinct()
            .toList()
        return expressions.singleOrNull()
    }

    private fun modIdExpressionFromModClass(source: String, className: String?, packageName: String?): String? {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        val rawArgument = Regex("""@Mod\s*\(\s*([^)]+?)\s*\)""")
            .find(code)
            ?.takeIf { match ->
                executableCode
                    .substring(match.range.first, match.range.last + 1)
                    .contains("@Mod")
            }
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: return null
        val argument = Regex("""(?:value\s*=\s*)?(.+)""")
            .matchEntire(rawArgument)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: return null
        if (argument.isBlank() || argument.any { it == ';' || it == '{' || it == '}' || it == '\n' || it == '\r' }) {
            return null
        }
        Regex(""""([^"]+)"""").matchEntire(argument)?.let { literal ->
            return javaStringLiteral(literal.groupValues[1])
        }

        val localClassName = className ?: Regex("""(?m)\bclass\s+([A-Za-z_$][\w$]*)\b""")
            .find(executableCode)
            ?.groupValues
            ?.get(1)
        val constantName = when {
            Regex("""[A-Za-z_$][\w$]*""").matches(argument) -> argument
            localClassName != null -> Regex("""${Regex.escape(localClassName)}\.([A-Za-z_$][\w$]*)""")
                .matchEntire(argument)
                ?.groupValues
                ?.get(1)
            else -> null
        } ?: return null
        findJavaStringConstant(source, constantName) ?: return null
        val owner = listOfNotNull(packageName?.takeIf { it.isNotBlank() }, localClassName)
            .joinToString(".")
            .takeIf { it.isNotBlank() }
            ?: return null
        return "$owner.$constantName"
    }

    private fun detectUniqueProjectModId(projectDir: Path): String? {
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            val value = raw?.trim()?.trim('"') ?: return
            if (value.isNotBlank() &&
                !value.contains('$') &&
                !value.contains('{') &&
                Regex("""[a-z0-9_.-]+""", RegexOption.IGNORE_CASE).matches(value)
            ) {
                candidates.add(value)
            }
        }

        projectDir.resolve("gradle.properties")
            .takeIf { it.exists() }
            ?.readText()
            ?.let { properties ->
                Regex("""(?m)^\s*(?:mod_id|modid)\s*=\s*([^\s#]+)""")
                    .findAll(properties)
                    .forEach { addCandidate(it.groupValues[1]) }
            }

        listOf(
            projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
            projectDir.resolve("src/main/resources/META-INF/mods.toml")
        ).forEach { toml ->
            toml.takeIf { it.exists() }
                ?.readText()
                ?.let { text ->
                    projectModIdsFromToml(text).forEach(::addCandidate)
                }
        }

        val srcDir = projectDir.resolve("src/main/java")
        if (srcDir.exists()) {
            java.nio.file.Files.walk(srcDir)
                .filter { it.toString().endsWith(".java") }
                .forEach { file ->
                    val text = file.readText()
                    val code = maskJavaComments(text)
                    val executableCode = maskJavaCommentsAndLiterals(text)
                    Regex("""@Mod\s*\(\s*"([a-z0-9_.-]+)"\s*\)""", RegexOption.IGNORE_CASE)
                        .findAll(code)
                        .filter { match ->
                            executableCode
                                .substring(match.range.first, match.range.last + 1)
                                .contains("@Mod")
                        }
                        .forEach { addCandidate(it.groupValues[1]) }

                    Regex("""@Mod\s*\(\s*(?:[A-Za-z_$][\w$]*\.)?([A-Za-z_$][\w$]*)\s*\)""")
                        .findAll(code)
                        .filter { match ->
                            executableCode
                                .substring(match.range.first, match.range.last + 1)
                                .contains("@Mod")
                        }
                        .forEach { match ->
                            findJavaStringConstant(text, match.groupValues[1])?.let(::addCandidate)
                        }
                }
        }

        return candidates.singleOrNull()
    }

    private fun projectModIdsFromToml(text: String): List<String> {
        val ids = mutableListOf<String>()
        var inModsTable = false
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[[") -> inModsTable = trimmed == "[[mods]]"
                trimmed.startsWith("[") -> inModsTable = false
                inModsTable -> {
                    Regex("""^modId\s*=\s*"([^"]+)"""")
                        .find(trimmed)
                        ?.groupValues
                        ?.get(1)
                        ?.let(ids::add)
                }
            }
        }
        return ids
    }

    private fun findJavaStringConstant(source: String, constantName: String): String? {
        val code = maskJavaComments(source)
        val executableCode = maskJavaCommentsAndLiterals(source)
        return Regex("(?m)\\b(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?(?:final\\s+)?String\\s+${Regex.escape(constantName)}\\s*=\\s*\"([^\"]+)\"")
            .find(code)
            ?.takeIf { match ->
                val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)
                executableSegment.contains("String") &&
                    executableSegment.contains("=")
            }
            ?.groupValues
            ?.get(1)
    }

    private fun javaStringLiteral(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun migrateLootTableProviderConstructor(source: String, providerClass: String): String {
        val constructorPattern = Regex(
            """(?ms)^([ \t]*)public\s+${Regex.escape(providerClass)}\s*\(\s*PackOutput\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*super\((.*?)\);\s*}"""
        )
        return constructorPattern.replace(source) { match ->
            val indent = match.groupValues[1]
            val outputParam = match.groupValues[2]
            val superArgs = splitTopLevel(match.groupValues[3], ',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (superArgs.firstOrNull() != outputParam || superArgs.any { it.contains("lookupProvider") }) {
                match.value
            } else {
                "$indent" +
                    "public $providerClass(PackOutput $outputParam, CompletableFuture<HolderLookup.Provider> lookupProvider) {\n" +
                    "$indent    super(${(superArgs + "lookupProvider").joinToString(", ")});\n" +
                    "$indent}"
            }
        }
    }

    private fun migrateCustomStatRegistration(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val javaFiles = java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .toList()
        val migratedStatClasses = linkedSetOf<String>()

        val statDeclarationPattern = Regex(
            """public\s+static\s+final\s+ResourceLocation\s+([A-Za-z_$][\w$]*)\s*=\s*(?:ResourceLocation\.fromNamespaceAndPath\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\)|new\s+ResourceLocation\s*\(\s*([^,]+?)\s*,\s*"([^"]+)"\s*\))\s*;"""
        )

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            if (!original.contains("BuiltInRegistries.CUSTOM_STAT") || !original.contains("Stats.CUSTOM.get")) {
                continue
            }

            val declarations = statDeclarationPattern.findAll(original)
                .mapNotNull { match ->
                    val modIdExpr = match.groupValues[2].ifBlank { match.groupValues[4] }.trim()
                    val pathName = match.groupValues[3].ifBlank { match.groupValues[5] }.trim()
                    if (modIdExpr.isBlank() || pathName.isBlank()) {
                        null
                    } else {
                        CustomStatDeclaration(match.groupValues[1], modIdExpr, pathName)
                    }
            }
                .toList()
            if (declarations.isEmpty()) continue
            val customStatNamespaces = declarations.map { it.modIdExpr }.distinct()
            if (customStatNamespaces.size != 1) {
                throw IllegalStateException(
                    "Cannot migrate custom stat registration in " +
                        projectDir.relativize(javaFile).toString().replace('\\', '/') +
                        ": mixed custom stat namespaces ${customStatNamespaces.joinToString(", ")}"
                )
            }
            val customStatNamespace = customStatNamespaces.single()
            val declaredStatOwner = javaTopLevelTypeOpening(original)?.name
                ?: throw IllegalStateException(
                    "Cannot migrate custom stat registration in " +
                        projectDir.relativize(javaFile).toString().replace('\\', '/') +
                        ": missing top-level Java type declaration"
                )

            var modified = original
            val oldImports = listOf(
                "net.minecraft.core.Registry",
                "net.minecraft.core.registries.BuiltInRegistries",
                "net.minecraft.stats.StatFormatter",
                "net.minecraft.stats.Stats"
            )
            for (importName in oldImports) {
                modified = modified.replace(
                    Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n"""),
                    ""
                )
            }
            modified = ensureJavaImport(modified, "net.minecraft.core.registries.Registries")
            modified = ensureJavaImport(modified, "net.neoforged.bus.api.IEventBus")
            modified = ensureJavaImport(modified, "net.neoforged.neoforge.registries.DeferredRegister")

            if (!modified.contains("DeferredRegister<ResourceLocation> CUSTOM_STATS")) {
                val classMatch = javaTopLevelTypeOpening(modified)
                    ?: throw IllegalStateException(
                        "Cannot migrate custom stat registration in " +
                            projectDir.relativize(javaFile).toString().replace('\\', '/') +
                            ": missing top-level Java type declaration"
                    )
                val declaration = "\n    private static final DeferredRegister<ResourceLocation> CUSTOM_STATS =\n" +
                    "            DeferredRegister.create(Registries.CUSTOM_STAT, $customStatNamespace);\n"
                modified = modified.substring(0, classMatch.range.last + 1) +
                    declaration +
                    modified.substring(classMatch.range.last + 1)
            }

            val missingRegistrations = declarations.filterNot {
                modified.contains("CUSTOM_STATS.register(\"${it.pathName}\"")
            }
            if (missingRegistrations.isNotEmpty()) {
                val latestConstant = statDeclarationPattern.findAll(modified).lastOrNull()
                if (latestConstant != null) {
                    val staticBlock = buildString {
                        append("\n\n    static {\n")
                        for (declaration in missingRegistrations) {
                            append("        CUSTOM_STATS.register(\"${declaration.pathName}\", () -> ${declaration.fieldName});\n")
                        }
                        append("    }")
                    }
                    modified = modified.substring(0, latestConstant.range.last + 1) +
                        staticBlock +
                        modified.substring(latestConstant.range.last + 1)
                }
            }

            modified = Regex(
                """(?s)\n([ \t]*)public\s+static\s+void\s+register\s*\(\s*\)\s*\{.*?\n\1\}"""
            ).replace(modified) { match ->
                "\n${match.groupValues[1]}public static void register(IEventBus modEventBus) {\n" +
                    "${match.groupValues[1]}    CUSTOM_STATS.register(modEventBus);\n" +
                    "${match.groupValues[1]}}"
            }
            modified = Regex(
                """(?s)\n[ \t]*private\s+static\s+void\s+registerStat\s*\([^)]*\)\s*\{.*?\n[ \t]*\}"""
            ).replace(modified, "")

            if (modified != original) {
                migratedStatClasses.add(declaredStatOwner)
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Migrate legacy custom stat registration to DeferredRegister",
                    before = "Registry.register(BuiltInRegistries.CUSTOM_STAT, ...)",
                    after = "DeferredRegister.create(Registries.CUSTOM_STAT, ...)",
                    confidence = Confidence.HIGH,
                    ruleId = "build-custom-stat-deferred-register"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        if (migratedStatClasses.isEmpty()) return changes

        for (javaFile in javaFiles) {
            val original = javaFile.readText()
            var modified = original
            for (className in migratedStatClasses) {
                val oldCallPattern = Regex("""(?m)^[ \t]*${Regex.escape(className)}\.register\(\);\s*(?://.*)?\r?\n?""")
                if (!oldCallPattern.containsMatchIn(modified)) continue

                modified = oldCallPattern.replace(modified, "")
                val newCall = "$className.register(modEventBus);"
                if (!modified.contains(newCall)) {
                    val lines = modified.lines().toMutableList()
                    val registerIdx = lines.indexOfLast {
                        it.contains(".register(modEventBus);") && !it.contains(newCall)
                    }
                    val eventBusIdx = lines.indexOfFirst { it.contains("IEventBus modEventBus") && it.contains("{") }
                    val insertIdx = when {
                        registerIdx >= 0 -> registerIdx + 1
                        eventBusIdx >= 0 -> eventBusIdx + 1
                        else -> -1
                    }
                    if (insertIdx >= 0) {
                        val indent = lines.getOrNull((insertIdx - 1).coerceAtLeast(0))
                            ?.takeWhile { it.isWhitespace() }
                            ?.ifBlank { "        " }
                            ?: "        "
                        lines.add(insertIdx, "$indent$newCall")
                        modified = lines.joinToString("\n")
                    }
                }
            }

            if (modified != original) {
                changes.add(Change(
                    file = javaFile,
                    line = 1,
                    description = "Register custom stats on the mod event bus before registry freeze",
                    before = "ModStats.register() during setup",
                    after = "ModStats.register(modEventBus) during construction",
                    confidence = Confidence.HIGH,
                    ruleId = "build-custom-stat-mod-event-bus"
                ))
                if (!dryRun) javaFile.writeText(modified)
            }
        }

        return changes
    }

    private fun javaTopLevelTypeOpening(source: String): JavaTopLevelTypeOpening? {
        val executableCode = maskJavaCommentsAndLiterals(source)
        val match = Regex(
            """(?m)^[ \t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b[^{]*\{"""
        ).find(executableCode) ?: return null
        return JavaTopLevelTypeOpening(match.groupValues[1], match.range)
    }

    private fun migrateRegisterEventResourceLocations(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("RegisterEvent") ||
                    (!original.contains("registry.register(ResourceLocation.parse(") &&
                        !Regex("""registry\.register\(\s*"[A-Za-z0-9_./:-]+"\s*,""").containsMatchIn(original))) {
                    return@forEach
                }

                var resolvedModIdExpr: String? = null
                fun sourceModIdExpr(): String? {
                    resolvedModIdExpr?.let { return it }
                    val detected = Regex("""@EventBusSubscriber\s*\([^)]*\bmodid\s*=\s*([^,)]+)""")
                        .find(original)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?: Regex("""@Mod\s*\(\s*([^)]+?)\s*\)""")
                            .find(original)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                        ?: Regex("""(?:public\s+)?(?:static\s+)?final\s+String\s+(?:MODID|MOD_ID)\s*=\s*"([^"]+)"""")
                            .find(original)
                            ?.groupValues
                            ?.get(1)
                            ?.let { "\"$it\"" }
                        ?: detectModId(projectDir)?.let { "\"$it\"" }
                    resolvedModIdExpr = detected
                    return detected
                }

                var modified = Regex("""registry\.register\(\s*ResourceLocation\.parse\("([A-Za-z0-9_./:-]+)"\)\s*,""")
                    .replace(original) { match ->
                        val path = match.groupValues[1]
                        if (path.contains(":")) {
                            match.value
                        } else {
                            sourceModIdExpr()
                                ?.let { modIdExpr -> """registry.register(ResourceLocation.fromNamespaceAndPath($modIdExpr, "$path"),""" }
                                ?: match.value
                        }
                    }
                modified = Regex("""registry\.register\(\s*"([A-Za-z0-9_./:-]+)"\s*,""")
                    .replace(modified) { match ->
                        val id = match.groupValues[1]
                        if (id.contains(":")) {
                            """registry.register(ResourceLocation.parse("$id"),"""
                        } else {
                            sourceModIdExpr()
                                ?.let { modIdExpr -> """registry.register(ResourceLocation.fromNamespaceAndPath($modIdExpr, "$id"),""" }
                                ?: match.value
                        }
                    }

                if (modified != original) {
                    modified = ensureJavaImport(modified, "net.minecraft.resources.ResourceLocation")
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Namespace RegisterEvent resource location IDs with the current mod id",
                        before = "registry.register(\"name\", ...) or registry.register(ResourceLocation.parse(\"name\"), ...)",
                        after = "registry.register(ResourceLocation.fromNamespaceAndPath(MODID, \"name\"), ...)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-registerevent-resource-location-namespace"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        return changes
    }

    private fun migrateWorldCarverRegisterEvents(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes
        val migratedCarverClasses = linkedSetOf<String>()

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("RegisterEvent") ||
                    (!original.contains("WORLD_CARVERS") && !original.contains("Registries.CARVER"))) {
                    return@forEach
                }

                var modified = original
                val fieldIds = Regex(""""([A-Za-z0-9_./-]+)"\s*\)\s*,\s*([A-Za-z_$][\w$]*)""")
                    .findAll(original)
                    .associate { it.groupValues[2] to it.groupValues[1] }
                    .toMutableMap()
                val migratedFields = linkedSetOf<String>()
                val modIdExpr = detectWorldCarverModIdExpression(original, projectDir, javaFile)
                    ?: throw IllegalStateException(
                        "Cannot derive mod id for world carver registration in " +
                            projectDir.relativize(javaFile).toString().replace('\\', '/')
                    )

                modified = Regex("""(?m)^[ \t]*@(?:Mod\.)?EventBusSubscriber\([^\r\n]*\)\s*\r?\n""")
                    .replace(modified, "")

                val carverFieldPattern = Regex(
                    """(?ms)^([ \t]*)public\s+static\s+final\s+([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+\2\s*\((.*?)\);"""
                )
                modified = carverFieldPattern.replace(modified) { match ->
                    val indent = match.groupValues[1]
                    val type = match.groupValues[2]
                    val field = match.groupValues[3]
                    val args = match.groupValues[4].trim()
                    if (!type.endsWith("Carver")) {
                        match.value
                    } else {
                        val registryName = fieldIds[field] ?: camelOrConstantToRegistryPath(field)
                        migratedFields.add(field)
                        "$indent" +
                            "public static final DeferredHolder<WorldCarver<?>, $type> $field = " +
                            "CARVER_TYPES.register(\"$registryName\", () -> new $type($args));"
                    }
                }

                if (migratedFields.isNotEmpty()) {
                    val classMatch = Regex("""(?m)^[ \t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*class\s+([A-Za-z_$][\w$]*)[^{]*\{""").find(modified)
                    if (classMatch != null) {
                        val className = classMatch.groupValues[1]
                        migratedCarverClasses.add(className)
                        Regex("""(?m)^package\s+([\w.]+);""").find(modified)?.groupValues?.get(1)?.let { packageName ->
                            migratedCarverClasses.add("$packageName.$className")
                        }
                        if (!modified.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES")) {
                            val insertPos = classMatch.range.last + 1
                            modified = modified.substring(0, insertPos) +
                                "\n\tpublic static final DeferredRegister<WorldCarver<?>> CARVER_TYPES = DeferredRegister.create(Registries.CARVER, $modIdExpr);\n" +
                                modified.substring(insertPos)
                        }
                    }
                }

                val registerMethodPattern = Regex(
                    """(?m)^[ \t]*(?://[^\r\n]*\r?\n\s*)*(?:@[^\r\n]+\r?\n\s*)*public\s+static\s+void\s+register\s*\(\s*RegisterEvent\s+[A-Za-z_$][\w$]*\s*\)\s*\{"""
                )
                val methodMatch = registerMethodPattern.find(modified)
                if (methodMatch != null) {
                    val openBrace = modified.indexOf('{', methodMatch.range.first)
                    val closeBrace = if (openBrace >= 0) findMatchingBrace(modified, openBrace) else -1
                    if (closeBrace > openBrace) {
                        var removeEnd = closeBrace + 1
                        if (removeEnd < modified.length && modified[removeEnd] == '\r') removeEnd++
                        if (removeEnd < modified.length && modified[removeEnd] == '\n') removeEnd++
                        modified = modified.substring(0, methodMatch.range.first) + modified.substring(removeEnd)
                    }
                }

                for (field in migratedFields) {
                    modified = Regex("""\b${Regex.escape(field)}\.configured\(""")
                        .replace(modified, "$field.value().configured(")
                }

                if (migratedFields.isNotEmpty()) {
                    modified = addJavaImportIfMissing(modified, "net.minecraft.world.level.levelgen.carver.WorldCarver")
                    modified = addJavaImportIfMissing(modified, "net.neoforged.neoforge.registries.DeferredHolder")
                    modified = addJavaImportIfMissing(modified, "net.neoforged.neoforge.registries.DeferredRegister")
                    modified = removeJavaImport(modified, "net.minecraftforge.eventbus.api.SubscribeEvent")
                    modified = removeJavaImport(modified, "net.neoforged.bus.api.SubscribeEvent")
                    modified = removeJavaImport(modified, "net.minecraftforge.fml.common.Mod")
                    modified = removeJavaImport(modified, "net.neoforged.fml.common.Mod")
                    modified = removeJavaImport(modified, "net.minecraftforge.registries.ForgeRegistries")
                    modified = removeJavaImport(modified, "net.neoforged.neoforge.registries.ForgeRegistries")
                    modified = removeJavaImport(modified, "net.minecraftforge.registries.RegisterEvent")
                    modified = removeJavaImport(modified, "net.neoforged.neoforge.registries.RegisterEvent")
                    if (!Regex("""\bObjects\.""").containsMatchIn(modified)) {
                        modified = removeJavaImport(modified, "java.util.Objects")
                    }
                    if (!Regex("""\bResourceLocation\b""").containsMatchIn(modified)) {
                        modified = removeJavaImport(modified, "net.minecraft.resources.ResourceLocation")
                    }
                    if (!Regex("""\bBuiltInRegistries\b""").containsMatchIn(modified)) {
                        modified = removeJavaImport(modified, "net.minecraft.core.registries.BuiltInRegistries")
                    }
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Migrate world carver RegisterEvent registration to DeferredRegister",
                        before = "RegisterEvent + ForgeRegistries.WORLD_CARVERS",
                        after = "DeferredRegister<WorldCarver<?>> CARVER_TYPES",
                        confidence = Confidence.HIGH,
                        ruleId = "build-world-carver-deferred-register"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val listenerPattern = Regex(
                    """(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.addListener\(\s*((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*)::register\s*\);\s*$"""
                )
                val modified = listenerPattern.replace(original) { match ->
                    val indent = match.groupValues[1]
                    val bus = match.groupValues[2]
                    val carverClass = match.groupValues[3]
                    val simpleName = carverClass.substringAfterLast('.')
                    if (carverClass in migratedCarverClasses || simpleName in migratedCarverClasses) {
                        "$indent$carverClass.CARVER_TYPES.register($bus);"
                    } else {
                        match.value
                    }
                }
                if (modified != original) {
                    val match = listenerPattern.find(original)
                    changes.add(Change(
                        file = javaFile,
                        line = match?.let { original.lineNumberAt(it.range.first) } ?: 1,
                        description = "Register migrated world carver DeferredRegister on the mod event bus",
                        before = match?.value?.trim() ?: "CarverTypes::register listener",
                        after = "CarverTypes.CARVER_TYPES.register(modEventBus)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-world-carver-modbus-register"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        return changes
    }

    private fun parseLegacyArmorMaterial(
        path: Path,
        content: String,
        projectDir: Path,
        errors: MutableList<String>
    ): LegacyArmorMaterial? {
        val enumMatch = Regex("""public\s+enum\s+([A-Za-z_$][\w$]*)\s+implements\s+ArmorMaterial\b""")
            .find(content) ?: return null
        val enumName = enumMatch.groupValues[1]
        val bodyStart = content.indexOf('{', enumMatch.range.last)
        if (bodyStart < 0) return null
        val constantsEnd = findLegacyEnumConstantsEnd(content, bodyStart + 1)
        if (constantsEnd < 0) return null

        val constants = splitTopLevel(content.substring(bodyStart + 1, constantsEnd), ',')
            .mapNotNull { parseLegacyArmorMaterialConstant(it) }
        if (constants.isEmpty()) return null

        val packageName = Regex("""(?m)^package\s+([\w.]+);""")
            .find(content)?.groupValues?.get(1) ?: return null
        val modIdExpr = Regex("""return\s+([A-Za-z_$][\w$.]*\.MODID)\s*\+""")
            .find(content)?.groupValues?.get(1)
            ?: Regex("""\b([A-Za-z_$][\w$]*\.MODID)\b""").find(content)?.groupValues?.get(1)
            ?: Regex("""\b([A-Za-z_$][\w$]*\.ID)\b""").find(content)?.groupValues?.get(1)
            ?: projectModIdExpression(projectDir)
        if (modIdExpr == null) {
            val relative = projectDir.relativize(path).toString().replace('\\', '/')
            errors.add(
                "Cannot derive mod id expression for legacy ArmorMaterial enum $enumName in $relative: " +
                    "expected a source MODID/ID reference in the enum or a unique project @Mod/mod metadata id"
            )
            return null
        }
        val modClass = when {
            modIdExpr.endsWith(".MODID") -> modIdExpr.substringBefore(".MODID")
            modIdExpr.endsWith(".ID") -> modIdExpr.substringBefore(".ID")
            else -> ""
        }
        val modImport = if (modClass.isNotBlank() && !modClass.contains("\"") && !modClass.contains(".")) {
            Regex("""(?m)^import\s+([\w.]+\.$modClass);""").find(content)?.groupValues?.get(1)
        } else {
            null
        }
        val sourceImports = Regex("""(?m)^import\s+([\w.]+);""")
            .findAll(content)
            .map { it.groupValues[1] }
            .toList()
        val referencedTopLevelSymbols = constants
            .flatMap { listOf(it.equipSound, it.repairIngredient) }
            .flatMap { Regex("""\b([A-Z][A-Za-z0-9_$]*)\.""").findAll(it).map { match -> match.groupValues[1] }.toList() }
            .toSet()
        val extraImports = sourceImports
            .filter { importName -> referencedTopLevelSymbols.contains(importName.substringAfterLast('.')) }
            .filterNot { it == modImport }

        return LegacyArmorMaterial(enumName, path, packageName, modIdExpr, modImport, extraImports, constants)
    }

    private fun parseLegacyArmorMaterialConstant(raw: String): LegacyArmorMaterialConstant? {
        val withoutLineComments = raw.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
            .trim()
            .trimEnd(',')
            .trim()
        if (withoutLineComments.isEmpty()) return null
        val match = Regex("""(?s)^([A-Za-z_$][\w$]*)\s*\((.*)\)$""").find(withoutLineComments) ?: return null
        val args = splitTopLevel(match.groupValues[2], ',').map { it.trim() }
        if (args.size == 7 && args[2].contains("EnumMap") && args[2].contains("ArmorItem.Type")) {
            return parseEnumMapArmorMaterialConstant(match.groupValues[1], args)
        }
        if (args.size < 9) return null

        val protections = Regex("""new\s+int\s*\[\]\s*\{([^}]*)\}""")
            .find(args[3])
            ?.groupValues
            ?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return null
        if (protections.size < 4) return null

        return LegacyArmorMaterialConstant(
            fieldName = match.groupValues[1],
            registryName = stripJavaString(args[0]),
            textureName = stripJavaString(args[1]),
            protections = protections,
            enchantmentValue = args[4],
            equipSound = args[5],
            toughness = args[6],
            knockbackResistance = args[7],
            repairIngredient = args.drop(8).joinToString(", ")
        )
    }

    private fun parseEnumMapArmorMaterialConstant(
        fieldName: String,
        args: List<String>
    ): LegacyArmorMaterialConstant? {
        val protectionsByType = Regex("""ArmorItem\.Type\.(BOOTS|LEGGINGS|CHESTPLATE|HELMET|BODY)\s*,\s*([^) ;]+)\s*\)""")
            .findAll(args[2])
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        val protections = listOf("BOOTS", "LEGGINGS", "CHESTPLATE", "HELMET")
            .map { protectionsByType[it] ?: return null }
        val registryName = stripJavaString(args[0])
        return LegacyArmorMaterialConstant(
            fieldName = fieldName,
            registryName = registryName,
            textureName = registryName,
            protections = protections,
            enchantmentValue = args[3],
            equipSound = args[4],
            toughness = args[5],
            knockbackResistance = "0.0F",
            repairIngredient = args[6],
            bodyProtection = protectionsByType["BODY"] ?: protections[2]
        )
    }

    private fun findLegacyEnumConstantsEnd(content: String, start: Int): Int {
        var parenDepth = 0
        var braceDepth = 0
        var inString = false
        var escaped = false
        for (i in start until content.length) {
            val ch = content[i]
            if (inString) {
                escaped = ch == '\\' && !escaped
                if (ch == '"' && !escaped) inString = false
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                ';' -> if (parenDepth == 0 && braceDepth == 0) return i
            }
        }
        return -1
    }

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var escaped = false
        for (i in value.indices) {
            val ch = value[i]
            if (inString) {
                escaped = ch == '\\' && !escaped
                if (ch == '"' && !escaped) inString = false
                if (ch != '\\') escaped = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                delimiter -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                    result.add(value.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(value.substring(start))
        return result
    }

    private fun splitTopLevelArgs(value: String): List<String> =
        splitTopLevel(value, ',').map { it.trim() }.filter { it.isNotBlank() }

    private fun migrateLegacyTreeGrowerSubclass(source: String, compatPackage: String): String {
        if (!source.contains("getConfiguredFeature(") && !source.contains("getConfiguredMegaFeature(")) return source
        val classPattern = Regex("""\bclass\s+(\w+)\s+extends\s+(AbstractMegaTreeGrower|AbstractTreeGrower|TreeGrower)\b""")
        if (!classPattern.containsMatchIn(source)) return source

        var result = source
        result = removeJavaImport(result, "net.minecraft.world.level.block.grower.AbstractTreeGrower")
        result = removeJavaImport(result, "net.minecraft.world.level.block.grower.AbstractMegaTreeGrower")
        result = removeJavaImport(result, "net.minecraft.world.level.block.grower.TreeGrower")
        result = ensureJavaImport(result, "$compatPackage.ModPorterAbstractTreeGrower")
        result = classPattern.replace(result) { match ->
            "class ${match.groupValues[1]} extends ModPorterAbstractTreeGrower"
        }
        return result
    }

    private fun ensureLegacyTreeGrowerCompatBase(projectDir: Path, compatPackage: String, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        val compatFile = srcDir.resolve(compatPackage.replace('.', '/')).resolve("ModPorterAbstractTreeGrower.java")
        val source = legacyTreeGrowerCompatSource(compatPackage)
        if (compatFile.exists() && compatFile.readText() == source) return emptyList()
        if (!dryRun) {
            compatFile.parent.createDirectories()
            compatFile.writeText(source)
        }
        return listOf(Change(
            file = compatFile,
            line = 1,
            description = "Generate compatibility base for legacy AbstractTreeGrower subclasses",
            before = "AbstractTreeGrower/AbstractMegaTreeGrower inheritance",
            after = "ModPorterAbstractTreeGrower extends TreeGrower and preserves overridden feature selection",
            confidence = Confidence.HIGH,
            ruleId = "build-tree-grower-compat-base"
        ))
    }

    private fun legacyTreeGrowerCompatSource(packageName: String): String = """
package $packageName;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

public abstract class ModPorterAbstractTreeGrower extends TreeGrower {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    protected ModPorterAbstractTreeGrower() {
        super("modporter_legacy_tree_" + NEXT_ID.getAndIncrement(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    protected abstract ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean flowers);

    protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredMegaFeature(RandomSource random) {
        return null;
    }

    @Override
    public boolean growTree(ServerLevel level, ChunkGenerator chunkGenerator, BlockPos pos, BlockState state, RandomSource random) {
        ResourceKey<ConfiguredFeature<?, ?>> megaFeature = this.getConfiguredMegaFeature(random);
        if (megaFeature != null) {
            Holder<ConfiguredFeature<?, ?>> holder = level.registryAccess()
                    .registryOrThrow(Registries.CONFIGURED_FEATURE)
                    .getHolder(megaFeature)
                    .orElse(null);
            var event = net.neoforged.neoforge.event.EventHooks.fireBlockGrowFeature(level, random, pos, holder);
            holder = event.getFeature();
            if (event.isCanceled()) return false;
            if (holder != null) {
                for (int x = 0; x >= -1; x--) {
                    for (int z = 0; z >= -1; z--) {
                        if (isTwoByTwoSapling(state, level, pos, x, z)) {
                            ConfiguredFeature<?, ?> configuredFeature = holder.value();
                            BlockState air = Blocks.AIR.defaultBlockState();
                            level.setBlock(pos.offset(x, 0, z), air, 4);
                            level.setBlock(pos.offset(x + 1, 0, z), air, 4);
                            level.setBlock(pos.offset(x, 0, z + 1), air, 4);
                            level.setBlock(pos.offset(x + 1, 0, z + 1), air, 4);
                            if (configuredFeature.place(level, chunkGenerator, random, pos.offset(x, 0, z))) {
                                return true;
                            }
                            level.setBlock(pos.offset(x, 0, z), state, 4);
                            level.setBlock(pos.offset(x + 1, 0, z), state, 4);
                            level.setBlock(pos.offset(x, 0, z + 1), state, 4);
                            level.setBlock(pos.offset(x + 1, 0, z + 1), state, 4);
                            return false;
                        }
                    }
                }
            }
        }

        ResourceKey<ConfiguredFeature<?, ?>> feature = this.getConfiguredFeature(random, this.hasFlowers(level, pos));
        if (feature == null) return false;
        Holder<ConfiguredFeature<?, ?>> holder = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolder(feature)
                .orElse(null);
        var event = net.neoforged.neoforge.event.EventHooks.fireBlockGrowFeature(level, random, pos, holder);
        holder = event.getFeature();
        if (event.isCanceled() || holder == null) return false;

        ConfiguredFeature<?, ?> configuredFeature = holder.value();
        BlockState fluidState = level.getFluidState(pos).createLegacyBlock();
        level.setBlock(pos, fluidState, 4);
        if (configuredFeature.place(level, chunkGenerator, random, pos)) {
            if (level.getBlockState(pos) == fluidState) {
                level.sendBlockUpdated(pos, state, fluidState, 2);
            }
            return true;
        }
        level.setBlock(pos, state, 4);
        return false;
    }

    private static boolean isTwoByTwoSapling(BlockState state, BlockGetter level, BlockPos pos, int xOffset, int zOffset) {
        Block block = state.getBlock();
        return level.getBlockState(pos.offset(xOffset, 0, zOffset)).is(block)
                && level.getBlockState(pos.offset(xOffset + 1, 0, zOffset)).is(block)
                && level.getBlockState(pos.offset(xOffset, 0, zOffset + 1)).is(block)
                && level.getBlockState(pos.offset(xOffset + 1, 0, zOffset + 1)).is(block);
    }

    private boolean hasFlowers(LevelAccessor level, BlockPos pos) {
        for (BlockPos candidate : BlockPos.MutableBlockPos.betweenClosed(pos.below().north(2).west(2), pos.above().south(2).east(2))) {
            if (level.getBlockState(candidate).is(BlockTags.FLOWERS)) {
                return true;
            }
        }
        return false;
    }
}
""".trimIndent()

    private fun stripJavaString(value: String): String =
        value.trim().removePrefix("\"").removeSuffix("\"")

    private fun renderArmorMaterialRegistry(material: LegacyArmorMaterial): String {
        val imports = buildList {
            material.modImport?.let { add("import $it;") }
            material.extraImports.forEach { add("import $it;") }
            add("import net.minecraft.Util;")
            add("import net.minecraft.core.Holder;")
            add("import net.minecraft.core.registries.Registries;")
            add("import net.minecraft.resources.ResourceLocation;")
            add("import net.minecraft.sounds.SoundEvent;")
            add("import net.minecraft.sounds.SoundEvents;")
            add("import net.minecraft.world.item.ArmorItem;")
            add("import net.minecraft.world.item.ArmorMaterial;")
            add("import net.minecraft.world.item.Items;")
            add("import net.minecraft.world.item.crafting.Ingredient;")
            add("import net.neoforged.neoforge.registries.DeferredHolder;")
            add("import net.neoforged.neoforge.registries.DeferredRegister;")
            add("import java.util.EnumMap;")
            add("import java.util.HashMap;")
            add("import java.util.List;")
            add("import java.util.Map;")
            add("import java.util.function.Supplier;")
        }.distinct().joinToString("\n")

        val constants = material.constants.joinToString("\n\n") { renderArmorMaterialConstant(it) }
        return """
            package ${material.packageName};

            $imports

            public class ${material.enumName} {

                public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
                        DeferredRegister.create(Registries.ARMOR_MATERIAL, ${material.modIdExpr});

                private static final Map<Holder<ArmorMaterial>, String> TEXTURE_NAMES = new HashMap<>();

            $constants

                public static String getTextureName(Holder<ArmorMaterial> holder) {
                    return TEXTURE_NAMES.get(holder);
                }

                private static DeferredHolder<ArmorMaterial, ArmorMaterial> registerWithTexture(
                        String name,
                        String textureName,
                        EnumMap<ArmorItem.Type, Integer> defense,
                        int enchantmentValue,
                        Holder<SoundEvent> equipSound,
                        Supplier<Ingredient> repairIngredient,
                        float toughness,
                        float knockbackResistance
                ) {
                    List<ArmorMaterial.Layer> layers = "arcticarmor".equals(textureName)
                            ? List.of(
                                    new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(${material.modIdExpr}, textureName), "_dyed", true),
                                    new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(${material.modIdExpr}, textureName), "_overlay", false)
                            )
                            : List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(${material.modIdExpr}, textureName)));
                    DeferredHolder<ArmorMaterial, ArmorMaterial> holder = ARMOR_MATERIALS.register(name, () -> new ArmorMaterial(
                            defense,
                            enchantmentValue,
                            equipSound,
                            repairIngredient,
                            layers,
                            toughness,
                            knockbackResistance
                    ));
                    TEXTURE_NAMES.put(holder, textureName);
                    return holder;
                }

                private ${material.enumName}() {
                }
            }
        """.trimIndent()
    }

    private fun renderArmorMaterialConstant(constant: LegacyArmorMaterialConstant): String {
        val rawSound = constant.equipSound.removeSuffix(".get()")
        val sound = if (rawSound.startsWith("SoundEvents.") &&
            !rawSound.contains("ARMOR_EQUIP_")) {
            "Holder.direct($rawSound)"
        } else {
            rawSound
        }
        return """
                public static final DeferredHolder<ArmorMaterial, ArmorMaterial> ${constant.fieldName} =
                        registerWithTexture("${constant.registryName}", "${constant.textureName}",
                                Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                                    map.put(ArmorItem.Type.BOOTS, ${constant.protections[0]});
                                    map.put(ArmorItem.Type.LEGGINGS, ${constant.protections[1]});
                                    map.put(ArmorItem.Type.CHESTPLATE, ${constant.protections[2]});
                                    map.put(ArmorItem.Type.HELMET, ${constant.protections[3]});
                                    map.put(ArmorItem.Type.BODY, ${constant.bodyProtection ?: constant.protections[2]});
                                }),
                                ${constant.enchantmentValue},
                                $sound,
                                ${constant.repairIngredient},
                                ${constant.toughness},
                                ${constant.knockbackResistance}
                        );
        """.trimIndent()
    }

    private fun rewriteLegacyArmorMaterialConsumers(
        srcDir: Path,
        materials: List<LegacyArmorMaterial>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                if (materials.any { it.path == javaFile }) return@forEach
                val original = javaFile.readText()
                var modified = original
                var touched = false

                for (material in materials) {
                    if (modified.contains(material.enumName)) {
                        modified = modified.replace(
                            Regex("""\(\(${Regex.escape(material.enumName)}\)\s*getMaterial\(\)\)\.getTextureName\(\)"""),
                            "${material.enumName}.getTextureName(this.material)"
                        )
                        modified = modified.replace(
                            Regex("""\(\(${Regex.escape(material.enumName)}\)\s*this\.material\)\.getTextureName\(\)"""),
                            "${material.enumName}.getTextureName(this.material)"
                        )
                        modified = modified.replace(
                            Regex("""\b${Regex.escape(material.enumName)}\s+([A-Za-z_$][\w$]*)"""),
                            "Holder<ArmorMaterial> $1"
                        )
                        touched = true
                    }
                }

                if (modified.contains("extends ArmorItem")) {
                    val before = modified
                    modified = modified.replace(
                        Regex("""\bArmorMaterial\s+([A-Za-z_$][\w$]*)"""),
                        "Holder<ArmorMaterial> $1"
                    )
                    modified = rewriteLegacyArmorTextureOverride(modified)
                    if (modified != before) touched = true
                }

                if (touched && modified != original) {
                    if (modified.contains("Holder<ArmorMaterial>")) {
                        modified = ensureJavaImport(modified, "net.minecraft.core.Holder")
                        modified = ensureJavaImport(modified, "net.minecraft.world.item.ArmorMaterial")
                    }
                    if (Regex("""\bResourceLocation\b""").containsMatchIn(modified)) {
                        modified = ensureJavaImport(modified, "net.minecraft.resources.ResourceLocation")
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite ArmorItem consumers for 1.21 Holder<ArmorMaterial> and texture hook",
                        before = "legacy ArmorMaterial item constructor/texture hook",
                        after = "Holder<ArmorMaterial> constructor/ResourceLocation texture hook",
                        confidence = Confidence.HIGH,
                        ruleId = "build-legacy-armor-material-consumer"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(modified)
                    }
                }
            }
        return changes
    }

    private fun rewriteLegacyArmorTextureOverride(content: String): String {
        var modified = content.replace(
            Regex("""public\s+(@Nullable\s+)?String\s+getArmorTexture\s*\(\s*ItemStack\s+([A-Za-z_$][\w$]*)\s*,\s*Entity\s+([A-Za-z_$][\w$]*)\s*,\s*(?:net\.minecraft\.world\.entity\.)?EquipmentSlot\s+([A-Za-z_$][\w$]*)\s*,\s*String\s+[A-Za-z_$][\w$]*\s*\)""")
        ) { match ->
            val nullable = match.groupValues[1].ifBlank { "" }
            "public ${nullable}ResourceLocation getArmorTexture(ItemStack ${match.groupValues[2]}, Entity ${match.groupValues[3]}, EquipmentSlot ${match.groupValues[4]}, ArmorMaterial.Layer layer, boolean innerModel)"
        }
        modified = modified.replace(
            Regex("""return\s+([A-Za-z_$][\w$.]*\.MODID)\s*\+\s*":textures/models/armor/"\s*\+\s*([^;]+?)\s*\+\s*"\.png";"""),
            """return ResourceLocation.fromNamespaceAndPath($1, "textures/models/armor/" + $2 + ".png");"""
        )
        modified = modified.replace(
            Regex("""return\s+([A-Za-z_$][\w$.]*\.ARMOR_DIR\s*\+\s*"[^"]+"\s*);"""),
            """return ResourceLocation.parse($1);"""
        )
        return modified
    }

    private fun registerLegacyArmorMaterials(
        srcDir: Path,
        materials: List<LegacyArmorMaterial>,
        dryRun: Boolean
    ): List<Change> {
        val changes = mutableListOf<Change>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("@Mod(") || !original.contains("IEventBus")) return@forEach
                val busName = Regex("""\bIEventBus\s+([A-Za-z_$][\w$]*)""")
                    .find(original)
                    ?.groupValues
                    ?.get(1)
                    ?: return@forEach

                var modified = original
                val packageName = Regex("""(?m)^package\s+([\w.]+);""")
                    .find(original)?.groupValues?.get(1)

                for (material in materials) {
                    val registration = "${material.enumName}.ARMOR_MATERIALS.register($busName);"
                    if (modified.contains(registration)) continue
                    if (packageName != material.packageName) {
                        modified = ensureJavaImport(modified, "${material.packageName}.${material.enumName}")
                    }
                    val lines = modified.lines().toMutableList()
                    val insertIdx = modBusDeclarationInsertIndex(lines, busName)
                    if (insertIdx >= 0) {
                        val indent = modBusDeclarationInsertionIndent(lines, insertIdx)
                        lines.add(insertIdx, "$indent$registration")
                        modified = lines.joinToString("\n")
                    }
                }

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Register generated ArmorMaterial DeferredRegister on mod event bus",
                        before = "(missing ArmorMaterial registry registration)",
                        after = "ArmorMaterials.ARMOR_MATERIALS.register(modEventBus)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-register-armor-materials"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(modified)
                    }
                }
            }
        return changes
    }

    private fun insertModBusLineAfterBusDeclaration(content: String, busName: String, line: String): String? {
        val lines = content.lines().toMutableList()
        val insertIdx = modBusDeclarationInsertIndex(lines, busName)
        if (insertIdx < 0) return null
        val indent = modBusDeclarationInsertionIndent(lines, insertIdx)
        lines.add(insertIdx, "$indent$line")
        return lines.joinToString("\n")
    }

    private fun modBusDeclarationInsertIndex(lines: List<String>, busName: String): Int {
        val eventBusIdx = lines.indexOfFirst {
            Regex("""\bIEventBus\s+${Regex.escape(busName)}\b""").containsMatchIn(it)
        }
        if (eventBusIdx < 0) return -1
        var idx = eventBusIdx
        while (idx < lines.size && !lines[idx].contains("{") && !lines[idx].contains(";")) {
            idx++
        }
        return if (idx < lines.size) idx + 1 else -1
    }

    private fun modBusDeclarationInsertionIndent(lines: List<String>, insertIdx: Int): String {
        val anchor = lines.getOrNull(insertIdx - 1) ?: return "        "
        val base = anchor.takeWhile { it.isWhitespace() }
        return if (anchor.trimEnd().endsWith("{")) base + "    " else base
    }

    private fun rewriteLegacyTreeGrowers(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val growers = mutableListOf<LegacyTreeGrower>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val text = javaFile.readText()
                val classMatch = Regex("""\bclass\s+(\w+)\s+extends\s+(AbstractMegaTreeGrower|AbstractTreeGrower|TreeGrower)\b""")
                    .find(text)
                    ?: return@forEach
                if (!text.contains("getConfiguredFeature(") && !text.contains("getConfiguredMegaFeature(")) return@forEach
                val className = classMatch.groupValues[1]
                growers.add(LegacyTreeGrower(className, javaFile))
            }

        if (growers.isEmpty()) return changes

        val compatPackage = detectRequiredCompatShimPackage(projectDir, "legacy tree grower compatibility base")

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .filter { javaFile -> growers.none { it.path == javaFile } }
            .forEach { javaFile ->
                val original = javaFile.readText()
                var modified = original
                var touched = false

                for (grower in growers) {
                    if (modified.contains("AbstractTreeGrower") || modified.contains("AbstractMegaTreeGrower")) {
                        touched = true
                        modified = modified
                            .replace(
                                "import net.minecraft.world.level.block.grower.AbstractTreeGrower;",
                                "import net.minecraft.world.level.block.grower.TreeGrower;"
                            )
                            .replace(
                                "import net.minecraft.world.level.block.grower.AbstractMegaTreeGrower;",
                                "import net.minecraft.world.level.block.grower.TreeGrower;"
                            )
                            .replace(Regex("""\bAbstractMegaTreeGrower\b"""), "TreeGrower")
                            .replace(Regex("""\bAbstractTreeGrower\b"""), "TreeGrower")
                    }
                }

                if (touched) {
                    modified = ensureJavaImport(modified, "net.minecraft.world.level.block.grower.TreeGrower")

                    if (!dryRun) javaFile.writeText(modified)
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite legacy AbstractTreeGrower call-site types to TreeGrower",
                        before = "AbstractTreeGrower/AbstractMegaTreeGrower type references",
                        after = "TreeGrower type references",
                        confidence = Confidence.HIGH,
                        ruleId = "build-tree-grower-compat"
                    ))
                }
            }

        for (grower in growers) {
            val original = grower.path.readText()
            val migrated = migrateLegacyTreeGrowerSubclass(original, compatPackage)
            if (migrated != original) {
                if (!dryRun) grower.path.writeText(migrated)
                changes.add(Change(
                    file = grower.path,
                    line = 1,
                    description = "Retarget legacy AbstractTreeGrower subclass to generated TreeGrower compatibility base",
                    before = "class ${grower.className} extends AbstractTreeGrower/AbstractMegaTreeGrower",
                    after = "class ${grower.className} extends ModPorterAbstractTreeGrower",
                    confidence = Confidence.HIGH,
                    ruleId = "build-tree-grower-helper"
                ))
            }
        }

        changes.addAll(ensureLegacyTreeGrowerCompatBase(projectDir, compatPackage, dryRun))
        changes.addAll(ensureAccessTransformerEntries(
            projectDir,
            listOf("public-f net.minecraft.world.level.block.grower.TreeGrower"),
            dryRun,
            "build-tree-grower-unfinal-at",
            "Allow legacy custom tree growers to extend TreeGrower through a generated compatibility base"
        ))

        val obsoleteShim = srcDir.resolve("net/minecraft/world/level/block/grower/AbstractTreeGrower.java")
        if (obsoleteShim.exists()) {
            if (!dryRun) java.nio.file.Files.delete(obsoleteShim)
            changes.add(Change(
                file = obsoleteShim,
                line = 1,
                description = "Remove obsolete AbstractTreeGrower shim from Minecraft package",
                before = "net.minecraft.world.level.block.grower.AbstractTreeGrower shim",
                after = "(removed)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-abstract-tree-grower-shim"
            ))
        }

        return changes
    }

    private fun addLegacyMmlibShims(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val localRecipeBasePackage = detectLocalRecipeBasePackage(srcDir)
        if (localRecipeBasePackage != null) {
            var needsLocalRecipeBase = false
            java.nio.file.Files.walk(srcDir)
                .filter { it.extension == "java" }
                .forEach { javaFile ->
                    val original = javaFile.readText()
                    var migrated = original
                        .replace("import cn.mcmod_mmf.mmlib.fluid.FluidIngredient;", "import $localRecipeBasePackage.FluidIngredient;")
                        .replace("import cn.mcmod_mmf.mmlib.recipe.AbstractRecipe;", "import $localRecipeBasePackage.AbstractRecipe;")
                        .replace("import cn.mcmod_mmf.mmlib.recipe.AbstractRecipeSerializer;", "import $localRecipeBasePackage.AbstractRecipeSerializer;")
                        .replace("import cn.mcmod_mmf.mmlib.recipe.ChanceResult;", "import $localRecipeBasePackage.ChanceResult;")
                        .replace("cn.mcmod_mmf.mmlib.fluid.FluidIngredient", "$localRecipeBasePackage.FluidIngredient")
                        .replace("cn.mcmod_mmf.mmlib.recipe.AbstractRecipeSerializer", "$localRecipeBasePackage.AbstractRecipeSerializer")
                        .replace("cn.mcmod_mmf.mmlib.recipe.AbstractRecipe", "$localRecipeBasePackage.AbstractRecipe")
                        .replace("cn.mcmod_mmf.mmlib.recipe.ChanceResult", "$localRecipeBasePackage.ChanceResult")

                    if (migrated != original) {
                        needsLocalRecipeBase = true
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate removed MMLib recipe/fluid helpers to a project-local recipe base package",
                            before = "cn.mcmod_mmf.mmlib.recipe / cn.mcmod_mmf.mmlib.fluid",
                            after = localRecipeBasePackage,
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-mmlib-recipe-base"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(migrated)
                        }
                    }
                }

            if (needsLocalRecipeBase) {
                changes.addAll(addLocalMmlibRecipeBase(srcDir, localRecipeBasePackage, dryRun))
            }
        }

        val obsoleteShimDir = srcDir.resolve("cn/mcmod_mmf/mmlib")
        if (obsoleteShimDir.exists()) {
            changes.add(Change(
                file = obsoleteShimDir,
                line = 1,
                description = "Remove generated MMLib package shims and use the real MMLib dependency",
                before = "src/main/java/cn/mcmod_mmf/mmlib/* generated shims",
                after = "(removed)",
                confidence = Confidence.HIGH,
                ruleId = "build-remove-mmlib-package-shims"
            ))
            if (!dryRun) {
                java.nio.file.Files.walk(obsoleteShimDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
        return changes
    }

    private fun detectLocalRecipeBasePackage(srcDir: Path): String? {
        val ownerPackages = linkedSetOf<String>()
        java.nio.file.Files.walk(srcDir)
            .filter { it.extension == "java" }
            .forEach { javaFile ->
                val source = javaFile.readText()
                val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                    .find(source)
                    ?.groupValues
                    ?.get(1)
                    ?: return@forEach
                if (usesRemovedMmlibRecipeBaseType(source)) {
                    ownerPackages.add(packageName)
                }
            }
        val baseOwnerPackage = commonJavaPackagePrefix(ownerPackages) ?: return null
        return "$baseOwnerPackage.base"
    }

    private fun usesRemovedMmlibRecipeBaseType(source: String): Boolean {
        val code = maskJavaCommentsAndLiterals(source)
        val removedType = """(?:cn\.mcmod_mmf\.mmlib\.(?:recipe|fluid)\.)?(?:AbstractRecipe|AbstractRecipeSerializer|FluidIngredient|ChanceResult)"""
        val importsRemovedType = Regex("""(?m)^\s*import\s+cn\.mcmod_mmf\.mmlib\.(?:recipe|fluid)\.(?:AbstractRecipe|AbstractRecipeSerializer|FluidIngredient|ChanceResult)\s*;""")
            .containsMatchIn(code)
        return Regex("""\b(?:extends|implements)\s+$removedType\b""").containsMatchIn(code) ||
            Regex("""\b$removedType\s*<""").containsMatchIn(code) ||
            Regex("""\b$removedType\s+[A-Za-z_$][\w$]*\b""").containsMatchIn(code) ||
            (importsRemovedType && Regex("""\b(?:AbstractRecipe|AbstractRecipeSerializer|FluidIngredient|ChanceResult)\b""").containsMatchIn(code))
    }

    private fun commonJavaPackagePrefix(packages: Collection<String>): String? {
        if (packages.isEmpty()) return null
        val splitPackages = packages.map { it.split('.') }
        val first = splitPackages.first()
        val common = mutableListOf<String>()
        for ((index, segment) in first.withIndex()) {
            if (splitPackages.all { index < it.size && it[index] == segment }) {
                common.add(segment)
            } else {
                break
            }
        }
        return common.takeIf { it.size >= 2 }?.joinToString(".")
    }

    private fun addLocalMmlibRecipeBase(srcDir: Path, basePackage: String, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val packageDir = srcDir.resolve(basePackage.replace('.', '/'))
        val sources = linkedMapOf(
            "AbstractRecipe.java" to localMmlibAbstractRecipeSource(basePackage),
            "ChanceResult.java" to localMmlibChanceResultSource(basePackage),
            "FluidIngredient.java" to localMmlibFluidIngredientSource(basePackage),
            "AbstractRecipeSerializer.java" to localMmlibRecipeSerializerSource(basePackage)
        )

        for ((fileName, source) in sources) {
            val file = packageDir.resolve(fileName)
            if (file.exists()) continue
            changes.add(Change(
                file = file,
                line = 1,
                description = "Add project-local replacement for removed MMLib recipe helper $fileName",
                before = "(missing)",
                after = "$basePackage.$fileName",
                confidence = Confidence.HIGH,
                ruleId = "build-add-local-mmlib-recipe-base"
            ))
            if (!dryRun) {
                packageDir.createDirectories()
                file.writeText(source)
            }
        }
        return changes
    }

    private fun localMmlibAbstractRecipeSource(basePackage: String): String = """
package $basePackage;

import com.google.gson.annotations.Expose;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/**
 * Project-local replacement for MMLib recipe base helpers removed in MMLib 1.21.1.
 */
public abstract class AbstractRecipe implements Recipe<RecipeWrapper> {
    public String group = "";
    @Expose
    public float experience;
    @Expose
    public int recipeTime;

    public float getExperience() {
        return experience;
    }

    public int getRecipeTime() {
        return recipeTime;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public abstract ItemStack getResultItem(HolderLookup.Provider registries);

    @Override
    public boolean isSpecial() {
        return true;
    }
}
""".trimIndent()

    private fun localMmlibChanceResultSource(basePackage: String): String = """
package $basePackage;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

public record ChanceResult(ItemStack stack, float chance) {
    public static final ChanceResult EMPTY = new ChanceResult(ItemStack.EMPTY, 0.0F);

    public ItemStack rollOutput(RandomSource random, int fortuneLevel) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        float adjustedChance = Math.min(1.0F, chance + Math.max(0, fortuneLevel) * 0.1F);
        return random.nextFloat() < adjustedChance ? stack.copy() : ItemStack.EMPTY;
    }
}
""".trimIndent()

    private fun localMmlibFluidIngredientSource(basePackage: String): String = """
package $basePackage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

public abstract class FluidIngredient implements Predicate<FluidStack> {
    public static final FluidIngredient EMPTY = new EmptyFluidIngredient();

    protected int amountRequired;
    private List<FluidStack> matchingFluidStacks;

    public static FluidIngredient fromTag(TagKey<Fluid> tag, int amount) {
        return new TagFluidIngredient(tag, amount);
    }

    public static FluidIngredient fromFluid(Fluid fluid, int amount) {
        return new SingleFluidIngredient(fluid, amount);
    }

    public static FluidIngredient fromFluid(Supplier<? extends Fluid> fluid, int amount) {
        return fromFluid(fluid.get(), amount);
    }

    public static FluidIngredient fromFluidStack(FluidStack stack) {
        return stack == null || stack.isEmpty() ? EMPTY : fromFluid(stack.getFluid(), stack.getAmount());
    }

    public int getAmount() {
        return amountRequired;
    }

    public int getRequiredAmount() {
        return amountRequired;
    }

    public List<FluidStack> getMatchingFluidStacks() {
        if (matchingFluidStacks == null) {
            matchingFluidStacks = determineMatchingFluidStacks();
        }
        return matchingFluidStacks;
    }

    public FluidStack[] getStacks() {
        return getMatchingFluidStacks().toArray(FluidStack[]::new);
    }

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        writeInternal(json);
        json.addProperty("amount", amountRequired);
        return json;
    }

    public static boolean isFluidIngredient(JsonElement json) {
        if (json == null || !json.isJsonObject()) return false;
        JsonObject obj = json.getAsJsonObject();
        return obj.has("fluid") || obj.has("tag");
    }

    public static FluidIngredient deserialize(JsonElement json) {
        if (json == null || !json.isJsonObject()) return EMPTY;
        JsonObject obj = json.getAsJsonObject();
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : 1000;
        if (obj.has("tag")) {
            ResourceLocation tagId = ResourceLocation.parse(obj.get("tag").getAsString());
            return fromTag(TagKey.create(Registries.FLUID, tagId), amount);
        }
        if (obj.has("fluid")) {
            Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(obj.get("fluid").getAsString()));
            return fromFluid(fluid, amount);
        }
        return EMPTY;
    }

    @Override
    public boolean test(FluidStack stack) {
        return stack != null && stack.getAmount() >= amountRequired && testInternal(stack);
    }

    protected abstract boolean testInternal(FluidStack stack);
    protected abstract List<FluidStack> determineMatchingFluidStacks();
    protected abstract void writeInternal(JsonObject json);

    private static final class EmptyFluidIngredient extends FluidIngredient {
        private EmptyFluidIngredient() {
            this.amountRequired = 0;
        }

        @Override
        public boolean test(FluidStack stack) {
            return false;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return false;
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.emptyList();
        }

        @Override
        protected void writeInternal(JsonObject json) {
        }
    }

    private static final class SingleFluidIngredient extends FluidIngredient {
        private final Fluid fluid;

        private SingleFluidIngredient(Fluid fluid, int amount) {
            this.fluid = fluid;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().isSame(fluid);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.singletonList(new FluidStack(fluid, amountRequired));
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
        }
    }

    private static final class TagFluidIngredient extends FluidIngredient {
        private final TagKey<Fluid> tag;

        private TagFluidIngredient(TagKey<Fluid> tag, int amount) {
            this.tag = tag;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().builtInRegistryHolder().is(tag);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            List<FluidStack> stacks = new ArrayList<>();
            BuiltInRegistries.FLUID.getTag(tag).ifPresent(holders ->
                holders.forEach(holder -> stacks.add(new FluidStack(holder.value(), amountRequired)))
            );
            return stacks;
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("tag", tag.location().toString());
        }
    }
}
""".trimIndent()

    private fun localMmlibRecipeSerializerSource(basePackage: String): String = """
package $basePackage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.lang.reflect.Type;
import java.util.stream.Stream;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.fluids.FluidStack;

public class AbstractRecipeSerializer<T extends AbstractRecipe> implements RecipeSerializer<T> {
    private final Class<T> recipeClass;
    private final Gson gson;
    private final MapCodec<T> mapCodec;
    private final StreamCodec<RegistryFriendlyByteBuf, T> streamCodec;

    public AbstractRecipeSerializer(Class<T> recipeClass) {
        this.recipeClass = recipeClass;
        this.gson = createGson();
        this.mapCodec = createMapCodec();
        this.streamCodec = createStreamCodec();
    }

    private Gson createGson() {
        return new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .registerTypeHierarchyAdapter(Ingredient.class, new IngredientAdapter())
                .registerTypeHierarchyAdapter(ItemStack.class, new ItemStackAdapter())
                .registerTypeHierarchyAdapter(FluidStack.class, new FluidStackAdapter())
                .registerTypeHierarchyAdapter(FluidIngredient.class, new FluidIngredientAdapter())
                .registerTypeHierarchyAdapter(ChanceResult.class, new ChanceResultAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<Ingredient>>(){}.getType(), new IngredientListAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<ItemStack>>(){}.getType(), new ItemStackListAdapter())
                .registerTypeAdapter(new TypeToken<NonNullList<ChanceResult>>(){}.getType(), new ChanceResultListAdapter())
                .create();
    }

    private MapCodec<T> createMapCodec() {
        return new MapCodec<>() {
            @Override
            public <O> RecordBuilder<O> encode(T input, DynamicOps<O> ops, RecordBuilder<O> prefix) {
                JsonObject json = toJson(input);
                for (var entry : json.entrySet()) {
                    prefix = prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
                }
                return prefix;
            }

            @Override
            public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
                try {
                    JsonObject json = new JsonObject();
                    input.entries().forEach(pair -> {
                        String key = ops.getStringValue(pair.getFirst()).result().orElse("");
                        if (!key.isEmpty()) {
                            json.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
                        }
                    });
                    return DataResult.success(fromJson(json));
                } catch (Exception e) {
                    return DataResult.error(() -> "Failed to decode recipe: " + e.getMessage());
                }
            }

            @Override
            public <O> Stream<O> keys(DynamicOps<O> ops) {
                return Stream.empty();
            }
        };
    }

    private StreamCodec<RegistryFriendlyByteBuf, T> createStreamCodec() {
        return StreamCodec.of(
                (buf, recipe) -> buf.writeUtf(gson.toJson(recipe)),
                buf -> fromJson(com.google.gson.JsonParser.parseString(buf.readUtf(32767)).getAsJsonObject())
        );
    }

    @Override
    public MapCodec<T> codec() {
        return mapCodec;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return streamCodec;
    }

    public T fromJson(JsonObject json) {
        return gson.fromJson(json, recipeClass);
    }

    public JsonObject toJson(T recipe) {
        return gson.toJsonTree(recipe).getAsJsonObject();
    }

    private static class IngredientAdapter implements JsonSerializer<Ingredient>, JsonDeserializer<Ingredient> {
        @Override
        public JsonElement serialize(Ingredient src, Type typeOfSrc, JsonSerializationContext context) {
            return Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public Ingredient deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Ingredient.CODEC.decode(JsonOps.INSTANCE, json).result()
                    .map(com.mojang.datafixers.util.Pair::getFirst)
                    .orElse(Ingredient.EMPTY);
        }
    }

    private static class ItemStackAdapter implements JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {
        @Override
        public JsonElement serialize(ItemStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src.isEmpty()) return JsonNull.INSTANCE;
            return ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return decodeItemStack(json, "ItemStack");
        }
    }

    private static ItemStack decodeItemStack(JsonElement json, String contextName) throws JsonParseException {
        if (json == null || json.isJsonNull()) return ItemStack.EMPTY;
        JsonElement normalized = normalizeLegacyItemStackJson(json);
        var decoded = ItemStack.CODEC.decode(JsonOps.INSTANCE, normalized);
        return decoded.result()
                .map(com.mojang.datafixers.util.Pair::getFirst)
                .orElseThrow(() -> new JsonParseException(
                        "Failed to decode " + contextName + ": " + decoded.error().map(Object::toString).orElse("unknown codec error")));
    }

    private static JsonElement normalizeLegacyItemStackJson(JsonElement json) {
        if (json == null || json.isJsonNull()) return JsonNull.INSTANCE;
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            JsonObject normalized = new JsonObject();
            normalized.addProperty("id", json.getAsString());
            return normalized;
        }
        if (!json.isJsonObject()) return json;
        JsonObject obj = json.getAsJsonObject();
        if (!obj.has("item") || obj.has("id")) return json;

        JsonObject normalized = new JsonObject();
        normalized.add("id", obj.get("item"));
        copyIfPresent(obj, normalized, "count");
        copyIfPresent(obj, normalized, "components");
        if (obj.has("nbt")) {
            JsonObject components = normalized.has("components") && normalized.get("components").isJsonObject()
                    ? normalized.getAsJsonObject("components")
                    : new JsonObject();
            components.add("minecraft:custom_data", obj.get("nbt"));
            normalized.add("components", components);
        }
        return normalized;
    }

    private static void copyIfPresent(JsonObject from, JsonObject to, String key) {
        if (from.has(key)) {
            to.add(key, from.get(key));
        }
    }

    private static class FluidStackAdapter implements JsonSerializer<FluidStack>, JsonDeserializer<FluidStack> {
        @Override
        public JsonElement serialize(FluidStack src, Type typeOfSrc, JsonSerializationContext context) {
            if (src.isEmpty()) return JsonNull.INSTANCE;
            return FluidStack.CODEC.encodeStart(JsonOps.INSTANCE, src).result().orElse(JsonNull.INSTANCE);
        }

        @Override
        public FluidStack deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return FluidStack.EMPTY;
            return FluidStack.CODEC.decode(JsonOps.INSTANCE, json).result()
                    .map(com.mojang.datafixers.util.Pair::getFirst)
                    .orElse(FluidStack.EMPTY);
        }
    }

    private static class FluidIngredientAdapter implements JsonSerializer<FluidIngredient>, JsonDeserializer<FluidIngredient> {
        @Override
        public JsonElement serialize(FluidIngredient src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == FluidIngredient.EMPTY) return JsonNull.INSTANCE;
            return src.serialize();
        }

        @Override
        public FluidIngredient deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return FluidIngredient.EMPTY;
            return FluidIngredient.deserialize(json);
        }
    }

    private static class ChanceResultAdapter implements JsonSerializer<ChanceResult>, JsonDeserializer<ChanceResult> {
        @Override
        public JsonElement serialize(ChanceResult src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject json = new JsonObject();
            json.add("item", ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, src.stack()).result().orElse(JsonNull.INSTANCE));
            json.addProperty("chance", src.chance());
            return json;
        }

        @Override
        public ChanceResult deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || !json.isJsonObject()) return ChanceResult.EMPTY;
            JsonObject obj = json.getAsJsonObject();
            ItemStack stack = decodeItemStack(chanceResultStackJson(obj), "ChanceResult.item");
            float chance = obj.has("chance") ? obj.get("chance").getAsFloat() : 1.0F;
            return new ChanceResult(stack, chance);
        }
    }

    private static JsonElement chanceResultStackJson(JsonObject obj) {
        if (obj.has("stack")) return obj.get("stack");
        if (!obj.has("item")) return JsonNull.INSTANCE;
        JsonElement item = obj.get("item");
        if (item != null && item.isJsonObject()) return item;

        JsonObject stack = new JsonObject();
        stack.add("item", item);
        copyIfPresent(obj, stack, "count");
        copyIfPresent(obj, stack, "components");
        copyIfPresent(obj, stack, "nbt");
        return stack;
    }

    private static class IngredientListAdapter implements JsonSerializer<NonNullList<Ingredient>>, JsonDeserializer<NonNullList<Ingredient>> {
        @Override
        public JsonElement serialize(NonNullList<Ingredient> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (Ingredient ingredient : src) {
                array.add(context.serialize(ingredient));
            }
            return array;
        }

        @Override
        public NonNullList<Ingredient> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<Ingredient> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    Ingredient ingredient = context.deserialize(element, Ingredient.class);
                    list.add(ingredient != null ? ingredient : Ingredient.EMPTY);
                }
            }
            return list;
        }
    }

    private static class ItemStackListAdapter implements JsonSerializer<NonNullList<ItemStack>>, JsonDeserializer<NonNullList<ItemStack>> {
        @Override
        public JsonElement serialize(NonNullList<ItemStack> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (ItemStack stack : src) {
                array.add(context.serialize(stack));
            }
            return array;
        }

        @Override
        public NonNullList<ItemStack> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<ItemStack> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    ItemStack stack = context.deserialize(element, ItemStack.class);
                    list.add(stack != null ? stack : ItemStack.EMPTY);
                }
            }
            return list;
        }
    }

    private static class ChanceResultListAdapter implements JsonSerializer<NonNullList<ChanceResult>>, JsonDeserializer<NonNullList<ChanceResult>> {
        @Override
        public JsonElement serialize(NonNullList<ChanceResult> src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            for (ChanceResult result : src) {
                array.add(context.serialize(result));
            }
            return array;
        }

        @Override
        public NonNullList<ChanceResult> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            NonNullList<ChanceResult> list = NonNullList.create();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    ChanceResult result = context.deserialize(element, ChanceResult.class);
                    list.add(result != null ? result : ChanceResult.EMPTY);
                }
            }
            return list;
        }
    }
}
""".trimIndent()

    private fun rewriteLegacyCapabilityHooks(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                if (!original.contains("LazyOptional") &&
                    !original.contains("getCapability(") &&
                    !original.contains("invalidateCaps(")) {
                    return@forEach
                }

                var modified = original
                modified = Regex(
                    """(?ms)^([ \t]*)(?:@Override\s+)?public\s+<T>\s+LazyOptional<T>\s+getCapability\s*\(([^)]*)\)\s*\{\s*return\s+super\.getCapability\s*\([^;]*\);\s*\}""",
                    RegexOption.MULTILINE
                ).replace(modified, "")
                if (!Regex("""\bCapability\s*<""").containsMatchIn(modified)) {
                    modified = Regex("""(?m)^[ \t]*import\s+[\w.]+\.compat\.Capability;\s*\r?\n""")
                        .replace(modified, "")
                    modified = Regex("""(?m)^[ \t]*import\s+net\.neoforged\.neoforge\.capabilities\.Capability;\s*\r?\n""")
                        .replace(modified, "")
                }
                modified = Regex(
                    """(?m)^([ \t]*)@Override\s*\r?\n([ \t]*public\s+void\s+invalidateCaps\s*\()"""
                ).replace(modified) { match ->
                    match.groupValues[1] + match.groupValues[2]
                }
                modified = Regex(
                    """(?m)^[ \t]*super\.invalidateCaps\(\);\s*\r?\n"""
                ).replace(modified, "")

                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Rewrite legacy capability fallback hooks for removed Forge capability overrides",
                        before = "super.getCapability(...) / super.invalidateCaps()",
                        after = "delete super-only capability hook / local invalidation only",
                        confidence = Confidence.HIGH,
                        ruleId = "build-rewrite-legacy-capability-hooks"
                    ))
                    if (!dryRun) javaFile.writeText(modified)
                }
            }

        return changes
    }

    private fun addLegacyCapabilityShims(projectDir: Path, dryRun: Boolean, errors: MutableList<String>): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        var resolvedCompatPackage: String? = null
        var failedCompatPackage = false
        fun compatPackage(): String? {
            resolvedCompatPackage?.let { return it }
            if (failedCompatPackage) return null
            return try {
                detectRequiredCompatShimPackage(projectDir, "legacy source compatibility shims")
                    .also { resolvedCompatPackage = it }
            } catch (e: Exception) {
                failedCompatPackage = true
                errors.add(e.message ?: "Cannot derive generated compat shim package for legacy source compatibility shims")
                null
            }
        }

        var needsLazyOptional = false
        var needsCapability = false
        var needsConditionalRecipe = false
        var needsRenderUtils = false
        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .filter { !srcDir.relativize(it).toString().replace('\\', '/').startsWith("net/neoforged/neoforge/") }
            .forEach { javaFile ->
                val text = javaFile.readText()
                val staleLazyOptionalRemoved = removeStaleLazyOptionalImports(text)
                if (staleLazyOptionalRemoved != text) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Remove stale LazyOptional compatibility import after structural capability migration",
                        before = "unused LazyOptional import",
                        after = "(removed)",
                        confidence = Confidence.HIGH,
                        ruleId = "build-remove-stale-lazyoptional-import"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(staleLazyOptionalRemoved)
                    }
                }
                val textForCapabilities = staleLazyOptionalRemoved
                var capabilitySource = textForCapabilities
                val hasLazyOptionalRelocationSource = listOf(
                    "net.minecraftforge.common.util.LazyOptional",
                    "net.neoforged.neoforge.common.util.LazyOptional",
                    "net.minecraftforge.common.util.*",
                    "net.neoforged.neoforge.common.util.*",
                    "com.modporter.compat.LazyOptional"
                ).any { textForCapabilities.contains(it) }
                if (hasLazyOptionalRelocationSource) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedLazyOptionalSource = relocateLazyOptionalImports(textForCapabilities, packageName)
                    if (relocatedLazyOptionalSource != textForCapabilities) {
                        needsLazyOptional = true
                        capabilitySource = relocatedLazyOptionalSource
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate LazyOptional compatibility import out of NeoForge package",
                            before = "net.neoforged.neoforge.common.util.LazyOptional",
                            after = "$packageName.LazyOptional",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-lazyoptional-import"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedLazyOptionalSource)
                        }
                    }
                } else if (sourceWithoutJavaImports(textForCapabilities).contains("LazyOptional")) {
                    if (compatPackage() != null) {
                        needsLazyOptional = true
                    }
                }

                var conditionalSource = capabilitySource
                val hasCapabilityRelocationSource = listOf(
                    "net.neoforged.neoforge.capabilities.Capability",
                    "com.modporter.compat.Capability"
                ).any { capabilitySource.contains(it) }
                if (hasCapabilityRelocationSource) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedCapability = relocateCapabilityImports(capabilitySource, packageName)
                    if (relocatedCapability != capabilitySource) {
                        needsCapability = true
                        conditionalSource = relocatedCapability
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate Capability compatibility import out of NeoForge package",
                            before = "net.neoforged.neoforge.capabilities.Capability",
                            after = "$packageName.Capability",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-capability-import"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedCapability)
                        }
                    }
                } else if (capabilitySource.contains("net.neoforged.neoforge.capabilities.Capability") ||
                    Regex("""\bCapability\s*<""").containsMatchIn(capabilitySource)) {
                    if (compatPackage() != null) {
                        needsCapability = true
                    }
                }

                var renderSource = conditionalSource
                val hasConditionalRecipeRelocationSource = listOf(
                    "net.minecraftforge.common.crafting.ConditionalRecipe",
                    "net.neoforged.neoforge.common.crafting.ConditionalRecipe"
                ).any { conditionalSource.contains(it) }
                if (hasConditionalRecipeRelocationSource) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedConditionalRecipe = relocateConditionalRecipeImports(conditionalSource, packageName)
                    if (relocatedConditionalRecipe != conditionalSource) {
                        needsConditionalRecipe = true
                        renderSource = relocatedConditionalRecipe
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate removed ConditionalRecipe builder API to generated NeoForge RecipeOutput adapter",
                            before = "net.neoforged.neoforge.common.crafting.ConditionalRecipe",
                            after = "$packageName.ConditionalRecipe",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-conditionalrecipe-import"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedConditionalRecipe)
                        }
                    }
                } else if (conditionalSource.contains("ConditionalRecipe.builder()") ||
                    Regex("""\bConditionalRecipe\.Builder\b""").containsMatchIn(conditionalSource)) {
                    if (compatPackage() != null) {
                        needsConditionalRecipe = true
                    }
                }
                if (renderSource.contains("cn.mcmod_mmf.mmlib.client.RenderUtils") ||
                    renderSource.contains("RenderUtils.renderFluidStack(")) {
                    val packageName = compatPackage() ?: return@forEach
                    val relocatedRenderUtils = relocateRenderUtilsImportsAndCalls(renderSource, packageName)
                    if (relocatedRenderUtils != renderSource) {
                        needsRenderUtils = true
                        changes.add(Change(
                            file = javaFile,
                            line = 1,
                            description = "Relocate removed MMLib RenderUtils fluid helper to generated GUI fluid renderer",
                            before = "cn.mcmod_mmf.mmlib.client.RenderUtils.renderFluidStack(...)",
                            after = "$packageName.RenderUtils.renderFluidStack(guiGraphics, ...)",
                            confidence = Confidence.HIGH,
                            ruleId = "build-relocate-renderutils-fluid"
                        ))
                        if (!dryRun) {
                            javaFile.writeText(relocatedRenderUtils)
                        }
                    }
                }
            }

        val compatPackage = resolvedCompatPackage
        val compatPath = compatPackage?.replace('.', '/')

        if (needsLazyOptional && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/LazyOptional.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add LazyOptional source compatibility shim",
                    before = "(missing)",
                    after = "LazyOptional shim",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-lazyoptional-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyLazyOptionalShim(compatPackage))
                }
            }
        }

        if (needsCapability && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/Capability.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add Capability type source compatibility shim",
                    before = "(missing)",
                    after = "Capability shim",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-capability-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyCapabilityShim(compatPackage))
                }
            }
        }

        if (needsConditionalRecipe && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/ConditionalRecipe.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add ConditionalRecipe builder adapter backed by RecipeOutput.withConditions",
                    before = "(missing)",
                    after = "ConditionalRecipe adapter",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-conditionalrecipe-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyConditionalRecipeShim(compatPackage))
                }
            }
        }

        if (needsRenderUtils && compatPackage != null && compatPath != null) {
            val shim = srcDir.resolve("$compatPath/RenderUtils.java")
            if (!shim.exists()) {
                changes.add(Change(
                    file = shim,
                    line = 1,
                    description = "Add GUI fluid render helper compatible with removed MMLib RenderUtils.renderFluidStack",
                    before = "(missing)",
                    after = "RenderUtils fluid renderer",
                    confidence = Confidence.HIGH,
                    ruleId = "build-add-renderutils-fluid-shim"
                ))
                if (!dryRun) {
                    shim.parent.createDirectories()
                    shim.writeText(legacyRenderUtilsShim(compatPackage))
                }
            }
        }

        if (!failedCompatPackage) {
            changes.addAll(removeOldGlobalCapabilityShims(srcDir, dryRun))
        }

        return changes
    }

    private fun detectRequiredCompatShimPackage(projectDir: Path, reason: String): String {
        val modId = detectUniqueProjectModId(projectDir)
            ?: error("Cannot derive generated compat shim package for $reason: missing or ambiguous @Mod annotation and mod metadata mod id")
        val packageSegment = sanitizeRequiredPackageSegment(modId, reason)
        return "com.modporter.generated.$packageSegment.compat"
    }

    private fun sanitizeRequiredPackageSegment(value: String, reason: String): String {
        val sanitized = value.lowercase()
            .replace(Regex("""[^a-z0-9_]"""), "_")
            .trim('_')
        require(sanitized.isNotBlank()) {
            "Cannot derive generated compat shim package for $reason: mod id '$value' has no valid Java package segment"
        }
        return if (sanitized.first().isDigit()) "m$sanitized" else sanitized
    }

    private fun removeOldGlobalCapabilityShims(srcDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val oldShims = listOf(
            srcDir.resolve("com/modporter/compat/LazyOptional.java") to "build-remove-global-lazyoptional-shim",
            srcDir.resolve("com/modporter/compat/Capability.java") to "build-remove-global-capability-shim"
        )
        for ((shim, ruleId) in oldShims) {
            if (!shim.exists()) continue
            val text = shim.readText()
            val generatedByModporter = text.contains("generated by modporter") &&
                text.contains("package com.modporter.compat;")
            if (!generatedByModporter) continue
            changes.add(Change(
                file = shim,
                line = 1,
                description = "Remove old global capability shim package to avoid NeoForge module split-package conflicts",
                before = "com.modporter.compat",
                after = "per-mod generated capability shim package",
                confidence = Confidence.HIGH,
                ruleId = ruleId
            ))
            if (!dryRun) {
                shim.deleteIfExists()
            }
        }
        return changes
    }

    private fun relocateLazyOptionalImports(text: String, compatPackage: String): String =
        text
            .replace("import net.minecraftforge.common.util.LazyOptional;", "import $compatPackage.LazyOptional;")
            .replace("import net.neoforged.neoforge.common.util.LazyOptional;", "import $compatPackage.LazyOptional;")
            .replace("import net.minecraftforge.common.util.*;", "import $compatPackage.LazyOptional;")
            .replace("import net.neoforged.neoforge.common.util.*;", "import $compatPackage.LazyOptional;")
            .replace("import com.modporter.compat.LazyOptional;", "import $compatPackage.LazyOptional;")
            .replace("net.minecraftforge.common.util.LazyOptional", "$compatPackage.LazyOptional")
            .replace("net.neoforged.neoforge.common.util.LazyOptional", "$compatPackage.LazyOptional")
            .replace("com.modporter.compat.LazyOptional", "$compatPackage.LazyOptional")

    private fun removeStaleLazyOptionalImports(text: String): String {
        if (!text.contains("LazyOptional")) return text
        val withoutImports = sourceWithoutJavaImports(text)
        if (withoutImports.contains("LazyOptional")) return text
        return Regex("""(?m)^[ \t]*import\s+(?:[\w.]+\.)?LazyOptional\s*;\s*\r?\n""")
            .replace(text, "")
    }

    private fun sourceWithoutJavaImports(text: String): String =
        Regex("""(?m)^[ \t]*import\s+[^\r\n]+;\s*\r?\n""").replace(text, "")

    private fun relocateCapabilityImports(text: String, compatPackage: String): String =
        text
            .replace("import net.neoforged.neoforge.capabilities.Capability;", "import $compatPackage.Capability;")
            .replace("import com.modporter.compat.Capability;", "import $compatPackage.Capability;")
            .replace("net.neoforged.neoforge.capabilities.Capability", "$compatPackage.Capability")
            .replace("com.modporter.compat.Capability", "$compatPackage.Capability")

    private fun relocateConditionalRecipeImports(text: String, compatPackage: String): String =
        text
            .replace("import net.minecraftforge.common.crafting.ConditionalRecipe;", "import $compatPackage.ConditionalRecipe;")
            .replace("import net.neoforged.neoforge.common.crafting.ConditionalRecipe;", "import $compatPackage.ConditionalRecipe;")
            .replace("net.minecraftforge.common.crafting.ConditionalRecipe", "$compatPackage.ConditionalRecipe")
            .replace("net.neoforged.neoforge.common.crafting.ConditionalRecipe", "$compatPackage.ConditionalRecipe")

    private fun relocateRenderUtilsImportsAndCalls(text: String, compatPackage: String): String {
        var result = text
            .replace("import cn.mcmod_mmf.mmlib.client.RenderUtils;", "import $compatPackage.RenderUtils;")
            .replace("cn.mcmod_mmf.mmlib.client.RenderUtils", "$compatPackage.RenderUtils")
        result = Regex("""RenderUtils\.renderFluidStack\(\s*(?!ms\s*,)""")
            .replace(result, "RenderUtils.renderFluidStack(ms, ")
        return result
    }

    private fun cleanupDuplicateOverrides(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        val duplicateOverride = Regex(
            """(?m)^([ \t]*)@Override[ \t]*\r?\n((?:[ \t]*@[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\([^)\r\n]*\))?[ \t]*\r?\n)+[ \t]*)@Override"""
        )

        java.nio.file.Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val original = javaFile.readText()
                val modified = duplicateOverride.replace(original) { match ->
                    match.groupValues[2] + "@Override"
                }
                if (modified != original) {
                    changes.add(Change(
                        file = javaFile,
                        line = 1,
                        description = "Remove duplicate @Override generated by chained method-signature rewrites",
                        before = "@Override + annotations + @Override",
                        after = "annotations + @Override",
                        confidence = Confidence.HIGH,
                        ruleId = "build-cleanup-duplicate-override"
                    ))
                    if (!dryRun) {
                        javaFile.writeText(modified)
                    }
                }
            }

        return changes
    }

    companion object {
        val EMPTY_GAMETEST_STRUCTURE_SNBT = """
{
  DataVersion: 3953,
  size: [1, 1, 1],
  entities: [],
  blocks: [],
  palette: []
}
""".trimIndent()

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
        clientWorld {
            client()
            programArguments.addAll '--quickPlayPath', 'quickplay/modporter_smoke_world.json', '--quickPlaySingleplayer', 'modporter_smoke_world'
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
        gameTestServer {
            type = "gameTestServer"
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        data {
            data()
            programArguments.addAll '--mod', project.mod_id, '--all', '--output', file('src/generated/resources/').getAbsolutePath(), '--existing', file('src/main/resources/').getAbsolutePath()
        }
    }

    mods {
        "MOD_ID_TOKEN" {
            sourceSet(sourceSets.main)
        }
    }
}

sourceSets.main.resources {
    srcDir 'src/generated/resources'
}

tasks.matching { it.name == 'prepareGameTestServerRun' }.configureEach {
    doLast {
        def gameTestStructures = file('src/main/resources/gameteststructures')
        if (gameTestStructures.exists()) {
            copy {
                from gameTestStructures
                into file('run/gameteststructures')
            }
        }
    }
}""".trimIndent().replace("MOD_ID_TOKEN", "\${project.mod_id}")

        fun legacyLazyOptionalShim(compatPackage: String): String = """
package $compatPackage;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Source-compatibility adapter generated by modporter for legacy call chains.
 * NeoForge 1.21 removed LazyOptional; new capabilities should use RegisterCapabilitiesEvent directly.
 */
public final class LazyOptional<T> {
    private final Supplier<? extends T> supplier;
    private boolean valid = true;
    private boolean resolved = false;
    private T value;

    private LazyOptional(Supplier<? extends T> supplier) {
        this.supplier = supplier;
    }

    public static <T> LazyOptional<T> of(Supplier<? extends T> supplier) {
        return new LazyOptional<>(supplier);
    }

    public static <T> LazyOptional<T> ofNullable(T value) {
        return new LazyOptional<>(() -> value);
    }

    public static <T> LazyOptional<T> empty() {
        return new LazyOptional<>(() -> null);
    }

    @SuppressWarnings("unchecked")
    public <R> LazyOptional<R> cast() {
        return new LazyOptional<>(() -> (R) this.orElse(null));
    }

    public void ifPresent(Consumer<? super T> consumer) {
        T current = orElse(null);
        if (current != null) {
            consumer.accept(current);
        }
    }

    public <R> LazyOptional<R> map(Function<? super T, ? extends R> mapper) {
        return new LazyOptional<>(() -> {
            T current = orElse(null);
            return current != null ? mapper.apply(current) : null;
        });
    }

    public LazyOptional<T> filter(Predicate<? super T> predicate) {
        return new LazyOptional<>(() -> {
            T current = orElse(null);
            return current != null && predicate.test(current) ? current : null;
        });
    }

    public T orElse(T other) {
        if (!valid) {
            return other;
        }
        if (!resolved) {
            value = supplier.get();
            resolved = true;
        }
        return value != null ? value : other;
    }

    public T orElseGet(Supplier<? extends T> other) {
        T current = orElse(null);
        return current != null ? current : other.get();
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        T current = orElse(null);
        if (current != null) {
            return current;
        }
        throw exceptionSupplier.get();
    }

    public Optional<T> resolve() {
        return Optional.ofNullable(orElse(null));
    }

    public boolean isPresent() {
        return orElse(null) != null;
    }

    public void invalidate() {
        valid = false;
        value = null;
        resolved = true;
    }
}
""".trimIndent()

        fun legacyCapabilityShim(compatPackage: String): String = """
package $compatPackage;

/**
 * Generated compatibility type for legacy Forge capability declarations.
 * NeoForge 1.21 models capabilities with typed BlockCapability/ItemCapability objects.
 */
public final class Capability<T> {
}
""".trimIndent()

        fun legacyConditionalRecipeShim(compatPackage: String): String = """
package $compatPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.common.conditions.ICondition;

/**
 * Source-compatibility adapter generated by modporter for Forge conditional recipe builders.
 * NeoForge 1.21 carries recipe conditions through RecipeOutput.withConditions(...).
 */
public final class ConditionalRecipe {
    private ConditionalRecipe() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ICondition> conditions = new ArrayList<>();
        private final List<Consumer<RecipeOutput>> recipes = new ArrayList<>();
        private ResourceLocation advancementId;

        public Builder addCondition(ICondition condition) {
            this.conditions.add(condition);
            return this;
        }

        public Builder addRecipe(Consumer<RecipeOutput> recipe) {
            this.recipes.add(recipe);
            return this;
        }

        public Builder generateAdvancement(ResourceLocation id) {
            this.advancementId = id;
            return this;
        }

        public void build(RecipeOutput output, String namespace, String path) {
            build(output, ResourceLocation.fromNamespaceAndPath(namespace, path));
        }

        public void build(RecipeOutput output, ResourceLocation id) {
            RecipeOutput conditionalOutput = output.withConditions(conditions.toArray(ICondition[]::new));
            RecipeOutput namedOutput = new RecipeOutput() {
                @Override
                public void accept(ResourceLocation generatedId, Recipe<?> recipe, AdvancementHolder advancement) {
                    forward(conditionalOutput, recipe, advancement);
                }

                @Override
                public void accept(ResourceLocation generatedId, Recipe<?> recipe, AdvancementHolder advancement, ICondition... extraConditions) {
                    RecipeOutput target = extraConditions.length == 0 ? conditionalOutput : conditionalOutput.withConditions(extraConditions);
                    forward(target, recipe, advancement);
                }

                private void forward(RecipeOutput target, Recipe<?> recipe, AdvancementHolder advancement) {
                    AdvancementHolder forwardedAdvancement = advancement;
                    if (advancementId != null && advancement != null) {
                        forwardedAdvancement = new AdvancementHolder(advancementId, advancement.value());
                    }
                    target.accept(id, recipe, forwardedAdvancement);
                }

                @Override
                public Advancement.Builder advancement() {
                    return conditionalOutput.advancement();
                }
            };
            for (Consumer<RecipeOutput> recipe : recipes) {
                recipe.accept(namedOutput);
            }
        }
    }
}
""".trimIndent()

        fun legacyRenderUtilsShim(compatPackage: String): String = """
package $compatPackage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Source-compatibility renderer generated by modporter for the removed MMLib RenderUtils.renderFluidStack helper.
 * It draws the fluid still texture in 16x16 tiles and applies the fluid tint color.
 */
public final class RenderUtils {
    private RenderUtils() {
    }

    public static void renderFluidStack(GuiGraphics guiGraphics, int x, int y, int width, int height, float z, FluidStack fluidStack) {
        if (guiGraphics == null || fluidStack == null || fluidStack.isEmpty() || width <= 0 || height <= 0) {
            return;
        }

        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluidStack.getFluid());
        ResourceLocation texture = extensions.getStillTexture(fluidStack);
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(texture);
        int tint = extensions.getTintColor(fluidStack);
        float alpha = ((tint >> 24) & 0xFF) / 255.0F;
        if (alpha <= 0.0F) {
            alpha = 1.0F;
        }
        float red = ((tint >> 16) & 0xFF) / 255.0F;
        float green = ((tint >> 8) & 0xFF) / 255.0F;
        float blue = (tint & 0xFF) / 255.0F;

        guiGraphics.setColor(red, green, blue, alpha);
        int zOffset = (int) z;
        for (int drawX = 0; drawX < width; drawX += 16) {
            for (int drawY = 0; drawY < height; drawY += 16) {
                int drawWidth = Math.min(16, width - drawX);
                int drawHeight = Math.min(16, height - drawY);
                guiGraphics.blit(x + drawX, y + drawY, zOffset, drawWidth, drawHeight, sprite);
            }
        }
        guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
""".trimIndent()

        val LEGACY_MMLIB_FLUID_INGREDIENT_SHIM = """
package cn.mcmod_mmf.mmlib.fluid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Generated compatibility implementation for old MMLib fluid ingredients on NeoForge 1.21.
 */
public abstract class FluidIngredient implements Predicate<FluidStack> {
    public static final FluidIngredient EMPTY = new EmptyFluidIngredient();

    protected int amountRequired;
    private List<FluidStack> matchingFluidStacks;

    public static FluidIngredient fromTag(TagKey<Fluid> tag, int amount) {
        return new TagFluidIngredient(tag, amount);
    }

    public static FluidIngredient fromFluid(Fluid fluid, int amount) {
        return new SingleFluidIngredient(fluid, amount);
    }

    public static FluidIngredient fromFluid(Supplier<? extends Fluid> fluid, int amount) {
        return fromFluid(fluid.get(), amount);
    }

    public static FluidIngredient fromFluidStack(FluidStack stack) {
        return stack == null || stack.isEmpty() ? EMPTY : fromFluid(stack.getFluid(), stack.getAmount());
    }

    public int getAmount() {
        return amountRequired;
    }

    public int getRequiredAmount() {
        return amountRequired;
    }

    public List<FluidStack> getMatchingFluidStacks() {
        if (matchingFluidStacks == null) {
            matchingFluidStacks = determineMatchingFluidStacks();
        }
        return matchingFluidStacks;
    }

    public FluidStack[] getStacks() {
        return getMatchingFluidStacks().toArray(FluidStack[]::new);
    }

    public JsonObject serialize() {
        JsonObject json = new JsonObject();
        writeInternal(json);
        json.addProperty("amount", amountRequired);
        return json;
    }

    public static boolean isFluidIngredient(JsonElement json) {
        if (json == null || !json.isJsonObject()) return false;
        JsonObject obj = json.getAsJsonObject();
        return obj.has("fluid") || obj.has("tag");
    }

    public static FluidIngredient deserialize(JsonElement json) {
        if (json == null || !json.isJsonObject()) return EMPTY;
        JsonObject obj = json.getAsJsonObject();
        int amount = obj.has("amount") ? obj.get("amount").getAsInt() : 1000;
        if (obj.has("tag")) {
            ResourceLocation tagId = ResourceLocation.parse(obj.get("tag").getAsString());
            return fromTag(TagKey.create(Registries.FLUID, tagId), amount);
        }
        if (obj.has("fluid")) {
            Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(obj.get("fluid").getAsString()));
            return fromFluid(fluid, amount);
        }
        return EMPTY;
    }

    @Override
    public boolean test(FluidStack stack) {
        return stack != null && stack.getAmount() >= amountRequired && testInternal(stack);
    }

    protected abstract boolean testInternal(FluidStack stack);
    protected abstract List<FluidStack> determineMatchingFluidStacks();
    protected abstract void writeInternal(JsonObject json);

    private static final class EmptyFluidIngredient extends FluidIngredient {
        private EmptyFluidIngredient() {
            this.amountRequired = 0;
        }

        @Override
        public boolean test(FluidStack stack) {
            return false;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return false;
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.emptyList();
        }

        @Override
        protected void writeInternal(JsonObject json) {
        }
    }

    private static final class SingleFluidIngredient extends FluidIngredient {
        private final Fluid fluid;

        private SingleFluidIngredient(Fluid fluid, int amount) {
            this.fluid = fluid;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().isSame(fluid);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            return Collections.singletonList(new FluidStack(fluid, amountRequired));
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid).toString());
        }
    }

    private static final class TagFluidIngredient extends FluidIngredient {
        private final TagKey<Fluid> tag;

        private TagFluidIngredient(TagKey<Fluid> tag, int amount) {
            this.tag = tag;
            this.amountRequired = amount;
        }

        @Override
        protected boolean testInternal(FluidStack stack) {
            return stack.getFluid().builtInRegistryHolder().is(tag);
        }

        @Override
        protected List<FluidStack> determineMatchingFluidStacks() {
            List<FluidStack> stacks = new ArrayList<>();
            BuiltInRegistries.FLUID.getTag(tag).ifPresent(holders ->
                holders.forEach(holder -> stacks.add(new FluidStack(holder.value(), amountRequired)))
            );
            return stacks;
        }

        @Override
        protected void writeInternal(JsonObject json) {
            json.addProperty("tag", tag.location().toString());
        }
    }
}
""".trimIndent()

        val LEGACY_MMLIB_ABSTRACT_RECIPE_SHIM = """
package cn.mcmod_mmf.mmlib.recipe;

import com.google.gson.annotations.Expose;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;

/**
 * Generated compatibility base for MMLib recipes on the NeoForge 1.21 Recipe API.
 */
public abstract class AbstractRecipe implements Recipe<RecipeWrapper> {
    protected ResourceLocation id;
    public String group = "";
    @Expose
    public float experience;
    @Expose
    public int recipeTime;

    public void setId(ResourceLocation id) {
        this.id = id;
    }

    public ResourceLocation getId() {
        return id;
    }

    public float getExperience() {
        return experience;
    }

    public int getRecipeTime() {
        return recipeTime;
    }

    @Override
    public String getGroup() {
        return group;
    }

    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return getResultItem((HolderLookup.Provider) null);
    }

    @Override
    public abstract ItemStack getResultItem(HolderLookup.Provider registries);

    @Override
    public boolean isSpecial() {
        return true;
    }
}
""".trimIndent()

        val LEGACY_MMLIB_CHANCE_RESULT_SHIM = """
package cn.mcmod_mmf.mmlib.recipe;

import com.google.gson.annotations.Expose;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * Generated compatibility value type for MMLib chance results.
 */
public record ChanceResult(@Expose ItemStack stack, @Expose float chance) {
    public static final ChanceResult EMPTY = new ChanceResult(ItemStack.EMPTY, 0.0F);

    public ItemStack rollOutput(RandomSource random, int fortuneLevel) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        float effectiveChance = Math.min(1.0F, chance + Math.max(0, fortuneLevel) * 0.05F);
        return random.nextFloat() <= effectiveChance ? stack.copy() : ItemStack.EMPTY;
    }
}
""".trimIndent()

        val LEGACY_MMLIB_ABSTRACT_RECIPE_SERIALIZER_SHIM = """
package cn.mcmod_mmf.mmlib.recipe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.stream.Stream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * Generated compatibility serializer for old Gson-backed MMLib recipes on the 1.21 RecipeSerializer API.
 */
public class AbstractRecipeSerializer<T extends AbstractRecipe> implements RecipeSerializer<T> {
    private final Class<T> recipeClass;
    private final Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private final MapCodec<T> mapCodec = new MapCodec<>() {
        @Override
        public <O> RecordBuilder<O> encode(T input, DynamicOps<O> ops, RecordBuilder<O> prefix) {
            JsonObject json = toJson(input);
            for (var entry : json.entrySet()) {
                prefix = prefix.add(entry.getKey(), JsonOps.INSTANCE.convertTo(ops, entry.getValue()));
            }
            return prefix;
        }

        @Override
        public <O> DataResult<T> decode(DynamicOps<O> ops, MapLike<O> input) {
            try {
                JsonObject json = new JsonObject();
                input.entries().forEach(pair -> {
                    String key = ops.getStringValue(pair.getFirst()).result().orElse("");
                    if (!key.isEmpty()) {
                        json.add(key, ops.convertTo(JsonOps.INSTANCE, pair.getSecond()));
                    }
                });
                return DataResult.success(fromJson(json));
            } catch (Exception e) {
                return DataResult.error(() -> "Failed to decode legacy MMLib recipe: " + e.getMessage());
            }
        }

        @Override
        public <O> Stream<O> keys(DynamicOps<O> ops) {
            return Stream.empty();
        }
    };
    private final StreamCodec<RegistryFriendlyByteBuf, T> streamCodec = StreamCodec.of(
        (buf, recipe) -> buf.writeUtf(gson.toJson(recipe)),
        buf -> fromJson(com.google.gson.JsonParser.parseString(buf.readUtf(32767)).getAsJsonObject())
    );

    public AbstractRecipeSerializer(Class<T> recipeClass) {
        this.recipeClass = recipeClass;
    }

    @Override
    public MapCodec<T> codec() {
        return mapCodec;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return streamCodec;
    }

    public T fromJson(JsonObject json) {
        try {
            return gson.fromJson(json, recipeClass);
        } catch (JsonParseException e) {
            throw e;
        }
    }

    public JsonObject toJson(T recipe) {
        return gson.toJsonTree(recipe).getAsJsonObject();
    }
}
""".trimIndent()

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

        fun findMatchingParen(content: String, openIndex: Int): Int =
            findClosing(content, openIndex, '(', ')')

        /**
         * Generic bracket matcher: find the closing delimiter matching the opener at [openIndex].
         */
        fun findClosing(content: String, openIndex: Int, openChar: Char, closeChar: Char): Int {
            if (openIndex !in content.indices || content[openIndex] != openChar) return -1

            var depth = 0
            var i = openIndex
            while (i < content.length) {
                val ch = content[i]
                val next = content.getOrNull(i + 1)

                when {
                    ch == '/' && next == '/' -> {
                        val end = content.indexOfAny(charArrayOf('\n', '\r'), i + 2)
                        i = if (end < 0) content.length else end + 1
                        continue
                    }
                    ch == '/' && next == '*' -> {
                        val end = content.indexOf("*/", i + 2)
                        if (end < 0) return -1
                        i = end + 2
                        continue
                    }
                    ch == '"' && next == '"' && content.getOrNull(i + 2) == '"' -> {
                        val end = content.indexOf("\"\"\"", i + 3)
                        if (end < 0) return -1
                        i = end + 3
                        continue
                    }
                    ch == '\'' && next == '\'' && content.getOrNull(i + 2) == '\'' -> {
                        val end = content.indexOf("'''", i + 3)
                        if (end < 0) return -1
                        i = end + 3
                        continue
                    }
                    ch == '"' || ch == '\'' -> {
                        i = skipQuotedLiteral(content, i, ch)
                        if (i < 0) return -1
                        continue
                    }
                }

                when (ch) {
                    openChar -> depth++
                    closeChar -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return -1
        }

        private fun skipQuotedLiteral(content: String, startIndex: Int, quote: Char): Int {
            var escaped = false
            var i = startIndex + 1
            while (i < content.length) {
                val ch = content[i]
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    return i + 1
                }
                i++
            }
            return -1
        }

        fun findJavaStatementEnd(content: String, startIndex: Int): Int {
            var parenDepth = 0
            var braceDepth = 0
            var bracketDepth = 0
            var inString = false
            var inChar = false
            var inLineComment = false
            var inBlockComment = false
            var inTripleString = false
            var tripleQuote = '\u0000'
            var escaped = false
            var i = startIndex
            while (i < content.length) {
                val ch = content[i]
                val next = content.getOrNull(i + 1)
                val nextTwo = content.getOrNull(i + 2)

                if (inLineComment) {
                    if (ch == '\n' || ch == '\r') inLineComment = false
                    i++
                    continue
                }
                if (inBlockComment) {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (inTripleString) {
                    if (ch == tripleQuote && next == tripleQuote && nextTwo == tripleQuote) {
                        inTripleString = false
                        tripleQuote = '\u0000'
                        i += 3
                    } else {
                        i++
                    }
                    continue
                }
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                    i++
                    continue
                }
                if (inChar) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '\'') {
                        inChar = false
                    }
                    i++
                    continue
                }

                when {
                    ch == '/' && next == '/' -> {
                        inLineComment = true
                        i += 2
                        continue
                    }
                    ch == '/' && next == '*' -> {
                        inBlockComment = true
                        i += 2
                        continue
                    }
                    (ch == '"' || ch == '\'') && next == ch && nextTwo == ch -> {
                        inTripleString = true
                        tripleQuote = ch
                        i += 3
                        continue
                    }
                    ch == '"' -> inString = true
                    ch == '\'' -> inChar = true
                    ch == '(' -> parenDepth++
                    ch == ')' -> if (parenDepth > 0) parenDepth--
                    ch == '{' -> braceDepth++
                    ch == '}' -> if (braceDepth > 0) braceDepth--
                    ch == '[' -> bracketDepth++
                    ch == ']' -> if (bracketDepth > 0) bracketDepth--
                    ch == ';' && parenDepth == 0 && braceDepth == 0 && bracketDepth == 0 -> return i
                }
                i++
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
