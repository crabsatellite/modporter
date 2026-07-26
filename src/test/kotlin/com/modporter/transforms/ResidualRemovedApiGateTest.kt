package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class ResidualRemovedApiGateTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact removed potion api residues are hard gated`() {
        val source = tempDir.resolve("src/main/java/com/example/LegacyPotionCalls.java")
        source.parent.createDirectories()
        source.writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.Potions;
            import net.minecraft.world.item.alchemy.PotionUtils;

            class LegacyPotionCalls {
                Object effects(CompoundTag tag) {
                    return PotionUtils.getAllEffects(tag);
                }

                Potion empty() {
                    return Potions.EMPTY;
                }

                Potion named(String id) {
                    return Potion.byName(id);
                }
            }
        """.trimIndent())

        val result = BuildSystemPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("PotionUtils") }, result.errors.toString())
        assertTrue(result.errors.any { it.contains("Potions.EMPTY") }, result.errors.toString())
        assertTrue(result.errors.any { it.contains("Potion.byName") }, result.errors.toString())
    }

    @Test
    fun `project lookalikes and documentation do not trigger potion residue gate`() {
        val source = tempDir.resolve("src/main/java/com/example/ProjectPotionCalls.java")
        source.parent.createDirectories()
        source.writeText("""
            package com.example;

            class ProjectPotionCalls {
                private static final String DOC =
                    "PotionUtils.getAllEffects(tag); Potions.EMPTY; Potion.byName(id)";

                static class PotionUtils {
                    static Object getAllEffects(Object tag) {
                        return tag;
                    }
                }

                static class Potions {
                    static final Object EMPTY = new Object();
                }

                static class Potion {
                    static Object byName(String id) {
                        return id;
                    }
                }

                Object values(Object tag) {
                    return PotionUtils.getAllEffects(tag) == Potions.EMPTY
                        ? Potion.byName("project:value")
                        : tag;
                }
            }
        """.trimIndent())

        val result = BuildSystemPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }
}
