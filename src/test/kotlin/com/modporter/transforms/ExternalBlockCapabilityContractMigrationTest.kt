package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.ExactLegacyBlockCapabilityGraph
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalBlockCapabilityContractMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `external API capability token closes through predicate overrides and BlockEntity registration`() {
        write("sample/ExampleMod.java", """
            package sample;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            @Mod("sample")
            public class ExampleMod {
                public ExampleMod(ModContainer container) {
                    IEventBus modEventBus = container.getEventBus();
                }
            }
        """.trimIndent())
        write("sample/AllBlockEntityTypes.java", """
            package sample;
            import com.tterrag.registrate.util.entry.BlockEntityEntry;
            class AllBlockEntityTypes {
                static Registrate REGISTRATE;
                static final BlockEntityEntry<PeripheralBlockEntity> PERIPHERAL =
                    REGISTRATE.blockEntity("peripheral", PeripheralBlockEntity::new).register();
            }
        """.trimIndent())
        val base = write("sample/BaseBridge.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            class BaseBridge {
                public <T> boolean isPeripheral(Capability<T> capability) {
                    return false;
                }
                public <T> LazyOptional<T> peripheral() {
                    return LazyOptional.empty();
                }
            }
        """.trimIndent())
        val bridge = write("sample/PeripheralBridge.java", """
            package sample;
            import net.neoforged.neoforge.capabilities.Capability;
            import net.neoforged.neoforge.capabilities.CapabilityManager;
            import net.neoforged.neoforge.capabilities.CapabilityToken;
            import com.modporter.generated.sample.compat.LazyOptional;
            import dan200.computercraft.api.peripheral.IPeripheral;
            class PeripheralBridge extends BaseBridge {
                static final Capability<IPeripheral> PERIPHERAL =
                    CapabilityManager.get(new CapabilityToken<>() {});
                @Override
                public <T> boolean isPeripheral(Capability<T> capability) {
                    return capability == PERIPHERAL;
                }
                @Override
                public <T> LazyOptional<T> peripheral() {
                    return LazyOptional.empty();
                }
            }
        """.trimIndent())
        val blockEntity = write("sample/PeripheralBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            class PeripheralBlockEntity extends BlockEntity {
                private final BaseBridge bridge = new PeripheralBridge();
                PeripheralBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (bridge.isPeripheral(capability))
                        return bridge.peripheral();
                    return super.getCapability(capability, side);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedBase = base.readText()
        val migratedBridge = bridge.readText()
        val migratedBlockEntity = blockEntity.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migratedBridge.contains(
                "net.neoforged.neoforge.capabilities.BlockCapability<dan200.computercraft.api.peripheral.IPeripheral, net.minecraft.core.Direction> PERIPHERAL"
            ),
            migratedBridge
        )
        assertTrue(
            migratedBridge.contains("dan200.computercraft.api.peripheral.PeripheralCapability.get()"),
            migratedBridge
        )
        assertTrue(migratedBase.contains("BlockCapability<T, net.minecraft.core.Direction> capability"), migratedBase)
        assertTrue(migratedBridge.contains("BlockCapability<T, net.minecraft.core.Direction> capability"), migratedBridge)
        assertFalse(migratedBridge.contains("CapabilityManager"), migratedBridge)
        assertFalse(migratedBridge.contains("CapabilityToken"), migratedBridge)
        assertFalse(migratedBlockEntity.contains("getCapability("), migratedBlockEntity)
        assertTrue(
            migratedBlockEntity.contains("dan200.computercraft.api.peripheral.PeripheralCapability.get(),"),
            migratedBlockEntity
        )
        assertTrue(
            migratedBlockEntity.contains(
                "blockEntity.bridge.<dan200.computercraft.api.peripheral.IPeripheral>peripheral().orElse(null)"
            ),
            migratedBlockEntity
        )
    }

    @Test
    fun `same named predicate on an unrelated receiver remains a hard gate`() {
        write("sample/ExampleMod.java", """
            package sample;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            @Mod("sample")
            public class ExampleMod {
                public ExampleMod(ModContainer container) {
                    IEventBus modEventBus = container.getEventBus();
                }
            }
        """.trimIndent())
        write("sample/AllBlockEntityTypes.java", """
            package sample;
            import com.tterrag.registrate.util.entry.BlockEntityEntry;
            class AllBlockEntityTypes {
                static Registrate REGISTRATE;
                static final BlockEntityEntry<UnrelatedBlockEntity> UNRELATED =
                    REGISTRATE.blockEntity("unrelated", UnrelatedBlockEntity::new).register();
            }
        """.trimIndent())
        write("sample/PeripheralBridge.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.CapabilityManager;
            import com.modporter.generated.sample.compat.CapabilityToken;
            import com.modporter.generated.sample.compat.LazyOptional;
            import dan200.computercraft.api.peripheral.IPeripheral;
            class PeripheralBridge {
                static final Capability<IPeripheral> PERIPHERAL =
                    CapabilityManager.get(new CapabilityToken<>() {});
                boolean isPeripheral(Capability<?> capability) {
                    return capability == PERIPHERAL;
                }
                <T> LazyOptional<T> peripheral() {
                    return LazyOptional.empty();
                }
            }
        """.trimIndent())
        write("sample/UnrelatedBridge.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            class UnrelatedBridge {
                boolean isPeripheral(Capability<?> capability) {
                    return capability != null;
                }
                <T> LazyOptional<T> peripheral() {
                    return LazyOptional.empty();
                }
            }
        """.trimIndent())
        val blockEntity = write("sample/UnrelatedBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            class UnrelatedBlockEntity extends BlockEntity {
                private final PeripheralBridge peripheralBridge = new PeripheralBridge();
                private final UnrelatedBridge bridge = new UnrelatedBridge();
                UnrelatedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (peripheralBridge.isPeripheral(capability))
                        return peripheralBridge.peripheral();
                    if (bridge.isPeripheral(capability))
                        return bridge.peripheral();
                    return super.getCapability(capability, side);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = blockEntity.readText()

        assertTrue(result.errors.any { it.contains("unproven predicate branches") }, result.errors.joinToString("\n"))
        assertTrue(migrated.contains("getCapability("), migrated)
        assertFalse(migrated.contains("registerCapabilities("), migrated)
    }

    @Test
    fun `capability discovery does not resolve unrelated ambiguous nested types`() {
        write("sample/LayerPattern.java", """
            package sample;
            class LayerPattern {
                static class Builder {}
                static class Layer {
                    static class Builder {}
                }
                static Builder builder() {
                    return new Builder();
                }
                boolean accepts(Builder builder) {
                    return true;
                }
            }
        """.trimIndent())

        val changes = ExactLegacyBlockCapabilityGraph.build(tempDir.resolve("src/main/java"))
            .migrateDeclarationsAndPredicates(dryRun = false)

        assertTrue(changes.isEmpty(), changes.joinToString("\n"))
    }

    @Test
    fun `built in capability predicate family supplies exact registration target`() {
        write("sample/ExampleMod.java", """
            package sample;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            @Mod("sample")
            public class ExampleMod {
                public ExampleMod(ModContainer container) {
                    IEventBus modEventBus = container.getEventBus();
                }
            }
        """.trimIndent())
        write("sample/AllBlockEntityTypes.java", """
            package sample;
            import com.tterrag.registrate.util.entry.BlockEntityEntry;
            class AllBlockEntityTypes {
                static Registrate REGISTRATE;
                static final BlockEntityEntry<InventoryBlockEntity> INVENTORY =
                    REGISTRATE.blockEntity("inventory", InventoryBlockEntity::new).register();
            }
        """.trimIndent())
        val base = write("sample/BaseBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.Capabilities;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            class BaseBlockEntity extends BlockEntity {
                BaseBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                protected boolean isItemHandlerCap(Capability<?> capability) {
                    return capability == Capabilities.ItemHandler.BLOCK;
                }
            }
        """.trimIndent())
        val blockEntity = write("sample/InventoryBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.items.IItemHandler;
            class InventoryBlockEntity extends BaseBlockEntity {
                private final IItemHandler handler = null;
                private final LazyOptional<IItemHandler> itemCapability = LazyOptional.of(() -> handler);
                InventoryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (isItemHandlerCap(capability) && side != Direction.UP)
                        return itemCapability.cast();
                    return super.getCapability(capability, side);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedBase = base.readText()
        val migrated = blockEntity.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migratedBase.contains("BlockCapability<?, net.minecraft.core.Direction> capability"), migratedBase)
        assertFalse(migrated.contains("getCapability("), migrated)
        assertTrue(migrated.contains("Capabilities.ItemHandler.BLOCK,"), migrated)
        assertTrue(
            migrated.contains("import net.neoforged.neoforge.capabilities.Capabilities;"),
            migrated
        )
        assertTrue(migrated.contains("side != Direction.UP"), migrated)
    }

    @Test
    fun `built in capability guard binds exact BlockEntity instance state in static registration`() {
        write("sample/ExampleMod.java", """
            package sample;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            @Mod("sample")
            public class ExampleMod {
                public ExampleMod(ModContainer container) {
                    IEventBus modEventBus = container.getEventBus();
                }
            }
        """.trimIndent())
        write("sample/AllBlockEntityTypes.java", """
            package sample;
            import com.tterrag.registrate.util.entry.BlockEntityEntry;
            class AllBlockEntityTypes {
                static Registrate REGISTRATE;
                static final BlockEntityEntry<InventoryBlockEntity> INVENTORY =
                    REGISTRATE.blockEntity("inventory", InventoryBlockEntity::new).register();
            }
        """.trimIndent())
        val blockEntity = write("sample/InventoryBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.Capabilities;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.items.IItemHandler;
            class /* declaration comments must not hide the owner */ InventoryBlockEntity
                extends /* inheritance comments must not hide the base */ BlockEntity {
                private final IItemHandler handler = null;
                private final LazyOptional<IItemHandler> itemCapability = LazyOptional.of(() -> handler);
                InventoryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                static boolean exposes(Level level, BlockPos pos, BlockState state, Direction side) {
                    return true;
                }
                boolean isItemHandlerCap(Capability<?> capability) {
                    return capability == Capabilities.ItemHandler.BLOCK;
                }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction direction) {
                    if (isItemHandlerCap(capability) &&
                        (direction == null || exposes(level, worldPosition, getBlockState(), direction)))
                        return itemCapability.cast();
                    return super.getCapability(capability, direction);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = blockEntity.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains(
                "side == null || exposes(blockEntity.level, blockEntity.worldPosition, blockEntity.getBlockState(), side)"
            ),
            migrated
        )
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
