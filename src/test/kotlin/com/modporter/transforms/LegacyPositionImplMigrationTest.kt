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

class LegacyPositionImplMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `three coordinate position values become Vec3`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("ProjectileFactory.java")
        file.writeText("""
            package example;
            import net.minecraft.core.Position;
            import net.minecraft.core.PositionImpl;
            class ProjectileFactory {
                Position position(double x, double y, double z) {
                    PositionImpl value = new PositionImpl(x, y, z);
                    return value;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("Vec3 value = new Vec3(x, y, z);"), migrated)
        assertTrue(migrated.contains("import net.minecraft.world.phys.Vec3;"), migrated)
        assertFalse(migrated.contains("PositionImpl"), migrated)
    }

    @Test
    fun `custom PositionImpl types do not satisfy Minecraft owner proof`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("CustomPosition.java")
        val original = """
            package example;
            class CustomPosition {
                Object create(double x, double y, double z) {
                    return new PositionImpl(x, y, z);
                }
            }
        """.trimIndent()
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("no exact Minecraft owner import") })
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `unsupported PositionImpl constructors hard fail without project writes`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val valid = dir.resolve("AValid.java")
        val validOriginal = """
            package example;
            import net.minecraft.core.PositionImpl;
            class AValid {
                Object create() { return new PositionImpl(1, 2, 3); }
            }
        """.trimIndent()
        valid.writeText(validOriginal)
        val invalid = dir.resolve("Invalid.java")
        invalid.writeText("""
            package example;
            import net.minecraft.core.PositionImpl;
            class Invalid {
                Object create(Position other) { return new PositionImpl(other); }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("not an exact three-coordinate value") })
        assertTrue(valid.readText() == validOriginal, valid.readText())
        assertTrue(invalid.readText().contains("new PositionImpl(other)"))
    }
}
