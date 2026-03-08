package com.modporter.integration

import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceMigrationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun setupResourceProject(): Path {
        val projectDir = tempDir.resolve("resmod")
        val resourceDir = projectDir.resolve("src/main/resources")

        // mods.toml
        resourceDir.resolve("META-INF").createDirectories()
        resourceDir.resolve("META-INF/mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="resmod"
            [[dependencies.resmod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        // Data folders with plural names
        val dataDir = resourceDir.resolve("data/resmod")
        dataDir.resolve("tags/blocks").createDirectories()
        dataDir.resolve("tags/items").createDirectories()
        dataDir.resolve("tags/entity_types").createDirectories()
        dataDir.resolve("recipes").createDirectories()
        dataDir.resolve("loot_tables/blocks").createDirectories()
        dataDir.resolve("advancements").createDirectories()

        // Sample files in those directories
        dataDir.resolve("tags/items/my_tag.json").writeText("""{"values":["resmod:item"]}""")
        dataDir.resolve("recipes/my_recipe.json").writeText("""{"type":"minecraft:crafting_shaped"}""")
        dataDir.resolve("loot_tables/blocks/my_block.json").writeText("""{"type":"minecraft:block"}""")
        dataDir.resolve("advancements/root.json").writeText("""{"criteria":{}}""")

        // pack.mcmeta
        resourceDir.resolve("pack.mcmeta").writeText("""{"pack":{"pack_format":15,"description":"Test"}}""")

        return projectDir
    }

    @Test
    fun `renames mods toml to neoforge mods toml`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val neoToml = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        val oldToml = projectDir.resolve("src/main/resources/META-INF/mods.toml")

        assertTrue(neoToml.exists(), "neoforge.mods.toml should exist")
        assertFalse(oldToml.exists(), "Old mods.toml should be removed")
    }

    @Test
    fun `updates mods toml content`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val content = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("neoforge"), "Should reference neoforge instead of forge")
    }

    @Test
    fun `renames data folders`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")

        // New singular folders should exist
        assertTrue(dataDir.resolve("tags/item").exists(), "tags/items -> tags/item")
        assertTrue(dataDir.resolve("tags/block").exists(), "tags/blocks -> tags/block")
        assertTrue(dataDir.resolve("recipe").exists(), "recipes -> recipe")
        assertTrue(dataDir.resolve("loot_table").exists(), "loot_tables -> loot_table")
        assertTrue(dataDir.resolve("advancement").exists(), "advancements -> advancement")
    }

    @Test
    fun `preserves files during folder rename`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")

        // Files should be preserved in renamed folders
        assertTrue(dataDir.resolve("tags/item/my_tag.json").exists(), "Tag file should be preserved")
        assertTrue(dataDir.resolve("recipe/my_recipe.json").exists(), "Recipe file should be preserved")
    }

    @Test
    fun `updates pack format`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val packMcmeta = projectDir.resolve("src/main/resources/pack.mcmeta").readText()
        assertTrue(packMcmeta.contains("\"pack_format\": 48") || packMcmeta.contains("\"pack_format\":48"),
            "Pack format should be updated to 48 (data pack format)")
        assertTrue(packMcmeta.contains("supported_formats"), "Should include supported_formats range")
    }

    @Test
    fun `dry run does not rename folders`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).analyze(projectDir)

        // Old folders should still exist
        val dataDir = projectDir.resolve("src/main/resources/data/resmod")
        assertTrue(dataDir.resolve("tags/items").exists(), "Dry run should not rename folders")
        assertTrue(dataDir.resolve("recipes").exists(), "Dry run should not rename folders")

        // But changes should be reported
        assertTrue(result.changeCount > 0, "Dry run should report changes")
    }

    @Test
    fun `handles project without resources dir`() {
        val projectDir = tempDir.resolve("nores")
        projectDir.createDirectories()
        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        assertEquals(0, result.changeCount, "No resources = no changes")
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `handles generated resources dir`() {
        val projectDir = tempDir.resolve("genmod")
        val genDir = projectDir.resolve("src/generated/resources/data/genmod")
        genDir.resolve("tags/items").createDirectories()
        genDir.resolve("tags/items/gen_tag.json").writeText("""{"values":[]}""")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        assertTrue(genDir.resolve("tags/item").exists(), "Should also rename in generated resources")
    }
}
