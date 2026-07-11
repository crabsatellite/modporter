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

class LegacyDefaultTooltipFlagsMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `default modifier tooltip flags move into source proven constructor attributes`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("QuietSword.java")
        file.writeText("""
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            import net.minecraft.world.item.SwordItem;
            class QuietSword extends SwordItem {
                QuietSword(Properties properties) {
                    super(Tiers.IRON, 3, 1.0F, properties);
                }
                @Override
                public int getDefaultTooltipHideFlags(ItemStack stack) {
                    return TooltipPart.MODIFIERS.getMask();
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("SwordItem.createAttributes(Tiers.IRON, 3, 1.0F).withTooltip(false)"),
            migrated
        )
        assertFalse(migrated.contains("getDefaultTooltipHideFlags"), migrated)
        assertFalse(migrated.contains("TooltipPart"), migrated)
    }

    @Test
    fun `nontrivial default flag logic hard fails without writing other files`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val valid = dir.resolve("AValidSword.java")
        val validOriginal = """
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            import net.minecraft.world.item.SwordItem;
            class AValidSword extends SwordItem {
                AValidSword(Properties properties) {
                    super(Tiers.IRON, properties.attributes(attributes()));
                }
                public int getDefaultTooltipHideFlags(ItemStack stack) {
                    return TooltipPart.MODIFIERS.getMask();
                }
            }
        """.trimIndent()
        valid.writeText(validOriginal)
        val invalid = dir.resolve("ConditionalSword.java")
        invalid.writeText("""
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            import net.minecraft.world.item.SwordItem;
            class ConditionalSword extends SwordItem {
                ConditionalSword(Properties properties) {
                    super(Tiers.IRON, properties.attributes(attributes()));
                }
                public int getDefaultTooltipHideFlags(ItemStack stack) {
                    return stack.isEmpty() ? 0 : TooltipPart.MODIFIERS.getMask();
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("not exactly MODIFIERS") }, result.errors.joinToString("\n"))
        assertTrue(valid.readText() == validOriginal, valid.readText())
        assertTrue(invalid.readText().contains("stack.isEmpty() ? 0"), invalid.readText())
    }

    @Test
    fun `stack modifier tooltip hiding preserves existing attribute values`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("DisplayStack.java")
        file.writeText("""
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            class DisplayStack {
                void prepare(ItemStack stack) {
                    stack.hideTooltipPart(TooltipPart.MODIFIERS);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains(
                "stack.set(DataComponents.ATTRIBUTE_MODIFIERS, stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).withTooltip(false))"
            ),
            migrated
        )
        assertFalse(migrated.contains("hideTooltipPart"), migrated)
    }

    @Test
    fun `every direct constructor path must preserve hidden modifier semantics`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("IncompleteSword.java")
        val original = """
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            import net.minecraft.world.item.SwordItem;
            class IncompleteSword extends SwordItem {
                IncompleteSword(Properties properties) {
                    super(Tiers.IRON, 3, 1.0F, properties);
                }
                IncompleteSword(Properties properties, boolean alternate) {
                    super(properties);
                }
                public int getDefaultTooltipHideFlags(ItemStack stack) {
                    return TooltipPart.MODIFIERS.getMask();
                }
            }
        """.trimIndent()
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("no source-proven Properties.attributes migration") })
        assertFalse(file.readText().contains("withTooltip(false)"), file.readText())
        assertTrue(file.readText().contains("getDefaultTooltipHideFlags"), file.readText())
    }

    @Test
    fun `project types named SwordItem do not satisfy Minecraft owner proof`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("CustomSword.java")
        val original = """
            package example;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;
            class SwordItem {
                static class Properties {
                    Properties attributes(Object value) { return this; }
                }
                SwordItem(Object tier, int damage, float speed, Properties properties) {}
            }
            class CustomSword extends SwordItem {
                CustomSword(Properties properties) {
                    super(Tiers.IRON, 3, 1.0F, properties);
                }
                public int getDefaultTooltipHideFlags(ItemStack stack) {
                    return TooltipPart.MODIFIERS.getMask();
                }
            }
        """.trimIndent()
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("no source-proven Properties.attributes migration") })
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `legacy tool constructor migration is scoped to its exact owner class`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("MixedOwners.java")
        file.writeText("""
            package example;
            import net.minecraft.world.item.SwordItem;
            class RealSword extends SwordItem {
                RealSword(Properties properties) {
                    super(Tiers.IRON, 3, -2.4F, properties);
                }
            }
            class OtherBase {
                OtherBase(Object a, Object b, Object c, Object d) {}
            }
            class OtherItem extends OtherBase {
                OtherItem(Object properties) {
                    super(A, B, C, properties);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("super(Tiers.IRON, properties.attributes(SwordItem.createAttributes(Tiers.IRON, 3, -2.4F)))"),
            migrated
        )
        assertTrue(migrated.contains("super(A, B, C, properties);"), migrated)
    }
}
