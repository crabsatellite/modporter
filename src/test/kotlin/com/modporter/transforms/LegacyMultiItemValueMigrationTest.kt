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

class LegacyMultiItemValueMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `compound identity moves from Value subtype to exact owning Ingredient`() {
        val file = writeSource(validSource())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("import net.neoforged.neoforge.common.crafting.CompoundIngredient;"), migrated)
        assertTrue(migrated.contains("boolean isCompoundIngredient"), migrated)
        assertTrue(
            migrated.contains("adapt(values[0], source.getCustomIngredient() instanceof CompoundIngredient)"),
            migrated
        )
        assertTrue(
            migrated.contains("adapt(values[1], source.getCustomIngredient() instanceof CompoundIngredient)"),
            migrated
        )
        assertTrue(migrated.contains("if (isCompoundIngredient)"), migrated)
        assertFalse(migrated.contains("MultiItemValue"), migrated)
    }

    @Test
    fun `custom same named type does not satisfy NeoForge owner proof`() {
        val original = validSource().replace(
            "import net.neoforged.neoforge.common.crafting.MultiItemValue;",
            "import sample.custom.MultiItemValue;"
        )
        val file = writeSource(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("no exact NeoForge owner import") }, result.errors.joinToString("\n"))
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `unproven helper argument hard fails without writes`() {
        val original = validSource().replace("adapt(values[0])", "adapt(other())")
        val file = writeSource(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("not an exact Ingredient values element") }, result.errors.joinToString("\n"))
        assertTrue(file.readText() == original, file.readText())
    }

    private fun writeSource(source: String): Path {
        val dir = tempDir.resolve("src/main/java/sample/recipe")
        dir.createDirectories()
        val file = dir.resolve("IngredientFilter.java")
        file.writeText(source)
        return file
    }

    private fun validSource(): String = """
        package sample.recipe;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.item.crafting.Ingredient;
        import net.minecraft.world.item.crafting.Ingredient.Value;
        import net.neoforged.neoforge.common.crafting.MultiItemValue;
        final class IngredientFilter {
            static ItemStack convert(Ingredient source) {
                Value[] values = source.values;
                if (values.length > 1) return adapt(values[1]);
                return adapt(values[0]);
            }
            private static ItemStack adapt(Value choice) {
                if (choice instanceof MultiItemValue) {
                    return new ItemStack(null);
                }
                return ItemStack.EMPTY;
            }
            private static Value other() { return null; }
        }
    """.trimIndent()
}
