package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactProjectProviderCallMigration
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactProjectProviderCallMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `provider closes through project methods fields static calls and collection lambdas`() {
        write("sample/Codec.java", """
            package sample;
            import java.util.function.Function;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Codec {
                CompoundTag serializeNBT(HolderLookup.Provider registries) {
                    registries.toString();
                    return new CompoundTag();
                }
                static Codec read(CompoundTag tag, HolderLookup.Provider registries) {
                    registries.toString();
                    return new Codec();
                }
            }
        """.trimIndent())
        val inventory = write("sample/Inventory.java", """
            package sample;
            import java.util.List;
            import net.minecraft.nbt.CompoundTag;
            class Inventory {
                List<Codec> items;
                CompoundTag write() {
                    CompoundTag tag = new CompoundTag();
                    items.forEach(item -> tag.put("Item", item.serializeNBT()));
                    return tag;
                }
                void read(CompoundTag tag) {
                    items.add(Codec.read(tag.getCompound("Item")));
                }
            }
        """.trimIndent())
        val root = write("sample/Root.java", """
            package sample;
            import java.util.function.Function;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Root {
                Inventory inventory;
                void save(CompoundTag tag, HolderLookup.Provider lookup) {
                    Function<Codec, CompoundTag> encoder = Codec::serializeNBT;
                    tag.put("Inventory", inventory.write());
                }
                void load(CompoundTag tag, HolderLookup.Provider lookup) {
                    Function<CompoundTag, Codec> decoder = Codec::read;
                    inventory.read(tag.getCompound("Inventory"));
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migratedInventory = inventory.readText()
        val migratedRoot = root.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migratedInventory.contains("CompoundTag write(net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedInventory)
        assertTrue(migratedInventory.contains("item.serializeNBT(modporterRegistries)"), migratedInventory)
        assertTrue(migratedInventory.contains("void read(CompoundTag tag, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedInventory)
        assertTrue(migratedInventory.contains("Codec.read(tag.getCompound(\"Item\"), modporterRegistries)"), migratedInventory)
        assertTrue(migratedRoot.contains("inventory.write(lookup)"), migratedRoot)
        assertTrue(migratedRoot.contains("inventory.read(tag.getCompound(\"Inventory\"), lookup)"), migratedRoot)
        assertTrue(migratedRoot.contains("modporterValue -> modporterValue.serializeNBT(lookup)"), migratedRoot)
        assertTrue(migratedRoot.contains("modporterArg0 -> Codec.read(modporterArg0, lookup)"), migratedRoot)
    }

    @Test
    fun `lambda parameter type uses exact non functional arguments to select an overload`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        write("sample/First.java", """
            package sample;
            class First { Codec codec; }
        """.trimIndent())
        write("sample/Second.java", """
            package sample;
            class Second { Codec codec; }
        """.trimIndent())
        write("sample/Registry.java", """
            package sample;
            import java.util.function.Consumer;
            class Registry {
                void create(String id, Consumer<First> action) {}
                void create(int id, Consumer<Second> action) {}
            }
        """.trimIndent())
        val file = write("sample/Usage.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Usage {
                Registry registry;
                void run(HolderLookup.Provider registries) {
                    registry.create("first", value -> value.codec.encode());
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("value.codec.encode(registries)"), migrated)
    }

    @Test
    fun `new provider parameter is appended without inferring position from CompoundTag`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val bridge = write("sample/Bridge.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            class Bridge {
                Codec codec;
                void save(CompoundTag tag, int flags) { codec.encode(); }
            }
        """.trimIndent())
        val root = write("sample/Root.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Root {
                Bridge bridge;
                void run(CompoundTag tag, HolderLookup.Provider registries) {
                    bridge.save(tag, 1);
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migratedBridge = bridge.readText()
        val migratedRoot = root.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migratedBridge.contains(
            "void save(CompoundTag tag, int flags, net.minecraft.core.HolderLookup.Provider modporterRegistries)"
        ), migratedBridge)
        assertTrue(migratedBridge.contains("codec.encode(modporterRegistries)"), migratedBridge)
        assertTrue(migratedRoot.contains("bridge.save(tag, 1, registries)"), migratedRoot)
    }

    @Test
    fun `existing legacy arity overload is not redirected to provider overload`() {
        val file = write("sample/Overloaded.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Overloaded {
                void encode(String value) {}
                void encode(String value, HolderLookup.Provider registries) { registries.toString(); }
                void invoke() { encode("value"); }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `inherited legacy arity overload is not redirected to provider overload`() {
        val file = write("sample/Inherited.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Base {
                void encode(String value) {}
            }
            class Inherited extends Base {
                void encode(String value, HolderLookup.Provider registries) { registries.toString(); }
                void invoke(HolderLookup.Provider registries) { encode("value"); }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `typed player parameter supplies provider without changing override signature`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void serializeNBT(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Handler.java", """
            package sample;
            import net.minecraft.server.level.ServerPlayer;
            abstract class Base { abstract void handle(ServerPlayer player); }
            class Handler extends Base {
                Codec codec;
                @Override void handle(ServerPlayer player) { codec.serializeNBT(); }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("void handle(ServerPlayer player)"), migrated)
        assertTrue(migrated.contains("codec.serializeNBT(player.registryAccess())"), migrated)
        assertTrue(!migrated.contains("handle(ServerPlayer player, "), migrated)
    }

    @Test
    fun `unused generated provider is removed instead of propagated`() {
        val file = write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                static String of(String value, HolderLookup.Provider registries) { return value; }
                String read(String value) { return of(value); }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("static String of(String value)"), migrated)
        assertTrue(migrated.contains("return of(value);"), migrated)
        assertTrue(!migrated.contains("HolderLookup.Provider registries"), migrated)
    }

    @Test
    fun `same named project Provider is not treated as HolderLookup Provider`() {
        val file = write("sample/Business.java", """
            package sample;
            class Provider {}
            class Business {
                void encode(Provider provider) {}
                void invoke() { encode(); }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `provider demand without an exact source remains atomic for compile gate`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void serializeNBT(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/DeadEnd.java", """
            package sample;
            class DeadEnd {
                Codec codec;
                void save() { codec.serializeNBT(); }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `provider demand does not partially change an override contract`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void serializeNBT(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Handler.java", """
            package sample;
            class Handler implements Runnable {
                Codec codec;
                @Override public void run() { codec.serializeNBT(); }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `override contract is preserved when Override annotation is omitted`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val handler = write("sample/Handler.java", """
            package sample;
            interface Action { void run(); }
            class Handler implements Action {
                Codec codec;
                public void run() { codec.encode(); }
            }
        """.trimIndent())
        val root = write("sample/Root.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Root {
                Handler handler;
                void invoke(HolderLookup.Provider registries) { handler.run(); }
            }
        """.trimIndent())
        val originalHandler = handler.readText()
        val originalRoot = root.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(handler.readText() == originalHandler, handler.readText())
        assertTrue(root.readText() == originalRoot, root.readText())
    }

    @Test
    fun `owned context provider does not remove an unannotated override parameter`() {
        write("sample/Context.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Context { Level level; }
        """.trimIndent())
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void read(Context context, HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Handler.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            interface Action { void read(Context context, HolderLookup.Provider registries); }
            class Handler implements Action {
                Codec codec;
                public void read(Context context, HolderLookup.Provider registries) {
                    codec.read(context, registries);
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `unknown project call argument does not select a sole same arity method`() {
        val file = write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import third.party.External;
            class Codec {
                void encode(String value, HolderLookup.Provider registries) { registries.toString(); }
                String value(External input) { return input.toString(); }
                void invoke(HolderLookup.Provider registries) {
                    encode(value(External.source()));
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `project Function type does not authorize a java functional method reference rewrite`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                String encode(HolderLookup.Provider registries) { return registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Usage.java", """
            package sample;
            interface Function<T, R> {}
            class Usage {
                void register(net.minecraft.core.HolderLookup.Provider registries) {
                    Function<Codec, String> encoder = Codec::encode;
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `functional method reference return type must match exactly enough to rewrite`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                String encode(HolderLookup.Provider registries) { return registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Usage.java", """
            package sample;
            import java.util.function.Function;
            class Usage {
                void register(net.minecraft.core.HolderLookup.Provider registries) {
                    Function<Codec, Integer> encoder = Codec::encode;
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `out of scope same named local is not used as a provider ownership root`() {
        write("sample/Context.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Context { Level level; }
        """.trimIndent())
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void read(Context context, HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Holder.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Holder {
                Context context;
                Codec codec;
                void load(Context other, HolderLookup.Provider registries) {
                    { Context context = other; context.toString(); }
                    codec.read(context, registries);
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `local assignments cannot prove inherited player inventory field equivalence`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Menu.java", """
            package sample;
            import net.minecraft.world.entity.player.Inventory;
            import net.minecraft.world.entity.player.Player;
            class Menu {
                Player player;
                Inventory playerInventory;
                Codec codec;
                void unrelated(Inventory inv) {
                    Player player = null;
                    Inventory playerInventory = null;
                    player = inv.player;
                    playerInventory = inv;
                }
                void run() { codec.encode(); }
            }
        """.trimIndent())
        val original = file.readText()

        assertFailsWith<IllegalStateException> {
            ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        }
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `anonymous method cannot borrow an outer instance field as its exact provider root`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Outer.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Outer {
                Level level;
                Runnable task = new Runnable() {
                    public void run() { new Codec().encode(); }
                };
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `unresolved local value cannot fall back to a same named static receiver type`() {
        write("sample/Target.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Target {
                static void encode(String value, HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Caller.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Caller {
                Object source() { return new Object(); }
                void run(HolderLookup.Provider registries) {
                    var Target = source();
                    Target.encode("value");
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `unresolved local argument cannot fall back to a same named field type`() {
        write("sample/Target.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Target {
                static void encode(String value, HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Caller.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Caller {
                String value;
                Object source() { return new Object(); }
                void run(HolderLookup.Provider registries) {
                    var value = source();
                    Target.encode(value);
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `static context field is not treated as parameter owned provider state`() {
        write("sample/Context.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Context { static Level level; }
        """.trimIndent())
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void read(Context context, HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Holder.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Holder {
                Codec codec;
                void load(Context context, HolderLookup.Provider registries) {
                    codec.read(context, registries);
                }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `recursive generic bound remains unresolved without overflowing`() {
        val file = write("sample/Generic.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Generic<T extends Comparable<T>> {
                T value;
                void encode(T input, HolderLookup.Provider registries) { registries.toString(); }
                void run(HolderLookup.Provider registries) { encode(value); }
            }
        """.trimIndent())
        val original = file.readText()

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertTrue(file.readText() == original, file.readText())
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
