package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyItemHandlerSetItemMigration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LegacyItemHandlerSetItemMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `uses class literal lambda type and project return inheritance to migrate item handler mutation`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("SmartInventory.java").writeText(
            """
            package com.example;
            import net.neoforged.neoforge.items.IItemHandlerModifiable;
            public class SmartInventory implements IItemHandlerModifiable {
            }
            """.trimIndent()
        )
        source.resolve("MachineBlockEntity.java").writeText(
            """
            package com.example;
            public class MachineBlockEntity {
                public static class Inventory extends SmartInventory {
                }
                private final Inventory inventory = new Inventory();
                public Inventory getInventory() {
                    return inventory;
                }
            }
            """.trimIndent()
        )
        source.resolve("Scene.java").writeText(
            """
            package com.example;
            public class Scene {
                void insert(World world, Object pos, Object stack) {
                    world.modifyBlockEntity(pos, MachineBlockEntity.class, machine -> machine.getInventory()
                        .setItem(0, stack));
                    String documentation = "machine.getInventory().setItem(0, stack)";
                }
            }
            """.trimIndent()
        )

        val changes = LegacyItemHandlerSetItemMigration().migrate(tempDir, dryRun = false)
        val scene = source.resolve("Scene.java").readText()

        assertTrue(changes.single().ruleId == "struct-item-handler-set-stack-in-slot")
        assertTrue(scene.contains("machine.getInventory()\n            .setStackInSlot(0, stack)"), scene)
        assertTrue(scene.contains("\"machine.getInventory().setItem(0, stack)\""), scene)
    }

    @Test
    fun `does not rewrite setItem when project return type is not a modifiable handler`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("MachineBlockEntity.java").writeText(
            """
            package com.example;
            public class MachineBlockEntity {
                public Container getInventory() {
                    return new Container();
                }
            }
            """.trimIndent()
        )
        source.resolve("Scene.java").writeText(
            """
            package com.example;
            public class Scene {
                void insert(World world, Object pos, Object stack) {
                    world.modifyBlockEntity(pos, MachineBlockEntity.class, machine -> machine.getInventory().setItem(0, stack));
                }
            }
            """.trimIndent()
        )

        val changes = LegacyItemHandlerSetItemMigration().migrate(tempDir, dryRun = false)
        val scene = source.resolve("Scene.java").readText()

        assertTrue(changes.isEmpty())
        assertTrue(scene.contains("getInventory().setItem(0, stack)"), scene)
    }
}
