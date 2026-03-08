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
 * Build verification tests using synthetic Forge 1.20.1 mod fixtures.
 * Each test creates a minimal mod exercising one API pattern, runs the full pipeline,
 * and verifies the output has correct NeoForge references.
 */
class BuildVerificationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun runPipeline(projectDir: Path): PipelineResult {
        val mappingDb = MappingDatabase.loadDefault()
        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(mappingDb),
                AstTransformPass(mappingDb),
                StructuralRefactorPass(),
                BuildSystemPass(),
                ResourceMigrationPass(mappingDb)
            ),
            dryRun = false
        )
        return pipeline.run(projectDir)
    }

    private fun assertNoForgeReferences(projectDir: Path, fileExtension: String = ".java") {
        val forgePatterns = listOf(
            "net.minecraftforge",
            "MinecraftForge.EVENT_BUS",
            "ForgeRegistries.",
        )
        java.nio.file.Files.walk(projectDir)
            .filter { it.toString().endsWith(fileExtension) }
            .forEach { file ->
                val content = file.toFile().readText()
                for (pattern in forgePatterns) {
                    assertFalse(content.contains(pattern),
                        "Found remaining Forge reference '$pattern' in $file")
                }
            }
    }

    // ─── Fixture: Basic Item/Block Registration Mod ───────────────────────

    private fun createBasicItemMod(): Path {
        val projectDir = tempDir.resolve("basic-item-mod")

        // build.gradle
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
                id 'java'
            }

            minecraft {
                mappings channel: 'official', version: '1.20.1'
                runs {
                    client { workingDirectory project.file('run') }
                    server { workingDirectory project.file('run') }
                }
            }

            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }

            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        // gradle.properties
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            forge_version=47.2.0
            mod_id=basicmod
        """.trimIndent())

        // settings.gradle
        projectDir.resolve("settings.gradle").writeText("""
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    maven { url = 'https://maven.minecraftforge.net/' }
                }
            }
        """.trimIndent())

        // Main mod class
        val srcDir = projectDir.resolve("src/main/java/com/example/basicmod")
        srcDir.createDirectories()
        srcDir.resolve("BasicMod.java").writeText("""
            package com.example.basicmod;

            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;
            import net.minecraftforge.registries.RegistryObject;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;

            @Mod("basicmod")
            public class BasicMod {
                public static final DeferredRegister<Item> ITEMS =
                    DeferredRegister.create(ForgeRegistries.ITEMS, "basicmod");
                public static final DeferredRegister<Block> BLOCKS =
                    DeferredRegister.create(ForgeRegistries.BLOCKS, "basicmod");

                public static final RegistryObject<Item> MY_ITEM =
                    ITEMS.register("my_item", () -> new Item(new Item.Properties()));

                public BasicMod() {
                    var bus = FMLJavaModLoadingContext.get().getModEventBus();
                    ITEMS.register(bus);
                    BLOCKS.register(bus);
                }
            }
        """.trimIndent())

        // Resources
        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        metaInf.resolve("mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="basicmod"
            version="1.0.0"
            [[dependencies.basicmod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        return projectDir
    }

    @Test
    fun `basic item mod - source code transforms correctly`() {
        val projectDir = createBasicItemMod()
        val result = runPipeline(projectDir)

        assertTrue(result.totalChanges > 0, "Should have changes")

        // Check Java source
        val modFile = projectDir.resolve("src/main/java/com/example/basicmod/BasicMod.java")
        val content = modFile.readText()
        assertTrue(content.contains("net.neoforged"), "Should have NeoForge package")
        assertFalse(content.contains("net.minecraftforge"), "Should not have Forge package")
        assertTrue(content.contains("Registries.ITEM"), "ForgeRegistries.ITEMS -> Registries.ITEM in DeferredRegister.create")
        assertTrue(content.contains("Registries.BLOCK"), "ForgeRegistries.BLOCKS -> Registries.BLOCK in DeferredRegister.create")
    }

    @Test
    fun `basic item mod - build gradle transforms correctly`() {
        val projectDir = createBasicItemMod()
        runPipeline(projectDir)

        val buildGradle = projectDir.resolve("build.gradle").readText()
        assertTrue(buildGradle.contains("net.neoforged.moddev"), "Should use NeoForge plugin")
        assertFalse(buildGradle.contains("net.minecraftforge.gradle"), "Should not have ForgeGradle")
        assertFalse(buildGradle.contains("maven.minecraftforge.net"), "Should not have Forge Maven URL")
    }

    @Test
    fun `basic item mod - settings gradle transforms correctly`() {
        val projectDir = createBasicItemMod()
        runPipeline(projectDir)

        val settings = projectDir.resolve("settings.gradle").readText()
        assertFalse(settings.contains("maven.minecraftforge.net"), "Should not have Forge Maven URL")
        assertTrue(settings.contains("maven.neoforged.net"), "Should have NeoForge Maven URL")
    }

    @Test
    fun `basic item mod - gradle properties transforms correctly`() {
        val projectDir = createBasicItemMod()
        runPipeline(projectDir)

        val props = projectDir.resolve("gradle.properties").readText()
        assertTrue(props.contains("minecraft_version=1.21.1"), "MC version should be 1.21.1")
        assertFalse(Regex("""(?<!\w)forge_version\s*=""").containsMatchIn(props),
            "Should not have standalone forge_version property")
    }

    @Test
    fun `basic item mod - resources transform correctly`() {
        val projectDir = createBasicItemMod()
        runPipeline(projectDir)

        val neoToml = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        assertTrue(neoToml.exists(), "neoforge.mods.toml should exist")

        val oldToml = projectDir.resolve("src/main/resources/META-INF/mods.toml")
        assertFalse(oldToml.exists(), "Old mods.toml should be gone")

        val content = neoToml.readText()
        assertTrue(content.contains("neoforge"), "Should reference neoforge")
    }

    // ─── Fixture: Event Subscriber Mod ────────────────────────────────────

    private fun createEventMod(): Path {
        val projectDir = tempDir.resolve("event-mod")
        projectDir.createDirectories()

        // Minimal build.gradle
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        val srcDir = projectDir.resolve("src/main/java/com/example/eventmod")
        srcDir.createDirectories()

        srcDir.resolve("EventHandler.java").writeText("""
            package com.example.eventmod;

            import net.minecraftforge.event.entity.living.LivingHurtEvent;
            import net.minecraftforge.event.entity.player.PlayerEvent;
            import net.minecraftforge.eventbus.api.SubscribeEvent;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.event.server.ServerStartingEvent;

            @Mod.EventBusSubscriber(modid = "eventmod")
            public class EventHandler {
                @SubscribeEvent
                public static void onLivingHurt(LivingHurtEvent event) {
                    MinecraftForge.EVENT_BUS.post(event);
                }

                @SubscribeEvent
                public static void onServerStarting(ServerStartingEvent event) {
                    System.out.println("Server starting!");
                }

                @SubscribeEvent
                public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
                    System.out.println("Player logged in!");
                }
            }
        """.trimIndent())

        return projectDir
    }

    @Test
    fun `event mod - transforms event classes and packages`() {
        val projectDir = createEventMod()
        val result = runPipeline(projectDir)

        assertTrue(result.totalChanges > 0)

        val content = projectDir.resolve(
            "src/main/java/com/example/eventmod/EventHandler.java").readText()
        assertFalse(content.contains("net.minecraftforge"), "No Forge packages remaining")
        assertTrue(content.contains("net.neoforged"), "Should have NeoForge packages")
        assertTrue(content.contains("NeoForge.EVENT_BUS") || !content.contains("MinecraftForge"),
            "MinecraftForge should be replaced")
    }

    // ─── Fixture: Config Mod ──────────────────────────────────────────────

    private fun createConfigMod(): Path {
        val projectDir = tempDir.resolve("config-mod")
        projectDir.createDirectories()

        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        val srcDir = projectDir.resolve("src/main/java/com/example/configmod")
        srcDir.createDirectories()

        srcDir.resolve("ModConfig.java").writeText("""
            package com.example.configmod;

            import net.minecraftforge.common.ForgeConfigSpec;

            public class ModConfig {
                public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
                public static final ForgeConfigSpec.IntValue MAX_ITEMS;
                public static final ForgeConfigSpec.BooleanValue ENABLE_FEATURE;
                public static final ForgeConfigSpec SPEC;

                static {
                    BUILDER.push("general");
                    MAX_ITEMS = BUILDER.defineInRange("maxItems", 64, 1, 1000);
                    ENABLE_FEATURE = BUILDER.define("enableFeature", true);
                    BUILDER.pop();
                    SPEC = BUILDER.build();
                }
            }
        """.trimIndent())

        return projectDir
    }

    @Test
    fun `config mod - transforms ForgeConfigSpec to ModConfigSpec`() {
        val projectDir = createConfigMod()
        runPipeline(projectDir)

        val content = projectDir.resolve(
            "src/main/java/com/example/configmod/ModConfig.java").readText()
        assertTrue(content.contains("ModConfigSpec"), "ForgeConfigSpec -> ModConfigSpec")
        assertFalse(content.contains("ForgeConfigSpec"), "No ForgeConfigSpec remaining")
    }

    // ─── Fixture: Resource/Data Pack Mod ──────────────────────────────────

    private fun createResourceMod(): Path {
        val projectDir = tempDir.resolve("resource-mod")
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

        // Data folders (plural)
        val dataDir = resourceDir.resolve("data/resmod")
        dataDir.resolve("tags/items").createDirectories()
        dataDir.resolve("tags/blocks").createDirectories()
        dataDir.resolve("recipes").createDirectories()
        dataDir.resolve("loot_tables/blocks").createDirectories()
        dataDir.resolve("advancements").createDirectories()

        // Sample files
        dataDir.resolve("tags/items/my_tag.json").writeText("""{"values":["resmod:item1"]}""")
        dataDir.resolve("recipes/my_recipe.json").writeText("""{"type":"minecraft:crafting_shaped"}""")
        dataDir.resolve("loot_tables/blocks/my_block.json").writeText("""{"type":"minecraft:block"}""")
        dataDir.resolve("advancements/root.json").writeText("""{"criteria":{}}""")

        // pack.mcmeta
        resourceDir.resolve("pack.mcmeta").writeText("""{"pack":{"pack_format":15,"description":"Test"}}""")

        return projectDir
    }

    @Test
    fun `resource mod - renames all data folders to singular`() {
        val projectDir = createResourceMod()
        runPipeline(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")
        assertTrue(dataDir.resolve("tags/item").exists(), "tags/items -> tags/item")
        assertTrue(dataDir.resolve("tags/block").exists(), "tags/blocks -> tags/block")
        assertTrue(dataDir.resolve("recipe").exists(), "recipes -> recipe")
        assertTrue(dataDir.resolve("loot_table").exists(), "loot_tables -> loot_table")
        assertTrue(dataDir.resolve("advancement").exists(), "advancements -> advancement")
    }

    @Test
    fun `resource mod - preserves files in renamed folders`() {
        val projectDir = createResourceMod()
        runPipeline(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")
        assertTrue(dataDir.resolve("tags/item/my_tag.json").exists(), "Tag file preserved")
        assertTrue(dataDir.resolve("recipe/my_recipe.json").exists(), "Recipe file preserved")
        assertTrue(dataDir.resolve("loot_table/blocks/my_block.json").exists(), "Loot table preserved")
        assertTrue(dataDir.resolve("advancement/root.json").exists(), "Advancement preserved")
    }

    @Test
    fun `resource mod - updates pack format`() {
        val projectDir = createResourceMod()
        runPipeline(projectDir)

        val packMcmeta = projectDir.resolve("src/main/resources/pack.mcmeta").readText()
        assertTrue(packMcmeta.contains("\"pack_format\": 48") || packMcmeta.contains("\"pack_format\":48"),
            "Pack format should be 48 (data pack format)")
        assertTrue(packMcmeta.contains("supported_formats"), "Should include supported_formats range")
    }

    @Test
    fun `resource mod - mods toml renamed and updated`() {
        val projectDir = createResourceMod()
        runPipeline(projectDir)

        val neoToml = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        assertTrue(neoToml.exists(), "neoforge.mods.toml should exist")
        assertFalse(projectDir.resolve("src/main/resources/META-INF/mods.toml").exists(),
            "Old mods.toml should be removed")

        val content = neoToml.readText()
        assertTrue(content.contains("neoforge"), "Should reference neoforge not forge")
    }

    // ─── Fixture: Capability Mod (Complex Pattern) ────────────────────────

    private fun createCapabilityMod(): Path {
        val projectDir = tempDir.resolve("capability-mod")
        projectDir.createDirectories()

        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        val srcDir = projectDir.resolve("src/main/java/com/example/capmod")
        srcDir.createDirectories()

        srcDir.resolve("EnergyProvider.java").writeText("""
            package com.example.capmod;

            import net.minecraftforge.common.capabilities.Capability;
            import net.minecraftforge.common.capabilities.ICapabilityProvider;
            import net.minecraftforge.common.capabilities.ForgeCapabilities;
            import net.minecraftforge.common.util.LazyOptional;
            import net.minecraft.core.Direction;

            public class EnergyProvider implements ICapabilityProvider {
                private final LazyOptional<IEnergyStorage> energy = LazyOptional.of(() -> new EnergyImpl());

                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    if (cap == ForgeCapabilities.ENERGY) {
                        return energy.cast();
                    }
                    return LazyOptional.empty();
                }

                public void invalidate() {
                    energy.invalidate();
                }
            }
        """.trimIndent())

        return projectDir
    }

    @Test
    fun `capability mod - detects structural changes needed`() {
        val projectDir = createCapabilityMod()
        val result = runPipeline(projectDir)

        // Capabilities require structural refactoring - should detect them
        val structuralChanges = result.passResults
            .filter { it.passName == "Structural Refactor" }
            .flatMap { it.changes }

        assertTrue(structuralChanges.isNotEmpty(),
            "Should detect capability-related structural changes")

        // Check that package renames still happened
        val content = projectDir.resolve(
            "src/main/java/com/example/capmod/EnergyProvider.java").readText()
        assertTrue(content.contains("net.neoforged") || !content.contains("net.minecraftforge.common.capabilities"),
            "Package renames should be applied")
    }

    // ─── Fixture: Networking Mod ──────────────────────────────────────────

    private fun createNetworkMod(): Path {
        val projectDir = tempDir.resolve("network-mod")
        projectDir.createDirectories()

        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        val srcDir = projectDir.resolve("src/main/java/com/example/netmod")
        srcDir.createDirectories()

        srcDir.resolve("NetworkHandler.java").writeText("""
            package com.example.netmod;

            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.simple.SimpleChannel;
            import net.minecraftforge.network.PacketDistributor;
            import net.minecraft.resources.ResourceLocation;

            public class NetworkHandler {
                private static final String PROTOCOL_VERSION = "1";
                public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("netmod", "main"),
                    () -> PROTOCOL_VERSION,
                    PROTOCOL_VERSION::equals,
                    PROTOCOL_VERSION::equals
                );

                public static void register() {
                    int id = 0;
                    CHANNEL.registerMessage(id++, MyPacket.class, MyPacket::encode, MyPacket::decode, MyPacket::handle);
                }

                public static void sendToServer(Object msg) {
                    CHANNEL.sendToServer(msg);
                }
            }
        """.trimIndent())

        return projectDir
    }

    @Test
    fun `network mod - detects networking changes needed`() {
        val projectDir = createNetworkMod()
        val result = runPipeline(projectDir)

        // Should detect networking structural changes
        val allChanges = result.passResults.flatMap { it.changes }
        assertTrue(allChanges.any { it.ruleId.contains("network") || it.ruleId.contains("struct") },
            "Should detect networking-related changes")

        // Package renames should still happen
        val content = projectDir.resolve(
            "src/main/java/com/example/netmod/NetworkHandler.java").readText()
        assertFalse(content.contains("net.minecraftforge.network"),
            "Forge network package should be renamed")
    }

    // ─── Full Pipeline Integration ────────────────────────────────────────

    @Test
    fun `full pipeline - all passes run without errors on basic mod`() {
        val projectDir = createBasicItemMod()
        val result = runPipeline(projectDir)

        assertEquals(0, result.totalErrors, "No errors expected: ${
            result.passResults.flatMap { it.errors }
        }")

        // Verify all 5 passes ran
        val passNames = result.passResults.map { it.passName }
        assertTrue(passNames.contains("Text Replacement"), "Text Replacement pass should run")
        assertTrue(passNames.contains("Build System"), "Build System pass should run")
        assertTrue(passNames.contains("Resource Migration"), "Resource Migration pass should run")
    }

    @Test
    fun `full pipeline - no remaining forge references after conversion`() {
        val projectDir = createBasicItemMod()
        runPipeline(projectDir)
        assertNoForgeReferences(projectDir)
    }

    @Test
    fun `full pipeline - idempotent on already-converted mod`() {
        val projectDir = createBasicItemMod()

        // First conversion
        val result1 = runPipeline(projectDir)
        val content1 = projectDir.resolve(
            "src/main/java/com/example/basicmod/BasicMod.java").readText()

        // Second conversion (should be no-op or minimal)
        val result2 = runPipeline(projectDir)
        val content2 = projectDir.resolve(
            "src/main/java/com/example/basicmod/BasicMod.java").readText()

        assertEquals(content1, content2, "Second run should not change the source")
    }

    // ─── Build Output Validation ──────────────────────────────────────────

    @Test
    fun `converted build gradle has valid neoforge structure`() {
        val projectDir = createBasicItemMod()
        runPipeline(projectDir)

        val buildGradle = projectDir.resolve("build.gradle").readText()

        // Must have NeoForge plugin
        assertTrue(buildGradle.contains("net.neoforged.moddev"),
            "Must declare NeoForge ModDev plugin")

        // Must NOT have Forge artifacts
        assertFalse(buildGradle.contains("net.minecraftforge:forge"),
            "Must not reference Forge dependency")
        assertFalse(buildGradle.contains("net.minecraftforge.gradle"),
            "Must not reference ForgeGradle plugin")

        // Must have NeoForge configuration
        assertTrue(buildGradle.contains("neoForge") || buildGradle.contains("neoforge"),
            "Must have neoForge configuration block")
    }

    @Test
    fun `converted settings gradle references neoforge repos`() {
        val projectDir = createBasicItemMod()
        runPipeline(projectDir)

        val settings = projectDir.resolve("settings.gradle").readText()
        assertTrue(settings.contains("maven.neoforged.net"),
            "Settings must reference NeoForge Maven")
        assertFalse(settings.contains("maven.minecraftforge.net"),
            "Settings must not reference Forge Maven")
    }

    // ─── Error Classification Helper ──────────────────────────────────────

    @Test
    fun `error classification - build system errors detected`() {
        // Create a mod with ONLY a build.gradle (no source code)
        val projectDir = tempDir.resolve("build-only")
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

        val result = runPipeline(projectDir)
        val buildChanges = result.passResults
            .filter { it.passName == "Build System" }
            .flatMap { it.changes }

        assertTrue(buildChanges.any { it.ruleId.startsWith("build-") },
            "Should detect build system changes")
    }

    @Test
    fun `empty project produces no errors`() {
        val projectDir = tempDir.resolve("empty")
        projectDir.createDirectories()
        val result = runPipeline(projectDir)
        assertEquals(0, result.totalErrors)
        assertEquals(0, result.totalChanges)
    }
}
