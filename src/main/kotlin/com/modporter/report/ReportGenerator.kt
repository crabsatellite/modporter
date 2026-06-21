package com.modporter.report

import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.PipelineResult
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Generates detailed migration reports in Markdown format.
 */
class ReportGenerator {

    fun generate(result: PipelineResult, outputPath: Path) {
        val lowChangeCount = result.passResults.sumOf { it.lowConfidence }
        val report = buildString {
            appendLine("# ${result.pipelineName} Migration Report")
            appendLine()
            appendLine("**Mode**: ${if (result.dryRun) "DRY RUN (no changes applied)" else "APPLIED"}")
            appendLine("**Total changes**: ${result.totalChanges}")
            appendLine("**Total skipped**: ${result.totalSkipped}")
            appendLine("**Total errors**: ${result.totalErrors}")
            appendLine()

            appendLine("## Summary by Pass")
            appendLine()
            appendLine("| Pass | Changes | High | Medium | Low | Skipped | Errors |")
            appendLine("|------|---------|------|--------|-----|---------|--------|")
            for (passResult in result.passResults) {
                appendLine(
                    "| ${passResult.passName} | ${passResult.changeCount} | " +
                        "${passResult.highConfidence} | ${passResult.mediumConfidence} | " +
                        "${passResult.lowConfidence} | ${passResult.skipped.size} | ${passResult.errors.size} |"
                )
            }
            appendLine()

            appendLine("## Changes Requiring Automated Validation")
            appendLine()
            appendLine("### MEDIUM Confidence (expected to validate under strict gates)")
            appendLine()
            for (passResult in result.passResults) {
                val mediumChanges = passResult.changes.filter { it.confidence == Confidence.MEDIUM }
                if (mediumChanges.isNotEmpty()) {
                    appendLine("#### ${passResult.passName}")
                    for (change in mediumChanges) {
                        appendLine("- **${change.file}:${change.line}** - ${change.description}")
                        appendLine("  - Before: `${change.before}`")
                        appendLine("  - After: `${change.after}`")
                    }
                    appendLine()
                }
            }

            appendLine("### LOW Confidence (blocks strict success until an automated gate covers it)")
            appendLine()
            for (passResult in result.passResults) {
                val lowChanges = passResult.changes.filter { it.confidence == Confidence.LOW }
                if (lowChanges.isNotEmpty()) {
                    appendLine("#### ${passResult.passName}")
                    for (change in lowChanges) {
                        appendLine("- **${change.file}:${change.line}** - ${change.description}")
                        appendLine("  - Before: `${change.before}`")
                        appendLine("  - After: `${change.after}`")
                    }
                    appendLine()
                }
            }

            if (result.totalSkipped > 0) {
                appendLine("## Skipped Source Shapes")
                appendLine()
                appendLine(
                    "Skipped source shapes are incomplete migrations and must be closed by parser support " +
                        "or explicit automated rules before a hands-off port is accepted."
                )
                appendLine()
                for (passResult in result.passResults) {
                    if (passResult.skipped.isNotEmpty()) {
                        appendLine("### ${passResult.passName}")
                        for (skipped in passResult.skipped) {
                            appendLine("- $skipped")
                        }
                        appendLine()
                    }
                }
            }

            if (result.totalErrors > 0) {
                appendLine("## Errors")
                appendLine()
                for (passResult in result.passResults) {
                    if (passResult.errors.isNotEmpty()) {
                        appendLine("### ${passResult.passName}")
                        for (error in passResult.errors) {
                            appendLine("- $error")
                        }
                        appendLine()
                    }
                }
            }

            if (result.totalErrors > 0 || result.totalSkipped > 0 || lowChangeCount > 0) {
                appendLine("## Blocking Migration Work")
                appendLine()
                appendLine("Hands-off success is blocked by the concrete signals in this report:")
                appendLine()
                if (lowChangeCount > 0) {
                    appendLine("- **LOW confidence changes**: add automated coverage or replace with deterministic rules.")
                }
                if (result.totalSkipped > 0) {
                    appendLine("- **Skipped source shapes**: add parser support or explicit source-shape migrations.")
                }
                if (result.totalErrors > 0) {
                    appendLine("- **Reported errors**: fix the failed migration rules or source-derived hard gates.")
                }
                appendLine(
                    "- **Final gates**: compile, client, server, GameTest, world-load, " +
                        "creative-tab browsing, and clean runtime logs must pass."
                )
            }
        }

        outputPath.writeText(report)
    }
}
