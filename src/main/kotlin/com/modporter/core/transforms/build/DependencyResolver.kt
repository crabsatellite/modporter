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
 * 3. Fall back to exclusion for unavailable deps
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
        // Find matching known dep entry
        val known = knownDeps.find { forgeDep.contains(it.forgePrefix) }

        if (known != null) {
            return when (known.status) {
                "available" -> {
                    if (known.neoforgeCoords.isNotEmpty()) {
                        log.info("Resolved dependency: ${known.forgePrefix} → NeoForge (${known.notes})")
                        DepResolution.Resolved(
                            coords = known.neoforgeCoords,
                            mavenUrl = known.mavenUrl,
                            notes = known.notes
                        )
                    } else {
                        log.warn("Dep ${known.forgePrefix} marked available but no coords specified")
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
                else -> {
                    log.info("Dependency unavailable: ${known.forgePrefix} (${known.notes})")
                    DepResolution.Unavailable(known.notes)
                }
            }
        }

        // Unknown dep: try Modrinth if online
        if (!offlineMode) {
            return resolveUnknownOnline(forgeDep)
        }

        return DepResolution.Unknown
    }

    private fun resolveOnline(known: KnownDep): DepResolution {
        val slug = known.modrinthSlug ?: return DepResolution.Unavailable("No Modrinth slug for ${known.forgePrefix}")

        onlineCache[slug]?.let { return it }

        val result = queryModrinth(slug)
        onlineCache[slug] = result
        return result
    }

    private fun resolveUnknownOnline(forgeDep: String): DepResolution {
        // Try to extract a slug from the dependency coordinate
        // e.g., "some.group:mod-name:1.0" → try "mod-name" as slug
        val parts = forgeDep.split(":")
        if (parts.size < 2) return DepResolution.Unknown

        val artifactId = parts[1].lowercase()
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
}

sealed class DepResolution {
    data class Resolved(
        val coords: List<NeoForgeCoord>,
        val mavenUrl: String?,
        val notes: String
    ) : DepResolution()

    data class Unavailable(val reason: String) : DepResolution()
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
    val neoforgeCoords: List<NeoForgeCoord> = emptyList(),
    val mavenUrl: String? = null,
    val status: String = "unavailable",
    val notes: String = ""
)

@Serializable
data class NeoForgeCoord(
    val config: String = "implementation",
    val coord: String,
    val transitive: Boolean = true
)

@Serializable
data class ModrinthVersion(
    val id: String = "",
    val version_number: String = "",
    val loaders: List<String> = emptyList(),
    val game_versions: List<String> = emptyList()
)
