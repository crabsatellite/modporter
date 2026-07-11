package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyMutableRecipeWrapperMigration
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LegacyMutableRecipeWrapperMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `migrates direct wrappers and proven subclasses to real modifiable handler delegation`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("DirectUse.java").writeText(
            """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.items.ItemStackHandler;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
            public class DirectUse {
                private static final RecipeWrapper INPUT = new RecipeWrapper(new ItemStackHandler(1));
                static void prepare(ItemStack stack) {
                    INPUT.setItem(0, stack);
                    String documentation = "RecipeWrapper.setItem(0, stack)";
                }
            }
            """.trimIndent()
        )
        source.resolve("CustomInput.java").writeText(
            """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.items.ItemStackHandler;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
            public class CustomInput extends RecipeWrapper {
                public CustomInput(ItemStack stack) {
                    super(new ItemStackHandler(1));
                    inv.setStackInSlot(0, stack);
                }
            }
            """.trimIndent()
        )
        source.resolve("CustomUse.java").writeText(
            """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            public class CustomUse {
                private final CustomInput input = new CustomInput(ItemStack.EMPTY);
                void prepare(ItemStack stack) {
                    input.setItem(0, stack);
                }
            }
            """.trimIndent()
        )

        val changes = LegacyMutableRecipeWrapperMigration().migrate(tempDir, dryRun = false)
        val direct = source.resolve("DirectUse.java").readText()
        val custom = source.resolve("CustomInput.java").readText()
        val generated = tempDir.resolve(
            "src/main/java/com/modporter/generated/compat/MutableRecipeWrapper.java"
        ).readText()

        assertTrue(changes.any { it.ruleId == "struct-mutable-recipe-wrapper" })
        assertTrue(
            direct.contains(
                "com.modporter.generated.compat.MutableRecipeWrapper INPUT = new com.modporter.generated.compat.MutableRecipeWrapper(new ItemStackHandler(1))"
            ),
            direct
        )
        assertTrue(direct.contains("INPUT.setItem(0, stack);"), direct)
        assertTrue(direct.contains("\"RecipeWrapper.setItem(0, stack)\""), direct)
        assertTrue(custom.contains("extends com.modporter.generated.compat.MutableRecipeWrapper"), custom)
        assertTrue(generated.contains("private final IItemHandlerModifiable mutableHandler;"), generated)
        assertTrue(generated.contains("mutableHandler.setStackInSlot(slot, stack);"), generated)
    }

    @Test
    fun `hard fails mutable wrapper use when backing mutability is not proven`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("UnknownUse.java").writeText(
            """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.items.IItemHandler;
            import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
            public class UnknownUse {
                private final RecipeWrapper input;
                public UnknownUse(IItemHandler handler) {
                    input = new RecipeWrapper(handler);
                }
                void prepare(ItemStack stack) {
                    input.setItem(0, stack);
                }
            }
            """.trimIndent()
        )

        val error = assertThrows(IllegalStateException::class.java) {
            LegacyMutableRecipeWrapperMigration().migrate(tempDir, dryRun = false)
        }
        assertTrue(error.message.orEmpty().contains("backing handler is not proven modifiable"), error.message)
    }
}
