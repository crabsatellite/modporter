package com.modporter.core.transforms.build

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Resolves Forge dependencies to their NeoForge 1.21.1 equivalents.
 *
 * Strategy:
 * 1. Check known-good mappings from neoforge-deps.json (offline-safe)
 * 2. If status is "check_online" and not offline, query Modrinth API
 * 3. Remove only deps with an explicit stale/bundled mapping
 * 4. Leave unavailable deps active so resolution or compilation fails honestly
 */
class DependencyResolver(
    private val offlineMode: Boolean = false,
    private val mappingsPrefix: String = "/mappings/forge2neo"
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }
    private val knownDeps: List<KnownDep> = loadKnownDeps()
    private val onlineCache = mutableMapOf<String, DepResolution>()

    fun resolve(forgeDep: String): DepResolution {
        val coordinate = extractDependencyCoordinate(forgeDep)
        val known = if (coordinate != null) {
            knownDeps.find { it.matches(coordinate) }
        } else {
            knownDeps.find { forgeDep.trim() == it.forgePrefix }
        }

        if (known != null) {
            return resolveKnown(known, coordinate)
        }

        // Unknown dep: try Modrinth if online
        if (!offlineMode) {
            return resolveUnknownOnline(coordinate ?: extractDependencyCoordinate(forgeDep) ?: return DepResolution.Unknown)
        }

        return DepResolution.Unknown
    }

    fun resolveReferencedClass(binaryName: String): DepResolution {
        val known = knownDeps.find { dep ->
            dep.packagePrefixes.any { prefix ->
                binaryName == prefix.trimEnd('.') || binaryName.startsWith(prefix)
            }
        } ?: return DepResolution.Unknown

        return resolveKnown(known)
    }

    fun targetVersionProperties(): List<KnownDepVersionProperty> =
        knownDeps.flatMap { it.versionProperties }

    fun targetVersionPropertiesForBuild(buildText: String): List<KnownDepVersionProperty> {
        val dependencyBlocks = gradleDependencyDeclarationBlocks(buildText)
        return knownDeps
            .filter { known ->
                dependencyBlocks.any { block ->
                    extractDependencyCoordinate(block)?.let { coordinate -> known.matches(coordinate) } == true
                }
            }
            .flatMap { it.versionProperties }
    }

    private fun resolveOnline(known: KnownDep): DepResolution {
        val slug = known.modrinthSlug ?: return DepResolution.Unavailable("No Modrinth slug for ${known.forgePrefix}")

        onlineCache[slug]?.let { return it }

        val result = queryModrinth(slug)
        onlineCache[slug] = result
        return result
    }

    private fun resolveKnown(known: KnownDep, coordinate: GradleDependencyCoordinate? = null): DepResolution =
        when (known.status) {
            "available", "compile_only_compat", "replacement" -> {
                if (known.neoforgeCoords.isNotEmpty()) {
                    log.info("Resolved dependency: ${known.forgePrefix} -> NeoForge (${known.notes})")
                    DepResolution.Resolved(
                        coords = known.neoforgeCoords,
                        mavenUrl = known.mavenUrl,
                        notes = known.notes
                    )
                } else {
                    log.warn("Dep ${known.forgePrefix} marked ${known.status} but no coords specified")
                    DepResolution.Unavailable(known.notes)
                }
            }
            "check_online" -> {
                if (offlineMode) {
                    log.info("Offline mode: skipping online check for ${known.forgePrefix}")
                    DepResolution.Unavailable("${known.notes} (offline mode, not checked)")
                } else {
                    resolveOnline(known)
                }
            }
            "runtime_absent" -> {
                if (known.removeConfigurations.isNotEmpty() && coordinate?.configuration !in known.removeConfigurations) {
                    log.info(
                        "Dependency unavailable outside scoped removal: ${known.forgePrefix} " +
                            "configuration=${coordinate?.configuration} (${known.notes})"
                    )
                    DepResolution.Unavailable(known.notes)
                } else {
                    log.info("Runtime dependency removed by explicit absent-target mapping: ${known.forgePrefix} (${known.notes})")
                    DepResolution.Remove(known.notes)
                }
            }
            "remove" -> {
                log.info("Dependency removed by explicit mapping: ${known.forgePrefix} (${known.notes})")
                DepResolution.Remove(known.notes)
            }
            else -> {
                log.info("Dependency unavailable: ${known.forgePrefix} (${known.notes})")
                DepResolution.Unavailable(known.notes)
            }
        }

    private fun resolveUnknownOnline(coordinate: GradleDependencyCoordinate): DepResolution {
        val artifactId = coordinate.artifact.lowercase()
            .replace(Regex("-forge$"), "")
            .replace(Regex("-1\\.\\d+\\.\\d+$"), "")

        if (artifactId.isBlank()) return DepResolution.Unknown

        onlineCache[artifactId]?.let { return it }

        val result = queryModrinth(artifactId)
        onlineCache[artifactId] = result
        return result
    }

    private fun queryModrinth(slug: String): DepResolution {
        return try {
            log.info("Querying Modrinth for NeoForge 1.21.1 version of '$slug'...")
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()

            val url = "https://api.modrinth.com/v2/project/$slug/version" +
                    "?loaders=%5B%22neoforge%22%5D&game_versions=%5B%221.21.1%22%5D"

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "modporter/0.2.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                parseModrinthResponse(slug, response.body())
            } else if (response.statusCode() == 404) {
                log.info("Modrinth: project '$slug' not found")
                DepResolution.Unavailable("Not found on Modrinth")
            } else {
                log.warn("Modrinth API returned ${response.statusCode()} for '$slug'")
                DepResolution.Unknown
            }
        } catch (e: Exception) {
            log.warn("Modrinth API error for '$slug': ${e.message}")
            DepResolution.Unknown
        }
    }

    private fun parseModrinthResponse(slug: String, body: String): DepResolution {
        try {
            val versions = json.decodeFromString<List<ModrinthVersion>>(body)
            if (versions.isEmpty()) {
                log.info("Modrinth: no NeoForge 1.21.1 versions for '$slug'")
                return DepResolution.Unavailable("No NeoForge 1.21.1 version on Modrinth")
            }

            // Use the first (latest) version
            val version = versions.first()
            val versionNumber = version.version_number

            log.info("Modrinth: found NeoForge 1.21.1 version '$versionNumber' for '$slug'")

            // Construct Modrinth Maven coordinates
            val coord = NeoForgeCoord(
                config = "implementation",
                coord = "maven.modrinth:$slug:$versionNumber"
            )

            return DepResolution.Resolved(
                coords = listOf(coord),
                mavenUrl = "https://api.modrinth.com/maven",
                notes = "Resolved from Modrinth (version $versionNumber)"
            )
        } catch (e: Exception) {
            log.warn("Failed to parse Modrinth response for '$slug': ${e.message}")
            return DepResolution.Unknown
        }
    }

    private fun loadKnownDeps(): List<KnownDep> {
        return try {
            val text = javaClass.getResourceAsStream("$mappingsPrefix/neoforge-deps.json")
                ?.bufferedReader()?.readText()
                ?: run {
                    log.warn("neoforge-deps.json not found at $mappingsPrefix")
                    return emptyList()
                }
            val db = json.decodeFromString<KnownDepsFile>(text)
            db.dependencies
        } catch (e: Exception) {
            log.error("Failed to load neoforge-deps.json: ${e.message}")
            emptyList()
        }
    }

    private fun extractDependencyCoordinate(text: String): GradleDependencyCoordinate? {
        val withoutLineComments = text.lines()
            .joinToString("\n") { line -> line.substringBefore("//") }
        val configuration = dependencyConfiguration(withoutLineComments)
        quotedCoordinate(withoutLineComments, configuration)?.let { return it }
        mapNotationCoordinate(withoutLineComments, configuration)?.let { return it }
        val trimmed = withoutLineComments.trim()
        if (trimmed.count { it == ':' } in 1..3 && !trimmed.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            return coordinateFromNotation(trimmed, configuration = null)
        }
        return null
    }

    private fun gradleDependencyDeclarationBlocks(text: String): List<String> {
        val depKeywords = listOf("compileOnly", "runtimeOnly", "implementation", "annotationProcessor", "def ")
        val lines = text.lines()
        val blocks = mutableListOf<String>()
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
                for (ch in lines[j]) {
                    when (ch) {
                        '(', '[' -> depth++
                        ')', ']' -> depth--
                    }
                }
                j++
            } while (j < lines.size && depth > 0)
            blocks += lines.subList(blockStart, j).joinToString("\n")
            i = j
        }
        return blocks
    }

    private fun dependencyConfiguration(text: String): String? =
        Regex("""^\s*([A-Za-z_][A-Za-z0-9_.]*)\b""")
            .find(text)
            ?.groupValues
            ?.get(1)

    private fun quotedCoordinate(text: String, configuration: String?): GradleDependencyCoordinate? {
        val quotePattern = Regex("""["']([^"']+:[^"']+)["']""")
        return quotePattern.findAll(text)
            .mapNotNull { coordinateFromNotation(it.groupValues[1], configuration) }
            .firstOrNull()
    }

    private fun mapNotationCoordinate(text: String, configuration: String?): GradleDependencyCoordinate? {
        val group = Regex("""\bgroup\s*:\s*["']([^"']+)["']""")
            .find(text)
            ?.groupValues
            ?.get(1)
        val artifact = Regex("""\b(?:name|artifact)\s*:\s*["']([^"']+)["']""")
            .find(text)
            ?.groupValues
            ?.get(1)
        if (group.isNullOrBlank() || artifact.isNullOrBlank()) return null
        val version = Regex("""\bversion\s*:\s*["']([^"']+)["']""")
            .find(text)
            ?.groupValues
            ?.get(1)
        return GradleDependencyCoordinate(
            configuration = configuration,
            group = group,
            artifact = artifact,
            version = version,
            classifier = null,
            notation = listOfNotNull(group, artifact, version).joinToString(":")
        )
    }

    private fun coordinateFromNotation(notation: String, configuration: String?): GradleDependencyCoordinate? {
        val parts = notation.split(":")
        if (parts.size < 2) return null
        val group = parts[0].trim()
        val artifact = parts[1].trim()
        if (group.isBlank() || artifact.isBlank()) return null
        return GradleDependencyCoordinate(
            configuration = configuration,
            group = group,
            artifact = artifact,
            version = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() },
            classifier = parts.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() },
            notation = notation
        )
    }

    private fun KnownDep.matches(coordinate: GradleDependencyCoordinate): Boolean {
        val prefix = forgePrefix.trim()
        if (':' in prefix) {
            val group = prefix.substringBefore(':')
            val artifactPrefix = prefix.substringAfter(':')
            if (!coordinate.group.equals(group, ignoreCase = false)) return false
            val artifact = coordinate.artifact.lowercase()
            val wanted = artifactPrefix.lowercase()
            return artifact == wanted || artifact.startsWith("$wanted-")
        }
        return coordinate.group == prefix || coordinate.group.startsWith("$prefix.")
    }
}

sealed class DepResolution {
    data class Resolved(
        val coords: List<NeoForgeCoord>,
        val mavenUrl: String?,
        val notes: String
    ) : DepResolution()

    data class Unavailable(val reason: String) : DepResolution()
    data class Remove(val reason: String) : DepResolution()
    data object Unknown : DepResolution()
}

@Serializable
data class KnownDepsFile(
    val description: String = "",
    val targetLoader: String = "neoforge",
    val targetGameVersion: String = "1.21.1",
    val dependencies: List<KnownDep> = emptyList()
)

@Serializable
data class KnownDep(
    val forgePrefix: String,
    val modrinthSlug: String? = null,
    val packagePrefixes: List<String> = emptyList(),
    val neoforgeCoords: List<NeoForgeCoord> = emptyList(),
    val versionProperties: List<KnownDepVersionProperty> = emptyList(),
    val removeConfigurations: List<String> = emptyList(),
    val mavenUrl: String? = null,
    val status: String = "unavailable",
    val notes: String = ""
)

@Serializable
data class KnownDepVersionProperty(
    val name: String,
    val value: String,
    val notes: String = ""
)

@Serializable
data class NeoForgeCoord(
    val config: String = "implementation",
    val coord: String,
    val transitive: Boolean = true
)

data class GradleDependencyCoordinate(
    val configuration: String?,
    val group: String,
    val artifact: String,
    val version: String?,
    val classifier: String?,
    val notation: String
)

@Serializable
data class ModrinthVersion(
    val id: String = "",
    val version_number: String = "",
    val loaders: List<String> = emptyList(),
    val game_versions: List<String> = emptyList()
)
