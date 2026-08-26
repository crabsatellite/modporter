package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactLegacyCustomDataMigration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactLegacyCustomDataMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `structural pass wires the exact custom data graph before common rewrites`() {
        val source = writeJava(
            "WiredCustomData.java",
            """
            package com.example;

            import net.minecraft.world.item.ItemStack;

            class WiredCustomData {
                void write(ItemStack stack) {
                    stack.getOrCreateTag().putInt("Count", 1);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = source.readText()

        assertTrue(
            result.changes.any { it.ruleId == "struct-exact-legacy-custom-data-graph" },
            result.changes.joinToString()
        )
        assertTrue(migrated.contains("CustomData.update"), migrated)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
    }

    @Test
    fun `direct item and fluid custom data operations preserve writes explicitly`() {
        val source = writeJava(
            "ExactCustomData.java",
            """
            package com.example;

            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            class ExactCustomData {
                void mutateItem(ItemStack stack) {
                    stack.getOrCreateTag().putInt("Count", 3);
                }

                int readItem(ItemStack stack) {
                    return stack.getOrCreateTag().getInt("Count");
                }

                void mutateFluid(FluidStack fluid, Mode mode) {
                    NBTHelper.writeEnum(fluid.getOrCreateTag(), "Mode", mode);
                }

                Mode readFluid(FluidStack fluid) {
                    return NBTHelper.readEnum(fluid.getOrCreateTag(), "Mode", Mode.class);
                }

                enum Mode {
                    FIRST, SECOND
                }
            }
            """.trimIndent()
        )

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size)
        assertTrue(
            Regex(
                """net\.minecraft\.world\.item\.component\.CustomData\.update\(""" +
                    """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*stack,\s*""" +
                    """modPorterCustomDataTag\s*->\s*modPorterCustomDataTag\.putInt\("Count",\s*3\)\);"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            migrated.contains(
                "(stack).getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getInt(\"Count\")"
            ),
            migrated
        )
        assertTrue(
            Regex(
                """net\.minecraft\.Util\.make\(fluid,\s*modPorterCustomDataOwner\s*->\s*""" +
                    """net\.minecraft\.Util\.make\(\(modPorterCustomDataOwner\)\.getOrDefault\(""" +
                    """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                    """net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\),\s*""" +
                    """modPorterCustomDataTag\s*->"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains("NBTHelper.writeEnum(modPorterCustomDataTag, \"Mode\", mode);"), migrated)
        assertTrue(
            migrated.contains(
                "(modPorterCustomDataOwner).set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.of(modPorterCustomDataTag))"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "NBTHelper.readEnum((fluid).getOrDefault(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag(), \"Mode\", Mode.class)"
            ),
            migrated
        )
        assertTrue(!migrated.contains("getOrCreateTag()"), migrated)
    }

    @Test
    fun `local live tag aliases write back after every proven mutation`() {
        val source = writeJava(
            "AliasCustomData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class AliasCustomData {
                int update(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.putString("Name", "value");
                    int result = tag.getInt("Count");
                    tag.remove("Name");
                    return result;
                }

                void explicitWriteBack(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.putInt("Count", 1);
                    stack.set(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(tag)
                    );
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            migrated.contains(
                "CompoundTag tag = (stack).getOrDefault(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag();"
            ),
            migrated
        )
        assertEquals(
            3,
            Regex("""if \(\(tag\)\.isEmpty\(\)\)""").findAll(migrated).count(),
            migrated
        )
        assertEquals(
            4,
            Regex("""CustomData\.of\(tag\)""").findAll(migrated).count(),
            migrated
        )
        assertTrue(migrated.indexOf("tag.putString") < migrated.indexOf("CustomData.of(tag)"), migrated)
        assertTrue(migrated.indexOf("tag.remove") < migrated.lastIndexOf("CustomData.of(tag)"), migrated)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
    }

    @Test
    fun `member aliases and fluid call receivers are evaluated once through owner snapshots`() {
        val source = writeJava(
            "OwnerSnapshots.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            class OwnerSnapshots {
                ItemStack item;

                void fieldAlias() {
                    CompoundTag tag = item.getOrCreateTag();
                    tag.putInt("Count", 1);
                }

                void returnedFluid() {
                    nextFluid().getOrCreateTag().putInt("Count", 1);
                }

                FluidStack nextFluid() {
                    return null;
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            Regex(
                """net\.minecraft\.world\.item\.ItemStack\s+modPorterCustomDataOwner\s*=\s*item;"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            migrated.contains(
                "(modPorterCustomDataOwner).set(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA"
            ),
            migrated
        )
        assertTrue(
            Regex(
                """net\.minecraft\.Util\.make\(nextFluid\(\),\s*""" +
                    """modPorterCustomDataOwner\s*->\s*net\.minecraft\.Util\.make\("""
            ).containsMatchIn(migrated),
            migrated
        )
        assertEquals(
            1,
            Regex("""net\.minecraft\.Util\.make\(nextFluid\(\)""").findAll(migrated).count(),
            migrated
        )
    }

    @Test
    fun `unsupported tag escape keeps every exact call in the file untouched`() {
        val source = writeJava(
            "UnsupportedCustomData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class UnsupportedCustomData {
                void mixed(ItemStack first, ItemStack second) {
                    first.getOrCreateTag().putInt("Safe", 1);
                    consume(second.getOrCreateTag());
                }

                void consume(CompoundTag tag) {
                }
            }
            """.trimIndent()
        )
        val original = source.readText()

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertEquals(original, source.readText())
    }

    @Test
    fun `receiver reassignment snapshots the original owner while lookalikes remain untouched`() {
        val source = writeJava(
            "AmbiguousCustomData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class AmbiguousCustomData {
                void reassigned(ItemStack stack, ItemStack replacement) {
                    CompoundTag tag = stack.getOrCreateTag();
                    stack = replacement;
                    tag.putInt("Count", 1);
                }

                int lookalike(Box box) {
                    return box.getOrCreateTag().getInt("Count");
                }

                static class Box {
                    CompoundTag getOrCreateTag() {
                        return new CompoundTag();
                    }
                }
            }
            """.trimIndent()
        )
        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size)
        assertTrue(
            Regex(
                """net\.minecraft\.world\.item\.ItemStack\s+modPorterCustomDataOwner\s*=\s*stack;"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains("stack = replacement;"), migrated)
        assertTrue(
            migrated.contains(
                "(modPorterCustomDataOwner).set(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA"
            ),
            migrated
        )
        assertTrue(migrated.contains("return box.getOrCreateTag().getInt(\"Count\");"), migrated)
    }

    private fun writeJava(name: String, source: String): Path {
        val directory = tempDir.resolve("src/main/java/com/example")
        directory.createDirectories()
        return directory.resolve(name).also { it.writeText(source) }
    }
}
