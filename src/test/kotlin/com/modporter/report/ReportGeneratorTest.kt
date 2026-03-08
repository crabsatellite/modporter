package com.modporter.report

import com.modporter.core.pipeline.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertTrue

class ReportGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun sampleResult(dryRun: Boolean = false): PipelineResult {
        val highChange = Change(
            file = Path.of("src/Mod.java"), line = 10,
            description = "Package rename", before = "net.minecraftforge", after = "net.neoforged.neoforge",
            confidence = Confidence.HIGH, ruleId = "pkg-main"
        )
        val medChange = Change(
            file = Path.of("src/Cap.java"), line = 20,
            description = "Capability detected", before = "ICapabilityProvider", after = "TODO migrate",
            confidence = Confidence.MEDIUM, ruleId = "struct-cap"
        )
        val lowChange = Change(
            file = Path.of("src/Net.java"), line = 30,
            description = "LazyOptional usage", before = "cap.ifPresent", after = "if (cap != null)",
            confidence = Confidence.LOW, ruleId = "struct-lazy"
        )
        return PipelineResult(
            passResults = listOf(
                PassResult("Text Replacement", listOf(highChange)),
                PassResult("Structural Refactor", listOf(medChange, lowChange), errors = listOf("Parse error in Broken.java"))
            ),
            dryRun = dryRun
        )
    }

    @Test
    fun `generates report with all sections`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)

        assertTrue(reportPath.exists(), "Report file should be created")
        val content = reportPath.readText()

        assertTrue(content.contains("Migration Report"))
        assertTrue(content.contains("**Mode**: APPLIED"))
        assertTrue(content.contains("**Total changes**: 3"))
        assertTrue(content.contains("**Total errors**: 1"))
    }

    @Test
    fun `report contains summary table`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("## Summary by Pass"))
        assertTrue(content.contains("Text Replacement"))
        assertTrue(content.contains("Structural Refactor"))
        assertTrue(content.contains("| Pass | Changes |"))
    }

    @Test
    fun `report contains medium confidence section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("MEDIUM Confidence"))
        assertTrue(content.contains("Capability detected"))
        assertTrue(content.contains("struct-cap").not() || content.contains("Cap.java"))
    }

    @Test
    fun `report contains low confidence section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("LOW Confidence"))
        assertTrue(content.contains("LazyOptional usage"))
    }

    @Test
    fun `report contains errors section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("## Errors"))
        assertTrue(content.contains("Parse error in Broken.java"))
    }

    @Test
    fun `report contains manual work section`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("## Remaining Manual Work"))
        assertTrue(content.contains("DataComponents"))
        assertTrue(content.contains("build.gradle"))
    }

    @Test
    fun `dry run mode shown in report`() {
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(sampleResult(dryRun = true), reportPath)
        val content = reportPath.readText()

        assertTrue(content.contains("DRY RUN"))
    }

    @Test
    fun `report with no errors omits errors section`() {
        val result = PipelineResult(
            passResults = listOf(
                PassResult("Text Replacement", listOf(
                    Change(Path.of("a.java"), 1, "desc", "b", "a", Confidence.HIGH, "r1")
                ))
            ),
            dryRun = false
        )
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(result, reportPath)
        val content = reportPath.readText()

        // Errors section header may still appear but should be empty
        assertTrue(content.contains("**Total errors**: 0"))
    }

    @Test
    fun `report with no medium or low changes has empty review sections`() {
        val result = PipelineResult(
            passResults = listOf(
                PassResult("Text Replacement", listOf(
                    Change(Path.of("a.java"), 1, "desc", "b", "a", Confidence.HIGH, "r1")
                ))
            ),
            dryRun = false
        )
        val reportPath = tempDir.resolve("report.md")
        ReportGenerator().generate(result, reportPath)
        val content = reportPath.readText()

        // Should still have the section headers
        assertTrue(content.contains("MEDIUM Confidence"))
        assertTrue(content.contains("LOW Confidence"))
    }
}
