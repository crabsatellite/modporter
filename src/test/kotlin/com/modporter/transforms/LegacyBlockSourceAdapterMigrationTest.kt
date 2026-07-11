package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyBlockSourceAdapterMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact legacy adapter is inlined into target record and removed`() {
        val adapter = writeAdapter()
        val caller = writeCaller("new MovingBlockSource(context, pos, facing)")

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = caller.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(adapter.exists())
        assertFalse(migrated.contains("MovingBlockSource"), migrated)
        assertTrue(migrated.contains("MinecraftServer blockSourceServer = context.world.getServer();"), migrated)
        assertTrue(migrated.contains("BlockState blockSourceState = context.state;"), migrated)
        assertTrue(migrated.contains("blockSourceState.hasProperty(BlockStateProperties.FACING)"), migrated)
        assertTrue(migrated.contains("new BlockSource(blockSourceLevel, pos, blockSourceState, null)"), migrated)
    }

    @Test
    fun `complex constructor arguments hard fail without deleting adapter`() {
        val adapter = writeAdapter()
        val caller = writeCaller("new MovingBlockSource(context(), pos, facing)")
        val adapterOriginal = adapter.readText()
        val callerOriginal = caller.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("named arguments") }, result.errors.joinToString("\n"))
        assertTrue(adapter.readText() == adapterOriginal, adapter.readText())
        assertTrue(caller.readText() == callerOriginal, caller.readText())
    }

    @Test
    fun `additional adapter behavior hard fails without deleting source`() {
        val adapter = writeAdapter()
        adapter.writeText(adapter.readText().replace(
            "public BlockPos getPos() { return pos; }",
            "public BlockPos getPos() { if (pos == null) return null; return pos; }"
        ))
        val caller = writeCaller("new MovingBlockSource(context, pos, facing)")
        val adapterOriginal = adapter.readText()
        val callerOriginal = caller.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("method bodies contain unsupported semantics") }, result.errors.joinToString("\n"))
        assertTrue(adapter.readText() == adapterOriginal, adapter.readText())
        assertTrue(caller.readText() == callerOriginal, caller.readText())
    }

    private fun writeAdapter(): Path {
        val dir = tempDir.resolve("src/main/java/sample/adapter")
        dir.createDirectories()
        val file = dir.resolve("MovingBlockSource.java")
        file.writeText("""
            package sample.adapter;
            import sample.motion.MotionContext;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.dispenser.BlockSource;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.block.state.properties.BlockStateProperties;
            public class MovingBlockSource implements BlockSource {
                private final BlockPos pos;
                private final MotionContext context;
                private final Direction facing;
                public MovingBlockSource(MotionContext context, BlockPos pos) { this(context, pos, null); }
                public MovingBlockSource(MotionContext context, BlockPos pos, Direction facing) {
                    this.pos = pos;
                    this.context = context;
                    this.facing = facing;
                }
                public double x() { return (double)this.pos.getX() + 0.5D; }
                public double y() { return (double)this.pos.getY() + 0.5D; }
                public double z() { return (double)this.pos.getZ() + 0.5D; }
                public BlockPos getPos() { return pos; }
                public BlockState getBlockState() {
                    if (context.state.hasProperty(BlockStateProperties.FACING) && facing != null)
                        return context.state.setValue(BlockStateProperties.FACING, facing);
                    return context.state;
                }
                public <T extends BlockEntity> T getEntity() { return null; }
                public ServerLevel getLevel() {
                    MinecraftServer server = context.world.getServer();
                    return server != null ? server.getLevel(context.world.dimension()) : null;
                }
            }
        """.trimIndent())
        return file
    }

    private fun writeCaller(construction: String): Path {
        val dir = tempDir.resolve("src/main/java/sample/use")
        dir.createDirectories()
        val file = dir.resolve("Runner.java")
        file.writeText("""
            package sample.use;
            import sample.adapter.MovingBlockSource;
            import sample.motion.MotionContext;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.core.dispenser.BlockSource;
            final class Runner {
                void run(MotionContext context, BlockPos pos, Direction facing) {
                    BlockSource source = $construction;
                    consume(source);
                }
                MotionContext context() { return null; }
                void consume(BlockSource source) {}
            }
        """.trimIndent())
        return file
    }
}
