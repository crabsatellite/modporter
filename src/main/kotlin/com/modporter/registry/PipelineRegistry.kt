package com.modporter.registry

import com.modporter.pipelines.forge2neo.Forge2NeoPipeline
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Central registry of available migration pipelines.
 * Pipelines are registered at startup and can be looked up by ID or auto-detected.
 */
object PipelineRegistry {
    private val pipelines = mutableMapOf<String, PipelineDefinition>()

    init {
        register(Forge2NeoPipeline.definition)
    }

    fun register(definition: PipelineDefinition) {
        pipelines[definition.id] = definition
        logger.debug { "Registered pipeline: ${definition.id} (${definition.displayName})" }
    }

    fun get(id: String): PipelineDefinition? = pipelines[id]

    fun list(): List<PipelineDefinition> = pipelines.values.toList()

    /**
     * Auto-detect which pipeline to use based on project contents.
     * Scans Java files for framework-specific patterns.
     */
    fun detect(projectDir: Path): PipelineDefinition? {
        val javaFiles = if (Files.exists(projectDir.resolve("src/main/java"))) {
            Files.walk(projectDir.resolve("src/main/java"))
                .filter { it.toString().endsWith(".java") }
                .toList()
                .take(20) // Sample first 20 files for speed
        } else {
            emptyList()
        }

        if (javaFiles.isEmpty()) return pipelines.values.firstOrNull()

        val sampleContent = javaFiles.joinToString("\n") { it.toFile().readText() }

        // Score each pipeline by how many detection patterns match
        return pipelines.values
            .map { def ->
                val score = def.detectionPatterns.count { pattern -> sampleContent.contains(pattern) }
                def to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}
