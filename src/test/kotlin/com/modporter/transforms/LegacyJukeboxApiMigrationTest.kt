package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyJukeboxApiMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `record checks and typed jukebox inventory calls migrate without name guesses`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("JukeboxAccess.java")
        file.writeText("""
            package example;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.RecordItem;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

            class JukeboxAccess {
                ItemStack insert(BlockEntity blockEntity, ItemStack stack, boolean simulate) {
                    if (!(stack.getItem() instanceof RecordItem)) return stack;
                    if (!(blockEntity instanceof JukeboxBlockEntity jukebox)) return stack;
                    if (!jukebox.getFirstItem().isEmpty()) return stack;
                    ItemStack inserted = stack.copy();
                    if (!simulate) jukebox.setItem(0, inserted);
                    return stack;
                }
                void shadowed(Entity jukebox) {
                    jukebox.getFirstItem();
                    jukebox.setItem(0, null);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("stack.get(DataComponents.JUKEBOX_PLAYABLE) == null"), migrated)
        assertTrue(migrated.contains("jukebox.getTheItem()"), migrated)
        assertTrue(migrated.contains("jukebox.setTheItem(inserted)"), migrated)
        assertTrue(migrated.contains("void shadowed(Entity jukebox)"), migrated)
        assertTrue(migrated.contains("jukebox.getFirstItem()"), migrated)
        assertTrue(migrated.contains("jukebox.setItem(0, null)"), migrated)
        assertFalse(migrated.contains("import net.minecraft.world.item.RecordItem;"), migrated)
    }

    @Test
    fun `record item pattern variables hard fail instead of leaving dangling uses`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("RecordPattern.java")
        file.writeText("""
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.RecordItem;
            class RecordPattern {
                int length(ItemStack stack) {
                    if (stack.getItem() instanceof RecordItem record) return record.getAnalogOutput();
                    return 0;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("RecordItem pattern variable") }, result.errors.joinToString("\n"))
        assertTrue(file.readText().contains("instanceof RecordItem record"), file.readText())
    }
}
