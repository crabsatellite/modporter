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
        val declaredThirdPartyRoots = surfaces
            .flatMap { it.markers }
            .distinct()
        val undeclaredThirdPartySentinels = listOf(
            "alexsmobs.",
            "alexscaves.",
            "farmersdelight:",
            "twilightforest.",
            "twilightforest:",
            "team-twilight:",
            "vazkii.patchouli"
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
                undeclaredThirdPartySentinels
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
        val undeclaredThirdPartyRuleIdTokens = undeclaredThirdPartySentinels
            .map { root ->
                root.trimEnd('.', ':')
                    .substringAfterLast('.')
                    .substringAfterLast(':')
                    .lowercase()
                    .replace('_', '-')
            }
            .filter { token -> token.any { it.isLetter() } }
            .distinct()

        val ruleIdOffenders = productionFiles
            .flatMap { file ->
                val relative = projectRoot.relativize(file).invariantSeparatorsPathString
                Regex(""""([^"]+)"""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .flatMap { ruleId ->
                        val declaringSurfaces = ruleIdPrefixes
                            .filter { (prefix, _) -> ruleId.startsWith(prefix) }
                            .map { (_, surface) -> surface }
                        when {
                            declaringSurfaces.isNotEmpty() &&
                                declaringSurfaces.none { surface -> relative in surface.allowedFiles } ->
                                listOf("$relative contains third-party API rule id $ruleId outside declared API-surface files")
                            declaringSurfaces.isEmpty() && undeclaredThirdPartyRuleIdTokens.any { token ->
                                ruleId.startsWith("$token-") ||
                                    ruleId.contains("-$token-") ||
                                    ruleId.contains("-$token")
                            } ->
                                listOf("$relative contains undeclared third-party API rule id $ruleId")
                            else -> emptyList()
                        }
                    }
                    .toList()
            }

        val duplicateMarkerOffenders = surfaces
            .flatMap { surface -> surface.markers.map { marker -> marker to surface.id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { ids -> ids.distinct().size > 1 }
            .map { (marker, ids) -> "API surface marker $marker is declared by multiple surfaces ${ids.distinct()}" }

        val ambiguousMarkerOffenders = declaredThirdPartyRoots
            .map { marker -> marker to surfacesDeclaring(marker).map { it.id }.distinct() }
            .filter { (_, ids) -> ids.size > 1 }
            .map { (marker, ids) -> "API surface marker $marker has ambiguous ownership $ids" }

        val offenders = allowedFileOffenders +
            undeclaredRootOffenders +
            markerScopeOffenders +
            ruleIdOffenders +
            duplicateMarkerOffenders +
            ambiguousMarkerOffenders
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
    fun `old dependency wrapper normalization is coordinate agnostic`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/build/BuildSystemPass.kt")
            .readText()
        val start = source.indexOf("private fun normalizeOldDependencyWrappers")
        assertTrue(start >= 0, "normalizeOldDependencyWrappers is missing")
        val end = source.indexOf("private data class LegacyTreeGrower", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "known dependency prefix list" to "allDepPrefixes",
            "filtered dependency prefix list" to "depPrefixes",
            "JEI coordinate prefix" to "mezz.jei:",
            "Botania coordinate prefix" to "vazkii.botania",
            "Create coordinate prefix" to "com.simibubi.create",
            "CurseMaven coordinate prefix" to "curse.maven:",
            "prefix-gated wrapper rewrite" to "depPrefixes.any"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("""blockText.contains("fg.deobf")""") &&
                body.contains("fg\\.deobf") &&
                body.contains("removeFgDeobfWrapper"),
            "Old dependency wrappers must be detected by Gradle wrapper shape, not artifact coordinates"
        )
        assertTrue(
            forbidden.isEmpty(),
            "Old dependency wrapper normalization must not depend on known artifact prefixes: $forbidden"
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
    fun `bucket fluid helper migration uses executable source`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateBucketItemCanBlockContainFluidSource")
        assertTrue(start >= 0, "migrateBucketItemCanBlockContainFluidSource is missing")
        val end = source.indexOf("private fun migrateUnboundLevelRegistryAccessCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw canPlaceLiquid rewrite" to Regex("""rewriteJavaCall\(body,\s*"canPlaceLiquid""""),
            "raw canBlockContainFluid rewrite" to Regex("""rewriteJavaCall\(body,\s*"canBlockContainFluid""""),
            "raw unqualified token scan" to Regex("""result\.indexOf\(token,\s*cursor\)"""),
            "raw unqualified paren matching" to Regex("""findMatchingParen\(result,\s*openParen\)"""),
            "raw helper method scan" to Regex("""helperPattern\.findAll\(result\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("collectJavaClassDeclarations(executableCode)") &&
                body.contains("val executableResult = maskJavaCommentsAndLiterals(result)") &&
                body.contains("helperPattern.findAll(executableResult)") &&
                body.contains("rewriteExecutableJavaCall(body, \"canPlaceLiquid\")") &&
                body.contains("rewriteExecutableJavaCall(body, \"canBlockContainFluid\")") &&
                body.contains("executableCode.indexOf(token, cursor)") &&
                body.contains("findMatchingParen(executableCode, openParen)"),
            "Bucket fluid helper migration must locate helpers and calls in executable Java, not comments or strings"
        )
        assertTrue(
            offenders.isEmpty(),
            "Bucket fluid helper migration must not rewrite commented examples: $offenders"
        )
    }

    @Test
    fun `bucket fluid access migration uses executable source`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateBucketItemFluidAccessSource")
        assertTrue(start >= 0, "migrateBucketItemFluidAccessSource is missing")
        val end = source.indexOf("private fun migrateItemStackHoverNameSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getFluid replacement" to Regex("""\.replace\("this\.getFluid\(\)",\s*"this\.content"\)"""),
            "raw FluidStack variable scan" to Regex("""\.findAll\(result\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("executableCode.contains(\"extends BucketItem\")") &&
                body.contains("replaceExecutableJavaRegex(result, Regex") &&
                body.contains(".findAll(executableCode)"),
            "Bucket fluid access migration must locate getFluid usages and FluidStack variables in executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Bucket fluid access migration must not rewrite comments or strings: $offenders"
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
    fun `structural mod access inference ignores comments and string literals`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun inferModAccess")
        assertTrue(start >= 0, "inferModAccess is missing")
        val end = source.indexOf("/**\n     * Transform packet classes", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw mod id expression scan" to ".find(source)",
            "raw mod import scan" to "containsMatchIn(source)",
            "raw logger scan" to ".findAll(source)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains(".find(executableCode)"),
            "Structural mod access inference must derive mod id/import/logger references from executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Structural mod access inference must not treat comments or string literals as source evidence: $offenders"
        )
    }

    @Test
    fun `particle options codec migration uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateParticleOptionsCodecs")
        assertTrue(start >= 0, "migrateParticleOptionsCodecs is missing")
        val end = source.indexOf("private fun particleCodecSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw deserializer prefilter" to """source.contains("ParticleOptions.Deserializer")""",
            "raw implements prefilter" to """source.contains("implements ParticleOptions")""",
            "raw writeToNetwork prefilter" to """source.contains("writeToNetwork")""",
            "raw class scan" to ".find(source)",
            "raw field scan" to ".findAll(source)",
            "raw class replacement" to "result.replace(",
            "raw regex replacement" to ".replace(result,"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""!executableCode.contains("ParticleOptions.Deserializer")""") &&
                body.contains(".find(executableCode)") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("replaceExecutableRegex("),
            "ParticleOptions codec migration must prove and rewrite class, fields, and methods from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "ParticleOptions codec migration must not treat comments or string literals as source evidence: $offenders"
        )
    }

    @Test
    fun `particle deserializer class removal uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun removeParticleDeserializerClass")
        assertTrue(start >= 0, "removeParticleDeserializerClass is missing")
        val end = source.indexOf("private fun migrateParticleTypeRegistrations", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw marker lookup" to "source.indexOf(marker)",
            "raw open brace lookup" to "source.indexOf('{', markerIndex)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("executableCode.indexOf(marker)") &&
                body.contains("executableCode.indexOf('{', markerIndex)"),
            "Particle deserializer removal must locate the inner class in executable Java, not comments"
        )
        assertTrue(
            offenders.isEmpty(),
            "Particle deserializer removal must not delete commented sample classes: $offenders"
        )
    }

    @Test
    fun `particle type registration migration rewrites executable source only`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateParticleTypeRegistrations")
        assertTrue(start >= 0, "migrateParticleTypeRegistrations is missing")
        val end = source.indexOf("private fun migrateParticleNetworkCodecs", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw particle type prefilter" to """source.contains("new ParticleType<>(false, new")""",
            "raw deserializer prefilter" to """source.contains(".Deserializer()")""",
            "raw deserializer scan" to ".findAll(result)",
            "raw particle type replacement" to ".replace(result, \"new ParticleType<>(false)\")",
            "raw codec block replacement" to "codecBlock.replace(result)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""!executableCode.contains("new ParticleType<>(false, new")""") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("replaceExecutableRegex("),
            "ParticleType registration migration must discover and rewrite registrations from executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "ParticleType registration migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `particle network codec migration uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateParticleNetworkCodecs")
        assertTrue(start >= 0, "migrateParticleNetworkCodecs is missing")
        val end = source.indexOf("private fun migrateJadeTooltipElementHelper", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw ParticleOptions prefilter" to """source.contains("ParticleOptions")""",
            "raw getDeserializer prefilter" to """source.contains("getDeserializer().fromNetwork")""",
            "raw writeToNetwork prefilter" to """source.contains("writeToNetwork")""",
            "raw FriendlyByteBuf replacement" to "Regex(\"\"\"\\bFriendlyByteBuf\\b\"\"\").replace(result",
            "raw deserializer return replacement" to "result.replace(",
            "raw writeToNetwork replacement" to ".replace(result, \"writeParticle",
            "raw helper insertion" to "readParticlePattern.replace(result)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""!executableCode.contains("ParticleOptions")""") &&
                body.contains("val afterReadMigration = replaceExecutableRegex(") &&
                body.contains("if (afterReadMigration == result) return source") &&
                body.contains("replaceExecutableRegex(result, Regex(\"\"\"\\bFriendlyByteBuf\\b\"\"\"))") &&
                body.contains("!maskJavaCommentsAndLiterals(result).contains(\"void writeParticle(\")"),
            "Particle network codec migration must prove and rewrite network serialization from executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Particle network codec migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `jade tooltip element helper migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = textPass.indexOf("private fun migrateJadeTooltipElementHelper")
        assertTrue(start >= 0, "migrateJadeTooltipElementHelper is missing")
        val end = textPass.indexOf("private data class NetworkHooksOpenScreenMigration", start + 1).let {
            if (it < 0) textPass.length else it
        }
        val body = textPass.substring(start, end)
        val offenders = listOf(
            "raw getElementHelper prefilter" to body.contains("""source.contains(".getElementHelper")"""),
            "raw ITooltip import prefilter" to body.contains("""source.contains("import snownee.jade.api.ITooltip;")"""),
            "raw ITooltip fqcn prefilter" to body.contains("""source.contains("snownee.jade.api.ITooltip")"""),
            "raw tooltip variable collection" to body.contains(".findAll(source)"),
            "raw helper replacement" to body.contains(".replace(result")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".getElementHelper")""") &&
                body.contains("""executableCode.contains("import snownee.jade.api.ITooltip;")""") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("replaceExecutableRegex("),
            "Jade tooltip helper migration must collect receivers and rewrite calls from executable Java source only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Jade tooltip helper migration must not rewrite comments or strings: $offenders"
        )
    }

    @Test
    fun `network hooks open screen migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val cleanupStart = textPass.indexOf("private fun cleanupImports")
        assertTrue(cleanupStart >= 0, "cleanupImports is missing")
        val cleanupEnd = textPass.indexOf("private fun migrateRemainingRegistryObjectWildcardHolders", cleanupStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val cleanupBody = textPass.substring(cleanupStart, cleanupEnd)
        val migrateStart = textPass.indexOf("private fun migrateNetworkHooksOpenScreen")
        assertTrue(migrateStart >= 0, "migrateNetworkHooksOpenScreen is missing")
        val migrateEnd = textPass.indexOf("private fun migrateInventoryRecipeHolderInterface", migrateStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val migrateBody = textPass.substring(migrateStart, migrateEnd)
        val writerStart = textPass.indexOf("private fun isNetworkHooksExtraDataWriter")
        assertTrue(writerStart >= 0, "isNetworkHooksExtraDataWriter is missing")
        val writerEnd = textPass.indexOf("private fun isNetworkHooksBlockPosExtra", writerStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val writerBody = textPass.substring(writerStart, writerEnd)
        val blockPosStart = textPass.indexOf("private fun isNetworkHooksBlockPosExtra")
        assertTrue(blockPosStart >= 0, "isNetworkHooksBlockPosExtra is missing")
        val blockPosEnd = textPass.indexOf("private fun stripOuterParentheses", blockPosStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val blockPosBody = textPass.substring(blockPosStart, blockPosEnd)
        val offenders = listOf(
            "raw openScreen prefilter" to migrateBody.contains("""source.contains("NetworkHooks.openScreen")"""),
            "raw openScreen scan" to migrateBody.contains("source.indexOf(callName"),
            "raw openScreen paren scan" to migrateBody.contains("source.indexOf('(', callStart"),
            "raw openScreen delimiter scan" to migrateBody.contains("findMatchingDelimiter(source, openParen"),
            "raw writer declaration scan" to writerBody.contains(".containsMatchIn(source)"),
            "raw blockpos declaration scan" to blockPosBody.contains(".containsMatchIn(source)"),
            "raw NetworkHooks import cleanup" to cleanupBody.contains("""!result.contains("NetworkHooks.")""")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            migrateBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                migrateBody.contains("""executableCode.contains("NetworkHooks.openScreen")""") &&
                migrateBody.contains("executableCode.indexOf(callName, searchFrom)") &&
                migrateBody.contains("findMatchingDelimiter(executableCode, openParen") &&
                writerBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                writerBody.contains(".containsMatchIn(executableCode)") &&
                blockPosBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                blockPosBody.contains(".containsMatchIn(executableCode)") &&
                cleanupBody.contains("""maskJavaCommentsAndLiterals(result).contains("NetworkHooks.")"""),
            "NetworkHooks.openScreen migration and cleanup must use executable Java source evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "NetworkHooks.openScreen migration must not use comments or strings as call/type evidence: $offenders"
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
    fun `structural unused import cleanup ignores comments and literals`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val simpleStart = source.indexOf("private fun removeUnusedSimpleImports")
        assertTrue(simpleStart >= 0, "removeUnusedSimpleImports is missing")
        val simpleEnd = source.indexOf("private fun removeUnusedImportsBySimpleNamePattern", simpleStart + 1)
        assertTrue(simpleEnd > simpleStart, "removeUnusedImportsBySimpleNamePattern must follow removeUnusedSimpleImports")
        val simpleBody = source.substring(simpleStart, simpleEnd)

        val patternStart = simpleEnd
        val patternEnd = source.indexOf("\n    private fun", patternStart + 1).let {
            if (it < 0) source.length else it
        }
        val patternBody = source.substring(patternStart, patternEnd)

        val offenders = listOf(
            "simple import cleanup raw reference scan" to !simpleBody.contains("maskJavaCommentsAndLiterals(withoutImport)"),
            "pattern import cleanup raw reference scan" to !patternBody.contains("maskJavaCommentsAndLiterals(withoutImport)")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Unused import cleanup must ignore references that exist only in comments, strings, or text blocks: $offenders"
        )
    }

    @Test
    fun `qualified Java invocation helper scans executable code only`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteJavaInvocation(")
        assertTrue(start >= 0, "rewriteJavaInvocation is missing")
        val end = source.indexOf("private fun removeJavaStatementsMatching", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)

        val offenders = listOf(
            "missing executable mask" to !body.contains("val executableCode = maskJavaCommentsAndLiterals(result)"),
            "raw qualified invocation index" to body.contains("result.indexOf(\"${'$'}qualifiedName(\", cursor)"),
            "raw parenthesis matcher" to body.contains("findMatchingParen(result, openParen)")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Qualified invocation migrations must locate calls and argument structure in executable Java only: $offenders"
        )
    }

    @Test
    fun `offset Java call helper delegates to executable scanner`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteJavaCallWithOffset(")
        assertTrue(start >= 0, "rewriteJavaCallWithOffset is missing")
        val end = source.indexOf("private fun rewriteJavaInvocationArguments", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)

        val offenders = listOf(
            "missing executable delegate" to !body.contains("rewriteExecutableJavaCallWithOffset(source, methodName, transform)"),
            "raw token scan" to body.contains("result.indexOf(token, cursor)"),
            "raw parenthesis matcher" to body.contains("findMatchingParen(result, openParen)"),
            "raw receiver scan" to body.contains("findExpressionReceiverStart(result, tokenIndex)")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            offenders.isEmpty(),
            "Offset Java call rewrites must reuse the executable-code scanner instead of raw source traversal: $offenders"
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
    fun `recipe book category finder migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRecipeBookCategoryFinderRecipeHolders")
        assertTrue(start >= 0, "migrateRecipeBookCategoryFinderRecipeHolders is missing")
        val end = source.indexOf("private fun migrateRecipeManagerCraftingInputSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw registerRecipeCategoryFinder prefilter" to """source.contains("registerRecipeCategoryFinder(")""",
            "raw instanceof prefilter" to """source.contains(" instanceof ")""",
            "raw registerRecipeCategoryFinder rewrite" to """rewriteJavaCall(source, "registerRecipeCategoryFinder")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("registerRecipeCategoryFinder(")""") &&
                body.contains("""executableCode.contains(" instanceof ")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "registerRecipeCategoryFinder")""") &&
                body.contains("val lambda = args[1]"),
            "Recipe book category finder holder migration must inspect executable calls and lambda shape"
        )
        assertTrue(
            offenders.isEmpty(),
            "Recipe book category finder migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `furnace can burn accessor migration uses executable binding evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val migrateStart = source.indexOf("private fun migrateLegacyAbstractFurnaceCanBurnInvokerBoundary")
        assertTrue(migrateStart >= 0, "migrateLegacyAbstractFurnaceCanBurnInvokerBoundary is missing")
        val migrateEnd = source.indexOf("private fun nearestFurnaceCanBurnAccessorCast", migrateStart + 1).let {
            if (it < 0) source.length else it
        }
        val migrateBody = source.substring(migrateStart, migrateEnd)
        val bindingStart = source.indexOf("private fun nearestFurnaceCanBurnAccessorCast")
        assertTrue(bindingStart >= 0, "nearestFurnaceCanBurnAccessorCast is missing")
        val bindingEnd = source.indexOf("private fun migrateRecipeHolderOptionalMapLambdaValueAccess", bindingStart + 1).let {
            if (it < 0) source.length else it
        }
        val bindingBody = source.substring(bindingStart, bindingEnd)
        val offenders = listOf(
            "raw callCanBurn scan" to migrateBody.contains("""rewriteJavaCallWithOffset(result, "callCanBurn")"""),
            "raw nearest binding source" to bindingBody.contains("source.substring(prefixStart, offset)"),
            "declaration backreference binding" to bindingBody.contains("""\s*\1\s*\)"""),
            "missing stale assignment guard" to !bindingBody.contains("afterBinding")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            migrateBody.contains("""rewriteExecutableJavaCallWithOffset(result, "callCanBurn")""") &&
                bindingBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                bindingBody.contains("val prefixCode = executableCode.substring(prefixStart, offset)") &&
                bindingBody.contains("bindingPattern.findAll(prefixCode).lastOrNull()") &&
                bindingBody.contains("afterBinding") &&
                bindingBody.contains("source.substring(prefixStart + expressionRange.first"),
            "Furnace canBurn accessor migration must bind call receivers from executable cast assignments"
        )
        assertTrue(
            offenders.isEmpty(),
            "Furnace canBurn accessor migration must not use comments or stale declarations as binding evidence: $offenders"
        )
    }

    @Test
    fun `unbound level registry access migration uses executable token edits`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateUnboundLevelRegistryAccessCalls")
        assertTrue(start >= 0, "migrateUnboundLevelRegistryAccessCalls is missing")
        val end = source.indexOf("private fun migrateFoodComponentAccess", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source prefilter" to body.contains("""source.contains("level.registryAccess()")"""),
            "raw method scan" to body.contains("methodPattern.findAll(source)"),
            "raw brace matching" to body.contains("findMatchingBrace(source, openBrace)"),
            "raw method replace" to body.contains("""methodText.replace("level.registryAccess()", registryAccess)"""),
            "raw result splice" to body.contains("result.substring(0, match.range.first) + replacement")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("executableCode.contains(token)") &&
                body.contains("methodPattern.findAll(executableCode)") &&
                body.contains("findMatchingBrace(executableCode, openBrace)") &&
                body.contains("edits += index until index + token.length to registryAccess") &&
                body.contains("return applyStringEdits(source, edits)"),
            "Unbound level registryAccess migration must replace only executable token ranges"
        )
        assertTrue(
            offenders.isEmpty(),
            "Unbound level registryAccess migration must not rewrite comments or string literals: $offenders"
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
    fun `legacy mob custom damage source attack migration uses executable doHurtTarget method range`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyMobCustomDamageSourceAttacks")
        assertTrue(start >= 0, "migrateLegacyMobCustomDamageSourceAttacks is missing")
        val end = source.indexOf("private fun migrateLegacyMobCustomDamageSourceUtilityAttacks", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw doHurtTarget prefilter" to """source.contains("boolean doHurtTarget(Entity entity)")""",
            "raw getDamageBonus prefilter" to """source.contains("EnchantmentHelper.getDamageBonus(")""",
            "raw method extraction" to """javaMethodText(source, "doHurtTarget")""",
            "raw method replacement" to "source.replace(methodText, replacement)",
            "raw method pattern search" to "find(methodText)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("javaMethodRanges(executableCode)") &&
                body.contains("val methodText = source.substring(method.range)") &&
                body.contains("val executableMethodText = executableCode.substring(method.range)") &&
                body.contains("oldEnchantmentBlock.find(executableMethodText)") &&
                body.contains("applyStringEdits(methodText, edits)") &&
                body.contains("source.substring(0, method.range.first) + replacement + source.substring(method.range.last + 1)") &&
                body.contains("addImportIfMissing(result, \"net.minecraft.world.damagesource.DamageSource\")"),
            "Legacy mob damage-source attack migration must edit the executable doHurtTarget method range"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy mob damage-source attack migration must not use comments, strings, or raw method text as evidence: $offenders"
        )
    }

    @Test
    fun `legacy passenger attachment migration uses executable method ranges`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyPassengerAttachmentOverrides")
        assertTrue(start >= 0, "migrateLegacyPassengerAttachmentOverrides is missing")
        val end = source.indexOf("private fun migrateLegacyEntityOverrideSignatures", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getPassengersRidingOffset prefilter" to """source.contains("getPassengersRidingOffset()")""",
            "raw attachment point prefilter" to """source.contains("getPassengerAttachmentPoint(")""",
            "raw offset method extraction" to """javaMethodText(source, "getPassengersRidingOffset")""",
            "raw offset method replacement" to "source.replace(offsetMethod, replacement)",
            "raw method removal" to "removeMethodByName(result",
            "raw distance scan" to """.find(source)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val methods = javaMethodRanges(executableCode)") &&
				body.contains("val offsetMethodText = source.substring(offsetMethod.range)") &&
				body.contains("val executableOffsetMethodText = executableCode.substring(offsetMethod.range)") &&
				body.contains("if (riderPositionMethods.size > 1)") &&
				body.contains("executableCode.substring(method.range)") &&
				body.contains("javaMethodRangeWithTrailingLineBreak(source") &&
				body.contains("applyStringEdits(source, edits)"),
            "Legacy passenger attachment migration must edit executable Java method ranges"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy passenger attachment migration must not read comments, strings, or raw methods as migration evidence: $offenders"
        )
    }

    @Test
    fun `legacy custom portal block migration uses executable method ranges`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyCustomPortalBlockProtocol")
        assertTrue(start >= 0, "migrateLegacyCustomPortalBlockProtocol is missing")
        val end = source.indexOf("private fun migrateLegacyConcretePowderConcreteAccessor", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val destinationStart = source.indexOf("private fun legacyPortalDestinationMethod")
        assertTrue(destinationStart >= 0, "legacyPortalDestinationMethod is missing")
        val destinationEnd = source.indexOf("private fun extractFirstMethodCallArgument", destinationStart + 1).let {
            if (it < 0) source.length else it
        }
        val destinationBody = source.substring(destinationStart, destinationEnd)
        val offenders = listOf(
            "raw portal prefilter" to """source.contains("getPortalEntrancePos")""",
            "raw block class check" to "containsMatchIn(source)",
            "raw entityInside extraction" to """javaMethodText(source, "entityInside")""",
            "raw entityInside replacement" to "result.replace(entityInside",
            "raw handleTeleportation extraction" to """javaMethodText(source, "handleTeleportation")"""
        )
            .filter { (_, marker) -> body.contains(marker) || destinationBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val methods = javaMethodRanges(executableCode)") &&
                body.contains("val entityInside = source.substring(entityInsideMethod.range)") &&
                body.contains("val executableEntityInside = executableCode.substring(entityInsideMethod.range)") &&
                body.contains("source.replaceRange(entityInsideMethod.range, migratedEntityInside)") &&
                destinationBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                destinationBody.contains("javaMethodRanges(executableCode).singleOrNull") &&
                destinationBody.contains("val handleTeleportationCode = maskJavaCommentsAndLiterals(handleTeleportation)"),
            "Legacy custom portal migration must locate portal methods in executable Java method ranges"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy custom portal migration must not use comments, strings, or raw methods as portal evidence: $offenders"
        )
    }

    @Test
    fun `legacy projectile weapon migration uses executable finishUsingItem method range`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyProjectileWeaponFinishUsingItemSource")
        assertTrue(start >= 0, "migrateLegacyProjectileWeaponFinishUsingItemSource is missing")
        val parserStart = source.indexOf("private fun parseLegacyProjectileWeaponShape", start + 1)
        assertTrue(parserStart >= 0, "parseLegacyProjectileWeaponShape is missing")
        val migrationBody = source.substring(start, parserStart)
        val parserEnd = source.indexOf("private fun modernProjectileWeaponFinishUsingItem", parserStart + 1).let {
            if (it < 0) source.length else it
        }
        val parserBody = source.substring(parserStart, parserEnd)
        val offenders = listOf(
            "raw ProjectileWeaponItem prefilter" to """source.contains("extends ProjectileWeaponItem")""",
            "raw finishUsingItem extraction" to """javaDeclaredMethodText(source, "finishUsingItem")""",
            "raw method replacement" to "source.replace(methodText",
            "raw helper method existence check" to """result.contains("shootProjectile(LivingEntity shooter, Projectile projectile")""",
            "raw custom projectile signature replacement" to ".replace(result) { match ->",
            "raw parser method body" to ".find(methodText)",
            "raw parser addFreshEntity check" to "methodText.contains("
        )
            .filter { (_, marker) -> migrationBody.contains(marker) || parserBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            migrationBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                migrationBody.contains("val method = javaMethodRanges(executableCode).singleOrNull") &&
                migrationBody.contains("val methodText = source.substring(method.range)") &&
                migrationBody.contains("source.replaceRange(method.range, modernProjectileWeaponFinishUsingItem(shape))") &&
                migrationBody.contains("var resultCode = maskJavaCommentsAndLiterals(result)") &&
                migrationBody.contains("replaceExecutableRegex(result, customProjectilePattern)") &&
                parserBody.contains("val methodCode = maskJavaCommentsAndLiterals(methodText)") &&
                parserBody.contains(".find(methodCode)") &&
                parserBody.contains("methodCode.contains(\"${'$'}level.addFreshEntity(${'$'}projectileVariable);\")"),
            "Legacy projectile weapon migration must parse and replace only executable finishUsingItem method code"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy projectile weapon migration must not use comments, strings, or raw methods as firing logic evidence: $offenders"
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
            "unconditional function codec replacement" to """.replace(result, "new LootItemFunctionType<>($1.CODEC)")""",
            "raw result function type scan" to """result.contains("new LootItemFunctionType<>(")""",
            "raw function register replacement" to """"DeferredRegister<LootItemFunctionType> FUNCTIONS""""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> "loot type registry codec migration contains $label" }

        assertTrue(
            body.contains("lootCodecOwnerIsAvailable") &&
                body.contains("replaceExecutableRegex(result, conditionTypePattern)") &&
                body.contains("replaceExecutableRegex(result, functionTypePattern)") &&
                body.contains("maskJavaCommentsAndLiterals(result).contains(\"new LootItemFunctionType<>(\")") &&
                body.contains("replaceCommentMaskedRegex(result, functionHolderPattern)"),
            "Loot type registry codec migrations must check project-proven CODEC owners before replacing serializer constructors"
        )
        assertTrue(
            offenders.isEmpty(),
            "Loot type registry codec migrations must not reference CODEC fields unless the owner migration was proven: $offenders"
        )
    }

    @Test
    fun `loot codec migrations use executable structure and comment masked serializers`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLootSerializerCodecs")
        assertTrue(start >= 0, "migrateLootSerializerCodecs is missing")
        val end = source.indexOf("private fun migrateNeoForgeConditionSerializerCodecs", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw serializer prefilter" to """source.contains("Serializer<")""",
            "raw loot condition prefilter" to """source.contains("implements LootItemCondition")""",
            "raw loot function prefilter" to """source.contains("extends LootItemConditionalFunction")""",
            "raw class name extraction" to ".find(source)?.groupValues?.get(1)",
            "raw inner class match" to ".find(source) ?: return null",
            "raw inner class removal match" to ".find(source) ?: return source",
            "raw CODEC owner scan" to "containsMatchIn(source)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("private fun lootConditionSerializerName") &&
                body.contains("val serializerCode = maskJavaComments(serializer)") &&
                body.contains("find(maskJavaCommentsAndLiterals(source))") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains(".find(executableCode)") &&
                body.contains("replaceExecutableRegex(result, constructorPattern)") &&
                body.contains("replaceCommentMaskedRegex(result, functionHolderPattern)"),
            "Loot codec migrations must locate Java structure in executable code while preserving JSON strings only inside serializer bodies"
        )
        assertTrue(
            offenders.isEmpty(),
            "Loot codec migrations must not use comments, strings, or raw source as structural evidence: $offenders"
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
    fun `FMLJavaModLoadingContext migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateFMLJavaModLoadingContext")
        assertTrue(start >= 0, "migrateFMLJavaModLoadingContext is missing")
        val end = source.indexOf("private data class FluidBucketCapabilityMigration", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "raw @Mod prefilter" to "text.contains(\"@Mod(\")",
            "raw FML prefilter" to "text.contains(\"FMLJavaModLoadingContext\")",
            "raw class scan" to """).find(text)?.groupValues""",
            "raw context event-bus replacement" to """.replace("${'$'}{contextVarName}.getModEventBus()""",
            "raw context config replacement" to """.replace("${'$'}{contextVarName}.registerConfig(""",
            "exact static getter scan" to """executableCode.contains("FMLJavaModLoadingContext.get().getModEventBus()")""",
            "raw active-container config replacement" to "\"ModLoadingContext.get().getActiveContainer().registerConfig(\"",
            "raw direct config replacement" to "\"ModLoadingContext.get().registerConfig(\"",
            "raw ModLoadingContext import scan" to """!text.contains("ModLoadingContext.")""",
            "raw regex import removal" to "text.replace(Regex("
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("var executableCode = maskJavaCommentsAndLiterals(text)") &&
                body.contains("Regex(\"\"\"@\\s*Mod") &&
                body.contains("staticFmlModEventBusPattern.containsMatchIn(executableCode)") &&
                body.contains(".findAll(executableCode)") &&
                body.contains(".singleOrNull()") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("removeExecutableImport(text, \"net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext\")") &&
                body.contains("maskJavaCommentsAndLiterals(text).contains(\"ModLoadingContext.\")") &&
                body.contains("addExecutableImportIfMissing(text, \"net.neoforged.fml.ModContainer\")") &&
                body.contains("addExecutableImportIfMissing(text, \"net.neoforged.bus.api.IEventBus\")"),
            "FMLJavaModLoadingContext migration must derive constructor, calls, and imports from executable Java"
        )
        assertTrue(
            forbidden.isEmpty(),
            "FMLJavaModLoadingContext migration must not use comments or strings as source evidence: $forbidden"
        )
    }

    @Test
    fun `legacy static FML mod event bus migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyStaticFmlModEventBusAccess")
        assertTrue(start >= 0, "migrateLegacyStaticFmlModEventBusAccess is missing")
        val end = source.indexOf("private fun migrateLegacyEntityStepHeightOverrides", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val forbidden = listOf(
            "raw static getter prefilter" to """source.contains("FMLJavaModLoadingContext.get().getModEventBus()")""",
            "raw annotation scan" to ".find(source)",
            "raw static getter replacement" to "source.replace(",
            "raw FML import removal" to "removeImport(result, \"net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext\")"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("staticGetterPattern.containsMatchIn(executableCode)") &&
                body.contains(".find(executableCode)") &&
                body.contains("source.substring(it).trim()") &&
                body.contains("replaceExecutableRegex(source, staticGetterPattern)") &&
                body.contains("removeExecutableImport(result, \"net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext\")"),
            "Legacy static FML mod event-bus migration must use executable calls and source-ranged annotation arguments"
        )
        assertTrue(
            forbidden.isEmpty(),
            "Legacy static FML mod event-bus migration must not use comments or strings as source evidence: $forbidden"
        )
    }

    @Test
    fun `command source stack level migration uses executable scoped evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCommandSourceStackLevelAccess")
        assertTrue(start >= 0, "migrateCommandSourceStackLevelAccess is missing")
        val end = source.indexOf("private fun migrateLegacyStaticFmlModEventBusAccess", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw CommandSourceStack prefilter" to """source.contains("CommandSourceStack")""",
            "raw level-call prefilter" to """source.contains(".level()")""",
            "raw result variable scan" to ".findAll(result)",
            "raw variable replacement" to ".replace(result, \"${'$'}variable.getLevel()\")",
            "full source edit fallback" to "replaceExecutableRegex(source"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("commandSourceStackExecutableScopes(executableCode)") &&
                body.contains(".findAll(executableCode, scope.first)") &&
                body.contains(".takeWhile { it.range.last <= scope.last }") &&
                body.contains(".filter { match -> declarationOffsets.any { it < match.range.first } }") &&
                body.contains("return applyStringEdits(source, edits)") &&
                body.contains("private fun commandSourceStackExecutableScopes(executableCode: String)") &&
                body.contains("javaMethodRanges(executableCode)") &&
                body.contains("javaTypeBlocks(executableCode, executableCode)") &&
                body.contains("constructorPattern.findAll(executableCode)"),
            "CommandSourceStack level migration must use executable method/constructor scope evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "CommandSourceStack level migration must not rewrite comments, strings, or full-file same-name calls: $offenders"
        )
    }

    @Test
    fun `legacy entity step height migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyEntityStepHeightOverrides")
        assertTrue(start >= 0, "migrateLegacyEntityStepHeightOverrides is missing")
        val end = source.indexOf("private fun migrateLegacyEntityTypeAabbCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getStepHeight prefilter" to """source.contains("getStepHeight(")""",
            "raw signature replacement" to ".replace(source)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("getStepHeight")""") &&
                body.contains("return replaceExecutableRegex(") &&
                body.contains("""\b(public|protected)\s+float\s+getStepHeight"""),
            "Legacy getStepHeight migration must inspect and rewrite executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy getStepHeight migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `saddleable equipSaddle signature migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateSaddleableEquipSaddleSignature")
        assertTrue(start >= 0, "migrateSaddleableEquipSaddleSignature is missing")
        val end = source.indexOf("private fun migrateMthTrigonometryFloatArguments", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw equipSaddle prefilter" to """source.contains("equipSaddle(")""",
            "raw SoundSource prefilter" to """source.contains("SoundSource")""",
            "raw signature replacement" to ").replace(source) { match ->"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("equipSaddle(")""") &&
                body.contains("""executableCode.contains("SoundSource")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("addImportIfMissing(result, \"net.minecraft.world.item.ItemStack\")"),
            "Saddleable equipSaddle signature migration must inspect and rewrite executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Saddleable equipSaddle signature migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `potion component migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("""maskJavaCommentsAndLiterals(result).contains("PotionUtils.")""")
        assertTrue(start >= 0, "potion component migration block is missing")
        val end = source.indexOf("result = migrateUseOnContextDataComponentHasCalls(result)", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw PotionUtils prefilter" to """result.contains("PotionUtils.")""",
            "raw PotionUtils replacement" to Regex("""Regex\([\s\S]*?PotionUtils[\s\S]*?\)\s*\.\s*replace\(result"""),
            "raw Potions.EMPTY replacement" to "result.replace(\"Potions.EMPTY\"",
            "raw Potion.byName replacement" to Regex("""Regex\([\s\S]*?Potion\.byName[\s\S]*?\)\s*\.\s*replace\(result""")
        )
            .filter { (_, marker) ->
                when (marker) {
                    is String -> body.contains(marker)
                    is Regex -> marker.containsMatchIn(body)
                    else -> false
                }
            }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("maskJavaCommentsAndLiterals(result)") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("migrateLegacyPotionEmptyStringChecks(result)") &&
                body.contains("migratedPotionUtils") &&
                body.contains("needsDataComponents = true") &&
                body.contains("needsPotionContents = true"),
            "Potion component migration must inspect executable Java and gate import flags on real rewrites"
        )
        assertTrue(
            source.contains("private fun migrateLegacyPotionEmptyStringChecks(source: String): String") &&
                source.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                source.contains("sourcePattern.findAll(source)") &&
                source.contains("executablePattern.find(executableCode, match.range.first)") &&
                source.contains("return applyStringEdits(source, edits)"),
            "Potion string-key migration must verify source matches against executable Java ranges"
        )
        assertTrue(
            offenders.isEmpty(),
            "Potion component migration must not rewrite comments or string literals with raw replacements: $offenders"
        )
    }

    @Test
    fun `use on context data component has migration uses method local executable evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateUseOnContextDataComponentHasCalls")
        assertTrue(start >= 0, "migrateUseOnContextDataComponentHasCalls is missing")
        val end = source.indexOf("private fun migrateThisStackUseDurationCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "whole-file UseOnContext context fallback" to """result.contains("UseOnContext context")""",
            "raw this.has replacement against full source" to """result = Regex(""" + "\"\"\"" + """\bthis\.has"""
        )
            .filter { (_, marker) -> source.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("javaMethodRangesIncludingDefault(executableCode)") &&
                body.contains("contextParameterPattern.find(methodText)") &&
                body.contains("source.substring(absoluteRange) != match.value") &&
                body.contains("return applyStringEdits(source, edits)") &&
                source.contains("val executableSource = maskJavaCommentsAndLiterals(source)") &&
                source.contains("pattern.findAll(executableSource)") &&
                source.contains("findMatchingBrace(executableSource, openBrace)"),
            "UseOnContext item component migration must derive context and call sites from the same executable method"
        )
        assertTrue(
            offenders.isEmpty(),
            "UseOnContext item component migration must not use whole-file fallbacks or raw source replacements: $offenders"
        )
    }

    @Test
    fun `this stack use duration migration uses method local living entity evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateThisStackUseDurationCalls")
        assertTrue(start >= 0, "migrateThisStackUseDurationCalls is missing")
        val end = source.indexOf("private fun migrateLegacyAddLayersSkinNameLoopsSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val genericStart = source.indexOf("private fun migrateItemUseDurationCalls")
        assertTrue(genericStart >= 0, "migrateItemUseDurationCalls is missing")
        val genericEnd = source.indexOf("private fun migrateArmorMaterialHolderFields", genericStart + 1).let {
            if (it < 0) source.length else it
        }
        val genericBody = source.substring(genericStart, genericEnd)
        val offenders = listOf(
            "whole-file LivingEntity living fallback" to """result.contains("LivingEntity living")""",
            "whole-file LivingEntity entity fallback" to """result.contains("LivingEntity entity")""",
            "raw getUseDuration replacement against full source" to """result = Regex(""" + "\"\"\"" + """\bthis\.getUseDuration""",
            "raw getUseDuration method-call migration" to """migrateMethodCalls(result, ".getUseDuration")""",
            "non-executable getUseDuration rewrite" to """rewriteJavaCall(result, "getUseDuration")"""
        )
            .filter { (_, marker) -> source.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("javaMethodRangesIncludingDefault(executableCode)") &&
                body.contains("val parameterList = method.header.substring(openParen + 1, closeParen)") &&
                body.contains(".singleOrNull()") &&
                body.contains("source.substring(absoluteRange) != match.value") &&
                body.contains("return applyStringEdits(source, edits)"),
            "this.getUseDuration(stack) migration must bind the LivingEntity parameter from the same executable method"
        )
        assertTrue(
            genericBody.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                genericBody.contains("findExpressionReceiverStart(executableCode, tokenIndex)") &&
                genericBody.contains("migratedItemUseDurationCall(") &&
                genericBody.contains("singleLivingEntityParameterAt(executableCode, callOffset)") &&
                genericBody.contains("if (receiver == \"this\" && stackArg == \"stack\")"),
            "generic getUseDuration migration must locate executable calls and bind this.getUseDuration(stack) structurally"
        )
        assertTrue(
            offenders.isEmpty(),
            "this.getUseDuration(stack) migration must not use whole-file LivingEntity fallbacks or raw replacements: $offenders"
        )
    }

    @Test
    fun `legacy restore migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        assertTrue(
            source.contains("""replaceExecutableRegex(result, Regex(""" + "\"\"\"" + """\.restore\("""),
            "legacy restore migration must use executable Java rewriting"
        )
        assertTrue(
            !source.contains("""result.replace(".restore(true, false)", ".restore()")"""),
            "legacy restore migration must not rewrite comments or string literals with raw String.replace"
        )
    }

    @Test
    fun `pickup block player parameter migrations use method local executable evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val notNullStart = source.indexOf("private fun migrateNotNullPickupBlockOverridePlayerParameterSource")
        assertTrue(notNullStart >= 0, "migrateNotNullPickupBlockOverridePlayerParameterSource is missing")
        val notNullEnd = source.indexOf("private fun collectSuperPickupBlockEdits", notNullStart + 1).let {
            if (it < 0) source.length else it
        }
        val notNullBody = source.substring(notNullStart, notNullEnd)
        val fluidStart = source.indexOf("private fun migrateLegacyFluidInterfacePlayerParametersSource")
        assertTrue(fluidStart >= 0, "migrateLegacyFluidInterfacePlayerParametersSource is missing")
        val fluidEnd = source.indexOf("private fun migrateLegacyBucketPickupCallSites", fluidStart + 1).let {
            if (it < 0) source.length else it
        }
        val fluidBody = source.substring(fluidStart, fluidEnd)
        val offenders = listOf(
            "whole-file pickupBlock super replacement" to """result.replace("super.pickupBlock(level, pos, state)")""",
            "broad pickupBlock super replacement after any signature change" to "if (result != beforeLiquidInterfaces)",
            "broad super pickupBlock regex" to """super\.pickupBlock\(\s*([^,\r\n]+)"""
        )
            .filter { (_, marker) -> source.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            notNullBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                notNullBody.contains("findMatchingBrace(executableCode, openBrace)") &&
                notNullBody.contains("source.substring(match.range) != match.value") &&
                notNullBody.contains("collectSuperPickupBlockEdits(source, executableCode, method") &&
                notNullBody.contains("return applyStringEdits(source, edits) to edits.isNotEmpty()"),
            "@NotNull pickupBlock override migration must bind the added player parameter to the executable method being migrated"
        )
        assertTrue(
            fluidBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                fluidBody.contains("javaMethodRangesIncludingDefault(executableCode)") &&
                fluidBody.contains("method.name == \"pickupBlock\"") &&
                fluidBody.contains("method.name == \"canPlaceLiquid\"") &&
                fluidBody.contains("source.substring(absoluteRange) == pickupMatch.value") &&
                fluidBody.contains("collectSuperPickupBlockEdits(source, executableCode, method"),
            "Fluid interface player parameter migration must use executable method headers and method-local super call edits"
        )
        assertTrue(
            offenders.isEmpty(),
            "pickupBlock player parameter migrations must not use raw or whole-file replacements: $offenders"
        )
    }

    @Test
    fun `step height addition migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val stepHeightIndex = source.indexOf("STEP_HEIGHT_ADDITION")
        assertTrue(stepHeightIndex >= 0, "STEP_HEIGHT_ADDITION migration is missing")
        val body = source.substring((stepHeightIndex - 600).coerceAtLeast(0), (stepHeightIndex + 1200).coerceAtMost(source.length))
        val offenders = listOf(
            "raw simple STEP_HEIGHT_ADDITION replacement" to ".replace(\"NeoForgeMod.STEP_HEIGHT_ADDITION.get()\"",
            "raw qualified STEP_HEIGHT_ADDITION replacement" to ".replace(\"net.neoforged.neoforge.common.NeoForgeMod.STEP_HEIGHT_ADDITION.get()\""
        )
            .filter { (_, marker) -> source.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("maskJavaCommentsAndLiterals(result)") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("beforeStepHeight") &&
                body.contains("maskJavaCommentsAndLiterals(withoutNeoForgeMod)"),
            "STEP_HEIGHT_ADDITION migration must rewrite executable Java only and remove imports using executable evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "STEP_HEIGHT_ADDITION migration must not use raw String.replace: $offenders"
        )
    }

    @Test
    fun `vanilla 121 small API rewrites use executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val hoverIndex = source.indexOf("hasCustomHoverName")
        assertTrue(hoverIndex >= 0, "small API rewrite block is missing")
        val body = source.substring((hoverIndex - 400).coerceAtLeast(0), (hoverIndex + 2200).coerceAtMost(source.length))
        val offenders = listOf(
            "raw hasCustomHoverName replacement" to ".replace(\".hasCustomHoverName()\"",
            "raw getBiome(pos) replacement" to ".replace(\".getBiome(pos).get().\"",
            "raw Tags.Items.HEADS replacement" to ".replace(\"Tags.Items.HEADS\"",
            "raw BLOCK_REACH replacement" to ".replace(\"NeoForgeMod.BLOCK_REACH.get()\"",
            "raw ENTITY_REACH replacement" to ".replace(\"NeoForgeMod.ENTITY_REACH.get()\""
        )
            .filter { (_, marker) -> source.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("beforeCustomHoverName") &&
                body.contains("beforeItemHeads") &&
                body.contains("executableReachSource") &&
                body.contains("beforeReach") &&
                body.contains("replaceExecutableRegex(result, Regex") &&
                body.contains("maskJavaCommentsAndLiterals(withoutNeoForgeMod)"),
            "Small vanilla 1.21 API rewrites must use executable source evidence and set imports only after real rewrites"
        )
        assertTrue(
            offenders.isEmpty(),
            "Small vanilla 1.21 API rewrites must not use raw String.replace: $offenders"
        )
    }

    @Test
    fun `common vanilla 121 API rewrites use executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateVanilla121ApiSource")
        assertTrue(start >= 0, "migrateVanilla121ApiSource is missing")
        val end = source.indexOf("result = migrateEntityDimensionsRecordAccessors", start + 1)
        assertTrue(end > start, "common vanilla 1.21 API rewrite block boundary is missing")
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getBlockReach replacement" to "result.replace(\".getBlockReach()\"",
            "raw canCreateFluidSource fixed variable replacement" to "EventHooks.canCreateFluidSource(level, neighborPos, neighborState, true)",
            "raw ResourceLocation codec replacement" to "result.replace(\"ResourceLocation.CODEC.codec().\"",
            "raw mulPoseMatrix replacement" to "result.replace(\".mulPoseMatrix(\"",
            "raw bow tag replacement" to "result.replace(\"Tags.Items.TOOLS_BOWS\"",
            "raw crossbow tag replacement" to "result.replace(\"Tags.Items.TOOLS_CROSSBOWS\"",
            "raw fishing rod tag replacement" to "result.replace(\"Tags.Items.TOOLS_FISHING_RODS\"",
            "raw armor slot replacement" to "result.replace(\"EquipmentSlot.Type.ARMOR\"",
            "raw cauldron interaction regex replacement" to ".replace(result, \"$1.map().put(\")",
            "raw setTame replacement" to "result.replace(\".setTame(true);\"",
            "raw disableShield regex replacement" to ".replace(result, \".disableShield()\")",
            "raw grass item replacement" to "result.replace(\"Items.GRASS\"",
            "raw default durability replacement" to "result.replace(\".defaultDurability(\"",
            "raw mob equipment slot replacement" to "result.replace(\"Mob.getEquipmentSlotForItem(\""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("replaceExecutableRegex(result, Regex") &&
                body.contains("rewriteExecutableJavaInvocationArguments(result, \"EventHooks.canCreateFluidSource\")") &&
                body.contains("val executableEquipmentSlotSource = maskJavaCommentsAndLiterals(result)") &&
                body.contains("executableEquipmentSlotSource.contains(\"Mob.getEquipmentSlotForItem(\")"),
            "Common vanilla 1.21 API rewrites must use executable source evidence and invocation parsing"
        )
        assertTrue(
            offenders.isEmpty(),
            "Common vanilla 1.21 API rewrites must not use raw replacements or fixed local variable call shapes: $offenders"
        )
    }

    @Test
    fun `brewing recipe migration derives event recipes from executable source calls`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateBrewingRecipeRegistrationSource")
        assertTrue(start >= 0, "migrateBrewingRecipeRegistrationSource is missing")
        val end = source.indexOf("private fun migrateVanilla121ApiSource", start + 1)
        assertTrue(end > start, "migrateBrewingRecipeRegistrationSource boundary is missing")
        val body = source.substring(start, end)
        val offenders = listOf(
            "HotBath hot water bottle" to "HOT_WATER_BOTTLE",
            "HotBath honey bottle" to "HONEY_BATH_BOTTLE",
            "HotBath milk bottle" to "MILK_BATH_BOTTLE",
            "HotBath herbal bottle" to "HERBAL_BATH_BOTTLE",
            "HotBath peony bottle" to "PEONY_BATH_BOTTLE",
            "HotBath rose bottle" to "ROSE_BATH_BOTTLE",
            "hardcoded custom fluid recipe" to "new CustomFluidBrewingRecipe()",
            "fixed setup call removal" to "result.replace(\"registerBrewingRecipes(event);\""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val recipeArguments = collectLegacyBrewingRecipeAddRecipeArguments(source)") &&
                body.contains("event.getBuilder().addRecipe(${'$'}args);") &&
                body.contains("replaceExecutableRegex(result, Regex") &&
                body.contains("removeExecutableImport(result, \"net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry\")") &&
                body.contains("addExecutableImportIfMissing(result, \"net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent\")") &&
                body.contains("findMatchingParen(executableCode, openParen)"),
            "Brewing migration must derive event builder recipes from executable BrewingRecipeRegistry.addRecipe calls"
        )
        assertTrue(
            offenders.isEmpty(),
            "Brewing migration must not retain mod-specific recipe names or fixed setup-call rewrites: $offenders"
        )
    }

    @Test
    fun `legacy registry utility migrations use executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val lazyStart = source.indexOf("private fun migrateLegacyLazyRegistryCodecs")
        assertTrue(lazyStart >= 0, "migrateLegacyLazyRegistryCodecs is missing")
        val delegateStart = source.indexOf("private fun migrateLegacyDeferredHolderDelegateAccessors", lazyStart + 1)
        assertTrue(delegateStart > lazyStart, "migrateLegacyLazyRegistryCodecs boundary is missing")
        val registryStart = source.indexOf("private fun migrateLegacyRegistryObjectMethodReferences")
        assertTrue(registryStart >= 0, "migrateLegacyRegistryObjectMethodReferences is missing")
        val registryEnd = source.indexOf("private fun migrateDeferredHolderCollectionVariance", registryStart + 1)
        assertTrue(registryEnd > registryStart, "migrateLegacyRegistryObjectMethodReferences boundary is missing")
        val lazyBody = source.substring(lazyStart, delegateStart)
        val registryBody = source.substring(registryStart, registryEnd)
        val inspectedBody = lazyBody + registryBody
        val offenders = listOf(
            "raw ExtraCodecs prefilter" to "source.contains(\"ExtraCodecs.lazyInitializedCodec(\")",
            "raw lazy codec regex replacement" to ".replace(source) { match -> match.groupValues[1] }",
            "raw ExtraCodecs import check" to "result.contains(\"ExtraCodecs.\")",
            "raw RegistryObject prefilter" to "source.contains(\"RegistryObject::get\")",
            "raw RegistryObject method reference replacement" to "source.replace(\"RegistryObject::get\"",
            "raw RegistryObject import check" to "result.contains(\"RegistryObject\")"
        )
            .filter { (_, marker) -> inspectedBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            lazyBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                lazyBody.contains("replaceExecutableJavaRegex(source") &&
                lazyBody.contains("maskJavaCommentsAndLiterals(result)") &&
                lazyBody.contains("removeExecutableImport(result, \"net.minecraft.util.ExtraCodecs\")"),
            "Legacy lazy registry codec migration must rewrite and clean imports using executable Java evidence"
        )
        assertTrue(
            registryBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                registryBody.contains("replaceExecutableJavaRegex(source") &&
                registryBody.contains("addExecutableImportIfMissing(result, \"net.neoforged.neoforge.registries.DeferredHolder\")") &&
                registryBody.contains("maskJavaCommentsAndLiterals(result)") &&
                registryBody.contains("removeExecutableImport(result, \"net.neoforged.neoforge.registries.RegistryObject\")"),
            "Legacy RegistryObject method reference migration must rewrite and clean imports using executable Java evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy registry utility migrations must not use raw-source evidence or replacements: $offenders"
        )
    }

    @Test
    fun `legacy holder accessor migrations use executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val delegateStart = source.indexOf("private fun migrateLegacyDeferredHolderDelegateAccessors")
        assertTrue(delegateStart >= 0, "migrateLegacyDeferredHolderDelegateAccessors is missing")
        val holderStart = source.indexOf("private fun migrateLegacyHolderAccessors")
        assertTrue(holderStart >= 0, "migrateLegacyHolderAccessors is missing")
        val holderEnd = source.indexOf("private fun migrateLegacyItemConstructorsAndProperties", holderStart + 1)
        assertTrue(holderEnd > holderStart, "migrateLegacyHolderAccessors boundary is missing")
        val delegateBody = source.substring(delegateStart, holderStart)
        val holderBody = source.substring(holderStart, holderEnd)
        val inspectedBody = delegateBody + holderBody
        val offenders = listOf(
            "raw getHolder orElseThrow replacement" to ".replace(\".getHolder().orElseThrow()\"",
            "raw getHolder get replacement" to ".replace(\".getHolder().get()\"",
            "raw instant effect replacement" to ".replace(\".getEffect().isInstantenous(\"",
            "raw apply instant effect replacement" to ".replace(\".getEffect().applyInstantenousEffect(\"",
            "raw biome holder replacement" to ".replace(result) { match -> \"${'$'}{match.groupValues[1]}.value()\" }"
        )
            .filter { (_, marker) -> inspectedBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            delegateBody.contains("maskJavaCommentsAndLiterals(source)") &&
                delegateBody.contains("replaceExecutableJavaRegex(source"),
            "DeferredHolder delegate accessor migration must gate replacements on executable Java"
        )
        assertTrue(
            holderBody.contains("val executableSource = maskJavaCommentsAndLiterals(source)") &&
                holderBody.contains("replaceExecutableJavaRegex(") &&
                holderBody.contains(".findAll(maskJavaCommentsAndLiterals(result))") &&
                holderBody.contains("rewriteExecutableJavaInvocationArguments"),
            "Legacy holder accessor migration must collect and rewrite executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy holder accessor migrations must not use raw String.replace or raw Regex.replace: $offenders"
        )
    }

    @Test
    fun `holder value accessor migration uses executable typed variables`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateHolderValueAccessorsSource")
        assertTrue(start >= 0, "migrateHolderValueAccessorsSource is missing")
        val end = source.indexOf("private fun migrateScreenBackgroundRenderedEventSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getEffect description replacement" to "result.replace(\".getEffect().getDescriptionId()\"",
            "raw getModelName replacement" to "result.replace(\"${'$'}variable.getModelName()\"",
            "hardcoded effectHolder assignment" to "MobEffect effect = effectHolder;",
            "raw variable scan" to ".findAll(result)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("replaceExecutableRegex(") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("playerSkinVariables") &&
                body.contains("mobEffectHolderVariables") &&
                body.contains("Holder") &&
                body.contains("MobEffect") &&
                body.contains("Regex(\"\"\"\\b${'$'}{Regex.escape(variable)}\\.getModelName\\(\\)\"\"\")") &&
                body.contains("Regex(\"\"\"\\bMobEffect\\s+([A-Za-z_") &&
                body.contains("${'$'}{Regex.escape(variable)}\\s*;\"\"\")"),
            "Holder value accessor migration must rewrite executable typed variables, not comments or hardcoded names"
        )
        assertTrue(
            offenders.isEmpty(),
            "Holder value accessor migration must not use raw whole-file replacements or hardcoded names: $offenders"
        )
    }

    @Test
    fun `screen background rendered migration uses executable method evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateScreenBackgroundRenderedEventSource")
        assertTrue(start >= 0, "migrateScreenBackgroundRenderedEventSource is missing")
        val end = source.indexOf("private fun migrateLegacyHeartTypeSheetCoordinatesSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw background event prefilter" to body.contains("""source.contains("ScreenEvent.BackgroundRendered")"""),
            "raw renderBackground prefilter" to body.contains("""source.contains("renderBackground(GuiGraphics")"""),
            "raw method scan" to body.contains("methodPattern.find(result, cursor)"),
            "raw brace matching" to body.contains("findMatchingBrace(result, openBrace)"),
            "raw event body check" to body.contains("val body = result.substring(openBrace + 1, closeBrace)")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("ScreenEvent.BackgroundRendered")""") &&
                body.contains("""executableCode.contains("renderBackground(GuiGraphics")""") &&
                body.contains("val executableResult = maskJavaCommentsAndLiterals(result)") &&
                body.contains("methodPattern.find(executableResult, cursor)") &&
                body.contains("findMatchingBrace(executableResult, openBrace)") &&
                body.contains("val body = executableResult.substring(openBrace + 1, closeBrace)") &&
                body.contains("javaDeclarationStartWithLeadingMetadata(result, match.range.first)"),
            "Screen background rendered migration must derive signatures and event-only bodies from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Screen background rendered migration must not treat comments or strings as migration evidence: $offenders"
        )
    }

    @Test
    fun `legacy HeartType sheet coordinate migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyHeartTypeSheetCoordinatesSource")
        assertTrue(start >= 0, "migrateLegacyHeartTypeSheetCoordinatesSource is missing")
        val end = source.indexOf("private fun migrateLegacyProjectilePortalBranchSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getX prefilter" to body.contains("""source.contains(".getX(")"""),
            "raw blit prefilter" to body.contains("""source.contains(".blit(")"""),
            "raw direct HeartType scan" to body.contains("containsMatchIn(source)"),
            "raw blit invocation rewrite" to body.contains("""rewriteJavaInvocationArguments(source, "blit")"""),
            "raw receiver collection" to body.contains(".findAll(source)"),
            "raw import scan" to body.contains("""source.contains("import net.minecraft.client.gui.Gui.HeartType;")"""),
            "raw getX receiver scan" to body.contains("receiverPattern.find(result, cursor)"),
            "raw getX paren matching" to body.contains("findMatchingParen(result, openParen)"),
            "raw helper existence scan" to body.contains("""result.contains("private static int modporterLegacyHeartTypeX(")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".getX(")""") &&
                body.contains("""executableCode.contains(".blit(")""") &&
                body.contains("containsMatchIn(executableCode)") &&
                body.contains("""rewriteExecutableJavaInvocationArguments(source, "blit")""") &&
                body.contains("""maskJavaCommentsAndLiterals(result).contains("private static int modporterLegacyHeartTypeX(")""") &&
                body.contains("private fun collectHeartTypeReceiverNames(source: String)") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("""executableCode.contains("import net.minecraft.client.gui.Gui.HeartType;")""") &&
                body.contains("val executableExpression = maskJavaCommentsAndLiterals(result)") &&
                body.contains("receiverPattern.find(executableExpression, cursor)") &&
                body.contains("findMatchingParen(executableExpression, openParen)"),
            "Legacy HeartType sheet coordinate migration must collect receivers and rewrite blit arguments from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy HeartType sheet coordinate migration must not treat comments or strings as source evidence: $offenders"
        )
    }

    @Test
    fun `legacy projectile portal branch removal uses executable branch evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyProjectilePortalBranchSource")
        assertTrue(start >= 0, "migrateLegacyProjectilePortalBranchSource is missing")
        val end = source.indexOf("private fun migrateColoredCutoutModelCopyLayerRenderSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw handleInsidePortal prefilter" to body.contains("""source.contains("handleInsidePortal(")"""),
            "raw TheEndGatewayBlockEntity prefilter" to body.contains("""source.contains("TheEndGatewayBlockEntity.teleportEntity")"""),
            "raw HitResult prefilter" to body.contains("""source.contains("HitResult.Type.BLOCK")"""),
            "raw block hit scan" to body.contains("blockHitPattern.find(result, cursor)"),
            "raw branch brace lookup" to body.contains("result.indexOf('{', match.range.last)"),
            "raw branch brace matching" to body.contains("findMatchingBrace(result, openBrace)"),
            "raw branch body evidence" to body.contains("val body = result.substring(openBrace + 1, closeBrace)")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("handleInsidePortal(")""") &&
                body.contains("""executableCode.contains("TheEndGatewayBlockEntity.teleportEntity")""") &&
                body.contains("""executableCode.contains("HitResult.Type.BLOCK")""") &&
                body.contains("val executableResult = maskJavaCommentsAndLiterals(result)") &&
                body.contains("blockHitPattern.find(executableResult, cursor)") &&
                body.contains("executableResult.indexOf('{', match.range.last)") &&
                body.contains("findMatchingBrace(executableResult, openBrace)") &&
                body.contains("val body = executableResult.substring(openBrace + 1, closeBrace)"),
            "Legacy projectile portal branch removal must locate removable branches from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy projectile portal branch removal must not delete branches using comments or strings as evidence: $offenders"
        )
    }

    @Test
    fun `colored cutout layer packed color migration uses executable invocation evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateColoredCutoutModelCopyLayerRenderSource")
        assertTrue(start >= 0, "migrateColoredCutoutModelCopyLayerRenderSource is missing")
        val end = source.indexOf("private fun migrateLegacyShearableSignaturesSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw colored cutout prefilter" to body.contains("""source.contains("coloredCutoutModelCopyLayerRender(")"""),
            "raw invocation rewrite" to body.contains("""rewriteJavaInvocationArguments(source, "coloredCutoutModelCopyLayerRender")"""),
            "raw FastColor import insertion" to body.contains("""addImportIfMissing(result, "net.minecraft.util.FastColor")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("coloredCutoutModelCopyLayerRender(")""") &&
                body.contains("""rewriteExecutableJavaInvocationArguments(source, "coloredCutoutModelCopyLayerRender")""") &&
                body.contains("if (args.size != 16)") &&
                body.contains("FastColor.ARGB32.colorFromFloat") &&
                body.contains("""addExecutableImportIfMissing(result, "net.minecraft.util.FastColor")"""),
            "Colored cutout layer migration must rewrite executable helper invocations before importing FastColor"
        )
        assertTrue(
            offenders.isEmpty(),
            "Colored cutout layer migration must not rewrite comments or strings: $offenders"
        )
    }

    @Test
    fun `legacy add layer skin migrations use executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("val skinModelCallMigrated = migrateLegacyPlayerSkinModelCallsSource")
        assertTrue(start >= 0, "legacy add layer skin migration call site is missing")
        val end = source.indexOf("private fun migrateJava21StrictWarningSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw direct skin prefilter" to "result.contains(\"event.getSkin(\\\"default\\\")\")",
            "raw direct skin replacement" to ".replace(\"event.getSkin(\\\"default\\\")\"",
            "raw loop scan on result" to "declarationPattern.find(result, cursor)",
            "raw loop brace matching" to "findMatchingBrace(result, openBrace)",
            "raw loop body extraction" to "result.substring(openBrace + 1, closeBrace)",
            "raw loop splice" to "result.substring(0, match.range.first)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("private fun migrateLegacyPlayerSkinModelCallsSource(source: String): String") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("sourceCallPattern.findAll(source)") &&
                body.contains("executableCallPattern.find(executableCode, match.range.first)") &&
                body.contains("private fun migrateLegacyAddLayersSkinNameLoopsSource(source: String): String") &&
                body.contains("sourceDeclarationPattern.find(source, cursor)") &&
                body.contains("executableDeclarationPattern.find(executableCode, match.range.first)") &&
                body.contains("findMatchingBrace(executableCode, openBrace)") &&
                body.contains("val body = executableCode.substring(openBrace + 1, closeBrace)") &&
                body.contains("return applyStringEdits(source, edits)"),
            "Legacy AddLayers skin migrations must verify executable source before rewriting direct calls or loops"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy AddLayers skin migrations must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `Java 21 redundant cast migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRedundantJava21Casts")
        assertTrue(start >= 0, "migrateRedundantJava21Casts is missing")
        val end = source.indexOf("private fun migrateBlockDefaultStateThisEscapeWarning", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw variable scan" to ".findAll(result)",
            "raw float cast replacement" to ".replace(result, variable)",
            "raw vector cast replacement" to ".replace(result) { match -> match.groupValues[1] }"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("findAll(executableCode)") &&
                body.contains("maskJavaCommentsAndLiterals(result)") &&
                body.contains("replaceExecutableRegex(result, Regex"),
            "Java 21 redundant cast migration must inspect and rewrite executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Java 21 redundant cast migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `Mth trigonometry float argument migration uses executable method source`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateMthTrigonometryFloatArguments")
        assertTrue(start >= 0, "migrateMthTrigonometryFloatArguments is missing")
        val end = source.indexOf("private fun migrateMapDecorationRecordSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw Mth.sin prefilter" to """source.contains("Mth.sin(")""",
            "raw Mth.cos prefilter" to """source.contains("Mth.cos(")""",
            "raw method scan" to "methodPattern.find(result, cursor)",
            "raw brace matching" to "findMatchingBrace(result, openBrace)",
            "raw method extraction" to "result.substring(method.range.first",
            "raw method splice" to "result.substring(0, method.range.first)",
            "unanchored method pattern" to "Regex(\"\"\"(?m)(?:public|protected|private)?"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("Mth.sin(")""") &&
                body.contains("""executableCode.contains("Mth.cos(")""") &&
                body.contains("val methodPattern = Regex(\"\"\"(?m)^[ \\t]*") &&
                body.contains("methodPattern.find(executableCode, cursor)") &&
                body.contains("findMatchingBrace(executableCode, openBrace)") &&
                body.contains("val methodExecutableText = executableCode.substring(methodRange)") &&
                body.contains("findAll(methodExecutableText)") &&
                body.contains("source.substring(absoluteRange) == match.value") &&
                body.contains("return applyStringEdits(source, edits)"),
            "Mth trigonometry migration must collect variables and rewrite calls from executable Java method source"
        )
        assertTrue(
            offenders.isEmpty(),
            "Mth trigonometry migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy entity type AABB migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyEntityTypeAabbCalls")
        assertTrue(start >= 0, "migrateLegacyEntityTypeAabbCalls is missing")
        val end = source.indexOf("private fun findExpressionReceiverStart", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getAABB prefilter" to """source.contains(".getAABB(")""",
            "raw token scan" to "source.indexOf(token, cursor)",
            "raw StringBuilder migration" to "StringBuilder()",
            "manual changed flag" to "var changed = false"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""val callPattern = Regex(""" + "\"\"\"" + """\.\s*getAABB\s*\(""") &&
                body.contains("callPattern.findAll(executableCode)") &&
                body.contains("findMatchingParen(executableCode, openParen)") &&
                body.contains("val receiverEnd = previousNonWhitespaceEnd(executableCode, tokenIndex)") &&
                body.contains("findExpressionReceiverStart(executableCode, receiverEnd)") &&
                body.contains("edits += receiverStart..closeParen") &&
                body.contains("private fun previousNonWhitespaceEnd(source: String, offset: Int)") &&
                body.contains("return applyStringEdits(source, edits)"),
            "Legacy EntityType.getAABB migration must derive call ranges from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy EntityType.getAABB migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy block valid spawn migration uses executable method and constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyBlockValidSpawnOverrides")
        assertTrue(start >= 0, "migrateLegacyBlockValidSpawnOverrides is missing")
        val end = source.indexOf("private fun migrateLegacyBlockAndEntityCapabilityAccessors", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw isValidSpawn prefilter" to """source.contains("isValidSpawn(")""",
            "raw EntityType prefilter" to """source.contains("EntityType<?>")""",
            "raw method extraction" to """javaMethodText(source, "isValidSpawn")""",
            "raw method removal" to """removeMethodByName(source, "isValidSpawn")""",
            "raw constructor scan" to "constructorPattern.find(result)",
            "raw isValidSpawn usage scan" to """containsMatchIn(result)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("javaMethodRanges(executableCode)") &&
                body.contains(".singleOrNull { it.name == \"isValidSpawn\"") &&
                body.contains("val executableMethodText = executableCode.substring(method.range)") &&
                body.contains("val returnRange = returnMatch.groups[1]?.range ?: return source") &&
                body.contains("var result = source.removeRange(method.range.first, methodEnd)") &&
                body.contains("val executableResult = maskJavaCommentsAndLiterals(result)") &&
                body.contains("constructorPattern.find(executableResult)") &&
                body.contains("maskJavaCommentsAndLiterals(result)") &&
                body.contains("maskJavaCommentsAndLiterals(withoutEntityType)") &&
                body.contains("maskJavaCommentsAndLiterals(withoutSpawnPlacements)"),
            "Legacy isValidSpawn migration must derive method removal, return expression, and constructor insertion from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy isValidSpawn migration must not use comments or strings as migration evidence: $offenders"
        )
    }

    @Test
    fun `legacy block and entity capability accessor migration uses executable typed receivers`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyBlockAndEntityCapabilityAccessors")
        assertTrue(start >= 0, "migrateLegacyBlockAndEntityCapabilityAccessors is missing")
        val end = source.indexOf("private fun consumerToItemHandlerStatement", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw capability prefilter" to """source.contains("getCapability(Capabilities.ItemHandler.")""",
            "raw block ifPresent replacement" to "blockIfPresentPattern.replace(result)",
            "raw entity ifPresent replacement" to "entityIfPresentPattern.replace(result)",
            "untyped entity fallback" to "if (receiver in blockEntityVariables) return@replace match.value",
            "full-file BLOCK-to-ENTITY replacement" to """Capabilities\.ItemHandler\.BLOCK\)""",
            "raw block entity access collection" to "collectBlockEntityAccesses(result)",
            "raw block entity variable collection" to "collectBlockEntityVariables(result)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("collectBlockEntityAccesses(executableCode)") &&
                body.contains("collectBlockEntityVariables(executableCode)") &&
                body.contains("blockIfPresentPattern.findAll(executableCode)") &&
                body.contains("val executableAfterBlockEdits = maskJavaCommentsAndLiterals(result)") &&
                body.contains("entityIfPresentPattern.findAll(executableAfterBlockEdits)") &&
                body.contains("isEntityCapabilityReceiver(executableAfterBlockEdits") &&
                body.contains("applyStringEdits(result, blockEdits)") &&
                body.contains("applyStringEdits(result, entityEdits)") &&
                body.contains("javaLocalVariableTypes(scope)[receiver]") &&
                body.contains("javaInheritanceIndex.inherits(receiverType, entityBaseTypes)"),
            "Legacy block/entity capability migration must use executable Java and typed receiver evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy block/entity capability migration must not rewrite raw source, comments, strings, or untyped receivers: $offenders"
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
    fun `legacy reviveCaps migration uses executable method range`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyCapabilityReviveCapsSource")
        assertTrue(start >= 0, "migrateLegacyCapabilityReviveCapsSource is missing")
        val end = source.indexOf("private fun cleanupLegacyCapabilityOverrideImports", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw revive method extraction" to """javaDeclaredMethodText(source, "reviveCaps")""",
            "raw clearRemoved prefilter" to """source.contains("void clearRemoved(")""",
            "raw method replacement" to "source.replace(methodText"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val method = javaMethodRanges(executableCode).singleOrNull") &&
                body.contains("val methodText = source.substring(method.range)") &&
                body.contains("source.replaceRange(method.range, migratedMethod)"),
            "Legacy reviveCaps migration must locate and replace only executable Java method ranges"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy reviveCaps migration must not use comments, strings, or raw method text as lifecycle evidence: $offenders"
        )
    }

    @Test
    fun `legacy capability cache cleanup uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun removeLegacyCapabilityCacheOnlyMethods")
        assertTrue(start >= 0, "removeLegacyCapabilityCacheOnlyMethods is missing")
        val end = source.indexOf("private data class LegacyCapabilityFacadeSpec", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw cache method extraction" to "javaDeclaredMethodText(result, methodName)",
            "raw method field evidence" to "fields.none { methodText.contains(it) }",
            "raw method removal" to "result.replace(methodText",
            "raw if for block scan" to ".find(result, searchStart)",
            "raw paren matching" to "findMatchingParen(result, openParen)",
            "raw brace matching" to "findMatchingBrace(result, openBrace)",
            "raw block field evidence" to "containsMatchIn(blockText)",
            "raw LazyOptional field line evidence" to "line.contains(\"LazyOptional\")"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("javaMethodRanges(executableCode)") &&
                body.contains("val executableMethodText = executableCode.substring(method.range)") &&
                body.contains("result.removeRange(method.range)") &&
                body.contains("var executableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("find(executableCode, searchStart)") &&
                body.contains("findMatchingParen(executableCode, openParen)") &&
                body.contains("findMatchingBrace(executableCode, openBrace)") &&
                body.contains("val executableBlockText = executableCode.substring") &&
                body.contains("val executableLines = maskJavaCommentsAndLiterals(source).splitToSequence"),
            "Legacy capability cache cleanup must delete only executable cache methods, blocks, and fields"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy capability cache cleanup must not delete comments or strings as cache logic: $offenders"
        )
    }

    @Test
    fun `JEI recipe category background migration uses executable method ranges`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateJeiRecipeCategoryBackgroundApi")
        assertTrue(start >= 0, "migrateJeiRecipeCategoryBackgroundApi is missing")
        val end = source.indexOf("private fun migrateRequiredRemovalWarningAnnotations", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw JEI prefilter" to """source.contains("IRecipeCategory<")""",
            "raw background method extraction" to """javaDeclaredMethodText(source, "getBackground")""",
            "raw background field check" to "containsMatchIn(source)",
            "raw background method removal" to "source.replace(backgroundMethod",
            "raw getIcon extraction" to """javaDeclaredMethodText(result, "getIcon")""",
            "raw draw extraction" to """javaDeclaredMethodText(result, "draw")""",
            "raw draw method offset" to "result.indexOf(drawMethod)",
            "raw setRecipe extraction" to """javaDeclaredMethodText(result, "setRecipe")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val backgroundMethod = javaMethodRanges(executableCode).singleOrNull") &&
                body.contains("val executableBackgroundMethodText = executableCode.substring(backgroundMethod.range)") &&
                body.contains("source.removeRange(backgroundMethod.range)") &&
                body.contains("var resultExecutableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("javaMethodRanges(resultExecutableCode).firstOrNull { it.name == \"getIcon\" }") &&
                body.contains("javaMethodRanges(resultExecutableCode).firstOrNull { it.name == \"draw\" }") &&
                body.contains("val executableDrawMethodText = resultExecutableCode.substring(drawMethod.range)") &&
                body.contains("result.replaceRange(it.range, sizeMethods + \"\\n\\n\" + result.substring(it.range))") &&
                body.contains("result.replaceRange(it.range, draw + \"\\n\\n\" + result.substring(it.range))"),
            "JEI recipe category background migration must locate and edit executable Java methods only"
        )
        assertTrue(
            offenders.isEmpty(),
            "JEI recipe category background migration must not use comments or strings as API evidence: $offenders"
        )
    }

    @Test
    fun `legacy skull owner verify migration uses executable method range`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacySkullOwnerVerifyComponentsSource")
        assertTrue(start >= 0, "migrateLegacySkullOwnerVerifyComponentsSource is missing")
        val end = source.indexOf("private fun migrateLegacyItemStackTagWrites", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw verifyTagAfterLoad prefilter" to """source.contains("verifyTagAfterLoad")""",
            "raw SkullOwner prefilter" to """source.contains("SkullOwner")""",
            "raw method extraction" to """javaDeclaredMethodText(source, "verifyTagAfterLoad")""",
            "raw method evidence" to """methodText.contains("SkullOwner")""",
            "raw method replacement" to "source.replace(methodText"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val code = maskJavaComments(source)") &&
                body.contains("val method = javaMethodRanges(executableCode).singleOrNull") &&
                body.contains("val methodText = source.substring(method.range)") &&
                body.contains("val methodCode = maskJavaComments(methodText)") &&
                body.contains("source.replaceRange(method.range, replacement)"),
            "Legacy SkullOwner verify migration must locate verifyTagAfterLoad from executable Java method ranges while preserving executable NBT string-key evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy SkullOwner verify migration must not use comments, strings, or raw method text as method evidence: $offenders"
        )
    }

    @Test
    fun `legacy shearable signature migration uses executable method ranges`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyShearableSignaturesSource")
        assertTrue(start >= 0, "migrateLegacyShearableSignaturesSource is missing")
        val end = source.indexOf("private fun migrateLegacyIgnoreExplosionSignatureSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw onSheared prefilter" to """source.contains("onSheared(")""",
            "raw isShearable prefilter" to """source.contains("isShearable(")""",
            "raw onSheared replacement" to "onShearedPattern.replace(result)",
            "raw onSheared method extraction" to """javaDeclaredMethodText(source, "onSheared")""",
            "raw isShearable replacement" to ").replace(result) { match ->"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val methodRanges = javaMethodRanges(executableCode)") &&
                body.contains("onShearedPattern.findAll(executableCode)") &&
                body.contains("val methodText = executableCode.substring(method.range)") &&
                body.contains("methodText.substring(bodyStart)") &&
                body.contains("isShearablePattern.findAll(executableCode)") &&
                body.contains("applyStringEdits(source, edits)"),
            "Legacy shearable signature migration must locate signatures and fortune usage from executable Java source"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy shearable signature migration must not use comments, strings, or raw method text as signature evidence: $offenders"
        )
    }

    @Test
    fun `legacy ignore explosion signature migration uses executable source`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyIgnoreExplosionSignatureSource")
        assertTrue(start >= 0, "migrateLegacyIgnoreExplosionSignatureSource is missing")
        val end = source.indexOf("private fun migrateDimensionSpecialEffectsCloudSignatureSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw ignoreExplosion prefilter" to """source.contains("ignoreExplosion(")""",
            "raw signature replacement" to ".replace(source) { match ->",
            "manual changed flag" to "var changed = false"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("signaturePattern.findAll(executableCode)") &&
                body.contains("applyStringEdits(source, edits)") &&
                body.contains("addImportIfMissing(result, \"net.minecraft.world.level.Explosion\")"),
            "Legacy ignoreExplosion signature migration must rewrite only executable method signatures"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy ignoreExplosion signature migration must not use comments or strings as signature evidence: $offenders"
        )
    }

    @Test
    fun `dimension cloud render signature migration uses executable method ranges`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateDimensionSpecialEffectsCloudSignatureSource")
        assertTrue(start >= 0, "migrateDimensionSpecialEffectsCloudSignatureSource is missing")
        val end = source.indexOf("private fun migrateCustomDataComponentsSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw renderClouds prefilter" to """source.contains("renderClouds(")""",
            "raw signature replacement" to ").replace(source) { match ->",
            "raw method extraction" to """javaDeclaredMethodText(result, "renderClouds")""",
            "raw method evidence" to "cloudMethod.contains",
            "raw method replacement" to "result.replace(cloudMethod"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("signaturePattern.findAll(executableCode)") &&
                body.contains("applyStringEdits(source, signatureEdits)") &&
                body.contains("val resultExecutableCode = if (changed) maskJavaCommentsAndLiterals(result) else executableCode") &&
                body.contains("javaMethodRanges(resultExecutableCode)") &&
                body.contains(".filter { it.name == \"renderClouds\" }") &&
                body.contains("val executableMethodText = resultExecutableCode.substring(method.range)") &&
                body.contains("val methodText = result.substring(method.range)") &&
                body.contains("poseEdits += (method.range.first + pushPoseMatch.range.first)..") &&
                body.contains("applyStringEdits(result, poseEdits)"),
            "Dimension cloud renderer migration must edit only executable renderClouds signatures and method bodies"
        )
        assertTrue(
            offenders.isEmpty(),
            "Dimension cloud renderer migration must not use comments, strings, or raw method text as renderClouds evidence: $offenders"
        )
    }

    @Test
    fun `custom data component child tag migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCustomDataComponentsSource")
        assertTrue(start >= 0, "migrateCustomDataComponentsSource is missing")
        val end = source.indexOf("private fun migrateBrewingRecipeRegistrationSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw item getTagElement prefilter" to body.contains("""result.contains("stack.getTagElement(")"""),
            "raw item removeTagKey prefilter" to body.contains("""result.contains("stack.removeTagKey(")"""),
            "raw custom data prefilter" to body.contains("""result.contains("DataComponents.CUSTOM_DATA")"""),
            "raw getTagElement replacement" to body.contains(".replace(\"stack.getTagElement("),
            "raw removeTagKey replacement" to body.contains(".replace(\"stack.removeTagKey("),
            "raw fluid getChildTag replacement" to body.contains(".replace(Regex(\"\"\"CompoundTag\\s+(\\w+)"),
            "raw setFluidId body replacement" to body.contains("replaceMethodBody(result, \"setFluidId"),
            "raw import insertion" to body.contains("addImportIfMissing(result,"),
            "raw item helper presence scan" to body.contains("""source.contains("getCustomDataChild(ItemStack stack")"""),
            "raw fluid helper presence scan" to body.contains("""source.contains("getFluidCustomDataChild(FluidStack stack")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("var executableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("""executableCode.contains("stack.getTagElement(")""") &&
                body.contains("""executableCode.contains("stack.removeTagKey(")""") &&
                body.contains("""executableCode.contains("DataComponents.CUSTOM_DATA")""") &&
                body.contains("replaceExecutableRegex(result, Regex(Regex.escape(legacyCustomFluidTagCopy)))") &&
                body.contains("""\bstack\.getTagElement\s*\(""") &&
                body.contains("""\bstack\.removeTagKey\s*\(""") &&
                body.contains("replaceExecutableMethodBody(result, \"setFluidId") &&
                body.contains("""addExecutableImportIfMissing(result, "net.minecraft.core.component.DataComponents")""") &&
                body.contains("""executableCode.contains("FluidStack")""") &&
                body.contains("""executableCode.contains(".getOrCreateChildTag(")""") &&
                body.contains("""executableCode.contains(".getChildTag(")""") &&
                body.contains("maskJavaCommentsAndLiterals(source).contains(\"getCustomDataChild(ItemStack stack\")") &&
                body.contains("maskJavaCommentsAndLiterals(source).contains(\"getFluidCustomDataChild(FluidStack stack\")") &&
                source.contains("private fun replaceExecutableMethodBody(source: String, methodName: String, replacement: String)") &&
                source.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                source.contains("findMatchingBrace(executableCode, openBrace)"),
            "CustomData child tag migration must rewrite executable ItemStack/FluidStack tag APIs and methods only"
        )
        assertTrue(
            offenders.isEmpty(),
            "CustomData child tag migration must not use comments or strings as API evidence: $offenders"
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
    fun `registry access empty fallback migration uses executable token evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRegistryAccessEmptyFallbacks")
        assertTrue(start >= 0, "migrateRegistryAccessEmptyFallbacks is missing")
        val end = source.indexOf("private fun isRegistryAccessEmptyExpression", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw RegistryAccess.EMPTY prefilter" to body.contains("""source.contains("RegistryAccess.EMPTY")"""),
            "raw source scan" to body.contains("source.indexOf(qualifiedEmpty"),
            "mutable result scan" to body.contains("result.indexOf(qualifiedEmpty"),
            "raw result splice" to body.contains("result.substring(0, index) + replacement"),
            "cursor advanced by replacement length" to body.contains("cursor = index + replacement.length")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("executableCode.contains(qualifiedEmpty)") &&
                body.contains("executableCode.indexOf(qualifiedEmpty, cursor)") &&
                body.contains("registryAccessExpressionAt(source, index") &&
                body.contains("edits += index until index + qualifiedEmpty.length to replacement") &&
                body.contains("return applyStringEdits(source, edits)"),
            "RegistryAccess.EMPTY fallback migration must replace only executable token ranges"
        )
        assertTrue(
            offenders.isEmpty(),
            "RegistryAccess.EMPTY fallback migration must not rewrite comments or string literals: $offenders"
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
    fun `json reload deserializer migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRemovedJsonReloadDeserializersSource")
        assertTrue(start >= 0, "migrateRemovedJsonReloadDeserializersSource is missing")
        val end = source.indexOf("private fun migrateModLoadingContextRegisterConfigSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source prefilter" to body.contains("""source.contains("Deserializers.createFunctionSerializer().create()")"""),
            "raw source replacement" to body.contains("source.replace(\"Deserializers.createFunctionSerializer().create()\""),
            "raw Deserializers import usage scan" to body.contains("""withoutDeserializers.contains("Deserializers.")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("Deserializers.createFunctionSerializer().create()")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("""\bDeserializers\.createFunctionSerializer\(\)\.create\(\)""") &&
                body.contains("maskJavaCommentsAndLiterals(withoutDeserializers).contains(\"Deserializers.\")"),
            "JSON reload deserializer migration must rewrite executable calls and clean imports from executable usage"
        )
        assertTrue(
            offenders.isEmpty(),
            "JSON reload deserializer migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `mod loading context config migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateModLoadingContextRegisterConfigSource")
        assertTrue(start >= 0, "migrateModLoadingContextRegisterConfigSource is missing")
        val end = source.indexOf("private fun migrateMobEffectHolderCallsSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source prefilter" to body.contains("""source.contains("ModLoadingContext.get().registerConfig(")"""),
            "raw container scan" to body.contains(".find(source)"),
            "raw active container replacement" to body.contains("replace(\"ModLoadingContext.get().getActiveContainer().registerConfig("),
            "raw context replacement" to body.contains("replace(\"ModLoadingContext.get().registerConfig("),
            "raw ModLoadingContext import usage scan" to body.contains("""withoutImport.contains("ModLoadingContext.")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("ModLoadingContext.get().registerConfig(")""") &&
                body.contains(".findAll(executableCode)") &&
                body.contains(".distinct()") &&
                body.contains(".singleOrNull()") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("""\bModLoadingContext\.get\(\)\.registerConfig\(""") &&
                body.contains("maskJavaCommentsAndLiterals(withoutImport).contains(\"ModLoadingContext.\")"),
            "ModLoadingContext config migration must derive the container and calls from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "ModLoadingContext config migration must not use comments or strings as source evidence: $offenders"
        )
    }

    @Test
    fun `INBTSerializable holder lookup migration uses executable type body evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateINBTSerializableHolderLookupSource")
        assertTrue(start >= 0, "migrateINBTSerializableHolderLookupSource is missing")
        val end = source.indexOf("private fun migrateTooltipContextImportsSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source prefilter" to body.contains("""source.contains("serializeNBT()")"""),
            "raw implements prefilter" to body.contains("""source.contains("implements")"""),
            "raw INBTSerializable prefilter" to body.contains("""source.contains("INBTSerializable")"""),
            "raw regex replacement" to body.contains(".replace(result)")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""\bdeserializeNBT\s*\(\s*CompoundTag\b""") &&
                body.contains("containsMatchIn(executableCode)") &&
                body.contains("inbtSerializableTypeBodyRanges(source).isEmpty()") &&
                body.contains("replaceExecutableRegex(current, pattern)") &&
                body.contains("ranges.any { match.range.first in it }") &&
                body.contains("private fun inbtSerializableTypeBodyRanges(source: String)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""\b(?:implements|extends)\b""") &&
                body.contains("""\bINBTSerializable\b""") &&
                body.contains("findMatchingBrace(executableCode, openBrace)"),
            "INBTSerializable holder lookup migration must use executable serializable type body ranges"
        )
        assertTrue(
            offenders.isEmpty(),
            "INBTSerializable holder lookup migration must not use raw whole-file source evidence: $offenders"
        )
    }

    @Test
    fun `tooltip context import migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateTooltipContextImportsSource")
        assertTrue(start >= 0, "migrateTooltipContextImportsSource is missing")
        val end = source.indexOf("private fun migrateRemovedJsonReloadDeserializersSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val vanillaFunctionStart = source.indexOf("private fun migrateVanilla121ApiSource")
        assertTrue(vanillaFunctionStart >= 0, "migrateVanilla121ApiSource is missing")
        val vanillaStart = source.indexOf("val resultExecutableCode = maskJavaCommentsAndLiterals(result)", vanillaFunctionStart)
        assertTrue(vanillaStart >= 0, "migrateVanilla121ApiSource final executable import gate is missing")
        val vanillaEnd = source.indexOf("if (needsOverlayTexture", vanillaStart + 1)
        assertTrue(vanillaEnd > vanillaStart, "migrateVanilla121ApiSource final import gate boundary is missing")
        val vanillaImportBody = source.substring(vanillaStart, vanillaEnd)
        val offenders = listOf(
            "raw TooltipContext scan" to body.contains("""source.contains("Item.TooltipContext")"""),
            "raw Item import scan" to body.contains("""source.contains("import net.minecraft.world.item.Item;")"""),
            "raw import insertion" to body.contains("addImportIfMissing(source, \"net.minecraft.world.item.Item\")"),
            "raw vanilla TooltipContext import scan" to vanillaImportBody.contains("""result.contains("Item.TooltipContext")"""),
            "raw vanilla Item import insertion" to vanillaImportBody.contains("addImportIfMissing(result, \"net.minecraft.world.item.Item\")")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("Item.TooltipContext")""") &&
                body.contains("""executableCode.contains("import net.minecraft.world.item.Item;")""") &&
                body.contains("addExecutableImportIfMissing(source, \"net.minecraft.world.item.Item\")") &&
                vanillaImportBody.contains("val resultExecutableCode = maskJavaCommentsAndLiterals(result)") &&
                vanillaImportBody.contains("""resultExecutableCode.contains("Item.TooltipContext")""") &&
                vanillaImportBody.contains("addExecutableImportIfMissing(result, \"net.minecraft.world.item.Item\")"),
            "TooltipContext import migration must derive the need for import from executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "TooltipContext import migration must not treat comments or strings as source evidence: $offenders"
        )
    }

    @Test
    fun `mob effect holder migration scopes executable method evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateMobEffectHolderCallsSource")
        assertTrue(start >= 0, "migrateMobEffectHolderCallsSource is missing")
        val end = source.indexOf("private fun migrateHolderValueAccessorsSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw source prefilter" to body.contains("""source.contains("MobEffect")"""),
            "raw holder variable scan" to body.contains(".findAll(result)"),
            "raw wrapAsHolder replacement" to body.contains("""result.replace("BuiltInRegistries.MOB_EFFECT.wrapAsHolder("""),
            "whole-file holder api parameter scan" to body.contains("containsMatchIn(result)"),
            "raw BuiltInRegistries import usage scan" to body.contains("""result.contains("BuiltInRegistries.MOB_EFFECT")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("MobEffect")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains(".findAll(maskJavaCommentsAndLiterals(result))") &&
                body.contains("for (method in javaMethodRangesIncludingDefault(result).asReversed())") &&
                body.contains("val methodText = result.substring(method.range)") &&
                body.contains("javaMethodParameters(methodText)") &&
                body.contains("""simpleJavaTypeName(it.type) == "MobEffect"""") &&
                body.contains("val executableMethodText = maskJavaCommentsAndLiterals(methodText)") &&
                body.contains("containsMatchIn(executableMethodText)") &&
                body.contains("result = result.replaceRange(method.range, migratedMethod)") &&
                body.contains("""maskJavaCommentsAndLiterals(result).contains("BuiltInRegistries.MOB_EFFECT")"""),
            "MobEffect holder migration must derive holder parameters from executable current-method evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "MobEffect holder migration must not rewrite comments or use whole-file same-name parameter inference: $offenders"
        )
    }

    @Test
    fun `legacy game event constructor migration uses executable call and constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyGameEventConstructors")
        assertTrue(start >= 0, "migrateLegacyGameEventConstructors is missing")
        val end = source.indexOf("private fun migrateLegacyBannerPatternConstructors", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val helperStart = source.indexOf("private fun rewriteExecutableJavaNew")
        assertTrue(helperStart >= 0, "rewriteExecutableJavaNew is missing")
        val helperEnd = source.indexOf("private fun rewriteSuperConstructorCalls", helperStart + 1).let {
            if (it < 0) source.length else it
        }
        val helperBody = source.substring(helperStart, helperEnd)
        val offenders = listOf(
            "raw GameEvent prefilter" to """source.contains("new GameEvent(")""",
            "raw DeferredRegister prefilter" to """source.contains("DeferredRegister")""",
            "raw register collection" to ".findAll(source)",
            "raw supplier constructor rewrite" to """rewriteJavaNew(supplier, "GameEvent")""",
            "raw register rewrite" to """rewriteJavaCall(source, "register")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("new GameEvent(")""") &&
                body.contains("""executableCode.contains("DeferredRegister")""") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("""rewriteExecutableJavaNew(supplier, "GameEvent")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "register")""") &&
                body.contains("stringLiteral.matches(registerId)") &&
                body.contains("legacyId != registerId"),
            "Legacy GameEvent constructor migration must derive registers, register calls, and nested constructors from executable Java"
        )
        assertTrue(
            helperBody.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                helperBody.contains("executableCode.indexOf(token, cursor)") &&
                helperBody.contains("findMatchingParen(executableCode, openParen)") &&
                helperBody.contains("splitTopLevelJavaArgs(result.substring(openParen + 1, closeParen))"),
            "Executable constructor rewriting must find constructors in masked Java while preserving original argument text"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy GameEvent constructor migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `deferred holder game event arguments use executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateDeferredHolderGameEventArguments")
        assertTrue(start >= 0, "migrateDeferredHolderGameEventArguments is missing")
        val end = source.indexOf("private fun migrateLegacyCommonHooksToolChecks", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw gameEvent prefilter" to """source.contains(".gameEvent(")""",
            "raw get prefilter" to """source.contains(".get()")""",
            "raw gameEvent rewrite" to """rewriteJavaCall(source, "gameEvent")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".gameEvent(")""") &&
                body.contains("""executableCode.contains(".get()")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "gameEvent")""") &&
                body.contains("provenHolderExpression(holderExpression)"),
            "DeferredHolder GameEvent migration must use executable call evidence and proven holder fields"
        )
        assertTrue(
            offenders.isEmpty(),
            "DeferredHolder GameEvent migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `tooltip part hiding migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyTooltipPartHiding")
        assertTrue(start >= 0, "migrateLegacyTooltipPartHiding is missing")
        val end = source.indexOf("private fun parseJavaConstructorParameters", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw hideTooltipPart prefilter" to """source.contains(".hideTooltipPart(")""",
            "raw tooltip part prefilter" to """source.contains("ItemStack.TooltipPart.ADDITIONAL")""",
            "raw hideTooltipPart rewrite" to """rewriteJavaCall(source, "hideTooltipPart")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".hideTooltipPart(")""") &&
                body.contains("""executableCode.contains("ItemStack.TooltipPart.ADDITIONAL")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "hideTooltipPart")""") &&
                body.contains("DataComponents.HIDE_ADDITIONAL_TOOLTIP"),
            "Tooltip part hiding migration must use executable call evidence and preserve real replacement semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "Tooltip part hiding migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `aabb vec3 encapsulating migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateAabbVec3EncapsulatingFullBlocks")
        assertTrue(start >= 0, "migrateAabbVec3EncapsulatingFullBlocks is missing")
        val end = source.indexOf("private fun javaDeclaredSimpleTypeSets", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw encapsulatingFullBlocks prefilter" to """source.contains("AABB.encapsulatingFullBlocks(")""",
            "raw encapsulatingFullBlocks rewrite" to """rewriteJavaCall(source, "encapsulatingFullBlocks")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("AABB.encapsulatingFullBlocks(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "encapsulatingFullBlocks")""") &&
                body.contains("""receiver != "AABB"""") &&
                body.contains("isAabbVec3Expression(it, declaredTypes)"),
            "AABB Vec3 encapsulating migration must use executable call evidence and proven Vec3 arguments"
        )
        assertTrue(
            offenders.isEmpty(),
            "AABB Vec3 encapsulating migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `client level entity insertion migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateClientLevelEntityInsertionCalls")
        assertTrue(start >= 0, "migrateClientLevelEntityInsertionCalls is missing")
        val end = source.indexOf("private fun migrateLegacyResourceLocationValidationSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw putNonPlayerEntity prefilter" to """source.contains(".putNonPlayerEntity(")""",
            "raw putNonPlayerEntity rewrite" to """rewriteJavaCall(source, "putNonPlayerEntity")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".putNonPlayerEntity(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "putNonPlayerEntity")""") &&
                body.contains("args.size != 2") &&
                body.contains(".addEntity("),
            "ClientLevel entity insertion migration must inspect executable calls and preserve two-argument replacement semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "ClientLevel entity insertion migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy finalize spawn call migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteLegacyFinalizeSpawnCalls")
        assertTrue(start >= 0, "rewriteLegacyFinalizeSpawnCalls is missing")
        val end = source.indexOf("private fun migrateLegacyFinalizeSpawnMixinDescriptors", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw finalizeSpawn prefilter" to """source.contains(".finalizeSpawn(")""",
            "raw SpawnGroupData prefilter" to """source.contains("SpawnGroupData")""",
            "raw MobSpawnType prefilter" to """source.contains("MobSpawnType")""",
            "raw finalizeSpawn rewrite" to """rewriteJavaCall(source, "finalizeSpawn")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".finalizeSpawn(")""") &&
                body.contains("""executableCode.contains("SpawnGroupData")""") &&
                body.contains("""executableCode.contains("MobSpawnType")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "finalizeSpawn")""") &&
                body.contains("args.size != 5") &&
                body.contains("args.take(4)"),
            "Legacy finalizeSpawn call migration must inspect executable calls and preserve five-argument tag removal semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy finalizeSpawn call migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `randomizable container loot table migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRandomizableContainerLootTableCalls")
        assertTrue(start >= 0, "migrateRandomizableContainerLootTableCalls is missing")
        val end = source.indexOf("private fun migrateContainerEntityLootTableResourceKeys", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw setLootTable prefilter" to """source.contains("RandomizableContainerBlockEntity.setLootTable(")""",
            "raw setLootTable rewrite" to """rewriteJavaCall(source, "setLootTable")""",
            "raw old import usage check" to """containsMatchIn(withoutBlockEntityImport)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("RandomizableContainerBlockEntity.setLootTable(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "setLootTable")""") &&
                body.contains("""receiver != "RandomizableContainerBlockEntity"""") &&
                body.contains("args.size != 4") &&
                body.contains("maskJavaCommentsAndLiterals(withoutBlockEntityImport)"),
            "RandomizableContainerBlockEntity.setLootTable migration must use executable call and import usage evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "RandomizableContainerBlockEntity.setLootTable migration must not rewrite comments or preserve imports from literals: $offenders"
        )
    }

    @Test
    fun `armor foil buffer migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateArmorFoilBufferCalls")
        assertTrue(start >= 0, "migrateArmorFoilBufferCalls is missing")
        val end = source.indexOf("private fun migrateArmorTrimComponentRendering", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getArmorFoilBuffer prefilter" to """source.contains("ItemRenderer.getArmorFoilBuffer(")""",
            "raw getArmorFoilBuffer rewrite" to """rewriteJavaCall(source, "getArmorFoilBuffer")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("ItemRenderer.getArmorFoilBuffer(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "getArmorFoilBuffer")""") &&
                body.contains("""receiver != "ItemRenderer"""") &&
                body.contains("args.size != 4") &&
                body.contains("args[3].trim()"),
            "Armor foil buffer migration must use executable call evidence and preserve glint argument semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "Armor foil buffer migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `state switching button texture migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun containsLegacyStateSwitchingButtonInitCall")
        assertTrue(start >= 0, "containsLegacyStateSwitchingButtonInitCall is missing")
        val end = source.indexOf("private fun legacyStateSwitchingButtonAssignmentTarget", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw initTextureValues detector" to """source.indexOf("initTextureValues(")""",
            "raw superclass match" to """containsMatchIn(source)""",
            "raw superclass replacement" to "source.replace(Regex(",
            "raw initTextureValues rewrite" to """rewriteJavaCall(source, "initTextureValues")""",
            "raw LegacyStateSwitchingButton skip check" to """source.contains("extends LegacyStateSwitchingButton")""",
            "raw object creation scan" to """find(source, cursor)""",
            "global object creation replace" to "result.replace(before, after)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.indexOf("initTextureValues(", cursor)""") &&
                body.contains("findMatchingParen(executableCode, openParen)") &&
                body.contains("""containsMatchIn(maskJavaCommentsAndLiterals(source))""") &&
                body.contains("replaceExecutableRegex(source, Regex(") &&
                body.contains("""rewriteExecutableJavaCall(source, "initTextureValues")""") &&
                body.contains("""executableCode.contains("extends LegacyStateSwitchingButton")""") &&
                body.contains("new\\s+StateSwitchingButton") &&
                body.contains(".find(executableCode, cursor)") &&
                body.contains("replacements.asReversed().forEach"),
            "StateSwitchingButton texture migration must use executable source for detection, rewriting, and object creation replacement"
        )
        assertTrue(
            offenders.isEmpty(),
            "StateSwitchingButton texture migration must not infer or rewrite from comments/strings: $offenders"
        )
    }

    @Test
    fun `inventory screen entity preview migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateInventoryScreenEntityPreviewCalls")
        assertTrue(start >= 0, "migrateInventoryScreenEntityPreviewCalls is missing")
        val end = source.indexOf("private fun migrateLegacyTesselatorSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw InventoryScreen prefilter" to """source.contains("InventoryScreen.renderEntityInInventory")""",
            "raw follows mouse rewrite" to """rewriteJavaCall(source, "renderEntityInInventoryFollowsMouse")""",
            "raw direct preview rewrite" to """rewriteJavaCall(result, "renderEntityInInventory")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("InventoryScreen.renderEntityInInventory")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "renderEntityInInventoryFollowsMouse")""") &&
                body.contains("""rewriteExecutableJavaCall(result, "renderEntityInInventory")""") &&
                body.contains("""receiver != "InventoryScreen"""") &&
                body.contains("args.size != 7") &&
                body.contains("new org.joml.Vector3f"),
            "InventoryScreen entity preview migration must use executable call evidence and preserve preview argument semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "InventoryScreen entity preview migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `data result getOrThrow migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyDataResultGetOrThrowCalls")
        assertTrue(start >= 0, "migrateLegacyDataResultGetOrThrowCalls is missing")
        val end = source.indexOf("private fun migrateKnownVanillaCodecCodecCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getOrThrow prefilter" to """source.contains(".getOrThrow(")""",
            "raw getOrThrow rewrite" to """rewriteJavaCall(source, "getOrThrow")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".getOrThrow(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "getOrThrow")""") &&
                body.contains("args.size != 2") &&
                body.contains("""args[0].trim() != "true""""),
            "DataResult getOrThrow migration must inspect executable calls and preserve argument-shape checks"
        )
        assertTrue(
            offenders.isEmpty(),
            "DataResult getOrThrow migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `known vanilla codec codec migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateKnownVanillaCodecCodecCalls")
        assertTrue(start >= 0, "migrateKnownVanillaCodecCodecCalls is missing")
        val end = source.indexOf("private fun migrateMapCodecFeatureConstructorArguments", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw CODEC.codec prefilter" to """source.contains(".CODEC.codec()")""",
            "raw CODEC.codec regex replacement" to """.replace(result) { match -> match.groupValues[1] }"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".CODEC.codec()")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("match.groupValues[1]"),
            "Known vanilla CODEC.codec() migration must rewrite executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Known vanilla CODEC.codec() migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `record codec builder witness migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRecordCodecBuilderWitnessSource")
        assertTrue(start >= 0, "migrateRecordCodecBuilderWitnessSource is missing")
        val end = source.indexOf("private fun migrateLegacyTickEventSource", start + 1)
        assertTrue(end > start, "migrateRecordCodecBuilderWitnessSource boundary is missing")
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw RecordCodecBuilder prefilter" to """source.contains("RecordCodecBuilder.")""",
            "raw ResourceLocation list codec replacement" to """source.replace("ResourceLocation.CODEC.codec().listOf()", "ResourceLocation.CODEC.listOf()")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("RecordCodecBuilder.")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("""ResourceLocation\.CODEC\.codec\(\)\.listOf\(\)""") &&
                body.contains(""".findAll(maskJavaCommentsAndLiterals(result))""") &&
                body.contains("""maskJavaCommentsAndLiterals(result).contains("RecordCodecBuilder.<${'$'}typeName>")"""),
            "RecordCodecBuilder witness migration must rewrite ResourceLocation CODEC list calls only in executable source"
        )
        assertTrue(
            offenders.isEmpty(),
            "RecordCodecBuilder witness migration must not rewrite comments or arbitrary strings: $offenders"
        )
    }

    @Test
    fun `registry object reflection migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyRegistryObjectReflection")
        assertTrue(start >= 0, "migrateLegacyRegistryObjectReflection is missing")
        val end = source.indexOf("private fun payloadPathName", start + 1)
        assertTrue(end > start, "migrateLegacyRegistryObjectReflection boundary is missing")
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw RegistryObject.class prefilter" to """source.contains("RegistryObject.class")""",
            "raw RegistryObject.class replacement" to """source.replace("RegistryObject.class", "DeferredHolder.class")""",
            "raw RegistryObject fields replacement" to """source.replace("RegistryObject fields", "DeferredHolder fields")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("RegistryObject.class")""") &&
                body.contains("replaceExecutableRegex(source, Regex") &&
                body.contains("val maskedResult = maskJavaCommentsAndLiterals(result)") &&
                body.contains("executableSlice.trimStart().startsWith(\"return\")") &&
                body.contains("applyStringEdits(result, messageEdits)"),
            "RegistryObject reflection migration must rewrite class literals and paired return messages with executable evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "RegistryObject reflection migration must not rewrite comments or arbitrary strings: $offenders"
        )
    }

    @Test
    fun `legacy event bus post migration uses executable call and cancellation evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()

        fun bodyBetween(startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            assertTrue(start >= 0, "$startMarker is missing")
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val migrationBody = bodyBetween(
            "private fun migrateLegacyEventBusPostBooleans",
            "private fun collapseDuplicateEventBusPostCancellationChecks"
        )
        val stripBody = bodyBetween(
            "private fun stripUnusedEventBusPostCancellationChecks",
            "private data class EventBusPostCall"
        )
        val finderBody = bodyBetween(
            "private fun findEventBusPostCall",
            "private fun trailingIsCanceledCallEnds"
        )
        val trailingBody = bodyBetween(
            "private fun trailingIsCanceledCallEnds",
            "private fun isStandaloneExpressionStart"
        )
        val standaloneBody = bodyBetween(
            "private fun isStandaloneExpressionStart",
            "private fun migrateLegacyFollowOwnerGoalConstructors"
        )
        val combined = migrationBody + stripBody + finderBody + trailingBody + standaloneBody
        val offenders = listOf(
            "raw post prefilter" to """source.contains("EVENT_BUS.post(")""",
            "raw post rewrite" to """rewriteJavaCall(source, "post")""",
            "raw post finder" to "source.indexOf(token, cursor)",
            "raw post paren matcher" to "findMatchingParen(source, openParen)",
            "raw receiver finder" to "findExpressionReceiverStart(source, tokenIndex)",
            "raw isCanceled chain match" to """source.startsWith(".isCanceled", dot)""",
            "raw isCanceled paren matcher" to "findMatchingParen(source, openParen)"
        )
            .filter { (_, marker) -> combined.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            migrationBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                migrationBody.contains("""executableCode.contains("EVENT_BUS.post(")""") &&
                migrationBody.contains("""rewriteExecutableJavaCall(source, "post")""") &&
                migrationBody.contains("""receiver.endsWith("EVENT_BUS")"""),
            "Legacy event bus post migration must rewrite post calls from executable Java only"
        )
        assertTrue(
            stripBody.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                stripBody.contains("skipWhitespace(executableCode, chainEnd)") &&
                stripBody.contains("executableCode[next] != ';'"),
            "Unused event bus post cancellation cleanup must inspect executable semicolon context"
        )
        assertTrue(
            finderBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                finderBody.contains("executableCode.indexOf(token, cursor)") &&
                finderBody.contains("findMatchingParen(executableCode, openParen)") &&
                finderBody.contains("findExpressionReceiverStart(executableCode, tokenIndex)") &&
                finderBody.contains("splitTopLevelJavaArgs(source.substring(openParen + 1, closeParen))"),
            "Event bus post finder must locate calls in executable Java while preserving original arguments"
        )
        assertTrue(
            trailingBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                trailingBody.contains("""executableCode.startsWith(".isCanceled", dot)""") &&
                trailingBody.contains("findMatchingParen(executableCode, openParen)") &&
                trailingBody.contains("executableCode.substring(openParen + 1, closeParen).isNotBlank()"),
            "Event bus cancellation-chain cleanup must identify isCanceled calls in executable Java only"
        )
        assertTrue(
            standaloneBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                standaloneBody.contains("executableCode.substring(lineStart, expressionStart).trim().isNotEmpty()") &&
                standaloneBody.contains("executableCode[index].isWhitespace()") &&
                standaloneBody.contains("executableCode[index] == ';'"),
            "Standalone event bus post cleanup must ignore comments and literals around the expression"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy event bus post migration must not rewrite or clean comments and string literals: $offenders"
        )
    }

    @Test
    fun `legacy FollowOwnerGoal constructor migration uses executable constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyFollowOwnerGoalConstructors")
        assertTrue(start >= 0, "migrateLegacyFollowOwnerGoalConstructors is missing")
        val end = source.indexOf("private fun migrateLegacyDyeColorFloatArrays", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw FollowOwnerGoal prefilter" to """source.contains("new FollowOwnerGoal(")""",
            "raw FollowOwnerGoal constructor rewrite" to """rewriteJavaNew(source, "FollowOwnerGoal")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("new FollowOwnerGoal(")""") &&
                body.contains("""rewriteExecutableJavaNew(source, "FollowOwnerGoal")""") &&
                body.contains("args.size == 5") &&
                body.contains("args.take(4)"),
            "FollowOwnerGoal constructor migration must locate constructors in executable Java and preserve old five-argument semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "FollowOwnerGoal constructor migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy vanilla block constructor migration uses executable constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyVanillaBlockConstructors121")
        assertTrue(start >= 0, "migrateLegacyVanillaBlockConstructors121 is missing")
        val end = source.indexOf("private fun looksLikeBlockPropertiesExpression", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val constructors = listOf(
            "StairBlock",
            "ButtonBlock",
            "FenceGateBlock",
            "PressurePlateBlock",
            "DoorBlock",
            "TrapDoorBlock",
            "TorchBlock",
            "WallTorchBlock"
        )
        val offenders = constructors.flatMap { constructor ->
            listOf(
                "raw $constructor prefilter" to """source.contains("new $constructor(")""",
                "raw $constructor constructor rewrite" to """rewriteJavaNew(result, "$constructor")"""
            )
        }
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                constructors.all { constructor ->
                    body.contains("""executableCode.contains("new $constructor(")""") &&
                        body.contains("""rewriteExecutableJavaNew(result, "$constructor")""")
                } &&
                body.contains("looksLikeBlockPropertiesExpression") &&
                body.contains("looksLikeParticleOptionsExpression") &&
                body.contains("args.size != 4") &&
                body.contains("args.size != 3") &&
                body.contains("args.size != 2"),
            "Legacy vanilla block constructor migration must locate every supported constructor in executable Java and preserve signature-shape checks"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy vanilla block constructor migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy model event constructor migration uses executable constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyModelEventSource")
        assertTrue(start >= 0, "migrateLegacyModelEventSource is missing")
        val end = source.indexOf("private fun migrateRegisterAdditionalModelResourceLocations", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw ModelResourceLocation prefilter" to """source.contains("ModelResourceLocation")""",
            "raw ModelEvent prefilter" to """source.contains("ModelEvent.")""",
            "raw ModelResourceLocation constructor rewrite" to """rewriteJavaNew(result, "ModelResourceLocation")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("ModelResourceLocation")""") &&
                body.contains("""executableCode.contains("ModelEvent.")""") &&
                body.contains("""rewriteExecutableJavaNew(result, "ModelResourceLocation")""") &&
                body.contains("args.size != 2") &&
                body.contains("variant == \"\\\"inventory\\\"\""),
            "Legacy model event constructor migration must locate ModelResourceLocation constructors in executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy model event constructor migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy vanilla advancement criterion constructors use executable constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyAdvancementDatagenSource")
        assertTrue(start >= 0, "migrateLegacyAdvancementDatagenSource is missing")
        val end = source.indexOf("private fun legacyCriteriaTriggerExpression", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val constructors = listOf(
            "ItemUsedOnLocationTrigger.TriggerInstance",
            "PlayerTrigger.TriggerInstance",
            "InventoryChangeTrigger.TriggerInstance"
        )
        val offenders = constructors
            .filter { constructor -> body.contains("""rewriteJavaNew(result, "$constructor")""") }
            .map { constructor -> "raw $constructor constructor rewrite" }

        assertTrue(
            constructors.all { constructor ->
                body.contains("""rewriteExecutableJavaNew(result, "$constructor")""")
            } &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("returnPattern.findAll(executableCode)") &&
                body.contains("signaturePattern.findAll(maskJavaCommentsAndLiterals(result))"),
            "Legacy vanilla advancement criterion constructor migrations must locate constructors and return helpers in executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy vanilla advancement criterion constructor migrations must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy neoforge model api constructors use executable constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyNeoForgeModelApiSource")
        assertTrue(start >= 0, "migrateLegacyNeoForgeModelApiSource is missing")
        val end = source.indexOf("private fun migrateAttributeModifierResourceLocationIds", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw BlockElementFace prefilter" to """source.contains("BlockElementFace")""",
            "raw ResourceLocation prefilter" to """source.contains("new ResourceLocation(")""",
            "raw BlockElementFace constructor rewrite" to """rewriteJavaNew(result, "BlockElementFace")""",
            "raw ResourceLocation constructor rewrite" to """rewriteJavaNew(result, "ResourceLocation")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("BlockElementFace")""") &&
                body.contains("""executableCode.contains("new ResourceLocation(")""") &&
                body.contains("""rewriteExecutableJavaNew(result, "BlockElementFace")""") &&
                body.contains("""rewriteExecutableJavaNew(result, "ResourceLocation")""") &&
                body.contains("findAll(maskJavaCommentsAndLiterals(result))") &&
                body.contains("replaceExecutableJavaRegex(result"),
            "Legacy NeoForge model API constructor and face accessor migrations must inspect executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy NeoForge model API migrations must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `attribute modifier constructor id migration uses executable constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateAttributeModifierResourceLocationIds")
        assertTrue(start >= 0, "migrateAttributeModifierResourceLocationIds is missing")
        val end = source.indexOf("private fun modifierConstantPathName", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw AttributeModifier constructor rewrite" to """rewriteJavaNew(result, "AttributeModifier")""",
            "raw string-name AttributeModifier regex rewrite" to """new\s+AttributeModifier\(\s*"([^"]+)""",
            "raw uuid-name AttributeModifier regex rewrite" to """new\s+AttributeModifier\(\s*([^,\r\n]+)\s*,\s*"([^"]+)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("""rewriteExecutableJavaNew(result, "AttributeModifier")""") &&
                body.contains("args.size == 3") &&
                body.contains("args.size != 4") &&
                body.contains("idAliases[legacyId] = id") &&
                body.contains("legacyName.endsWith(\".toString()\")"),
            "AttributeModifier constructor id migration must parse executable Java constructors and preserve legacy name/id semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "AttributeModifier constructor id migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy advancement rewards constructor migration uses executable constructor evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyLootAndRegistryAccess")
        assertTrue(start >= 0, "migrateLegacyLootAndRegistryAccess is missing")
        val end = source.indexOf("private fun migrateLegacyLootTableResourceLocationRegistry", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw AdvancementRewards constructor prefilter" to """result.contains("new AdvancementRewards(")""",
            "raw AdvancementRewards constructor rewrite" to """rewriteJavaNew(result, "AdvancementRewards")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("maskJavaCommentsAndLiterals(result).contains(\"new AdvancementRewards(\")") &&
                body.contains("""rewriteExecutableJavaNew(result, "AdvancementRewards")""") &&
                body.contains("if (args.size != 4)") &&
                body.contains("legacyResourceLocationArrayToList(args[1])") &&
                body.contains("legacyResourceLocationArrayToList(args[2])"),
            "Legacy AdvancementRewards constructor migration must locate constructors in executable Java and preserve loot/recipe argument semantics"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy AdvancementRewards constructor migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy creative enchantment instance migration uses executable constructor and loop evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyCreativeTabEnchantmentInstances")
        assertTrue(start >= 0, "migrateLegacyCreativeTabEnchantmentInstances is missing")
        val end = source.indexOf("private fun migrateLegacyHolderAccessors", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw EnchantmentInstance source prefilter" to """source.contains("EnchantmentInstance(")""",
            "raw ENCHANTMENTS source prefilter" to """source.contains(".ENCHANTMENTS.getEntries()")""",
            "raw EnchantmentInstance constructor prefilter" to """result.contains("new EnchantmentInstance(Enchantments.")""",
            "raw EnchantmentInstance constructor rewrite" to """rewriteJavaNew(result, "EnchantmentInstance")""",
            "raw helper invocation rewrite" to """rewriteJavaInvocationArguments(result, helperName)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableSource = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableSource.contains("EnchantmentInstance(")""") &&
                body.contains("val initialExecutableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("""rewriteExecutableJavaNew(result, "EnchantmentInstance")""") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("findMatchingBrace(executableCode, openBrace)") &&
                body.contains("rewriteExecutableJavaInvocationArguments(result, helperName)") &&
                body.contains("replaceExecutableJavaRegex(result, loopPattern)") &&
                body.contains("replaceExecutableJavaRegex(result, Regex(\"\"\"new\\s+EnchantmentInstance"),
            "Legacy creative tab EnchantmentInstance migration must locate constructors, helper calls, and enchantment loops in executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy creative tab EnchantmentInstance migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy enchantment constant rename migration uses executable references`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyEnchantmentConstantNames")
        assertTrue(start >= 0, "migrateLegacyEnchantmentConstantNames is missing")
        val end = source.indexOf("private fun migrateLegacyEnchantmentLevelHelperCall", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw Enchantments prefilter" to """source.contains("Enchantments.")""",
            "raw Enchantments constant replacement" to "result.replace(\"Enchantments.${'$'}oldName\", \"Enchantments.${'$'}newName\")"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("maskJavaCommentsAndLiterals(source).contains(\"Enchantments.\")") &&
                body.contains("replaceExecutableJavaRegex(result, Regex(\"\"\"\\bEnchantments\\."),
            "Legacy enchantment constant rename migration must rewrite only executable Enchantments references"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy enchantment constant rename migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy item constructor migration uses executable constructor and superclass evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyItemConstructorsAndProperties")
        assertTrue(start >= 0, "migrateLegacyItemConstructorsAndProperties is missing")
        val end = source.indexOf("private fun migrateLegacyTierLevelSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw SwordItem source prefilter" to """source.contains("new SwordItem(")""",
            "raw meat source prefilter" to """source.contains(".meat()")""",
            "raw meat replacement" to """source.replace(".meat()", "")""",
            "raw BowlFoodItem replacement" to """result.replace("new BowlFoodItem(", "new Item(")""",
            "raw SwordItem constructor rewrite" to """rewriteJavaNew(result, "SwordItem")""",
            "raw SwordItem superclass check" to "Regex(\"\"\"\\bextends\\s+SwordItem\\b\"\"\").containsMatchIn(result)",
            "raw super constructor rewrite" to """rewriteSuperConstructorCalls(result)"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableSource = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableSource.contains("new SwordItem(")""") &&
                body.contains("replaceExecutableJavaRegex(source, Regex(\"\"\"\\.meat\\(\\)\"\"\"))") &&
                body.contains("replaceExecutableJavaRegex(result, Regex(\"\"\"\\bnew\\s+BowlFoodItem") &&
                body.contains("""rewriteExecutableJavaNew(result, "SwordItem")""") &&
                body.contains("containsMatchIn(maskJavaCommentsAndLiterals(result))") &&
                body.contains("rewriteExecutableSuperConstructorCalls(result)") &&
                source.contains("private fun rewriteExecutableSuperConstructorCalls("),
            "Legacy item constructor migration must locate item constructors and tool superclass calls in executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy item constructor migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy sound event supplier constructor migration uses executable declarations and calls`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val collectStart = source.indexOf("private fun collectSoundEventSupplierConstructors")
        assertTrue(collectStart >= 0, "collectSoundEventSupplierConstructors is missing")
        val collectEnd = source.indexOf("private fun isLegacySoundEventSupplierParameter", collectStart + 1).let {
            if (it < 0) source.length else it
        }
        val collectBody = source.substring(collectStart, collectEnd)
        val migrateStart = source.indexOf("private fun migrateLegacySoundEventSupplierLambdas")
        assertTrue(migrateStart >= 0, "migrateLegacySoundEventSupplierLambdas is missing")
        val migrateEnd = source.indexOf("private fun migrateSoundEventSupplierConstructorArgs", migrateStart + 1).let {
            if (it < 0) source.length else it
        }
        val migrateBody = source.substring(migrateStart, migrateEnd)
        val offenders = listOf(
            "raw package scan" to ".find(source)",
            "raw declared type scan" to ".findAll(source)",
            "raw constructor scan" to "constructorPattern.findAll(source)",
            "raw record scan" to "recordPattern.findAll(source)",
            "raw SoundEvents prefilter" to """source.contains("SoundEvents.")""",
            "raw supplier constructor rewrite" to "rewriteJavaNew(result, className)",
            "raw super scan" to """result.indexOf("super(", cursor)""",
            "raw super paren match" to "findMatchingParen(result, openParen)"
        )
            .filter { (_, marker) -> collectBody.contains(marker) || migrateBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            collectBody.contains("val code = maskJavaComments(source)") &&
                collectBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                collectBody.contains(".find(code)") &&
                collectBody.contains(".findAll(executableCode)") &&
                collectBody.contains("constructorPattern.findAll(executableCode)") &&
                collectBody.contains("recordPattern.findAll(executableCode)") &&
                migrateBody.contains("maskJavaCommentsAndLiterals(source).contains(\"SoundEvents.\")") &&
                migrateBody.contains("rewriteExecutableJavaNew(result, className)") &&
                migrateBody.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                migrateBody.contains("executableCode.indexOf(\"super(\", cursor)") &&
                migrateBody.contains("findMatchingParen(executableCode, openParen)"),
            "Legacy sound event supplier migration must collect constructor signatures and call sites from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy sound event supplier migration must not use comments or string literals as constructor evidence: $offenders"
        )
    }

    @Test
    fun `drop experience constructor migration uses executable constructor and call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val subclassStart = source.indexOf("private fun migrateDropExperienceSubclassConstructorSource")
        assertTrue(subclassStart >= 0, "migrateDropExperienceSubclassConstructorSource is missing")
        val subclassEnd = source.indexOf("private fun migrateDropExperienceConstructorCallSitesSource", subclassStart + 1).let {
            if (it < 0) source.length else it
        }
        val subclassBody = source.substring(subclassStart, subclassEnd)
        val callStart = source.indexOf("private fun migrateDropExperienceConstructorCallSitesSource")
        assertTrue(callStart >= 0, "migrateDropExperienceConstructorCallSitesSource is missing")
        val callEnd = source.indexOf("private fun looksLikeIntProviderExpression", callStart + 1).let {
            if (it < 0) source.length else it
        }
        val callBody = source.substring(callStart, callEnd)
        val offenders = listOf(
            "raw subclass constructor scan" to "constructorPattern.find(source)",
            "raw subclass constructor replace" to "constructorPattern.replaceFirst",
            "raw subclass super replace" to "superPattern.replace(result",
            "raw callsite prefilter" to """source.contains("new ")""",
            "raw int provider variable scan" to ".findAll(source)",
            "raw drop experience callsite rewrite" to "rewriteJavaNew(result, className)"
        )
            .filter { (_, marker) -> subclassBody.contains(marker) || callBody.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            subclassBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                subclassBody.contains("constructorPattern.find(executableCode)") &&
                subclassBody.contains("replaceExecutableJavaRegex(result, superPattern)") &&
                callBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                callBody.contains("executableCode.contains(\"new \")") &&
                callBody.contains(".findAll(executableCode)") &&
                callBody.contains("rewriteExecutableJavaNew(result, className)"),
            "DropExperienceBlock constructor migration must use executable Java evidence for declarations and calls"
        )
        assertTrue(
            offenders.isEmpty(),
            "DropExperienceBlock constructor migration must not use comments or string literals as evidence: $offenders"
        )
    }

    @Test
    fun `stair and flower constructor migrations use executable constructor and call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        fun body(startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            assertTrue(start >= 0, "$startMarker is missing")
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val stairSubclassBody = body(
            "private fun migrateStairBlockSubclassConstructorSource",
            "private fun migrateStairBlockConstructorCallSitesSource"
        )
        val stairCallBody = body(
            "private fun migrateStairBlockConstructorCallSitesSource",
            "private fun migrateFlowerBlockMobEffectHolderConstructors"
        )
        val flowerSubclassBody = body(
            "private fun migrateFlowerBlockSubclassConstructorSource",
            "private fun migrateFlowerBlockConstructorCallSitesSource"
        )
        val flowerCallBody = body(
            "private fun migrateFlowerBlockConstructorCallSitesSource",
            "private fun normalizeMobEffectHolderExpression"
        )
        val combined = listOf(stairSubclassBody, stairCallBody, flowerSubclassBody, flowerCallBody).joinToString("\n")
        val offenders = listOf(
            "raw subclass constructor scan" to "constructorPattern.find(source)",
            "raw subclass super scan" to "superPattern.containsMatchIn(source)",
            "raw subclass constructor replace" to "constructorPattern.replaceFirst",
            "raw stair callsite prefilter" to """source.contains(".defaultBlockState()")""",
            "raw flower callsite prefilter" to """source.contains("Effects.")""",
            "raw supplier import retention" to "containsMatchIn(result)",
            "raw flower supplier retention" to """result.contains("Supplier<")""",
            "raw constructor callsite rewrite" to "rewriteJavaNew(result, className)"
        )
            .filter { (_, marker) -> combined.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            stairSubclassBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                stairSubclassBody.contains("constructorPattern.find(executableCode)") &&
                stairSubclassBody.contains("superPattern.containsMatchIn(executableCode)") &&
                stairSubclassBody.contains("val resultExecutableCode = maskJavaCommentsAndLiterals(result)") &&
                stairCallBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                stairCallBody.contains("executableCode.contains(\".defaultBlockState()\")") &&
                stairCallBody.contains("rewriteExecutableJavaNew(result, className)") &&
                flowerSubclassBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                flowerSubclassBody.contains("constructorPattern.find(executableCode)") &&
                flowerSubclassBody.contains("superPattern.containsMatchIn(executableCode)") &&
                flowerSubclassBody.contains("val resultExecutableCode = maskJavaCommentsAndLiterals(result)") &&
                flowerCallBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                flowerCallBody.contains("executableCode.contains(\"Effects.\")") &&
                flowerCallBody.contains("rewriteExecutableJavaNew(result, className)"),
            "StairBlock and FlowerBlock constructor migrations must use executable Java evidence for declarations and calls"
        )
        assertTrue(
            offenders.isEmpty(),
            "StairBlock and FlowerBlock constructor migrations must not use comments or string literals as evidence: $offenders"
        )
    }

    @Test
    fun `map codec serialization migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateMapCodecSerializationCalls")
        assertTrue(start >= 0, "migrateMapCodecSerializationCalls is missing")
        val end = source.indexOf("private fun migrateLegacyDataResultGetOrThrowCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw CODEC prefilter" to """source.contains(".CODEC.")""",
            "raw CODEC replacement" to ".replace(result)"
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".CODEC.")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("""\.CODEC)\.(parse|encodeStart)"""),
            "MapCodec serialization migration must rewrite only executable CODEC parse/encodeStart calls"
        )
        assertTrue(
            offenders.isEmpty(),
            "MapCodec serialization migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `creative tab entries and bytebuf map migrations use executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()

        fun bodyBetween(startMarker: String, endMarker: String): String {
            val start = source.indexOf(startMarker)
            assertTrue(start >= 0, "$startMarker is missing")
            val end = source.indexOf(endMarker, start + 1).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

        val creativeBody = bodyBetween(
            "private fun migrateCreativeTabEntriesAccessSource",
            "private fun migrateItemStackHoverNameSource"
        )
        val networkBody = bodyBetween(
            "private fun migrateFriendlyByteBufAmbiguousMethodReferencesSource",
            "private fun streamEncoderMethodReferenceLambda"
        )
        val offenders = listOf(
            "raw putAfter prefilter" to creativeBody.contains("""source.contains(".getEntries().putAfter(")"""),
            "raw putAfter rewrite" to creativeBody.contains("""rewriteJavaCall(source, "putAfter")"""),
            "raw writeMap prefilter" to networkBody.contains("""source.contains(".writeMap(")"""),
            "raw readMap prefilter" to networkBody.contains("""source.contains(".readMap(")"""),
            "raw writeUUID prefilter" to networkBody.contains("""source.contains("FriendlyByteBuf::writeUUID")"""),
            "raw readUUID prefilter" to networkBody.contains("""source.contains("FriendlyByteBuf::readUUID")"""),
            "raw writeMap rewrite" to networkBody.contains("""rewriteJavaCall(source, "writeMap")"""),
            "raw readMap rewrite" to networkBody.contains("""rewriteJavaCall(result, "readMap")""")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            creativeBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                creativeBody.contains("""executableCode.contains(".getEntries().putAfter(")""") &&
                creativeBody.contains("""rewriteExecutableJavaCall(source, "putAfter")""") &&
                creativeBody.contains("""receiver.endsWith(".getEntries()")""") &&
                creativeBody.contains("""receiver.removeSuffix(".getEntries()")"""),
            "Creative tab entry insertion migration must derive calls from executable Java and preserve receiver shape"
        )
        assertTrue(
            networkBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                networkBody.contains("""executableCode.contains(".writeMap(")""") &&
                networkBody.contains("""executableCode.contains(".readMap(")""") &&
                networkBody.contains("""executableCode.contains("FriendlyByteBuf::writeUUID")""") &&
                networkBody.contains("""executableCode.contains("FriendlyByteBuf::readUUID")""") &&
                networkBody.contains("""rewriteExecutableJavaCall(source, "writeMap")""") &&
                networkBody.contains("""rewriteExecutableJavaCall(result, "readMap")""") &&
                networkBody.contains("streamEncoderMethodReferenceLambda") &&
                networkBody.contains("streamDecoderMethodReferenceLambda"),
            "FriendlyByteBuf map migration must derive calls and method-reference evidence from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Creative tab and FriendlyByteBuf map migrations must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `item stack hover name migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateItemStackHoverNameSource")
        assertTrue(start >= 0, "migrateItemStackHoverNameSource is missing")
        val end = source.indexOf("private fun migrateFriendlyByteBufAmbiguousMethodReferencesSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw setHoverName prefilter" to body.contains("""source.contains(".setHoverName(")"""),
            "raw setHoverName call rewrite" to body.contains("""rewriteJavaCall(result, "setHoverName")"""),
            "masked assignment argument reuse" to body.contains("val nameExpression = match.groupValues[4].trim()"),
            "raw assignment replacement" to body.contains(".replace(result)")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".setHoverName(")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("val beforeAssignmentRewrite = result") &&
                body.contains("match.groups[index]?.range") &&
                body.contains("beforeAssignmentRewrite.substring") &&
                body.contains("""rewriteExecutableJavaCall(result, "setHoverName")""") &&
                body.contains("DataComponents.CUSTOM_NAME"),
            "ItemStack hover name migration must derive matches from executable Java while preserving original argument text"
        )
        assertTrue(
            offenders.isEmpty(),
            "ItemStack hover name migration must not rewrite comments or string literals or reuse masked captures: $offenders"
        )
    }

    @Test
    fun `texture atlas sprite coordinate migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateTextureAtlasSpriteFloatCoordinateCalls")
        assertTrue(start >= 0, "migrateTextureAtlasSpriteFloatCoordinateCalls is missing")
        val end = source.indexOf("private fun migratePartialTickAccessors", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getU prefilter" to body.contains("""source.contains(".getU(")"""),
            "raw getV prefilter" to body.contains("""source.contains(".getV(")"""),
            "raw getU rewrite" to body.contains("""rewriteJavaCall(result, "getU")"""),
            "raw getV rewrite" to body.contains("""rewriteJavaCall(result, "getV")""")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".getU(")""") &&
                body.contains("""executableCode.contains(".getV(")""") &&
                body.contains("""rewriteExecutableJavaCall(result, "getU")""") &&
                body.contains("""rewriteExecutableJavaCall(result, "getV")""") &&
                body.contains("needsFloatCast(args[0])"),
            "TextureAtlasSprite coordinate migration must inspect executable Java calls before adding casts"
        )
        assertTrue(
            offenders.isEmpty(),
            "TextureAtlasSprite coordinate migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy gui survival elements migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyGuiSurvivalElementsSource")
        assertTrue(start >= 0, "migrateLegacyGuiSurvivalElementsSource is missing")
        val end = source.indexOf("private fun migrateLegacyJumpFromGroundVisibility", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw shouldDrawSurvivalElements prefilter" to body.contains("""source.contains(".shouldDrawSurvivalElements()")"""),
            "raw shouldDrawSurvivalElements replacement" to Regex("""Regex\([\s\S]*?\)\s*\.\s*replace\(""").containsMatchIn(body)
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".shouldDrawSurvivalElements()")""") &&
                body.contains("replaceExecutableRegex(source") &&
                body.contains("gameMode.canHurtPlayer()") &&
                body.contains("options.hideGui"),
            "Legacy gui survival element migration must rewrite only executable Java source"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy gui survival element migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `legacy model render packed color migration uses executable source evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyModelRenderPackedColorBodies")
        assertTrue(start >= 0, "migrateLegacyModelRenderPackedColorBodies is missing")
        val end = source.indexOf("private fun isLegacyModelPartColorRenderArgs", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw renderToBuffer prefilter" to body.contains("""source.contains("renderToBuffer(")"""),
            "raw render prefilter" to body.contains("""source.contains(".render(")"""),
            "raw render rewrite" to body.contains("""rewriteJavaCall(result, "render")"""),
            "raw renderToBuffer rewrite" to body.contains("""rewriteJavaCall(result, "renderToBuffer")"""),
            "raw body render rewrite" to body.contains("""rewriteJavaCall(body, "render")"""),
            "raw int-color signature scan" to body.contains("signature.find(result, cursor)"),
            "raw float-color signature scan" to body.contains("floatColorSignature.find(result, cursor)")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("renderToBuffer(")""") &&
                body.contains("""executableCode.contains(".render(")""") &&
                body.contains("""rewriteExecutableJavaCall(result, "render")""") &&
                body.contains("""rewriteExecutableJavaCall(result, "renderToBuffer")""") &&
                body.contains("""rewriteExecutableJavaCall(body, "render")""") &&
                body.contains("val executableResult = maskJavaCommentsAndLiterals(result)") &&
                body.contains("signature.find(executableResult, cursor)") &&
                body.contains("floatColorSignature.find(executableResult, cursor)") &&
                body.contains("findMatchingBrace(executableResult, openBrace)"),
            "Legacy model render packed color migration must derive calls and method bodies from executable Java"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy model render packed color migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `model render packed color text migration is not a raw json replacement`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val replacements = projectRoot
            .resolve("src/main/resources/mappings/forge2neo/text-replacements.json")
            .readText()
        val textPass = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = textPass.indexOf("private fun migrateModelRenderPackedColorText")
        assertTrue(start >= 0, "migrateModelRenderPackedColorText is missing")
        val end = textPass.indexOf("private fun migrateRemovedTagManagerAccess", start + 1).let {
            if (it < 0) textPass.length else it
        }
        val body = textPass.substring(start, end)
        val forbiddenJsonRules = listOf(
            "model-render-buffer-color-signature",
            "modelpart-render-color-arg",
            "model-render-buffer-float-call"
        ).filter { replacements.contains(""""id": "$it"""") }
        val offenders = listOf(
            "raw renderToBuffer prefilter" to body.contains("""source.contains("renderToBuffer(")"""),
            "raw render prefilter" to body.contains("""source.contains(".render(")"""),
            "raw direct regex replace" to Regex("""Regex\([\s\S]*?\)\s*\.\s*replace\(""").containsMatchIn(body)
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("renderToBuffer(")""") &&
                body.contains("""executableCode.contains(".render(")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("FastColor.ARGB32.colorFromFloat"),
            "Model render packed-color text migration must run through executable source filtering"
        )
        assertTrue(
            forbiddenJsonRules.isEmpty() && offenders.isEmpty(),
            "Model render packed-color migration must not be raw JSON/global regex replacement: json=$forbiddenJsonRules code=$offenders"
        )
    }

    @Test
    fun `inventory recipe holder interface migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = textPass.indexOf("private fun migrateInventoryRecipeHolderInterface")
        assertTrue(start >= 0, "migrateInventoryRecipeHolderInterface is missing")
        val end = textPass.indexOf("private fun migrateModelRenderPackedColorText", start + 1).let {
            if (it < 0) textPass.length else it
        }
        val body = textPass.substring(start, end)
        val offenders = listOf(
            "raw import prefilter" to body.contains("source.contains(oldImport)"),
            "raw import replace" to body.contains(".replace(oldImport"),
            "raw implements replacement" to Regex("""Regex\([\s\S]*?implements[\s\S]*?\)\s*\.\s*replace\(""").containsMatchIn(body)
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("executableCode.contains(oldImport)") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("import[ \\t]+net\\.minecraft\\.world\\.inventory\\.RecipeHolder") &&
                body.contains("""\bRecipeHolder\b""") &&
                body.contains("RecipeCraftingHolder"),
            "Inventory RecipeHolder interface migration must rewrite only executable import and implements clauses"
        )
        assertTrue(
            offenders.isEmpty(),
            "Inventory RecipeHolder interface migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `registry object wildcard holder migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = textPass.indexOf("private fun migrateRemainingRegistryObjectWildcardHolders")
        assertTrue(start >= 0, "migrateRemainingRegistryObjectWildcardHolders is missing")
        val end = textPass.indexOf("private fun migrateParticleOptionsCodecs", start + 1).let {
            if (it < 0) textPass.length else it
        }
        val body = textPass.substring(start, end)
        val offenders = listOf(
            "raw RegistryObject prefilter" to body.contains("""source.contains("RegistryObject<")"""),
            "raw wildcard replacement" to Regex("""Regex\([\s\S]*?RegistryObject[\s\S]*?\)\s*\.\s*replace\(source""").containsMatchIn(body)
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("RegistryObject<")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("DeferredHolder<\$type, ? extends \$type>"),
            "RegistryObject wildcard holder migration must inspect executable Java source only"
        )
        assertTrue(
            offenders.isEmpty(),
            "RegistryObject wildcard holder migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `partial nbt ingredient migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = textPass.indexOf("private fun migratePartialNbtIngredients")
        assertTrue(start >= 0, "migratePartialNbtIngredients is missing")
        val end = textPass.indexOf("private fun migrateSingleItemRecipeBuilderResults", start + 1).let {
            if (it < 0) textPass.length else it
        }
        val body = textPass.substring(start, end)
        val offenders = listOf(
            "raw partial nbt prefilter" to body.contains("""source.contains("PartialNBTIngredient")"""),
            "raw damage replacement" to Regex("""Regex\([\s\S]*?ItemStack\.TAG_DAMAGE[\s\S]*?\)\s*\.\s*replace\(result""").containsMatchIn(body),
            "raw potion replacement" to Regex("""Regex\([\s\S]*?BuiltInRegistries\.POTION[\s\S]*?\)\s*\.\s*replace\(result""").containsMatchIn(body)
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("PartialNBTIngredient")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("match.value") &&
                body.contains("""return@replaceExecutableRegex match.value"""),
            "PartialNBTIngredient migration must use executable Java source and leave unsupported matches untouched"
        )
        assertTrue(
            offenders.isEmpty(),
            "PartialNBTIngredient migration must not rewrite comments or string literals with raw regex replacement: $offenders"
        )
    }

    @Test
    fun `single item recipe result migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = textPass.indexOf("private fun migrateSingleItemRecipeBuilderResults")
        assertTrue(start >= 0, "migrateSingleItemRecipeBuilderResults is missing")
        val end = textPass.indexOf("private fun collectLegacyDyeableLeatherItemClasses", start + 1).let {
            if (it < 0) textPass.length else it
        }
        val body = textPass.substring(start, end)
        val offenders = listOf(
            "raw single item prefilter" to body.contains("""source.contains("SingleItemRecipeBuilder.Result")"""),
            "raw stonecutting accept prefilter" to body.contains("""source.contains(".accept(stonecutting(")"""),
            "raw regex replacement" to Regex("""Regex\([\s\S]*?stonecutting[\s\S]*?\)\s*\.\s*replace\(result""").containsMatchIn(body),
            "raw string replacement" to body.contains("result.replace("),
            "raw wrapper marker lookup" to body.contains("source.indexOf(classMarker)")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("SingleItemRecipeBuilder.Result")""") &&
                body.contains("""executableCode.contains(".accept(stonecutting(")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("executableCode.indexOf(classMarker)") &&
                body.contains("maskJavaCommentsAndLiterals(result).contains"),
            "SingleItemRecipeBuilder.Result migration must inspect and rewrite executable Java source only"
        )
        assertTrue(
            offenders.isEmpty(),
            "SingleItemRecipeBuilder.Result migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `dyeable leather external color migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val collectStart = textPass.indexOf("private fun collectLegacyDyeableLeatherItemClasses")
        assertTrue(collectStart >= 0, "collectLegacyDyeableLeatherItemClasses is missing")
        val collectEnd = textPass.indexOf("private fun migrateDyeableLeatherItemColors", collectStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val collectBody = textPass.substring(collectStart, collectEnd)
        val callSiteStart = textPass.indexOf("private fun migrateDyeableLeatherGetColorCallSites")
        assertTrue(callSiteStart >= 0, "migrateDyeableLeatherGetColorCallSites is missing")
        val callSiteEnd = textPass.indexOf("private fun migrateDyeableLeatherInstanceofGetColorCallSites", callSiteStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val callSiteBody = textPass.substring(callSiteStart, callSiteEnd)
        val itemColorsStart = textPass.indexOf("private fun migrateDyeableLeatherItemColors")
        assertTrue(itemColorsStart >= 0, "migrateDyeableLeatherItemColors is missing")
        val itemColorsEnd = textPass.indexOf("private fun migrateDyeableLeatherGetColorCallSites", itemColorsStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val itemColorsBody = textPass.substring(itemColorsStart, itemColorsEnd)
        val instanceStart = textPass.indexOf("private fun migrateDyeableLeatherInstanceofGetColorCallSites")
        assertTrue(instanceStart >= 0, "migrateDyeableLeatherInstanceofGetColorCallSites is missing")
        val instanceEnd = textPass.indexOf("private fun insertDyeableDefaultColor", instanceStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val instanceBody = textPass.substring(instanceStart, instanceEnd)
        val defaultColorStart = textPass.indexOf("private fun insertDyeableDefaultColor")
        assertTrue(defaultColorStart >= 0, "insertDyeableDefaultColor is missing")
        val defaultColorEnd = textPass.indexOf("private fun migrateTierSortingRegistryTiers", defaultColorStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val defaultColorBody = textPass.substring(defaultColorStart, defaultColorEnd)
        val offenders = listOf(
            "raw legacy class collection" to collectBody.contains(".findAll(source)"),
            "raw dyeable type prefilter" to itemColorsBody.contains("""source.contains("DyeableLeatherItem")"""),
            "raw known class prefilter" to itemColorsBody.contains("""source.contains("instanceof ${'$'}className")"""),
            "raw cast call-site replacement" to callSiteBody.contains(".replace(source)"),
            "raw instanceof getColor prefilter" to instanceBody.contains("""source.contains(".getColor(")"""),
            "raw instanceof search" to instanceBody.contains("instanceofPattern.find(result"),
            "raw instanceof brace search" to instanceBody.contains("result.indexOf('{', match.range.last)"),
            "raw instanceof body replacement" to instanceBody.contains("colorCallPattern.replace(body)"),
            "raw default color scan" to defaultColorBody.contains("""source.contains("DEFAULT_COLOR")"""),
            "raw default color class scan" to defaultColorBody.contains(".find(source)")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            collectBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                collectBody.contains(".findAll(executableCode)") &&
                itemColorsBody.contains("val executableSource = maskJavaCommentsAndLiterals(source)") &&
                itemColorsBody.contains("""executableSource.contains("DyeableLeatherItem")""") &&
                callSiteBody.contains("replaceExecutableRegex(") &&
                callSiteBody.contains("match.value") &&
                callSiteBody.contains("matchEntire(match.value)") &&
                instanceBody.contains("val executableSource = maskJavaCommentsAndLiterals(source)") &&
                instanceBody.contains("maskJavaCommentsAndLiterals(result)") &&
                instanceBody.contains("maskJavaCommentsAndLiterals(body)") &&
                instanceBody.contains("replaceRange(") &&
                defaultColorBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                defaultColorBody.contains(".find(executableCode)"),
            "Dyeable leather external color migration must use executable Java source for class evidence and cast call-sites"
        )
        assertTrue(
            offenders.isEmpty(),
            "Dyeable leather external color migration must not derive evidence from comments or rewrite comments/strings: $offenders"
        )
    }

    @Test
    fun `legacy enchantment category runtime migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val start = textPass.indexOf("private fun migrateEnchantmentCategoryRuntimeChecks")
        assertTrue(start >= 0, "migrateEnchantmentCategoryRuntimeChecks is missing")
        val end = textPass.indexOf("private fun migrateLootSerializerCodecs", start + 1).let {
            if (it < 0) textPass.length else it
        }
        val body = textPass.substring(start, end)
        val offenders = listOf(
            "raw EnchantmentCategory prefilter" to body.contains("""source.contains("EnchantmentCategory")"""),
            "raw enchantment array prefilter" to body.contains("""source.contains("new Enchantment[0]")"""),
            "raw enchantment array replacement" to Regex("""Regex\([\s\S]*?EnchantmentHelper[\s\S]*?\)\s*\.\s*replace\(result""").containsMatchIn(body),
            "raw category helper replacement" to Regex("""Regex\([\s\S]*?EnchantmentCategory[\s\S]*?\)\s*\.\s*replace\(result""").containsMatchIn(body)
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("EnchantmentCategory")""") &&
                body.contains("""executableCode.contains("new Enchantment[0]")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("match.value") &&
                body.contains("matchEntire(match.value)"),
            "Legacy enchantment category runtime migration must use executable Java source and original expression captures"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy enchantment category runtime migration must not rewrite comments or string literals: $offenders"
        )
    }

    @Test
    fun `curative item effect migration uses executable call evidence`() {
        val source = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyCurativeItemEffectsSource")
        assertTrue(start >= 0, "migrateLegacyCurativeItemEffectsSource is missing")
        val end = source.indexOf("private fun legacyCurativeItemEffectCure", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw curePotionEffects prefilter" to """source.contains(".curePotionEffects(")""",
            "raw curePotionEffects rewrite" to """rewriteJavaCall(source, "curePotionEffects")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".curePotionEffects(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "curePotionEffects")""") &&
                body.contains("legacyCurativeItemEffectCure(args[0])"),
            "Curative item effect migration must inspect executable calls and preserve proven cure argument matching"
        )
        assertTrue(
            offenders.isEmpty(),
            "Curative item effect migration must not rewrite comments or string literals: $offenders"
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
    fun `recipe serializer factory generic call migration uses executable call evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRecipeSerializerFactoryCallSites")
        assertTrue(start >= 0, "migrateRecipeSerializerFactoryCallSites is missing")
        val end = source.indexOf("private fun migrateGenericCookingRecipeOutputBuilderSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw generic prefilter" to """source.contains(".generic(")""",
            "raw generic rewrite" to """rewriteJavaCall(source, "generic")"""
        )
            .filter { (_, marker) -> body.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".generic(")""") &&
                body.contains("""rewriteExecutableJavaCall(source, "generic")""") &&
                body.contains("""receiver == "SimpleCookingRecipeBuilder"""") &&
                body.contains("normalizeRecipeSerializerExpression(args.last())") &&
                body.contains("recipeSerializerFactoryHints.fieldToFactory[serializerExpression]"),
            "Recipe serializer factory generic call migration must derive call sites from executable Java and keep serializer hint binding"
        )
        assertTrue(
            offenders.isEmpty(),
            "Recipe serializer factory generic call migration must not rewrite comments or string literals: $offenders"
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
            "java file-name owner fallback" to Regex("""classNameOfJavaSource\(source\)\s*\?:\s*javaFile\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "raw source method scan" to Regex("""methodPattern\.findAll\(source\)"""),
            "raw source owner declaration" to Regex("""classNameOfJavaSource\(source\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "collectLegacyItemStackPredicateOverrideMethods contains $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("classNameOfJavaSource(executableCode)") &&
                body.contains("methodPattern.findAll(executableCode)"),
            "Registry-backed item-stack predicate method collection must use executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Registry-backed item-stack predicate migrations must use source-declared Java owners, not Java file-name fallback inference: $offenders"
        )
    }

    @Test
    fun `registry backed item stack predicate registry access requires unique player backed source type`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyRegistryBackedItemStackPredicateOverrides")
        assertTrue(start >= 0, "migrateLegacyRegistryBackedItemStackPredicateOverrides is missing")
        val end = source.indexOf("private fun migrateLegacyItemStackPredicateOverrideCallSites", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw inventory field scan" to Regex("""\.find(?:All)?\(source\)"""),
            "global simple type fallback" to Regex("""javaTypes\.(?:singleOrNull|firstOrNull)\s*\{[^}]*className"""),
            "unconditional player field visibility" to Regex("""var\s+result\s*=\s*migratePlayerBackedSimpleContainerFieldVisibility\(source\)"""),
            "raw map rewrite result replace" to Regex("""\.replace\(result,\s*"Map<Function<RegistryAccess""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("legacyItemStackPredicateOverrideMethodAccesses(methods, javaTypes)") &&
                body.contains("requiredContainerTypes") &&
                body.contains("currentTypeFqn in requiredContainerTypes") &&
                body.contains("resolveKnownJavaTypeReference") &&
                body.contains("isPlayerBackedSimpleContainerType(type)") &&
                body.contains("inventoryFields.singleOrNull()") &&
                body.contains("replaceExecutableRegex("),
            "Registry-backed item-stack predicate migration must resolve a unique source-backed player container before rewriting"
        )
        assertTrue(
            offenders.isEmpty(),
            "Registry-backed item-stack predicate migration must not guess registryAccess or rewrite raw comments: $offenders"
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
            "java file-name owner fallback" to Regex("""classNameOfJavaSource\(source\)\s*\?:\s*javaFile\.fileName\.toString\(\)\.removeSuffix\("\.java"\)"""),
            "raw source owner declaration" to Regex("""classNameOfJavaSource\(source\)"""),
            "raw source package declaration" to Regex("""packageNameOf\(source\)"""),
            "raw pattern field scan" to Regex("""\.findAll\(source\)"""),
            "raw method range scan" to Regex("""javaMethodRanges\(source\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "legacy banner component collectors contain $label" }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("classNameOfJavaSource(executableCode)") &&
                body.contains("packageNameOf(executableCode)") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("val code = maskJavaComments(source)") &&
                body.contains("classNameOfJavaSource(code)") &&
                body.contains("packageNameOf(code)") &&
                body.contains("javaMethodRanges(code)"),
            "Legacy banner component collectors must derive owners and factories from comment-masked Java source"
        )
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
        val offenders = listOf(
            "raw BannerPattern prefilter" to Regex("""source\.contains\(\s*"BannerPattern"\s*\)"""),
            "raw constructor prefilter" to Regex("""source\.contains\(\s*"new BannerPattern\("\s*\)"""),
            "raw constructor rewrite" to Regex("""\brewriteJavaNew\s*\(\s*source\s*,\s*"BannerPattern""""),
            "raw namespace scan" to Regex("""\.find\s*\(\s*source\s*\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> "legacy BannerPattern constructor migration contains $label" }

        assertTrue(
            offenders.isEmpty(),
            "Legacy BannerPattern constructor migration must inspect executable Java code, not comments or string literals: $offenders"
        )
        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("executableCode.contains(\"BannerPattern\")") &&
                body.contains("executableCode.contains(\"new BannerPattern(\")") &&
                body.contains("rewriteExecutableJavaNew(source, \"BannerPattern\")") &&
                body.contains("bannerPatternDeferredRegisterNamespaceExpression(source)") &&
                body.contains("rewriteExecutableJavaCall(source, \"create\")") &&
                body.contains("receiver == \"DeferredRegister\"") &&
                body.contains("args[0].trim() == \"Registries.BANNER_PATTERN\"") &&
                body.contains("namespaces.distinct().singleOrNull()"),
            "Legacy BannerPattern constructor migration must read executable constructor calls and unambiguous namespace evidence from the BANNER_PATTERN DeferredRegister"
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
    fun `legacy banner item factory call rewrites use executable source`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun rewriteQualifiedLegacyBannerItemStackFactoryCalls")
        assertTrue(start >= 0, "rewriteQualifiedLegacyBannerItemStackFactoryCalls is missing")
        val end = source.indexOf("private fun looksLikeJavaMethodDeclaration", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw qualified call rewrite" to Regex("""rewriteJavaCallWithOffset\(source,\s*factory\.methodName"""),
            "raw unqualified token scan" to Regex("""result\.indexOf\(token,\s*cursor\)"""),
            "raw unqualified paren matching" to Regex("""findMatchingParen\(result,\s*openParen\)"""),
            "raw declaration check" to Regex("""looksLikeJavaMethodDeclaration\(result,\s*tokenIndex,\s*closeParen\)""")
        )
            .filter { (_, pattern) -> pattern.containsMatchIn(body) }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("rewriteExecutableJavaCallWithOffset(source, factory.methodName)") &&
                body.contains("val executableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("executableCode.indexOf(token, cursor)") &&
                body.contains("findMatchingParen(executableCode, openParen)") &&
                body.contains("looksLikeJavaMethodDeclaration(executableCode, tokenIndex, closeParen)"),
            "Legacy banner item factory calls must be located in executable Java, not comments or strings"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy banner item factory calls must not rewrite commented examples: $offenders"
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
    fun `top level packet payload migration does not infer direction from names or client references`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val staticStart = source.indexOf("private fun staticPacketCandidate")
        assertTrue(staticStart >= 0, "staticPacketCandidate is missing")
        val staticEnd = source.indexOf("private fun packetReceptionDirection", staticStart + 1).let {
            if (it < 0) source.length else it
        }
        val staticBody = source.substring(staticStart, staticEnd)
        val directionStart = source.indexOf("private fun packetReceptionDirection")
        assertTrue(directionStart >= 0, "packetReceptionDirection is missing")
        val directionEnd = source.indexOf("private fun simpleChannelNamespaceExpression", directionStart + 1).let {
            if (it < 0) source.length else it
        }
        val directionBody = source.substring(directionStart, directionEnd)
        val migrationStart = source.indexOf("private fun transformPacketClasses")
        assertTrue(migrationStart >= 0, "transformPacketClasses is missing")
        val migrationEnd = source.indexOf("if (packetClasses.isEmpty())", migrationStart + 1).let {
            if (it < 0) source.length else it
        }
        val registrationBody = source.substring(migrationStart, migrationEnd)
        val combined = listOf(staticBody, directionBody, registrationBody).joinToString("\n")
        val offenders = listOf(
            "S2C class-name direction" to """className.startsWith("S2C")""",
            "ToClient class-name direction" to """className.contains("ToClient")""",
            "Minecraft client reference direction" to """packetContent.contains("Minecraft.getInstance()")""",
            "ClientLevel reference direction" to """packetContent.contains("ClientLevel")""",
            "Dist client reference direction" to """packetContent.contains("Dist.CLIENT")""",
            "client import direction" to """containsMatchIn(packetContent)""",
            "raw registerMessage prefilter" to """content.contains(".registerMessage(")""",
            "raw registerMessage scan" to "findAll(content)",
            "raw registration paren match" to "findMatchingParen(content, openParen)",
            "raw packet constructor scan" to ".find(packetContent)"
        )
            .filter { (_, marker) -> combined.contains(marker) }
            .map { (label, _) -> label }

        assertTrue(
            staticBody.contains("val executableCode = maskJavaCommentsAndLiterals(content)") &&
                staticBody.contains("packetReceptionDirection(executableCode)") &&
                directionBody.contains("hasClientReceptionGuard && !hasServerReceptionGuard -> true") &&
                directionBody.contains("hasServerReceptionGuard && !hasClientReceptionGuard -> false") &&
                registrationBody.contains("val executableContent = maskJavaCommentsAndLiterals(content)") &&
                registrationBody.contains("findAll(executableContent)") &&
                registrationBody.contains("findMatchingParen(executableContent, openParen)") &&
                registrationBody.contains("val executablePacketContent = maskJavaCommentsAndLiterals(packetContent)") &&
                registrationBody.contains("?: packetReceptionDirection(executablePacketContent)") &&
                registrationBody.contains("?: return@registrations"),
            "Top-level packet payload migration must use explicit registration direction or executable reception-side guards"
        )
        assertTrue(
            offenders.isEmpty(),
            "Top-level packet payload migration must not infer direction from class names, client references, or comments: $offenders"
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
    fun `curios attribute modifier holder migration uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCuriosAttributeModifierHolderTypes")
        assertTrue(start >= 0, "migrateCuriosAttributeModifierHolderTypes is missing")
        val end = source.indexOf("private fun migrateAttributeModifierMultimapHolderTypes", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw getAttributeModifiers prefilter" to body.contains("""source.contains("getAttributeModifiers")"""),
            "raw AttributeModifier prefilter" to body.contains("""source.contains("AttributeModifier")"""),
            "raw Multimap prefilter" to body.contains("""source.contains("Multimap")"""),
            "raw Attribute prefilter" to body.contains("""source.contains("Attribute")"""),
            "raw Curios type scan" to body.contains("containsMatchIn(source)"),
            "raw Multimap replacement" to body.contains(".replace(result, \"Multimap<Holder<Attribute>, AttributeModifier>\")"),
            "raw ImmutableMultimap builder replacement" to body.contains(".replace(result, \"ImmutableMultimap.Builder<Holder<Attribute>, AttributeModifier>\")"),
            "raw ImmutableMultimap replacement" to body.contains(".replace(result, \"ImmutableMultimap<Holder<Attribute>, AttributeModifier>\")"),
            "raw Holder import insertion" to body.contains("""addImportIfMissing(result, "net.minecraft.core.Holder")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("getAttributeModifiers")""") &&
                body.contains("""executableCode.contains("AttributeModifier")""") &&
                body.contains("""executableCode.contains("Multimap")""") &&
                body.contains("""executableCode.contains("Attribute")""") &&
                body.contains("containsMatchIn(executableCode)") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("""\bMultimap\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""") &&
                body.contains("""\bImmutableMultimap\.Builder\s*<\s*Attribute\s*,\s*AttributeModifier\s*>""") &&
                body.contains("""addExecutableImportIfMissing(result, "net.minecraft.core.Holder")"""),
            "Curios attribute modifier holder migration must rewrite only executable Curios attribute generic surfaces"
        )
        assertTrue(
            offenders.isEmpty(),
            "Curios attribute modifier holder migration must not rewrite comments or strings: $offenders"
        )
    }

    @Test
    fun `legacy item max damage migration uses executable item declarations`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyItemMaxDamageCalls")
        assertTrue(start >= 0, "migrateLegacyItemMaxDamageCalls is missing")
        val end = source.indexOf("private fun migrateRecipeHolderIdAndLocalMmlibApi", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw max damage prefilter" to body.contains("""source.contains(".getMaxDamage()")"""),
            "raw Item declaration scan" to body.contains("containsMatchIn(source)"),
            "raw Item variable collection" to body.contains(".findAll(source)"),
            "raw max damage replacement" to body.contains(".replace(result,")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains(".getMaxDamage()")""") &&
                body.contains("containsMatchIn(executableCode)") &&
                body.contains(".findAll(executableCode)") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("""\b${'$'}{Regex.escape(variable)}\.getMaxDamage\(\)"""),
            "Legacy Item.getMaxDamage migration must derive variables from executable declarations and rewrite executable calls only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Legacy Item.getMaxDamage migration must not use comments or strings as Item variable evidence: $offenders"
        )
    }

    @Test
    fun `cacheable function optional boundary migration uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateCacheableFunctionOptionalBoundaries")
        assertTrue(start >= 0, "migrateCacheableFunctionOptionalBoundaries is missing")
        val end = source.indexOf("private fun migrateRecipeHolderAccess", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw CacheableFunction prefilter" to body.contains("""source.contains("CacheableFunction")"""),
            "raw getFunction prefilter" to body.contains("""source.contains("getFunction()")"""),
            "raw executeFunction prefilter" to body.contains("""source.contains("BlockStateRecipeUtil.executeFunction(")"""),
            "raw assignment replacement" to body.contains(".replace(result)"),
            "raw executeFunction postfilter" to body.contains("""result.contains("BlockStateRecipeUtil.executeFunction(")"""),
            "raw Optional import insertion" to body.contains("""addImportIfMissing(result, "java.util.Optional")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("CacheableFunction")""") &&
                body.contains("""executableCode.contains("getFunction()")""") &&
                body.contains("""executableCode.contains("BlockStateRecipeUtil.executeFunction(")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("maskJavaCommentsAndLiterals(result)") &&
                body.contains("""addExecutableImportIfMissing(result, "java.util.Optional")"""),
            "CacheableFunction optional boundary migration must inspect and rewrite executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "CacheableFunction optional boundary migration must not migrate comments or strings: $offenders"
        )
    }

    @Test
    fun `recipe holder access migration binds declared names instead of fixed locals`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRecipeHolderAccess")
        assertTrue(start >= 0, "migrateRecipeHolderAccess is missing")
        val end = source.indexOf("private fun migrateLegacyItemMaxDamageCalls", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "fixed rm getRecipes prefilter" to body.contains("""source.contains("rm.getRecipes()")"""),
            "fixed rm byKey prefilter" to body.contains("""source.contains("rm.byKey(")"""),
            "fixed all collection replacement" to body.contains("""Collection<Recipe<?>> all = rm.getRecipes();"""),
            "fixed recipe helper signature replacement" to body.contains("""private static Recipe<?> recipe("""),
            "fixed r local replacement" to body.contains("""Recipe<?> r = recipe("""),
            "fixed r receiver rewrite" to body.contains("""receiver == "r""""),
            "fixed RegistryAccess ra rewrite" to body.contains("""RegistryAccess ra"""),
            "raw RecipeHolder import insertion" to body.contains("""addImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("val recipeCollectionPattern = Regex(") &&
                body.contains("val collectionNames = recipeCollectionPattern.findAll(executableCode)") &&
                body.contains("val recipeHolderMethods = linkedSetOf<String>()") &&
                body.contains("val methodPattern = Regex(") &&
                body.contains("contains(\".byKey(\")") &&
                body.contains("replaceExecutableRegex(result, recipeCollectionPattern)") &&
                body.contains("val holderVariables = linkedSetOf<String>()") &&
                body.contains("val registryAccessVariables = Regex(") &&
                body.contains("""addExecutableImportIfMissing(result, "net.minecraft.world.item.crafting.RecipeHolder")"""),
            "Recipe holder access migration must bind RecipeManager lists, helper methods, holder locals, and RegistryAccess names from executable source"
        )
        assertTrue(
            offenders.isEmpty(),
            "Recipe holder access migration must not depend on fixed sample local names: $offenders"
        )
    }

    @Test
    fun `nitrogen block property pair runtime migration uses executable source evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateNitrogenBlockPropertyPairRuntimeAccess")
        assertTrue(start >= 0, "migrateNitrogenBlockPropertyPairRuntimeAccess is missing")
        val end = source.indexOf("private fun migrateRecipeDisplayRecipeIdWithoutHolderSource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw BlockPropertyPair prefilter" to body.contains("""source.contains("BlockPropertyPair")"""),
            "raw getFluid scan" to body.contains("""result.contains(".getFluid()")"""),
            "raw LiquidBlock scan" to body.contains("""result.contains("LiquidBlock")"""),
            "raw getFluid replacement" to body.contains(""".replace(result)"""),
            "raw loop scan" to body.contains("loopPattern.find(result"),
            "raw brace matching" to body.contains("findMatchingBrace(result, openBrace)")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("""executableCode.contains("BlockPropertyPair")""") &&
                body.contains("""executableCode.contains(".getFluid()")""") &&
                body.contains("""executableCode.contains("LiquidBlock")""") &&
                body.contains("replaceExecutableRegex(") &&
                body.contains("val executableResult = maskJavaCommentsAndLiterals(result)") &&
                body.contains("loopPattern.find(executableResult, cursor)") &&
                body.contains("findMatchingBrace(executableResult, openBrace)"),
            "Nitrogen BlockPropertyPair runtime migration must inspect and rewrite executable Java only"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nitrogen BlockPropertyPair runtime migration must not use comments or strings as evidence: $offenders"
        )
    }

    @Test
    fun `recipe display recipe id migration uses executable recipe receiver evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateRecipeDisplayRecipeIdWithoutHolderSource")
        assertTrue(start >= 0, "migrateRecipeDisplayRecipeIdWithoutHolderSource is missing")
        val end = source.indexOf("private fun migrateLegacyPlacementBanDisplaySource", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val legacyDisplayStart = source.indexOf("private fun migrateLegacyPlacementBanDisplaySource")
        assertTrue(legacyDisplayStart >= 0, "migrateLegacyPlacementBanDisplaySource is missing")
        val legacyDisplayEnd = source.indexOf("private fun replacePlacementBanJeiSetRecipe", legacyDisplayStart + 1).let {
            if (it < 0) source.length else it
        }
        val legacyDisplayBody = source.substring(legacyDisplayStart, legacyDisplayEnd)
        val offenders = listOf(
            "raw BasicDisplay prefilter" to body.contains("""source.contains("extends BasicDisplay")"""),
            "raw Recipe wildcard prefilter" to body.contains("""source.contains("Recipe<?>")"""),
            "raw Optional prefilter" to body.contains("""source.contains("Optional.of(")"""),
            "raw getId prefilter" to body.contains("""source.contains(".getId()")"""),
            "fixed recipe receiver" to body.contains("""Optional\.of\(\s*recipe\.getId"""),
            "raw source replacement" to body.contains(""".replace(source, "Optional.empty()")"""),
            "legacy display fixed recipe receiver" to legacyDisplayBody.contains("""result.replace("Optional.of(recipe.getId())", "Optional.empty()")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                body.contains("replaceExecutableRegex(source, recipeIdOptionalPattern)") &&
                body.contains("recipeDisplayRecipeReceiverAt(") &&
                body.contains("match.groupValues[1]") &&
                body.contains("javaInheritanceIndex") &&
                body.contains("recipeImplementationClasses"),
            "Recipe display id migration must bind executable Optional.of(receiver.getId()) calls to proven Recipe parameters"
        )
        assertTrue(
            offenders.isEmpty(),
            "Recipe display id migration must not depend on comments, strings, or fixed receiver names: $offenders"
        )
    }

    @Test
    fun `placement ban display bypass block migration uses executable receiver evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("private fun migrateLegacyPlacementBanDisplaySource")
        assertTrue(start >= 0, "migrateLegacyPlacementBanDisplaySource is missing")
        val end = source.indexOf("private fun replacePlacementBanJeiSetRecipe", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val helperStart = source.indexOf("private fun migratePlacementBanBypassBlockOptionalAccess")
        assertTrue(helperStart >= 0, "migratePlacementBanBypassBlockOptionalAccess is missing")
        val helperEnd = source.indexOf("private fun replacePlacementBanJeiSetRecipe", helperStart + 1).let {
            if (it < 0) source.length else it
        }
        val helper = source.substring(helperStart, helperEnd)
        val offenders = listOf(
            "fixed recipe bypass declaration" to body.contains("""BlockStateIngredient bypassBlockIngredient = recipe.getBypassBlock();"""),
            "fixed display bypass declaration" to body.contains("""var bypassBlock = display.getBypassBlock();"""),
            "fixed recipe null empty check" to body.contains("""recipe.getBypassBlock() == null || recipe.getBypassBlock().isEmpty()"""),
            "fixed display null empty check" to body.contains("""display.getBypassBlock() == null || display.getBypassBlock().isEmpty()"""),
            "fixed local bypass null empty check" to body.contains("""bypassBlock != null && !bypassBlock.isEmpty()"""),
            "fixed recipe getPairs" to body.contains("""recipe.getBypassBlock().getPairs()"""),
            "fixed local getPairs" to body.contains("""bypassBlock.getPairs()"""),
            "fixed display input index rewrite" to body.contains("""display.getInputEntries().get(0), bypassBlock.get().getPairs()"""),
            "fixed REIUtils recipe rewrite" to body.contains("""REIUtils.toIngredientList(recipe.getBypassBlock()""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("migratePlacementBanBypassBlockOptionalAccess(result)") &&
                helper.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                helper.contains("replaceExecutableRegex(") &&
                helper.contains("val bypassBlockLocals = Regex(") &&
                helper.contains("match.groupValues[1]") &&
                helper.contains("getBypassBlock().map(blockStateIngredient -> REIUtils.toIngredientList(blockStateIngredient.getPairs())).orElse(List.of())"),
            "PlacementBan display bypass migration must derive receivers and local names from executable source"
        )
        assertTrue(
            offenders.isEmpty(),
            "PlacementBan display bypass migration must not depend on fixed recipe/display/bypassBlock sample names: $offenders"
        )
    }

    @Test
    fun `placement ban display biome migration uses executable receiver evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val helperStart = source.indexOf("private fun migratePlacementBanBiomeAccess")
        assertTrue(helperStart >= 0, "migratePlacementBanBiomeAccess is missing")
        val start = source.indexOf("private fun migrateLegacyPlacementBanDisplaySource")
        assertTrue(start >= 0, "migrateLegacyPlacementBanDisplaySource is missing")
        val body = source.substring(start, helperStart)
        val helperEnd = source.indexOf("private fun migratePlacementBanBypassBlockOptionalAccess", helperStart + 1).let {
            if (it < 0) source.length else it
        }
        val helper = source.substring(helperStart, helperEnd)
        val offenders = listOf(
            "fixed Optional recipe biome pair" to body.contains("""Optional.ofNullable(recipe.getBiomeKey())"""),
            "fixed recipe constructor reorder" to body.contains("""recipe.getBypassBlock(), recipe.getBiome()"""),
            "fixed recipe biome pair" to body.contains("""recipe.getBiomeKey(), recipe.getBiomeTag()"""),
            "fixed display biome pair" to body.contains("""display.getBiomeKey(), display.getBiomeTag()"""),
            "fixed display populate rewrite" to body.contains("""this.populateBiomeInformation(display.getBiomeKey(), display.getBiomeTag(), tooltip);"""),
            "fixed recipe populate rewrite" to body.contains("""this.populateBiomeInformation(recipe.getBiomeKey(), recipe.getBiomeTag(), tooltip);""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("migratePlacementBanBiomeAccess(result, placementBanBaseClasses, placementBanDisplayClasses)") &&
                helper.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                helper.contains("val receiverTypes = placementBanBiomeReceiverTypes(") &&
                helper.contains("replaceExecutableRegex(") &&
                helper.contains("placementBanBiomeReceiverKind(receiver, receiverTypes)") &&
                helper.contains("PlacementBanBiomeReceiverKind.DISPLAY") &&
                helper.contains("PlacementBanBiomeReceiverKind.RECIPE") &&
                helper.contains("null -> match.value"),
            "PlacementBan display biome migration must derive recipe/display receivers from executable source"
        )
        assertTrue(
            offenders.isEmpty(),
            "PlacementBan display biome migration must not depend on fixed recipe/display sample names: $offenders"
        )
    }

    @Test
    fun `nullable import cleanup uses executable annotation evidence`() {
        val projectRoot = Path.of("").toAbsolutePath()
        val source = projectRoot
            .resolve("src/main/kotlin/com/modporter/core/transforms/structural/StructuralRefactorPass.kt")
            .readText()
        val start = source.indexOf("val nullableExecutableCode = maskJavaCommentsAndLiterals(result)")
        assertTrue(start >= 0, "Nullable import cleanup block is missing")
        val end = source.indexOf("return result", start + 1).let {
            if (it < 0) source.length else it
        }
        val body = source.substring(start, end)
        val offenders = listOf(
            "raw nullable annotation scan" to body.contains("""result.contains("@Nullable")"""),
            "raw JetBrains Nullable import scan" to body.contains("""result.contains("import org.jetbrains.annotations.Nullable;")"""),
            "raw javax Nullable import scan" to body.contains("""result.contains("import javax.annotation.Nullable;")"""),
            "raw javax Nullable import insertion" to body.contains("""addImportIfMissing(result, "javax.annotation.Nullable")"""),
            "raw javax Nullable import removal" to body.contains("""removeImport(result, "javax.annotation.Nullable")""")
        )
            .filter { (_, found) -> found }
            .map { (label, _) -> label }

        assertTrue(
            body.contains("val nullableExecutableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("""(?<![\w$])@Nullable(?![\w$])""") &&
                body.contains("""nullableExecutableCode.contains("import org.jetbrains.annotations.Nullable;")""") &&
                body.contains("""nullableExecutableCode.contains("import javax.annotation.Nullable;")""") &&
                body.contains("""addExecutableImportIfMissing(result, "javax.annotation.Nullable")""") &&
                body.contains("val nullableImportExecutableCode = maskJavaCommentsAndLiterals(result)") &&
                body.contains("""removeExecutableImport(result, "javax.annotation.Nullable")"""),
            "Nullable import cleanup must use executable annotation/import evidence"
        )
        assertTrue(
            offenders.isEmpty(),
            "Nullable import cleanup must not use comments or strings as annotation/import evidence: $offenders"
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
    fun `tier sorting registry migration uses executable source evidence`() {
        val textPass = Path.of("")
            .toAbsolutePath()
            .resolve("src/main/kotlin/com/modporter/core/transforms/text/TextReplacementPass.kt")
            .readText()
        val tierStart = textPass.indexOf("private fun migrateTierSortingRegistryTiers")
        assertTrue(tierStart >= 0, "migrateTierSortingRegistryTiers is missing")
        val tierEnd = textPass.indexOf("private fun migrateTierSortingRegistryDropChecks", tierStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val tierBody = textPass.substring(tierStart, tierEnd)
        val dropStart = textPass.indexOf("private fun migrateTierSortingRegistryDropChecks")
        assertTrue(dropStart >= 0, "migrateTierSortingRegistryDropChecks is missing")
        val dropEnd = textPass.indexOf("private fun tierSortingDropCheckExpression", dropStart + 1).let {
            if (it < 0) textPass.length else it
        }
        val dropBody = textPass.substring(dropStart, dropEnd)
        val offenders = listOf(
            "raw register prefilter" to tierBody.contains("""source.contains("TierSortingRegistry.registerTier(")"""),
            "raw drop prefilter" to tierBody.contains("""source.contains("TierSortingRegistry.isCorrectTierForDrops(")"""),
            "raw result register prefilter" to tierBody.contains("""result.contains("TierSortingRegistry.registerTier(")"""),
            "raw register scan" to tierBody.contains("result.indexOf(marker"),
            "raw register open paren scan" to tierBody.contains("result.indexOf('(', markerIndex"),
            "raw register delimiter scan" to tierBody.contains("findMatchingDelimiter(result, openParen"),
            "raw drop marker prefilter" to dropBody.contains("source.contains(marker)"),
            "raw drop scan" to dropBody.contains("source.indexOf(marker"),
            "raw drop open paren scan" to dropBody.contains("source.indexOf('(', markerIndex"),
            "raw drop delimiter scan" to dropBody.contains("findMatchingDelimiter(source, openParen")
        )
            .filter { (_, failed) -> failed }
            .map { (label, _) -> label }

        assertTrue(
            tierBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                tierBody.contains("""executableCode.contains("TierSortingRegistry.registerTier(")""") &&
                tierBody.contains("""executableCode.contains("TierSortingRegistry.isCorrectTierForDrops(")""") &&
                tierBody.contains("val executableResult = maskJavaCommentsAndLiterals(result)") &&
                tierBody.contains("executableResult.indexOf(marker, cursor)") &&
                tierBody.contains("findMatchingDelimiter(executableResult, openParen") &&
                dropBody.contains("val executableCode = maskJavaCommentsAndLiterals(source)") &&
                dropBody.contains("executableCode.contains(marker)") &&
                dropBody.contains("executableCode.indexOf(marker, cursor)") &&
                dropBody.contains("findMatchingDelimiter(executableCode, openParen"),
            "TierSortingRegistry migration must locate registerTier and drop checks in executable Java source"
        )
        assertTrue(
            offenders.isEmpty(),
            "TierSortingRegistry migration must not rewrite comments or strings: $offenders"
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
