package com.modporter.integration

import com.modporter.core.pipeline.*
import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.build.BuildSystemPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests verifying the converted mod is ready for runClient.
 * Goes beyond compilation — checks mods.toml content, resource structure,
 * build configuration for run tasks, and pack.mcmeta correctness.
 */
class RuntimeReadinessTest {

    @TempDir
    lateinit var tempDir: Path

    private fun runPipeline(projectDir: Path): PipelineResult {
        val mappingDb = MappingDatabase.loadDefault()
        return Pipeline(
            passes = listOf(
                TextReplacementPass(mappingDb),
                AstTransformPass(mappingDb),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(mappingDb)
            ),
            dryRun = false
        ).run(projectDir)
    }

    private fun createFullMod(): Path {
        val dir = tempDir.resolve("fullmod")

        // build.gradle
        dir.createDirectories()
        dir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
                id 'java'
            }
            java.toolchain.languageVersion = JavaLanguageVersion.of(17)
            minecraft {
                mappings channel: 'official', version: '1.20.1'
                runs {
                    client {
                        workingDirectory project.file('run')
                        property 'forge.logging.markers', 'REGISTRIES'
                        mods { testmod { source sourceSets.main } }
                    }
                    server {
                        workingDirectory project.file('run')
                        mods { testmod { source sourceSets.main } }
                    }
                    data {
                        workingDirectory project.file('run')
                        args '--mod', 'testmod', '--all'
                        mods { testmod { source sourceSets.main } }
                    }
                }
            }
            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        dir.resolve("settings.gradle").writeText("""
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    maven { url = 'https://maven.minecraftforge.net/' }
                }
            }
        """.trimIndent())

        dir.resolve("gradle.properties").writeText("""
            org.gradle.jvmargs=-Xmx3G
            minecraft_version=1.20.1
            forge_version=47.2.0
            mod_id=testmod
        """.trimIndent())

        // Java source
        val src = dir.resolve("src/main/java/com/example/testmod")
        src.createDirectories()
        src.resolve("TestMod.java").writeText("""
            package com.example.testmod;

            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;
            import net.minecraftforge.registries.RegistryObject;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.CreativeModeTabs;
            import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;

            @Mod("testmod")
            public class TestMod {
                public static final DeferredRegister<Item> ITEMS =
                    DeferredRegister.create(ForgeRegistries.ITEMS, "testmod");

                public static final RegistryObject<Item> TEST_ITEM =
                    ITEMS.register("test_item", () -> new Item(new Item.Properties()));

                public TestMod() {
                    var bus = FMLJavaModLoadingContext.get().getModEventBus();
                    ITEMS.register(bus);
                    bus.addListener(this::addCreative);
                }

                private void addCreative(BuildCreativeModeTabContentsEvent event) {
                    if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
                        event.accept(TEST_ITEM);
                    }
                }
            }
        """.trimIndent())

        // Complete mods.toml
        val metaInf = dir.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        metaInf.resolve("mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="testmod"
            version="1.0.0"
            displayName="Test Mod"
            displayTest="MATCH_VERSION"
            description='''A test mod for runtime verification'''

            [[dependencies.testmod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.testmod]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.21)"
            ordering="NONE"
            side="BOTH"
        """.trimIndent())

        // Data pack resources (plural folders)
        val data = dir.resolve("src/main/resources/data/testmod")
        data.resolve("tags/items").createDirectories()
        data.resolve("tags/blocks").createDirectories()
        data.resolve("recipes").createDirectories()
        data.resolve("loot_tables/blocks").createDirectories()
        data.resolve("tags/items/test_items.json").writeText("""{"values":["testmod:test_item"]}""")
        data.resolve("recipes/test_recipe.json").writeText("""{"type":"minecraft:crafting_shapeless","ingredients":[{"item":"minecraft:diamond"}],"result":{"item":"testmod:test_item","count":1}}""")
        data.resolve("loot_tables/blocks/test_block.json").writeText("""{"type":"minecraft:block","pools":[]}""")

        // Forge namespace data (GLM, biome modifiers, etc.)
        val forgeData = dir.resolve("src/main/resources/data/forge/loot_modifiers")
        forgeData.createDirectories()
        forgeData.resolve("global_loot_modifiers.json").writeText("""{"entries":["testmod:test_glm"],"conditions":[{"type":"forge:loot_table_id"}]}""")

        // Forge namespace tags (should go to c: namespace)
        val forgeTags = dir.resolve("src/main/resources/data/forge/tags/items")
        forgeTags.createDirectories()
        forgeTags.resolve("ingots.json").writeText("""{"replace":false,"values":["testmod:test_item"]}""")

        // Asset resources
        val assets = dir.resolve("src/main/resources/assets/testmod")
        assets.resolve("textures/item").createDirectories()
        assets.resolve("models/item").createDirectories()
        assets.resolve("lang").createDirectories()
        assets.resolve("lang/en_us.json").writeText("""{"item.testmod.test_item":"Test Item"}""")
        assets.resolve("models/item/test_item.json").writeText("""{"parent":"item/generated","textures":{"layer0":"testmod:item/test_item"}}""")

        // pack.mcmeta
        dir.resolve("src/main/resources/pack.mcmeta").writeText("""{"pack":{"pack_format":15,"description":"testmod resources"}}""")

        return dir
    }

    // ─── mods.toml Content Tests ──────────────────────────────────────

    @Test
    fun `neoforge mods toml has correct loaderVersion`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("""loaderVersion="[1,)""""), "loaderVersion should be [4,)")
    }

    @Test
    fun `neoforge mods toml has neoforge dependency`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("""modId="neoforge""""), "Should depend on neoforge not forge")
        assertFalse(content.contains("""modId="forge""""), "No forge dependency")
    }

    @Test
    fun `neoforge mods toml uses type instead of mandatory`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("""type="required""""), "mandatory=true → type=\"required\"")
        assertFalse(content.contains("mandatory"), "No mandatory field")
    }

    @Test
    fun `neoforge mods toml has correct version ranges`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("[21.1,)"), "NeoForge version range should be [21.1,)")
        assertTrue(content.contains("[1.21.1,1.22)"), "MC version range should be [1.21.1,1.22)")
    }

    @Test
    fun `neoforge mods toml removes displayTest`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertFalse(content.contains("displayTest"), "displayTest should be removed")
    }

    @Test
    fun `neoforge mods toml preserves modId and displayName`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("""modId="testmod""""), "modId preserved")
        assertTrue(content.contains("""displayName="Test Mod""""), "displayName preserved")
        assertTrue(content.contains("""license="MIT""""), "license preserved")
    }

    // ─── Data Pack Structure Tests ────────────────────────────────────

    @Test
    fun `data folders are depluralised`() {
        val dir = createFullMod()
        runPipeline(dir)
        val data = dir.resolve("src/main/resources/data/testmod")

        assertTrue(data.resolve("tags/item").exists(), "tags/items → tags/item")
        assertTrue(data.resolve("tags/block").exists(), "tags/blocks → tags/block")
        assertTrue(data.resolve("recipe").exists(), "recipes → recipe")
        assertTrue(data.resolve("loot_table").exists(), "loot_tables → loot_table")
    }

    @Test
    fun `data files preserved after folder rename`() {
        val dir = createFullMod()
        runPipeline(dir)
        val data = dir.resolve("src/main/resources/data/testmod")

        assertTrue(data.resolve("tags/item/test_items.json").exists())
        assertTrue(data.resolve("recipe/test_recipe.json").exists())
        assertTrue(data.resolve("loot_table/blocks/test_block.json").exists())
    }

    @Test
    fun `asset resources are untouched`() {
        val dir = createFullMod()
        runPipeline(dir)
        val assets = dir.resolve("src/main/resources/assets/testmod")

        assertTrue(assets.resolve("lang/en_us.json").exists(), "lang file preserved")
        assertTrue(assets.resolve("models/item/test_item.json").exists(), "model preserved")

        // Verify content unchanged
        val lang = assets.resolve("lang/en_us.json").readText()
        assertTrue(lang.contains("Test Item"), "lang content preserved")
    }

    // ─── Build Configuration Tests ────────────────────────────────────

    @Test
    fun `build gradle has runClient configuration`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("build.gradle").readText()

        assertTrue(content.contains("neoForge {"), "Has neoForge block")
        assertTrue(content.contains("client()"), "Has client run config")
        assertTrue(content.contains("server()"), "Has server run config")
        assertTrue(content.contains("data()"), "Has data run config")
    }

    @Test
    fun `build gradle has Java 21 toolchain`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("build.gradle").readText()

        assertTrue(content.contains("JavaLanguageVersion.of(21)"), "Java 21 toolchain")
        assertFalse(content.contains("JavaLanguageVersion.of(17)"), "No Java 17")
    }

    @Test
    fun `gradle properties has correct versions`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("gradle.properties").readText()

        assertTrue(content.contains("minecraft_version=1.21.1"), "MC 1.21.1")
        assertTrue(content.contains("neo_forge_version=21.1.219"), "NeoForge version")
    }

    @Test
    fun `pack mcmeta has correct format`() {
        val dir = createFullMod()
        runPipeline(dir)
        val content = dir.resolve("src/main/resources/pack.mcmeta").readText()

        assertTrue(content.contains("\"pack_format\": 48") || content.contains("\"pack_format\":48"),
            "pack_format should be 48 (data pack format for 1.21.1)")
        assertTrue(content.contains("supported_formats"),
            "Should include supported_formats range")
    }

    // ─── Full Pipeline Integration ────────────────────────────────────

    @Test
    fun `full mod conversion produces zero errors`() {
        val dir = createFullMod()
        val result = runPipeline(dir)

        assertEquals(0, result.totalErrors, "Should have zero errors: ${
            result.passResults.flatMap { it.errors }
        }")
    }

    @Test
    fun `all five passes run`() {
        val dir = createFullMod()
        val result = runPipeline(dir)

        val passNames = result.passResults.map { it.passName }
        assertTrue("Text Replacement" in passNames)
        assertTrue("Build System" in passNames)
        assertTrue("Resource Migration" in passNames)
    }

    @Test
    fun `no forge references remain in any file type`() {
        val dir = createFullMod()
        runPipeline(dir)

        // Check Java files
        java.nio.file.Files.walk(dir.resolve("src"))
            .filter { it.toString().endsWith(".java") }
            .forEach { file ->
                val content = file.toFile().readText()
                assertFalse(content.contains("net.minecraftforge"),
                    "Java file ${file.fileName} still has net.minecraftforge")
            }

        // Check build files
        val buildGradle = dir.resolve("build.gradle").readText()
        assertFalse(buildGradle.contains("net.minecraftforge"), "build.gradle has forge ref")

        // Check mods.toml
        assertFalse(dir.resolve("src/main/resources/META-INF/mods.toml").exists(),
            "Old mods.toml should not exist")
    }

    // ─── JSON Data File Transformation Tests ─────────────────────────

    @Test
    fun `recipe result item key renamed to id`() {
        val dir = createFullMod()
        runPipeline(dir)
        val recipe = dir.resolve("src/main/resources/data/testmod/recipe/test_recipe.json").readText()
        assertTrue(recipe.contains("\"id\""), "Recipe result should use \"id\" key")
        assertFalse(recipe.contains("\"item\":\"testmod:test_item\""), "Recipe result should not use \"item\" key for result")
    }

    @Test
    fun `data forge directory split into c and neoforge`() {
        val dir = createFullMod()
        runPipeline(dir)
        assertFalse(dir.resolve("src/main/resources/data/forge").exists(), "data/forge/ should not exist")
        assertTrue(dir.resolve("src/main/resources/data/neoforge").exists(), "data/neoforge/ should exist for non-tag data")
        assertTrue(dir.resolve("src/main/resources/data/c").exists(), "data/c/ should exist for common tags")
    }

    @Test
    fun `forge tags moved to c namespace`() {
        val dir = createFullMod()
        runPipeline(dir)
        // tags/items was depluralised to tags/item too
        assertTrue(dir.resolve("src/main/resources/data/c/tags/item/ingots.json").exists()
                || dir.resolve("src/main/resources/data/c/tags/items/ingots.json").exists(),
            "Forge tags should be moved to data/c/tags/")
    }

    @Test
    fun `forge namespace replaced in JSON data files`() {
        val dir = createFullMod()
        runPipeline(dir)
        val glm = dir.resolve("src/main/resources/data/neoforge/loot_modifiers/global_loot_modifiers.json").readText()
        assertFalse(glm.contains("\"forge:"), "No forge: namespace should remain")
        assertTrue(glm.contains("\"neoforge:"), "Should use neoforge: namespace")
    }

    @Test
    fun `forge conditions key migrated to neoforge conditions`() {
        val dir = createFullMod()
        runPipeline(dir)
        val glm = dir.resolve("src/main/resources/data/neoforge/loot_modifiers/global_loot_modifiers.json").readText()
        assertTrue(glm.contains("\"neoforge:conditions\"") || !glm.contains("\"conditions\""),
            "conditions key should be migrated to neoforge:conditions")
    }
}

