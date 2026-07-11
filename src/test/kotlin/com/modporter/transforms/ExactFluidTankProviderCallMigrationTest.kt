package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactFluidTankProviderCallMigration
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactFluidTankProviderCallMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `project FluidTank subtype calls use the callable declared provider`() {
        write("sample/BufferTank.java", """
            package sample;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
            class BufferTank extends FluidTank {
                BufferTank() { super(1000); }
            }
        """.trimIndent())
        val file = write("sample/Storage.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Storage {
                BufferTank contents;
                void save(CompoundTag tag, HolderLookup.Provider lookup) {
                    tag.put("Tank", contents.writeToNBT(new CompoundTag()));
                }
                void load(CompoundTag tag, HolderLookup.Provider lookup) {
                    contents.readFromNBT(tag.getCompound("Tank"));
                }
            }
        """.trimIndent())

        val changes = ExactFluidTankProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("contents.writeToNBT(lookup, new CompoundTag())"), migrated)
        assertTrue(migrated.contains("contents.readFromNBT(lookup, tag.getCompound(\"Tank\"))"), migrated)
    }

    @Test
    fun `project class named FluidTank remains untouched`() {
        val file = write("sample/Business.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            class FluidTank {
                CompoundTag writeToNBT(CompoundTag tag) { return tag; }
                void readFromNBT(CompoundTag tag) {}
            }
            class Business {
                FluidTank tank;
                void copy(CompoundTag tag) {
                    tank.writeToNBT(tag);
                    tank.readFromNBT(tag);
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactFluidTankProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `provider threads through lambda instance and static project calls`() {
        write("sample/BufferTank.java", """
            package sample;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
            class BufferTank extends FluidTank {
                BufferTank() { super(1000); }
            }
        """.trimIndent())
        val codec = write("sample/Codec.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            class Codec {
                BufferTank contents = new BufferTank();
                CompoundTag serializeNBT(CompoundTag tag) {
                    contents.writeToNBT(tag);
                    return tag;
                }
                static Codec fromNBT(CompoundTag tag) {
                    Codec value = new Codec();
                    value.contents.readFromNBT(tag);
                    return value;
                }
            }
        """.trimIndent())
        val root = write("sample/Root.java", """
            package sample;
            import java.util.Map;
            import java.util.function.Consumer;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Root {
                Map<String, Codec> codecs;
                void forEach(Consumer<Codec> action) { action.accept(codecs.values().iterator().next()); }
                void save(CompoundTag tag, HolderLookup.Provider lookup) {
                    codecs.values().forEach(codec -> codec.serializeNBT(tag));
                    forEach(codec -> codec.serializeNBT(tag));
                }
                Codec load(CompoundTag tag, HolderLookup.Provider lookup) {
                    return Codec.fromNBT(tag);
                }
            }
        """.trimIndent())
        write("sample/Unrelated.java", """
            package sample;
            class Item { void serializeNBT() {} }
            enum Unrelated {
                VALUE;
                void invoke(Item item) { item.serializeNBT(); }
            }
        """.trimIndent())

        val changes = ExactFluidTankProviderCallMigration().migrate(tempDir, dryRun = false)
        val migratedCodec = codec.readText()
        val migratedRoot = root.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migratedCodec.contains("serializeNBT(CompoundTag tag, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedCodec)
        assertTrue(migratedCodec.contains("contents.writeToNBT(modporterRegistries, tag)"), migratedCodec)
        assertTrue(migratedCodec.contains("fromNBT(CompoundTag tag, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedCodec)
        assertTrue(migratedCodec.contains("value.contents.readFromNBT(modporterRegistries, tag)"), migratedCodec)
        assertTrue(migratedRoot.contains("codec.serializeNBT(tag, lookup)"), migratedRoot)
        assertTrue(migratedRoot.contains("Codec.fromNBT(tag, lookup)"), migratedRoot)
    }

    @Test
    fun `exact FluidTank call without a provider hard fails without writes`() {
        val file = write("sample/MissingProvider.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
            class MissingProvider {
                FluidTank tank;
                void save(CompoundTag tag) { tank.writeToNBT(tag); }
            }
        """.trimIndent())
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            ExactFluidTankProviderCallMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("has no exact project caller"), error.message)
        assertTrue(file.readText() == original, file.readText())
        assertFalse(file.readText().contains("registryAccess()"), file.readText())
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
