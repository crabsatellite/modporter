package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyBlockValidSpawnMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `five parameter spawn override becomes a properties predicate`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("NoSpawnBlock.java")
        file.writeText("""
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.SpawnPlacements.Type;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class NoSpawnBlock extends Block {
                NoSpawnBlock(Properties properties) {
                    super(properties);
                }
                @Override
                public boolean isValidSpawn(BlockState state, BlockGetter level, BlockPos pos, Type type,
                                            EntityType<?> entityType) {
                    return false;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("super(properties.isValidSpawn((state, level, pos, entityType) -> false));"),
            migrated
        )
        assertFalse(migrated.contains("boolean isValidSpawn("), migrated)
        assertFalse(migrated.contains("SpawnPlacements.Type"), migrated)
    }

    @Test
    fun `removed spawn placement type dependencies hard fail`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("TypeSensitiveBlock.java")
        file.writeText("""
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.SpawnPlacements.Type;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            class TypeSensitiveBlock extends Block {
                TypeSensitiveBlock(Properties properties) {
                    super(properties);
                }
                @Override
                public boolean isValidSpawn(BlockState state, BlockGetter level, BlockPos pos, Type type,
                                            EntityType<?> entityType) {
                    return type != null;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.any { it.contains("state unavailable before super") }, result.errors.joinToString("\n"))
        assertTrue(migrated.contains("return type != null;"), migrated)
        assertFalse(migrated.contains("properties.isValidSpawn"), migrated)
    }

    @Test
    fun `project Block names do not satisfy Minecraft owner proof`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("CustomBlock.java")
        file.writeText("""
            package example;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.state.BlockState;
            class Block {
                Block(Object properties) {}
            }
            class CustomBlock extends Block {
                CustomBlock(Object properties) {
                    super(properties);
                }
                @Override
                public boolean isValidSpawn(BlockState state, BlockGetter level, BlockPos pos,
                                            EntityType<?> entityType) {
                    return false;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.any { it.contains("not proven to directly extend Minecraft Block") })
        assertTrue(migrated.contains("boolean isValidSpawn("), migrated)
        assertFalse(migrated.contains("properties.isValidSpawn"), migrated)
    }
}
