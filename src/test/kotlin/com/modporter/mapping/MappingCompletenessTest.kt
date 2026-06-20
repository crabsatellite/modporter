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
    fun `text replacements do not carry disabled noop or uncertainty rules`() {
        val db = MappingDatabase.loadDefault()
        val forbiddenDescriptions = listOf(
            "disabled rule" to Regex("""^\s*Disabled:""", RegexOption.IGNORE_CASE),
            "uncertain rule" to Regex("""\bmay\s+need\b""", RegexOption.IGNORE_CASE)
        )
        val offenders = db.getTextReplacements().flatMap { rule ->
            buildList {
                if (rule.pattern == "(?!)") {
                    add("${rule.id} has inert no-op pattern")
                }
                forbiddenDescriptions
                    .filter { (_, pattern) -> pattern.containsMatchIn(rule.description) }
                    .forEach { (label, _) -> add("${rule.id} has $label description") }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Text replacements must be executable migrations/removals, not disabled placeholders or uncertainty notes: $offenders"
        )
    }

    @Test
    fun `text replacements do not migrate register event string ids without source structure`() {
        val db = MappingDatabase.loadDefault()
        val offenders = db.getTextReplacements()
            .filter { rule ->
                rule.pattern.contains("registry") &&
                    rule.pattern.contains("register") &&
                    (rule.pattern.contains("\"") || rule.replacement.contains("ResourceLocation.parse"))
            }
            .map { it.id }

        assertTrue(
            offenders.isEmpty(),
            "RegisterEvent string-id migration needs source-derived mod id structure and must not be a text replacement: $offenders"
        )
    }

    @Test
    fun `text replacements do not rewrite untyped getTag calls`() {
        val db = MappingDatabase.loadDefault()
        val offenders = db.getTextReplacements()
            .filter { rule -> rule.pattern == ".getTag()" || rule.pattern.contains("""\.getTag\(\)""") }
            .map { it.id }

        assertTrue(
            offenders.isEmpty(),
            "Bare getTag() replacements are not type-safe; use structural ItemStack rules instead: $offenders"
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
    fun `dependency mapping notes describe artifacts not benchmark upstream ports`() {
        val text = javaClass.getResourceAsStream("/mappings/forge2neo/neoforge-deps.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("neoforge-deps.json missing")
        val dependencies = Json.parseToJsonElement(text).jsonObject.getValue("dependencies").jsonArray
        val forbidden = listOf(
            "upstream port citation" to Regex("""\bupstream\b""", RegexOption.IGNORE_CASE),
            "benchmark citation" to Regex("""\bbenchmark\b""", RegexOption.IGNORE_CASE)
        )

        val offenders = dependencies.flatMapIndexed { index, element ->
            val dependency = element.jsonObject
            val dependencyId = dependency["forgePrefix"]?.jsonPrimitive?.content ?: "dependency[$index]"
            val notes = buildList {
                dependency["notes"]?.jsonPrimitive?.content?.let { note ->
                    add("notes" to note)
                }
                dependency["versionProperties"]?.jsonArray?.forEach { versionElement ->
                    val versionProperty = versionElement.jsonObject
                    val name = versionProperty["name"]?.jsonPrimitive?.content.orEmpty()
                    versionProperty["notes"]?.jsonPrimitive?.content?.let { note ->
                        add("versionProperties[$name].notes" to note)
                    }
                }
            }
            notes.flatMap { (field, note) ->
                forbidden
                    .filter { (_, pattern) -> pattern.containsMatchIn(note) }
                    .map { (label, _) -> "$dependencyId $field contains $label" }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Dependency mappings must cite source/target artifacts, not benchmark upstream ports: $offenders"
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
            "scepter_repair",
            "ModPorterScepterRepairRecipe",
            "_scepter",
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
    fun `third party API migration markers are declared API surfaces`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val apiSurfaceFile = projectRoot.resolve("src/main/resources/mappings/forge2neo/api-surfaces.json")
        data class ApiSurface(
            val id: String,
            val markers: List<String>,
            val ruleIdPrefixes: List<String>,
            val allowedFiles: Set<String>
        )

        val surfaces = Json.parseToJsonElement(apiSurfaceFile.readText()).jsonArray.map { element ->
            val json = element.jsonObject
            val id = json.getValue("id").jsonPrimitive.content
            val markers = listOf("packagePrefixes", "coordinatePrefixes", "resourceTypeIds", "ruleIdPrefixes")
                .flatMap { key ->
                    json[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                }
            ApiSurface(
                id = id,
                markers = markers,
                ruleIdPrefixes = json["ruleIdPrefixes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                allowedFiles = json["allowedFiles"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            )
        }
        val malformed = surfaces
            .filter { surface -> surface.id.isBlank() || surface.markers.isEmpty() || surface.allowedFiles.isEmpty() }
            .map { surface -> surface.id.ifBlank { "<blank>" } }

        assertTrue(malformed.isEmpty(), "API surfaces must have ids, markers, and allowed files: $malformed")

        val scannedRoots = listOf(
            projectRoot.resolve("src/main/kotlin"),
            projectRoot.resolve("src/main/resources/mappings/forge2neo")
        )
        val excludedFiles = setOf(
            "src/main/resources/mappings/forge2neo/api-surfaces.json",
            "src/main/resources/mappings/forge2neo/neoforge-deps.json"
        )
        val thirdPartyRoots = listOf(
            "com.aetherteam.",
            "com.blamejared.crafttweaker",
            "com.simibubi.create",
            "curse.maven:",
            "dev.engine-room.flywheel",
            "farmersdelight:",
            "maven.modrinth:",
            "mezz.jei.",
            "mezz.jei:",
            "net.createmod.ponder",
            "noobanidus.mods.lootr.",
            "org.valkyrienskies",
            "org.violetmoon.",
            "sereneseasons.",
            "squeek.appleskin",
            "top.theillusivec4.",
            "top.theillusivec4:",
            "vazkii.botania"
        )

        val productionFiles = scannedRoots
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
        val productionRelativeFiles = productionFiles
            .map { file -> projectRoot.relativize(file).invariantSeparatorsPathString }
            .toSet()
        val allowedFileOffenders = surfaces.flatMap { surface ->
            surface.allowedFiles
                .filter { allowed -> allowed !in productionRelativeFiles }
                .map { allowed -> "${surface.id} allows non-production file $allowed" }
        }

        fun surfacesDeclaring(root: String): List<ApiSurface> =
            surfaces.filter { surface ->
                surface.markers.any { marker -> root.startsWith(marker) || marker.startsWith(root) }
            }

        val undeclaredRootOffenders = productionFiles
            .flatMap { file ->
                val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                val text = file.readText()
                thirdPartyRoots
                    .filter { root -> text.contains(root) && surfacesDeclaring(root).isEmpty() }
                    .map { root -> "$relative contains undeclared third-party API marker $root" }
            }

        val markerScopeOffenders = productionFiles
            .flatMap { file ->
                val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                val text = file.readText()
                surfaces.flatMap { surface ->
                    surface.markers
                        .filter { marker -> text.contains(marker) && relative !in surface.allowedFiles }
                        .map { marker ->
                            "$relative contains ${surface.id} marker $marker outside declared API-surface files"
                        }
                }
            }

        val ruleIdPrefixes = surfaces.flatMap { surface ->
            surface.ruleIdPrefixes.map { prefix -> prefix to surface }
        }

        val ruleIdOffenders = productionFiles
            .flatMap { file ->
                val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                Regex(""""((?:struct|res|build)-[a-z0-9]+-[^"]*)"""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .filter { ruleId ->
                        listOf("nitrogen", "cumulus", "curios", "farmersdelight", "quark").any { ruleId.contains("-$it-") }
                    }
                    .flatMap { ruleId ->
                        val declaringSurfaces = ruleIdPrefixes
                            .filter { (prefix, _) -> ruleId.startsWith(prefix) }
                            .map { (_, surface) -> surface }
                        when {
                            declaringSurfaces.isEmpty() ->
                                listOf("$relative contains undeclared third-party API rule id $ruleId")
                            declaringSurfaces.none { surface -> relative in surface.allowedFiles } ->
                                listOf("$relative contains third-party API rule id $ruleId outside declared API-surface files")
                            else -> emptyList()
                        }
                    }
                    .toList()
            }

        val offenders = allowedFileOffenders + undeclaredRootOffenders + markerScopeOffenders + ruleIdOffenders
        assertTrue(
            offenders.isEmpty(),
            "Third-party API migrations must stay inside declared API surfaces, not ad hoc mod-specific rules: $offenders"
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
            "whole-file registryAccess helper" to Regex("""\binferRegistryAccessExpression\b"""),
            "name-suffix entity registryAccess inference" to Regex("""\.endsWith\("Entity"\)|\.endsWith\("Projectile"\)"""),
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
    fun `legacy doEnchant damage effect migration does not use nearby damage source fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        assertTrue(
            !source.contains("inferDamageSourceForDoEnchantDamageEffects"),
            "Legacy doEnchantDamageEffects migration must not infer damage sources from nearby declarations"
        )

        val start = source.indexOf("private fun damageSourceFromPrecedingTargetHurt")
        assertTrue(start >= 0, "damageSourceFromPrecedingTargetHurt is missing")
        val end = source.indexOf("private fun migrateMethodCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "method-scope DamageSource declaration scan" to "DamageSource",
            "last declaration fallback" to "lastOrNull()"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "legacy doEnchantDamageEffects migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy doEnchantDamageEffects migration must use the same target's preceding hurt call, not nearby DamageSource declarations: $offenders"
        )
    }

    @Test
    fun `legacy damage bonus migration does not use method wide client side fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        assertTrue(
            !source.contains("inferServerLevelExpressionForMethod"),
            "Legacy getDamageBonus migration must not infer ServerLevel from whole-method client-side scans"
        )

        val start = source.indexOf("private fun sourceProvenServerLevelExpressionForDamageBonus")
        assertTrue(start >= 0, "sourceProvenServerLevelExpressionForDamageBonus is missing")
        val end = source.indexOf("private fun migrateLivingDamageEventBoundarySource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "whole-method this.level client-side scan" to "methodText.contains(\"this.level().isClientSide()\")",
            "whole-method negated this.level client-side scan" to "methodText.contains(\"!this.level().isClientSide()\")",
            "whole-method Level early-return scan" to "methodText.contains(\"if (${'$'}levelName.isClientSide()) return;\")",
            "negated Level client-side block fallback" to "if (!${'$'}levelName.isClientSide())"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "legacy getDamageBonus migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy getDamageBonus migration must use source-proven ServerLevel evidence before the call site, not whole-method client-side fallbacks: $offenders"
        )
    }

    @Test
    fun `legacy item stack random hurt migration does not use whole file server level fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        assertTrue(
            !source.contains("inferServerLevelExpression("),
            "Legacy ItemStack.hurt migration must not infer ServerLevel from whole-source declarations"
        )

        val start = source.indexOf("private fun sourceProvenServerLevelExpressionForLegacyItemStackHurt")
        assertTrue(start >= 0, "sourceProvenServerLevelExpressionForLegacyItemStackHurt is missing")
        val end = source.indexOf("private fun legacyItemStackHurtConditionParts", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "whole-source containsMatchIn scan" to "containsMatchIn(source)",
            "last declaration fallback" to "lastOrNull()"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "legacy ItemStack.hurt migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy ItemStack.hurt migration must use call-site method prefix evidence, not whole-file ServerLevel fallbacks: $offenders"
        )
    }

    @Test
    fun `legacy damage bonus migration binds damage source to target hurt`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        assertTrue(
            !source.contains("firstHurtDamageSourceArgument"),
            "Legacy getDamageBonus migration must not use the first or only hurt call from the whole method"
        )

        val start = source.indexOf("private fun damageSourceForDamageBonusTarget")
        assertTrue(start >= 0, "damageSourceForDamageBonusTarget is missing")
        val end = source.indexOf("private fun sourceProvenServerLevelExpressionForDamageBonus", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "whole-method bare hurt scan" to "methodText.indexOf(\".hurt(\", cursor)",
            "bare hurt token offset" to "tokenIndex + \".hurt\".length"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "legacy getDamageBonus damage source migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy getDamageBonus migration must bind DamageSource to the same LivingEntity target, not any method hurt call: $offenders"
        )
    }

    @Test
    fun `loot conditional function codec migrations do not synthesize member names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun inferLootConditionalFunctionCodecField")
        assertTrue(start >= 0, "inferLootConditionalFunctionCodecField is missing")
        val end = source.indexOf("private fun inferEntityTypeAndIntLootFunctionCodecField", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "item member fallback" to "?: \"item\"",
            "default item member fallback" to "?: \"oldItem\"",
            "success member fallback" to "?: \"success\""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "loot conditional function codec migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Loot conditional function codec migrations must derive member names from serializer source, not synthesize placeholders: $offenders"
        )
    }

    @Test
    fun `loot entity and int function codec migrations do not use local variable member fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun inferEntityTypeAndIntLootFunctionCodecField")
        assertTrue(start >= 0, "inferEntityTypeAndIntLootFunctionCodecField is missing")
        val end = source.indexOf("private fun migrateLootTypeRegistryCodecConstructors", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "constructor local variable member fallback" to "?: arg"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "loot entity/int function codec migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Loot entity/int function codec migrations must derive object member names from serializer source, not local variable names: $offenders"
        )
    }

    @Test
    fun `production mod event bus migrations do not synthesize variable names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "modEventBus elvis fallback" to "?: \"modEventBus\"",
            "eventBus elvis fallback" to "?: \"eventBus\""
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
            "Mod event bus migrations must derive the bus variable from source structure, not synthesize variable names: $offenders"
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
    fun `production migrations do not synthesize placeholder mod id values`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "literal modid return" to "return \"\\\"modid\\\"\"",
            "literal modid elvis fallback" to "?: \"\\\"modid\\\"\"",
            "plain modid elvis fallback" to "?: \"modid\"",
            "package-tail modid fallback" to "substringAfterLast('.', \"modid\")",
            "generated compat shared package fallback" to "?: \"shared\"",
            "generated compat blank mod package fallback" to "ifBlank { \"mod\" }",
            "legacy generated compat placeholder resolver" to "private fun detectGeneratedCompatPackage"
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
            "Migration rules must fail through source-derived mod id gates, not synthesize placeholder modid values: $offenders"
        )
    }

    @Test
    fun `production resource migrations do not synthesize placeholder asset stems`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "blank fuel asset stem fallback" to "ifBlank { \"fuel\" }",
            "generated fuel fallback asset name" to "nitrogen_fuel_fuel"
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
            "Resource migrations must derive asset names from source paths or hard-gate, not synthesize placeholder stems: $offenders"
        )
    }

    @Test
    fun `legacy pack resource compat package detection does not synthesize placeholder package segments`() {
        val structuralPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = structuralPass.indexOf("private fun detectRequiredGeneratedCompatPackage")
        assertTrue(start >= 0, "detectRequiredGeneratedCompatPackage is missing")
        val end = structuralPass.indexOf("\n    private fun migrateLegacyPackResourceApis", start)
        assertTrue(end > start, "detectRequiredGeneratedCompatPackage boundary is missing")
        val body = structuralPass.substring(start, end)
        val forbidden = listOf(
            "shared package fallback" to "?: \"shared\"",
            "literal shared package segment" to "\"shared\"",
            "literal mod package fallback" to "?: \"mod\"",
            "blank mod id fallback" to "ifBlank { \"mod\" }"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Generated compat package names must come from source-declared mod ids, not placeholders: $offenders"
        )
        assertTrue(
            body.contains("missing @Mod annotation and mod metadata mod id"),
            "Generated compat package detection must hard-gate missing source mod ids"
        )
        assertTrue(
            structuralPass.contains("""detectRequiredGeneratedCompatPackage(projectDir, "legacy pack resource adapters")"""),
            "Legacy pack resource adapter generation must use required source-derived mod ids"
        )
    }

    @Test
    fun `custom entity capability LazyOptional bridge uses required source mod ids`() {
        val structuralPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = structuralPass.indexOf("private fun migrateCustomEntityCapabilities")
        assertTrue(start >= 0, "migrateCustomEntityCapabilities is missing")
        val end = structuralPass.indexOf("\n    private fun findCapabilityImplementations", start)
        assertTrue(end > start, "migrateCustomEntityCapabilities boundary is missing")
        val body = structuralPass.substring(start, end)
        val offenders = listOf(
            "placeholder compat resolver" to "detectGeneratedCompatPackage(projectDir)",
            "shared package fallback" to "?: \"shared\"",
            "blank mod id fallback" to "ifBlank { \"mod\" }"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Custom entity capability LazyOptional bridge must not synthesize generated compat package ids: $offenders"
        )
        assertTrue(
            body.contains("""detectRequiredGeneratedCompatPackage(projectDir, "custom entity capability LazyOptional bridge")"""),
            "Custom entity capability LazyOptional bridge must use a required source-derived mod id gate"
        )
        assertTrue(
            structuralPass.contains("private fun rewriteLegacyEntityCapabilityQueries(") &&
                structuralPass.contains("compatPackage: () -> String"),
            "Custom entity capability query rewrites must resolve compat packages lazily at the bridge site"
        )
    }

    @Test
    fun `common LazyOptional import bridge uses required source mod ids`() {
        val structuralPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = structuralPass.indexOf("private fun migrateCommonNeoForge121Apis")
        assertTrue(start >= 0, "migrateCommonNeoForge121Apis is missing")
        val end = structuralPass.indexOf("\n    private data class", start).let {
            if (it < 0) structuralPass.indexOf("\n    private fun migrateVanilla121ApiSource", start) else it
        }
        assertTrue(end > start, "migrateCommonNeoForge121Apis boundary is missing")
        val body = structuralPass.substring(start, end)
        val offenders = listOf(
            "placeholder compat resolver" to "detectGeneratedCompatPackage(projectDir)",
            "shared package fallback" to "?: \"shared\"",
            "blank mod id fallback" to "ifBlank { \"mod\" }"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Common LazyOptional import bridge must not synthesize generated compat package ids: $offenders"
        )
        assertTrue(
            body.contains("""detectRequiredGeneratedCompatPackage(projectDir, "common 1.21 LazyOptional import bridge")"""),
            "Common LazyOptional import bridge must use a required source-derived mod id gate"
        )
        assertTrue(
            structuralPass.contains("private fun migrateGeneratedLazyOptionalImportSource(source: String, generatedCompatPackage: () -> String)"),
            "Generated LazyOptional import migration must resolve compat packages lazily at the import bridge site"
        )
    }

    @Test
    fun `resource mod id detection does not infer constant owners from file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val resourceMigrator = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()
        val start = resourceMigrator.indexOf("private fun detectJavaModIds")
        assertTrue(start >= 0, "detectJavaModIds is missing")
        val end = resourceMigrator.indexOf("\n    private fun ", start + 1).let {
            if (it < 0) resourceMigrator.length else it
        }
        val body = resourceMigrator.substring(start, end)
        val forbidden = listOf(
            "java file-name owner" to Regex("""fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "type-name elvis file fallback" to Regex("""javaTypeNameContainingOffset\([^)]*\)\s*\?:\s*[^;\r\n]*file"""),
            "bare @Mod file owner fallback" to Regex("""ids\.putIfAbsent\([^,\r\n]*className""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "detectJavaModIds contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Resource mod id detection must use source-declared owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `production migrations do not derive mod identity from project directory names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "mod id project directory fallback" to Regex("""(?i)mod_?id[\s\S]{0,180}projectDir\.fileName\.toString\(\)"""),
            "detectModId project directory fallback" to Regex("""detectModId\(projectDir\)[\s\S]{0,180}projectDir\.fileName\.toString\(\)"""),
            "metadata mod id project directory fallback" to Regex("""projectMetadataModId\(projectDir\)[\s\S]{0,180}projectDir\.fileName\.toString\(\)""")
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
            "Migration rules must derive mod identity from source metadata, not checkout or benchmark directory names: $offenders"
        )
    }

    @Test
    fun `production migrations do not derive mod identity from package names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "package tail mod id fallback" to Regex("""packageName\.substringAfterLast\('\.'\)""")
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
            "Migration rules must derive mod identity from declared source or metadata, not Java package names: $offenders"
        )
    }

    @Test
    fun `resource string constant resolution does not use qualified tail fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val resourceMigrator = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()
        val start = resourceMigrator.indexOf("private fun resolveJavaStringExpression")
        assertTrue(start >= 0, "resolveJavaStringExpression is missing")
        val end = resourceMigrator.indexOf("\n    private fun ", start + 1).let { if (it < 0) resourceMigrator.length else it }
        val body = resourceMigrator.substring(start, end)

        val forbidden = listOf(
            "qualified tail fallback" to "substringAfterLast('.')"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "resolveJavaStringExpression contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Resource string migrations must resolve exact source constants, not guess by the last qualified segment: $offenders"
        )
    }

    @Test
    fun `code awarded advancement detection does not infer constant owners from file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val resourceMigrator = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()
        val start = resourceMigrator.indexOf("private fun detectCodeAwardedAdvancements")
        assertTrue(start >= 0, "detectCodeAwardedAdvancements is missing")
        val end = resourceMigrator.indexOf("\n    private fun ", start + 1).let {
            if (it < 0) resourceMigrator.length else it
        }
        val body = resourceMigrator.substring(start, end)
        val forbidden = listOf(
            "fallback class name" to Regex("""fallbackClassName"""),
            "file-name class fallback" to Regex("""javaTypeNameContainingOffset\([^)]*\)\s*\?:\s*[^;\r\n]*fileName""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "detectCodeAwardedAdvancements contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Code-awarded advancement detection must use source-declared constant owners, not file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `production migrations do not resolve source constants by qualified tail fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "mod id qualified tail fallback" to Regex("""modIds\[[^\]\r\n]*substringAfterLast\('\.'\)[^\]\r\n]*]"""),
            "constant qualified tail fallback" to Regex("""constants\[[^\]\r\n]*substringAfterLast\('\.'\)[^\]\r\n]*]""")
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
            "Source constant migrations must resolve exact or unique constants, not degrade qualified references to their last segment: $offenders"
        )
    }

    @Test
    fun `custom enchantment data migrations do not resolve references by qualified tail fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val forbidden = listOf(
            "category qualified tail fallback" to Regex("""categories\[[^\]\r\n]*substringAfterLast\('\.'\)[^\]\r\n]*]"""),
            "enchantment qualified tail fallback" to Regex("""enchantmentRefs\[[^\]\r\n]*substringAfterLast\('\.'\)[^\]\r\n]*]"""),
            "registry qualified tail fallback" to Regex("""registryEntries\[[^\]\r\n]*substringAfterLast\('\.'\)[^\]\r\n]*]"""),
            "class source qualified tail lookup" to Regex("""classSources\[[^\]\r\n]*substringAfterLast\('\.'\)[^\]\r\n]*]""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(source) }
            .map { (label, _) -> "TextReplacementPass contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Custom enchantment data migrations must resolve Java references structurally, not by the last qualified segment: $offenders"
        )
    }

    @Test
    fun `custom enchantment data migrations do not infer declaration owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun detectLegacyJavaModIds")
        assertTrue(start >= 0, "detectLegacyJavaModIds is missing")
        val end = source.indexOf("private fun collectLegacyItemRegistryEntries", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "java file-name owner" to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "type-name elvis file fallback" to Regex("""javaTypeNameContainingOffset\([^)]*\)\s*\?:\s*file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "bare @Mod file owner fallback" to Regex("""ids\.putIfAbsent\([^,\r\n]*className"""),
            "global simple mod id table" to Regex("""\bsimpleValues\b""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "TextReplacementPass custom enchantment data contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Custom enchantment data migrations must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `loot table registry migrations do not use class name suffix inference`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyLootTableResourceLocationRegistry")
        assertTrue(start >= 0, "migrateLegacyLootTableResourceLocationRegistry is missing")
        val end = source.indexOf("\n    private fun ", start + 1).let { if (it < 0) source.length else it }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "class name loot suffix variable" to "classLooksLikeLootRegistry",
            "LootTables class suffix regex" to Regex("""LootTables\|LootIds|Loot\|LootTables""")
        )
        val offenders = forbidden
            .filter { (_, marker) ->
                when (marker) {
                    is String -> body.contains(marker)
                    is Regex -> marker.containsMatchIn(body)
                    else -> false
                }
            }
            .map { (label, _) -> "migrateLegacyLootTableResourceLocationRegistry contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Loot table registry migrations must use API/register-set structure, not class-name suffix inference: $offenders"
        )

        val keyExpressionStart = source.indexOf("private fun isLootTableResourceKeyExpression")
        assertTrue(keyExpressionStart >= 0, "isLootTableResourceKeyExpression is missing")
        val keyExpressionEnd = source.indexOf("\n    private fun ", keyExpressionStart + 1).let {
            if (it < 0) source.length else it
        }
        val keyExpressionBody = source.substring(keyExpressionStart, keyExpressionEnd)
        val keyExpressionOffenders = listOf(
            "owner name Loot suffix regex" to Regex("""\(\?:Loot\|LootTables\)|LootTables\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(keyExpressionBody) }
            .map { (label, _) -> "isLootTableResourceKeyExpression contains $label" }

        assertTrue(
            keyExpressionOffenders.isEmpty(),
            "Loot table key-expression detection must use API/evidence structure, not owner-name suffix inference: $keyExpressionOffenders"
        )

        val resourceKeyCollectorsStart = source.indexOf("private fun collectResourceKeyLootTableFieldOwners")
        assertTrue(resourceKeyCollectorsStart >= 0, "collectResourceKeyLootTableFieldOwners is missing")
        val resourceKeyCollectorsEnd = source.indexOf("private fun collectMapCodecConstantOwners", resourceKeyCollectorsStart + 1).let {
            if (it < 0) source.length else it
        }
        val resourceKeyCollectorsBody = source.substring(resourceKeyCollectorsStart, resourceKeyCollectorsEnd)
        val resourceKeyCollectorOffenders = listOf(
            "java file-name owner fallback" to Regex("""classNameOfJavaSource\(source\)\s*\?:\s*javaFile\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(resourceKeyCollectorsBody) }
            .map { (label, _) -> "loot table ResourceKey collectors contain $label" }

        assertTrue(
            resourceKeyCollectorOffenders.isEmpty(),
            "Loot table ResourceKey collectors must use source-declared Java owners, not Java file-name fallback inference: $resourceKeyCollectorOffenders"
        )
    }

    @Test
    fun `registry backed item stack predicate migrations do not infer owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun collectLegacyItemStackPredicateOverrideMethods")
        assertTrue(start >= 0, "collectLegacyItemStackPredicateOverrideMethods is missing")
        val end = source.indexOf("private fun migrateLegacyRegistryBackedItemStackPredicateOverrides", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner fallback" to Regex("""classNameOfJavaSource\(source\)\s*\?:\s*javaFile\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "collectLegacyItemStackPredicateOverrideMethods contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Registry-backed item-stack predicate migrations must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `legacy banner component migrations do not infer owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun collectLegacyBannerPatternLayerFactories")
        assertTrue(start >= 0, "collectLegacyBannerPatternLayerFactories is missing")
        val end = source.indexOf("private fun migrateLegacyBannerPatternLayerSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner fallback" to Regex("""classNameOfJavaSource\(source\)\s*\?:\s*javaFile\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "legacy banner component collectors contain $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy banner component migrations must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `deferred register and holder collectors do not infer owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun registerMissingDeferredRegisterFields")
        assertTrue(start >= 0, "registerMissingDeferredRegisterFields is missing")
        val end = source.indexOf("private fun migrateRecordComponentFieldAccessSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "main class file-name owner fallback" to Regex("""classNameOfJavaSource\(mainClass\.readText\(\)\)\s*\?:\s*mainClass\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "java file-name owner fallback" to Regex("""classNameOfJavaSource\(source\)\s*\?:\s*javaFile\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "deferred register/holder collector contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "DeferredRegister and DeferredHolder collectors must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `placement modifier type registry collectors do not infer owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun collectPlacementModifierTypeRegistryFields")
        assertTrue(start >= 0, "collectPlacementModifierTypeRegistryFields is missing")
        val end = source.indexOf("private fun migrateBuiltInPlacementModifierTypeRegistrations", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner fallback" to Regex("""classNameOfJavaSource\(source\)\s*\?:\s*javaFile\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "placement modifier type collector contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Placement modifier type registry collectors must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `worldgen region accessor migrations do not infer mixin owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun legacyWorldGenRegionStructureManagerAccessor")
        assertTrue(start >= 0, "legacyWorldGenRegionStructureManagerAccessor is missing")
        val end = source.indexOf("private fun rewriteWorldGenRegionStructureManagerAccessorCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner fallback" to Regex("""javaTopLevelTypeName\(source\)\s*\?:\s*file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "WorldGenRegion accessor collector contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "WorldGenRegion accessor migrations must use source-declared mixin owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `missing mapping alias migrations do not infer owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateMissingMappingsAliases")
        assertTrue(start >= 0, "migrateMissingMappingsAliases is missing")
        val end = source.indexOf("private fun extractMissingMappingAliasRules", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner" to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "missing mapping alias migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "MissingMapping alias migrations must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `base packet migrations do not infer owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateBasePacketPayloads")
        assertTrue(start >= 0, "migrateBasePacketPayloads is missing")
        val end = source.indexOf("private fun rewritePacketRelayCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner" to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "java file-name candidate filter" to Regex("""fileName\.toString\(\)\.removeSuffix\("\.java"\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "BasePacket migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "BasePacket migrations must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `legacy advancement trigger migration does not depend on fixed project class names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateAdvancementCriterionTriggers")
        assertTrue(start >= 0, "migrateAdvancementCriterionTriggers is missing")
        val end = source.indexOf("private data class LegacyCriterionRegistration", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed trigger file name" to "AdvancementTrigger.java",
            "fixed registrar file name" to "ExtraEventsRegister.java",
            "fixed registrar call" to "ExtraEventsRegister.register(",
            "fixed trigger constructor" to "new AdvancementTrigger"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "legacy advancement trigger migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy advancement trigger migrations must use source-declared trigger and registrar owners, not fixed project names: $offenders"
        )
    }

    @Test
    fun `legacy capability facade attachment migration does not depend on dirtiness class names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyCapabilityFacadeToAttachment")
        assertTrue(start >= 0, "migrateLegacyCapabilityFacadeToAttachment is missing")
        val end = source.indexOf("private fun modIdReferenceForGeneratedClass", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed DirtinessCapability name" to "DirtinessCapability",
            "fixed DirtinessData name" to "DirtinessData",
            "fixed DirtinessAttachment name" to "DirtinessAttachment",
            "dirtiness rule id" to "struct-dirtiness",
            "java file-name semantic filter" to ".fileName.toString() ==",
            "placeholder attachment id fallback" to "ifBlank { \"data\" }"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "legacy capability facade migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy capability facade attachment migrations must use source-declared facade/data owners, not dirtiness-specific names: $offenders"
        )
    }

    @Test
    fun `legacy capability facade lookup migration does not depend on dirtiness class names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyCapabilityFacadeLookups")
        assertTrue(start >= 0, "migrateLegacyCapabilityFacadeLookups is missing")
        val end = source.indexOf("private fun migrateLegacyCapabilityFacadeToAttachment", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed DirtinessCapability name" to "DirtinessCapability",
            "fixed DirtinessData name" to "DirtinessData",
            "fixed DirtinessAttachment name" to "DirtinessAttachment",
            "old direct dirtiness getCapability rewrite" to "getCapability(DirtinessCapability.DIRTINESS",
            "old dirtiness getOrNull block rewrite" to "DirtinessData data = DirtinessCapability.getOrNull",
            "dirtiness rule id" to "struct-dirtiness"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "legacy capability facade lookup migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy capability facade lookup migrations must use source-declared facade/data owners, not dirtiness-specific names: $offenders"
        )
    }

    @Test
    fun `nested simplechannel migration does not depend on fixed networking class names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateKnownNestedSimpleChannelNetworking")
        assertTrue(start >= 0, "migrateKnownNestedSimpleChannelNetworking is missing")
        val end = source.indexOf("private data class ModAccess", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed CustomFluidNetworking name" to "CustomFluidNetworking",
            "fixed custom fluid packet name" to "SyncCustomFluids",
            "fixed DirtinessNetworking name" to "DirtinessNetworking",
            "fixed dirtiness packet name" to "SyncDirtiness",
            "fixed dirtiness capability call" to "DirtinessCapability",
            "fixed networking file-name branch" to "fileName ==",
            "fixed whole-file networking template" to "PayloadSource(packageName"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "nested SimpleChannel migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Nested SimpleChannel migrations must use source-declared packet/register/send structure, not fixed networking class templates: $offenders"
        )
    }

    @Test
    fun `build mod id helpers do not scan arbitrary constant references`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val helpers = listOf(
            "inferModIdExpression",
            "detectWorldCarverModIdExpression"
        )
        val forbidden = listOf(
            "prefix owner namespace shortcut" to ".prefix\\(",
            "arbitrary MODID constant alternation" to "(MOD_ID|MODID|ID)",
            "arbitrary ID constant alternation" to "(ID|MOD_ID|MODID)"
        )

        val offenders = helpers.flatMap { helper ->
            val start = source.indexOf("private fun $helper")
            if (start < 0) return@flatMap listOf("$helper is missing")
            val end = source.indexOf("\n    private fun ", start + 1).let { if (it < 0) source.length else it }
            val body = source.substring(start, end)
            forbidden
                .filter { (_, marker) -> body.contains(marker) }
                .map { (label, _) -> "$helper contains $label" }
        }

        assertTrue(
            offenders.isEmpty(),
            "Build-system mod id helpers must use @Mod, subscriber modid, or project metadata, not arbitrary constant references: $offenders"
        )
    }

    @Test
    fun `text resource namespace helpers do not infer mod id from data directories`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val helper = "projectMetadataNamespaces"
        val start = source.indexOf("private fun $helper")
        assertTrue(start >= 0, "$helper is missing")
        val end = source.indexOf("\n    private fun ", start + 1).let { if (it < 0) source.length else it }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "data directory scan" to ".resolve(\"data\")",
            "directory namespace listing" to "Files.list"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "$helper contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Resource namespace helpers must use mod metadata or source expressions, not data directory names: $offenders"
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
