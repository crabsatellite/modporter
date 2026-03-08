package com.modporter.transforms

import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextReplacementTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createTestFile(content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("TestMod.java")
        file.writeText(content)
        return tempDir
    }

    @Test
    fun `package renames are applied correctly`() {
        val projectDir = createTestFile("""
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.eventbus.api.IEventBus;
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("net.neoforged.neoforge.common.NeoForge"))
        assertTrue(transformed.contains("net.neoforged.fml.common.Mod"))
        assertTrue(transformed.contains("net.neoforged.bus.api.IEventBus"))
        assertTrue(!transformed.contains("net.minecraftforge"))

        assertTrue(result.changeCount > 0)
        assertTrue(result.changes.all { it.confidence == Confidence.HIGH })
    }

    @Test
    fun `class renames are applied correctly`() {
        val projectDir = createTestFile("""
            MinecraftForge.EVENT_BUS.register(this);
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            ForgeHooks.onLivingAttack(entity, source, amount);
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("NeoForge.EVENT_BUS"))
        assertTrue(transformed.contains("ModConfigSpec.Builder"))
        assertTrue(transformed.contains("CommonHooks.onLivingAttack"))
    }

    @Test
    fun `registry constants are replaced`() {
        val projectDir = createTestFile("""
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
            ForgeRegistries.ITEMS.getKey(item);
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        // DeferredRegister.create uses Registries (ResourceKey)
        assertTrue(transformed.contains("DeferredRegister.create(Registries.ITEM"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.BLOCK"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.ENTITY_TYPE"))
        // Non-DeferredRegister context uses BuiltInRegistries (Registry instance)
        assertTrue(transformed.contains("BuiltInRegistries.ITEM.getKey"))
    }

    @Test
    fun `IForgeXXX interface renames via text rules`() {
        val projectDir = createTestFile("""
            ForgeSpawnEggItem egg = new ForgeSpawnEggItem(entity, color1, color2, props);
            ForgeTier myTier = new ForgeTier(5, 2000, 10.0f, 4.0f, 20, tags, repairIngredient);
            ToolActions.DIG = new ToolAction();
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("DeferredSpawnEggItem"))
        assertTrue(transformed.contains("SimpleTier"))
        assertTrue(transformed.contains("ItemAbilities"))
    }

    @Test
    fun `dry run does not modify files`() {
        val original = """
            import net.minecraftforge.common.MinecraftForge;
        """.trimIndent()
        val projectDir = createTestFile(original)

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.analyze(projectDir)

        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        assertEquals(original, content, "Dry run should not modify files")
        assertTrue(result.changeCount > 0, "Dry run should still report changes")
    }
}
