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

class LegacyProjectileDispenseAccessorMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `exact invoker contract becomes field accessors with explicit direction flow`() {
        writeAccessor()
        val wrapper = writeWrapper(oneVector = true)

        val result = StructuralRefactorPass().apply(tempDir)
        val accessor = accessorFile().readText()
        val migrated = wrapper.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(accessor.contains("@Accessor(\"projectileItem\")"), accessor)
        assertTrue(accessor.contains("ProjectileItem projectile("), accessor)
        assertTrue(accessor.contains("@Accessor(\"dispenseConfig\")"), accessor)
        assertTrue(accessor.contains("ProjectileItem.DispenseConfig uncertainty("), accessor)
        assertFalse(accessor.contains("@Invoker"), accessor)
        assertFalse(accessor.contains("float power()"), accessor)
        assertTrue(migrated.contains("Direction projectileDirection"), migrated)
        assertTrue(migrated.contains("Direction.getNearest(heading.x, heading.y, heading.z)"), migrated)
        assertTrue(migrated.contains("bridge.projectile().asProjectile(level, position, stack, projectileDirection)"), migrated)
        assertTrue(migrated.contains("bridge.uncertainty().uncertainty()"), migrated)
        assertTrue(migrated.contains("bridge.uncertainty().power()"), migrated)
    }

    @Test
    fun `custom same named target cannot satisfy owner proof`() {
        val dir = tempDir.resolve("src/main/java/sample/bridge")
        dir.createDirectories()
        val file = dir.resolve("VanillaProjectileBridge.java")
        val original = accessorSource().replace(
            "import net.minecraft.core.dispenser.ProjectileDispenseBehavior;",
            "import sample.fake.ProjectileDispenseBehavior;"
        )
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `ambiguous vector direction hard fails project atomically`() {
        val accessor = writeAccessor()
        val wrapper = writeWrapper(oneVector = false)
        val accessorOriginal = accessor.readText()
        val wrapperOriginal = wrapper.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("one exactly typed Vec3 direction parameter") }, result.errors.joinToString("\n"))
        assertTrue(accessor.readText() == accessorOriginal, accessor.readText())
        assertTrue(wrapper.readText() == wrapperOriginal, wrapper.readText())
    }

    @Test
    fun `additional invoker semantics hard fail project atomically`() {
        val accessor = writeAccessor()
        accessor.writeText(
            accessor.readText().replace(
                "    @Invoker(\"getPower\")\n    float power();",
                "    @Invoker(\"getPower\")\n    float power();\n    @Invoker(\"legacyExtra\")\n    int extra();"
            )
        )
        val wrapper = writeWrapper(oneVector = true)
        val accessorOriginal = accessor.readText()
        val wrapperOriginal = wrapper.readText()

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("exactly three explicit string-valued invokers") }, result.errors.joinToString("\n"))
        assertTrue(accessor.readText() == accessorOriginal, accessor.readText())
        assertTrue(wrapper.readText() == wrapperOriginal, wrapper.readText())
    }

    private fun writeAccessor(): Path {
        val file = accessorFile()
        file.parent.createDirectories()
        file.writeText(accessorSource())
        return file
    }

    private fun accessorFile(): Path =
        tempDir.resolve("src/main/java/sample/bridge/VanillaProjectileBridge.java")

    private fun accessorSource(): String = """
        package sample.bridge;
        import net.minecraft.core.Position;
        import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
        import net.minecraft.world.entity.projectile.Projectile;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.Level;
        import org.spongepowered.asm.mixin.Mixin;
        import org.spongepowered.asm.mixin.gen.Invoker;
        @Mixin(ProjectileDispenseBehavior.class)
        public interface VanillaProjectileBridge {
            @Invoker("getProjectile")
            Projectile projectile(Level level, Position position, ItemStack stack);
            @Invoker("getUncertainty")
            float uncertainty();
            @Invoker("getPower")
            float power();
        }
    """.trimIndent()

    private fun writeWrapper(oneVector: Boolean): Path {
        val dir = tempDir.resolve("src/main/java/sample/wrapper")
        dir.createDirectories()
        val file = dir.resolve("MobileProjectileBehavior.java")
        val extra = if (oneVector) "" else ", Vec3 alternate"
        file.writeText("""
            package sample.wrapper;
            import sample.bridge.VanillaProjectileBridge;
            import net.minecraft.core.Position;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.phys.Vec3;
            abstract class MobileProjectileBehavior {
                Projectile launch(Level level, Position position, ItemStack stack, Vec3 heading$extra) {
                    return make(level, position, stack);
                }
                abstract Projectile make(Level level, Position position, ItemStack stack);
                static MobileProjectileBehavior wrap(ProjectileDispenseBehavior behavior) {
                    VanillaProjectileBridge bridge = (VanillaProjectileBridge) behavior;
                    return new MobileProjectileBehavior() {
                        @Override
                        Projectile make(Level level, Position position, ItemStack stack) {
                            return bridge.projectile(level, position, stack);
                        }
                        float spread() { return bridge.uncertainty(); }
                        float speed() { return bridge.power(); }
                    };
                }
            }
        """.trimIndent())
        return file
    }
}
