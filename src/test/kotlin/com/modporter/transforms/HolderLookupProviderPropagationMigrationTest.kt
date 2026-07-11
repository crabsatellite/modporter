package com.modporter.transforms

import com.modporter.core.transforms.shared.HolderLookupProviderPropagationMigration
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class HolderLookupProviderPropagationMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `threads provider future through static helper call graph from exact event type`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("RecipeGen.java").writeText(
            """
            package com.example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;

            public class RecipeGen {
                public RecipeGen(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
                }
            }
            """.trimIndent()
        )
        source.resolve("ProviderRegistry.java").writeText(
            """
            package com.example;

            import net.minecraft.data.PackOutput;

            public class ProviderRegistry {
                public static void register(PackOutput output) {
                    new RecipeGen(output);
                }
            }
            """.trimIndent()
        )
        source.resolve("ModData.java").writeText(
            """
            package com.example;

            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gather(GatherDataEvent dataEvent) {
                    ProviderRegistry.register(dataEvent.getGenerator().getPackOutput());
                }
            }
            """.trimIndent()
        )
        source.resolve("UnrelatedOverloads.java").writeText(
            """
            package com.example;
            public class UnrelatedOverloads {
                static void map(String a, String b, String c, String d) {}
                static void map(int a, int b, int c, int d) {}
            }
            """.trimIndent()
        )

        val changes = HolderLookupProviderPropagationMigration().migrate(tempDir, dryRun = false)
        val registry = source.resolve("ProviderRegistry.java").readText()
        val data = source.resolve("ModData.java").readText()

        assertTrue(changes.any { it.ruleId == "shared-holderlookup-provider-call-graph" })
        assertTrue(
            registry.contains(
                "register(PackOutput output, java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> modporterLookupProvider)"
            ),
            registry
        )
        assertTrue(registry.contains("new RecipeGen(output, modporterLookupProvider);"), registry)
        assertTrue(
            data.contains(
                "ProviderRegistry.register(dataEvent.getGenerator().getPackOutput(), dataEvent.getLookupProvider());"
            ),
            data
        )
    }

    @Test
    fun `recursively threads provider through project local helper layers`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("RecipeGen.java").writeText(
            """
            package com.example;
            public class RecipeGen {
                public RecipeGen(net.minecraft.data.PackOutput output,
                    java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> registries) {
                }
            }
            """.trimIndent()
        )
        source.resolve("ProviderRegistry.java").writeText(
            """
            package com.example;
            public class ProviderRegistry {
                public static void register(net.minecraft.data.PackOutput output) {
                    new RecipeGen(output);
                }
            }
            """.trimIndent()
        )
        source.resolve("ProviderInstaller.java").writeText(
            """
            package com.example;
            public class ProviderInstaller {
                public static void install(net.minecraft.data.PackOutput output) {
                    ProviderRegistry.register(output);
                }
            }
            """.trimIndent()
        )
        source.resolve("ModData.java").writeText(
            """
            package com.example;
            import net.neoforged.neoforge.data.event.GatherDataEvent;
            public class ModData {
                public static void gather(GatherDataEvent event) {
                    ProviderInstaller.install(event.getGenerator().getPackOutput());
                }
            }
            """.trimIndent()
        )

        HolderLookupProviderPropagationMigration().migrate(tempDir, dryRun = false)
        val registry = source.resolve("ProviderRegistry.java").readText()
        val installer = source.resolve("ProviderInstaller.java").readText()
        val data = source.resolve("ModData.java").readText()

        assertTrue(registry.contains("new RecipeGen(output, modporterLookupProvider)"), registry)
        assertTrue(installer.contains("ProviderRegistry.register(output, modporterLookupProvider)"), installer)
        assertTrue(data.contains("ProviderInstaller.install(event.getGenerator().getPackOutput(), event.getLookupProvider())"), data)
    }

    @Test
    fun `uses exact local future without depending on its variable name`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("RecipeGen.java").writeText(
            """
            package com.example;
            public class RecipeGen {
                public RecipeGen(net.minecraft.data.PackOutput output,
                    java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> constructorOnly) {
                }
                public static void register(net.minecraft.data.PackOutput output) {
                    new RecipeGen(output);
                }
            }
            """.trimIndent()
        )
        source.resolve("ModData.java").writeText(
            """
            package com.example;
            public class ModData {
                public static void gather(
                    net.minecraft.data.PackOutput output,
                    java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> registryFuture) {
                    RecipeGen.register(output);
                }
            }
            """.trimIndent()
        )

        HolderLookupProviderPropagationMigration().migrate(tempDir, dryRun = false)
        val provider = source.resolve("RecipeGen.java").readText()
        val data = source.resolve("ModData.java").readText()

        assertTrue(provider.contains("new RecipeGen(output, modporterLookupProvider)"), provider)
        assertTrue(data.contains("RecipeGen.register(output, registryFuture)"), data)
        assertTrue(!provider.contains("new RecipeGen(output, constructorOnly)"), provider)
    }

    @Test
    fun `hard fails when the project call graph has no typed provider source`() {
        val source = tempDir.resolve("src/main/java/com/example").createDirectories()
        source.resolve("RecipeGen.java").writeText(
            """
            package com.example;
            public class RecipeGen {
                public RecipeGen(net.minecraft.data.PackOutput output,
                    java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> registries) {
                }
            }
            """.trimIndent()
        )
        source.resolve("ProviderRegistry.java").writeText(
            """
            package com.example;
            public class ProviderRegistry {
                public static void register(net.minecraft.data.PackOutput output) {
                    new RecipeGen(output);
                }
            }
            """.trimIndent()
        )

        val error = assertThrows(IllegalStateException::class.java) {
            HolderLookupProviderPropagationMigration().migrate(tempDir, dryRun = false)
        }
        assertTrue(error.message.orEmpty().contains("no HolderLookup.Provider fallback is allowed"), error.message)
    }
}
