package com.modporter.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to verify the mapping database is complete and internally consistent.
 */
class MappingCompletenessTest {

    @Test
    fun `all text replacements have unique IDs`() {
        val db = MappingDatabase.loadDefault()
        val ids = db.getTextReplacements().map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys

        assertTrue(duplicates.isEmpty(),
            "Found duplicate text replacement IDs: $duplicates")
    }

    @Test
    fun `all text replacements have non-empty patterns and replacements`() {
        val db = MappingDatabase.loadDefault()
        db.getTextReplacements().forEach { rule ->
            assertTrue(rule.pattern.isNotBlank(), "Rule ${rule.id} has empty pattern")
            // Replacement can be empty for removal rules (e.g., removing deprecated API usage)
            assertTrue(rule.description.isNotBlank(), "Rule ${rule.id} has empty description")
        }
    }

    @Test
    fun `text replacements do not generate placeholder comments`() {
        val db = MappingDatabase.loadDefault()
        val forbidden = listOf("TODO", "[forge2neo]", "/*", "//")
        val offenders = db.getTextReplacements().filter { rule ->
            forbidden.any { marker -> rule.replacement.contains(marker) }
        }.map { it.id }

        assertTrue(
            offenders.isEmpty(),
            "Text replacements must perform real migrations or removals, not emit placeholder comments: $offenders"
        )
    }

    @Test
    fun `no text replacement pattern equals its replacement`() {
        val db = MappingDatabase.loadDefault()
        db.getTextReplacements().forEach { rule ->
            assertTrue(rule.pattern != rule.replacement,
                "Rule ${rule.id} has pattern equal to replacement: ${rule.pattern}")
        }
    }

    @Test
    fun `dependency mappings do not depend on local machine paths`() {
        val text = javaClass.getResourceAsStream("/mappings/forge2neo/neoforge-deps.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("neoforge-deps.json missing")

        val forbidden = listOf(
            "E:/",
            "E:\\",
            "files('",
            "files(\"",
            "local NeoForge",
            "local HotBath",
            "sibling jar"
        )
        val offenders = forbidden.filter { text.contains(it, ignoreCase = true) }

        assertTrue(
            offenders.isEmpty(),
            "Dependency mappings must use reproducible public coordinates or explicit unavailable status, not local paths: $offenders"
        )
    }

    @Test
    fun `dependency mappings use supported status semantics`() {
        val text = javaClass.getResourceAsStream("/mappings/forge2neo/neoforge-deps.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("neoforge-deps.json missing")
        val dependencies = Json.parseToJsonElement(text).jsonObject.getValue("dependencies").jsonArray
        val supported = setOf("available", "unavailable", "check_online", "remove")

        val offenders = dependencies.flatMap { element ->
            val dep = element.jsonObject
            val prefix = dep.getValue("forgePrefix").jsonPrimitive.content
            val status = dep["status"]?.jsonPrimitive?.content ?: "unavailable"
            val coords = dep["neoforgeCoords"]?.jsonArray.orEmpty()
            val notes = dep["notes"]?.jsonPrimitive?.content.orEmpty()
            buildList {
                if (status !in supported) {
                    add("$prefix has unsupported status $status")
                }
                if (status == "available" && coords.isEmpty()) {
                    add("$prefix is available without NeoForge coordinates")
                }
                if (status == "remove" && coords.isNotEmpty()) {
                    add("$prefix is remove but still declares NeoForge coordinates")
                }
                if (status == "remove" && notes.isBlank()) {
                    add("$prefix is remove without evidence notes")
                }
                val versionProperties = dep["versionProperties"]?.jsonArray.orEmpty()
                versionProperties.forEach { versionElement ->
                    val versionProperty = versionElement.jsonObject
                    val name = versionProperty["name"]?.jsonPrimitive?.content.orEmpty()
                    val value = versionProperty["value"]?.jsonPrimitive?.content.orEmpty()
                    if (!Regex("""[A-Za-z_][A-Za-z0-9_.-]*""").matches(name)) {
                        add("$prefix has invalid version property name $name")
                    }
                    if (value.isBlank()) {
                        add("$prefix has blank target version property $name")
                    }
                    if (Regex("""\b1\.20(?:\.1)?\b""").containsMatchIn(value)) {
                        add("$prefix leaves target version property $name on old Minecraft line: $value")
                    }
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Dependency mappings must express supported automated resolution semantics: $offenders"
        )
    }

    @Test
    fun `default production migration code has no mod specific rule remnants`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val scannedRoots = listOf(
            projectRoot.resolve("src/main/kotlin"),
            projectRoot.resolve("src/main/resources/mappings/forge2neo")
        )
        val excludedFiles = setOf(
            "src/main/resources/mappings/forge2neo/neoforge-deps.json"
        )
        val forbidden = listOf(
            "twilightforest",
            "TwilightForest",
            "build-twilight",
            "res-twilight",
            "struct-twilight",
            "TFCave",
            "TFDamageTypes",
            "TFItems",
            "TFBlocks",
            "TFAdvancements",
            "Sakura",
            "sakura",
            "HotBath",
            "hotbath",
            "ConstructionWand",
            "constructionwand",
            "glass_sword",
            "FermenterRecipe",
            "DistillerRecipe"
        )

        val offenders = scannedRoots
            .filter { Files.exists(it) }
            .flatMap { root ->
                Files.walk(root).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { it.extension in setOf("kt", "json", "toml", "properties") }
                        .filter { file ->
                            val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                            relative !in excludedFiles
                        }
                        .toList()
                }
            }
            .flatMap { file ->
                val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                val text = file.readText()
                forbidden
                    .filter { marker -> text.contains(marker) }
                    .map { marker -> "$relative contains $marker" }
            }

        assertTrue(
            offenders.isEmpty(),
            "Default migration code must use source-shape/API rules, not mod-specific rules: $offenders"
        )
    }

    @Test
    fun `production kotlin sources do not contain TODO placeholder or reflection fallback debt`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val allowedReflectionMigrator = "src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt"
        val forbidden = listOf(
            "TODO" to Regex("""\bTODO\b"""),
            "FIXME" to Regex("""\bFIXME\b"""),
            "Class.forName" to Regex("""\bClass\.forName\s*\("""),
            "getDeclaredField" to Regex("""\bgetDeclaredField\s*\("""),
            "getDeclaredMethod" to Regex("""\bgetDeclaredMethod\s*\("""),
            "getDeclaredConstructor" to Regex("""\bgetDeclaredConstructor\s*\("""),
            "getMethod" to Regex("""\.getMethod\s*\("""),
            "setAccessible" to Regex("""\bsetAccessible\s*\(""")
        )

        val offenders = Files.walk(projectRoot.resolve("src/main/kotlin")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .flatMap { file ->
                    val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                    val text = file.readText()
                    forbidden
                        .filter { (marker, pattern) ->
                            pattern.containsMatchIn(text) &&
                                !(relative == allowedReflectionMigrator && marker in setOf("getDeclaredField", "getDeclaredMethod", "getDeclaredConstructor", "getMethod", "setAccessible", "Class.forName"))
                        }
                        .map { (marker, _) -> "$relative contains $marker" }
                        .stream()
                }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "Production Kotlin sources must not carry TODO placeholders or reflection fallbacks: $offenders"
        )
    }

    @Test
    fun `production registry access migrations do not use nearby variable or fallback inference`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "nearby registryAccess helper" to Regex("""inferRegistryAccessExpressionNear"""),
            "windowed registryAccess scan" to Regex("""offset\s*-\s*\d+[\s\S]{0,400}registryAccess\(\)"""),
            "last declaration registryAccess scan" to Regex("""lastOrNull\(\)[\s\S]{0,400}registryAccess\(\)"""),
            "registryAccess elvis fallback" to Regex("\\?:\\s*\"[^\"\\r\\n]*registryAccess\\(\\)\"")
        )

        val offenders = Files.walk(projectRoot.resolve("src/main/kotlin")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .flatMap { file ->
                    val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                    val text = file.readText()
                    forbidden
                        .filter { (_, pattern) -> pattern.containsMatchIn(text) }
                        .map { (label, _) -> "$relative contains $label" }
                        .stream()
                }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "Registry access migrations must use structured source-shape resolution, not nearby-variable or fallback inference: $offenders"
        )
    }

    @Test
    fun `production capability migrations do not infer unrelated level variables`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "capability level inference helper" to Regex("""inferLevelVariableForCapability""")
        )

        val offenders = Files.walk(projectRoot.resolve("src/main/kotlin")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .flatMap { file ->
                    val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                    val text = file.readText()
                    forbidden
                        .filter { (_, pattern) -> pattern.containsMatchIn(text) }
                        .map { (label, _) -> "$relative contains $label" }
                        .stream()
                }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "Capability migrations must derive Level/WorldGenLevel from the Java source relationship being migrated, not from unrelated nearby declarations: $offenders"
        )
    }

    @Test
    fun `production migrations do not synthesize minecraft namespace when mod id is missing`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val quotedMinecraftStringLiteral = "\"\\\"minecraft\\\"\""
        val forbidden = listOf(
            "literal minecraft mod id return" to "return $quotedMinecraftStringLiteral",
            "literal minecraft mod id elvis fallback" to "?: $quotedMinecraftStringLiteral"
        )

        val offenders = Files.walk(projectRoot.resolve("src/main/kotlin")).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .flatMap { file ->
                    val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                    val text = file.readText()
                    forbidden
                        .filter { (_, marker) -> text.contains(marker) }
                        .map { (label, _) -> "$relative contains $label" }
                        .stream()
                }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "Migration rules must derive mod ids from source structure, not silently synthesize minecraft as a namespace fallback: $offenders"
        )
    }

    @Test
    fun `default migration surfaces do not retreat to manual handling`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val scannedRoots = listOf(
            projectRoot.resolve("README.md"),
            projectRoot.resolve("docs"),
            projectRoot.resolve("src/main/kotlin"),
            projectRoot.resolve("src/main/resources/mappings/forge2neo")
        )
        val forbidden = listOf(
            "manual" to Regex("""\bmanual\b""", RegexOption.IGNORE_CASE),
            "manual review" to Regex("""manual\s+review""", RegexOption.IGNORE_CASE),
            "manual migration" to Regex("""manual\s+migration""", RegexOption.IGNORE_CASE),
            "manual intervention" to Regex("""manual\s+intervention""", RegexOption.IGNORE_CASE),
            "manual follow-up" to Regex("""manual\s+follow[- ]up""", RegexOption.IGNORE_CASE),
            "follow-up" to Regex("""follow[- ]ups?""", RegexOption.IGNORE_CASE),
            "by hand" to Regex("""\bby\s+hand\b""", RegexOption.IGNORE_CASE),
            "hand edit" to Regex("""hand[- ]edit""", RegexOption.IGNORE_CASE),
            "requires human" to Regex("""requires\s+human""", RegexOption.IGNORE_CASE),
            "needs human" to Regex("""needs\s+human""", RegexOption.IGNORE_CASE),
            "human verification" to Regex("""human\s+verification""", RegexOption.IGNORE_CASE),
            "human review" to Regex("""human\s+review""", RegexOption.IGNORE_CASE),
            "human-in-the-loop" to Regex("""human[- ]in[- ]the[- ]loop""", RegexOption.IGNORE_CASE),
            "comment out" to Regex("""comment\s+out""", RegexOption.IGNORE_CASE),
            "commented out" to Regex("""commented\s+out""", RegexOption.IGNORE_CASE),
            "placeholder" to Regex("""\bplaceholder\b""", RegexOption.IGNORE_CASE)
        )

        val files = scannedRoots.flatMap { root ->
            when {
                Files.isRegularFile(root) -> listOf(root)
                Files.exists(root) -> Files.walk(root).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { it.extension in setOf("kt", "json", "toml", "properties", "md") }
                        .toList()
                }
                else -> emptyList()
            }
        }

        val offenders = files.flatMap { file ->
            val relative = projectRoot.relativize(file).invariantSeparatorsPathString
            val text = file.readText()
            forbidden
                .filter { (_, pattern) -> pattern.containsMatchIn(text) }
                .map { (label, _) -> "$relative contains $label" }
        }

        assertTrue(
            offenders.isEmpty(),
            "Default migration surfaces must fail through automated gates instead of retreating to manual handling: $offenders"
        )
    }

    @Test
    fun `package renames are ordered correctly`() {
        // More specific packages must come BEFORE the generic net.minecraftforge
        val db = MappingDatabase.loadDefault()
        val pkgRules = db.getTextReplacements().filter { it.id.startsWith("pkg-") }

        val mainIdx = pkgRules.indexOfFirst { it.id == "pkg-forge-main" }
        assertTrue(mainIdx == pkgRules.lastIndex,
            "pkg-forge-main must be the LAST package rule to avoid double-replacement")

        // Verify all specific packages come before the generic one
        val specificPkgs = listOf("pkg-fml", "pkg-eventbus", "pkg-api-distmarker",
            "pkg-forgespi", "pkg-accesstransformer", "pkg-coremod", "pkg-jarjar")
        specificPkgs.forEach { id ->
            val idx = pkgRules.indexOfFirst { it.id == id }
            if (idx >= 0) {
                assertTrue(idx < mainIdx,
                    "$id (index $idx) must come before pkg-forge-main (index $mainIdx)")
            }
        }
    }

    @Test
    fun `class mappings have distinct source and target`() {
        val db = MappingDatabase.loadDefault()
        db.getAllClassMappings().forEach { (forge, mapping) ->
            assertTrue(forge != mapping.neoForgeClass,
                "Class mapping $forge maps to itself")
        }
    }

    @Test
    fun `class mappings do not have conflicting targets`() {
        val db = MappingDatabase.loadDefault()
        val targets = db.getAllClassMappings().values.groupBy { it.neoForgeClass }
        val conflicts = targets.filter { it.value.size > 1 }

        assertTrue(conflicts.isEmpty(),
            "Multiple classes map to the same target: ${conflicts.keys}")
    }

    @Test
    fun `method mappings reference known patterns`() {
        val db = MappingDatabase.loadDefault()
        db.getAllMethodMappings().forEach { (key, mapping) ->
            assertTrue(mapping.forgeClass.isNotBlank(), "Method mapping $key has empty forgeClass")
            assertTrue(mapping.forgeMethod.isNotBlank(), "Method mapping $key has empty forgeMethod")
            // neoForgeMethod can be empty for REMOVED methods
            assertTrue(mapping.description.isNotBlank(), "Method mapping $key has empty description")
        }
    }

    @Test
    fun `resource renames are unique and non-overlapping`() {
        val db = MappingDatabase.loadDefault()
        val renames = db.getAllResourceRenames()

        // No two source paths should be the same
        assertTrue(renames.size == renames.keys.distinct().size,
            "Resource renames have duplicate sources")

        // No target should equal any source (avoid cycles)
        val sources = renames.keys
        val targets = renames.values.toSet()
        val overlap = sources.intersect(targets)
        assertTrue(overlap.isEmpty(),
            "Resource rename has circular mapping: $overlap")
    }

    @Test
    fun `minimum mapping counts are met`() {
        val db = MappingDatabase.loadDefault()

        assertTrue(db.getTextReplacements().size >= 30,
            "Should have >= 30 text replacements, got ${db.getTextReplacements().size}")
        assertTrue(db.getAllClassMappings().size >= 40,
            "Should have >= 40 class mappings, got ${db.getAllClassMappings().size}")
        assertTrue(db.getAllMethodMappings().size >= 15,
            "Should have >= 15 method mappings, got ${db.getAllMethodMappings().size}")
        assertTrue(db.getAllResourceRenames().size >= 12,
            "Should have >= 12 resource renames, got ${db.getAllResourceRenames().size}")
    }

    @Test
    fun `IForgeXXX extension pattern is systematic`() {
        val db = MappingDatabase.loadDefault()
        val extensions = db.getAllClassMappings()
            .filter { it.key.startsWith("IForge") && it.key != "IForgeShearable" }

        extensions.forEach { (forge, mapping) ->
            val baseName = forge.removePrefix("IForge")
            val expected = "I${baseName}Extension"
            assertEquals(expected, mapping.neoForgeClass,
                "$forge should map to $expected, got ${mapping.neoForgeClass}")
        }
    }

    @Test
    fun `regex text replacements compile without error`() {
        val db = MappingDatabase.loadDefault()
        db.getTextReplacements().filter { it.isRegex }.forEach { rule ->
            try {
                Regex(rule.pattern)
            } catch (e: Exception) {
                throw AssertionError("Rule ${rule.id} has invalid regex '${rule.pattern}': ${e.message}")
            }
        }
    }
}
