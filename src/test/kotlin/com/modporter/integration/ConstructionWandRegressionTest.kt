package com.modporter.integration

import com.modporter.core.pipeline.*
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.resources.ResourceMigrationPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression test: runs the full forge2neo pipeline on the ConstructionWand mod
 * (Forge 1.20.1) and verifies that the conversion produces zero errors.
 *
 * This is a real-world mod with:
 * - Hybrid build system (buildscript + plugins block)
 * - Map-style Forge dependency
 * - Integration modules (JEI, Botania)
 * - Packet networking (encode/decode)
 * - Custom recipes
 * - Data generators
 * - Capability system usage
 */
class ConstructionWandRegressionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun copyFixture(): Path {
        val fixtureDir = Path.of("src/test/resources/fixtures/constructionwand-orig")
        if (!Files.exists(fixtureDir)) {
            println("Skipping: ConstructionWand fixture not found at $fixtureDir")
            return tempDir
        }
        val projectDir = tempDir.resolve("ConstructionWand")
        fixtureDir.toFile().copyRecursively(projectDir.toFile())
        return projectDir
    }

    @Test
    fun `pipeline produces zero errors on ConstructionWand`() {
        val projectDir = copyFixture()
        if (!Files.exists(projectDir.resolve("build.gradle"))) return

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = false
        )

        val result = pipeline.run(projectDir)

        assertEquals(0, result.totalErrors, "Pipeline should produce zero errors: ${result.passResults.flatMap { it.errors }}")
        assertTrue(result.totalChanges > 200, "Should make significant changes (got ${result.totalChanges})")
    }

    @Test
    fun `converted ConstructionWand has no Forge references in source`() {
        val projectDir = copyFixture()
        if (!Files.exists(projectDir.resolve("build.gradle"))) return

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = false
        )
        pipeline.run(projectDir)

        // Check Java source files for remaining Forge references
        val srcDir = projectDir.resolve("src/main/java")
        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val content = javaFile.toFile().readText()
                // Skip commented-out lines
                val activeLines = content.lines().filter { !it.trim().startsWith("//") }
                val activeContent = activeLines.joinToString("\n")
                assertFalse(activeContent.contains("net.minecraftforge"),
                    "File ${javaFile.fileName} still has net.minecraftforge references")
                assertFalse(activeContent.contains("MinecraftForge."),
                    "File ${javaFile.fileName} still has MinecraftForge references")
            }
    }

    @Test
    fun `converted build gradle has NeoForge structure`() {
        val projectDir = copyFixture()
        if (!Files.exists(projectDir.resolve("build.gradle"))) return

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = false
        )
        pipeline.run(projectDir)

        val buildGradle = projectDir.resolve("build.gradle").toFile().readText()
        assertTrue(buildGradle.contains("net.neoforged.moddev"), "Should have NeoForge ModDev plugin")
        assertTrue(buildGradle.contains("neoForge {"), "Should have neoForge {} block")
        assertFalse(buildGradle.contains("net.minecraftforge.gradle"), "Should not have ForgeGradle plugin")
        assertTrue(buildGradle.contains("sourceSets.main.java {"), "Should have source exclusions")
        assertTrue(buildGradle.contains("exclude"), "Should exclude integration modules")
    }

    @Test
    fun `converted mods toml references neoforge`() {
        val projectDir = copyFixture()
        if (!Files.exists(projectDir.resolve("build.gradle"))) return

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = false
        )
        pipeline.run(projectDir)

        val modsToml = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        assertTrue(Files.exists(modsToml), "neoforge.mods.toml should exist")
        val content = modsToml.toFile().readText()
        assertTrue(content.contains("neoforge"), "Should reference neoforge dependency")
    }

    @Test
    fun `pipeline is idempotent on ConstructionWand`() {
        val projectDir = copyFixture()
        if (!Files.exists(projectDir.resolve("build.gradle"))) return

        val db = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = false
        )

        // First run
        val result1 = pipeline.run(projectDir)
        assertTrue(result1.totalChanges > 0, "First run should make changes")

        // Capture state after first run
        val buildAfter1 = projectDir.resolve("build.gradle").toFile().readText()

        // Second run
        val result2 = pipeline.run(projectDir)
        val buildAfter2 = projectDir.resolve("build.gradle").toFile().readText()

        assertEquals(buildAfter1, buildAfter2, "build.gradle should not change on second run")
    }
}
