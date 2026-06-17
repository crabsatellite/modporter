package com.modporter.transforms

import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextReplacementTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createTestFile(content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("TestMod.java")
        file.writeText(content)
        return tempDir
    }

    @Test
    fun `package renames are applied correctly`() {
        val projectDir = createTestFile("""
            package com.example;
            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.eventbus.api.IEventBus;
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("net.neoforged.neoforge.common.NeoForge"))
        assertTrue(transformed.contains("net.neoforged.fml.common.Mod"))
        assertTrue(transformed.contains("net.neoforged.bus.api.IEventBus"))
        assertTrue(!transformed.contains("net.minecraftforge"))

        assertTrue(result.changeCount > 0)
        assertTrue(result.changes.all { it.confidence == Confidence.HIGH })
    }

    @Test
    fun `class renames are applied correctly`() {
        val projectDir = createTestFile("""
            MinecraftForge.EVENT_BUS.register(this);
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            ForgeHooks.onLivingAttack(entity, source, amount);
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("NeoForge.EVENT_BUS"))
        assertTrue(transformed.contains("ModConfigSpec.Builder"))
        assertTrue(transformed.contains("CommonHooks.onLivingAttack"))
    }

    @Test
    fun `network direction checks are not replaced with constant placeholders`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                public boolean server(Object ctx) {
                    return ctx.get().getDirection().getReceptionSide().isServer();
                }

                public boolean client(Object ctx) {
                    return ctx.get().getDirection().getReceptionSide().isClient();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("ctx.get().getDirection().getReceptionSide().isServer()"))
        assertTrue(transformed.contains("ctx.get().getDirection().getReceptionSide().isClient()"))
        assertFalse(transformed.contains("true /*"))
        assertFalse(transformed.contains("direction check"))
    }

    @Test
    fun `registry constants are replaced`() {
        val projectDir = createTestFile("""
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.FLUIDS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.POI_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.VILLAGER_PROFESSIONS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.FOLIAGE_PLACER_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.TREE_DECORATOR_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.WORLD_CARVERS, MOD_ID);
            DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, MOD_ID);
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MOD_ID);
            DeferredRegister
                .create(ForgeRegistries.RECIPE_TYPES, MOD_ID);
            DeferredRegister.create(
                ForgeRegistries.FEATURES, MOD_ID);
            ForgeRegistries.ITEMS.getKey(item);
            ForgeRegistries.FLUIDS.getValue(fluidId);
            ForgeRegistries.POTIONS.getKey(potion);
            ForgeRegistries.PAINTING_VARIANTS.getKey(art);
            BootstapContext<?> context;
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        // DeferredRegister.create uses Registries (ResourceKey)
        assertTrue(transformed.contains("DeferredRegister.create(Registries.ITEM"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.BLOCK"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.ENTITY_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.FLUID"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.VILLAGER_PROFESSION"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.FOLIAGE_PLACER_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.TREE_DECORATOR_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.CARVER"))
        assertTrue(transformed.contains("DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES"))
        assertTrue(transformed.contains("DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.RECIPE_TYPE"))
        assertTrue(transformed.contains("DeferredRegister.create(Registries.FEATURE"))
        // Non-DeferredRegister context uses BuiltInRegistries (Registry instance)
        assertTrue(transformed.contains("BuiltInRegistries.ITEM.getKey"))
        assertTrue(transformed.contains("BuiltInRegistries.FLUID.get(fluidId)"))
        assertTrue(transformed.contains("BuiltInRegistries.POTION.getKey(potion)"))
        assertTrue(transformed.contains("BuiltInRegistries.PAINTING_VARIANT.getKey(art)"))
        assertTrue(transformed.contains("BootstrapContext<?> context"))
    }

    @Test
    fun `removed DistExecutor imports do not leave migration placeholders`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.neoforged.fml.DistExecutor;

            public class TestMod {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("DistExecutor"))
        assertFalse(transformed.contains("[forge2neo]"))
        assertFalse(transformed.contains("removed in NeoForge"))
    }

    @Test
    fun `syntax sensitive migrations keep valid Java statements`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.ContainerHelper;
            import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
            import net.minecraft.world.item.ItemStack;
            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.simple.SimpleChannel;

            public class TestMod extends MeleeAttackGoal {
                public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
                    ExampleMod.prefix("channel"),
                    () -> "1",
                    "1"::equals,
                    "1"::equals
                );

                public void render() {
                    Tesselator tesselator = Tesselator.getInstance();
                    BufferBuilder bufferbuilder = tesselator.getBuilder();
                    bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                }

                public void tick() {
                    if (true)
                        super.tick();
                }

                public void save(CompoundTag tag) {
                    ContainerHelper.saveAllItems(tag, this.getItemStacks());
                    ContainerHelper.loadAllItems(tag, this.getItemStacks());
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(
            transformed.contains("BufferBuilder bufferbuilder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);") ||
                transformed.contains("bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);")
        )
        assertTrue(transformed.contains("super.tick();"))
        assertTrue(transformed.contains("ContainerHelper.saveAllItems(tag, this.getItemStacks(), net.minecraft.core.RegistryAccess.EMPTY);"))
        assertTrue(transformed.contains("ContainerHelper.loadAllItems(tag, this.getItemStacks(), net.minecraft.core.RegistryAccess.EMPTY);"))
        assertTrue(transformed.contains("NetworkRegistry.newSimpleChannel("))
        assertFalse(transformed.contains("TODO: [forge2neo]"))
        assertFalse(transformed.contains("this.getItemStacks(,"))
    }

    @Test
    fun `rendered buffer rename adds mesh data import`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.blaze3d.vertex.BufferBuilder;

            public interface TestMod {
                BufferBuilder.RenderedBuffer build(BufferBuilder builder);
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import com.mojang.blaze3d.vertex.MeshData;"))
        assertTrue(transformed.contains("MeshData build(BufferBuilder builder);"))
        assertFalse(transformed.contains("BufferBuilder.RenderedBuffer"))
    }

    @Test
    fun `gui overlay render event migrates type and layer name access`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.neoforged.neoforge.client.event.RenderGuiOverlayEvent;
            import net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay;

            public class TestMod {
                public static void preOverlay(RenderGuiOverlayEvent.Pre event) {
                    if (event.getOverlay().id() == VanillaGuiOverlay.MOUNT_HEALTH.id()) {
                        event.setCanceled(true);
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.client.gui.VanillaGuiLayers;"))
        assertTrue(transformed.contains("preOverlay(RenderGuiLayerEvent.Pre event)"))
        assertTrue(transformed.contains("event.getName().equals(VanillaGuiLayers.VEHICLE_HEALTH)"))
        assertFalse(transformed.contains("RenderGuiOverlayEvent"))
        assertFalse(transformed.contains("VanillaGuiOverlay"))
        assertFalse(transformed.contains("getOverlay()"))
    }

    @Test
    fun `IForgeXXX interface renames via text rules`() {
        val projectDir = createTestFile("""
            ForgeSpawnEggItem egg = new ForgeSpawnEggItem(entity, color1, color2, props);
            ForgeTier myTier = new ForgeTier(5, 2000, 10.0f, 4.0f, 20, tags, repairIngredient);
            ToolActions.DIG = new ToolAction();
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("DeferredSpawnEggItem"))
        assertTrue(transformed.contains("SimpleTier"))
        assertTrue(transformed.contains("ItemAbilities"))
    }

    @Test
    fun `selection list coordinate rules do not rewrite local variables`() {
        val projectDir = createTestFile("""
            private void fillBox(int x1, int y1, int z1, int x2, int y2, int z2) {
                for (int x = x1; x <= x2; x++) {
                    for (int y = y1; y <= y2; y++) {
                    }
                }
                int left = this.x0;
                int bottom = super.y1;
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("fillBox(int x1, int y1, int z1, int x2, int y2, int z2)"))
        assertTrue(transformed.contains("for (int x = x1; x <= x2; x++)"))
        assertTrue(transformed.contains("for (int y = y1; y <= y2; y++)"))
        assertTrue(transformed.contains("int left = this.getX();"))
        assertTrue(transformed.contains("int bottom = super.getBottom();"))
    }

    @Test
    fun `render highlight chained partial tick uses delta tracker accessor`() {
        val projectDir = createTestFile("""
            double partialTicks = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            double directPartialTicks = event.getPartialTick();
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("event.getDeltaTracker().getGameTimeDeltaPartialTick(false)"))
        assertTrue(transformed.contains("double directPartialTicks = event.getPartialTick();"))
    }

    @Test
    fun `hurtAndBreak lambda callback becomes equipment slot overload`() {
        val projectDir = createTestFile("""
            import net.minecraft.world.InteractionHand;

            public class TestMod {
                void damage(ItemStack wand, Player player) {
                    wand.hurtAndBreak(1, player, e -> e.onEquippedItemBroken(InteractionHand.MAIN_HAND));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.entity.EquipmentSlot;"))
        assertTrue(transformed.contains("wand.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);"))
        assertTrue(!transformed.contains("onEquippedItemBroken(InteractionHand.MAIN_HAND)"))
    }

    @Test
    fun `legacy hand references after useWithoutItem migration use main hand`() {
        val projectDir = createTestFile("""
            public class TestMod {
                void useWithoutItem(Player player, ItemStack heldStack) {
                    ItemStack stack = player.getItemInHand(hand);
                    player.swing(handIn);
                    heldStack.hurtAndBreak(1, player, p -> p.onEquippedItemBroken(hand));
                    this.takePlates(level, pos, state, player, handIn);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.InteractionHand;"))
        assertTrue(transformed.contains("import net.minecraft.world.entity.EquipmentSlot;"))
        assertTrue(transformed.contains("ItemStack stack = player.getMainHandItem();"))
        assertTrue(transformed.contains("player.swing(InteractionHand.MAIN_HAND);"))
        assertTrue(transformed.contains("heldStack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);"))
        assertTrue(transformed.contains("this.takePlates(level, pos, state, player, InteractionHand.MAIN_HAND);"))
        assertFalse(transformed.contains("handIn"))
        assertFalse(transformed.contains("onEquippedItemBroken(hand"))
    }

    @Test
    fun `gui overlay registration migrates to layered draw API`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.gui.GuiGraphics;
            import net.neoforged.neoforge.client.event.RegisterGuiOverlaysEvent;
            import net.neoforged.neoforge.client.gui.overlay.ExtendedGui;
            import net.neoforged.neoforge.client.gui.overlay.IGuiOverlay;
            import net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay;

            public class TestMod {
                public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
                    event.registerAbove(
                            VanillaGuiOverlay.HOTBAR.id(),
                            "cooldown_hud",
                            new CooldownHudOverlay()
                    );
                }
            }

            class CooldownHudOverlay implements IGuiOverlay {
                public static final ResourceLocation OVERLAY_ID = ResourceLocation.fromNamespaceAndPath("example", "cooldown_hud");

                @Override
                public void render(ExtendedGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
                    for (int i = 0; i < player.getInventory().size(); i++) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.client.gui.VanillaGuiLayers;"))
        assertTrue(transformed.contains("import net.minecraft.client.gui.LayeredDraw;"))
        assertTrue(transformed.contains("import net.minecraft.client.DeltaTracker;"))
        assertTrue(transformed.contains("registerGuiOverlays(RegisterGuiLayersEvent event)"))
        assertTrue(transformed.contains("event.registerAbove(VanillaGuiLayers.HOTBAR, CooldownHudOverlay.OVERLAY_ID, new CooldownHudOverlay())"))
        assertTrue(transformed.contains("class CooldownHudOverlay implements LayeredDraw.Layer"))
        assertTrue(transformed.contains("public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker)"))
        assertTrue(transformed.contains("player.getInventory().getContainerSize()"))
        assertTrue(!transformed.contains("RegisterGuiOverlaysEvent"))
        assertTrue(!transformed.contains("IGuiOverlay"))
        assertTrue(!transformed.contains("ExtendedGui"))
        assertTrue(!transformed.contains("VanillaGuiOverlay"))
    }

    @Test
    fun `model render rgba floats migrate to packed color`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;

            public class TestMod {
                private Model model;
                private ModelPart group;

                @Override
                public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay,
                                           float red, float green, float blue, float alpha) {
                    group.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
                }

                public void renderExternal(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float alpha) {
                    model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay,
                            1.0F, 1.0F, 1.0F, alpha);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.util.FastColor;"))
        assertTrue(transformed.contains("public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color)"))
        assertTrue(transformed.contains("group.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);"))
        assertTrue(transformed.contains("model.renderToBuffer(poseStack, vertexConsumer, packedLight, packedOverlay, FastColor.ARGB32.colorFromFloat(alpha, 1.0F, 1.0F, 1.0F));"))
        assertTrue(!transformed.contains("float red, float green, float blue, float alpha"))
    }

    @Test
    fun `entity synched data definitions migrate to builder`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.network.syncher.EntityDataAccessor;

            public class TestMod {
                @Override
                protected void defineSynchedData() {
                    super.defineSynchedData();
                    this.entityData.define(DATA_LIFETIME, 20);
                    this.getEntityData().define(DATA_LOADING, true);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.network.syncher.SynchedEntityData;"))
        assertTrue(transformed.contains("protected void defineSynchedData(SynchedEntityData.Builder builder)"))
        assertTrue(transformed.contains("super.defineSynchedData(builder);"))
        assertTrue(transformed.contains("builder.define(DATA_LIFETIME, 20);"))
        assertTrue(transformed.contains("builder.define(DATA_LOADING, true);"))
        assertTrue(!transformed.contains("this.entityData.define("))
        assertTrue(!transformed.contains("this.getEntityData().define("))
        assertTrue(!transformed.contains("super.defineSynchedData();"))
    }

    @Test
    fun `public entity synched data definitions keep visibility when migrating to builder`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.network.syncher.EntityDataAccessor;

            public class TestMod {
                @Override
                public void defineSynchedData() {
                    super.defineSynchedData();
                    this.getEntityData().define(DATA_STATE, false);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.network.syncher.SynchedEntityData;"))
        assertTrue(transformed.contains("public void defineSynchedData(SynchedEntityData.Builder builder)"))
        assertTrue(transformed.contains("super.defineSynchedData(builder);"))
        assertTrue(transformed.contains("builder.define(DATA_STATE, false);"))
        assertTrue(!transformed.contains("public void defineSynchedData()"))
        assertTrue(!transformed.contains("super.defineSynchedData();"))
    }

    @Test
    fun `saved data computeIfAbsent migrates to factory provider API`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.saveddata.SavedData;

            public class TestMod extends SavedData {
                private static final String DATA_NAME = "example_data";

                public static TestMod load(CompoundTag tag) {
                    return new TestMod();
                }

                @Override
                public CompoundTag save(CompoundTag tag) {
                    return tag;
                }

                public static TestMod get(ServerLevel level) {
                    return level.getDataStorage().computeIfAbsent(
                            TestMod::load,
                            TestMod::new,
                            DATA_NAME
                    );
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.HolderLookup;"))
        assertTrue(transformed.contains("public static TestMod load(CompoundTag tag, HolderLookup.Provider registries)"))
        assertTrue(transformed.contains("public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries)"))
        assertTrue(transformed.contains("computeIfAbsent(new SavedData.Factory<>(TestMod::new, TestMod::load), DATA_NAME"))
        assertTrue(!transformed.contains("TestMod::load,\n"))
        assertTrue(!transformed.contains("TestMod::new,\n"))
    }

    @Test
    fun `common 1_21 API moves migrate by text rules`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.chunk.ChunkStatus;
            import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
            import net.neoforged.neoforge.event.level.BlockEvent;
            import net.neoforged.neoforge.event.tick.LevelTickEvent;

            public class TestMod {
                public static void onMobSpawn(MobSpawnEvent.FinalizeSpawn event) {
                }

                public static void onWorldTick(LevelTickEvent event) {
                    Object level = event.level;
                    Object status = ChunkStatus.FULL;
                }

                @Override
                public int getUseDuration(ItemStack stack) {
                    return 20;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.level.chunk.status.ChunkStatus;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.level.BlockEvent;"))
        assertTrue(transformed.contains("public static void onMobSpawn(FinalizeSpawnEvent event)"))
        assertTrue(transformed.contains("public static void onWorldTick(LevelTickEvent.Post event)"))
        assertTrue(transformed.contains("Object level = event.getLevel();"))
        assertTrue(transformed.contains("public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity entity)"))
        assertTrue(!transformed.contains("MobSpawnEvent.FinalizeSpawn"))
        assertTrue(!transformed.contains("Object level = event.level;"))
    }

    @Test
    fun `jade tooltip element helper migrates only declared ITooltip receivers`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import snownee.jade.api.ITooltip;

            public class TestMod {
                public void appendTooltip(ITooltip tooltip, OtherHelper other, ItemStack stack) {
                    tooltip.add(tooltip.getElementHelper().smallItem(stack));
                    tooltip.append(tooltip.getElementHelper().smallItem(stack));
                    other.getElementHelper();
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "jade-tooltip-elementhelper-static" })
        assertTrue(transformed.contains("import snownee.jade.api.ui.IElementHelper;"))
        assertTrue(transformed.contains("tooltip.add(IElementHelper.get().smallItem(stack));"))
        assertTrue(transformed.contains("tooltip.append(IElementHelper.get().smallItem(stack));"))
        assertTrue(transformed.contains("other.getElementHelper();"))
        assertFalse(transformed.contains("tooltip.getElementHelper()"))
    }

    @Test
    fun `deferred holder concrete registrations use registry base generic`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class TestMod {
                public static final DeferredHolder<CoolBlock, CoolBlock> COOL_BLOCK = BLOCKS.register(
                        "cool_block",
                        () -> new CoolBlock()
                );

                public static final DeferredHolder<CoolItem, CoolItem> COOL_ITEM = ITEMS.register(
                        "cool_item",
                        () -> new CoolItem()
                );
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("DeferredHolder<Block, CoolBlock> COOL_BLOCK = BLOCKS.register"))
        assertTrue(transformed.contains("DeferredHolder<Item, CoolItem> COOL_ITEM = ITEMS.register"))
    }

    @Test
    fun `chunk generator codec and abstract signatures migrate to mapcodec api`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.Executor;
            import net.minecraft.core.RegistryAccess;
            import net.minecraft.world.level.StructureManager;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.chunk.ChunkGenerator;
            import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
            import net.minecraft.world.level.levelgen.RandomState;
            import net.minecraft.world.level.levelgen.blending.Blender;
            import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestMod extends ChunkGenerator {
                public static final DeferredRegister<Codec<? extends ChunkGenerator>> CHUNK_GENERATORS = null;
                public static final DeferredHolder<Codec<TestMod>, Codec<TestMod>> TEST_GENERATOR =
                        CHUNK_GENERATORS.register("test", () -> TestMod.CODEC);

                public static final Codec<TestMod> CODEC = RecordCodecBuilder.create(instance ->
                        instance.group().apply(instance, TestMod::new)
                );

                @Override
                protected Codec<? extends ChunkGenerator> codec() {
                    return CODEC;
                }

                @Override
                public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                             StructureManager structureManager, ChunkAccess chunk,
                                             StructureTemplateManager structureTemplateManager) {
                }

                @Override
                public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random, StructureManager structureManager, ChunkAccess chunk) {
                    return CompletableFuture.completedFuture(chunk);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(transformed.contains("DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS"))
        assertTrue(transformed.contains("DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<TestMod>> TEST_GENERATOR"))
        assertTrue(transformed.contains("public static final MapCodec<TestMod> CODEC = RecordCodecBuilder.mapCodec"))
        assertTrue(transformed.contains("protected MapCodec<? extends ChunkGenerator> codec()"))
        assertTrue(transformed.contains("StructureTemplateManager structureTemplateManager)"))
        assertTrue(transformed.contains("fillFromNoise(Blender blender, RandomState random, StructureManager structureManager, ChunkAccess chunk)"))
    }

    @Test
    fun `common 1_21 signature migrations compile against holder and provider APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.authlib.GameProfile;
            import net.minecraft.advancements.Advancement;
            import net.minecraft.core.BlockPos;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.network.Connection;
            import net.minecraft.network.protocol.PacketFlow;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.chunk.LevelChunk;
            import net.minecraft.world.level.chunk.status.ChunkStatus;

            import java.util.UUID;

            public class TestMod extends Item {
                public TestMod(Properties properties) {
                    super(properties);
                }

                public void sample(GameTestHelper helper, ServerPlayer player, ChunkAccess chunk,
                                   MobEffectInstance effect, ItemStack stack) {
                    Advancement advancement = player.getServer().getAdvancements()
                            .getAdvancement(ResourceLocation.parse("minecraft:end/root"));
                    Advancement helperAdvancement = PlayerHelper.getAdvancement(player, ResourceLocation.parse("minecraft:story/root"));
                    player.getAdvancements().award(advancement, "entered_end");
                    boolean complete = chunk.getStatus().isOrAfter(ChunkStatus.FULL);
                    Player mock = helper.makeMockSurvivalPlayer();
                    ListTag ender = player.getEnderChestInventory().createTag();
                    player.getEnderChestInventory().fromTag(ender);
                    Tag savedEffect = effect.save(new CompoundTag());
                    int efficiency = stack.getEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY);
                    stack.enchant(Enchantments.BLOCK_EFFICIENCY, 1);
                    stack.getOrCreateTag().putLong("cooldown", 123L);
                }

                @Override
                public boolean canApplyAtEnchantingTable(ItemStack stack, Enchantment enchantment) {
                    return enchantment == Enchantments.BLOCK_EFFICIENCY;
                }

                static ServerPlayer connected(GameTestHelper helper) {
                    ServerPlayer player = new ServerPlayer(
                            helper.getLevel().getServer(),
                            helper.getLevel(),
                            new GameProfile(UUID.randomUUID(), "test-player")
                    );
                    Connection connection = new Connection(PacketFlow.SERVERBOUND);
                    helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player);
                    return player;
                }

                static void blockEntityCopy(ServerLevel mirrorWorld, LevelChunk targetChunk, BlockPos pos,
                                            CompoundTag copy, BlockEntity sourceBE) {
                    BlockEntity targetBE = BlockEntity.loadStatic(pos, targetChunk.getBlockState(pos), copy);
                    if (targetBE != null) {
                        targetBE.load(copy);
                        targetBE.load(sourceBE.saveWithoutMetadata());
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.advancements.AdvancementHolder;"))
        assertTrue(transformed.contains("import net.minecraft.server.level.ClientInformation;"))
        assertTrue(transformed.contains("import net.minecraft.server.network.CommonListenerCookie;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.GameType;"))
        assertTrue(transformed.contains("AdvancementHolder advancement = player.getServer().getAdvancements()"))
        assertTrue(transformed.contains(".get(ResourceLocation.parse(\"minecraft:end/root\"))"))
        assertTrue(transformed.contains("Advancement helperAdvancement = PlayerHelper.getAdvancement(player, ResourceLocation.parse(\"minecraft:story/root\"));"))
        assertTrue(transformed.contains("chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL)"))
        assertTrue(transformed.contains("helper.makeMockPlayer(GameType.SURVIVAL)"))
        assertTrue(transformed.contains("player.getEnderChestInventory().createTag(player.registryAccess())"))
        assertTrue(transformed.contains("player.getEnderChestInventory().fromTag(ender, player.registryAccess())"))
        assertTrue(transformed.contains("Tag savedEffect = effect.save();"))
        assertTrue(transformed.contains("new GameProfile(UUID.randomUUID(), \"test-player\"), ClientInformation.createDefault())"))
        assertTrue(transformed.contains("placeNewPlayer(connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false))"))
        assertTrue(transformed.contains("BlockEntity.loadStatic(pos, targetChunk.getBlockState(pos), copy, mirrorWorld.registryAccess())"))
        assertTrue(transformed.contains("targetBE.loadWithComponents(copy, targetBE.getLevel().registryAccess());"))
        assertTrue(transformed.contains("targetBE.loadWithComponents(sourceBE.saveWithoutMetadata(targetBE.getLevel().registryAccess()), targetBE.getLevel().registryAccess());"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY))"))
        assertTrue(transformed.contains("stack.enchant(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 1)"))
        assertTrue(transformed.contains("CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, customDataTag -> customDataTag.putLong(\"cooldown\", 123L));"))
        assertTrue(transformed.contains("public boolean supportsEnchantment(ItemStack stack, net.minecraft.core.Holder<Enchantment> enchantment)"))
        assertTrue(transformed.contains("return enchantment.is(Enchantments.EFFICIENCY);"))
    }

    @Test
    fun `legacy custom enchantments migrate to resource keys and holder lookups`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class InstantWorldMirror {
                static final String MODID = "example";

                void init(Object modEventBus) {
                    ModEnchantments.ENCHANTMENTS.register(modEventBus);
                }
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        InstantWorldMirror.MODID
                );

                public static final EnchantmentCategory MIRROR_CATEGORY = EnchantmentCategory.create(
                        InstantWorldMirror.MODID + "_mirror",
                        item -> true
                );

                public static final DeferredHolder<Enchantment, Enchantment> PERMANENCE = ENCHANTMENTS.register(
                        "permanence",
                        PermanentMirrorEnchantment::new
                );

                private ModEnchantments() {
                }

                public static boolean hasPermanence(Level level, ItemStack stack) {
                    return stack.getEnchantmentLevel(PERMANENCE.get()) > 0;
                }

                public static void applyPermanence(Level level, ItemStack stack) {
                    stack.enchant(PERMANENCE.get(), 1);
                }

                public static boolean isPermanence(Enchantment enchantment) {
                    return enchantment == PERMANENCE.get();
                }

                private static class PermanentMirrorEnchantment extends Enchantment {
                    private PermanentMirrorEnchantment() {
                        super(Rarity.RARE, MIRROR_CATEGORY, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                    }
                }
            }

            final class Usage {
                void use(ItemStack stack, MirrorItem mirror) {
                    stack.getEnchantmentLevel(ModEnchantments.PERMANENCE.get());
                    mirror.canApplyAtEnchantingTable(stack, ModEnchantments.PERMANENCE.get());
                    new EnchantmentInstance(ModEnchantments.PERMANENCE.get(), 1);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(!transformed.contains("ENCHANTMENTS.register(modEventBus)"))
        assertTrue(!transformed.contains("DeferredRegister<Enchantment> ENCHANTMENTS"))
        assertTrue(!transformed.contains("EnchantmentCategory.create"))
        assertTrue(!transformed.contains("extends Enchantment"))
        assertTrue(transformed.contains("private static net.minecraft.core.Holder<Enchantment> holder(net.minecraft.world.level.Level level, net.minecraft.resources.ResourceKey<Enchantment> key)"))
        assertTrue(transformed.contains("public static final net.minecraft.resources.ResourceKey<Enchantment> PERMANENCE"))
        assertTrue(transformed.contains("public static final java.util.List<net.minecraft.resources.ResourceKey<Enchantment>> ENCHANTMENTS"))
        assertTrue(transformed.contains("java.util.List.of(PERMANENCE)"))
        assertTrue(transformed.contains("ResourceLocation.fromNamespaceAndPath(InstantWorldMirror.MODID, \"permanence\")"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(holder(level, PERMANENCE))"))
        assertTrue(transformed.contains("stack.enchant(holder(level, PERMANENCE), 1)"))
        assertTrue(transformed.contains("public static boolean isPermanence(net.minecraft.core.Holder<Enchantment> enchantment)"))
        assertTrue(transformed.contains("return enchantment.is(PERMANENCE);"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(ModEnchantments.PERMANENCE))"))
        assertTrue(transformed.contains("mirror.supportsEnchantment(stack, net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(ModEnchantments.PERMANENCE))"))
        assertTrue(transformed.contains("new EnchantmentInstance(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(ModEnchantments.PERMANENCE), 1)"))
    }

    @Test
    fun `legacy custom enchantment registrations generate source derived data json and item tags`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModItems.java").writeText("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModItems {
                public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ExampleMod.MODID);
                public static final DeferredHolder<Item, Item> BLOCK_AND_CHAIN = ITEMS.register("block_and_chain", () -> new ChainBlockItem());
            }
        """.trimIndent())
        srcDir.resolve("ModEffects.java").writeText("""
            package com.example;

            import net.minecraft.world.effect.MobEffect;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEffects {
                public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ExampleMod.MODID);
                public static final DeferredHolder<MobEffect, MobEffect> FROSTY = EFFECTS.register("frosty", () -> new FrostyEffect());
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, ExampleMod.MODID);
                public static final DeferredHolder<Enchantment, Enchantment> FIRE_REACT = ENCHANTMENTS.register("fire_react", () -> new FireReactEnchantment(Enchantment.Rarity.UNCOMMON));
                public static final DeferredHolder<Enchantment, Enchantment> CHILL_AURA = ENCHANTMENTS.register("chill_aura", () -> new ChillAuraEnchantment(Enchantment.Rarity.UNCOMMON));
                public static final DeferredHolder<Enchantment, Enchantment> DESTRUCTION = ENCHANTMENTS.register("destruction", () -> new DestructionEnchantment(Enchantment.Rarity.RARE));
                public static final EnchantmentCategory BLOCK_AND_CHAIN = EnchantmentCategory.create("example_block_and_chain", item -> item instanceof ChainBlockItem);
            }
        """.trimIndent())
        srcDir.resolve("LootOnlyEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;

            public class LootOnlyEnchantment extends Enchantment {
                protected LootOnlyEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot[] slots) {
                    super(rarity, category, slots);
                }

                @Override
                public boolean isTradeable() {
                    return false;
                }

                @Override
                public boolean isTreasureOnly() {
                    return true;
                }

                @Override
                public boolean isDiscoverable() {
                    return false;
                }
            }
        """.trimIndent())
        srcDir.resolve("FireReactEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.util.RandomSource;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.item.enchantment.Enchantments;

            public class FireReactEnchantment extends LootOnlyEnchantment {
                public FireReactEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
                }

                @Override
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof ArmorItem || super.canEnchant(stack);
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxCost(int level) {
                    return this.getMinCost(level) + 15;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public void doPostHurt(LivingEntity user, Entity attacker, int level) {
                    RandomSource random = user.getRandom();
                    if (attacker != null && shouldHit(level, random, attacker)) {
                        attacker.setSecondsOnFire(2 + (random.nextInt(level) * 3));
                    }
                }

                public static boolean shouldHit(int level, RandomSource random, Entity attacker) {
                    return level > 0 && random.nextFloat() < 0.15F * (float)level;
                }

                @Override
                protected boolean checkCompatibility(Enchantment other) {
                    return super.checkCompatibility(other) && other != ModEnchantments.CHILL_AURA.get() && other != Enchantments.THORNS;
                }
            }
        """.trimIndent())
        srcDir.resolve("ChillAuraEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.util.RandomSource;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.item.enchantment.Enchantments;

            public class ChillAuraEnchantment extends LootOnlyEnchantment {
                public ChillAuraEnchantment(Rarity rarity) {
                    super(rarity, EnchantmentCategory.ARMOR, new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET});
                }

                @Override
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof ArmorItem || super.canEnchant(stack);
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxCost(int level) {
                    return this.getMinCost(level) + 15;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public void doPostHurt(LivingEntity user, Entity attacker, int level) {
                    if (attacker instanceof LivingEntity entity) {
                        doChillAuraEffect(entity, 200, level - 1, this.shouldHit(level, user.getRandom()));
                    }
                }

                public static void doChillAuraEffect(LivingEntity victim, int duration, int amplifier, boolean shouldHit) {
                    if (shouldHit) {
                        victim.addEffect(new MobEffectInstance(ModEffects.FROSTY.get(), duration, amplifier));
                    }
                }

                private boolean shouldHit(int level, RandomSource random) {
                    return level > 0 && random.nextFloat() < 0.15F * level;
                }

                @Override
                protected boolean checkCompatibility(Enchantment other) {
                    return super.checkCompatibility(other) && other != ModEnchantments.FIRE_REACT.get() && other != Enchantments.THORNS;
                }
            }
        """.trimIndent())
        srcDir.resolve("DestructionEnchantment.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.MobType;
            import net.minecraft.world.item.ItemStack;

            public class DestructionEnchantment extends LootOnlyEnchantment {
                public DestructionEnchantment(Rarity rarity) {
                    super(rarity, ModEnchantments.BLOCK_AND_CHAIN, new EquipmentSlot[]{EquipmentSlot.MAINHAND});
                }

                @Override
                public int getMinCost(int level) {
                    return 5 + (level - 1) * 9;
                }

                @Override
                public int getMaxCost(int level) {
                    return this.getMinCost(level) + 15;
                }

                @Override
                public int getMaxLevel() {
                    return 3;
                }

                @Override
                public boolean canEnchant(ItemStack stack) {
                    return stack.getItem() instanceof ChainBlockItem;
                }

                @Override
                public float getDamageBonus(int level, MobType type, ItemStack item) {
                    return -level * 1.5F;
                }
            }
        """.trimIndent())

        val result = TextReplacementPass(MappingDatabase.loadDefault()).apply(tempDir)

        val fireReact = tempDir.resolve("src/generated/resources/data/example/enchantment/fire_react.json").readText()
        val chillAura = tempDir.resolve("src/generated/resources/data/example/enchantment/chill_aura.json").readText()
        val destruction = tempDir.resolve("src/generated/resources/data/example/enchantment/destruction.json").readText()
        val blockAndChainTag = tempDir.resolve("src/generated/resources/data/example/tags/item/enchantable/block_and_chain.json").readText()

        assertTrue(result.changes.any { it.ruleId == "text-custom-enchantment-data" })
        assertTrue(result.changes.any { it.ruleId == "text-custom-enchantment-item-tag" })
        assertTrue(fireReact.contains(""""supported_items": "#minecraft:enchantable/armor""""))
        assertTrue(fireReact.contains(""""type": "minecraft:ignite""""))
        assertTrue(fireReact.contains(""""example:chill_aura""""))
        assertTrue(fireReact.contains(""""minecraft:thorns""""))
        assertTrue(chillAura.contains(""""type": "minecraft:apply_mob_effect""""))
        assertTrue(chillAura.contains(""""to_apply": "example:frosty""""))
        assertTrue(chillAura.contains(""""min_duration": 10.0"""))
        assertTrue(destruction.contains(""""supported_items": "#example:enchantable/block_and_chain""""))
        assertTrue(destruction.contains(""""slots": [
    "hand"
  ]"""))
        assertTrue(destruction.contains(""""type": "minecraft:add""""))
        assertTrue(destruction.contains(""""base": -1.5"""))
        assertTrue(blockAndChainTag.contains(""""example:block_and_chain""""))
    }

    @Test
    fun `custom enchantment resource keys infer mod id expression from source`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            final class ExampleMod {
                static final String MODID = "example";
            }

            public final class ModEnchantments {
                public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(
                        BuiltInRegistries.ENCHANTMENT,
                        ExampleMod.MODID
                );
                private static final EnchantmentCategory MAGIC = EnchantmentCategory.create(ExampleMod.MODID + "_magic", item -> true);
                public static final DeferredHolder<Enchantment, Enchantment> FLAME = ENCHANTMENTS.register("flame", FlameEnchantment::new);

                public static boolean hasFlame(Level level, ItemStack stack) {
                    return stack.getEnchantmentLevel(FLAME.get()) > 0;
                }

                public static boolean isFlame(Enchantment enchantment) {
                    return enchantment == FLAME.get();
                }

                private static class FlameEnchantment extends Enchantment {
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, \"flame\")"))
        assertTrue(transformed.contains("stack.getEnchantmentLevel(holder(level, FLAME))"))
        assertTrue(transformed.contains("public static boolean isFlame(net.minecraft.core.Holder<Enchantment> enchantment)"))
        assertTrue(transformed.contains("return enchantment.is(FLAME);"))
        assertFalse(transformed.contains("InstantWorldMirror.MODID"))
        assertFalse(transformed.contains("DeferredHolder<Enchantment, Enchantment> FLAME"))
    }

    @Test
    fun `legacy enchantment category runtime checks migrate to holder item support`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentCategory;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            public class TestMod {
                public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
                    return this.canApplyEnchantment(EnchantmentHelper.getEnchantments(stack).keySet().toArray(new Enchantment[0]));
                }

                public boolean supportsEnchantment(ItemStack stack, net.minecraft.core.Holder<Enchantment> enchantment) {
                    return this.canApplyEnchantment(enchantment);
                }

                private boolean canApplyEnchantment(Enchantment... enchantments) {
                    for (Enchantment enchantment : enchantments) {
                        if (enchantment.category == EnchantmentCategory.DIGGER || enchantment.canEnchant(Items.IRON_AXE.getDefaultInstance()))
                            return true;
                    }
                    return false;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("EnchantmentCategory"))
        assertFalse(transformed.contains("new Enchantment[0]"))
        assertFalse(transformed.contains(".category"))
        assertTrue(transformed.contains("this.canApplyEnchantment(EnchantmentHelper.getEnchantments(stack).keySet())"))
        assertTrue(transformed.contains("private boolean canApplyEnchantment(Iterable<net.minecraft.core.Holder<Enchantment>> enchantments)"))
        assertTrue(transformed.contains("Items.IRON_AXE.getDefaultInstance().supportsEnchantment(enchantment)"))
        assertTrue(transformed.contains("private boolean canApplyEnchantment(net.minecraft.core.Holder<Enchantment> enchantment)"))
    }

    @Test
    fun `jei mobtype and unqualified properties rules migrate common 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import mezz.jei.api.forge.ForgeTypes;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.MobType;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.BushBlock;

            public class TestMod extends BushBlock {
                public TestMod() {
                    super(Properties.copy(Blocks.BAMBOO_SAPLING));
                }

                public boolean isIllager(LivingEntity entity) {
                    return entity.getMobType() == MobType.ILLAGER;
                }

                public Object fluidType() {
                    return ForgeTypes.FLUID_STACK;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import mezz.jei.api.neoforge.NeoForgeTypes;"))
        assertTrue(transformed.contains("import net.minecraft.tags.EntityTypeTags;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.block.state.BlockBehaviour;"))
        assertTrue(transformed.contains("super(BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_SAPLING));"))
        assertTrue(transformed.contains("entity.getType().is(EntityTypeTags.ILLAGER)"))
        assertTrue(transformed.contains("NeoForgeTypes.FLUID_STACK"))
        assertTrue(!transformed.contains("mezz.jei.api.forge"))
        assertTrue(!transformed.contains("return ForgeTypes."))
        assertTrue(!transformed.contains("import net.minecraft.world.entity.MobType;"))
        assertTrue(!transformed.contains("getMobType() == MobType"))
        assertTrue(!transformed.contains("Properties.copy"))
    }

    @Test
    fun `removed 1_21 entity and block helper APIs migrate without placeholders`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobType;
            import net.minecraft.world.entity.SpawnPlacements;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.items.IItemHandler;
            import net.neoforged.neoforge.items.wrapper.EmptyHandler;

            public class TestMod {
                AbstractTreeGrower grower;

                @Override
                public MobType getMobType() {
                    return MobType.UNDEAD;
                }

                protected IItemHandler createUnSidedHandler() {
                    return new EmptyHandler();
                }

                public boolean isValidSpawn(BlockState state, BlockGetter level, BlockPos pos, SpawnPlacements.Type type, EntityType<?> entityType) {
                    return false;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.level.block.grower.TreeGrower;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;"))
        assertTrue(transformed.contains("TreeGrower grower;"))
        assertTrue(transformed.contains("return EmptyItemHandler.INSTANCE;"))
        assertTrue(transformed.contains("isValidSpawn(BlockState state, BlockGetter level, BlockPos pos, EntityType<?> entityType)"))
        assertFalse(transformed.contains("AbstractTreeGrower"))
        assertFalse(transformed.contains("EmptyHandler"))
        assertFalse(transformed.contains("public MobType getMobType()"))
        assertFalse(transformed.contains("SpawnPlacements.Type"))
        assertFalse(transformed.contains("TODO"))
        assertFalse(transformed.contains("[forge2neo]"))
    }

    @Test
    fun `custom registries shaped recipes and registry object parameters migrate to real 1_21 types`() {
        val projectDir = createTestFile("""
            package com.example;

            import java.util.function.Supplier;
            import net.minecraft.core.Registry;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.SpawnPlacements;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.level.block.ChestBlock;
            import net.minecraft.world.item.crafting.ShapedRecipe;
            import net.neoforged.neoforge.event.entity.SpawnPlacementRegisterEvent;
            import net.neoforged.neoforge.common.crafting.IShapedRecipe;
            import net.neoforged.neoforge.registries.IForgeRegistry;
            import net.neoforged.neoforge.registries.RegistryObject;

            class TestMod {
                Supplier<IForgeRegistry<Foo>> REGISTRY;

                void recipe(Object recipe) {
                    if (recipe instanceof IShapedRecipe<?> rec) {
                        int width = rec.getRecipeWidth();
                        int height = rec.getRecipeHeight();
                    }
                }

                void make(RegistryObject<? extends ChestBlock> block) {
                    block.get();
                }

                void addEntityAndEgg(RegistryObject<? extends EntityType<?>> entity) {
                    entity.get();
                }

                void makeCustom(RegistryObject<? extends Foo> foo) {
                    foo.get();
                }

                void registerSpawnPlacements(SpawnPlacementRegisterEvent event, EntityType<?> type) {
                    event.register(type, SpawnPlacements.Type.ON_GROUND, null, null, SpawnPlacementRegisterEvent.Operation.REPLACE);
                }
            }

            record UncraftingRecipe() implements CraftingRecipe, IShapedRecipe<CraftingInput> {
                @Override
                public int getRecipeWidth() {
                    return 3;
                }

                @Override
                public int getRecipeHeight() {
                    return 3;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("Supplier<Registry<Foo>> REGISTRY;"))
        assertTrue(transformed.contains("recipe instanceof ShapedRecipe rec"))
        assertTrue(transformed.contains("rec.getWidth()"))
        assertTrue(transformed.contains("rec.getHeight()"))
        assertTrue(transformed.contains("void make(Supplier<? extends ChestBlock> block)"))
        assertTrue(transformed.contains("void addEntityAndEgg(DeferredHolder<EntityType<?>, ? extends EntityType<?>> entity)"))
        assertTrue(transformed.contains("void makeCustom(DeferredHolder<Foo, ? extends Foo> foo)"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;"))
        assertTrue(transformed.contains("import net.minecraft.world.entity.SpawnPlacementTypes;"))
        assertTrue(transformed.contains("void registerSpawnPlacements(RegisterSpawnPlacementsEvent event, EntityType<?> type)"))
        assertTrue(transformed.contains("SpawnPlacementTypes.ON_GROUND"))
        assertTrue(transformed.contains("RegisterSpawnPlacementsEvent.Operation.REPLACE"))
        assertTrue(transformed.contains("record UncraftingRecipe() implements CraftingRecipe"))
        assertTrue(transformed.contains("public int getWidth()"))
        assertTrue(transformed.contains("public int getHeight()"))
        assertFalse(transformed.contains("IForgeRegistry"))
        assertFalse(transformed.contains("IShapedRecipe"))
        assertFalse(transformed.contains("RegistryObject"))
        assertFalse(Regex("""@Override\s*\r?\n\s*public int get(?:Width|Height)\(""").containsMatchIn(transformed))
        assertFalse(transformed.contains("TODO"))
        assertFalse(transformed.contains("[forge2neo]"))
    }

    @Test
    fun `bonemeal and pathfind signatures migrate when split across lines`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                public static final DeferredHolder<RotatedPillarBlock, RotatedPillarBlock> LOG = BLOCKS
                        .register("log", MapleLogBlock::new);

                public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                        PathComputationType type) {
                    return false;
                }

                public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state,
                        boolean isClient) {
                    return true;
                }

                public void call(BonemealableBlock growable, LevelReader level, BlockPos pos, BlockState top) {
                    growable.isValidBonemealTarget(level, pos.above(), top, false);
                }

                public void tick(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
                    if (CommonHooks.onCropsGrowPre(level, pos, state, random.nextInt(3) == 0)) {
                        level.setBlock(pos, state, 2);
                        CommonHooks.onCropsGrowPost(level, pos, state);
                    }
                    Object props = BlockBehaviour.Properties
                            .copy(Blocks.OAK_LOG);
                    Object stair = new net.minecraft.world.level.block.StairBlock(() -> LOG.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_STAIRS));
                    Object door = new net.minecraft.world.level.block.DoorBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_DOOR), net.minecraft.world.level.block.state.properties.BlockSetType.BAMBOO);
                    Object bush = new net.minecraft.world.level.block.BushBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.PLANT).noCollission().instabreak().sound(SoundType.GRASS).randomTicks());
                }

                public boolean canPlaceLiquid(BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
                    return false;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("DeferredHolder<Block, RotatedPillarBlock> LOG = BLOCKS"))
        assertTrue(transformed.contains(".register(\"log\", () -> new MapleLogBlock())"))
        assertTrue(transformed.contains("public boolean isPathfindable(BlockState state, PathComputationType type)"))
        assertTrue(transformed.contains("public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state)"))
        assertTrue(transformed.contains("growable.isValidBonemealTarget(level, pos.above(), top);"))
        assertTrue(transformed.contains("net.neoforged.neoforge.common.CommonHooks.canCropGrow(level, pos, state, random.nextInt(3) == 0)"))
        assertTrue(transformed.contains("net.neoforged.neoforge.common.CommonHooks.fireCropGrowPost(level, pos, state);"))
        assertTrue(transformed.contains("BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG)"))
        assertTrue(transformed.contains("new net.minecraft.world.level.block.StairBlock(LOG.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_STAIRS))"))
        assertTrue(transformed.contains("new net.minecraft.world.level.block.DoorBlock(net.minecraft.world.level.block.state.properties.BlockSetType.BAMBOO, BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_DOOR))"))
        assertTrue(transformed.contains("protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BushBlock> codec()"))
        assertTrue(transformed.contains("return com.mojang.serialization.MapCodec.unit(this);"))
        assertTrue(transformed.contains("public boolean canPlaceLiquid(net.minecraft.world.entity.player.Player player, BlockGetter world, BlockPos pos, BlockState state, Fluid fluid)"))
        assertTrue(!transformed.contains("boolean isClient"))
        assertTrue(!transformed.contains(", false)"))
        assertTrue(!transformed.contains("onCropsGrowPost removed"))
    }

    @Test
    fun `recipe holder network open and fuel APIs migrate to 1_21 surfaces`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.SimpleContainer;
            import net.minecraft.world.Container;
            import net.neoforged.neoforge.network.NetworkHooks;
            import net.neoforged.neoforge.items.ItemHandlerHelper;

            public class TestMod {
                private ResourceLocation lastRecipeID;

                public static final DeferredHolder<MenuType<StoneMortarContainer>, MenuType<StoneMortarContainer>> STONE_MORTAR =
                        CONTAINER_TYPES.register("stone_mortar", () -> IMenuTypeExtension.create(StoneMortarContainer::new));

                private static <C extends Container, T extends Recipe<C>> List<T> findRecipesByType(RecipeType<T> type) {
                    return MC.level.getRecipeManager().getAllRecipesFor(type);
                }

                public Optional<SmeltingRecipe> vanilla(ItemStack stack, Level level) {
                    SimpleContainer container = new SimpleContainer(stack.copy());
                    Optional<SmeltingRecipe> recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, container, level);
                    return recipe;
                }

                public List<CookingPotRecipe> customList(Level level, RecipeWrapper wrapper) {
                    List<CookingPotRecipe> recipes = level.getRecipeManager()
                            .getRecipesFor(RecipeTypeRegistry.COOKING_RECIPE_TYPE.get(), wrapper, level);
                    return recipes;
                }

                public void cached(Level level) {
                    Optional<? extends Recipe<RecipeWrapper>> recipeOpt = level.getRecipeManager()
                            .getAllRecipesFor(RecipeTypeRegistry.COOKING_RECIPE_TYPE.get()).stream()
                            .filter(now -> now.getId().equals(lastRecipeID)).findFirst();
                    if (recipeOpt.isPresent()) {
                        Recipe<RecipeWrapper> recipe = recipeOpt.get();
                    }
                }

                public void experience(Level world, Object2IntMap.Entry<ResourceLocation> entry) {
                    world.getRecipeManager().byKey(entry.getKey()).ifPresent(recipe -> use(((CookingPotRecipe) recipe).getExperience()));
                }

                public void open(ServerPlayer player, MenuProvider menu, BlockPos pos) {
                    NetworkHooks.openScreen(player, menu, pos);
                }

                public int burn(ItemStack stack) {
                    return CommonHooks.getBurnTime(stack, null);
                }

                public IFluidHandlerItem handler(ItemStack stack) {
                    return FluidUtil.getFluidHandler(ItemHandlerHelper.copyStackWithSize(stack, 1)).orElse(null);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.item.crafting.SingleRecipeInput;"))
        assertTrue(transformed.contains("import net.minecraft.world.item.crafting.RecipeHolder;"))
        assertFalse(transformed.contains("import net.neoforged.neoforge.network.NetworkHooks;"))
        assertTrue(transformed.contains("DeferredHolder<MenuType<?>, MenuType<StoneMortarContainer>> STONE_MORTAR"))
        assertTrue(transformed.contains("private static <C extends net.minecraft.world.item.crafting.RecipeInput, T extends Recipe<C>> List<T> findRecipesByType"))
        assertTrue(transformed.contains("SingleRecipeInput container = new SingleRecipeInput(stack.copy());"))
        assertTrue(transformed.contains(".getRecipeFor(RecipeType.SMELTING, container, level).map(RecipeHolder::value);"))
        assertTrue(transformed.contains(".getRecipesFor(RecipeTypeRegistry.COOKING_RECIPE_TYPE.get(), wrapper, level).stream().map(RecipeHolder::value).toList();"))
        assertTrue(transformed.contains("var recipeOpt = level.getRecipeManager()"))
        assertTrue(transformed.contains(".filter(now -> now.id().equals(lastRecipeID)).findFirst();"))
        assertTrue(transformed.contains("Recipe<RecipeWrapper> recipe = recipeOpt.get().value();"))
        assertTrue(transformed.contains("((CookingPotRecipe) recipe.value()).getExperience()"))
        assertTrue(transformed.contains("(player).openMenu(menu, buf -> buf.writeBlockPos(pos));"))
        assertTrue(transformed.contains("return stack.getBurnTime(null);"))
        assertTrue(transformed.contains("FluidUtil.getFluidHandler(stack.copyWithCount(1)).orElse(null);"))
    }

    @Test
    fun `inventory recipe holder interface migrates to recipe crafting holder`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.inventory.RecipeHolder;

            public class MenuBackedBlockEntity implements RecipeHolder {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.inventory.RecipeCraftingHolder;"))
        assertFalse(transformed.contains("import net.minecraft.world.item.crafting.RecipeHolder;"))
        assertTrue(transformed.contains("implements RecipeCraftingHolder"))
    }

    @Test
    fun `block path type rename does not rewrite project class names`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.level.pathfinder.BlockPathTypes;

            public class AetherBlockPathTypes {
                public static final BlockPathTypes BOSS_DOORWAY = BlockPathTypes.create("BOSS_DOORWAY", -1.0F);
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public class AetherBlockPathTypes"))
        assertTrue(transformed.contains("import net.minecraft.world.level.pathfinder.PathType;"))
        assertTrue(transformed.contains("public static final PathType BOSS_DOORWAY = PathType.create"))
        assertFalse(transformed.contains("public class AetherPathType"))
    }

    @Test
    fun `glass block rename does not rewrite project class names`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.level.block.GlassBlock;

            public class QuicksoilGlassBlock extends GlassBlock {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public class QuicksoilGlassBlock extends TransparentBlock"))
        assertTrue(transformed.contains("import net.minecraft.world.level.block.TransparentBlock;"))
        assertFalse(transformed.contains("public class QuicksoilTransparentBlock"))
        assertFalse(transformed.contains("import net.minecraft.world.level.block.GlassBlock;"))
    }

    @Test
    fun `removed item marker interfaces are deleted from multi-interface implements clauses`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.Vanishable;

            class AccessoryItem extends Item implements ICurioItem, Vanishable {
            }

            class LeatherGlovesItem extends GlovesItem implements DyeableLeatherItem {
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("class AccessoryItem extends Item implements ICurioItem"))
        assertTrue(transformed.contains("class LeatherGlovesItem extends GlovesItem {"))
        assertFalse(transformed.contains("Vanishable"))
        assertFalse(transformed.contains("DyeableLeatherItem"))
    }

    @Test
    fun `simple container import is preserved when the class extends simple container`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.SimpleContainer;

            public class LoreInventory extends SimpleContainer {
                public LoreInventory() {
                    super(1);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.SimpleContainer;"))
        assertTrue(transformed.contains("extends SimpleContainer"))
        assertFalse(transformed.contains("import net.minecraft.world.item.crafting.SingleRecipeInput;"))
    }

    @Test
    fun `generic dirt message screen renames to generic message screen`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.client.gui.screens.GenericDirtMessageScreen;

            public class ScreenUse {
                void open(Component title) {
                    new GenericDirtMessageScreen(title);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.client.gui.screens.GenericMessageScreen;"))
        assertTrue(transformed.contains("new GenericMessageScreen(title);"))
        assertFalse(transformed.contains("GenericDirtMessageScreen"))
    }

    @Test
    fun `command function cacheable function migrates to split cacheable type`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.commands.CommandFunction;

            public class FunctionUse {
                CacheableTarget target(CommandFunction.CacheableFunction function) {
                    return new CacheableTarget(function, CommandFunction.CacheableFunction.NONE);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.commands.CacheableFunction;"))
        assertTrue(transformed.contains("import net.minecraft.commands.functions.CommandFunction;"))
        assertTrue(transformed.contains("CacheableTarget target(CacheableFunction function)"))
        assertTrue(transformed.contains("new CacheableTarget(function, java.util.Optional.empty())"))
        assertFalse(transformed.contains("CommandFunction.CacheableFunction"))
        assertFalse(transformed.contains("import net.minecraft.commands.CommandFunction;"))
    }

    @Test
    fun `removed tag manager item access migrates to registry holders`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.tags.ITagManager;

            public class TagAccess {
                public static void add(TagKey<Item> itemTag, int burnTime) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    if (tags != null) {
                        tags.getTag(itemTag).stream().forEach((item) -> getMap().put(item, burnTime));
                    }
                }

                public static boolean empty(TagKey<Item> itemTag) {
                    boolean flag = true;
                    ITagManager<Item> itemTags = BuiltInRegistries.ITEM.tags();
                    if (itemTags != null) {
                        if (itemTags.getTag(itemTag).isEmpty()) {
                            flag = false;
                        }
                    }
                    return flag;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("ITagManager"))
        assertFalse(transformed.contains("net.neoforged.neoforge.registries.tags"))
        assertTrue(transformed.contains("BuiltInRegistries.ITEM.getTagOrEmpty(itemTag).forEach((holder) -> { Item item = holder.value(); getMap().put(item, burnTime); });"), transformed)
        assertTrue(transformed.contains("if (!BuiltInRegistries.ITEM.getTagOrEmpty(itemTag).iterator().hasNext()) {"), transformed)
    }

    @Test
    fun `network hooks open screen handles nested simple menu provider lambda`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.network.NetworkHooks;

            public class TestMod {
                public void open(ServerPlayer serverPlayer) {
                    NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider((id, inventory, playerEntity) -> new LoreBookMenu(id, inventory), Component.translatable("menu.example.book")));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(transformed.contains("(serverPlayer).openMenu(new SimpleMenuProvider((id, inventory, playerEntity) -> new LoreBookMenu(id, inventory), Component.translatable(\"menu.example.book\")));"), transformed)
        assertFalse(transformed.contains("buf.writeBlockPos(inventory, playerEntity)"), transformed)
        assertFalse(transformed.contains("NetworkHooks.openScreen"), transformed)
        assertFalse(transformed.contains("import net.minecraftforge.network.NetworkHooks;"), transformed)
    }

    @Test
    fun `network hooks open screen rejects untyped third argument instead of guessing`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraftforge.network.NetworkHooks;

            public class TestMod {
                public void open(ServerPlayer player, MenuProvider menu, Object payload) {
                    NetworkHooks.openScreen(player, menu, payload);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.errors.any { it.contains("Cannot safely migrate NetworkHooks.openScreen") }, result.errors.joinToString("\n"))
        assertTrue(transformed.contains("NetworkHooks.openScreen(player, menu, payload);"), transformed)
        assertTrue(transformed.contains("import net.neoforged.neoforge.network.NetworkHooks;") ||
            transformed.contains("import net.minecraftforge.network.NetworkHooks;"), transformed)
    }

    @Test
    fun `sakura gui tags bucket event and vertex APIs migrate to 1_21 surfaces`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod {
                public void screen(GuiGraphics ms, int mouseX, int mouseY, float partialTicks, BucketItem bucketItem) {
                    this.renderBackground(ms);
                    Fluid fluid = bucketItem.getFluid();
                }

                public void event(BlockToolModificationEvent event) {
                    ItemAbility ability = event.getToolAction();
                }

                public void tags() {
                    Object itemTags = List.of(Tags.Items.COBBLESTONE, Tags.Items.GRAVEL, Tags.Items.GLASS, Tags.Items.STRING, Tags.Items.STONE, Tags.Items.SAND);
                    Object blockTags = List.of(Tags.Blocks.COBBLESTONE, Tags.Blocks.GRAVEL, Tags.Blocks.GLASS, Tags.Blocks.STONE, Tags.Blocks.SAND);
                }

                public void render(VertexConsumer builder, Matrix4f matrix, Matrix3f normal, float x, float y, float z, float u, float v, int packedLight, float nx, float ny, float nz) {
                    builder.vertex(matrix, x, y, z).setUv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).setNormal(normal, nx, ny, nz).endVertex();
                    builder.vertex(matrix, x, y, z).overlayCoords(0, 10).uv2(packedLight).endVertex();
                    mesh.vertex(x, y, z);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("this.renderBackground(ms, mouseX, mouseY, partialTicks);"))
        assertTrue(transformed.contains("Fluid fluid = bucketItem.content;"))
        assertTrue(transformed.contains("ItemAbility ability = event.getItemAbility();"))
        assertTrue(transformed.contains("Tags.Items.COBBLESTONES"))
        assertTrue(transformed.contains("Tags.Items.GRAVELS"))
        assertTrue(transformed.contains("Tags.Items.GLASS_BLOCKS"))
        assertTrue(transformed.contains("Tags.Items.STRINGS"))
        assertTrue(transformed.contains("Tags.Items.STONES"))
        assertTrue(transformed.contains("Tags.Items.SANDS"))
        assertTrue(transformed.contains("Tags.Blocks.COBBLESTONES"))
        assertTrue(transformed.contains("Tags.Blocks.GRAVELS"))
        assertTrue(transformed.contains("Tags.Blocks.GLASS_BLOCKS"))
        assertTrue(transformed.contains("Tags.Blocks.STONES"))
        assertTrue(transformed.contains("Tags.Blocks.SANDS"))
        assertTrue(transformed.contains("builder.addVertex(matrix, x, y, z).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight).setNormal(nx, ny, nz);"))
        assertTrue(transformed.contains("builder.addVertex(matrix, x, y, z).setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight);"))
        assertTrue(transformed.contains("mesh.vertex(x, y, z);"))
        assertFalse(transformed.contains("bucketItem.getFluid()"))
        assertFalse(transformed.contains("event.getToolAction()"))
        assertFalse(transformed.contains("builder.vertex("))
        assertFalse(transformed.contains(".overlayCoords("))
        assertFalse(transformed.contains(".uv2("))
        assertFalse(transformed.contains(".endVertex()"))
    }

    @Test
    fun `sakura effect particle fluid and nested event migrations compile against 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.core.particles.SimpleParticleType;
            import net.minecraft.world.level.material.FlowingFluid;
            import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class TestMod {
                public static final DeferredHolder<SimpleParticleType, SimpleParticleType> LEAF = PARTICLES.register("leaf", () -> new SimpleParticleType(false));
                public static final DeferredHolder<FlowingFluid, FlowingFluid> SAKE = FLUIDS.register("sake", () -> new BaseFlowingFluid.Source(PROPS));

                public void onLivingHurt(LivingDamageEvent.Post event, Player player, MobEffectInstance instance) {
                    if (player.hasEffect(EffectRegistry.FIRE_BLADE.get())) {
                        int amplifier = player.getEffect(EffectRegistry.EXP_UP.get()).getAmplifier();
                    }
                    boolean bad = instance.getEffect().isBeneficial();
                    Object effect = new MobEffectInstance(EffectRegistry.GOLDEN_HEART.get(), 20, 0);
                    Object bucket = new BucketItem(FluidRegistry.SAKE_STILL, props);
                    Object block = new LiquidBlock(FluidRegistry.SAKE_FLOWING, props);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.particles.ParticleType;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.material.Fluid;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;"))
        assertFalse(transformed.contains("import net.neoforged.neoforge.event.entity.living.Post;"))
        assertTrue(transformed.contains("DeferredHolder<ParticleType<?>, SimpleParticleType> LEAF"))
        assertTrue(transformed.contains("DeferredHolder<Fluid, FlowingFluid> SAKE"))
        assertTrue(transformed.contains("public void onLivingHurt(Post event"))
        assertTrue(transformed.contains("player.hasEffect(EffectRegistry.FIRE_BLADE)"))
        assertTrue(transformed.contains("player.getEffect(EffectRegistry.EXP_UP)"))
        assertTrue(transformed.contains("instance.getEffect().value().isBeneficial()"))
        assertTrue(transformed.contains("new MobEffectInstance(EffectRegistry.GOLDEN_HEART, 20, 0)"))
        assertTrue(transformed.contains("new BucketItem(FluidRegistry.SAKE_STILL.get(), props)"))
        assertTrue(transformed.contains("new LiquidBlock(FluidRegistry.SAKE_FLOWING.get(), props)"))
        assertFalse(transformed.contains("DeferredHolder<SimpleParticleType, SimpleParticleType>"))
        assertFalse(transformed.contains("EffectRegistry.FIRE_BLADE.get()"))
        assertFalse(transformed.contains("getEffect().isBeneficial()"))
    }

    @Test
    fun `block override signature rules migrate common 1_21 methods`() {
        val projectDir = createTestFile("""
            package com.example;

            import javax.annotation.Nullable;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;

            public class TestMod {
                @Override
                @SuppressWarnings("deprecation")
                @Override
                protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
                    return InteractionResult.PASS;
                }

                @Override
                public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable Entity player) {
                    return true;
                }

                @Override
                public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
                    super.playerWillDestroy(level, pos, state, player);
                }

                @Override
                public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("@Override\n                @SuppressWarnings"))
        assertTrue(transformed.contains("isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable LivingEntity player)"))
        assertTrue(transformed.contains("import net.minecraft.world.entity.LivingEntity;"))
        assertTrue(transformed.contains("public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player)"))
        assertTrue(transformed.contains("return super.playerWillDestroy(level, pos, state, player);"))
        assertTrue(transformed.contains("getCloneItemStack(LevelReader level, BlockPos pos, BlockState state)"))
        assertTrue(transformed.contains("import net.minecraft.world.level.LevelReader;"))
    }

    @Test
    fun `effect tick signature migration adds boolean return`() {
        val projectDir = createTestFile("""
            package com.example;

            public class TestMod extends MobEffect {
                @Override
                public void applyEffectTick(LivingEntity entity, int amplifier) {
                    entity.getActiveEffects().stream()
                        .filter(e -> !e.getEffect().isBeneficial())
                        .map(MobEffectInstance::getEffect)
                        .toList()
                        .forEach(entity::removeEffect);
                }

                @Override
                public boolean isDurationEffectTick(int duration, int amplifier) {
                    return (duration & 1) == 0;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public boolean applyEffectTick(LivingEntity entity, int amplifier)"))
        assertTrue(transformed.contains("return true;"))
        assertTrue(transformed.contains("shouldApplyEffectTickThisTick(int duration, int amplifier)"))
        assertFalse(transformed.contains("public void applyEffectTick"))
        assertFalse(transformed.contains("isDurationEffectTick("))
    }

    @Test
    fun `hotbath holder tooltip and mapcodec migrations compile against 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.serialization.MapCodec;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectCategory;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;

            public class TestMod extends Item {
                public static final Codec<BaseFields> BASE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    EffectEntry.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(BaseFields::effects)
                ).apply(instance, BaseFields::new));

                public void appendHoverText(@NotNull ItemStack stack, @org.jetbrains.annotations.Nullable net.minecraft.world.level.Level level, @NotNull List<Component> tooltipComponents, @NotNull TooltipFlag tooltipFlag) {
                    super.appendHoverText(stack, level, tooltipComponents, tooltipFlag);
                }

                public int getUseDuration(@NotNull ItemStack stack) {
                    return 32;
                }

                public MobEffectInstance toMobEffectInstance(ResourceLocation effect) {
                    MobEffect mobEffect = BuiltInRegistries.MOB_EFFECT.get(effect);
                    if (mobEffect == null) {
                        return null;
                    }
                    return new MobEffectInstance(mobEffect, 20, 0, false, true, true);
                }

                private static boolean isHarmfulEffect(MobEffect effect) {
                    return effect.getCategory() == MobEffectCategory.HARMFUL && effect != MobEffects.BAD_OMEN;
                }

                void remove(MobEffectInstance effectInstance, ServerPlayer player) {
                    MobEffect effectHolder = effectInstance.getEffect();
                    if (isHarmfulEffect(effectHolder) && effectHolder != MobEffects.UNLUCK) {
                        player.removeEffect(effectHolder);
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.Holder;"))
        assertTrue(transformed.contains("EffectEntry.CODEC.codec().listOf()"))
        assertTrue(transformed.contains("appendHoverText(@NotNull ItemStack stack, Item.TooltipContext level,"))
        assertTrue(transformed.contains("super.appendHoverText(stack, level, tooltipComponents, tooltipFlag);"))
        assertTrue(transformed.contains("public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity entity)"))
        assertTrue(transformed.contains("Holder<MobEffect> mobEffect = BuiltInRegistries.MOB_EFFECT.getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.MOB_EFFECT, effect)).orElse(null);"))
        assertTrue(transformed.contains("return new MobEffectInstance(mobEffect, 20, 0, false, true, true);"))
        assertTrue(transformed.contains("private static boolean isHarmfulEffect(Holder<MobEffect> effect)"))
        assertTrue(transformed.contains("return effect.value().getCategory() == MobEffectCategory.HARMFUL"))
        assertTrue(transformed.contains("Holder<MobEffect> effectHolder = effectInstance.getEffect();"))
        assertTrue(transformed.contains("player.removeEffect(effectHolder);"))
    }

    @Test
    fun `requirements strategy and plant imports migrate without placeholders`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.advancements.RequirementsStrategy;
            import net.neoforged.neoforge.common.IPlantable;
            import net.neoforged.neoforge.common.PlantType;

            public class TestMod implements RequirementsStrategy {
                IPlantable plantable;
                PlantType plantType;
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.advancements.AdvancementRequirements;"))
        assertTrue(transformed.contains("implements AdvancementRequirements.Strategy"))
        assertFalse(transformed.contains("import net.neoforged.neoforge.common.IPlantable;"))
        assertFalse(transformed.contains("import net.neoforged.neoforge.common.PlantType;"))
        assertFalse(transformed.contains("PlantType removed"))
        assertFalse(transformed.contains("TODO"))
    }

    @Test
    fun `tool item constructors and durability callbacks migrate to 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.world.item.DiggerItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ShovelItem;
            import net.minecraft.world.item.SwordItem;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.SimpleTier;

            public class TestMod extends SwordItem {
                public TestMod(Tier tier, int attackDamageModifier, float attackSpeedModifier, Properties properties) {
                    super(tier, attackDamageModifier, attackSpeedModifier, properties);
                }

                void combat(ItemStack stack, Player player, LivingEntity entityLiving, int timeLeft, Player targetPlayer) {
                    int ticksUsed = getUseDuration(stack) - timeLeft;
                    float sweepRatio = EnchantmentHelper.getSweepingDamageRatio(player);
                    float enchantBonus = EnchantmentHelper.getDamageBonus(stack, entityLiving.getMobType()) / 1.2F;
                    targetPlayer.disableShield(false);
                    stack.hurtAndBreak(2, player, (p) -> p.onEquippedItemBroken(EquipmentSlot.MAINHAND));
                    ItemStack blade = ItemStack.of(tag.getCompound(TAG_BLADE));
                }

                ItemStack remainder(ItemStack stack) {
                    ItemStack copy = stack.copy();
                    if (copy.hurt(1, net.minecraft.util.RandomSource.create(), null)) {
                        return ItemStack.EMPTY;
                    }
                    return copy;
                }
            }

            class BroomItem extends ShovelItem {
                public BroomItem(Tier tier, float attackDamage, float attackSpeed, Properties properties) {
                    super(tier, attackDamage, attackSpeed, properties);
                }

                void damage(UseOnContext context) {
                    context.getItemInHand().hurtAndBreak(1, context.getPlayer(),
                        p -> p.onEquippedItemBroken(context.getHand()));
                }
            }

            class HammerItem extends DiggerItem {
                public HammerItem(Tier tier, float attackDamage, float attackSpeed, Properties properties) {
                    super(attackDamage, attackSpeed, tier, SakuraBlockTags.MINEABLE_WITH_HAMMER, properties);
                }
            }

            class Tiers {
                public static final Tier TACHI = new SimpleTier(3, 457, 7.0F, 3.0F, 18,
                        BlockTags.NEEDS_DIAMOND_TOOL, () -> Ingredient.EMPTY);
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("super(tier, properties.attributes(createAttributes(tier, attackDamageModifier, attackSpeedModifier)));"))
        assertTrue(transformed.contains("super(tier, properties.attributes(createAttributes(tier, attackDamage, attackSpeed)));"))
        assertTrue(transformed.contains("super(tier, SakuraBlockTags.MINEABLE_WITH_HAMMER, properties.attributes(DiggerItem.createAttributes(tier, attackDamage, attackSpeed)));"))
        assertTrue(transformed.contains("new SimpleTier(BlockTags.NEEDS_DIAMOND_TOOL, 457, 7.0F, 3.0F, 18, () -> Ingredient.EMPTY)"))
        assertTrue(transformed.contains("getUseDuration(stack, entityLiving) - timeLeft"))
        assertTrue(transformed.contains("float sweepRatio = EnchantmentHelper.getSweepingDamageRatio(player);"))
        assertFalse(transformed.contains("float sweepRatio = 0.5F;"))
        assertTrue(transformed.contains("float enchantBonus = EnchantmentHelper.getDamageBonus(stack, entityLiving.getMobType()) / 1.2F;"))
        assertTrue(transformed.contains("targetPlayer.disableShield();"))
        assertTrue(transformed.contains("stack.hurtAndBreak(2, player, EquipmentSlot.MAINHAND);"))
        assertTrue(transformed.contains("context.getItemInHand().hurtAndBreak(1, context.getPlayer(), EquipmentSlot.MAINHAND);"))
        assertTrue(transformed.contains("ItemStack.parseOptional(player.registryAccess(), tag.getCompound(TAG_BLADE))"))
        assertTrue(transformed.contains("copy.setDamageValue(copy.getDamageValue() + 1);"))
        assertTrue(transformed.contains("if (copy.getDamageValue() >= copy.getMaxDamage())"))
        assertFalse(transformed.contains("copy.hurt(1, net.minecraft.util.RandomSource.create(), null)"))
    }

    @Test
    fun `custom particle options migrate to codec and stream codec APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.brigadier.StringReader;
            import com.mojang.brigadier.exceptions.CommandSyntaxException;
            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.core.particles.ParticleOptions;
            import net.minecraft.core.particles.ParticleType;
            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.registries.DeferredHolder;

            import javax.annotation.Nonnull;

            public class LeafParticleData implements ParticleOptions {
                public final int r;
                public final int g;
                public final int b;

                public LeafParticleData(int r, int g, int b) {
                    this.r = r;
                    this.g = g;
                    this.b = b;
                }

                @Nonnull
                @Override
                public ParticleType<?> getType() {
                    return TFParticleType.FALLEN_LEAF.get();
                }

                public static Codec<LeafParticleData> codecLeaf() {
                    return RecordCodecBuilder.create((instance) -> instance.group(
                            Codec.INT.fieldOf("r").forGetter((obj) -> obj.r),
                            Codec.INT.fieldOf("g").forGetter((obj) -> obj.g),
                            Codec.INT.fieldOf("b").forGetter((obj) -> obj.b))
                            .apply(instance, LeafParticleData::new));
                }

                @Override
                public void writeToNetwork(@Nonnull FriendlyByteBuf buf) {
                    buf.writeVarInt(r);
                    buf.writeVarInt(g);
                    buf.writeVarInt(b);
                }

                @Nonnull
                @Override
                public String writeToString() {
                    return String.format("%d %d %d", r, g, b);
                }

                public static class Deserializer implements ParticleOptions.Deserializer<LeafParticleData> {
                    @Nonnull
                    @Override
                    public LeafParticleData fromCommand(@Nonnull ParticleType<LeafParticleData> type, @Nonnull StringReader reader) throws CommandSyntaxException {
                        return new LeafParticleData(reader.readInt(), reader.readInt(), reader.readInt());
                    }

                    @Nonnull
                    @Override
                    public LeafParticleData fromNetwork(@Nonnull ParticleType<LeafParticleData> type, FriendlyByteBuf buf) {
                        return new LeafParticleData(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
                    }
                }
            }

            class TFParticleType {
                public static final DeferredHolder<ParticleType<LeafParticleData>, ParticleType<LeafParticleData>> FALLEN_LEAF =
                        PARTICLES.register("fallen_leaf", () -> new ParticleType<>(false, new LeafParticleData.Deserializer()) {
                            @Override
                            public Codec<LeafParticleData> codec() {
                                return LeafParticleData.codecLeaf();
                            }
                        });
            }

            class ParticlePacket {
                public ParticlePacket(FriendlyByteBuf buf) {
                    ParticleType<?> type = null;
                    readParticle(type, buf);
                }

                private <T extends ParticleOptions> T readParticle(ParticleType<T> particleType, FriendlyByteBuf buf) {
                    return particleType.getDeserializer().fromNetwork(particleType, buf);
                }

                public void encode(FriendlyByteBuf buf) {
                    QueuedParticle queuedParticle = null;
                    queuedParticle.particleOptions.writeToNetwork(buf);
                }

                private record QueuedParticle(ParticleOptions particleOptions) {
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(transformed.contains("import net.minecraft.network.RegistryFriendlyByteBuf;"))
        assertTrue(transformed.contains("import net.minecraft.network.codec.ByteBufCodecs;"))
        assertTrue(transformed.contains("import net.minecraft.network.codec.StreamCodec;"))
        assertTrue(transformed.contains("public record LeafParticleData(int r, int g, int b) implements ParticleOptions"))
        assertTrue(transformed.contains("public static MapCodec<LeafParticleData> CODEC = RecordCodecBuilder.mapCodec"))
        assertTrue(transformed.contains("public static StreamCodec<? super RegistryFriendlyByteBuf, LeafParticleData> STREAM_CODEC = StreamCodec.composite"))
        assertTrue(transformed.contains("ByteBufCodecs.VAR_INT, p -> p.r"))
        assertTrue(transformed.contains("DeferredHolder<ParticleType<?>, ParticleType<LeafParticleData>> FALLEN_LEAF"))
        assertTrue(transformed.contains("new ParticleType<>(false)"))
        assertTrue(transformed.contains("public MapCodec<LeafParticleData> codec()"))
        assertTrue(transformed.contains("return LeafParticleData.CODEC;"))
        assertTrue(transformed.contains("public StreamCodec<? super RegistryFriendlyByteBuf, LeafParticleData> streamCodec()"))
        assertTrue(transformed.contains("return LeafParticleData.STREAM_CODEC;"))
        assertTrue(transformed.contains("ParticlePacket(RegistryFriendlyByteBuf buf)"))
        assertTrue(transformed.contains("return particleType.streamCodec().decode(buf);"))
        assertTrue(transformed.contains("private <T extends ParticleOptions> void writeParticle(T particleOptions, RegistryFriendlyByteBuf buf)"))
        assertTrue(transformed.contains("particleType.streamCodec().encode(buf, particleOptions);"))
        assertTrue(transformed.contains("writeParticle(queuedParticle.particleOptions, buf);"))
        assertFalse(transformed.contains("ParticleOptions.Deserializer"))
        assertFalse(transformed.contains("writeToNetwork"))
        assertFalse(transformed.contains("writeToString"))
        assertFalse(transformed.contains("getDeserializer()"))
        assertFalse(transformed.contains("new LeafParticleData.Deserializer()"))
        assertFalse(transformed.contains("codecLeaf()"))
        assertFalse(transformed.contains("import net.minecraft.network.FriendlyByteBuf;"))
    }

    @Test
    fun `loot datagen builders and global loot modifier generics migrate to 1_21 APIs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import net.minecraft.world.level.storage.loot.entries.LootTableReference;
            import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;
            import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithLootingCondition;
            import net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider;
            import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestMod {
                public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS = null;
                public static final DeferredHolder<Codec<FieryToolSmeltingModifier>, Codec<FieryToolSmeltingModifier>> FIERY_PICK_SMELTING =
                        LOOT_MODIFIERS.register("fiery_pick_smelting", () -> FieryToolSmeltingModifier.CODEC);

                void loot() {
                    pool.add(LootTableReference.lootTableReference(TFLootTables.USELESS_LOOT).setWeight(25));
                    entry.when(LootItemRandomChanceWithLootingCondition.randomChanceAndLootingBoost(0.5F, 0.1F));
                    table.apply(CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY).copy("SkullOwner", "SkullOwner"));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.entries.NestedLootTable;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithEnchantedBonusCondition;"))
        assertTrue(transformed.contains("import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;"))
        assertTrue(transformed.contains("import net.minecraft.core.component.DataComponents;"))
        assertTrue(transformed.contains("DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<FieryToolSmeltingModifier>> FIERY_PICK_SMELTING"))
        assertTrue(transformed.contains("NestedLootTable.lootTableReference(TFLootTables.USELESS_LOOT)"))
        assertTrue(transformed.contains("LootItemRandomChanceWithEnchantedBonusCondition.randomChanceAndLootingBoost(this.registries, 0.5F, 0.1F)"))
        assertTrue(transformed.contains("CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY).include(DataComponents.PROFILE).include(DataComponents.NOTE_BLOCK_SOUND).include(DataComponents.CUSTOM_NAME)"))
        assertFalse(transformed.contains("LootTableReference"))
        assertFalse(transformed.contains("LootItemRandomChanceWithLootingCondition"))
        assertFalse(transformed.contains("CopyNbtFunction"))
        assertFalse(transformed.contains("ContextNbtProvider"))
        assertFalse(transformed.contains("SkullOwner"))
        assertFalse(transformed.contains("ChunkGenerator"))
        assertFalse(transformed.contains("MapMapCodec"))
    }

    @Test
    fun `legacy loot serializers migrate to MapCodec backed loot types`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ModExistsCondition.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class ModExistsCondition implements LootItemCondition {
                private final String modID;

                public ModExistsCondition(String modID) {
                    this.modID = modID;
                }

                public LootItemConditionType getType() {
                    return TestLoot.MOD_EXISTS.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class ConditionSerializer implements Serializer<ModExistsCondition> {
                    public void serialize(JsonObject json, ModExistsCondition value, JsonSerializationContext context) {
                        json.addProperty("mod_id", value.modID);
                    }

                    public ModExistsCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new ModExistsCondition(GsonHelper.getAsString(json, "mod_id"));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityTargetCondition.java").writeText("""
            package com.example;

            import com.google.common.collect.ImmutableSet;
            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
            import java.util.Set;

            public class EntityTargetCondition implements LootItemCondition {
                final LootContext.EntityTarget entityTarget;

                private EntityTargetCondition(LootContext.EntityTarget entityTarget) {
                    this.entityTarget = entityTarget;
                }

                public Set<LootContextParam<?>> getReferencedContextParams() {
                    return ImmutableSet.of(this.entityTarget.getParam());
                }

                public LootItemConditionType getType() {
                    return TestLoot.ENTITY_TARGET.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class ConditionSerializer implements Serializer<EntityTargetCondition> {
                    public void serialize(JsonObject json, EntityTargetCondition condition, JsonSerializationContext context) {
                        json.add("entity", context.serialize(condition.entityTarget));
                    }

                    public EntityTargetCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new EntityTargetCondition(GsonHelper.getAsObject(json, "entity", context, LootContext.EntityTarget.class));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("IsMinionCondition.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public record IsMinionCondition(boolean inverse) implements LootItemCondition {
                public LootItemConditionType getType() {
                    return TestLoot.IS_MINION.get();
                }

                public boolean test(LootContext context) {
                    return !inverse;
                }

                public static class ConditionSerializer implements Serializer<IsMinionCondition> {
                    public void serialize(JsonObject json, IsMinionCondition value, JsonSerializationContext context) {
                        json.addProperty("inverse", value.inverse);
                    }

                    public IsMinionCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new IsMinionCondition(GsonHelper.getAsBoolean(json, "inverse", false));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("UncraftingTableEnabledCondition.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.Serializer;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class UncraftingTableEnabledCondition implements LootItemCondition {
                private UncraftingTableEnabledCondition() {
                }

                public LootItemConditionType getType() {
                    return TestLoot.UNCRAFTING_TABLE_ENABLED.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class ConditionSerializer implements Serializer<UncraftingTableEnabledCondition> {
                    public void serialize(JsonObject json, UncraftingTableEnabledCondition value, JsonSerializationContext context) {
                    }

                    public UncraftingTableEnabledCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new UncraftingTableEnabledCondition();
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ConfigEnabled.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

            public class ConfigEnabled implements LootItemCondition {
                private final ConfigValue config;

                public ConfigEnabled(ConfigValue config) {
                    this.config = config;
                }

                public LootItemConditionType getType() {
                    return TestLoot.CONFIG_ENABLED.get();
                }

                public boolean test(LootContext context) {
                    return true;
                }

                public static class Serializer implements net.minecraft.world.level.storage.loot.Serializer<ConfigEnabled> {
                    public void serialize(JsonObject json, ConfigEnabled instance, JsonSerializationContext context) {
                        json.addProperty("config", ConfigSerializationUtil.serialize(instance.config));
                    }

                    public ConfigEnabled deserialize(JsonObject json, JsonDeserializationContext context) {
                        return new ConfigEnabled(ConfigSerializationUtil.deserialize(GsonHelper.getAsString(json, "config")));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ModItemSwap.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import com.google.gson.JsonSyntaxException;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class ModItemSwap extends LootItemConditionalFunction {
                private final Item item;
                private final Item oldItem;
                private final boolean success;

                protected ModItemSwap(LootItemCondition[] conditions, Item item, Item old, boolean success) {
                    super(conditions);
                    this.item = item;
                    this.oldItem = old;
                    this.success = success;
                }

                public LootItemFunctionType getType() {
                    return TestLoot.ITEM_OR_DEFAULT.get();
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return new ItemStack(this.item, stack.getCount());
                }

                public static class Builder extends LootItemConditionalFunction.Builder<ModItemSwap.Builder> {
                    private Item item;
                    private Item oldItem;

                    protected ModItemSwap.Builder getThis() {
                        return this;
                    }

                    public LootItemFunction build() {
                        return new ModItemSwap(this.getConditions(), this.item, this.oldItem, true);
                    }
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<ModItemSwap> {
                    public void serialize(JsonObject object, ModItemSwap function, JsonSerializationContext serializationContext) {
                        if (function.success)
                            object.addProperty("item", BuiltInRegistries.ITEM.getKey(function.item).toString());
                        else
                            object.addProperty("default", BuiltInRegistries.ITEM.getKey(function.item).toString());
                        object.addProperty("default", BuiltInRegistries.ITEM.getKey(function.oldItem).toString());
                    }

                    public ModItemSwap deserialize(JsonObject object, JsonDeserializationContext deserializationContext, LootItemCondition[] conditions) {
                        Item item;
                        boolean success;
                        try {
                            item = GsonHelper.getAsItem(object, "item");
                            success = true;
                        } catch (JsonSyntaxException e) {
                            item = GsonHelper.getAsItem(object, "default");
                            success = false;
                        }
                        return new ModItemSwap(conditions, item, GsonHelper.getAsItem(object, "default"), success);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("NoExtraLootFunction.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            public class NoExtraLootFunction extends LootItemConditionalFunction {
                protected NoExtraLootFunction(LootItemCondition[] conditions) {
                    super(conditions);
                }

                public LootItemFunctionType getType() {
                    return TestLoot.NO_EXTRA.get();
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return stack;
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<NoExtraLootFunction> {
                    public void serialize(JsonObject json, NoExtraLootFunction instance, JsonSerializationContext context) {
                        super.serialize(json, instance, context);
                    }

                    public NoExtraLootFunction deserialize(JsonObject json, JsonDeserializationContext context, LootItemCondition[] conditions) {
                        return new NoExtraLootFunction(conditions);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("SpawnEntityLootFunction.java").writeText("""
            package com.example;

            import com.google.gson.JsonDeserializationContext;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSerializationContext;
            import com.google.gson.JsonSyntaxException;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

            public class SpawnEntityLootFunction extends LootItemConditionalFunction {
                private final EntityType<?> entityType;
                private final int count;

                protected SpawnEntityLootFunction(LootItemCondition[] conditions, EntityType<?> entityType, int count) {
                    super(conditions);
                    this.entityType = entityType;
                    this.count = count;
                }

                public LootItemFunctionType getType() {
                    return TestLoot.SPAWN_ENTITY.get();
                }

                public ItemStack run(ItemStack stack, LootContext context) {
                    return stack;
                }

                public static class Serializer extends LootItemConditionalFunction.Serializer<SpawnEntityLootFunction> {
                    public void serialize(JsonObject json, SpawnEntityLootFunction instance, JsonSerializationContext context) {
                        super.serialize(json, instance, context);
                        json.addProperty("entity", EntityType.getKey(instance.entityType).toString());
                        json.addProperty("count", instance.count);
                    }

                    public SpawnEntityLootFunction deserialize(JsonObject json, JsonDeserializationContext context, LootItemCondition[] conditions) {
                        EntityType<?> entityType = EntityType.byString(GsonHelper.getAsString(json, "entity")).orElseThrow(() -> new JsonSyntaxException("No value present!"));
                        int count = GsonHelper.getAsInt(json, "count");
                        return new SpawnEntityLootFunction(conditions, entityType, count);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("TestLoot.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class TestLoot {
                public static final DeferredRegister<LootItemConditionType> CONDITIONS = DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, "example");
                public static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, "example");

                public static final DeferredHolder<LootItemFunctionType, LootItemFunctionType> ITEM_OR_DEFAULT =
                        FUNCTIONS.register("item_or_default", () -> new LootItemFunctionType(new ModItemSwap.Serializer()));
                public static final DeferredHolder<LootItemFunctionType, LootItemFunctionType> NO_EXTRA =
                        FUNCTIONS.register("no_extra", () -> new LootItemFunctionType(new NoExtraLootFunction.Serializer()));
                public static final DeferredHolder<LootItemFunctionType, LootItemFunctionType> SPAWN_ENTITY =
                        FUNCTIONS.register("spawn_entity", () -> new LootItemFunctionType(new SpawnEntityLootFunction.Serializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> MOD_EXISTS =
                        CONDITIONS.register("mod_exists", () -> new LootItemConditionType(new ModExistsCondition.ConditionSerializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> CONFIG_ENABLED =
                        CONDITIONS.register("config_enabled", () -> new LootItemConditionType(new ConfigEnabled.Serializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> ENTITY_TARGET =
                        CONDITIONS.register("entity_target", () -> new LootItemConditionType(new EntityTargetCondition.ConditionSerializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> IS_MINION =
                        CONDITIONS.register("is_minion", () -> new LootItemConditionType(new IsMinionCondition.ConditionSerializer()));
                public static final DeferredHolder<LootItemConditionType, LootItemConditionType> UNCRAFTING_TABLE_ENABLED =
                        CONDITIONS.register("uncrafting_table_enabled", () -> new LootItemConditionType(new UncraftingTableEnabledCondition.ConditionSerializer()));
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(tempDir)

        val modExists = srcDir.resolve("ModExistsCondition.java").readText()
        val entityTarget = srcDir.resolve("EntityTargetCondition.java").readText()
        val isMinion = srcDir.resolve("IsMinionCondition.java").readText()
        val uncrafting = srcDir.resolve("UncraftingTableEnabledCondition.java").readText()
        val configEnabled = srcDir.resolve("ConfigEnabled.java").readText()
        val modItemSwap = srcDir.resolve("ModItemSwap.java").readText()
        val noExtraLootFunction = srcDir.resolve("NoExtraLootFunction.java").readText()
        val spawnEntityLootFunction = srcDir.resolve("SpawnEntityLootFunction.java").readText()
        val registry = srcDir.resolve("TestLoot.java").readText()

        assertTrue(result.changes.any { it.ruleId == "loot-serializer-mapcodec" })
        assertTrue(modExists.contains("public static final MapCodec<ModExistsCondition> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec"))
        assertTrue(modExists.contains("com.mojang.serialization.Codec.STRING.fieldOf(\"mod_id\").forGetter(o -> o.modID)"))
        assertTrue(entityTarget.contains("LootContext.EntityTarget.CODEC.fieldOf(\"entity\").forGetter(o -> o.entityTarget)"))
        assertTrue(isMinion.contains("com.mojang.serialization.Codec.BOOL.optionalFieldOf(\"inverse\", false).forGetter(o -> o.inverse)"))
        assertTrue(uncrafting.contains("public static final MapCodec<UncraftingTableEnabledCondition> CODEC = MapCodec.unit(new UncraftingTableEnabledCondition());"))
        assertTrue(configEnabled.contains("com.mojang.serialization.Codec.STRING.fieldOf(\"config\").forGetter(o -> ConfigSerializationUtil.serialize(o.config))"))
        assertTrue(configEnabled.contains("value -> new ConfigEnabled(ConfigSerializationUtil.deserialize(value))"))
        assertTrue(modItemSwap.contains("public static final MapCodec<ModItemSwap> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec"))
        assertTrue(modItemSwap.contains("protected ModItemSwap(java.util.List<LootItemCondition> conditions, Item item, Item old, boolean success)"))
        assertTrue(modItemSwap.contains("public LootItemFunctionType<ModItemSwap> getType()"))
        assertTrue(noExtraLootFunction.contains("public static final MapCodec<NoExtraLootFunction> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).apply(instance, NoExtraLootFunction::new));"))
        assertTrue(noExtraLootFunction.contains("protected NoExtraLootFunction(java.util.List<LootItemCondition> conditions)"))
        assertTrue(noExtraLootFunction.contains("public LootItemFunctionType<NoExtraLootFunction> getType()"))
        assertTrue(spawnEntityLootFunction.contains("BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf(\"entity\").forGetter(o -> o.entityType)"))
        assertTrue(spawnEntityLootFunction.contains("com.mojang.serialization.Codec.INT.fieldOf(\"count\").forGetter(o -> o.count)"))
        assertTrue(spawnEntityLootFunction.contains("protected SpawnEntityLootFunction(java.util.List<LootItemCondition> conditions, EntityType<?> entityType, int count)"))
        assertTrue(spawnEntityLootFunction.contains("public LootItemFunctionType<SpawnEntityLootFunction> getType()"))
        assertTrue(registry.contains("DeferredRegister<LootItemFunctionType<?>> FUNCTIONS"))
        assertTrue(registry.contains("DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<ModItemSwap>> ITEM_OR_DEFAULT"))
        assertTrue(registry.contains("DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<NoExtraLootFunction>> NO_EXTRA"))
        assertTrue(registry.contains("DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<SpawnEntityLootFunction>> SPAWN_ENTITY"))
        assertTrue(registry.contains("new LootItemFunctionType<>(ModItemSwap.CODEC)"))
        assertTrue(registry.contains("new LootItemFunctionType<>(NoExtraLootFunction.CODEC)"))
        assertTrue(registry.contains("new LootItemFunctionType<>(SpawnEntityLootFunction.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(ModExistsCondition.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(ConfigEnabled.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(EntityTargetCondition.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(IsMinionCondition.CODEC)"))
        assertTrue(registry.contains("new LootItemConditionType(UncraftingTableEnabledCondition.CODEC)"))
        assertFalse(modExists.contains("ConditionSerializer"))
        assertFalse(configEnabled.contains("net.minecraft.world.level.storage.loot.Serializer"))
        assertFalse(modItemSwap.contains("LootItemConditionalFunction.Serializer"))
        assertFalse(noExtraLootFunction.contains("LootItemConditionalFunction.Serializer"))
        assertFalse(spawnEntityLootFunction.contains("LootItemConditionalFunction.Serializer"))
        assertFalse(spawnEntityLootFunction.contains("EntityType.byString"))
        assertFalse(modItemSwap.contains("JsonSyntaxException"))
        assertFalse(modExists.contains("net.minecraft.world.level.storage.loot.Serializer"))
    }

    @Test
    fun `legacy neoforge recipe condition serializers migrate to condition codecs`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.common.conditions.ICondition;
            import net.neoforged.neoforge.common.conditions.IConditionSerializer;

            public class TestMod implements ICondition {
                private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("example", "enabled");
                public static final TestMod INSTANCE = new TestMod();

                private TestMod() {
                }

                @Override
                public ResourceLocation getID() {
                    return ID;
                }

                @Override
                public boolean test(IContext context) {
                    return true;
                }

                public static class Serializer implements IConditionSerializer<TestMod> {
                    public static final Serializer INSTANCE = new Serializer();

                    @Override
                    public ResourceLocation getID() {
                        return ID;
                    }

                    @Override
                    public TestMod read(JsonObject json) {
                        return new TestMod();
                    }

                    @Override
                    public void write(JsonObject json, TestMod value) {
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "neoforge-condition-serializer-mapcodec" })
        assertTrue(transformed.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(transformed.contains("public static final String CONDITION_ID = \"enabled\";"))
        assertTrue(transformed.contains("public static final MapCodec<TestMod> CODEC = MapCodec.unit(INSTANCE);"))
        assertTrue(transformed.contains("public MapCodec<? extends ICondition> codec()"))
        assertTrue(transformed.contains("return CODEC;"))
        assertFalse(transformed.contains("IConditionSerializer"))
        assertFalse(transformed.contains("ResourceLocation"))
        assertFalse(transformed.contains("JsonObject"))
        assertFalse(transformed.contains("getID()"))
        assertFalse(transformed.contains("class Serializer"))
    }

    @Test
    fun `partial nbt ingredient helpers migrate to data component ingredients`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.Util;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.crafting.PartialNBTIngredient;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class TestMod {
                public final PartialNBTIngredient scepter(Item scepter) {
                    return PartialNBTIngredient.of(scepter, Util.make(() -> {
                        CompoundTag nbt = new CompoundTag();
                        nbt.putInt(ItemStack.TAG_DAMAGE, scepter.getMaxDamage());
                        return nbt;
                    }));
                }

                public final PartialNBTIngredient potion(Potion potion) {
                    return PartialNBTIngredient.of(Items.POTION, Util.make(() -> {
                        CompoundTag nbt = new CompoundTag();
                        nbt.putString("Potion", BuiltInRegistries.POTION.getKey(potion).toString());
                        return nbt;
                    }));
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.core.Holder;"))
        assertTrue(transformed.contains("import net.minecraft.core.component.DataComponents;"))
        assertTrue(transformed.contains("import net.minecraft.world.item.alchemy.PotionContents;"))
        assertTrue(transformed.contains("import net.neoforged.neoforge.common.crafting.DataComponentIngredient;"))
        assertTrue(transformed.contains("public final Ingredient scepter(Item scepter)"))
        assertTrue(transformed.contains("DataComponentIngredient.of(false, DataComponents.DAMAGE, scepter.getMaxDamage(), scepter)"))
        assertTrue(transformed.contains("public final Ingredient potion(Holder<Potion> potion)"))
        assertTrue(transformed.contains("DataComponentIngredient.of(false, DataComponents.POTION_CONTENTS, new PotionContents(potion), Items.POTION)"))
        assertFalse(transformed.contains("PartialNBTIngredient"))
        assertFalse(transformed.contains("CompoundTag"))
        assertFalse(transformed.contains("Util.make"))
        assertFalse(transformed.contains("ItemStack.TAG_DAMAGE"))
    }

    @Test
    fun `single item recipe result wrappers migrate to recipe output save calls`() {
        val projectDir = createTestFile("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.SingleItemRecipeBuilder;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.level.ItemLike;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.core.registries.BuiltInRegistries;
            import org.jetbrains.annotations.Nullable;

            import java.util.function.Consumer;

            public class TestMod {
                protected static void buildRecipes(RecipeOutput consumer) {
                    consumer.accept(stonecutting(Blocks.STONE, ModBlocks.CUT_STONE.get()));
                    consumer.accept(stonecutting(Blocks.STONE, ModBlocks.TILE.get(), 2));
                }

                private static Wrapper stonecutting(ItemLike input, ItemLike output) {
                    return stonecutting(input, output, 1);
                }

                private static Wrapper stonecutting(ItemLike input, ItemLike output, int count) {
                    return new Wrapper(getIdFor(input.asItem(), output.asItem()), Ingredient.of(input), output.asItem(), count);
                }

                private static ResourceLocation getIdFor(Item input, Item output) {
                    String path = String.format("stonecutting/%s/%s", BuiltInRegistries.ITEM.getKey(input).getPath(), BuiltInRegistries.ITEM.getKey(output).getPath());
                    return prefix(path);
                }

                // Wrapper that allows you to not have an advancement
                public static class Wrapper extends SingleItemRecipeBuilder.Result {
                    public Wrapper(ResourceLocation id, Ingredient input, Item output, int count) {
                        super(id, RecipeSerializer.STONECUTTER, "", input, output, count, null, null);
                    }

                    @Nullable
                    @Override
                    public JsonObject serializeAdvancement() {
                        return null;
                    }

                    @Nullable
                    @Override
                    public ResourceLocation getAdvancementId() {
                        return null;
                    }
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("import net.minecraft.data.recipes.RecipeCategory;"))
        assertTrue(transformed.contains("stonecutting(consumer, Blocks.STONE, ModBlocks.CUT_STONE.get());"))
        assertTrue(transformed.contains("stonecutting(consumer, Blocks.STONE, ModBlocks.TILE.get(), 2);"))
        assertTrue(transformed.contains("private static void stonecutting(RecipeOutput recipe, ItemLike input, ItemLike output)"))
        assertTrue(transformed.contains("SingleItemRecipeBuilder.stonecutting(Ingredient.of(input), RecipeCategory.BUILDING_BLOCKS, output.asItem(), count).unlockedBy(\"has_block\", has(input)).save(recipe, getIdFor(input, output));"))
        assertTrue(transformed.contains("private static ResourceLocation getIdFor(ItemLike input, ItemLike output)"))
        assertTrue(transformed.contains("BuiltInRegistries.ITEM.getKey(input.asItem()).getPath()"))
        assertTrue(transformed.contains("CriteriaTriggers.INVENTORY_CHANGED.createCriterion"))
        assertFalse(transformed.contains("SingleItemRecipeBuilder.Result"))
        assertFalse(transformed.contains("consumer.accept(stonecutting"))
        assertFalse(transformed.contains("serializeAdvancement"))
        assertFalse(transformed.contains("RecipeSerializer.STONECUTTER"))
    }

    @Test
    fun `dyeable leather item implementations migrate to dyed item color components`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.ChatFormatting;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;
            import net.minecraft.network.chat.MutableComponent;
            import net.minecraft.network.chat.Style;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.core.component.DataComponents;

            public class TestMod extends ArmorItem implements DyeableLeatherItem {
                private static final MutableComponent TOOLTIP = Component.translatable("item.example.arctic.desc").setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY));

                public TestMod(ArmorMaterial armorMaterial, Type type, Properties properties) {
                    super(armorMaterial, type, properties);
                }

                @Override
                public boolean hasCustomColor(ItemStack stack) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    return tag.contains("display", 10) && tag.getCompound("display").contains("color", 3);
                }

                @Override
                public int getColor(ItemStack stack) {
                    return this.getColor(stack, 1);
                }

                @Override
                public void clearColor(ItemStack stack) {
                    this.removeColor(stack);
                }

                public int getColor(ItemStack stack, int type) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    return type == 0 ? 0xFFFFFF : tag.getCompound("display").getInt("color");
                }

                public void removeColor(ItemStack stack) {
                    stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().remove("color");
                }

                @Override
                public void setColor(ItemStack stack, int color) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    tag.putInt("color", color);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public class TestMod extends ArmorItem"))
        assertTrue(transformed.contains("public static final int DEFAULT_COLOR = 0xFFBDCFD9;"))
        assertTrue(transformed.contains("return stack.has(DataComponents.DYED_COLOR);"))
        assertTrue(transformed.contains("return type == 0 ? 0xFFFFFF : DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);"))
        assertTrue(transformed.contains("stack.remove(DataComponents.DYED_COLOR);"))
        assertTrue(transformed.contains("stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color, true));"))
        assertTrue(transformed.contains("import net.minecraft.world.item.component.DyedItemColor;"))
        assertFalse(transformed.contains("DyeableLeatherItem"))
        assertFalse(transformed.contains("CUSTOM_DATA"))
        assertFalse(transformed.contains("CompoundTag"))
        assertFalse(transformed.contains("@Override\n\tpublic boolean hasCustomColor"))
    }

    @Test
    fun `dyeable leather item color migration does not require tooltip fields`() {
        val projectDir = createTestFile("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.core.component.DataComponents;

            public class TestMod extends ArmorItem implements DyeableLeatherItem {
                @Override
                public boolean hasCustomColor(ItemStack stack) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    return tag.contains("display", 10) && tag.getCompound("display").contains("color", 3);
                }

                @Override
                public void setColor(ItemStack stack, int color) {
                    CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    tag.putInt("color", color);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertTrue(transformed.contains("public static final int DEFAULT_COLOR = 0xFFBDCFD9;"))
        assertTrue(transformed.contains("DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);"))
        assertFalse(transformed.contains("DyeableLeatherItem"))
        assertFalse(transformed.contains("CUSTOM_DATA"))
    }

    @Test
    fun `dyeable leather item interface and external color handlers migrate to dyed item color`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val itemDir = srcDir.resolve("item")
        val clientDir = srcDir.resolve("client")
        itemDir.createDirectories()
        clientDir.createDirectories()
        itemDir.resolve("LeatherGlovesItem.java").writeText("""
            package com.example.item;

            import net.minecraft.world.item.DyeableLeatherItem;

            public class LeatherGlovesItem extends GlovesItem implements DyeableLeatherItem {
                public LeatherGlovesItem(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        clientDir.resolve("ColorResolver.java").writeText("""
            package com.example.client;

            import net.minecraft.world.item.DyeableLeatherItem;
            import net.minecraft.world.item.ItemStack;

            public class ColorResolver {
                public int color(ItemStack stack, int tintIndex) {
                    return tintIndex > 0 ? -1 : ((DyeableLeatherItem) stack.getItem()).getColor(stack);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(tempDir)

        val item = itemDir.resolve("LeatherGlovesItem.java").readText()
        val colorResolver = clientDir.resolve("ColorResolver.java").readText()

        assertTrue(item.contains("public class LeatherGlovesItem extends GlovesItem"))
        assertFalse(item.contains("DyeableLeatherItem"))
        assertTrue(colorResolver.contains("DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR)"))
        assertTrue(colorResolver.contains("import net.minecraft.world.item.component.DyedItemColor;"))
        assertFalse(colorResolver.contains("DyeableLeatherItem"))
        assertFalse(colorResolver.contains(".getColor(stack)"))
    }

    @Test
    fun `tier sorting registry custom tiers migrate to simple tier incorrect block tags`() {
        val projectDir = createTestFile("""
            package com.example.util;

            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.item.Tiers;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.neoforge.common.SimpleTier;
            import net.neoforged.neoforge.common.TierSortingRegistry;
            import com.example.ExampleMod;
            import com.example.init.ModItems;

            import java.util.List;

            public class TestMod {
                public static final Tier COPPER = TierSortingRegistry.registerTier(
                        new SimpleTier(2, 512, 6.5F, 2, 25, BlockTags.create(ExampleMod.prefix("needs_copper_tool")), () -> Ingredient.of(ModItems.COPPER_INGOT.get())),
                        ExampleMod.prefix("copper"), List.of(Tiers.IRON), List.of(Tiers.DIAMOND));

                public static final Tier GLASS = TierSortingRegistry.registerTier(
                        new SimpleTier(BlockTags.create(ExampleMod.prefix("needs_glass_tool")), 1, 1.0F, 36.0F, 30, () -> Ingredient.EMPTY),
                        ExampleMod.prefix("glass"), List.of(Tiers.WOOD), List.of());
            }
        """.trimIndent())
        projectDir.resolve("src/generated/resources/data/example").createDirectories()
        projectDir.resolve("src/generated/resources/data/anothermod").createDirectories()
        projectDir.resolve("src/main/java/com/example/ExampleMod.java").writeText("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public class ExampleMod {
                public static final String ID = "example";

                public static ResourceLocation prefix(String path) {
                    return ResourceLocation.fromNamespaceAndPath(ID, path);
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        val copperTag = projectDir.resolve("src/generated/resources/data/example/tags/block/incorrect_for_copper_tool.json")
        val glassTag = projectDir.resolve("src/generated/resources/data/example/tags/block/incorrect_for_glass_tool.json")

        assertTrue(transformed.contains("""new SimpleTier(BlockTags.create(ExampleMod.prefix("incorrect_for_copper_tool")), 512, 6.5F, 2, 25, () -> Ingredient.of(ModItems.COPPER_INGOT.get()))"""))
        assertTrue(transformed.contains("""new SimpleTier(BlockTags.create(ExampleMod.prefix("incorrect_for_glass_tool")), 1, 1.0F, 36.0F, 30, () -> Ingredient.EMPTY)"""))
        assertTrue(result.changes.any { it.ruleId == "tier-incorrect-block-tag-resource" })
        assertTrue(copperTag.exists())
        assertTrue(copperTag.readText().contains(""""#minecraft:incorrect_for_iron_tool""""))
        assertTrue(glassTag.exists())
        assertTrue(glassTag.readText().contains(""""#minecraft:incorrect_for_wooden_tool""""))
        assertFalse(transformed.contains("TierSortingRegistry"))
        assertFalse(transformed.contains("needs_copper_tool"))
        assertFalse(transformed.contains("needs_glass_tool"))
        assertFalse(transformed.contains("List.of(Tiers"))
    }

    @Test
    fun `tier sorting registry drop checks migrate to tier tool properties`() {
        val projectDir = createTestFile("""
            package com.example.util;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.common.TierSortingRegistry;

            public class TestMod {
                public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
                    return TierSortingRegistry.isCorrectTierForDrops(getHarvestLevel(stack), state);
                }

                private Tier getHarvestLevel(ItemStack stack) {
                    return null;
                }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        pass.apply(projectDir)

        val transformed = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()

        assertFalse(transformed.contains("TierSortingRegistry"))
        assertTrue(transformed.contains("(getHarvestLevel(stack)).createToolProperties("))
        assertTrue(transformed.contains("state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE) ? net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE"))
        assertTrue(transformed.contains("net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE).isCorrectForDrops(state)"))
    }

    @Test
    fun `dry run does not modify files`() {
        val original = """
            import net.minecraftforge.common.MinecraftForge;
        """.trimIndent()
        val projectDir = createTestFile(original)

        val db = MappingDatabase.loadDefault()
        val pass = TextReplacementPass(db)
        val result = pass.analyze(projectDir)

        val content = projectDir.resolve("src/main/java/com/example/TestMod.java").readText()
        assertEquals(original, content, "Dry run should not modify files")
        assertTrue(result.changeCount > 0, "Dry run should still report changes")
    }
}
