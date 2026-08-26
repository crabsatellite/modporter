package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.streams.toList
import kotlin.test.assertTrue

class PotionUtilsGeneratedCompileTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generated potion component calls compile against target contracts`() {
        writeJava(
            "com/example/PotionCalls.java",
            """
            package com.example;

            import java.util.Collection;
            import java.util.List;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionUtils;
            import net.minecraft.world.item.alchemy.Potions;

            class PotionCalls {
                ItemStack create() {
                    return PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
                }

                void clear(ItemStack stack) {
                    PotionUtils.setPotion(stack, Potions.EMPTY);
                }

                List<MobEffectInstance> custom(ItemStack stack) {
                    List<MobEffectInstance> effects = PotionUtils.getCustomEffects(stack);
                    effects.clear();
                    return effects;
                }

                List<MobEffectInstance> all(ItemStack stack) {
                    List<MobEffectInstance> effects = PotionUtils.getMobEffects(stack);
                    effects.clear();
                    return effects;
                }

                ItemStack append(ItemStack stack, Collection<MobEffectInstance> effects) {
                    return PotionUtils.setCustomEffects(stack, effects);
                }

                int conditionalColor(boolean first, ItemStack left, ItemStack right) {
                    return PotionUtils.getColor(first ? left : right);
                }

                int castColor(Object stack) {
                    return PotionUtils.getColor((ItemStack) stack);
                }

                boolean water(ItemStack stack) {
                    return PotionUtils.getPotion(stack) == Potions.WATER;
                }

                boolean absent(ItemStack stack) {
                    return PotionUtils.getPotion(stack) == Potions.EMPTY;
                }

                boolean storedWater(ItemStack stack) {
                    Potion potion = PotionUtils.getPotion(stack);
                    return potion == Potions.WATER;
                }

                void visitPotions() {
                    for (Potion potion : BuiltInRegistries.POTION.stream().toList()) {
                        if (potion == Potions.EMPTY) {
                            continue;
                        }
                        potion.toString();
                    }
                }

                void tooltip(ItemStack stack, List<Component> lines) {
                    PotionUtils.addPotionTooltip(stack, lines, 1.0F);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        assertTrue(result.errors.isEmpty(), result.errors.toString())
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
            val units = fileManager.getJavaFileObjectsFromFiles(sources)
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("-d", classesDir.toString()),
                null,
                units
            ).call()
            assertTrue(
                success,
                diagnostics.diagnostics.joinToString("\n") {
                    "${it.kind} ${it.source?.name}:${it.lineNumber}: ${it.getMessage(null)}"
                }
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
            "net/minecraft/core/Holder.java",
            """
            package net.minecraft.core;

            public final class Holder<T> {
                private final T value;

                public Holder(T value) {
                    this.value = value;
                }

                public T value() {
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

            import net.minecraft.world.item.alchemy.PotionContents;

            public final class DataComponents {
                public static final DataComponentType<PotionContents> POTION_CONTENTS =
                    new DataComponentType<>();
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/core/registries/BuiltInRegistries.java",
            """
            package net.minecraft.core.registries;

            import java.util.List;
            import java.util.stream.Stream;
            import net.minecraft.world.item.alchemy.Potion;

            public final class BuiltInRegistries {
                public static final Registry<Potion> POTION = new Registry<>();

                public static final class Registry<T> {
                    public Stream<T> stream() {
                        return List.<T>of().stream();
                    }
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/network/chat/Component.java",
            """
            package net.minecraft.network.chat;

            public class Component {
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/effect/MobEffectInstance.java",
            """
            package net.minecraft.world.effect;

            public class MobEffectInstance {
                public MobEffectInstance() {
                }

                public MobEffectInstance(MobEffectInstance source) {
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/Item.java",
            """
            package net.minecraft.world.item;

            public class Item {
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/Items.java",
            """
            package net.minecraft.world.item;

            public final class Items {
                public static final Item POTION = new Item();
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/ItemStack.java",
            """
            package net.minecraft.world.item;

            import java.util.function.BiFunction;
            import java.util.function.UnaryOperator;
            import net.minecraft.core.component.DataComponentType;

            public class ItemStack {
                public ItemStack(Item item) {
                }

                public <T> T getOrDefault(DataComponentType<T> type, T defaultValue) {
                    return defaultValue;
                }

                public <T> T update(DataComponentType<T> type, T defaultValue, UnaryOperator<T> updater) {
                    return updater.apply(defaultValue);
                }

                public <T, U> T update(
                    DataComponentType<T> type,
                    T defaultValue,
                    U updateValue,
                    BiFunction<T, U, T> updater
                ) {
                    return updater.apply(defaultValue, updateValue);
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/alchemy/Potion.java",
            """
            package net.minecraft.world.item.alchemy;

            import java.util.List;
            import java.util.Optional;
            import net.minecraft.core.Holder;
            import net.minecraft.world.effect.MobEffectInstance;

            public class Potion {
                public static String getName(Optional<Holder<Potion>> potion, String prefix) {
                    return prefix;
                }

                public List<MobEffectInstance> getEffects() {
                    return List.of();
                }
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/alchemy/Potions.java",
            """
            package net.minecraft.world.item.alchemy;

            import net.minecraft.core.Holder;

            public final class Potions {
                public static final Holder<Potion> WATER = new Holder<>(new Potion());
            }
            """.trimIndent()
        )
        writeJava(
            "net/minecraft/world/item/alchemy/PotionContents.java",
            """
            package net.minecraft.world.item.alchemy;

            import java.util.List;
            import java.util.Optional;
            import java.util.function.Consumer;
            import net.minecraft.core.Holder;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.effect.MobEffectInstance;

            public final class PotionContents {
                public static final PotionContents EMPTY =
                    new PotionContents(Optional.empty(), Optional.empty(), List.of());

                private final Optional<Holder<Potion>> potion;
                private final Optional<Integer> customColor;
                private final List<MobEffectInstance> customEffects;

                public PotionContents(
                    Optional<Holder<Potion>> potion,
                    Optional<Integer> customColor,
                    List<MobEffectInstance> customEffects
                ) {
                    this.potion = potion;
                    this.customColor = customColor;
                    this.customEffects = customEffects;
                }

                public Optional<Holder<Potion>> potion() {
                    return potion;
                }

                public Optional<Integer> customColor() {
                    return customColor;
                }

                public List<MobEffectInstance> customEffects() {
                    return customEffects.stream().map(MobEffectInstance::new).toList();
                }

                public PotionContents withPotion(Holder<Potion> value) {
                    return new PotionContents(Optional.of(value), customColor, customEffects);
                }

                public Iterable<MobEffectInstance> getAllEffects() {
                    return customEffects;
                }

                public int getColor() {
                    return 0;
                }

                public void addPotionTooltip(
                    Consumer<Component> consumer,
                    float durationFactor,
                    float ticksPerSecond
                ) {
                }
            }
            """.trimIndent()
        )
    }

    private fun writeJava(relativePath: String, source: String) {
        val file = tempDir.resolve("src/main/java").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(source)
    }
}
