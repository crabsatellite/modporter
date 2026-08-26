package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactLegacyCustomDataMigration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList
import kotlin.test.assertTrue

class ExactLegacyCustomDataGeneratedCompileTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generated custom data graphs compile against target contracts`() {
        writeJava(
            "com/example/CustomDataCalls.java",
            """
            package com.example;

            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            class CustomDataCalls {
                void item(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.putInt("Count", tag.getInt("Count") + 1);
                }

                void fluid(FluidStack stack, Mode mode) {
                    NBTHelper.writeEnum(stack.getOrCreateTag(), "Mode", mode);
                }

                Mode read(FluidStack stack) {
                    return NBTHelper.readEnum(stack.getOrCreateTag(), "Mode", Mode.class);
                }

                void returnedFluid() {
                    nextFluid().getOrCreateTag().putInt("Count", 1);
                }

                FluidStack nextFluid() {
                    return new FluidStack();
                }

                void projectMutation(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    mutate(tag);
                }

                static void mutate(CompoundTag tag) {
                    tag.putInt("Count", 1);
                }

                enum Mode {
                    FIRST
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migratedSource = tempDir.resolve("src/main/java/com/example/CustomDataCalls.java").readText()
        writeTargetStubs()

        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val classesDir = tempDir.resolve("classes")
        classesDir.createDirectories()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fileManager ->
            val sources = Files.walk(tempDir.resolve("src/main/java")).use { paths ->
                paths.filter { it.toString().endsWith(".java") }
                    .map(Path::toFile)
                    .toList()
            }
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("-d", classesDir.toString()),
                null,
                fileManager.getJavaFileObjectsFromFiles(sources)
            ).call()
            assertTrue(
                success,
                diagnostics.diagnostics.joinToString("\n") { diagnostic ->
                    "${diagnostic.kind}: ${diagnostic.source?.name}:" +
                        "${diagnostic.lineNumber} ${diagnostic.getMessage(null)}"
                } + "\n\n" + migratedSource
            )
        }
    }

    private fun writeTargetStubs() {
        writeJava(
            "net/minecraft/Util.java",
            """
            package net.minecraft;

            import java.util.function.Consumer;

            public final class Util {
                public static <T> T make(T value, Consumer<T> consumer) {
                    consumer.accept(value);
                    return value;
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/core/component/DataComponentType.java",
            """
            package net.minecraft.core.component;

            public final class DataComponentType<T> {
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/core/component/DataComponents.java",
            """
            package net.minecraft.core.component;

            import net.minecraft.world.item.component.CustomData;

            public final class DataComponents {
                public static final DataComponentType<CustomData> CUSTOM_DATA =
                    new DataComponentType<>();
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/nbt/CompoundTag.java",
            """
            package net.minecraft.nbt;

            public final class CompoundTag {
                public int getInt(String key) {
                    return 0;
                }

                public void putInt(String key, int value) {
                }

                public boolean isEmpty() {
                    return false;
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/component/CustomData.java",
            """
            package net.minecraft.world.item.component;

            import java.util.function.Consumer;
            import net.minecraft.core.component.DataComponentType;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            public final class CustomData {
                public static final CustomData EMPTY = new CustomData();

                public static CustomData of(CompoundTag tag) {
                    return new CustomData();
                }

                public static void update(
                    DataComponentType<CustomData> type,
                    ItemStack stack,
                    Consumer<CompoundTag> updater
                ) {
                    updater.accept(new CompoundTag());
                }

                public CompoundTag copyTag() {
                    return new CompoundTag();
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;

            import net.minecraft.core.component.DataComponentType;

            public class ItemStack {
                public <T> T getOrDefault(DataComponentType<T> type, T defaultValue) {
                    return defaultValue;
                }

                public <T> T set(DataComponentType<? super T> type, T value) {
                    return value;
                }

                public <T> T remove(DataComponentType<? extends T> type) {
                    return null;
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/neoforged/neoforge/fluids/FluidStack.java",
            """
            package net.neoforged.neoforge.fluids;

            import net.minecraft.core.component.DataComponentType;

            public class FluidStack {
                public <T> T getOrDefault(DataComponentType<T> type, T defaultValue) {
                    return defaultValue;
                }

                public <T> T set(DataComponentType<? super T> type, T value) {
                    return value;
                }

                public <T> T remove(DataComponentType<? extends T> type) {
                    return null;
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/createmod/catnip/nbt/NBTHelper.java",
            """
            package net.createmod.catnip.nbt;

            import net.minecraft.nbt.CompoundTag;

            public final class NBTHelper {
                public static <E extends Enum<E>> void writeEnum(
                    CompoundTag tag,
                    String key,
                    E value
                ) {
                }

                public static <E extends Enum<E>> E readEnum(
                    CompoundTag tag,
                    String key,
                    Class<E> type
                ) {
                    return type.getEnumConstants()[0];
                }
            }
            """.trimIndent()
        )
    }

    private fun writeJava(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
