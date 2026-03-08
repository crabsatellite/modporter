package com.modporter.registry

import com.modporter.core.pipeline.Pass
import com.modporter.mapping.MappingDatabase
import java.nio.file.Path

/**
 * Describes a migration pipeline: source framework, target framework,
 * mapping files, and the passes to execute.
 */
data class PipelineDefinition(
    val id: String,
    val displayName: String,
    val sourceFramework: String,
    val targetFramework: String,
    val mappingsPrefix: String,
    val passFactory: (MappingDatabase) -> List<Pass>,
    val validationPatterns: List<String> = emptyList(),
    val detectionPatterns: List<String> = emptyList()
)
