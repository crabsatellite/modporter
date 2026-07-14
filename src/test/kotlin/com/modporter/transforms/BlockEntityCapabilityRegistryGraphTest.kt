package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactBlockEntityRegistryGraph
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockEntityCapabilityRegistryGraphTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `factory method references bind parameterized BlockEntity constructors to every registry field`() {
        write("com/example/ExampleMod.java", """
            package com.example;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            @Mod("example")
            public class ExampleMod {
                public ExampleMod(ModContainer container) {
                    IEventBus modEventBus = container.getEventBus();
                }
            }
        """.trimIndent())
        write("com/example/AllBlockEntityTypes.java", """
            package com.example;
            import com.tterrag.registrate.util.entry.BlockEntityEntry;
            public class AllBlockEntityTypes {
                static Registrate REGISTRATE;
                public static final BlockEntityEntry<TankBlockEntity> PRIMARY =
                    REGISTRATE.blockEntity("primary", TankBlockEntity::new).register();
                public static final BlockEntityEntry<TankBlockEntity> SECONDARY =
                    REGISTRATE.blockEntity("secondary", TankBlockEntity::new).register();
            }
        """.trimIndent())
        val blockEntity = write("com/example/TankBlockEntity.java", """
            package com.example;
            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.fluids.capability.IFluidHandler;
            public class TankBlockEntity extends BlockEntity {
                private final IFluidHandler tank = null;
                private final LazyOptional<IFluidHandler> fluidCapability = LazyOptional.of(() -> tank);
                public TankBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (capability == Capabilities.FluidHandler.BLOCK)
                        return fluidCapability.cast();
                    return super.getCapability(capability, side);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = blockEntity.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "struct-blockentity-capability-provider" })
        assertTrue(
            migrated.contains("com.example.AllBlockEntityTypes.PRIMARY.get(),"),
            migrated
        )
        assertTrue(
            migrated.contains("com.example.AllBlockEntityTypes.SECONDARY.get(),"),
            migrated
        )
        assertTrue(Regex("Capabilities\\.FluidHandler\\.BLOCK").findAll(migrated).count() == 2, migrated)
        assertFalse(migrated.contains("getCapability("), migrated)
    }

    @Test
    fun `registry discovery does not resolve unrelated ambiguous static field types`() {
        write("sample/LayerPattern.java", """
            package sample;
            class LayerPattern {
                static class Builder {}
                static class Layer {
                    static class Builder {}
                }
                static Builder BUILDER;
            }
        """.trimIndent())

        val graph = ExactBlockEntityRegistryGraph.build(tempDir.resolve("src/main/java"))

        assertTrue(graph.referencesFor("sample.MissingBlockEntity").isEmpty())
    }

    @Test
    fun `abstract capability owner registers every exact concrete factory subtype`() {
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
                static final BlockEntityEntry<ConcreteA> A =
                    REGISTRATE.blockEntity("a", ConcreteA::new).register();
                static final BlockEntityEntry<ConcreteB> B =
                    REGISTRATE.blockEntity("b", ConcreteB::new).register();
            }
        """.trimIndent())
        val base = write("sample/AbstractStorageBlockEntity.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.items.IItemHandler;
            abstract class AbstractStorageBlockEntity extends BlockEntity {
                private final IItemHandler handler = null;
                private final LazyOptional<IItemHandler> capability = LazyOptional.of(() -> handler);
                AbstractStorageBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> requested, Direction side) {
                    if (requested == Capabilities.ItemHandler.BLOCK)
                        return capability.cast();
                    return super.getCapability(requested, side);
                }
            }
            class ConcreteA extends AbstractStorageBlockEntity {
                ConcreteA(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
            }
            class ConcreteB extends AbstractStorageBlockEntity {
                ConcreteB(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = base.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(migrated.contains("getCapability("), migrated)
        assertTrue(migrated.contains("sample.AllBlockEntityTypes.A.get(),"), migrated)
        assertTrue(migrated.contains("sample.AllBlockEntityTypes.B.get(),"), migrated)
        assertTrue(migrated.contains("(blockEntity, side) -> blockEntity.handler"), migrated)
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
