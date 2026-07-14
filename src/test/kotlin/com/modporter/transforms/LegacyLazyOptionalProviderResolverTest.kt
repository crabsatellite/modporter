package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactLegacyLazyOptionalProviderResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyLazyOptionalProviderResolverTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resolves exact map and unused project method LazyOptional providers`() {
        write("sample/Provider.java", """
            package sample;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.Direction;
            class Provider {
                LazyOptional<Handler> optional;
                <T> LazyOptional<T> getProvider(Capability<T> capability, Direction side) {
                    return optional.cast();
                }
                <T> LazyOptional<T> getProviderUsingCapability(Capability<T> capability, Direction side) {
                    return capability == null ? LazyOptional.empty() : optional.cast();
                }
            }
        """.trimIndent())
        val owner = write("sample/Owner.java", """
            package sample;
            import java.util.Map;
            import com.modporter.generated.sample.compat.Capability;
            import com.modporter.generated.sample.compat.LazyOptional;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.entity.BlockEntity;
            class Owner extends BlockEntity {
                Map<Direction, LazyOptional<Handler>> handlers;
                Provider provider;
                LazyOptional<Handler> itemHandler = LazyOptional.empty();
                boolean ready() { return true; }
                boolean isTarget(Capability<?> capability) { return true; }
                static boolean exposes(Object level, Object pos, Object state, Direction side) { return true; }
                boolean exactGuard(Direction side) {
                    return side == null || exposes(level, worldPosition, getBlockState(), side);
                }
                void initialize() { itemHandler = LazyOptional.of(Handler::new); }
                <T> LazyOptional<T> mapProvider(Capability<T> capability, Direction side) {
                    return handlers.get(side).cast();
                }
                <T> LazyOptional<T> delegatedProvider(Capability<T> capability, Direction side) {
                    return provider.getProvider(capability, side);
                }
                <T> LazyOptional<T> rejectedProvider(Capability<T> capability, Direction side) {
                    return provider.getProviderUsingCapability(capability, side);
                }
                <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (!isTarget(capability))
                        return super.getCapability(capability, side);
                    if (!ready())
                        return super.getCapability(capability, side);
                    if (!itemHandler.isPresent())
                        initialize();
                    return itemHandler.cast();
                }
            }
            class Handler {}
        """.trimIndent())
        val resolver = ExactLegacyLazyOptionalProviderResolver(tempDir.resolve("src/main/java"))

        assertEquals(
            "blockEntity.handlers.get(side).orElse(null)",
            resolver.resolve(owner, "sample.Owner", "mapProvider", "handlers.get(side).cast()", "capability", "side")
        )
        assertEquals(
            "blockEntity.provider.getProvider(null, side).orElse(null)",
            resolver.resolve(
                owner,
                "sample.Owner",
                "delegatedProvider",
                "provider.getProvider(capability, side)",
                "capability",
                "side"
            )
        )
        assertNull(
            resolver.resolve(
                owner,
                "sample.Owner",
                "rejectedProvider",
                "provider.getProviderUsingCapability(capability, side)",
                "capability",
                "side"
            )
        )
        assertEquals(
            "blockEntity.itemHandler.orElse(null)",
            resolver.resolve(
                owner,
                "sample.Owner",
                "getCapability",
                "itemHandler.cast()",
                "capability",
                "side"
            )
        )
        val guarded = resolver.resolveGuardedTail(
            owner,
            "sample.Owner",
            "getCapability",
            "isTarget",
            "capability",
            "side"
        )
        assertEquals(true, guarded?.contains("if (!blockEntity.ready())"), guarded)
        assertEquals(true, guarded?.contains("return null;"), guarded)
        assertEquals(true, guarded?.contains("blockEntity.initialize();"), guarded)
        assertEquals(true, guarded?.contains("return blockEntity.itemHandler.orElse(null);"), guarded)
        assertEquals(
            "side == null || exposes(blockEntity.level, blockEntity.worldPosition, blockEntity.getBlockState(), side)",
            resolver.rewriteInstanceExpression(
                owner,
                "sample.Owner",
                "exactGuard",
                "side == null || exposes(level, worldPosition, getBlockState(), side)",
                mapOf("side" to "side")
            )
        )
    }

    private fun write(relative: String, source: String): Path {
        val file = tempDir.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source)
        return file
    }
}
