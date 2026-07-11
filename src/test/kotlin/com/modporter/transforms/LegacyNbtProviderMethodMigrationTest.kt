package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyNbtProviderMethodMigration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LegacyNbtProviderMethodMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `migrates compound tag lifecycle declarations and internal forwarding as one family`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("BaseData.java").writeText(
            """
            package com.example;
            import java.util.function.Consumer;
            import net.minecraft.nbt.CompoundTag;
            public class BaseData {
                public void write(CompoundTag tag, boolean clientPacket) {
                    forEach(value -> value.write(tag, clientPacket));
                }
                public void read(CompoundTag tag, boolean clientPacket) {
                }
                public void writeSafe(CompoundTag tag) {
                    write(tag, false);
                }
                public void writeClient(CompoundTag tag) {
                    saveAdditional(tag);
                }
                public void readClient(CompoundTag tag) {
                    loadAdditional(tag);
                }
                void saveAdditional(CompoundTag tag) {
                    write(tag, false);
                }
                void loadAdditional(CompoundTag tag) {
                    read(tag, false);
                }
                void forEach(Consumer<BaseData> action) {
                    action.accept(this);
                }
            }
            """.trimIndent()
        )
        source.resolve("ChildData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class ChildData extends BaseData {
                @Override
                public void write(CompoundTag data, boolean clientPacket) {
                    super.write(data, clientPacket);
                }
                @Override
                public void read(CompoundTag data, boolean clientPacket) {
                    super.read(data, clientPacket);
                }
                @Override
                public void readClient(CompoundTag data) {
                    super.readClient(data);
                }
                @Override
                public void writeClient(CompoundTag data) {
                    super.writeClient(data);
                }
                private static final String DOC = "write(CompoundTag, boolean)";
            }
            """.trimIndent()
        )

        val changes = LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val base = source.resolve("BaseData.java").readText()
        val child = source.resolve("ChildData.java").readText()

        assertTrue(changes.all { it.ruleId == "struct-nbt-provider-method-family" })
        assertTrue(base.contains("write(CompoundTag tag, net.minecraft.core.HolderLookup.Provider modporterRegistries, boolean clientPacket)"), base)
        assertTrue(base.contains("writeSafe(CompoundTag tag, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), base)
        assertTrue(base.contains("write(tag, modporterRegistries, false);"), base)
        assertTrue(base.contains("value.write(tag, modporterRegistries, clientPacket)"), base)
        assertTrue(base.contains("writeClient(CompoundTag tag, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), base)
        assertTrue(child.contains("super.write(data, modporterRegistries, clientPacket);"), child)
        assertTrue(child.contains("super.read(data, modporterRegistries, clientPacket);"), child)
        assertTrue(child.contains("readClient(CompoundTag data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), child)
        assertTrue(child.contains("super.readClient(data, modporterRegistries);"), child)
        assertTrue(child.contains("writeClient(CompoundTag data, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), child)
        assertTrue(child.contains("super.writeClient(data, modporterRegistries);"), child)
        assertTrue(child.contains("\"write(CompoundTag, boolean)\""), child)
    }

    @Test
    fun `propagates an existing provider aware parent signature to an old child override`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("BaseData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.core.HolderLookup;
            public class BaseData {
                public void readClient(CompoundTag tag, HolderLookup.Provider exactProvider) {
                }
            }
            """.trimIndent()
        )
        source.resolve("ChildData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class ChildData extends BaseData {
                @Override
                public void readClient(CompoundTag tag) {
                    super.readClient(tag);
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val child = source.resolve("ChildData.java").readText()

        assertTrue(child.contains("readClient(CompoundTag tag, net.minecraft.core.HolderLookup.Provider modporterRegistries)"), child)
        assertTrue(child.contains("super.readClient(tag, modporterRegistries);"), child)
    }

    @Test
    fun `does not migrate unrelated write overloads`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("Unrelated.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class Unrelated {
                void write(String text, boolean flush) {
                }
                void invoke(CompoundTag tag) {
                    write("doc", false);
                }
            }
            """.trimIndent()
        )

        val changes = LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        assertTrue(changes.isEmpty())
        assertTrue(source.resolve("Unrelated.java").readText().contains("write(String text, boolean flush)"))
    }

    @Test
    fun `does not add providers to same shaped static calls on an unrelated declared owner`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("DataFamily.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class DataFamily {
                void write(CompoundTag tag, boolean clientPacket) {
                    Unrelated.write(tag, new Unrelated());
                }
            }
            """.trimIndent()
        )
        source.resolve("Unrelated.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class Unrelated {
                static void write(CompoundTag tag, Unrelated value) {
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val migrated = source.resolve("DataFamily.java").readText()

        assertTrue(migrated.contains("Unrelated.write(tag, new Unrelated());"), migrated)
        assertTrue(!migrated.contains("Unrelated.write(tag, modporterRegistries"), migrated)
    }

    @Test
    fun `uses exact typed contexts for provider aware nbt family calls`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("SyncedData.java").writeText(
            """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public abstract class SyncedData extends BlockEntity {
                public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
                    return new CompoundTag();
                }
                public void readClient(CompoundTag tag, HolderLookup.Provider provider) {
                }
                public void writeSafe(CompoundTag tag, HolderLookup.Provider provider) {
                }
                void send() {
                    getUpdateTag();
                }
            }
            """.trimIndent()
        )
        source.resolve("TypedCallers.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.entity.player.Inventory;
            import net.minecraft.world.level.Level;
            public class TypedCallers {
                TypedCallers(Inventory inventory, FriendlyByteBuf buffer, SyncedData data) {
                    data.readClient(buffer.readNbt());
                }
                void copy(Level level, SyncedData data, CompoundTag tag) {
                    data.writeSafe(tag);
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val synced = source.resolve("SyncedData.java").readText()
        val callers = source.resolve("TypedCallers.java").readText()

        assertTrue(synced.contains("getUpdateTag(this.getLevel().registryAccess());"), synced)
        assertTrue(callers.contains("data.readClient(buffer.readNbt(), inventory.player.registryAccess());"), callers)
        assertTrue(callers.contains("data.writeSafe(tag, level.registryAccess());"), callers)
    }

    @Test
    fun `resolves inherited nbt targets and typed pattern variables`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("SyncedData.java").writeText(
            """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public abstract class SyncedData extends BlockEntity {
                public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
                    return new CompoundTag();
                }
                public void readClient(CompoundTag tag, HolderLookup.Provider provider) {
                }
                public void writeSafe(CompoundTag tag, HolderLookup.Provider provider) {
                }
            }
            """.trimIndent()
        )
        source.resolve("ChildData.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class ChildData extends CachedData {
                void send() {
                    getUpdateTag();
                }
            }
            """.trimIndent()
        )
        source.resolve("CachedData.java").writeText(
            """
            package com.example;
            public abstract class CachedData extends SyncedData {
            }
            """.trimIndent()
        )
        source.resolve("PatternCaller.java").writeText(
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            public class PatternCaller {
                void read(Level level, Object value, CompoundTag tag) {
                    if (value instanceof ChildData child) {
                        child.readClient(tag);
                    }
                }
                void write(BlockEntity blockEntity, CompoundTag tag) {
                    if (blockEntity instanceof ChildData child) {
                        child.writeSafe(tag);
                    }
                }
            }
            """.trimIndent()
        )

        LegacyNbtProviderMethodMigration().migrate(tempDir, dryRun = false)
        val child = source.resolve("ChildData.java").readText()
        val caller = source.resolve("PatternCaller.java").readText()

        assertTrue(child.contains("getUpdateTag(this.getLevel().registryAccess());"), child)
        assertTrue(caller.contains("child.readClient(tag, level.registryAccess());"), caller)
        assertTrue(caller.contains("child.writeSafe(tag, blockEntity.getLevel().registryAccess());"), caller)
    }
}
