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

class LegacyPacketTargetMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local player packet targets inline into direct distributor sends`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("PlayerPackets.java")
        file.writeText("""
            package example;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.network.PacketDistributor;
            import net.neoforged.neoforge.network.PacketDistributor.PacketTarget;
            class PlayerPackets {
                void send(ServerPlayer player, Object first, Object second) {
                    PacketTarget target = PacketDistributor.PLAYER.with(() -> player);
                    Channel.get().send(target, first);
                    Channel.get().send(target, second);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("PacketDistributor.sendToPlayer(player, first)"), migrated)
        assertTrue(migrated.contains("PacketDistributor.sendToPlayer(player, second)"), migrated)
        assertFalse(migrated.contains("PacketTarget target"), migrated)
        assertFalse(migrated.contains("PacketDistributor.PacketTarget"), migrated)
    }

    @Test
    fun `packet targets with non send uses hard fail`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val validFile = dir.resolve("AValidTarget.java")
        val validOriginal = """
            package example;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.network.PacketDistributor;
            import net.neoforged.neoforge.network.PacketDistributor.PacketTarget;
            class AValidTarget {
                void send(ServerPlayer player, Object payload) {
                    PacketTarget target = PacketDistributor.PLAYER.with(() -> player);
                    Channel.get().send(target, payload);
                }
            }
        """.trimIndent()
        validFile.writeText(validOriginal)
        val file = dir.resolve("EscapedTarget.java")
        file.writeText("""
            package example;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.network.PacketDistributor;
            import net.neoforged.neoforge.network.PacketDistributor.PacketTarget;
            class EscapedTarget {
                void send(ServerPlayer player, Object payload) {
                    PacketTarget target = PacketDistributor.PLAYER.with(() -> player);
                    inspect(target);
                    Channel.get().send(target, payload);
                }
                void inspect(Object value) {}
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("non-send uses") }, result.errors.joinToString("\n"))
        assertTrue(file.readText().contains("inspect(target)"), file.readText())
        assertTrue(validFile.readText() == validOriginal, validFile.readText())
    }
}
