package com.modporter.integration

import com.modporter.core.pipeline.Confidence
import com.modporter.core.pipeline.Pipeline
import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration test that runs the full pipeline on a sample Forge mod project.
 */
class FullPipelineTest {

    @TempDir
    lateinit var tempDir: Path

    private fun setupSampleMod(): Path {
        val projectDir = tempDir.resolve("sample-mod")

        // Create source files
        val srcDir = projectDir.resolve("src/main/java/com/example/samplemod")
        srcDir.createDirectories()

        srcDir.resolve("SampleMod.java").writeText("""
            package com.example.samplemod;

            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.eventbus.api.IEventBus;
            import net.minecraftforge.eventbus.api.SubscribeEvent;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
            import net.minecraftforge.registries.DeferredRegister;
            import net.minecraftforge.registries.ForgeRegistries;
            import net.minecraftforge.registries.RegistryObject;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.Item;

            @Mod("samplemod")
            public class SampleMod {
                public static final String MOD_ID = "samplemod";

                public static final DeferredRegister<Item> ITEMS =
                    DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

                public static final RegistryObject<Item> MY_ITEM =
                    ITEMS.register("my_item", () -> new Item(new Item.Properties()));

                public SampleMod() {
                    IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
                    ITEMS.register(modBus);
                    MinecraftForge.EVENT_BUS.register(this);
                }

                @SubscribeEvent
                public void onServerStarting(net.minecraftforge.event.server.ServerStartingEvent event) {
                }

                public void test() {
                    ResourceLocation id = new ResourceLocation("samplemod", "test");
                    ResourceLocation parsed = new ResourceLocation("samplemod:test");
                }
            }
        """.trimIndent())

        srcDir.resolve("SampleCapProvider.java").writeText("""
            package com.example.samplemod;

            import net.minecraftforge.common.capabilities.ICapabilityProvider;
            import net.minecraftforge.common.capabilities.Capability;
            import net.minecraftforge.common.capabilities.ForgeCapabilities;
            import net.minecraftforge.common.util.LazyOptional;
            import net.minecraft.core.Direction;

            public class SampleCapProvider implements ICapabilityProvider {
                private final LazyOptional<Object> handler = LazyOptional.of(() -> new Object());

                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    if (cap == ForgeCapabilities.ITEM_HANDLER) {
                        return handler.cast();
                    }
                    return LazyOptional.empty();
                }
            }
        """.trimIndent())

        // Create resource directories
        val resourceDir = projectDir.resolve("src/main/resources")
        resourceDir.resolve("META-INF").createDirectories()
        resourceDir.resolve("META-INF/mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="samplemod"
            [[dependencies.samplemod]]
            modId="forge"
            mandatory=true
        """.trimIndent())

        // Create data directories with old plural names
        val dataDir = resourceDir.resolve("data/samplemod")
        dataDir.resolve("tags/blocks").createDirectories()
        dataDir.resolve("tags/items").createDirectories()
        dataDir.resolve("recipes").createDirectories()
        dataDir.resolve("loot_tables").createDirectories()

        // Add a sample tag file
        dataDir.resolve("tags/items/my_tag.json").writeText("""{"values":["samplemod:my_item"]}""")

        return projectDir
    }

    @Test
    fun `full pipeline transforms sample mod correctly`() {
        val projectDir = setupSampleMod()
        val db = MappingDatabase.loadDefault()

        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = false
        )

        val result = pipeline.run(projectDir)

        // Verify pipeline ran all passes
        assertTrue(result.passResults.size >= 3, "Should run at least 3 passes")
        assertTrue(result.totalChanges > 0, "Should have made changes")

        // Verify text replacements applied
        val mainFile = projectDir.resolve("src/main/java/com/example/samplemod/SampleMod.java").readText()
        assertTrue(mainFile.contains("net.neoforged.neoforge"), "Package should be renamed")
        assertTrue(mainFile.contains("NeoForge.EVENT_BUS"), "EVENT_BUS should be renamed")
        assertTrue(mainFile.contains("Registries.ITEM"), "DeferredRegister registry should use Registries.ITEM")
        assertFalse(mainFile.contains("net.minecraftforge"), "No old package references should remain")

        // Verify AST transforms
        assertTrue(mainFile.contains("ResourceLocation.fromNamespaceAndPath"), "RL constructor should be transformed")
        assertTrue(mainFile.contains("ResourceLocation.parse"), "RL single-arg should be transformed")

        // Verify structural detection
        val capFile = projectDir.resolve("src/main/java/com/example/samplemod/SampleCapProvider.java").readText()
        // Structural pass should detect but may or may not rewrite depending on confidence
        val structResult = result.passResults.find { it.passName == "Structural Refactor" }
        assertTrue(structResult != null && structResult.changeCount > 0,
            "Should detect capability patterns")

        // Verify resource migration
        val resourceDir = projectDir.resolve("src/main/resources")

        // mods.toml should be renamed
        val neoModsToml = resourceDir.resolve("META-INF/neoforge.mods.toml")
        assertTrue(neoModsToml.exists(), "mods.toml should be renamed to neoforge.mods.toml")
        val tomlContent = neoModsToml.readText()
        assertTrue(tomlContent.contains("neoforge"), "mods.toml should reference neoforge")
    }

    @Test
    fun `dry run pipeline reports changes without modifying`() {
        val projectDir = setupSampleMod()
        val originalContent = projectDir.resolve("src/main/java/com/example/samplemod/SampleMod.java").readText()
        val db = MappingDatabase.loadDefault()

        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass(),
                ResourceMigrationPass(db)
            ),
            dryRun = true
        )

        val result = pipeline.run(projectDir)

        assertTrue(result.totalChanges > 0, "Should report changes")
        assertTrue(result.dryRun, "Should be marked as dry run")

        // File should be unchanged
        val currentContent = projectDir.resolve("src/main/java/com/example/samplemod/SampleMod.java").readText()
        assertTrue(originalContent == currentContent, "Files should not be modified in dry run")
    }

    @Test
    fun `confidence filtering works`() {
        val projectDir = setupSampleMod()
        val db = MappingDatabase.loadDefault()

        val pipeline = Pipeline(
            passes = listOf(
                TextReplacementPass(db),
                AstTransformPass(db),
                StructuralRefactorPass()
            ),
            minConfidence = Confidence.HIGH,
            dryRun = true
        )

        val result = pipeline.run(projectDir)

        // All changes should be HIGH confidence only
        result.passResults.flatMap { it.changes }.forEach { change ->
            assertTrue(change.confidence == Confidence.HIGH,
                "All changes should be HIGH confidence when filtered, got ${change.confidence} for ${change.ruleId}")
        }
    }
}
