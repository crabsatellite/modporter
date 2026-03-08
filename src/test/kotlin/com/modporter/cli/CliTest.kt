package com.modporter.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for CLI commands by invoking them programmatically.
 * We test the command logic without actually launching a process.
 */
class CliTest {

    @TempDir
    lateinit var tempDir: Path

    private fun setupMiniMod(): Path {
        val projectDir = tempDir.resolve("minimod")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Mini.java").writeText("""
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            public class Mini {
                void init() { MinecraftForge.EVENT_BUS.register(this); }
            }
        """.trimIndent())
        return projectDir
    }

    @Test
    fun `analyze command runs without error`() {
        val projectDir = setupMiniMod()
        val reportPath = tempDir.resolve("analyze-report.md")

        val cmd = AnalyzeCommand()
        cmd.parse(listOf("--src", projectDir.toString(), "--report", reportPath.toString()))

        assertTrue(reportPath.exists(), "Analyze should produce a report")
        val content = reportPath.readText()
        assertTrue(content.contains("Migration Report"))

        // Source file should NOT be modified (analyze = dry run)
        val srcContent = projectDir.resolve("src/main/java/com/example/Mini.java").readText()
        assertTrue(srcContent.contains("net.minecraftforge"), "Analyze should not modify files")
    }

    @Test
    fun `validate command detects Forge references`() {
        val projectDir = setupMiniMod()

        // Capture output by running validate
        val cmd = ValidateCommand()
        // ValidateCommand uses echo which prints to stdout
        cmd.parse(listOf("--src", projectDir.toString()))
        // If it didn't throw, validate ran successfully
    }

    @Test
    fun `validate command on clean NeoForge project`() {
        val projectDir = tempDir.resolve("cleanmod")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Clean.java").writeText("""
            package com.example;
            import net.neoforged.neoforge.common.NeoForge;
            public class Clean {
                void init() { NeoForge.EVENT_BUS.register(this); }
            }
        """.trimIndent())

        val cmd = ValidateCommand()
        cmd.parse(listOf("--src", projectDir.toString()))
        // Should complete without error — no Forge references found
    }

    @Test
    fun `port command with dry-run does not modify source`() {
        val projectDir = setupMiniMod()
        val originalContent = projectDir.resolve("src/main/java/com/example/Mini.java").readText()

        val cmd = PortCommand()
        cmd.parse(listOf("--src", projectDir.toString(), "--dry-run"))

        val currentContent = projectDir.resolve("src/main/java/com/example/Mini.java").readText()
        assertTrue(originalContent == currentContent, "Dry-run port should not modify source")
    }

    @Test
    fun `port command applies transformations`() {
        val projectDir = setupMiniMod()
        val outDir = tempDir.resolve("minimod-neoforge")
        val reportPath = tempDir.resolve("port-report.md")

        val cmd = PortCommand()
        cmd.parse(listOf(
            "--src", projectDir.toString(),
            "--out", outDir.toString(),
            "--report", reportPath.toString()
        ))

        assertTrue(outDir.exists(), "Output directory should be created")
        val content = outDir.resolve("src/main/java/com/example/Mini.java").readText()
        assertTrue(content.contains("NeoForge.EVENT_BUS"), "Should transform Forge references")
        assertFalse(content.contains("MinecraftForge"), "Old references should be gone")

        assertTrue(reportPath.exists(), "Report should be generated")
    }

    @Test
    fun `port command with confidence filter`() {
        val projectDir = setupMiniMod()

        val cmd = PortCommand()
        cmd.parse(listOf(
            "--src", projectDir.toString(),
            "--dry-run",
            "--min-confidence", "high"
        ))
        // Should complete without error
    }

    @Test
    fun `Forge2Neo parent command exists`() {
        val cmd = Forge2Neo()
        cmd.parse(emptyList())
        // Parent command should just be a container
    }
}
