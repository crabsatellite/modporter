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
    fun `lambda overload selection rejects a project interface with multiple abstract methods`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        write("sample/Value.java", """
            package sample;
            class Value { Codec codec; }
        """.trimIndent())
        write("sample/Result.java", """
            package sample;
            class Result {}
        """.trimIndent())
        write("sample/NonFunctional.java", """
            package sample;
            interface NonFunctional {
                Result create();
                void inspect(Value value);
            }
        """.trimIndent())
        write("sample/Registry.java", """
            package sample;
            import java.util.function.Function;
            class Registry {
                void create(String id, NonFunctional value) {}
                void create(String id, Function<Value, Result> factory) {}
            }
        """.trimIndent())
        val file = write("sample/Usage.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Usage {
                Registry registry;
                void run(HolderLookup.Provider registries) {
                    registry.create("value", value -> {
                        value.codec.encode();
                        return new Result();
                    });
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
    fun `project Entity subtype parameter supplies its exact registry provider`() {
        write("sample/VehicleEntity.java", """
            package sample;
            import net.minecraft.world.entity.Entity;
            abstract class VehicleEntity extends Entity {}
        """.trimIndent())
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Writer.java", """
            package sample;
            class Writer {
                Codec codec;
                void write(VehicleEntity entity) {
                    codec.encode();
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("codec.encode(entity.registryAccess())"), migrated)
    }

    @Test
    fun `unique provider field on an exact parameter supplies the registry provider`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        write("sample/Context.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Context { Level world; }
        """.trimIndent())
        val file = write("sample/Handler.java", """
            package sample;
            class Handler {
                Codec codec;
                void run(Context context) { codec.decode(); }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("void run(Context context)"), migrated)
        assertTrue(migrated.contains("codec.decode(context.world.registryAccess())"), migrated)
    }

    @Test
    fun `unique visible level local supplies the registry provider`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Handler.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Handler {
                Codec codec;
                Level level() { return null; }
                void run() {
                    Level level = level();
                    codec.decode();
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("void run()"), migrated)
        assertTrue(migrated.contains("codec.decode(level.registryAccess())"), migrated)
    }

    @Test
    fun `project subtype of a Ponder scene supplies its explicit world provider`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        write("sample/Scene.java", """
            package sample;
            import net.createmod.ponder.foundation.PonderSceneBuilder;
            class Scene extends PonderSceneBuilder {}
        """.trimIndent())
        val file = write("sample/Scenes.java", """
            package sample;
            class Scenes {
                Codec codec;
                void build() {
                    Scene scene = new Scene();
                    codec.encode();
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("codec.encode(scene.world().getHolderLookupProvider())"), migrated)
        assertTrue(migrated.contains("void build()"), migrated)
    }

    @Test
    fun `exact Ponder scene callback uses its declared builder provider root`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void encode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Scenes.java", """
            package sample;
            import net.createmod.ponder.api.scene.SceneBuilder;
            import net.createmod.ponder.api.scene.SceneBuildingUtil;
            class Scenes {
                static Codec codec;
                public static void build(SceneBuilder builder, SceneBuildingUtil util) {
                    codec.encode();
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("codec.encode(builder.world().getHolderLookupProvider())"), migrated)
        assertTrue(!migrated.contains("build(SceneBuilder builder, SceneBuildingUtil util,"), migrated)
    }

    @Test
    fun `visible local alias collapses an equivalent parameter field provider source`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        write("sample/Context.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Context { Level world; }
        """.trimIndent())
        val file = write("sample/Handler.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Handler {
                Codec codec;
                void run(Context context) {
                    Level world = context.world;
                    codec.decode();
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("codec.decode(world.registryAccess())"), migrated)
    }

    @Test
    fun `multiple visible level locals hard gate instead of choosing the nearest`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Handler.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Handler {
                Codec codec;
                Level level() { return null; }
                void run() {
                    Level first = level();
                    Level second = level();
                    codec.decode();
                }
            }
        """.trimIndent())
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("Ambiguous exact HolderLookup.Provider sources"), error.message)
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `nullable typed parameter is excluded while a non nullable level supplies the provider`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Reader.java", """
            package sample;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            class Reader {
                Codec codec;
                void read(Level level, Player player) {
                    codec.decode();
                    if (player != null) player.getUUID();
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("codec.decode(level.registryAccess())"), migrated)
        assertTrue(!migrated.contains("player.registryAccess()"), migrated)
    }

    @Test
    fun `nullable typed parameter supplies a provider only inside a dominating non null branch`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val file = write("sample/Reader.java", """
            package sample;
            import net.minecraft.world.entity.player.Player;
            class Reader {
                Codec codec;
                void read(Player player) {
                    if (player != null) {
                        codec.decode();
                    }
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("codec.decode(player.registryAccess())"), migrated)
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
    fun `parameter owned provider discharge removes exact registry accessor call arguments`() {
        write("sample/Context.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            class Context {
                Level world;
                CompoundTag data;
            }
        """.trimIndent())
        val filter = write("sample/Filters.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.world.item.ItemStack;
            class Filters {
                static ItemStack get(Context context, HolderLookup.Provider registries) {
                    return ItemStack.parseOptional(registries, context.data);
                }
            }
        """.trimIndent())
        val caller = write("sample/Caller.java", """
            package sample;
            import net.minecraft.world.level.Level;
            class Caller {
                Object read(Context context, Level level) {
                    return Filters.get(context, level.registryAccess());
                }
            }
        """.trimIndent())

        ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migratedFilter = filter.readText()
        val migratedCaller = caller.readText()

        assertTrue(migratedFilter.contains("static ItemStack get(Context context)"), migratedFilter)
        assertTrue(
            migratedFilter.contains("ItemStack.parseOptional(context.world.registryAccess(), context.data)"),
            migratedFilter
        )
        assertTrue(migratedCaller.contains("Filters.get(context);"), migratedCaller)
    }

    @Test
    fun `parameter owned provider discharges to a unique declared level source`() {
        val target = write("sample/SnapshotStore.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class SnapshotStore {
                CompoundTag capture(Level level, BlockEntity blockEntity, HolderLookup.Provider registries) {
                    return blockEntity.getUpdateTag(registries);
                }
            }
        """.trimIndent())
        val caller = write("sample/SnapshotCaller.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class SnapshotCaller {
                CompoundTag capture(SnapshotStore store, Level level, BlockEntity blockEntity) {
                    return store.capture(level, blockEntity, level.registryAccess());
                }
            }
        """.trimIndent())

        ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        val migratedTarget = target.readText()
        val migratedCaller = caller.readText()
        assertTrue(migratedTarget.contains("capture(Level level, BlockEntity blockEntity)"), migratedTarget)
        assertTrue(migratedTarget.contains("blockEntity.getUpdateTag(level.registryAccess())"), migratedTarget)
        assertTrue(migratedCaller.contains("store.capture(level, blockEntity)"), migratedCaller)
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
    fun `provider demand without an exact source hard gates atomically`() {
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

        val error = assertFailsWith<IllegalStateException> {
            ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("do not reach a declared provider boundary"), error.message)
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

        val error = assertFailsWith<IllegalStateException> {
            ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("Unseeded exact provider demands"), error.message)
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `provider demand seeds a complete Entity helper override family from exact callback boundaries`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void writeAdditional(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val base = write("sample/BaseEntity.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.world.entity.Entity;
            import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
            abstract class BaseEntity extends Entity implements IEntityWithComplexSpawn {
                Codec codec;
                protected void writeAdditional(CompoundTag tag, boolean spawnPacket) {
                    codec.writeAdditional();
                }
                protected void addAdditionalSaveData(CompoundTag tag) {
                    writeAdditional(tag, false);
                }
                public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
                    writeAdditional(new CompoundTag(), true);
                }
            }
        """.trimIndent())
        val child = write("sample/ChildEntity.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            abstract class ChildEntity extends BaseEntity {
                @Override
                protected void writeAdditional(CompoundTag tag, boolean spawnPacket) {
                    super.writeAdditional(tag, spawnPacket);
                }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migratedBase = base.readText()
        val migratedChild = child.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migratedBase.contains("writeAdditional(CompoundTag tag, boolean spawnPacket, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedBase)
        assertTrue(migratedBase.contains("codec.writeAdditional(modporterRegistries)"), migratedBase)
        assertTrue(migratedBase.contains("writeAdditional(tag, false, this.registryAccess())"), migratedBase)
        assertTrue(migratedBase.contains("writeAdditional(new CompoundTag(), true, buffer.registryAccess())"), migratedBase)
        assertTrue(migratedChild.contains("writeAdditional(CompoundTag tag, boolean spawnPacket, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migratedChild)
        assertTrue(migratedChild.contains("super.writeAdditional(tag, spawnPacket, modporterRegistries)"), migratedChild)
    }

    @Test
    fun `unannotated project override family migrates atomically from an exact boundary`() {
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
        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isNotEmpty())
        assertTrue(handler.readText().contains("public void run(net.minecraft.core.HolderLookup.Provider modporterRegistries)"), handler.readText())
        assertTrue(handler.readText().contains("codec.encode(modporterRegistries)"), handler.readText())
        assertTrue(root.readText().contains("handler.run(registries)"), root.readText())
    }

    @Test
    fun `public abstract project api family becomes an explicit provider boundary`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val api = write("sample/Input.java", """
            package sample;
            interface Input { public abstract void setData(String data); }
        """.trimIndent())
        val implementation = write("sample/Entry.java", """
            package sample;
            class Entry implements Input {
                Codec codec;
                public void setData(String data) { codec.decode(); }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isNotEmpty())
        assertTrue(api.readText().contains("setData(String data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), api.readText())
        assertTrue(implementation.readText().contains("setData(String data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), implementation.readText())
        assertTrue(implementation.readText().contains("codec.decode(modporterRegistries)"), implementation.readText())
    }

    @Test
    fun `public concrete project api becomes an explicit provider boundary`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val api = write("sample/Parser.java", """
            package sample;
            class Parser {
                public static void read(Codec codec, String data) { codec.decode(); }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = api.readText()

        assertTrue(changes.isNotEmpty())
        assertTrue(migrated.contains("read(Codec codec, String data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), migrated)
        assertTrue(migrated.contains("codec.decode(modporterRegistries)"), migrated)
    }

    @Test
    fun `provider demand reaches an abstract project api through nested override families`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Codec {
                void decode(HolderLookup.Provider registries) { registries.toString(); }
            }
        """.trimIndent())
        val api = write("sample/Input.java", """
            package sample;
            interface Input { public abstract void setData(String data); }
        """.trimIndent())
        val base = write("sample/BaseEntry.java", """
            package sample;
            abstract class BaseEntry implements Input {
                public void setData(String data) { readAdditional(data); }
                protected void readAdditional(String data) {}
            }
        """.trimIndent())
        val child = write("sample/ChildEntry.java", """
            package sample;
            class ChildEntry extends BaseEntry {
                Codec codec;
                @Override protected void readAdditional(String data) { codec.decode(); }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isNotEmpty())
        assertTrue(api.readText().contains("setData(String data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), api.readText())
        assertTrue(base.readText().contains("setData(String data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), base.readText())
        assertTrue(base.readText().contains("readAdditional(data, modporterRegistries)"), base.readText())
        assertTrue(base.readText().contains("readAdditional(String data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), base.readText())
        assertTrue(child.readText().contains("readAdditional(String data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), child.readText())
        assertTrue(child.readText().contains("codec.decode(modporterRegistries)"), child.readText())
    }

    @Test
    fun `override family provider demand crosses an exact project helper before reaching a root`() {
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
        val dispatcher = write("sample/Dispatcher.java", """
            package sample;
            class Dispatcher {
                Handler handler;
                void dispatch() { handler.run(); }
            }
        """.trimIndent())
        val root = write("sample/Root.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            class Root {
                Dispatcher dispatcher;
                void invoke(HolderLookup.Provider registries) { dispatcher.dispatch(); }
            }
        """.trimIndent())

        val changes = ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isNotEmpty())
        assertTrue(handler.readText().contains("public void run(net.minecraft.core.HolderLookup.Provider modporterRegistries)"), handler.readText())
        assertTrue(handler.readText().contains("codec.encode(modporterRegistries)"), handler.readText())
        assertTrue(dispatcher.readText().contains("void dispatch(net.minecraft.core.HolderLookup.Provider modporterRegistries)"), dispatcher.readText())
        assertTrue(dispatcher.readText().contains("handler.run(modporterRegistries)"), dispatcher.readText())
        assertTrue(root.readText().contains("dispatcher.dispatch(registries)"), root.readText())
    }

    @Test
    fun `override family provider demand without a reachable root hard gates atomically`() {
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
        val dispatcher = write("sample/Dispatcher.java", """
            package sample;
            class Dispatcher {
                Handler handler;
                void dispatch() { handler.run(); }
            }
        """.trimIndent())
        val originalHandler = handler.readText()
        val originalDispatcher = dispatcher.readText()

        val error = assertFailsWith<IllegalStateException> {
            ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("do not reach a declared provider boundary"), error.message)
        assertTrue(handler.readText() == originalHandler, handler.readText())
        assertTrue(dispatcher.readText() == originalDispatcher, dispatcher.readText())
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

    @Test
    fun `pattern receivers are resolved only on Java true flow and exiting guard paths`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Codec {
                boolean decode(CompoundTag tag, HolderLookup.Provider registries) { return true; }
            }
        """.trimIndent())
        val file = write("sample/Caller.java", """
            package sample;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            class Caller {
                boolean conditional(Level level, Object value, CompoundTag tag) {
                    return value instanceof Codec codec && codec.decode(tag);
                }
                boolean guarded(Level level, Object value, CompoundTag tag) {
                    if (!(value instanceof Codec codec))
                        return false;
                    return codec.decode(tag);
                }
            }
        """.trimIndent())

        ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("codec.decode(tag, level.registryAccess())"), migrated)
        assertTrue(Regex("codec\\.decode\\(tag, level\\.registryAccess\\(\\)\\)").findAll(migrated).count() == 2, migrated)
    }

    @Test
    fun `Minecraft singleton local supplies a provider only after an exact non null level guard`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Codec {
                void decode(CompoundTag tag, HolderLookup.Provider registries) {}
            }
        """.trimIndent())
        val file = write("sample/Caller.java", """
            package sample;
            import net.minecraft.client.Minecraft;
            import net.minecraft.nbt.CompoundTag;
            class Caller {
                static void run(Codec codec, CompoundTag tag) {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.level == null) return;
                    codec.decode(tag);
                }
            }
        """.trimIndent())

        ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("codec.decode(tag, minecraft.level.registryAccess());"), migrated)
    }

    @Test
    fun `earlier Minecraft level dereference is not provider evidence at a later call`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Codec {
                void decode(CompoundTag tag, HolderLookup.Provider registries) {}
            }
        """.trimIndent())
        val file = write("sample/Caller.java", """
            package sample;
            import net.minecraft.client.Minecraft;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            public class Caller {
                public static void run(Codec codec, CompoundTag tag) {
                    Minecraft.getInstance().level.getGameTime();
                    codec.decode(tag);
                }
            }
        """.trimIndent())

        ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(
            migrated.contains("HolderLookup.Provider modporterRegistries)"),
            migrated
        )
        assertTrue(migrated.contains("codec.decode(tag, modporterRegistries);"), migrated)
        assertTrue(!migrated.contains("Minecraft.getInstance().level.registryAccess()"), migrated)
    }

    @Test
    fun `exact Block placement callback owns its Level registry provider`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Codec {
                void decode(CompoundTag tag, HolderLookup.Provider registries) {}
            }
        """.trimIndent())
        val file = write("sample/PlacementBlock.java", """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class PlacementBlock extends Block {
                Codec codec;
                @Override
                public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
                    codec.decode(new CompoundTag());
                }
            }
        """.trimIndent())

        ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("codec.decode(new CompoundTag(), level.registryAccess());"), migrated)
        assertTrue(!migrated.contains("HolderLookup.Provider modporterRegistries"), migrated)
    }

    @Test
    fun `same shaped non Block method does not receive the external callback contract`() {
        write("sample/Codec.java", """
            package sample;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            class Codec {
                void decode(CompoundTag tag, HolderLookup.Provider registries) {}
            }
        """.trimIndent())
        val file = write("sample/Unrelated.java", """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            class Unrelated {
                Codec codec;
                void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
                    codec.decode(new CompoundTag());
                }
            }
        """.trimIndent())
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            ExactProjectProviderCallMigration().migrate(tempDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("Ambiguous exact HolderLookup.Provider"), error.message)
        assertTrue(file.readText() == original, file.readText())
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
