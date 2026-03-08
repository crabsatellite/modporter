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
        val report = buildString {
            appendLine("# ${result.pipelineName} Migration Report")
            appendLine()
            appendLine("**Mode**: ${if (result.dryRun) "DRY RUN (no changes applied)" else "APPLIED"}")
            appendLine("**Total changes**: ${result.totalChanges}")
            appendLine("**Total errors**: ${result.totalErrors}")
            appendLine()

            // Summary table
            appendLine("## Summary by Pass")
            appendLine()
            appendLine("| Pass | Changes | High | Medium | Low | Errors |")
            appendLine("|------|---------|------|--------|-----|--------|")
            for (passResult in result.passResults) {
                appendLine("| ${passResult.passName} | ${passResult.changeCount} | " +
                    "${passResult.highConfidence} | ${passResult.mediumConfidence} | " +
                    "${passResult.lowConfidence} | ${passResult.errors.size} |")
            }
            appendLine()

            // Detailed changes by confidence
            appendLine("## Changes Requiring Manual Review")
            appendLine()
            appendLine("### MEDIUM Confidence (likely correct, please verify)")
            appendLine()
            for (passResult in result.passResults) {
                val mediumChanges = passResult.changes.filter { it.confidence == Confidence.MEDIUM }
                if (mediumChanges.isNotEmpty()) {
                    appendLine("#### ${passResult.passName}")
                    for (change in mediumChanges) {
                        appendLine("- **${change.file}:${change.line}** — ${change.description}")
                        appendLine("  - Before: `${change.before}`")
                        appendLine("  - After: `${change.after}`")
                    }
                    appendLine()
                }
            }

            appendLine("### LOW Confidence (needs human verification)")
            appendLine()
            for (passResult in result.passResults) {
                val lowChanges = passResult.changes.filter { it.confidence == Confidence.LOW }
                if (lowChanges.isNotEmpty()) {
                    appendLine("#### ${passResult.passName}")
                    for (change in lowChanges) {
                        appendLine("- **${change.file}:${change.line}** — ${change.description}")
                        appendLine("  - Before: `${change.before}`")
                        appendLine("  - After: `${change.after}`")
                    }
                    appendLine()
                }
            }

            // Errors
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

            // Manual TODO list
            appendLine("## Remaining Manual Work")
            appendLine()
            appendLine("The following changes could NOT be automated and require manual intervention:")
            appendLine()
            appendLine("- [ ] **NBT → DataComponents**: Convert all `stack.getTag()`/`setTag()` to typed `DataComponentType` access")
            appendLine("- [ ] **Enchantment system**: Convert code-defined enchantments to data-driven JSON in `data/<modid>/enchantment/`")
            appendLine("- [ ] **Recipe system**: Update `Recipe<Container>` to `Recipe<RecipeInput>` with new input types")
            appendLine("- [ ] **build.gradle**: Update to NeoGradle plugin and Java 21 toolchain")
            appendLine("- [ ] **Rendering pipeline**: Verify vertex rendering changes (color format, buffer builder)")
            appendLine("- [ ] **Test compilation**: Run `./gradlew build` and fix remaining compile errors")
            appendLine("- [ ] **Runtime test**: Launch the game and verify mod functionality")
        }

        outputPath.writeText(report)
    }
}
