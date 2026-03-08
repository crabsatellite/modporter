package com.modporter.core.pipeline

import mu.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Pipeline orchestrator that runs transformation passes in order.
 */
class Pipeline(
    private val passes: List<Pass>,
    private val minConfidence: Confidence = Confidence.LOW,
    private val dryRun: Boolean = false,
    private val pipelineName: String = "ModPorter"
) {
    /**
     * Run all applicable passes on the project.
     */
    fun run(projectDir: Path): PipelineResult {
        logger.info { "Starting $pipelineName pipeline on: $projectDir" }
        logger.info { "Mode: ${if (dryRun) "DRY RUN" else "APPLY"}, Min confidence: $minConfidence" }

        val sortedPasses = passes.sortedBy { it.order }
        val results = mutableListOf<PassResult>()

        for (pass in sortedPasses) {
            if (!pass.isApplicable(projectDir)) {
                logger.info { "Skipping pass '${pass.name}' (not applicable)" }
                continue
            }

            logger.info { "Running pass ${pass.order}: ${pass.name}" }

            val result = if (dryRun) {
                pass.analyze(projectDir)
            } else {
                pass.apply(projectDir)
            }

            // Filter by confidence (lower ordinal = higher confidence)
            // HIGH=0, MEDIUM=1, LOW=2 — keep changes with ordinal <= minConfidence ordinal
            val filtered = result.copy(
                changes = result.changes.filter { it.confidence.ordinal <= minConfidence.ordinal }
            )

            results.add(filtered)
            logger.info {
                "Pass '${pass.name}' complete: ${filtered.changeCount} changes " +
                "(${filtered.highConfidence} high, ${filtered.mediumConfidence} medium, ${filtered.lowConfidence} low)"
            }

            if (filtered.errors.isNotEmpty()) {
                logger.warn { "Pass '${pass.name}' had ${filtered.errors.size} errors" }
                filtered.errors.forEach { logger.warn { "  - $it" } }
            }
        }

        return PipelineResult(results, dryRun, pipelineName)
    }
}

/**
 * Aggregate result of the entire pipeline run.
 */
data class PipelineResult(
    val passResults: List<PassResult>,
    val dryRun: Boolean,
    val pipelineName: String = "ModPorter"
) {
    val totalChanges get() = passResults.sumOf { it.changeCount }
    val totalErrors get() = passResults.sumOf { it.errors.size }

    fun summary(): String = buildString {
        appendLine("═══════════════════════════════════════════")
        appendLine("  $pipelineName Pipeline ${if (dryRun) "(DRY RUN)" else ""} Summary")
        appendLine("═══════════════════════════════════════════")
        for (result in passResults) {
            appendLine("  ${result.passName}: ${result.changeCount} changes")
        }
        appendLine("───────────────────────────────────────────")
        appendLine("  Total changes: $totalChanges")
        appendLine("  Total errors: $totalErrors")
        appendLine("═══════════════════════════════════════════")
    }
}
