package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderAwareNbtContractMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `project CompoundTag serializable contracts migrate definitions bodies and call sites`() {
        val inventory = javaFile(
            "ProjectInventory.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.common.util.INBTSerializable;
            import net.neoforged.neoforge.items.ItemStackHandler;

            public class ProjectInventory implements INBTSerializable<CompoundTag> {
                private final ItemStackHandler backing = new ItemStackHandler();

                @Override
                public CompoundTag serializeNBT() {
                    return backing.serializeNBT();
                }

                @Override
                public void deserializeNBT(CompoundTag tag) {
                    backing.deserializeNBT(tag);
                }
            }
            """.trimIndent()
        )
        val owner = javaFile(
            "InventoryBlockEntity.java",
            """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            public class InventoryBlockEntity extends BlockEntity {
                private final ProjectInventory inventory = new ProjectInventory();

                public InventoryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }

                protected void write(CompoundTag tag, boolean clientPacket) {
                    tag.put("Inventory", inventory.serializeNBT());
                }

                protected void read(CompoundTag tag, boolean clientPacket) {
                    inventory.deserializeNBT(tag.getCompound("Inventory"));
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migratedInventory = inventory.readText()
        val migratedOwner = owner.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migratedInventory.contains(
                "public CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider provider)"
            ),
            migratedInventory
        )
        assertTrue(migratedInventory.contains("backing.serializeNBT(provider)"), migratedInventory)
        assertTrue(
            migratedInventory.contains(
                "public void deserializeNBT(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag)"
            ),
            migratedInventory
        )
        assertTrue(migratedInventory.contains("backing.deserializeNBT(provider, tag)"), migratedInventory)
        assertTrue(migratedOwner.contains("inventory.serializeNBT(modporterRegistries)"), migratedOwner)
        assertTrue(
            migratedOwner.contains(
                "inventory.deserializeNBT(modporterRegistries, tag.getCompound(\"Inventory\"))"
            ),
            migratedOwner
        )
        assertFalse(migratedOwner.contains("inventory.deserializeNBT(tag.getCompound(\"Inventory\"),"), migratedOwner)
    }

    @Test
    fun `ItemStackHandler overrides preserve the provider first contract in super calls`() {
        val file = javaFile(
            "ProcessingInventory.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            public class ProcessingInventory extends ItemStackHandler {
                @Override
                public CompoundTag serializeNBT() {
                    CompoundTag tag = super.serializeNBT();
                    tag.putBoolean("Locked", true);
                    return tag;
                }

                @Override
                public void deserializeNBT(CompoundTag tag) {
                    super.deserializeNBT(tag);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("public CompoundTag serializeNBT(HolderLookup.Provider registries)"),
            migrated
        )
        assertTrue(migrated.contains("super.serializeNBT(registries)"), migrated)
        assertTrue(
            migrated.contains("public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag)"),
            migrated
        )
        assertTrue(migrated.contains("super.deserializeNBT(registries, tag)"), migrated)
        assertFalse(migrated.contains("deserializeNBT(CompoundTag tag, HolderLookup.Provider"), migrated)
    }

    @Test
    fun `entity attachment lambda derives the provider from the exact data receiver`() {
        javaFile(
            "CartData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.common.util.INBTSerializable;

            public class CartData implements INBTSerializable<CompoundTag> {
                @Override
                public CompoundTag serializeNBT() {
                    return new CompoundTag();
                }

                @Override
                public void deserializeNBT(CompoundTag tag) {
                }
            }
            """.trimIndent()
        )
        javaFile(
            "DataAttachments.java",
            """
            package com.example;

            import java.util.function.Supplier;
            import net.neoforged.neoforge.attachment.AttachmentType;

            public class DataAttachments {
                public static final Supplier<AttachmentType<CartData>> CART_DATA = null;
            }
            """.trimIndent()
        )
        val packet = javaFile(
            "CartPacket.java",
            """
            package com.example;

            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;

            public class CartPacket {
                void apply(Entity exactEntity, CompoundTag tag) {
                    LazyOptional.ofNullable(exactEntity.getData(DataAttachments.CART_DATA.get()))
                        .ifPresent(data -> data.deserializeNBT(tag));
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = packet.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains("data.deserializeNBT(exactEntity.registryAccess(), tag)"),
            migrated
        )
        assertFalse(migrated.contains("level.registryAccess()"), migrated)
        assertFalse(migrated.contains("RegistryAccess.EMPTY"), migrated)
    }

    @Test
    fun `non CompoundTag INBTSerializable contracts are not generalized`() {
        val file = javaFile(
            "ListData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.neoforged.neoforge.common.util.INBTSerializable;

            public class ListData implements INBTSerializable<ListTag> {
                @Override
                public ListTag serializeNBT() {
                    return new ListTag();
                }

                @Override
                public void deserializeNBT(ListTag tag) {
                }

                public CompoundTag documentationValue() {
                    return new CompoundTag();
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(migrated.contains("public ListTag serializeNBT()"), migrated)
        assertTrue(migrated.contains("public void deserializeNBT(ListTag tag)"), migrated)
        assertFalse(migrated.contains("HolderLookup.Provider"), migrated)
    }

    private fun javaFile(name: String, source: String): Path {
        val directory = tempDir.resolve("src/main/java/com/example").createDirectories()
        return directory.resolve(name).also { it.writeText(source) }
    }
}
