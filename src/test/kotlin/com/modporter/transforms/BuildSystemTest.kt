package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildSystemTest {

    @TempDir
    lateinit var tempDir: Path

    private val pass = BuildSystemPass()

    @Test
    fun `replaces ForgeGradle plugin with NeoForge ModDev`() {
        val projectDir = tempDir.resolve("p1")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(content.contains("net.neoforged.moddev"))
        assertFalse(content.contains("net.minecraftforge.gradle"))
    }

    @Test
    fun `replaces Forge Maven repository URL`() {
        val projectDir = tempDir.resolve("p2")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(content.contains("maven.neoforged.net"))
        assertFalse(content.contains("maven.minecraftforge.net"))
    }

    @Test
    fun `adds content filter to Tamaized repository`() {
        val projectDir = tempDir.resolve("p2-tamaized-filter")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            repositories {
                maven {
                    name 'Tama Maven'
                    url "https://maven.tamaized.com/releases"
                }
                maven {
                    name 'Curseforge Maven'
                    url "https://www.cursemaven.com"
                    content {
                        includeGroup "curse.maven"
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-repository-content-filter" })
        assertTrue(content.contains("includeGroup \"tamaized\""))
        assertTrue(content.contains("includeGroup \"curse.maven\""))
    }

    @Test
    fun `replaces Forge dependency with comment`() {
        val projectDir = tempDir.resolve("p3")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
                minecraft "net.neoforged:forge:${'$'}{project.minecraft_version}-${'$'}{project.neo_version}"
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertFalse(content.contains("net.minecraftforge:forge"))
        assertFalse(content.contains("net.neoforged:forge"))
        assertTrue(content.contains("neoForge"))
    }

    @Test
    fun `replaces minecraft block with neoForge block`() {
        val projectDir = tempDir.resolve("p4")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertFalse(content.contains("minecraft {"))
        assertTrue(content.contains("neoForge {"))
        assertTrue(content.contains("parchment"))
    }

    @Test
    fun `transforms settings gradle`() {
        val projectDir = tempDir.resolve("p5")
        projectDir.createDirectories()
        projectDir.resolve("settings.gradle").writeText("""
            pluginManagement {
                repositories {
                    maven { url = 'https://maven.minecraftforge.net/' }
                }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("settings.gradle").readText()
        assertTrue(content.contains("maven.neoforged.net"))
        assertFalse(content.contains("maven.minecraftforge.net"))
    }

    @Test
    fun `transforms gradle properties`() {
        val projectDir = tempDir.resolve("p6")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("""
            base_minecraft_version=1.20
            minecraft_version=1.20.1
            forge_version=47.2.0
            mod_id=testmod
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()
        assertTrue(content.contains("base_minecraft_version=1.21"))
        assertTrue(content.contains("minecraft_version=1.21.1"))
        assertTrue(content.contains("neo_forge_version=21.1.230"))
        assertFalse(Regex("""(?<!\w)forge_version\s*=""").containsMatchIn(content),
            "Should not have standalone forge_version property")
    }

    @Test
    fun `normalizes neoforge properties without double prefixing`() {
        val projectDir = tempDir.resolve("neoforge-props")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            neoforge_version=47.1.0
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()

        assertTrue(content.contains("neo_forge_version=21.1.230"), content)
        assertFalse(content.contains("neoneo_forge_version"), content)
    }

    @Test
    fun `normalizes old minecraft version property references in build script`() {
        val projectDir = tempDir.resolve("mc-version-build-props")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            version = "${'$'}{mc_version}-${'$'}{mod_version}-neoforge"
            dependencies {
                implementation "example:dep:${'$'}{project.mc_version}"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(content.contains("""version = "${'$'}{minecraft_version}-${'$'}{mod_version}-neoforge""""), content)
        assertTrue(content.contains("""implementation "example:dep:${'$'}{project.minecraft_version}""""), content)
        assertFalse(content.contains("mc_version"), content)
    }

    @Test
    fun `updates known dependency version properties from mapping database`() {
        val projectDir = tempDir.resolve("known-dependency-version-props")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            nitrogen_version=1.20.1-1.0.12-neoforge
            cumulus_version=1.20.1-1.0.1-neoforge
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.changes.any { it.ruleId == "build-props-dependency-version" })
        assertTrue(content.contains("nitrogen_version=1.21.1-1.1.25-neoforge"), content)
        assertTrue(content.contains("cumulus_version=1.21.1-2.0.8-neoforge"), content)
        assertFalse(content.contains("1.20.1-1.0.12-neoforge"), content)
        assertFalse(content.contains("1.20.1-1.0.1-neoforge"), content)
    }

    @Test
    fun `migrates coremod ASMAPI scripts to NeoForge package`() {
        val projectDir = tempDir.resolve("coremod-asmapi")
        val asmDir = projectDir.resolve("src/main/resources/META-INF/asm")
        asmDir.createDirectories()
        asmDir.resolve("seed.js").writeText("""
            var ASM = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            var Opcodes = Java.type('org.objectweb.asm.Opcodes');
        """.trimIndent())

        val result = pass.apply(projectDir)
        val script = asmDir.resolve("seed.js").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-coremod-asmapi-neoforge" })
        assertTrue(script.contains("net.neoforged.coremod.api.ASMAPI"))
        assertFalse(script.contains("net.minecraftforge.coremod.api.ASMAPI"))
    }

    @Test
    fun `registers animal spawn placement on mod bus`() {
        val projectDir = tempDir.resolve("spawn-placement")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    EntityRegistry.ENTITY_TYPES.register(modEventBus);
                    modEventBus.addListener(EntityRegistry::registerAttributes);
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityRegistry.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;
            import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class EntityRegistry {
                public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
                    DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);

                public static final DeferredHolder<EntityType<?>, EntityType<DeerEntity>> DEER = ENTITY_TYPES.register("deer",
                    () -> EntityType.Builder.of(DeerEntity::new, MobCategory.CREATURE)
                        .sized(0.9F, 0.95F).build("deer"));

                public static void registerAttributes(EntityAttributeCreationEvent event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("DeerEntity.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.animal.Animal;
            import net.minecraft.world.level.Level;

            public class DeerEntity extends Animal {
                public DeerEntity(EntityType<? extends DeerEntity> type, Level level) {
                    super(type, level);
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val registry = srcDir.resolve("EntityRegistry.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()
        assertTrue(registry.contains("import net.minecraft.world.entity.SpawnPlacementTypes;"))
        assertTrue(registry.contains("import net.minecraft.world.level.levelgen.Heightmap;"))
        assertTrue(registry.contains("import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;"))
        assertTrue(registry.contains("public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event)"))
        assertTrue(registry.contains("event.register(DEER.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Animal::checkAnimalSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);"))
        assertTrue(mod.contains("modEventBus.addListener(com.example.EntityRegistry::registerSpawnPlacements);"))
    }

    @Test
    fun `registers existing spawn placement method on arbitrary mod bus variable`() {
        val projectDir = tempDir.resolve("spawn-placement-existing-method")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modbus = modContainer.getEventBus();
                    EntityRegistry.ENTITY_TYPES.register(modbus);
                    modbus.addListener(com.example.EntityRegistry::addEntityAttributes);
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityRegistry.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
            import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class EntityRegistry {
                public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
                    DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);

                public static void addEntityAttributes(EntityAttributeCreationEvent event) {
                }

                public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val mod = srcDir.resolve("ExampleMod.java").readText()
        assertTrue(mod.contains("modbus.addListener(com.example.EntityRegistry::addEntityAttributes);"))
        assertTrue(mod.contains("modbus.addListener(com.example.EntityRegistry::registerSpawnPlacements);"))
    }

    @Test
    fun `registers spawn placement listener outside guarded client listener block`() {
        val projectDir = tempDir.resolve("spawn-placement-client-guard")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val clientDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        clientDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.client.ClientRegistry;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(ClientRegistry::registerClientStuff);
                    EntityRegistry.ENTITY_TYPES.register(modEventBus);
                    modEventBus.addListener(EntityRegistry::registerAttributes);
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityRegistry.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;
            import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class EntityRegistry {
                public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
                    DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);

                public static final DeferredHolder<EntityType<?>, EntityType<DeerEntity>> DEER = ENTITY_TYPES.register("deer",
                    () -> EntityType.Builder.of(DeerEntity::new, MobCategory.CREATURE)
                        .sized(0.9F, 0.95F).build("deer"));

                public static void registerAttributes(EntityAttributeCreationEvent event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("DeerEntity.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.animal.Animal;
            import net.minecraft.world.level.Level;

            public class DeerEntity extends Animal {
                public DeerEntity(EntityType<? extends DeerEntity> type, Level level) {
                    super(type, level);
                }
            }
        """.trimIndent())
        clientDir.resolve("ClientRegistry.java").writeText("""
            package com.example.client;

            import net.minecraft.client.Minecraft;

            public class ClientRegistry {
                public static void registerClientStuff(Object event) {
                    Minecraft.getInstance();
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val mod = srcDir.resolve("ExampleMod.java").readText()
        val guardIdx = mod.indexOf("if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT)")
        val spawnIdx = mod.indexOf("modEventBus.addListener(com.example.EntityRegistry::registerSpawnPlacements);")

        assertTrue(guardIdx >= 0, mod)
        assertTrue(spawnIdx >= 0, mod)
        assertTrue(spawnIdx < guardIdx, mod)
    }

    @Test
    fun `migrates structure template pool reflection fields to access transformers`() {
        val projectDir = tempDir.resolve("structure-pool-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("VillageStructures.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;
            import java.lang.reflect.Field;

            public class VillageStructures {
                void add(StructureTemplatePool pool) throws Exception {
                    Field templatesField = ObfuscationReflectionHelper.findField(
                            StructureTemplatePool.class, "f_210560_"); // templates
                    Field rawTemplatesField = ObfuscationReflectionHelper.findField(
                            StructureTemplatePool.class, "f_210559_"); // rawTemplates
                    Object templates = templatesField.get(pool);
                    Object rawTemplates = rawTemplatesField.get(pool);
                    templatesField.set(pool, templates);
                    rawTemplatesField.set(pool, rawTemplates);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("VillageStructures.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-structure-pool-at-direct-fields" })
        assertTrue(content.contains("Object templates = pool.templates;"))
        assertTrue(content.contains("Object rawTemplates = pool.rawTemplates;"))
        assertTrue(content.contains("pool.templates = templates;"))
        assertTrue(content.contains("pool.rawTemplates = rawTemplates;"))
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"))
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"))
        assertFalse(content.contains("f_210560_"))
        assertFalse(content.contains("f_210559_"))
        assertFalse(content.contains("ObfuscationReflectionHelper"))
        assertFalse(content.contains("getDeclaredField"))
        assertFalse(content.contains("setAccessible"))
    }

    @Test
    fun `adds structure pool access transformers only for typed direct field access`() {
        val projectDir = tempDir.resolve("structure-pool-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("PoolAccess.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

            public class PoolAccess {
                void patch(StructureTemplatePool pool) {
                    Object templates = pool.templates;
                    pool.rawTemplates = pool.rawTemplates;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"), at)
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"), at)
    }

    @Test
    fun `structure pool access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("structure-pool-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("PoolNotes.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

            public class PoolNotes {
                String note = "StructureTemplatePool pool.templates pool.rawTemplates";

                void describe() {
                    // StructureTemplatePool pool; pool.templates; pool.rawTemplates;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `adds goal selector and server level access transformers only for typed member access`() {
        val projectDir = tempDir.resolve("goal-lightning-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ServerHooks.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.ai.goal.GoalSelector;

            public class ServerHooks {
                void patch(GoalSelector selector, ServerLevel level, BlockPos origin) {
                    selector.availableGoals.clear();
                    level.findLightningTargetAround(origin);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals"), at)
        assertTrue(
            at.contains("public net.minecraft.server.level.ServerLevel findLightningTargetAround(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"),
            at
        )
    }

    @Test
    fun `goal selector and server level access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("goal-lightning-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ServerNotes.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.ai.goal.GoalSelector;

            public class ServerNotes {
                String note = "GoalSelector selector; selector.availableGoals; ServerLevel level; level.findLightningTargetAround(origin);";

                void describe() {
                    // GoalSelector selector; selector.availableGoals.clear();
                    // ServerLevel level; level.findLightningTargetAround(origin);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `adds creative selected tab access transformer only for static field access`() {
        val projectDir = tempDir.resolve("creative-selectedtab-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CreativeDirectAccess.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
            import net.minecraft.world.item.CreativeModeTab;

            public class CreativeDirectAccess {
                CreativeModeTab selected() {
                    return CreativeModeInventoryScreen.selectedTab;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab"), at)
    }

    @Test
    fun `creative selected tab access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("creative-selectedtab-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CreativeNotes.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

            public class CreativeNotes {
                String note = "CreativeModeInventoryScreen.selectedTab";

                void describe() {
                    // CreativeModeInventoryScreen.selectedTab
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `adds level renderer access transformers only for typed field access`() {
        val projectDir = tempDir.resolve("level-renderer-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RendererAccess.java").writeText("""
            package com.example;

            import net.minecraft.client.renderer.LevelRenderer;

            public class RendererAccess {
                void capture(LevelRenderer renderer) {
                    Object sky = renderer.skyBuffer;
                    Object dark = renderer.darkBuffer;
                    int rain = renderer.rainSoundTime;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.renderer.LevelRenderer skyBuffer"), at)
        assertTrue(at.contains("public net.minecraft.client.renderer.LevelRenderer darkBuffer"), at)
        assertTrue(at.contains("public net.minecraft.client.renderer.LevelRenderer rainSoundTime"), at)
    }

    @Test
    fun `level renderer access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("level-renderer-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RendererNotes.java").writeText("""
            package com.example;

            import net.minecraft.client.renderer.LevelRenderer;

            public class RendererNotes {
                String note = "LevelRenderer renderer; renderer.skyBuffer; renderer.darkBuffer; renderer.rainSoundTime;";

                void describe() {
                    // LevelRenderer renderer; renderer.skyBuffer; renderer.darkBuffer; renderer.rainSoundTime;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `migrates pending block entity reflection field to access transformer`() {
        val projectDir = tempDir.resolve("pending-be-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ChunkCleaner.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.chunk.LevelChunk;
            import java.lang.reflect.Field;
            import java.util.Map;

            public class ChunkCleaner {
                private static Field pendingBlockEntitiesField = null;
                private static boolean pendingBEFieldInitialized = false;

                private static void clearPendingBlockEntities(LevelChunk chunk) {
                    try {
                        if (!pendingBEFieldInitialized) {
                            pendingBEFieldInitialized = true;
                            for (java.lang.reflect.Field field : LevelChunk.class.getDeclaredFields()) {
                                if (java.util.Map.class.isAssignableFrom(field.getType())) {
                                    field.setAccessible(true);
                                    Object testValue = field.get(chunk);
                                    if (testValue instanceof java.util.Map<?, ?> testMap && !testMap.isEmpty()) {
                                        Object firstKey = testMap.keySet().iterator().next();
                                        if (firstKey instanceof BlockPos) {
                                            pendingBlockEntitiesField = field;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (pendingBlockEntitiesField != null) {
                            Object value = pendingBlockEntitiesField.get(chunk);
                            if (value instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                                int count = map.size();
                                map.clear();
                                if (count > 0) {
                                    ExampleMod.LOGGER.debug("Cleared {} pending block entities from chunk", count);
                                }
                            }
                        }
                    } catch (Exception e) {
                        ExampleMod.LOGGER.debug("Could not clear pending BEs via reflection: {}", e.getMessage());
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("ChunkCleaner.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-levelchunk-pending-blockentities-at" })
        assertTrue(content.contains("Map<BlockPos, CompoundTag> pendingBlockEntities = chunk.pendingBlockEntities;"))
        assertTrue(content.contains("pendingBlockEntities.clear();"))
        assertTrue(at.contains("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities"))
        assertFalse(content.contains("java.lang.reflect"))
        assertFalse(content.contains("Field "))
        assertFalse(content.contains("getDeclaredFields"))
        assertFalse(content.contains("setAccessible"))
    }

    @Test
    fun `adds pending block entity access transformer only for typed chunk field access`() {
        val projectDir = tempDir.resolve("pending-be-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ChunkDirectAccess.java").writeText("""
            package com.example;

            import net.minecraft.world.level.chunk.LevelChunk;

            public class ChunkDirectAccess {
                void clear(LevelChunk chunk) {
                    chunk.pendingBlockEntities.clear();
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities"), at)
    }

    @Test
    fun `pending block entity access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("pending-be-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ChunkNotes.java").writeText("""
            package com.example;

            import net.minecraft.world.level.chunk.LevelChunk;

            public class ChunkNotes {
                String note = "LevelChunk chunk; chunk.pendingBlockEntities.clear();";

                void describe() {
                    // LevelChunk chunk; chunk.pendingBlockEntities.clear();
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `migrates entity visibility method reflection to vanilla boss type check`() {
        val projectDir = tempDir.resolve("entity-visibility-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("BossVisibility.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.Entity;
            import java.lang.reflect.Method;

            public class BossVisibility {
                private static boolean hasNativePlayerVisibilityHook(Entity entity) {
                    return declaresVisibilityHook(entity.getClass(), "startSeenByPlayer")
                            || declaresVisibilityHook(entity.getClass(), "stopSeenByPlayer");
                }

                private static boolean declaresVisibilityHook(Class<?> entityClass, String methodName) {
                    try {
                        Method method = entityClass.getMethod(methodName, ServerPlayer.class);
                        return method.getDeclaringClass() != Entity.class;
                    } catch (ReflectiveOperationException e) {
                        return true;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("BossVisibility.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-entity-visibility-hook-no-reflection" })
        assertTrue(content.contains("import net.minecraft.world.entity.boss.wither.WitherBoss;"), content)
        assertTrue(content.contains("return entity instanceof WitherBoss;"), content)
        assertFalse(content.contains("declaresVisibilityHook"))
        assertFalse(content.contains("getMethod"))
        assertFalse(content.contains("java.lang.reflect"))
        assertFalse(content.contains("ServerPlayer"))
    }

    @Test
    fun `registers existing mixin configs when migrating away from manifest registration`() {
        val projectDir = tempDir.resolve("existing-mixin-config-registration")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            tasks.named('jar', Jar).configure {
                manifest {
                    attributes([
                        "MixinConfigs": "example.mixins.json"
                    ])
                }
            }
        """.trimIndent())
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"
        """.trimIndent())
        resourcesDir.resolve("example.mixins.json").writeText("""
            {
              "required": true,
              "package": "com.example.mixin",
              "mixins": [
                "BlockAccessor"
              ]
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val buildGradle = projectDir.resolve("build.gradle").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-register-existing-mixin-config" })
        assertFalse(buildGradle.contains("MixinConfigs"), buildGradle)
        assertTrue(modsToml.contains("[[mixins]]"), modsToml)
        assertTrue(modsToml.contains("config=\"example.mixins.json\""), modsToml)
    }

    @Test
    fun `migrates class for name compat checks to static API checks`() {
        val projectDir = tempDir.resolve("class-for-name-compat")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"
        """.trimIndent())
        resourcesDir.resolve("example.mixins.json").writeText("""
            {
              "required": false,
              "package": "com.example",
              "mixins": [
                "FluidMixin"
              ]
            }
        """.trimIndent())
        srcDir.resolve("CompatManager.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.common.NeoForge;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public class CompatManager {
                private static final Logger LOGGER = LoggerFactory.getLogger(CompatManager.class);

                public static void registerEventHandlers(String modId, Class<?>... handlerClasses) {
                    for (Class<?> handlerClass : handlerClasses) {
                        try {
                            Class.forName(handlerClass.getName(), true, handlerClass.getClassLoader());
                            NeoForge.EVENT_BUS.register(handlerClass);
                        } catch (Throwable e) {
                            throw new RuntimeException("Event handler registration failed", e);
                        }
                    }
                }

                public static void verifyApiClasses(String modId, String... classNames) {
                    for (String className : classNames) {
                        try {
                            Class.forName(className);
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException("Missing API class " + className, e);
                        }
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val manager = srcDir.resolve("CompatManager.java").readText()
        val mixin = srcDir.resolve("FluidMixin.java").readText()
        val accessor = srcDir.resolve("modporter/mixin/ModPorterSpaceTypeAccessor.java").readText()
        val generatedMixinConfig = resourcesDir.resolve("examplemod.modporter.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-enum-mixin-invoker" })
        assertTrue(result.changes.any { it.ruleId == "build-generated-mixin-config" })
        assertTrue(manager.contains("NeoForge.EVENT_BUS.register(handlerClass);"), manager)
        assertTrue(manager.contains("ModList.get().isLoaded(modId)"), manager)
        assertTrue(manager.contains("import net.neoforged.fml.ModList;"), manager)
        assertTrue(mixin.contains("import com.example.modporter.mixin.ModPorterSpaceTypeAccessor;"), mixin)
        assertTrue(mixin.contains("return ModPorterSpaceTypeAccessor.modporter${'$'}valueOf(\"BLOCKING\");"), mixin)
        assertTrue(accessor.contains("package com.example.modporter.mixin;"), accessor)
        assertTrue(accessor.contains("@Mixin(targets = \"com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType\", remap = false)"), accessor)
        assertTrue(accessor.contains("@Invoker(value = \"valueOf\", remap = false)"), accessor)
        assertTrue(generatedMixinConfig.contains("\"package\": \"com.example.modporter.mixin\""), generatedMixinConfig)
        assertTrue(generatedMixinConfig.contains("\"ModPorterSpaceTypeAccessor\""), generatedMixinConfig)
        assertTrue(modsToml.contains("config=\"examplemod.modporter.mixins.json\""), modsToml)
        assertFalse(manager.contains("Class.forName"))
        assertFalse(mixin.contains("Class.forName"))
        assertFalse(mixin.contains("Enum.valueOf"))
        assertFalse(mixin.contains("@SuppressWarnings"))
    }

    @Test
    fun `does not rewrite API verifier when class-for-name appears only in comments or strings`() {
        val projectDir = tempDir.resolve("class-for-name-api-verifier-comment-string")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("CompatManager.java").writeText("""
            package com.example;

            public class CompatManager {
                public static void verifyApiClasses(String modId, String... classNames) {
                    String marker = "Class.forName(";
                    // Class.forName(classNames[0]);
                    if (marker.isEmpty()) {
                        throw new IllegalStateException("unreachable");
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("CompatManager.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(source.contains("""String marker = "Class.forName(";"""), source)
        assertTrue(source.contains("// Class.forName(classNames[0]);"), source)
        assertFalse(source.contains("ModList.get().isLoaded(modId)"), source)
        assertFalse(source.contains("import net.neoforged.fml.ModList;"), source)
    }

    @Test
    fun `generated mixin config resolves templated mods toml mod id from gradle properties`() {
        val projectDir = tempDir.resolve("templated-generated-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=hotbath\n")
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="${'$'}{loader_version_range}"

            [[mods]]
            modId="${'$'}{mod_id}"
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(resourcesDir.resolve("hotbath.modporter.mixins.json").exists())
        assertFalse(resourcesDir.resolve("${'$'}{mod_id}.modporter.mixins.json").exists())
        assertTrue(modsToml.contains("config=\"hotbath.modporter.mixins.json\""), modsToml)
        assertFalse(modsToml.contains("config=\"${'$'}{mod_id}.modporter.mixins.json\""), modsToml)
    }

    @Test
    fun `generated mixin invoker joins conditional parent mixin config`() {
        val projectDir = tempDir.resolve("conditional-parent-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"
        """.trimIndent())
        resourcesDir.resolve("example.create.mixins.json").writeText("""
            {
              "required": false,
              "minVersion": "0.8",
              "package": "com.example",
              "compatibilityLevel": "JAVA_21",
              "plugin": "com.example.CreateMixinPlugin",
              "mixins": [
              ],
              "injectors": {
                "defaultRequire": 0
              }
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val conditionalConfig = resourcesDir.resolve("example.create.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(conditionalConfig.contains("\"modporter.mixin.ModPorterSpaceTypeAccessor\""), conditionalConfig)
        assertFalse(resourcesDir.resolve("examplemod.modporter.mixins.json").exists())
        assertFalse(modsToml.contains("examplemod.modporter.mixins.json"), modsToml)
    }

    @Test
    fun `generated mixin invoker prefers most specific conditional mixin config`() {
        val projectDir = tempDir.resolve("specific-conditional-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example/create")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"

            [[mixins]]
            config="example.mixins.json"

            [[mixins]]
            config="example.create.mixins.json"
        """.trimIndent())
        resourcesDir.resolve("example.mixins.json").writeText("""
            {
              "required": false,
              "package": "com.example",
              "plugin": "com.example.RootMixinPlugin",
              "mixins": [
                "ExistingMixin",
                "create.modporter.mixin.ModPorterSpaceTypeAccessor"
              ]
            }
        """.trimIndent())
        resourcesDir.resolve("example.create.mixins.json").writeText("""
            {
              "required": false,
              "minVersion": "0.8",
              "package": "com.example.create",
              "compatibilityLevel": "JAVA_21",
              "plugin": "com.example.create.CreateMixinPlugin",
              "mixins": [
              ],
              "injectors": {
                "defaultRequire": 0
              }
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example.create;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val parentConfig = resourcesDir.resolve("example.mixins.json").readText()
        val createConfig = resourcesDir.resolve("example.create.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(createConfig.contains("\"modporter.mixin.ModPorterSpaceTypeAccessor\""), createConfig)
        assertFalse(parentConfig.contains("create.modporter.mixin.ModPorterSpaceTypeAccessor"), parentConfig)
        assertFalse(resourcesDir.resolve("examplemod.modporter.mixins.json").exists())
        assertFalse(modsToml.contains("examplemod.modporter.mixins.json"), modsToml)
    }

    @Test
    fun `adds compile only dependency for reflected optional API packages`() {
        val projectDir = tempDir.resolve("reflected-optional-api-dep")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation "com.example:kept:1.0.0"
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            public class SeasonCompat {
                void resolve() throws Exception {
                    Class.forName("sereneseasons.api.season.SeasonHelper");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-reflected-optional-api-dependencies" })
        assertTrue(content.contains("""compileOnly "curse.maven:serene-seasons-291874:6182596""""))
        assertTrue(content.contains("https://www.cursemaven.com"))
        assertTrue(content.contains("""includeGroup "curse.maven""""))
    }

    @Test
    fun `reflected optional API dependency scan ignores comments`() {
        val projectDir = tempDir.resolve("reflected-optional-api-comment")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
                implementation "com.example:kept:1.0.0"
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            public class SeasonCompat {
                void resolve() {
                    // Class.forName("sereneseasons.api.season.SeasonHelper");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-reflected-optional-api-dependencies" })
        assertFalse(content.contains("serene-seasons"), content)
        assertFalse(content.contains("cursemaven"), content)
    }

    @Test
    fun `rewrites class for name isInstance checks without member reflection`() {
        val projectDir = tempDir.resolve("class-for-name-isinstance")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExternalEntityCompat.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.Entity;

            public class ExternalEntityCompat {
                private static Class<?> targetClass = null;
                private static boolean targetClassResolved = false;

                public static boolean isExternalEntity(Entity entity) {
                    try {
                        if (!targetClassResolved) {
                            targetClassResolved = true;
                            targetClass = Class.forName("example.optional.ExternalEntity");
                        }
                        return targetClass != null && targetClass.isInstance(entity);
                    } catch (Exception ignored) {
                        targetClassResolved = true;
                        return false;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExternalEntityCompat.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("""return modporterRuntimeInstanceOf(entity, "example.optional.ExternalEntity");"""), content)
        assertTrue(content.contains("private static boolean modporterRuntimeInstanceOf(Object value, String binaryClassName)"))
        assertFalse(content.contains("Class.forName"))
        assertFalse(content.contains("isInstance("))
        assertFalse(content.contains("targetClassResolved"))
    }

    @Test
    fun `class for name isInstance migration ignores commented reflection flow`() {
        val projectDir = tempDir.resolve("class-for-name-isinstance-comment")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExternalEntityCompat.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.Entity;

            public class ExternalEntityCompat {
                private static Class<?> targetClass = null;

                // try {
                //     targetClass = Class.forName("example.optional.ExternalEntity");
                //     return targetClass != null && targetClass.isInstance(entity);
                // } catch (Exception ignored) {
                //     return false;
                // }
                public static boolean isExternalEntity(Entity entity) {
                    return false;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExternalEntityCompat.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("return false;"), content)
        assertFalse(content.contains("modporterRuntimeInstanceOf"), content)
    }

    @Test
    fun `rewrites season helper reflection to static optional API calls`() {
        val projectDir = tempDir.resolve("season-helper-static-api")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            import java.lang.reflect.Method;
            import net.minecraft.world.level.Level;

            public class SeasonCompat {
                public enum WinterSubSeason { NONE, EARLY, MID, LATE }

                private static boolean seasonResolved = false;
                private static Method seasonHelperGetState;
                private static Method seasonStateGetSubSeason;
                private static Method subSeasonName;

                private static void resolveSeasonApi() {
                    if (seasonResolved) return;
                    seasonResolved = true;
                    try {
                        Class<?> seasonHelper = Class.forName("sereneseasons.api.season.SeasonHelper");
                        seasonHelperGetState = seasonHelper.getMethod("getSeasonState", Level.class);
                        Class<?> iSeasonState = Class.forName("sereneseasons.api.season.ISeasonState");
                        seasonStateGetSubSeason = iSeasonState.getMethod("getSubSeason");
                        Class<?> subSeason = Class.forName("sereneseasons.api.season.Season${'$'}SubSeason");
                        subSeasonName = subSeason.getMethod("name");
                    } catch (Exception ignored) {
                        seasonHelperGetState = null;
                    }
                }

                public static WinterSubSeason getWinterSubSeason(Level level) {
                    resolveSeasonApi();
                    if (seasonHelperGetState == null) return WinterSubSeason.NONE;
                    try {
                        Object state = seasonHelperGetState.invoke(null, level);
                        if (state == null) return WinterSubSeason.NONE;
                        Object sub = seasonStateGetSubSeason.invoke(state);
                        if (sub == null) return WinterSubSeason.NONE;
                        String name = (String) subSeasonName.invoke(sub);
                        if ("EARLY_WINTER".equals(name)) return WinterSubSeason.EARLY;
                        return WinterSubSeason.NONE;
                    } catch (Exception ignored) {
                        return WinterSubSeason.NONE;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("SeasonCompat.java").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("import sereneseasons.api.season.ISeasonState;"), content)
        assertTrue(content.contains("import sereneseasons.api.season.Season.SubSeason;"), content)
        assertTrue(content.contains("import sereneseasons.api.season.SeasonHelper;"), content)
        assertTrue(content.contains("ISeasonState state = SeasonHelper.getSeasonState(level);"), content)
        assertTrue(content.contains("SubSeason sub = state.getSubSeason();"), content)
        assertTrue(content.contains("String name = sub.name();"), content)
        assertTrue(content.contains("if (state == null) return WinterSubSeason.NONE;"), content)
        assertTrue(content.contains("if (sub == null) return WinterSubSeason.NONE;"), content)
        assertFalse(content.contains("Season.SubSeason sub"), content)
        assertTrue(build.contains("""compileOnly "curse.maven:serene-seasons-291874:6182596""""))
        assertFalse(content.contains("Class.forName"))
        assertFalse(content.contains(".getMethod("))
        assertFalse(content.contains("java.lang.reflect.Method"))
        assertFalse(content.contains(".invoke("))
    }

    @Test
    fun `season helper reflection migration ignores commented reflection flow`() {
        val projectDir = tempDir.resolve("season-helper-comment")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            import net.minecraft.world.level.Level;

            public class SeasonCompat {
                public enum WinterSubSeason { NONE, EARLY, MID, LATE }

                private static void resolveSeasonApi() {
                    // Class<?> seasonHelper = Class.forName("sereneseasons.api.season.SeasonHelper");
                    // seasonHelperGetState = seasonHelper.getMethod("getSeasonState", Level.class);
                    // Class<?> iSeasonState = Class.forName("sereneseasons.api.season.ISeasonState");
                    // seasonStateGetSubSeason = iSeasonState.getMethod("getSubSeason");
                    // Class<?> subSeason = Class.forName("sereneseasons.api.season.Season${'$'}SubSeason");
                    // subSeasonName = subSeason.getMethod("name");
                }

                public static WinterSubSeason getWinterSubSeason(Level level) {
                    return WinterSubSeason.NONE;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("SeasonCompat.java").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(result.changes.any { it.ruleId == "build-reflected-optional-api-dependencies" })
        assertFalse(content.contains("SeasonHelper.getSeasonState"), content)
        assertFalse(build.contains("serene-seasons"), build)
    }

    @Test
    fun `rewrites DeferredHolder reflection collectors to explicit registry lists`() {
        val projectDir = tempDir.resolve("deferredholder-reflection-collector")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("BlockItemRegistry.java").writeText("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class BlockItemRegistry {
                public static final DeferredRegister<Item> ITEMS = null;
                public static final DeferredHolder<Item, Item> FIRST = ITEMS.register("first", Item::new);
                public static final DeferredHolder<Item, Item> SECOND = ITEMS.register("second", Item::new);
                private static final DeferredHolder<Item, Item> INTERNAL = ITEMS.register("internal", Item::new);
            }
        """.trimIndent())
        srcDir.resolve("BlockItemsTest.java").writeText("""
            package com.example;

            import java.lang.reflect.Field;
            import java.lang.reflect.Modifier;
            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class BlockItemsTest {
                @SuppressWarnings("unchecked")
                private static List<DeferredHolder<Item, Item>> collect() {
                    List<DeferredHolder<Item, Item>> out = new ArrayList<>();
                    for (Field f : BlockItemRegistry.class.getDeclaredFields()) {
                        int mods = f.getModifiers();
                        if (!(Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods))) continue;
                        if (!DeferredHolder.class.isAssignableFrom(f.getType())) continue;
                        try {
                            out.add((DeferredHolder<Item, Item>) f.get(null));
                        } catch (IllegalAccessException ignored) {}
                    }
                    return out;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("BlockItemsTest.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-deferredholder-reflection-collector" })
        assertTrue(content.contains("out.add(BlockItemRegistry.FIRST);"), content)
        assertTrue(content.contains("out.add(BlockItemRegistry.SECOND);"), content)
        assertFalse(content.contains("INTERNAL"), content)
        assertFalse(content.contains("java.lang.reflect"), content)
        assertFalse(content.contains("getDeclaredFields"), content)
    }

    @Test
    fun `migrates creative selected tab reflection to access transformer`() {
        val projectDir = tempDir.resolve("creative-selectedtab-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            neoForge {
                version = "21.1.219"
            }
        """.trimIndent())
        srcDir.resolve("CreativeFilters.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
            import net.minecraft.world.item.CreativeModeTab;
            import net.minecraft.world.item.CreativeModeTabs;

            public class CreativeFilters {
              private static CreativeModeTab getSelectedTab() {
                try {
                  java.lang.reflect.Field field =
                      CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                  field.setAccessible(true);
                  return (CreativeModeTab) field.get(null);
                } catch (ReflectiveOperationException | ClassCastException e) {
                  return CreativeModeTabs.getDefaultTab();
                }
              }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("CreativeFilters.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-creative-selectedtab-at" })
        assertTrue(content.contains("CreativeModeInventoryScreen.selectedTab"), content)
        assertTrue(at.contains("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab"))
        assertTrue(build.contains("validateAccessTransformers = true"), build)
        assertFalse(content.contains("getDeclaredField"))
        assertFalse(content.contains("setAccessible"))
        assertFalse(content.contains("java.lang.reflect"))
    }

    @Test
    fun `creative selected tab reflection migration ignores comments outside method evidence`() {
        val projectDir = tempDir.resolve("creative-selectedtab-comment-no-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("CreativeFilters.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
            import net.minecraft.world.item.CreativeModeTab;
            import net.minecraft.world.item.CreativeModeTabs;

            public class CreativeFilters {
              // CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
              // field.setAccessible(true);
              // field.get(null);

              private static CreativeModeTab getSelectedTab() {
                return CreativeModeTabs.getDefaultTab();
              }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("CreativeFilters.java").readText()
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-creative-selectedtab-at" })
        assertTrue(content.contains("return CreativeModeTabs.getDefaultTab();"), content)
        assertFalse(content.contains("CreativeModeInventoryScreen.selectedTab"), content)
        assertFalse(atFile.exists())
    }

    @Test
    fun `rewrites class-for-name presence checks to class resource probes`() {
        val projectDir = tempDir.resolve("class-for-name-presence")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example;

            public class ClientSetup {
                public static boolean optionalPresent = false;

                public static void setup() {
                    try {
                        Class.forName("net.optional.ExampleConfig");
                        optionalPresent = true;
                    } catch (ClassNotFoundException e) {
                        optionalPresent = false;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(content.contains("Class.forName"), content)
        assertTrue(content.contains("""optionalPresent = modporterClassResourcePresent("net.optional.ExampleConfig");"""), content)
        assertTrue(content.contains("private static boolean modporterClassResourcePresent(String binaryClassName)"), content)
    }

    @Test
    fun `does not rewrite commented class-for-name presence checks`() {
        val projectDir = tempDir.resolve("commented-class-for-name-presence")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example;

            public class ClientSetup {
                public static boolean optionalPresent = false;

                public static void setup() {
                    // try {
                    //     Class.forName("net.optional.ExampleConfig");
                    //     optionalPresent = true;
                    // } catch (ClassNotFoundException e) {
                    //     optionalPresent = false;
                    // }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("""//     Class.forName("net.optional.ExampleConfig");"""), content)
        assertFalse(content.contains("modporterClassResourcePresent"), content)
    }

    @Test
    fun `rewrites class-for-name loader presence probes to class resource probes`() {
        val projectDir = tempDir.resolve("class-for-name-loader-presence")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMixinPlugin.java").writeText("""
            package com.example;

            public class ExampleMixinPlugin {
                private boolean optionalA = false;
                private boolean optionalB = false;

                public void onLoad(String mixinPackage) {
                    try {
                        Class.forName("net.optional.First", false, getClass().getClassLoader());
                        optionalA = true;
                    } catch (ClassNotFoundException e) {
                    }
                    try {
                        Class.forName("net.optional.Second", false, Thread.currentThread().getContextClassLoader());
                        optionalB = true;
                    } catch (ClassNotFoundException e) {
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMixinPlugin.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(content.contains("Class.forName"), content)
        assertFalse(content.contains("ClassNotFoundException"), content)
        assertTrue(content.contains("""optionalA = modporterClassResourcePresent("net.optional.First");"""), content)
        assertTrue(content.contains("""optionalB = modporterClassResourcePresent("net.optional.Second");"""), content)
        assertTrue(content.contains("private static boolean modporterClassResourcePresent(String binaryClassName)"), content)
    }

    @Test
    fun `removes ForgeGradle JarJar import and task type after ModDev migration`() {
        val projectDir = tempDir.resolve("forgegradle-jarjar-import")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            import net.minecraftforge.gradle.userdev.tasks.JarJar

            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            tasks.named('jarJar', JarJar).configure {
                archiveClassifier = ''
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-import" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-jarjar-type" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-jarjar-archive-classifier" })
        assertFalse(content.contains("net.minecraftforge.gradle.userdev.tasks.JarJar"), content)
        assertFalse(content.contains(", JarJar"), content)
        assertFalse(content.contains("archiveClassifier = ''"), content)
    }

    @Test
    fun `removes ModdingX ForgeGradle companion plugins and recreates sourceJar task`() {
        val projectDir = tempDir.resolve("moddingx-forgegradle-companion")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'java-library'
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
                id 'org.moddingx.modgradle.mapping' version '[4,5)'
                id 'org.moddingx.modgradle.sourcejar' version '[4,5)' apply false
            }

            apply plugin: 'org.moddingx.modgradle.sourcejar'

            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        artifact sourceJar
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-companion-plugin" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-companion-apply" })
        assertTrue(result.changes.any { it.ruleId == "build-sourcejar-task" })
        assertFalse(content.contains("org.moddingx.modgradle.mapping"), content)
        assertFalse(content.contains("org.moddingx.modgradle.sourcejar"), content)
        assertTrue(content.contains("tasks.register('sourceJar', Jar)"), content)
        assertTrue(content.contains("from sourceSets.main.allSource"), content)
        assertTrue(content.contains("artifact sourceJar"), content)
    }

    @Test
    fun `removes bundled jarJar mixin dependencies and normalizes range pin dsl`() {
        val projectDir = tempDir.resolve("jarjar-range-pin")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
                jarJar("io.github.llamalad7:mixinextras-forge:${'$'}{project.mixinextras_version}") {
                    jarJar.ranged(it, "[${'$'}{project.mixinextras_version},)")
                    jarJar.pin(it, "${'$'}{project.mixinextras_version}")
                }

                jarJar "com.example:library:${'$'}{project.library_version}" {
                    jarJar.ranged(it, "[${'$'}{project.library_version},)")
                    jarJar.pin(it, "${'$'}{project.library_version}")
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-remove-bundled-mixin-dependency" })
        assertTrue(result.changes.any { it.ruleId == "build-normalize-jarjar-range-pin" })
        assertFalse(content.contains("mixinextras"), content)
        assertFalse(content.contains("jarJar.ranged"), content)
        assertFalse(content.contains("jarJar.pin"), content)
        assertTrue(content.contains("""jarJar "com.example:library:${'$'}{project.library_version}""""), content)
    }

    @Test
    fun `publishing uses archive jar instead of ModDev jarJar task`() {
        val projectDir = tempDir.resolve("jarjar-publication")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        artifact project.tasks.jarJar
                    }
                }
            }

            curseforge {
                project {
                    mainArtifact(tasks.jarJar)
                }
            }

            modrinth {
                uploadFile = tasks.jarJar
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-jarjar-publication-archive" })
        assertTrue(content.contains("artifact tasks.named('jar')"), content)
        assertTrue(content.contains("mainArtifact(tasks.jar)"), content)
        assertTrue(content.contains("uploadFile = tasks.jar"), content)
        assertFalse(content.contains("tasks.jarJar"), content)
    }

    @Test
    fun `rewrites add-layers renderer reflection to public renderer lookup API`() {
        val projectDir = tempDir.resolve("addlayers-renderers-at")
        val srcDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example.client;

            import net.minecraft.client.model.EntityModel;
            import net.minecraft.client.renderer.entity.EntityRenderer;
            import net.minecraft.client.renderer.entity.LivingEntityRenderer;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.EntityRenderersEvent;
            import java.lang.reflect.Field;
            import java.util.Map;

            public class ClientSetup {
                private static Field field_EntityRenderersEvent${'$'}AddLayers_renderers;

                @SubscribeEvent
                @SuppressWarnings("unchecked")
                public static void attachRenderLayers(EntityRenderersEvent.AddLayers event) {
                    if (field_EntityRenderersEvent${'$'}AddLayers_renderers == null) {
                        try {
                            field_EntityRenderersEvent${'$'}AddLayers_renderers = EntityRenderersEvent.AddLayers.class.getDeclaredField("renderers");
                            field_EntityRenderersEvent${'$'}AddLayers_renderers.setAccessible(true);
                        } catch (NoSuchFieldException e) {
                            e.printStackTrace();
                        }
                    }
                    if (field_EntityRenderersEvent${'$'}AddLayers_renderers != null) {
                        event.getSkins().forEach(renderer -> {
                            LivingEntityRenderer<Player, EntityModel<Player>> skin = event.getSkin(renderer);
                            attachRenderLayers(skin);
                        });
                        try {
                            ((Map<EntityType<?>, EntityRenderer<?>>) field_EntityRenderersEvent${'$'}AddLayers_renderers.get(event)).values().stream().
                                    filter(LivingEntityRenderer.class::isInstance).map(LivingEntityRenderer.class::cast).forEach(ClientSetup::attachRenderLayers);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }

                private static void attachRenderLayers(LivingEntityRenderer<?, ?> renderer) {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-entityrenderers-addlayers-api" })
        assertFalse(content.contains("getDeclaredField"), content)
        assertFalse(content.contains("setAccessible"), content)
        assertFalse(content.contains("java.lang.reflect.Field"), content)
        assertFalse(content.contains("field_EntityRenderersEvent"), content)
        assertTrue(content.contains("event.getSkins().forEach(renderer -> {"), content)
        assertTrue(content.contains("attachRenderLayers(skin);"), content)
        assertTrue(content.contains("});"), content)
        assertTrue(content.contains("event.getEntityTypes().stream().map(event::getRenderer)."), content)
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(!atFile.exists() || !atFile.readText().contains("EntityRenderersEvent${'$'}AddLayers renderers"))
    }

    @Test
    fun `add-layers renderer reflection migration ignores comments outside method evidence`() {
        val projectDir = tempDir.resolve("addlayers-renderers-comment-markers")
        val srcDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example.client;

            import net.minecraft.client.model.EntityModel;
            import net.minecraft.client.renderer.entity.EntityRenderer;
            import net.minecraft.client.renderer.entity.LivingEntityRenderer;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.EntityRenderersEvent;
            import java.util.Map;

            public class ClientSetup {
                // EntityRenderersEvent.AddLayers.class.getDeclaredField("renderers");
                // field_EntityRenderersEvent${'$'}AddLayers_renderers.setAccessible(true);

                @SubscribeEvent
                @SuppressWarnings("unchecked")
                public static void attachRenderLayers(EntityRenderersEvent.AddLayers event) {
                    event.getSkins().forEach(renderer -> {
                        LivingEntityRenderer<Player, EntityModel<Player>> skin = event.getSkin(renderer);
                        attachRenderLayers(skin);
                    });
                    ((Map<EntityType<?>, EntityRenderer<?>>) renderers.get(event)).values().stream().
                            filter(LivingEntityRenderer.class::isInstance).map(LivingEntityRenderer.class::cast).forEach(ClientSetup::attachRenderLayers);
                }

                private static void attachRenderLayers(LivingEntityRenderer<?, ?> renderer) {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-entityrenderers-addlayers-api" })
        assertTrue(content.contains("renderers.get(event)"), content)
        assertTrue(content.contains("getDeclaredField(\"renderers\")"), content)
    }

    @Test
    fun `rewrites obfuscation method handles to mixin invoker calls`() {
        val projectDir = tempDir.resolve("methodhandle-mixin")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val metaInfDir = projectDir.resolve("src/main/resources/META-INF")
        srcDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"
        """.trimIndent())
        srcDir.resolve("EntityUtil.java").writeText("""
            package com.example;

            import net.minecraft.core.Direction;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.decoration.HangingEntity;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;
            import org.jetbrains.annotations.Nullable;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.reflect.Method;

            public class EntityUtil {
                private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
                private static final Method reflectedDeathSound = ObfuscationReflectionHelper.findMethod(LivingEntity.class, "m_5592_");
                private static final MethodHandle boundDeathSound;
                private static final Method reflectedDirectionSetter = ObfuscationReflectionHelper.findMethod(HangingEntity.class, "m_6022_", Direction.class);
                private static final MethodHandle boundDirectionSetter;

                static {
                    MethodHandle temporaryDeathSound = null;
                    MethodHandle temporaryDirectionSetter = null;
                    try {
                        temporaryDeathSound = LOOKUP.unreflect(reflectedDeathSound);
                        temporaryDirectionSetter = LOOKUP.unreflect(reflectedDirectionSetter);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                    boundDeathSound = temporaryDeathSound;
                    boundDirectionSetter = temporaryDirectionSetter;
                }

                @Nullable
                public static SoundEvent getDeathSound(LivingEntity living) {
                    SoundEvent sound = null;
                    if (boundDeathSound != null) {
                        try {
                            sound = (SoundEvent) boundDeathSound.invokeExact(living);
                        } catch (Throwable e) {
                        }
                    }
                    return sound;
                }

                public static void setPaintingDirection(HangingEntity painting, Direction direction) {
                    try {
                        boundDirectionSetter.invoke(painting, direction);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("EntityUtil.java").readText()
        val livingInvoker = srcDir.resolve("modporter/mixin/ModPorterLivingEntityInvoker.java").readText()
        val hangingInvoker = srcDir.resolve("modporter/mixin/ModPorterHangingEntityInvoker.java").readText()
        val mixinConfig = projectDir.resolve("src/main/resources/examplemod.modporter.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-obfuscation-methodhandle-mixin-invoker" })
        assertTrue(result.changes.any { it.ruleId == "build-protected-method-mixin-invoker" })
        assertTrue(result.changes.any { it.ruleId == "build-register-generated-mixin-config" })
        assertFalse(content.contains("ObfuscationReflectionHelper"), content)
        assertFalse(content.contains("MethodHandle"), content)
        assertFalse(content.contains("java.lang.reflect.Method"), content)
        assertTrue(content.contains("import com.example.modporter.mixin.ModPorterLivingEntityInvoker;"), content)
        assertTrue(content.contains("import com.example.modporter.mixin.ModPorterHangingEntityInvoker;"), content)
        assertTrue(content.contains("return ((ModPorterLivingEntityInvoker) living).modporter${'$'}getDeathSound();"), content)
        assertTrue(content.contains("((ModPorterHangingEntityInvoker) painting).modporter${'$'}setDirection(direction);"), content)
        assertTrue(livingInvoker.contains("package com.example.modporter.mixin;"), livingInvoker)
        assertTrue(livingInvoker.contains("@Mixin(LivingEntity.class)"), livingInvoker)
        assertTrue(livingInvoker.contains("@Invoker(\"getDeathSound\")"), livingInvoker)
        assertTrue(hangingInvoker.contains("package com.example.modporter.mixin;"), hangingInvoker)
        assertTrue(hangingInvoker.contains("@Mixin(HangingEntity.class)"), hangingInvoker)
        assertTrue(hangingInvoker.contains("@Invoker(\"setDirection\")"), hangingInvoker)
        assertTrue(mixinConfig.contains("\"package\": \"com.example.modporter.mixin\""), mixinConfig)
        assertTrue(mixinConfig.contains("\"ModPorterLivingEntityInvoker\""), mixinConfig)
        assertTrue(mixinConfig.contains("\"ModPorterHangingEntityInvoker\""), mixinConfig)
        assertTrue(modsToml.contains("[[mixins]]"), modsToml)
        assertTrue(modsToml.contains("config=\"examplemod.modporter.mixins.json\""), modsToml)
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(!atFile.exists() || !atFile.readText().contains("getDeathSound()"))
        assertTrue(!atFile.exists() || !atFile.readText().contains("setDirection("))
    }

    @Test
    fun `obfuscation method handle migration ignores commented binding evidence`() {
        val projectDir = tempDir.resolve("methodhandle-comment-markers")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("EntityUtil.java").writeText("""
            package com.example;

            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.world.entity.LivingEntity;
            import org.jetbrains.annotations.Nullable;

            public class EntityUtil {
                // private static final Method reflectedDeathSound = ObfuscationReflectionHelper.findMethod(LivingEntity.class, "m_5592_");
                // private static final MethodHandle boundDeathSound;
                // boundDeathSound = LOOKUP.unreflect(reflectedDeathSound);

                @Nullable
                public static SoundEvent getDeathSound(LivingEntity living) {
                    return null;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("EntityUtil.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-obfuscation-methodhandle-mixin-invoker" })
        assertFalse(srcDir.resolve("modporter/mixin/ModPorterLivingEntityInvoker.java").exists())
        assertTrue(content.contains("return null;"), content)
    }

    @Test
    fun `guards existing client only listener method references from dedicated server class loading`() {
        val projectDir = tempDir.resolve("client-listener-dist-guard")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(ClientEvent::registerRecipeSerializers);
                    modEventBus.addListener(ClientEvent::registerParticleFactories);
                    modEventBus.addListener(this::commonSetup);
                }

                private void commonSetup(FMLCommonSetupEvent event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientEvent.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.registries.RegisterEvent;
            import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

            public class ClientEvent {
                public static void registerRecipeSerializers(RegisterEvent event) {
                }

                public static void registerParticleFactories(RegisterParticleProvidersEvent event) {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-client-only-listener-dist-guard" })
        assertTrue(content.contains("if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {"), content)
        assertTrue(Regex("""(?m)^[ \t]*modEventBus\.addListener\(ClientEvent::registerRecipeSerializers\);\s*$""").containsMatchIn(content), content)
        assertFalse(Regex("""(?m)^[ \t]*if\s*\([^\r\n]*FMLLoader\.getDist\(\)[^\r\n]*\)\s*\{\s*\r?\n[ \t]*modEventBus\.addListener\(ClientEvent::registerRecipeSerializers\);""").containsMatchIn(content), content)
        assertTrue(content.contains("modEventBus.addListener(ClientEvent::registerParticleFactories);"), content)
        assertTrue(content.contains("modEventBus.addListener(this::commonSetup);"), content)
        assertFalse(Regex("""if\s*\([^\r\n]*FMLLoader\.getDist\(\)[^\r\n]*\)\s*\{\s*\r?\n[ \t]*modEventBus\.addListener\(this::commonSetup\);""").containsMatchIn(content))
    }

    @Test
    fun `client only listener guard ignores comments and strings`() {
        val projectDir = tempDir.resolve("client-listener-comment-no-guard")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(ClientEvent::commonSetup);
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientEvent.java").writeText("""
            package com.example;

            public class ClientEvent {
                String note = "net.minecraft.client.gui.screens.Screen RegisterParticleProvidersEvent";

                public static void commonSetup(Object event) {
                    // net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
                    // net.minecraft.client.gui.screens.Screen
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-client-only-listener-dist-guard" })
        assertTrue(content.contains("modEventBus.addListener(ClientEvent::commonSetup);"), content)
        assertFalse(content.contains("FMLLoader.getDist()"), content)
    }

    @Test
    fun `marks client lifecycle event bus subscribers as client dist`() {
        val projectDir = tempDir.resolve("client-subscriber-dist")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
            import net.neoforged.neoforge.common.NeoForge;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)
                public static class ModEvents {
                    @SubscribeEvent
                    public static void onClientSetup(FMLClientSetupEvent event) {
                        NeoForge.EVENT_BUS.register(new ClientScreenEvents());
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientScreenEvents.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.Screen;

            public class ClientScreenEvents {
                Screen screen;
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-client-eventbus-subscriber-dist" })
        assertTrue(content.contains("@EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = net.neoforged.api.distmarker.Dist.CLIENT)"), content)
    }

    @Test
    fun `client event bus subscriber dist marking ignores comments and strings`() {
        val projectDir = tempDir.resolve("client-subscriber-comment-no-dist")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)
                public static class ModEvents {
                    String note = "FMLClientSetupEvent RegisterShadersEvent net.minecraft.client.gui.screens.Screen";

                    @SubscribeEvent
                    public static void commonSetup(FMLCommonSetupEvent event) {
                        // FMLClientSetupEvent RegisterParticleProvidersEvent
                        // net.neoforged.neoforge.client.event.ModelEvent
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-client-eventbus-subscriber-dist" })
        assertTrue(content.contains("@EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)"), content)
        assertFalse(content.contains("value = net.neoforged.api.distmarker.Dist.CLIENT"), content)
    }

    @Test
    fun `restores getTag on local TagKey holder types after nbt text migration`() {
        val projectDir = tempDir.resolve("restore-local-gettag")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Filters.java").writeText("""
            package com.example;

            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;

            public class Filters {
                void add(TagFilter filter) {
                    if (filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag() != null) {
                        use(filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
                    }
                }

                void use(Object value) {}

                public static class TagFilter {
                    private final TagKey<Item> tag = null;

                    public TagKey<Item> getTag() {
                        return this.tag;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("Filters.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-restore-non-itemstack-gettag" })
        assertTrue(content.contains("if (filter.getTag() != null)"), content)
        assertTrue(content.contains("use(filter.getTag());"), content)
        assertFalse(content.contains("filter.getOrDefault"))
    }

    @Test
    fun `passes model resource location id from modify baking result lambda`() {
        val projectDir = tempDir.resolve("modelresource-id")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClientModels.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.client.event.ModelEvent;

            public class ClientModels {
                public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
                    event.getModels().replaceAll((location, model) ->
                        ModelHooks.shouldWrap(location) ? new Wrapped(model) : model);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientModels.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-modelresource-location-id" })
        assertTrue(content.contains("ModelHooks.shouldWrap(location.id())"), content)
        assertFalse(content.contains("shouldWrap(location)"))
    }

    @Test
    fun `retargets client color handler mixin imports to event package shape`() {
        val projectDir = tempDir.resolve("client-colorhandler-package")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ColorHandlerMixin.java").writeText("""
            package com.example;

            import org.spongepowered.asm.mixin.Mixin;
            import examplemod.client.ColorHandler;

            @Mixin(ColorHandler.class)
            public class ColorHandlerMixin {
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("ColorHandlerMixin.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-client-event-colorhandler-target" })
        assertTrue(content.contains("import examplemod.client.event.ColorHandler;"), content)
        assertFalse(content.contains("import examplemod.client.ColorHandler;"))
    }

    @Test
    fun `migrates stale Twilight access transformers to valid 121 targets`() {
        val projectDir = tempDir.resolve("twilight-at")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            # No API yet for Trunk Placers yet
            public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/Codec;)V # ctor
            public net.minecraft.client.resources.model.ModelBakery f_119234_ # UNREFERENCED_TEXTURES
            public net.minecraft.world.level.chunk.ChunkGenerator m_223138_(Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/levelgen/RandomState;)Ljava/util/List; # getPlacementsForStructure
            public net.minecraft.world.entity.ai.goal.GoalSelector f_25345_ # availableGoals
        """.trimIndent())

        pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/MapCodec;)V # ctor"))
        assertTrue(at.contains("public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals # availableGoals"))
        assertFalse(at.contains("Lcom/mojang/serialization/Codec;)V"))
        assertFalse(at.contains("UNREFERENCED_TEXTURES"))
        assertFalse(at.contains("getPlacementsForStructure"))
        assertFalse(at.contains("f_119234_"))
        assertFalse(at.contains("m_223138_"))
    }

    @Test
    fun `drops access transformers for removed 121 inner classes`() {
        val projectDir = tempDir.resolve("removed-inner-class-at")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.client.gui.Gui${'$'}HeartType
            public net.minecraft.world.item.crafting.SimpleCookingSerializer${'$'}CookieBaker
            public net.minecraft.client.gui.screens.TitleScreen${'$'}WarningLabel
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.gui.Gui${'$'}HeartType"))
        assertFalse(at.contains("SimpleCookingSerializer${'$'}CookieBaker"))
        assertFalse(at.contains("TitleScreen${'$'}WarningLabel"))
    }

    @Test
    fun `adds fired weapon arrow access transformer when migrated projectile source needs it`() {
        val projectDir = tempDir.resolve("projectile-fired-weapon-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.107'
            }
        """.trimIndent())
        srcDir.resolve("ExampleProjectileHooks.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.projectile.AbstractArrow;

            public class ExampleProjectileHooks {
                public static void rememberWeapon(AbstractArrow arrow) {
                    arrow.firedFromWeapon = null;
                    arrow.setPierceLevel((byte) 1);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.projectile.AbstractArrow firedFromWeapon"), at)
        assertTrue(at.contains("public net.minecraft.world.entity.projectile.AbstractArrow setPierceLevel(B)V"), at)
        assertTrue(build.contains("accessTransformers"), build)
    }

    @Test
    fun `abstract arrow access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("projectile-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ProjectileNotes.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.projectile.AbstractArrow;

            public class ProjectileNotes {
                String note = "AbstractArrow arrow; arrow.firedFromWeapon = null; arrow.setPierceLevel((byte) 1);";

                void describe() {
                    // AbstractArrow arrow; arrow.firedFromWeapon = null; arrow.setPierceLevel((byte) 1);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `removes TitleScreen accessors for fields and inner classes removed in 121`() {
        val projectDir = tempDir.resolve("removed-title-screen-accessors")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("TitleScreenAccessor.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.components.SplashRenderer;
            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.renderer.PanoramaRenderer;
            import net.neoforged.neoforge.client.gui.TitleScreenModUpdateIndicator;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.Mutable;
            import org.spongepowered.asm.mixin.gen.Accessor;

            @Mixin(TitleScreen.class)
            public interface TitleScreenAccessor {
                @Accessor("splash")
                SplashRenderer example${'$'}getSplash();

                @Mutable
                @Accessor("panorama")
                void example${'$'}setPanorama(PanoramaRenderer panorama);

                @Accessor(value = "modUpdateNotification", remap = false)
                TitleScreenModUpdateIndicator example${'$'}getModUpdateNotification();

                @Accessor(value = "modUpdateNotification", remap = false)
                void example${'$'}setModUpdateNotification(TitleScreenModUpdateIndicator widget);

                @Accessor
                TitleScreen.WarningLabel example${'$'}getWarningLabel();
            }
        """.trimIndent())
        srcDir.resolve("ExampleTitleScreen.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.renderer.PanoramaRenderer;

            public class ExampleTitleScreen extends TitleScreen {
                public ExampleTitleScreen() {
                    TitleScreenAccessor accessor = (TitleScreenAccessor) this;
                    accessor.example${'$'}setPanorama(new PanoramaRenderer(CUBE_MAP));
                    accessor.example${'$'}setModUpdateNotification(new ExampleModUpdateIndicator(this));
                    accessor.example${'$'}getModUpdateNotification().init();
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleModUpdateIndicator.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.TitleScreen;
            import net.neoforged.neoforge.client.gui.TitleScreenModUpdateIndicator;

            public class ExampleModUpdateIndicator extends TitleScreenModUpdateIndicator {
                public ExampleModUpdateIndicator(TitleScreen screen) {
                    super(screen);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val accessor = srcDir.resolve("TitleScreenAccessor.java").readText()
        val screen = srcDir.resolve("ExampleTitleScreen.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-title-screen-removed-accessors" })
        assertTrue(result.changes.any { it.ruleId == "build-title-screen-removed-accessor-calls" })
        assertTrue(
            result.changes.any { it.ruleId == "build-title-screen-update-indicator-class" },
            result.changes.joinToString("\n") { "${it.ruleId}: ${it.file.fileName}" } + "\n\n" + screen
        )
        assertTrue(accessor.contains("SplashRenderer example${'$'}getSplash()"))
        assertFalse(accessor.contains("PanoramaRenderer"))
        assertFalse(accessor.contains("TitleScreenModUpdateIndicator"))
        assertFalse(accessor.contains("WarningLabel"))
        assertFalse(screen.contains("example${'$'}setPanorama"))
        assertFalse(screen.contains("example${'$'}setModUpdateNotification"))
        assertFalse(screen.contains("example${'$'}getModUpdateNotification"))
        assertFalse(screen.contains("PanoramaRenderer"))
        assertFalse(srcDir.resolve("ExampleModUpdateIndicator.java").exists())
    }

    @Test
    fun `migrates removed noParticlesOnBreak block property to AT backed helper`() {
        val projectDir = tempDir.resolve("no-particles-block-properties")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.139'
            }

            neoForge {
                version = "21.1.203"
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Blocks.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.SoundType;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class Blocks {
                public static final Block JAR = new Block(BlockBehaviour.Properties.of().noOcclusion().noParticlesOnBreak().sound(SoundType.BONE_BLOCK));
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val blockSource = srcDir.resolve("Blocks.java").readText()
        val helper = projectDir.resolve("src/main/java/com/modporter/compat/ModPorterBlockProperties.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-block-properties-no-particles" })
        assertTrue(blockSource.contains("com.modporter.compat.ModPorterBlockProperties.noParticlesOnBreak(BlockBehaviour.Properties.of().noOcclusion()).sound(SoundType.BONE_BLOCK)"))
        assertFalse(blockSource.contains("noParticlesOnBreak()"))
        assertTrue(helper.contains("properties.spawnTerrainParticles = false;"))
        assertTrue(at.contains("public net.minecraft.world.level.block.state.BlockBehaviour\$Properties spawnTerrainParticles"))
        assertTrue(build.contains("accessTransformers"))
    }

    @Test
    fun `cleans split tick phase checks after NeoForge event migration`() {
        val projectDir = tempDir.resolve("split-tick-phase")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("TickHandlers.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.client.event.RenderFrameEvent;
            import net.neoforged.neoforge.event.TickEvent;

            public class TickHandlers {
                public static void clientTick(ClientTickEvent.Post event) {
                    if (event.phase == TickEvent.Phase.END && ready()) {
                        run();
                    }
                    if (event.phase != TickEvent.Phase.END || blocked()) {
                        return;
                    }
                    run();
                }

                public static void renderTick(RenderFrameEvent.Post event) {
                    if (event.phase == TickEvent.Phase.START) {
                        render();
                    }
                }

                private static boolean ready() { return true; }
                private static boolean blocked() { return false; }
                private static void run() {}
                private static void render() {}
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val source = srcDir.resolve("TickHandlers.java").readText()
        assertTrue(result.changes.any { it.ruleId == "build-cleanup-split-tick-phase" })
        assertTrue(source.contains("public static void renderTick(RenderFrameEvent.Pre event)"))
        assertTrue(source.contains("if (ready())"))
        assertTrue(source.contains("if (blocked())"))
        assertFalse(source.contains("TickEvent.Phase"))
        assertFalse(source.contains("event.phase"))
        assertFalse(source.contains("import net.neoforged.neoforge.event.TickEvent;"), source)
    }

    @Test
    fun `split tick cleanup does not rewrite unrelated java files`() {
        val projectDir = tempDir.resolve("split-tick-noop")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val javaFile = srcDir.resolve("Plain.java")
        javaFile.writeText("""
            package com.example;

            public class Plain {
                public void run() {
                    System.out.println("ok");
                }
            }
        """.trimIndent())

        val before = javaFile.readText()
        val result = pass.apply(projectDir)

        assertFalse(result.changes.any { it.ruleId == "build-cleanup-split-tick-phase" })
        assertEquals(before, javaFile.readText())
    }

    @Test
    fun `hard gates forbidden reflection after migration`() {
        val projectDir = tempDir.resolve("forbidden-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("UnsafeReflection.java").writeText("""
            package com.example;

            public class UnsafeReflection {
                void read() throws Exception {
                    UnsafeReflection.class.getDeclaredMethod("read").setAccessible(true);
                    UnsafeReflection.class.getMethod("read");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        assertTrue(result.errors.any { it.contains("Forbidden reflection") && it.contains("getDeclaredMethod") })
        assertTrue(result.errors.any { it.contains("Forbidden reflection") && it.contains("getMethod") })
        assertTrue(result.errors.any { it.contains("Forbidden reflection") && it.contains("setAccessible") })
    }

    @Test
    fun `removes standalone mixin dependencies bundled by NeoForge`() {
        val projectDir = tempDir.resolve("bundled-mixin-deps")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'java'
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
                implementation "com.example:kept:1.0.0"
                annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'
                implementation "io.github.llamalad7:mixinextras-forge:0.3.5"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(result.changes.any { it.ruleId == "build-remove-bundled-mixin-dependency" })
        assertTrue(content.contains("com.example:kept:1.0.0"), content)
        assertFalse(content.contains("org.spongepowered:mixin"), content)
        assertFalse(content.contains("io.github.llamalad7:mixinextras"), content)
        assertFalse(content.contains("annotationProcessor 'org.spongepowered:mixin"), content)
    }

    @Test
    fun `analyze mode does not modify files`() {
        val projectDir = tempDir.resolve("p7")
        projectDir.createDirectories()
        val originalContent = """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent()
        projectDir.resolve("build.gradle").writeText(originalContent)

        val result = pass.analyze(projectDir)
        assertTrue(result.changeCount > 0)

        // File should not be modified
        assertEquals(originalContent, projectDir.resolve("build.gradle").readText())
    }

    @Test
    fun `handles kotlin DSL build gradle kts`() {
        val projectDir = tempDir.resolve("p8")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins {
                id("net.minecraftforge.gradle") version "[6.0,6.2)"
            }
            dependencies {
                minecraft("net.minecraftforge:forge:1.20.1-47.2.0")
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle.kts").readText()
        assertTrue(content.contains("net.neoforged.moddev"))
        assertFalse(content.contains("net.minecraftforge"))
    }

    @Test
    fun `handles project with no build files`() {
        val projectDir = tempDir.resolve("p9")
        projectDir.createDirectories()

        val result = pass.apply(projectDir)
        assertEquals(0, result.changeCount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `updates Gradle wrapper below ModDev minimum`() {
        val projectDir = tempDir.resolve("p9-wrapper")
        val wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        wrapperProps.parent.createDirectories()
        wrapperProps.writeText("""
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-all.zip
            networkTimeout=10000
            validateDistributionUrl=true
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = wrapperProps.readText()

        assertTrue(result.changes.any { it.ruleId == "build-gradle-wrapper" })
        assertTrue(content.contains("gradle-8.14.4-bin.zip"))
        assertFalse(content.contains("gradle-8.5-all.zip"))
    }

    @Test
    fun `all changes are HIGH confidence`() {
        val projectDir = tempDir.resolve("p10")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        val result = pass.analyze(projectDir)
        assertTrue(result.changeCount > 0)
        assertTrue(result.changes.all {
            it.confidence == com.modporter.core.pipeline.Confidence.HIGH
        }, "All build system changes should be HIGH confidence")
    }

    @Test
    fun `guards optional prepareGameTestServerRun hook`() {
        val projectDir = tempDir.resolve("p11")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            tasks.named('prepareGameTestServerRun').configure {
                doLast {
                    println 'copying generated gametest assets'
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-guard-optional-run-task" })
        assertTrue(content.contains("tasks.matching { it.name == 'prepareGameTestServerRun' }.configureEach {"))
        assertFalse(content.contains("tasks.named('prepareGameTestServerRun').configure"))
    }

    @Test
    fun `creates missing empty gametest structure templates`() {
        val projectDir = tempDir.resolve("p11b")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("ExampleGameTests.java").writeText("""
            package com.example;

            import net.minecraft.gametest.framework.GameTest;

            public final class ExampleGameTests {
                private static final String TEMPLATE = "mirror_lifecycle_empty";

                @GameTest(template = TEMPLATE, timeoutTicks = 40)
                public static void constantTemplate() {
                }

                @GameTest(template = "empty_1x1", timeoutTicks = 40)
                public static void literalTemplate() {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        assertEquals(2, result.changes.count { it.ruleId == "build-gametest-empty-structure" })
        assertTrue(projectDir.resolve("src/main/resources/gameteststructures/mirror_lifecycle_empty.snbt").exists())
        assertTrue(projectDir.resolve("src/main/resources/gameteststructures/empty_1x1.snbt").exists())
    }

    @Test
    fun `normalizes ForgeGradle mod dependency configurations`() {
        val projectDir = tempDir.resolve("p12")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                modCompileOnly("cn.mcmod_mmf.mysterious_mountain_lib:MMLib:1.5.18-1.20.1")
                modRuntimeOnly("cn.mcmod_mmf.mysterious_mountain_lib:MMLib:1.5.18-1.20.1")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-mod-dependency-configuration" })
        assertFalse(content.contains("modCompileOnly"))
        assertFalse(content.contains("modRuntimeOnly"))
        assertTrue(content.contains("compileOnly"))
        assertTrue(content.contains("runtimeOnly"))
    }

    @Test
    fun `adds legacy capability shims and removes super only hooks`() {
        val projectDir = tempDir.resolve("p13")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("MachineBlockEntity.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.capabilities.Capability;
            import net.neoforged.neoforge.common.util.LazyOptional;

            @Mod("examplemod")
            public class MachineBlockEntity extends BaseBlockEntity {
                private LazyOptional<Object> handler = LazyOptional.of(Object::new);

                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Object side) {
                    return super.getCapability(cap, side);
                }

                @Override
                public void invalidateCaps() {
                    super.invalidateCaps();
                    handler.invalidate();
                }
            }

            class BaseBlockEntity {
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("MachineBlockEntity.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-add-lazyoptional-shim" })
        assertTrue(result.changes.any { it.ruleId == "build-relocate-lazyoptional-import" })
        assertFalse(result.changes.any { it.ruleId == "build-add-capability-shim" })
        assertFalse(result.changes.any { it.ruleId == "build-relocate-capability-import" })
        assertTrue(projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/LazyOptional.java").exists())
        assertFalse(projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/Capability.java").exists())
        assertFalse(projectDir.resolve("src/main/java/com/modporter/compat/LazyOptional.java").exists())
        assertFalse(projectDir.resolve("src/main/java/com/modporter/compat/Capability.java").exists())
        assertTrue(source.contains("import com.modporter.generated.examplemod.compat.LazyOptional;"))
        assertFalse(source.contains("net.neoforged.neoforge.common.util.LazyOptional"))
        assertFalse(source.contains("net.neoforged.neoforge.capabilities.Capability"))
        assertFalse(source.contains("public <T> LazyOptional<T> getCapability"))
        assertFalse(source.contains("LazyOptional.empty();"))
        assertFalse(source.contains("[forge2neo]"))
        assertTrue(source.contains("handler.invalidate();"))
        assertFalse(source.contains("import com.modporter.generated.examplemod.compat.Capability;"))
        assertFalse(source.contains("super.getCapability"))
        assertFalse(source.contains("super.invalidateCaps();"))
    }

    @Test
    fun `adds conditional recipe adapter backed by RecipeOutput conditions`() {
        val projectDir = tempDir.resolve("p13-conditional-recipe")
        val srcDir = projectDir.resolve("src/main/java/com/example/data")
        srcDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("Recipes.java").writeText("""
            package com.example.data;

            import net.minecraft.data.recipes.RecipeOutput;
            import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
            import net.neoforged.neoforge.common.crafting.ConditionalRecipe;

            public class Recipes {
                public ConditionalRecipe.Builder whenModLoaded(String modid) {
                    return ConditionalRecipe.builder().addCondition(new ModLoadedCondition(modid));
                }

                public void build(RecipeOutput output) {
                    whenModLoaded("farmersdelight").build(output, "examplemod", "rice");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("Recipes.java").readText()
        val shim = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/ConditionalRecipe.java")

        assertTrue(result.changes.any { it.ruleId == "build-relocate-conditionalrecipe-import" })
        assertTrue(result.changes.any { it.ruleId == "build-add-conditionalrecipe-shim" })
        assertTrue(source.contains("import com.modporter.generated.examplemod.compat.ConditionalRecipe;"))
        assertFalse(source.contains("net.neoforged.neoforge.common.crafting.ConditionalRecipe"))
        assertTrue(shim.exists())
        val shimContent = shim.readText()
        assertTrue(shimContent.contains("output.withConditions(conditions.toArray(ICondition[]::new))"))
        assertTrue(shimContent.contains("public void accept(ResourceLocation generatedId, Recipe<?> recipe, AdvancementHolder advancement, ICondition... extraConditions)"))
        assertTrue(shimContent.contains("target.accept(id, recipe, forwardedAdvancement);"))
        assertTrue(shimContent.contains("new AdvancementHolder(advancementId, advancement.value())"))
        assertTrue(shimContent.contains("recipe.accept(namedOutput);"))
    }

    @Test
    fun `relocates legacy mmlib render utils to generated fluid renderer`() {
        val projectDir = tempDir.resolve("p13-renderutils")
        val srcDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("ExampleScreen.java").writeText("""
            package com.example.client;

            import cn.mcmod_mmf.mmlib.client.RenderUtils;
            import net.minecraft.client.gui.GuiGraphics;
            import net.neoforged.neoforge.fluids.FluidStack;

            public class ExampleScreen {
                public void render(GuiGraphics ms, FluidStack stack) {
                    RenderUtils.renderFluidStack(9, 10, 16, 16, 0.0F, stack);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("ExampleScreen.java").readText()
        val shim = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/RenderUtils.java")

        assertTrue(result.changes.any { it.ruleId == "build-relocate-renderutils-fluid" })
        assertTrue(result.changes.any { it.ruleId == "build-add-renderutils-fluid-shim" })
        assertTrue(source.contains("import com.modporter.generated.examplemod.compat.RenderUtils;"))
        assertTrue(source.contains("RenderUtils.renderFluidStack(ms, 9, 10, 16, 16, 0.0F, stack);"))
        assertFalse(source.contains("cn.mcmod_mmf.mmlib.client.RenderUtils"))
        assertTrue(shim.exists())
        val shimContent = shim.readText()
        assertTrue(shimContent.contains("IClientFluidTypeExtensions.of(fluidStack.getFluid())"))
        assertTrue(shimContent.contains("TextureAtlas.LOCATION_BLOCKS"))
        assertTrue(shimContent.contains("guiGraphics.blit("))
        assertFalse(shimContent.contains("TODO"))
    }

    @Test
    fun `removes stale LazyOptional imports without generating compatibility shim`() {
        val projectDir = tempDir.resolve("p13-stale-lazyoptional")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("OnlyImport.java").writeText("""
            package com.example;

            import com.modporter.generated.examplemod.compat.LazyOptional;

            public class OnlyImport {
                public int value() {
                    return 1;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("OnlyImport.java").readText()
        val shim = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/LazyOptional.java")

        assertTrue(result.changes.any { it.ruleId == "build-remove-stale-lazyoptional-import" })
        assertFalse(source.contains("LazyOptional"), source)
        assertFalse(shim.exists())
    }

    @Test
    fun `legacy compatibility shims hard gate missing project mod id`() {
        val projectDir = tempDir.resolve("p13-missing-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("Machine.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.common.util.LazyOptional;

            public class Machine {
                private final LazyOptional<Object> handler = LazyOptional.of(Object::new);
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("Machine.java").readText()

        assertTrue(result.errors.any { it.contains("Cannot derive generated compat shim package for legacy source compatibility shims") })
        assertTrue(source.contains("import net.neoforged.neoforge.common.util.LazyOptional;"))
        assertFalse(projectDir.resolve("src/main/java/com/modporter/generated/shared/compat/LazyOptional.java").exists())
        assertFalse(result.changes.any { it.ruleId == "build-add-lazyoptional-shim" })
    }

    @Test
    fun `rewrites legacy abstract tree grower call sites`() {
        val projectDir = tempDir.resolve("p14")
        val treeDir = projectDir.resolve("src/main/java/com/example/tree")
        val blockDir = projectDir.resolve("src/main/java/com/example/block")
        treeDir.createDirectories()
        blockDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        treeDir.resolve("CherryTreeGrower.java").writeText("""
            package com.example.tree;

            import net.minecraft.resources.ResourceKey;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;
            import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

            public class CherryTreeGrower extends AbstractTreeGrower {
                @Override
                protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean flowers) {
                    return TreeFeatures.CHERRY_KEY;
                }
            }
        """.trimIndent())
        blockDir.resolve("BlockRegistry.java").writeText("""
            package com.example.block;

            import com.example.tree.CherryTreeGrower;
            import net.minecraft.world.level.block.SaplingBlock;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;

            public class BlockRegistry {
                private static SaplingBlock sapling(AbstractTreeGrower grower) {
                    return new SaplingBlock(grower, null);
                }

                static SaplingBlock cherry() {
                    return sapling(new CherryTreeGrower());
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val registry = blockDir.resolve("BlockRegistry.java").readText()
        val grower = treeDir.resolve("CherryTreeGrower.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-compat" })
        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-helper" })
        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-compat-base" })
        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-unfinal-at" })
        assertTrue(registry.contains("import net.minecraft.world.level.block.grower.TreeGrower;"))
        assertFalse(registry.contains(";import"))
        assertTrue(registry.contains("private static SaplingBlock sapling(TreeGrower grower)"))
        assertTrue(registry.contains("new CherryTreeGrower()"))
        assertTrue(grower.contains("import com.modporter.generated.examplemod.compat.ModPorterAbstractTreeGrower;"))
        assertTrue(grower.contains("public class CherryTreeGrower extends ModPorterAbstractTreeGrower"))
        assertTrue(grower.contains("protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature"))
        assertFalse(grower.contains("extends AbstractTreeGrower"))
        val compatBase = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/ModPorterAbstractTreeGrower.java").readText()
        assertTrue(compatBase.contains("extends TreeGrower"))
        assertTrue(compatBase.contains("protected abstract ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature"))
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(atFile.contains("public-f net.minecraft.world.level.block.grower.TreeGrower"))
        assertFalse(projectDir.resolve("src/main/java/net/minecraft/world/level/block/grower/AbstractTreeGrower.java").exists())
    }

    @Test
    fun `legacy abstract tree grower compat hard gates missing project mod id`() {
        val projectDir = tempDir.resolve("p14-missing-modid")
        val treeDir = projectDir.resolve("src/main/java/com/example/tree")
        treeDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        treeDir.resolve("CherryTreeGrower.java").writeText("""
            package com.example.tree;

            import net.minecraft.resources.ResourceKey;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;
            import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

            public class CherryTreeGrower extends AbstractTreeGrower {
                @Override
                protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean flowers) {
                    return TreeFeatures.CHERRY_KEY;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val grower = treeDir.resolve("CherryTreeGrower.java").readText()

        assertTrue(result.errors.any { it.contains("Cannot derive generated compat shim package for legacy tree grower compatibility base") })
        assertTrue(grower.contains("extends AbstractTreeGrower"))
        assertFalse(projectDir.resolve("src/main/java/com/modporter/generated/shared/compat/ModPorterAbstractTreeGrower.java").exists())
    }

    @Test
    fun `rewrites legacy armor material enums and consumers`() {
        val projectDir = tempDir.resolve("p15")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        val modDir = projectDir.resolve("src/main/java/com/example")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        modDir.resolve("TestMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(TestMod.MODID)
            public class TestMod {
                public static final String MODID = "example";

                public TestMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(this::setup);
                }
            }
        """.trimIndent())
        itemDir.resolve("ExampleArmorMaterials.java").writeText("""
            package com.example.item;

            import com.example.TestMod;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.function.Supplier;

            public enum ExampleArmorMaterials implements ArmorMaterial {
                STRAW("straw", "strawhat", 6, new int[]{0, 0, 0, 1}, 30,
                        SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, () -> Ingredient.EMPTY),
                KIMONO("kimono", "kimono_base", 1, new int[]{0, 0, 0, 0}, 0,
                        SoundEvents.WOOL_PLACE, 0.0F, 0.0F, () -> Ingredient.of(Items.PAPER));

                private final String name;
                private final String textureName;
                private final SoundEvent sound;
                private final Supplier<Ingredient> repairIngredient;

                ExampleArmorMaterials(String name, String textureName, int durabilityMult, int[] protections, int enchant,
                                       SoundEvent sound, float tough, float kb, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.textureName = textureName;
                    this.sound = sound;
                    this.repairIngredient = repair;
                }

                @Override
                public String getName() {
                    return TestMod.MODID + ":" + this.name;
                }

                public String getTextureName() {
                    return this.textureName;
                }
            }
        """.trimIndent())
        itemDir.resolve("SamuraiArmorItem.java").writeText("""
            package com.example.item;

            import com.example.TestMod;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ItemStack;
            import javax.annotation.Nullable;

            public class SamuraiArmorItem extends ArmorItem {
                public SamuraiArmorItem(ExampleArmorMaterials material, Type type, Properties properties) {
                    super(material, type, properties);
                }

                @Override
                public @Nullable String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
                    String materialName = ((ExampleArmorMaterials) getMaterial()).getTextureName();
                    return TestMod.MODID + ":textures/models/armor/" + materialName + ".png";
                }
            }
        """.trimIndent())
        itemDir.resolve("KimonoItem.java").writeText("""
            package com.example.item;

            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;

            public class KimonoItem extends ArmorItem {
                public KimonoItem(ArmorMaterial material, Type type, Properties properties) {
                    super(material, type, properties);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val material = itemDir.resolve("ExampleArmorMaterials.java").readText()
        val samurai = itemDir.resolve("SamuraiArmorItem.java").readText()
        val kimono = itemDir.resolve("KimonoItem.java").readText()
        val mod = modDir.resolve("TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-legacy-armor-material-registry" })
        assertTrue(result.changes.any { it.ruleId == "build-legacy-armor-material-consumer" })
        assertTrue(result.changes.any { it.ruleId == "build-register-armor-materials" })
        assertFalse(material.contains("enum ExampleArmorMaterials implements ArmorMaterial"))
        assertTrue(material.contains("DeferredRegister.create(Registries.ARMOR_MATERIAL, TestMod.MODID)"))
        assertTrue(material.contains("Holder.direct(SoundEvents.WOOL_PLACE)"))
        assertTrue(samurai.contains("Holder<ArmorMaterial> material"))
        assertTrue(samurai.contains("ExampleArmorMaterials.getTextureName(this.material)"))
        assertTrue(samurai.contains("ResourceLocation.fromNamespaceAndPath(TestMod.MODID"))
        assertTrue(kimono.contains("Holder<ArmorMaterial> material"))
        assertTrue(mod.contains("import com.example.item.ExampleArmorMaterials;"))
        assertTrue(mod.contains("ExampleArmorMaterials.ARMOR_MATERIALS.register(modEventBus);"))
    }

    @Test
    fun `registers generated armor materials outside existing deferred register loops`() {
        val projectDir = tempDir.resolve("p15-armor-loop")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        val modDir = projectDir.resolve("src/main/java/com/example")
        itemDir.createDirectories()
        modDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        modDir.resolve("TestMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.registries.DeferredRegister;

            @Mod(TestMod.MODID)
            public class TestMod {
                public static final String MODID = "example";

                public TestMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    DeferredRegister<?>[] registers = { ExampleRegistry.BLOCKS, ExampleRegistry.ITEMS };
                    for (DeferredRegister<?> register : registers) {
                        register.register(modEventBus);
                    }
                }
            }
        """.trimIndent())
        itemDir.resolve("ExampleArmorMaterials.java").writeText("""
            package com.example.item;

            import com.example.TestMod;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.function.Supplier;

            public enum ExampleArmorMaterials implements ArmorMaterial {
                STRAW("straw", "strawhat", 6, new int[]{0, 0, 0, 1}, 30,
                        SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, () -> Ingredient.of(Items.WHEAT));

                private final String name;
                private final String textureName;
                private final SoundEvent sound;
                private final Supplier<Ingredient> repairIngredient;

                ExampleArmorMaterials(String name, String textureName, int durabilityMult, int[] protections, int enchant,
                                       SoundEvent sound, float tough, float kb, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.textureName = textureName;
                    this.sound = sound;
                    this.repairIngredient = repair;
                }

                @Override
                public String getName() {
                    return TestMod.MODID + ":" + this.name;
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val mod = modDir.resolve("TestMod.java").readText()
        val armorRegistration = "ExampleArmorMaterials.ARMOR_MATERIALS.register(modEventBus);"
        val armorIdx = mod.indexOf(armorRegistration)
        val arrayIdx = mod.indexOf("DeferredRegister<?>[] registers")
        val loopIdx = mod.indexOf("for (DeferredRegister<?> register : registers)")
        val loopRegisterIdx = mod.indexOf("register.register(modEventBus);")

        assertTrue(armorIdx >= 0, mod)
        assertTrue(armorIdx < arrayIdx, mod)
        assertTrue(loopIdx >= 0, mod)
        assertTrue(loopRegisterIdx > loopIdx, mod)
        assertFalse(mod.substring(loopIdx, loopRegisterIdx).contains(armorRegistration), mod)
    }

    @Test
    fun `legacy armor material migration rejects package derived mod ids`() {
        val projectDir = tempDir.resolve("p15-armor-no-modid")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        val materialFile = itemDir.resolve("ExampleArmorMaterials.java")
        materialFile.writeText("""
            package com.example.item;

            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.function.Supplier;

            public enum ExampleArmorMaterials implements ArmorMaterial {
                STRAW("straw", "strawhat", 6, new int[]{0, 0, 0, 1}, 30,
                        SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, () -> Ingredient.of(Items.WHEAT));

                private final String name;
                private final String textureName;
                private final SoundEvent sound;
                private final Supplier<Ingredient> repairIngredient;

                ExampleArmorMaterials(String name, String textureName, int durabilityMult, int[] protections, int enchant,
                                       SoundEvent sound, float tough, float kb, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.textureName = textureName;
                    this.sound = sound;
                    this.repairIngredient = repair;
                }

                @Override
                public String getName() {
                    return this.name;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val material = materialFile.readText()

        assertTrue(
            result.errors.any {
                it.contains("Cannot derive mod id expression for legacy ArmorMaterial enum ExampleArmorMaterials")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(result.changes.any { it.ruleId == "build-legacy-armor-material-registry" })
        assertTrue(material.contains("enum ExampleArmorMaterials implements ArmorMaterial"))
        assertFalse(material.contains("DeferredRegister.create(Registries.ARMOR_MATERIAL, \"item\")"))
    }

    @Test
    fun `relocates removed mmlib recipe helpers into project local base package`() {
        val projectDir = tempDir.resolve("p15-mmlib")
        val recipeDir = projectDir.resolve("src/main/java/com/example/recipes")
        recipeDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        recipeDir.resolve("ExampleRecipe.java").writeText("""
            package com.example.recipes;

            import cn.mcmod_mmf.mmlib.fluid.FluidIngredient;
            import cn.mcmod_mmf.mmlib.recipe.AbstractRecipe;
            import cn.mcmod_mmf.mmlib.recipe.AbstractRecipeSerializer;
            import cn.mcmod_mmf.mmlib.recipe.ChanceResult;

            public class ExampleRecipe extends AbstractRecipe {
                FluidIngredient fluid;
                ChanceResult result;
                AbstractRecipeSerializer<ExampleRecipe> serializer;
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = recipeDir.resolve("ExampleRecipe.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-relocate-mmlib-recipe-base" })
        assertTrue(result.changes.any { it.ruleId == "build-add-local-mmlib-recipe-base" })
        assertTrue(recipe.contains("import com.example.recipes.base.FluidIngredient;"))
        assertTrue(recipe.contains("import com.example.recipes.base.AbstractRecipe;"))
        assertTrue(recipe.contains("import com.example.recipes.base.AbstractRecipeSerializer;"))
        assertTrue(recipe.contains("import com.example.recipes.base.ChanceResult;"))
        assertTrue(projectDir.resolve("src/main/java/com/example/recipes/base/FluidIngredient.java").exists())
        val abstractRecipe = projectDir.resolve("src/main/java/com/example/recipes/base/AbstractRecipe.java")
        assertTrue(abstractRecipe.exists())
        val abstractRecipeSource = abstractRecipe.readText()
        assertTrue(abstractRecipeSource.contains("public abstract ItemStack getResultItem(HolderLookup.Provider registries);"))
        assertFalse(abstractRecipeSource.contains("return ItemStack.EMPTY;"))
        assertFalse(abstractRecipeSource.contains("Temporary source-compatibility shim"))
        assertTrue(projectDir.resolve("src/main/java/com/example/recipes/base/ChanceResult.java").exists())
        val serializerSource = projectDir.resolve("src/main/java/com/example/recipes/base/AbstractRecipeSerializer.java").readText()
        assertTrue(serializerSource.contains("private static JsonElement normalizeLegacyItemStackJson(JsonElement json)"))
        assertTrue(serializerSource.contains("normalized.add(\"id\", obj.get(\"item\"));"))
        assertTrue(serializerSource.contains("components.add(\"minecraft:custom_data\", obj.get(\"nbt\"));"))
        assertTrue(serializerSource.contains("ItemStack stack = decodeItemStack(chanceResultStackJson(obj), \"ChanceResult.item\");"))
        assertTrue(serializerSource.contains("orElseThrow(() -> new JsonParseException("))
        assertFalse(serializerSource.contains(".orElse(ItemStack.EMPTY);"))
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/fluid/FluidIngredient.java").exists())
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/recipe/AbstractRecipe.java").exists())
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/recipe/ChanceResult.java").exists())
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/recipe/AbstractRecipeSerializer.java").exists())
    }

    @Test
    fun `does not exclude legacy datagen sources from strict migration`() {
        val projectDir = tempDir.resolve("p16")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        val builderDir = dataDir.resolve("builder")
        dataDir.createDirectories()
        builderDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
            }
        """.trimIndent())
        builderDir.resolve("ExampleRecipeBuilder.java").writeText("""
            package com.example.data.builder;

            public class ExampleRecipeBuilder {
                public static class Result implements RecipeOutput {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/data/ExampleRecipeProvider.java'"))
        assertFalse(content.contains("exclude 'com/example/data/builder/ExampleRecipeBuilder.java'"))
    }

    @Test
    fun `does not exclude main-source gametest fixtures from strict migration`() {
        val projectDir = tempDir.resolve("p17")
        val testDir = projectDir.resolve("src/main/java/com/example/test/util")
        testDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        testDir.resolve("SakuraTestBase.java").writeText("""
            package com.example.test.util;

            public final class SakuraTestBase {
                RegistryObject<?> oldForgeTestOnlyField;
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/test/util/SakuraTestBase.java'"))
    }

    @Test
    fun `does not create source exclusions for removed api references`() {
        val projectDir = tempDir.resolve("p18")
        val blockDir = projectDir.resolve("src/main/java/com/example/block")
        blockDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        blockDir.resolve("ProviderRegistry.java").writeText("""
            package com.example.block;

            public final class ProviderRegistry {
                public static final Object PROVIDER = Capabilities.ITEM_HANDLER;
            }
        """.trimIndent())
        blockDir.resolve("FoodBlock.java").writeText("""
            package com.example.block;

            public class FoodBlock extends Block {
                public FoodBlock() {
                    super(ProviderRegistry.PROVIDER);
                }

                public int unrelated01() { return 1; }
                public int unrelated02() { return 2; }
                public int unrelated03() { return 3; }
                public int unrelated04() { return 4; }
                public int unrelated05() { return 5; }
                public int unrelated06() { return 6; }
                public int unrelated07() { return 7; }
                public int unrelated08() { return 8; }
                public int unrelated09() { return 9; }
                public int unrelated10() { return 10; }
                public int unrelated11() { return 11; }
                public int unrelated12() { return 12; }
                public int unrelated13() { return 13; }
                public int unrelated14() { return 14; }
                public int unrelated15() { return 15; }
                public int unrelated16() { return 16; }
                public int unrelated17() { return 17; }
                public int unrelated18() { return 18; }
                public int unrelated19() { return 19; }
                public int unrelated20() { return 20; }
                public int unrelated21() { return 21; }
                public int unrelated22() { return 22; }
                public int unrelated23() { return 23; }
                public int unrelated24() { return 24; }
                public int unrelated25() { return 25; }
                public int unrelated26() { return 26; }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/block/ProviderRegistry.java'"))
        assertFalse(content.contains("exclude 'com/example/block/FoodBlock.java'"))
    }

    @Test
    fun `keeps curse maven compileOnly integrations in source set without excluding core`() {
        val projectDir = tempDir.resolve("p19")
        val compatDir = projectDir.resolve("src/main/java/com/example/compat")
        val coreDir = projectDir.resolve("src/main/java/com/example/core")
        compatDir.createDirectories()
        coreDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("curse.maven:alexs-mobs-426558:4610263")
            }
        """.trimIndent())
        compatDir.resolve("AlexsMobsEventHandler.java").writeText("""
            package com.example.compat;

            import com.github.alexthe666.alexsmobs.entity.EntityFly;

            public final class AlexsMobsEventHandler {
                public static boolean hasFlies(Object level) {
                    return EntityFly.class != null && level != null;
                }
            }
        """.trimIndent())
        compatDir.resolve("CompatBridge.java").writeText("""
            package com.example.compat;

            public final class CompatBridge {
                public boolean usesAlexsMobs(Object level) {
                    if (level == null) {
                        return false;
                    }
                    return AlexsMobsEventHandler.hasFlies(level);
                }

                public int unrelated01() { return 1; }
                public int unrelated02() { return 2; }
                public int unrelated03() { return 3; }
                public int unrelated04() { return 4; }
                public int unrelated05() { return 5; }
                public int unrelated06() { return 6; }
                public int unrelated07() { return 7; }
                public int unrelated08() { return 8; }
                public int unrelated09() { return 9; }
                public int unrelated10() { return 10; }
                public int unrelated11() { return 11; }
                public int unrelated12() { return 12; }
                public int unrelated13() { return 13; }
                public int unrelated14() { return 14; }
                public int unrelated15() { return 15; }
                public int unrelated16() { return 16; }
                public int unrelated17() { return 17; }
                public int unrelated18() { return 18; }
                public int unrelated19() { return 19; }
                public int unrelated20() { return 20; }
                public int unrelated21() { return 21; }
                public int unrelated22() { return 22; }
                public int unrelated23() { return 23; }
                public int unrelated24() { return 24; }
                public int unrelated25() { return 25; }
                public int unrelated26() { return 26; }
            }
        """.trimIndent())
        coreDir.resolve("CoreRegistry.java").writeText("""
            package com.example.core;

            import com.example.compat.AlexsMobsEventHandler;

            public final class CoreRegistry {
                public static final String OPTIONAL_HANDLER =
                    AlexsMobsEventHandler.class.getName();

                public int unrelated01() { return 1; }
                public int unrelated02() { return 2; }
                public int unrelated03() { return 3; }
                public int unrelated04() { return 4; }
                public int unrelated05() { return 5; }
                public int unrelated06() { return 6; }
                public int unrelated07() { return 7; }
                public int unrelated08() { return 8; }
                public int unrelated09() { return 9; }
                public int unrelated10() { return 10; }
                public int unrelated11() { return 11; }
                public int unrelated12() { return 12; }
                public int unrelated13() { return 13; }
                public int unrelated14() { return 14; }
                public int unrelated15() { return 15; }
                public int unrelated16() { return 16; }
                public int unrelated17() { return 17; }
                public int unrelated18() { return 18; }
                public int unrelated19() { return 19; }
                public int unrelated20() { return 20; }
                public int unrelated21() { return 21; }
                public int unrelated22() { return 22; }
                public int unrelated23() { return 23; }
                public int unrelated24() { return 24; }
                public int unrelated25() { return 25; }
                public int unrelated26() { return 26; }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(content.contains("""compileOnly "curse.maven:alexs-mobs-1-21-1-port-1415721:7391745""""))
        assertFalse(content.contains("4610263"))
        assertFalse(content.contains("E:/MinecraftDev"))
        assertFalse(content.contains("exclude 'com/example/compat/AlexsMobsEventHandler.java'"))
        assertFalse(content.contains("exclude 'com/example/compat/CompatBridge.java'"))
        assertFalse(content.contains("exclude 'com/example/core/CoreRegistry.java'"))
    }

    @Test
    fun `resolves Create CurseMaven dependency to NeoForge compatible file`() {
        val projectDir = tempDir.resolve("p19-create")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("curse.maven:create-328085:7178761")
                runtimeOnly "curse.maven:create-328085:7178761"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:create-328085:7408951""""))
        assertFalse(content.contains("7178761"))
        assertFalse(content.contains("net.minecraftforge.fluids"))
    }

    @Test
    fun `resolves JEI to compile common api and full NeoForge runtime jars`() {
        val projectDir = tempDir.resolve("p19-jei")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("mezz.jei:jei-1.20.1-forge-api:15.2.0.27")
                runtimeOnly fg.deobf("mezz.jei:jei-1.20.1-forge:15.2.0.27")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "mezz.jei:jei-1.21.1-common-api:19.21.2.313""""))
        assertTrue(content.contains("""compileOnly "mezz.jei:jei-1.21.1-neoforge:19.21.2.313""""))
        assertTrue(content.contains("""runtimeOnly "mezz.jei:jei-1.21.1-neoforge:19.21.2.313""""))
        assertFalse(content.contains("jei-1.20.1-forge"), content)
        assertFalse(content.contains("fg.deobf"), content)
    }

    @Test
    fun `resolves versioned public library dependencies to target NeoForge coordinates`() {
        val projectDir = tempDir.resolve("p19-versioned-public-libraries")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf("com.aetherteam.nitrogen:nitrogen_internals:${'$'}{project.nitrogen_version}")
                implementation fg.deobf("com.aetherteam.cumulus:cumulus_menus:${'$'}{project.cumulus_version}")
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            nitrogen_version=1.20.1-1.0.12-neoforge
            cumulus_version=1.20.1-1.0.1-neoforge
        """.trimIndent())

        val result = pass.apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()
        val props = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(build.contains("""implementation "com.aetherteam.nitrogen:nitrogen_internals:1.21.1-1.1.25-neoforge""""))
        assertTrue(build.contains("""implementation "com.aetherteam.cumulus:cumulus_menus:1.21.1-2.0.8-neoforge""""))
        assertTrue(build.contains("https://packages.aether-mod.net/Nitrogen"))
        assertTrue(build.contains("https://packages.aether-mod.net/Cumulus"))
        assertTrue(props.contains("nitrogen_version=1.21.1-1.1.25-neoforge"), props)
        assertTrue(props.contains("cumulus_version=1.21.1-2.0.8-neoforge"), props)
        assertFalse(build.contains("fg.deobf"), build)
        assertFalse(props.contains("1.20.1-1.0"), props)
    }

    @Test
    fun `resolves replaced runtime-only optional mod dependencies`() {
        val projectDir = tempDir.resolve("p19-runtime-replacement")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            repositories {
                maven {
                    name 'Tama Maven'
                    url "https://maven.tamaized.com/releases"
                }
            }

            dependencies {
                runtimeOnly fg.deobf("team-twilight:crossdimcommands:${'$'}{project.base_minecraft_version}-1.0")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""runtimeOnly "com.telepathicgrunt:CommandStructures-Neoforge:4.3.2+1.20.6""""))
        assertTrue(content.contains("https://nexus.resourcefulbees.com/repository/maven-public/"))
        assertFalse(content.contains("team-twilight:crossdimcommands"))
    }

    @Test
    fun `resolves and removes stale curse maven runtime dependencies by explicit mapping`() {
        val projectDir = tempDir.resolve("p19-runtime-stale-removal")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf("curse.maven:jade-324717:4681833")
                compileOnly "curse.maven:lootr-361276:${'$'}{project.lootr_version}"
                runtimeOnly "curse.maven:jeed-532286:4599236"
                runtimeOnly fg.deobf("curse.maven:museum-curator-859070:4629894")
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            lootr_version=5636598
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        val props = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:jade-324717:5813144""""))
        assertTrue(content.contains("""compileOnly "curse.maven:lootr-361276:5832064""""))
        assertTrue(content.contains("""runtimeOnly "curse.maven:jeed-532286:6550600""""))
        assertFalse(content.contains("4681833"))
        assertFalse(content.contains("5636598"))
        assertFalse(content.contains("4599236"))
        assertFalse(content.contains("curse.maven:museum-curator-859070"))
        assertFalse(content.contains("4629894"))
        assertTrue(props.contains("lootr_version=5832064"), props)
    }

    @Test
    fun `renders resolved optional public curse maven dependencies as compileOnly coordinates`() {
        val projectDir = tempDir.resolve("p19-public-curse-maven")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("curse.maven:alexs-mobs-426558:5698791")
                runtimeOnly "curse.maven:alexs-mobs-426558:5698791"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:alexs-mobs-1-21-1-port-1415721:7391745""""))
        assertFalse(content.contains("""runtimeOnly "curse.maven:alexs-mobs-1-21-1-port-1415721:7391745""""))
        assertFalse(content.contains("5698791"))
        assertFalse(content.contains("E:/MinecraftDev"))
        assertFalse(content.contains("""compileOnly "files("""))
    }

    @Test
    fun `resolves Botania map style dependency to NeoForge compileOnly snapshot`() {
        val projectDir = tempDir.resolve("p19-botania")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly [
                    group: "vazkii.botania",
                    name: "Botania",
                    version: "${'$'}{project.botania}",
                    classifier: "api"
                ]
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "vazkii.botania:botania-neoforge-1.21.1:452-SNAPSHOT""""))
        assertTrue(content.contains("https://maven.blamejared.com"))
        assertFalse(content.contains("TO" + "DO: Update for NeoForge"))
        assertFalse(content.contains("name: \"Botania\""))
    }

    @Test
    fun `resolves CraftTweaker to official NeoForge maven artifact`() {
        val projectDir = tempDir.resolve("p19-crafttweaker")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf("com.blamejared.crafttweaker:CraftTweaker-forge-1.20.1:14.0.14")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""implementation "com.blamejared.crafttweaker:CraftTweaker-neoforge-1.21.1:21.0.38""""))
        assertTrue(content.contains("https://maven.blamejared.com"))
        assertFalse(content.contains("CraftTweaker-forge-1.20.1"))
        assertFalse(content.contains("maven.modrinth:crafttweaker"))
    }

    @Test
    fun `migrates RecipeProvider lookup constructor and special recipe factory`() {
        val projectDir = tempDir.resolve("p19-datagen")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("RecipeGenerator.java").writeText("""
            package com.example.data;

            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.data.recipes.SpecialRecipeBuilder;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class RecipeGenerator extends RecipeProvider {
                public RecipeGenerator(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput consumer) {
                    specialRecipe(consumer, RecipeWandUpgrade.SERIALIZER);
                }

                private void specialRecipe(RecipeOutput consumer, SimpleCraftingRecipeSerializer<RecipeWandUpgrade> serializer) {
                    ResourceLocation name = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
                    SpecialRecipeBuilder.special(serializer).save(consumer, ExampleMod.loc("dynamic/" + name.getPath()).toString());
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new RecipeGenerator(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("RecipeGenerator.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-datagen-api-121" })
        assertTrue(recipe.contains("import java.util.concurrent.CompletableFuture;"))
        assertTrue(recipe.contains("import net.minecraft.core.HolderLookup;"))
        assertTrue(recipe.contains("import net.minecraft.core.registries.BuiltInRegistries;"))
        assertTrue(recipe.contains("public RecipeGenerator(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider)"))
        assertTrue(recipe.contains("super(packOutput, lookupProvider);"))
        assertTrue(recipe.contains("java.util.function.Function<net.minecraft.world.item.crafting.CraftingBookCategory, net.minecraft.world.item.crafting.Recipe<?>> recipeFactory"))
        assertTrue(recipe.contains("specialRecipe(consumer, RecipeWandUpgrade::new, BuiltInRegistries.RECIPE_SERIALIZER.getKey(RecipeWandUpgrade.SERIALIZER));"))
        assertTrue(recipe.contains("SpecialRecipeBuilder.special(recipeFactory).save"))
        assertFalse(recipe.contains("SpecialRecipeBuilder.special(serializer)"))
        assertFalse(recipe.contains("ResourceLocation name ="))
        assertFalse(recipe.contains("Registries.RECIPE_SERIALIZER.getKey(serializer)"))
        assertTrue(modData.contains("new RecipeGenerator(packOutput, event.getLookupProvider())"))
    }

    @Test
    fun `migrates mmlib recipe and loot datagen providers to lookup constructors`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import com.example.ExampleMod;
            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, ExampleMod.MODID);
                }
            }
        """.trimIndent())
        dataDir.resolve("ExampleLootTableProvider.java").writeText("""
            package com.example.data;

            import net.minecraft.data.PackOutput;
            import net.minecraft.data.loot.LootTableProvider;
            import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
            import java.util.List;
            import java.util.Set;

            public class ExampleLootTableProvider extends LootTableProvider {
                public ExampleLootTableProvider(PackOutput packOutput) {
                    super(packOutput, Set.of(), List.of(new SubProviderEntry(ExampleBlockLoot::new, LootContextParamSets.BLOCK)));
                }
            }
        """.trimIndent())
        dataDir.resolve("ExampleLootModifierProvider.java").writeText("""
            package com.example.data;

            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

            public class ExampleLootModifierProvider extends GlobalLootModifierProvider {
                public ExampleLootModifierProvider(PackOutput output, String modid) {
                    super(output, modid);
                }

                protected void start() {
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import com.example.ExampleMod;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;
            import java.util.concurrent.CompletableFuture;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                    generator.addProvider(true, new ExampleLootTableProvider(packOutput));
                    generator.addProvider(true, new ExampleLootModifierProvider(packOutput, ExampleMod.MODID));
                }
            }
        """.trimIndent())
        projectDir.resolve("src/main/java/com/example/ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()
        val lootTable = dataDir.resolve("ExampleLootTableProvider.java").readText()
        val lootModifier = dataDir.resolve("ExampleLootModifierProvider.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-datagen-api-121" })
        assertTrue(recipe.contains("public ExampleRecipeProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider)"))
        assertTrue(recipe.contains("super(packOutput, com.example.ExampleMod.MODID, lookupProvider);"))
        assertTrue(lootTable.contains("public ExampleLootTableProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider)"))
        assertTrue(lootTable.contains("lookupProvider"))
        assertTrue(lootModifier.contains("public ExampleLootModifierProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, String modid)"))
        assertTrue(lootModifier.contains("super(output, lookupProvider, modid);"))
        assertTrue(modData.contains("new ExampleRecipeProvider(packOutput, provider)"))
        assertTrue(modData.contains("new ExampleLootTableProvider(packOutput, provider)"))
        assertTrue(modData.contains("new ExampleLootModifierProvider(packOutput, provider, ExampleMod.MODID)"))
    }

    @Test
    fun `global loot modifier provider keeps already migrated lookup constructor calls`() {
        val projectDir = tempDir.resolve("p19-global-loot-modifier-existing-lookup")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleLootModifierProvider.java").writeText("""
            package com.example.data;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

            public class ExampleLootModifierProvider extends GlobalLootModifierProvider {
                public ExampleLootModifierProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
                    super(output, lookupProvider, "example");
                }

                protected void start() {
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
                    generator.addProvider(true, new ExampleLootModifierProvider(packOutput, provider));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(modData.contains("new ExampleLootModifierProvider(packOutput, provider)"), modData)
        assertFalse(modData.contains("provider, provider"), modData)
    }

    @Test
    fun `mmlib recipe provider derives mod id from project metadata`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen-metadata-modid")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        val metaInfDir = projectDir.resolve("src/main/resources/META-INF")
        dataDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        metaInfDir.resolve("mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="examplemod"
            version="1.0.0"
            displayName="Example Mod"
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, "example_recipe");
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected build-system errors: ${result.errors}")
        assertTrue(result.changes.any { it.ruleId == "build-datagen-api-121" })
        assertFalse(recipe.contains("\"minecraft\""))
        assertTrue(recipe.contains("CompletableFuture<HolderLookup.Provider> lookupProvider"))
        assertTrue(recipe.contains("super(packOutput, \"examplemod\", lookupProvider);"))
        assertTrue(modData.contains("new ExampleRecipeProvider(packOutput, event.getLookupProvider())"))
    }

    @Test
    fun `mmlib recipe provider does not derive mod id from unrelated constants`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen-unrelated-modid")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import com.dependency.DependencyMod;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, DependencyMod.MODID);
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for AbstractRecipeProvider ExampleRecipeProvider") },
            "Expected hard migration error, got: ${result.errors}"
        )
        assertFalse(recipe.contains("DependencyMod.MODID, lookupProvider"))
        assertTrue(recipe.contains("super(packOutput);"))
    }

    @Test
    fun `mmlib recipe provider without any mod id source reports hard migration error`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen-missing-modid")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, "example_recipe");
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for AbstractRecipeProvider ExampleRecipeProvider") },
            "Expected hard migration error, got: ${result.errors}"
        )
        assertFalse(recipe.contains("\"minecraft\""))
        assertTrue(recipe.contains("super(packOutput);"))
        assertTrue(modData.contains("new ExampleRecipeProvider(packOutput)"))
    }

    @Test
    fun `migrates custom stat registration to deferred register`() {
        val projectDir = tempDir.resolve("p19-custom-stats")
        val rootDir = projectDir.resolve("src/main/java/com/example")
        val basicsDir = rootDir.resolve("basics")
        basicsDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        rootDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.basics.ModStats;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
                    modEventBus.addListener(this::commonSetup);
                    ModItems.ITEMS.register(modEventBus);
                }

                private void commonSetup(final FMLCommonSetupEvent event) {
                    ModStats.register();
                }
            }
        """.trimIndent())
        basicsDir.resolve("ModStats.java").writeText("""
            package com.example.basics;

            import com.example.ExampleMod;
            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.stats.StatFormatter;
            import net.minecraft.stats.Stats;

            public class ModStats
            {
                public static final ResourceLocation USE_WAND = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "use_wand");

                public static void register() {
                    registerStat(USE_WAND);
                }

                private static void registerStat(ResourceLocation registryName) {
                    Registry.register(BuiltInRegistries.CUSTOM_STAT, registryName.getPath(), registryName);
                    Stats.CUSTOM.get(registryName, StatFormatter.DEFAULT);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val stats = basicsDir.resolve("ModStats.java").readText()
        val mod = rootDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-custom-stat-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "build-custom-stat-mod-event-bus" })
        assertTrue(stats.contains("import net.minecraft.core.registries.Registries;"))
        assertTrue(stats.contains("import net.neoforged.bus.api.IEventBus;"))
        assertTrue(stats.contains("import net.neoforged.neoforge.registries.DeferredRegister;"))
        assertTrue(stats.contains("DeferredRegister.create(Registries.CUSTOM_STAT, ExampleMod.MODID)"))
        assertTrue(stats.contains("""CUSTOM_STATS.register("use_wand", () -> USE_WAND);"""))
        assertTrue(stats.contains("public static void register(IEventBus modEventBus)"))
        assertFalse(stats.contains("Registry.register(BuiltInRegistries.CUSTOM_STAT"))
        assertFalse(stats.contains("Stats.CUSTOM.get"))
        assertTrue(mod.contains("ModStats.register(modEventBus);"))
        assertFalse(mod.contains("ModStats.register();"))
    }

    @Test
    fun `namespaces register event resource locations with mod id`() {
        val projectDir = tempDir.resolve("p19-registerevent-location")
        val itemDir = projectDir.resolve("src/main/java/com/example/items")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        itemDir.resolve("ModItems.java").writeText("""
            package com.example.items;

            import com.example.ExampleMod;
            import net.minecraft.core.registries.Registries;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.Mod.EventBusSubscriber;
            import net.neoforged.neoforge.registries.RegisterEvent;

            @EventBusSubscriber(modid = ExampleMod.MODID)
            public class ModItems {
                @SubscribeEvent
                public static void registerRecipeSerializers(RegisterEvent event) {
                    event.register(Registries.RECIPE_SERIALIZER, registry -> {
                        registry.register("wand_upgrade", RecipeWandUpgrade.SERIALIZER);
                        registry.register("example:qualified_upgrade", QualifiedUpgrade.SERIALIZER);
                    });
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = itemDir.resolve("ModItems.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-registerevent-resource-location-namespace" })
        assertTrue(content.contains("import net.minecraft.resources.ResourceLocation;"))
        assertTrue(content.contains("""registry.register(ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "wand_upgrade"), RecipeWandUpgrade.SERIALIZER);"""))
        assertTrue(content.contains("""registry.register(ResourceLocation.parse("example:qualified_upgrade"), QualifiedUpgrade.SERIALIZER);"""))
        assertFalse(content.contains("""ResourceLocation.parse("wand_upgrade")"""))
        assertFalse(content.contains("""registry.register("wand_upgrade""""))
    }

    @Test
    fun `register event string ids are not namespaced without source mod id evidence`() {
        val projectDir = tempDir.resolve("p19-registerevent-location-no-modid")
        val itemDir = projectDir.resolve("src/main/java/com/example/items")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        itemDir.resolve("ModItems.java").writeText("""
            package com.example.items;

            import net.minecraft.core.registries.Registries;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.registries.RegisterEvent;

            public class ModItems {
                @SubscribeEvent
                public static void registerRecipeSerializers(RegisterEvent event) {
                    event.register(Registries.RECIPE_SERIALIZER, registry -> {
                        registry.register("wand_upgrade", RecipeWandUpgrade.SERIALIZER);
                    });
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = itemDir.resolve("ModItems.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-registerevent-resource-location-namespace" })
        assertTrue(content.contains("""registry.register("wand_upgrade", RecipeWandUpgrade.SERIALIZER);"""))
        assertFalse(content.contains("minecraft:wand_upgrade"))
        assertFalse(content.contains("ResourceLocation.fromNamespaceAndPath"))
    }

    @Test
    fun `migrates world carver register event to deferred register`() {
        val projectDir = tempDir.resolve("p19-world-carvers")
        val initDir = projectDir.resolve("src/main/java/com/example/init")
        val rootDir = projectDir.resolve("src/main/java/com/example")
        initDir.createDirectories()
        rootDir.createDirectories()
        initDir.resolve("ExampleCarvers.java").writeText("""
            package com.example.init;

            import net.minecraft.core.HolderGetter;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.data.worldgen.BootstrapContext;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.BlockTags;
            import net.minecraft.util.valueproviders.ConstantFloat;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.levelgen.VerticalAnchor;
            import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
            import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
            import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.registries.ForgeRegistries;
            import net.neoforged.neoforge.registries.RegisterEvent;
            import com.example.ExampleMod;
            import com.example.world.ExampleCavesCarver;

            import java.util.Objects;

            @Mod.EventBusSubscriber(modid = ExampleMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
            public class ExampleCarvers {
                public static final ExampleCavesCarver EXAMPLE_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, false);
                public static final ExampleCavesCarver HIGHLAND_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, true);

                // configured versions need explicit registration
                @SubscribeEvent
                public static void register(RegisterEvent evt) {
                    if (Objects.equals(evt.getForgeRegistry(), ForgeRegistries.WORLD_CARVERS)) {
                        evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(ExampleMod.prefix("example_caves"), EXAMPLE_CAVES));
                        evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(ExampleMod.prefix("highland_caves"), HIGHLAND_CAVES));
                    }
                }

                public static final ResourceKey<ConfiguredWorldCarver<?>> EXAMPLE_CAVES_CONFIGURED = registerKey("example_caves");

                private static ResourceKey<ConfiguredWorldCarver<?>> registerKey(String name) {
                    return ResourceKey.create(Registries.CONFIGURED_CARVER, ExampleMod.prefix(name));
                }

                public static void bootstrap(BootstrapContext<ConfiguredWorldCarver<?>> context) {
                    HolderGetter<Block> blocks = context.lookup(Registries.BLOCK);
                    context.register(EXAMPLE_CAVES_CONFIGURED, EXAMPLE_CAVES.configured(new CaveCarverConfiguration(0.1F, UniformHeight.of(VerticalAnchor.aboveBottom(5), VerticalAnchor.absolute(-8)), ConstantFloat.of(0.6F), VerticalAnchor.bottom(), blocks.getOrThrow(BlockTags.OVERWORLD_CARVER_REPLACEABLES), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), ConstantFloat.of(-0.7F))));
                }
            }
        """.trimIndent())
        rootDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;

            public class ExampleMod {
                public static final String ID = "example";

                public ExampleMod(IEventBus modbus) {
                    modbus.addListener(com.example.init.ExampleCarvers::register);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val carvers = initDir.resolve("ExampleCarvers.java").readText()
        val mod = rootDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-world-carver-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "build-world-carver-modbus-register" })
        assertTrue(carvers.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES = DeferredRegister.create(Registries.CARVER, ExampleMod.ID)"))
        assertTrue(carvers.contains("DeferredHolder<WorldCarver<?>, ExampleCavesCarver> EXAMPLE_CAVES = CARVER_TYPES.register(\"example_caves\""))
        assertTrue(carvers.contains("DeferredHolder<WorldCarver<?>, ExampleCavesCarver> HIGHLAND_CAVES = CARVER_TYPES.register(\"highland_caves\""))
        assertTrue(carvers.contains("EXAMPLE_CAVES.value().configured(new CaveCarverConfiguration"))
        assertFalse(carvers.contains("RegisterEvent"))
        assertFalse(carvers.contains("ForgeRegistries.WORLD_CARVERS"))
        assertFalse(carvers.contains("@SubscribeEvent"))
        assertTrue(mod.contains("com.example.init.ExampleCarvers.CARVER_TYPES.register(modbus);"))
        assertFalse(mod.contains("ExampleCarvers::register"))
    }

    @Test
    fun `world carver migration does not derive mod id from unrelated constants`() {
        val projectDir = tempDir.resolve("p19-world-carvers-unrelated-modid")
        val initDir = projectDir.resolve("src/main/java/com/example/init")
        initDir.createDirectories()
        initDir.resolve("ExampleCarvers.java").writeText("""
            package com.example.init;

            import com.dependency.DependencyMod;
            import com.example.world.ExampleCavesCarver;
            import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.registries.ForgeRegistries;
            import net.neoforged.neoforge.registries.RegisterEvent;

            public class ExampleCarvers {
                public static final ExampleCavesCarver EXAMPLE_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, false);

                @SubscribeEvent
                public static void register(RegisterEvent evt) {
                    evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(DependencyMod.prefix("example_caves"), EXAMPLE_CAVES));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val carvers = initDir.resolve("ExampleCarvers.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for world carver registration") },
            "Expected hard migration error, got: ${result.errors}"
        )
        assertFalse(carvers.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES"))
        assertTrue(carvers.contains("DependencyMod.prefix(\"example_caves\")"))
    }

    @Test
    fun `does not exclude legacy criterion triggers and payload registrars with missing packet classes`() {
        val projectDir = tempDir.resolve("p20")
        val advancementDir = projectDir.resolve("src/main/java/com/example/advancements")
        val networkDir = projectDir.resolve("src/main/java/com/example/network")
        advancementDir.createDirectories()
        networkDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        advancementDir.resolve("AdvancementTrigger.java").writeText("""
            package com.example.advancements;

            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.CriterionTriggerInstance;
            import net.minecraft.advancements.critereon.SerializationContext;

            public class AdvancementTrigger implements CriterionTrigger<AdvancementTrigger.Instance> {
                public static class Instance implements CriterionTriggerInstance {
                }
            }
        """.trimIndent())
        networkDir.resolve("ModNetwork.java").writeText("""
            package com.example.network;

            import net.neoforged.neoforge.network.registration.PayloadRegistrar;

            public class ModNetwork {
                public static void register(PayloadRegistrar registrar) {
                    registrar.playToClient(MissingPacket.TYPE, MissingPacket.STREAM_CODEC, MissingPacket::handle);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/advancements/AdvancementTrigger.java'"))
        assertFalse(content.contains("exclude 'com/example/network/ModNetwork.java'"))
    }

    @Test
    fun `does not exclude codec based criterion triggers`() {
        val projectDir = tempDir.resolve("p20b")
        val advancementDir = projectDir.resolve("src/main/java/com/example/advancements")
        advancementDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        advancementDir.resolve("AdvancementTrigger.java").writeText("""
            package com.example.advancements;

            import com.mojang.serialization.Codec;
            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.CriterionTriggerInstance;
            import net.minecraft.advancements.critereon.CriterionValidator;
            import net.minecraft.server.PlayerAdvancements;

            public class AdvancementTrigger implements CriterionTrigger<AdvancementTrigger.Instance> {
                public void addPlayerListener(PlayerAdvancements playerAdvancements, CriterionTrigger.Listener<Instance> listener) {}
                public void removePlayerListener(PlayerAdvancements playerAdvancements, CriterionTrigger.Listener<Instance> listener) {}
                public void removePlayerListeners(PlayerAdvancements playerAdvancements) {}
                public Codec<Instance> codec() { return Codec.unit(new Instance()); }

                public static class Instance implements CriterionTriggerInstance {
                    public void validate(CriterionValidator validator) {}
                }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(content.contains("exclude 'com/example/advancements/AdvancementTrigger.java'"))
    }

    @Test
    fun `cleans duplicate override annotations after chained rewrites`() {
        val projectDir = tempDir.resolve("p21")
        val blockDir = projectDir.resolve("src/main/java/com/example/block")
        blockDir.createDirectories()
        blockDir.resolve("TaikoBlock.java").writeText("""
            package com.example.block;

            public class TaikoBlock {
                @Override
                @SuppressWarnings("deprecation")
                @Override
                protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
                    return InteractionResult.SUCCESS;
                }

                @Override
                @Deprecated
                @Override
                protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit, int variant) {
                    return InteractionResult.SUCCESS;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = blockDir.resolve("TaikoBlock.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-cleanup-duplicate-override" })
        assertFalse(Regex("""@Override\s*\r?\n\s*@SuppressWarnings\("deprecation"\)\s*\r?\n\s*@Override""").containsMatchIn(content))
        assertFalse(Regex("""@Override\s*\r?\n\s*@Deprecated\s*\r?\n\s*@Override""").containsMatchIn(content))
        assertTrue(Regex("""@SuppressWarnings\("deprecation"\)\s*\r?\n\s*@Override""").containsMatchIn(content))
        assertTrue(Regex("""@Deprecated\s*\r?\n\s*@Override""").containsMatchIn(content))
    }

    @Test
    fun `does not repair commented cleanup artifacts with synthetic returns`() {
        val projectDir = tempDir.resolve("p22")
        val rootDir = projectDir.resolve("src/main/java/cn/mcmod/sakura")
        val fluidDir = rootDir.resolve("fluid")
        val noodleDir = rootDir.resolve("block/noodles")
        val decoratorDir = rootDir.resolve("level/tree/decorator")
        val particleDir = rootDir.resolve("particle")
        fluidDir.createDirectories()
        noodleDir.createDirectories()
        decoratorDir.createDirectories()
        particleDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            sourceSets {
                main {
                    java {
                        exclude 'cn/mcmod/sakura/item/FoodRegistry.java'
                    }
                }
            }
        """.trimIndent())
        fluidDir.resolve("BucketItemRegistry.java").writeText("""
            package cn.mcmod.sakura.fluid;

            public class BucketItemRegistry {
                public static final Object ITEMS = new Object();
            }
        """.trimIndent())
        rootDir.resolve("SakuraMod.java").writeText("""
            package cn.mcmod.sakura;

            // [forge2neo] import cn.mcmod.sakura.fluid.BucketItemRegistry; // excluded

            public class SakuraMod {
                public void register(Object modEventBus) {
                    BucketItemRegistry.ITEMS.toString();
                }
            }
        """.trimIndent())
        noodleDir.resolve("BlockPasta.java").writeText("""
            package cn.mcmod.sakura.block.noodles;

            import net.minecraft.world.item.ItemStack;

            public class BlockPasta {
                public ItemStack getNoodle() {
                    // [forge2neo] return new ItemStack(FoodRegistry.FOOD.get()); // excluded: FoodRegistry unavailable
                }
            }
        """.trimIndent())
        decoratorDir.resolve("ChestnutBurrDecorator.java").writeText("""
            package cn.mcmod.sakura.level.tree.decorator;

            public class ChestnutBurrDecorator {
                protected TreeDecoratorType<?> type() {
                    // [forge2neo] return SakuraFeatureRegistry.CHESTNUT_BURR_DECORATOR.get(); // excluded: SakuraFeatureRegistry unavailable
                }
            }
        """.trimIndent())
        particleDir.resolve("ShowerParticle.java").writeText("""
            package cn.mcmod.sakura.particle;

            public class ShowerParticle {
                public ParticleRenderType getRenderType() {
                    // [forge2neo] return ClientConfig.enableTranslucentParticles // excluded: ClientConfig unavailable
                        ? ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
                        : ParticleRenderType.PARTICLE_SHEET_OPAQUE;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val pastaContent = noodleDir.resolve("BlockPasta.java").readText()
        val decoratorContent = decoratorDir.resolve("ChestnutBurrDecorator.java").readText()
        val particleContent = particleDir.resolve("ShowerParticle.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-repair-post-cleanup-artifacts" })
        assertFalse(result.changes.any { it.ruleId == "build-cleanup-excluded-refs" })
        assertFalse(pastaContent.contains("return ItemStack.EMPTY;"))
        assertFalse(decoratorContent.contains("return null;"))
        assertFalse(particleContent.contains("return null;"))
    }
}
