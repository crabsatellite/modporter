package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class LegacyDirectTrackingChunkTargetMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `direct tracking target uses an exactly declared local level chunk`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("PulseSender.java")
        file.writeText("""
            package example;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            class PulseSender {
                void send(Level level, BlockPos pos, Object payload) {
                    LevelChunk chunk = level.getChunkAt(pos);
                    Channel.get().send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), payload);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains(
                "PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) chunk.getLevel(), chunk.getPos(), payload)"
            ),
            migrated
        )
    }

    @Test
    fun `same named non chunk parameter blocks field fallback and all writes`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val valid = dir.resolve("AValid.java")
        val validOriginal = """
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            class AValid {
                void send(LevelChunk chunk, Object payload) {
                    Channel.get().send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), payload);
                }
            }
        """.trimIndent()
        valid.writeText(validOriginal)
        val invalid = dir.resolve("Shadowed.java")
        invalid.writeText("""
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            class Shadowed {
                LevelChunk chunk;
                void send(Object chunk, Object payload) {
                    Channel.get().send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), payload);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("Cannot resolve tracking chunk supplier") }, result.errors.joinToString("\n"))
        assertTrue(valid.readText() == validOriginal, valid.readText())
        assertTrue(invalid.readText().contains("TRACKING_CHUNK.with(() -> chunk)"), invalid.readText())
    }

    @Test
    fun `method call chunk suppliers hard fail rather than infer return types`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("OpaqueSupplier.java")
        file.writeText("""
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            class OpaqueSupplier {
                LevelChunk lookupChunk() { return null; }
                void send(Object payload) {
                    Channel.get().send(PacketDistributor.TRACKING_CHUNK.with(() -> lookupChunk()), payload);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("Cannot resolve tracking chunk supplier") }, result.errors.joinToString("\n"))
        assertTrue(file.readText().contains("() -> lookupChunk()"), file.readText())
    }

    @Test
    fun `out of scope local does not provide type evidence for a field`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("OutOfScope.java")
        val original = """
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.neoforged.neoforge.network.PacketDistributor;
            class OutOfScope {
                Object chunk;
                void send(Object payload) {
                    {
                        LevelChunk chunk = null;
                    }
                    Channel.get().send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), payload);
                }
            }
        """.trimIndent()
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("Cannot resolve tracking chunk supplier") })
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `project PacketDistributor names do not satisfy NeoForge owner proof`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("CustomDistributor.java")
        val original = """
            package example;
            import net.minecraft.world.level.chunk.LevelChunk;
            class CustomDistributor {
                void send(LevelChunk chunk, Object payload) {
                    Channel.get().send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), payload);
                }
            }
        """.trimIndent()
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("no exact NeoForge PacketDistributor import") })
        assertTrue(file.readText() == original, file.readText())
    }
}
