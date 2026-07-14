package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyLazyOptionalCapabilityIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `guarded and delegated LazyOptional providers preserve exact control flow`() {
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
                static final BlockEntityEntry<GuardedBlockEntity> GUARDED =
                    REGISTRATE.blockEntity("guarded", GuardedBlockEntity::new).register();
                static final BlockEntityEntry<DelegatedBlockEntity> DELEGATED =
                    REGISTRATE.blockEntity("delegated", DelegatedBlockEntity::new).register();
            }
        """.trimIndent())
        write("sample/BaseBlockEntity.java", """
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
                boolean isItemHandlerCap(Capability<?> capability) {
                    return capability == Capabilities.ItemHandler.BLOCK;
                }
            }
        """.trimIndent())
        val guarded = write("sample/GuardedBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.items.IItemHandler;
            class GuardedBlockEntity extends BaseBlockEntity {
                private LazyOptional<IItemHandler> itemHandler = LazyOptional.empty();
                GuardedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                boolean ready() { return true; }
                void initializeItemHandler() { itemHandler = LazyOptional.of(() -> null); }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (!isItemHandlerCap(capability))
                        return super.getCapability(capability, side);
                    if (!ready())
                        return super.getCapability(capability, side);
                    if (!itemHandler.isPresent())
                        initializeItemHandler();
                    return itemHandler.cast();
                }
            }
        """.trimIndent())
        write("sample/Provider.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.Direction;
            import net.neoforged.neoforge.items.IItemHandler;
            class Provider {
                LazyOptional<IItemHandler> value = LazyOptional.empty();
                <T> LazyOptional<T> get(Capability<T> ignored, Direction side) {
                    return value.cast();
                }
            }
        """.trimIndent())
        val delegated = write("sample/DelegatedBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            class DelegatedBlockEntity extends BaseBlockEntity {
                private final Provider provider = new Provider();
                DelegatedBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (isItemHandlerCap(capability))
                        return provider.get(capability, side);
                    return super.getCapability(capability, side);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedGuarded = guarded.readText()
        val migratedDelegated = delegated.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(migratedGuarded.contains("getCapability("), migratedGuarded)
        assertTrue(migratedGuarded.contains("if (!blockEntity.ready())"), migratedGuarded)
        assertTrue(migratedGuarded.contains("blockEntity.initializeItemHandler();"), migratedGuarded)
        assertTrue(migratedGuarded.contains("return blockEntity.itemHandler.orElse(null);"), migratedGuarded)
        assertFalse(migratedDelegated.contains("getCapability("), migratedDelegated)
        assertTrue(
            migratedDelegated.contains("blockEntity.provider.get(null, side).orElse(null)"),
            migratedDelegated
        )
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
