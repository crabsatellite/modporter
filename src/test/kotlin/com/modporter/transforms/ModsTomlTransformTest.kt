package com.modporter.transforms

import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for mods.toml → neoforge.mods.toml content transformation.
 * Verifies all field changes required for NeoForge 1.21.1 compatibility.
 */
class ModsTomlTransformTest {

    @TempDir
    lateinit var tempDir: Path

    private val pass = ResourceMigrationPass(MappingDatabase.loadDefault())

    private fun transformToml(input: String): String {
        val file = tempDir.resolve("test.toml")
        file.writeText(input)
        pass.transformModsToml(file)
        return file.readText()
    }

    @Test
    fun `updates loaderVersion from Forge range to NeoForge`() {
        val result = transformToml("""
            modLoader="javafml"
            loaderVersion="[47,)"
        """.trimIndent())

        assertTrue(result.contains("""loaderVersion="[1,)""""), "loaderVersion should be [1,)")
        assertFalse(result.contains("[47,)"), "Old Forge version range should be gone")
    }

    @Test
    fun `updates loaderVersion with different Forge versions`() {
        // Some mods might use different Forge version ranges
        val result = transformToml("""loaderVersion="[43,)".""".trimIndent())
        assertTrue(result.contains("[1,)"), "Should handle other Forge version ranges")
    }

    @Test
    fun `replaces modId forge with neoforge in dependencies`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        assertTrue(result.contains("""modId="neoforge""""), "forge → neoforge")
        assertFalse(result.contains("""modId="forge""""), "No forge modId remaining")
    }

    @Test
    fun `replaces modId forge with spaces around equals`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId = "forge"
        """.trimIndent())

        assertTrue(result.contains("""modId="neoforge""""), "Should handle spaces around =")
        assertFalse(result.contains(""""forge""""), "No forge reference remaining")
    }

    @Test
    fun `replaces mandatory true with type required`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="neoforge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        assertTrue(result.contains("""type="required""""), "mandatory=true → type=\"required\"")
        assertFalse(result.contains("mandatory=true"), "No mandatory=true remaining")
    }

    @Test
    fun `replaces mandatory false with type optional`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="jei"
            mandatory=false
        """.trimIndent())

        assertTrue(result.contains("""type="optional""""), "mandatory=false → type=\"optional\"")
        assertFalse(result.contains("mandatory=false"), "No mandatory=false remaining")
    }

    @Test
    fun `updates neoforge dependency versionRange`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="neoforge"
            type="required"
            versionRange="[47,)"
        """.trimIndent())

        assertTrue(result.contains("[21.1,)"), "NeoForge versionRange should be [21.1,)")
    }

    @Test
    fun `updates minecraft dependency versionRange`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="minecraft"
            type="required"
            versionRange="[1.20.1,1.21)"
        """.trimIndent())

        assertTrue(result.contains("[1.21.1,1.22)"), "MC versionRange should be [1.21.1,1.22)")
        assertFalse(result.contains("[1.20.1"), "Old MC version range should be gone")
    }

    @Test
    fun `removes displayTest field`() {
        val result = transformToml("""
            [[mods]]
            modId="mymod"
            displayTest="MATCH_VERSION"
            version="1.0"
        """.trimIndent())

        assertFalse(result.contains("displayTest"), "displayTest should be removed")
        assertTrue(result.contains("modId=\"mymod\""), "Other fields preserved")
    }

    @Test
    fun `removes clientSideOnly field`() {
        val result = transformToml("""
            modLoader="javafml"
            clientSideOnly=true
            [[mods]]
            modId="mymod"
        """.trimIndent())

        assertFalse(result.contains("clientSideOnly"), "clientSideOnly should be removed")
    }

    @Test
    fun `preserves unrelated fields`() {
        val result = transformToml("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="mymod"
            version="1.0.0"
            displayName="My Mod"
            description="A test mod"
            authors="TestAuthor"
        """.trimIndent())

        assertTrue(result.contains("""license="MIT""""), "license preserved")
        assertTrue(result.contains("""modId="mymod""""), "modId preserved")
        assertTrue(result.contains("""version="1.0.0""""), "version preserved")
        assertTrue(result.contains("""displayName="My Mod""""), "displayName preserved")
        assertTrue(result.contains("""authors="TestAuthor""""), "authors preserved")
    }

    @Test
    fun `handles complete realistic mods toml`() {
        val result = transformToml("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="examplemod"
            version="1.0.0"
            displayName="Example Mod"
            displayTest="MATCH_VERSION"
            description='''An example mod'''

            [[dependencies.examplemod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.examplemod]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.21)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.examplemod]]
            modId="jei"
            mandatory=false
            versionRange="[15.2,)"
            ordering="AFTER"
            side="CLIENT"
        """.trimIndent())

        // Verify all transformations
        assertTrue(result.contains("""loaderVersion="[1,)""""), "loaderVersion updated")
        assertFalse(result.contains("displayTest"), "displayTest removed")
        assertTrue(result.contains("""modId="neoforge""""), "forge → neoforge")
        assertTrue(result.contains("[21.1,)"), "NeoForge version range")
        assertTrue(result.contains("[1.21.1,1.22)"), "MC version range")
        assertTrue(result.contains("""type="required""""), "mandatory=true → type=required")
        assertTrue(result.contains("""type="optional""""), "mandatory=false → type=optional")
        assertFalse(result.contains("mandatory"), "No mandatory field remaining")

        // Preserve unrelated fields
        assertTrue(result.contains("""license="MIT""""), "license preserved")
        assertTrue(result.contains("""ordering="AFTER""""), "ordering preserved")
        assertTrue(result.contains("""side="CLIENT""""), "side preserved")
        assertTrue(result.contains("""modId="jei""""), "JEI dep preserved")
    }

    @Test
    fun `does not modify non-forge dependency modId`() {
        val result = transformToml("""
            [[dependencies.mymod]]
            modId="jei"
            mandatory=false
        """.trimIndent())

        assertTrue(result.contains("""modId="jei""""), "JEI modId should not change")
    }

    @Test
    fun `cleans up double blank lines from removals`() {
        val result = transformToml("""
            modLoader="javafml"
            clientSideOnly=true
            loaderVersion="[47,)"

            [[mods]]
            modId="mymod"
            displayTest="MATCH_VERSION"
            version="1.0"
        """.trimIndent())

        assertFalse(result.contains("\n\n\n"), "Should not have triple+ blank lines")
    }
}
