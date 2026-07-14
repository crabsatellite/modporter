package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactBlockEntityCapabilityQueryMigration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactBlockEntityCapabilityQueryMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `migrates stable explicit and implicit BlockEntity queries without changing item capabilities`() {
        val file = write("sample/Queries.java", """
            package sample;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.Direction;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.items.IItemHandler;
            class Queries extends BlockEntity {
                LazyOptional<IItemHandler> explicit(BlockEntity target, Direction side) {
                    return target.getCapability(Capabilities.ItemHandler.BLOCK, side);
                }
                LazyOptional<IItemHandler> implicit() {
                    return getCapability(Capabilities.ItemHandler.BLOCK);
                }
                Object item(ItemStack stack) {
                    return stack.getCapability(Capabilities.ItemHandler.ITEM);
                }
            }
        """.trimIndent())

        val changes = ExactBlockEntityCapabilityQueryMigration(tempDir.resolve("src/main/java"))
            .migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.size == 1, changes.joinToString("\n"))
        assertTrue(
            migrated.contains(
                "LazyOptional.ofNullable(java.util.Optional.ofNullable(target.getLevel()).map( modporterLevel -> modporterLevel.getCapability(Capabilities.ItemHandler.BLOCK, target.getBlockPos(), side)).orElse(null))"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "LazyOptional.ofNullable(java.util.Optional.ofNullable(this.getLevel()).map( modporterLevel -> modporterLevel.getCapability(Capabilities.ItemHandler.BLOCK, this.getBlockPos(), null)).orElse(null))"
            ),
            migrated
        )
        assertTrue(migrated.contains("stack.getCapability(Capabilities.ItemHandler.ITEM)"), migrated)
    }

    @Test
    fun `hard gates BlockEntity query receivers that would be evaluated twice`() {
        val file = write("sample/Queries.java", """
            package sample;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class Queries {
                BlockEntity lookup() { return null; }
                Object query() {
                    return lookup().getCapability(Capabilities.ItemHandler.BLOCK);
                }
            }
        """.trimIndent())
        val original = file.readText()

        assertFailsWith<IllegalStateException> {
            ExactBlockEntityCapabilityQueryMigration(tempDir.resolve("src/main/java"))
                .migrate(dryRun = false)
        }
        assertEquals(original, file.readText())
    }

    @Test
    fun `null preserving level lookup uses a collision free lambda name`() {
        val file = write("sample/Collision.java", """
            package sample;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class Collision {
                Object query(BlockEntity target) {
                    int modporterLevel = 1;
                    return target.getCapability(Capabilities.ItemHandler.BLOCK);
                }
            }
        """.trimIndent())

        ExactBlockEntityCapabilityQueryMigration(tempDir.resolve("src/main/java"))
            .migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("map( modporterLevel1 -> modporterLevel1.getCapability("), migrated)
    }

    @Test
    fun `migrates direct legacy invalidation to BlockEntity capability invalidation without an adapter`() {
        val file = write("sample/Invalidation.java", """
            package sample;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class Invalidation {
                void reset(BlockEntity target) {
                    target.getCapability(Capabilities.ItemHandler.BLOCK).invalidate();
                    target.getCapability(Capabilities.FluidHandler.BLOCK).invalidate();
                }
            }
        """.trimIndent())

        val changes = ExactBlockEntityCapabilityQueryMigration(tempDir.resolve("src/main/java"))
            .migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.size == 1, changes.joinToString("\n"))
        assertEquals(2, Regex("""target\.invalidateCapabilities\(\)""").findAll(migrated).count(), migrated)
        assertTrue(!migrated.contains("getCapability("), migrated)
        assertTrue(!migrated.contains("LazyOptional"), migrated)
    }

    @Test
    fun `resolves a BlockEntity receiver from the exact method type parameter erasure bound`() {
        val file = write("sample/GenericInvalidation.java", """
            package sample;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            interface Container {}
            class GenericInvalidation {
                static <T extends BlockEntity & Container> void reset(T target) {
                    target.getCapability(Capabilities.ItemHandler.BLOCK).invalidate();
                }
            }
        """.trimIndent())

        ExactBlockEntityCapabilityQueryMigration(tempDir.resolve("src/main/java"))
            .migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(migrated.contains("target.invalidateCapabilities();"), migrated)
        assertTrue(!migrated.contains("getCapability("), migrated)
    }

    @Test
    fun `discovers generated adapter and resolves lambda type from exact BlockEntity class literal`() {
        write("com/modporter/generated/sample/compat/LazyOptional.java", """
            package com.modporter.generated.sample.compat;
            class LazyOptional<T> {
                static <T> LazyOptional<T> ofNullable(T value) { return null; }
            }
        """.trimIndent())
        val file = write("sample/Scene.java", """
            package sample;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class Scene {
                Instructions instructions;
                void render() {
                    instructions.modifyBlockEntity(
                        BlockEntity.class,
                        be -> be.getCapability(Capabilities.ItemHandler.BLOCK).ifPresent(value -> {})
                    );
                }
            }
            class Instructions {}
        """.trimIndent())

        val changes = ExactBlockEntityCapabilityQueryMigration(tempDir.resolve("src/main/java"))
            .migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(changes.size == 1, changes.joinToString("\n"))
        assertTrue(
            migrated.contains(
                "com.modporter.generated.sample.compat.LazyOptional.ofNullable(java.util.Optional.ofNullable(be.getLevel()).map( modporterLevel -> modporterLevel.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null)).orElse(null))"
            ),
            migrated
        )
    }

    @Test
    fun `uses an exact planned generated adapter type before the build pass materializes it`() {
        val file = write("sample/PlannedAdapter.java", """
            package sample;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class PlannedAdapter {
                Object query(BlockEntity target) {
                    return target.getCapability(Capabilities.ItemHandler.BLOCK).orElse(null);
                }
            }
        """.trimIndent())

        ExactBlockEntityCapabilityQueryMigration(
            tempDir.resolve("src/main/java"),
            plannedLazyOptionalType = { "com.modporter.generated.sample.compat.LazyOptional" }
        ).migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(
            migrated.contains(
                "com.modporter.generated.sample.compat.LazyOptional.ofNullable(java.util.Optional.ofNullable(target.getLevel()).map( modporterLevel -> modporterLevel.getCapability(Capabilities.ItemHandler.BLOCK, target.getBlockPos(), null)).orElse(null))"
            ),
            migrated
        )
    }

    @Test
    fun `resolves Optional lambda receiver through a method bound with an extends wildcard`() {
        write("sample/IBE.java", """
            package sample;
            import java.util.Optional;
            import net.minecraft.world.level.block.entity.BlockEntity;
            interface IBE<T extends BlockEntity> {
                Optional<T> getBlockEntityOptional();
            }
        """.trimIndent())
        val file = write("sample/WildcardQuery.java", """
            package sample;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class WildcardQuery {
                static <T extends IBE<? extends BlockEntity>> Object query(T owner) {
                    return owner.getBlockEntityOptional()
                        .map(be -> be.getCapability(Capabilities.ItemHandler.BLOCK).orElse(null))
                        .orElse(null);
                }
            }
        """.trimIndent())

        ExactBlockEntityCapabilityQueryMigration(
            tempDir.resolve("src/main/java"),
            plannedLazyOptionalType = { "com.modporter.generated.sample.compat.LazyOptional" }
        ).migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(
            migrated.contains(
                "java.util.Optional.ofNullable(be.getLevel()).map( modporterLevel -> modporterLevel.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null)).orElse(null)"
            ),
            migrated
        )
    }

    @Test
    fun `resolves lambda receiver through an inherited generic interface method`() {
        write("sample/IBE.java", """
            package sample;
            import java.util.function.Consumer;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.entity.BlockEntity;
            interface IBE<T extends BlockEntity> {
                default void withBlockEntityDo(BlockGetter level, BlockPos pos, Consumer<T> action) {}
            }
        """.trimIndent())
        write("sample/TargetBlockEntity.java", """
            package sample;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class TargetBlockEntity extends BlockEntity {}
        """.trimIndent())
        val file = write("sample/InheritedQuery.java", """
            package sample;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class InheritedQuery implements IBE<TargetBlockEntity> {
                void query(Level level, BlockPos pos) {
                    withBlockEntityDo(level, pos, be -> {
                        Object handler = be.getCapability(Capabilities.ItemHandler.BLOCK).orElse(null);
                    });
                }
            }
        """.trimIndent())

        ExactBlockEntityCapabilityQueryMigration(
            tempDir.resolve("src/main/java"),
            plannedLazyOptionalType = { "com.modporter.generated.sample.compat.LazyOptional" }
        ).migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(
            migrated.contains(
                "java.util.Optional.ofNullable(be.getLevel()).map( modporterLevel -> modporterLevel.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null)).orElse(null)"
            ),
            migrated
        )
    }

    @Test
    fun `migrates a vanilla HopperBlockEntity pattern receiver from its exact external type contract`() {
        val file = write("sample/HopperQuery.java", """
            package sample;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.HopperBlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class HopperQuery {
                Object query(BlockEntity target) {
                    if (target instanceof HopperBlockEntity hopper) {
                        return hopper.getCapability(Capabilities.ItemHandler.BLOCK).orElse(null);
                    }
                    return null;
                }
            }
        """.trimIndent())

        ExactBlockEntityCapabilityQueryMigration(
            tempDir.resolve("src/main/java"),
            plannedLazyOptionalType = { "com.modporter.generated.sample.compat.LazyOptional" }
        ).migrate(dryRun = false)
        val migrated = file.readText()

        assertTrue(
            migrated.contains(
                "java.util.Optional.ofNullable(hopper.getLevel()).map( modporterLevel -> modporterLevel.getCapability(Capabilities.ItemHandler.BLOCK, hopper.getBlockPos(), null)).orElse(null)"
            ),
            migrated
        )
    }

    @Test
    fun `hard gates a resolved BLOCK capability receiver that is not proven to be a BlockEntity`() {
        val file = write("sample/ForeignQuery.java", """
            package sample;
            import net.neoforged.neoforge.capabilities.Capabilities;
            class ForeignQuery {
                Object query(ForeignTarget target) {
                    return target.getCapability(Capabilities.ItemHandler.BLOCK);
                }
            }
            class ForeignTarget {}
        """.trimIndent())
        val original = file.readText()

        val error = assertFailsWith<IllegalStateException> {
            ExactBlockEntityCapabilityQueryMigration(
                tempDir.resolve("src/main/java"),
                plannedLazyOptionalType = { "com.modporter.generated.sample.compat.LazyOptional" }
            ).migrate(dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("not proven to be a BlockEntity"), error.message)
        assertTrue(file.readText() == original, file.readText())
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
