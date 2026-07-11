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

class LegacyConfigScreenFactoryMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `nested legacy factory becomes a direct config screen supplier`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("ClientSetup.java")
        file.writeText("""
            package example;
            import net.neoforged.neoforge.client.ConfigScreenHandler;
            class ClientSetup {
                void register(ModContainer container) {
                    container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, previous) -> new SettingsScreen(previous)));
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("registerExtensionPoint(IConfigScreenFactory.class"), migrated)
        assertTrue(migrated.contains("() -> (minecraft, previous) -> new SettingsScreen(previous)"), migrated)
        assertTrue(migrated.contains("import net.neoforged.neoforge.client.gui.IConfigScreenFactory;"), migrated)
        assertFalse(migrated.contains("ConfigScreenHandler"), migrated)
    }

    @Test
    fun `custom ConfigScreenHandler names do not satisfy owner proof`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val file = dir.resolve("CustomFactory.java")
        val original = """
            package example;
            class CustomFactory {
                void register(ModContainer container) {
                    container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, previous) -> previous));
                }
            }
        """.trimIndent()
        file.writeText(original)

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("no exact NeoForge owner import") })
        assertTrue(file.readText() == original, file.readText())
    }

    @Test
    fun `non lambda legacy factory construction hard fails atomically`() {
        val dir = tempDir.resolve("src/main/java/example")
        dir.createDirectories()
        val valid = dir.resolve("AValid.java")
        val validOriginal = """
            package example;
            import net.neoforged.neoforge.client.ConfigScreenHandler;
            class AValid {
                void register(ModContainer container) {
                    container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, previous) -> previous));
                }
            }
        """.trimIndent()
        valid.writeText(validOriginal)
        val invalid = dir.resolve("Invalid.java")
        invalid.writeText("""
            package example;
            import net.neoforged.neoforge.client.ConfigScreenHandler;
            class Invalid {
                Object create(ScreenFactory factory) {
                    return new ConfigScreenHandler.ConfigScreenFactory(factory);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("not one direct lambda") }, result.errors.joinToString("\n"))
        assertTrue(valid.readText() == validOriginal, valid.readText())
        assertTrue(invalid.readText().contains("new ConfigScreenHandler.ConfigScreenFactory(factory)"))
    }
}
