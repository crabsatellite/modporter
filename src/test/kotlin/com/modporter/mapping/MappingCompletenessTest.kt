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
    fun `auto pipeline detection does not fall back to registered defaults`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val registrySource = projectRoot
            .resolve("src/main/kotlin/com/modporter/registry/PipelineRegistry.kt")
            .readText()
        val cliSource = projectRoot
            .resolve("src/main/kotlin/com/modporter/cli/Main.kt")
            .readText()
        val offenders = listOf(
            "empty Java source defaults to first registered pipeline" to registrySource.contains("pipelines.values.firstOrNull()"),
            "auto resolver falls back to forge2neo" to Regex("""PipelineRegistry\.detect\(projectDir\)\s*\?:\s*PipelineRegistry\.get\("forge2neo"\)""")
                .containsMatchIn(cliSource)
        )
            .filter { (_, present) -> present }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Auto pipeline detection must fail closed without source evidence instead of selecting a default pipeline: $offenders"
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
            "ACEffectRegistry",
            "ModEffects.NOURISHMENT",
            "ModEffects.COMFORT",
            "Sakura",
            "sakura",
            "HotBath",
            "hotbath",
            "Farmers Delight",
            "farmersdelight",
            "descriptive_item",
            "DescriptiveItem",
            "bath_herb",
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
    fun `nitrogen fuel migrations use typed API shape evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val structuralSource = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val resourceSource = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()

        fun functionBody(source: String, startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            assertTrue(start >= 0, "$startMarker is missing")
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val structuralBody = functionBody(
            structuralSource,
            "private fun migrateNitrogenFuelCategorySource",
            "private fun collectNitrogenFuelTextureFields"
        )
        val resourceBody = functionBody(
            resourceSource,
            "private fun collectNitrogenFuelSpriteSpecs",
            "private fun collectJavaStringConstants"
        )
        val forbiddenMarkers = listOf(
            """source.contains("com.aetherteam.nitrogen.integration.")""",
            """source.contains("categories.fuel.AbstractFuelCategory")""",
            """result.contains("com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory")""",
            """result.contains("com.aetherteam.nitrogen.integration.rei.categories.fuel.AbstractFuelCategory")""",
            """result.contains("new AbstractFuelCategory(")""",
            """result.contains("getTexture()")"""
        )
        val offenders = listOf(
            "structural" to structuralBody,
            "resource" to resourceBody
        ).flatMap { (label, body) ->
            forbiddenMarkers
                .filter { marker -> body.contains(marker) }
                .map { marker -> "$label Nitrogen fuel migration uses broad marker $marker" }
        }

        assertTrue(
            structuralBody.contains("containsNitrogenFuelCategoryApiUse(source, \"jei\")") &&
                structuralBody.contains("containsNitrogenFuelCategoryApiUse(source, \"rei\")") &&
                structuralBody.contains("containsNitrogenFuelGetTextureOverride(result)") &&
                structuralBody.contains("containsNitrogenFuelCategoryConstructorCall(result)") &&
                structuralSource.contains("maskJavaComments(source)") &&
                structuralSource.contains("maskJavaCommentsAndLiterals(source)"),
            "Structural Nitrogen fuel migration must use typed API-shape evidence and comment masking"
        )
        assertTrue(
            resourceBody.contains("commentMaskedSources") &&
                resourceBody.contains("val executableMaskedSources = sourceTexts.mapValues") &&
                resourceBody.contains("containsNitrogenFuelCategoryApiUse(source)") &&
                resourceBody.contains("val executableSource = executableMaskedSources.getValue(javaFile)") &&
                resourceBody.contains("val executableSegment = executableSource.substring(match.range.first, match.range.last + 1)") &&
                resourceBody.contains("executableSegment.contains(\"ResourceLocation\")") &&
                resourceBody.contains("executableSegment.contains(\"fromNamespaceAndPath\")") &&
                resourceBody.contains("executableSegment.contains(\"new ResourceLocation\")") &&
                resourceSource.contains("maskJavaComments(source)") &&
                resourceSource.contains("maskJavaCommentsAndLiterals(source)"),
            "Resource Nitrogen fuel sprite migration must use typed API-shape evidence and executable texture declarations"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nitrogen fuel migrations must not infer API ownership from broad file markers: $offenders"
        )
    }

    @Test
    fun `nitrogen sync call migration does not borrow owner entity ids for receiver calls`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteNitrogenSyncCalls")
        assertTrue(start >= 0, "rewriteNitrogenSyncCalls is missing")
        val end = source.indexOf("private fun stripDimensionAccessor", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "receiver owner elvis fallback" to "receiverEntityId ?: ownerEntityId",
            "unknown direction owner fallback" to Regex("""else\s*->\s*(?:receiverEntityId|ownerEntityId)""")
        )
        val offenders = forbidden
            .filter { (_, marker) ->
                when (marker) {
                    is String -> body.contains(marker)
                    is Regex -> marker.containsMatchIn(body)
                    else -> false
                }
            }
            .map { (label, _) -> "Nitrogen sync migration contains $label" }

        assertTrue(
            body.contains("nitrogenSyncIdExpression(args, receiverName, ownerEntityId, receiverEntityId)") &&
                body.contains("direction.contains(\"Direction.CLIENT\")") &&
                body.contains("nitrogenCallSiteEntityId(receiverName, ownerEntityId, receiverEntityId)") &&
                body.contains("receiverName.isNotBlank() && receiverName != \"this\"") &&
                body.contains("return receiverEntityId") &&
                body.contains("return ownerEntityId") &&
                body.contains("else -> null"),
            "Nitrogen sync migration must bind entity ids to the actual call receiver and fail closed for unknown directions"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nitrogen sync migration must not fall back from unresolved receiver owners to the enclosing owner: $offenders"
        )
    }

    @Test
    fun `nitrogen sync receiver owner accessors use Java visible FQN resolution`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun javaNonStaticWildcardImports")
        assertTrue(start >= 0, "javaNonStaticWildcardImports is missing")
        val end = source.indexOf("private fun rewriteNitrogenSyncCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val receiverStart = body.indexOf("private fun inferNitrogenReceiverOwnerEntityIds")
        assertTrue(receiverStart >= 0, "inferNitrogenReceiverOwnerEntityIds is missing")
        val receiverBody = body.substring(receiverStart)
        val forbidden = listOf(
            "simple name accessor map" to "typeName to accessors.single()",
            "simple name receiver type alternation" to "val typeAlternation = ownerAccessors.keys"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "Nitrogen sync receiver owner resolution contains $label" }
            .plus(
                if (Regex("""findAll\(source\)""").containsMatchIn(receiverBody)) {
                    listOf("Nitrogen sync receiver owner resolution contains raw source receiver scan")
                } else {
                    emptyList()
                }
            )

        assertTrue(
            body.contains("val fqn = if (packageName.isBlank()) typeName else \"\$packageName.\$typeName\"") &&
                body.contains("val knownTypes = ownerAccessors.keys") &&
                body.contains("javaNonStaticWildcardImports(code)") &&
                body.contains("resolveKnownJavaTypeReference(rawType, packageName, imports, wildcardImports, knownTypes)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(text)") &&
                body.contains("return candidates.filter { it in knownTypes }.distinct().singleOrNull()"),
            "Nitrogen sync receiver owner resolution must bind accessors by Java-visible FQN, not project-global simple names"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nitrogen sync receiver owner resolution must not use simple-name or raw-source fallback matching: $offenders"
        )
    }

    @Test
    fun `nitrogen synchable detection requires resolved API owners`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun hasNitrogenAttachmentSyncPacketMethod")
        assertTrue(start >= 0, "hasNitrogenAttachmentSyncPacketMethod is missing")
        val end = source.indexOf("private fun extractJavaTopLevelSuperTypes", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "raw INBTSynchable source marker" to """source.contains("INBTSynchable")""",
            "simple-name INBTSynchable fallback" to """substringAfterLast('.') == "INBTSynchable"""",
            "unbounded supertype resolver in synchable closure" to "resolveJavaTypeReference(superType, type.packageName, type.imports)"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "Nitrogen synchable detection contains $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("extractJavaTopLevelSuperTypes(executableCode)") &&
                body.contains("resolveKnownJavaTypeReference(superType, packageName, imports, wildcardImports, nitrogenSynchableTypes)") &&
                body.contains("wildcardImports = javaNonStaticWildcardImports(code)") &&
                body.contains("resolveKnownJavaTypeReference(") &&
                body.contains("resolved in synchableTypes"),
            "Nitrogen synchable detection must resolve Java-visible API owners instead of trusting simple names or raw markers"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nitrogen synchable detection must not infer API identity from local simple names: $offenders"
        )
    }

    @Test
    fun `nitrogen attachment suppliers require resolved neoforge attachment type`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun collectNitrogenAttachmentSuppliers")
        assertTrue(start >= 0, "collectNitrogenAttachmentSuppliers is missing")
        val end = source.indexOf("private fun ensureNitrogenSyncPacketAttachmentGetter", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "raw AttachmentType marker" to """source.contains("AttachmentType<")""",
            "raw source supplier scan" to Regex("""findAll\(source\)"""),
            "unresolved attachment supplier add" to Regex("""fun\s+addSupplier\s*\(\s*typeName:\s*String,\s*fieldName:\s*String""")
        )
        val offenders = forbidden
            .filter { (_, marker) ->
                when (marker) {
                    is String -> body.contains(marker)
                    is Regex -> marker.containsMatchIn(body)
                    else -> false
                }
            }
            .map { (label, _) -> "Nitrogen attachment supplier collection contains $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("if (!executableCode.contains(\"AttachmentType<\")) continue") &&
                body.contains("val wildcardImports = javaNonStaticWildcardImports(code)") &&
                body.contains("fun addSupplier(attachmentTypeRef: String, typeName: String, fieldName: String)") &&
                body.contains("isNeoForgeAttachmentTypeReference(attachmentTypeRef, packageName, imports, wildcardImports)") &&
                body.contains(".findAll(executableCode)") &&
                source.contains("private fun isNeoForgeAttachmentTypeReference("),
            "Nitrogen attachment supplier collection must prove AttachmentType resolves to NeoForge API"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nitrogen attachment supplier collection must not trust raw source or unqualified AttachmentType names: $offenders"
        )
    }

    @Test
    fun `backpack container API migration binds inventory wrapper to slot constructor`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateQuarkBackpackInventoryWrappers")
        assertTrue(start >= 0, "migrateQuarkBackpackInventoryWrappers is missing")
        val end = source.indexOf("private fun hasNitrogenAttachmentSyncPacketMethod", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "file-level BackpackSlot guard" to """original.contains("new BackpackSlot(")""",
            "file-level BackpackSlot negated guard" to """!original.contains("new BackpackSlot(")""",
            "raw InventoryIIH import prefilter" to """original.contains("org.violetmoon.quark.base.util.InventoryIIH")""",
            "raw wrapper declaration prefilter" to "wrapperDeclaration.containsMatchIn(original)",
            "raw BackpackSlot consumer scan" to ".containsMatchIn(original)",
            "raw source wrapper replacement" to "wrapperDeclaration.replace(original)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "backpack container migration contains $label" }

        assertTrue(
            body.contains("Regex.escape(wrapperName)") &&
                body.contains("BackpackSlot") &&
                body.contains("backpackSlotConsumesWrapper") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(original)") &&
                body.contains("wrapperDeclaration.findAll(executableCode)") &&
                body.contains("containsMatchIn(executableCode)") &&
                body.contains("modified.substring(0, range.first)"),
            "Backpack container migration must prove the declared wrapper variable is passed to BackpackSlot"
        )
        assertTrue(
            offenders.isEmpty(),
            "Backpack container migration must use variable-level constructor evidence, not whole-file BackpackSlot presence: $offenders"
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
    fun `production cleanup rules do not depend on legacy forge2neo comment markers`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val forbidden = listOf(
            "legacy forge2neo comment marker" to "[forge2neo]",
            "split TODO regex bypass" to "T(?:ODO)"
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
            "Production cleanup must be structural and must not depend on legacy tool comment markers: $offenders"
        )
    }

    @Test
    fun `structural migrations do not synthesize placeholder names for unclear source structure`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val forbiddenMarkers = listOf(
            """ifBlank { "CUSTOM" }""",
            """ifBlank { "custom" }""",
            "?: \"output\""
        )
        val offenders = forbiddenMarkers
            .filter { marker -> source.contains(marker) }

        assertTrue(
            source.contains("private fun enumExtensionNamePart(value: String): String?") &&
                source.contains("private fun prefixedEnumExtensionName(modId: String, legacyName: String): String?") &&
                source.contains("private fun legacyRaritySerializedName(modId: String, legacyName: String): String?") &&
                source.contains("private fun enumExtensionNameParameter(modId: String, legacyName: String): String?"),
            "Enum extension naming helpers must fail closed when source literals cannot be normalized"
        )
        assertTrue(
            offenders.isEmpty(),
            "Production structural migrations must not invent placeholder enum/resource/provider names: $offenders"
        )
    }

    @Test
    fun `bucket pickup call site migration requires source player evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyBucketPickupCallSites")
        assertTrue(start >= 0, "migrateLegacyBucketPickupCallSites is missing")
        val end = source.indexOf("private fun migrateLegacyBucketPickupLocalDeclarationBody", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "missing enclosing method falls back to null player" to "lastOrNull() ?: return \"null\"",
            "out-of-scope method falls back to null player" to "if (closeBrace <= offset) return \"null\"",
            "missing Player parameter falls back to nullable null" to "singlePlayerParameterName(method.groupValues[1]) ?: \"null\"",
            "missing Player parameter in direct call migration falls back to nullable null" to "singlePlayerParameterName(match.groupValues[1]) ?: \"null\""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
                body.contains("private fun bucketPickupPlayerArgumentForEnclosingMethod(source: String, offset: Int): String?") &&
                body.contains("lastOrNull() ?: return null") &&
                body.contains("if (closeBrace <= offset) return null") &&
                body.contains("if (playerArgument == null)") &&
                body.contains("return singlePlayerParameterName(method.groupValues[1])"),
            "BucketPickup call-site migration must rewrite only when the current Java method exposes a Player or ServerPlayer parameter"
        )
        assertTrue(
            offenders.isEmpty(),
            "BucketPickup call-site migration must not use nullable null as a fallback for missing source Player evidence: $offenders"
        )
    }

    @Test
    fun `level summary nullable migration uses enclosing method structure`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun isNullableLevelSummaryMethod")
        assertTrue(start >= 0, "isNullableLevelSummaryMethod is missing")
        val end = source.indexOf("private fun migrateLegacyBindingCurseChecks", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "file-prefix method scan" to "source.substring(0, offset)",
            "last previous method fallback" to "lastOrNull()"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("javaMethodRanges(source).firstOrNull { offset in it.range }") &&
                body.contains("LevelSummary") &&
                body.contains("@Nullable"),
            "LevelSummary nullable migration must inspect the enclosing Java method, not a previous method in the file"
        )
        assertTrue(
            offenders.isEmpty(),
            "LevelSummary nullable migration must not use file-prefix or previous-method fallback: $offenders"
        )
    }

    @Test
    fun `cumulus panorama migration uses resolved mod id expression namespace`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun cumulusPanoramaExpression")
        assertTrue(start >= 0, "cumulusPanoramaExpression is missing")
        val end = source.indexOf("private fun resolveLiteralModIdExpression", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "project metadata mod id fallback" to "projectMetadataModId(projectDir)",
            "single asset namespace fallback" to "singleAssetNamespace(projectDir)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("resolveCumulusModIdNamespace(projectDir, source, modIdExpression)"),
            "Cumulus panorama migration must validate resources against the source mod id expression namespace"
        )
        assertTrue(
            offenders.isEmpty(),
            "Cumulus panorama migration must not borrow metadata or single-asset namespaces: $offenders"
        )
    }

    @Test
    fun `cumulus menu definition migration uses executable registry evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCumulusMenuDefinitionSource")
        assertTrue(start >= 0, "migrateCumulusMenuDefinitionSource is missing")
        val end = source.indexOf("private fun migrateCumulusMenuConstructorArgs", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw Cumulus registry key prefilter" to "source.contains(\"Cumulus.MENU_REGISTRY_KEY\")",
            "raw DeferredRegister prefilter" to "source.contains(\"DeferredRegister<Menu>\")",
            "raw Cumulus menu import prefilter" to "source.contains(\"com.aetherteam.cumulus.api.Menu\")",
            "raw register declaration lookup" to "registerPattern.find(source)",
            "raw menu parenthesis matching" to "findMatchingParen(source, openParen)",
            "raw legacy background marker" to "source.contains(\"Menu.Background\")"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "cumulus menu definition migration contains $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("registerPattern.find(executableCode)") &&
                body.contains("findCumulusMenuRegistrations(source, executableCode, registerField)") &&
                body.contains("val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)") &&
                body.contains("findMatchingParen(executableCode, openParen)"),
            "Cumulus menu definition migration must prove registry declarations and registrations from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Cumulus menu definition migration must not treat comments or strings as registry evidence: $offenders"
        )
    }

    @Test
    fun `structural metadata mod id helper requires unique metadata evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun projectMetadataModId")
        assertTrue(start >= 0, "projectMetadataModId is missing")
        val end = source.indexOf("private fun removeUnusedCumulusBooleanSupplierFields", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)

        assertTrue(
            body.contains("val candidates = linkedSetOf<String>()") &&
                body.contains("return candidates.singleOrNull()"),
            "Structural metadata mod id detection must accept only a unique declared metadata mod id"
        )
        assertTrue(
            !body.contains(".firstOrNull()") && !body.contains(".find("),
            "Structural metadata mod id detection must not choose the first metadata declaration"
        )
    }

    @Test
    fun `client only build detection ignores comments and strings`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()

        fun functionBody(startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            assertTrue(start >= 0, "$startMarker is missing")
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val sourceClassifier = functionBody(
            "private fun isClientOnlyJavaSource",
            "private fun guardClientOnlyListenerReferences"
        )
        val methodClassifier = functionBody(
            "private fun javaMethodContainsClientOnlyApis",
            "private fun isWithinDistClientGuard"
        )
        val subscriberClassifier = functionBody(
            "private fun classBodyContainsClientOnlyApis",
            "private fun addDistClientValueToEventBusSubscriberAnnotation"
        )
        val offenders = listOf(
            "source classifier raw client package scan" to (sourceClassifier to """source.contains("net.minecraft.client.")"""),
            "source classifier raw NeoForge client event scan" to (sourceClassifier to """source.contains("net.neoforged.neoforge.client.event.")"""),
            "method classifier raw client package scan" to (methodClassifier to """methodSource.contains("net.minecraft.client.")"""),
            "method classifier raw NeoForge client event scan" to (methodClassifier to """methodSource.contains("net.neoforged.neoforge.client.event.")"""),
            "method classifier raw import scan" to (methodClassifier to """).findAll(classSource)"""),
            "method classifier raw event name scan" to (methodClassifier to """).containsMatchIn(methodSource)"""),
            "subscriber classifier raw client package scan" to (subscriberClassifier to """body.contains("net.minecraft.client.")"""),
            "subscriber classifier raw NeoForge client event scan" to (subscriberClassifier to """body.contains("net.neoforged.neoforge.client.event.")"""),
            "subscriber classifier raw event name scan" to (subscriberClassifier to """).containsMatchIn(body)""")
        )
            .filter { (_, pair) -> pair.first.contains(pair.second) }
            .map { (label, _) -> "client-only detection contains $label" }

        assertTrue(
            sourceClassifier.contains("val code = maskJavaCommentsAndLiterals(source)") &&
                methodClassifier.contains("val methodCode = maskJavaCommentsAndLiterals(methodSource)") &&
                methodClassifier.contains("val classCode = maskJavaCommentsAndLiterals(classSource)") &&
                subscriberClassifier.contains("val code = maskJavaCommentsAndLiterals(body)"),
            "Client-only build detection must classify executable Java code, not comments or string literals"
        )
        assertTrue(
            offenders.isEmpty(),
            "Client-only build detection must not use raw source/body/method strings as evidence: $offenders"
        )
    }

    @Test
    fun `creative selected tab reflection migration uses method local evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCreativeModeInventorySelectedTabReflection")
        assertTrue(start >= 0, "migrateCreativeModeInventorySelectedTabReflection is missing")
        val end = source.indexOf("private fun migrateEntityRenderersAddLayersReflection", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "whole-file selectedTab reflection scan" to """original.contains("CreativeModeInventoryScreen.class.getDeclaredField(\"selectedTab\")")""",
            "whole-file setAccessible scan" to """original.contains("setAccessible(true)")""",
            "whole-file field get scan" to """original.contains("field.get(null)")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "creative selectedTab migration contains $label" }

        assertTrue(
            body.contains("val methodSource = original.substring(methodMatch.range.first, closeBrace + 1)") &&
                body.contains("containsCreativeSelectedTabReflection(methodSource)") &&
                body.contains("Field\\s+([A-Za-z_$][\\w$]*)") &&
                body.contains("Regex.escape(fieldName)") &&
                source.contains("private fun maskJavaComments(source: String)"),
            "Creative selectedTab reflection migration must prove reflection inside getSelectedTab(), not from whole-file markers"
        )
        assertTrue(
            offenders.isEmpty(),
            "Creative selectedTab reflection migration must not use whole-file reflection markers: $offenders"
        )
    }

    @Test
    fun `add layers renderer reflection migration uses method local evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateEntityRenderersAddLayersReflection")
        assertTrue(start >= 0, "migrateEntityRenderersAddLayersReflection is missing")
        val end = source.indexOf("private fun migrateObfuscationReflectionMethodHandles", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "whole-file AddLayers renderers reflection scan" to """original.contains("EntityRenderersEvent.AddLayers.class.getDeclaredField(\"renderers\")")""",
            "whole-file setAccessible scan" to """original.contains(".setAccessible(true)")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "add-layers renderer migration contains $label" }

        assertTrue(
            body.contains("val methodSource = original.substring(methodMatch.range.first, closeBrace + 1)") &&
                body.contains("containsEntityRenderersAddLayersReflection(methodSource, eventParam)") &&
                body.contains("private fun containsEntityRenderersAddLayersReflection(methodSource: String, eventParam: String)") &&
                body.contains("val code = maskJavaComments(methodSource)") &&
                body.contains("Regex.escape(fieldName)") &&
                body.contains("Regex.escape(eventParam)"),
            "AddLayers renderer reflection migration must prove declared-field, setAccessible, and get(event) inside the AddLayers handler"
        )
        assertTrue(
            offenders.isEmpty(),
            "AddLayers renderer reflection migration must not use whole-file reflection markers: $offenders"
        )
    }

    @Test
    fun `obfuscation method handle migration binds source declared fields`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateObfuscationReflectionMethodHandles")
        assertTrue(start >= 0, "migrateObfuscationReflectionMethodHandles is missing")
        val end = source.indexOf("private fun restoreNonItemStackGetTagCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed LivingEntity findMethod marker" to """modified.contains("ObfuscationReflectionHelper.findMethod(LivingEntity.class, \"m_5592_\"")""",
            "fixed LivingEntity handle field" to "handle_LivingEntity_getDeathSound",
            "fixed HangingEntity findMethod marker" to """modified.contains("ObfuscationReflectionHelper.findMethod(HangingEntity.class, \"m_6022_\"")""",
            "fixed HangingEntity handle field" to "handle_HangingEntity_setDirection"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "obfuscation method-handle migration contains $label" }

        assertTrue(
            body.contains("collectObfuscationMethodHandleBindings(original)") &&
                body.contains("binding.handleFieldName") &&
                body.contains("methodHandleFieldsForUnreflectedMethod(code, methodFieldName, declaredHandleFields)") &&
                body.contains("val code = maskJavaComments(source)") &&
                body.contains("javaMethodHeaderDeclaresParameter(enclosingMethod, \"HangingEntity\", entityArg)") &&
                body.contains("javaMethodHeaderDeclaresParameter(enclosingMethod, \"Direction\", directionArg)"),
            "Obfuscation method-handle migration must bind findMethod, unreflect, handle field, and invoke calls from source structure"
        )
        assertTrue(
            offenders.isEmpty(),
            "Obfuscation method-handle migration must not depend on fixed sample field names or whole-file method markers: $offenders"
        )
    }

    @Test
    fun `class for name isInstance migration uses try local executable evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteClassForNameIsInstanceChecks")
        assertTrue(start >= 0, "rewriteClassForNameIsInstanceChecks is missing")
        val end = source.indexOf("private fun ensureRuntimeInstanceHelper", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source Class.forName assignment" to """).find(source)""",
            "raw source isInstance return" to """).find(source)""",
            "raw source try lookup" to "source.lastIndexOf(\"try\""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "Class.forName isInstance migration contains $label" }

        assertTrue(
            body.contains("val code = maskJavaComments(source)") &&
                body.contains("findEnclosingTryStartForStatement(code, forNameAssignment.range.first)") &&
                body.contains("val tryBody = code.substring(openBrace + 1, closeBrace)") &&
                body.contains("returnPattern.find(tryBody, searchFrom)") &&
                body.contains("Regex.escape(classVariable)") &&
                body.contains("findFollowingCatchBlockEnd(code, closeBrace + 1)"),
            "Class.forName isInstance migration must prove assignment and return inside the same executable try/catch"
        )
        assertTrue(
            offenders.isEmpty(),
            "Class.forName isInstance migration must not use raw whole-file source as migration evidence: $offenders"
        )
    }

    @Test
    fun `season helper reflection migration uses masked method local evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteSeasonStateReflectionWithoutReflection")
        assertTrue(start >= 0, "rewriteSeasonStateReflectionWithoutReflection is missing")
        val end = source.indexOf("private fun removeRedundantClassForNameOnClassObjects", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source getSeasonState scan" to """source.contains("getSeasonState")""",
            "raw source getSubSeason scan" to """source.contains("getSubSeason")""",
            "raw source Class.forName scan" to """source.contains("Class.forName(")""",
            "raw method body Class.forName scan" to """source.substring(openBrace + 1, closeBrace).contains("Class.forName(")""",
            "raw resolve body extraction" to """val resolveBody = source.substring(resolveOpenBrace + 1, resolveCloseBrace)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "SeasonHelper reflection migration contains $label" }

        assertTrue(
            body.contains("val code = maskJavaComments(source)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("findAll(code)") &&
                body.contains("val methodBody = code.substring(openBrace + 1, closeBrace)") &&
                body.contains("val executableMethodBody = executableCode.substring(openBrace + 1, closeBrace)") &&
                body.contains("val resolveBody = code.substring(resolveOpenBrace + 1, resolveCloseBrace)") &&
                body.contains("val executableResolveBody = executableCode.substring(resolveOpenBrace + 1, resolveCloseBrace)") &&
                body.contains("fun MatchResult.hasExecutableClassForNameGetMethod()") &&
                body.contains("val executableSegment = executableResolveBody.substring(range.first, range.last + 1)") &&
                body.contains("executableSegment.contains(\"Class.forName(\")") &&
                body.contains("executableSegment.contains(\".getMethod(\")") &&
                body.contains("firstOrNull { it.hasExecutableClassForNameGetMethod() }"),
            "SeasonHelper reflection migration must capture API names from comment-masked code but prove reflection calls from executable method-local code"
        )
        assertTrue(
            offenders.isEmpty(),
            "SeasonHelper reflection migration must not use raw source or raw method text as evidence: $offenders"
        )
    }

    @Test
    fun `redundant class for name removal uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun removeRedundantClassForNameOnClassObjects")
        assertTrue(start >= 0, "removeRedundantClassForNameOnClassObjects is missing")
        val end = source.indexOf("private fun rewriteClassForNamePresenceChecksWithoutReflection", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw regex replacement" to ".replace(source, \"\")",
            "raw source match scan" to "pattern.findAll(source)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "Redundant Class.forName removal contains $label" }

        assertTrue(
            body.contains("val code = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val matches = pattern.findAll(code).toList()") &&
                body.contains("for (match in matches.asReversed())") &&
                body.contains("result.substring(0, match.range.first)") &&
                body.contains("result.substring(match.range.last + 1)"),
            "Redundant Class.forName removal must delete only executable Java statements"
        )
        assertTrue(
            offenders.isEmpty(),
            "Redundant Class.forName removal must not delete raw source text: $offenders"
        )
    }

    @Test
    fun `class for name presence migration uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteClassForNamePresenceChecksWithoutReflection")
        assertTrue(start >= 0, "rewriteClassForNamePresenceChecksWithoutReflection is missing")
        val end = source.indexOf("private fun ensureClassResourcePresenceHelper", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source replacement" to "pattern.replace(source)",
            "raw source match scan" to "pattern.findAll(source)",
            "raw source no-op comparison" to "rewritten == source"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "Class.forName presence migration contains $label" }

        assertTrue(
            body.contains("val code = maskJavaComments(source)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("pattern.findAll(code)") &&
                body.contains("executablePattern.matchEntire") &&
                body.contains("executableCode.substring(match.range.first, match.range.last + 1)") &&
                body.contains("matches.asReversed()"),
            "Class.forName presence migration must prove the try/catch probe from executable Java code before rewriting"
        )
        assertTrue(
            offenders.isEmpty(),
            "Class.forName presence migration must not use raw whole-file source as migration evidence: $offenders"
        )
    }

    @Test
    fun `class for name enum valueOf migration uses executable try evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteClassForNameEnumValueOf")
        assertTrue(start >= 0, "rewriteClassForNameEnumValueOf is missing")
        val end = source.indexOf("private fun rewriteDeferredHolderReflectionCollectors", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source enum flow scan" to "tryPattern.findAll(source)",
            "raw source replacement match list" to "tryPattern.findAll(source).toList()"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "Class.forName enum valueOf migration contains $label" }

        assertTrue(
            body.contains("val code = maskJavaComments(source)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val matches = tryPattern.findAll(code)") &&
                body.contains("executablePattern.matchEntire") &&
                body.contains("executableCode.substring(match.range.first, match.range.last + 1)") &&
                body.contains("if (matches.isEmpty()) return source to emptyList()") &&
                body.contains("for (match in matches.asReversed())"),
            "Class.forName enum valueOf migration must prove the try/Class.forName/Enum.valueOf flow from executable Java code"
        )
        assertTrue(
            offenders.isEmpty(),
            "Class.forName enum valueOf migration must not use raw source text as migration evidence: $offenders"
        )
    }

    @Test
    fun `string API verification migration uses executable method body evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteStringApiVerificationWithoutReflection")
        assertTrue(start >= 0, "rewriteStringApiVerificationWithoutReflection is missing")
        val end = source.indexOf("private fun rewriteClassForNameEnumValueOf", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw method lookup" to ").find(source) ?: return source",
            "raw brace lookup" to "source.indexOf('{', methodMatch.range.first)",
            "raw brace match" to "findMatchingBrace(source, openBrace)",
            "raw method body extraction" to "val body = source.substring(openBrace + 1, closeBrace)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "String API verification migration contains $label" }

        assertTrue(
            body.contains("val code = maskJavaCommentsAndLiterals(source)") &&
                body.contains(").find(code) ?: return source") &&
                body.contains("val openBrace = code.indexOf('{', methodMatch.range.first)") &&
                body.contains("findMatchingBrace(code, openBrace)") &&
                body.contains("val body = code.substring(openBrace + 1, closeBrace)") &&
                body.contains("if (!body.contains(\"Class.forName(\")) return source"),
            "String API verification migration must prove Class.forName from executable method body code"
        )
        assertTrue(
            offenders.isEmpty(),
            "String API verification migration must not use raw method text as migration evidence: $offenders"
        )
    }

    @Test
    fun `reflected optional dependency scan ignores comments and literals`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun reflectedBinaryClassNames")
        assertTrue(start >= 0, "reflectedBinaryClassNames is missing")
        val end = source.indexOf("private fun insertDependencies", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw file text scan" to """pattern.findAll(javaFile.readText())"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "reflected optional dependency scan contains $label" }

        assertTrue(
            body.contains("val source = javaFile.readText()") &&
                body.contains("val code = maskJavaComments(source)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("pattern.findAll(code)") &&
                body.contains("executableCode.substring(match.range.first, match.range.last + 1)") &&
                body.contains(".contains(\"Class.forName(\")"),
            "Reflected optional dependency collection must capture class names from comment-masked code but prove Class.forName from executable Java code"
        )
        assertTrue(
            offenders.isEmpty(),
            "Reflected optional dependency collection must not use raw Java text as evidence: $offenders"
        )
    }

    @Test
    fun `java comment and literal masks handle text blocks`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val maskImplementations = listOf(
            "BuildSystemPass" to projectRoot.resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt"),
            "ResourceMigrator" to projectRoot.resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt"),
            "StructuralRefactorPass" to projectRoot.resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
        )

        val offenders = maskImplementations.mapNotNull { (label, path) ->
            val source = path.readText()
            val start = source.indexOf("private fun maskJavaCommentsAndLiterals")
            if (start < 0) return@mapNotNull "$label is missing maskJavaCommentsAndLiterals"
            val end = source.indexOf("\n    private fun", start + 1).let {
                if (it < 0) source.length else it
            }
            val body = source.substring(start, end)
            val charArrayScannerHandlesTextBlocks =
                body.contains("index + 2 < chars.size") &&
                    body.contains("chars[index + 2]") &&
                    body.contains("index += 3")
            val stateScannerHandlesTextBlocks =
                body.contains("var inTextBlock = false") &&
                    body.contains("val nextTwo = source.getOrNull(index + 2)") &&
                    body.contains("result.append(\"   \")")
            if (charArrayScannerHandlesTextBlocks || stateScannerHandlesTextBlocks) null else {
                "$label maskJavaCommentsAndLiterals does not mask Java text blocks"
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Java comment/literal masking must treat text blocks as literals so documentation cannot drive migrations: $offenders"
        )
    }

    @Test
    fun `structural brace matching ignores Java comments and literals`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val braceStart = source.indexOf("private fun findMatchingBrace")
        assertTrue(braceStart >= 0, "findMatchingBrace is missing")
        val braceEnd = source.indexOf("private fun migrateEventBusSubscriberStaticMethodsSource", braceStart + 1).let {
            if (it < 0) source.length else it
        }
        val braceBody = source.substring(braceStart, braceEnd)
        val cleanupStart = source.indexOf("private fun removeEmptySubscriberClasses")
        assertTrue(cleanupStart >= 0, "removeEmptySubscriberClasses is missing")
        val cleanupBody = source.substring(cleanupStart)

        val offenders = listOf(
            "missing string literal skip" to !braceBody.contains("skipJavaStringLiteral(source, i)"),
            "missing char literal skip" to !braceBody.contains("skipJavaCharLiteral(source, i)"),
            "missing text block skip" to !braceBody.contains("source.indexOf(\"\\\"\\\"\\\"\", i + 3)"),
            "missing line comment skip" to !braceBody.contains("source[i] == '/' && source[i + 1] == '/'"),
            "missing block comment skip" to !braceBody.contains("source[i] == '/' && source[i + 1] == '*'"),
            "empty subscriber raw brace counter" to cleanupBody.contains("braceCount"),
            "empty subscriber silent brace skip" to cleanupBody.contains("Couldn't match braces"),
            "empty subscriber lacks hard error" to !cleanupBody.contains("Cannot match @EventBusSubscriber inner class body")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Structural brace matching must use Java-aware parsing and expose unmatched subscriber bodies: $offenders"
        )
    }

    @Test
    fun `text and resource Java brace matchers handle text blocks`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val matchers = listOf(
            "TextReplacementPass" to Triple(
                projectRoot.resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt"),
                "private fun findMatchingBrace",
                "private data class ParticleField"
            ),
            "ResourceMigrator" to Triple(
                projectRoot.resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt"),
                "private fun findMatchingJavaBrace",
                "private fun migrateRecipeResultEntries"
            )
        )

        val offenders = matchers.mapNotNull { (label, config) ->
            val (path, startMarker, endMarker) = config
            val source = path.readText()
            val start = source.indexOf(startMarker)
            if (start < 0) return@mapNotNull "$label is missing $startMarker"
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            val body = source.substring(start, end)
            val hasTextBlockSkip =
                body.contains("source.getOrNull(index + 2) == '\"'") &&
                    body.contains("source.indexOf(\"\\\"\\\"\\\"\", index + 3)") &&
                    body.contains("continue")
            if (hasTextBlockSkip) null else "$label brace matcher does not skip Java text blocks"
        }

        assertTrue(
            offenders.isEmpty(),
            "Java brace matchers must treat text blocks as literals so documentation cannot terminate class or method bodies: $offenders"
        )
    }

    @Test
    fun `build system delimiter scanners ignore comments and literals`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()

        val closingStart = source.indexOf("fun findClosing")
        assertTrue(closingStart >= 0, "findClosing is missing")
        val closingEnd = source.indexOf("fun findJavaStatementEnd", closingStart + 1).let {
            if (it < 0) source.length else it
        }
        val closingBody = source.substring(closingStart, closingEnd)

        val statementStart = source.indexOf("fun findJavaStatementEnd")
        assertTrue(statementStart >= 0, "findJavaStatementEnd is missing")
        val statementEnd = source.indexOf("fun String.lineNumberAt", statementStart + 1).let {
            if (it < 0) source.length else it
        }
        val statementBody = source.substring(statementStart, statementEnd)

        val offenders = listOf(
            "raw delimiter for-loop" to closingBody.contains("for (i in openIndex until content.length)"),
            "closing scanner missing line comment skip" to !closingBody.contains("ch == '/' && next == '/'"),
            "closing scanner missing block comment skip" to !closingBody.contains("ch == '/' && next == '*'"),
            "closing scanner missing triple double-quoted skip" to
                !closingBody.contains("content.indexOf(\"\\\"\\\"\\\"\", i + 3)"),
            "closing scanner missing triple single-quoted skip" to
                !closingBody.contains("content.indexOf(\"'''\", i + 3)"),
            "closing scanner missing quoted literal skip" to
                !closingBody.contains("skipQuotedLiteral(content, i, ch)"),
            "Java statement scanner missing triple literal state" to
                !statementBody.contains("var inTripleString = false"),
            "Java statement scanner missing generic triple delimiter detection" to
                !statementBody.contains("(ch == '\"' || ch == '\\'') && next == ch && nextTwo == ch"),
            "Java statement scanner missing comment skip" to
                !(statementBody.contains("ch == '/' && next == '/'") && statementBody.contains("ch == '/' && next == '*'")),
            "Java statement scanner missing balanced semicolon rule" to
                !statementBody.contains("ch == ';' && parenDepth == 0 && braceDepth == 0 && bracketDepth == 0")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Build-system delimiter helpers must parse structure outside comments and literals only: $offenders"
        )
    }

    @Test
    fun `mods toml dependency migrations are bounded to dependency tables`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()

        fun bodyBetween(startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            assertTrue(start >= 0, "$startMarker is missing")
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val transformBody = bodyBetween("internal fun transformModsToml", "private fun migrateCustomEnchantmentData")
        val mandatoryBody = bodyBetween("private fun migrateMandatoryFields", "private fun updateDependencyBlocks")
        val dependencyBody = bodyBetween("private fun updateDependencyBlocks", "private fun isTomlTableHeader")
        val helperBody = bodyBetween("private fun isTomlTableHeader", "private fun migrateForgeDataDir")

        val offenders = listOf(
            "global forge modId replacement in transformModsToml" to
                transformBody.contains("content.replace(\n            Regex(\"\"\"modId\\s*=\\s*\"forge\"\"\""),
            "mandatory migration not gated to dependency tables" to
                !(mandatoryBody.contains("blockIsDependency") &&
                    mandatoryBody.contains("isTomlTableHeader(line)") &&
                    mandatoryBody.contains("isTomlDependencyArrayHeader(line)")),
            "dependency migration uses substring table detection" to
                dependencyBody.contains("line.contains(\"dependencies\")"),
            "dependency migration not gated to dependency tables" to
                !(dependencyBody.contains("isTomlTableHeader(line)") &&
                    dependencyBody.contains("isTomlDependencyArrayHeader(line)") &&
                    dependencyBody.contains("if (!inDependencyBlock) continue")),
            "dependency migration still exposes nearby block wording" to
                dependencyBody.contains("nearby", ignoreCase = true),
            "TOML helper missing ordinary and array table support" to
                !(helperBody.contains("\\[\\s*") &&
                    helperBody.contains("\\[\\[\\s*") &&
                    helperBody.contains("dependencies\\."))
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "mods.toml dependency migrations must use TOML table boundaries, not whole-file or nearby-field heuristics: $offenders"
        )
    }

    @Test
    fun `resource missing item model collection uses executable registration evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()
        val start = source.indexOf("private fun detectRegisteredItems")
        assertTrue(start >= 0, "detectRegisteredItems is missing")
        val end = source.indexOf("private fun itemModelJson", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw file text registration scan" to "registerPattern.findAll(file.readText())",
            "registered item class-name texture inference" to "item.className",
            "hardcoded descriptive item id" to "descriptive_item",
            "hardcoded alternate item texture" to "bath_herb"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "missing item model registration detection contains $label" }

        assertTrue(
            body.contains("val source = file.readText()") &&
                body.contains("val code = maskJavaComments(source)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("registerPattern.findAll(code)") &&
                body.contains("val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)") &&
                body.contains("executableSegment.contains(\"ITEMS.register(\")"),
            "Missing item model generation must capture item ids from comment-masked source but prove registrations from executable Java code"
        )
        assertTrue(
            offenders.isEmpty(),
            "Missing item model generation must use direct registered item and texture evidence, not raw Java text or class-name texture guesses: $offenders"
        )
    }

    @Test
    fun `forbidden reflection detection ignores comments and literals`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun detectForbiddenReflection")
        assertTrue(start >= 0, "detectForbiddenReflection is missing")
        val end = source.indexOf("private fun migrateCoremodScripts", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw Java readLines scan" to "javaFile.readLines()",
            "line-comment-only skip" to "trimmed.startsWith(\"//\")",
            "javadoc-line-only skip" to "trimmed.startsWith(\"*\")"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "Forbidden reflection detection contains $label" }

        assertTrue(
            body.contains("val code = maskJavaCommentsAndLiterals(javaFile.readText())") &&
                body.contains("code.lines().forEachIndexed"),
            "Forbidden reflection detection must scan executable Java code, not comments or string literals"
        )
        assertTrue(
            offenders.isEmpty(),
            "Forbidden reflection detection must not use raw line text as hardgate evidence: $offenders"
        )
    }

    @Test
    fun `required access transformer collection uses typed source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun collectRequiredAccessTransformerEntries")
        assertTrue(start >= 0, "collectRequiredAccessTransformerEntries is missing")
        val end = source.indexOf("private fun ensureAccessTransformerEntries", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "whole-file availableGoals scan" to """source.contains(".availableGoals")""",
            "whole-file findLightningTargetAround scan" to """source.contains(".findLightningTargetAround(")""",
            "whole-file StructureTemplatePool templates scan" to """source.contains("StructureTemplatePool") && source.contains(".templates")""",
            "whole-file StructureTemplatePool rawTemplates scan" to """source.contains("StructureTemplatePool") && source.contains(".rawTemplates")""",
            "whole-file pendingBlockEntities scan" to """source.contains(".pendingBlockEntities")""",
            "whole-file firedFromWeapon scan" to """source.contains(".firedFromWeapon =")""",
            "whole-file setPierceLevel scan" to """source.contains(".setPierceLevel(")""",
            "whole-file CreativeModeInventoryScreen selectedTab scan" to """source.contains("CreativeModeInventoryScreen.selectedTab")""",
            "whole-file skyBuffer scan" to """source.contains(".skyBuffer")""",
            "whole-file darkBuffer scan" to """source.contains(".darkBuffer")""",
            "whole-file rainSoundTime scan" to """source.contains(".rainSoundTime")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "required AT collection contains $label" }

        assertTrue(
            body.contains("containsGoalSelectorAvailableGoalsAccess(source)") &&
                body.contains("containsServerLevelFindLightningTargetCall(source)") &&
                body.contains("containsStructureTemplatePoolFieldAccess(source, \"templates\")") &&
                body.contains("containsStructureTemplatePoolFieldAccess(source, \"rawTemplates\")") &&
                body.contains("containsChunkPendingBlockEntitiesAccess(source)") &&
                body.contains("containsAbstractArrowFieldAccess(source, \"firedFromWeapon\")") &&
                body.contains("containsAbstractArrowMethodCall(source, \"setPierceLevel\")") &&
                body.contains("containsCreativeModeInventorySelectedTabAccess(source)") &&
                body.contains("containsLevelRendererFieldAccess(source, \"skyBuffer\")") &&
                body.contains("containsLevelRendererFieldAccess(source, \"darkBuffer\")") &&
                body.contains("containsLevelRendererFieldAccess(source, \"rainSoundTime\")") &&
                body.contains("maskJavaCommentsAndLiterals"),
            "Required AT collection must use typed field access evidence, not comment/string or whole-file markers"
        )
        assertTrue(
            offenders.isEmpty(),
            "Required AT collection must not infer member access needs from broad contains checks: $offenders"
        )
    }

    @Test
    fun `access transformer migration does not derive members from comments`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateAccessTransformerLine")
        assertTrue(start >= 0, "migrateAccessTransformerLine is missing")
        val end = source.indexOf("private fun finalizeAccessTransformerEntry", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val dynamicSrgReplacement = Regex("""Regex\(\s*"{3}\\b[fm]_\\d[\s\S]{0,240}\.replace\(entry,\s*[a-zA-Z_]""")
        val offenders = listOf(
            "comment-derived AT helper" to source.contains("migrateAccessTransformerLineFromComment"),
            "comment token member name" to source.contains("commentName"),
            "comment special member name" to source.contains("specialName"),
            "first comment token extraction" to body.contains(".substringAfter(\"#\", \"\").trim().substringBefore"),
            "dynamic SRG replacement" to dynamicSrgReplacement.containsMatchIn(body)
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> "access transformer migration contains $label" }

        assertTrue(
            body.contains("public net.minecraft.client.resources.model.ModelBakery f_119234_") &&
                body.contains("public net.minecraft.world.level.chunk.ChunkGenerator m_223138_") &&
                body.contains("else -> entry"),
            "Access transformer migration must use explicit legacy member mappings and leave unknown SRG entries for validation"
        )
        assertTrue(
            offenders.isEmpty(),
            "Access transformer migration must not infer named members from AT comments: $offenders"
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
    fun `entity level accessor migration does not use global simple type fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateEntityLevelAccessorCalls")
        assertTrue(start >= 0, "migrateEntityLevelAccessorCalls is missing")
        val end = source.indexOf("\n    private fun ", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "global simple type index" to Regex("""\btypeBySimple\b"""),
            "unique simple type fallback" to Regex("""singleOrNull\(\)""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "migrateEntityLevelAccessorCalls contains $label" }

        assertTrue(
            body.contains("val typeByFqn = types.associateBy { it.fqn }") &&
                body.contains("val sourceType = typeByFqn[normalized]") &&
                body.contains("owner.imports[normalized]") &&
                body.contains("owner.wildcardImports"),
            "Entity level accessor migration must resolve inheritance through Java-visible FQNs"
        )
        assertTrue(
            offenders.isEmpty(),
            "Entity level accessor migration must not infer inheritance from project-global simple-name uniqueness: $offenders"
        )
    }

    @Test
    fun `mmlib recipe base relocation does not infer target package from package suffixes`() {
        val source = Path.of("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .toAbsolutePath()
            .readText()
        val start = source.indexOf("private fun detectLocalRecipeBasePackage")
        val end = source.indexOf("private fun addLocalMmlibRecipeBase", start)
        assertTrue(start >= 0 && end > start, "Could not locate detectLocalRecipeBasePackage body")
        val body = source.substring(start, end)
        val forbidden = listOf(
            "package suffix check" to Regex("""packageName\.endsWith\("""),
            "candidate package voting" to Regex("""groupingBy\s*\{[\s\S]{0,120}maxByOrNull""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "MMLib recipe base relocation must use executable removed-type owner evidence, not package suffix guesses: $offenders"
        )
        assertTrue(body.contains("usesRemovedMmlibRecipeBaseType(source)"))
        assertTrue(body.contains("commonJavaPackagePrefix(ownerPackages)"))
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
    fun `loot condition codec migrations do not synthesize member names from json keys`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun inferLootConditionCodecField")
        assertTrue(start >= 0, "inferLootConditionCodecField is missing")
        val end = source.indexOf("private fun migrateLootConditionalFunctionSerializerCodec", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "json key member synthesis helper" to "jsonKeyToJavaMember",
            "string key member fallback" to "serializedMembers[key] ?: jsonKey",
            "boolean key member fallback" to "serializedMembers[key] ?: jsonKey",
            "entity target key member fallback" to "serializedMembers[key] ?: jsonKey"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "loot condition codec migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Loot condition codec migrations must derive getters from serializer writes, not synthesize members from JSON keys: $offenders"
        )
    }

    @Test
    fun `loot type registry codec migrations require proven codec owners`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLootTypeRegistryCodecConstructors")
        assertTrue(start >= 0, "migrateLootTypeRegistryCodecConstructors is missing")
        val end = source.indexOf("private fun lootCodecOwnerIsAvailable", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "unconditional condition codec replacement" to """.replace(result, "new LootItemConditionType($1.CODEC)")""",
            "unconditional function codec replacement" to """.replace(result, "new LootItemFunctionType<>($1.CODEC)")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "loot type registry codec migration contains $label" }

        assertTrue(
            body.contains("lootCodecOwnerIsAvailable"),
            "Loot type registry codec migrations must check project-proven CODEC owners before replacing serializer constructors"
        )
        assertTrue(
            offenders.isEmpty(),
            "Loot type registry codec migrations must not reference CODEC fields unless the owner migration was proven: $offenders"
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
    fun `empty event bus registration removal resolves mod bus event owners`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun removeEmptyEventBusRegistration")
        assertTrue(start >= 0, "removeEmptyEventBusRegistration is missing")
        val end = source.indexOf("private fun migrateFMLJavaModLoadingContext", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "raw subscribe scan" to "subscribePattern.findAll(text)",
            "simple mod-bus event owner scan" to "modBusEventTypes.any",
            "simple event type containment" to "eventType.contains(modEvent)"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "empty event-bus registration removal contains $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(text)") &&
                body.contains("val imports = javaNonStaticImports(code)") &&
                body.contains("eventTypes.isEmpty() || eventTypes.any") &&
                body.contains("isKnownModBusEventParameterType(eventType, imports)") &&
                body.contains("private fun isKnownModBusEventParameterType") &&
                body.contains("modBusEventTypeOwners.any") &&
                body.contains("resolveJavaTypeReferenceFromImportsOrFqn(rawType, imports) ?: return false") &&
                body.contains("return imports[normalized]"),
            "Empty event-bus registration removal must resolve event parameter owners through Java-visible imports/FQNs and fail closed"
        )
        assertTrue(
            offenders.isEmpty(),
            "Empty event-bus registration removal must not trust simple event names or raw source scans: $offenders"
        )
    }

    @Test
    fun `production mod event bus listener migrations do not infer owners from java file names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        fun functionBody(name: String): String {
            val start = source.indexOf("private fun $name")
            assertTrue(start >= 0, "$name is missing")
            val end = source.indexOf("\n    private fun ", start + 1).let {
                if (it < 0) source.length else it
            }
            return source.substring(start, end)
        }

        val collectRefs = functionBody("collectModBusListenerRefs")
        val staticSubscribers = functionBody("migrateStaticModBusSubscribers")
        val forbidden = listOf(
            "collect listener file-name owner" to (collectRefs to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")),
            "static subscriber file-name owner" to (staticSubscribers to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""))
        )
        val offenders = forbidden
            .filter { (_, scoped) -> scoped.second.containsMatchIn(scoped.first) }
            .map { (label, _) -> label }

        assertTrue(
            collectRefs.contains("val typeBlocks = javaTypeBlocks(text, executableCode)") &&
                collectRefs.contains("javaTypeBlockContainingOffset(match.range.first, typeBlocks)") &&
                staticSubscribers.contains("val typeBlocks = javaTypeBlocks(text, executableCode)") &&
                staticSubscribers.contains("javaTypeBlockForModAnnotation(annotation.range.last, typeBlocks)") &&
                staticSubscribers.contains("javaListenerTypeReferenceExpression(packageName, mainPackage, owner)"),
            "Mod event bus listener migrations must bind method references to source-declared Java type blocks"
        )
        assertTrue(
            offenders.isEmpty(),
            "Mod event bus listener migrations must use declared Java types, not Java file-name owner guesses: $offenders"
        )
    }

    @Test
    fun `mod bus event extraction and registration resolve event parameter owners`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        fun functionBody(name: String): String {
            val start = source.indexOf("private fun $name")
            assertTrue(start >= 0, "$name is missing")
            val end = source.indexOf("\n    private fun ", start + 1).let {
                if (it < 0) source.length else it
            }
            return source.substring(start, end)
        }

        val extraction = functionBody("extractClientOnlyEventMethods")
        val listenerRefs = functionBody("collectModBusListenerRefs")
        val staticSubscribers = functionBody("migrateStaticModBusSubscribers")
        val forbidden = listOf(
            "extract modBusEventTypes simple scan" to extraction.contains("modBusEventTypes.any"),
            "extract method declaration contains scan" to Regex("""methodDecl\.contains\(""").containsMatchIn(extraction),
            "listener refs modBusEventTypes simple scan" to listenerRefs.contains("modBusEventTypes.any"),
            "listener refs parameter contains scan" to Regex("""parameterType\.contains\(""").containsMatchIn(listenerRefs),
            "static subscriber modBusEventTypes simple scan" to staticSubscribers.contains("modBusEventTypes.any"),
            "static subscriber event contains scan" to Regex("""handler\.eventType\.contains\(""").containsMatchIn(staticSubscribers),
            "static subscriber client-only simple scan" to staticSubscribers.contains("clientOnlyEventNames.any")
        )
            .filter { (_, present) -> present }
            .map { (label, _) -> label }

        assertTrue(
            extraction.contains("val imports = javaNonStaticImports(code)") &&
                extraction.contains("isKnownModBusEventParameterType(it, imports)") &&
                extraction.contains("isKnownClientOnlyModBusEventParameterType(it, imports)") &&
                listenerRefs.contains("val imports = javaNonStaticImports(code)") &&
                listenerRefs.contains("isKnownModBusEventParameterType(parameterType, imports)") &&
                staticSubscribers.contains("val imports = javaNonStaticImports(code)") &&
                staticSubscribers.contains("isKnownClientOnlyModBusEventParameterType(eventType, imports)") &&
                staticSubscribers.contains("isKnownModBusEventParameterType(handler.eventType, imports)"),
            "Mod-bus event extraction/registration must resolve parameter owners through Java-visible imports/FQNs"
        )
        assertTrue(
            forbidden.isEmpty(),
            "Mod-bus event extraction/registration must not infer event identity from simple names: $forbidden"
        )
    }

    @Test
    fun `block codec registry holder migration binds holder owners to declared type blocks`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun findBlockRegistryHolderExpression")
        assertTrue(start >= 0, "findBlockRegistryHolderExpression is missing")
        val end = source.indexOf("\n    private fun ", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "java file-name owner" to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "raw source registration scan" to Regex("""registrationPattern\.find\(source\)""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("registrationPattern.find(executableCode)") &&
                body.contains("val typeBlocks = javaTypeBlocks(source, executableCode)") &&
                body.contains("javaTypeBlockContainingOffset(match.range.first, typeBlocks)?.name ?: continue"),
            "Block codec registry holder migration must bind holder owners to executable Java type blocks"
        )
        assertTrue(
            offenders.isEmpty(),
            "Block codec registry holder migration must not infer owners from Java file names or raw source scans: $offenders"
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
            "generated mixin config placeholder fallback" to "?: \"modporter\"",
            "generated compat shared package fallback" to "?: \"shared\"",
            "generated compat blank mod package fallback" to "ifBlank { \"mod\" }",
            "legacy generated compat placeholder resolver" to "private fun detectGeneratedCompatPackage",
            "legacy first metadata mod id reader" to "private fun readFirstModId"
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
    fun `custom entity attachment register namespace is not selected by capability order`() {
        val structuralPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val helperStart = structuralPass.indexOf("private fun attachmentRegisterModIdExpression")
        assertTrue(helperStart >= 0, "attachmentRegisterModIdExpression is missing")
        val helperEnd = structuralPass.indexOf("\n    private fun migrateCustomEntityCapabilities", helperStart)
        assertTrue(helperEnd > helperStart, "attachmentRegisterModIdExpression boundary is missing")
        val helperBody = structuralPass.substring(helperStart, helperEnd)
        val migrationStart = structuralPass.indexOf("private fun migrateCustomEntityCapabilities")
        assertTrue(migrationStart >= 0, "migrateCustomEntityCapabilities is missing")
        val migrationEnd = structuralPass.indexOf("\n    private fun findCapabilityImplementations", migrationStart)
        assertTrue(migrationEnd > migrationStart, "migrateCustomEntityCapabilities boundary is missing")
        val migrationBody = structuralPass.substring(migrationStart, migrationEnd)
        val offenders = listOf(
            "level capability first namespace" to "levelCapabilities.firstOrNull()?.modIdExpression",
            "entity attachment first namespace" to "entityAttachmentCapabilities.firstNotNullOf { it.modIdExpression }"
        )
            .filter { (_, marker) -> migrationBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            helperBody.contains("namespaceExpressions.size == 1") &&
                helperBody.contains("multiple namespace expressions") &&
                helperBody.contains("errors.add("),
            "Attachment DeferredRegister namespace selection must hard gate unless all source namespace expressions match"
        )
        assertTrue(
            migrationBody.contains("attachmentRegisterModIdExpression(") &&
                migrationBody.contains("?: continue"),
            "Custom entity capability migration must skip ambiguous attachment namespace files instead of choosing an ordered candidate"
        )
        assertTrue(
            offenders.isEmpty(),
            "Attachment DeferredRegister namespace must not be selected from the first capability: $offenders"
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
        val generatedBridgeStart = structuralPass.indexOf("private fun migrateGeneratedLazyOptionalImportSource")
        assertTrue(generatedBridgeStart >= 0, "migrateGeneratedLazyOptionalImportSource is missing")
        val generatedBridgeEnd = structuralPass.indexOf("\n    private fun ", generatedBridgeStart + 1).let {
            if (it < 0) structuralPass.length else it
        }
        val generatedBridgeBody = structuralPass.substring(generatedBridgeStart, generatedBridgeEnd)
        assertTrue(
            generatedBridgeBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                generatedBridgeBody.contains("containsMatchIn(executableCode)"),
            "Generated LazyOptional import bridge must inspect executable Java before requiring a generated compat package"
        )
        assertTrue(
            !generatedBridgeBody.contains("""source.contains("LazyOptional<")"""),
            "Generated LazyOptional import bridge must not let comments or strings trigger the required mod-id hard gate"
        )
    }

    @Test
    fun `attachment LazyOptional getData return migration uses executable method evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyAttachmentGetDataLazyOptionalReturns")
        assertTrue(start >= 0, "migrateLegacyAttachmentGetDataLazyOptionalReturns is missing")
        val end = source.indexOf("private fun migrateAttachmentGetDataIfPresentSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw LazyOptional prefilter" to """source.contains("LazyOptional<")""",
            "raw getData prefilter" to """source.contains(".getData(")""",
            "raw whole-source replacement" to """).replace(source)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("methodPattern.findAll(executableCode)") &&
                body.contains("returnPattern.findAll(methodBody)") &&
                body.contains("applyStringEdits(source, edits)"),
            "Attachment LazyOptional getData return migration must inspect executable method source and apply bounded edits"
        )
        assertTrue(
            offenders.isEmpty(),
            "Attachment LazyOptional getData return migration must not use raw whole-source evidence: $offenders"
        )
    }

    @Test
    fun `attachment getData ifPresent migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateAttachmentGetDataIfPresentSource")
        assertTrue(start >= 0, "migrateAttachmentGetDataIfPresentSource is missing")
        val end = source.indexOf("private fun migrateLegacyEntityCapabilityOptionalChains", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getData prefilter" to """source.contains(".getData(")""",
            "raw ifPresent prefilter" to """source.contains(".ifPresent(")""",
            "raw receiver scan" to "receiverPattern.find(result, cursor)",
            "raw matching paren scan" to "findMatchingParen(result",
            "raw ifPresent token scan" to "result.startsWith(\".ifPresent\""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val initialExecutableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("receiverPattern.find(executableCode, cursor)") &&
                body.contains("findMatchingParen(executableCode") &&
                body.contains("val lambdaCode = executableCode.substring"),
            "Attachment getData ifPresent migration must prove calls from executable Java before rewriting"
        )
        assertTrue(
            offenders.isEmpty(),
            "Attachment getData ifPresent migration must not use comments or strings as source evidence: $offenders"
        )
    }

    @Test
    fun `entity capability optional chain migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyEntityCapabilityOptionalChains")
        assertTrue(start >= 0, "migrateLegacyEntityCapabilityOptionalChains is missing")
        val end = source.indexOf("private fun migratePlayerCloneCapabilityLifecycleSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getCapability prefilter" to """source.contains(".getCapability(")""",
            "raw map prefilter" to """source.contains(".map(")""",
            "raw orElse prefilter" to """source.contains(".orElse(")""",
            "raw whole-source replace" to "pattern.replace(source)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("pattern.findAll(executableCode)") &&
                body.contains("source.substring(capabilityCallRange.first") &&
                body.contains("source.substring(bodyRange.first") &&
                body.contains("applyStringEdits(source, edits)"),
            "Entity capability optional-chain migration must match executable Java while preserving original replacement text"
        )
        assertTrue(
            offenders.isEmpty(),
            "Entity capability optional-chain migration must not use comments or strings as source evidence: $offenders"
        )
    }

    @Test
    fun `player clone capability lifecycle migration uses executable method evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migratePlayerCloneCapabilityLifecycleSource")
        assertTrue(start >= 0, "migratePlayerCloneCapabilityLifecycleSource is missing")
        val end = source.indexOf("private fun migrateLegacyAuthlibProfileFetchSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw revive prefilter" to """source.contains("reviveCaps()")""",
            "raw invalidate prefilter" to """source.contains("invalidateCapabilities()")""",
            "raw invalidateCaps prefilter" to """source.contains("invalidateCaps()")""",
            "raw copyFrom prefilter" to """source.contains(".copyFrom(")""",
            "raw declared method extraction" to "javaDeclaredMethodText(source, \"clone\")",
            "raw method text replacement" to "source.replace(methodText, migratedMethod)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains(").find(executableCode)") &&
                body.contains("val methodExecutable = executableCode.substring") &&
                body.contains("revivePattern.findAll(methodExecutable)") &&
                body.contains("invalidatePattern.findAll(methodExecutable)") &&
                body.contains("applyStringEdits(source, edits)"),
            "Player clone capability lifecycle migration must locate clone wrappers from executable Java method source"
        )
        assertTrue(
            offenders.isEmpty(),
            "Player clone capability lifecycle migration must not use comments as wrapper evidence: $offenders"
        )
    }

    @Test
    fun `painting variant accessor migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyPaintingVariantAccessors")
        assertTrue(start >= 0, "migrateLegacyPaintingVariantAccessors is missing")
        val end = source.indexOf("private fun migrateLegacyPaintingVariantRegistrySource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw PaintingVariant prefilter" to """source.contains("PaintingVariant")""",
            "raw variable collection" to ".findAll(source)",
            "raw width replacement" to """.replace("${'$'}variable.getWidth()", "${'$'}variable.width()")""",
            "raw height replacement" to """.replace("${'$'}variable.getHeight()", "${'$'}variable.height()")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("""${'$'}{Regex.escape(variable)}\.getWidth\(\)""") &&
                body.contains("""${'$'}{Regex.escape(variable)}\.getHeight\(\)"""),
            "PaintingVariant accessor migration must collect declarations and rewrite calls from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "PaintingVariant accessor migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `text color literal migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateTextColorParseColorLiteralSource")
        assertTrue(start >= 0, "migrateTextColorParseColorLiteralSource is missing")
        val end = source.indexOf("private fun migrateGameProfileDisplayNameComponents", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw TextColor prefilter" to """source.contains("TextColor.parseColor(")""",
            "raw regex replacement" to ".replace(source)",
            "raw parseColor regex over whole source" to """TextColor\.parseColor\("""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("TextColor.parseColor(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "parseColor")""") &&
                body.contains("""receiver != "TextColor"""") &&
                body.contains("literalColor.matchEntire(args[0].trim())"),
            "TextColor literal migration must inspect executable calls and validate the real string literal argument"
        )
        assertTrue(
            offenders.isEmpty(),
            "TextColor literal migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `game profile display name migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateGameProfileDisplayNameComponents")
        assertTrue(start >= 0, "migrateGameProfileDisplayNameComponents is missing")
        val end = source.indexOf("private fun migrateRegistryAccessEmptyFallbacks", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw ComponentUtils prefilter" to """source.contains("ComponentUtils.getDisplayName(")""",
            "raw getDisplayName rewrite" to """rewriteJavaCall(source, "getDisplayName")""",
            "raw ComponentUtils import usage scan" to """containsMatchIn(withoutComponentUtils)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("ComponentUtils.getDisplayName(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "getDisplayName")""") &&
                body.contains("""receiver != "ComponentUtils"""") &&
                body.contains("maskJavaCommentsAndLiterals(withoutComponentUtils)"),
            "GameProfile display-name migration must inspect executable calls and remove imports using executable usage evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "GameProfile display-name migration must not rewrite comments or keep imports because of strings: $offenders"
        )
    }

    @Test
    fun `game event listener migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyGameEventListenerSource")
        assertTrue(start >= 0, "migrateLegacyGameEventListenerSource is missing")
        val end = source.indexOf("private fun normaliseGameEventHolderComparison", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw GameEventListener prefilter" to """source.contains("GameEventListener")""",
            "raw handleGameEvent prefilter" to """source.contains("handleGameEvent(")""",
            "raw signature replacement" to ".replace(result)",
            "raw holder variable collection" to ".findAll(result)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("GameEventListener")""") &&
                body.contains("""executableCode.contains("handleGameEvent(")""") &&
                body.contains("replaceExecutableRegex(result, Regex(") &&
                body.contains(".findAll(maskJavaCommentsAndLiterals(result))"),
            "GameEventListener migration must collect signatures and event variables from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "GameEventListener migration must not rewrite comments or string literals: $offenders"
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
            "bare @Mod file owner fallback" to Regex("""ids\.putIfAbsent\([^,\r\n]*className"""),
            "raw source package scan" to Regex("""\.find\(content\)"""),
            "raw source constant scan" to Regex("""\.findAll\(content\)"""),
            "global simple mod id table" to Regex("""\bsimpleValues\b"""),
            "global unique bare mod id" to Regex("""values\.size\s*==\s*1"""),
            "bare mod id table entry" to Regex("""ids\[\s*name\s*]""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "detectJavaModIds contains $label" }

        assertTrue(
            body.contains("val code = maskJavaComments(content)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(content)") &&
                body.contains(".find(code)") &&
                body.contains(".findAll(code)") &&
                body.contains("javaTypeNameContainingOffset(code, match.range.first)") &&
                body.contains("executableSegment.contains(\"static\")") &&
                body.contains("executableSegment.contains(\"final\")") &&
                body.contains("executableSegment.contains(\"String\")") &&
                body.contains("executableSegment.contains(\"@Mod\")") &&
                body.contains("executableSegment.contains(\"class\")"),
            "Resource mod id detection must collect ids from comment-masked source and prove declarations from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Resource mod id detection must use source-declared owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `custom enchantment key detection uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val resourceMigrator = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()
        val start = resourceMigrator.indexOf("private fun migrateCustomEnchantmentData")
        assertTrue(start >= 0, "migrateCustomEnchantmentData is missing")
        val end = resourceMigrator.indexOf("\n    private fun customEnchantmentDataExists", start + 1).let {
            if (it < 0) resourceMigrator.length else it
        }
        val body = resourceMigrator.substring(start, end)
        val forbidden = listOf(
            "raw source custom enchantment key scan" to "keyPattern.findAll(content)"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "migrateCustomEnchantmentData contains $label" }

        assertTrue(
            body.contains("val code = maskJavaComments(content)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(content)") &&
                body.contains("keyPattern.findAll(code)") &&
                body.contains("val executableSegment = executableCode.substring(match.range.first, match.range.last + 1)") &&
                body.contains("executableSegment.contains(\"ResourceKey\")") &&
                body.contains("executableSegment.contains(\"Enchantment\")") &&
                body.contains("executableSegment.contains(\"ResourceLocation\")") &&
                body.contains("executableSegment.contains(\"fromNamespaceAndPath\")"),
            "Custom enchantment key detection must derive string values from comment-masked source and prove key declarations from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Custom enchantment key detection must not treat comments or text blocks as resource-key evidence: $offenders"
        )
    }

    @Test
    fun `custom recipe codec hint collection uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val resourceMigrator = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()

        fun functionBody(name: String): String {
            val start = resourceMigrator.indexOf("private fun $name")
            assertTrue(start >= 0, "$name is missing")
            val end = resourceMigrator.indexOf("\n    private fun ", start + 1).let {
                if (it < 0) resourceMigrator.length else it
            }
            return resourceMigrator.substring(start, end)
        }

        val javaSourceInfo = functionBody("javaSourceInfo")
        val collectHints = functionBody("collectRecipeDataCodecHints")
        val collectNamespaces = functionBody("collectRecipeSerializerRegistryNamespaces")
        val fieldsForType = functionBody("recipeCodecFieldsForType")
        val fieldsInBlock = functionBody("recipeCodecFieldsInBlock")
        val listFieldsInBlock = functionBody("recipeListEntryCodecFieldsInBlock")
        val forbidden = listOf(
            "javaSourceInfo raw package/import scan" to (javaSourceInfo to Regex("""\.(find|findAll)\(content\)""")),
            "javaSourceInfo file-name type owner" to (javaSourceInfo to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)""")),
            "recipe register raw precheck" to (collectHints to Regex("""source\.content\.contains\("\.register\("\)""")),
            "recipe register raw scan" to (collectHints to Regex("""registerPattern\.findAll\(source\.content\)""")),
            "recipe namespace raw precheck" to (collectNamespaces to Regex("""source\.content\.contains\("DeferredRegister"\)""")),
            "recipe namespace raw scan" to (collectNamespaces to Regex("""createPattern\.findAll\(source\.content\)""")),
            "recipe class raw block range" to (fieldsForType to Regex("""javaClassBlockRange\(source\.code""")),
            "recipe codec field raw scan" to (fieldsInBlock to Regex("""\.(findAll)\(block\)\s*\.map""")),
            "recipe list entry raw codec acceptance" to (listFieldsInBlock to Regex("""codecListPattern\.findAll\(block\)[\s\S]{0,240}recipeCodecFieldsForType"""))
        )
        val offenders = forbidden
            .filter { (_, scoped) -> scoped.second.containsMatchIn(scoped.first) }
            .map { (label, _) -> label }

        assertTrue(
            javaSourceInfo.contains("val code = maskJavaComments(content)") &&
                javaSourceInfo.contains("val executableCode = maskJavaCommentsAndLiterals(content)") &&
                javaSourceInfo.contains(".find(executableCode)") &&
                javaSourceInfo.contains(".findAll(executableCode)") &&
                javaSourceInfo.contains("val simpleName = sourceDeclaredJavaTypeName(executableCode) ?: return null") &&
                collectHints.contains("source.executableCode.contains(\".register(\")") &&
                collectHints.contains("registerPattern.findAll(source.code)") &&
                collectHints.contains("val executableSegment = source.executableCode.substring(match.range.first, match.range.last + 1)") &&
                collectNamespaces.contains("source.executableCode.contains(\"DeferredRegister\")") &&
                collectNamespaces.contains("createPattern.findAll(source.code)") &&
                fieldsForType.contains("javaClassBlockRange(source.executableCode, className)") &&
                fieldsForType.contains("recipeCodecFieldsInBlock(classBlock, executableClassBlock)") &&
                fieldsForType.contains("recipeListEntryCodecFieldsInBlock(classBlock, executableClassBlock, source, index, visited)") &&
                fieldsForType.contains("directSuperclassReference(executableClassBlock, className)") &&
                fieldsInBlock.contains("val executableSegment = executableBlock.substring(match.range.first, match.range.last + 1)") &&
                fieldsInBlock.contains("executableSegment.contains(\"ItemStack\")") &&
                fieldsInBlock.contains("executableSegment.contains(\"CompoundTag\")") &&
                listFieldsInBlock.contains("val executableSegment = executableBlock.substring(match.range.first, match.range.last + 1)") &&
                listFieldsInBlock.contains("executableSegment.contains(\"CODEC\")") &&
                listFieldsInBlock.contains("executableSegment.contains(\"listOf\")") &&
                listFieldsInBlock.contains("resolveRecipeCodecOwner") &&
                listFieldsInBlock.contains("itemStackFieldsByListField"),
            "Custom recipe codec hint collection must read string values from comment-masked code and prove registrations/codecs from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Custom recipe codec hint collection must not treat comments or text blocks as serializer evidence: $offenders"
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
    fun `resource string constant collection uses executable declarations`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val resourceMigrator = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()
        val start = resourceMigrator.indexOf("private fun collectJavaStringConstants")
        assertTrue(start >= 0, "collectJavaStringConstants is missing")
        val end = resourceMigrator.indexOf("\n    private fun resolveJavaStringExpression", start + 1).let {
            if (it < 0) resourceMigrator.length else it
        }
        val body = resourceMigrator.substring(start, end)
        val forbidden = listOf(
            "raw source constant scan" to "sources.forEach { source ->",
            "unfiltered constant scan" to "constantPattern.findAll(source).forEach",
            "global simple string table" to "simpleValues",
            "global unique bare string constant" to "values.size == 1",
            "bare string constant table entry" to "constants[name]"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "collectJavaStringConstants contains $label" }

        assertTrue(
            body.contains("val source = maskJavaComments(rawSource)") &&
                body.contains("val executableSource = maskJavaCommentsAndLiterals(rawSource)") &&
                body.contains("constantPattern.findAll(source)") &&
                body.contains("val executableSegment = executableSource.substring(match.range.first, match.range.last + 1)") &&
                body.contains("executableSegment.contains(\"static\")") &&
                body.contains("executableSegment.contains(\"final\")") &&
                body.contains("executableSegment.contains(\"String\")"),
            "Resource string constant collection must capture values from comment-masked code but prove declarations from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Resource string constant collection must not accept comments or text blocks as constants: $offenders"
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
            "file-name class fallback" to Regex("""javaTypeNameContainingOffset\([^)]*\)\s*\?:\s*[^;\r\n]*fileName"""),
            "global simple constant table" to Regex("""\bsimpleValues\b"""),
            "bare constant lookup table entry" to Regex("""constants\[\s*name\s*]""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "detectCodeAwardedAdvancements contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Code-awarded advancement detection must use call-site owners, not file-name or global-unique constant inference: $offenders"
        )
    }

    @Test
    fun `java type owner resolution does not fall back to previous declarations`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val sources = listOf(
            "ResourceMigrator" to projectRoot
                .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
                .readText(),
            "TextReplacementPass" to projectRoot
                .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
                .readText()
        )

        fun helperBody(source: String): String {
            val start = source.indexOf("private fun javaTypeNameContainingOffset")
            assertTrue(start >= 0, "javaTypeNameContainingOffset is missing")
            val end = source.indexOf("\n    private fun ", start + 1).let {
                if (it < 0) source.length else it
            }
            return source.substring(start, end)
        }

        val offenders = sources.flatMap { (label, source) ->
            val body = helperBody(source)
            listOf(
                "takeWhile previous-type scan" to "takeWhile { it.range.first <= offset }",
                "last declaration fallback" to "lastOrNull()",
                "post-loop type lookup" to "return typePattern.findAll(source)"
            )
                .filter { (_, marker) -> body.contains(marker) }
                .map { (reason, _) -> "$label contains $reason" }
        }

        assertTrue(
            sources.all { (_, source) ->
                val body = helperBody(source)
                body.contains("offset in openBrace..closeBrace") &&
                    Regex("""return\s+null\s*\}\s*$""").containsMatchIn(body)
            },
            "Java owner resolution must return a type only when the offset is inside that type body"
        )
        assertTrue(
            offenders.isEmpty(),
            "Java owner resolution must not assign orphaned declarations to the previous type: $offenders"
        )
    }

    @Test
    fun `resource java type resolution does not use global simple name fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val resourceMigrator = projectRoot
            .resolve("src/main/kotlin/com/modporter/resources/ResourceMigrator.kt")
            .readText()
        val start = resourceMigrator.indexOf("private fun resolveSimpleJavaType")
        assertTrue(start >= 0, "resolveSimpleJavaType is missing")
        val end = resourceMigrator.indexOf("\n    private fun ", start + 1).let {
            if (it < 0) resourceMigrator.length else it
        }
        val body = resourceMigrator.substring(start, end)
        val forbidden = listOf(
            "global simple type index" to Regex("""\bbySimpleName\b"""),
            "global unique simple type fallback" to Regex("""\bsimpleMatches\b"""),
            "unique simple type fallback" to Regex("""singleOrNull\(\)""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "resolveSimpleJavaType contains $label" }

        assertTrue(
            body.contains("context.imports[simpleName]") &&
                body.contains("index.byFqName[\"\${context.packageName}.\$simpleName\"]") &&
                body.contains("context.wildcardImports") &&
                Regex("""return\s+null\s*\}\s*$""").containsMatchIn(body),
            "Resource Java type resolution must follow Java visibility through imports, package, and wildcard imports"
        )
        assertTrue(
            offenders.isEmpty(),
            "Resource Java type resolution must not resolve invisible types by project-global simple-name uniqueness: $offenders"
        )
    }

    @Test
    fun `code awarded advancement detection uses executable source evidence`() {
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
            "raw source package scan" to ".find(content)",
            "raw source constant scan" to "constantPattern.findAll(content)",
            "raw source call scan" to "callPattern.findAll(content)"
        )
        val offenders = forbidden
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "detectCodeAwardedAdvancements contains $label" }

        assertTrue(
            body.contains("val code = maskJavaComments(content)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(content)") &&
                body.contains("constantPattern.findAll(code)") &&
                body.contains("javaTypeNameContainingOffset(code, match.range.first)") &&
                body.contains("callPattern.findAll(code)") &&
                body.contains("rawId.contains(\".\") -> constants[rawId]") &&
                body.contains("constants[\"\$owner.\$rawId\"]") &&
                body.contains("executableCode") &&
                body.contains("contains(\"tryAwardAdvancement\")"),
            "Code-awarded advancement detection must derive values from comment-masked code and prove declarations/calls from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Code-awarded advancement detection must not treat comments or text blocks as award evidence: $offenders"
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
    fun `nitrogen recipe builder migration does not bind serializer by global uniqueness`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateNitrogenRecipeBuilders")
        assertTrue(start >= 0, "migrateNitrogenRecipeBuilders is missing")
        val end = source.indexOf("private fun collectNitrogenRecipeSerializers", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)

        assertTrue(
            body.contains("val expectedSerializerName = type.className.removeSuffix(\"Builder\") + \"Serializer\"") &&
                body.contains("val serializer = serializerByName[expectedSerializerName]") &&
                body.contains("?: continue"),
            "Nitrogen recipe builder migration must require a serializer name derived from the builder type"
        )
        assertTrue(
            !body.contains("serializers.singleOrNull()"),
            "Nitrogen recipe builder migration must not bind unmatched builders to a globally unique serializer"
        )
    }

    @Test
    fun `serializer backed cooking builder migration uses serializer holder evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateGenericCookingRecipeOutputBuilderSource")
        assertTrue(start >= 0, "migrateGenericCookingRecipeOutputBuilderSource is missing")
        val end = source.indexOf("private data class RecipeFactoryArgument", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val callSiteStart = source.indexOf("private fun collectRecipeBuilderFactoryInterfaceHints")
        assertTrue(callSiteStart >= 0, "collectRecipeBuilderFactoryInterfaceHints is missing")
        val callSiteEnd = source.indexOf("private fun addFactoryParameterToBuilderConstructor", callSiteStart + 1).let {
            if (it < 0) source.length else it
        }
        val callSiteBody = source.substring(callSiteStart, callSiteEnd)

        assertTrue(
            body.contains("referencedRecipeFactoryInterfaces(source, recipeSerializerFactoryHints).singleOrNull()") &&
                body.contains("recipeBuilderFactoryInterfaces[className]?.singleOrNull()"),
            "Cooking builder factory migration must use direct source references or call-site serializer holder evidence"
        )
        assertTrue(
            callSiteBody.contains("maskJavaCommentsAndLiterals(javaFile.readText())") &&
                callSiteBody.contains("recipeSerializerFactoryHints.fieldToFactoryInterface[it]"),
            "Cooking builder call-site factory evidence must come from executable serializer holder calls"
        )
        assertTrue(
            !source.contains("compatibleRecipeFactoryInterfaces") &&
                !body.contains("resultParamNames"),
            "Cooking builder factory migration must not bind factories by parameter-name compatibility alone"
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
    fun `custom enchantment data migrations do not use global simple reference fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private data class LegacyReferenceIndex")
        assertTrue(start >= 0, "LegacyReferenceIndex is missing")
        val end = source.indexOf("private fun resolveLegacyClassReference", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "simple owner fallback table" to Regex("""\bbySimpleOwner\b"""),
            "simple field fallback table" to Regex("""\bbySimpleField\b"""),
            "global unique reference helper" to Regex("""uniqueLegacyReferences"""),
            "simple owner candidates" to Regex("""simpleOwnerCandidates"""),
            "simple field candidates" to Regex("""simpleFieldCandidates""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "legacy reference resolver contains $label" }

        assertTrue(
            body.contains("javaTypeNameContainingOffset(source, offset)") &&
                body.contains("context.staticFieldImports[field]") &&
                body.contains("context.typeImports[owner]") &&
                body.contains("context.wildcardImports"),
            "Custom enchantment references must resolve through Java-visible owners, static imports, and explicit imports"
        )
        assertTrue(
            offenders.isEmpty(),
            "Custom enchantment references must not resolve from project-global unique field or owner names: $offenders"
        )
    }

    @Test
    fun `custom enchantment class sources do not use global simple name fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun resolveLegacyClassReference")
        assertTrue(start >= 0, "resolveLegacyClassReference is missing")
        val end = source.indexOf("private fun resolveLegacyModIdExpression", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "unresolved simple class fallback" to Regex("""\?:\s*trimmed"""),
            "package-scoped class indexed by raw simple name" to Regex("""result\.getOrPut\(className\)""")
        )
        val offenders = forbidden
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "legacy class source resolution contains $label" }

        assertTrue(
            body.contains("context.typeImports[trimmed]") &&
                body.contains("val samePackage = \"\${context.packageName}.\$trimmed\"") &&
                body.contains("return wildcardMatches.singleOrNull()") &&
                body.contains("val key = if (packageName.isBlank()) className else \"\$packageName.\$className\"") &&
                body.contains("resolvedClassName == null"),
            "Legacy custom enchantment class references must resolve through Java-visible imports, package, or wildcard imports"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy custom enchantment class sources must not resolve invisible classes by project-global simple-name uniqueness: $offenders"
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
            "LootTables class suffix regex" to Regex("""LootTables\|LootIds|Loot\|LootTables"""),
            "local LootTable marker fallback" to "inSourceLootTableApiMarker",
            "blank owner class fallback" to "classNameOfJavaSource(source) ?: \"\""
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
        assertTrue(
            body.contains("legacyResourceLocationSetReturnMethods"),
            "Loot table registry migrations must prove set-returning helper methods from external loot API call sites"
        )
        assertTrue(
            body.contains("legacyResourceLocationSetAliases"),
            "Loot table registry migrations must propagate proof through explicit set aliases instead of local markers"
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
    fun `legacy banner pattern constructors require deferred register namespace evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyBannerPatternConstructors")
        assertTrue(start >= 0, "migrateLegacyBannerPatternConstructors is missing")
        val end = source.indexOf("private fun migrateLegacyWallSignBlockCodecSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)

        assertTrue(
            body.contains("bannerPatternDeferredRegisterNamespaceExpression(source)") &&
                body.contains("DeferredRegister\\.create\\(\\s*Registries\\.BANNER_PATTERN"),
            "Legacy BannerPattern constructor migration must read namespace from the BANNER_PATTERN DeferredRegister"
        )
        assertTrue(
            !body.contains("inferModAccess"),
            "Legacy BannerPattern constructor migration must not use @Mod/mod access as a namespace fallback"
        )
    }

    @Test
    fun `legacy banner item factory lookup migrations require scoped registry evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun inferBannerPatternRegistryLookupExpression")
        assertTrue(start >= 0, "inferBannerPatternRegistryLookupExpression is missing")
        val end = source.indexOf("private fun inferCreativeTabBannerPatternLookup", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "file-prefix scope fallback" to Regex("""inferBannerPatternLookupFromScope\s*\(\s*source\.substring\s*\(\s*0\s*,\s*offset\s*\)"""),
            "unscoped null-header lookup fallback" to Regex("""inferBannerPatternLookupFromScope\s*\([^)]*,\s*null\s*\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "inferBannerPatternRegistryLookupExpression contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy banner item factory call migrations must use the current Java method or creative-tab lambda as registry evidence, not file-prefix lookup fallback: $offenders"
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
    fun `placement modifier type migration does not use project mod id namespace fallback`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateBuiltInPlacementModifierTypeRegistrations")
        assertTrue(start >= 0, "migrateBuiltInPlacementModifierTypeRegistrations is missing")
        val end = source.indexOf("private fun migratePlacementModifierTypeDeferredHolderReferences", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)

        assertTrue(
            body.contains("placementModifierRegistryNamespaceExpression(source)") &&
                body.contains("ResourceLocation\\.fromNamespaceAndPath") &&
                body.contains("ResourceLocation\\.parse"),
            "Placement modifier type migration must derive namespace from existing ResourceLocation registrations"
        )
        assertTrue(
            !body.contains("modId: String") &&
                !body.contains("return modId") &&
                !body.contains("\"${'$'}it\""),
            "Placement modifier type migration must not synthesize a DeferredRegister namespace from project mod id"
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
    fun `title screen update indicator cleanup uses executable class declarations`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun removeUnusedTitleScreenUpdateIndicatorClasses")
        assertTrue(start >= 0, "removeUnusedTitleScreenUpdateIndicatorClasses is missing")
        val end = source.indexOf("private fun cleanupTitleScreenRemovedAccessorImports", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner fallback" to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "raw source inheritance sentinel" to Regex("""source\.contains\("extends TitleScreenModUpdateIndicator"\)"""),
            "raw source class scan" to Regex("""\.find\(source\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "TitleScreen update indicator cleanup contains $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains(".find(executableCode)") &&
                body.contains("?: return@mapNotNull null"),
            "TitleScreen update indicator cleanup must prove an executable class declaration before deleting files"
        )
        assertTrue(
            offenders.isEmpty(),
            "TitleScreen update indicator cleanup must not infer owners from file names or comments/strings: $offenders"
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
    fun `deferred holder presence migration uses declared owners and executable code`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateDeferredHolderPresenceChecks")
        assertTrue(start >= 0, "migrateDeferredHolderPresenceChecks is missing")
        val end = source.indexOf("private fun migrateMenuScreensRegistration", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "java file-name owner fallback" to Regex("""file\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "ad hoc class regex owner" to Regex("""classPattern\.find\(content\)"""),
            "raw content replace" to Regex("""content\.replace\("""),
            "raw declaration scan" to Regex("""declarationPattern\.findAll\(content\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "DeferredHolder presence migration contains $label" }

        assertTrue(
            body.contains("classNameOfJavaSource(content) ?: return@flatMap emptyList()") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(content)") &&
                body.contains("declarationPattern.findAll(executableCode)") &&
                body.contains("replaceExecutableRegex("),
            "DeferredHolder presence migration must bind static holders to declared Java owners and rewrite only executable code"
        )
        assertTrue(
            offenders.isEmpty(),
            "DeferredHolder presence migration must not infer owners from file names or rewrite comments/strings: $offenders"
        )
    }

    @Test
    fun `source backed payload migration does not depend on generated network class names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateSourceBackedAccessorCalls")
        assertTrue(start >= 0, "migrateSourceBackedAccessorCalls is missing")
        val end = source.indexOf("private fun migrateUseItemOnInteractionResultReturns", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "generated ModNetwork class marker" to Regex("""class\s+ModNetwork|class ModNetwork|ModNetwork\.java"""),
            "untyped registrar text marker" to Regex("""source\.contains\("registrar\."\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "source-backed payload migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Source-backed payload migration must use PayloadRegistrar registration structure, not generated network class names or raw registrar text: $offenders"
        )
        assertTrue(body.contains("registeredPayloadTypes(source)"))
        assertTrue(body.contains("PayloadRegistrar"))
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
    fun `fluid item capability migration does not depend on custom fluid class names`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCustomFluidItemCapabilities")
        assertTrue(start >= 0, "migrateCustomFluidItemCapabilities is missing")
        val end = source.indexOf("private data class CuriosItemCapabilityMigration", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed CustomFluidCapabilities name" to "CustomFluidCapabilities",
            "fixed CustomFluidItems name" to "CustomFluidItems",
            "fixed CustomFluidContainerHandler name" to "CustomFluidContainerHandler",
            "fixed CustomFluidContainerProvider name" to "CustomFluidContainerProvider",
            "fixed bucket field name" to "CUSTOM_FLUID_BUCKET",
            "fixed bottle field name" to "CUSTOM_FLUID_BOTTLE",
            "raw initCapabilities prefilter" to "source.contains(\"initCapabilities\")",
            "raw factory prefilter" to "source.contains(\"\${factory.className}.\")",
            "raw initCapabilities method extraction" to "javaMethodText(source, \"initCapabilities\")"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "fluid item capability migration contains $label" }

        assertTrue(
            body.contains("legacyFluidItemProviderFactory") &&
                body.contains("legacyFluidItemProviderRegistrations") &&
                body.contains("findRegisteredItemReferences(javaFiles, itemClassName)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("javaMethodText(executableCode, \"initCapabilities\")"),
            "Fluid item capability migration must derive provider and item registrations from source structure"
        )
        assertTrue(
            offenders.isEmpty(),
            "Fluid item capability migration must not depend on fixed custom-fluid class names: $offenders"
        )
    }

    @Test
    fun `curios item capability migration derives local helper names from source structure`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCuriosItemCapabilities")
        assertTrue(start >= 0, "migrateCuriosItemCapabilities is missing")
        val end = source.indexOf("private fun registerCuriosItemCapabilitiesOnModBus", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed local setupCurio helper" to "setupCurio",
            "fixed local setupCuriosCapability factory" to "setupCuriosCapability",
            "raw Curios provider file scan" to "text.contains(\"CurioItemCapability.createProvider\")",
            "raw ICurio provider file scan" to "text.contains(\"new ICurio\")",
            "raw Curios provider index lookup" to "source.indexOf(\"CurioItemCapability.createProvider\")"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "curios item capability migration contains $label" }

        assertTrue(
            body.contains("legacyCuriosProviderFactory") &&
                body.contains("legacyCuriosHelperMethodNames") &&
                body.contains("legacyCuriosItemHooks") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("javaMethodRangesIncludingDefault(executableCode)") &&
                body.contains("findRegisteredItemReferences(javaFiles, itemClassName)"),
            "Curios item capability migration must derive provider, helper, and item registrations from executable source structure"
        )
        assertTrue(
            offenders.isEmpty(),
            "Curios item capability migration must not depend on fixed project-local helper names or raw-source evidence: $offenders"
        )
    }

    @Test
    fun `curios helper and inventory optional migrations use executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCuriosHelperRemovalApis")
        assertTrue(start >= 0, "migrateCuriosHelperRemovalApis is missing")
        val end = source.indexOf("private fun migrateCuriosAttributeModifierHolderTypes", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw Curios helper prefilter" to "source.contains(\"CuriosApi.getCuriosHelper()\")",
            "raw Curios inventory prefilter" to "source.contains(\"CuriosApi.getCuriosInventory\")",
            "raw LazyOptional prefilter" to "source.contains(\"LazyOptional\")",
            "raw helper getEquippedCurios rewrite" to "rewriteJavaCall(result, \"getEquippedCurios\")",
            "raw helper findFirstCurio rewrite" to "rewriteJavaCall(result, \"findFirstCurio\")",
            "raw LazyOptional type replacement" to ".replace(source, \"Optional<ICuriosItemHandler>\")",
            "raw LazyOptional resolve replacement" to ".replace(result, variable)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "curios helper migration contains $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("rewriteExecutableJavaCall(result, \"getEquippedCurios\")") &&
                body.contains("rewriteExecutableJavaCall(result, \"findFirstCurio\")") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("val executableResult = maskJavaCommentsAndLiterals(result)"),
            "Curios helper and inventory optional migrations must inspect executable Java and rewrite only executable code"
        )
        assertTrue(
            offenders.isEmpty(),
            "Curios helper and inventory optional migrations must not treat comments or strings as migration evidence: $offenders"
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
            "fixed whole-file networking template" to "PayloadSource(packageName",
            "missing channel args mod id fallback" to "nestedSimpleChannelArgs(source) ?: return inferModAccess",
            "unresolved channel namespace mod id fallback" to "return inferModAccess(source)?.modIdExpression"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "nested SimpleChannel migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Nested SimpleChannel migrations must use source-declared packet/register/send structure, not fixed networking class templates: $offenders"
        )
    }

    @Test
    fun `nested simplechannel packet direction uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun nestedPacketDirection")
        assertTrue(start >= 0, "nestedPacketDirection is missing")
        val end = source.indexOf("private fun nestedSimpleChannelModIdExpression", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw explicit direction scan" to """explicit.contains("PLAY_TO_CLIENT")""",
            "raw payload variable scan" to ".findAll(source)",
            "raw packet body scan" to "javaTypeBody(source, packetClassName)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val executableDirection = maskJavaCommentsAndLiterals(explicit)") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("javaTypeBody(executableCode, packetClassName)"),
            "Nested SimpleChannel direction inference must inspect executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nested SimpleChannel direction inference must not use comments or strings as direction evidence: $offenders"
        )
    }

    @Test
    fun `nested simplechannel packet discovery uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        fun bodyBetween(startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            assertTrue(start >= 0, "$startMarker is missing")
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val migrationBody = bodyBetween(
            "private fun migrateKnownNestedSimpleChannelNetworking",
            "private data class NestedSimpleChannelPacketInfo"
        )
        val namesBody = bodyBetween("private fun nestedSimpleChannelNames", "private fun nestedSimpleChannelPackets")
        val packetsBody = bodyBetween("private fun nestedSimpleChannelPackets", "private fun nestedPacketBufferType")
        val bufferBody = bodyBetween("private fun nestedPacketBufferType", "private fun javaTypeBody")
        val argsBody = bodyBetween("private fun nestedSimpleChannelArgs", "private fun migrateNestedSimpleChannelPacketSource")

        val offenders = listOf(
            "raw SimpleChannel prefilter" to migrationBody.contains("""!original.contains("SimpleChannel")"""),
            "raw newSimpleChannel prefilter" to migrationBody.contains("""!original.contains("NetworkRegistry.newSimpleChannel")"""),
            "raw registerMessage prefilter" to migrationBody.contains("""!original.contains(".registerMessage(")"""),
            "raw channel name scan" to namesBody.contains(".findAll(source)"),
            "raw registerMessage lookup" to packetsBody.contains("source.indexOf(callName, cursor)"),
            "raw registerMessage paren match" to packetsBody.contains("findMatchingParen(source, openParen)"),
            "raw packet type lookup" to packetsBody.contains("findTypeDeclarationStart(source, packetClassName)"),
            "raw encode buffer lookup" to bufferBody.contains(".find(source)?.let"),
            "raw newSimpleChannel lookup" to argsBody.contains("val index = source.indexOf(token)"),
            "raw newSimpleChannel paren match" to argsBody.contains("findMatchingParen(source, openParen)")
        )
            .filter { (_, present) -> present }
            .map { (label, _) -> label }

        assertTrue(
            migrationBody.contains("val executableCode = maskJavaCommentsAndLiterals(original)") &&
                migrationBody.contains("""!executableCode.contains("SimpleChannel")""") &&
                migrationBody.contains("""!executableCode.contains("NetworkRegistry.newSimpleChannel")""") &&
                migrationBody.contains("""!executableCode.contains(".registerMessage(")""") &&
                namesBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                namesBody.contains(".findAll(executableCode)") &&
                packetsBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                packetsBody.contains("executableCode.indexOf(callName, cursor)") &&
                packetsBody.contains("findMatchingParen(executableCode, openParen)") &&
                packetsBody.contains("findTypeDeclarationStart(executableCode, packetClassName)") &&
                bufferBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                bufferBody.contains(".find(executableCode)?.let") &&
                argsBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                argsBody.contains("val index = executableCode.indexOf(token)") &&
                argsBody.contains("findMatchingParen(executableCode, openParen)"),
            "Nested SimpleChannel discovery must locate channel and packet structure from executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nested SimpleChannel discovery must not use comments or strings as structure evidence: $offenders"
        )
    }

    @Test
    fun `legacy simplechannel wrapper cleanup requires payload registration evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("// 8. Replace old SimpleChannel registration class")
        assertTrue(start >= 0, "legacy SimpleChannel replacement block is missing")
        val end = source.indexOf("changes.addAll(cleanupInlineSimpleChannelRegistrations", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val gateIndex = body.indexOf("val modNetworkFile = file.parent?.resolve(\"ModNetwork.java\")")
        val packetEvidenceIndex = body.indexOf("val registeredPacketNames = Regex")
        val wrapperIndex = body.indexOf("val newContent = buildString")

        assertTrue(
            gateIndex >= 0 &&
                body.contains("modNetworkText.contains(\"PayloadRegistrar\")") &&
                body.contains("modNetworkText.contains(\"registrar.\")"),
            "Legacy SimpleChannel wrappers must require generated payload registrar evidence before replacing source"
        )
        assertTrue(
            packetEvidenceIndex >= 0 &&
                body.contains("modNetworkText.contains(\"${'$'}packetName.TYPE\")") &&
                body.contains("implements\\s+CustomPacketPayload") &&
                body.contains("CustomPacketPayload.Type<${'$'}packetName>") &&
                packetEvidenceIndex < wrapperIndex,
            "Legacy SimpleChannel wrappers must prove registerMessage packet payloads before generating replacement wrappers"
        )
    }

    @Test
    fun `simplechannel packet payload migration requires channel namespace evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun transformPacketClasses")
        assertTrue(start >= 0, "transformPacketClasses is missing")
        val end = source.indexOf("private fun detectModBusVariable", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)

        assertTrue(
            !body.contains("detectModId(projectDir)"),
            "SimpleChannel payload migration must not use project-level mod id as channel namespace fallback"
        )
        assertTrue(
            !body.contains("ResourceLocation.fromNamespaceAndPath(\"${'$'}modId") &&
                !body.contains("event.registrar(\"${'$'}modId"),
            "SimpleChannel payload TYPE and registrar namespaces must come from channel namespace evidence"
        )
        assertTrue(
            body.contains("simpleChannelNamespaceExpression(content, channelName)") &&
                body.contains("namespaceExpression = namespaceExpression") &&
                body.contains("event.registrar(${'$'}registrarNamespaceExpression)"),
            "SimpleChannel payload migration must thread parsed channel namespace evidence into generated payloads and registrar"
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
    fun `build mod id helpers use executable Java evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()

        fun functionBody(name: String): String {
            val start = source.indexOf("private fun $name")
            assertTrue(start >= 0, "$name is missing")
            val end = source.indexOf("\n    private fun ", start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val projectModAnnotation = functionBody("projectModAnnotationExpression")
        val modIdFromModClass = functionBody("modIdExpressionFromModClass")
        val legacyDetectModId = functionBody("detectModId")
        val uniqueModId = functionBody("detectUniqueProjectModId")
        val stringConstant = functionBody("findJavaStringConstant")
        val forbidden = listOf(
            "project @Mod raw package scan" to (projectModAnnotation to Regex("""\.find\(source\)""")),
            "mod class raw annotation scan" to (modIdFromModClass to Regex("""@Mod[\s\S]{0,120}\.find\(source\)""")),
            "mod class raw class scan" to (modIdFromModClass to Regex("""\bclass[\s\S]{0,120}\.find\(source\)""")),
            "legacy mod id raw direct scan" to (legacyDetectModId to Regex("""@Mod[\s\S]{0,160}\.find\(text\)""")),
            "legacy mod id raw annotation scan" to (legacyDetectModId to Regex("""@Mod[\s\S]{0,160}\.findAll\(text\)""")),
            "legacy mod id raw constant scan" to (legacyDetectModId to Regex("""\.find\(text\)""")),
            "unique mod id raw annotation scan" to (uniqueModId to Regex("""@Mod[\s\S]{0,160}\.findAll\(text\)""")),
            "string constant raw scan" to (stringConstant to Regex("""\.find\(source\)"""))
        )
        val offenders = forbidden
            .filter { (_, scoped) -> scoped.second.containsMatchIn(scoped.first) }
            .map { (label, _) -> label }

        assertTrue(
            projectModAnnotation.contains("val executableSource = maskJavaCommentsAndLiterals(source)") &&
                projectModAnnotation.contains(".find(executableSource)") &&
                modIdFromModClass.contains("val code = maskJavaComments(source)") &&
                modIdFromModClass.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                modIdFromModClass.contains(".find(code)") &&
                modIdFromModClass.contains(".find(executableCode)") &&
                legacyDetectModId.contains("val code = maskJavaComments(text)") &&
                legacyDetectModId.contains("val executableCode = maskJavaCommentsAndLiterals(text)") &&
                legacyDetectModId.contains(".findAll(code)") &&
                legacyDetectModId.contains("return candidates.singleOrNull()") &&
                uniqueModId.contains("val code = maskJavaComments(text)") &&
                uniqueModId.contains("val executableCode = maskJavaCommentsAndLiterals(text)") &&
                uniqueModId.contains(".findAll(code)") &&
                stringConstant.contains("val code = maskJavaComments(source)") &&
                stringConstant.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                stringConstant.contains(".find(code)") &&
                stringConstant.contains("executableSegment.contains(\"String\")"),
            "Build-system mod id helpers must read values from comment-masked Java and prove @Mod/constants from executable source"
        )
        assertTrue(
            offenders.isEmpty(),
            "Build-system mod id helpers must not treat comments or text blocks as mod id evidence: $offenders"
        )
    }

    @Test
    fun `structural mod id helpers use executable Java evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()

        fun functionBody(name: String): String {
            val start = source.indexOf("private fun $name")
            assertTrue(start >= 0, "$name is missing")
            val end = source.indexOf("\n    private fun ", start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val detectModId = functionBody("detectModId")
        val detectModMainClass = functionBody("detectModMainClass")
        val modAnnotationArgument = functionBody("modAnnotationArgumentExpression")
        val explicitModIdReference = functionBody("explicitModIdReferenceForGeneratedClass")
        val javaModIdReferenceExpression = functionBody("javaModIdReferenceExpression")
        val javaTypeReferenceExpression = functionBody("javaTypeReferenceExpression")
        val detectModIdsFromText = functionBody("detectModIdsFromText")
        val javaTypeBlocks = functionBody("javaTypeBlocks")
        val javaStaticFinalStringConstant = functionBody("javaStaticFinalStringConstant")
        val staticStringConstant = functionBody("hasStaticFinalStringConstant")
        val forbidden = listOf(
            "main class raw annotation scan" to (detectModMainClass to Regex("""@Mod[\s\S]{0,120}containsMatchIn\(text\)""")),
            "main class first-match return" to (detectModMainClass to Regex("""return\s+file""")),
            "annotation argument raw scan" to (modAnnotationArgument to Regex("""@Mod[\s\S]{0,120}\.find\(source\)""")),
            "explicit mod id raw main scan" to (explicitModIdReference to Regex("""@Mod[\s\S]{0,160}\.find\(mainText\)""")),
            "explicit mod id file-name owner" to (explicitModIdReference to Regex("""mainClass\.fileName""")),
            "explicit mod id first type owner" to (explicitModIdReference to Regex("""classNameOfJavaSource\(mainText\)""")),
            "explicit mod id global constant fallback" to (explicitModIdReference to Regex("""hasStaticFinalStringConstant\(mainText""")),
            "explicit mod id first annotation match" to (explicitModIdReference to Regex("""@Mod[\s\S]{0,160}\.find\(code\)""")),
            "detect text raw direct scan" to (detectModIdsFromText to Regex("""@Mod[\s\S]{0,160}\.find\(text\)""")),
            "detect text raw constant scan" to (detectModIdsFromText to Regex("""\.find\(text\)""")),
            "detect text first candidate return" to (detectModIdsFromText to Regex("""return\s+it\.groupValues\[1]""")),
            "static constant raw contains scan" to (staticStringConstant to Regex("""containsMatchIn\(source\)"""))
        )
        val offenders = forbidden
            .filter { (_, scoped) -> scoped.second.containsMatchIn(scoped.first) }
            .map { (label, _) -> label }

        assertTrue(
            detectModId.contains("val candidates = linkedSetOf<String>()") &&
                detectModId.contains("candidates.addAll(detectModIdsFromText(text))") &&
                detectModId.contains("return candidates.singleOrNull()") &&
                detectModMainClass.contains("val candidates = mutableListOf<Path>()") &&
                detectModMainClass.contains("maskJavaCommentsAndLiterals(text)") &&
                detectModMainClass.contains("return candidates.singleOrNull()") &&
                modAnnotationArgument.contains("val code = maskJavaComments(source)") &&
                modAnnotationArgument.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                modAnnotationArgument.contains(".find(code)") &&
                explicitModIdReference.contains("val code = maskJavaComments(mainText)") &&
                explicitModIdReference.contains("val executableCode = maskJavaCommentsAndLiterals(mainText)") &&
                explicitModIdReference.contains("val typeBlocks = javaTypeBlocks(mainText, executableCode)") &&
                explicitModIdReference.contains("val references = linkedSetOf<String>()") &&
                explicitModIdReference.contains(".findAll(code)") &&
                explicitModIdReference.contains("javaTypeBlockForModAnnotation(match.range.last, typeBlocks)") &&
                explicitModIdReference.contains("javaStaticFinalStringConstant(code, executableCode, owner, constName, typeBlocks)") &&
                explicitModIdReference.contains("javaModIdReferenceExpression(mainPackage, generatedPackage, owner, constName)") &&
                explicitModIdReference.contains("references.singleOrNull()?.let { return it }") &&
                javaModIdReferenceExpression.contains("javaTypeReferenceExpression(mainPackage, generatedPackage, owner)") &&
                javaTypeReferenceExpression.contains("owner.isPublic") &&
                detectModIdsFromText.contains("val candidates = linkedSetOf<String>()") &&
                detectModIdsFromText.contains("val code = maskJavaComments(text)") &&
                detectModIdsFromText.contains("val executableCode = maskJavaCommentsAndLiterals(text)") &&
                detectModIdsFromText.contains("val typeBlocks = javaTypeBlocks(text, executableCode)") &&
                detectModIdsFromText.contains(".findAll(code)") &&
                detectModIdsFromText.contains("javaTypeBlockForModAnnotation(match.range.last, typeBlocks)") &&
                detectModIdsFromText.contains("javaStaticFinalStringConstant(code, executableCode, owner, constName, typeBlocks)") &&
                detectModIdsFromText.contains("return candidates") &&
                javaTypeBlocks.contains("isPublic = Regex") &&
                javaTypeBlocks.contains("findMatchingBrace(executableCode, openBrace)") &&
                javaStaticFinalStringConstant.contains("declaringType == owner") &&
                staticStringConstant.contains("val code = maskJavaComments(source)") &&
                staticStringConstant.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                staticStringConstant.contains(".find(code)"),
            "Structural mod id helpers must read @Mod and constants from comment-masked Java and prove executable source evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "Structural mod id helpers must not treat comments or text blocks as mod id evidence: $offenders"
        )
    }

    @Test
    fun `tier incorrect tag resources require source namespace evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val migrationStart = source.indexOf("private fun migrateTierSortingRegistryCall")
        assertTrue(migrationStart >= 0, "migrateTierSortingRegistryCall is missing")
        val migrationEnd = source.indexOf("private fun simpleTierConstructorArguments", migrationStart + 1).let {
            if (it < 0) source.length else it
        }
        val migrationBody = source.substring(migrationStart, migrationEnd)
        val resourceStart = source.indexOf("private fun ensureTierIncorrectTagResources")
        assertTrue(resourceStart >= 0, "ensureTierIncorrectTagResources is missing")
        val resourceEnd = source.indexOf("private fun targetResourceDirs", resourceStart + 1).let {
            if (it < 0) source.length else it
        }
        val resourceBody = source.substring(resourceStart, resourceEnd)
        val forbidden = listOf(
            "metadata namespace fallback" to "projectMetadataNamespaces",
            "mods.toml namespace fallback" to "readModIds",
            "single metadata namespace fallback" to "metadataNamespaces.singleOrNull()",
            "nullable resource namespace" to "namespace?.let"
        )
        val offenders = forbidden
            .filter { (_, marker) -> source.contains(marker) || resourceBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            migrationBody.contains("val namespace = namespaceFromTagExpression(oldNeedsTag, projectDir, sourceFile)") &&
                migrationBody.contains("if (namespace == null)") &&
                migrationBody.contains("Cannot derive namespace for custom tool tier tag"),
            "Tier incorrect tag migration must hard gate when the old tag expression has no namespace"
        )
        assertTrue(
            resourceBody.contains(".resolve(spec.namespace)") &&
                resourceBody.contains("after = \"\${spec.namespace}:\${spec.path} -> \${spec.vanillaReference}\""),
            "Generated tier incorrect tag resources must use the namespace parsed from the source tag expression"
        )
        assertTrue(
            offenders.isEmpty(),
            "Tier incorrect tag resources must not infer namespaces from project metadata or directories: $offenders"
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
