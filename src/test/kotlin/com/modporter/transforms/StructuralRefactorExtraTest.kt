package com.modporter.transforms

import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Additional tests for StructuralRefactorPass edge cases and error handling.
 */
class StructuralRefactorExtraTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createFile(filename: String, content: String): Path {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve(filename).writeText(content)
        return tempDir
    }

    @Test
    fun `handles unparseable file gracefully`() {
        val projectDir = createFile("Broken.java", "this is not valid java {{{")
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        // Should not crash, may have 0 changes
        assertTrue(result.errors.isEmpty() || result.changeCount == 0)
    }

    @Test
    fun `handles file with no capability patterns`() {
        val projectDir = createFile("Clean.java", """
            package com.example;
            public class Clean {
                int x = 5;
                void doStuff() { System.out.println("hello"); }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertEquals(0, result.changeCount)
    }

    @Test
    fun `migrates legacy pack resource APIs to resources supplier adapters`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("examplemod")
            public class ExampleMod {
            }
        """.trimIndent())
        srcDir.resolve("PackSetup.java").writeText("""
            package com.example;

            import java.nio.file.Path;
            import net.minecraft.SharedConstants;
            import net.minecraft.network.chat.Component;
            import net.minecraft.server.packs.PackType;
            import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
            import net.minecraft.server.packs.repository.Pack;
            import net.minecraft.server.packs.repository.PackSource;
            import net.minecraft.world.flag.FeatureFlagSet;
            import net.neoforged.neoforge.event.AddPackFindersEvent;
            import net.neoforged.neoforge.resource.PathPackResources;

            public class PackSetup {
                public void add(AddPackFindersEvent event, Path sourcePath) {
                    PathPackResources pack = new PathPackResources("example:" + sourcePath, true, sourcePath);
                    Pack.ResourcesSupplier resourcesSupplier = (string) -> pack;
                    Pack.Info info = Pack.readPackInfo("builtin/example", resourcesSupplier);
                    if (info != null) {
                        event.addRepositorySource((source) -> source.accept(Pack.create("builtin/example", Component.literal("Example"), true, resourcesSupplier, info, PackType.CLIENT_RESOURCES, Pack.Position.TOP, false, PackSource.BUILT_IN)));
                    }
                    PackMetadataSection metadata = new PackMetadataSection(Component.literal("Desc"), SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES));
                    event.addRepositorySource((source) -> source.accept(Pack.create("builtin/example_inline", Component.literal("Inline"), false, (string) -> pack, new Pack.Info(metadata.getDescription(), metadata.getPackFormat(PackType.SERVER_DATA), metadata.getPackFormat(PackType.CLIENT_RESOURCES), FeatureFlagSet.of(), pack.isHidden()), PackType.CLIENT_RESOURCES, Pack.Position.TOP, false, PackSource.BUILT_IN)));
                }
            }
        """.trimIndent())
        srcDir.resolve("CombinedPackResources.java").writeText("""
            package com.example;

            import java.nio.file.Path;
            import java.util.List;
            import net.minecraft.server.packs.PackResources;
            import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
            import net.neoforged.neoforge.resource.DelegatingPackResources;

            public class CombinedPackResources extends DelegatingPackResources {
                public CombinedPackResources(String id, PackMetadataSection packInfo, List<? extends PackResources> packs, Path sourcePack) {
                    super(id, true, packInfo, packs);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val setup = srcDir.resolve("PackSetup.java").readText()
        val combined = srcDir.resolve("CombinedPackResources.java").readText()
        val compatDir = tempDir.resolve("src/main/java/com/modporter/generated/examplemod/compat")

        assertTrue(result.changes.any { it.ruleId == "struct-legacy-pack-resources" })
        assertTrue(result.changes.any { it.ruleId == "struct-generate-path-pack-resources" })
        assertTrue(result.changes.any { it.ruleId == "struct-generate-delegating-pack-resources" })
        assertTrue(result.changes.any { it.ruleId == "struct-generate-legacy-pack-resources-supplier" })
        assertTrue(setup.contains("import com.modporter.generated.examplemod.compat.PathPackResources;"))
        assertTrue(setup.contains("new com.modporter.generated.examplemod.compat.LegacyPackResourcesSupplier((string) -> pack)"))
        assertTrue(setup.contains("Pack.Metadata info = Pack.readPackMetadata("))
        assertTrue(setup.contains("new Pack(new PackLocationInfo("))
        assertTrue(setup.contains("new PackSelectionConfig("))
        assertTrue(setup.contains("PackCompatibility.forVersion(new InclusiveRange<>("))
        assertTrue(setup.contains("metadata.description()"))
        assertTrue(setup.contains("metadata.packFormat()"))
        assertFalse(setup.contains("Pack.create("))
        assertFalse(setup.contains("Pack.Info"))
        assertFalse(setup.contains("readPackInfo"))
        assertFalse(setup.contains("getDescription()"))
        assertFalse(setup.contains("getPackFormat("))
        assertTrue(combined.contains("import com.modporter.generated.examplemod.compat.DelegatingPackResources;"))
        assertTrue(compatDir.resolve("PathPackResources.java").exists())
        assertTrue(compatDir.resolve("DelegatingPackResources.java").readText().contains("extends AbstractPackResources"))
        assertTrue(compatDir.resolve("LegacyPackResourcesSupplier.java").readText().contains("openPrimary(PackLocationInfo location)"))
    }

    @Test
    fun `migrates cross class DeferredHolder presence checks without touching optionals`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("EntityRegistry.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.registries.DeferredHolder;

            public class EntityRegistry {
                public static final DeferredHolder<EntityType<?>, EntityType<DeerEntity>> DEER = null;
            }
        """.trimIndent())
        srcDir.resolve("ContentRegistryTest.java").writeText("""
            package com.example;

            import java.util.Optional;

            public class ContentRegistryTest {
                public void verify(Optional<String> optional) {
                    boolean registryBound = EntityRegistry.DEER.isPresent();
                    boolean optionalPresent = optional.isPresent();
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val transformed = srcDir.resolve("ContentRegistryTest.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-deferredholder-isbound" })
        assertTrue(transformed.contains("EntityRegistry.DEER.isBound()"))
        assertTrue(transformed.contains("optional.isPresent()"))
        assertTrue(!transformed.contains("EntityRegistry.DEER.isPresent()"))
    }

    @Test
    fun `moves mod bus event listener registration off common NeoForge bus`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
            import net.neoforged.neoforge.common.NeoForge;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(this::commonSetup);
                }

                private void commonSetup(Object event) {
                }

                @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)
                public static class ModEvents {
                    @SubscribeEvent
                    public static void onClientSetup(FMLClientSetupEvent event) {
                        NeoForge.EVENT_BUS.addListener(ClientEvent::registerParticleFactories);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientEvent.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

            public class ClientEvent {
                public static void registerParticleFactories(RegisterParticleProvidersEvent event) {
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-modbus-listener-registration" })
        assertTrue(migrated.contains("modEventBus.addListener(ClientEvent::registerParticleFactories);"))
        assertTrue(!migrated.contains("NeoForge.EVENT_BUS.addListener(ClientEvent::registerParticleFactories);"))
        assertTrue(!migrated.contains("[forge2neo] Mod-bus event handlers extracted"))
        assertTrue(migrated.trimEnd().endsWith("}"))
    }

    @Test
    fun `migrates static bus mod subscribers to constructor listener registration`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val dataDir = srcDir.resolve("data")
        srcDir.createDirectories()
        dataDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
                    Registry.register(modEventBus);
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
            public class ModData {
                @SubscribeEvent
                public static void gatherData(GatherDataEvent event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientHooks.java").writeText("""
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.api.distmarker.OnlyIn;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

            @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
            public class ClientHooks {
                @OnlyIn(Dist.CLIENT)
                @SubscribeEvent
                public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val main = srcDir.resolve("ExampleMod.java").readText()
        val data = dataDir.resolve("ModData.java").readText()
        val clientHooks = srcDir.resolve("ClientHooks.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-static-modbus-subscriber-registration" })
        assertTrue(result.changes.any { it.ruleId == "struct-static-modbus-subscriber-cleanup" })
        assertTrue(main.contains("modEventBus.addListener(com.example.data.ModData::gatherData);"))
        assertTrue(main.contains("FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT"))
        assertTrue(main.contains("modEventBus.addListener(com.example.ClientHooks::registerItemColors);"))
        assertTrue(!data.contains("@EventBusSubscriber"))
        assertTrue(!data.contains("@SubscribeEvent"))
        assertTrue(!data.contains("EventBusSubscriber.Bus.MOD"))
        assertTrue(!clientHooks.contains("@EventBusSubscriber"))
        assertTrue(!clientHooks.contains("@SubscribeEvent"))
    }

    @Test
    fun `inserts static mod bus subscribers into constructor with local mod bus variable`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val dataDir = srcDir.resolve("data")
        srcDir.createDirectories()
        dataDir.createDirectories()
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
                    Registry.ITEMS.register(modEventBus);
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
            public class ModData {
                @SubscribeEvent
                public static void gatherData(GatherDataEvent event) {
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)
        val main = srcDir.resolve("ExampleMod.java").readText()
        val listenerIndex = main.indexOf("modEventBus.addListener(com.example.data.ModData::gatherData);")
        val classEndIndex = main.lastIndexOf("}")

        assertTrue(main.contains("Registry.ITEMS.register(modEventBus);\n        modEventBus.addListener(com.example.data.ModData::gatherData);"))
        assertTrue(listenerIndex > 0)
        assertTrue(listenerIndex < classEndIndex)
    }

    @Test
    fun `detects ifPresent and orElse on capability-related expressions`() {
        val projectDir = createFile("CapUsage.java", """
            package com.example;
            public class CapUsage {
                void use() {
                    Object handler = getCapability(cap, side);
                    handler.ifPresent(h -> use(h));
                    Object fallback = handler.orElse(null);
                    Object resolved = cap.resolve().orElseThrow();
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertTrue(result.changes.any { it.ruleId == "struct-lazy-optional-method" },
            "Should detect ifPresent/orElse/orElseThrow on cap-related code")
    }

    @Test
    fun `migrates ItemStack capability Optional resolve pattern`() {
        val projectDir = createFile("ItemHandlerUsage.java", """
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.items.IItemHandler;
            import java.util.Optional;

            public class ItemHandlerUsage {
                int count(ItemStack stack) {
                    if (stack.getCapability(Capabilities.ITEM_HANDLER).isPresent()) {
                        Optional<IItemHandler> maybe = stack.getCapability(Capabilities.ITEM_HANDLER).resolve();
                        if (maybe.isEmpty()) return 0;
                        IItemHandler handler = maybe.get();
                        return handler.getSlots();
                    }
                    return 0;
                }

                boolean direct(ItemStack inventoryStack) {
                    return inventoryStack.getCapability(Capabilities.ItemHandler.BLOCK) != null;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = projectDir
            .resolve("src/main/java/com/example/ItemHandlerUsage.java")
            .readText()

        assertTrue(result.changes.any { it.ruleId == "struct-itemstack-item-handler-capability" })
        assertTrue(result.changes.any { it.ruleId == "struct-capability-resolve-nullable" })
        assertTrue(result.changes.filter { it.ruleId.startsWith("struct-capability") || it.ruleId.startsWith("struct-itemstack") }
            .all { it.confidence == Confidence.HIGH })
        assertTrue(migrated.contains("stack.getCapability(Capabilities.ItemHandler.ITEM) != null"))
        assertTrue(migrated.contains("IItemHandler maybe = stack.getCapability(Capabilities.ItemHandler.ITEM);"))
        assertTrue(migrated.contains("inventoryStack.getCapability(Capabilities.ItemHandler.ITEM) != null"))
        assertTrue(migrated.contains("if (maybe == null)"))
        assertTrue(migrated.contains("IItemHandler handler = maybe;"))
        assertTrue(!migrated.contains("Optional<"))
        assertTrue(!migrated.contains(".resolve()"))
    }

    @Test
    fun `packet migration does not replace mod main class with SimpleChannel`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        srcDir.createDirectories()
        networkDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.network.simple.SimpleChannel;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";
                public static ExampleMod instance;
                public SimpleChannel channel;

                public ExampleMod() {
                    instance = this;
                }
            }
        """.trimIndent())

        networkDir.resolve("PacketPing.java").writeText("""
            package com.example.network;

            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.network.handling.IPayloadContext;

            public class PacketPing {
                public static void encode(PacketPing msg, FriendlyByteBuf buf) {
                }

                public static PacketPing decode(FriendlyByteBuf buf) {
                    return new PacketPing();
                }

                public static class Handler {
                    public static void handle(PacketPing msg, IPayloadContext ctx) {
                    }
                }
            }
        """.trimIndent())

        networkDir.resolve("ModPackets.java").writeText("""
            package com.example.network;

            import net.minecraft.server.level.ServerPlayer;
            import net.minecraftforge.network.simple.SimpleChannel;

            public class ModPackets {
                private static SimpleChannel INSTANCE;
                public static void sendToPlayer(Object packet, ServerPlayer player) {
                    INSTANCE.sendTo(packet, player.connection.connection, null);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val mainClass = srcDir.resolve("ExampleMod.java").readText()
        val modNetwork = networkDir.resolve("ModNetwork.java").readText()
        val modPackets = networkDir.resolve("ModPackets.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-generate-mod-network" })
        assertTrue(mainClass.contains("public static final String MODID = \"example\";"))
        assertTrue(mainClass.contains("public static ExampleMod instance;"))
        assertTrue(!mainClass.contains("Replaced SimpleChannel registration class"))
        assertTrue(modNetwork.contains("PacketPing.Handler::handle"))
        assertTrue(modPackets.contains("void sendToPlayer(ServerPlayer player, T msg)"))
        assertTrue(modPackets.contains("void sendToPlayer(T msg, ServerPlayer player)"))
    }

    @Test
    fun `packet migration removes inline SimpleChannel setup from mod main class`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        srcDir.createDirectories()
        networkDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.network.PacketPing;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.simple.SimpleChannel;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";
                private static final String PROTOCOL_VERSION = "1";
                public SimpleChannel channel;

                public ExampleMod(IEventBus modEventBus) {
                    modEventBus.addListener(this::commonSetup);
                    Registry.ITEMS.register(modEventBus);
                }

                private void commonSetup(FMLCommonSetupEvent event) {
                    channel = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(MODID, "main"), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
                    int packetIndex = 0;
                    channel.registerMessage(packetIndex++, PacketPing.class, PacketPing::encode, PacketPing::decode, PacketPing.Handler::handle);
                    Registry.afterNetwork();
                }
            }
        """.trimIndent())

        networkDir.resolve("PacketPing.java").writeText("""
            package com.example.network;

            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.network.handling.IPayloadContext;

            public class PacketPing {
                public static void encode(PacketPing packet, FriendlyByteBuf buf) {
                }

                public static PacketPing decode(FriendlyByteBuf buf) {
                    return new PacketPing();
                }

                public static class Handler {
                    public static void handle(PacketPing packet, IPayloadContext ctx) {
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val main = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-remove-inline-simplechannel-registration" })
        assertTrue(main.contains("modEventBus.addListener(ModNetwork::register);"))
        assertTrue(main.contains("Registry.afterNetwork();"))
        assertTrue(!main.contains("SimpleChannel"))
        assertTrue(!main.contains("NetworkRegistry"))
        assertTrue(!main.contains("registerMessage"))
        assertTrue(!main.contains("packetIndex"))
    }

    @Test
    fun `packet migration supports records client direction and IPayloadContext handlers`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        srcDir.createDirectories()
        networkDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
            }
        """.trimIndent())

        networkDir.resolve("MenuPacket.java").writeText("""
            package com.example.network;

            import com.example.client.MenuScreen;
            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.network.handling.IPayloadContext;
            import java.util.function.Supplier;

            public record MenuPacket(String mode) {
                public static void encode(MenuPacket packet, FriendlyByteBuf buf) {
                    buf.writeUtf(packet.mode);
                }

                public static MenuPacket decode(FriendlyByteBuf buf) {
                    return new MenuPacket(buf.readUtf());
                }

                public static void handle(MenuPacket packet, IPayloadContext contextSupplier) {
                    NetworkEvent.Context context = contextSupplier.get();
                    context.enqueueWork(() -> MenuScreen.open(packet));
                    context.setPacketHandled(true);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val packet = networkDir.resolve("MenuPacket.java").readText()
        val modNetwork = networkDir.resolve("ModNetwork.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-packet-payload" })
        assertTrue(result.changes.any { it.ruleId == "struct-packet-payload-context" })
        assertTrue(packet.contains("public record MenuPacket(String mode) implements CustomPacketPayload {"))
        assertTrue(packet.contains("""ResourceLocation.fromNamespaceAndPath("example", "menu")"""))
        assertTrue(packet.contains("public static void handle(MenuPacket packet, IPayloadContext context) {"))
        assertTrue(!packet.contains("NetworkEvent.Context"))
        assertTrue(!packet.contains("setPacketHandled"))
        assertTrue(modNetwork.contains("registrar.playToClient("))
    }

    @Test
    fun `packet migration maps reception side guards to payload direction`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        srcDir.createDirectories()
        networkDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                }
            }
        """.trimIndent())

        networkDir.resolve("ClientPacket.java").writeText("""
            package com.example.network;

            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.network.handling.IPayloadContext;
            import java.util.function.Supplier;

            public class ClientPacket {
                public static void encode(ClientPacket packet, FriendlyByteBuf buf) {
                }

                public static ClientPacket decode(FriendlyByteBuf buf) {
                    return new ClientPacket();
                }

                public static class Handler {
                    public static void handle(ClientPacket packet, IPayloadContext ctx) {
                        if(!ctx.get().getDirection().getReceptionSide().isClient()) return;
                        ctx.get().setPacketHandled(true);
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val packet = networkDir.resolve("ClientPacket.java").readText()
        val modNetwork = networkDir.resolve("ModNetwork.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-packet-payload-context-cleanup" })
        assertTrue(modNetwork.contains("registrar.playToClient("))
        assertTrue(!packet.contains("ctx.get()"))
        assertTrue(!packet.contains("setPacketHandled"))
        assertTrue(!packet.contains("java.util.function.Supplier"))
    }

    @Test
    fun `base packet records handlers and relay calls migrate to payload registrar`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        val clientboundDir = networkDir.resolve("packet/clientbound")
        val serverboundDir = networkDir.resolve("packet/serverbound")
        srcDir.createDirectories()
        networkDir.createDirectories()
        clientboundDir.createDirectories()
        serverboundDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.network.ExamplePacketHandler;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                    ExamplePacketHandler.register();
                }
            }
        """.trimIndent())
        networkDir.resolve("ExamplePacketHandler.java").writeText("""
            package com.example.network;

            import com.example.network.packet.clientbound.ClientNoticePacket;
            import com.example.network.packet.clientbound.BossPacket;
            import com.example.network.packet.serverbound.ServerActionPacket;
            import com.aetherteam.nitrogen.network.BasePacket;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.simple.SimpleChannel;
            import java.util.function.Function;

            public class ExamplePacketHandler {
                public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath("example", "main"), () -> "1", "1"::equals, "1"::equals);
                private static int index;

                public static synchronized void register() {
                    register(ClientNoticePacket.class, ClientNoticePacket::decode);
                    register(BossPacket.Display.class, BossPacket.Display::decode);
                    register(ServerActionPacket.class, ServerActionPacket::decode);
                }

                private static <MSG extends BasePacket> void register(final Class<MSG> packet, Function<FriendlyByteBuf, MSG> decoder) {
                    INSTANCE.messageBuilder(packet, index++).encoder(BasePacket::encode).decoder(decoder).consumerMainThread(BasePacket::handle).add();
                }
            }
        """.trimIndent())
        clientboundDir.resolve("ClientNoticePacket.java").writeText("""
            package com.example.network.packet.clientbound;

            import com.aetherteam.nitrogen.network.BasePacket;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.entity.player.Player;

            public record ClientNoticePacket(int value) implements BasePacket {
                @Override
                public void encode(FriendlyByteBuf buf) {
                    buf.writeInt(this.value());
                }

                public static ClientNoticePacket decode(FriendlyByteBuf buf) {
                    return new ClientNoticePacket(buf.readInt());
                }

                @Override
                public void execute(Player player) {
                    player.getId();
                }
            }
        """.trimIndent())
        clientboundDir.resolve("BossPacket.java").writeText("""
            package com.example.network.packet.clientbound;

            import com.aetherteam.nitrogen.network.BasePacket;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.entity.player.Player;

            public abstract class BossPacket implements BasePacket {
                protected final int bossID;

                public BossPacket(int bossID) {
                    this.bossID = bossID;
                }

                @Override
                public void encode(FriendlyByteBuf buf) {
                    buf.writeInt(this.bossID);
                }

                public static class Display extends BossPacket {
                    public Display(int bossID) {
                        super(bossID);
                    }

                    public static Display decode(FriendlyByteBuf buf) {
                        return new Display(buf.readInt());
                    }

                    @Override
                    public void execute(Player player) {
                        player.getId();
                    }
                }
            }
        """.trimIndent())
        serverboundDir.resolve("ServerActionPacket.java").writeText("""
            package com.example.network.packet.serverbound;

            import com.aetherteam.nitrogen.network.BasePacket;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.world.entity.player.Player;

            public record ServerActionPacket(int entityID) implements BasePacket {
                @Override
                public void encode(FriendlyByteBuf buf) {
                    buf.writeInt(this.entityID());
                }

                public static ServerActionPacket decode(FriendlyByteBuf buf) {
                    return new ServerActionPacket(buf.readInt());
                }

                @Override
                public void execute(Player player) {
                    player.getId();
                }
            }
        """.trimIndent())
        srcDir.resolve("RelayUse.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.network.PacketRelay;
            import com.example.network.ExamplePacketHandler;
            import com.example.network.packet.clientbound.ClientNoticePacket;
            import com.example.network.packet.serverbound.ServerActionPacket;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;

            public class RelayUse {
                public void send(ServerLevel level, ServerPlayer player) {
                    PacketRelay.sendToPlayer(ExamplePacketHandler.INSTANCE, new ClientNoticePacket(1), player);
                    PacketRelay.sendToServer(ExamplePacketHandler.INSTANCE, new ServerActionPacket(2));
                    PacketRelay.sendToAll(ExamplePacketHandler.INSTANCE, new ClientNoticePacket(3));
                    PacketRelay.sendToNear(ExamplePacketHandler.INSTANCE, new ClientNoticePacket(4), player.getX(), player.getY(), player.getZ(), 8.0, level.dimension());
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val clientPacket = clientboundDir.resolve("ClientNoticePacket.java").readText()
        val bossPacket = clientboundDir.resolve("BossPacket.java").readText()
        val serverPacket = serverboundDir.resolve("ServerActionPacket.java").readText()
        val handler = networkDir.resolve("ExamplePacketHandler.java").readText()
        val main = srcDir.resolve("ExampleMod.java").readText()
        val relay = srcDir.resolve("RelayUse.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-basepacket-payload" })
        assertTrue(result.changes.any { it.ruleId == "struct-basepacket-handler-registration" })
        assertTrue(result.changes.any { it.ruleId == "struct-basepacket-main-registration" })
        assertTrue(result.changes.any { it.ruleId == "struct-packetrelay-distributor" })
        assertTrue(clientPacket.contains("implements CustomPacketPayload"), clientPacket)
        assertTrue(clientPacket.contains("StreamCodec.of((buf, packet) -> packet.encode(buf), ClientNoticePacket::decode)"), clientPacket)
        assertFalse(clientPacket.contains("BasePacket"), clientPacket)
        assertFalse(clientPacket.contains("@Override\n    public void encode"), clientPacket)
        assertTrue(bossPacket.contains("abstract class BossPacket implements CustomPacketPayload"), bossPacket)
        assertTrue(bossPacket.contains("Type<Display> TYPE"), bossPacket)
        assertTrue(bossPacket.contains("StreamCodec.of((buf, packet) -> packet.encode(buf), Display::decode)"), bossPacket)
        assertFalse(bossPacket.contains("BossPacket.TYPE"), bossPacket)
        assertFalse(bossPacket.contains("@Override\n    public void encode"), bossPacket)
        assertTrue(serverPacket.contains("implements CustomPacketPayload"), serverPacket)
        assertTrue(handler.contains("public static void register(RegisterPayloadHandlersEvent event)"), handler)
        assertTrue(handler.contains("registrar.playToClient(ClientNoticePacket.TYPE, ClientNoticePacket.STREAM_CODEC, (payload, context) -> payload.execute(context.player()));"), handler)
        assertTrue(handler.contains("registrar.playToClient(BossPacket.Display.TYPE, BossPacket.Display.STREAM_CODEC, (payload, context) -> payload.execute(context.player()));"), handler)
        assertTrue(handler.contains("registrar.playToServer(ServerActionPacket.TYPE, ServerActionPacket.STREAM_CODEC, (payload, context) -> payload.execute(context.player()));"), handler)
        assertFalse(handler.contains("SimpleChannel"), handler)
        assertFalse(handler.contains("BasePacket"), handler)
        assertTrue(main.contains("modEventBus.addListener(ExamplePacketHandler::register);"), main)
        assertFalse(main.contains("ExamplePacketHandler.register();"), main)
        assertTrue(relay.contains("PacketDistributor.sendToPlayer(player, new ClientNoticePacket(1));"), relay)
        assertTrue(relay.contains("PacketDistributor.sendToServer(new ServerActionPacket(2));"), relay)
        assertTrue(relay.contains("PacketDistributor.sendToAllPlayers(new ClientNoticePacket(3));"), relay)
        assertTrue(relay.contains("PacketDistributor.sendToPlayersNear(level, null, player.getX(), player.getY(), player.getZ(), 8.0, new ClientNoticePacket(4));"), relay)
        assertFalse(relay.contains("PacketRelay"), relay)
        assertFalse(relay.contains("ExamplePacketHandler"), relay)
    }

    @Test
    fun `server packet handler maps getSender to IPayloadContext player check`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        srcDir.createDirectories()
        networkDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
            }
        """.trimIndent())

        networkDir.resolve("TeleportPacket.java").writeText("""
            package com.example.network;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.network.handling.IPayloadContext;

            public class TeleportPacket {
                public static void encode(TeleportPacket packet, FriendlyByteBuf buf) {
                }

                public static TeleportPacket decode(FriendlyByteBuf buf) {
                    return new TeleportPacket();
                }

                public static void handle(TeleportPacket packet, IPayloadContext contextSupplier) {
                    NetworkEvent.Context context = contextSupplier.get();
                    context.enqueueWork(() -> {
                        ServerPlayer serverPlayer = context.getSender();
                        if (serverPlayer != null) {
                            Teleports.run(serverPlayer);
                        }
                    });
                    context.setPacketHandled(true);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(tempDir)
        val packet = networkDir.resolve("TeleportPacket.java").readText()

        assertTrue(packet.contains("if (context.player() instanceof ServerPlayer serverPlayer) {"))
        assertTrue(!packet.contains("getSender()"))
        assertTrue(!packet.contains("setPacketHandled"))
    }

    @Test
    fun `migrates Sakura gametest resource assertions to NeoForge resources`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ResourceIntegrityTest.java").writeText("""
            package com.example;

            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;

            public class ResourceIntegrityTest {
                @GameTest
                public static void loot_modifiers_have_scoped_conditions(GameTestHelper helper) {
                    String global = resourceText("data/forge/loot_modifiers/global_loot_modifiers.json");
                    assertModifierHasCondition(helper, "fishing", "forge:loot_table_id", "minecraft:gameplay/fishing");
                    assertModifierTargetsBlock(helper, "grass_seeds", "minecraft:grass");
                }

                @GameTest
                public static void terrablender_dependency_is_optional(GameTestHelper helper) {
                    String modsToml = resourceTextContaining("META-INF/mods.toml", "modId=\"sakura\"");
                    String block = dependencyBlock(modsToml, "terrablender");
                    SakuraTestBase.assertTrue(helper, block.contains("mandatory=false"),
                            "TerraBlender must be optional, not a hard dependency");
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val content = srcDir.resolve("ResourceIntegrityTest.java").readText()
        assertTrue(content.contains("data/neoforge/loot_modifiers/global_loot_modifiers.json"))
        assertTrue(content.contains("META-INF/neoforge.mods.toml"))
        assertTrue(content.contains("""block.contains("type=\"optional\"")"""))
        assertTrue(content.contains("neoforge:loot_table_id"))
        assertTrue(content.contains("minecraft:short_grass"))
        assertTrue(!content.contains("data/forge/loot_modifiers/global_loot_modifiers.json"))
        assertTrue(!content.contains("META-INF/mods.toml"))
        assertTrue(!content.contains("mandatory=false"))
        assertTrue(!content.contains("\"forge:loot_table_id\""))
        assertTrue(!content.contains("minecraft:grass"))
    }

    @Test
    fun `recipe serializer registry key is only migrated in DeferredRegister create`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RecipeRegistry.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class RecipeRegistry {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
                        DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, "example");

                public static ResourceLocation key(RecipeSerializer<?> serializer) {
                    return BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val content = srcDir.resolve("RecipeRegistry.java").readText()
        assertTrue(content.contains("DeferredRegister.create(Registries.RECIPE_SERIALIZER, \"example\")"))
        assertTrue(content.contains("BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer)"))
        assertTrue(content.contains("import net.minecraft.core.registries.BuiltInRegistries;"))
        assertTrue(content.contains("import net.minecraft.core.registries.Registries;"))
    }

    @Test
    fun `migrates remaining FMLJavaModLoadingContext static getter calls`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("ExampleMod.java")
        file.writeText("""
            package com.example;

            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.config.ModConfig;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod(ModContainer modContainer) {
                    FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
                    ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, ExampleConfig.SPEC);
                }

                private void setup(Object event) {
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.changes.any { it.ruleId == "struct-fml-context-to-eventbus" })
        assertTrue(migrated.contains("public ExampleMod(IEventBus modEventBus, ModContainer modContainer)"))
        assertTrue(migrated.contains("modEventBus.addListener(this::setup);"))
        assertTrue(migrated.contains("modContainer.registerConfig(ModConfig.Type.SERVER, ExampleConfig.SPEC);"))
        assertTrue(!migrated.contains("FMLJavaModLoadingContext"))
        assertTrue(!migrated.contains("ModLoadingContext"))
    }

    @Test
    fun `migrates delegated FMLJavaModLoadingContext constructor to single NeoForge constructor`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("ExampleMod.java")
        file.writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.config.ModConfig;
            import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

            @Mod("example")
            public class ExampleMod {
                private final IEventBus modEventBus;

                @SuppressWarnings("removal")
                public ExampleMod(ModContainer modContainer) {
                    this(FMLJavaModLoadingContext.get());
                }

                public ExampleMod(FMLJavaModLoadingContext context, ModContainer modContainer) {
                    this.modEventBus = context.getModEventBus();
                    IEventBus modEventBus = this.modEventBus;
                    ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.COMMON, ExampleConfig.SPEC);
                    modEventBus.addListener(this::setup);
                }

                private void setup(Object event) {
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val migrated = file.readText()

        assertTrue(result.changes.any { it.ruleId == "struct-fml-context-to-eventbus" })
        assertTrue(migrated.contains("public ExampleMod(IEventBus modEventBus, ModContainer modContainer)"))
        assertTrue(!migrated.contains("public ExampleMod(ModContainer modContainer)"))
        assertTrue(!migrated.contains("FMLJavaModLoadingContext"))
        assertTrue(!migrated.contains("IEventBus modEventBus = this.modEventBus;"))
        assertTrue(migrated.contains("this.modEventBus = modEventBus;"))
        assertTrue(migrated.contains("modContainer.registerConfig(ModConfig.Type.COMMON, ExampleConfig.SPEC);"))
        assertTrue(migrated.contains("modEventBus.addListener(this::setup);"))
    }

    @Test
    fun `migrates bucket initCapabilities to RegisterCapabilitiesEvent item registration`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val itemsDir = srcDir.resolve("items")
        val registersDir = srcDir.resolve("registers")
        itemsDir.createDirectories()
        registersDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.registers.ItemRegister;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";

                public ExampleMod(IEventBus modEventBus) {
                    ItemRegister.register(modEventBus);
                }
            }
        """.trimIndent())

        registersDir.resolve("ItemRegister.java").writeText("""
            package com.example.registers;

            import com.example.items.BathBucketItem;
            import net.minecraft.world.item.Item;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ItemRegister {
                public static final DeferredRegister<Item> ITEMS = null;

                public static final DeferredHolder<Item, Item> HOT_WATER_BUCKET = ITEMS.register("hot_water_bucket",
                        () -> new BathBucketItem(FluidsRegister.HOT_WATER_FLUID, new Item.Properties().stacksTo(1)));

                public static final DeferredHolder<Item, Item> HONEY_BATH_BUCKET = ITEMS.register("honey_bath_bucket",
                        () -> new BathBucketItem(FluidsRegister.HONEY_BATH_FLUID, new Item.Properties().stacksTo(1)));

                public static void register(IEventBus eventBus) {
                    ITEMS.register(eventBus);
                }
            }
        """.trimIndent())

        itemsDir.resolve("BathBucketItem.java").writeText("""
            package com.example.items;

            import net.minecraft.world.item.BucketItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.material.Fluid;
            import java.util.function.Supplier;
            import org.jetbrains.annotations.Nullable;

            public class BathBucketItem extends BucketItem {
                public BathBucketItem(Supplier<? extends Fluid> supplier, Properties properties) {
                    super(supplier, properties);
                }

                @Override
                public net.neoforged.neoforge.capabilities.ICapabilityProvider initCapabilities(ItemStack stack, @Nullable net.minecraft.nbt.CompoundTag nbt) {
                    return new net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper(stack);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val bucket = itemsDir.resolve("BathBucketItem.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-fluid-bucket-item-capability" })
        assertTrue(result.changes.any { it.ruleId == "struct-fluid-bucket-capability-listener" })
        assertTrue(bucket.contains("super(supplier.get(), properties);"))
        assertTrue(bucket.contains("public static void registerCapabilities(RegisterCapabilitiesEvent event, ItemLike... items)"))
        assertTrue(bucket.contains("event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> new FluidBucketWrapper(stack), items);"))
        assertTrue(!bucket.contains("initCapabilities"))
        assertTrue(!bucket.contains("ICapabilityProvider"))
        assertTrue(mod.contains("import com.example.items.BathBucketItem;"))
        assertTrue(mod.contains("import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;"))
        assertTrue(mod.contains("modEventBus.addListener((RegisterCapabilitiesEvent event) -> BathBucketItem.registerCapabilities(event,"))
        assertTrue(mod.contains("ItemRegister.HOT_WATER_BUCKET.get()"))
        assertTrue(mod.contains("ItemRegister.HONEY_BATH_BUCKET.get()"))
    }

    @Test
    fun `migrates custom fluid item capabilities to RegisterCapabilitiesEvent`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val customDir = srcDir.resolve("custom_fluid")
        customDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.custom_fluid.CustomFluidItems;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod(IEventBus modEventBus) {
                    CustomFluidItems.register(modEventBus);
                }
            }
        """.trimIndent())

        customDir.resolve("CustomFluidItems.java").writeText("""
            package com.example.custom_fluid;

            import net.minecraft.world.item.Item;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class CustomFluidItems {
                public static final DeferredRegister<Item> ITEMS = null;
                public static final DeferredHolder<Item, CustomFluidBucketItem> CUSTOM_FLUID_BUCKET =
                        ITEMS.register("custom_fluid_bucket", () -> new CustomFluidBucketItem(new Item.Properties()));
                public static final DeferredHolder<Item, CustomFluidBottleItem> CUSTOM_FLUID_BOTTLE =
                        ITEMS.register("custom_fluid_bottle", () -> new CustomFluidBottleItem(new Item.Properties()));
                public static void register(IEventBus eventBus) {
                    ITEMS.register(eventBus);
                }
            }
        """.trimIndent())

        customDir.resolve("CustomFluidCapabilities.java").writeText("""
            package com.example.custom_fluid;

            import com.crabmod.hotbath.HotBath;
            import net.minecraft.core.Direction;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import com.modporter.compat.Capability;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import com.modporter.compat.LazyOptional;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.fluids.FluidStack;
            import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
            import org.jetbrains.annotations.Nullable;
            import javax.annotation.Nonnull;
            import net.neoforged.fml.common.EventBusSubscriber;

            @EventBusSubscriber(modid = HotBath.MOD_ID)
            public final class CustomFluidCapabilities {
                public static final ResourceLocation CAPABILITY_ID = ResourceLocation.fromNamespaceAndPath(HotBath.MOD_ID, "custom_fluid_container");

                private CustomFluidCapabilities() {
                }

                @SubscribeEvent
                public static void attachCapabilities(AttachCapabilitiesEvent<ItemStack> event) {
                    ItemStack stack = event.getObject();
                    if (stack.is(Items.BUCKET)) {
                        event.addCapability(CAPABILITY_ID, createProvider(stack, ContainerKind.BUCKET));
                    }
                }

                public static ICapabilityProvider createProvider(ItemStack stack, boolean bucket) {
                    return createProvider(stack, bucket ? ContainerKind.BUCKET : ContainerKind.BOTTLE);
                }

                private static ICapabilityProvider createProvider(ItemStack stack, ContainerKind kind) {
                    return new CustomFluidContainerProvider(new CustomFluidContainerHandler(stack, kind));
                }

                private enum ContainerKind {
                    BUCKET(1000),
                    BOTTLE(250);
                    final int amount;
                    ContainerKind(int amount) { this.amount = amount; }
                }

                private static final class CustomFluidContainerProvider implements ICapabilityProvider {
                    private final LazyOptional<IFluidHandlerItem> handler;
                    private CustomFluidContainerProvider(IFluidHandlerItem handler) {
                        this.handler = LazyOptional.of(() -> handler);
                    }
                    @Nonnull
                    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
                        if (cap == Capabilities.FLUID_HANDLER_ITEM) return handler.cast();
                        return LazyOptional.empty();
                    }
                }

                private static final class CustomFluidContainerHandler implements IFluidHandlerItem {
                    private ItemStack container;
                    private final ContainerKind kind;
                    private CustomFluidContainerHandler(ItemStack container, ContainerKind kind) {
                        this.container = container;
                        this.kind = kind;
                    }
                    public int getTanks() { return 1; }
                    public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
                    public int getTankCapacity(int tank) { return kind.amount; }
                    public boolean isFluidValid(int tank, FluidStack stack) { return true; }
                    public int fill(FluidStack resource, FluidAction action) { return 0; }
                    public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
                    public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
                    public ItemStack getContainer() { return container; }
                }
            }
        """.trimIndent())

        customDir.resolve("CustomFluidBucketItem.java").writeText("""
            package com.example.custom_fluid;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import org.jetbrains.annotations.Nullable;

            public class CustomFluidBucketItem extends Item {
                public CustomFluidBucketItem(Properties properties) { super(properties); }

                @Override
                public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
                    return CustomFluidCapabilities.createProvider(stack, true);
                }
            }
        """.trimIndent())

        customDir.resolve("CustomFluidBottleItem.java").writeText("""
            package com.example.custom_fluid;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import org.jetbrains.annotations.Nullable;

            public class CustomFluidBottleItem extends Item {
                public CustomFluidBottleItem(Properties properties) { super(properties); }

                @Override
                public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
                    return CustomFluidCapabilities.createProvider(stack, false);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val capabilities = customDir.resolve("CustomFluidCapabilities.java").readText()
        val bucket = customDir.resolve("CustomFluidBucketItem.java").readText()
        val bottle = customDir.resolve("CustomFluidBottleItem.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-custom-fluid-item-capabilities" })
        assertTrue(result.changes.any { it.ruleId == "struct-custom-fluid-item-initcapabilities" })
        assertTrue(result.changes.any { it.ruleId == "struct-custom-fluid-capability-listener" })
        assertTrue(capabilities.contains("public static void registerCapabilities(RegisterCapabilitiesEvent event)"))
        assertTrue(capabilities.contains("Capabilities.FluidHandler.ITEM"))
        assertTrue(capabilities.contains("CustomFluidItems.CUSTOM_FLUID_BUCKET.get()"))
        assertTrue(capabilities.contains("CustomFluidItems.CUSTOM_FLUID_BOTTLE.get()"))
        assertTrue(!capabilities.contains("AttachCapabilitiesEvent"))
        assertTrue(!capabilities.contains("ICapabilityProvider"))
        assertTrue(!capabilities.contains("LazyOptional"))
        assertTrue(!bucket.contains("initCapabilities"))
        assertTrue(!bottle.contains("initCapabilities"))
        assertTrue(mod.contains("modEventBus.addListener(CustomFluidCapabilities::registerCapabilities);"))
    }

    @Test
    fun `migrates Curios initCapabilities providers to RegisterCapabilitiesEvent item registration`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val itemDir = srcDir.resolve("item")
        val compatDir = srcDir.resolve("compat/curios")
        val registerDir = srcDir.resolve("init")
        itemDir.createDirectories()
        compatDir.createDirectories()
        registerDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.compat.curios.CuriosCompat;
            import com.example.init.ItemRegister;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.ModList;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            public class ExampleMod {
                public ExampleMod(ModContainer modContainer) {
                    IEventBus modbus = modContainer.getEventBus();
                    ItemRegister.ITEMS.register(modbus);
                    if (ModList.get().isLoaded("curios")) {
                        ExampleEvents.register();
                    }
                }
            }
        """.trimIndent())

        registerDir.resolve("ItemRegister.java").writeText("""
            package com.example.init;

            import com.example.item.CharmItem;
            import com.example.item.HeadItem;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ItemRegister {
                public static final DeferredRegister<Item> ITEMS = null;
                public static final DeferredHolder<Item, Item> CHARM =
                        ITEMS.register("charm", () -> new CharmItem(new Item.Properties()));
                public static final DeferredHolder<Item, Item> HEAD =
                        ITEMS.register("head", () -> new HeadItem(new Item.Properties()));
            }
        """.trimIndent())

        compatDir.resolve("CuriosCompat.java").writeText("""
            package com.example.compat.curios;

            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import net.neoforged.neoforge.network.PacketDistributor;
            import top.theillusivec4.curios.api.SlotContext;
            import top.theillusivec4.curios.api.type.capability.ICurio;
            import top.theillusivec4.curios.common.capability.CurioItemCapability;
            import com.example.network.ExamplePacket;
            import com.example.network.ExamplePacketHandler;
            import javax.annotation.Nonnull;

            public class CuriosCompat {
                public static ICapabilityProvider setupCuriosCapability(ItemStack stack) {
                    return CurioItemCapability.createProvider(new ICurio() {
                        @Override
                        public ItemStack getStack() {
                            return stack;
                        }

                        @Nonnull
                        @Override
                        public SoundInfo getEquipSound(SlotContext slotContext) {
                            return new SoundInfo(SoundEvents.ARMOR_EQUIP_GENERIC, 1.0F, 1.0F);
                        }

                        @Override
                        public void onEquip(SlotContext context, ItemStack prevStack) {
                            ExamplePacketHandler.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(context::entity), new ExamplePacket(context.entity().getId()));
                        }
                    });
                }
            }
        """.trimIndent())

        itemDir.resolve("CurioItem.java").writeText("""
            package com.example.item;

            import com.example.compat.curios.CuriosCompat;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.fml.ModList;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import org.jetbrains.annotations.Nullable;

            public interface CurioItem {
                @Nullable
                default ICapabilityProvider setupCurio(ItemStack stack, @Nullable ICapabilityProvider provider) {
                    if (ModList.get().isLoaded("curios")) {
                        return CuriosCompat.setupCuriosCapability(stack);
                    }
                    return provider;
                }
            }
        """.trimIndent())

        itemDir.resolve("CharmItem.java").writeText("""
            package com.example.item;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import org.jetbrains.annotations.Nullable;

            public class CharmItem extends Item implements CurioItem {
                public CharmItem(Properties properties) { super(properties); }

                @Nullable
                @Override
                public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
                    return this.setupCurio(stack, super.initCapabilities(stack, nbt));
                }
            }
        """.trimIndent())

        itemDir.resolve("HeadItem.java").writeText("""
            package com.example.item;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import org.jetbrains.annotations.Nullable;

            public class HeadItem extends Item implements CurioItem {
                public HeadItem(Properties properties) { super(properties); }

                @Nullable
                @Override
                public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
                    return this.setupCurio(stack, super.initCapabilities(stack, nbt));
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val compat = compatDir.resolve("CuriosCompat.java").readText()
        val marker = itemDir.resolve("CurioItem.java").readText()
        val charm = itemDir.resolve("CharmItem.java").readText()
        val head = itemDir.resolve("HeadItem.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-curios-item-capability" })
        assertTrue(result.changes.any { it.ruleId == "struct-curios-item-initcapabilities" })
        assertTrue(result.changes.any { it.ruleId == "struct-curios-item-capability-listener" })
        assertTrue(compat.contains("public static void registerCuriosCapabilities(RegisterCapabilitiesEvent event, ItemLike... items)"))
        assertTrue(compat.contains("event.registerItem(CuriosCapability.ITEM, (stack, context) -> new ICurio()"))
        assertTrue(compat.contains("new SoundInfo(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 1.0F, 1.0F)"))
        assertTrue(compat.contains("PacketDistributor.sendToPlayersTrackingEntityAndSelf(context.entity(), new ExamplePacket(context.entity().getId()));"))
        assertTrue(!compat.contains("CurioItemCapability"))
        assertTrue(!compat.contains("ICapabilityProvider setupCuriosCapability"))
        assertTrue(!compat.contains("CHANNEL.send"))
        assertTrue(!marker.contains("setupCurio"))
        assertTrue(!marker.contains("ModList"))
        assertTrue(!charm.contains("initCapabilities"))
        assertTrue(!charm.contains("ICapabilityProvider"))
        assertTrue(!head.contains("initCapabilities"))
        assertTrue(!head.contains("ICapabilityProvider"))
        assertTrue(mod.contains("modbus.addListener((RegisterCapabilitiesEvent event) -> CuriosCompat.registerCuriosCapabilities(event,"))
        assertTrue(mod.contains("ItemRegister.CHARM.get()"))
        assertTrue(mod.contains("ItemRegister.HEAD.get()"))
    }

    @Test
    fun `registers legacy recipe condition codecs through deferred register`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.common.crafting.CraftingHelper;
            import net.neoforged.neoforge.registries.RegisterEvent;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modbus = modContainer.getEventBus();
                    ExampleLoot.CONDITIONS.register(modbus);
                    modbus.addListener(this::registerExtraStuff);
                }

                public void registerExtraStuff(RegisterEvent evt) {
                    if (evt.getRegistryKey().equals(Registries.BIOME_SOURCE)) {
                        Registry.register(BuiltInRegistries.BIOME_SOURCE, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, "example"), null);
                    } else if (evt.getRegistryKey().equals(Registries.RECIPE_SERIALIZER)) {
                        CraftingHelper.register(ExampleCondition.Serializer.INSTANCE);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleLoot.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ExampleLoot {
                public static final DeferredRegister<LootItemConditionType> CONDITIONS =
                        DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, ExampleMod.MOD_ID);
            }
        """.trimIndent())
        srcDir.resolve("ExampleCondition.java").writeText("""
            package com.example;

            import com.mojang.serialization.MapCodec;
            import net.neoforged.neoforge.common.conditions.ICondition;

            public class ExampleCondition implements ICondition {
                public static final String CONDITION_ID = "example_enabled";
                public static final ExampleCondition INSTANCE = new ExampleCondition();
                public static final MapCodec<ExampleCondition> CODEC = MapCodec.unit(INSTANCE);

                @Override
                public MapCodec<? extends ICondition> codec() {
                    return CODEC;
                }

                @Override
                public boolean test(IContext context) {
                    return true;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        val mod = srcDir.resolve("ExampleMod.java").readText()
        val registry = srcDir.resolve("ExampleLoot.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-condition-codec-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "struct-condition-codec-main-register" })
        assertTrue(result.changes.any { it.ruleId == "struct-condition-codec-remove-craftinghelper-register" })
        assertTrue(registry.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(registry.contains("import net.neoforged.neoforge.common.conditions.ICondition;"))
        assertTrue(registry.contains("import net.neoforged.neoforge.registries.NeoForgeRegistries;"))
        assertTrue(registry.contains("DeferredRegister<MapCodec<? extends ICondition>> CONDITION_CODECS"))
        assertTrue(registry.contains("DeferredHolder<MapCodec<? extends ICondition>, MapCodec<ExampleCondition>> EXAMPLE_CONDITION"))
        assertTrue(registry.contains("CONDITION_CODECS.register(ExampleCondition.CONDITION_ID, () -> ExampleCondition.CODEC)"))
        assertTrue(mod.contains("ExampleLoot.CONDITION_CODECS.register(modbus);"))
        assertTrue(!mod.contains("CraftingHelper.register"))
        assertTrue(!mod.contains("common.crafting.CraftingHelper"))
        assertTrue(!mod.contains("Registries.RECIPE_SERIALIZER"))
    }

    @Test
    fun `migrates legacy item attribute modifier overrides to item attribute components`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";
            }
        """.trimIndent())
        srcDir.resolve("GiantSwordItem.java").writeText("""
            package com.example;

            import com.google.common.collect.ImmutableMultimap;
            import com.google.common.collect.Multimap;
            import java.util.UUID;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.minecraft.world.item.SwordItem;
            import net.minecraft.world.item.Tier;
            import net.neoforged.neoforge.common.NeoForgeMod;

            public class GiantSwordItem extends SwordItem {
                static final UUID REACH = UUID.fromString("00000000-0000-0000-0000-000000000001");
                static final UUID RANGE = UUID.fromString("00000000-0000-0000-0000-000000000002");

                public GiantSwordItem(Tier material, Properties properties) {
                    super(material, 10, -3.5F, properties);
                }

                @Override
                public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
                    ImmutableMultimap.Builder<Attribute, AttributeModifier> attributeBuilder = ImmutableMultimap.builder();
                    attributeBuilder.putAll(super.getDefaultAttributeModifiers(slot));
                    attributeBuilder.put(NeoForgeMod.BLOCK_REACH.get(), new AttributeModifier(REACH, "Reach modifier", 2.5, AttributeModifier.Operation.ADD_VALUE));
                    attributeBuilder.put(NeoForgeMod.ENTITY_REACH.get(), new AttributeModifier(RANGE, "Range modifier", 2.5, AttributeModifier.Operation.ADD_VALUE));
                    return slot == EquipmentSlot.MAINHAND ? attributeBuilder.build() : super.getDefaultAttributeModifiers(slot);
                }
            }
        """.trimIndent())
        srcDir.resolve("GiantPickItem.java").writeText("""
            package com.example;

            import com.google.common.collect.ImmutableMultimap;
            import com.google.common.collect.Multimap;
            import java.util.UUID;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.minecraft.world.item.PickaxeItem;
            import net.minecraft.world.item.Tier;
            import net.neoforged.neoforge.common.NeoForgeMod;

            public class GiantPickItem extends PickaxeItem {
                static final UUID REACH = UUID.fromString("00000000-0000-0000-0000-000000000001");
                static final UUID RANGE = UUID.fromString("00000000-0000-0000-0000-000000000002");

                public GiantPickItem(Tier material, Properties properties) {
                    super(material, properties.attributes(net.minecraft.world.item.DiggerItem.createAttributes(material, 8, -3.5F)));
                }

                @Override
                public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
                    ImmutableMultimap.Builder<Attribute, AttributeModifier> attributeBuilder = ImmutableMultimap.builder();
                    attributeBuilder.putAll(super.getDefaultAttributeModifiers(slot));
                    attributeBuilder.put(NeoForgeMod.BLOCK_REACH.get(), new AttributeModifier(REACH, "Reach modifier", 2.5, AttributeModifier.Operation.ADD_VALUE));
                    attributeBuilder.put(NeoForgeMod.ENTITY_REACH.get(), new AttributeModifier(RANGE, "Range modifier", 2.5, AttributeModifier.Operation.ADD_VALUE));
                    return slot == EquipmentSlot.MAINHAND ? attributeBuilder.build() : super.getDefaultAttributeModifiers(slot);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = srcDir.resolve("GiantSwordItem.java").readText()
        val migratedPick = srcDir.resolve("GiantPickItem.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-item-attribute-modifier-component" })
        assertTrue(migrated.contains("import net.minecraft.world.entity.EquipmentSlotGroup;"))
        assertTrue(migrated.contains("import net.minecraft.world.entity.ai.attributes.Attributes;"))
        assertTrue(migrated.contains("import net.minecraft.world.item.component.ItemAttributeModifiers;"))
        assertTrue(migrated.contains("super(material, properties.attributes(createGiantSwordItemAttributes(material, 10, -3.5F)));"))
        assertTrue(migrated.contains("public static ItemAttributeModifiers createGiantSwordItemAttributes(Tier tier, int damage, float speed)"))
        assertTrue(migrated.contains("SwordItem.createAttributes(tier, damage, speed)"))
        assertTrue(migrated.contains("Attributes.BLOCK_INTERACTION_RANGE"))
        assertTrue(migrated.contains("ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, \"reach_modifier\")"))
        assertTrue(!migrated.contains("getDefaultAttributeModifiers"))
        assertTrue(!migrated.contains("ImmutableMultimap"))
        assertTrue(!migrated.contains("Multimap<Attribute"))
        assertTrue(!migrated.contains("NeoForgeMod"))
        assertTrue(migratedPick.contains("super(material, properties.attributes(createGiantPickItemAttributes(material, 8, -3.5F)));"))
        assertTrue(migratedPick.contains("net.minecraft.world.item.DiggerItem.createAttributes(tier, damage, speed)"))
        assertTrue(!migratedPick.contains("getDefaultAttributeModifiers"))
        assertTrue(!migratedPick.contains("import net.minecraft.world.entity.ai.attributes.Attribute;"))
    }

    @Test
    fun `keeps attribute imports for generic multimap type references`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("AttributeMaps.java").writeText("""
            package com.example;

            import com.google.common.collect.ImmutableMultimap;
            import com.google.common.collect.Multimap;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.minecraft.world.entity.ai.attributes.Attributes;

            public class AttributeMaps {
                public Multimap<Attribute, AttributeModifier> modifiers() {
                    ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
                    builder.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("example", "damage"), 1.0, AttributeModifier.Operation.ADD_VALUE));
                    return builder.build();
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)
        val migrated = srcDir.resolve("AttributeMaps.java").readText()

        assertTrue(migrated.contains("import net.minecraft.world.entity.ai.attributes.Attribute;"), migrated)
        assertTrue(migrated.contains("Multimap<Attribute, AttributeModifier>"), migrated)
        assertTrue(migrated.contains("ImmutableMultimap.Builder<Attribute, AttributeModifier>"), migrated)
    }

    @Test
    fun `migrates dirtiness player capability to NeoForge attachment bridge`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val dirtinessDir = srcDir.resolve("dirtiness")
        val registersDir = srcDir.resolve("registers")
        dirtinessDir.createDirectories()
        registersDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.registers.EntityRegister;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";

                public ExampleMod(IEventBus modEventBus) {
                    EntityRegister.register(modEventBus);
                }
            }
        """.trimIndent())

        registersDir.resolve("EntityRegister.java").writeText("""
            package com.example.registers;

            import net.neoforged.bus.api.IEventBus;

            public class EntityRegister {
                public static void register(IEventBus eventBus) {
                }
            }
        """.trimIndent())

        dirtinessDir.resolve("DirtinessData.java").writeText("""
            package com.example.dirtiness;

            import net.minecraft.nbt.CompoundTag;

            public class DirtinessData {
                public CompoundTag serializeNBT() {
                    return new CompoundTag();
                }

                public void deserializeNBT(CompoundTag tag) {
                }
            }
        """.trimIndent())

        dirtinessDir.resolve("DirtinessCapability.java").writeText("""
            package com.example.dirtiness;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.neoforge.capabilities.ICapabilityProvider;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;
            import com.modporter.compat.CapabilityManager;
            import com.modporter.compat.CapabilityToken;
            import com.modporter.compat.LazyOptional;

            public class DirtinessCapability {
                public static final Object DIRTINESS = CapabilityManager.get(new CapabilityToken<DirtinessData>() {});

                public static LazyOptional<DirtinessData> get(Player player) {
                    return player.getCapability(DIRTINESS);
                }

                public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
                    event.addCapability(null, new DirtinessProvider());
                }

                public static class DirtinessProvider implements ICapabilityProvider {
                    private final DirtinessData data = new DirtinessData();
                    public CompoundTag serializeNBT() {
                        return data.serializeNBT();
                    }
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val bridge = dirtinessDir.resolve("DirtinessCapability.java").readText()
        val attachment = dirtinessDir.resolve("DirtinessAttachment.java").readText()
        val data = dirtinessDir.resolve("DirtinessData.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-dirtiness-attachment-bridge" })
        assertTrue(result.changes.any { it.ruleId == "struct-dirtiness-attachment-register" })
        assertTrue(result.changes.any { it.ruleId == "struct-dirtiness-data-attachment-serializable" })
        assertTrue(result.changes.any { it.ruleId == "struct-dirtiness-attachment-main-register" })
        assertTrue(bridge.contains("player.getData(DirtinessAttachment.DIRTINESS)"))
        assertTrue(!bridge.contains("ICapabilityProvider"))
        assertTrue(!bridge.contains("AttachCapabilitiesEvent"))
        assertTrue(!bridge.contains("CapabilityManager"))
        assertTrue(attachment.contains("DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, com.example.ExampleMod.MOD_ID)"))
        assertTrue(attachment.contains("AttachmentType.serializable(DirtinessData::new).copyOnDeath().build()"))
        assertTrue(data.contains("public class DirtinessData implements INBTSerializable<CompoundTag>"))
        assertTrue(data.contains("public CompoundTag serializeNBT(HolderLookup.Provider provider)"))
        assertTrue(data.contains("public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag)"))
        assertTrue(mod.contains("import com.example.dirtiness.DirtinessAttachment;"))
        assertTrue(mod.contains("DirtinessAttachment.register(modEventBus);"))
    }

    @Test
    fun `custom entity capability migration imports Direction for sided EntityCapability`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val capabilityDir = srcDir.resolve("capability")
        capabilityDir.createDirectories()

        capabilityDir.resolve("PlayerData.java").writeText("""
            package com.example.capability;

            import net.minecraft.world.entity.player.Player;

            public interface PlayerData {
                void setEntity(Player player);
            }
        """.trimIndent())

        capabilityDir.resolve("PlayerDataCapability.java").writeText("""
            package com.example.capability;

            public class PlayerDataCapability implements PlayerData {
                public void setEntity(net.minecraft.world.entity.player.Player player) {
                }
            }
        """.trimIndent())

        capabilityDir.resolve("ExampleCapabilities.java").writeText("""
            package com.example.capability;

            import com.modporter.compat.Capability;
            import com.modporter.compat.CapabilityManager;
            import com.modporter.compat.CapabilityToken;
            import net.minecraft.world.entity.Entity;
            import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;

            public class ExampleCapabilities {
                public static final Capability<PlayerData> PLAYER_DATA = CapabilityManager.get(new CapabilityToken<>() {});

                public static void registerCapabilities(RegisterCapabilitiesEvent event) {
                    event.register(PlayerData.class);
                }

                public static void attachCapabilities(AttachCapabilitiesEvent<Entity> event) {
                    event.addCapability(null, new PlayerDataCapability());
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val capabilities = capabilityDir.resolve("ExampleCapabilities.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-custom-entity-capabilities" })
        assertTrue(capabilities.contains("import net.minecraft.core.Direction;"), capabilities)
        assertTrue(capabilities.contains("EntityCapability<PlayerData, Direction> PLAYER_DATA"), capabilities)
        assertTrue(capabilities.contains("event.registerEntity(PLAYER_DATA"), capabilities)
    }

    @Test
    fun `custom capability migration derives entity providers and level attachments from attach events`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val capabilityDir = srcDir.resolve("capability")
        capabilityDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "examplemod";

                public ExampleMod(IEventBus modEventBus) {
                }
            }
        """.trimIndent())
        capabilityDir.resolve("PlayerData.java").writeText("""
            package com.example.capability;

            import com.modporter.generated.examplemod.compat.LazyOptional;
            import net.minecraft.world.entity.player.Player;

            public interface PlayerData {
                static LazyOptional<PlayerData> get(Player player) {
                    return LazyOptional.ofNullable(player.getCapability(ExampleCapabilities.PLAYER_DATA));
                }
            }
        """.trimIndent())
        capabilityDir.resolve("LevelData.java").writeText("""
            package com.example.capability;

            import com.modporter.generated.examplemod.compat.LazyOptional;
            import net.minecraft.world.level.Level;

            public interface LevelData {
                static LazyOptional<LevelData> get(Level level) {
                    return LazyOptional.ofNullable(level.getCapability(ExampleCapabilities.LEVEL_DATA));
                }
            }
        """.trimIndent())
        capabilityDir.resolve("PlayerDataCapability.java").writeText("""
            package com.example.capability;

            import net.minecraft.world.entity.player.Player;

            public class PlayerDataCapability implements PlayerData {
                public PlayerDataCapability(Player player) {
                }
            }
        """.trimIndent())
        capabilityDir.resolve("LevelDataCapability.java").writeText("""
            package com.example.capability;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            public class LevelDataCapability implements LevelData, net.neoforged.neoforge.common.util.INBTSerializable<CompoundTag> {
                public LevelDataCapability(Level level) {
                }

                public CompoundTag serializeNBT(HolderLookup.Provider provider) {
                    return new CompoundTag();
                }

                public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
                }
            }
        """.trimIndent())
        capabilityDir.resolve("ExampleCapabilities.java").writeText("""
            package com.example.capability;

            import com.example.ExampleMod;
            import com.example.compat.Capability;
            import com.example.compat.CapabilityManager;
            import com.example.compat.CapabilityToken;
            import com.example.compat.CapabilityProvider;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;

            public class ExampleCapabilities {
                public static final Capability<PlayerData> PLAYER_DATA = CapabilityManager.get(new CapabilityToken<>() {});
                public static final Capability<LevelData> LEVEL_DATA = CapabilityManager.get(new CapabilityToken<>() {});

                @SubscribeEvent
                public static void register(RegisterCapabilitiesEvent event) {
                    event.register(PlayerData.class);
                    event.register(LevelData.class);
                }

                public static void attachEntityCapabilities(AttachCapabilitiesEvent<Entity> event) {
                    if (event.getObject() instanceof LivingEntity livingEntity) {
                        if (livingEntity instanceof Player player) {
                            event.addCapability(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "player_data"), new CapabilityProvider(ExampleCapabilities.PLAYER_DATA, new PlayerDataCapability(player)));
                        }
                    }
                }

                public static void attachLevelCapabilities(AttachCapabilitiesEvent<Level> event) {
                    event.addCapability(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "level_data"), new CapabilityProvider(ExampleCapabilities.LEVEL_DATA, new LevelDataCapability(event.getObject())));
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val capabilities = capabilityDir.resolve("ExampleCapabilities.java").readText()
        val playerData = capabilityDir.resolve("PlayerData.java").readText()
        val levelData = capabilityDir.resolve("LevelData.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-custom-entity-capabilities" })
        assertTrue(result.changes.any { it.ruleId == "struct-level-capability-attachment-main-register" })
        assertTrue(capabilities.contains("EntityCapability<PlayerData, Direction> PLAYER_DATA"), capabilities)
        assertTrue(capabilities.contains("Supplier<AttachmentType<LevelData>> LEVEL_DATA"), capabilities)
        assertTrue(capabilities.contains("DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES"), capabilities)
        assertTrue(capabilities.contains("AttachmentType.serializable(holder -> new LevelDataCapability((Level) holder)).build()"), capabilities)
        assertTrue(capabilities.contains("new PlayerDataCapability(player)"), capabilities)
        assertTrue(capabilities.contains("if (!(entity instanceof Player player)) return null;"), capabilities)
        assertFalse(capabilities.contains("AttachCapabilitiesEvent"))
        assertFalse(capabilities.contains("CapabilityProvider"))
        assertFalse(Regex("""@SubscribeEvent\s+public static void registerAttachments""").containsMatchIn(capabilities.replace("\r\n", "\n")), capabilities)
        assertFalse(capabilities.contains("event.register(PlayerData.class)"))
        assertTrue(playerData.contains("player.getCapability(ExampleCapabilities.PLAYER_DATA, null)"), playerData)
        assertTrue(levelData.contains("level.getData(ExampleCapabilities.LEVEL_DATA.get())"), levelData)
        assertTrue(mod.contains("ExampleCapabilities.registerAttachments(modEventBus);"), mod)
    }

    @Test
    fun `serializable attached entity capabilities migrate to attachments and Nitrogen sync API`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val capabilityDir = srcDir.resolve("capability")
        val networkDir = srcDir.resolve("network/packet")
        capabilityDir.createDirectories()
        networkDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "examplemod";

                public ExampleMod(IEventBus modEventBus) {
                }
            }
        """.trimIndent())
        capabilityDir.resolve("SynchedData.java").writeText("""
            package com.example.capability;

            import com.aetherteam.nitrogen.capability.INBTSynchable;
            import com.modporter.generated.examplemod.compat.LazyOptional;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;

            public interface SynchedData extends INBTSynchable<CompoundTag> {
                static LazyOptional<SynchedData> get(Player player) {
                    return LazyOptional.ofNullable(player.getCapability(ExampleCapabilities.PLAYER_DATA));
                }
            }
        """.trimIndent())
        capabilityDir.resolve("SynchedDataCapability.java").writeText("""
            package com.example.capability;

            import com.aetherteam.nitrogen.capability.INBTSynchable;
            import com.aetherteam.nitrogen.network.BasePacket;
            import com.example.network.ExamplePacketHandler;
            import com.example.network.packet.SynchedDataSyncPacket;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraftforge.network.simple.SimpleChannel;
            import org.apache.commons.lang3.tuple.Triple;
            import java.util.Map;
            import java.util.function.Consumer;
            import java.util.function.Supplier;

            public class SynchedDataCapability implements SynchedData {
                private final Player player;
                private final Map<String, Triple<Type, Consumer<Object>, Supplier<Object>>> functions = Map.of();

                public SynchedDataCapability(Player player) {
                    this.player = player;
                }

                public Player getPlayer() {
                    return this.player;
                }

                public void update(boolean value) {
                    this.setSynched(INBTSynchable.Direction.CLIENT, "setValue", value);
                }

                public CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider provider) {
                    return new CompoundTag();
                }

                public void deserializeNBT(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
                }

                public Map<String, Triple<Type, Consumer<Object>, Supplier<Object>>> getSynchableFunctions() {
                    return this.functions;
                }

                public BasePacket getSyncPacket(String key, Type type, Object value) {
                    return new SynchedDataSyncPacket(key, type, value);
                }

                public SimpleChannel getPacketChannel() {
                    return ExamplePacketHandler.INSTANCE;
                }
            }
        """.trimIndent())
        capabilityDir.resolve("ExampleCapabilities.java").writeText("""
            package com.example.capability;

            import com.example.ExampleMod;
            import com.example.compat.Capability;
            import com.example.compat.CapabilityManager;
            import com.example.compat.CapabilityProvider;
            import com.example.compat.CapabilityToken;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;

            @EventBusSubscriber(modid = ExampleMod.MOD_ID)
            public class ExampleCapabilities {
                public static final Capability<SynchedData> PLAYER_DATA = CapabilityManager.get(new CapabilityToken<>() {});

                @SubscribeEvent
                public static void register(RegisterCapabilitiesEvent event) {
                    event.register(SynchedData.class);
                }

                public static void attachEntityCapabilities(AttachCapabilitiesEvent<Entity> event) {
                    if (event.getObject() instanceof Player player) {
                        event.addCapability(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "player_data"), new CapabilityProvider(ExampleCapabilities.PLAYER_DATA, new SynchedDataCapability(player)));
                    }
                }
            }
        """.trimIndent())
        networkDir.resolve("SynchedDataSyncPacket.java").writeText("""
            package com.example.network.packet;

            import com.aetherteam.nitrogen.capability.INBTSynchable;
            import com.aetherteam.nitrogen.network.packet.SyncEntityPacket;
            import com.example.capability.SynchedData;
            import net.minecraft.network.FriendlyByteBuf;
            import oshi.util.tuples.Quartet;

            public class SynchedDataSyncPacket extends SyncEntityPacket<SynchedData> {
                public SynchedDataSyncPacket(Quartet<Integer, String, INBTSynchable.Type, Object> values) {
                    super(values);
                }

                public SynchedDataSyncPacket(int playerID, String key, INBTSynchable.Type type, Object value) {
                    super(playerID, key, type, value);
                }

                public static SynchedDataSyncPacket decode(FriendlyByteBuf buf) {
                    return new SynchedDataSyncPacket(SyncEntityPacket.decodeEntityValues(buf));
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val capabilities = capabilityDir.resolve("ExampleCapabilities.java").readText()
        val api = capabilityDir.resolve("SynchedData.java").readText()
        val impl = capabilityDir.resolve("SynchedDataCapability.java").readText()
        val packet = networkDir.resolve("SynchedDataSyncPacket.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-custom-entity-capabilities" })
        assertTrue(result.changes.any { it.ruleId == "struct-nitrogen-attachment-api" })
        assertTrue(capabilities.contains("Supplier<AttachmentType<SynchedData>> PLAYER_DATA"), capabilities)
        assertTrue(capabilities.contains("AttachmentType.serializable(holder -> new SynchedDataCapability((Player) holder)).build()"), capabilities)
        assertFalse(capabilities.contains("EntityCapability<SynchedData"), capabilities)
        assertFalse(capabilities.contains("RegisterCapabilitiesEvent"), capabilities)
        assertFalse(capabilities.contains("@SubscribeEvent"), capabilities)
        assertFalse(capabilities.contains("@EventBusSubscriber"), capabilities)
        assertTrue(api.contains("import com.aetherteam.nitrogen.attachment.INBTSynchable;"), api)
        assertTrue(api.contains("import net.neoforged.neoforge.common.util.INBTSerializable;"), api)
        assertTrue(api.contains("extends INBTSynchable, INBTSerializable<CompoundTag>"), api)
        assertTrue(api.contains("player.getData(ExampleCapabilities.PLAYER_DATA.get())"), api)
        assertTrue(impl.contains("public SyncPacket getSyncPacket(int entityID, String key, Type type, Object value)"), impl)
        assertTrue(impl.contains("new SynchedDataSyncPacket(entityID, key, type, value)"), impl)
        assertTrue(impl.contains("this.setSynched(this.getPlayer().getId(), INBTSynchable.Direction.CLIENT, \"setValue\", value);"), impl)
        assertFalse(impl.contains("getPacketChannel"), impl)
        assertFalse(impl.contains("BasePacket"), impl)
        assertTrue(packet.contains("import com.aetherteam.nitrogen.attachment.INBTSynchable;"), packet)
        assertTrue(packet.contains("RegistryFriendlyByteBuf buf"), packet)
        assertTrue(mod.contains("ExampleCapabilities.registerAttachments(modEventBus);"), mod)
    }

    @Test
    fun `attachment registration helper is not treated as subscribe event`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val capabilityDir = srcDir.resolve("capability")
        capabilityDir.createDirectories()

        capabilityDir.resolve("ExampleCapabilities.java").writeText("""
            package com.example.capability;

            import com.example.ExampleMod;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.attachment.AttachmentType;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import net.neoforged.neoforge.registries.NeoForgeRegistries;

            @EventBusSubscriber(modid = ExampleMod.MOD_ID)
            public class ExampleCapabilities {
                public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ExampleMod.MOD_ID);

                @SubscribeEvent

                public static void registerAttachments(IEventBus modEventBus) {
                    ATTACHMENT_TYPES.register(modEventBus);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val capabilities = capabilityDir.resolve("ExampleCapabilities.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-attachment-registration-event-cleanup" })
        assertTrue(capabilities.contains("public static void registerAttachments(IEventBus modEventBus)"), capabilities)
        assertFalse(capabilities.contains("@SubscribeEvent"), capabilities)
        assertFalse(capabilities.contains("@EventBusSubscriber"), capabilities)
        assertFalse(capabilities.contains("net.neoforged.bus.api.SubscribeEvent"), capabilities)
        assertFalse(capabilities.contains("net.neoforged.fml.common.EventBusSubscriber"), capabilities)
        assertFalse(capabilities.contains("net.neoforged.fml.common.Mod"), capabilities)
    }

    @Test
    fun `migrates legacy advancement triggers to codec DeferredRegister API`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val advancementDir = srcDir.resolve("advancements")
        val registersDir = srcDir.resolve("registers")
        advancementDir.createDirectories()
        registersDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.registers.EntityRegister;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";

                public ExampleMod(IEventBus modEventBus) {
                    EntityRegister.register(modEventBus);
                }
            }
        """.trimIndent())

        registersDir.resolve("EntityRegister.java").writeText("""
            package com.example.registers;

            import net.neoforged.bus.api.IEventBus;

            public class EntityRegister {
                public static void register(IEventBus eventBus) {
                }
            }
        """.trimIndent())

        advancementDir.resolve("AdvancementTrigger.java").writeText("""
            package com.example.advancements;

            import com.google.gson.JsonObject;
            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.CriterionTriggerInstance;
            import net.minecraft.advancements.critereon.SerializationContext;
            import net.minecraft.resources.ResourceLocation;

            public class AdvancementTrigger implements CriterionTrigger<AdvancementTrigger.Instance> {
                public ResourceLocation getId() {
                    return null;
                }

                public Instance createInstance(JsonObject json, DeserializationContext context) {
                    return new Instance();
                }

                public static class Instance implements CriterionTriggerInstance {
                    public JsonObject serializeToJson(SerializationContext context) {
                        return new JsonObject();
                    }
                }
            }
        """.trimIndent())

        registersDir.resolve("ExtraEventsRegister.java").writeText("""
            package com.example.registers;

            import com.example.advancements.AdvancementTrigger;
            import net.minecraft.advancements.CriteriaTriggers;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            public class ExtraEventsRegister {
                @SubscribeEvent
                public static void registerAdvancementTrigger(FMLCommonSetupEvent event) {
                    event.enqueueWork(() -> {
                        CriteriaTriggers.register(new AdvancementTrigger("example", "foot_health"));
                        CriteriaTriggers.register(new AdvancementTrigger("example", "milk_skin"));
                    });
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val trigger = advancementDir.resolve("AdvancementTrigger.java").readText()
        val registrar = registersDir.resolve("ExtraEventsRegister.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-advancement-trigger-codec" })
        assertTrue(result.changes.any { it.ruleId == "struct-advancement-trigger-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "struct-advancement-trigger-main-register" })
        assertTrue(trigger.contains("public @NotNull Codec<Instance> codec()"))
        assertTrue(trigger.contains("implements CriterionTrigger<AdvancementTrigger.Instance>"))
        assertTrue(!trigger.contains("SerializationContext"))
        assertTrue(!trigger.contains("DeserializationContext"))
        assertTrue(!trigger.contains("serializeToJson"))
        assertTrue(registrar.contains("DeferredRegister<CriterionTrigger<?>> TRIGGERS"))
        assertTrue(registrar.contains("TRIGGERS.register(\"foot_health\", () -> new AdvancementTrigger(\"example\", \"foot_health\"))"))
        assertTrue(registrar.contains("TRIGGERS.register(\"milk_skin\", () -> new AdvancementTrigger(\"example\", \"milk_skin\"))"))
        assertTrue(!registrar.contains("CriteriaTriggers"))
        assertTrue(mod.contains("import com.example.registers.ExtraEventsRegister;"))
        assertTrue(mod.contains("ExtraEventsRegister.register(modEventBus);"))
    }

    @Test
    fun `migrates SimpleCriterionTrigger classes and CriteriaTriggers registry fields`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val advancementDir = srcDir.resolve("advancements")
        val blockDir = srcDir.resolve("block")
        advancementDir.createDirectories()
        blockDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modbus = modContainer.getEventBus();
                }
            }
        """.trimIndent())

        advancementDir.resolve("TFAdvancements.java").writeText("""
            package com.example.advancements;

            import net.minecraft.advancements.CriteriaTriggers;

            public class TFAdvancements {
                public static final ActivateGhastTrapTrigger ACTIVATED_GHAST_TRAP = CriteriaTriggers.register(new ActivateGhastTrapTrigger());

                public static void init() {}
            }
        """.trimIndent())

        advancementDir.resolve("ActivateGhastTrapTrigger.java").writeText("""
            package com.example.advancements;

            import com.google.gson.JsonObject;
            import net.minecraft.advancements.critereon.*;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerPlayer;
            import com.example.ExampleMod;

            public class ActivateGhastTrapTrigger extends SimpleCriterionTrigger<ActivateGhastTrapTrigger.Instance> {
                public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "activate_ghast_trap");

                @Override
                public ResourceLocation getId() {
                    return ID;
                }

                @Override
                protected ActivateGhastTrapTrigger.Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext ctx) {
                    return new ActivateGhastTrapTrigger.Instance(player);
                }

                public void trigger(ServerPlayer player) {
                    this.trigger(player, instance -> true);
                }

                public static class Instance extends AbstractCriterionTriggerInstance {
                    public Instance(ContextAwarePredicate player) {
                        super(ActivateGhastTrapTrigger.ID, player);
                    }

                    public static Instance activateTrap() {
                        return new Instance(ContextAwarePredicate.ANY);
                    }
                }
            }
        """.trimIndent())

        blockDir.resolve("GhastTrapBlock.java").writeText("""
            package com.example.block;

            import com.example.advancements.TFAdvancements;
            import net.minecraft.server.level.ServerPlayer;

            public class GhastTrapBlock {
                public void award(ServerPlayer player) {
                    TFAdvancements.ACTIVATED_GHAST_TRAP.trigger(player);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val registry = advancementDir.resolve("TFAdvancements.java").readText()
        val trigger = advancementDir.resolve("ActivateGhastTrapTrigger.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()
        val block = blockDir.resolve("GhastTrapBlock.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-criterion-trigger-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "struct-simple-criterion-trigger-codec" })
        assertTrue(result.changes.any { it.ruleId == "struct-criterion-trigger-holder-get" })
        assertTrue(result.changes.any { it.ruleId == "struct-criterion-trigger-main-register" })
        assertTrue(registry.contains("DeferredRegister<CriterionTrigger<?>> TRIGGERS"))
        assertTrue(registry.contains("DeferredHolder<CriterionTrigger<?>, ActivateGhastTrapTrigger> ACTIVATED_GHAST_TRAP"))
        assertTrue(registry.contains("TRIGGERS.register(\"activate_ghast_trap\", ActivateGhastTrapTrigger::new)"))
        assertTrue(!registry.contains("CriteriaTriggers"))
        assertTrue(trigger.contains("public record Instance(Optional<ContextAwarePredicate> player) implements SimpleInstance"))
        assertTrue(trigger.contains("public Codec<ActivateGhastTrapTrigger.Instance> codec()"))
        assertTrue(trigger.contains("public static Criterion<ActivateGhastTrapTrigger.Instance> activateTrap()"))
        assertTrue(!trigger.contains("AbstractCriterionTriggerInstance"))
        assertTrue(!trigger.contains("DeserializationContext"))
        assertTrue(mod.contains("import com.example.advancements.TFAdvancements;"))
        assertTrue(mod.contains("TFAdvancements.TRIGGERS.register(modbus);"))
        assertTrue(block.contains("TFAdvancements.ACTIVATED_GHAST_TRAP.get().trigger(player);"))
    }

    @Test
    fun `migrates singleton registered item SimpleCriterionTrigger without hardcoded factory names`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val advancementDir = srcDir.resolve("advancement")
        advancementDir.createDirectories()

        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.advancement.ExampleAdvancementTriggers;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MOD_ID)
            public class ExampleMod {
                public static final String MOD_ID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                }

                public void commonSetup() {
                    ExampleAdvancementTriggers.init();
                }
            }
        """.trimIndent())

        advancementDir.resolve("ExampleAdvancementTriggers.java").writeText("""
            package com.example.advancement;

            import net.minecraft.advancements.CriteriaTriggers;

            public class ExampleAdvancementTriggers {
                public static void init() {
                    CriteriaTriggers.register(IncubationTrigger.INSTANCE);
                }
            }
        """.trimIndent())

        advancementDir.resolve("IncubationTrigger.java").writeText("""
            package com.example.advancement;

            import com.example.ExampleMod;
            import com.google.gson.JsonObject;
            import net.minecraft.advancements.critereon.*;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.item.ItemStack;

            public class IncubationTrigger extends SimpleCriterionTrigger<IncubationTrigger.Instance> {
                private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "incubation_trigger");
                public static final IncubationTrigger INSTANCE = new IncubationTrigger();

                @Override
                public ResourceLocation getId() {
                    return ID;
                }

                @Override
                public IncubationTrigger.Instance createInstance(JsonObject json, ContextAwarePredicate predicate, DeserializationContext context) {
                    ItemPredicate itemPredicate = ItemPredicate.fromJson(json.get("item"));
                    return new IncubationTrigger.Instance(predicate, itemPredicate);
                }

                public void trigger(ServerPlayer player, ItemStack stack) {
                    this.trigger(player, instance -> instance.test(stack));
                }

                public static class Instance extends SimpleCriterionTrigger.SimpleInstance {
                    private final ItemPredicate item;

                    public Instance(ContextAwarePredicate predicate, ItemPredicate item) {
                        super(IncubationTrigger.ID, predicate);
                        this.item = item;
                    }

                    public static IncubationTrigger.Instance forItem(ItemPredicate item) {
                        return new IncubationTrigger.Instance(ContextAwarePredicate.ANY, item);
                    }

                    public static IncubationTrigger.Instance forAny() {
                        return forItem(ItemPredicate.ANY);
                    }

                    public boolean test(ItemStack stack) {
                        return this.item.test(stack);
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val registry = advancementDir.resolve("ExampleAdvancementTriggers.java").readText()
        val trigger = advancementDir.resolve("IncubationTrigger.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-criterion-trigger-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "struct-simple-criterion-trigger-codec" })
        assertTrue(registry.contains("DeferredRegister<CriterionTrigger<?>> TRIGGERS"), registry)
        assertTrue(registry.contains("DeferredHolder<CriterionTrigger<?>, IncubationTrigger> INCUBATION_TRIGGER"), registry)
        assertTrue(registry.contains("TRIGGERS.register(\"incubation_trigger\", IncubationTrigger::new)"), registry)
        assertTrue(!registry.contains("CriteriaTriggers"), registry)
        assertTrue(!registry.contains("public static void init"), registry)
        assertTrue(trigger.contains("public static Criterion<IncubationTrigger.Instance> forItem(ItemPredicate item)"), trigger)
        assertTrue(trigger.contains("public static Criterion<IncubationTrigger.Instance> forAny()"), trigger)
        assertTrue(trigger.contains("this.item.get().test(stack)"), trigger)
        assertTrue(!trigger.contains("uncraftedItem"), trigger)
        assertTrue(!trigger.contains("ItemPredicate.fromJson"), trigger)
        assertTrue(mod.contains("ExampleAdvancementTriggers.TRIGGERS.register(modEventBus);"), mod)
        assertTrue(!mod.contains("ExampleAdvancementTriggers.init();"), mod)
    }

    @Test
    fun `migrates transparent block beacon color and legacy plant APIs`() {
        val blockDir = tempDir.resolve("src/main/java/com/example/block")
        blockDir.createDirectories()

        blockDir.resolve("AuroralizedGlassBlock.java").writeText("""
            package com.example.block;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.LevelReader;
            import net.minecraft.world.level.block.AbstractGlassBlock;
            import net.minecraft.world.level.block.state.BlockState;

            public class AuroralizedGlassBlock extends AbstractGlassBlock {
                public AuroralizedGlassBlock(Properties properties) {
                    super(properties);
                }

                @Override
                public float[] getBeaconColorMultiplier(BlockState state, LevelReader level, BlockPos pos, BlockPos beaconPos) {
                    int color = ColorUtil.hsvToRGB(Noise.sample(pos), 1.0f, 1.0f);
                    int red = (color & 16711680) >> 16;
                    int green = (color & 65280) >> 8;
                    int blue = (color & 255);
                    return new float[] {red / 255.0F, green / 255.0F, blue / 255.0F};
                }
            }
        """.trimIndent())

        blockDir.resolve("UberousSoilBlock.java").writeText("""
            package com.example.block;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.tags.BlockTags;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.common.IPlantable;
            import net.neoforged.neoforge.common.PlantType;

            public class UberousSoilBlock extends Block {
                public UberousSoilBlock(Properties properties) {
                    super(properties);
                }

                @Override
                public PlantType getPlantType(BlockGetter getter, BlockPos pos) {
                    return PlantType.PLAINS;
                }

                @Override
                public boolean canSustainPlant(BlockState state, BlockGetter getter, BlockPos pos, Direction direction, IPlantable plantable) {
                    if (direction != Direction.UP) return false;
                    PlantType plantType = plantable.getPlantType(getter, pos.relative(direction));
                    return plantType == PlantType.CROP || plantType == PlantType.PLAINS;
                }

                public boolean convert(BlockState above, Object bonemealableBlock, net.minecraft.world.level.Level level, BlockPos fromPos) {
                    return bonemealableBlock instanceof IPlantable iPlantable && iPlantable.getPlantType(level, fromPos) == PlantType.CROP;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val glass = blockDir.resolve("AuroralizedGlassBlock.java").readText()
        val soil = blockDir.resolve("UberousSoilBlock.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-transparent-block-beacon-color" })
        assertTrue(result.changes.any { it.ruleId == "struct-legacy-plant-api" })
        assertTrue(glass.contains("import net.minecraft.world.level.block.TransparentBlock;"))
        assertTrue(glass.contains("extends TransparentBlock"))
        assertTrue(glass.contains("public Integer getBeaconColorMultiplier"))
        assertTrue(glass.contains("return ColorUtil.hsvToRGB(Noise.sample(pos), 1.0f, 1.0f);"))
        assertTrue(!glass.contains("float[] getBeaconColorMultiplier"))
        assertTrue(soil.contains("public TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos soilPosition, Direction facing, BlockState plant)"))
        assertTrue(soil.contains("if (plant.is(BlockTags.CROPS)) return TriState.TRUE;"))
        assertTrue(soil.contains("above.is(BlockTags.CROPS)"))
        assertTrue(!soil.contains("getPlantType("))
        assertTrue(!soil.contains("IPlantable"))
        assertTrue(!soil.contains("PlantType"))
    }

    @Test
    fun `migrates DistExecutor block body to FMLLoader guard`() {
        val projectDir = createFile("ClientPacket.java", """
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.fml.DistExecutor;

            public class ClientPacket {
                static void handle(ClientPacket packet, Context context) {
                    context.enqueueWork(() -> {
                        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                            ClientHooks.sync(packet);
                            Logger.debug("synced");
                        });
                    });
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = projectDir
            .resolve("src/main/java/com/example/ClientPacket.java")
            .readText()
        val distChange = result.changes.single { it.ruleId == "struct-dist-executor-dist-guard" }

        assertEquals(Confidence.HIGH, distChange.confidence)
        assertTrue(migrated.contains("import net.neoforged.fml.loading.FMLLoader;"))
        assertTrue(migrated.contains("if (FMLLoader.getDist() == Dist.CLIENT) {"))
        assertTrue(migrated.contains("ClientHooks.sync(packet);"))
        assertTrue(!migrated.contains("DistExecutor"))
        assertTrue(result.changes.none { it.ruleId == "struct-dist-executor" })
    }

    @Test
    fun `migrates DistExecutor expression body enqueueWork to block guard`() {
        val projectDir = createFile("MenuPacket.java", """
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            // [forge2neo] import net.neoforged.fml.DistExecutor;
            // DistExecutor removed in NeoForge

            public class MenuPacket {
                static void handle(MenuPacket packet, Context context) {
                    context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                            Dist.CLIENT,
                            () -> () -> ClientMenu.open(packet)
                    ));
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = projectDir
            .resolve("src/main/java/com/example/MenuPacket.java")
            .readText()
        val distChange = result.changes.single { it.ruleId == "struct-dist-executor-dist-guard" }

        assertEquals(Confidence.HIGH, distChange.confidence)
        assertTrue(migrated.contains("context.enqueueWork(() -> {"))
        assertTrue(migrated.contains("if (FMLLoader.getDist() == Dist.CLIENT) {"))
        assertTrue(migrated.contains("ClientMenu.open(packet);"))
        assertTrue(!migrated.contains("DistExecutor"))
        assertTrue(!migrated.contains("DistExecutor removed in NeoForge"))
        assertTrue(result.changes.none { it.ruleId == "struct-dist-executor" })
    }

    @Test
    fun `migrates DistExecutor runForDist client block statement to dist guard`() {
        val projectDir = createFile("ClientRegistration.java", """
            package com.example;

            import net.neoforged.fml.DistExecutor;

            public class ClientRegistration {
                public ClientRegistration(EventBus bus) {
                    DistExecutor.unsafeRunForDist(() -> () -> {
                        ClientMenus.register(bus);
                        ClientTabs.register(bus);
                        return true;
                    }, () -> () -> false);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = projectDir
            .resolve("src/main/java/com/example/ClientRegistration.java")
            .readText()

        assertTrue(result.changes.any { it.ruleId == "struct-dist-executor-dist-guard" })
        assertTrue(migrated.contains("import net.neoforged.api.distmarker.Dist;"))
        assertTrue(migrated.contains("import net.neoforged.fml.loading.FMLLoader;"))
        assertTrue(migrated.contains("if (FMLLoader.getDist() == Dist.CLIENT) {"))
        assertTrue(migrated.contains("ClientMenus.register(bus);"))
        assertTrue(migrated.contains("ClientTabs.register(bus);"))
        assertTrue(!migrated.contains("DistExecutor"))
        assertTrue(!migrated.contains("return true;"))
    }

    @Test
    fun `BaseEntityBlock codec is high confidence with Properties constructor`() {
        val projectDir = createFile("LightBlock.java", """
            package com.example;

            import net.minecraft.world.level.block.BaseEntityBlock;

            public class LightBlock extends BaseEntityBlock {
                public LightBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = projectDir
            .resolve("src/main/java/com/example/LightBlock.java")
            .readText()
        val codecChange = result.changes.single { it.ruleId == "struct-base-entity-block-codec" }

        assertEquals(Confidence.HIGH, codecChange.confidence)
        assertTrue(migrated.contains("MapCodec<LightBlock> CODEC = simpleCodec(LightBlock::new);"))
        assertTrue(migrated.contains("protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec()"))
    }

    @Test
    fun `block codec generation honors crop and bush parent codec types`() {
        val projectDir = createFile("RiceCrop.java", """
            package com.example;

            import net.minecraft.world.level.block.CropBlock;

            public class RiceCrop extends CropBlock {
                public RiceCrop(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.resolve("BambooShoot.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.BushBlock;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class BambooShoot extends BushBlock {
                public BambooShoot() {
                    super(BlockBehaviour.Properties.ofFullCopy(Blocks.BAMBOO_SAPLING));
                }
            }
        """.trimIndent())
        srcDir.resolve("BambooBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.RotatedPillarBlock;

            public class BambooBlock extends RotatedPillarBlock {
                public BambooBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val crop = srcDir.resolve("RiceCrop.java").readText()
        val bush = srcDir.resolve("BambooShoot.java").readText()
        val pillar = srcDir.resolve("BambooBlock.java").readText()

        assertTrue(crop.contains("public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.CropBlock> codec()"))
        assertTrue(bush.contains("public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BushBlock> codec()"))
        assertTrue(bush.contains("public BambooShoot(net.minecraft.world.level.block.state.BlockBehaviour.Properties properties)"))
        assertTrue(bush.contains("super(properties);"))
        assertTrue(pillar.contains("public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.RotatedPillarBlock> codec()"))
    }

    @Test
    fun `block codec generation uses vanilla parent visibility and generic bounds`() {
        val projectDir = createFile("AerogelSlab.java", """
            package com.example;

            import net.minecraft.world.level.block.SlabBlock;

            public class AerogelSlab extends SlabBlock {
                public AerogelSlab(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.resolve("AerogelWall.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.WallBlock;

            public class AerogelWall extends WallBlock {
                public AerogelWall(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("QuickPane.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.IronBarsBlock;

            public class QuickPane extends IronBarsBlock {
                public QuickPane(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("AetherGrass.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.GrassBlock;

            public class AetherGrass extends GrassBlock {
                public AetherGrass(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("EnchantedGrass.java").writeText("""
            package com.example;

            public class EnchantedGrass extends AetherGrass {
                public EnchantedGrass(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("AetherFarm.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.FarmBlock;

            public class AetherFarm extends FarmBlock {
                public AetherFarm(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("AetherPath.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.DirtPathBlock;

            public class AetherPath extends DirtPathBlock {
                public AetherPath(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("AetherIce.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.FrostedIceBlock;

            public class AetherIce extends FrostedIceBlock {
                public AetherIce(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("SkyBed.java").writeText("""
            package com.example;

            import net.minecraft.world.item.DyeColor;
            import net.minecraft.world.level.block.BedBlock;

            public class SkyBed extends BedBlock {
                public SkyBed(Properties properties) {
                    super(DyeColor.CYAN, properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("AltarBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.AbstractFurnaceBlock;
            import net.minecraft.core.BlockPos;

            public class AltarBlock extends AbstractFurnaceBlock {
                public AltarBlock(Properties properties) {
                    super(properties);
                }

                @Override
                protected void openContainer(Level level, BlockPos pos, Player player) {
                }
            }
        """.trimIndent())
        srcDir.resolve("TreasureChestBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.AbstractChestBlock;
            import net.minecraft.world.level.block.entity.ChestBlockEntity;

            public class TreasureChestBlock extends AbstractChestBlock<ChestBlockEntity> {
                public TreasureChestBlock(Properties properties) {
                    super(properties, () -> null);
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(projectDir)

        assertTrue(srcDir.resolve("AerogelSlab.java").readText().contains("public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.SlabBlock> codec()"))
        assertTrue(srcDir.resolve("AerogelWall.java").readText().contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.WallBlock> codec()"))
        assertTrue(srcDir.resolve("QuickPane.java").readText().contains("public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.IronBarsBlock> codec()"))
        assertTrue(srcDir.resolve("AetherGrass.java").readText().contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.GrassBlock> codec()"))
        assertTrue(srcDir.resolve("EnchantedGrass.java").readText().contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.GrassBlock> codec()"))
        assertTrue(srcDir.resolve("AetherFarm.java").readText().contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.FarmBlock> codec()"))
        assertTrue(srcDir.resolve("AetherPath.java").readText().contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.DirtPathBlock> codec()"))
        assertTrue(srcDir.resolve("AetherIce.java").readText().contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.FrostedIceBlock> codec()"))
        assertTrue(srcDir.resolve("SkyBed.java").readText().contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.BedBlock> codec()"))
        assertTrue(srcDir.resolve("AltarBlock.java").readText().contains("protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.AbstractFurnaceBlock> codec()"))
        assertTrue(srcDir.resolve("TreasureChestBlock.java").readText().contains("protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.AbstractChestBlock<ChestBlockEntity>> codec()"))
    }

    @Test
    fun `block codec generation resolves custom parent chains to nearest vanilla codec bound`() {
        val projectDir = createFile("TFLeavesBlock.java", """
            package com.example;

            import net.minecraft.world.level.block.LeavesBlock;

            public class TFLeavesBlock extends LeavesBlock {
                public TFLeavesBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.resolve("MagicLeavesBlock.java").writeText("""
            package com.example;

            public class MagicLeavesBlock extends TFLeavesBlock {
                public MagicLeavesBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("CritterBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.BaseEntityBlock;

            public abstract class CritterBlock extends BaseEntityBlock {
                protected CritterBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("CicadaBlock.java").writeText("""
            package com.example;

            public class CicadaBlock extends CritterBlock {
                public CicadaBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("IronLadderBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.LadderBlock;

            public class IronLadderBlock extends LadderBlock {
                public IronLadderBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val tfLeaves = srcDir.resolve("TFLeavesBlock.java").readText()
        val magicLeaves = srcDir.resolve("MagicLeavesBlock.java").readText()
        val cicada = srcDir.resolve("CicadaBlock.java").readText()
        val ladder = srcDir.resolve("IronLadderBlock.java").readText()

        assertTrue(tfLeaves.contains("public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.LeavesBlock> codec()"))
        assertTrue(magicLeaves.contains("public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.LeavesBlock> codec()"))
        assertTrue(cicada.contains("protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec()"))
        assertTrue(!ladder.contains("MapCodec<IronLadderBlock> CODEC"))
        assertTrue(!ladder.contains("codec()"))
    }

    @Test
    fun `BaseEntityBlock codec is skipped when no compilable constructor path is known`() {
        val projectDir = createFile("CustomBlock.java", """
            package com.example;

            import net.minecraft.world.level.block.BaseEntityBlock;

            public class CustomBlock extends BaseEntityBlock {
                public CustomBlock(Properties properties, int level) {
                    super(properties);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = projectDir
            .resolve("src/main/java/com/example/CustomBlock.java")
            .readText()

        assertTrue(result.changes.none { it.ruleId == "struct-base-entity-block-codec" })
        assertTrue(!migrated.contains("simpleCodec(CustomBlock::new)"))
    }

    @Test
    fun `block codec captures non codec constructor field from instance`() {
        val projectDir = createFile("LanternBlock.java", """
            package com.example;

            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.phys.shapes.VoxelShape;

            public class LanternBlock extends Block {
                private final VoxelShape shape;

                public LanternBlock(Properties properties, VoxelShape shape) {
                    super(properties);
                    this.shape = shape;
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/LanternBlock.java").readText()

        assertTrue(migrated.contains("return simpleCodec(properties -> new LanternBlock(properties, this.shape));"))
        assertTrue(!migrated.contains("VoxelShape.CODEC"))
        assertTrue(!migrated.contains("MapCodec<LanternBlock> CODEC"))
    }

    @Test
    fun `block codec uses unit constructor when class has non properties constructor`() {
        val projectDir = createFile("CampfirePotBlock.java", """
            package com.example;

            import net.minecraft.world.level.block.BaseEntityBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class CampfirePotBlock extends BaseEntityBlock {
                public CampfirePotBlock(boolean defaultLit) {
                    super(BlockBehaviour.Properties.of());
                }
            }
        """.trimIndent())
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.resolve("BlockRegistry.java").writeText("""
            package com.example;

            public class BlockRegistry {
                public static final Object CAMPFIRE_POT = register("campfire_pot", () -> new CampfirePotBlock(false));
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val migrated = srcDir.resolve("CampfirePotBlock.java").readText()

        assertTrue(migrated.contains("MapCodec<CampfirePotBlock> CODEC = com.mojang.serialization.MapCodec.unit(() -> new CampfirePotBlock(false));"))
        assertTrue(!migrated.contains("simpleCodec(CampfirePotBlock::new)"))
    }

    @Test
    fun `block codec uses registry holder when constructor args are not self contained`() {
        val projectDir = createFile("LinkedLogBlock.java", """
            package com.example;

            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class LinkedLogBlock extends Block {
                private final DeferredHolder<Block, Block> linked;

                public LinkedLogBlock(BlockBehaviour.Properties properties, DeferredHolder<Block, Block> linked) {
                    super(properties);
                    this.linked = linked;
                }
            }
        """.trimIndent())
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.resolve("BlockRegistry.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class BlockRegistry {
                public static final BlockBehaviour.Properties LOCAL_PROPS = BlockBehaviour.Properties.of();
                public static final DeferredHolder<Block, Block> OTHER = null;
                public static final DeferredHolder<Block, LinkedLogBlock> LINKED_LOG =
                        register("linked_log", () -> new LinkedLogBlock(LOCAL_PROPS, OTHER));
            }
        """.trimIndent())

        StructuralRefactorPass().apply(projectDir)
        val migrated = srcDir.resolve("LinkedLogBlock.java").readText()

        assertTrue(migrated.contains("MapCodec<LinkedLogBlock> CODEC = com.mojang.serialization.MapCodec.unit(() -> com.example.BlockRegistry.LINKED_LOG.get());"))
        assertTrue(!migrated.contains("LOCAL_PROPS"))
        assertTrue(!migrated.contains("OTHER))"))
    }

    @Test
    fun `custom horizon block codec keeps horizontal directional return bound`() {
        val projectDir = createFile("TatamiBlock.java", """
            package com.example;

            import cn.mcmod_mmf.mmlib.block.BaseHorizonBlock;

            public class TatamiBlock extends BaseHorizonBlock {
                public TatamiBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/TatamiBlock.java").readText()

        assertTrue(migrated.contains("MapCodec<TatamiBlock> CODEC = simpleCodec(TatamiBlock::new);"))
        assertTrue(migrated.contains("MapCodec<? extends net.minecraft.world.level.block.HorizontalDirectionalBlock> codec()"))
    }

    @Test
    fun `legacy recipe output result adapters are preserved through RecipeOutput accept`() {
        val projectDir = createFile("StoneMortarRecipeBuilder.java", """
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.resources.ResourceLocation;

            public class StoneMortarRecipeBuilder {
                public void save(RecipeOutput consumer, ResourceLocation id) {
                    consumer.accept(new StoneMortarRecipeBuilder.Result(id, this.count()));
                }

                private int count() { return 1; }

                public static class Result implements RecipeOutput {
                    private final StoneMortarRecipe recipe = new StoneMortarRecipe();

                    public Result(ResourceLocation id, int count) {
                        recipe.setId(id);
                    }

                    @Override
                    public void serializeRecipeData(JsonObject json) {
                    }

                    @Override
                    public ResourceLocation getId() {
                        return recipe.getId();
                    }
                }
            }

            class StoneMortarRecipe {
                void setId(ResourceLocation id) {}
                ResourceLocation getId() { return null; }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/StoneMortarRecipeBuilder.java").readText()

        assertTrue(migrated.contains("StoneMortarRecipeBuilder.Result result = new StoneMortarRecipeBuilder.Result(id, this.count());"))
        assertTrue(migrated.contains("consumer.accept(result.getId(), result.recipe, null);"))
        assertTrue(migrated.contains("public static class Result {"))
        assertTrue(!migrated.contains("@Override"))
    }

    @Test
    fun `mmlib recipe id tracking reads ids from holder tracked recipe id`() {
        val projectDir = createFile("CookingPotBlockEntity.java", """
            package com.example;

            import javax.annotation.Nullable;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;

            public class CookingPotBlockEntity {
                private ResourceLocation lastRecipeID;

                public void finish(Recipe<?> recipe) {
                    trackRecipeExperience(recipe);
                }

                public void trackRecipeExperience(@Nullable Recipe<?> recipe) {
                    if (recipe != null) {
                        ResourceLocation recipeID = recipe.getId();
                        add(recipeID);
                    }
                }

                private void add(ResourceLocation id) {}
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/CookingPotBlockEntity.java").readText()

        assertTrue(migrated.contains("trackRecipeExperience(lastRecipeID);"))
        assertTrue(migrated.contains("trackRecipeExperience(@Nullable ResourceLocation recipeId)"))
        assertTrue(migrated.contains("if (recipeId != null)"))
        assertTrue(migrated.contains("add(recipeId);"))
        assertTrue(!migrated.contains("trackRecipeExperience(@Nullable Recipe"))
        assertTrue(!migrated.contains("recipe.getId()"))
    }

    @Test
    fun `client screen registration migrates to RegisterMenuScreensEvent`() {
        val projectDir = createFile("ScreensRegistry.java", """
            package com.example;

            import net.minecraft.client.gui.screens.MenuScreens;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

            public class ScreensRegistry {
                @SubscribeEvent
                public static void screenRegistry(final FMLClientSetupEvent event) {
                    event.enqueueWork(() -> {
                        MenuScreens.register(ContainerRegistry.STONE_MORTAR.get(), StoneMortarScreen::new);
                    });
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/ScreensRegistry.java").readText()

        assertTrue(migrated.contains("import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;"))
        assertTrue(migrated.contains("screenRegistry(final RegisterMenuScreensEvent event)"))
        assertTrue(migrated.contains("event.register(ContainerRegistry.STONE_MORTAR.get(), StoneMortarScreen::new);"))
        assertTrue(!migrated.contains("MenuScreens.register"))
        assertTrue(!migrated.contains("enqueueWork"))
    }

    @Test
    fun `half migrated client screen event register uses RegisterMenuScreensEvent`() {
        val projectDir = createFile("ScreensRegistry.java", """
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

            public class ScreensRegistry {
                @SubscribeEvent
                public static void screenRegistry(final FMLClientSetupEvent event) {
                    event.enqueueWork(() -> {
                        event.register(ContainerRegistry.STONE_MORTAR.get(), StoneMortarScreen::new);
                    });
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/ScreensRegistry.java").readText()

        assertTrue(migrated.contains("import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;"))
        assertTrue(migrated.contains("screenRegistry(final RegisterMenuScreensEvent event)"))
        assertTrue(migrated.contains("event.register(ContainerRegistry.STONE_MORTAR.get(), StoneMortarScreen::new);"))
        assertTrue(!migrated.contains("FMLClientSetupEvent"))
        assertTrue(!migrated.contains("enqueueWork"))
    }

    @Test
    fun `useItemOn maps helper InteractionResult to ItemInteractionResult`() {
        val projectDir = createFile("NabeBlock.java", """
            package com.example;

            import net.minecraft.world.InteractionResult;

            public class NabeBlock {
                protected net.minecraft.world.ItemInteractionResult useItemOn(Object itemstack, Object state, Object level, Object pos, Object player, Object hand, Object hitResult) {
                    return eat(level, pos, state, player);
                }

                protected InteractionResult eat(Object level, Object pos, Object state, Object player) {
                    return InteractionResult.SUCCESS;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/NabeBlock.java").readText()

        assertTrue(migrated.contains("return switch (eat(level, pos, state, player))"))
        assertTrue(migrated.contains("case SUCCESS -> net.minecraft.world.ItemInteractionResult.SUCCESS;"))
        assertTrue(migrated.contains("default -> net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;"))
    }

    @Test
    fun `detects EventNetworkChannel`() {
        val projectDir = createFile("EventNet.java", """
            package com.example;
            public class EventNet {
                private EventNetworkChannel channel;
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertTrue(result.changes.any { it.ruleId == "struct-networking-channel" })
    }

    @Test
    fun `apply mode writes changes to file`() {
        val projectDir = createFile("ApplyTest.java", """
            package com.example;
            import net.minecraftforge.common.capabilities.ICapabilityProvider;
            public class ApplyTest implements ICapabilityProvider {
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    return null;
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        assertTrue(result.changeCount > 0)
    }

    @Test
    fun `detects newSimpleChannel call`() {
        val projectDir = createFile("NetReg.java", """
            package com.example;
            public class NetReg {
                void init() {
                    Object ch = NetworkRegistry.newSimpleChannel(id, ver, c, s);
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertTrue(result.changes.any { it.ruleId == "struct-networking-register" })
    }

    @Test
    fun `migrates legacy living tick events and removes split tick phase checks`() {
        val projectDir = createFile("TickHandlers.java", """
            package com.example;

            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.LivingEntity;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.event.entity.living.LivingEvent;

            public class TickHandlers {
                private static int pending;

                @SubscribeEvent
                public static void onLivingTick(LivingEvent.LivingTickEvent event) {
                    if (event.getEntity().level().isClientSide()) return;
                    LivingEntity entity = event.getEntity();
                    use(entity);
                }

                @SubscribeEvent
                public static void onPlayerTick(LivingEvent.LivingTickEvent event) {
                    if (!(event.getEntity() instanceof ServerPlayer player)) return;
                    use(player);
                }

                @SubscribeEvent
                public static void onClientTick(ClientTickEvent.Post event) {
                    if (event.phase != TickEvent.Phase.END || pending <= 0) {
                        return;
                    }
                    pending--;
                }

                private static void use(LivingEntity entity) {}
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/TickHandlers.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-legacy-tick-events" })
        assertTrue(migrated.contains("import net.neoforged.neoforge.event.tick.EntityTickEvent;"))
        assertTrue(migrated.contains("import net.neoforged.neoforge.event.tick.PlayerTickEvent;"))
        assertTrue(migrated.contains("public static void onLivingTick(EntityTickEvent.Post event)"))
        assertTrue(migrated.contains("if (!(event.getEntity() instanceof LivingEntity entity)) return;"))
        assertTrue(migrated.contains("public static void onPlayerTick(PlayerTickEvent.Post event)"))
        assertTrue(migrated.contains("if (pending <= 0) {"))
        assertTrue(!migrated.contains("LivingEvent.LivingTickEvent"))
        assertTrue(!migrated.contains("TickEvent.Phase"))
    }

    @Test
    fun `migrates BlockEntity provider signatures and packet update tag loading`() {
        val projectDir = createFile("CustomBE.java", """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.Connection;
            import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            import org.jetbrains.annotations.NotNull;

            public class CustomBE extends BlockEntity {
                public CustomBE(BlockPos pos, BlockState state) {
                    super(null, pos, state);
                }

                @Override
                protected void saveAdditional(@NotNull CompoundTag tag) {
                    super.saveAdditional(tag, registries);
                }

                @Override
                public void load(@NotNull CompoundTag tag) {
                    super.loadAdditional(tag, registries);
                }

                @Override
                public @NotNull CompoundTag getUpdateTag() {
                    CompoundTag tag = super.getUpdateTag();
                    return tag;
                }

                @Override
                public void handleUpdateTag(@NotNull CompoundTag tag) {
                    super.handleUpdateTag(tag);
                }

                @Override
                public void onDataPacket(@NotNull Connection net, @NotNull ClientboundBlockEntityDataPacket pkt) {
                    CompoundTag tag = pkt.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    if (tag != null) {
                        load(tag);
                    }
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/CustomBE.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-blockentity-holderlookup" })
        assertTrue(migrated.contains("import net.minecraft.core.HolderLookup;"))
        assertTrue(migrated.contains("protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.Provider registries)"))
        assertTrue(migrated.contains("protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.Provider registries)"))
        assertTrue(migrated.contains("public @NotNull CompoundTag getUpdateTag(HolderLookup.Provider registries)"))
        assertTrue(migrated.contains("public void handleUpdateTag(@NotNull CompoundTag tag, HolderLookup.Provider lookupProvider)"))
        assertTrue(migrated.contains("public void onDataPacket(@NotNull Connection connection, @NotNull ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider)"))
        assertTrue(migrated.contains("CompoundTag tag = pkt.getTag();"))
        assertTrue(migrated.contains("loadWithComponents(tag, lookupProvider);"))
    }

    @Test
    fun `adds Item import for TooltipContext and wraps external MobEffect variables`() {
        val projectDir = createFile("EffectItem.java", """
            package com.example;

            import net.minecraft.network.chat.Component;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import java.util.List;

            public class EffectItem {
                public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
                }

                public void apply(Player player) {
                    MobEffect coldResistance = ExternalEffects.COLD_RESISTANCE.get();
                    if (player.hasEffect(coldResistance)) {
                        MobEffectInstance current = player.getEffect(coldResistance);
                    }
                    player.addEffect(new MobEffectInstance(coldResistance, 100));
                    player.removeEffect(coldResistance);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/EffectItem.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-tooltip-context-import" })
        assertTrue(result.changes.any { it.ruleId == "struct-mobeffect-holder-direct" })
        assertTrue(migrated.contains("import net.minecraft.world.item.Item;"))
        assertTrue(migrated.contains("import net.minecraft.core.registries.BuiltInRegistries;"))
        assertTrue(migrated.contains("player.hasEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(coldResistance))"))
        assertTrue(migrated.contains("player.getEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(coldResistance))"))
        assertTrue(migrated.contains("new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(coldResistance), 100)"))
        assertTrue(migrated.contains("player.removeEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(coldResistance))"))
    }

    @Test
    fun `migrates item and fluid custom child tags to CustomData components`() {
        val projectDir = createFile("CustomFluidNBTHelper.java", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;

            public class CustomFluidNBTHelper {
                public static final String TAG_CUSTOM_FLUID = "HotbathCustomFluid";
                public static final String TAG_FLUID_ID = "FluidId";
                public static final String TAG_FLUID_COLOR = "FluidColor";
                public static final String TAG_FLUID_NAME = "FluidName";

                public static void setFluidId(ItemStack stack, ResourceLocation fluidId) {
                    CompoundTag tag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getCompound(TAG_CUSTOM_FLUID);
                    tag.putString(TAG_FLUID_ID, fluidId.toString());
                    CustomFluidRegistry.getDefinition(fluidId).ifPresent(definition -> {
                        tag.putInt(TAG_FLUID_COLOR, definition.color());
                        tag.putString(TAG_FLUID_NAME, definition.getTranslationKey());
                    });
                }

                public static ResourceLocation getFluidId(ItemStack stack) {
                    CompoundTag tag = stack.getTagElement(TAG_CUSTOM_FLUID);
                    return tag != null ? ResourceLocation.parse(tag.getString(TAG_FLUID_ID)) : null;
                }

                public static void clearFluidData(ItemStack stack) {
                    stack.removeTagKey(TAG_CUSTOM_FLUID);
                }
            }
        """.trimIndent())
        val fluidFile = projectDir.resolve("src/main/java/com/example/CustomFluidStackHelper.java")
        fluidFile.writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.fluids.FluidStack;

            public class CustomFluidStackHelper {
                public static boolean isDynamicCustomFluid(FluidStack stack) {
                    return true;
                }

                public static void setFluidId(FluidStack stack, ResourceLocation fluidId) {
                    if (stack == null || stack.isEmpty() || fluidId == null || !isDynamicCustomFluid(stack)) {
                        return;
                    }
                    CompoundTag customFluidTag = stack.getOrCreateChildTag(CustomFluidNBTHelper.TAG_CUSTOM_FLUID);
                    customFluidTag.putString(CustomFluidNBTHelper.TAG_FLUID_ID, fluidId.toString());
                }

                public static ResourceLocation getFluidId(FluidStack stack) {
                    CompoundTag customFluidTag = stack.getChildTag(CustomFluidNBTHelper.TAG_CUSTOM_FLUID);
                    return customFluidTag != null ? ResourceLocation.parse(customFluidTag.getString(CustomFluidNBTHelper.TAG_FLUID_ID)) : null;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val itemMigrated = tempDir.resolve("src/main/java/com/example/CustomFluidNBTHelper.java").readText()
        val fluidMigrated = tempDir.resolve("src/main/java/com/example/CustomFluidStackHelper.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-custom-data-components" })
        assertTrue(itemMigrated.contains("import net.minecraft.core.component.DataComponents;"))
        assertTrue(itemMigrated.contains("import net.minecraft.world.item.component.CustomData;"))
        assertTrue(itemMigrated.contains("updateCustomDataChild(stack, TAG_CUSTOM_FLUID, tag -> {"))
        assertTrue(itemMigrated.contains("CustomData.update(DataComponents.CUSTOM_DATA, stack"))
        assertTrue(itemMigrated.contains("CompoundTag tag = getCustomDataChild(stack, TAG_CUSTOM_FLUID);"))
        assertTrue(itemMigrated.contains("removeCustomDataChild(stack, TAG_CUSTOM_FLUID);"))
        assertTrue(fluidMigrated.contains("updateFluidCustomDataChild(stack, CustomFluidNBTHelper.TAG_CUSTOM_FLUID"))
        assertTrue(fluidMigrated.contains("stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));"))
        assertTrue(fluidMigrated.contains("CompoundTag customFluidTag = getFluidCustomDataChild(stack, CustomFluidNBTHelper.TAG_CUSTOM_FLUID);"))
    }

    @Test
    fun `migrates brewing registration to NeoForge brewing event`() {
        val projectDir = createFile("HotBath.java", """
            package com.example;

            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry;
            import net.neoforged.neoforge.common.NeoForge;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            public class HotBath {
                public HotBath() {
                    NeoForge.EVENT_BUS.register(this);
                }

                private void commonSetup(final FMLCommonSetupEvent event) {
                    registerBrewingRecipes(event);
                }

                private void registerBrewingRecipes(final FMLCommonSetupEvent event) {
                    event.enqueueWork(() -> {
                        BrewingRecipeRegistry.addRecipe(Ingredient.of(ItemRegister.HOT_WATER_BOTTLE.get()), Ingredient.of(Items.GUNPOWDER), ItemRegister.SPLASH_HOT_WATER_BOTTLE.get().getDefaultInstance());
                        BrewingRecipeRegistry.addRecipe(new CustomFluidBrewingRecipe());
                    });
                }

                /*
                @SubscribeEvent
                public void onBrewingRecipeRegister(net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent event) {
                    event.getBuilder().addRecipe(recipe);
                }
                */
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/HotBath.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-brewing-recipes-event" })
        assertTrue(migrated.contains("import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;"))
        assertTrue(!migrated.contains("BrewingRecipeRegistry"))
        assertTrue(!migrated.contains("registerBrewingRecipes(event);"))
        assertTrue(migrated.contains("@SubscribeEvent"))
        assertTrue(migrated.contains("public void registerBrewingRecipes(final RegisterBrewingRecipesEvent event)"))
        assertTrue(migrated.contains("event.getBuilder().addRecipe(new CustomFluidBrewingRecipe());"))
        assertTrue(!migrated.contains("onBrewingRecipeRegister"))
    }

    @Test
    fun `migrates common Minecraft 121 vanilla API changes`() {
        val projectDir = createFile("VanillaApiUse.java", """
            package com.example;

            import net.minecraft.client.player.AbstractClientPlayer;
            import net.minecraft.client.renderer.entity.player.PlayerRenderer;
            import net.minecraft.core.Holder;
            import net.minecraft.core.particles.ParticleTypes;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffects;
            import net.minecraft.world.entity.MobType;
            import net.minecraft.world.entity.ai.attributes.Attribute;
            import net.minecraft.world.entity.ai.attributes.AttributeInstance;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.minecraft.world.entity.ai.attributes.Attributes;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.trading.MerchantOffer;
            import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

            public class VanillaApiUse {
                void player(Player player) {
                    ItemStack stack = new ItemStack(Items.POTION);
                    double reach = player.getBlockReach();
                    if (player.getMobType() == MobType.UNDEAD) {}
                    new MerchantOffer(new ItemStack(Items.EMERALD, 10), new ItemStack(Items.DIRT), 16, 2, 0.05F);
                    int color = net.minecraft.world.item.alchemy.PotionUtils.getColor(stack);
                }

                void attr(Player player, Attribute attribute, ResourceLocation modifierName, boolean add, double value, AttributeModifier.Operation operation) {
                    AttributeInstance attributeInstance = player.getAttribute(attribute);
                    java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(modifierName.toString().getBytes());
                    AttributeModifier modifier = new AttributeModifier(uuid, modifierName.toString(), value, AttributeModifier.Operation.MULTIPLY_TOTAL);
                    if (!attributeInstance.hasModifier(modifier)) {}
                    attributeInstance.removeModifier(uuid);
                }

                void effect(Holder<MobEffect> effectHolder) {
                    MobEffect effect = effectHolder;
                    if (effectHolder != MobEffects.UNLUCK) {}
                }

                void model(AbstractClientPlayer client, TrophyType trophyType) {
                    if (client.getModelName().equals("slim")) {}
                    String modelName = trophyType.getModelName();
                }

                public static void onPlayerHurt(LivingDamageEvent event) {
                    Object entity = event.getEntity();
                }

                public static void clampPlayerHurt(LivingDamageEvent event) {
                    float amount = event.getAmount();
                    event.setAmount(Math.min(amount, 10.0F));
                }

                static class TrophyType {
                    String getModelName() {
                        return "trophy";
                    }
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/VanillaApiUse.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(migrated.contains("player.blockInteractionRange()"))
        assertTrue(migrated.contains("player.getType().is(EntityTypeTags.UNDEAD)"))
        assertTrue(migrated.contains("new ItemCost(Items.EMERALD, 10)"))
        assertTrue(migrated.contains("stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getColor()"))
        assertTrue(migrated.contains("Holder<Attribute> attribute"))
        assertTrue(migrated.contains("new AttributeModifier(modifierName, value, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)"))
        assertTrue(migrated.contains("attributeInstance.hasModifier(modifier.id())"))
        assertTrue(migrated.contains("attributeInstance.removeModifier(modifierName)"))
        assertTrue(migrated.contains("MobEffect effect = effectHolder.value();"))
        assertTrue(migrated.contains("client.getSkin().model().id().equals(\"slim\")"))
        assertTrue(migrated.contains("String modelName = trophyType.getModelName();"))
        assertTrue(migrated.contains("onPlayerHurt(LivingDamageEvent.Post event)"))
        assertTrue(migrated.contains("clampPlayerHurt(LivingDamageEvent.Pre event)"))
        assertTrue(migrated.contains("float amount = event.getNewDamage();"))
        assertTrue(migrated.contains("event.setNewDamage(Math.min(amount, 10.0F));"))
    }

    @Test
    fun `migrates custom recipe serializer and deferred holder generics`() {
        val projectDir = createFile("CustomFluidCraftingRecipe.java", """
            package com.example;

            import com.crabmod.hotbath.HotBath;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonParseException;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.NonNullList;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.*;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import net.minecraft.core.RegistryAccess;

            public class CustomFluidCraftingRecipe implements CraftingRecipe {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
                        DeferredRegister.create(Registries.RECIPE_SERIALIZER, HotBath.MOD_ID);
                public static final DeferredHolder<RecipeSerializer<CustomFluidCraftingRecipe>, RecipeSerializer<CustomFluidCraftingRecipe>> SERIALIZER =
                        RECIPE_SERIALIZERS.register("custom_fluid_crafting", Serializer::new);
                private final ResourceLocation id;
                private final ResourceLocation fluidId;
                private final Item ingredient;
                private final int ingredientCount;
                public CustomFluidCraftingRecipe(ResourceLocation id, ResourceLocation fluidId, Item ingredient, int ingredientCount) {
                    this.id = id;
                    this.fluidId = fluidId;
                    this.ingredient = ingredient;
                    this.ingredientCount = ingredientCount;
                }
                public ResourceLocation getFluidId() { return fluidId; }
                public Item getIngredient() { return ingredient; }
                public int getIngredientCount() { return ingredientCount; }
                public boolean matches(CraftingInput input, Level level) { return true; }
                public ItemStack assemble(CraftingInput input, HolderLookup.Provider provider) { return ItemStack.EMPTY; }
                public boolean canCraftInDimensions(int width, int height) { return true; }
                @Override
                public ItemStack getResultItem(RegistryAccess registryAccess) { return ItemStack.EMPTY; }
                @Override
                public ResourceLocation getId() { return id; }
                public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }
                public CraftingBookCategory category() { return CraftingBookCategory.MISC; }
                public static class Serializer implements RecipeSerializer<CustomFluidCraftingRecipe> {
                    @Override
                    public CustomFluidCraftingRecipe fromJson(ResourceLocation recipeId, JsonObject json) { return null; }
                    @Override
                    public CustomFluidCraftingRecipe fromNetwork(ResourceLocation recipeId, FriendlyByteBuf buffer) { return null; }
                    @Override
                    public void toNetwork(FriendlyByteBuf buffer, CustomFluidCraftingRecipe recipe) {}
                }
            }
        """.trimIndent())
        val registryFile = projectDir.resolve("src/main/java/com/example/RegistryUse.java")
        registryFile.writeText("""
            package com.example;

            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.LiquidBlock;
            import net.neoforged.neoforge.fluids.FluidType;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class RegistryUse {
                DeferredHolder<LiquidBlock, LiquidBlock> fluidBlock;
                DeferredHolder<DynamicFluidType, DynamicFluidType> fluidType;
                private static <T extends Block> DeferredHolder<T, T> registerBlock(String name, java.util.function.Supplier<T> block) { return null; }
                private static <T extends Block> DeferredHolder<Item, Item> registerBlockItem(String name, DeferredHolder<T, T> block) { return null; }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val recipe = tempDir.resolve("src/main/java/com/example/CustomFluidCraftingRecipe.java").readText()
        val registry = tempDir.resolve("src/main/java/com/example/RegistryUse.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-recipe-codec-121" })
        assertTrue(result.changes.any { it.ruleId == "struct-deferredholder-generics" })
        assertTrue(recipe.contains("DeferredHolder<RecipeSerializer<?>, Serializer> SERIALIZER"))
        assertTrue(recipe.contains("public MapCodec<CustomFluidCraftingRecipe> codec()"))
        assertTrue(recipe.contains("public StreamCodec<RegistryFriendlyByteBuf, CustomFluidCraftingRecipe> streamCodec()"))
        assertTrue(recipe.contains("public ItemStack getResultItem(HolderLookup.Provider registryAccess)"))
        assertTrue(!recipe.contains("@Override\n    public ResourceLocation getId()"))
        assertTrue(registry.contains("DeferredHolder<Block, LiquidBlock> fluidBlock"))
        assertTrue(registry.contains("DeferredHolder<FluidType, DynamicFluidType> fluidType"))
        assertTrue(registry.contains("DeferredHolder<Block, T> registerBlock"))
        assertTrue(registry.contains("registerBlockItem(String name, DeferredHolder<Block, T> block)"))
    }

    @Test
    fun `migrates verified third party compat APIs to NeoForge 121`() {
        val projectDir = createFile("AlexsCavesEventHandler.java", """
            package com.example;

            import com.github.alexmodguy.alexscaves.server.potion.ACEffectRegistry;
            import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.LivingEntity;

            public class AlexsCavesEventHandler {
                void cure(LivingEntity living, ServerLevel serverLevel) {
                    MobEffectInstance radiation = living.getEffect(ACEffectRegistry.IRRADIATED.get());
                    living.removeEffect(ACEffectRegistry.IRRADIATED.get());
                    serverLevel.getChunkSource().broadcastAndSend(living,
                            new ClientboundRemoveMobEffectPacket(living.getId(), ACEffectRegistry.IRRADIATED.get()));
                    living.addEffect(new MobEffectInstance(ACEffectRegistry.IRRADIATED.get(), 100, 0));
                }
            }
        """.trimIndent())
        projectDir.resolve("src/main/java/com/example/FarmersDelightEventHandler.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.server.level.ServerPlayer;
            import vectorwing.farmersdelight.common.registry.ModEffects;

            public class FarmersDelightEventHandler {
                void grant(ServerPlayer player) {
                    MobEffect nourishmentEffect = ModEffects.NOURISHMENT.get();
                    MobEffectInstance currentEffect = player.getEffect(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(nourishmentEffect));
                    player.addEffect(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(nourishmentEffect), 100, 0));
                }
            }
        """.trimIndent())
        projectDir.resolve("src/main/java/com/example/EpicFightClientHelper.java").writeText("""
            package com.example;

            import com.crabmod.hotbath.dirtiness.DirtinessOverlayRenderer;
            import net.minecraft.world.entity.EntityType;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.common.NeoForge;
            import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;
            import yesman.epicfight.client.ClientEngine;
            import yesman.epicfight.client.events.engine.RenderEngine;
            import yesman.epicfight.client.renderer.FirstPersonRenderer;
            import yesman.epicfight.client.renderer.patched.entity.PPlayerRenderer;

            public class EpicFightClientHelper {
                private static FirstPersonRenderer registeredFirstPersonRenderer;

                static void registerLayers(IEventBus modEventBus) {
                    modEventBus.addListener(EpicFightClientHelper::onModifyPatchedRenderers);
                    NeoForge.EVENT_BUS.register(EpicFightClientHelper.class);
                }

                private static void onModifyPatchedRenderers(PatchedRenderersEvent.Modify event) {
                    if (event.get(EntityType.PLAYER) instanceof PPlayerRenderer playerRenderer) {
                        playerRenderer.addPatchedLayerAlways(DirtinessOverlayRenderer.class, new EpicFightDirtinessPatchedLayer());
                    }
                    registerFirstPersonLayerIfAvailable();
                }

                @SubscribeEvent
                public static void onClientTick(ClientTickEvent.Post event) {
                    registerFirstPersonLayerIfAvailable();
                }

                private static void registerFirstPersonLayerIfAvailable() {
                    ClientEngine clientEngine = ClientEngine.getInstance();
                    RenderEngine renderEngine = clientEngine != null ? clientEngine.renderEngine : null;
                    if (renderEngine == null) {
                        return;
                    }
                }
            }
        """.trimIndent())
        projectDir.resolve("src/main/java/com/example/HotBath.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;

            public class HotBath {
                void register() {
                    CompatManager.registerCompat("epicfight", "Epic Fight", () -> true, () -> {},
                            "yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent${'$'}Modify",
                            "yesman.epicfight.client.ClientEngine",
                            "yesman.epicfight.client.events.engine.RenderEngine");
                }

                @SubscribeEvent
                public void onServerStarting(Object event) {
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val alexsCaves = tempDir.resolve("src/main/java/com/example/AlexsCavesEventHandler.java").readText()
        val farmers = tempDir.resolve("src/main/java/com/example/FarmersDelightEventHandler.java").readText()
        val epicFight = tempDir.resolve("src/main/java/com/example/EpicFightClientHelper.java").readText()
        val hotBath = tempDir.resolve("src/main/java/com/example/HotBath.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-verified-compat-121-api" })
        assertTrue(alexsCaves.contains("living.getEffect(ACEffectRegistry.IRRADIATED)"))
        assertTrue(alexsCaves.contains("living.removeEffect(ACEffectRegistry.IRRADIATED)"))
        assertTrue(alexsCaves.contains("new ClientboundRemoveMobEffectPacket(living.getId(), ACEffectRegistry.IRRADIATED)"))
        assertTrue(alexsCaves.contains("new MobEffectInstance(ACEffectRegistry.IRRADIATED, 100, 0)"))
        assertTrue(!alexsCaves.contains("ACEffectRegistry.IRRADIATED.get()"))

        assertTrue(farmers.contains("import net.minecraft.core.Holder;"))
        assertTrue(farmers.contains("Holder<MobEffect> nourishmentEffect = ModEffects.NOURISHMENT;"))
        assertTrue(farmers.contains("player.getEffect(nourishmentEffect)"))
        assertTrue(farmers.contains("new MobEffectInstance(nourishmentEffect, 100, 0)"))
        assertTrue(!farmers.contains("BuiltInRegistries.MOB_EFFECT.wrapAsHolder(nourishmentEffect)"))
        assertTrue(!farmers.contains("ModEffects.NOURISHMENT.get()"))

        assertTrue(epicFight.contains("import yesman.epicfight.api.client.event.EpicFightClientEventHooks;"))
        assertTrue(epicFight.contains("import yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent;"))
        assertTrue(epicFight.contains("EpicFightClientEventHooks.Registry.MODIFY_PATCHED_ENTITY.registerEvent(EpicFightClientHelper::onModifyPatchedRenderers);"))
        assertTrue(epicFight.contains("NeoForge.EVENT_BUS.addListener(EpicFightClientHelper::onClientTick);"))
        assertTrue(epicFight.contains("private static void onModifyPatchedRenderers(RegisterPatchedRenderersEvent.ModifyEntity event)"))
        assertTrue(epicFight.contains("RenderEngine renderEngine = RenderEngine.getInstance();"))
        assertTrue(!epicFight.contains("api.client.forgeevent.PatchedRenderersEvent"))
        assertTrue(!epicFight.contains("onModifyPatchedRenderers(PatchedRenderersEvent.Modify"))
        assertTrue(!epicFight.contains("ClientEngine"))
        assertTrue(!epicFight.contains("@SubscribeEvent"))

        assertTrue(hotBath.contains("import net.neoforged.bus.api.SubscribeEvent;"))
        assertTrue(hotBath.contains("yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent${'$'}ModifyEntity"))
        assertTrue(hotBath.contains("yesman.epicfight.api.client.event.EpicFightClientEventHooks"))
        assertTrue(!hotBath.contains("yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent${'$'}Modify"))
        assertTrue(!hotBath.contains("yesman.epicfight.client.ClientEngine"))

    }

    @Test
    fun `migrates ModLoadingContext config registration when constructor already has ModContainer`() {
        val projectDir = createFile("ExampleMod.java", """
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.ModLoadingContext;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.config.ModConfig;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ExampleConfig.SPEC);
                    ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-modloadingcontext-register-config" })
        assertTrue(migrated.contains("modContainer.registerConfig(ModConfig.Type.COMMON, ExampleConfig.SPEC);"))
        assertTrue(migrated.contains("modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);"))
        assertTrue(!migrated.contains("ModLoadingContext.get()"))
        assertTrue(!migrated.contains("import net.neoforged.fml.ModLoadingContext;"))
    }

    @Test
    fun `migrates MobEffect helper parameters to Holder without deleting effect logic`() {
        val projectDir = createFile("EffectHelper.java", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.effect.MobEffects;
            import net.minecraft.world.entity.LivingEntity;

            public class EffectHelper {
                private static void applyEffects(LivingEntity entity) {
                    addStackingEffect(entity, MobEffects.REGENERATION, 400, 1);
                    entity.removeEffect(MobEffects.POISON);
                }

                private static void addStackingEffect(LivingEntity entity, MobEffect effect, int durationIncrement, int amplifier) {
                    CompoundTag data = entity.getPersistentData();
                    String key = "example.last_stack." + effect.getDescriptionId();
                    if (data.getLong(key) > 0) return;
                    MobEffectInstance current = entity.getEffect(effect);
                    int currentDuration = (current != null) ? current.getDuration() : 0;
                    entity.addEffect(new MobEffectInstance(effect, currentDuration + durationIncrement, amplifier, true, false));
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/EffectHelper.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-mobeffect-holder-direct" })
        assertTrue(migrated.contains("import net.minecraft.core.Holder;"))
        assertTrue(migrated.contains("private static void addStackingEffect(LivingEntity entity, Holder<MobEffect> effect, int durationIncrement, int amplifier)"))
        assertTrue(migrated.contains("effect.value().getDescriptionId()"))
        assertTrue(migrated.contains("MobEffectInstance current = entity.getEffect(effect);"))
        assertTrue(migrated.contains("entity.addEffect(new MobEffectInstance(effect, currentDuration + durationIncrement, amplifier, true, false));"))
        assertTrue(migrated.contains("entity.removeEffect(MobEffects.POISON);"))
    }

    @Test
    fun `migrates FlowerBlock constructors and call sites to MobEffect holders`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("FlowerEntityBlock.java").writeText("""
            package com.example;

            import java.util.function.Supplier;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.level.block.EntityBlock;
            import net.minecraft.world.level.block.FlowerBlock;

            public class FlowerEntityBlock extends FlowerBlock implements EntityBlock {
                public FlowerEntityBlock(Supplier<MobEffect> effectSupplier, int effectDuration, Properties properties) {
                    super(effectSupplier, effectDuration, properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("FlowerRegistry.java").writeText("""
            package com.example;

            import net.minecraft.world.effect.MobEffects;
            import net.minecraft.world.level.block.FlowerBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class FlowerRegistry {
                public Object vanilla() {
                    return new FlowerBlock(() -> MobEffects.POISON, 7, BlockBehaviour.Properties.of());
                }

                public Object customVanillaEffect() {
                    return new FlowerEntityBlock(() -> MobEffects.REGENERATION, 5, BlockBehaviour.Properties.of());
                }

                public Object customDeferredEffect() {
                    return new FlowerEntityBlock(() -> ModEffects.COMFORT.get(), 6, BlockBehaviour.Properties.of());
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val block = srcDir.resolve("FlowerEntityBlock.java").readText()
        val registry = srcDir.resolve("FlowerRegistry.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-flower-block-holder-constructor" })
        assertTrue(block.contains("import net.minecraft.core.Holder;"), block)
        assertTrue(!block.contains("import java.util.function.Supplier;"), block)
        assertTrue(block.contains("public FlowerEntityBlock(Holder<MobEffect> effectSupplier, float effectDuration, Properties properties)"), block)
        assertTrue(block.contains("super(effectSupplier, effectDuration, properties);"), block)
        assertTrue(registry.contains("new FlowerBlock(MobEffects.POISON, 7, BlockBehaviour.Properties.of())"), registry)
        assertTrue(registry.contains("new FlowerEntityBlock(MobEffects.REGENERATION, 5, BlockBehaviour.Properties.of())"), registry)
        assertTrue(registry.contains("new FlowerEntityBlock(ModEffects.COMFORT, 6, BlockBehaviour.Properties.of())"), registry)
        assertTrue(!registry.contains("() -> MobEffects"), registry)
        assertTrue(!registry.contains("ModEffects.COMFORT.get()"), registry)
    }

    @Test
    fun `migrates DeferredHolder registry base generics for entity types and multiline block item helpers`() {
        val projectDir = createFile("RegistryUse.java", """
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;
            import net.minecraft.world.item.BlockItem;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            import java.util.function.Supplier;

            public class RegistryUse {
                public static final DeferredRegister<Block> BLOCKS = null;
                public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = null;

                public static final DeferredHolder<EntityType<RubberDuckEntity>, EntityType<RubberDuckEntity>> RUBBER_DUCK =
                        ENTITY_TYPES.register("rubber_duck", () -> EntityType.Builder.<RubberDuckEntity>of(RubberDuckEntity::new, MobCategory.MISC).build("rubber_duck"));

                private static <T extends Block> DeferredHolder<T, T> registerBlock(String name, Supplier<T> block) {
                    DeferredHolder<Block, T> toReturn = BLOCKS.register(name, block);
                    registerBlockItem(name, toReturn);
                    return toReturn;
                }

                private static <T extends Block> DeferredHolder<Item, Item> registerBlockItem(
                        String name, DeferredHolder<T, T> block) {
                    return null;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/RegistryUse.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-deferredholder-generics" })
        assertTrue(migrated.contains("DeferredHolder<EntityType<?>, EntityType<RubberDuckEntity>> RUBBER_DUCK"))
        assertTrue(migrated.contains("DeferredHolder<Block, T> registerBlock(String name, Supplier<T> block)"))
        assertTrue(migrated.contains("registerBlockItem(String name, DeferredHolder<Block, T> block)"))
        assertTrue(!migrated.contains("DeferredHolder<EntityType<RubberDuckEntity>, EntityType<RubberDuckEntity>>"))
        assertTrue(!migrated.contains("DeferredHolder<T, T> block"))
    }

    @Test
    fun `migrates Sakura registry and vanilla 121 API compile surfaces`() {
        val projectDir = createFile("ContentRegistry.java", """
            package com.example;

            import cn.mcmod_mmf.mmlib.item.ItemFoodBase;
            import cn.mcmod_mmf.mmlib.registry.ItemRegistryUtil;
            import com.google.common.collect.ImmutableSet;
            import java.util.Map;
            import java.util.function.Supplier;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.world.entity.npc.VillagerProfession;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.entity.ai.village.poi.PoiType;
            import net.minecraft.sounds.SoundEvents;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ContentRegistry {
                public static final DeferredRegister<Item> ITEMS = null;
                public static final DeferredHolder<ItemFoodBase, ItemFoodBase> FOOD =
                        register("food", () -> new ItemFoodBase(new Item.Properties(), null));
                public static final Map<TeaSet, DeferredHolder<Item, Item>> TEAS =
                        ItemRegistryUtil.mapOfKeys(TeaSet.class, tea -> drink(tea.effects()));
                public static final DeferredHolder<PoiType, PoiType> FARMER_POI = null;

                public static Item axe(Tier tier) {
                    return new net.minecraft.world.item.AxeItem(tier, 9.0F, -3.3F, new Item.Properties());
                }

                public static Item shovel(Tier tier, Item.Properties properties) {
                    return new net.minecraft.world.item.ShovelItem(tier, 1.5F, -3.0F, properties.stacksTo(1));
                }

                public static VillagerProfession profession() {
                    return new VillagerProfession("example:farmer",
                            x -> x.get() == FARMER_POI.get(),
                            x -> x.get() == FARMER_POI.get(),
                            ImmutableSet.of(), ImmutableSet.of(),
                            SoundEvents.VILLAGER_WORK_FARMER);
                }

                public static void recipe(RecipeOutput output) {
                    CookingBuilder.cooking().requires(Items.GRASS).save(output);
                }

                private static Item drink(Object effects) {
                    return new Item(new Item.Properties());
                }

                private static <V extends Item> DeferredHolder<V, V> register(String name, Supplier<V> item) {
                    return ITEMS.register(name, item);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/ContentRegistry.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(result.changes.any { it.ruleId == "struct-deferredholder-generics" })
        assertTrue(migrated.contains("DeferredHolder<Item, ItemFoodBase> FOOD"))
        assertTrue(migrated.contains("private static <V extends Item> DeferredHolder<Item, V> register"))
        assertTrue(migrated.contains("(DeferredHolder<Item, V>) (DeferredHolder<?, ?>) ITEMS.register(name, item)"))
        assertTrue(migrated.contains("tea.getEffects()"))
        assertTrue(migrated.contains("new net.minecraft.world.item.AxeItem(tier, new Item.Properties().attributes(net.minecraft.world.item.DiggerItem.createAttributes(tier, 9.0F, -3.3F)))"))
        assertTrue(migrated.contains("new net.minecraft.world.item.ShovelItem(tier, properties.stacksTo(1).attributes(net.minecraft.world.item.DiggerItem.createAttributes(tier, 1.5F, -3.0F)))"))
        assertTrue(migrated.contains("x -> x.value() == FARMER_POI.get()"))
        assertTrue(migrated.contains("Items.SHORT_GRASS"))
        assertTrue(!migrated.contains("Items.GRASS"))
    }

    @Test
    fun `migrates Sakura loot feature and item component compile surfaces`() {
        val projectDir = createFile("SakuraBlockLoot.java", """
            package com.example;

            import cn.mcmod_mmf.mmlib.data.loot.AbstartctBlockLoot;
            import java.util.Set;
            import net.minecraft.world.item.Item;

            public class SakuraBlockLoot extends AbstartctBlockLoot {
                public SakuraBlockLoot(Set<Item> pExplosionResistant) {
                    super(pExplosionResistant);
                }

                public SakuraBlockLoot() {
                    super(Set.of());
                }
            }
        """.trimIndent())
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.resolve("SakuraFeatureRegistry.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.feature.Feature;
            import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
            import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacerType;
            import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType;
            import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType;
            import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecoratorType;
            import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class SakuraFeatureRegistry {
                public static final DeferredHolder<Feature<NoneFeatureConfiguration>, Feature<NoneFeatureConfiguration>> HOT_SPRING_FEATURE = null;
                public static final DeferredHolder<TrunkPlacerType<BranchingTrunkPlacer>, TrunkPlacerType<BranchingTrunkPlacer>> BRANCHING_TRUNK = null;
                public static final DeferredHolder<FoliagePlacerType<CanopyFoliagePlacer>, FoliagePlacerType<CanopyFoliagePlacer>> CANOPY_FOLIAGE = null;
                public static final DeferredHolder<TreeDecoratorType<FallenLeavesDecorator>, TreeDecoratorType<FallenLeavesDecorator>> FALLEN_LEAVES_DECORATOR = null;
                public static final DeferredHolder<PlacementModifierType<SurfaceWaterDepthFilter>, PlacementModifierType<SurfaceWaterDepthFilter>> SURFACE_WATER_DEPTH = null;
                public static final DeferredHolder<StructurePoolElementType<JapaneseHouseElement>, StructurePoolElementType<JapaneseHouseElement>> JAPANESE_HOUSE = null;

                private static <P extends SurfaceWaterDepthFilter> DeferredHolder<PlacementModifierType<P>, PlacementModifierType<P>> registerPlacer(String name) {
                    return null;
                }
            }
        """.trimIndent())
        srcDir.resolve("SheathItem.java").writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResultHolder;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Tiers;
            import net.minecraft.world.level.Level;

            public class SheathItem extends Item {
                public SheathItem(Properties properties) {
                    super(properties.defaultDurability(Tiers.WOOD.getUses()));
                }

                public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
                    ItemStack sheathStack = player.getMainHandItem();
                    ItemStack otherStack = player.getItemInHand(hand);
                    CompoundTag tag = new CompoundTag();
                    tag.put("SheathBlade", otherStack.copy().save(new net.minecraft.nbt.CompoundTag()));
                    return InteractionResultHolder.sidedSuccess(player.getMainHandItem(), level.isClientSide());
                }
            }
        """.trimIndent())
        srcDir.resolve("RiceSeedsItem.java").writeText("""
            package com.example;

            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.item.context.UseOnContext;

            public class RiceSeedsItem {
                InteractionResult useOn(UseOnContext context) {
                    InteractionResult result = InteractionResult.PASS;
                    return !result.consumesAction() && this.has(net.minecraft.core.component.DataComponents.FOOD)
                            ? this.use(context.getLevel(), context.getPlayer(), context.getHand()).getResult()
                            : result;
                }
            }
        """.trimIndent())
        srcDir.resolve("JapaneseHouseElement.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.level.StructureManager;
            import net.minecraft.world.level.WorldGenLevel;
            import net.minecraft.world.level.block.Rotation;
            import net.minecraft.world.level.chunk.ChunkGenerator;
            import net.minecraft.world.level.levelgen.structure.BoundingBox;
            import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
            import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

            public class JapaneseHouseElement extends StructurePoolElement {
                public boolean place(StructureTemplateManager manager, WorldGenLevel level,
                                     StructureManager structureManager, ChunkGenerator chunkGenerator,
                                     BlockPos origin, BlockPos jigsawTargetPos,
                                     Rotation rotation,
                                     BoundingBox boundingBox, RandomSource random, boolean keepJigsaws) {
                    return true;
                }
            }
        """.trimIndent())
        srcDir.resolve("FishingModifier.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
            import net.neoforged.neoforge.common.loot.LootModifier;

            public class FishingModifier extends LootModifier {
                public static final Codec<FishingModifier> CODEC = RecordCodecBuilder
                        .create(inst -> codecStart(inst).apply(inst, FishingModifier::new));

                protected FishingModifier(LootItemCondition[] conditionsIn) {
                    super(conditionsIn);
                }

                @Override
                public Codec<? extends IGlobalLootModifier> codec() {
                    return CODEC;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(projectDir)
        val loot = srcDir.resolve("SakuraBlockLoot.java").readText()
        val feature = srcDir.resolve("SakuraFeatureRegistry.java").readText()
        val sheath = srcDir.resolve("SheathItem.java").readText()
        val riceSeeds = srcDir.resolve("RiceSeedsItem.java").readText()
        val house = srcDir.resolve("JapaneseHouseElement.java").readText()
        val fishingModifier = srcDir.resolve("FishingModifier.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(result.changes.any { it.ruleId == "struct-deferredholder-generics" })
        assertTrue(loot.contains("public SakuraBlockLoot(net.minecraft.core.HolderLookup.Provider provider)"))
        assertTrue(loot.contains("super(provider);"))
        assertTrue(!loot.contains("super(Set.of())"))
        assertTrue(feature.contains("DeferredHolder<Feature<?>, Feature<NoneFeatureConfiguration>> HOT_SPRING_FEATURE"))
        assertTrue(feature.contains("DeferredHolder<TrunkPlacerType<?>, TrunkPlacerType<BranchingTrunkPlacer>> BRANCHING_TRUNK"))
        assertTrue(feature.contains("DeferredHolder<FoliagePlacerType<?>, FoliagePlacerType<CanopyFoliagePlacer>> CANOPY_FOLIAGE"))
        assertTrue(feature.contains("DeferredHolder<TreeDecoratorType<?>, TreeDecoratorType<FallenLeavesDecorator>> FALLEN_LEAVES_DECORATOR"))
        assertTrue(feature.contains("DeferredHolder<PlacementModifierType<?>, PlacementModifierType<SurfaceWaterDepthFilter>> SURFACE_WATER_DEPTH"))
        assertTrue(feature.contains("DeferredHolder<PlacementModifierType<?>, PlacementModifierType<P>> registerPlacer"))
        assertTrue(feature.contains("DeferredHolder<StructurePoolElementType<?>, StructurePoolElementType<JapaneseHouseElement>> JAPANESE_HOUSE"))
        assertTrue(sheath.contains("super(properties.durability(Tiers.WOOD.getUses()));"))
        assertTrue(sheath.contains("ItemStack sheathStack = player.getItemInHand(hand);"))
        assertTrue(sheath.contains("otherStack.copy().save(player.registryAccess(), new net.minecraft.nbt.CompoundTag())"))
        assertTrue(sheath.contains("InteractionResultHolder.success(player.getItemInHand(hand))"))
        assertTrue(riceSeeds.contains("return result;"))
        assertTrue(!riceSeeds.contains("this.has(net.minecraft.core.component.DataComponents.FOOD)"))
        assertTrue(house.contains("import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;"))
        assertTrue(house.contains("BoundingBox boundingBox, RandomSource random, LiquidSettings liquidSettings, boolean keepJigsaws"))
        assertTrue(fishingModifier.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(fishingModifier.contains("public static final MapCodec<FishingModifier> CODEC"))
        assertTrue(fishingModifier.contains("RecordCodecBuilder\n            .mapCodec("))
        assertTrue(fishingModifier.contains("public MapCodec<? extends IGlobalLootModifier> codec()"))
        assertTrue(!fishingModifier.contains("import com.mojang.serialization.Codec;"))
    }

    @Test
    fun `migrates Sakura strict gametest and runtime compile surfaces`() {
        val projectDir = createFile("LootModifiterRegistry.java", """
            package com.example;

            import com.mojang.serialization.Codec;
            import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import net.neoforged.neoforge.registries.NeoForgeRegistries;

            public class LootModifiterRegistry {
                public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLM =
                        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, "example");
                public static final DeferredHolder<Codec<? extends IGlobalLootModifier>, Codec<? extends IGlobalLootModifier>> SEEDSDROP =
                        GLM.register("grass_drops", () -> SeedsDrop.SeedDropModifier.CODEC);
            }
        """.trimIndent())
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.resolve("SeedsDrop.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
            import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
            import net.neoforged.neoforge.common.loot.LootModifier;

            public class SeedsDrop {
                public static class SeedDropModifier extends LootModifier {
                    public SeedDropModifier(LootItemCondition[] conditionsIn) {
                        super(conditionsIn);
                    }

                    public static final Codec<SeedDropModifier> CODEC = RecordCodecBuilder
                            .create(inst -> codecStart(inst).apply(inst, SeedDropModifier::new));

                    @Override
                    public Codec<? extends IGlobalLootModifier> codec() {
                        return CODEC;
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("SheathKeyPacket.java").writeText("""
            package com.example;

            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.network.handling.IPayloadContext;

            public class SheathKeyPacket implements CustomPacketPayload {
                public static void handle(SheathKeyPacket msg, IPayloadContext ctx) {
                    ctx.enqueueWork(() -> {
                        ServerPlayer player = ctx.player();
                        if (player == null) return;
                        net.minecraft.world.item.ItemStack sheathKatana = net.minecraft.world.item.ItemStack.EMPTY;
                        net.minecraft.world.item.ItemStack otherStack = net.minecraft.world.item.ItemStack.EMPTY;
                        net.minecraft.nbt.CompoundTag tag = sheathKatana.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                        tag.put("SheathBlade", otherStack.copy().save(player.registryAccess(), new net.minecraft.nbt.CompoundTag()));
                    });
                }
            }
        """.trimIndent())
        srcDir.resolve("CropsBehaviorTest.java").writeText("""
            package com.example;

            public class CropsBehaviorTest {
                void test(BonemealableBlock bm, GameTestHelper helper, BlockState young) {
                    bm.isValidBonemealTarget(helper.getLevel(), helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 0)), young, false);
                }
            }
        """.trimIndent())
        srcDir.resolve("BlockItemsTest.java").writeText("""
            package com.example;

            public class BlockItemsTest {
                boolean isRegistryField(java.lang.reflect.Field f) {
                    return RegistryObject.class.isAssignableFrom(f.getType());
                }
                String message() {
                    return "BlockItemRegistry has no RegistryObject fields";
                }
            }
        """.trimIndent())
        srcDir.resolve("SpecialItemsTest.java").writeText("""
            package com.example;

            import net.minecraft.world.food.FoodProperties;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            public class SpecialItemsTest {
                void test() {
                    Item item = ItemRegistry.HYDRA_RAMEN.get();
                    FoodProperties food = item.getFoodProperties();
                    int nutrition = food.getNutrition();
                }
            }
        """.trimIndent())
        srcDir.resolve("CuisinesTest.java").writeText("""
            package com.example;

            import cn.mcmod_mmf.mmlib.item.ItemFoodBase;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class CuisinesTest {
                void test() {
                    DeferredHolder<ItemFoodBase, ItemFoodBase> obj = FoodRegistry.CUISINES.get(null);
                }
            }
        """.trimIndent())
        srcDir.resolve("IronChainRecipeTest.java").writeText("""
            package com.example;

            import net.minecraft.core.RegistryAccess;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeManager;
            import java.util.Collection;

            public class IronChainRecipeTest {
                private static Recipe<?> recipe(GameTestHelper helper, String path) {
                    RecipeManager rm = helper.getLevel().getRecipeManager();
                    return rm.byKey(null).orElse(null);
                }
                private static java.util.Set<Item> computeReachable(java.util.Set<Item> seed, RecipeManager rm, RegistryAccess ra) {
                    Collection<Recipe<?>> all = rm.getRecipes();
                    for (Recipe<?> recipe : all) {
                        ItemStack result = recipe.getResultItem(ra);
                    }
                    return seed;
                }
                void test(GameTestHelper helper) {
                    Recipe<?> r = recipe(helper, "x");
                    ItemStack out = r.getResultItem(helper.getLevel().registryAccess());
                }
            }
        """.trimIndent())
        srcDir.resolve("SakuraVillagerTrades.java").writeText("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.trading.MerchantOffer;

            public class SakuraVillagerTrades {
                MerchantOffer getOffer(Item item, int count, int maxTrades, int xp, int coinAmount) {
                    ItemStack coinStack = new ItemStack(ItemRegistry.MATERIALS.get(SakuraNormalItemSet.COIN).get(), coinAmount);
                    ItemStack itemStack = new ItemStack(item, count);
                    return new MerchantOffer(itemStack, ItemStack.EMPTY, coinStack, maxTrades, xp, 0.05F);
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(projectDir)

        val lootRegistry = srcDir.resolve("LootModifiterRegistry.java").readText()
        val seedsDrop = srcDir.resolve("SeedsDrop.java").readText()
        val sheath = srcDir.resolve("SheathKeyPacket.java").readText()
        val crops = srcDir.resolve("CropsBehaviorTest.java").readText()
        val blockItems = srcDir.resolve("BlockItemsTest.java").readText()
        val specialItems = srcDir.resolve("SpecialItemsTest.java").readText()
        val cuisines = srcDir.resolve("CuisinesTest.java").readText()
        val recipes = srcDir.resolve("IronChainRecipeTest.java").readText()
        val trades = srcDir.resolve("SakuraVillagerTrades.java").readText()

        assertTrue(lootRegistry.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(lootRegistry.contains("DeferredRegister<MapCodec<? extends IGlobalLootModifier>> GLM"))
        assertTrue(lootRegistry.contains("DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<? extends IGlobalLootModifier>> SEEDSDROP"))
        assertTrue(!lootRegistry.contains("import com.mojang.serialization.Codec;"))
        assertTrue(seedsDrop.contains("public static final MapCodec<SeedDropModifier> CODEC"))
        assertTrue(seedsDrop.contains("RecordCodecBuilder"))
        assertTrue(seedsDrop.contains(".mapCodec("))
        assertTrue(!seedsDrop.contains(".create("))
        assertTrue(seedsDrop.contains("public MapCodec<? extends IGlobalLootModifier> codec()"))
        assertTrue(sheath.contains("if (!(ctx.player() instanceof ServerPlayer player)) return;"))
        assertTrue(sheath.contains("tag.put(\"SheathBlade\", otherStack.copy().save(player.registryAccess(), new net.minecraft.nbt.CompoundTag()));"))
        assertTrue(!sheath.contains("tag.put(\"SheathBlade\", otherStack.copy().save(player.registryAccess(), new net.minecraft.nbt.CompoundTag())));"))
        assertTrue(sheath.contains("sheathKatana.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));"))
        assertTrue(crops.contains("bm.isValidBonemealTarget(helper.getLevel(), helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 0)), young)"))
        assertTrue(!crops.contains(", false)"))
        assertTrue(blockItems.contains("DeferredHolder.class.isAssignableFrom(f.getType())"))
        assertTrue(blockItems.contains("BlockItemRegistry has no DeferredHolder fields"))
        assertTrue(specialItems.contains("import net.minecraft.core.component.DataComponents;"))
        assertTrue(specialItems.contains("ItemStack stack = new ItemStack(ItemRegistry.HYDRA_RAMEN.get());"))
        assertTrue(specialItems.contains("FoodProperties food = stack.get(DataComponents.FOOD);"))
        assertTrue(specialItems.contains("food.nutrition()"))
        assertTrue(cuisines.contains("import net.minecraft.world.item.Item;"))
        assertTrue(cuisines.contains("DeferredHolder<Item, ItemFoodBase> obj"))
        assertTrue(recipes.contains("import net.minecraft.world.item.crafting.RecipeHolder;"))
        assertTrue(recipes.contains("private static RecipeHolder<?> recipe("))
        assertTrue(recipes.contains("Collection<RecipeHolder<?>> all = rm.getRecipes();"))
        assertTrue(recipes.contains("for (RecipeHolder<?> holder : all)"))
        assertTrue(recipes.contains("Recipe<?> recipe = holder.value();"))
        assertTrue(recipes.contains("RecipeHolder<?> r = recipe(helper, \"x\");"))
        assertTrue(recipes.contains("r.value().getResultItem(helper.getLevel().registryAccess())"))
        assertTrue(recipes.contains("HolderLookup.Provider ra"))
        assertTrue(trades.contains("new MerchantOffer(new net.minecraft.world.item.trading.ItemCost(item, count), java.util.Optional.empty(), coinStack, maxTrades, xp, 0.05F)"))
    }

    @Test
    fun `migrates block entity fluid capability override to RegisterCapabilitiesEvent`() {
        val projectDir = createFile("ExampleMod.java", """
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    BlockEntitiesRegister.register(modEventBus);
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/BathtubBlockEntity.java").writeText("""
            package com.example;

            import com.modporter.compat.Capability;
            import com.modporter.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.fluids.capability.IFluidHandler;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
            import org.jetbrains.annotations.NotNull;
            import org.jetbrains.annotations.Nullable;

            public class BathtubBlockEntity extends BlockEntity {
                private final FluidTank fluidTank = new FluidTank(1000);
                private final LazyOptional<IFluidHandler> fluidHandler = LazyOptional.of(() -> fluidTank);

                public BathtubBlockEntity(BlockPos pos, BlockState state) {
                    super(BlockEntitiesRegister.BATHTUB_BLOCK_ENTITY.get(), pos, state);
                }

                @NotNull
                public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable net.minecraft.core.Direction side) {
                    if (cap == Capabilities.FluidHandler.BLOCK) {
                        return fluidHandler.cast();
                    }
                    return LazyOptional.empty();
                }

                public void invalidateCaps() {
                    fluidHandler.invalidate();
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/BlockEntitiesRegister.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.minecraft.world.level.block.entity.BlockEntityType;

            public class BlockEntitiesRegister {
                public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BathtubBlockEntity>> BATHTUB_BLOCK_ENTITY = null;
                public static void register(IEventBus eventBus) {}
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val blockEntity = tempDir.resolve("src/main/java/com/example/BathtubBlockEntity.java").readText()
        val mod = tempDir.resolve("src/main/java/com/example/ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-blockentity-fluid-capability" })
        assertTrue(result.changes.any { it.ruleId == "struct-blockentity-fluid-capability-listener" })
        assertTrue(blockEntity.contains("public static void registerCapabilities(RegisterCapabilitiesEvent event)"))
        assertTrue(blockEntity.contains("event.registerBlockEntity("))
        assertTrue(blockEntity.contains("Capabilities.FluidHandler.BLOCK"))
        assertTrue(blockEntity.contains("BlockEntitiesRegister.BATHTUB_BLOCK_ENTITY.get()"))
        assertTrue(blockEntity.contains("blockEntity.getFluidTank()"))
        assertTrue(blockEntity.contains("public FluidTank getFluidTank()"))
        assertTrue(!blockEntity.contains("getCapability("))
        assertTrue(!blockEntity.contains("LazyOptional"))
        assertTrue(mod.contains("modEventBus.addListener(BathtubBlockEntity::registerCapabilities);"))
    }

    @Test
    fun `migrates CraftingInput recipe assemble RegistryAccess parameter to HolderLookup provider`() {
        val projectDir = createFile("UpgradeRecipe.java", """
            package com.example;

            import net.minecraft.core.RegistryAccess;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.CustomRecipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class UpgradeRecipe extends CustomRecipe {
                public UpgradeRecipe(CraftingBookCategory category) {
                    super(category);
                }

                @Override
                public ItemStack assemble(CraftingInput input, RegistryAccess registryAccess) {
                    return ItemStack.EMPTY;
                }

                @Override
                public boolean matches(CraftingInput input, net.minecraft.world.level.Level level) {
                    return false;
                }

                @Override
                public boolean canCraftInDimensions(int width, int height) {
                    return true;
                }

                @Override
                public RecipeSerializer<?> getSerializer() {
                    return null;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val recipe = tempDir.resolve("src/main/java/com/example/UpgradeRecipe.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(recipe.contains("import net.minecraft.core.HolderLookup;"))
        assertTrue(recipe.contains("public ItemStack assemble(CraftingInput input, HolderLookup.Provider registryAccess)"))
        assertFalse(recipe.contains("RegistryAccess"))
    }

    @Test
    fun `migrates RecipeManager byKey RecipeHolder lambda access to value`() {
        val projectDir = createFile("RecipeXp.java", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.level.Level;

            public class RecipeXp {
                public void grant(Level world, java.util.Map.Entry<ResourceLocation, Integer> entry) {
                    world.getRecipeManager().byKey(entry.getKey()).ifPresent(recipe -> use(((CookingRecipe) recipe.get()).getExperience()));
                }

                private void use(float value) {}

                interface CookingRecipe extends Recipe<net.minecraft.world.item.crafting.RecipeInput> {
                    float getExperience();
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val recipe = tempDir.resolve("src/main/java/com/example/RecipeXp.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(recipe.contains("((CookingRecipe) recipe.value()).getExperience()"))
        assertFalse(recipe.contains("recipe.get()).getExperience()"))
    }

    @Test
    fun `migrates source shaped block entity item and fluid capabilities to RegisterCapabilitiesEvent`() {
        val projectDir = createFile("ExampleMod.java", """
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    BlockEntityRegistry.BLOCK_ENTITIES.register(modEventBus);
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/MachineBlockEntity.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            public abstract class MachineBlockEntity extends BlockEntity {
                public MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/CookingBlockEntity.java").writeText("""
            package com.example;

            import com.modporter.generated.example.compat.Capability;
            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
            import net.neoforged.neoforge.items.IItemHandler;

            public class CookingBlockEntity extends MachineBlockEntity {
                private LazyOptional<IItemHandler> inputHandler;
                private LazyOptional<IItemHandler> outputHandler;
                private LazyOptional<FluidTank> fluidTank;

                public CookingBlockEntity(BlockPos pos, BlockState state) {
                    super(BlockEntityRegistry.COOKING.get(), pos, state);
                    this.inputHandler = LazyOptional.of(() -> createInput());
                    this.outputHandler = LazyOptional.of(() -> createOutput());
                    this.fluidTank = LazyOptional.of(() -> createTank());
                }

                public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                    if (!this.isRemoved()) {
                        if (cap.equals(Capabilities.ItemHandler.BLOCK)) {
                            if (side == null || side.equals(Direction.UP)) {
                                return inputHandler.cast();
                            } else {
                                return outputHandler.cast();
                            }
                        }
                        if (cap.equals(Capabilities.FluidHandler.BLOCK)) {
                            return this.fluidTank.cast();
                        }
                    }
                    return super.getCapability(cap, side);
                }

                @Override
                public void reviveCaps() {
                    super.reviveCaps();
                    inputHandler = LazyOptional.of(() -> createInput());
                    outputHandler = LazyOptional.of(() -> createOutput());
                    fluidTank = LazyOptional.of(() -> createTank());
                }

                private IItemHandler createInput() { return null; }
                private IItemHandler createOutput() { return null; }
                private FluidTank createTank() { return null; }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/BlockEntityRegistry.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class BlockEntityRegistry {
                public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = null;
                public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CookingBlockEntity>> COOKING = null;
                public static void register(IEventBus eventBus) {}
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val blockEntity = tempDir.resolve("src/main/java/com/example/CookingBlockEntity.java").readText()
        val mod = tempDir.resolve("src/main/java/com/example/ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-blockentity-capability-provider" })
        assertTrue(result.changes.any { it.ruleId == "struct-blockentity-capability-provider-listener" })
        assertTrue(blockEntity.contains("event.registerBlockEntity(\n                Capabilities.ItemHandler.BLOCK,\n                BlockEntityRegistry.COOKING.get(),"))
        assertTrue(blockEntity.contains("side == null || side.equals(Direction.UP) ? blockEntity.inputHandler.orElse(null) : blockEntity.outputHandler.orElse(null)"))
        assertTrue(blockEntity.contains("event.registerBlockEntity(\n                Capabilities.FluidHandler.BLOCK,\n                BlockEntityRegistry.COOKING.get(),"))
        assertTrue(blockEntity.contains("blockEntity.fluidTank.orElse(null)"))
        assertTrue(blockEntity.contains("blockEntity.isRemoved() ? null"))
        assertTrue(blockEntity.contains("public void clearRemoved()"))
        assertTrue(blockEntity.contains("super.clearRemoved();"))
        assertFalse(blockEntity.contains("getCapability("))
        assertFalse(blockEntity.contains("super.getCapability"))
        assertFalse(blockEntity.contains("reviveCaps"))
        assertFalse(blockEntity.contains("import com.modporter.generated.example.compat.Capability;"))
        assertTrue(mod.contains("modEventBus.addListener(CookingBlockEntity::registerCapabilities);"))
    }

    @Test
    fun `migrates additional NeoForge 121 block entity and client API surfaces`() {
        val projectDir = createFile("BaseInventoryEntity.java", """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.NonNullList;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.entity.player.Inventory;
            import net.minecraft.world.inventory.AbstractContainerMenu;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            public abstract class BaseInventoryEntity extends BaseContainerBlockEntity {
                protected NonNullList<ItemStack> items;
                public BaseInventoryEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                    this.items = NonNullList.withSize(1, ItemStack.EMPTY);
                }
                @Override
                protected Component getDefaultName() { return Component.literal("x"); }
                @Override
                protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return null; }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/SyncEntity.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.Connection;
            import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockState;

            public class SyncEntity extends BaseInventoryEntity {
                public SyncEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
                    super(type, pos, state);
                }
                @Override
                public CompoundTag getUpdateTag() {
                    CompoundTag tag = super.getUpdateTag();
                    saveAdditional(tag);
                    return tag;
                }
                @Override
                public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
                    return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
                }
                @Override
                public void onDataPacket(net.minecraft.network.Connection net, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt) {
                    net.minecraft.nbt.CompoundTag tag = pkt.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    if (tag != null) {
                        this.load(tag);
                    }
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/TooltipBlock.java").writeText("""
            package com.example;

            import java.util.List;
            import javax.annotation.Nullable;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.BlockItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import net.minecraft.world.level.BlockGetter;

            public class TooltipBlock {
                public void appendHoverText(ItemStack stack, @Nullable BlockGetter level, List<Component> tooltip, TooltipFlag flag) {
                    CompoundTag tag = BlockItem.getBlockEntityData(stack);
                    super.appendHoverText(stack, level, tooltip, flag);
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/WaterRenderer.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.VertexConsumer;
            import org.joml.Matrix4f;

            public class WaterRenderer {
                void add(VertexConsumer builder, Matrix4f matrix, int packedLight, float r, float g, float b, float a, float nx, float ny, float nz) {
                    builder.addVertex(matrix, 0, 0, 0)
                           .color(r, g, b, a)
                           .setUv(0, 0)
                           .overlayCoords(0, 10)
                           .uv2(packedLight)
                           .normal(nx, ny, nz);
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/SeatEntity.java").writeText("""
            package com.example;

            import net.minecraft.network.protocol.Packet;
            import net.minecraft.network.protocol.game.ClientGamePacketListener;
            import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
            import net.minecraft.world.entity.Entity;

            public class SeatEntity extends Entity {
                @Override
                public Packet<ClientGamePacketListener> getAddEntityPacket() {
                    return new ClientboundAddEntityPacket(this);
                }
            }
        """.trimIndent())
        tempDir.resolve("src/main/java/com/example/FacingBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.HorizontalDirectionalBlock;

            public class FacingBlock extends HorizontalDirectionalBlock {
                public FacingBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val inventory = tempDir.resolve("src/main/java/com/example/BaseInventoryEntity.java").readText()
        val sync = tempDir.resolve("src/main/java/com/example/SyncEntity.java").readText()
        val tooltip = tempDir.resolve("src/main/java/com/example/TooltipBlock.java").readText()
        val renderer = tempDir.resolve("src/main/java/com/example/WaterRenderer.java").readText()
        val entity = tempDir.resolve("src/main/java/com/example/SeatEntity.java").readText()
        val block = tempDir.resolve("src/main/java/com/example/FacingBlock.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-base-container-items-accessors" })
        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(result.changes.any { it.ruleId == "struct-remove-legacy-entity-spawn-packet" })
        assertTrue(result.changes.any { it.ruleId == "struct-base-entity-block-codec" })
        assertTrue(inventory.contains("protected NonNullList<ItemStack> getItems()"))
        assertTrue(inventory.contains("protected void setItems(NonNullList<ItemStack> items)"))
        assertTrue(sync.contains("getUpdateTag(HolderLookup.Provider registries)"))
        assertTrue(sync.contains("saveAdditional(tag, registries);"))
        assertTrue(sync.contains("onDataPacket(net.minecraft.network.Connection connection, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider)"))
        assertTrue(sync.contains("CompoundTag tag = pkt.getTag();"))
        assertTrue(sync.contains("loadWithComponents(tag, lookupProvider);"))
        assertTrue(tooltip.contains("Item.TooltipContext level"))
        assertTrue(tooltip.contains("stack.getOrDefault(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"))
        assertTrue(renderer.contains(".setColor(r, g, b, a)"))
        assertTrue(renderer.contains(".setOverlay(OverlayTexture.NO_OVERLAY)"))
        assertTrue(renderer.contains(".setLight(packedLight)"))
        assertTrue(renderer.contains(".setNormal(nx, ny, nz)"))
        assertTrue(!entity.contains("getAddEntityPacket"))
        assertTrue(block.contains("MapCodec<FacingBlock> CODEC = simpleCodec(FacingBlock::new);"))
        assertTrue(block.contains("MapCodec<? extends net.minecraft.world.level.block.HorizontalDirectionalBlock> codec()"))
    }

    @Test
    fun `migrates vertex helper matrix normal pair to transformed normal vector`() {
        val projectDir = createFile("ShojiRenderer.java", """
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import org.joml.Matrix3f;
            import org.joml.Matrix4f;

            public class ShojiRenderer {
                public void render(PoseStack poseStack, VertexConsumer builder, int packedLight, int packedOverlay) {
                    Matrix4f matrix = poseStack.last().pose();
                    Matrix3f normal = poseStack.last().normal();
                    addVertex(builder, matrix, normal, 1, 2, 3, 0, 0, 1, 0, 0, packedLight, packedOverlay);
                }

                private void addVertex(VertexConsumer builder, Matrix4f pose, Matrix3f normal,
                        float x, float y, float z, float u, float v,
                        float nx, float ny, float nz,
                        int packedLight, int packedOverlay) {
                    builder.addVertex(pose, x, y, z)
                            .setUv(u, v)
                            .setOverlay(packedOverlay)
                            .setLight(packedLight)
                            .setNormal(normal, nx, ny, nz);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val renderer = tempDir.resolve("src/main/java/com/example/ShojiRenderer.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(renderer.contains("Matrix4f matrix = poseStack.last().pose();"))
        assertTrue(renderer.contains("Matrix3f normal = poseStack.last().normal();"))
        assertTrue(renderer.contains("private void addVertex(VertexConsumer builder, Matrix4f pose, Matrix3f normal,"))
        assertTrue(renderer.contains("org.joml.Vector3f transformedNormal = normal.transform(nx, ny, nz, new org.joml.Vector3f());"))
        assertTrue(renderer.contains(".setNormal(transformedNormal.x(), transformedNormal.y(), transformedNormal.z())"))
        assertTrue(!renderer.contains(".setNormal(normal, nx, ny, nz)"))
    }

    @Test
    fun `migrates item stack fortune and legacy random hurt APIs`() {
        val projectDir = createFile("ChoppingBoardBlockEntity.java", """
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantments;

            public class ChoppingBoardBlockEntity {
                Object level;

                public void process(ItemStack toolStack) {
                    int fortune = toolStack.getEnchantmentLevel(Enchantments.BLOCK_FORTUNE);
                    if (toolStack.hurt(1, level.random, null)) {
                        toolStack.setCount(0);
                    }
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val transformed = tempDir.resolve("src/main/java/com/example/ChoppingBoardBlockEntity.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("level.registryAccess().lookup(net.minecraft.core.registries.Registries.ENCHANTMENT)"))
        assertTrue(transformed.contains(".flatMap(reg -> reg.get(Enchantments.FORTUNE))"))
        assertTrue(transformed.contains("EnchantmentHelper.getItemEnchantmentLevel(holder, toolStack)"))
        assertTrue(transformed.contains("toolStack.setDamageValue(toolStack.getDamageValue() + 1);"))
        assertTrue(transformed.contains("if (toolStack.getDamageValue() >= toolStack.getMaxDamage())"))
        assertTrue(!transformed.contains("Enchantments.BLOCK_FORTUNE"))
        assertTrue(!transformed.contains("toolStack.hurt(1, level.random, null)"))
    }

    @Test
    fun `migrates raider spawn equipment and raid enchantment APIs`() {
        val projectDir = createFile("SamuraiIllagerEntity.java", """
            package com.example;

            import javax.annotation.Nullable;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.Difficulty;
            import net.minecraft.world.DifficultyInstance;
            import net.minecraft.world.entity.MobSpawnType;
            import net.minecraft.world.entity.SpawnGroupData;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.minecraft.world.level.ServerLevelAccessor;

            public class SamuraiIllagerEntity {
                Object random;

                @Nullable
                @Override
                public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                        MobSpawnType spawnType, @Nullable SpawnGroupData spawnData, @Nullable CompoundTag tag) {
                    spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag);
                    populateDefaultEquipmentSlots(this.random, difficulty);
                    populateDefaultEquipmentEnchantments(this.random, difficulty);
                    return spawnData;
                }

                @Override
                protected void populateDefaultEquipmentEnchantments(net.minecraft.util.RandomSource random, DifficultyInstance difficulty) {
                    super.populateDefaultEquipmentEnchantments(random, difficulty);
                    ItemStack mainhand = this.getMainHandItem();
                    EnchantmentHelper.enchantItem(this.random, mainhand, 5 + difficulty.getDifficulty().getId() * this.random.nextInt(6), false);
                }

                @Override
                public void applyRaidBuffs(int wave, boolean hasBonus) {
                    ItemStack weapon = new ItemStack();
                    net.minecraft.world.entity.raid.Raid raid = this.getCurrentRaid();
                    if (raid != null) {
                        int enchLevel = 1;
                        if (wave > raid.getNumGroups(Difficulty.NORMAL)) {
                            enchLevel = 2;
                        }
                        boolean shouldEnchant = this.random.nextFloat() <= raid.getEnchantOdds();
                        if (shouldEnchant) {
                            EnchantmentHelper.enchantItem(this.random, weapon, 5 + enchLevel * 3, false);
                        }
                    }
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val transformed = tempDir.resolve("src/main/java/com/example/SamuraiIllagerEntity.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("import net.minecraft.server.level.ServerLevel;"))
        assertTrue(transformed.contains("import net.minecraft.util.RandomSource;"))
        assertTrue(transformed.contains("import net.minecraft.world.item.enchantment.providers.VanillaEnchantmentProviders;"))
        assertTrue(!transformed.contains("import net.minecraft.nbt.CompoundTag;"))
        assertTrue(transformed.contains("MobSpawnType spawnType, @Nullable SpawnGroupData spawnData)"))
        assertTrue(transformed.contains("spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnData);"))
        assertTrue(transformed.contains("RandomSource randomsource = level.getRandom();"))
        assertTrue(transformed.contains("populateDefaultEquipmentEnchantments(level, randomsource, difficulty);"))
        assertTrue(transformed.contains("protected void populateDefaultEquipmentEnchantments(ServerLevelAccessor level, RandomSource random, DifficultyInstance difficulty)"))
        assertTrue(transformed.contains("super.populateDefaultEquipmentEnchantments(level, random, difficulty);"))
        assertTrue(transformed.contains("EnchantmentHelper.enchantItemFromProvider(mainhand, level.registryAccess(), VanillaEnchantmentProviders.MOB_SPAWN_EQUIPMENT, difficulty, random);"))
        assertTrue(transformed.contains("public void applyRaidBuffs(ServerLevel level, int wave, boolean hasBonus)"))
        assertTrue(transformed.contains("VanillaEnchantmentProviders.RAID_VINDICATOR_POST_WAVE_5"))
        assertTrue(transformed.contains("VanillaEnchantmentProviders.RAID_VINDICATOR"))
        assertTrue(transformed.contains("EnchantmentHelper.enchantItemFromProvider(weapon, level.registryAccess(), resourcekey, level.getCurrentDifficultyAt(this.blockPosition()), this.random);"))
        assertTrue(!transformed.contains("@Nullable CompoundTag tag"))
        assertTrue(!transformed.contains("EnchantmentHelper.enchantItem(this.random"))
    }

    @Test
    fun `migrates finalizeSpawn with arbitrary parameter names`() {
        val projectDir = createFile("Minotaur.java", """
            package com.example;

            import javax.annotation.Nullable;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.DifficultyInstance;
            import net.minecraft.world.entity.MobSpawnType;
            import net.minecraft.world.entity.SpawnGroupData;
            import net.minecraft.world.level.ServerLevelAccessor;

            public class Minotaur {
                Minotaur helper;

                @Override
                public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty,
                        MobSpawnType reason, @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
                    data = super.finalizeSpawn(accessor, difficulty, reason, data, tag);
                    helper.finalizeSpawn(accessor, difficulty, MobSpawnType.NATURAL, data, tag);
                    populateDefaultEquipmentSlots(this.random, difficulty);
                    populateDefaultEquipmentEnchantments(this.random, difficulty);
                    return data;
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val transformed = tempDir.resolve("src/main/java/com/example/Minotaur.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("import net.minecraft.util.RandomSource;"))
        assertTrue(transformed.contains("MobSpawnType reason, @Nullable SpawnGroupData data)"))
        assertTrue(transformed.contains("data = super.finalizeSpawn(accessor, difficulty, reason, data);"))
        assertTrue(transformed.contains("helper.finalizeSpawn(accessor, difficulty, MobSpawnType.NATURAL, data);"))
        assertTrue(transformed.contains("RandomSource randomsource = accessor.getRandom();"))
        assertTrue(transformed.contains("populateDefaultEquipmentEnchantments(accessor, randomsource, difficulty);"))
        assertTrue(!transformed.contains("@Nullable CompoundTag tag"))
    }

    @Test
    fun `migrates legacy mmlib datagen helpers without disabling providers`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("SakuraBlockStateProvider.java").writeText("""
            package com.example;

            import cn.mcmod_mmf.mmlib.data.AbstractBlockStateProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.SlabBlock;
            import net.minecraft.world.level.block.state.properties.BlockStateProperties;
            import net.neoforged.neoforge.common.data.ExistingFileHelper;

            public class SakuraBlockStateProvider extends AbstractBlockStateProvider {
                public SakuraBlockStateProvider(PackOutput out, String modid, ExistingFileHelper helper) {
                    super(out, modid, helper);
                }

                @Override
                protected void registerStatesAndModels() {
                    log(BlockRegistry.SAKURA_LOG);
                    crossBlock(BlockRegistry.SAKURA_SAPLING);
                    stageBlock(BlockRegistry.BUCKWHEAT_CROP, BlockStateProperties.AGE_7);
                    facingSlabBlock(BlockRegistry.TATAMI_SLAB, texture("tatami"), texture("tatami"), texture("tatami"));
                }
            }
        """.trimIndent())
        srcDir.resolve("SakuraItemModelProvider.java").writeText("""
            package com.example;

            import cn.mcmod_mmf.mmlib.data.AbstractItemModelProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.world.item.BlockItem;
            import net.minecraft.world.level.block.BushBlock;
            import net.neoforged.neoforge.common.data.ExistingFileHelper;

            public class SakuraItemModelProvider extends AbstractItemModelProvider {
                public SakuraItemModelProvider(PackOutput out, String modid, ExistingFileHelper helper) {
                    super(out, modid, helper);
                }

                @Override
                protected void registerModels() {
                    BlockItem blockItem = (BlockItem) item.get();
                    if (blockItem.getBlock() instanceof BushBlock)
                        bushItem(item);
                    else
                        itemBlock(blockItem::getBlock);
                    normalItem(item);
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(tempDir)
        val blockProvider = srcDir.resolve("SakuraBlockStateProvider.java").readText()
        val itemProvider = srcDir.resolve("SakuraItemModelProvider.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(blockProvider.contains("import java.util.function.Supplier;"))
        assertTrue(blockProvider.contains("log(BlockRegistry.SAKURA_LOG::get);"))
        assertTrue(blockProvider.contains("crossBlock(BlockRegistry.SAKURA_SAPLING::get);"))
        assertTrue(blockProvider.contains("stageBlock(BlockRegistry.BUCKWHEAT_CROP::get, BlockStateProperties.AGE_7);"))
        assertTrue(blockProvider.contains("facingSlabBlock(BlockRegistry.TATAMI_SLAB::get, texture(\"tatami\"), texture(\"tatami\"), texture(\"tatami\"));"))
        assertTrue(blockProvider.contains("private void stageBlock(Supplier<? extends Block> blockSupplier, IntegerProperty ageProperty, Property<?>... ignored)"))
        assertTrue(blockProvider.contains("models().crop(modelName, texture(modelName))"))
        assertTrue(blockProvider.contains("private void facingSlabBlock(Supplier<? extends SlabBlock> slabSupplier"))
        assertTrue(blockProvider.contains("cn.mcmod_mmf.mmlib.block.FacingSlab.FACING"))
        assertTrue(itemProvider.contains("itemBlockFlat(() -> blockItem.getBlock());"))
        assertTrue(itemProvider.contains("toBlock(() -> blockItem.getBlock());"))
        assertTrue(itemProvider.contains("singleTex(item);"))
        assertTrue(!itemProvider.contains("bushItem(item);"))
        assertTrue(!itemProvider.contains("normalItem(item);"))
    }

    @Test
    fun `migrates direct DistExecutor expression without dropping nested call parentheses`() {
        val projectDir = createFile("ClientOnlyHelper.java", """
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.fml.DistExecutor;

            public class ClientOnlyHelper {
                void stop(Object handle) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ShowerHeadClientHelper.stopBathEffect(handle));
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val result = pass.apply(projectDir)
        val migrated = tempDir.resolve("src/main/java/com/example/ClientOnlyHelper.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-dist-executor-dist-guard" })
        assertTrue(migrated.contains("if (FMLLoader.getDist() == Dist.CLIENT)"))
        assertTrue(migrated.contains("ShowerHeadClientHelper.stopBathEffect(handle);"))
        assertTrue(!migrated.contains("DistExecutor"))
    }

    @Test
    fun `migrates Sakura recipe holders without relying on recipe instance ids`() {
        val srcDir = tempDir.resolve("src/main/java/cn/mcmod/sakura")
        val recipesDir = srcDir.resolve("recipes")
        val blockEntityDir = srcDir.resolve("block/entity")
        val builderDir = srcDir.resolve("data/builder")
        recipesDir.createDirectories()
        blockEntityDir.createDirectories()
        builderDir.createDirectories()

        recipesDir.resolve("RecipeTypeRegistry.java").writeText("""
            package cn.mcmod.sakura.recipes;

            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class RecipeTypeRegistry {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
                        DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, "sakura");
            }
        """.trimIndent())
        recipesDir.resolve("CookingPotRecipe.java").writeText("""
            package cn.mcmod.sakura.recipes;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.RegistryAccess;
            import net.minecraft.world.item.ItemStack;

            public class CookingPotRecipe {
                @Override
                public ItemStack getResultItem(RegistryAccess pRegistryAccess) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent())
        blockEntityDir.resolve("CookingPotBlockEntity.java").writeText("""
            package cn.mcmod.sakura.block.entity;

            import cn.mcmod.sakura.recipes.CookingPotRecipe;
            import cn.mcmod.sakura.recipes.RecipeTypeRegistry;
            import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
            import javax.annotation.Nullable;
            import java.util.List;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeHolder;

            public class CookingPotBlockEntity {
                private ResourceLocation lastRecipeID;
                private Object level;
                private Object inventoryWrapper;
                private Object2IntOpenHashMap<ResourceLocation> experienceTracker;

                private void roll(CookingPotRecipe recipe) {
                    List<ItemStack> results = recipe.rollByproducts(null, 0);
                    for (ItemStack resultStack : results) {
                    }
                }

                private void findRecipe() {
                    List<CookingPotRecipe> recipes = level.getRecipeManager().getRecipesFor(RecipeTypeRegistry.COOKING_RECIPE_TYPE.get(),
                            inventoryWrapper, level).stream().map(RecipeHolder::value).toList();
                    for(CookingPotRecipe recipe : recipes) {
                        if(recipe.matchesWithFluid(null, inventoryWrapper, level)) {
                          lastRecipeID = recipe.getId();
                        }
                    }
                }

                private void finish(CookingPotRecipe recipe) {
                    trackRecipeExperience(recipe);
                }

                public void trackRecipeExperience(@Nullable Recipe<?> recipe) {
                    if (recipe != null) {
                        if (!(recipe instanceof cn.mcmod_mmf.mmlib.recipe.AbstractRecipe abstractRecipe)) {
                            return;
                        }
                        ResourceLocation recipeID = abstractRecipe.getId();
                        experienceTracker.addTo(recipeID, 1);
                    }
                }
            }
        """.trimIndent())
        builderDir.resolve("CookingPotRecipeBuilder.java").writeText("""
            package cn.mcmod.sakura.data.builder;

            import cn.mcmod.sakura.recipes.CookingPotRecipe;
            import net.minecraft.resources.ResourceLocation;

            public class CookingPotRecipeBuilder {
                public static class Result {
                    private final CookingPotRecipe recipe = new CookingPotRecipe();

                    public Result(ResourceLocation id) {
                        recipe.setId(id);
                    }

                    public ResourceLocation getId() {
                        return recipe.getId();
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val registry = recipesDir.resolve("RecipeTypeRegistry.java").readText()
        val recipe = recipesDir.resolve("CookingPotRecipe.java").readText()
        val blockEntity = blockEntityDir.resolve("CookingPotBlockEntity.java").readText()
        val builder = builderDir.resolve("CookingPotRecipeBuilder.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(registry.contains("DeferredRegister.create(Registries.RECIPE_SERIALIZER, \"sakura\")"))
        assertTrue(!registry.contains("BuiltInRegistries.RECIPE_SERIALIZER"))
        assertTrue(recipe.contains("getResultItem(HolderLookup.Provider pRegistryAccess)"))
        assertTrue(!recipe.contains("RegistryAccess pRegistryAccess"))
        assertTrue(blockEntity.contains("List<RecipeHolder<CookingPotRecipe>> recipes = level.getRecipeManager().getRecipesFor"))
        assertTrue(blockEntity.contains("for (RecipeHolder<CookingPotRecipe> holder : recipes)"))
        assertTrue(blockEntity.contains("CookingPotRecipe recipe = holder.value();"))
        assertTrue(blockEntity.contains("lastRecipeID = holder.id();"))
        assertTrue(blockEntity.contains("trackRecipeExperience(lastRecipeID);"))
        assertTrue(blockEntity.contains("trackRecipeExperience(@Nullable ResourceLocation recipeId)"))
        assertTrue(blockEntity.contains("List<ItemStack> results = recipe.rollByproducts(null, 0);"))
        assertTrue(!blockEntity.contains("RecipeHolder<ItemStack>"))
        assertTrue(builder.contains("private final ResourceLocation id;"))
        assertTrue(builder.contains("this.id = id;"))
        assertTrue(builder.contains("return this.id;"))
        assertTrue(!builder.contains("recipe.setId(id);"))
        assertTrue(!builder.contains("recipe.getId()"))
        assertTrue(!blockEntity.contains("cn.mcmod_mmf.mmlib.recipe.AbstractRecipe"))
        assertTrue(!blockEntity.contains("recipe.getId()"))
    }

    @Test
    fun `migrates potion components and complex spawn data without placeholders`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("PotionItem.java").writeText("""
            package com.example;

            import java.util.List;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import net.minecraft.world.item.alchemy.PotionUtils;
            import net.minecraft.world.item.alchemy.Potions;

            class PotionItem {
                boolean visible(ItemStack stack) {
                    return PotionUtils.getPotion(stack) != Potions.EMPTY;
                }

                int color(ItemStack stack) {
                    return PotionUtils.getColor(stack);
                }

                boolean empty(ItemStack stack) {
                    return PotionUtils.getMobEffects(stack).isEmpty();
                }

                void apply(ItemStack stack) {
                    for (MobEffectInstance effect : PotionUtils.getMobEffects(stack)) {
                        effect.getAmplifier();
                    }
                }

                void set(ItemStack stack) {
                    PotionUtils.setPotion(stack, Potions.EMPTY);
                }

                void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
                    PotionUtils.addPotionTooltip(stack, tooltip, 1.0F);
                }
            }
        """.trimIndent())
        srcDir.resolve("SpawnEntity.java").writeText("""
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.entity.IEntityAdditionalSpawnData;

            class SpawnEntity implements IEntityAdditionalSpawnData {
                @Override
                public void writeSpawnData(FriendlyByteBuf buffer) {
                }

                @Override
                public void readSpawnData(FriendlyByteBuf additionalData) {
                }
            }
        """.trimIndent())
        srcDir.resolve("ProjectileEntity.java").writeText("""
            package com.example;

            import net.minecraft.network.protocol.Packet;
            import net.minecraft.network.protocol.game.ClientGamePacketListener;
            import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
            import net.neoforged.neoforge.network.NetworkHooks;

            class ProjectileEntity {
                public Packet<ClientGamePacketListener> getAddEntityPacket() {
                    return NetworkHooks.getEntitySpawningPacket(this);
                }

                public void recreateFromPacket(ClientboundAddEntityPacket packet) {
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val potion = srcDir.resolve("PotionItem.java").readText()
        val spawn = srcDir.resolve("SpawnEntity.java").readText()
        val projectile = srcDir.resolve("ProjectileEntity.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(potion.contains("stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion().isPresent()"))
        assertTrue(potion.contains("stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getColor()"))
        assertTrue(potion.contains("!stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getAllEffects().iterator().hasNext()"))
        assertTrue(potion.contains("for (MobEffectInstance effect : stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getAllEffects())"))
        assertTrue(potion.contains("stack.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)"))
        assertTrue(potion.contains("stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).addPotionTooltip(tooltip::add, 1.0F, 20.0F)"))
        assertTrue(!potion.contains("PotionUtils"))
        assertTrue(!potion.contains("TODO"))
        assertTrue(!potion.contains("[forge2neo]"))

        assertTrue(spawn.contains("import net.minecraft.network.RegistryFriendlyByteBuf;"))
        assertTrue(spawn.contains("import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;"))
        assertTrue(spawn.contains("class SpawnEntity implements IEntityWithComplexSpawn"))
        assertTrue(spawn.contains("writeSpawnData(RegistryFriendlyByteBuf buffer)"))
        assertTrue(spawn.contains("readSpawnData(RegistryFriendlyByteBuf additionalData)"))
        assertTrue(!spawn.contains("import net.minecraft.network.FriendlyByteBuf;"))
        assertTrue(!spawn.contains("IEntityAdditionalSpawnData"))

        assertTrue(projectile.contains("import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;"))
        assertTrue(projectile.contains("recreateFromPacket(ClientboundAddEntityPacket packet)"))
        assertTrue(!projectile.contains("getAddEntityPacket"))
        assertTrue(!projectile.contains("NetworkHooks"))
        assertTrue(!projectile.contains("ClientGamePacketListener"))
        assertTrue(!projectile.contains("import net.minecraft.network.protocol.Packet;"))
    }

    @Test
    fun `migrates removed entity riding offset center expression`() {
        val projectDir = createFile("EntityCenter.java", """
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.phys.Vec3;

            public class EntityCenter {
                public Vec3 center(Entity entity) {
                    return new Vec3(entity.getX(), entity.getY() - entity.getMyRidingOffset() + entity.getBbHeight() / 2, entity.getZ());
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(projectDir)
        val transformed = tempDir.resolve("src/main/java/com/example/EntityCenter.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("return entity.getBoundingBox().getCenter();"))
        assertTrue(!transformed.contains("getMyRidingOffset"))
    }

    @Test
    fun `migrates record accessors advancement strategy and synced data buffers`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("EntityShape.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityDimensions;

            public class EntityShape {
                public float width(Entity entity, EntityDimensions dimensions) {
                    return entity.dimensions.width + dimensions.height;
                }

                public boolean fixed(Entity entity) {
                    return entity.dimensions.fixed;
                }
            }
        """.trimIndent())
        srcDir.resolve("GroupedRequirements.java").writeText("""
            package com.example;

            import net.minecraft.advancements.AdvancementRequirements;
            import java.util.Collection;

            public record GroupedRequirements() implements AdvancementRequirements.Strategy {
                @Override
                public String[][] createRequirements(Collection<String> strings) {
                    return new String[][] { strings.toArray(String[]::new) };
                }
            }
        """.trimIndent())
        srcDir.resolve("SyncedPacket.java").writeText("""
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.syncher.SynchedEntityData;

            public class SyncedPacket {
                public void encode(FriendlyByteBuf buffer, SynchedEntityData.DataValue<?> dataValue) {
                    dataValue.write(buffer);
                }

                public SynchedEntityData.DataValue<?> decode(FriendlyByteBuf buffer, int id) {
                    return SynchedEntityData.DataValue.read(buffer, id);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val entityShape = srcDir.resolve("EntityShape.java").readText()
        val groupedRequirements = srcDir.resolve("GroupedRequirements.java").readText()
        val syncedPacket = srcDir.resolve("SyncedPacket.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(entityShape.contains("entity.dimensions.width() + dimensions.height()"))
        assertTrue(entityShape.contains("entity.dimensions.fixed()"))
        assertTrue(!entityShape.contains(".dimensions.width "))
        assertTrue(groupedRequirements.contains("public AdvancementRequirements create(Collection<String> strings)"))
        assertTrue(groupedRequirements.contains("return new AdvancementRequirements("))
        assertTrue(!groupedRequirements.contains("return new String[][]"))
        assertTrue(!groupedRequirements.contains("createRequirements("))
        assertTrue(syncedPacket.contains("import net.minecraft.network.RegistryFriendlyByteBuf;"))
        assertTrue(!syncedPacket.contains("import net.minecraft.network.FriendlyByteBuf;"))
        assertTrue(syncedPacket.contains("encode(RegistryFriendlyByteBuf buffer"))
        assertTrue(syncedPacket.contains("decode(RegistryFriendlyByteBuf buffer"))
    }

    @Test
    fun `migrates vanilla map decoration record shape without touching entity coordinate getters`() {
        val projectDir = createFile("MapMarker.java", """
            package com.example;

            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            public class MapMarker {
                public void move(Player player, MapItemSavedData mapdata) {
                    int x = (int) player.getX();
                    MapDecoration decoration = mapdata.decorations.get(player.getName().getString());
                    if (decoration != null) {
                        mapdata.decorations.put(player.getName().getString(), new MapDecoration(MapDecoration.Type.PLAYER_OFF_MAP, decoration.getX(), decoration.getY(), decoration.getRot(), null));
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(projectDir)
        val transformed = tempDir.resolve("src/main/java/com/example/MapMarker.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;"))
        assertTrue(transformed.contains("player.getX()"))
        assertTrue(transformed.contains("new MapDecoration(MapDecorationTypes.PLAYER_OFF_MAP, decoration.x(), decoration.y(), decoration.rot(), java.util.Optional.empty())"))
        assertTrue(!transformed.contains("MapDecoration.Type."))
        assertTrue(!transformed.contains("decoration.getX()"))
    }

    @Test
    fun `useItemOn maps cross file InteractionResult helper returns`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Lightable.java").writeText("""
            package com.example;

            import net.minecraft.world.InteractionResult;

            public interface Lightable {
                default InteractionResult light(Object state, Object level) {
                    return InteractionResult.PASS;
                }
            }
        """.trimIndent())
        srcDir.resolve("CandleBlock.java").writeText("""
            package com.example;

            public class CandleBlock implements Lightable {
                protected net.minecraft.world.ItemInteractionResult useItemOn(Object stack, Object state, Object level, Object pos, Object player, Object hand, Object hit) {
                    return this.light(state, level);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("CandleBlock.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-useitemon-interactionresult-return" })
        assertTrue(transformed.contains("return switch (this.light(state, level))"))
        assertTrue(transformed.contains("case SUCCESS -> net.minecraft.world.ItemInteractionResult.SUCCESS;"))
        assertTrue(!transformed.contains("return this.light(state, level);"))
    }

    @Test
    fun `migrates common vanilla item damage recipe particle fluid and skull profile APIs`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CommonApiShapes.java").writeText("""
            package com.example;

            import com.mojang.authlib.GameProfile;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.particles.ParticleTypes;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.NbtUtils;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.SkullBlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class CommonApiShapes {
                void damage(ItemStack stack, Player player, InteractionHand hand) {
                    stack.hurtAndBreak(1, player, (user) -> {
                        user.onEquippedItemBroken(hand);
                    });
                }

                Recipe<?> recipe(Level level, FurnaceLike furnace) {
                    return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, furnace, level).orElse(null);
                }

                boolean fuel(BlockState state) {
                    return state.getBlock().getFluidState(state).is(null);
                }

                void particles(ServerLike server, BlockPos pos, java.util.Random rand) {
                    server.sendParticles(ParticleTypes.ENTITY_EFFECT,
                            pos.getX() + rand.nextDouble(),
                            pos.getY() + rand.nextDouble(),
                            pos.getZ() + rand.nextDouble(), 1,
                            rand.nextFloat(), rand.nextFloat(), rand.nextFloat(), 1);
                }

                void owner(ItemStack stack, SkullBlockEntity skull) {
                    GameProfile gameprofile = null;
                    CompoundTag compoundtag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    if (compoundtag.contains("SkullOwner", 10)) {
                        gameprofile = NbtUtils.readGameProfile(compoundtag.getCompound("SkullOwner"));
                    } else if (compoundtag.contains("SkullOwner", 8)) {
                        gameprofile = new GameProfile(null, compoundtag.getString("SkullOwner"));
                    }
                    skull.setOwner(gameprofile);
                    compoundtag.put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), skull.getOwnerProfile()));
                }

                void renderOwner(ItemStack stack) {
                    GameProfile renderProfile = null;
                    CompoundTag compoundtag = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                    if (compoundtag.contains("SkullOwner", 10)) {
                        renderProfile = NbtUtils.readGameProfile(compoundtag.getCompound("SkullOwner"));
                    } else if (compoundtag.contains("SkullOwner", 8)) {
                        renderProfile = new GameProfile(null, compoundtag.getString("SkullOwner"));
                        compoundtag.remove("SkullOwner");
                        SkullBlockEntity.updateGameprofile(renderProfile, (resolved) ->
                                compoundtag.put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), resolved)));
                    }
                    use(renderProfile);
                }
            }

            interface FurnaceLike {
                ItemStack getItem(int slot);
            }

            interface ServerLike {
                void sendParticles(Object particle, double x, double y, double z, int count, double dx, double dy, double dz, double speed);
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("CommonApiShapes.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("stack.hurtAndBreak(1, player, net.minecraft.world.entity.LivingEntity.getSlotForHand(hand));"))
        assertTrue(!transformed.contains("onEquippedItemBroken"))
        assertTrue(transformed.contains("new SingleRecipeInput(furnace.getItem(0))"))
        assertTrue(transformed.contains(".map(RecipeHolder::value).orElse(null)"))
        assertTrue(transformed.contains("state.getFluidState().is(null)"))
        assertTrue(!transformed.contains("getBlock().getFluidState(state)"))
        assertTrue(transformed.contains("ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, rand.nextFloat(), rand.nextFloat(), rand.nextFloat())"))
        assertTrue(transformed.contains("ResolvableProfile gameprofile = null;"))
        assertTrue(transformed.contains("ResolvableProfile renderProfile = null;"))
        assertTrue(transformed.contains("ResolvableProfile.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, compoundtag.getCompound(\"SkullOwner\")).result().orElse(null)"))
        assertTrue(transformed.contains("new ResolvableProfile(java.util.Optional.of(compoundtag.getString(\"SkullOwner\")), java.util.Optional.empty(), new com.mojang.authlib.properties.PropertyMap())"))
        assertTrue(transformed.contains("ResolvableProfile.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, skull.getOwnerProfile()).getOrThrow()"))
        assertTrue(transformed.contains("renderProfile.resolve().thenAccept(resolved ->"))
        assertTrue(!transformed.contains("updateGameprofile"))
        assertTrue(!transformed.contains("NbtUtils.readGameProfile"))
        assertTrue(!transformed.contains("NbtUtils.writeGameProfile"))
        assertTrue(!transformed.contains("import com.mojang.authlib.GameProfile;"))
        assertTrue(!transformed.contains("import net.minecraft.nbt.NbtUtils;"))
    }

    @Test
    fun `migrates recipe book menu single stack recipe inputs from source typed recipe registries`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleRecipeTypes.java").writeText("""
            package com.example;

            import net.minecraft.world.item.crafting.RecipeType;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class ExampleRecipeTypes {
                public static final DeferredHolder<RecipeType<?>, RecipeType<IncubationRecipe>> INCUBATION = null;
            }
        """.trimIndent())
        srcDir.resolve("IncubationRecipe.java").writeText("""
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.world.Container;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.level.Level;

            public class IncubationRecipe implements Recipe<Container> {
                @Override
                public boolean matches(Container menu, Level level) {
                    return menu.getItem(0).isEmpty();
                }

                @Override
                public ItemStack assemble(Container menu, HolderLookup.Provider provider) {
                    return ItemStack.EMPTY;
                }
            }
        """.trimIndent())
        srcDir.resolve("IncubatorMenu.java").writeText("""
            package com.example;

            import net.minecraft.world.Container;
            import net.minecraft.world.SimpleContainer;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.entity.player.StackedContents;
            import net.minecraft.world.inventory.RecipeBookMenu;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.level.Level;

            public class IncubatorMenu extends RecipeBookMenu<Container> {
                private final Container container;
                private final Level level;

                @Override
                public void fillCraftSlotsStackedContents(StackedContents contents) {}

                @Override
                public void clearCraftingContent() {}

                @Override
                public boolean recipeMatches(Recipe<? super Container> recipe) {
                    return recipe.matches(this.container, this.level);
                }

                protected boolean canIncubate(ItemStack stack) {
                    return this.level.getRecipeManager().getRecipeFor(ExampleRecipeTypes.INCUBATION.get(), new SimpleContainer(stack), this.level).isPresent();
                }

                @Override public int getResultSlotIndex() { return -1; }
                @Override public int getGridWidth() { return 1; }
                @Override public int getGridHeight() { return 1; }
                @Override public int getSize() { return 1; }
                @Override public boolean stillValid(Player player) { return true; }
            }
        """.trimIndent())
        srcDir.resolve("RecipeBookBehavior.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
            import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
            import net.minecraft.world.inventory.RecipeBookMenu;

            public interface RecipeBookBehavior<T extends RecipeBookMenu<?>, V extends AbstractContainerScreen<T> & RecipeUpdateListener> {
            }
        """.trimIndent())
        srcDir.resolve("AbstractRecipeBookScreen.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
            import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
            import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
            import net.minecraft.world.Container;
            import net.minecraft.world.inventory.RecipeBookMenu;

            public abstract class AbstractRecipeBookScreen<T extends RecipeBookMenu<Container>, S extends RecipeBookComponent> extends AbstractContainerScreen<T> implements RecipeUpdateListener, RecipeBookBehavior<T, AbstractRecipeBookScreen<T, S>> {
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val menu = srcDir.resolve("IncubatorMenu.java").readText()
        val recipe = srcDir.resolve("IncubationRecipe.java").readText()
        val behavior = srcDir.resolve("RecipeBookBehavior.java").readText()
        val screen = srcDir.resolve("AbstractRecipeBookScreen.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(menu.contains("extends RecipeBookMenu<SingleRecipeInput, com.example.IncubationRecipe>"), menu)
        assertTrue(menu.contains("public boolean recipeMatches(RecipeHolder<com.example.IncubationRecipe> recipe)"), menu)
        assertTrue(menu.contains("recipe.value().matches(new SingleRecipeInput(this.container.getItem(0)), this.level)"), menu)
        assertTrue(menu.contains("getRecipeFor(ExampleRecipeTypes.INCUBATION.get(), new SingleRecipeInput(stack), this.level)"), menu)
        assertTrue(menu.contains("import net.minecraft.world.item.crafting.RecipeHolder;"), menu)
        assertTrue(menu.contains("import net.minecraft.world.item.crafting.SingleRecipeInput;"), menu)
        assertFalse(menu.contains("Recipe<? super Container>"), menu)
        assertTrue(recipe.contains("implements Recipe<SingleRecipeInput>"), recipe)
        assertTrue(recipe.contains("boolean matches(SingleRecipeInput menu, Level level)"), recipe)
        assertTrue(recipe.contains("ItemStack assemble(SingleRecipeInput menu, HolderLookup.Provider provider)"), recipe)
        assertFalse(recipe.contains("import net.minecraft.world.Container;"), recipe)
        assertTrue(behavior.contains("RecipeBookMenu<?, ?>"), behavior)
        assertTrue(screen.contains("T extends RecipeBookMenu<?, ?>"), screen)
        assertFalse(screen.contains("import net.minecraft.world.Container;"), screen)
    }

    @Test
    fun `migrates recipe holder loops to method recipe type parameter bounds`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Conversion.java").writeText("""
            package com.example;

            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;

            public interface Conversion<R extends MatchEventRecipe & BlockStateRecipe> {
                default <T extends R> boolean convert(RecipeType<T> recipeType, Level level) {
                    for (R recipe : level.getRecipeManager().getAllRecipesFor(recipeType)) { // existing source comment
                        if (recipe.matches()) {
                            return true;
                        }
                    }
                    return false;
                }

                default <T extends R> boolean convertWithoutContext(RecipeType<T> recipeType, Level level) {
                    for (R recipe : level.getRecipeManager().getAllRecipesFor(recipeType)) {
                        if (recipe.matches()) {
                            return true;
                        }
                    }
                    return false;
                }
            }

            interface MatchEventRecipe {
                boolean matches();
            }

            interface BlockStateRecipe {
                boolean matches();
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("Conversion.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertEquals(2, Regex("""for\s*\(\s*RecipeHolder<T>\s+recipeHolder\s*:\s*level\.getRecipeManager\(\)\.getAllRecipesFor\(recipeType\)\s*\)""").findAll(transformed).count(), transformed)
        assertTrue(transformed.contains("import net.minecraft.world.item.crafting.RecipeHolder;"), transformed)
        assertTrue(transformed.contains("R recipe = recipeHolder.value();"), transformed)
        assertFalse(transformed.contains("RecipeHolder<R> recipeHolder"), transformed)
        assertFalse(transformed.contains("for (R recipe : level.getRecipeManager().getAllRecipesFor(recipeType))"), transformed)
    }

    @Test
    fun `migrates cacheable function recipe boundaries to optionals`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("FunctionRecipeBoundaries.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.recipe.BlockStateRecipeUtil;
            import com.aetherteam.nitrogen.recipe.recipes.AbstractBlockStateRecipe;
            import net.minecraft.commands.CacheableFunction;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;

            import javax.annotation.Nullable;

            public interface FunctionRecipeBoundaries {
                default boolean convert(Level level, BlockPos pos, BlockState newState, @Nullable CacheableFunction function) {
                    level.setBlockAndUpdate(pos, newState);
                    BlockStateRecipeUtil.executeFunction(level, pos, function);
                    return true;
                }

                default int freeze(Level level, BlockPos pos, AbstractBlockStateRecipe recipe) {
                    CacheableFunction function = recipe.getFunction();
                    BlockStateRecipeUtil.executeFunction(level, pos, function);
                    return 1;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("FunctionRecipeBoundaries.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("import java.util.Optional;"), transformed)
        assertTrue(transformed.contains("Optional<CacheableFunction> function)"), transformed)
        assertTrue(transformed.contains("Optional<CacheableFunction> function = recipe.getFunction();"), transformed)
        assertFalse(transformed.contains("@Nullable CacheableFunction function"), transformed)
        assertFalse(transformed.contains("CacheableFunction function = recipe.getFunction();"), transformed)
    }

    @Test
    fun `migrates nitrogen block state recipe codec and constructor boundaries`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("AbstractBiomeParameterRecipe.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import com.aetherteam.nitrogen.recipe.recipes.AbstractBlockStateRecipe;
            import net.minecraft.commands.CacheableFunction;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.biome.Biome;
            import net.minecraft.world.level.block.state.BlockState;

            import javax.annotation.Nullable;

            public abstract class AbstractBiomeParameterRecipe extends AbstractBlockStateRecipe {
                @Nullable
                private final ResourceKey<Biome> biomeKey;
                @Nullable
                private final TagKey<Biome> biomeTag;

                public AbstractBiomeParameterRecipe(RecipeType<?> type, ResourceLocation id, @Nullable ResourceKey<Biome> biomeKey, @Nullable TagKey<Biome> biomeTag, BlockStateIngredient ingredient, BlockPropertyPair result, @Nullable CacheableFunction function) {
                    super(type, id, ingredient, result, function);
                    this.biomeKey = biomeKey;
                    this.biomeTag = biomeTag;
                }

                @Override
                public boolean matches(Level level, BlockPos pos, BlockState state) {
                    if (this.biomeKey != null) {
                        return super.matches(level, pos, state) && level.getBiome(pos).is(this.biomeKey);
                    } else if (this.biomeTag != null) {
                        return super.matches(level, pos, state) && level.getBiome(pos).is(this.biomeTag);
                    } else {
                        return super.matches(level, pos, state);
                    }
                }

                @Nullable
                public ResourceKey<Biome> getBiomeKey() {
                    return this.biomeKey;
                }

                @Nullable
                public TagKey<Biome> getBiomeTag() {
                    return this.biomeTag;
                }
            }
        """.trimIndent())
        srcDir.resolve("IcestoneFreezableRecipe.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import com.aetherteam.nitrogen.recipe.recipes.AbstractBlockStateRecipe;
            import net.minecraft.commands.CacheableFunction;
            import net.minecraft.core.BlockPos;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;

            import javax.annotation.Nullable;

            public class IcestoneFreezableRecipe extends AbstractBlockStateRecipe {
                public IcestoneFreezableRecipe(ResourceLocation id, BlockStateIngredient ingredient, BlockPropertyPair result, @Nullable CacheableFunction function) {
                    super(ExampleRecipeTypes.ICESTONE_FREEZABLE.get(), id, ingredient, result, function);
                }

                public boolean matches(@Nullable Player player, Level level, BlockPos pos, @Nullable ItemStack stack, BlockState oldState, BlockState newState, RecipeType<?> recipeType) {
                    return true;
                }
            }
        """.trimIndent())
        srcDir.resolve("SwetBallRecipe.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import net.minecraft.commands.CacheableFunction;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.level.biome.Biome;

            import javax.annotation.Nullable;

            public class SwetBallRecipe extends AbstractBiomeParameterRecipe {
                public SwetBallRecipe(ResourceLocation id, @Nullable ResourceKey<Biome> biomeKey, @Nullable TagKey<Biome> biomeTag, BlockStateIngredient ingredient, BlockPropertyPair result, @Nullable CacheableFunction function) {
                    super(ExampleRecipeTypes.SWET_BALL_CONVERSION.get(), id, biomeKey, biomeTag, ingredient, result, function);
                }

                public SwetBallRecipe(ResourceLocation id, BlockStateIngredient ingredient, BlockPropertyPair result, @Nullable CacheableFunction function) {
                    this(id, null, null, ingredient, result, function);
                }
            }
        """.trimIndent())
        srcDir.resolve("BiomeParameterRecipeSerializer.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import com.aetherteam.nitrogen.recipe.BlockStateRecipeUtil;
            import com.aetherteam.nitrogen.recipe.serializer.BlockStateRecipeSerializer;
            import com.google.gson.JsonObject;
            import net.minecraft.commands.CacheableFunction;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.level.biome.Biome;
            import org.apache.commons.lang3.tuple.Pair;

            import javax.annotation.Nullable;

            public class BiomeParameterRecipeSerializer<T extends AbstractBiomeParameterRecipe> extends BlockStateRecipeSerializer<T> {
                private final BiomeParameterRecipeSerializer.CookieBaker<T> factory;

                public BiomeParameterRecipeSerializer(BiomeParameterRecipeSerializer.CookieBaker<T> factory, BlockStateRecipeSerializer.CookieBaker<T> superFactory) {
                    super(superFactory);
                    this.factory = factory;
                }

                @Override
                public T fromJson(ResourceLocation id, JsonObject json) {
                    Pair<ResourceKey<Biome>, TagKey<Biome>> biomeRecipeData = BlockStateRecipeUtil.biomeRecipeDataFromJson(json);
                    ResourceKey<Biome> biomeKey = biomeRecipeData.getLeft();
                    TagKey<Biome> biomeTag = biomeRecipeData.getRight();
                    T recipe = super.fromJson(id, json);
                    return this.factory.create(id, biomeKey, biomeTag, recipe.getIngredient(), recipe.getResult(), recipe.getFunction());
                }

                @Nullable
                @Override
                public T fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
                    ResourceKey<Biome> biomeKey = BlockStateRecipeUtil.readBiomeKey(buffer);
                    TagKey<Biome> biomeTag = BlockStateRecipeUtil.readBiomeTag(buffer);
                    BlockStateIngredient ingredient = BlockStateIngredient.fromNetwork(buffer);
                    BlockPropertyPair result = BlockStateRecipeUtil.readPair(buffer);
                    CacheableFunction function = BlockStateRecipeUtil.readFunction(buffer);
                    return this.factory.create(id, biomeKey, biomeTag, ingredient, result, function);
                }

                @Override
                public void toNetwork(FriendlyByteBuf buffer, T recipe) {
                    BlockStateRecipeUtil.writeBiomeKey(buffer, recipe.getBiomeKey());
                    BlockStateRecipeUtil.writeBiomeTag(buffer, recipe.getBiomeTag());
                    super.toNetwork(buffer, recipe);
                }

                public interface CookieBaker<T extends AbstractBiomeParameterRecipe> {
                    T create(ResourceLocation id, @Nullable ResourceKey<Biome> biomeKey, @Nullable TagKey<Biome> biomeTag, BlockStateIngredient ingredient, BlockPropertyPair result, @Nullable CacheableFunction function);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val abstractRecipe = srcDir.resolve("AbstractBiomeParameterRecipe.java").readText()
        val simpleRecipe = srcDir.resolve("IcestoneFreezableRecipe.java").readText()
        val biomeRecipe = srcDir.resolve("SwetBallRecipe.java").readText()
        val serializer = srcDir.resolve("BiomeParameterRecipeSerializer.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(abstractRecipe.contains("private final Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome;"), abstractRecipe)
        assertTrue(abstractRecipe.contains("super(type, ingredient, result, function);"), abstractRecipe)
        assertTrue(abstractRecipe.contains("public Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> getBiome()"), abstractRecipe)
        assertTrue(simpleRecipe.contains("public IcestoneFreezableRecipe(BlockStateIngredient ingredient, BlockPropertyPair result, Optional<ResourceLocation> function)"), simpleRecipe)
        assertTrue(simpleRecipe.contains("super(ExampleRecipeTypes.ICESTONE_FREEZABLE.get(), ingredient, result, function);"), simpleRecipe)
        assertTrue(simpleRecipe.contains("import javax.annotation.Nullable;"), simpleRecipe)
        assertTrue(biomeRecipe.contains("public SwetBallRecipe(Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, BlockStateIngredient ingredient, BlockPropertyPair result, Optional<ResourceLocation> function)"), biomeRecipe)
        assertTrue(biomeRecipe.contains("this(Optional.empty(), ingredient, result, function);"), biomeRecipe)
        assertTrue(serializer.contains("private final MapCodec<T> codec;"), serializer)
        assertTrue(serializer.contains("AbstractBlockStateRecipe.Factory<T> superFactory"), serializer)
        assertTrue(serializer.contains("BlockStateRecipeUtil.KEY_CODEC.optionalFieldOf(\"biome\").forGetter(AbstractBiomeParameterRecipe::getBiome)"), serializer)
        assertTrue(serializer.contains("Optional<ResourceLocation> function = buffer.readOptional(FriendlyByteBuf::readResourceLocation);"), serializer)
        assertFalse(serializer.contains("CookieBaker"), serializer)
        assertFalse(serializer.contains("fromJson(ResourceLocation id, JsonObject json)"), serializer)
    }

    @Test
    fun `migrates nitrogen recipe builders from project source structure`() {
        val recipeDir = tempDir.resolve("src/main/java/com/example/recipe/recipes/block")
        val serializerDir = tempDir.resolve("src/main/java/com/example/recipe/serializer")
        val registryDir = tempDir.resolve("src/main/java/com/example/recipe")
        val builderDir = tempDir.resolve("src/main/java/com/example/recipe/builder")
        val providerDir = tempDir.resolve("src/main/java/com/example/data")
        recipeDir.createDirectories()
        serializerDir.createDirectories()
        registryDir.createDirectories()
        builderDir.createDirectories()
        providerDir.createDirectories()

        recipeDir.resolve("AbstractBiomeParameterRecipe.java").writeText("""
            package com.example.recipe.recipes.block;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import com.aetherteam.nitrogen.recipe.recipes.AbstractBlockStateRecipe;
            import com.mojang.datafixers.util.Either;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.biome.Biome;

            import java.util.Optional;

            public abstract class AbstractBiomeParameterRecipe extends AbstractBlockStateRecipe {
                private final Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome;

                public AbstractBiomeParameterRecipe(RecipeType<?> type, Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, BlockStateIngredient ingredient, BlockPropertyPair result, Optional<ResourceLocation> function) {
                    super(type, ingredient, result, function);
                    this.biome = biome;
                }

                public Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> getBiome() {
                    return this.biome;
                }
            }
        """.trimIndent())
        recipeDir.resolve("SwetBallRecipe.java").writeText("""
            package com.example.recipe.recipes.block;

            public class SwetBallRecipe extends AbstractBiomeParameterRecipe {
            }
        """.trimIndent())
        recipeDir.resolve("PlacementConversionRecipe.java").writeText("""
            package com.example.recipe.recipes.block;

            public class PlacementConversionRecipe extends AbstractBiomeParameterRecipe {
            }
        """.trimIndent())
        recipeDir.resolve("AccessoryFreezableRecipe.java").writeText("""
            package com.example.recipe.recipes.block;

            import com.aetherteam.nitrogen.recipe.recipes.AbstractBlockStateRecipe;

            public class AccessoryFreezableRecipe extends AbstractBlockStateRecipe {
            }
        """.trimIndent())
        serializerDir.resolve("BiomeParameterRecipeSerializer.java").writeText("""
            package com.example.recipe.serializer;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import com.aetherteam.nitrogen.recipe.serializer.BlockStateRecipeSerializer;
            import com.example.recipe.recipes.block.AbstractBiomeParameterRecipe;
            import com.mojang.datafixers.util.Either;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.level.biome.Biome;

            import java.util.Optional;

            public class BiomeParameterRecipeSerializer<T extends AbstractBiomeParameterRecipe> extends BlockStateRecipeSerializer<T> {
                public interface Factory<T extends AbstractBiomeParameterRecipe> {
                    T create(Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome, BlockStateIngredient ingredient, BlockPropertyPair result, Optional<ResourceLocation> function);
                }
            }
        """.trimIndent())
        registryDir.resolve("ExampleRecipeSerializers.java").writeText("""
            package com.example.recipe;

            import com.aetherteam.nitrogen.recipe.serializer.BlockStateRecipeSerializer;
            import com.example.recipe.recipes.block.AccessoryFreezableRecipe;
            import com.example.recipe.recipes.block.PlacementConversionRecipe;
            import com.example.recipe.recipes.block.SwetBallRecipe;
            import com.example.recipe.serializer.BiomeParameterRecipeSerializer;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class ExampleRecipeSerializers {
                public static final DeferredHolder<BiomeParameterRecipeSerializer<SwetBallRecipe>, BiomeParameterRecipeSerializer<SwetBallRecipe>> SWET_BALL_CONVERSION = null;
                public static final DeferredHolder<BiomeParameterRecipeSerializer<PlacementConversionRecipe>, BiomeParameterRecipeSerializer<PlacementConversionRecipe>> PLACEMENT_CONVERSION = null;
                public static final DeferredHolder<BlockStateRecipeSerializer<AccessoryFreezableRecipe>, BlockStateRecipeSerializer<AccessoryFreezableRecipe>> ACCESSORY_FREEZABLE = null;
                public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<Object>> OTHER = null;
            }
        """.trimIndent())
        builderDir.resolve("BiomeParameterRecipeBuilder.java").writeText("""
            package com.example.recipe.builder;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import com.aetherteam.nitrogen.recipe.BlockStateRecipeUtil;
            import com.aetherteam.nitrogen.recipe.builder.BlockStateRecipeBuilder;
            import com.aetherteam.nitrogen.recipe.recipes.AbstractBlockStateRecipe;
            import com.aetherteam.nitrogen.recipe.serializer.BlockStateRecipeSerializer;
            import com.google.gson.JsonObject;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.level.biome.Biome;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.properties.Property;

            import javax.annotation.Nullable;
            import java.util.Map;

            public class BiomeParameterRecipeBuilder extends BlockStateRecipeBuilder {
                @Nullable
                private final ResourceKey<Biome> biomeKey;
                @Nullable
                private final TagKey<Biome> biomeTag;

                public BiomeParameterRecipeBuilder(BlockPropertyPair result, BlockStateIngredient ingredient, @Nullable ResourceKey<Biome> biomeKey, @Nullable TagKey<Biome> biomeTag, BlockStateRecipeSerializer<?> serializer) {
                    super(result, ingredient, serializer);
                    this.biomeKey = biomeKey;
                    this.biomeTag = biomeTag;
                }

                public static BiomeParameterRecipeBuilder recipe(BlockStateIngredient ingredient, Block result, ResourceKey<Biome> biomeKey, BlockStateRecipeSerializer<?> serializer) {
                    return recipe(BlockPropertyPair.of(result, Map.of()), ingredient, biomeKey, null, serializer);
                }

                public static BiomeParameterRecipeBuilder recipe(BlockStateIngredient ingredient, Block result, TagKey<Biome> biomeTag, BlockStateRecipeSerializer<?> serializer) {
                    return recipe(BlockPropertyPair.of(result, Map.of()), ingredient, null, biomeTag, serializer);
                }

                public static BiomeParameterRecipeBuilder recipe(BlockPropertyPair result, BlockStateIngredient ingredient, @Nullable ResourceKey<Biome> biomeKey, @Nullable TagKey<Biome> biomeTag, BlockStateRecipeSerializer<?> serializer) {
                    return new BiomeParameterRecipeBuilder(result, ingredient, biomeKey, biomeTag, serializer);
                }

                @Override
                public void save(RecipeOutput finishedRecipeConsumer, ResourceLocation id) {
                    finishedRecipeConsumer.accept(new BiomeParameterRecipeBuilder.Result(id, this.biomeKey, this.biomeTag, this.getIngredient(), this.getResultPair(), this.getSerializer()));
                }

                public static class Result extends BlockStateRecipeBuilder.Result {
                    public Result(ResourceLocation id, @Nullable ResourceKey<Biome> biomeKey, @Nullable TagKey<Biome> biomeTag, BlockStateIngredient ingredient, BlockPropertyPair result, RecipeSerializer<? extends AbstractBlockStateRecipe> serializer) {
                        super(id, ingredient, result, serializer, null);
                    }

                    @Override
                    public void serializeRecipeData(JsonObject json) {
                        BlockStateRecipeUtil.biomeKeyToJson(json, null);
                    }
                }
            }
        """.trimIndent())
        providerDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import com.aetherteam.nitrogen.recipe.BlockPropertyPair;
            import com.aetherteam.nitrogen.recipe.BlockStateIngredient;
            import com.aetherteam.nitrogen.recipe.builder.BlockStateRecipeBuilder;
            import com.example.recipe.ExampleRecipeSerializers;
            import com.example.recipe.builder.BiomeParameterRecipeBuilder;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.level.biome.Biome;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.properties.BlockStateProperties;
            import net.minecraft.world.level.block.state.properties.Property;

            import java.util.Map;

            public class ExampleRecipeProvider {
                protected BlockStateRecipeBuilder swetBallConversion(Block result, Block ingredient) {
                    return BiomeParameterRecipeBuilder.recipe(BlockStateIngredient.of(ingredient), result, ExampleRecipeSerializers.SWET_BALL_CONVERSION.get());
                }

                protected BlockStateRecipeBuilder convertPlacement(Block result, Block ingredient, TagKey<Biome> biome) {
                    return BiomeParameterRecipeBuilder.recipe(BlockStateIngredient.of(ingredient), result, biome, ExampleRecipeSerializers.PLACEMENT_CONVERSION.get());
                }

                protected BlockStateRecipeBuilder convertPlacementWithProperties(Block result, Map<Property<?>, Comparable<?>> resultProperties, Block ingredient, Map<Property<?>, Comparable<?>> ingredientProperties, TagKey<Biome> biome) {
                    return BiomeParameterRecipeBuilder.recipe(BlockStateIngredient.of(this.pair(ingredient, ingredientProperties)), result, resultProperties, biome, ExampleRecipeSerializers.PLACEMENT_CONVERSION.get());
                }

                protected BlockStateRecipeBuilder accessoryFreezable(Block result, Block ingredient) {
                    return BlockStateRecipeBuilder.recipe(BlockStateIngredient.of(this.pair(ingredient, Map.of(BlockStateProperties.LEVEL, 0))), result, ExampleRecipeSerializers.ACCESSORY_FREEZABLE.get());
                }

                protected BlockPropertyPair pair(Block block, Map<Property<?>, Comparable<?>> properties) {
                    return BlockPropertyPair.of(block, properties);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val builder = builderDir.resolve("BiomeParameterRecipeBuilder.java").readText()
        val provider = providerDir.resolve("ExampleRecipeProvider.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-nitrogen-recipe-builder-121" })
        assertTrue(builder.contains("import com.example.recipe.recipes.block.AbstractBiomeParameterRecipe;"), builder)
        assertTrue(builder.contains("import com.example.recipe.serializer.BiomeParameterRecipeSerializer;"), builder)
        assertTrue(builder.contains("public class BiomeParameterRecipeBuilder implements RecipeBuilder"), builder)
        assertTrue(builder.contains("private final Optional<Either<ResourceKey<Biome>, TagKey<Biome>>> biome;"), builder)
        assertTrue(builder.contains("private final BiomeParameterRecipeSerializer.Factory<?> factory;"), builder)
        assertTrue(builder.contains("AbstractBiomeParameterRecipe recipe = this.factory.create(this.biome, this.ingredient, this.result, this.function);"), builder)
        assertTrue(builder.contains("recipeOutput.accept(id, recipe, null);"), builder)
        assertFalse(builder.contains("BlockStateRecipeBuilder.Result"), builder)
        assertFalse(builder.contains("JsonObject"), builder)
        assertFalse(builder.contains("BlockStateRecipeUtil"), builder)

        assertTrue(provider.contains("BlockStateRecipeBuilder.recipe(BlockStateIngredient.of(ingredient), result, com.example.recipe.recipes.block.SwetBallRecipe::new)"), provider)
        assertTrue(provider.contains("protected BiomeParameterRecipeBuilder convertPlacement(Block result, Block ingredient, TagKey<Biome> biome)"), provider)
        assertTrue(provider.contains("BiomeParameterRecipeBuilder.recipe(BlockStateIngredient.of(ingredient), result, biome, com.example.recipe.recipes.block.PlacementConversionRecipe::new)"), provider)
        assertTrue(provider.contains("Reference2ObjectArrayMap<Property<?>, Comparable<?>> resultProperties"), provider)
        assertTrue(provider.contains("this.pair(ingredient, Optional.of(ingredientProperties))"), provider)
        assertTrue(provider.contains("new Reference2ObjectArrayMap<>(new Property<?>[]{BlockStateProperties.LEVEL}, new Comparable<?>[]{0})"), provider)
        assertTrue(provider.contains("BlockStateRecipeBuilder.recipe(BlockStateIngredient.of(this.pair(ingredient, Optional.of(new Reference2ObjectArrayMap<>"), provider)
        assertTrue(provider.contains("com.example.recipe.recipes.block.AccessoryFreezableRecipe::new"), provider)
        assertTrue(provider.contains("import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;"), provider)
        assertTrue(provider.contains("import java.util.Optional;"), provider)
        assertFalse(provider.contains("import java.util.Map;"), provider)
        assertFalse(provider.contains("ExampleRecipeSerializers.PLACEMENT_CONVERSION.get()"), provider)
    }

    @Test
    fun `migrates legacy item tag enchantment and mob spawn equipment APIs`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("SpawnAndItemShapes.java").writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ByteTag;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.DifficultyInstance;
            import net.minecraft.world.entity.*;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.ServerLevelAccessor;
            import net.minecraft.world.level.storage.loot.LootParams;

            public class SpawnAndItemShapes extends Mob {
                static final String TAG_MARKER = "marker";

                void tags(ItemStack stack, CompoundTag blockEntityTag) {
                    stack.addTagElement("BlockEntityTag", blockEntityTag);
                    stack.addTagElement(TAG_MARKER, ByteTag.valueOf((byte) 1));
                    stack.setTag(blockEntityTag);
                }

                boolean silk(LootParams.Builder builder) {
                    ItemStack tool = builder.getParameter(null);
                    return tool.getEnchantmentLevel(Enchantments.SILK_TOUCH) > 0;
                }

                @Override
                public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty,
                        MobSpawnType reason, SpawnGroupData spawnDataIn) {
                    SpawnGroupData data = super.finalizeSpawn(accessor, difficulty, reason, spawnDataIn, dataTag);
                    this.populateDefaultEquipmentSlots(accessor.getRandom(), difficulty);
                    this.populateDefaultEquipmentEnchantments(accessor.getRandom(), difficulty);
                    return data;
                }

                @Override
                protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("SpawnAndItemShapes.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("stack.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(blockEntityTag));"))
        assertTrue(transformed.contains("CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, tag -> tag.put(TAG_MARKER, ByteTag.valueOf((byte) 1)));"))
        assertTrue(transformed.contains("stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(blockEntityTag));"))
        assertTrue(transformed.contains("registry.get(Enchantments.SILK_TOUCH)"))
        assertTrue(transformed.contains("EnchantmentHelper.getItemEnchantmentLevel(holder, tool)"))
        assertTrue(transformed.contains("super.finalizeSpawn(accessor, difficulty, reason, spawnDataIn)"))
        assertTrue(transformed.contains("this.populateDefaultEquipmentSlots(accessor.getRandom(), difficulty)"))
        assertTrue(transformed.contains("this.populateDefaultEquipmentEnchantments(accessor, accessor.getRandom(), difficulty)"))
        assertTrue(transformed.contains("protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty)"))
        assertTrue(!transformed.contains("addTagElement"))
        assertTrue(!transformed.contains("setTag("))
        assertTrue(!transformed.contains("dataTag"))
    }

    @Test
    fun `migrates attribute modifier ids and calls to resource locations`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("AttributeShapes.java").writeText("""
            package com.example;

            import java.util.UUID;
            import net.minecraft.world.entity.ai.attributes.AttributeInstance;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;

            public class AttributeShapes {
                static final UUID REACH_MODIFIER = UUID.fromString("00000000-0000-0000-0000-000000000001");
                static final AttributeModifier ACTIVE = new AttributeModifier("Active Boost", 1.0D, AttributeModifier.Operation.ADD_VALUE);

                void update(AttributeInstance instance) {
                    UUID oldId = REACH_MODIFIER;
                    AttributeModifier speed = new AttributeModifier(oldId, "Segment Count Speed Boost", 0.1D, AttributeModifier.Operation.ADD_VALUE);
                    instance.removeModifier(oldId);
                    if (!instance.hasModifier(ACTIVE)) {
                        instance.addTransientModifier(ACTIVE);
                    }
                    instance.removeModifier(speed);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("AttributeShapes.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("net.minecraft.resources.ResourceLocation REACH_MODIFIER = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"com\", \"reach_modifier\")"))
        assertTrue(transformed.contains("new AttributeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"com\", \"active_boost\"), 1.0D, AttributeModifier.Operation.ADD_VALUE)"))
        assertTrue(transformed.contains("net.minecraft.resources.ResourceLocation oldId = REACH_MODIFIER;"))
        assertTrue(transformed.contains("new AttributeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"com\", \"segment_count_speed_boost\"), 0.1D, AttributeModifier.Operation.ADD_VALUE)"))
        assertTrue(transformed.contains("instance.removeModifier(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"com\", \"segment_count_speed_boost\"));"))
        assertTrue(transformed.contains("instance.hasModifier(ACTIVE.id())"))
        assertTrue(transformed.contains("instance.removeModifier(speed.id())"))
        assertTrue(!transformed.contains("import java.util.UUID;"))
    }

    @Test
    fun `migrates cross file attribute modifier id constants without touching non attribute ids`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ModifierIds.java").writeText("""
            package com.example;

            import java.util.UUID;

            public class ModifierIds {
                public static final UUID REACH_MODIFIER = UUID.fromString("00000000-0000-0000-0000-000000000001");
                public static final UUID TEMP_MODIFIER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
            }
        """.trimIndent())
        srcDir.resolve("ModifierUse.java").writeText("""
            package com.example;

            import java.util.UUID;
            import net.minecraft.world.entity.ai.attributes.AttributeInstance;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;

            public class ModifierUse {
                void update(AttributeInstance attackRange) {
                    UUID uuidForOppositeHand = ModifierIds.REACH_MODIFIER;
                    AttributeModifier giantModifier = attackRange.getModifier(uuidForOppositeHand);
                    if (giantModifier != null) {
                        attackRange.removeModifier(giantModifier);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("TemperatureUse.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.player.Player;

            public class TemperatureUse {
                void apply(Player player) {
                    TemperatureUtil.addTemperatureModifier(player, 1.0D, ModifierIds.TEMP_MODIFIER_UUID);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val ids = srcDir.resolve("ModifierIds.java").readText()
        val use = srcDir.resolve("ModifierUse.java").readText()
        val temperature = srcDir.resolve("TemperatureUse.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(ids.contains("public static final net.minecraft.resources.ResourceLocation REACH_MODIFIER = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(\"com\", \"reach_modifier\")"))
        assertTrue(ids.contains("public static final UUID TEMP_MODIFIER_UUID = UUID.fromString"))
        assertTrue(use.contains("net.minecraft.resources.ResourceLocation uuidForOppositeHand = ModifierIds.REACH_MODIFIER;"))
        assertTrue(use.contains("attackRange.getModifier(uuidForOppositeHand)"))
        assertTrue(use.contains("attackRange.removeModifier(giantModifier.id())"))
        assertTrue(temperature.contains("TemperatureUtil.addTemperatureModifier(player, 1.0D, ModifierIds.TEMP_MODIFIER_UUID)"))
        assertTrue(!temperature.contains("ResourceLocation"))
    }

    @Test
    fun `migrates additional vanilla 1_21 entity item recipe and particle API shapes`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("AdditionalVanillaShapes.java").writeText("""
            package com.example;

            import net.minecraft.core.particles.ParticleTypes;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.SimpleContainer;
            import net.minecraft.world.entity.EntityDimensions;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.Pose;
            import net.minecraft.world.entity.monster.Monster;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.entity.projectile.LargeFireball;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.gameevent.GameEvent;

            public class AdditionalVanillaShapes extends Monster {
                void shatter(ItemStack stack, LivingEntity attacker) {
                    stack.hurtAndBreak(stack.getMaxDamage() + 1, attacker, (user) -> {
                        user.level().playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), null, attacker.getSoundSource(), 1F, 0.5F);
                        user.onEquippedItemBroken(InteractionHand.MAIN_HAND);
                        user.gameEvent(GameEvent.ITEM_INTERACT_FINISH);
                    });
                }

                void armor(ItemStack stack, Player player) {
                    stack.hurtAndBreak(Math.min(player.tickCount, 3), player, user -> user.onEquippedItemBroken(stack.getEquipmentSlot() != null ? stack.getEquipmentSlot() : EquipmentSlot.HEAD));
                }

                Object smelt(ItemStack stack, Level level) {
                    return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SimpleContainer(stack), level)
                            .map(recipe -> recipe.getResultItem(level.registryAccess()))
                            .orElse(stack);
                }

                @Override
                public EntityDimensions getDimensions(Pose pose) {
                    return super.getDimensions(pose).scale(2.0F);
                }

                @Override
                public ResourceLocation getDefaultLootTable() {
                    return this.getType().getDefaultLootTable();
                }

                void fire(Level level, LivingEntity shooter, double d2, double d3, double d4) {
                    LargeFireball fireball = new LargeFireball(level, shooter, d2, d3, d4, 1);
                }

                void particles(ParticlePacket packet, double tx, double ty, double tz, float red, float green, float blue) {
                    packet.queueParticle(ParticleTypes.ENTITY_EFFECT, false, tx, ty, tz, red, green, blue);
                }
            }

            interface ParticlePacket {
                void queueParticle(Object particle, boolean force, double x, double y, double z, double dx, double dy, double dz);
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("AdditionalVanillaShapes.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("if (attacker.level() instanceof ServerLevel serverLevel)"))
        assertTrue(transformed.contains("stack.hurtAndBreak(stack.getMaxDamage() + 1, serverLevel, attacker, brokenItem ->"))
        assertTrue(transformed.contains("attacker.onEquippedItemBroken(brokenItem, EquipmentSlot.MAINHAND);"))
        assertTrue(transformed.contains("attacker.gameEvent(GameEvent.ITEM_INTERACT_FINISH);"))
        assertTrue(transformed.contains("stack.hurtAndBreak(Math.min(player.tickCount, 3), player, stack.getEquipmentSlot() != null ? stack.getEquipmentSlot() : EquipmentSlot.HEAD);"))
        assertTrue(transformed.contains("new SingleRecipeInput(stack)"))
        assertTrue(transformed.contains(".map(recipe -> recipe.value().getResultItem(level.registryAccess()))"))
        assertTrue(!transformed.contains("new SimpleContainer(stack)"))
        assertTrue(!transformed.contains("import net.minecraft.world.SimpleContainer;"))
        assertTrue(transformed.contains("protected EntityDimensions getDefaultDimensions(Pose pose)"))
        assertTrue(transformed.contains("super.getDefaultDimensions(pose).scale(2.0F)"))
        assertTrue(transformed.contains("public ResourceKey<LootTable> getDefaultLootTable()"))
        assertTrue(transformed.contains("new LargeFireball(level, shooter, new Vec3(d2, d3, d4).normalize(), 1)"))
        assertTrue(transformed.contains("ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, red, green, blue), false, tx, ty, tz, 0.0D, 0.0D, 0.0D"))
        assertTrue(!transformed.contains("public EntityDimensions getDimensions(Pose pose)"))
    }

    @Test
    fun `migrates legacy packet registrations and common 1_21 compile surfaces`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        val dataDir = srcDir.resolve("data")
        networkDir.createDirectories()
        dataDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.ID)
            public class ExampleMod {
                public static final String ID = "example";

                public ExampleMod(IEventBus bus) {
                    NetworkHandler.init();
                }
            }
        """.trimIndent())
        networkDir.resolve("NetworkHandler.java").writeText("""
            package com.example.network;

            import com.example.ExampleMod;
            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.simple.SimpleChannel;

            public class NetworkHandler {
                private static final String PROTOCOL = "1";
                public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ExampleMod.ID, () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

                public static void init() {
                    int id = 0;
                    CHANNEL.registerMessage(id++, DemoPacket.class, DemoPacket::encode, DemoPacket::new, DemoPacket.Handler::onMessage);
                    CHANNEL.registerMessage(id++, RegistryPacket.class, RegistryPacket::encode, RegistryPacket::new, RegistryPacket.Handler::onMessage);
                }
            }
        """.trimIndent())
        networkDir.resolve("DemoPacket.java").writeText("""
            package com.example.network;

            import net.minecraft.network.FriendlyByteBuf;
            import net.neoforged.neoforge.network.handling.IPayloadContext;

            public class DemoPacket {
                private final int value;

                public DemoPacket(int value) {
                    this.value = value;
                }

                public DemoPacket(FriendlyByteBuf buf) {
                    this.value = buf.readInt();
                }

                public void encode(FriendlyByteBuf buf) {
                    buf.writeInt(this.value);
                }

                public static class Handler {
                    public static boolean onMessage(DemoPacket packet, IPayloadContext context) {
                        return true;
                    }
                }
            }
        """.trimIndent())
        networkDir.resolve("RegistryPacket.java").writeText("""
            package com.example.network;

            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.neoforged.neoforge.network.handling.IPayloadContext;

            public class RegistryPacket {
                private final int value;

                public RegistryPacket(int value) {
                    this.value = value;
                }

                public RegistryPacket(RegistryFriendlyByteBuf buf) {
                    this.value = buf.readVarInt();
                }

                public void encode(RegistryFriendlyByteBuf buf) {
                    buf.writeVarInt(this.value);
                }

                public static class Handler {
                    public static boolean onMessage(RegistryPacket packet, IPayloadContext context) {
                        return true;
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("Common121.java").writeText("""
            package com.example;

            import net.minecraft.advancements.critereon.ItemPredicate;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.world.Container;
            import net.minecraft.world.level.ClipContext;
            import net.minecraft.world.level.gameevent.GameEvent;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.Mob;
            import net.minecraft.world.entity.MobSpawnType;
            import net.minecraft.world.entity.ai.attributes.AttributeInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.ServerLevelAccessor;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.phys.AABB;
            import net.minecraft.world.phys.Vec3;
            import net.neoforged.neoforge.common.NeoForgeMod;
            import net.neoforged.neoforge.common.Tags;
            import net.neoforged.neoforge.event.EventHooks;
            import net.neoforged.neoforge.network.PacketDistributor;
            import com.example.network.NetworkHandler;
            import com.example.network.DemoPacket;

            public class Common121 {
                void send(ServerLevel level, BlockPos pos, Entity entity, ItemStack stack, ItemPredicate predicate, TagKey<Block> tag, Mob mob, ServerLevelAccessor accessor, Container container) {
                    NetworkHandler.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), new DemoPacket(1));
                    NetworkHandler.CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(pos)), new DemoPacket(2));
                    Runnable callback = () -> NetworkHandler.CHANNEL.send(PacketDistributor.ALL.noArg(), new DemoPacket(3));
                    boolean matches = predicate.matches(stack);
                    boolean named = stack.hasCustomHoverName();
                    boolean skull = stack.is(Tags.Items.HEADS);
                    AttributeInstance reach = entity instanceof net.minecraft.world.entity.player.Player player ? player.getAttribute(NeoForgeMod.BLOCK_REACH.get()) : null;
                    boolean grief = EventHooks.getMobGriefingEvent(level, entity);
                    EventHooks.onFinalizeSpawn(mob, accessor, accessor.getCurrentDifficultyAt(pos), MobSpawnType.SPAWNER, null, null);
                    AABB box = new AABB(pos.above(16), pos.above(16).offset(1, 1, 1));
                    BuiltInRegistries.BLOCK.tags().getTag(tag).getRandomElement(level.getRandom()).get().defaultBlockState();
                    int size = container.size();
                    entity.gameEvent(GameEvent.ENTITY_ROAR);
                    level.clip(new ClipContext(Vec3.ZERO, Vec3.ZERO, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, null));
                }
            }
        """.trimIndent())
        dataDir.resolve("LootProvider.java").writeText("""
            package com.example.data;

            import net.minecraft.data.loot.LootTableSubProvider;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.level.storage.loot.LootTable;
            import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
            import java.util.function.BiConsumer;

            public class LootProvider implements LootTableSubProvider {
                @Override
                public void generate(BiConsumer<ResourceLocation, LootTable.Builder> register) {
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath("example", "demo");
                    register.accept(id, LootTable.lootTable());
                    NestedLootTable.lootTableReference(id);
                }
            }
        """.trimIndent())
        srcDir.resolve("LootIds.java").writeText("""
            package com.example;

            import com.google.common.collect.Sets;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.level.storage.loot.LootTable;
            import java.util.Set;

            public class LootIds {
                private static final Set<ResourceLocation> MOD_LOOT_TABLES = Sets.newHashSet();
                public static final ResourceLocation ENTITY_DROP = register("entities/demo");
                public final ResourceLocation lootTable;

                public LootIds(String path) {
                    this.lootTable = ExampleMod.prefix(path);
                }

                private static ResourceLocation register(String id) {
                    return register(ExampleMod.prefix(id));
                }

                private static ResourceLocation register(ResourceLocation id) {
                    if (MOD_LOOT_TABLES.add(id)) {
                        return id;
                    }
                    throw new IllegalArgumentException(id + " duplicate");
                }

                public static Set<ResourceLocation> allBuiltin() {
                    return MOD_LOOT_TABLES;
                }
            }
        """.trimIndent())
        srcDir.resolve("FluidInterfaces.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.LevelAccessor;
            import net.minecraft.world.level.block.BucketPickup;
            import net.minecraft.world.level.block.LiquidBlockContainer;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.material.Fluid;
            import net.minecraft.world.level.material.FluidState;

            public interface FluidInterfaces extends BucketPickup, LiquidBlockContainer {
                @Override
                default ItemStack pickupBlock(LevelAccessor level, BlockPos pos, BlockState state) {
                    return ItemStack.EMPTY;
                }

                @Override
                default boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
                    return true;
                }

                @Override
                default boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
                    return true;
                }
            }
        """.trimIndent())
        srcDir.resolve("VariantSpawnerBlock.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.BaseEntityBlock;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.minecraft.world.level.block.state.BlockState;

            public class VariantSpawnerBlock extends BaseEntityBlock {
                private final DemoVariant variant;

                public VariantSpawnerBlock(BlockBehaviour.Properties properties, DemoVariant variant) {
                    super(properties);
                    this.variant = variant;
                }

                @Override
                public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
                    return null;
                }

                enum DemoVariant {
                    ONE;
                    public static final com.mojang.serialization.Codec<DemoVariant> CODEC = null;
                }
            }
        """.trimIndent())
        srcDir.resolve("NamedVariant.java").writeText("""
            package com.example;

            import net.minecraft.util.StringRepresentable;

            public enum NamedVariant implements StringRepresentable {
                FIRST,
                SECOND;

                @Override
                public String getSerializedName() {
                    return name().toLowerCase(java.util.Locale.ROOT);
                }
            }
        """.trimIndent())
        srcDir.resolve("TriggerPredicate.java").writeText("""
            package com.example;

            import net.minecraft.advancements.critereon.ItemPredicate;
            import net.minecraft.world.item.ItemStack;
            import java.util.Optional;

            public class TriggerPredicate {
                public void trigger(ItemStack stack) {
                    this.trigger(instance -> instance.matches(stack));
                }

                void trigger(java.util.function.Predicate<TriggerInstance> predicate) {
                }

                public record TriggerInstance(Optional<ItemPredicate> item) {
                    public boolean matches(ItemStack stack) {
                        return this.item.isEmpty() || this.item.get().matches(stack);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleWallSignBlock.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.WallSignBlock;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.block.state.properties.WoodType;

            public class ExampleWallSignBlock extends WallSignBlock {
                public ExampleWallSignBlock(Properties properties, WoodType type) {
                    super(properties, type);
                }

                @Override
                public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
                    return null;
                }
            }
        """.trimIndent())
        srcDir.resolve("ThrowGoal.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
            import net.minecraft.world.entity.PathfinderMob;

            public class ThrowGoal extends MeleeAttackGoal {
                public ThrowGoal(PathfinderMob mob) {
                    super(mob, 1.0D, true);
                }

                @Override
                protected void checkAndPerformAttack(LivingEntity victim, double distance) {
                    double reach = this.getAttackReachSqr(victim);
                    if (distance <= reach && this.getTicksUntilNextAttack() <= 0) {
                        this.resetAttackCooldown();
                        this.mob.doHurtTarget(victim);
                    }
                }

                public static class LoudThrowGoal extends ThrowGoal {
                    public LoudThrowGoal(PathfinderMob mob) {
                        super(mob);
                    }

                    @Override
                    protected void checkAndPerformAttack(LivingEntity victim, double distance) {
                        super.checkAndPerformAttack(victim, distance);
                        if (distance > 4.0D) {
                            this.mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        }
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyRiderBlock.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.HitResult;
            import net.minecraft.world.phys.Vec3;
            import net.minecraft.world.entity.player.Player;

            public class LegacyRiderBlock extends Block {
                public LegacyRiderBlock(BlockBehaviour.Properties properties) {
                    super(properties);
                }

                @Override
                public ItemStack getCloneItemStack(BlockState state, HitResult result, BlockGetter getter, BlockPos pos, Player player) {
                    return getter.getBlockEntity(pos) == null ? ItemStack.EMPTY : new ItemStack(this.asItem());
                }

                public static class LegacyMount extends Entity {
                    public LegacyMount(EntityType<?> type, Level level) {
                        super(type, level);
                    }

                    @Override
                    public void positionRider(Entity passenger, Entity.MoveFunction callback) {
                        Vec3 riderPos = this.getRiderPosition(passenger);
                        callback.accept(passenger, riderPos.x(), riderPos.y(), riderPos.z());
                    }

                    @Override
                    public double getPassengersRidingOffset() {
                        return 2.25D;
                    }

                    private Vec3 getRiderPosition(Entity passenger) {
                        float distance = 0.4F;
                        double dx = Math.cos((this.getYRot() + 90) * Math.PI / 180.0D) * distance;
                        double dz = Math.sin((this.getYRot() + 90) * Math.PI / 180.0D) * distance;
                        return new Vec3(this.getX() + dx, this.getY() + this.getPassengersRidingOffset() + passenger.getMyRidingOffset(), this.getZ() + dz);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityOverrideShapes.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityDimensions;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.Pose;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class EntityOverrideShapes extends Entity {
                public EntityOverrideShapes(EntityType<?> type, Level level) {
                    super(type, level);
                }

                @Override
                protected float getStandingEyeHeight(Pose pose, EntityDimensions size) {
                    return this.isShiftKeyDown() ? 0.5F : super.getStandingEyeHeight(pose, size);
                }

                @Override
                public double getMyRidingOffset() {
                    return -0.25D;
                }

                @Override
                public boolean canChangeDimensions() {
                    return false;
                }

                public void invalidate(BlockEntity blockEntity) {
                    blockEntity.invalidateCaps();
                }
            }
        """.trimIndent())
        srcDir.resolve("AttackGoals.java").writeText("""
            package com.example;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.Mob;
            import net.minecraft.world.entity.PathfinderMob;
            import net.minecraft.world.entity.ai.goal.Goal;
            import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

            public class AttackGoals {
                static class SimpleGoal extends Goal {
                    private final Mob mob;
                    private int attackTick;

                    SimpleGoal(Mob mob) {
                        this.mob = mob;
                    }

                    @Override
                    public boolean canUse() {
                        LivingEntity target = mob.getTarget();
                        return target != null && this.getAttackReachSqr(target) >= this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
                    }

                    public void tick() {
                        LivingEntity livingentity = this.mob.getTarget();
                        double d0 = this.mob.getPerceivedTargetDistanceSquareForMeleeAttack(livingentity);
                        this.checkAndPerformAttack(livingentity, d0);
                    }

                    protected void checkAndPerformAttack(LivingEntity entity, double distance) {
                        double reach = this.getAttackReachSqr(entity);
                        if (distance <= reach) {
                            this.attackTick = this.adjustedTickDelay(20);
                            this.mob.swing(InteractionHand.MAIN_HAND);
                        }
                    }

                    protected double getAttackReachSqr(LivingEntity entity) {
                        return this.mob.getBbWidth() * 2.0F * this.mob.getBbWidth() * 2.0F + entity.getBbWidth();
                    }
                }

                static class ReachGoal extends MeleeAttackGoal {
                    ReachGoal(PathfinderMob mob) {
                        super(mob, 1.0D, false);
                    }

                    @Override
                    protected double getAttackReachSqr(LivingEntity target) {
                        return this.mob.getBbWidth() * this.mob.getBbHeight();
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val packet = networkDir.resolve("DemoPacket.java").readText()
        val registryPacket = networkDir.resolve("RegistryPacket.java").readText()
        val network = networkDir.resolve("ModNetwork.java").readText()
        val handler = networkDir.resolve("NetworkHandler.java").readText()
        val common = srcDir.resolve("Common121.java").readText()
        val loot = dataDir.resolve("LootProvider.java").readText()
        val lootIds = srcDir.resolve("LootIds.java").readText()
        val fluidInterfaces = srcDir.resolve("FluidInterfaces.java").readText()
        val variantSpawner = srcDir.resolve("VariantSpawnerBlock.java").readText()
        val namedVariant = srcDir.resolve("NamedVariant.java").readText()
        val triggerPredicate = srcDir.resolve("TriggerPredicate.java").readText()
        val signBlock = srcDir.resolve("ExampleWallSignBlock.java").readText()
        val throwGoal = srcDir.resolve("ThrowGoal.java").readText()
        val legacyRiderBlock = srcDir.resolve("LegacyRiderBlock.java").readText()
        val entityOverrideShapes = srcDir.resolve("EntityOverrideShapes.java").readText()
        val attackGoals = srcDir.resolve("AttackGoals.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-packet-payload" }, "changes=${result.changes} errors=${result.errors}")
        assertTrue(packet.contains("implements CustomPacketPayload"))
        assertTrue(packet.contains("StreamCodec.of((buf, packet) -> packet.encode(buf), DemoPacket::new)"))
        assertTrue(registryPacket.contains("StreamCodec<RegistryFriendlyByteBuf, RegistryPacket>"))
        assertTrue(network.contains("registrar.playBidirectional("))
        assertTrue(handler.contains("public static void init()"))
        assertTrue(!handler.contains("SimpleChannel"))
        assertTrue(common.contains("PacketDistributor.sendToPlayersTrackingEntity(entity, new DemoPacket(1))"))
        assertTrue(common.contains("PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), new DemoPacket(2))"))
        assertTrue(common.contains("Runnable callback = () -> PacketDistributor.sendToAllPlayers(new DemoPacket(3));"))
        assertTrue(common.contains("predicate.test(stack)"))
        assertTrue(common.contains("stack.has(DataComponents.CUSTOM_NAME)"))
        assertTrue(common.contains("stack.is(ItemTags.SKULLS)"))
        assertTrue(common.contains("Attributes.BLOCK_INTERACTION_RANGE"))
        assertTrue(common.contains("EventHooks.canEntityGrief(level, entity)"))
        assertTrue(common.contains("EventHooks.finalizeMobSpawn(mob, accessor, accessor.getCurrentDifficultyAt(pos), MobSpawnType.SPAWNER, null)"))
        assertTrue(common.contains("AABB.encapsulatingFullBlocks(pos.above(16), pos.above(16).offset(1, 1, 1))"))
        assertTrue(common.contains("BuiltInRegistries.BLOCK.getRandomElementOf(tag, level.getRandom()).map(holder -> holder.value().defaultBlockState()).orElseGet(Blocks.AIR::defaultBlockState)"))
        assertTrue(common.contains("container.getContainerSize()"))
        assertTrue(common.contains("GameEvent.ENTITY_ACTION"))
        assertTrue(common.contains("CollisionContext.empty()"))
        assertTrue(loot.contains("BiConsumer<ResourceKey<LootTable>, LootTable.Builder>"))
        assertTrue(loot.contains("register.accept(ResourceKey.create(Registries.LOOT_TABLE, id), LootTable.lootTable())"))
        assertTrue(loot.contains("NestedLootTable.lootTableReference(ResourceKey.create(Registries.LOOT_TABLE, id))"))
        assertTrue(lootIds.contains("Set<ResourceKey<LootTable>> MOD_LOOT_TABLES"))
        assertTrue(lootIds.contains("public static final ResourceKey<LootTable> ENTITY_DROP = register(\"entities/demo\")"))
        assertTrue(lootIds.contains("this.lootTable = ResourceKey.create(Registries.LOOT_TABLE, ExampleMod.prefix(path))"))
        assertTrue(lootIds.contains("private static ResourceKey<LootTable> register(String id)"))
        assertTrue(lootIds.contains("private static ResourceKey<LootTable> register(ResourceKey<LootTable> id)"))
        assertTrue(fluidInterfaces.contains("pickupBlock(Player player, LevelAccessor level, BlockPos pos, BlockState state)"))
        assertTrue(fluidInterfaces.contains("canPlaceLiquid(Player player, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid)"))
        assertTrue(variantSpawner.contains("com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec"))
        assertTrue(variantSpawner.contains("DemoVariant.CODEC.fieldOf(\"variant\").forGetter(o -> o.variant)"))
        assertTrue(variantSpawner.contains(".apply(instance, (variant, properties) -> new VariantSpawnerBlock(properties, variant))"))
        assertTrue(namedVariant.contains("StringRepresentable.EnumCodec<NamedVariant> CODEC = StringRepresentable.fromEnum(NamedVariant::values)"))
        assertTrue(triggerPredicate.contains("instance -> instance.matches(stack)"))
        assertTrue(triggerPredicate.contains("this.item.get().test(stack)"))
        assertTrue(signBlock.contains("super(type, properties);"))
        assertTrue(signBlock.contains("public static final com.mojang.serialization.MapCodec<net.minecraft.world.level.block.WallSignBlock> CODEC"))
        assertTrue(signBlock.contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.WallSignBlock> codec()"))
        assertTrue(throwGoal.contains("protected void checkAndPerformAttack(LivingEntity victim)"))
        assertTrue(throwGoal.contains("this.canPerformAttack(victim) && this.getTicksUntilNextAttack() <= 0"))
        assertTrue(throwGoal.contains("super.checkAndPerformAttack(victim);"))
        assertTrue(throwGoal.contains("this.mob.distanceToSqr(victim) > 4.0D"))
        assertTrue(!throwGoal.contains("getAttackReachSqr"))
        assertTrue(!throwGoal.contains("double distance"))
        assertTrue(legacyRiderBlock.contains("getCloneItemStack(BlockState state, HitResult result, LevelReader getter, BlockPos pos, Player player)"))
        assertTrue(legacyRiderBlock.contains("protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float yRot)"))
        assertTrue(legacyRiderBlock.contains("return new Vec3(0.0F, 2.25D, 0.4F);"))
        assertTrue(!legacyRiderBlock.contains("positionRider("))
        assertTrue(!legacyRiderBlock.contains("getPassengersRidingOffset"))
        assertTrue(entityOverrideShapes.contains("protected EntityDimensions getDefaultDimensions(Pose pose)"))
        assertTrue(entityOverrideShapes.contains("super.getDefaultDimensions(pose).withEyeHeight(this.isShiftKeyDown() ? 0.5F : super.getDefaultDimensions(pose).eyeHeight())"))
        assertTrue(entityOverrideShapes.contains("public Vec3 getVehicleAttachmentPoint(Entity vehicle)"))
        assertTrue(entityOverrideShapes.contains("return new Vec3(0.0D, -0.25D, 0.0D);"))
        assertTrue(entityOverrideShapes.contains("public boolean canChangeDimensions(Level from, Level to)"))
        assertTrue(entityOverrideShapes.contains("blockEntity.invalidateCapabilities();"))
        assertTrue(attackGoals.contains("return target != null && this.mob.isWithinMeleeAttackRange(target);"))
        assertTrue(attackGoals.contains("this.checkAndPerformAttack(livingentity);"))
        assertTrue(attackGoals.contains("this.attackTick <= 0 && this.mob.isWithinMeleeAttackRange(entity) && this.mob.hasLineOfSight(entity)"))
        assertTrue(attackGoals.contains("protected boolean canPerformAttack(LivingEntity target)"))
        assertTrue(attackGoals.contains("(this.mob.getBbWidth() * this.mob.getBbHeight()) >= this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ())"))
        assertTrue(!attackGoals.contains("getAttackReachSqr"))
    }

    @Test
    fun `migrates MissingMappingsEvent remappers to deferred register aliases`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val initDir = srcDir.resolve("init")
        val utilDir = srcDir.resolve("util")
        initDir.createDirectories()
        utilDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;
            import com.example.init.ModBlocks;
            import com.example.init.ModEntities;
            import com.example.init.ModItems;

            @Mod(ExampleMod.ID)
            public class ExampleMod {
                public static final String ID = "example";

                public ExampleMod(IEventBus bus) {
                    ModBlocks.BLOCKS.register(bus);
                    ModItems.ITEMS.register(bus);
                    ModEntities.ENTITIES.register(bus);
                }
            }
        """.trimIndent())
        initDir.resolve("ModBlocks.java").writeText("""
            package com.example.init;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import com.example.ExampleMod;

            public class ModBlocks {
                public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ExampleMod.ID);
                public static final Object NEW_STONE = BLOCKS.register("new_stone", () -> null);
                public static final Object DARK_FENCE_GATE = BLOCKS.register("dark_fence_gate", () -> null);
            }
        """.trimIndent())
        initDir.resolve("ModItems.java").writeText("""
            package com.example.init;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import com.example.ExampleMod;

            public class ModItems {
                public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ExampleMod.ID);
                public static final Object NEW_STONE = ITEMS.register("new_stone", () -> null);
                public static final Object DARK_FENCE_GATE = ITEMS.register("dark_fence_gate", () -> null);
            }
        """.trimIndent())
        initDir.resolve("ModEntities.java").writeText("""
            package com.example.init;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import com.example.ExampleMod;

            public class ModEntities {
                public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.ID);
            }
        """.trimIndent())
        utilDir.resolve("LegacyRemapper.java").writeText("""
            package com.example.util;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.event.MissingMappingsEvent;
            import com.example.ExampleMod;

            public class LegacyRemapper {
                @SubscribeEvent
                public static void remap(MissingMappingsEvent event) {
                    for (MissingMappingsEvent.Mapping<Block> mapping : event.getAllMappings(Registries.BLOCK)) {
                        remapBlock(mapping, "old_stone", "new_stone");
                        remapBlock(mapping, "_gate", "_fence_gate");
                    }
                    for (MissingMappingsEvent.Mapping<Item> mapping : event.getAllMappings(Registries.ITEM)) {
                        remapItem(mapping, "old_stone", "new_stone");
                        remapItem(mapping, "_gate", "_fence_gate");
                    }
                    for (MissingMappingsEvent.Mapping<EntityType<?>> mapping : event.getAllMappings(Registries.ENTITY_TYPE)) {
                        remapEntity(mapping, "old_mob", "new_mob");
                    }
                }

                private static void remapBlock(MissingMappingsEvent.Mapping<Block> mapping, String oldId, String newId) {
                    if (mapping.getKey().getPath().contains(oldId)) {
                        ResourceLocation replacement = ResourceLocation.fromNamespaceAndPath(ExampleMod.ID, newId);
                        mapping.remap(null);
                    }
                }

                private static void remapItem(MissingMappingsEvent.Mapping<Item> mapping, String oldId, String newId) {
                    if (mapping.getKey().getPath().contains(oldId)) {
                        ResourceLocation replacement = ResourceLocation.fromNamespaceAndPath(ExampleMod.ID, newId);
                        mapping.remap(null);
                    }
                }

                private static void remapEntity(MissingMappingsEvent.Mapping<EntityType<?>> mapping, String oldId, String newId) {
                    if (mapping.getKey().getPath().contains(oldId)) {
                        ResourceLocation replacement = ResourceLocation.fromNamespaceAndPath(ExampleMod.ID, newId);
                        mapping.remap(null);
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val remapper = utilDir.resolve("LegacyRemapper.java").readText()
        val main = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-missing-mappings-add-alias" }, "changes=${result.changes} errors=${result.errors}")
        assertTrue(result.changes.any { it.ruleId == "struct-missing-mappings-alias-call" }, "changes=${result.changes} errors=${result.errors}")
        assertTrue(remapper.contains("public static void addRegistryAliases()"))
        assertTrue(remapper.contains("remapEntryFromRegistries(\"old_stone\", \"new_stone\", ModBlocks.BLOCKS, ModItems.ITEMS);"))
        assertTrue(remapper.contains("remapEntryFromRegistries(\"dark_gate\", \"dark_fence_gate\", ModBlocks.BLOCKS, ModItems.ITEMS);"))
        assertTrue(remapper.contains("remapEntry(ModEntities.ENTITIES, \"old_mob\", \"new_mob\");"))
        assertTrue(remapper.contains("registry.addAlias(prefix(oldId), prefix(newId));"))
        assertTrue(!remapper.contains("MissingMappingsEvent"))
        assertTrue(!remapper.contains("mapping.remap"))
        assertTrue(main.contains("import com.example.util.LegacyRemapper;"))
        assertTrue(main.contains("LegacyRemapper.addRegistryAliases();"))
    }

    @Test
    fun `migrates strict runtime compile API surfaces without mod-specific rules`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("StrictSurfaces.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.DifficultyInstance;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.Mob;
            import net.minecraft.world.entity.ai.attributes.Attributes;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.ServerLevelAccessor;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.block.state.properties.BooleanProperty;
            import net.minecraft.world.level.block.state.properties.IntegerProperty;
            import net.neoforged.neoforge.capabilities.Capabilities;
            import net.neoforged.neoforge.items.IItemHandler;

            public class StrictSurfaces extends Mob {
                private static final BooleanProperty ACTIVE = BooleanProperty.create("active");
                private static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 8);

                protected StrictSurfaces(EntityType<? extends Mob> type, Level level) {
                    super(type, level);
                }

                @Override
                protected void populateDefaultEquipmentSlots(ServerLevelAccessor accessor, RandomSource random, DifficultyInstance difficulty) {
                    this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }

                public void spawn(ServerLevelAccessor accessor, DifficultyInstance difficulty) {
                    this.populateDefaultEquipmentSlots(accessor, accessor.getRandom(), difficulty);
                    this.populateDefaultEquipmentEnchantments(accessor.getRandom(), difficulty);
                }

                @Override
                public float getStepHeight() {
                    return 2.0F;
                }

                public void bounds(EntityType<?> type, double x, double y, double z, ServerLevel level) {
                    level.noCollision(type.getAABB(x, y, z));
                }

                public int silk(ServerLevel level, ItemStack stack) {
                    return EnchantmentHelper.getTagEnchantmentLevel(Enchantments.SILK_TOUCH, stack)
                        + EnchantmentHelper.getTagEnchantmentLevel(Enchantments.BLOCK_FORTUNE, stack);
                }

                public int customSilk(ItemStack stack) {
                    return EnchantmentHelper.getTagEnchantmentLevel(ExampleEnchantments.DESTRUCTION.get(), stack);
                }

                public boolean frost(LivingEntity living) {
                    return EnchantmentHelper.hasFrostWalker(living);
                }

                @Override
                public boolean doHurtTarget(Entity entity) {
                    float f = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
                    float f1 = (float) this.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
                    if (entity instanceof LivingEntity living) {
                        f += EnchantmentHelper.getDamageBonus(this.getMainHandItem(), living.getMobType());
                        f1 += (float) EnchantmentHelper.getKnockbackBonus(this);
                    }

                    int i = EnchantmentHelper.getFireAspect(this);
                    if (i > 0) {
                        entity.igniteForSeconds(i * 4);
                    }

                    boolean flag = entity.hurt(this.damageSources().mobAttack(this), f);
                    if (flag) {
                        if (f1 > 0.0F && entity instanceof LivingEntity living) {
                            living.knockback(f1 * 0.5F, net.minecraft.util.Mth.sin(this.getYRot() * net.minecraft.util.Mth.DEG_TO_RAD), -net.minecraft.util.Mth.cos(this.getYRot() * net.minecraft.util.Mth.DEG_TO_RAD));
                            this.setDeltaMovement(this.getDeltaMovement().multiply(0.6D, 1.0D, 0.6D));
                        }

                        if (entity instanceof Player player) {
                            this.maybeDisableShield(player, this.getMainHandItem(), player.isUsingItem() ? player.getUseItem() : ItemStack.EMPTY);
                        }

                        this.doEnchantDamageEffects(this, entity);
                        this.setLastHurtMob(entity);
                    }

                    return flag;
                }

                public void caps(Level level, BlockPos blockPos, Entity entity, Direction side) {
                    BlockEntity blockEntity = level.getBlockEntity(blockPos);
                    if (blockEntity != null) {
                        java.util.List<IItemHandler> handlers = new java.util.ArrayList<>();
                        blockEntity.getCapability(Capabilities.ItemHandler.BLOCK, side).ifPresent(handlers::add);
                    }
                    entity.getCapability(Capabilities.ItemHandler.BLOCK, side).ifPresent(handler -> handler.getSlots());
                }

                public boolean legacyStateGetter(BlockState state, BlockState snowOnState) {
                    return state.get(ACTIVE) && snowOnState.get(LAYERS) > 0;
                }

                public boolean canEat(ItemStack stack) {
                    return stack.getItem().has(net.minecraft.core.component.DataComponents.FOOD);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyAnimalFood.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.AgeableMob;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.ai.goal.TemptGoal;
            import net.minecraft.world.entity.animal.Animal;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.level.Level;

            public class LegacyAnimalFood extends Animal {
                public LegacyAnimalFood(EntityType<? extends LegacyAnimalFood> type, Level level) {
                    super(type, level);
                }

                @Override
                protected void registerGoals() {
                    this.goalSelector.addGoal(1, new TemptGoal(this, 1.0D, Ingredient.of(Items.WHEAT), false));
                }

                @Override
                public LegacyAnimalFood getBreedOffspring(ServerLevel level, AgeableMob mate) {
                    return null;
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyTamableFood.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.entity.AgeableMob;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.TamableAnimal;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.phys.Vec3;

            public class LegacyTamableFood extends TamableAnimal {
                public LegacyTamableFood(EntityType<? extends LegacyTamableFood> type, Level level) {
                    super(type, level);
                }

                @Override
                public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob mate) {
                    return null;
                }

                @Override
                public InteractionResult interactAt(Player player, Vec3 vec3, InteractionHand hand) {
                    if (this.getOwner() != null && this.getOwner().is(player) && player.getMainHandItem().is(Items.ROTTEN_FLESH)) {
                        return InteractionResult.SUCCESS;
                    }
                    return super.interactAt(player, vec3, hand);
                }
            }
        """.trimIndent())
        srcDir.resolve("SpawnBlock.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;

            public class SpawnBlock extends Block {
                public SpawnBlock(Properties properties) {
                    super(properties);
                }

                @Override
                public boolean isValidSpawn(BlockState state, BlockGetter getter, BlockPos pos, EntityType<?> entityType) {
                    return false;
                }
            }
        """.trimIndent())
        srcDir.resolve("NamedContainerBlockEntity.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.inventory.AbstractContainerMenu;
            import net.minecraft.world.inventory.InventoryMenu;
            import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class NamedContainerBlockEntity extends FurnaceBlockEntity {
                public NamedContainerBlockEntity(BlockPos pos, BlockState state) {
                    super(pos, state);
                }

                @Override
                protected Component getDefaultName() {
                    return Component.literal("demo");
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyCapabilityHandler.java").writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.common.util.INBTSerializable;

            public class LegacyCapabilityHandler implements INBTSerializable<CompoundTag> {
                @Override
                public CompoundTag serializeNBT() {
                    return new CompoundTag();
                }

                @Override
                public void deserializeNBT(CompoundTag tag) {
                }
            }
        """.trimIndent())
        srcDir.resolve("PlainFurnaceBlockEntity.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
            import net.minecraft.world.level.block.state.BlockState;

            public class PlainFurnaceBlockEntity extends FurnaceBlockEntity {
                public PlainFurnaceBlockEntity(BlockPos pos, BlockState state) {
                    super(pos, state);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyMushroomBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.MushroomBlock;

            public class LegacyMushroomBlock extends MushroomBlock {
                public LegacyMushroomBlock(Properties properties) {
                    super(properties, ModFeatures.BIG_MUSHROOM);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyWallSignBlock.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.WallSignBlock;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.block.state.properties.WoodType;

            public class LegacyWallSignBlock extends WallSignBlock {
                public static final com.mojang.serialization.MapCodec<LegacyWallSignBlock> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
                        WoodType.CODEC.fieldOf("type").forGetter(LegacyWallSignBlock::getType),
                        propertiesCodec()
                    ).apply(instance, (type, properties) -> new LegacyWallSignBlock(properties, type)));

                @Override
                public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.WallSignBlock> codec() {
                    return CODEC;
                }

                public LegacyWallSignBlock(Properties properties, WoodType type) {
                    super(type, properties);
                }

                @Override
                public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
                    return null;
                }
            }
        """.trimIndent())
        srcDir.resolve("PaintingCodecSurface.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import java.util.Optional;

            public record PaintingCodecSurface(String path, Optional<Parallax> parallax) {
                public static final MapCodec<PaintingCodecSurface> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.STRING.fieldOf("path").forGetter(PaintingCodecSurface::path),
                    Parallax.CODEC.optionalFieldOf("parallax").forGetter(PaintingCodecSurface::parallax)
                ).apply(instance, PaintingCodecSurface::new));

                public record Parallax(float multiplier) {
                    public static final MapCodec<Parallax> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Codec.FLOAT.fieldOf("multiplier").forGetter(Parallax::multiplier)
                    ).apply(instance, Parallax::new));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyRegistrySurface.java").writeText("""
            package com.example;

            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.registries.DeferredRegister;
            import net.neoforged.neoforge.registries.RegistryBuilder;
            import java.util.Optional;
            import java.util.function.Supplier;

            public record LegacyRegistrySurface(ResourceLocation texture) {
                public static final ResourceKey<Registry<LegacyRegistrySurface>> SURFACE_KEY = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("example", "surface"));
                public static final DeferredRegister<LegacyRegistrySurface> SURFACES = DeferredRegister.create(SURFACE_KEY, "example");
                public static final Supplier<Registry<LegacyRegistrySurface>> REGISTRY = SURFACES.makeRegistry(() -> new RegistryBuilder<LegacyRegistrySurface>().hasTags());

                public static LegacyRegistrySurface random(int index) {
                    return LegacyRegistrySurface.REGISTRY.get().getValues().toArray(LegacyRegistrySurface[]::new)[index % LegacyRegistrySurface.REGISTRY.get().getValues().size()];
                }

                public static int count() {
                    return REGISTRY.get().getValues().size();
                }

                public static Optional<LegacyRegistrySurface> byId(String id) {
                    return Optional.ofNullable(REGISTRY.get().getValue(ResourceLocation.parse(id)));
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientRenderingSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.systems.RenderSystem;
            import com.mojang.blaze3d.vertex.BufferBuilder;
            import com.mojang.blaze3d.vertex.DefaultVertexFormat;
            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.Tesselator;
            import com.mojang.blaze3d.vertex.VertexFormat;
            import net.minecraft.client.Minecraft;
            import net.neoforged.neoforge.client.event.RenderHighlightEvent;
            import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
            import net.neoforged.neoforge.client.event.ViewportEvent;

            public class ClientRenderingSurface {
                public void render(Minecraft minecraft, double x, double y, double z, int u, int v) {
                    PoseStack stack = RenderSystem.getModelViewStack();
                    float partialTick = minecraft.getPartialTick();
                    float frameTime = minecraft.getFrameTime();
                    BufferBuilder bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                    bufferbuilder.vertex(x, y, z).setUv(0.0F, partialTick).color(1.0F, 1.0F, 1.0F, partialTick).setLight(u, v);
                    BufferUploader.drawWithShader(buffer.buildOrThrow());
                }

                public void level(RenderLevelStageEvent event) {
                    double partialTick = event.getPartialTick();
                }

                public void highlight(RenderHighlightEvent.Block event) {
                    double partialTick = event.getPartialTick();
                }

                public void fog(ViewportEvent.ComputeFogColor event) {
                    double partialTick = event.getPartialTick();
                }
            }
        """.trimIndent())
        srcDir.resolve("PackedColorModel.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import net.minecraft.client.model.Model;
            import net.minecraft.client.model.geom.ModelPart;

            public class PackedColorModel extends Model {
                private final ModelPart root;

                public PackedColorModel(ModelPart root) {
                    super(null);
                    this.root = root;
                }

                public void renderToBuffer(PoseStack stack, VertexConsumer consumer, int light, int overlay, int color) {
                    this.root.render(stack, consumer, light, overlay, red, green, blue, alpha);
                    super.renderToBuffer(stack, consumer, light, overlay, FastColor.ARGB32.colorFromFloat(scale, red, green, blue));
                }
            }
        """.trimIndent())
        srcDir.resolve("ShortPackedColorModel.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import net.minecraft.client.model.Model;
            import net.minecraft.client.model.geom.ModelPart;

            public class ShortPackedColorModel extends Model {
                private final ModelPart root;

                public ShortPackedColorModel(ModelPart root) {
                    super(null);
                    this.root = root;
                }

                public void renderToBuffer(PoseStack stack, VertexConsumer consumer, int light, int overlay, int color) {
                    this.root.render(stack, consumer, light, overlay, r, g, b, a);
                    super.renderToBuffer(stack, consumer, light, overlay, FastColor.ARGB32.colorFromFloat(0.6F, r, g, b));
                }
            }
        """.trimIndent())
        srcDir.resolve("ItemRendererSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.client.renderer.texture.OverlayTexture;
            import net.minecraft.client.resources.model.BakedModel;
            import net.minecraft.world.item.ItemDisplayContext;
            import net.minecraft.world.item.ItemStack;

            public class ItemRendererSurface {
                public void render(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, BakedModel model) {
                    Minecraft.getInstance().getItemRenderer().render(stack, ItemDisplayContext.GUI, false, poseStack, bufferSource, 15728880, OverlayTexture.NO_OVERLAY, model.applyTransform(context, poseStack, false));
                }
            }
        """.trimIndent())
        srcDir.resolve("EmptyUnsidedHandlerBlockEntity.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.items.IItemHandler;
            import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;

            public class EmptyUnsidedHandlerBlockEntity {
                @Override
                protected IItemHandler createUnSidedHandler() {
                    return EmptyItemHandler.INSTANCE;
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyHangingEntity.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
            import net.minecraft.world.entity.decoration.HangingEntity;
            import net.minecraft.world.phys.Vec3;

            public class LegacyHangingEntity extends HangingEntity {
                @Override
                public int getWidth() {
                    return 32;
                }

                @Override
                public int getHeight() {
                    return 16;
                }

                @Override
                public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
                    this.setPos(x, y, z);
                }

                @Override
                public Vec3 trackingPosition() {
                    return Vec3.atLowerCornerOf(this.pos);
                }

                public void refresh() {
                    this.recalculateBoundingBox();
                }
            }
        """.trimIndent())
        srcDir.resolve("SpriteVertexSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.VertexConsumer;
            import net.minecraft.client.renderer.texture.TextureAtlasSprite;
            import org.joml.Matrix4f;

            public class SpriteVertexSurface {
                void render(VertexConsumer vertex, Matrix4f matrix4f, TextureAtlasSprite sprite, float x, float y, float z, double width) {
                    float u = sprite.getU(width * (double) 2);
                    float v = sprite.getV(1.0D);
                    vertex.vertex(matrix4f, x, y, z).color(255, 255, 255, 255);
                }
            }
        """.trimIndent())
        srcDir.resolve("GuiLayerSurface.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.Gui;
            import net.minecraft.client.gui.GuiGraphics;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
            import net.neoforged.neoforge.client.gui.overlay.VanillaGuiOverlay;

            @EventBusSubscriber(modid = ExampleMod.ID)
            public class GuiLayerSurface {
                public static void registerOverlays(RegisterGuiLayersEvent event) {
                    event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "indicator", (gui, graphics, partialTick, screenWidth, screenHeight) -> {
                        render(graphics, gui, partialTick, screenWidth, screenHeight);
                    });
                }

                private static void render(GuiGraphics graphics, Gui gui, float partialTick, int screenWidth, int screenHeight) {
                }
            }
        """.trimIndent())
        srcDir.resolve("PoseNormalSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import org.joml.Matrix3f;
            import org.joml.Matrix4f;

            public class PoseNormalSurface {
                public void render(PoseStack stack, VertexConsumer vertex) {
                    PoseStack.Pose pose = stack.last();
                    Matrix4f matrix = pose.pose();
                    Matrix3f normal = pose.normal();
                    emit(vertex, matrix, normal, 15);
                    vertex.addVertex(matrix, 0.0F, 0.0F, 0.0F).setNormal(normal, 0.0F, 1.0F, 0.0F);
                    vertex.addVertex(matrix, 0.0F, 1.0F, 0.0F).normal(pose.normal(), 0.0F, 1.0F, 0.0F);
                }

                private static void emit(VertexConsumer vertex, Matrix4f matrix, Matrix3f normal, int light) {
                    vertex.addVertex(matrix, 1.0F, 0.0F, 0.0F).setLight(light).setNormal(normal, 0.0F, 1.0F, 0.0F);
                }
            }
        """.trimIndent())
        srcDir.resolve("CollectionModelRenderSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import java.util.List;
            import net.minecraft.client.model.geom.ModelPart;
            import net.minecraft.util.FastColor;

            public class CollectionModelRenderSurface {
                private final List<ModelPart> parts;
                private final ModelPart[] segments;

                public CollectionModelRenderSurface(List<ModelPart> parts, ModelPart[] segments) {
                    this.parts = parts;
                    this.segments = segments;
                }

                public void render(PoseStack stack, VertexConsumer builder, int light, int overlay, int dyeRgb, float red, float green, float blue, float scale, float alpha) {
                    this.parts.forEach((renderer) -> renderer.render(stack, builder, light, overlay, red, green, blue, scale));
                    this.segments[0].render(stack, builder, light, overlay, FastColor.ARGB32.red(dyeRgb) / 255.0F, FastColor.ARGB32.green(dyeRgb) / 255.0F, FastColor.ARGB32.blue(dyeRgb) / 255.0F, alpha);
                }
            }
        """.trimIndent())
        srcDir.resolve("LossyCompoundSurface.java").writeText("""
            package com.example;

            import net.minecraft.client.model.geom.ModelPart;

            public class LossyCompoundSurface {
                private float glowIntensity;
                private float rangle;
                private int damageTaken;
                private final ModelPart arm;

                public LossyCompoundSurface(ModelPart arm) {
                    this.arm = arm;
                }

                public void tick(float amount, double rotation) {
                    this.glowIntensity += 0.05;
                    this.rangle += rotation;
                    this.damageTaken += amount;
                    this.arm.xRot += Math.PI * 1.25;
                }
            }
        """.trimIndent())
        srcDir.resolve("ModelEventSurface.java").writeText("""
            package com.example;

            import java.util.List;
            import java.util.Map;
            import net.minecraft.client.resources.model.BakedModel;
            import net.minecraft.client.resources.model.ModelResourceLocation;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.client.event.ModelEvent;

            @EventBusSubscriber(modid = ExampleMod.ID)
            public class ModelEventSurface {
                public static void loaders(ModelEvent.RegisterGeometryLoaders event) {
                    event.register("example_loader", ExampleLoader.INSTANCE);
                }

                public static void bake(ModelEvent.ModifyBakingResult event) {
                    List<Map.Entry<ResourceLocation, BakedModel>> models = event.getModels().entrySet().stream()
                            .filter(entry -> entry.getKey().getNamespace().equals(ExampleMod.ID) && entry.getKey().getPath().contains("leaves")).toList();
                    models.forEach(entry -> event.getModels().put(entry.getKey(), new WrappedModel(entry.getValue())));
                }

                public static void additional(ModelEvent.RegisterAdditional event) {
                    event.register(ExampleMod.prefix("block/surface"));
                    event.register(new ModelResourceLocation(ExampleMod.prefix("item/surface"), "inventory"));
                }
            }
        """.trimIndent())
        srcDir.resolve("TesselatorHelperSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.BufferBuilder;
            import com.mojang.blaze3d.vertex.DefaultVertexFormat;
            import com.mojang.blaze3d.vertex.Tesselator;
            import com.mojang.blaze3d.vertex.VertexFormat;

            public class TesselatorHelperSurface {
                private TesselatorHelperSurface shader;

                public final void invokeThenEndTesselator(Runnable execBind) {
                    invokeThenClear(execBind, () -> Tesselator.getInstance().end());
                }

                public final void invokeThenEndTesselator() {
                    invokeThenClear(() -> Tesselator.getInstance().end());
                }

                public final void invokeThenEndTesselator(int seed, float x, float y, float z) {
                    invokeThenClear(seed, x, y, z, () -> Tesselator.getInstance().end());
                }

                public void render(float x, float y, float z) {
                    BufferBuilder buffer = Tesselator.getInstance().getBuilder();
                    buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                    buffer.vertex(x, y, z).color(1.0F, 1.0F, 1.0F, 1.0F);
                    shader.invokeThenEndTesselator(1, x, y, z);
                }
            }
        """.trimIndent())
        srcDir.resolve("TesselatorVariableSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.BufferBuilder;
            import com.mojang.blaze3d.vertex.DefaultVertexFormat;
            import com.mojang.blaze3d.vertex.MeshData;
            import com.mojang.blaze3d.vertex.Tesselator;
            import com.mojang.blaze3d.vertex.VertexFormat;

            public class TesselatorVariableSurface {
                private MeshData build() {
                    Tesselator tessellator = Tesselator.getInstance();
                    BufferBuilder builder = tessellator.getBuilder();
                    builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
                    tessellator.end();
                    return builder.end();
                }
            }
        """.trimIndent())
        srcDir.resolve("ColorRecord.java").writeText("""
            package com.example;

            public record ColorRecord(int r, int g, int b) {
                public static final com.mojang.serialization.MapCodec<ColorRecord> CODEC =
                        com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
                                com.mojang.serialization.Codec.INT.fieldOf("r").forGetter(value -> value.r),
                                com.mojang.serialization.Codec.INT.fieldOf("g").forGetter(value -> value.g),
                                com.mojang.serialization.Codec.INT.fieldOf("b").forGetter(value -> value.b)
                        ).apply(instance, ColorRecord::new));
            }
        """.trimIndent())
        srcDir.resolve("RecordComponentConsumer.java").writeText("""
            package com.example;

            public class RecordComponentConsumer {
                public int color(ColorRecord data) {
                    return data.r + data.g + data.b;
                }
            }
        """.trimIndent())
        srcDir.resolve("RendererSetupSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.model.EntityModel;
            import net.minecraft.client.renderer.entity.MobRenderer;
            import net.minecraft.world.entity.LivingEntity;

            public class RendererSetupSurface<T extends LivingEntity, M extends EntityModel<T>> extends MobRenderer<T, M> {
                @Override
                protected void setupRotations(T entity, PoseStack stack, float ageInTicks, float rotationYaw, float partialTicks) {
                    super.setupRotations(entity, stack, ageInTicks, rotationYaw, partialTicks);
                }
            }
        """.trimIndent())
        srcDir.resolve("CommandSourceStackSurface.java").writeText("""
            package com.example;

            import net.minecraft.commands.CommandSourceStack;

            public class CommandSourceStackSurface {
                public void run(CommandSourceStack source) {
                    source.level().getSeed();
                }
            }
        """.trimIndent())
        srcDir.resolve("ItemStackSerializationSurface.java").writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public abstract class ItemStackSerializationSurface extends Entity {
                private ItemStack stack;

                protected ItemStackSerializationSurface(EntityType<?> type, Level level) {
                    super(type, level);
                }

                protected void readAdditionalSaveData(CompoundTag tag) {
                    this.stack = ItemStack.parseOptional(player.registryAccess(), tag.getCompound("Stack"));
                }

                protected void addAdditionalSaveData(CompoundTag tag) {
                    tag.put("Stack", this.stack.save());
                }
            }
        """.trimIndent())
        srcDir.resolve("MeleeAttackGoalSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.Mob;
            import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

            public class MeleeAttackGoalSurface extends MeleeAttackGoal {
                public MeleeAttackGoalSurface(Mob mob) {
                    super(mob, 1.0D, false);
                }

                @Override
                protected void checkAndPerformAttack(LivingEntity target, double distance) {
                    super.checkAndPerformAttack(target, Math.min(distance, 1.0D));
                }
            }
        """.trimIndent())
        srcDir.resolve("ApiBridgeSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.serialization.Codec;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.cauldron.CauldronInteraction;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.common.Tags;

            import java.util.List;

            public class ApiBridgeSurface {
                public static final Codec<List<ResourceLocation>> IDS = ResourceLocation.CODEC.codec().listOf();

                public void pose(PoseStack modelView, PoseStack source) {
                    modelView.mulPoseMatrix(source.last().pose());
                }

                public void load(Level level, BlockPos pos, CompoundTag tag) {
                    BlockEntity blockentity = level.getBlockEntity(pos);
                    if (blockentity != null) {
                        blockentity.load(tag);
                    }
                }

                public boolean ranged(ItemStack stack) {
                    return stack.is(Tags.Items.TOOLS_BOWS) || stack.is(Tags.Items.TOOLS_CROSSBOWS) || stack.is(Tags.Items.TOOLS_FISHING_RODS);
                }

                public void cauldron() {
                    CauldronInteraction.WATER.put(Items.LEATHER_HELMET, CauldronInteraction.DYED_ITEM);
                }
            }
        """.trimIndent())
        srcDir.resolve("DistExecutorSurface.java").writeText("""
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.fml.DistExecutor;

            public class DistExecutorSurface {
                public void init() {
                    DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientHooks::init);
                }
            }
        """.trimIndent())
        srcDir.resolve("CraftingBoundarySurface.java").writeText("""
            package com.example;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.stream.Collectors;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.inventory.AbstractContainerMenu;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.inventory.ResultContainer;
            import net.minecraft.world.inventory.TransientCraftingContainer;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.item.crafting.ShapedRecipe;
            import net.minecraft.world.level.GameRules;
            import net.minecraft.world.level.Level;

            public class CraftingBoundarySurface extends AbstractContainerMenu {
                public final CraftingContainer assemblyMatrix = new TransientCraftingContainer(this, 3, 3);
                private final CraftingContainer combineMatrix = new TransientCraftingContainer(this, 3, 3);
                private final ResultContainer result = new ResultContainer();
                private final Level level;
                private final Player player;
                private int recipeInCycle;

                protected CraftingBoundarySurface(Level level, Player player) {
                    super(null, 0);
                    this.level = level;
                    this.player = player;
                    this.chooseRecipe(this.combineMatrix);
                }

                private static Recipe<?>[] getRecipesFor(ItemStack inputStack, Level world) {
                    List<Recipe<?>> recipes = new ArrayList<>();
                    for (Recipe<?> recipe : world.getRecipeManager().getRecipes()) {
                        if (recipe instanceof CraftingRecipe &&
                                !recipe.getResultItem(world.registryAccess()).isEmpty() &&
                                ExampleConfig.disabledRecipes.contains(recipe.getId().toString())) {
                            recipes.add(recipe);
                        }
                    }
                    for (ExampleRecipe exampleRecipe : world.getRecipeManager().getAllRecipesFor(ExampleRecipes.EXAMPLE_TYPE.get())) {
                        if (exampleRecipe.accepts(inputStack)) recipes.add(exampleRecipe);
                    }
                    return recipes.toArray(new Recipe<?>[0]);
                }

                private static boolean isRecipeSupported(Recipe<?> recipe) {
                    return recipe instanceof ShapedRecipe;
                }

                private static CraftingRecipe[] getRecipesFor(CraftingContainer matrix, Level world) {
                    return world.getRecipeManager().getRecipesFor(RecipeType.CRAFTING, matrix, world).toArray(new CraftingRecipe[0]);
                }

                private void chooseRecipe(CraftingContainer inventory) {
                    CraftingRecipe[] recipes = getRecipesFor(inventory, this.level);
                    CraftingRecipe recipe = recipes[Math.floorMod(this.recipeInCycle, recipes.length)];
                    if (recipe != null && !recipe.isSpecial() && (!this.level.getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING) || ((ServerPlayer) this.player).getRecipeBook().contains(recipe))) {
                        this.result.setRecipeUsed(recipe);
                        this.result.setItem(0, recipe.assemble(inventory, this.level.registryAccess()));
                    }
                }

                public List<CraftingRecipe> visibleRecipes(RecipeManager manager) {
                    List<CraftingRecipe> recipes = manager.getAllRecipesFor(RecipeType.CRAFTING);
                    recipes = recipes.stream().filter(recipe ->
                            !recipe.getResultItem(this.level.registryAccess()).isEmpty() &&
                                    ExampleConfig.disabledRecipes.contains(recipe.getId().toString()))
                            .collect(Collectors.toList());
                    recipes.addAll(manager.getAllRecipesFor(ExampleRecipes.EXAMPLE_TYPE.get()));
                    return new ArrayList<>(manager.getAllRecipesFor(ExampleRecipes.EXAMPLE_TYPE.get()));
                }

                @Override
                public boolean stillValid(Player player) {
                    return true;
                }
            }
        """.trimIndent())
        srcDir.resolve("BlockStateRegistryRecipe.java").writeText("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.Container;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.state.BlockState;
            import org.jetbrains.annotations.Nullable;

            public record BlockStateRegistryRecipe(ResourceLocation recipeID, BlockState input, BlockState result) implements Recipe<Container> {
                @Override
                public boolean matches(Container container, Level level) { return true; }

                @Override
                public ItemStack assemble(Container container, HolderLookup.Provider access) { return ItemStack.EMPTY; }

                @Override
                public boolean canCraftInDimensions(int width, int height) { return true; }

                @Override
                public ItemStack getResultItem(HolderLookup.Provider access) { return ItemStack.EMPTY; }

                @Override
                public ResourceLocation getId() { return this.recipeID; }

                @Override
                public RecipeSerializer<?> getSerializer() { return ExampleRecipes.BLOCK_STATE_SERIALIZER.get(); }

                @Override
                public RecipeType<?> getType() { return ExampleRecipes.BLOCK_STATE_RECIPE.get(); }

                public static class Serializer implements RecipeSerializer<BlockStateRegistryRecipe> {
                    @Override
                    public BlockStateRegistryRecipe fromJson(ResourceLocation id, JsonObject object) {
                        Block input = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(GsonHelper.getAsString(object, "from")));
                        Block output = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(GsonHelper.getAsString(object, "to")));
                        if (input != null && output != null) {
                            return new BlockStateRegistryRecipe(id, input.defaultBlockState(), output.defaultBlockState());
                        }
                        return new BlockStateRegistryRecipe(id, Blocks.AIR.defaultBlockState(), Blocks.AIR.defaultBlockState());
                    }

                    @Nullable
                    @Override
                    public BlockStateRegistryRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
                        Block input = buffer.readRegistryIdUnsafe(BuiltInRegistries.BLOCK);
                        Block output = buffer.readRegistryIdUnsafe(BuiltInRegistries.BLOCK);
                        return new BlockStateRegistryRecipe(id, input.defaultBlockState(), output.defaultBlockState());
                    }

                    @Override
                    public void toNetwork(FriendlyByteBuf buffer, BlockStateRegistryRecipe recipe) {
                        buffer.writeRegistryIdUnsafe(BuiltInRegistries.BLOCK, recipe.input().getBlock());
                        buffer.writeRegistryIdUnsafe(BuiltInRegistries.BLOCK, recipe.result().getBlock());
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityRegistryRecipe.java").writeText("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.Container;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;
            import org.jetbrains.annotations.Nullable;

            public record EntityRegistryRecipe(ResourceLocation recipeID, EntityType<?> input, EntityType<?> result, boolean isReversible) implements Recipe<Container> {
                @Override
                public boolean matches(Container container, Level level) { return true; }

                @Override
                public ItemStack assemble(Container container, HolderLookup.Provider access) { return ItemStack.EMPTY; }

                @Override
                public boolean canCraftInDimensions(int width, int height) { return true; }

                @Override
                public ItemStack getResultItem(HolderLookup.Provider access) { return ItemStack.EMPTY; }

                @Override
                public ResourceLocation getId() { return this.recipeID; }

                @Override
                public RecipeSerializer<?> getSerializer() { return ExampleRecipes.ENTITY_SERIALIZER.get(); }

                @Override
                public RecipeType<?> getType() { return ExampleRecipes.ENTITY_RECIPE.get(); }

                public static class Serializer implements RecipeSerializer<EntityRegistryRecipe> {
                    @Override
                    public EntityRegistryRecipe fromJson(ResourceLocation id, JsonObject object) {
                        EntityType<?> input = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(GsonHelper.getAsString(object, "from")));
                        EntityType<?> output = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(GsonHelper.getAsString(object, "to")));
                        boolean reversible = GsonHelper.getAsBoolean(object, "reversible");
                        return new EntityRegistryRecipe(id, input, output, reversible);
                    }

                    @Nullable
                    @Override
                    public EntityRegistryRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
                        EntityType<?> input = buffer.readRegistryIdUnsafe(BuiltInRegistries.ENTITY_TYPE);
                        EntityType<?> output = buffer.readRegistryIdUnsafe(BuiltInRegistries.ENTITY_TYPE);
                        boolean reversible = buffer.readBoolean();
                        return new EntityRegistryRecipe(id, input, output, reversible);
                    }

                    @Override
                    public void toNetwork(FriendlyByteBuf buffer, EntityRegistryRecipe recipe) {
                        buffer.writeRegistryIdUnsafe(BuiltInRegistries.ENTITY_TYPE, recipe.input());
                        buffer.writeRegistryIdUnsafe(BuiltInRegistries.ENTITY_TYPE, recipe.result());
                        buffer.writeBoolean(recipe.isReversible());
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyShapedRecordRecipe.java").writeText("""
            package com.example;

            import com.google.common.collect.Maps;
            import com.google.common.collect.Sets;
            import com.google.gson.JsonArray;
            import com.google.gson.JsonElement;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonSyntaxException;
            import java.util.Arrays;
            import java.util.Map;
            import java.util.Set;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.core.NonNullList;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.util.GsonHelper;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.item.crafting.ShapedRecipe;
            import net.minecraft.world.level.Level;
            import org.jetbrains.annotations.Nullable;

            public record LegacyShapedRecordRecipe(ResourceLocation recipeID, int cost, int width, int height, Ingredient input, int count, NonNullList<Ingredient> resultItems) implements CraftingRecipe, ShapedRecipe {
                @Override
                public boolean matches(CraftingContainer container, Level level) { return false; }

                @Override
                public ItemStack assemble(CraftingContainer container, HolderLookup.Provider access) { return ItemStack.EMPTY; }

                @Override
                public ItemStack getResultItem(HolderLookup.Provider access) { return new ItemStack(Items.AIR, this.count); }

                @Override
                public boolean canCraftInDimensions(int width, int height) { return width >= this.width && height >= this.height; }

                public boolean isItemStackAnIngredient(ItemStack stack) {
                    return Arrays.stream(this.input().getItems()).anyMatch(i -> stack.is(i.getItem()) && stack.getCount() >= this.count());
                }

                @Override
                public ResourceLocation getId() { return this.recipeID; }

                @Override
                public RecipeSerializer<?> getSerializer() { return ExampleRecipes.LEGACY_SHAPED_SERIALIZER.get(); }

                @Override
                public RecipeType<?> getType() { return ExampleRecipes.LEGACY_SHAPED_RECIPE.get(); }

                @Override
                public CraftingBookCategory category() { return CraftingBookCategory.MISC; }

                @Override
                public NonNullList<Ingredient> getIngredients() { return this.resultItems(); }

                public static class Serializer implements RecipeSerializer<LegacyShapedRecordRecipe> {
                    @Override
                    public LegacyShapedRecordRecipe fromJson(ResourceLocation id, JsonObject json) { return null; }

                    @Nullable
                    @Override
                    public LegacyShapedRecordRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) { return null; }

                    @Override
                    public void toNetwork(FriendlyByteBuf buffer, LegacyShapedRecordRecipe recipe) {}
                }
            }
        """.trimIndent())
        srcDir.resolve("RenderArrayColorSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import net.minecraft.client.model.EntityModel;
            import net.minecraft.client.renderer.texture.OverlayTexture;
            import net.minecraft.world.entity.Entity;

            public class RenderArrayColorSurface {
                private EntityModel<Entity> model;
                private float[] necklaceColors = new float[]{1.0F, 0.0F, 0.0F};

                public void render(PoseStack stack, VertexConsumer vertexConsumer, int light) {
                    this.model.renderToBuffer(stack, vertexConsumer, light, OverlayTexture.NO_OVERLAY, this.necklaceColors[0], this.necklaceColors[1], this.necklaceColors[2], 1.0F);
                }
            }
        """.trimIndent())
        srcDir.resolve("StaticModBusSurface.java").writeText("""
            package com.example;

            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

            @EventBusSubscriber(value = Dist.CLIENT, modid = ExampleMod.ID)
            public class StaticModBusSurface {
                public static void init() {
                    IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
                    Shaders.init(bus);
                }
            }
        """.trimIndent())
        srcDir.resolve("BlockLootSurface.java").writeText("""
            package com.example;

            import net.minecraft.advancements.critereon.ItemPredicate;
            import net.minecraft.data.loot.BlockLootSubProvider;
            import net.minecraft.world.flag.FeatureFlags;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.storage.loot.LootTable;
            import net.minecraft.world.level.storage.loot.entries.LootItem;
            import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
            import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;
            import net.minecraft.world.level.storage.loot.predicates.BonusLevelTableCondition;
            import net.minecraft.world.level.storage.loot.predicates.MatchTool;

            import java.util.Set;

            public class BlockLootSurface extends BlockLootSubProvider {
                private static final LootItemCondition.Builder HAS_SHEARS = MatchTool.toolMatches(ItemPredicate.Builder.item().of(Items.SHEARS));

                public BlockLootSurface() {
                    super(Set.of(), FeatureFlags.REGISTRY.allFlags());
                }

                private LootTable.Builder leaves(Block block) {
                    return createSilkTouchOrShearsDispatchTable(block,
                            LootItem.lootTableItem(Items.STICK)
                                    .when(BonusLevelTableCondition.bonusLevelFlatChance(Enchantments.FORTUNE, 0.1F))
                                    .apply(ApplyBonusCount.addUniformBonusCount(Enchantments.FORTUNE)))
                            .withPool(net.minecraft.world.level.storage.loot.LootPool.lootPool().when(HAS_SHEARS.or(HAS_SILK_TOUCH).invert()));
                }

                protected static LootTable.Builder createSilkTouchOrShearsDispatchTable(Block block, LootPoolEntryContainer.Builder<?> builder) {
                    return createSelfDropDispatchTable(block, HAS_SHEARS.or(HAS_SILK_TOUCH), builder);
                }

                protected static LootTable.Builder createShearsDispatchTable(Block block, LootPoolEntryContainer.Builder<?> builder) {
                    return createSelfDropDispatchTable(block, HAS_SHEARS, builder);
                }

                protected LootTable.Builder createShearsOnlyDrop(net.minecraft.world.level.ItemLike item) {
                    return LootTable.lootTable().withPool(net.minecraft.world.level.storage.loot.LootPool.lootPool()
                            .when(HAS_SHEARS)
                            .add(LootItem.lootTableItem(item)));
                }
            }
        """.trimIndent())
        srcDir.resolve("ResolvableProfileSkullSurface.java").writeText("""
            package com.example;

            import com.mojang.authlib.GameProfile;
            import com.mojang.authlib.minecraft.MinecraftProfileTexture;
            import java.util.Map;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.renderer.RenderType;
            import net.minecraft.client.resources.DefaultPlayerSkin;
            import net.minecraft.core.UUIDUtil;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.component.ResolvableProfile;
            import net.minecraft.world.level.block.SkullBlock;
            import org.jetbrains.annotations.Nullable;

            public class ResolvableProfileSkullSurface {
                public RenderType render(SkullBlock.Type type, ResolvableProfile gameprofile) {
                    return getRenderType(type, gameprofile);
                }

                public static RenderType getRenderType(SkullBlock.Type type, @Nullable GameProfile profile) {
                    ResourceLocation resourcelocation = ResourceLocation.parse("textures/entity/skeleton/skeleton.png");
                    if (type == SkullBlock.Types.PLAYER && profile != null) {
                        Minecraft minecraft = Minecraft.getInstance();
                        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = minecraft.getSkinManager().getInsecureSkinInformation(profile);
                        return map.containsKey(MinecraftProfileTexture.Type.SKIN) ? RenderType.entityTranslucent(minecraft.getSkinManager().registerTexture(map.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN)) : RenderType.entityCutoutNoCull(DefaultPlayerSkin.getDefaultSkin(UUIDUtil.getOrCreatePlayerUUID(profile)));
                    } else {
                        return RenderType.entityCutoutNoCullZOffset(resourcelocation);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyProfileTextureSurface.java").writeText("""
            package com.example;

            import com.mojang.authlib.GameProfile;
            import com.mojang.authlib.minecraft.MinecraftProfileTexture;
            import java.util.Map;
            import java.util.Objects;
            import java.util.UUID;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.resources.DefaultPlayerSkin;
            import net.minecraft.core.UUIDUtil;
            import net.minecraft.resources.ResourceLocation;

            public class LegacyProfileTextureSurface {
                private Object model;
                private final Object slimModel = new Object();
                private final Object normalModel = new Object();

                public ResourceLocation texture(Minecraft minecraft, GameProfile profile) {
                    ResourceLocation texture = DefaultPlayerSkin.getDefaultTexture();
                    this.model = this.normalModel;
                    if (profile != null) {
                        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = minecraft.getSkinManager().getInsecureSkinInformation(profile);
                        if (map.containsKey(MinecraftProfileTexture.Type.SKIN)) {
                            MinecraftProfileTexture profileTexture = map.get(MinecraftProfileTexture.Type.SKIN);
                            texture = minecraft.getSkinManager().registerTexture(map.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN);
                            if (Objects.equals(profileTexture.getMetadata("model"), "slim")) this.model = this.slimModel;
                        } else {
                            UUID uuid = UUIDUtil.getOrCreatePlayerUUID(profile);
                            texture = DefaultPlayerSkin.get(uuid).texture();
                            if (DefaultPlayerSkin.get(uuid).model().id().equals("slim")) this.model = this.slimModel;
                        }
                    }
                    return texture;
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyToast.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.client.gui.components.toasts.Toast;
            import net.minecraft.client.gui.components.toasts.ToastComponent;
            import net.minecraft.network.chat.Component;

            public record LegacyToast(Component title) implements Toast {
                private static final Component UPPER_TEXT = Component.literal("Required");

                @Override
                public Toast.Visibility render(GuiGraphics graphics, ToastComponent component, long timer) {
                    graphics.blit(TEXTURE, 0, 0, 0, 0, this.width(), this.height());
                    return Toast.Visibility.SHOW;
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyEnchantmentComponentSurface.java").writeText("""
            package com.example;

            import java.util.Map;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            public class LegacyEnchantmentComponentSurface {
                public void merge(ItemStack input, ItemStack result) {
                    CompoundTag inputTags = null;
                    if (input.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag() != null) {
                        inputTags = input.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().copy();
                    }

                    // if the result has innate enchantments, add them on to our enchantment map
                    Map<Enchantment, Integer> resultInnateEnchantments = EnchantmentHelper.getEnchantments(result);

                    Map<Enchantment, Integer> inputEnchantments = EnchantmentHelper.getEnchantments(input);
                    // check if the input enchantments can even go onto the result item
                    inputEnchantments.keySet().removeIf(enchantment -> enchantment == null || !enchantment.canEnchant(result));

                    if (inputTags != null) {
                        // remove enchantments and damage, copy tags, re-add filtered enchantments
                        inputTags.remove("ench");
                        inputTags.remove("Damage");
                        result.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(inputTags));
                        EnchantmentHelper.setEnchantments(inputEnchantments, result);
                    }

                    // finally, add any innate enchantments back onto the result
                    for (Map.Entry<Enchantment, Integer> entry : resultInnateEnchantments.entrySet()) {

                        Enchantment ench = entry.getKey();
                        int level = entry.getValue();

                        // only apply enchants that are better than what we already have
                        // also don't add enchantments if they aren't compatible with already existing ones
                        if (EnchantmentHelper.isEnchantmentCompatible(EnchantmentHelper.getEnchantments(result).keySet(), ench) && EnchantmentHelper.getTagEnchantmentLevel(ench, result) < level) {
                            result.enchant(ench, level);
                        }
                    }
                }

                public int cost(ItemStack stack, ItemStack output) {
                    int damagedCost = EnchantmentHelper.getEnchantments(output).size();
                    int count = 0;

                    for (Map.Entry<Enchantment, Integer> entry : EnchantmentHelper.getEnchantments(stack).entrySet()) {
                        Enchantment ench = entry.getKey();
                        int level = entry.getValue();

                        if (ench != null && level > 0) {
                            count += getWeightModifier(ench) * level;
                            count += 1;
                        }
                    }

                    return count + damagedCost;
                }

                public boolean hit(ServerLevel level, Entity target, ItemStack stack, DamageSource source) {
                    float damage = 0.0F;
                    if (target instanceof LivingEntity living) {
                        damage = 10 + EnchantmentHelper.getDamageBonus(stack, living.getMobType());
                    }
                    return target.hurt(source, damage);
                }

                private static int getWeightModifier(Enchantment ench) {
                    return switch (ench.getRarity().getWeight()) {
                        case 1 -> 8;
                        case 2 -> 4;
                        case 3, 4, 5 -> 2;
                        default -> 1;
                    };
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacySpawnEggLookupSurface.java").writeText("""
            package com.example;

            import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
            import mezz.jei.api.recipe.RecipeIngredientRole;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.common.DeferredSpawnEggItem;

            public class LegacySpawnEggLookupSurface {
                public void layout(IRecipeLayoutBuilder builder, EntityType<?> input, EntityType<?> output) {
                    builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStack(new ItemStack(DeferredSpawnEggItem.fromEntityType(input)));
                    builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT).addItemStack(new ItemStack(DeferredSpawnEggItem.fromEntityType(output)));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacySpriteSourceProviderSurface.java").writeText("""
            package com.example;

            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.common.data.ExistingFileHelper;
            import net.neoforged.neoforge.common.data.SpriteSourceProvider;

            public class LegacySpriteSourceProviderSurface extends SpriteSourceProvider {
                public LegacySpriteSourceProviderSurface(PackOutput output, ExistingFileHelper helper) {
                    super(output, helper, ExampleMod.ID);
                }

                @Override
                protected void addSources() {
                    this.atlas(ExampleMod.ATLAS);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyDataGeneratorSurface.java").writeText("""
            package com.example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
            import net.neoforged.neoforge.common.data.ExistingFileHelper;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class LegacyDataGeneratorSurface {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
                    ExistingFileHelper helper = event.getExistingFileHelper();
                    DatapackBuiltinEntriesProvider datapackProvider = new RegistryDataGenerator(output, provider);
                    CompletableFuture<HolderLookup.Provider> lookupProvider = datapackProvider.getRegistryProvider();
                    generator.addProvider(event.includeClient(), new LegacySpriteSourceProviderSurface(output, helper));
                    generator.addProvider(event.includeServer(), new LegacyRecipeGenerator(output));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyRecipeBase.java").writeText("""
            package com.example;

            import net.minecraft.advancements.critereon.InventoryChangeTrigger;
            import net.minecraft.advancements.critereon.ItemPredicate;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;

            public abstract class LegacyRecipeBase extends RecipeProvider {
                public LegacyRecipeBase(PackOutput output) {
                    super(output);
                }

                public int durability(Item item) {
                    return item.getMaxDamage();
                }

                protected static InventoryChangeTrigger.TriggerInstance has(TagKey<Item> tag) {
                    return inventoryTrigger(ItemPredicate.Builder.item().of(tag).build());
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyRecipeGenerator.java").writeText("""
            package com.example;

            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.RecipeCategory;
            import net.minecraft.data.recipes.ShapelessRecipeBuilder;
            import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
            import net.minecraft.data.recipes.SpecialRecipeBuilder;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.AbstractCookingRecipe;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class LegacyRecipeGenerator extends LegacyRecipeBase {
                public LegacyRecipeGenerator(PackOutput output) {
                    super(output);
                }

                @Override
                protected void buildRecipes(RecipeOutput consumer) {
                    SpecialRecipeBuilder.special(LegacyRecipeSerializers.SPECIAL.get()).save(consumer, "example:special");
                    SimpleCookingRecipeBuilder.generic(Ingredient.of(Items.BEEF), RecipeCategory.FOOD, Items.COOKED_BEEF, 0.3F, 200, RecipeSerializer.SMELTING_RECIPE).save(consumer, "example:cooked_beef");
                    cooking(consumer, RecipeSerializer.SMOKING_RECIPE, 100);
                    ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, Items.STICK).unlockedBy("has_logs", has(net.minecraft.tags.ItemTags.LOGS)).save(consumer, "example:stick");
                }

                private void cooking(RecipeOutput consumer, RecipeSerializer<? extends AbstractCookingRecipe> serializer, int time) {
                    SimpleCookingRecipeBuilder.generic(Ingredient.of(Items.PORKCHOP), RecipeCategory.FOOD, Items.COOKED_PORKCHOP, 0.3F, time, serializer).save(consumer, "example:cooked_porkchop");
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacySpecialRecipe.java").writeText("""
            package com.example;

            import net.minecraft.world.item.crafting.CraftingBookCategory;

            public class LegacySpecialRecipe {
                public LegacySpecialRecipe(CraftingBookCategory category) {
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyRecipeSerializers.java").writeText("""
            package com.example;

            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class LegacyRecipeSerializers {
                public static final DeferredHolder<RecipeSerializer<LegacySpecialRecipe>, RecipeSerializer<LegacySpecialRecipe>> SPECIAL = null;
                public static final DeferredHolder<RecipeSerializer<LegacyOutputRecipe>, RecipeSerializer<LegacyOutputRecipe>> OUTPUT = null;
            }
        """.trimIndent())
        srcDir.resolve("LegacyOutputRecipe.java").writeText("""
            package com.example;

            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.ShapedRecipePattern;

            public class LegacyOutputRecipe {
                public LegacyOutputRecipe(int cost, Ingredient input, int count, ShapedRecipePattern pattern) {
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyRecipeBuilder.java").writeText("""
            package com.example;

            import com.google.gson.JsonObject;
            import net.minecraft.advancements.CriterionTriggerInstance;
            import net.minecraft.data.recipes.RecipeBuilder;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import org.jetbrains.annotations.Nullable;

            public class LegacyRecipeBuilder implements RecipeBuilder {
                private final Ingredient input = Ingredient.of(Items.STICK);
                private final int count = 1;
                private final int cost = 3;
                private final java.util.List<String> pattern = java.util.List.of("A");
                private final java.util.Map<Character, Ingredient> outputs = java.util.Map.of('A', Ingredient.of(Items.DIAMOND));

                public RecipeBuilder unlockedBy(String name, CriterionTriggerInstance criterion) {
                    return this;
                }

                public RecipeBuilder group(@Nullable String group) {
                    return this;
                }

                public Item getResult() {
                    return Items.STICK;
                }

                public void save(RecipeOutput output, ResourceLocation id) {
                    JsonObject json = new JsonObject();
                    json.add("input", this.input.toJson());
                    output.accept(new Result(id, this.input, this.count, this.cost, this.pattern, this.outputs));
                }

                public static class Result {
                    public Result(ResourceLocation id, Ingredient input, int count, int cost, java.util.List<String> pattern, java.util.Map<Character, Ingredient> outputs) {
                    }

                    public RecipeSerializer<?> getType() {
                        return LegacyRecipeSerializers.OUTPUT.get();
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("MapCodecOwner.java").writeText("""
            package com.example;

            import com.mojang.serialization.MapCodec;

            public record MapCodecOwner(String id) {
                public static final MapCodec<MapCodecOwner> CODEC = null;
            }
        """.trimIndent())
        srcDir.resolve("RegularCodecOwner.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;

            public record RegularCodecOwner(String id) {
                public static final Codec<RegularCodecOwner> CODEC = null;
            }
        """.trimIndent())
        srcDir.resolve("LegacyDatapackRegistrySurface.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.registries.DataPackRegistryEvent;

            public class LegacyDatapackRegistrySurface {
                public void register(DataPackRegistryEvent.NewRegistry event) {
                    event.dataPackRegistry(ExampleRegistries.MAP_CODEC_KEY, MapCodecOwner.CODEC);
                    event.dataPackRegistry(ExampleRegistries.REGULAR_CODEC_KEY, RegularCodecOwner.CODEC);
                    event.dataPackRegistry(ExampleRegistries.SYNCED_MAP_CODEC_KEY, MapCodecOwner.CODEC, MapCodecOwner.CODEC);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyForgeBusBindingSurface.java").writeText("""
            package com.example;

            public class LegacyForgeBusBindingSurface {
                public void init() {
                    Bindings.getForgeBus().get().addListener(ExampleEvents::listen);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyRarityMod.java").writeText("""
            package com.example;

            import net.minecraft.ChatFormatting;
            import net.minecraft.world.item.Rarity;
            import net.neoforged.fml.common.Mod;

            @Mod(LegacyRarityMod.ID)
            public class LegacyRarityMod {
                public static final String ID = "examplemod";
                private static final Rarity rarity = Rarity.create("TWILIGHT", ChatFormatting.DARK_GREEN);

                public static Rarity getRarity() {
                    return rarity;
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyDeferredRegisterOwner.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.levelgen.carver.WorldCarver;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class LegacyDeferredRegisterOwner {
                public static final DeferredRegister<WorldCarver<?>> CARVER_TYPES = DeferredRegister.create(Registries.CARVER, LegacyRarityMod.ID);
            }
        """.trimIndent())
        srcDir.resolve("LegacyDeferredRegisterMain.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;

            public class LegacyDeferredRegisterMain {
                public void init(IEventBus modbus) {
                    modbus.addListener(com.example.LegacyDeferredRegisterOwner::register);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyBiomeSourceSurface.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.core.Holder;
            import net.minecraft.world.level.biome.Biome;
            import net.minecraft.world.level.biome.BiomeSource;
            import net.minecraft.world.level.biome.Climate;

            public class LegacyBiomeSourceSurface extends BiomeSource {
                public static final Codec<LegacyBiomeSourceSurface> LEGACY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.FLOAT.fieldOf("scale").forGetter(source -> source.scale)
                ).apply(instance, LegacyBiomeSourceSurface::new));

                private final float scale;

                public LegacyBiomeSourceSurface(float scale) {
                    this.scale = scale;
                }

                @Override
                protected java.util.stream.Stream<Holder<Biome>> collectPossibleBiomes() { return java.util.stream.Stream.empty(); }

                @Override
                protected Codec<? extends BiomeSource> codec() { return LEGACY_CODEC; }

                @Override
                public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) { return null; }
            }
        """.trimIndent())
        srcDir.resolve("LegacyTopElementSurface.java").writeText("""
            package com.example;

            import mcjty.theoneprobe.api.IElement;
            import net.minecraft.network.FriendlyByteBuf;

            public class LegacyTopElementSurface implements IElement {
                @Override
                public void write(FriendlyByteBuf buf) {
                    buf.writeInt(1);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyDimensionEffectsSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.PoseStack;
            import net.minecraft.client.Camera;
            import net.minecraft.client.multiplayer.ClientLevel;
            import net.minecraft.client.renderer.DimensionSpecialEffects;
            import org.joml.Matrix4f;

            public class LegacyDimensionEffectsSurface extends DimensionSpecialEffects {
                public LegacyDimensionEffectsSurface() {
                    super(128.0F, true, SkyType.NORMAL, false, false);
                }

                @Override
                public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, Camera camera, Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
                    return ExampleSky.render(level, partialTick, poseStack, camera, projectionMatrix, setupFog);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyLootGenerator.java").writeText("""
            package com.example;

            import java.util.List;
            import java.util.Map;
            import java.util.Set;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.loot.LootTableProvider;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.level.storage.loot.LootTable;
            import net.minecraft.world.level.storage.loot.ValidationContext;
            import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

            public class LegacyLootGenerator extends LootTableProvider {
                public LegacyLootGenerator(PackOutput output) {
                    super(output, Set.of(), List.of(
                        new LootTableProvider.SubProviderEntry(LegacyLootTables::new, LootContextParamSets.CHEST)
                    ));
                }

                @Override
                protected void validate(Map<ResourceLocation, LootTable> map, ValidationContext validationContext) {
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyLootDataGenerator.java").writeText("""
            package com.example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class LegacyLootDataGenerator {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = event.getGenerator().getPackOutput();
                    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
                    generator.addProvider(event.includeServer(), new LegacyLootGenerator(output));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyGlobalLootModifierGenerator.java").writeText("""
            package com.example;

            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

            public class LegacyGlobalLootModifierGenerator extends GlobalLootModifierProvider {
                public LegacyGlobalLootModifierGenerator(PackOutput output) {
                    super(output, "example");
                }

                @Override
                protected void start() {
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyGlobalLootModifierData.java").writeText("""
            package com.example;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class LegacyGlobalLootModifierData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = event.getGenerator().getPackOutput();
                    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
                    generator.addProvider(event.includeServer(), new LegacyGlobalLootModifierGenerator(output));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyPackMetadata.java").writeText("""
            package com.example;

            import java.util.Arrays;
            import java.util.function.Function;
            import java.util.stream.Collectors;
            import net.minecraft.DetectedVersion;
            import net.minecraft.network.chat.Component;
            import net.minecraft.server.packs.PackType;
            import net.minecraft.server.packs.metadata.pack.PackMetadataSection;

            public class LegacyPackMetadata {
                public PackMetadataSection metadata() {
                    return new PackMetadataSection(
                        Component.literal("Resources"),
                        DetectedVersion.BUILT_IN.getPackVersion(PackType.CLIENT_RESOURCES),
                        Arrays.stream(PackType.values()).collect(Collectors.toMap(Function.identity(), DetectedVersion.BUILT_IN::getPackVersion)));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyLootTables.java").writeText("""
            package com.example;

            import net.minecraft.Util;
            import net.minecraft.data.loot.LootTableSubProvider;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.minecraft.world.level.storage.loot.LootTable;
            import net.minecraft.world.level.storage.loot.entries.LootItem;
            import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
            import net.minecraft.world.level.storage.loot.functions.EnchantWithLevelsFunction;
            import net.minecraft.world.level.storage.loot.functions.SetEnchantmentsFunction;
            import net.minecraft.world.level.storage.loot.functions.SetNbtFunction;
            import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

            import java.util.function.BiConsumer;
            import net.minecraft.resources.ResourceKey;

            public class LegacyLootTables implements LootTableSubProvider {
                @Override
                public void generate(BiConsumer<ResourceKey<LootTable>, LootTable.Builder> register) {
                    LootTable.lootTable()
                        .withPool(net.minecraft.world.level.storage.loot.LootPool.lootPool()
                            .add(LootItem.lootTableItem(Items.POTION).apply(SetNbtFunction.setTag(Util.make(new CompoundTag(), tag -> tag.putString("Potion", "minecraft:strong_regeneration")))))
                            .add(LootItem.lootTableItem(Items.BUNDLE).apply(SetNbtFunction.setTag(Util.make(new CompoundTag(), tag -> {
                                ListTag items = new ListTag();
                                items.add(new ItemStack(Items.DIAMOND).serializeNBT());
                                items.add(new ItemStack(Items.EMERALD, 2).serializeNBT());
                                tag.put("Items", items);
                            }))))
                            .add(LootItem.lootTableItem(Items.IRON_SWORD).apply(EnchantWithLevelsFunction.enchantWithLevels(ConstantValue.exactly(20))))
                            .add(LootItem.lootTableItem(Items.IRON_PICKAXE).apply(new SetEnchantmentsFunction.Builder(false).withEnchantment(Enchantments.ALL_DAMAGE_PROTECTION, ConstantValue.exactly(1))))
                            .add(LootItem.lootTableItem(Items.BOOK).apply(new EnchantRandomlyFunction.Builder().withEnchantment(ExampleEnchantments.CUSTOM.get()))));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyEntityLootTables.java").writeText("""
            package com.example;

            import net.minecraft.data.loot.EntityLootSubProvider;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.flag.FeatureFlags;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.level.storage.loot.BuiltInLootTables;
            import net.minecraft.world.level.storage.loot.LootContext;
            import net.minecraft.world.level.storage.loot.LootPool;
            import net.minecraft.world.level.storage.loot.LootTable;
            import net.minecraft.world.level.storage.loot.entries.LootItem;
            import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
            import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;
            import net.minecraft.world.level.storage.loot.functions.SetNameFunction;
            import net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition;
            import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

            public class LegacyEntityLootTables extends EntityLootSubProvider {
                protected LegacyEntityLootTables() {
                    super(FeatureFlags.REGISTRY.allFlags());
                }

                @Override
                public void generate() {
                    add(EntityType.ZOMBIE, LootTable.lootTable()
                        .withPool(LootPool.lootPool()
                            .add(LootItem.lootTableItem(Items.ROTTEN_FLESH)
                                .apply(LootingEnchantFunction.lootingMultiplier(UniformGenerator.between(0, 1)))
                                .apply(SetNameFunction.setName(net.minecraft.network.chat.Component.literal("named")))
                                .when(LootItemEntityPropertyCondition.hasProperties(LootContext.EntityTarget.THIS, ENTITY_ON_FIRE)))
                            .add(NestedLootTable.lootTableReference(ResourceKey.create(Registries.LOOT_TABLE, EntityType.SKELETON.getDefaultLootTable())))
                            .add(NestedLootTable.lootTableReference(ResourceKey.create(Registries.LOOT_TABLE, BuiltInLootTables.EMPTY)))));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyHolderParameterSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class LegacyHolderParameterSurface {
                private void helper(DeferredHolder<ExampleBlock, ExampleBlock> block) {
                    block.get();
                }

                private void armor(DeferredHolder<ArmorItem, ArmorItem> armor) {
                    armor.get();
                }

                public void call(DeferredHolder<Block, ExampleBlock> block) {
                    helper(block);
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.Block;

            public class ExampleBlock extends Block {
                public ExampleBlock(Properties properties) {
                    super(properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyMapData.java").writeText("""
            package com.example;

            import net.minecraft.network.chat.Component;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;

            public class LegacyMapData {
                public static class LegacyMarker extends MapDecoration {
                    private final int featureId;

                    public static class RenderContext {
                        public static int light;
                    }

                    public LegacyMarker(int featureId, byte x, byte y, byte rotation) {
                        super(Type.TARGET_X, x, y, rotation, Component.literal("marker"));
                        this.featureId = featureId;
                    }

                    @Override
                    public boolean render(int index) {
                        return getX() + getY() + getRot() + featureId + index > 0;
                    }

                    @Override
                    public boolean equals(Object other) {
                        return false;
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyMapPacket.java").writeText("""
            package com.example;

            import java.util.LinkedHashMap;
            import java.util.Map;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            public class LegacyMapPacket {
                public void copy(MapItemSavedData data, Iterable<LegacyMapData.LegacyMarker> markers) {
                    Map<String, MapDecoration> saved = new LinkedHashMap<>(data.decorations);
                    data.decorations.clear();
                    for (LegacyMapData.LegacyMarker marker : markers) {
                        data.decorations.put(marker.toString(), marker);
                    }
                    data.decorations.putAll(saved);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val surfaces = srcDir.resolve("StrictSurfaces.java").readText()
        val block = srcDir.resolve("SpawnBlock.java").readText()
        val container = srcDir.resolve("NamedContainerBlockEntity.java").readText()
        val capability = srcDir.resolve("LegacyCapabilityHandler.java").readText()
        val plainFurnace = srcDir.resolve("PlainFurnaceBlockEntity.java").readText()
        val mushroom = srcDir.resolve("LegacyMushroomBlock.java").readText()
        val sign = srcDir.resolve("LegacyWallSignBlock.java").readText()
        val paintingCodec = srcDir.resolve("PaintingCodecSurface.java").readText()
        val registrySurface = srcDir.resolve("LegacyRegistrySurface.java").readText()
        val clientRendering = srcDir.resolve("ClientRenderingSurface.java").readText()
        val packedColorModel = srcDir.resolve("PackedColorModel.java").readText()
        val shortPackedColorModel = srcDir.resolve("ShortPackedColorModel.java").readText()
        val itemRendererSurface = srcDir.resolve("ItemRendererSurface.java").readText()
        val emptyUnsidedHandler = srcDir.resolve("EmptyUnsidedHandlerBlockEntity.java").readText()
        val legacyHangingEntity = srcDir.resolve("LegacyHangingEntity.java").readText()
        val spriteVertexSurface = srcDir.resolve("SpriteVertexSurface.java").readText()
        val guiLayerSurface = srcDir.resolve("GuiLayerSurface.java").readText()
        val poseNormalSurface = srcDir.resolve("PoseNormalSurface.java").readText()
        val collectionModelRenderSurface = srcDir.resolve("CollectionModelRenderSurface.java").readText()
        val lossyCompoundSurface = srcDir.resolve("LossyCompoundSurface.java").readText()
        val modelEventSurface = srcDir.resolve("ModelEventSurface.java").readText()
        val tesselatorHelperSurface = srcDir.resolve("TesselatorHelperSurface.java").readText()
        val tesselatorVariableSurface = srcDir.resolve("TesselatorVariableSurface.java").readText()
        val colorRecord = srcDir.resolve("ColorRecord.java").readText()
        val recordComponentConsumer = srcDir.resolve("RecordComponentConsumer.java").readText()
        val rendererSetupSurface = srcDir.resolve("RendererSetupSurface.java").readText()
        val commandSourceStackSurface = srcDir.resolve("CommandSourceStackSurface.java").readText()
        val itemStackSerializationSurface = srcDir.resolve("ItemStackSerializationSurface.java").readText()
        val meleeAttackGoalSurface = srcDir.resolve("MeleeAttackGoalSurface.java").readText()
        val apiBridgeSurface = srcDir.resolve("ApiBridgeSurface.java").readText()
        val distExecutorSurface = srcDir.resolve("DistExecutorSurface.java").readText()
        val craftingBoundarySurface = srcDir.resolve("CraftingBoundarySurface.java").readText()
        val blockStateRegistryRecipe = srcDir.resolve("BlockStateRegistryRecipe.java").readText()
        val entityRegistryRecipe = srcDir.resolve("EntityRegistryRecipe.java").readText()
        val legacyShapedRecordRecipe = srcDir.resolve("LegacyShapedRecordRecipe.java").readText()
        val renderArrayColorSurface = srcDir.resolve("RenderArrayColorSurface.java").readText()
        val staticModBusSurface = srcDir.resolve("StaticModBusSurface.java").readText()
        val blockLootSurface = srcDir.resolve("BlockLootSurface.java").readText()
        val resolvableProfileSkullSurface = srcDir.resolve("ResolvableProfileSkullSurface.java").readText()
        val legacyProfileTextureSurface = srcDir.resolve("LegacyProfileTextureSurface.java").readText()
        val toast = srcDir.resolve("LegacyToast.java").readText()
        val animalFood = srcDir.resolve("LegacyAnimalFood.java").readText()
        val tamableFood = srcDir.resolve("LegacyTamableFood.java").readText()
        val legacyEnchantmentComponentSurface = srcDir.resolve("LegacyEnchantmentComponentSurface.java").readText()
        val legacySpawnEggLookupSurface = srcDir.resolve("LegacySpawnEggLookupSurface.java").readText()
        val legacySpriteSourceProviderSurface = srcDir.resolve("LegacySpriteSourceProviderSurface.java").readText()
        val legacyDataGeneratorSurface = srcDir.resolve("LegacyDataGeneratorSurface.java").readText()
        val legacyRecipeBase = srcDir.resolve("LegacyRecipeBase.java").readText()
        val legacyRecipeGenerator = srcDir.resolve("LegacyRecipeGenerator.java").readText()
        val legacyRecipeBuilder = srcDir.resolve("LegacyRecipeBuilder.java").readText()
        val legacyDatapackRegistrySurface = srcDir.resolve("LegacyDatapackRegistrySurface.java").readText()
        val legacyForgeBusBindingSurface = srcDir.resolve("LegacyForgeBusBindingSurface.java").readText()
        val legacyRarityMod = srcDir.resolve("LegacyRarityMod.java").readText()
        val enumExtensions = tempDir.resolve("src/main/resources/META-INF/enumextensions.json").readText()
        val enumHelper = srcDir.resolve("NeoForgeEnumExtensions.java").readText()
        val legacyDeferredRegisterMain = srcDir.resolve("LegacyDeferredRegisterMain.java").readText()
        val legacyBiomeSourceSurface = srcDir.resolve("LegacyBiomeSourceSurface.java").readText()
        val legacyTopElementSurface = srcDir.resolve("LegacyTopElementSurface.java").readText()
        val legacyDimensionEffectsSurface = srcDir.resolve("LegacyDimensionEffectsSurface.java").readText()
        val legacyLootGenerator = srcDir.resolve("LegacyLootGenerator.java").readText()
        val legacyLootDataGenerator = srcDir.resolve("LegacyLootDataGenerator.java").readText()
        val legacyGlobalLootModifierGenerator = srcDir.resolve("LegacyGlobalLootModifierGenerator.java").readText()
        val legacyGlobalLootModifierData = srcDir.resolve("LegacyGlobalLootModifierData.java").readText()
        val legacyPackMetadata = srcDir.resolve("LegacyPackMetadata.java").readText()
        val legacyLootTables = srcDir.resolve("LegacyLootTables.java").readText()
        val legacyEntityLootTables = srcDir.resolve("LegacyEntityLootTables.java").readText()
        val legacyHolderParameterSurface = srcDir.resolve("LegacyHolderParameterSurface.java").readText()
        val legacyMapData = srcDir.resolve("LegacyMapData.java").readText()
        val legacyMapPacket = srcDir.resolve("LegacyMapPacket.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(surfaces.contains("protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty)"))
        assertTrue(surfaces.contains("this.populateDefaultEquipmentSlots(accessor.getRandom(), difficulty);"))
        assertTrue(surfaces.contains("this.populateDefaultEquipmentEnchantments(accessor, accessor.getRandom(), difficulty);"))
        assertTrue(surfaces.contains("public float maxUpStep()"))
        assertTrue(surfaces.contains("type.getDimensions().makeBoundingBox(x, y, z)"))
        assertTrue(surfaces.contains("registry.get(Enchantments.SILK_TOUCH)"))
        assertTrue(surfaces.contains("registry.get(Enchantments.FORTUNE)"))
        assertTrue(surfaces.contains("registry.get(ExampleEnchantments.DESTRUCTION)"))
        assertTrue(surfaces.contains("state.getValue(ACTIVE) && snowOnState.getValue(LAYERS) > 0"), surfaces)
        assertTrue(surfaces.contains("return stack.has(net.minecraft.core.component.DataComponents.FOOD);"))
        assertTrue(surfaces.contains("EnchantmentHelper.hasTag(living.getItemBySlot(EquipmentSlot.FEET), EnchantmentTags.PREVENTS_ICE_MELTING)"))
        assertTrue(surfaces.contains("EnchantmentHelper.modifyDamage(serverlevel, this.getWeaponItem(), entity, damagesource, f)"))
        assertTrue(surfaces.contains("float f1 = this.getKnockback(entity, damagesource);"))
        assertTrue(surfaces.contains("EnchantmentHelper.doPostAttackEffects(serverlevel1, entity, damagesource);"))
        assertTrue(surfaces.contains("BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK"))
        assertTrue(surfaces.contains("entity.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, side)"))
        assertTrue(!surfaces.contains("getAABB("))
        assertTrue(!surfaces.contains("hasFrostWalker"))
        assertTrue(!surfaces.contains("getDamageBonus"))
        assertTrue(!surfaces.contains("getKnockbackBonus"))
        assertTrue(!surfaces.contains("getFireAspect"))
        assertTrue(!surfaces.contains("maybeDisableShield"))
        assertTrue(!surfaces.contains("doEnchantDamageEffects"))
        assertTrue(legacyEnchantmentComponentSurface.contains("ItemEnchantments.Mutable inputEnchantments = new ItemEnchantments.Mutable(input.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("inputEnchantments.removeIf(enchantment -> !result.supportsEnchantment(enchantment));"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("EnchantmentHelper.setEnchantments(result, inputEnchantments.toImmutable());"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("output.getTagEnchantments().size()"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getTagEnchantments().entrySet())"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("Enchantment ench = entry.getKey().value();"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("int level = entry.getIntValue();"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("switch (ench.getWeight())"), legacyEnchantmentComponentSurface)
        assertTrue(legacyEnchantmentComponentSurface.contains("EnchantmentHelper.modifyDamage(level, stack, living, source, 10)"), legacyEnchantmentComponentSurface)
        assertTrue(!legacyEnchantmentComponentSurface.contains("EnchantmentHelper.getEnchantments("), legacyEnchantmentComponentSurface)
        assertTrue(!legacyEnchantmentComponentSurface.contains("Map.Entry<Enchantment"), legacyEnchantmentComponentSurface)
        assertTrue(!legacyEnchantmentComponentSurface.contains("getRarity()"), legacyEnchantmentComponentSurface)
        assertTrue(!legacyEnchantmentComponentSurface.contains(".getMobType()"), legacyEnchantmentComponentSurface)
        assertTrue(legacySpawnEggLookupSurface.contains("SpawnEggItem spawnEggItem0 = SpawnEggItem.byId(input);"), legacySpawnEggLookupSurface)
        assertTrue(legacySpawnEggLookupSurface.contains("if (spawnEggItem0 != null)"), legacySpawnEggLookupSurface)
        assertTrue(legacySpawnEggLookupSurface.contains("builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStack(new ItemStack(spawnEggItem0));"), legacySpawnEggLookupSurface)
        assertTrue(legacySpawnEggLookupSurface.contains("SpawnEggItem spawnEggItem1 = SpawnEggItem.byId(output);"), legacySpawnEggLookupSurface)
        assertTrue(legacySpawnEggLookupSurface.contains("import net.minecraft.world.item.SpawnEggItem;"), legacySpawnEggLookupSurface)
        assertTrue(!legacySpawnEggLookupSurface.contains("DeferredSpawnEggItem"), legacySpawnEggLookupSurface)
        assertTrue(block.contains("super(properties.isValidSpawn((state, getter, pos, entityType) -> false));"))
        assertTrue(!block.contains("boolean isValidSpawn("))
        assertTrue(container.contains("public void setCustomName(net.minecraft.network.chat.Component name)"))
        assertTrue(container.contains("DataComponents.CUSTOM_NAME"))
        assertTrue(plainFurnace.contains("public void setCustomName(net.minecraft.network.chat.Component name)"))
        assertTrue(capability.contains("CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider provider)"))
        assertTrue(capability.contains("void deserializeNBT(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag)"))
        assertTrue(mushroom.contains("super(ModFeatures.BIG_MUSHROOM, properties);"))
        assertTrue(sign.contains("private final WoodType type;"))
        assertTrue(sign.contains("public com.mojang.serialization.MapCodec<WallSignBlock> codec()"), sign)
        assertTrue(sign.contains("forGetter(block -> ((LegacyWallSignBlock) block).getType())"), sign)
        assertTrue(paintingCodec.contains("Parallax.CODEC.codec().optionalFieldOf(\"parallax\")"))
        assertTrue(registrySurface.contains("Registry<LegacyRegistrySurface> REGISTRY = SURFACES.makeRegistry(builder -> {})"))
        assertTrue(!registrySurface.contains("Supplier<Registry<LegacyRegistrySurface>>"))
        assertTrue(!registrySurface.contains(".hasTags()"))
        assertTrue(registrySurface.contains("LegacyRegistrySurface.REGISTRY.stream().toArray(LegacyRegistrySurface[]::new)"))
        assertTrue(registrySurface.contains("(int) LegacyRegistrySurface.REGISTRY.stream().count()"))
        assertTrue(registrySurface.contains("(int) REGISTRY.stream().count()"))
        assertTrue(registrySurface.contains("REGISTRY.get(ResourceLocation.parse(id))"))
        assertTrue(clientRendering.contains("import com.mojang.blaze3d.vertex.BufferUploader;"))
        assertTrue(clientRendering.contains("stack.mulPose(RenderSystem.getModelViewMatrix());"))
        assertTrue(clientRendering.contains("minecraft.getTimer().getGameTimeDeltaPartialTick(false)"))
        assertTrue(clientRendering.contains("float frameTime = minecraft.getTimer().getGameTimeDeltaPartialTick(false);"))
        assertTrue(clientRendering.contains("double partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);"))
        assertTrue(clientRendering.contains("double partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);"))
        assertTrue(clientRendering.contains("public void fog(ViewportEvent.ComputeFogColor event)"))
        assertTrue(clientRendering.contains("double partialTick = event.getPartialTick();"))
        assertTrue(clientRendering.contains("bufferbuilder.addVertex((float) (x), (float) (y), (float) (z))"))
        assertTrue(clientRendering.contains(".setColor(1.0F, 1.0F, 1.0F, partialTick).setUv2(u, v)"))
        assertTrue(clientRendering.contains("BufferUploader.drawWithShader(bufferbuilder.buildOrThrow())"))
        assertTrue(packedColorModel.contains("float alpha = FastColor.ARGB32.alpha(color) / 255.0F;"))
        assertTrue(packedColorModel.contains("float scale = FastColor.ARGB32.alpha(color) / 255.0F;"))
        assertTrue(packedColorModel.contains("this.root.render(stack, consumer, light, overlay, FastColor.ARGB32.colorFromFloat(alpha, red, green, blue));"))
        assertTrue(shortPackedColorModel.contains("float a = FastColor.ARGB32.alpha(color) / 255.0F;"))
        assertTrue(shortPackedColorModel.contains("float r = FastColor.ARGB32.red(color) / 255.0F;"))
        assertTrue(shortPackedColorModel.contains("FastColor.ARGB32.colorFromFloat(0.6F, r, g, b)"))
        assertTrue(itemRendererSurface.contains("render(stack, ItemDisplayContext.GUI, false, poseStack, bufferSource, 15728880, OverlayTexture.NO_OVERLAY, model.applyTransform(context, poseStack, false))"))
        assertTrue(!itemRendererSurface.contains("FastColor.ARGB32.colorFromFloat(model.applyTransform"))
        assertTrue(!emptyUnsidedHandler.contains("createUnSidedHandler"))
        assertTrue(!emptyUnsidedHandler.contains("IItemHandler"))
        assertTrue(legacyHangingEntity.contains("protected AABB calculateBoundingBox(BlockPos pos, Direction direction)"))
        assertTrue(legacyHangingEntity.contains("double xSize = axis == Direction.Axis.X ? 0.0625D : this.getWidth() * scale;"))
        assertTrue(legacyHangingEntity.contains("public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps)"))
        assertTrue(!legacyHangingEntity.contains("boolean teleport"))
        assertTrue(spriteVertexSurface.contains("sprite.getU((float) (width * (double) 2))"))
        assertTrue(spriteVertexSurface.contains("sprite.getV((float) (1.0D))"))
        assertTrue(spriteVertexSurface.contains("vertex.addVertex(matrix4f, x, y, z).setColor(255, 255, 255, 255)"))
        assertTrue(guiLayerSurface.contains("import net.neoforged.neoforge.client.gui.VanillaGuiLayers;"))
        assertTrue(guiLayerSurface.contains("import net.minecraft.resources.ResourceLocation;"))
        assertTrue(guiLayerSurface.contains("event.registerAbove(VanillaGuiLayers.CROSSHAIR, ResourceLocation.fromNamespaceAndPath(ExampleMod.ID, \"indicator\"), (graphics, deltaTracker) -> {"))
        assertTrue(guiLayerSurface.contains("net.minecraft.client.gui.Gui gui = net.minecraft.client.Minecraft.getInstance().gui;"))
        assertTrue(guiLayerSurface.contains("float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);"))
        assertTrue(guiLayerSurface.contains("int screenWidth = graphics.guiWidth();"))
        assertTrue(guiLayerSurface.contains("int screenHeight = graphics.guiHeight();"))
        assertTrue(!guiLayerSurface.contains("VanillaGuiOverlay"))
        assertTrue(poseNormalSurface.contains("private static void emit(VertexConsumer vertex, Matrix4f matrix, PoseStack.Pose normal, int light)"), poseNormalSurface)
        assertTrue(poseNormalSurface.contains("emit(vertex, matrix, pose, 15);"))
        assertTrue(poseNormalSurface.contains("setNormal(pose, 0.0F, 1.0F, 0.0F)"))
        assertTrue(!poseNormalSurface.contains(".normal(pose.normal()"))
        assertTrue(!poseNormalSurface.contains("import org.joml.Matrix3f;"))
        assertTrue(collectionModelRenderSurface.contains("renderer.render(stack, builder, light, overlay, FastColor.ARGB32.colorFromFloat(scale, red, green, blue))"))
        assertTrue(collectionModelRenderSurface.contains("this.segments[0].render(stack, builder, light, overlay, FastColor.ARGB32.colorFromFloat(alpha, FastColor.ARGB32.red(dyeRgb) / 255.0F, FastColor.ARGB32.green(dyeRgb) / 255.0F, FastColor.ARGB32.blue(dyeRgb) / 255.0F))"))
        assertTrue(lossyCompoundSurface.contains("this.glowIntensity += (float) (0.05);"))
        assertTrue(lossyCompoundSurface.contains("this.rangle += (float) (rotation);"))
        assertTrue(lossyCompoundSurface.contains("this.damageTaken += (int) (amount);"))
        assertTrue(lossyCompoundSurface.contains("this.arm.xRot += (float) (Math.PI * 1.25);"))
        assertTrue(modelEventSurface.contains("event.register(ResourceLocation.fromNamespaceAndPath(ExampleMod.ID, \"example_loader\"), ExampleLoader.INSTANCE);"))
        assertTrue(modelEventSurface.contains("List<Map.Entry<ModelResourceLocation, BakedModel>> models"))
        assertTrue(modelEventSurface.contains("entry.getKey().id().getNamespace()"))
        assertTrue(modelEventSurface.contains("entry.getKey().id().getPath()"))
        assertTrue(modelEventSurface.contains("event.register(ModelResourceLocation.standalone(ExampleMod.prefix(\"block/surface\")))"))
        assertTrue(modelEventSurface.contains("event.register(ModelResourceLocation.standalone(ExampleMod.prefix(\"item/surface\")))"))
        assertTrue(tesselatorHelperSurface.contains("import com.mojang.blaze3d.vertex.BufferUploader;"))
        assertTrue(tesselatorHelperSurface.contains("public final void invokeThenEndTesselator(Runnable execBind, BufferBuilder builder)"))
        assertTrue(tesselatorHelperSurface.contains("public final void invokeThenEndTesselator(BufferBuilder builder)"))
        assertTrue(tesselatorHelperSurface.contains("public final void invokeThenEndTesselator(int seed, float x, float y, float z, BufferBuilder builder)"))
        assertTrue(tesselatorHelperSurface.contains("BufferUploader.drawWithShader(builder.buildOrThrow())"))
        assertTrue(tesselatorHelperSurface.contains("BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);"))
        assertTrue(tesselatorHelperSurface.contains("shader.invokeThenEndTesselator(1, x, y, z, buffer);"))
        assertTrue(tesselatorVariableSurface.contains("import com.mojang.blaze3d.vertex.BufferUploader;"))
        assertTrue(tesselatorVariableSurface.contains("BufferBuilder builder = null;"))
        assertTrue(tesselatorVariableSurface.contains("BufferUploader.drawWithShader(builder.buildOrThrow());"))
        assertTrue(tesselatorVariableSurface.contains("return builder.buildOrThrow();"))
        assertTrue(colorRecord.contains("RecordCodecBuilder.<ColorRecord>mapCodec"))
        assertTrue(colorRecord.contains("value.r()"))
        assertTrue(colorRecord.contains("value.g()"))
        assertTrue(colorRecord.contains("value.b()"))
        assertTrue(recordComponentConsumer.contains("return data.r() + data.g() + data.b();"))
        assertTrue(rendererSetupSurface.contains("protected void setupRotations(T entity, PoseStack stack, float ageInTicks, float rotationYaw, float scale, float partialTicks)"))
        assertTrue(rendererSetupSurface.contains("super.setupRotations(entity, stack, ageInTicks, rotationYaw, scale, partialTicks);"))
        assertTrue(commandSourceStackSurface.contains("source.getLevel().getSeed();"))
        assertTrue(itemStackSerializationSurface.contains("ItemStack.parseOptional(this.registryAccess(), tag.getCompound(\"Stack\"))"))
        assertTrue(itemStackSerializationSurface.contains("this.stack.save(this.registryAccess())"))
        assertTrue(meleeAttackGoalSurface.contains("protected void checkAndPerformAttack(LivingEntity target)"))
        assertTrue(meleeAttackGoalSurface.contains("super.checkAndPerformAttack(target);"))
        assertTrue(apiBridgeSurface.contains("ResourceLocation.CODEC.listOf()"))
        assertTrue(apiBridgeSurface.contains("modelView.mulPose(source.last().pose());"))
        assertTrue(apiBridgeSurface.contains("blockentity.loadWithComponents(tag, level.registryAccess());"))
        assertTrue(apiBridgeSurface.contains("Tags.Items.TOOLS_BOW"))
        assertTrue(apiBridgeSurface.contains("Tags.Items.TOOLS_CROSSBOW"))
        assertTrue(apiBridgeSurface.contains("Tags.Items.TOOLS_FISHING_ROD"))
        assertTrue(apiBridgeSurface.contains("CauldronInteraction.WATER.map().put(Items.LEATHER_HELMET, CauldronInteraction.DYED_ITEM);"))
        assertTrue(!apiBridgeSurface.contains("TOOLS_BOWS"))
        assertTrue(distExecutorSurface.contains("if (FMLLoader.getDist() == Dist.CLIENT)"))
        assertTrue(distExecutorSurface.contains("ClientHooks.init();"))
        assertTrue(!distExecutorSurface.contains("import net.neoforged.fml.DistExecutor;"))
        assertTrue(!distExecutorSurface.contains("DistExecutor.safeRunWhenOn"))
        assertTrue(craftingBoundarySurface.contains("public final CraftingContainer assemblyMatrix"))
        assertTrue(craftingBoundarySurface.contains("this.chooseRecipe(this.combineMatrix.asCraftInput());"))
        assertTrue(craftingBoundarySurface.contains("private static java.util.List<RecipeHolder<CraftingRecipe>> getRecipesFor(CraftingInput matrix, Level world)"))
        assertTrue(craftingBoundarySurface.contains("world.getRecipeManager().getRecipesFor(RecipeType.CRAFTING, matrix, world)"))
        assertTrue(!craftingBoundarySurface.contains("getRecipeManager().getRecipesFor(RecipeType.CRAFTING, matrix, world).toList()"))
        assertTrue(craftingBoundarySurface.contains("RecipeHolder<CraftingRecipe> recipe = recipes.get(Math.floorMod(this.recipeInCycle, recipes.size()))"))
        assertTrue(craftingBoundarySurface.contains("getRecipeBook().contains(recipe.id())"))
        assertTrue(craftingBoundarySurface.contains("this.result.setRecipeUsed(recipe);"))
        assertTrue(craftingBoundarySurface.contains("recipe.value().assemble(inventory, this.level.registryAccess())"))
        assertTrue(craftingBoundarySurface.contains("for (RecipeHolder<?> recipeHolder : world.getRecipeManager().getRecipes())"))
        assertTrue(craftingBoundarySurface.contains("Recipe<?> recipe = recipeHolder.value();"))
        assertTrue(craftingBoundarySurface.contains("ExampleConfig.disabledRecipes.contains(recipeHolder.id().toString())"))
        assertTrue(craftingBoundarySurface.contains("for (RecipeHolder<ExampleRecipe> exampleRecipeHolder : world.getRecipeManager().getAllRecipesFor(ExampleRecipes.EXAMPLE_TYPE.get()))"))
        assertTrue(craftingBoundarySurface.contains("ExampleRecipe exampleRecipe = exampleRecipeHolder.value();"))
        assertTrue(craftingBoundarySurface.contains("List<CraftingRecipe> recipes = manager.getAllRecipesFor(RecipeType.CRAFTING).stream()"))
        assertTrue(craftingBoundarySurface.contains("ExampleConfig.disabledRecipes.contains(holder.id().toString())"))
        assertTrue(craftingBoundarySurface.contains("recipes.addAll(manager.getAllRecipesFor(ExampleRecipes.EXAMPLE_TYPE.get()).stream().map(RecipeHolder::value).toList());"))
        assertTrue(!craftingBoundarySurface.contains("CraftingInput assemblyMatrix"))
        assertTrue(blockStateRegistryRecipe.contains("public record BlockStateRegistryRecipe(BlockState input, BlockState result) implements Recipe<RecipeInput>"))
        assertTrue(blockStateRegistryRecipe.contains("BuiltInRegistries.BLOCK.byNameCodec().fieldOf(\"from\").forGetter(recipe -> recipe.input().getBlock())"))
        assertTrue(blockStateRegistryRecipe.contains("BuiltInRegistries.BLOCK.byNameCodec().fieldOf(\"to\").forGetter(recipe -> recipe.result().getBlock())"))
        assertTrue(blockStateRegistryRecipe.contains("public MapCodec<BlockStateRegistryRecipe> codec()"))
        assertTrue(blockStateRegistryRecipe.contains("public StreamCodec<RegistryFriendlyByteBuf, BlockStateRegistryRecipe> streamCodec()"))
        assertTrue(blockStateRegistryRecipe.contains("new BlockStateRegistryRecipe(input.defaultBlockState(), output.defaultBlockState())"))
        assertTrue(!blockStateRegistryRecipe.contains("fromJson("))
        assertTrue(!blockStateRegistryRecipe.contains("readRegistryIdUnsafe"))
        assertTrue(!blockStateRegistryRecipe.contains("recipeID"))
        assertTrue(entityRegistryRecipe.contains("public record EntityRegistryRecipe(EntityType<?> input, EntityType<?> result, boolean isReversible) implements Recipe<RecipeInput>"))
        assertTrue(entityRegistryRecipe.contains("BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf(\"from\").forGetter(EntityRegistryRecipe::input)"))
        assertTrue(entityRegistryRecipe.contains("Codec.BOOL.fieldOf(\"reversible\").forGetter(EntityRegistryRecipe::isReversible)"))
        assertTrue(entityRegistryRecipe.contains("new EntityRegistryRecipe(input, output, reversible)"))
        assertTrue(!entityRegistryRecipe.contains("fromNetwork(ResourceLocation"))
        assertTrue(!entityRegistryRecipe.contains("writeRegistryIdUnsafe"))
        assertTrue(!entityRegistryRecipe.contains("recipeID"))
        assertTrue(legacyShapedRecordRecipe.contains("public class LegacyShapedRecordRecipe extends ShapedRecipe"))
        assertTrue(legacyShapedRecordRecipe.contains("new ShapedRecipePattern(width, height, resultItems, java.util.Optional.empty())"))
        assertTrue(legacyShapedRecordRecipe.contains("ShapedRecipePattern.MAP_CODEC.forGetter(recipe -> recipe.pattern)"))
        assertTrue(legacyShapedRecordRecipe.contains("Ingredient.CONTENTS_STREAM_CODEC.decode(buffer)"))
        assertTrue(legacyShapedRecordRecipe.contains("public int cost()"))
        assertTrue(!legacyShapedRecordRecipe.contains("public record LegacyShapedRecordRecipe"))
        assertTrue(!legacyShapedRecordRecipe.contains("fromJson(ResourceLocation"))
        assertTrue(!legacyShapedRecordRecipe.contains("recipeID"))
        assertTrue(renderArrayColorSurface.contains("FastColor.ARGB32.colorFromFloat(1.0F, this.necklaceColors[0], this.necklaceColors[1], this.necklaceColors[2])"))
        assertTrue(staticModBusSurface.contains("net.neoforged.fml.ModList.get().getModContainerById(ExampleMod.ID).orElseThrow().getEventBus()"))
        assertTrue(!staticModBusSurface.contains("FMLJavaModLoadingContext"))
        assertTrue(blockLootSurface.contains("public BlockLootSurface(HolderLookup.Provider registries)"))
        assertTrue(blockLootSurface.contains("super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);"))
        assertTrue(blockLootSurface.contains("this.registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE)"))
        assertTrue(blockLootSurface.contains("HAS_SHEARS.or(this.hasSilkTouch())"))
        assertTrue(blockLootSurface.contains("protected LootTable.Builder createSilkTouchOrShearsDispatchTable"))
        assertTrue(blockLootSurface.contains("protected LootTable.Builder createShearsDispatchTable"))
        assertTrue(blockLootSurface.contains("protected static LootTable.Builder createShearsOnlyDrop"))
        assertTrue(!blockLootSurface.contains("HAS_SILK_TOUCH"))
        assertTrue(legacySpriteSourceProviderSurface.contains("public LegacySpriteSourceProviderSurface(PackOutput output, CompletableFuture<HolderLookup.Provider> provider, ExistingFileHelper helper)"), legacySpriteSourceProviderSurface)
        assertTrue(legacySpriteSourceProviderSurface.contains("super(output, provider, ExampleMod.ID, helper);"), legacySpriteSourceProviderSurface)
        assertTrue(legacySpriteSourceProviderSurface.contains("protected void gather()"), legacySpriteSourceProviderSurface)
        assertTrue(!legacySpriteSourceProviderSurface.contains("addSources()"), legacySpriteSourceProviderSurface)
        assertTrue(legacyDataGeneratorSurface.contains("new LegacySpriteSourceProviderSurface(output, provider, helper)"), legacyDataGeneratorSurface)
        assertTrue(!legacyDataGeneratorSurface.contains("new LegacySpriteSourceProviderSurface(output, helper)"), legacyDataGeneratorSurface)
        assertTrue(legacyDataGeneratorSurface.contains("new LegacyRecipeGenerator(output, provider)"), legacyDataGeneratorSurface)
        assertTrue(legacyRecipeBase.contains("public LegacyRecipeBase(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider)"), legacyRecipeBase)
        assertTrue(legacyRecipeBase.contains("super(output, lookupProvider);"), legacyRecipeBase)
        assertTrue(legacyRecipeBase.contains("return item.getDefaultInstance().getMaxDamage();"), legacyRecipeBase)
        assertTrue(!legacyRecipeBase.contains("InventoryChangeTrigger.TriggerInstance has"), legacyRecipeBase)
        assertTrue(legacyRecipeGenerator.contains("public LegacyRecipeGenerator(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider)"), legacyRecipeGenerator)
        assertTrue(legacyRecipeGenerator.contains("super(output, lookupProvider);"), legacyRecipeGenerator)
        assertTrue(legacyRecipeGenerator.contains("protected void buildRecipes(RecipeOutput consumer)"), legacyRecipeGenerator)
        assertTrue(!legacyRecipeGenerator.contains("RecipeProvider.Runner"), legacyRecipeGenerator)
        assertTrue(legacyRecipeGenerator.contains("SpecialRecipeBuilder.special(com.example.LegacySpecialRecipe::new)"), legacyRecipeGenerator)
        assertTrue(legacyRecipeGenerator.contains("RecipeSerializer.SMELTING_RECIPE, SmeltingRecipe::new"), legacyRecipeGenerator)
        assertTrue(legacyRecipeGenerator.contains("RecipeSerializer.SMOKING_RECIPE, SmokingRecipe::new"), legacyRecipeGenerator)
        assertTrue(legacyRecipeGenerator.contains("private <T extends AbstractCookingRecipe> void cooking"), legacyRecipeGenerator)
        assertTrue(legacyRecipeGenerator.contains("RecipeSerializer<T> serializer, AbstractCookingRecipe.Factory<T> serializerFactory"), legacyRecipeGenerator)
        assertTrue(legacyRecipeBuilder.contains("RecipeBuilder unlockedBy(String name, Criterion<?> criterion)"), legacyRecipeBuilder)
        assertTrue(legacyRecipeBuilder.contains("void save(RecipeOutput output, ResourceLocation id)"), legacyRecipeBuilder)
        assertTrue(legacyRecipeBuilder.contains("Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, this.input).getOrThrow()"), legacyRecipeBuilder)
        assertTrue(legacyRecipeBuilder.contains("ShapedRecipePattern pattern = ShapedRecipePattern.of(this.outputs, this.pattern);"), legacyRecipeBuilder)
        assertTrue(legacyRecipeBuilder.contains("output.accept(id, new com.example.LegacyOutputRecipe(this.cost, this.input, this.count, pattern), null);"), legacyRecipeBuilder)
        assertTrue(legacyDatapackRegistrySurface.contains("event.dataPackRegistry(ExampleRegistries.MAP_CODEC_KEY, MapCodecOwner.CODEC.codec());"), legacyDatapackRegistrySurface)
        assertTrue(legacyDatapackRegistrySurface.contains("event.dataPackRegistry(ExampleRegistries.REGULAR_CODEC_KEY, RegularCodecOwner.CODEC);"), legacyDatapackRegistrySurface)
        assertTrue(legacyDatapackRegistrySurface.contains("event.dataPackRegistry(ExampleRegistries.SYNCED_MAP_CODEC_KEY, MapCodecOwner.CODEC.codec(), MapCodecOwner.CODEC.codec());"), legacyDatapackRegistrySurface)
        assertTrue(legacyForgeBusBindingSurface.contains("NeoForge.EVENT_BUS.addListener(ExampleEvents::listen);"), legacyForgeBusBindingSurface)
        assertTrue(!legacyForgeBusBindingSurface.contains("Bindings.getForgeBus()"), legacyForgeBusBindingSurface)
        assertTrue(legacyRarityMod.contains("Rarity.valueOf(\"EXAMPLEMOD_TWILIGHT\")"), legacyRarityMod)
        assertTrue(!legacyRarityMod.contains("Rarity.create("), legacyRarityMod)
        assertTrue(!legacyRarityMod.contains("ChatFormatting"), legacyRarityMod)
        assertTrue(enumExtensions.contains("\"name\": \"EXAMPLEMOD_TWILIGHT\""), enumExtensions)
        assertTrue(enumExtensions.contains("\"method\": \"Rarity_TWILIGHT\""), enumExtensions)
        assertTrue(enumHelper.contains("case 1 -> \"examplemod:twilight\";"), enumHelper)
        assertTrue(enumHelper.contains("style.withColor(ChatFormatting.DARK_GREEN)"), enumHelper)
        assertTrue(legacyDeferredRegisterMain.contains("com.example.LegacyDeferredRegisterOwner.CARVER_TYPES.register(modbus);"), legacyDeferredRegisterMain)
        assertTrue(!legacyDeferredRegisterMain.contains("::register"), legacyDeferredRegisterMain)
        assertTrue(legacyBiomeSourceSurface.contains("public static final MapCodec<LegacyBiomeSourceSurface> LEGACY_CODEC = RecordCodecBuilder.mapCodec("), legacyBiomeSourceSurface)
        assertTrue(legacyBiomeSourceSurface.contains("protected MapCodec<? extends BiomeSource> codec()"), legacyBiomeSourceSurface)
        assertTrue(legacyTopElementSurface.contains("public void toBytes(FriendlyByteBuf buf)"), legacyTopElementSurface)
        assertTrue(!legacyTopElementSurface.contains("public void write(FriendlyByteBuf buf)"), legacyTopElementSurface)
        assertTrue(legacyDimensionEffectsSurface.contains("Matrix4f modelViewMatrix, Camera camera"), legacyDimensionEffectsSurface)
        assertTrue(legacyDimensionEffectsSurface.contains("PoseStack poseStack = new PoseStack();"), legacyDimensionEffectsSurface)
        assertTrue(legacyDimensionEffectsSurface.contains("poseStack.mulPose(modelViewMatrix);"), legacyDimensionEffectsSurface)
        assertTrue(legacyLootGenerator.contains("public LegacyLootGenerator(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider)"), legacyLootGenerator)
        assertTrue(legacyLootGenerator.contains("super(output, Set.of(), List.of("), legacyLootGenerator)
        assertTrue(legacyLootGenerator.contains("), lookupProvider);"), legacyLootGenerator)
        assertTrue(legacyLootGenerator.contains("protected void validate(WritableRegistry<LootTable> writableregistry, ValidationContext validationContext, ProblemReporter.Collector problemreporter\$collector)"), legacyLootGenerator)
        assertTrue(legacyLootDataGenerator.contains("new LegacyLootGenerator(output, provider)"), legacyLootDataGenerator)
        assertTrue(legacyGlobalLootModifierGenerator.contains("public LegacyGlobalLootModifierGenerator(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider)"), legacyGlobalLootModifierGenerator)
        assertTrue(legacyGlobalLootModifierGenerator.contains("super(output, lookupProvider, \"example\");"), legacyGlobalLootModifierGenerator)
        assertTrue(legacyGlobalLootModifierData.contains("new LegacyGlobalLootModifierGenerator(output, provider)"), legacyGlobalLootModifierData)
        assertTrue(legacyPackMetadata.contains("java.util.Optional.of(new InclusiveRange<>(0, Integer.MAX_VALUE))"), legacyPackMetadata)
        assertTrue(!legacyPackMetadata.contains("Collectors.toMap"), legacyPackMetadata)
        assertTrue(legacyLootTables.contains("private final HolderLookup.Provider registries;"), legacyLootTables)
        assertTrue(legacyLootTables.contains("public LegacyLootTables(HolderLookup.Provider registries)"), legacyLootTables)
        assertTrue(legacyLootTables.contains("SetPotionFunction.setPotion(Potions.STRONG_REGENERATION)"), legacyLootTables)
        assertTrue(legacyLootTables.contains("SetContainerContents.setContents(ContainerComponentManipulators.BUNDLE_CONTENTS)"), legacyLootTables)
        assertTrue(legacyLootTables.contains("withEntry(LootItem.lootTableItem(Items.DIAMOND))"), legacyLootTables)
        assertTrue(legacyLootTables.contains("withEntry(LootItem.lootTableItem(Items.EMERALD).apply(SetItemCountFunction.setCount(ConstantValue.exactly(2))))"), legacyLootTables)
        assertTrue(legacyLootTables.contains("EnchantWithLevelsFunction.enchantWithLevels(this.registries, ConstantValue.exactly(20))"), legacyLootTables)
        assertTrue(legacyLootTables.contains("withEnchantment(this.registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), ConstantValue.exactly(1))"), legacyLootTables)
        assertTrue(legacyLootTables.contains("withEnchantment(this.registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(ExampleEnchantments.CUSTOM))"), legacyLootTables)
        assertTrue(!legacyLootTables.contains("SetNbtFunction"), legacyLootTables)
        assertTrue(!legacyLootTables.contains("ALL_DAMAGE_PROTECTION"), legacyLootTables)
        assertTrue(legacyEntityLootTables.contains("protected LegacyEntityLootTables(HolderLookup.Provider registries)"), legacyEntityLootTables)
        assertTrue(legacyEntityLootTables.contains("super(FeatureFlags.REGISTRY.allFlags(), registries);"), legacyEntityLootTables)
        assertTrue(legacyEntityLootTables.contains("EnchantedCountIncreaseFunction.lootingMultiplier(this.registries, UniformGenerator.between(0, 1))"), legacyEntityLootTables)
        assertTrue(legacyEntityLootTables.contains("SetNameFunction.setName(net.minecraft.network.chat.Component.literal(\"named\"), SetNameFunction.Target.CUSTOM_NAME)"), legacyEntityLootTables)
        assertTrue(legacyEntityLootTables.contains(".when(this.shouldSmeltLoot())"), legacyEntityLootTables)
        assertTrue(legacyEntityLootTables.contains("NestedLootTable.lootTableReference(EntityType.SKELETON.getDefaultLootTable())"), legacyEntityLootTables)
        assertTrue(legacyEntityLootTables.contains("NestedLootTable.lootTableReference(BuiltInLootTables.EMPTY)"), legacyEntityLootTables)
        assertTrue(!legacyEntityLootTables.contains("LootingEnchantFunction"), legacyEntityLootTables)
        assertTrue(!legacyEntityLootTables.contains("ENTITY_ON_FIRE"), legacyEntityLootTables)
        assertTrue(legacyHolderParameterSurface.contains("private void helper(DeferredHolder<Block, ExampleBlock> block)"), legacyHolderParameterSurface)
        assertTrue(legacyHolderParameterSurface.contains("private void armor(DeferredHolder<Item, ArmorItem> armor)"), legacyHolderParameterSurface)
        assertTrue(legacyMapData.contains("public static class LegacyMarker {"), legacyMapData)
        assertTrue(legacyMapData.contains("private final MapDecoration decoration;"), legacyMapData)
        assertTrue(legacyMapData.contains("this.decoration = new MapDecoration(MapDecorationTypes.TARGET_X, x, y, rotation, java.util.Optional.of(Component.literal(\"marker\")));"), legacyMapData)
        assertTrue(legacyMapData.contains("public boolean render(int index)"), legacyMapData)
        assertTrue(legacyMapData.contains("public MapDecoration asMapDecoration()"), legacyMapData)
        assertTrue(legacyMapData.contains("public byte getX()"), legacyMapData)
        assertTrue(!legacyMapData.contains("public int light()"), legacyMapData)
        assertTrue(!legacyMapData.contains("false()"), legacyMapData)
        assertTrue(!legacyMapData.contains("extends MapDecoration"), legacyMapData)
        assertTrue(legacyMapPacket.contains("data.decorations.put(marker.toString(), marker.asMapDecoration());"), legacyMapPacket)
        assertTrue(resolvableProfileSkullSurface.contains("import net.minecraft.client.resources.SkinManager;"))
        assertTrue(resolvableProfileSkullSurface.contains("public static RenderType getRenderType(SkullBlock.Type type, @Nullable ResolvableProfile profile)"))
        assertTrue(resolvableProfileSkullSurface.contains("SkinManager skinmanager = Minecraft.getInstance().getSkinManager();"))
        assertTrue(resolvableProfileSkullSurface.contains("RenderType.entityTranslucent(skinmanager.getInsecureSkin(profile.gameProfile()).texture())"))
        assertTrue(!resolvableProfileSkullSurface.contains("MinecraftProfileTexture"))
        assertTrue(!resolvableProfileSkullSurface.contains("UUIDUtil"))
        assertTrue(legacyProfileTextureSurface.contains("import net.minecraft.client.resources.PlayerSkin;"))
        assertTrue(legacyProfileTextureSurface.contains("PlayerSkin playerSkin = minecraft.getSkinManager().getInsecureSkin(profile);"))
        assertTrue(legacyProfileTextureSurface.contains("texture = playerSkin.texture();"))
        assertTrue(!legacyProfileTextureSurface.contains("getInsecureSkinInformation"))
        assertTrue(!legacyProfileTextureSurface.contains("MinecraftProfileTexture"))
        assertTrue(!legacyProfileTextureSurface.contains("UUIDUtil"))
        assertTrue(toast.contains("ResourceLocation.withDefaultNamespace(\"toast/advancement\")"))
        assertTrue(toast.contains("graphics.blitSprite(BACKGROUND_SPRITE, 0, 0, this.width(), this.height());"))
        assertTrue(animalFood.contains("public boolean isFood(ItemStack stack)"))
        assertTrue(animalFood.contains("return stack.is(Items.WHEAT);"))
        assertTrue(tamableFood.contains("public boolean isFood(ItemStack stack)"))
        assertTrue(tamableFood.contains("return stack.is(Items.ROTTEN_FLESH);"))
    }

    @Test
    fun `migrates legacy ITeleporter portal info flow to dimension transitions`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("DemoTeleporter.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraft.world.phys.Vec3;
            import net.neoforged.neoforge.common.util.ITeleporter;
            import java.util.function.Function;

            public class DemoTeleporter implements ITeleporter {
                private final boolean locked;

                public DemoTeleporter(boolean locked) {
                    this.locked = locked;
                }

                @Override
                public PortalInfo getPortalInfo(Entity entity, ServerLevel dest, Function<ServerLevel, PortalInfo> defaultPortalInfo) {
                    BlockPos destPos = entity.blockPosition();
                    PortalInfo pos = placeInExistingPortal(dest, entity, destPos);
                    return pos == null ? ITeleporter.super.getPortalInfo(entity, dest, defaultPortalInfo) : pos;
                }

                private static PortalInfo placeInExistingPortal(ServerLevel dest, Entity entity, BlockPos destPos) {
                    return null;
                }

                private static PortalInfo makePortalInfo(Entity entity, Vec3 pos) {
                    return new PortalInfo(pos, Vec3.ZERO, entity.getYRot(), entity.getXRot());
                }

                @Override
                public Entity placeEntity(Entity entity, ServerLevel currentWorld, ServerLevel destWorld, float yaw, Function<Boolean, Entity> repositionEntity) {
                    return repositionEntity.apply(false);
                }
            }
        """.trimIndent())
        srcDir.resolve("PortalCaller.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.Level;

            public class PortalCaller {
                public void send(Entity entity, ServerLevel serverWorld, boolean makeReturnPortal, boolean forcedEntry) {
                    if (entity.isPassenger() || entity.isVehicle() || !entity.canChangeDimensions()) {
                        return;
                    }

                    if (serverWorld == null)
                        return;

                    entity.changeDimension(serverWorld, makeReturnPortal ? new DemoTeleporter(forcedEntry) : new NoReturnTeleporter());
                }
            }
        """.trimIndent())
        srcDir.resolve("NoReturnTeleporter.java").writeText("""
            package com.example;

            public class NoReturnTeleporter extends DemoTeleporter {
                public NoReturnTeleporter() {
                    super(false);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val teleporter = srcDir.resolve("DemoTeleporter.java").readText()
        val caller = srcDir.resolve("PortalCaller.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(teleporter.contains("import net.minecraft.world.level.portal.DimensionTransition;"))
        assertTrue(!teleporter.contains("ITeleporter"))
        assertTrue(!teleporter.contains("import net.minecraft.world.level.portal.PortalInfo;"), teleporter)
        assertTrue(!teleporter.contains(" PortalInfo "), teleporter)
        assertTrue(!teleporter.contains("new PortalInfo("), teleporter)
        assertTrue(!teleporter.contains("placeEntity"))
        assertTrue(teleporter.contains("public DimensionTransition getPortalInfo(Entity entity, ServerLevel dest)"))
        assertTrue(teleporter.contains("new DimensionTransition(level, pos, Vec3.ZERO, entity.getYRot(), entity.getXRot(), DimensionTransition.PLACE_PORTAL_TICKET)"))
        assertTrue(caller.contains("entity.canChangeDimensions(entity.level(), serverWorld)"), caller)
        assertTrue(caller.contains("entity.changeDimension(makeReturnPortal ? new DemoTeleporter(forcedEntry).getPortalInfo(entity, serverWorld) : new NoReturnTeleporter().getPortalInfo(entity, serverWorld))"))
        assertTrue(!caller.contains("entity.canChangeDimensions())"))
        assertTrue(!caller.contains("changeDimension(serverWorld,"))
    }

    @Test
    fun `migrates ITeleporter portal info with project specific parameter names by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("NamedTeleporter.java").writeText("""
            package com.example;

            import net.minecraft.BlockUtil;
            import net.minecraft.core.Direction;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraft.world.level.portal.PortalShape;
            import net.minecraft.world.phys.Vec3;
            import net.neoforged.neoforge.common.util.ITeleporter;
            import java.util.Optional;
            import java.util.function.Function;

            public class NamedTeleporter implements ITeleporter {
                @Override
                public boolean playTeleportSound(ServerPlayer player, ServerLevel sourceLevel, ServerLevel destinationLevel) {
                    return false;
                }

                @Override
                public PortalInfo getPortalInfo(Entity entity, ServerLevel destinationLevel, Function<ServerLevel, PortalInfo> defaultPortalInfo) {
                    if (entity.isShiftKeyDown()) {
                        return new PortalInfo(new Vec3(entity.getX(), destinationLevel.getMaxBuildHeight(), entity.getZ()), Vec3.ZERO, entity.getYRot(), entity.getXRot());
                    }
                    Direction.Axis axis = Direction.Axis.X;
                    Vec3 vec3 = new Vec3(0.5, 0.0, 0.0);
                    Optional<BlockUtil.FoundRectangle> rectangle = Optional.empty();
                    return rectangle.map(found -> PortalShape.createPortalInfo(destinationLevel, found, axis, vec3, entity, entity.getDeltaMovement(), entity.getYRot(), entity.getXRot())).orElse(null);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val teleporter = srcDir.resolve("NamedTeleporter.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(!teleporter.contains("ITeleporter"), teleporter)
        assertTrue(!teleporter.contains("import net.minecraft.world.level.portal.PortalInfo"), teleporter)
        assertTrue(!teleporter.contains("new PortalInfo"), teleporter)
        assertTrue(!teleporter.contains("Function<ServerLevel, PortalInfo>"), teleporter)
        assertTrue(!teleporter.contains("destinationLevel, )"), teleporter)
        assertTrue(teleporter.contains("public boolean playTeleportSound(ServerPlayer player, ServerLevel sourceLevel, ServerLevel destinationLevel)"))
        assertTrue(teleporter.contains("public DimensionTransition getPortalInfo(Entity entity, ServerLevel destinationLevel)"))
        assertTrue(teleporter.contains("new DimensionTransition(destinationLevel, new Vec3(entity.getX(), destinationLevel.getMaxBuildHeight(), entity.getZ()), Vec3.ZERO, entity.getYRot(), entity.getXRot(), DimensionTransition.PLACE_PORTAL_TICKET)"), teleporter)
        assertTrue(teleporter.contains("PortalShape.findCollisionFreePosition(PortalShape.getRelativePosition(found, axis, vec3, entity.getDimensions(entity.getPose())), destinationLevel, entity, entity.getDimensions(entity.getPose()))"), teleporter)
    }

    @Test
    fun `migrates var getAllRecipesFor loops without invalid holder generic`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RecipeDisplaySurface.java").writeText("""
            package com.example;

            import java.util.List;

            public class RecipeDisplaySurface {
                public void registerDisplays(DisplayRegistry registry) {
                    for (var recipe : (List<?>) registry.getRecipeManager().getAllRecipesFor(ExampleRecipeTypes.ENCHANTING.get())) {
                        if (recipe instanceof EnchantingRecipe enchanting) {
                            use(enchanting);
                        }
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val surface = srcDir.resolve("RecipeDisplaySurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(surface.contains("import net.minecraft.world.item.crafting.RecipeHolder;"), surface)
        assertFalse(surface.contains("RecipeHolder<var>"), surface)
        assertTrue(surface.contains("for (Object recipeHolder : (List<?>) registry.getRecipeManager().getAllRecipesFor(ExampleRecipeTypes.ENCHANTING.get()))"), surface)
        assertTrue(surface.contains("var recipe = ((RecipeHolder<?>) recipeHolder).value();"), surface)
    }

    @Test
    fun `does not synthesize legacy ITeleporter default portal position without source evidence`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("UnclearTeleporter.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.level.portal.PortalInfo;
            import net.minecraft.world.phys.Vec3;
            import net.neoforged.neoforge.common.util.ITeleporter;
            import java.util.function.Function;

            public class UnclearTeleporter implements ITeleporter {
                @Override
                public PortalInfo getPortalInfo(Entity entity, ServerLevel dest, Function<ServerLevel, PortalInfo> defaultPortalInfo) {
                    PortalInfo pos = makePortalInfo(entity, Vec3.atCenterOf(entity.blockPosition()));
                    return pos == null ? ITeleporter.super.getPortalInfo(entity, dest, defaultPortalInfo) : pos;
                }

                private static PortalInfo makePortalInfo(Entity entity, Vec3 pos) {
                    return new PortalInfo(pos, Vec3.ZERO, entity.getYRot(), entity.getXRot());
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)
        val teleporter = srcDir.resolve("UnclearTeleporter.java").readText()

        assertTrue(teleporter.contains("implements ITeleporter"))
        assertTrue(teleporter.contains("ITeleporter.super.getPortalInfo(entity, dest, defaultPortalInfo)"))
        assertTrue(!teleporter.contains("DimensionTransition"))
        assertTrue(!teleporter.contains("Vec3.atCenterOf(entity.blockPosition())) : pos"))
    }

    @Test
    fun `migrates null legacy changeDimension ITeleporter override to dimension transition override`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("FloatingBlock.java").writeText("""
            package com.example;

            import javax.annotation.Nullable;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.Entity;
            import net.neoforged.neoforge.common.util.ITeleporter;

            public class FloatingBlock extends Entity {
                @Nullable
                @Override
                public Entity changeDimension(ServerLevel destination, ITeleporter teleporter) {
                    return null;
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)
        val floatingBlock = srcDir.resolve("FloatingBlock.java").readText()

        assertTrue(floatingBlock.contains("import net.minecraft.world.level.portal.DimensionTransition;"), floatingBlock)
        assertTrue(floatingBlock.contains("public Entity changeDimension(DimensionTransition transition)"), floatingBlock)
        assertTrue(floatingBlock.contains("return null;"), floatingBlock)
        assertTrue(!floatingBlock.contains("ITeleporter"), floatingBlock)
        assertTrue(!floatingBlock.contains("changeDimension(ServerLevel destination"), floatingBlock)
    }

    @Test
    fun `migrates NeoForge custom model API surfaces`() {
        val projectDir = createFile("CustomModelSurface.java", """
            package com.example;

            import java.util.function.Function;
            import net.minecraft.client.renderer.block.model.BakedQuad;
            import net.minecraft.client.renderer.block.model.BlockElement;
            import net.minecraft.client.renderer.block.model.BlockElementFace;
            import net.minecraft.client.renderer.block.model.BlockFaceUV;
            import net.minecraft.client.renderer.block.model.FaceBakery;
            import net.minecraft.client.renderer.block.model.ItemOverrides;
            import net.minecraft.client.renderer.texture.TextureAtlasSprite;
            import net.minecraft.client.resources.model.BakedModel;
            import net.minecraft.client.resources.model.Material;
            import net.minecraft.client.resources.model.ModelBaker;
            import net.minecraft.client.resources.model.ModelState;
            import com.mojang.blaze3d.vertex.PoseStack;
            import com.mojang.blaze3d.vertex.VertexConsumer;
            import net.minecraft.core.Direction;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.client.model.ExtraFaceData;
            import net.neoforged.neoforge.client.model.generators.CustomLoaderBuilder;
            import net.neoforged.neoforge.client.model.generators.ModelBuilder;
            import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
            import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
            import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
            import net.neoforged.neoforge.common.data.ExistingFileHelper;

            public class CustomModelSurface implements IUnbakedGeometry<CustomModelSurface> {
                private static final FaceBakery FACE_BAKERY = new FaceBakery();

                @Override
                public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides, ResourceLocation modelLocation) {
                    ResourceLocation copied = modelLocation;
                    BlockElement element = null;
                    BlockElementFace face = new BlockElementFace(null, 1, "#all", new BlockFaceUV(new float[]{0.0F, 0.0F, 16.0F, 16.0F}, 0), null);
                    TextureAtlasSprite sprite = spriteGetter.apply(context.getMaterial(face.texture));
                    BakedQuad baked = UnbakedGeometryHelper.bakeElementFace(element, face, sprite, Direction.NORTH, modelState, modelLocation);
                    BakedQuad direct = FACE_BAKERY.bakeQuad(element.from, element.to, face, sprite, Direction.NORTH, modelState, null, false, new ResourceLocation(sprite.atlasLocation().getNamespace(), sprite.atlasLocation().getPath()));
                    if (face.cullForDirection != null && face.tintIndex != -1 && face.uv.rotation != 0 && face.uv.uvs.length > 0 && face.getFaceData().equals(ExtraFaceData.DEFAULT)) {
                        return new DemoModel(copied, baked, direct);
                    }
                    return new DemoModel(modelLocation, baked, direct);
                }

                static class Builder<T extends ModelBuilder<T>> extends CustomLoaderBuilder<T> {
                    Builder(T parent, ExistingFileHelper helper) {
                        super(ResourceLocation.fromNamespaceAndPath("example", "surface"), parent, helper);
                    }
                }

                BlockElementFace withData(Direction cullface, int tintindex, String texture, BlockFaceUV uv) {
                    return new BlockElementFace(cullface, tintindex, texture, uv, new ExtraFaceData(0xFFFFFFFF, 15, 15, true));
                }

                void helperRender(ModelPartLike part, PoseStack stack, VertexConsumer consumer, int light, int overlay, float red, float green, float blue, float alpha) {
                    part.render(stack, consumer, light, overlay, color);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(projectDir)
        val transformed = tempDir.resolve("src/main/java/com/example/CustomModelSurface.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides)"))
        assertTrue(transformed.contains("ResourceLocation copied = net.minecraft.resources.ResourceLocation.parse(context.getModelName());"))
        assertTrue(transformed.contains("return new DemoModel(net.minecraft.resources.ResourceLocation.parse(context.getModelName()), baked, direct);"))
        assertTrue(transformed.contains("UnbakedGeometryHelper.bakeElementFace(element, face, sprite, Direction.NORTH, modelState)"))
        assertTrue(transformed.contains("FACE_BAKERY.bakeQuad(element.from, element.to, face, sprite, Direction.NORTH, modelState, null, false)"))
        assertTrue(transformed.contains("super(ResourceLocation.fromNamespaceAndPath(\"example\", \"surface\"), parent, helper, false);"))
        assertTrue(transformed.contains("new BlockElementFace(null, 1, \"#all\", new BlockFaceUV(new float[]{0.0F, 0.0F, 16.0F, 16.0F}, 0))"))
        assertTrue(transformed.contains("new BlockElementFace(cullface, tintindex, texture, uv, new ExtraFaceData(0xFFFFFFFF, 15, 15, true), new org.apache.commons.lang3.mutable.MutableObject<>())"))
        assertTrue(transformed.contains("context.getMaterial(face.texture())"))
        assertTrue(transformed.contains("face.cullForDirection() != null"))
        assertTrue(transformed.contains("face.tintIndex() != -1"))
        assertTrue(transformed.contains("face.uv().rotation != 0"))
        assertTrue(transformed.contains("face.uv().uvs.length > 0"))
        assertTrue(transformed.contains("face.faceData().equals(ExtraFaceData.DEFAULT)"))
        assertTrue(transformed.contains("part.render(stack, consumer, light, overlay, FastColor.ARGB32.colorFromFloat(alpha, red, green, blue));"))
        assertTrue(!transformed.contains("ResourceLocation modelLocation"))
        assertTrue(!transformed.contains("new ResourceLocation("))
    }

    @Test
    fun `migrates event bus post goal and dye color API surfaces`() {
        val projectDir = createFile("EventGoalColorSurface.java", """
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.TamableAnimal;
            import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
            import net.minecraft.world.entity.animal.Sheep;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.DyeColor;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.client.model.WolfModel;
            import net.minecraft.client.model.geom.builders.CubeDeformation;
            import net.minecraft.client.model.geom.builders.LayerDefinition;
            import net.neoforged.neoforge.client.event.EntityRenderersEvent;
            import net.neoforged.neoforge.common.NeoForge;
            import net.neoforged.neoforge.event.level.BlockEvent;

            public class EventGoalColorSurface extends TamableAnimal {
                public EventGoalColorSurface() {
                    this.goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.0D, 10.0F, 2.0F, true));
                }

                public void post(Level level, BlockPos pos, BlockState state, Player player) {
                    if (!NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player))) {
                        breakBlock();
                    }
                    if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player))) {
                        cancelBlock();
                    }
                }

                public void colors(DyeColor color, int i) {
                    float[] colorVal = color.getTextureDiffuseColors();
                    float red = colorVal[0];
                    float green = colorVal[1];
                    float blue = colorVal[2];

                    final float[] dyeRgb = Sheep.getColorArray(DyeColor.byId(i));
                    render(dyeRgb[0], dyeRgb[1], dyeRgb[2]);
                }

                @Override
                protected void dropExperience() {
                }

                @Override
                public int getExperienceReward() {
                    return super.getBaseExperienceReward();
                }

                public void layers(EntityRenderersEvent.RegisterLayerDefinitions event, Object layer) {
                    event.registerLayerDefinition(layer, WolfModel::createBodyLayer);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(projectDir)
        val transformed = tempDir.resolve("src/main/java/com/example/EventGoalColorSurface.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-vanilla-121-api" })
        assertTrue(transformed.contains("new FollowOwnerGoal(this, 1.0D, 10.0F, 2.0F)"))
        assertTrue(transformed.contains("!NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)).isCanceled()"))
        assertTrue(transformed.contains("NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)).isCanceled()"))
        assertTrue(transformed.contains("int colorVal = color.getTextureDiffuseColor();"))
        assertTrue(transformed.contains("float red = FastColor.ARGB32.red(colorVal) / 255.0F;"))
        assertTrue(transformed.contains("float green = FastColor.ARGB32.green(colorVal) / 255.0F;"))
        assertTrue(transformed.contains("float blue = FastColor.ARGB32.blue(colorVal) / 255.0F;"))
        assertTrue(transformed.contains("int dyeRgb = Sheep.getColor(DyeColor.byId(i));"))
        assertTrue(transformed.contains("render(FastColor.ARGB32.red(dyeRgb) / 255.0F, FastColor.ARGB32.green(dyeRgb) / 255.0F, FastColor.ARGB32.blue(dyeRgb) / 255.0F);"))
        assertTrue(transformed.contains("public boolean shouldDropExperience()"))
        assertTrue(transformed.contains("return false;"))
        assertTrue(transformed.contains("protected int getBaseExperienceReward()"))
        assertTrue(transformed.contains("event.registerLayerDefinition(layer, () -> LayerDefinition.create(WolfModel.createMeshDefinition(CubeDeformation.NONE), 64, 32));"))
        assertTrue(!transformed.contains("getTextureDiffuseColors()"))
        assertTrue(!transformed.contains("Sheep.getColorArray("))
        assertTrue(!transformed.contains("dropExperience()"))
        assertTrue(!transformed.contains("getExperienceReward()"))
    }

    @Test
    fun `migrates advancement holder lookups display access and packet fallback`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("PlayerHelper.java").writeText("""
            package com.example;

            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.AdvancementProgress;
            import net.minecraft.client.multiplayer.ClientAdvancements;
            import net.minecraft.client.player.LocalPlayer;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.player.Player;

            public class PlayerHelper {
                public static Advancement getAdvancement(Player player, ResourceLocation advancementLocation) {
                    if (player instanceof LocalPlayer localPlayer) {
                        ClientAdvancements manager = localPlayer.connection.getAdvancements();
                        return manager.getAdvancements().get(advancementLocation);
                    } else if (player instanceof ServerPlayer serverPlayer) {
                        return serverPlayer.getServer().getAdvancements().getAdvancement(advancementLocation);
                    }
                    return null;
                }

                public static boolean doesPlayerHaveRequiredAdvancement(Player player, Advancement advancement) {
                    if (player instanceof ServerPlayer serverPlayer) {
                        return advancement != null && serverPlayer.getAdvancements().getOrStartProgress(advancement).isDone();
                    }
                    return false;
                }
            }
        """.trimIndent())
        srcDir.resolve("PortalSurface.java").writeText("""
            package com.example;

            import net.minecraft.advancements.Advancement;
            import net.minecraft.advancements.DisplayInfo;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.network.PacketDistributor;

            public class PortalSurface {
                public void send(ServerPlayer player, ResourceLocation id) {
                    Advancement requirement = PlayerHelper.getAdvancement(player, id);
                    if (requirement != null && !PlayerHelper.doesPlayerHaveRequiredAdvancement(player, requirement)) {
                        DisplayInfo info = requirement.getDisplay();
                        PacketDistributor.sendToPlayer(player, info == null ? MissingAdvancementToast.FALLBACK : new MissingAdvancementToastPacket(info.getTitle(), info.getIcon()));
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val helper = srcDir.resolve("PlayerHelper.java").readText()
        val portal = srcDir.resolve("PortalSurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(helper.contains("import net.minecraft.advancements.AdvancementHolder;"))
        assertTrue(helper.contains("public static AdvancementHolder getAdvancement(Player player, ResourceLocation advancementLocation)"))
        assertTrue(helper.contains("return manager.get(advancementLocation);"))
        assertTrue(helper.contains("return serverPlayer.getServer().getAdvancements().get(advancementLocation);"))
        assertTrue(helper.contains("doesPlayerHaveRequiredAdvancement(Player player, AdvancementHolder advancement)"))
        assertTrue(helper.contains("serverPlayer.getAdvancements().getOrStartProgress(advancement).isDone()"))
        assertTrue(portal.contains("AdvancementHolder requirement = PlayerHelper.getAdvancement(player, id);"))
        assertTrue(portal.contains("DisplayInfo info = requirement.value().display().orElse(null);"))
        assertTrue(portal.contains("info == null ? new MissingAdvancementToastPacket(MissingAdvancementToast.FALLBACK.title(), MissingAdvancementToast.FALLBACK.icon()) : new MissingAdvancementToastPacket(info.getTitle(), info.getIcon())"))
        assertTrue(!portal.contains("requirement.getDisplay()"))
    }

    @Test
    fun `migrates legacy projectile dispenser chest boat and projectile damage APIs by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("DispenserSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.Position;
            import net.minecraft.core.dispenser.BlockSource;
            import net.minecraft.core.dispenser.ProjectileDispenseBehavior;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundSource;
            import net.minecraft.world.entity.projectile.Projectile;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.DispenserBlock;

            public class DispenserSurface {
                private boolean success;

                protected ItemStack execute(BlockSource source, ItemStack stack) {
                    ServerLevel level = source.level();
                    stack.hurt(1, level.getRandom(), null);
                    if (this.isSuccess() && stack.hurt(1, level.getRandom(), null)) {
                        stack.setCount(0);
                    }
                    source.level().playSound(null, source.x(), source.y(), source.z(), sound(), SoundSource.NEUTRAL, 1.0F, 1.0F);
                    Object behavior = new ProjectileDispenseBehavior() {
                        @Override
                        protected Projectile getProjectile(Level level, Position pos, ItemStack stack) {
                            return new DemoProjectile(level, pos);
                        }
                    };
                    return stack;
                }

                private boolean isSuccess() {
                    return success;
                }

                private SoundEvent sound() {
                    return null;
                }
            }
        """.trimIndent())
        srcDir.resolve("ArrowSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.projectile.AbstractArrow;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.level.Level;

            public abstract class ArrowSurface extends AbstractArrow {
                protected ArrowSurface(EntityType<? extends AbstractArrow> type, Level level) {
                    super(type, level);
                }

                @Override
                protected ItemStack getPickupItem() {
                    return new ItemStack(Items.ARROW);
                }
            }
        """.trimIndent())
        srcDir.resolve("ChestBoatSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.NonNullList;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.vehicle.ContainerEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootTable;
            import org.jetbrains.annotations.Nullable;

            public abstract class ChestBoatSurface implements ContainerEntity {
                @Nullable
                private ResourceLocation lootTable;

                protected void addAdditionalSaveData(CompoundTag tag) {
                    this.addChestVehicleSaveData(tag);
                }

                protected void readAdditionalSaveData(CompoundTag tag) {
                    this.readChestVehicleSaveData(tag);
                }

                @Nullable
                @Override
                public net.minecraft.resources.ResourceKey<LootTable> getLootTable() {
                    return this.lootTable;
                }

                @Override
                public void setLootTable(@Nullable ResourceLocation lootTable) {
                    this.lootTable = lootTable;
                }
            }
        """.trimIndent())
        srcDir.resolve("ProjectileSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.damagesource.DamageSource;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.projectile.LargeFireball;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.phys.EntityHitResult;

            public class ProjectileSurface extends LargeFireball {
                public ProjectileSurface(Level level, LivingEntity owner, double x, double y, double z, int power) {
                    super(level, owner, x, y, z, power);
                }

                protected void hit(EntityHitResult result) {
                    result.getEntity().hurt(this.damageSources().fireball(this, this.getOwner()), 16.0F);
                    this.doEnchantDamageEffects((LivingEntity) this.getOwner(), result.getEntity());
                }
            }
        """.trimIndent())
        srcDir.resolve("TrailSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.particles.ParticleOptions;
            import net.minecraft.core.particles.ParticleTypes;

            public class TrailSurface {
                public void tick(double r, double g, double b) {
                    this.makeTrail(ParticleTypes.ENTITY_EFFECT, r, g, b, 5);
                }

                private void makeTrail(ParticleOptions option, int amount) {
                }
            }
        """.trimIndent())
        srcDir.resolve("HurtingProjectileSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
            import net.minecraft.world.phys.Vec3;

            public class HurtingProjectileSurface {
                public void rebound(AbstractHurtingProjectile projectile, Vec3 rebound) {
                    projectile.xPower = rebound.x() * 0.1D;
                    projectile.yPower = rebound.y() * 0.1D;
                    projectile.zPower = rebound.z() * 0.1D;
                }
            }
        """.trimIndent())
        srcDir.resolve("EventSurface.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.bus.api.Event;
            import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

            public class EventSurface {
                @SubscribeEvent
                public void route(PlayerInteractEvent event) {
                    if (event instanceof PlayerInteractEvent.RightClickBlock rightClickBlock) {
                        checkTooFar(event, new Object());
                    }
                }

                public void rightClick(PlayerInteractEvent.RightClickBlock event) {
                    event.setUseBlock(Event.Result.DENY);
                }

                private static void checkTooFar(PlayerInteractEvent event, Object target) {
                    if (!event.isCanceled()) {
                        event.setCanceled(true);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("SpawnEggSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.SpawnEggItem;

            public class SpawnEggSurface {
                public boolean isType(ItemStack stack, SpawnEggItem spawnEggItem, Object expected) {
                    return spawnEggItem.getType(stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()) == expected;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val dispenser = srcDir.resolve("DispenserSurface.java").readText()
        val arrow = srcDir.resolve("ArrowSurface.java").readText()
        val chestBoat = srcDir.resolve("ChestBoatSurface.java").readText()
        val projectile = srcDir.resolve("ProjectileSurface.java").readText()
        val trail = srcDir.resolve("TrailSurface.java").readText()
        val hurtingProjectile = srcDir.resolve("HurtingProjectileSurface.java").readText()
        val event = srcDir.resolve("EventSurface.java").readText()
        val spawnEgg = srcDir.resolve("SpawnEggSurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(dispenser.contains("stack.hurtAndBreak(1, level, null, item -> {})"))
        assertTrue(dispenser.contains("if (this.isSuccess())"))
        assertTrue(dispenser.contains("stack.hurtAndBreak(1, level, null, item -> {"))
        assertTrue(dispenser.contains("source.center().x()"))
        assertTrue(dispenser.contains("import net.minecraft.core.Direction;"))
        assertTrue(dispenser.contains("new DefaultDispenseItemBehavior()"))
        assertTrue(dispenser.contains("Projectile projectile = new DemoProjectile(level, pos);"))
        assertTrue(dispenser.contains("projectile.shoot(direction.getStepX(), direction.getStepY(), direction.getStepZ(), 1.1F, 6.0F);"))
        assertTrue(!dispenser.contains("ProjectileDispenseBehavior"))
        assertTrue(arrow.contains("protected ItemStack getDefaultPickupItem()"))
        assertTrue(chestBoat.contains("private ResourceKey<LootTable> lootTable;"))
        assertTrue(chestBoat.contains("addChestVehicleSaveData(tag, this.registryAccess())"))
        assertTrue(chestBoat.contains("readChestVehicleSaveData(tag, this.registryAccess())"))
        assertTrue(chestBoat.contains("setLootTable(@Nullable ResourceKey<LootTable> lootTable)"))
        assertTrue(projectile.contains("super(level, owner, new Vec3(x, y, z), power);"))
        assertTrue(projectile.contains("DamageSource postAttackDamageSource = this.damageSources().fireball(this, this.getOwner());"))
        assertTrue(projectile.contains("EnchantmentHelper.doPostAttackEffects(serverLevel, result.getEntity(), postAttackDamageSource);"))
        assertTrue(trail.contains("ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, (float) r, (float) g, (float) b), 5"))
        assertTrue(hurtingProjectile.contains("projectile.accelerationPower = 0.1D;"))
        assertTrue(!hurtingProjectile.contains("xPower"))
        assertTrue(event.contains("event.setUseBlock(TriState.FALSE);"))
        assertTrue(event.contains("private static void checkTooFar(ICancellableEvent event, Object target)"))
        assertTrue(event.contains("public void route(PlayerInteractEvent.RightClickBlock rightClickBlock)"))
        assertTrue(event.contains("checkTooFar(rightClickBlock, new Object())"))
        assertTrue(!event.contains("void route(PlayerInteractEvent event)"), event)
        assertTrue(spawnEgg.contains("spawnEggItem.getType(stack) == expected"))
    }

    @Test
    fun `migrates abstract render living subscribers with pre render model mutations`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClientEvents.java").writeText("""
            package com.example;

            import net.minecraft.client.model.HeadedModel;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.RenderLivingEvent;

            public class ClientEvents {
                @SubscribeEvent
                public static void hideHead(RenderLivingEvent<?, ?> event) {
                    if (event.getRenderer().getModel() instanceof HeadedModel headedModel) {
                        headedModel.getHead().visible = false;
                    }
                }

                @SubscribeEvent
                public static void inspect(RenderLivingEvent<?, ?> event) {
                    event.getEntity().getName();
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)
        val events = srcDir.resolve("ClientEvents.java").readText()

        assertTrue(events.contains("hideHead(RenderLivingEvent.Pre<?, ?> event)"))
        assertTrue(events.contains("inspect(RenderLivingEvent<?, ?> event)"))
    }

    @Test
    fun `migrates drop experience block constructor order for subclasses and call sites`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RichOreBlock.java").writeText("""
            package com.example;

            import net.minecraft.util.valueproviders.UniformInt;
            import net.minecraft.world.level.block.DropExperienceBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class RichOreBlock extends DropExperienceBlock {
                public RichOreBlock(BlockBehaviour.Properties properties, UniformInt xpRange) {
                    super(properties, xpRange);
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleBlocks.java").writeText("""
            package com.example;

            import net.minecraft.util.valueproviders.UniformInt;
            import net.minecraft.world.level.block.DropExperienceBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class ExampleBlocks {
                public Object rich() {
                    return new RichOreBlock(BlockBehaviour.Properties.of().strength(3.0F), UniformInt.of(0, 2));
                }

                public Object vanilla() {
                    return new DropExperienceBlock(BlockBehaviour.Properties.of().strength(3.0F), UniformInt.of(3, 5));
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val block = srcDir.resolve("RichOreBlock.java").readText()
        val registry = srcDir.resolve("ExampleBlocks.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-drop-experience-constructor-order" })
        assertTrue(block.contains("public RichOreBlock(UniformInt xpRange, BlockBehaviour.Properties properties)"), block)
        assertTrue(block.contains("super(xpRange, properties);"), block)
        assertTrue(registry.contains("new RichOreBlock(UniformInt.of(0, 2), BlockBehaviour.Properties.of().strength(3.0F))"), registry)
        assertTrue(registry.contains("new DropExperienceBlock(UniformInt.of(3, 5), BlockBehaviour.Properties.of().strength(3.0F))"), registry)
        assertFalse(registry.contains("new RichOreBlock(BlockBehaviour.Properties"), registry)
        assertFalse(registry.contains("new DropExperienceBlock(BlockBehaviour.Properties"), registry)
    }

    @Test
    fun `migrates legacy vanilla block registry codec banner and near packet APIs by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("BlockRegistrySurface.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.ButtonBlock;
            import net.minecraft.world.level.block.DoorBlock;
            import net.minecraft.world.level.block.FenceGateBlock;
            import net.minecraft.world.level.block.PressurePlateBlock;
            import net.minecraft.world.level.block.StairBlock;
            import net.minecraft.world.level.block.TorchBlock;
            import net.minecraft.world.level.block.TrapDoorBlock;
            import net.minecraft.world.level.block.WallTorchBlock;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.minecraft.core.particles.ParticleTypes;

            public class BlockRegistrySurface {
                public Object stair() {
                    return new StairBlock(() -> Blocks.STONE.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(Blocks.STONE.get()));
                }

                public Object button() {
                    return new ButtonBlock(BlockBehaviour.Properties.of().noCollission(), ModBlockSets.WOOD_SET, 30, true);
                }

                public Object gate() {
                    return new FenceGateBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.PLANKS.get()), ModWoodTypes.WOOD);
                }

                public Object plate() {
                    return new PressurePlateBlock(PressurePlateBlock.Sensitivity.EVERYTHING, BlockBehaviour.Properties.of().strength(0.5F), ModBlockSets.WOOD_SET);
                }

                public Object door() {
                    return new DoorBlock(BlockBehaviour.Properties.of().noOcclusion(), ModBlockSets.WOOD_SET);
                }

                public Object trapdoor() {
                    return new TrapDoorBlock(BlockBehaviour.Properties.of().noOcclusion(), ModBlockSets.WOOD_SET);
                }

                public Object torch() {
                    return new TorchBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.TORCH), ParticleTypes.SMOKE);
                }

                public Object wallTorch() {
                    return new WallTorchBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WALL_TORCH), ModParticleTypes.SPARKLE.get());
                }
            }
        """.trimIndent())
        srcDir.resolve("BannerSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.block.entity.BannerPattern;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class BannerSurface {
                public static final DeferredRegister<BannerPattern> BANNER_PATTERNS = DeferredRegister.create(Registries.BANNER_PATTERN, ExampleMod.ID);
                public static final Object EXAMPLE = BANNER_PATTERNS.register("example", () -> new BannerPattern("ex"));
            }
        """.trimIndent())
        srcDir.resolve("MagicPaintingVariant.java").writeText("""
            package com.example;

            import com.mojang.serialization.MapCodec;

            public record MagicPaintingVariant(String id) {
                public static final MapCodec<MagicPaintingVariant> CODEC = null;
            }
        """.trimIndent())
        srcDir.resolve("RegistrySurface.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import net.minecraft.core.Holder;
            import net.minecraft.core.Registry;
            import net.minecraft.resources.RegistryFileCodec;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.util.ExtraCodecs;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class RegistrySurface {
                public static final ResourceKey<Registry<BiomeLayerType>> BIOME_LAYER_TYPE_KEY = null;
                public static final DeferredRegister<BiomeLayerType> BIOME_LAYER_TYPES = DeferredRegister.create(BIOME_LAYER_TYPE_KEY, ExampleMod.ID);
                public static final Registry<BiomeLayerType> REGISTRY = BIOME_LAYER_TYPES.makeRegistry(builder -> builder.allowModification().disableSync());
                public static final Codec<BiomeLayerType> CODEC = ExtraCodecs.lazyInitializedCodec(() -> REGISTRY.getCodec());
                public static final ResourceKey<Registry<MagicPaintingVariant>> MAGIC_KEY = null;
                public static final Codec<Holder<MagicPaintingVariant>> HOLDER_CODEC = RegistryFileCodec.create(MAGIC_KEY, MagicPaintingVariant.CODEC, false);
            }
        """.trimIndent())
        srcDir.resolve("BiomeLayerType.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;

            @FunctionalInterface
            public interface BiomeLayerType {
                Codec<? extends BiomeLayerFactory> getCodec();
            }
        """.trimIndent())
        srcDir.resolve("NetworkSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.network.PacketDistributor;

            public class NetworkSurface {
                private static void send(Level level, BlockPos pos, DemoPacket packet) {
                    PacketDistributor.TargetPoint targetPoint = new PacketDistributor.TargetPoint(pos.getX(), pos.getY(), pos.getZ(), 64, level.dimension());
                    NetworkHandler.CHANNEL.send(PacketDistributor.NEAR.with(() -> targetPoint), packet);
                }
            }
        """.trimIndent())
        srcDir.resolve("BlockHelperSurface.java").writeText("""
            package com.example;

            import java.util.function.Supplier;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.StairBlock;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class BlockHelperSurface {
                public static final DeferredRegister<Block> BLOCKS = null;
                public static final DeferredHolder<CustomBlock, CustomBlock> CUSTOM = register("custom", () -> new CustomBlock());
                public static final DeferredHolder<StairBlock, StairBlock> STAIRS = register("stairs", () -> new StairBlock(null, null));

                public static <T extends Block> DeferredHolder<T, T> register(String name, Supplier<Block> block) {
                    DeferredHolder<Block, ? extends Block> ret = BLOCKS.register(name, block);
                    return (DeferredHolder<T, T>) ret;
                }
            }

            class CustomBlock extends Block {
                CustomBlock() {
                    super(null);
                }
            }
        """.trimIndent())
        srcDir.resolve("FeatureSurface.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import java.util.List;
            import net.minecraft.world.level.levelgen.feature.Feature;
            import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
            import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
            import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;

            class ConfigSurface implements FeatureConfiguration {
                public static final Codec<ConfigSurface> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        TreeDecorator.CODEC.codec().listOf().fieldOf("decorators").forGetter(obj -> obj.decorators),
                        ProcessorRule.CODEC.codec().listOf().fieldOf("rules").forGetter(obj -> obj.rules)
                ).apply(instance, ConfigSurface::new));
                final List<TreeDecorator> decorators;
                final List<ProcessorRule> rules;

                ConfigSurface(List<TreeDecorator> decorators, List<ProcessorRule> rules) {
                    this.decorators = decorators;
                    this.rules = rules;
                }
            }

            record MapConfig(int value) implements FeatureConfiguration {
                public static final MapCodec<MapConfig> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Codec.INT.fieldOf("value").forGetter(MapConfig::value)
                ).apply(instance, MapConfig::new));
            }

            class MapConfigFeature extends Feature<MapConfig> {
                MapConfigFeature(Codec<MapConfig> codec) {
                    super(codec);
                }
            }

            public class FeatureSurface {
                Object value = new MapConfigFeature(MapConfig.CODEC);
            }
        """.trimIndent())
        srcDir.resolve("CreativeSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.item.CreativeModeTab;
            import net.minecraft.world.item.EnchantedBookItem;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentInstance;
            import net.minecraft.world.item.enchantment.Enchantments;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class CreativeSurface {
                public static CreativeModeTab tab() {
                    return CreativeModeTab.builder()
                            .displayItems((parameters, output) -> {
                                output.accept(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.ALL_DAMAGE_PROTECTION, 1)));
                                custom(output, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
                            })
                            .build();
                }

                private static void custom(CreativeModeTab.Output output, CreativeModeTab.TabVisibility visibility) {
                    for (DeferredHolder<Enchantment, ? extends Enchantment> enchantment : CustomEnchantments.ENCHANTMENTS.getEntries()) {
                        output.accept(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(enchantment.get(), enchantment.get().getMaxLevel())), visibility);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("MiscSurface.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.Registry;
            import net.minecraft.network.chat.Component;
            import net.minecraft.sounds.Music;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.Mob;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.util.ExtraCodecs;
            import net.minecraft.util.random.WeightedEntry;
            import net.neoforged.neoforge.common.CommonHooks;
            import net.neoforged.neoforge.registries.RegistryObject;

            public class MiscSurface {
                public static final Registry<BiomeLayerType> REGISTRY = null;
                public static final Codec<BiomeLayerType> CODEC = ExtraCodecs.lazyInitializedCodec(() -> REGISTRY.byNameCodec());

                public Music music() {
                    return new Music(Sounds.MUSIC.getHolder().orElseThrow(), 20, 40, true);
                }

                public boolean tool(BlockState state, Player player) {
                    return CommonHooks.isCorrectToolForDrops(state, player);
                }

                public Object refs(java.util.stream.Stream<RegistryObject<String>> stream) {
                    return stream.map(RegistryObject::get);
                }

                public Object data(WeightedEntry.Wrapper<Object> wrapper) {
                    return wrapper.getData();
                }

                public Object builtinCodec() {
                    return BuiltInRegistries.ENTITY_TYPE.getCodec().comapFlatMap(MiscSurface::mob, entityType -> entityType);
                }

                public String textJson() {
                    return Component.Serializer.toJson(Component.literal("ok"));
                }

                private static com.mojang.serialization.DataResult<EntityType<? extends Mob>> mob(EntityType<?> type) {
                    return com.mojang.serialization.DataResult.success((EntityType<? extends Mob>) type);
                }
            }
        """.trimIndent())
        srcDir.resolve("MapCodecRequiredSurface.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import java.util.List;
            import net.minecraft.world.level.levelgen.structure.BoundingBox;
            import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
            import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

            public class MapCodecRequiredSurface extends StructureProcessor {
                public static final Codec<MapCodecRequiredSurface> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.FLOAT.fieldOf("integrity").forGetter(o -> o.integrity)
                ).apply(instance, MapCodecRequiredSurface::new));
                public static final Codec<MapCodecRequiredSurface> LIST_CODEC = BoundingBox.CODEC.codec().listOf().xmap(MapCodecRequiredSurface::new, p -> p.cutouts);
                public static final Codec<MapCodecRequiredSurface> FIELD_CODEC = BoundingBox.CODEC.codec().listOf().fieldOf("cutouts").xmap(MapCodecRequiredSurface::new, p -> p.cutouts).codec();
                final float integrity;
                final List<BoundingBox> cutouts;

                MapCodecRequiredSurface(float integrity) {
                    this.integrity = integrity;
                    this.cutouts = List.of();
                }

                MapCodecRequiredSurface(List<BoundingBox> cutouts) {
                    this.integrity = 1.0F;
                    this.cutouts = cutouts;
                }

                protected StructureProcessorType<?> getType() {
                    return null;
                }

                public static class NestedProcessor extends StructureProcessor {
                    public static final NestedProcessor INSTANCE = new NestedProcessor();
                    public static final Codec<NestedProcessor> CODEC = Codec.unit(() -> INSTANCE);

                    protected StructureProcessorType<?> getType() {
                        return null;
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("HolderAndItemSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Holder;
            import net.minecraft.world.item.BowlFoodItem;
            import net.minecraft.world.item.FoodProperties;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.SwordItem;
            import net.minecraft.world.item.Tier;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.neoforged.neoforge.common.NeoForgeMod;

            public class HolderAndItemSurface {
                private final Holder<Widget> widget = null;
                private final java.util.HashMap<Block, Block> replacements = new java.util.HashMap<>();

                public Object holder(Level level, BlockPos pos, RegistryThing thing) {
                    this.widget.get().run();
                    return level.getBiome(pos).get();
                }

                public void blocks(Holder<Block> ore, Holder<Block> ground) {
                    ore.defaultBlockState();
                    replacements.put(ore, ground);
                }

                public void inferred(TagKey<Block> tag) {
                    BuiltInRegistries.BLOCK.getOrCreateTag(tag).forEach(ore -> {
                        if (!ore.defaultBlockState().is(tag)) {
                            replacements.put(ore, ore);
                        }
                    });
                }

                public Object deferred(RegistryThing thing) {
                    return thing.VALUE.getHolder().get();
                }

                public Object vanillaFluidTypes() {
                    return java.util.List.of(NeoForgeMod.EMPTY_TYPE.get(), NeoForgeMod.WATER_TYPE.get(), NeoForgeMod.LAVA_TYPE.get(), NeoForgeMod.MILK_TYPE.get());
                }

                public Object sword(Tier tier) {
                    return new SwordItem(tier, 3, -2.4F, new Item.Properties());
                }

                public Object food() {
                    return new BowlFoodItem(new Item.Properties().food(new FoodProperties.Builder().nutrition(1).meat().build()));
                }
            }
        """.trimIndent())
        srcDir.resolve("RecipeAndMenuSurface.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.MenuScreens;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.CustomRecipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.level.Level;

            public class RecipeAndMenuSurface extends CustomRecipe {
                public RecipeAndMenuSurface(CraftingBookCategory category) {
                    super(id, category);
                }

                public boolean matches(CraftingContainer container, Level level) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        container.getItem(i);
                    }
                    return true;
                }

                public ItemStack assemble(CraftingContainer container, HolderLookup.Provider access) {
                    return ItemStack.EMPTY;
                }

                public RecipeSerializer<?> getSerializer() {
                    return null;
                }

                public static void renderScreens() {
                    MenuScreens.register(ModMenus.EXAMPLE.get(), ExampleScreen::new);
                }
            }
        """.trimIndent())
        srcDir.resolve("RecipeBoundarySurface.java").writeText("""
            package com.example;

            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.core.NonNullList;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeType;

            public class RecipeBoundarySurface {
                private final CraftingContainer assemblyMatrix = null;
                private final Map<Integer, ItemStack> temp = new HashMap<>();

                public void take(Player player, ItemStack stack) {
                    for (Recipe<CraftingContainer> recipe : player.level().getRecipeManager().getRecipesFor(RecipeType.CRAFTING, this.assemblyMatrix, player.level())) {
                        recipe.getResultItem(player.level().registryAccess());
                    }
                    NonNullList<ItemStack> remainingItems = player.level().getRecipeManager().getRemainingItemsFor(RecipeType.CRAFTING, this.assemblyMatrix, player.level());
                }
            }
        """.trimIndent())
        srcDir.resolve("EnchantmentIterationSurface.java").writeText("""
            package com.example;

            import java.util.Objects;
            import net.minecraft.core.Holder;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.minecraft.world.item.enchantment.Enchantments;

            public class EnchantmentIterationSurface extends Item {
                public EnchantmentIterationSurface(Properties properties) {
                    super(properties);
                }

                public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
                    EnchantmentHelper.getEnchantments(book).forEach((enchantment, level) -> {
                        if (Objects.equals(Enchantments.THORNS, enchantment) || ModEnchantments.COLD.get().equals(enchantment)) {
                        }
                    });
                    return true;
                }

                public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
                    return !ModEnchantments.COLD.get().equals(enchantment) && super.canApplyAtEnchantingTable(stack, enchantment);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val blocks = srcDir.resolve("BlockRegistrySurface.java").readText()
        val banner = srcDir.resolve("BannerSurface.java").readText()
        val registry = srcDir.resolve("RegistrySurface.java").readText()
        val layerType = srcDir.resolve("BiomeLayerType.java").readText()
        val network = srcDir.resolve("NetworkSurface.java").readText()
        val blockHelper = srcDir.resolve("BlockHelperSurface.java").readText()
        val feature = srcDir.resolve("FeatureSurface.java").readText()
        val creative = srcDir.resolve("CreativeSurface.java").readText()
        val misc = srcDir.resolve("MiscSurface.java").readText()
        val requiredMapCodec = srcDir.resolve("MapCodecRequiredSurface.java").readText()
        val holderAndItem = srcDir.resolve("HolderAndItemSurface.java").readText()
        val recipeAndMenu = srcDir.resolve("RecipeAndMenuSurface.java").readText()
        val recipeBoundary = srcDir.resolve("RecipeBoundarySurface.java").readText()
        val enchantmentIteration = srcDir.resolve("EnchantmentIterationSurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(blocks.contains("new StairBlock(Blocks.STONE.get().defaultBlockState(), BlockBehaviour.Properties.ofFullCopy(Blocks.STONE.get()))"))
        assertTrue(blocks.contains("new ButtonBlock(ModBlockSets.WOOD_SET, 30, BlockBehaviour.Properties.of().noCollission())"))
        assertTrue(blocks.contains("new FenceGateBlock(ModWoodTypes.WOOD, BlockBehaviour.Properties.ofFullCopy(Blocks.PLANKS.get()))"))
        assertTrue(blocks.contains("new PressurePlateBlock(ModBlockSets.WOOD_SET, BlockBehaviour.Properties.of().strength(0.5F))"))
        assertTrue(blocks.contains("new DoorBlock(ModBlockSets.WOOD_SET, BlockBehaviour.Properties.of().noOcclusion())"))
        assertTrue(blocks.contains("new TrapDoorBlock(ModBlockSets.WOOD_SET, BlockBehaviour.Properties.of().noOcclusion())"))
        assertTrue(blocks.contains("new TorchBlock(ParticleTypes.SMOKE, BlockBehaviour.Properties.ofFullCopy(Blocks.TORCH))"))
        assertTrue(blocks.contains("new WallTorchBlock(ModParticleTypes.SPARKLE.get(), BlockBehaviour.Properties.ofFullCopy(Blocks.WALL_TORCH))"))
        assertTrue(banner.contains("new BannerPattern(ResourceLocation.fromNamespaceAndPath(ExampleMod.ID, \"ex\"), \"ex\")"))
        assertTrue(registry.contains("makeRegistry(builder -> {})"))
        assertTrue(registry.contains("REGISTRY.byNameCodec()"))
        assertTrue(registry.contains("RegistryFileCodec.create(MAGIC_KEY, MagicPaintingVariant.CODEC.codec(), false)"))
        assertTrue(layerType.contains("import com.mojang.serialization.MapCodec;"))
        assertTrue(layerType.contains("MapCodec<? extends BiomeLayerFactory> getCodec();"))
        assertTrue(!layerType.contains("import com.mojang.serialization.Codec;"))
        assertTrue(network.contains("import net.minecraft.server.level.ServerLevel;"))
        assertTrue(network.contains("PacketDistributor.sendToPlayersNear((ServerLevel) level, null, pos.getX(), pos.getY(), pos.getZ(), 64, packet)"))
        assertTrue(!network.contains("TargetPoint"))
        assertTrue(!network.contains("CHANNEL.send"))
        assertTrue(blockHelper.contains("DeferredHolder<Block, CustomBlock> CUSTOM"))
        assertTrue(blockHelper.contains("DeferredHolder<Block, StairBlock> STAIRS"))
        assertTrue(blockHelper.contains("DeferredHolder<Block, T> register(String name, Supplier<T> block)"))
        assertTrue(blockHelper.contains("DeferredHolder<Block, T> ret = BLOCKS.register(name, block);"))
        assertTrue(blockHelper.contains("return ret;"))
        assertTrue(feature.contains("RecordCodecBuilder.<ConfigSurface>create"))
        assertTrue(feature.contains("TreeDecorator.CODEC.listOf()"))
        assertTrue(feature.contains("ProcessorRule.CODEC.listOf()"))
        assertTrue(feature.contains("new MapConfigFeature(MapConfig.CODEC.codec())"))
        assertTrue(creative.contains("new EnchantmentInstance(parameters.holders().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), 1)"))
        assertTrue(creative.contains("custom(parameters, output, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY)"))
        assertTrue(creative.contains("private static void custom(CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output, CreativeModeTab.TabVisibility visibility)"))
        assertTrue(creative.contains("for (ResourceKey<Enchantment> enchantmentKey : CustomEnchantments.ENCHANTMENTS)"))
        assertTrue(creative.contains("Holder<Enchantment> enchantment = parameters.holders().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantmentKey);"))
        assertTrue(creative.contains("new EnchantmentInstance(enchantment, enchantment.value().getMaxLevel())"))
        assertTrue(misc.contains("REGISTRY.byNameCodec()"))
        assertTrue(misc.contains("BuiltInRegistries.ENTITY_TYPE.byNameCodec()"))
        assertTrue(misc.contains("Component.Serializer.toJson(Component.literal(\"ok\"), net.minecraft.core.RegistryAccess.EMPTY)"))
        assertTrue(!misc.contains("ExtraCodecs.lazyInitializedCodec"))
        assertTrue(misc.contains("new Music(Sounds.MUSIC.getDelegate(), 20, 40, true)"))
        assertTrue(misc.contains("player.getMainHandItem().isCorrectToolForDrops(state)"))
        assertTrue(misc.contains("DeferredHolder::get"))
        assertTrue(misc.contains("wrapper.data()"))
        assertTrue(requiredMapCodec.contains("MapCodec<MapCodecRequiredSurface> CODEC = RecordCodecBuilder.<MapCodecRequiredSurface>mapCodec"))
        assertTrue(requiredMapCodec.contains("MapCodec<MapCodecRequiredSurface> LIST_CODEC = BoundingBox.CODEC.listOf().fieldOf(\"cutouts\").xmap"))
        assertTrue(requiredMapCodec.contains("MapCodec<MapCodecRequiredSurface> FIELD_CODEC = BoundingBox.CODEC.listOf().fieldOf(\"cutouts\").xmap"))
        assertTrue(requiredMapCodec.contains("MapCodec<NestedProcessor> CODEC = MapCodec.unit(INSTANCE);"))
        assertTrue(holderAndItem.contains("this.widget.value().run()"))
        assertTrue(holderAndItem.contains("level.getBiome(pos).value()"))
        assertTrue(holderAndItem.contains("ore.value().defaultBlockState()"))
        assertTrue(holderAndItem.contains("replacements.put(ore.value(), ground.value())"))
        assertTrue(holderAndItem.contains("replacements.put(ore.value(), ore.value())"))
        assertTrue(holderAndItem.contains("thing.VALUE.getDelegate()"))
        assertTrue(holderAndItem.contains("NeoForgeMod.EMPTY_TYPE.value()"))
        assertTrue(holderAndItem.contains("NeoForgeMod.WATER_TYPE.value()"))
        assertTrue(holderAndItem.contains("NeoForgeMod.LAVA_TYPE.value()"))
        assertTrue(holderAndItem.contains("NeoForgeMod.MILK_TYPE.get()"))
        assertTrue(holderAndItem.contains("new SwordItem(tier, new Item.Properties().attributes(SwordItem.createAttributes(tier, 3, -2.4F)))"))
        assertTrue(holderAndItem.contains("new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(1).build()))"))
        assertTrue(recipeAndMenu.contains("super(category);"))
        assertTrue(!recipeAndMenu.contains("net.minecraft.world.inventory.CraftingInput"))
        assertTrue(recipeAndMenu.contains("matches(CraftingInput container, Level level)"))
        assertTrue(recipeAndMenu.contains("container.size()"))
        assertTrue(recipeAndMenu.contains("assemble(CraftingInput container, HolderLookup.Provider access)"))
        assertTrue(recipeAndMenu.contains("public static void renderScreens(RegisterMenuScreensEvent event)"))
        assertTrue(recipeAndMenu.contains("event.register(ModMenus.EXAMPLE.get(), ExampleScreen::new)"))
        assertTrue(recipeBoundary.contains("for (RecipeHolder<CraftingRecipe> recipeHolder : player.level().getRecipeManager().getRecipesFor(RecipeType.CRAFTING, this.assemblyMatrix.asCraftInput(), player.level()))"))
        assertTrue(recipeBoundary.contains("CraftingRecipe recipe = recipeHolder.value();"))
        assertTrue(recipeBoundary.contains("getRemainingItemsFor(RecipeType.CRAFTING, this.assemblyMatrix.asCraftInput(), player.level())"))
        assertTrue(!recipeBoundary.contains("Recipe<CraftingContainer>"))
        assertTrue(enchantmentIteration.contains("EnchantmentHelper.getEnchantmentsForCrafting(book)"))
        assertTrue(enchantmentIteration.contains("enchantment.is(Enchantments.THORNS)"))
        assertTrue(enchantmentIteration.contains("enchantment.is(ModEnchantments.COLD)"))
        assertTrue(enchantmentIteration.contains("super.supportsEnchantment(stack, enchantment)"))
    }

    @Test
    fun `migrates legacy game event constructors only inside matching deferred registers`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("GameEventRegistrySurface.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.gameevent.GameEvent;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class GameEventRegistrySurface {
                public static final DeferredRegister<GameEvent> GAME_EVENTS = DeferredRegister.create(Registries.GAME_EVENT, ExampleMod.ID);
                public static final DeferredHolder<GameEvent, GameEvent> MATCHING =
                    GAME_EVENTS.register("matching", () -> new GameEvent("matching", 4));
                public static final DeferredHolder<GameEvent, GameEvent> MISMATCH =
                    GAME_EVENTS.register("registered_id", () -> new GameEvent("legacy_id", 6));

                public Object unrelated() {
                    return OtherFactory.register("matching", () -> new GameEvent("matching", 8));
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val transformed = srcDir.resolve("GameEventRegistrySurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(transformed.contains("GAME_EVENTS.register(\"matching\", () -> new GameEvent(4))"), transformed)
        assertTrue(transformed.contains("GAME_EVENTS.register(\"registered_id\", () -> new GameEvent(\"legacy_id\", 6))"), transformed)
        assertTrue(transformed.contains("OtherFactory.register(\"matching\", () -> new GameEvent(\"matching\", 8))"), transformed)
    }

    @Test
    fun `migrates legacy structure start custom loads without dropping nbt logic`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleStructureStart.java").writeText("""
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.ChunkPos;
            import net.minecraft.world.level.levelgen.structure.Structure;
            import net.minecraft.world.level.levelgen.structure.StructureStart;
            import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

            public class ExampleStructureStart extends StructureStart {
                private boolean conquered;

                public ExampleStructureStart(Structure structure, ChunkPos chunkPos, int references, PiecesContainer pieces) {
                    super(structure, chunkPos, references, pieces);
                }

                protected void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
                    this.conquered = nbt.getBoolean("conquered");
                }
            }
        """.trimIndent())
        srcDir.resolve("StructureFactory.java").writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.ChunkPos;
            import net.minecraft.world.level.levelgen.structure.Structure;
            import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

            public interface StructureFactory {
                default ExampleStructureStart forDeserialization(Structure structure, ChunkPos chunkPos, int references, PiecesContainer pieces, CompoundTag nbt) {
                    ExampleStructureStart start = new ExampleStructureStart(structure, chunkPos, references, pieces);
                    start.load(nbt);
                    return start;
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleBlockEntity.java").writeText("""
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.block.entity.BlockEntity;

            public class ExampleBlockEntity extends BlockEntity {
                @Override
                protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
                    super.loadAdditional(tag, registries);
                    this.setChanged();
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val start = srcDir.resolve("ExampleStructureStart.java").readText()
        val factory = srcDir.resolve("StructureFactory.java").readText()
        val blockEntity = srcDir.resolve("ExampleBlockEntity.java").readText()
        assertTrue(start.contains("public void loadFromTag(CompoundTag nbt)"))
        assertTrue(start.contains("this.conquered = nbt.getBoolean(\"conquered\");"))
        assertTrue(!start.contains("HolderLookup.Provider"))
        assertTrue(factory.contains("start.loadFromTag(nbt);"))
        assertTrue(!factory.contains("start.load(nbt);"))
        assertTrue(blockEntity.contains("protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries)"))
        assertTrue(blockEntity.contains("super.loadAdditional(tag, registries);"))
        assertTrue(!blockEntity.contains("loadFromTag"))
    }

    @Test
    fun `migrates legacy item extension and projectile api hooks by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleBowItem.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.entity.projectile.AbstractArrow;
            import net.minecraft.world.item.ArrowItem;
            import net.minecraft.world.item.BowItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;

            public class ExampleBowItem extends BowItem {
                public ExampleBowItem(Properties properties) {
                    super(properties);
                }

                @Override
                public AbstractArrow customArrow(AbstractArrow arrow) {
                    arrow.getPersistentData().putBoolean("example", true);
                    return arrow;
                }

                @Override
                public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
                    if (living instanceof Player player) {
                        ItemStack itemstack = player.getProjectile(stack);
                        int i = this.getUseDuration(stack, entityLiving) - timeLeft;
                        ArrowItem arrowItem = (ArrowItem) itemstack.getItem();
                        AbstractArrow abstractArrow = arrowItem.createArrow(level, itemstack, player);
                        int k = 1;
                        if (k > 0) abstractArrow.setKnockback(k);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("WearableBlockItem.java").writeText("""
            package com.example;

            import net.minecraft.world.InteractionHand;
            import net.minecraft.world.InteractionResultHolder;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.BlockItem;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;

            public class WearableBlockItem extends BlockItem {
                public WearableBlockItem(Block block, Properties properties) {
                    super(block, properties);
                }

                @Override
                public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
                    ItemStack itemstack = player.getMainHandItem();
                    EquipmentSlot slot = this.getEquipmentSlotForItem(itemstack);
                    return InteractionResultHolder.success(itemstack);
                }

                @Override
                public boolean canEquip(ItemStack stack, EquipmentSlot slot, Entity entity) {
                    return slot == EquipmentSlot.HEAD;
                }
            }
        """.trimIndent())
        srcDir.resolve("AdvancementItem.java").writeText("""
            package com.example;

            import net.minecraft.advancements.AdvancementHolder;
            import net.minecraft.server.ServerAdvancementManager;
            import net.minecraft.world.item.Item;

            public class AdvancementItem extends Item {
                public AdvancementItem(Properties properties) {
                    super(properties);
                }

                public AdvancementHolder lookup(ServerAdvancementManager manager) {
                    return manager.getAdvancement(ExampleMod.prefix("beanstalk"));
                }
            }
        """.trimIndent())
        srcDir.resolve("ScepterItem.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            public class ScepterItem extends Item {
                public ScepterItem(Properties properties) {
                    super(properties);
                }

                public void drain(LivingEntity living, ItemStack stack) {
                    if (living instanceof Player player && !player.isCreative()) {
                        stack.hurtAndBreak(1, level, null, item -> {});
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ArmorTextureItem.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ItemStack;
            import org.jetbrains.annotations.Nullable;

            public class ArmorTextureItem extends ArmorItem {
                public ArmorTextureItem(Properties properties) {
                    super(null, Type.CHESTPLATE, properties);
                }

                @Override
                public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, @Nullable String layer) {
                    return "example:textures/models/armor/example_" + (slot == EquipmentSlot.LEGS ? "2" : "1") + (layer == null ? "_dyed" : "_overlay") + ".png";
                }
            }
        """.trimIndent())
        srcDir.resolve("RarityItem.java").writeText("""
            package com.example;

            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Rarity;

            public class RarityItem extends Item {
                public RarityItem(Properties properties) {
                    super(properties);
                }

                @Override
                public ItemStack getDefaultInstance() {
                    ItemStack stack = new ItemStack(this);
                    stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("rare"));
                    return stack;
                }

                @Override
                public Rarity getRarity(ItemStack stack) {
                    return stack.has(DataComponents.CUSTOM_NAME) ? Rarity.RARE : Rarity.UNCOMMON;
                }
            }
        """.trimIndent())
        srcDir.resolve("FrostedEffect.java").writeText("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.effect.MobEffect;
            import net.minecraft.world.effect.MobEffectCategory;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.ai.attributes.AttributeModifier;
            import net.minecraft.world.entity.ai.attributes.Attributes;

            public class FrostedEffect extends MobEffect {
                public static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath("example", "speed_modifier");

                public FrostedEffect() {
                    super(MobEffectCategory.HARMFUL, 0x56CBFD);
                    this.addAttributeModifier(Attributes.MOVEMENT_SPEED, SPEED.toString(), -0.15D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                }

                @Override
                public boolean applyEffectTick(LivingEntity living, int amplifier) {
                    living.setIsInPowderSnow(true);
                    if (amplifier > 0 && living.canFreeze()) {
                        living.setTicksFrozen(Math.min(living.getTicksRequiredToFreeze(), living.getTicksFrozen() + amplifier));
                        return true;
                    }
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val bow = srcDir.resolve("ExampleBowItem.java").readText()
        val wearable = srcDir.resolve("WearableBlockItem.java").readText()
        val advancement = srcDir.resolve("AdvancementItem.java").readText()
        val scepter = srcDir.resolve("ScepterItem.java").readText()
        val armor = srcDir.resolve("ArmorTextureItem.java").readText()
        val rarity = srcDir.resolve("RarityItem.java").readText()
        val frosted = srcDir.resolve("FrostedEffect.java").readText()
        assertTrue(bow.contains("public AbstractArrow customArrow(AbstractArrow arrow, ItemStack projectileStack, ItemStack weaponStack)"))
        assertTrue(bow.contains("this.getUseDuration(stack, living) - timeLeft"))
        assertTrue(bow.contains("arrowItem.createArrow(level, itemstack, player, stack)"))
        assertTrue(!bow.contains("setKnockback"))
        assertTrue(wearable.contains("player.getEquipmentSlotForItem(itemstack)"))
        assertTrue(wearable.contains("public boolean canEquip(ItemStack stack, EquipmentSlot slot, LivingEntity entity)"))
        assertTrue(!wearable.contains("import net.minecraft.world.entity.Entity;"))
        assertTrue(advancement.contains("return manager.get(ExampleMod.prefix(\"beanstalk\"));"))
        assertTrue(scepter.contains("stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);"))
        assertTrue(armor.contains("public ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, ArmorMaterial.Layer layer, boolean innerModel)"))
        assertTrue(armor.contains("return ResourceLocation.parse(\"example:textures/models/armor/example_\" + (slot == EquipmentSlot.LEGS ? \"2\" : \"1\") + (layer.dyeable() ? \"_dyed\" : \"_overlay\") + \".png\");"))
        assertTrue(!armor.contains("@Nullable String layer"))
        assertTrue(rarity.contains("public Rarity getRarity(ItemStack stack)"))
        assertTrue(!rarity.contains("@Override\n    public Rarity getRarity"))
        assertTrue(rarity.contains("stack.set(DataComponents.RARITY, this.getRarity(stack));"))
        assertTrue(rarity.contains("public void verifyComponentsAfterLoad(ItemStack stack)"))
        assertTrue(frosted.contains("this.addAttributeModifier(Attributes.MOVEMENT_SPEED, SPEED, -0.15D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);"))
        assertTrue(Regex("""(?s)public boolean applyEffectTick\([^)]*\)\s*\{.*return true;\s*\}""").containsMatchIn(frosted), frosted)
    }

    @Test
    fun `migrates legacy tooltip entity data loot and enchantment APIs by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("TooltipLegacyItem.java").writeText("""
            package com.example;

            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.TooltipFlag;
            import java.util.List;

            public class TooltipLegacyItem extends Item {
                public TooltipLegacyItem(Properties properties) {
                    super(properties);
                }

                @Override
                public void appendHoverText(ItemStack stack, Item.TooltipContext level, List<Component> tooltip, TooltipFlag flag) {
                    super.appendHoverText(stack, level, tooltip, flag);
                    Object registries = level.registryAccess();
                    tooltip.add(Component.literal(String.valueOf(level != null && level.dimensionType().natural() ? level.getMoonPhase() : -1)));
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityDataSpawner.java").writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;

            public class EntityDataSpawner {
                public void apply(Level level, Player player, Entity entity, CompoundTag tag) {
                    EntityType.updateCustomEntityTag(level, player, entity, tag);
                }
            }
        """.trimIndent())
        srcDir.resolve("LootTableHolder.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.level.LevelAccessor;
            import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
            import net.minecraft.world.level.storage.loot.LootTable;

            public class LootTableHolder {
                public final ResourceLocation lootTable;

                public LootTableHolder(ResourceLocation id) {
                    this.lootTable = ResourceKey.create(Registries.LOOT_TABLE, id);
                }

                public void fill(LevelAccessor level, BlockPos pos) {
                    if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container) {
                        container.setLootTable(this.lootTable, 0L);
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("LootTableDatagen.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.storage.loot.LootTable;
            import net.minecraft.world.level.storage.loot.entries.NestedLootTable;

            import java.util.function.BiConsumer;

            public class LootTableDatagen {
                public void generate(BiConsumer<ResourceKey<LootTable>, LootTable.Builder> register, LootTableHolder holder) {
                    register.accept(ResourceKey.create(Registries.LOOT_TABLE, holder.lootTable), LootTable.lootTable());
                    NestedLootTable.lootTableReference(ResourceKey.create(Registries.LOOT_TABLE, holder.lootTable));
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientEntityInserter.java").writeText("""
            package com.example;

            import net.minecraft.client.multiplayer.ClientLevel;
            import net.minecraft.world.entity.Entity;

            public class ClientEntityInserter {
                public void add(ClientLevel level, Entity entity) {
                    level.putNonPlayerEntity(0, entity);
                }
            }
        """.trimIndent())
        srcDir.resolve("EnchantedArmor.java").writeText("""
            package com.example;

            import net.minecraft.core.Holder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.Enchantment;
            import java.util.Objects;
            import java.util.concurrent.atomic.AtomicBoolean;

            public class EnchantedArmor extends Item {
                private static final TagKey<Enchantment> BANNED = null;

                public EnchantedArmor(Properties properties) {
                    super(properties);
                }

                public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
                    return !BuiltInRegistries.ENCHANTMENT.tags().getTag(BANNED).contains(enchantment);
                }

                public boolean isBookEnchantable(ItemStack stack, ItemStack book, Holder<Enchantment> enchantment) {
                    AtomicBoolean badEnchant = new AtomicBoolean();
                    for (Enchantment banned : BuiltInRegistries.ENCHANTMENT.tags().getTag(BANNED)) {
                        if (Objects.equals(banned, enchantment)) {
                            badEnchant.set(true);
                            break;
                        }
                    }
                    return !badEnchant.get();
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val tooltip = srcDir.resolve("TooltipLegacyItem.java").readText()
        val entityData = srcDir.resolve("EntityDataSpawner.java").readText()
        val lootTable = srcDir.resolve("LootTableHolder.java").readText()
        val lootDatagen = srcDir.resolve("LootTableDatagen.java").readText()
        val clientEntity = srcDir.resolve("ClientEntityInserter.java").readText()
        val enchantment = srcDir.resolve("EnchantedArmor.java").readText()
        assertTrue(tooltip.contains("import net.minecraft.world.level.Level;"))
        assertTrue(tooltip.contains("Level tooltipLevel = level != null ? level.level() : null;"))
        assertTrue(tooltip.contains("Object registries = tooltipLevel.registryAccess();"))
        assertTrue(tooltip.contains("tooltipLevel != null && tooltipLevel.dimensionType().natural() ? tooltipLevel.getMoonPhase() : -1"))
        assertTrue(entityData.contains("import net.minecraft.world.item.component.CustomData;"))
        assertTrue(entityData.contains("EntityType.updateCustomEntityTag(level, player, entity, CustomData.of(tag));"))
        assertTrue(lootTable.contains("public final ResourceKey<LootTable> lootTable;"))
        assertTrue(lootDatagen.contains("register.accept(holder.lootTable, LootTable.lootTable());"))
        assertTrue(lootDatagen.contains("NestedLootTable.lootTableReference(holder.lootTable);"))
        assertTrue(!lootDatagen.contains("ResourceKey.create(Registries.LOOT_TABLE, holder.lootTable)"))
        assertTrue(clientEntity.contains("level.addEntity(entity);"))
        assertTrue(enchantment.contains("return !enchantment.is(BANNED);"))
        assertTrue(enchantment.contains("if (enchantment.is(BANNED))"))
        assertTrue(!enchantment.contains("BuiltInRegistries.ENCHANTMENT"))
        assertTrue(!enchantment.contains("Objects.equals"))
    }

    @Test
    fun `migrates legacy custom map item saved data and packet APIs by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMapItem.java").writeText("""
            package com.example;

            import net.minecraft.network.chat.Component;
            import net.minecraft.network.protocol.Packet;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.MapItem;
            import net.minecraft.world.item.TooltipFlag;
            import net.minecraft.world.level.Level;
            import org.jetbrains.annotations.Nullable;

            import java.util.List;

            public class ExampleMapItem extends MapItem {
                public static final String STR_ID = "examplemap";

                public ExampleMapItem(Properties properties) {
                    super(properties);
                }

                @Nullable
                public static ExampleMapData getData(ItemStack stack, Level level) {
                    Integer id = getMapId(stack);
                    return id == null ? null : ExampleMapData.getExampleMapData(level, getMapName(id));
                }

                protected ExampleMapData getCustomMapData(ItemStack stack, Level level) {
                    ExampleMapData mapdata = getData(stack, level);
                    if (mapdata == null && !level.isClientSide()) {
                        mapdata = ExampleMapItem.createMapData(stack, level, level.getLevelData().getXSpawn(), level.getLevelData().getZSpawn(), level.dimension());
                    }
                    return mapdata;
                }

                private static ExampleMapData createMapData(ItemStack stack, Level level, int x, int z, ResourceKey<Level> dimension) {
                    int i = level.getFreeMapId();
                    ExampleMapData data = new ExampleMapData(x, z, (byte) 0, false, false, false, dimension);
                    ExampleMapData.registerExampleMapData(level, data, getMapName(i));
                    stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().putInt("map", i);
                    return data;
                }

                public static String getMapName(int id) {
                    return STR_ID + "_" + id;
                }

                @Nullable
                public Packet<?> getUpdatePacket(ItemStack stack, Level level, Player player) {
                    Integer id = getMapId(stack);
                    ExampleMapData mapdata = getCustomMapData(stack, level);
                    return id == null || mapdata == null ? null : mapdata.getUpdatePacket(id, player);
                }

                public void appendHoverText(ItemStack stack, Item.TooltipContext level, List<Component> tooltip, TooltipFlag flag) {
                    ExampleMapData mapdata = level == null ? null : getData(stack, level);
                    if (mapdata != null) {
                        tooltip.add(Component.literal("known"));
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleMapData.java").writeText("""
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.protocol.Packet;
            import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

            public class ExampleMapData extends MapItemSavedData {
                public ExampleMapData(int x, int z, byte scale, boolean track, boolean unlimited, boolean locked, ResourceKey<Level> dimension) {
                    super(x, z, scale, track, unlimited, locked, dimension);
                }

                public static ExampleMapData load(CompoundTag nbt) {
                    MapItemSavedData data = MapItemSavedData.load(nbt);
                    return new ExampleMapData(data.centerX, data.centerZ, data.scale, true, false, data.locked, data.dimension);
                }

                @Override
                public CompoundTag save(CompoundTag nbt) {
                    CompoundTag ret = super.save(nbt);
                    ret.putBoolean("custom", true);
                    return ret;
                }

                public static ExampleMapData getExampleMapData(Level level, String name) {
                    return ((ServerLevel) level).getServer().overworld().getDataStorage().get(ExampleMapData::load, name);
                }

                public static void registerExampleMapData(Level level, ExampleMapData data, String id) {
                    ((ServerLevel) level).getServer().overworld().getDataStorage().set(id, data);
                }

                @Override
                public Packet<?> getUpdatePacket(int mapId, Player player) {
                    Packet<?> packet = super.getUpdatePacket(mapId, player);
                    return packet instanceof ClientboundMapItemDataPacket mapPacket ? packet : packet;
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleMapPacket.java").writeText("""
            package com.example;

            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.MapRenderer;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.codec.StreamCodec;
            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
            import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleMapPacket implements CustomPacketPayload {
                public static final CustomPacketPayload.Type<ExampleMapPacket> TYPE =
                        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("example", "map"));
                public static final StreamCodec<FriendlyByteBuf, ExampleMapPacket> STREAM_CODEC =
                        StreamCodec.of((buf, packet) -> packet.encode(buf), ExampleMapPacket::new);
                private final ClientboundMapItemDataPacket inner;

                public ExampleMapPacket(ClientboundMapItemDataPacket inner) {
                    this.inner = inner;
                }

                public ExampleMapPacket(FriendlyByteBuf buf) {
                    this.inner = new ClientboundMapItemDataPacket(buf);
                }

                public void encode(FriendlyByteBuf buf) {
                    this.inner.write(buf);
                }

                @Override
                public Type<? extends CustomPacketPayload> type() {
                    return TYPE;
                }

                public void handle() {
                    String name = ExampleMapItem.getMapName(this.inner.getMapId());
                    ExampleMapData data = new ExampleMapData(0, 0, this.inner.getScale(), false, false, this.inner.isLocked(), Minecraft.getInstance().level.dimension());
                    MapRenderer renderer = Minecraft.getInstance().gameRenderer.getMapRenderer();
                    renderer.update(this.inner.getMapId(), data);
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val item = srcDir.resolve("ExampleMapItem.java").readText()
        val data = srcDir.resolve("ExampleMapData.java").readText()
        val packet = srcDir.resolve("ExampleMapPacket.java").readText()
        assertTrue(item.contains("MapId id = stack.get(DataComponents.MAP_ID);"))
        assertTrue(item.contains("level.getSharedSpawnPos().getX()"))
        assertTrue(item.contains("level.getSharedSpawnPos().getZ()"))
        assertTrue(item.contains("MapId i = level.getFreeMapId();"))
        assertTrue(item.contains("stack.set(DataComponents.MAP_ID, i);"))
        assertTrue(item.contains("public static String getMapName(MapId id)"))
        assertTrue(item.contains("return STR_ID + \"_\" + id.id();"))
        assertTrue(item.contains("Level tooltipLevel = level != null ? level.level() : null;"))
        assertTrue(item.contains("tooltipLevel == null ? null : getData(stack, tooltipLevel)"))
        assertTrue(data.contains("public static ExampleMapData load(CompoundTag nbt, HolderLookup.Provider registries)"))
        assertTrue(data.contains("MapItemSavedData.load(nbt, registries)"))
        assertTrue(data.contains("public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries)"))
        assertTrue(data.contains("super.save(nbt, registries)"))
        assertTrue(data.contains("getDataStorage().get(new SavedData.Factory<>(() -> null, ExampleMapData::load), name)"))
        assertTrue(data.contains("getUpdatePacket(MapId mapId, Player player)"))
        assertTrue(data.contains("super.getUpdatePacket(mapId, player)"))
        assertTrue(packet.contains("StreamCodec<RegistryFriendlyByteBuf, ExampleMapPacket>"))
        assertTrue(packet.contains("public ExampleMapPacket(RegistryFriendlyByteBuf buf)"))
        assertTrue(packet.contains("ClientboundMapItemDataPacket.STREAM_CODEC.decode(buf)"))
        assertTrue(packet.contains("ClientboundMapItemDataPacket.STREAM_CODEC.encode(buf, this.inner);"))
        assertTrue(packet.contains("this.inner.mapId()"))
        assertTrue(packet.contains("this.inner.scale()"))
        assertTrue(packet.contains("this.inner.locked()"))
        assertTrue(!packet.contains("import net.minecraft.network.FriendlyByteBuf;"))
    }

    @Test
    fun `migrates legacy skull owner and registry buffer APIs by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("LegacySkullItem.java").writeText("""
            package com.example;

            import com.mojang.authlib.GameProfile;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.NbtUtils;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.entity.SkullBlockEntity;

            public class LegacySkullItem extends Item {
                public LegacySkullItem(Properties properties) {
                    super(properties);
                }

                @Override
                public void verifyTagAfterLoad(CompoundTag tag) {
                    super.verifyTagAfterLoad(tag);
                    if (tag.contains("SkullOwner", 8) && !tag.getString("SkullOwner").isBlank()) {
                        GameProfile gameprofile = new GameProfile(null, tag.getString("SkullOwner"));
                        SkullBlockEntity.updateGameprofile(gameprofile, profile -> {
                            tag.put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), profile));
                        });
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyPayload.java").writeText("""
            package com.example;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.chat.Component;
            import net.minecraft.network.codec.StreamCodec;
            import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.ItemStack;

            public class LegacyPayload implements CustomPacketPayload {
                public static final CustomPacketPayload.Type<LegacyPayload> TYPE =
                        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("example", "legacy"));
                public static final StreamCodec<FriendlyByteBuf, LegacyPayload> STREAM_CODEC =
                        StreamCodec.of((buf, packet) -> packet.encode(buf), LegacyPayload::new);
                private final Component title;
                private final ItemStack icon;

                public LegacyPayload(Component title, ItemStack icon) {
                    this.title = title;
                    this.icon = icon;
                }

                public LegacyPayload(FriendlyByteBuf buf) {
                    this.title = buf.readComponent();
                    this.icon = buf.readItem();
                }

                public void encode(FriendlyByteBuf buf) {
                    buf.writeComponent(this.title);
                    buf.writeItem(this.icon);
                }

                @Override
                public Type<? extends CustomPacketPayload> type() {
                    return TYPE;
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val skull = srcDir.resolve("LegacySkullItem.java").readText()
        val payload = srcDir.resolve("LegacyPayload.java").readText()
        assertTrue(!skull.contains("verifyTagAfterLoad"))
        assertTrue(skull.contains("public void verifyComponentsAfterLoad(ItemStack stack)"))
        assertTrue(skull.contains("stack.get(DataComponents.PROFILE)"))
        assertTrue(skull.contains("stack.get(DataComponents.CUSTOM_DATA)"))
        assertTrue(skull.contains("profile.resolve().thenAcceptAsync"))
        assertTrue(!skull.contains("GameProfile"))
        assertTrue(payload.contains("StreamCodec<RegistryFriendlyByteBuf, LegacyPayload>"))
        assertTrue(payload.contains("ComponentSerialization.STREAM_CODEC.decode(buf)"))
        assertTrue(payload.contains("ItemStack.STREAM_CODEC.decode(buf)"))
        assertTrue(payload.contains("ComponentSerialization.STREAM_CODEC.encode(buf, this.title)"))
        assertTrue(payload.contains("ItemStack.STREAM_CODEC.encode(buf, this.icon)"))
        assertTrue(!payload.contains("import net.minecraft.network.FriendlyByteBuf;"))
    }

    @Test
    fun `migrates legacy capability painting biome holder and itemstack APIs by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("LegacyUtilitySurface.java").writeText("""
            package com.example;

            import java.util.Set;
            import java.util.stream.Collectors;
            import net.minecraft.core.Holder;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.PaintingVariantTags;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.decoration.PaintingVariant;
            import net.minecraft.world.entity.player.Inventory;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.capabilities.Capabilities;

            public class LegacyUtilitySurface {
                public boolean valid(String id) {
                    return ResourceLocation.isValidResourceLocation(id);
                }

                public boolean invalid(String id) {
                    return !ResourceLocation.isValidResourceLocation(id);
                }

                public Set<PaintingVariant> values(java.util.stream.Stream<Holder<PaintingVariant>> stream) {
                    return stream.map(Holder::get).collect(Collectors.toSet());
                }

                public ResourceKey<PaintingVariant> pick(int width, int height) {
                    for (PaintingVariant art : BuiltInRegistries.PAINTING_VARIANT.tags().getTag(PaintingVariantTags.PLACEABLE)) {
                        if (art.getWidth() == width && art.getHeight() >= height) {
                            return ResourceKey.create(net.minecraft.core.registries.Registries.PAINTING_VARIANT, BuiltInRegistries.PAINTING_VARIANT.getKey(art));
                        }
                    }
                    return null;
                }

                public boolean consume(LivingEntity living, int count) {
                    return living.getCapability(Capabilities.ItemHandler.ENTITY).map(inv -> {
                        int slots = inv.getSlots();
                        return slots > count;
                    }).orElse(false);
                }

                public ItemStack read(CompoundTag compoundtag, Inventory inventory) {
                    return ItemStack.of(compoundtag);
                }
            }
        """.trimIndent())
        srcDir.resolve("PaintingHelper.java").writeText("""
            package com.example;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.tags.PaintingVariantTags;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.entity.decoration.Painting;
            import net.minecraft.world.entity.decoration.PaintingVariant;
            import net.minecraft.world.level.WorldGenLevel;

            public class PaintingHelper {
                public static void previous(ServerLevel serverlevel1) {
                    serverlevel1.registryAccess();
                }

                public static void hang(WorldGenLevel world, Painting painting, ResourceKey<PaintingVariant> chosenPainting) {
                    painting.setVariant(BuiltInRegistries.PAINTING_VARIANT.getHolder(chosenPainting).get());
                }

                public static ResourceKey<PaintingVariant> getPaintingOfSize(RandomSource rand, int minSize) {
                    return getPaintingOfSize(rand, minSize, minSize, false);
                }

                public static ResourceKey<PaintingVariant> getPaintingOfSize(RandomSource rand, int width, int height, boolean exactMeasurements) {
                    List<ResourceKey<PaintingVariant>> valid = new ArrayList<>();
                    for (PaintingVariant art : BuiltInRegistries.PAINTING_VARIANT.tags().getTag(PaintingVariantTags.PLACEABLE)) {
                        if (art.getWidth() == width && art.getHeight() >= height) {
                            valid.add(ResourceKey.create(Registries.PAINTING_VARIANT, Objects.requireNonNull(BuiltInRegistries.PAINTING_VARIANT.getKey(art))));
                        }
                    }
                    return valid.isEmpty() ? null : valid.get(rand.nextInt(valid.size()));
                }
            }
        """.trimIndent())
        srcDir.resolve("PaintingCallSite.java").writeText("""
            package com.example;

            import net.minecraft.util.RandomSource;
            import net.minecraft.world.entity.decoration.Painting;
            import net.minecraft.world.level.WorldGenLevel;

            public class PaintingCallSite {
                public void place(WorldGenLevel world, RandomSource random, Painting painting) {
                    PaintingHelper.hang(world, painting, PaintingHelper.getPaintingOfSize(random, 16));
                }
            }
        """.trimIndent())
        srcDir.resolve("LegacyBiomeSource.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.MapCodec;
            import net.minecraft.core.Holder;
            import net.minecraft.world.level.biome.Biome;
            import net.minecraft.world.level.biome.BiomeSource;
            import net.minecraft.world.level.biome.Climate;

            public class LegacyBiomeSource extends BiomeSource {
                public static final MapCodec<LegacyBiomeSource> CODEC = MapCodec.unit(new LegacyBiomeSource());

                @Override
                protected Codec<LegacyBiomeSource> codec() {
                    return CODEC;
                }

                @Override
                protected java.util.stream.Stream<Holder<Biome>> collectPossibleBiomes() {
                    return java.util.stream.Stream.empty();
                }

                @Override
                public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
                    return null;
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val utility = srcDir.resolve("LegacyUtilitySurface.java").readText()
        val painting = srcDir.resolve("PaintingHelper.java").readText()
        val paintingCallSite = srcDir.resolve("PaintingCallSite.java").readText()
        val biome = srcDir.resolve("LegacyBiomeSource.java").readText()
        assertTrue(utility.contains("ResourceLocation.tryParse(id) != null"))
        assertTrue(utility.contains("ResourceLocation.tryParse(id) == null"))
        assertTrue(utility.contains("Holder::value"))
        assertTrue(utility.contains("art.width() == width"))
        assertTrue(utility.contains("art.height() >= height"))
        assertTrue(utility.contains("var inv = living.getCapability(Capabilities.ItemHandler.ENTITY);"))
        assertTrue(utility.contains("if (inv == null)"))
        assertTrue(utility.contains("return false;"))
        assertTrue(utility.contains("ItemStack.parseOptional(inventory.player.registryAccess(), compoundtag)"))
        assertTrue(painting.contains("painting.setVariant(world.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT).getHolderOrThrow(chosenPainting));"))
        assertTrue(painting.contains("getPaintingOfSize(RegistryAccess registryAccess, RandomSource rand, int minSize)"))
        assertTrue(painting.contains("for (Holder<PaintingVariant> artHolder : registryAccess.registryOrThrow(Registries.PAINTING_VARIANT).getTagOrEmpty(PaintingVariantTags.PLACEABLE))"))
        assertTrue(painting.contains("PaintingVariant art = artHolder.value();"))
        assertTrue(painting.contains("artHolder.unwrapKey().orElseThrow()"))
        assertTrue(paintingCallSite.contains("PaintingHelper.getPaintingOfSize(world.registryAccess(), random, 16)"))
        assertTrue(biome.contains("protected MapCodec<LegacyBiomeSource> codec()"))
        assertTrue(!biome.contains("protected Codec<LegacyBiomeSource> codec()"))
    }

    @Test
    fun `migrates legacy authlib chunk generator blockstate holder codec and grass color APIs by source shape`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val resourcesDir = tempDir.resolve("src/main/resources/META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        resourcesDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[4,)"
            [[mods]]
            modId="examplemod"
            version="1.0.0"
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;
            import net.minecraft.resources.ResourceLocation;

            @Mod("examplemod")
            public class ExampleMod {
                public static ResourceLocation prefix(String id) {
                    return ResourceLocation.fromNamespaceAndPath("examplemod", id);
                }
            }
        """.trimIndent())
        srcDir.resolve("AuthlibSurface.java").writeText("""
            package com.example;

            import com.mojang.authlib.EnvironmentParser;
            import com.mojang.authlib.GameProfile;
            import com.mojang.authlib.HttpAuthenticationService;
            import com.mojang.authlib.exceptions.*;
            import com.mojang.authlib.minecraft.client.ObjectMapper;
            import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
            import com.mojang.authlib.yggdrasil.YggdrasilEnvironment;
            import com.mojang.authlib.yggdrasil.response.MinecraftProfilePropertiesResponse;
            import org.apache.commons.lang3.StringUtils;
            import java.io.IOException;
            import java.net.Proxy;
            import java.net.URL;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.UUID;

            public class AuthlibSurface {
                public static final List<GameProfile> GAME_PROFILES = new ArrayList<>();

                public static void reload(String stringUUID) {
                    YggdrasilAuthenticationService service = new YggdrasilAuthenticationService(Proxy.NO_PROXY);
                    String baseUrl = EnvironmentParser.getEnvironmentFromProperties().orElse(YggdrasilEnvironment.PROD.getEnvironment()).getSessionHost() + "/session/minecraft/";
                    boolean requireSecure = false;
                    try {
                        UUID uuid = UUID.fromString(stringUUID);
                        URL url = HttpAuthenticationService.constantURL(baseUrl + "profile/" + uuid);
                        url = HttpAuthenticationService.concatenateURL(url, "unsigned=" + !requireSecure);

                        final MinecraftProfilePropertiesResponse response = ObjectMapper.create().readValue(service.performGetRequest(url), MinecraftProfilePropertiesResponse.class);

                        if (StringUtils.isNotBlank(response.getError())) {
                            throw new AuthenticationException(response.getErrorMessage());
                        }

                        if (response.getId() != null) {
                            final GameProfile result = new GameProfile(response.getId(), response.getName());
                            if (response.getProperties() != null)
                                result.getProperties().putAll(response.getProperties());
                            GAME_PROFILES.add(result);
                        }
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();
                    } catch (AuthenticationException | IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("Landmark.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;

            public class Landmark {
                public static final Codec<Landmark> CODEC = Codec.unit(new Landmark());
            }
        """.trimIndent())
        srcDir.resolve("ChunkCodecSurface.java").writeText("""
            package com.example;

            import com.google.common.collect.ImmutableSet;
            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import java.util.Map;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.level.biome.Biome;

            public class ChunkCodecSurface {
                public static final MapCodec<ChunkCodecSurface> CODEC = RecordCodecBuilder.mapCodec((instance) -> instance.group(
                    Landmark.CODEC.codec().listOf().xmap(ImmutableSet::copyOf, java.util.List::copyOf).fieldOf("landmarks").forGetter(o -> o.landmarks)
                ).apply(instance, ChunkCodecSurface::new));

                private final Map<ResourceKey<Biome>, ImmutableSet<Landmark>> landmarks;

                public ChunkCodecSurface(Map<ResourceKey<Biome>, ImmutableSet<Landmark>> landmarks) {
                    this.landmarks = landmarks;
                }
            }
        """.trimIndent())
        srcDir.resolve("ChunkGeneratorSurface.java").writeText("""
            package com.example;

            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.Executor;
            import net.minecraft.Util;
            import net.minecraft.world.level.StructureManager;
            import net.minecraft.world.level.chunk.ChunkAccess;
            import net.minecraft.world.level.chunk.ChunkGenerator;
            import net.minecraft.world.level.levelgen.RandomState;
            import net.minecraft.world.level.levelgen.blending.Blender;

            public abstract class ChunkGeneratorSurface extends ChunkGenerator {
                public final ChunkGenerator delegate;

                public ChunkGeneratorSurface(ChunkGenerator delegate) {
                    super(delegate.getBiomeSource());
                    this.delegate = delegate;
                }

                @Override
                public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random, StructureManager structureManager, ChunkAccess chunkAccess) {
                    return this.delegate.fillFromNoise(executor, blender, random, structureManager, chunkAccess)
                        .whenCompleteAsync((chunk, throwable) -> {}, executor);
                }
            }
        """.trimIndent())
        srcDir.resolve("WorldgenSurface.java").writeText("""
            package com.example;

            import java.util.Optional;
            import java.util.function.Supplier;
            import net.minecraft.core.Direction;
            import net.minecraft.core.Holder;
            import net.minecraft.core.HolderGetter;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.level.BlockGetter;
            import net.minecraft.world.level.LevelReader;
            import net.minecraft.world.level.biome.Biome;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

            public class WorldgenSurface {
                public boolean survives(BlockState state, LevelReader level, net.minecraft.core.BlockPos pos) {
                    return state.getBlock().canSurvive(state, level, pos);
                }

                public boolean sustains(BlockState state, BlockGetter level, net.minecraft.core.BlockPos pos, Supplier<Block> plantBlock) {
                    return state.getBlock().canSustainPlant(state, level, pos, Direction.UP, plantBlock.get());
                }

                public NoiseGeneratorSettings settings(Holder<NoiseGeneratorSettings> settings, net.minecraft.world.level.chunk.NoiseBasedChunkGenerator noiseGen) {
                    if (noiseGen.generatorSettings().isBound()) {
                        return noiseGen.generatorSettings().get();
                    }
                    return settings.get();
                }

                public Holder<Biome> biome(HolderGetter<Biome> biomeRegistry, ResourceKey<Biome> key) {
                    Holder.Reference<Biome> biomeHolder = biomeRegistry.getOrThrow(key);
                    biomeHolder.bindKey(key);
                    return biomeHolder;
                }

                public ResourceKey<Biome> optionalKey(Optional<ResourceKey<Biome>> biome) {
                    if (biome.isEmpty()) return null;
                    return biome.value();
                }
            }
        """.trimIndent())
        srcDir.resolve("BiomeGrassColors.java").writeText("""
            package com.example;

            import net.minecraft.world.level.GrassColor;
            import net.minecraft.world.level.biome.BiomeSpecialEffects.GrassColorModifier;

            public class BiomeGrassColors {
                public static int helper(int x, int color) {
                    return color + x;
                }

                public static final GrassColorModifier MAGIC = make("magic", (x, z, color) -> {
                    return helper((int) x, color);
                });
                public static final GrassColorModifier SWAMPY = make("swampy", (x, z, color) -> GrassColor.get(0.8F, 0.9F));

                private static GrassColorModifier make(String name, GrassColorModifier.ColorModifier delegate) {
                    name = ExampleMod.prefix(name).toString();
                    return GrassColorModifier.create(name, name, delegate);
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val authlib = srcDir.resolve("AuthlibSurface.java").readText()
        val codec = srcDir.resolve("ChunkCodecSurface.java").readText()
        val chunkGenerator = srcDir.resolve("ChunkGeneratorSurface.java").readText()
        val worldgen = srcDir.resolve("WorldgenSurface.java").readText()
        val grass = srcDir.resolve("BiomeGrassColors.java").readText()
        val enumHelper = srcDir.resolve("NeoForgeEnumExtensions.java").readText()
        val enumExtensions = resourcesDir.resolve("enumextensions.json").readText()
        val toml = resourcesDir.resolve("neoforge.mods.toml").readText()

        assertTrue(authlib.contains("service.createMinecraftSessionService().fetchProfile(uuid, false)"))
        assertTrue(authlib.contains("GAME_PROFILES.add(profileResult.profile())"))
        assertTrue(authlib.contains("catch (RuntimeException e)"))
        assertTrue(!authlib.contains("performGetRequest"))
        assertTrue(!authlib.contains("MinecraftProfilePropertiesResponse"))
        assertTrue(codec.contains("RecordCodecBuilder.<ChunkCodecSurface>mapCodec"))
        assertTrue(codec.contains(".forGetter((ChunkCodecSurface o) -> o.landmarks)"))
        assertTrue(codec.contains("Landmark.CODEC.listOf()"))
        assertTrue(chunkGenerator.contains("fillFromNoise(Blender blender, RandomState random, StructureManager structureManager, ChunkAccess chunkAccess)"))
        assertTrue(chunkGenerator.contains("this.delegate.fillFromNoise(blender, random, structureManager, chunkAccess)"))
        assertTrue(chunkGenerator.contains("Util.backgroundExecutor()"))
        assertTrue(!chunkGenerator.contains("java.util.concurrent.Executor"))
        assertTrue(worldgen.contains("state.canSurvive(level, pos)"))
        assertTrue(worldgen.contains("return !state.getBlock().canSustainPlant(state, level, pos, Direction.UP, plantBlock.get().defaultBlockState()).isFalse();"))
        assertTrue(worldgen.contains("noiseGen.generatorSettings().value()"))
        assertTrue(worldgen.contains("settings.value()"))
        assertTrue(!worldgen.contains("bindKey"))
        assertTrue(worldgen.contains("return biome.get();"))
        assertTrue(grass.contains("GrassColorModifier.valueOf(\"EXAMPLEMOD_MAGIC\")"))
        assertTrue(grass.contains("GrassColorModifier.valueOf(\"EXAMPLEMOD_SWAMPY\")"))
        assertTrue(!grass.contains("GrassColorModifier.create"))
        assertTrue(enumHelper.contains("case 0 -> \"examplemod:magic\""))
        assertTrue(enumHelper.contains("case 1 -> (GrassColorModifier.ColorModifier) ((x, z, color) -> {"))
        assertTrue(enumHelper.contains("BiomeGrassColors.helper((int) x, color)"))
        assertTrue(enumExtensions.contains("BiomeSpecialEffects${'$'}GrassColorModifier"))
        assertTrue(enumExtensions.contains("EXAMPLEMOD_MAGIC"))
        assertTrue(toml.contains("enumExtensions=\"META-INF/enumextensions.json\""))
        assertTrue(toml.indexOf("[[mods]]") < toml.indexOf("enumExtensions=\"META-INF/enumextensions.json\""), toml)
        assertTrue(toml.indexOf("enumExtensions=\"META-INF/enumextensions.json\"") < toml.indexOf("modId=\"examplemod\""), toml)
    }

    @Test
    fun `migrates strict warning surfaces by source shape without mod specific rules`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("StrictWarningSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.VertexConsumer;
            import java.util.List;
            import java.util.function.Consumer;
            import net.minecraft.core.particles.ColorParticleOption;
            import net.minecraft.core.particles.ParticleTypes;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
            import org.joml.Vector3f;

            public class StrictWarningSurface extends Block {
                private final String displayName = this.toString();
                private final String getterName = getDisplayName();

                public StrictWarningSurface(Properties properties) {
                    super(properties);
                    this.registerDefaultState(this.getStateDefinition().any());
                }

                public void render(VertexConsumer buffer, LivingEntity entity) {
                    float red = 1.0F;
                    float green = 0.5F;
                    float blue = 0.25F;
                    ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, (float) red, (float) green, (float) blue);
                    Vector3f[] vecList = new Vector3f[]{new Vector3f(), new Vector3f()};
                    buffer.addVertex((float) (vecList[0].x()), (float) (vecList[0].y()), (float) (vecList[0].z()));
                    for (Object stack : ((LivingEntity) entity).getArmorSlots()) {
                    }
                }

                @Override
                public void initializeClient(Consumer<IClientBlockExtensions> consumer) {
                    consumer.accept(new IClientBlockExtensions() {});
                }

                public String getDisplayName() {
                    return "name";
                }
            }
        """.trimIndent())
        srcDir.resolve("CuriosSurface.java").writeText("""
            package com.example;

            import java.util.Optional;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.player.Player;
            import top.theillusivec4.curios.api.CuriosApi;
            import top.theillusivec4.curios.api.SlotResult;

            public class CuriosSurface {
                public static void keep(Player player) {
                    CuriosApi.getCuriosHelper().getEquippedCurios(player).ifPresent(handler -> {});
                }

                public static boolean has(LivingEntity entity) {
                    Optional<SlotResult> slot = CuriosApi.getCuriosHelper().findFirstCurio(entity, stack -> !stack.isEmpty());
                    return slot.isPresent();
                }
            }
        """.trimIndent())
        srcDir.resolve("ConstructorThisCallSurface.java").writeText("""
            package com.example;

            public class ConstructorThisCallSurface {
                private final Child child;

                public ConstructorThisCallSurface() {
                    if (this.shouldInitialize()) {
                        System.out.println("init");
                    }
                    this.child = new Child(this);
                }

                public boolean shouldInitialize() {
                    return true;
                }

                private record Child(ConstructorThisCallSurface owner) {}
            }
        """.trimIndent())
        srcDir.resolve("NestedConstructorThisCallSurface.java").writeText("""
            package com.example;

            public class NestedConstructorThisCallSurface {
                public static class LookGoal {
                    public LookGoal() {
                        this.setFlags("look");
                    }

                    public void setFlags(String flag) {
                    }
                }

                public static class MoveGoal {
                    public MoveGoal() {
                        setFlags("move");
                    }

                    public void setFlags(String flag) {
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ConstructorMethodReferenceSurface.java").writeText("""
            package com.example;

            import java.util.function.Consumer;

            public class ConstructorMethodReferenceSurface {
                public ConstructorMethodReferenceSurface(Consumer<Object> bus) {
                    bus.accept(this::registerCommands);
                }

                public void registerCommands(Object event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("ConstructorAccessorSurface.java").writeText("""
            package com.example;

            public class ConstructorAccessorSurface {
                private final Object part;

                public ConstructorAccessorSurface() {
                    this.part = getRoot().getChild("part");
                }

                public Root getRoot() {
                    return new Root();
                }

                public static class Root {
                    public Object getChild(String name) {
                        return name;
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ResourceKeyEnchantmentSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;

            public class ResourceKeyEnchantmentSurface {
                public int level(ItemStack stack) {
                    return EnchantmentHelper.getTagEnchantmentLevel(ModEnchantments.DESTRUCTION.get(), stack);
                }
            }
        """.trimIndent())
        srcDir.resolve("CategorySurface.java").writeText("""
            package com.example;

            import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
            import mezz.jei.api.gui.drawable.IDrawable;
            import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
            import mezz.jei.api.recipe.IFocusGroup;
            import mezz.jei.api.recipe.RecipeType;
            import mezz.jei.api.recipe.category.IRecipeCategory;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.network.chat.Component;

            public class CategorySurface implements IRecipeCategory<CategorySurface.Recipe> {
                private final IDrawable background;
                private final IDrawable icon;

                public CategorySurface(IDrawable background, IDrawable icon) {
                    this.background = background;
                    this.icon = icon;
                }

                @Override
                public RecipeType<Recipe> getRecipeType() { return null; }

                @Override
                public Component getTitle() { return Component.empty(); }

                @Override
                public IDrawable getBackground() { return this.background; }

                @Override
                public IDrawable getIcon() { return this.icon; }

                @Override
                public void draw(Recipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics, double mouseX, double mouseY) {
                    this.icon.draw(graphics);
                }

                @Override
                public void setRecipe(IRecipeLayoutBuilder builder, Recipe recipe, IFocusGroup focuses) {}

                public record Recipe() {}
            }
        """.trimIndent())
        srcDir.resolve("NoDrawCategorySurface.java").writeText("""
            package com.example;

            import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
            import mezz.jei.api.gui.drawable.IDrawable;
            import mezz.jei.api.recipe.IFocusGroup;
            import mezz.jei.api.recipe.RecipeType;
            import mezz.jei.api.recipe.category.IRecipeCategory;
            import net.minecraft.network.chat.Component;

            public class NoDrawCategorySurface implements IRecipeCategory<NoDrawCategorySurface.Recipe> {
                private final IDrawable background;
                private final IDrawable icon;

                public NoDrawCategorySurface(IDrawable background, IDrawable icon) {
                    this.background = background;
                    this.icon = icon;
                }

                @Override
                public RecipeType<Recipe> getRecipeType() { return null; }

                @Override
                public Component getTitle() { return Component.empty(); }

                @Override
                public IDrawable getBackground() { return this.background; }

                @Override
                public IDrawable getIcon() { return this.icon; }

                @Override
                public void setRecipe(IRecipeLayoutBuilder builder, Recipe recipe, IFocusGroup focuses) {}

                public record Recipe() {}
            }
        """.trimIndent())
        srcDir.resolve("RendererSurface.java").writeText("""
            package com.example;

            import java.util.List;
            import mezz.jei.api.ingredients.IIngredientRenderer;
            import net.minecraft.client.gui.GuiGraphics;
            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.TooltipFlag;

            public class RendererSurface implements IIngredientRenderer<String> {
                @Override
                public void render(GuiGraphics graphics, String value) {}

                @Override
                public List<Component> getTooltip(String value, TooltipFlag flag) {
                    return List.of(Component.literal(value));
                }
            }
        """.trimIndent())
        srcDir.resolve("HelperSurface.java").writeText("""
            package com.example;

            import mezz.jei.api.ingredients.IIngredientHelper;
            import mezz.jei.api.ingredients.IIngredientType;
            import mezz.jei.api.ingredients.subtypes.UidContext;
            import net.minecraft.resources.ResourceLocation;

            public class HelperSurface implements IIngredientHelper<String> {
                @Override
                public IIngredientType<String> getIngredientType() { return null; }

                @Override
                public String getDisplayName(String value) { return value; }

                @Override
                public String getUniqueId(String value, UidContext context) { return value; }

                @Override
                public ResourceLocation getResourceLocation(String value) { return ResourceLocation.parse(value); }

                @Override
                public String copyIngredient(String value) { return value; }

                @Override
                public String getErrorInfo(String value) { return value; }
            }
        """.trimIndent())
        srcDir.resolve("JeiPluginSurface.java").writeText("""
            package com.example;

            import java.util.List;
            import mezz.jei.api.ingredients.IIngredientType;
            import mezz.jei.api.registration.IModIngredientRegistration;

            public class JeiPluginSurface {
                public static final IIngredientType<String> TYPE = () -> String.class;

                public void registerIngredients(IModIngredientRegistration registration) {
                    registration.register(TYPE, List.of(), new HelperSurface(), new RendererSurface());
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)

        val strict = srcDir.resolve("StrictWarningSurface.java").readText()
        val curios = srcDir.resolve("CuriosSurface.java").readText()
        val constructorThisCall = srcDir.resolve("ConstructorThisCallSurface.java").readText()
        val nestedConstructorThisCall = srcDir.resolve("NestedConstructorThisCallSurface.java").readText()
        val constructorMethodReference = srcDir.resolve("ConstructorMethodReferenceSurface.java").readText()
        val constructorAccessor = srcDir.resolve("ConstructorAccessorSurface.java").readText()
        val resourceKeyEnchantment = srcDir.resolve("ResourceKeyEnchantmentSurface.java").readText()
        val category = srcDir.resolve("CategorySurface.java").readText()
        val noDrawCategory = srcDir.resolve("NoDrawCategorySurface.java").readText()
        val renderer = srcDir.resolve("RendererSurface.java").readText()
        val helper = srcDir.resolve("HelperSurface.java").readText()
        val plugin = srcDir.resolve("JeiPluginSurface.java").readText()

        assertTrue(strict.contains("@SuppressWarnings(\"this-escape\")"))
        assertTrue(Regex("""@SuppressWarnings\("this-escape"\)\s+private\s+final\s+String\s+displayName""").containsMatchIn(strict))
        assertTrue(Regex("""@SuppressWarnings\("this-escape"\)\s+private\s+final\s+String\s+getterName""").containsMatchIn(strict))
        assertTrue(strict.contains("ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, red, green, blue)"))
        assertTrue(strict.contains("buffer.addVertex(vecList[0].x(), vecList[0].y(), vecList[0].z())"))
        assertTrue(strict.contains("for (Object stack : entity.getArmorSlots())"))
        assertTrue(Regex("""@SuppressWarnings\("removal"\)\s+@Override\s+public\s+void\s+initializeClient""").containsMatchIn(strict))
        assertTrue(curios.contains("CuriosApi.getCuriosInventory(player).map(handler -> handler.getEquippedCurios()).ifPresent"))
        assertTrue(curios.contains("CuriosApi.getCuriosInventory(entity).flatMap(handler -> handler.findFirstCurio(stack -> !stack.isEmpty()))"))
        assertTrue(Regex("""@SuppressWarnings\("this-escape"\)\s+public\s+ConstructorThisCallSurface""").containsMatchIn(constructorThisCall))
        assertTrue(Regex("""@SuppressWarnings\("this-escape"\)\s+public\s+LookGoal""").containsMatchIn(nestedConstructorThisCall))
        assertTrue(Regex("""@SuppressWarnings\("this-escape"\)\s+public\s+MoveGoal""").containsMatchIn(nestedConstructorThisCall))
        assertTrue(Regex("""@SuppressWarnings\("this-escape"\)\s+public\s+ConstructorMethodReferenceSurface""").containsMatchIn(constructorMethodReference))
        assertTrue(Regex("""@SuppressWarnings\("this-escape"\)\s+public\s+ConstructorAccessorSurface""").containsMatchIn(constructorAccessor))
        assertTrue(resourceKeyEnchantment.contains("EnchantmentHelper.getTagEnchantmentLevel(net.neoforged.neoforge.common.CommonHooks.resolveLookup(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(ModEnchantments.DESTRUCTION), stack)"))
        assertTrue(!resourceKeyEnchantment.contains("ModEnchantments.DESTRUCTION.get()"))
        assertTrue(!category.contains("getBackground()"))
        assertTrue(category.contains("public int getWidth()"))
        assertTrue(category.contains("return this.background.getWidth();"))
        assertTrue(category.contains("public int getHeight()"))
        assertTrue(Regex("""this\.background\.draw\(graphics\);\s+this\.icon\.draw\(graphics\);""").containsMatchIn(category))
        assertTrue(!noDrawCategory.contains("getBackground()"))
        assertTrue(noDrawCategory.contains("import mezz.jei.api.gui.ingredient.IRecipeSlotsView;"))
        assertTrue(noDrawCategory.contains("import net.minecraft.client.gui.GuiGraphics;"))
        assertTrue(Regex("""public\s+void\s+draw\((?:NoDrawCategorySurface\.)?Recipe\s+recipe,\s+IRecipeSlotsView\s+recipeSlotsView,\s+GuiGraphics\s+graphics,\s+double\s+mouseX,\s+double\s+mouseY\)""").containsMatchIn(noDrawCategory))
        assertTrue(noDrawCategory.contains("this.background.draw(graphics);"))
        assertTrue(Regex("""@SuppressWarnings\("removal"\)\s+@Override\s+public\s+List<Component>\s+getTooltip""").containsMatchIn(renderer))
        assertTrue(Regex("""@SuppressWarnings\("removal"\)\s+@Override\s+public\s+String\s+getUniqueId""").containsMatchIn(helper))
        assertTrue(Regex("""@SuppressWarnings\("removal"\)\s+public\s+void\s+registerIngredients""").containsMatchIn(plugin))
    }

    @Test
    fun `register additional model keys use standalone variant for inline and static field registrations`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClientEvents.java").writeText("""
            package com.example;

            import net.minecraft.client.resources.model.ModelResourceLocation;
            import net.neoforged.neoforge.client.event.ModelEvent;

            public class ClientEvents {
                public static final ModelResourceLocation LOCAL = ModelResourceLocation.inventory(ExampleMod.prefix("local"));

                public static void registerModels(ModelEvent.RegisterAdditional event) {
                    event.register(ModelResourceLocation.inventory(ExampleMod.prefix("inline")));
                    event.register(LayerModels.SHIELD);
                    event.register(LOCAL);
                }

                public static ModelResourceLocation normalLookup() {
                    return ModelResourceLocation.inventory(ExampleMod.prefix("item/held"));
                }
            }
        """.trimIndent())
        srcDir.resolve("LayerModels.java").writeText("""
            package com.example;

            import net.minecraft.client.resources.model.ModelResourceLocation;

            public class LayerModels {
                public static final ModelResourceLocation SHIELD = ModelResourceLocation.inventory(ExampleMod.prefix("shield"));
                public static final ModelResourceLocation HELD_ITEM = ModelResourceLocation.inventory(ExampleMod.prefix("held_item"));
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public class ExampleMod {
                public static final String ID = "example";

                public static ResourceLocation prefix(String path) {
                    return ResourceLocation.fromNamespaceAndPath(ID, path);
                }
            }
        """.trimIndent())
        val modelDir = tempDir.resolve("src/generated/resources/assets/example/models/item")
        modelDir.createDirectories()
        modelDir.resolve("inline.json").writeText("{}")
        modelDir.resolve("local.json").writeText("{}")
        modelDir.resolve("shield.json").writeText("{}")

        val result = StructuralRefactorPass().apply(tempDir)
        val clientEvents = srcDir.resolve("ClientEvents.java").readText()
        val layerModels = srcDir.resolve("LayerModels.java").readText()

        assertTrue(result.changes.any { it.ruleId == "struct-registeradditional-model-standalone" })
        assertTrue(result.changes.any { it.ruleId == "struct-registeradditional-model-item-path" })
        assertTrue(clientEvents.contains("event.register(ModelResourceLocation.standalone(ExampleMod.prefix(\"item/inline\")))"))
        assertTrue(clientEvents.contains("public static final ModelResourceLocation LOCAL = ModelResourceLocation.standalone(ExampleMod.prefix(\"item/local\"));"))
        assertTrue(clientEvents.contains("return ModelResourceLocation.inventory(ExampleMod.prefix(\"item/held\"));"))
        assertTrue(layerModels.contains("public static final ModelResourceLocation SHIELD = ModelResourceLocation.standalone(ExampleMod.prefix(\"item/shield\"));"))
        assertTrue(layerModels.contains("public static final ModelResourceLocation HELD_ITEM = ModelResourceLocation.inventory(ExampleMod.prefix(\"held_item\"));"))
    }

    @Test
    fun `migrates source backed accessors payload surface and context signatures`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        val networkDir = srcDir.resolve("network")
        srcDir.createDirectories()
        networkDir.createDirectories()
        srcDir.resolve("HolderSurface.java").writeText("""
            package com.example;

            import java.util.Set;
            import java.util.function.Supplier;
            import net.minecraft.core.Holder;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;

            public class HolderSurface {
                private Supplier<Set<Holder<Object>>> possibleValues;

                public void addAll(java.util.Set<Holder<Object>> target) {
                    target.addAll(this.possibleValues.value());
                }

                public void readBlade(ItemStack stack, Player player, net.minecraft.nbt.CompoundTag tag) {
                    ItemStack.parseOptional(level.registryAccess(), tag);
                }

                public int duration(net.minecraft.world.item.Item item, ItemStack stack) {
                    return item.getUseDuration(stack);
                }
            }
        """.trimIndent())
        srcDir.resolve("CombatSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.ai.attributes.Attributes;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.enchantment.EnchantmentHelper;
            import net.minecraft.world.level.Level;

            public class CombatSurface {
                public void releaseUsing(ItemStack stack, Level level, LivingEntity entityLiving) {
                    if (!(entityLiving instanceof Player player)) return;
                    if (level.isClientSide()) return;
                    float sweepRatio = EnchantmentHelper.getSweepingDamageRatio(player);
                    float baseDamage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
                    float enchantBonus = EnchantmentHelper.getDamageBonus(stack, entityLiving.getMobType()) / 1.2F;
                    entityLiving.hurt(player.damageSources().playerAttack(player), baseDamage + enchantBonus + sweepRatio);
                }
            }
        """.trimIndent())
        srcDir.resolve("DrinkKey.java").writeText("""
            package com.example;

            public enum DrinkKey {
                ONE;

                public Object[] getEffects() {
                    return new Object[0];
                }
            }
        """.trimIndent())
        srcDir.resolve("DrinkTest.java").writeText("""
            package com.example;

            public class DrinkTest {
                public void check() {
                    for (DrinkKey key : DrinkKey.values()) {
                        Object[] effects = key.effects();
                    }
                }
            }
        """.trimIndent())
        networkDir.resolve("ModNetwork.java").writeText("""
            package com.example.network;

            public class ModNetwork {
                public static void register(Object event) {
                    registrar.playToServer(DemoPacket.TYPE, DemoPacket.STREAM_CODEC, DemoPacket::handle);
                }
            }
        """.trimIndent())
        networkDir.resolve("DemoPacket.java").writeText("""
            package com.example.network;

            public class DemoPacket {
                public static final Object TYPE = new Object();
                public static final Object STREAM_CODEC = new Object();
                public static void handle(DemoPacket packet, Object context) {}
            }
        """.trimIndent())
        srcDir.resolve("NetworkTest.java").writeText("""
            package com.example;

            import com.example.network.LegacyNetwork;

            public class NetworkTest {
                public void check() {
                    Object channel = LegacyNetwork.CHANNEL;
                }
            }
        """.trimIndent())

        StructuralRefactorPass().apply(tempDir)
        val holder = srcDir.resolve("HolderSurface.java").readText()
        val combat = srcDir.resolve("CombatSurface.java").readText()
        val drinkTest = srcDir.resolve("DrinkTest.java").readText()
        val networkTest = srcDir.resolve("NetworkTest.java").readText()

        assertTrue(holder.contains("this.possibleValues.get()"))
        assertTrue(holder.contains("ItemStack.parseOptional(player.registryAccess(), tag)"))
        assertTrue(holder.contains("item.getUseDuration(stack, null)"))
        assertTrue(combat.contains("(float) player.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO)"))
        assertTrue(combat.contains("(EnchantmentHelper.modifyDamage((ServerLevel) level, stack, entityLiving, player.damageSources().playerAttack(player), baseDamage) - baseDamage) / 1.2F"))
        assertTrue(!combat.contains("getSweepingDamageRatio"))
        assertTrue(!combat.contains("getMobType()"))
        assertTrue(drinkTest.contains("key.getEffects()"))
        assertTrue(networkTest.contains("import com.example.network.DemoPacket;"))
        assertTrue(networkTest.contains("Object channel = DemoPacket.TYPE;"))
        assertTrue(!networkTest.contains("LegacyNetwork.CHANNEL"))
    }

    @Test
    fun `migrates source shaped compile debt without comment or fallback inference`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CustomFluidDefinition.java").writeText("""
            package com.example;

            public record CustomFluidDefinition(int color) {
            }
        """.trimIndent())
        srcDir.resolve("RecordAccessorSurface.java").writeText("""
            package com.example;

            import com.mojang.blaze3d.vertex.VertexConsumer;

            public class RecordAccessorSurface {
                public int read(CustomFluidDefinition definition, VertexConsumer consumer) {
                    consumer.color(255, 255, 255, 255);
                    return definition.setColor();
                }
            }
        """.trimIndent())
        srcDir.resolve("CommentContainerSurface.java").writeText("""
            package com.example;

            import java.util.List;
            import net.minecraft.world.entity.Entity;

            public class CommentContainerSurface {
                // Container entities are entities with inventories, not Container variables.
                public int count(List<Entity> entities) {
                    return entities.size();
                }
            }
        """.trimIndent())
        srcDir.resolve("UseDurationSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;

            public class UseDurationSurface {
                public int stackDuration(LivingEntity entity) {
                    return entity.getUseItem().getUseDuration();
                }

                public int itemDuration(Item item, ItemStack stack) {
                    return item.getUseDuration(stack);
                }
            }
        """.trimIndent())
        srcDir.resolve("RecipeSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.level.Level;

            public class RecipeSurface implements CraftingRecipe {
                public boolean matches(CraftingContainer container, Level level) {
                    return container.getContainerSize() > 0;
                }

                public ItemStack assemble(CraftingContainer container, HolderLookup.Provider access) {
                    return ItemStack.EMPTY;
                }

                public boolean canCraftInDimensions(int width, int height) { return true; }
                public ItemStack getResultItem(HolderLookup.Provider access) { return ItemStack.EMPTY; }
                public RecipeSerializer<?> getSerializer() { return null; }
                public CraftingBookCategory category() { return CraftingBookCategory.MISC; }
            }
        """.trimIndent())
        srcDir.resolve("ContainerScopeSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.Container;
            import net.minecraft.world.item.crafting.CraftingInput;

            public class ContainerScopeSurface {
                private static int recipeSize(CraftingInput matrix) {
                    return matrix.size();
                }

                private static int containerSize(Container matrix) {
                    int count = matrix.size();
                    for (int i = 0; i < matrix.size(); i++) {
                        count += i;
                    }
                    return count;
                }
            }
        """.trimIndent())
        srcDir.resolve("BlockRegistrySurface.java").writeText("""
            package com.example;

            import java.util.function.Supplier;
            import net.minecraft.world.item.BlockItem;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.level.block.Block;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class BlockRegistrySurface {
                public static final DeferredRegister<Block> BLOCKS = null;

                private static <T extends Block> DeferredHolder<Block, T> registerBlock(String name, Supplier<T> block) {
                    DeferredHolder<Block, T> toReturn = BLOCKS.register(name, block);
                    registerBlockItem(name, toReturn);
                    return toReturn;
                }

                private static <T extends Block> DeferredHolder<Block, Item> registerBlockItem(String name, DeferredHolder<Block, T> block) {
                    return ItemRegistry.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
                }
            }
        """.trimIndent())
        srcDir.resolve("ItemRegistry.java").writeText("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ItemRegistry {
                public static final DeferredRegister<Item> ITEMS = null;
            }
        """.trimIndent())
        srcDir.resolve("DirtinessCapability.java").writeText("""
            package com.example;

            import com.modporter.generated.example.compat.LazyOptional;
            import net.minecraft.world.entity.player.Player;

            public final class DirtinessCapability {
                public static LazyOptional<DirtinessData> get(Player player) {
                    return LazyOptional.of(() -> new DirtinessData());
                }
            }
        """.trimIndent())
        srcDir.resolve("DirtinessData.java").writeText("""
            package com.example;

            public class DirtinessData {
                public void clean() {}
            }
        """.trimIndent())
        srcDir.resolve("CapabilityUseSurface.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.player.Player;

            public class CapabilityUseSurface {
                public void clean(Player player) {
                    com.modporter.generated.example.compat.LazyOptional.ofNullable(player.getCapability(DirtinessCapability.DIRTINESS, null)).ifPresent(DirtinessData::clean);
                }
            }
        """.trimIndent())
        srcDir.resolve("ExternalUuidModifierSurface.java").writeText("""
            package com.example;

            import java.util.UUID;
            import net.minecraft.world.entity.player.Player;

            public class ExternalUuidModifierSurface {
                private static final UUID HOT_BATH_TEMP_MODIFIER_UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

                public void apply(Player player) {
                    TemperatureUtil.addTemperatureModifier(player, 1.0D, HOT_BATH_TEMP_MODIFIER_UUID);
                }
            }
        """.trimIndent())
        srcDir.resolve("AbstractTypedEntityBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.BaseEntityBlock;
            import net.minecraft.world.level.block.SkullBlock;

            public abstract class AbstractTypedEntityBlock extends BaseEntityBlock {
                private final SkullBlock.Type type;

                protected AbstractTypedEntityBlock(SkullBlock.Type type, Properties properties) {
                    super(properties);
                    this.type = type;
                }

                public SkullBlock.Type getType() {
                    return this.type;
                }
            }
        """.trimIndent())
        srcDir.resolve("SkullCandleBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.SkullBlock;

            public class SkullCandleBlock extends AbstractTypedEntityBlock {
                public SkullCandleBlock(SkullBlock.Type type, Properties properties) {
                    super(type, properties);
                }
            }
        """.trimIndent())
        srcDir.resolve("StaticLiquidBlock.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.LiquidBlock;
            import net.minecraft.world.level.material.Fluids;

            public class StaticLiquidBlock extends LiquidBlock {
                public StaticLiquidBlock(Properties properties) {
                    super(Fluids.WATER, properties);
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)

        val record = srcDir.resolve("RecordAccessorSurface.java").readText()
        val comment = srcDir.resolve("CommentContainerSurface.java").readText()
        val duration = srcDir.resolve("UseDurationSurface.java").readText()
        val recipe = srcDir.resolve("RecipeSurface.java").readText()
        val containerScope = srcDir.resolve("ContainerScopeSurface.java").readText()
        val registry = srcDir.resolve("BlockRegistrySurface.java").readText()
        val capability = srcDir.resolve("CapabilityUseSurface.java").readText()
        val externalUuid = srcDir.resolve("ExternalUuidModifierSurface.java").readText()
        val skull = srcDir.resolve("SkullCandleBlock.java").readText()
        val liquid = srcDir.resolve("StaticLiquidBlock.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(record.contains("consumer.setColor(255, 255, 255, 255)"))
        assertTrue(record.contains("return definition.color();"))
        assertTrue(!record.contains("definition.setColor()"))
        assertTrue(comment.contains("return entities.size();"))
        assertTrue(!comment.contains("entities.getContainerSize()"))
        assertTrue(duration.contains("entity.getUseItem().getUseDuration(entity)"))
        assertTrue(duration.contains("item.getUseDuration(stack, null)"))
        assertTrue(recipe.contains("matches(CraftingInput container, Level level)"))
        assertTrue(recipe.contains("assemble(CraftingInput container, HolderLookup.Provider access)"))
        assertTrue(recipe.contains("container.size() > 0"))
        assertTrue(!recipe.contains("CraftingContainer"))
        assertTrue(containerScope.contains("return matrix.size();"))
        assertTrue(containerScope.contains("int count = matrix.getContainerSize();"))
        assertTrue(containerScope.contains("i < matrix.getContainerSize()"))
        assertTrue(registry.contains("DeferredHolder<Item, BlockItem> registerBlockItem"))
        assertTrue(capability.contains("DirtinessCapability.get(player).ifPresent(DirtinessData::clean)"))
        assertTrue(!capability.contains("LazyOptional.ofNullable(DirtinessCapability.get"))
        assertTrue(!capability.contains("DirtinessCapability.DIRTINESS"))
        assertTrue(externalUuid.contains("private static final UUID HOT_BATH_TEMP_MODIFIER_UUID = UUID.fromString"))
        assertTrue(externalUuid.contains("TemperatureUtil.addTemperatureModifier(player, 1.0D, HOT_BATH_TEMP_MODIFIER_UUID)"))
        assertTrue(!externalUuid.contains("ResourceLocation HOT_BATH_TEMP_MODIFIER_UUID"))
        assertTrue(skull.contains("simpleCodec(properties -> new SkullCandleBlock(this.getType(), properties))"))
        assertTrue(liquid.contains("public com.mojang.serialization.MapCodec<net.minecraft.world.level.block.LiquidBlock> codec()"))
    }

    @Test
    fun `does not migrate ordinary Objects equality to holder is without holder proof`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ResourceLocationEqualitySurface.java").writeText("""
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import org.jetbrains.annotations.Nullable;

            public class ResourceLocationEqualitySurface {
                private ResourceLocation materialBlockId;

                public void setMaterialBlockId(@Nullable ResourceLocation materialBlockId) {
                    if (java.util.Objects.equals(this.materialBlockId, materialBlockId)) {
                        return;
                    }
                    this.materialBlockId = materialBlockId;
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = srcDir.resolve("ResourceLocationEqualitySurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("java.util.Objects.equals(this.materialBlockId, materialBlockId)"))
        assertFalse(migrated.contains("java.util.materialBlockId.is"))
        assertFalse(migrated.contains("materialBlockId.is("))
    }

    @Test
    fun `block item handler capability uses source block entity level relation`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CapabilityLevelSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.core.Direction;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;

            public class CapabilityLevelSurface {
                public void caps(Level unrelated, Level level, BlockPos blockPos, Direction side) {
                    BlockEntity blockEntity = level.getBlockEntity(blockPos);
                    if (blockEntity != null) {
                        blockEntity.getCapability(Capabilities.ItemHandler.BLOCK, side).ifPresent(handler -> handler.getSlots());
                    }
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = srcDir.resolve("CapabilityLevelSurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("if (level instanceof ServerLevel modporterServerLevel)"), migrated)
        assertTrue(migrated.contains("BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, modporterServerLevel, blockPos, side)"), migrated)
        assertFalse(migrated.contains("if (unrelated instanceof ServerLevel"), migrated)
    }

    @Test
    fun `block item handler capability without level position source relation is not guessed`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CapabilityAmbiguousSurface.java").writeText("""
            package com.example;

            import net.minecraft.core.Direction;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.neoforged.neoforge.capabilities.Capabilities;

            public class CapabilityAmbiguousSurface {
                public void caps(Level level, BlockEntity blockEntity, Direction side) {
                    blockEntity.getCapability(Capabilities.ItemHandler.BLOCK, side).ifPresent(handler -> handler.getSlots());
                }
            }
        """.trimIndent())

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = srcDir.resolve("CapabilityAmbiguousSurface.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(migrated.contains("blockEntity.getCapability(Capabilities.ItemHandler.BLOCK, side).ifPresent(handler -> handler.getSlots());"), migrated)
        assertFalse(migrated.contains("BlockCapabilityCache.create"), migrated)
        assertFalse(migrated.contains("ENTITY_AUTOMATION"), migrated)
    }

    @Test
    fun `empty project returns empty results`() {
        val projectDir = tempDir.resolve("empty-project")
        projectDir.createDirectories()
        val pass = StructuralRefactorPass()
        val result = pass.analyze(projectDir)
        assertEquals(0, result.changeCount)
        assertTrue(result.errors.isEmpty())
    }
}
