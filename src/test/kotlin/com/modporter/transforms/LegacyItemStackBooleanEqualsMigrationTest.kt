package com.modporter.transforms

import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyItemStackBooleanEqualsMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `literal comparison modes preserve exact ItemStack semantics`() {
        val file = write("sample/Compare.java", """
            package sample;
            import net.minecraft.world.item.ItemStack;
            class Compare {
                boolean full(ItemStack left, ItemStack right) { return left.equals(right, false); }
                boolean itemAndCount(ItemStack left, ItemStack right) { return left.equals(right, true); }
                boolean differentItemOrCount(ItemStack left, ItemStack right) { return !left.equals(right, true); }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("ItemStack.matches(left, right)"), migrated)
        assertTrue(migrated.contains("left.getCount() == right.getCount()"), migrated)
        assertTrue(migrated.contains("ItemStack.isSameItem(left, right)"), migrated)
        assertTrue(migrated.contains("!(left.getCount() == right.getCount() && ItemStack.isSameItem(left, right))"), migrated)
        assertFalse(migrated.contains("equals(right,"), migrated)
    }

    @Test
    fun `generic List element field resolves to ItemStack`() {
        val file = write("sample/Result.java", """
            package sample;
            import java.util.List;
            import net.minecraft.world.item.ItemStack;
            class Holder { ItemStack stack; }
            class Result {
                List<Holder> outputs;
                boolean same(ItemStack before) { return outputs.get(0).stack.equals(before, false); }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("ItemStack.matches(outputs.get(0).stack, before)"), migrated)
    }

    @Test
    fun `array access resolves the declared element type`() {
        val file = write("sample/ArrayCompare.java", """
            package sample;
            import net.minecraft.world.item.ItemStack;
            class ArrayCompare {
                ItemStack[] stacks;
                boolean same(ItemStack other) { return stacks[0].equals(other, false); }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("ItemStack.matches(stacks[0], other)"), migrated)
    }

    @Test
    fun `generic screen menu field and Player method resolve structurally`() {
        write("sample/MenuBase.java", """
            package sample;
            import net.minecraft.world.entity.player.Player;
            class MenuBase { Player player; }
        """.trimIndent())
        write("sample/HeldMenu.java", """
            package sample;
            import net.minecraft.world.item.ItemStack;
            class HeldMenu extends MenuBase { ItemStack contentHolder; }
        """.trimIndent())
        write("sample/AbstractUi.java", """
            package sample;
            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
            abstract class AbstractUi<T extends HeldMenu> extends AbstractContainerScreen<T> {}
        """.trimIndent())
        val file = write("sample/Screen.java", """
            package sample;
            class Screen extends AbstractUi<HeldMenu> {
                boolean valid() { return menu.player.getMainHandItem().equals(menu.contentHolder, false); }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("ItemStack.matches(menu.player.getMainHandItem(), menu.contentHolder)"),
            migrated
        )
    }

    @Test
    fun `project type index loads only explicitly reached sources`() {
        val file = write("sample/Compare.java", """
            package sample;
            import net.minecraft.world.item.ItemStack;
            class Compare {
                boolean same(ItemStack left, ItemStack right) { return left.equals(right, false); }
            }
        """.trimIndent())
        write("unrelated/Unused.java", """
            package unrelated;
            class Unused {}
        """.trimIndent())

        val index = JavaProjectTypeIndex.build(tempDir.resolve("src/main/java"))
        assertEquals(0, index.loadedSourceCount)
        index.unit(file)
        assertEquals(1, index.loadedSourceCount)

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("ItemStack.matches(left, right)"), migrated)
    }

    @Test
    fun `project custom boolean equals remains untouched`() {
        val file = write("sample/Custom.java", """
            package sample;
            class Value { boolean equals(Value other, boolean strict) { return strict; } }
            class Custom {
                boolean compare(Value left, Value right) { return left.equals(right, false); }
            }
        """.trimIndent())
        val original = file.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `project class named ItemStack is not treated as the Minecraft type`() {
        val file = write("sample/CustomStack.java", """
            package sample;
            class ItemStack {
                boolean equals(ItemStack other, boolean strict) { return strict; }
            }
            class CustomStack {
                boolean compare(ItemStack left, ItemStack right) { return left.equals(right, false); }
            }
        """.trimIndent())
        val original = file.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `unresolved receiver with ItemStack evidence hard fails without writes`() {
        val file = write("sample/Unknown.java", """
            package sample;
            import net.minecraft.world.item.ItemStack;
            class Unknown {
                ExternalValue value;
                boolean compare(ItemStack other) { return value.equals(other, false); }
            }
        """.trimIndent())
        val original = file.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("Cannot prove two-argument equals receiver") }, result.errors.joinToString("\n"))
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `true mode complex argument hard fails without duplicated evaluation`() {
        val file = write("sample/SideEffect.java", """
            package sample;
            import net.minecraft.world.item.ItemStack;
            class SideEffect {
                boolean compare(ItemStack left) { return left.equals(next(), true); }
                ItemStack next() { return ItemStack.EMPTY; }
            }
        """.trimIndent())
        val original = file.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("argument must be a single-evaluation named value") }, result.errors.joinToString("\n"))
        assertTrue(file.readText() == original, file.readText())
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
