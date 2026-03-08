package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildSystemTest {

    @TempDir
    lateinit var tempDir: Path

    private val pass = BuildSystemPass()

    @Test
    fun `replaces ForgeGradle plugin with NeoForge ModDev`() {
        val projectDir = tempDir.resolve("p1")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(content.contains("net.neoforged.moddev"))
        assertFalse(content.contains("net.minecraftforge.gradle"))
    }

    @Test
    fun `replaces Forge Maven repository URL`() {
        val projectDir = tempDir.resolve("p2")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(content.contains("maven.neoforged.net"))
        assertFalse(content.contains("maven.minecraftforge.net"))
    }

    @Test
    fun `replaces Forge dependency with comment`() {
        val projectDir = tempDir.resolve("p3")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertFalse(content.contains("net.minecraftforge:forge"))
        assertTrue(content.contains("neoForge"))
    }

    @Test
    fun `replaces minecraft block with neoForge block`() {
        val projectDir = tempDir.resolve("p4")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertFalse(content.contains("minecraft {"))
        assertTrue(content.contains("neoForge {"))
        assertTrue(content.contains("parchment"))
    }

    @Test
    fun `transforms settings gradle`() {
        val projectDir = tempDir.resolve("p5")
        projectDir.createDirectories()
        projectDir.resolve("settings.gradle").writeText("""
            pluginManagement {
                repositories {
                    maven { url = 'https://maven.minecraftforge.net/' }
                }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("settings.gradle").readText()
        assertTrue(content.contains("maven.neoforged.net"))
        assertFalse(content.contains("maven.minecraftforge.net"))
    }

    @Test
    fun `transforms gradle properties`() {
        val projectDir = tempDir.resolve("p6")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            forge_version=47.2.0
            mod_id=testmod
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()
        assertTrue(content.contains("minecraft_version=1.21.1"))
        assertTrue(content.contains("neo_forge_version=21.1.219"))
        assertFalse(Regex("""(?<!\w)forge_version\s*=""").containsMatchIn(content),
            "Should not have standalone forge_version property")
    }

    @Test
    fun `analyze mode does not modify files`() {
        val projectDir = tempDir.resolve("p7")
        projectDir.createDirectories()
        val originalContent = """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent()
        projectDir.resolve("build.gradle").writeText(originalContent)

        val result = pass.analyze(projectDir)
        assertTrue(result.changeCount > 0)

        // File should not be modified
        assertEquals(originalContent, projectDir.resolve("build.gradle").readText())
    }

    @Test
    fun `handles kotlin DSL build gradle kts`() {
        val projectDir = tempDir.resolve("p8")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins {
                id("net.minecraftforge.gradle") version "[6.0,6.2)"
            }
            dependencies {
                minecraft("net.minecraftforge:forge:1.20.1-47.2.0")
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle.kts").readText()
        assertTrue(content.contains("net.neoforged.moddev"))
        assertFalse(content.contains("net.minecraftforge"))
    }

    @Test
    fun `handles project with no build files`() {
        val projectDir = tempDir.resolve("p9")
        projectDir.createDirectories()

        val result = pass.apply(projectDir)
        assertEquals(0, result.changeCount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `all changes are HIGH confidence`() {
        val projectDir = tempDir.resolve("p10")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        val result = pass.analyze(projectDir)
        assertTrue(result.changeCount > 0)
        assertTrue(result.changes.all {
            it.confidence == com.modporter.core.pipeline.Confidence.HIGH
        }, "All build system changes should be HIGH confidence")
    }
}
