package com.modporter.integration

import com.modporter.core.pipeline.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineResultTest {

    @Test
    fun `summary contains all pass names`() {
        val result = PipelineResult(
            passResults = listOf(
                PassResult("Text Replacement", listOf(
                    Change(Path.of("a.java"), 1, "d", "b", "a", Confidence.HIGH, "r1"),
                    Change(Path.of("a.java"), 2, "d", "b", "a", Confidence.HIGH, "r2"),
                )),
                PassResult("AST Transform", listOf(
                    Change(Path.of("b.java"), 1, "d", "b", "a", Confidence.MEDIUM, "r3"),
                )),
                PassResult("Structural Refactor", emptyList(), errors = listOf("err1")),
            ),
            dryRun = false
        )

        val summary = result.summary()
        assertTrue(summary.contains("Text Replacement: 2 changes"))
        assertTrue(summary.contains("AST Transform: 1 changes"))
        assertTrue(summary.contains("Structural Refactor: 0 changes"))
        assertTrue(summary.contains("Total changes: 3"))
        assertTrue(summary.contains("Total errors: 1"))
        assertTrue(!summary.contains("DRY RUN"))
    }

    @Test
    fun `summary shows DRY RUN when applicable`() {
        val result = PipelineResult(passResults = emptyList(), dryRun = true)
        val summary = result.summary()
        assertTrue(summary.contains("DRY RUN"))
    }

    @Test
    fun `totalChanges sums across passes`() {
        val result = PipelineResult(
            passResults = listOf(
                PassResult("A", listOf(
                    Change(Path.of("x.java"), 1, "d", "b", "a", Confidence.HIGH, "r1"),
                )),
                PassResult("B", listOf(
                    Change(Path.of("y.java"), 1, "d", "b", "a", Confidence.LOW, "r2"),
                    Change(Path.of("z.java"), 1, "d", "b", "a", Confidence.MEDIUM, "r3"),
                )),
            ),
            dryRun = false
        )
        assertEquals(3, result.totalChanges)
        assertEquals(0, result.totalErrors)
    }

    @Test
    fun `totalErrors sums across passes`() {
        val result = PipelineResult(
            passResults = listOf(
                PassResult("A", emptyList(), errors = listOf("e1", "e2")),
                PassResult("B", emptyList(), errors = listOf("e3")),
            ),
            dryRun = false
        )
        assertEquals(0, result.totalChanges)
        assertEquals(3, result.totalErrors)
    }

    @Test
    fun `PassResult confidence counters work`() {
        val result = PassResult("Test", listOf(
            Change(Path.of("a.java"), 1, "d", "b", "a", Confidence.HIGH, "r1"),
            Change(Path.of("a.java"), 2, "d", "b", "a", Confidence.HIGH, "r2"),
            Change(Path.of("a.java"), 3, "d", "b", "a", Confidence.MEDIUM, "r3"),
            Change(Path.of("a.java"), 4, "d", "b", "a", Confidence.LOW, "r4"),
        ))

        assertEquals(4, result.changeCount)
        assertEquals(2, result.highConfidence)
        assertEquals(1, result.mediumConfidence)
        assertEquals(1, result.lowConfidence)
    }

    @Test
    fun `PassResult with skipped items`() {
        val result = PassResult("Test", emptyList(), skipped = listOf("skipped1.java", "skipped2.java"))
        assertEquals(0, result.changeCount)
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun `Pipeline skips non-applicable passes`() {
        val alwaysSkip = object : Pass {
            override val name = "Always Skip"
            override val order = 1
            override fun analyze(projectDir: Path) = PassResult(name, emptyList())
            override fun apply(projectDir: Path) = PassResult(name, emptyList())
            override fun isApplicable(projectDir: Path) = false
        }

        val pipeline = Pipeline(passes = listOf(alwaysSkip), dryRun = true)
        val result = pipeline.run(Path.of("."))

        assertEquals(0, result.passResults.size, "Non-applicable pass should be skipped")
    }

    @Test
    fun `Pipeline logs errors from passes`() {
        val errorPass = object : Pass {
            override val name = "Error Pass"
            override val order = 1
            override fun analyze(projectDir: Path) = PassResult(name, emptyList(), errors = listOf("test error"))
            override fun apply(projectDir: Path) = analyze(projectDir)
        }

        val pipeline = Pipeline(passes = listOf(errorPass), dryRun = true)
        val result = pipeline.run(Path.of("."))

        assertEquals(1, result.totalErrors)
        assertEquals("test error", result.passResults[0].errors[0])
    }
}
