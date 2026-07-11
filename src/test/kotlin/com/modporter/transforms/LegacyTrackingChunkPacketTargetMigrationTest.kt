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

class LegacyTrackingChunkPacketTargetMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `source proven inherited chunk target helpers migrate with their complete call graph`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val base = dir.resolve("BaseBlockEntity.java")
        base.writeText("""
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            abstract class BaseBlockEntity {
                protected Level level;
                protected BlockPos worldPosition;
                public PacketDistributor.PacketTarget packetTarget() {
                    return PacketDistributor.TRACKING_CHUNK.with(this::containedChunk);
                }
                public LevelChunk containedChunk() {
                    return level.getChunkAt(worldPosition);
                }
            }
        """.trimIndent())
        dir.resolve("MiddleBlockEntity.java").writeText("""
            package example;
            abstract class MiddleBlockEntity extends BaseBlockEntity {}
        """.trimIndent())
        val child = dir.resolve("ConcreteBlockEntity.java")
        child.writeText("""
            package example;
            class ConcreteBlockEntity extends MiddleBlockEntity {
                void notifyClient(Object first, Object second) {
                    Channel.get().send(packetTarget(), first);
                    if (second != null)
                        Channel.get().send(this.packetTarget(), second);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedBase = base.readText()
        val migratedChild = child.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(migratedBase.contains("PacketTarget packetTarget"), migratedBase)
        assertTrue(migratedBase.contains("LevelChunk containedChunk()"), migratedBase)
        assertTrue(
            migratedChild.contains(
                "PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level, new ChunkPos(worldPosition), first)"
            ),
            migratedChild
        )
        assertTrue(
            migratedChild.contains(
                "PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level, new ChunkPos(worldPosition), second)"
            ),
            migratedChild
        )
    }

    @Test
    fun `tracking target helper calls outside channel sends hard fail atomically`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("EscapedTarget.java")
        val original = """
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            class EscapedTarget {
                Level level;
                BlockPos pos;
                PacketDistributor.PacketTarget packetTarget() {
                    return PacketDistributor.TRACKING_CHUNK.with(this::chunk);
                }
                LevelChunk chunk() {
                    return level.getChunkAt(pos);
                }
                void send(Object payload) {
                    inspect(packetTarget());
                    Channel.get().send(packetTarget(), payload);
                }
                void inspect(Object value) {}
            }
        """.trimIndent()
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("Tracking PacketTarget call") }, result.errors.joinToString("\n"))
        assertTrue(file.readText().contains("inspect(packetTarget())"), file.readText())
        assertTrue(file.readText().contains("PacketDistributor.TRACKING_CHUNK.with"), file.readText())
    }

    @Test
    fun `tracking target suppliers without an exact chunk expression hard fail`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("OpaqueTarget.java")
        file.writeText("""
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            class OpaqueTarget {
                PacketDistributor.PacketTarget packetTarget() {
                    return PacketDistributor.TRACKING_CHUNK.with(this::chunk);
                }
                LevelChunk chunk() {
                    return lookupChunk();
                }
                void send(Object payload) {
                    Channel.get().send(packetTarget(), payload);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("Cannot derive exact level and position") }, result.errors.joinToString("\n"))
        assertTrue(file.readText().contains("lookupChunk()"), file.readText())
    }
}
