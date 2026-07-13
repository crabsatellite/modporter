package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.WrappedSerializableEntityCapabilityMigration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WrappedSerializableEntityCapabilityMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `wrapped serializable entity capability migrates as a closed attachment graph`() {
        writeClosedFixture()

        val result = StructuralRefactorPass().apply(tempDir)
        val provider = source("com/example/ControllerCapabilities.java").readText()
        val eventOwner = source("com/example/CommonEvents.java").readText()
        val main = source("com/example/ExampleMod.java").readText()
        val query = source("com/example/ControllerUser.java").readText()
        val directQuery = source("com/example/DirectControllerUser.java").readText()
        val unrelated = source("com/example/OtherCapability.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(result.changes.any { it.ruleId == "struct-wrapped-serializable-entity-attachment" })
        assertTrue(result.changes.any { it.ruleId == "struct-wrapped-serializable-entity-attachment-uses" })
        assertTrue(provider.contains("DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, \"example\")"), provider)
        assertTrue(provider.contains("ATTACHMENT_TYPES.register(\"controller\""), provider)
        assertTrue(provider.contains("AttachmentType.builder(Controller::empty)"), provider)
        assertTrue(provider.contains("new Controller((AbstractMinecart) holder)"), provider)
        assertTrue(provider.contains("value.deserializeNBT(provider, tag)"), provider)
        assertTrue(provider.contains("return value.serializeNBT(provider)"), provider)
        assertTrue(provider.contains("public static void attach(EntityJoinLevelEvent event)"), provider)
        assertTrue(provider.contains("if (!event.loadedFromDisk()) {"), provider)
        assertTrue(provider.contains("entity.setData(CONTROLLER, value)"), provider)
        val loadedGuard = provider.indexOf("if (!event.loadedFromDisk()) {")
        val attachmentWrite = provider.indexOf("entity.setData(CONTROLLER, value)", loadedGuard)
        val loadedGuardEnd = provider.indexOf("\n        }", attachmentWrite)
        val preservedAttachEffect = provider.indexOf("entity.setInvulnerable(false);", loadedGuardEnd)
        assertTrue(loadedGuard < attachmentWrite, provider)
        assertTrue(attachmentWrite < loadedGuardEnd, provider)
        assertTrue(loadedGuardEnd < preservedAttachEffect, provider)
        assertTrue(provider.contains("onRemoved(event.getLevel(), typedEntity)"), provider)
        assertTrue(provider.contains("LazyOptional.ofNullable(cart.getData(CONTROLLER.get()))"), provider)
        assertTrue(provider.contains("filter(value -> value != Controller.empty())"), provider)
        assertFalse(provider.contains("CapabilityManager"), provider)
        assertFalse(provider.contains("ICapabilitySerializable"), provider)
        assertFalse(provider.contains("RemovalListener"), provider)
        assertFalse(provider.contains("addListener"), provider)
        assertTrue(eventOwner.contains("attachController(EntityJoinLevelEvent event)"), eventOwner)
        assertTrue(eventOwner.contains("onControllerCapabilitiesEntityLeave(EntityLeaveLevelEvent event)"), eventOwner)
        assertFalse(eventOwner.contains("AttachCapabilitiesEvent"), eventOwner)
        assertTrue(main.contains("ControllerCapabilities.registerAttachments(modEventBus);"), main)
        assertTrue(query.contains("minecart.getData(ControllerCapabilities.CONTROLLER.get())"), query)
        assertTrue(query.contains("filter(value -> value != Controller.empty())"), query)
        assertTrue(directQuery.contains("import net.neoforged.neoforge.common.util.LazyOptional;"), directQuery)
        assertTrue(directQuery.contains("LazyOptional.ofNullable(minecart.getData(ControllerCapabilities.CONTROLLER.get()))"), directQuery)
        assertTrue(unrelated.contains("import com.modporter.generated.example.compat.Capability;"), unrelated)
        assertTrue(unrelated.contains("Capability<String> VALUE"), unrelated)
    }

    @Test
    fun `wrapped serializable entity capability fails closed without a sentinel factory`() {
        writeClosedFixture(includeSentinel = false)
        val providerFile = source("com/example/ControllerCapabilities.java")

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("requires one exact static sentinel factory") }, "errors=${result.errors}")
        assertTrue(result.changes.none { it.ruleId.startsWith("struct-wrapped-serializable-entity-attachment") })
        val after = providerFile.readText()
        assertTrue(after.contains("CapabilityManager.get"), after)
        assertTrue(after.contains("AttachCapabilitiesEvent<Entity>"), after)
    }

    @Test
    fun `wrapped serializable entity capability fails closed when serializer mutates extra state`() {
        writeClosedFixture(extraSerializeStatement = true)
        val providerFile = source("com/example/ControllerCapabilities.java")

        val result = StructuralRefactorPass().apply(tempDir)

        assertTrue(result.errors.any { it.contains("serializeNBT must delegate only") }, "errors=${result.errors}")
        assertTrue(result.changes.none { it.ruleId.startsWith("struct-wrapped-serializable-entity-attachment") })
        val after = providerFile.readText()
        assertTrue(after.contains("CapabilityManager.get"), after)
        assertTrue(after.contains("handler.sendData()"), after)
    }

    @Test
    fun `wrapped serializable entity capability fails closed on extra pre-listener behavior`() {
        writeClosedFixture()
        val providerFile = source("com/example/ControllerCapabilities.java")
        val before = providerFile.readText().replace(
            "event.addListener(() -> {",
            "entity.setGlowingTag(true);\n        event.addListener(() -> {"
        )
        providerFile.writeText(before)

        val errors = mutableListOf<String>()
        val changes = WrappedSerializableEntityCapabilityMigration().apply(tempDir, dryRun = false, errors)

        assertTrue(errors.any { it.contains("behavior outside the proven provider setup") }, "errors=$errors")
        assertTrue(changes.isEmpty())
        assertTrue(providerFile.readText() == before)
        assertFalse(source("com/example/ExampleMod.java").readText().contains("registerAttachments"))
    }

    @Test
    fun `wrapped serializable entity capability fails closed on listener side effects`() {
        writeClosedFixture()
        val providerFile = source("com/example/ControllerCapabilities.java")
        val before = providerFile.readText().replace(
            "capability.optional.invalidate();",
            "capability.optional.invalidate();\n            capability.handler.sendData();"
        )
        providerFile.writeText(before)

        val errors = mutableListOf<String>()
        val changes = WrappedSerializableEntityCapabilityMigration().apply(tempDir, dryRun = false, errors)

        assertTrue(errors.any { it.contains("not an exact wrapper invalidation") }, "errors=$errors")
        assertTrue(changes.isEmpty())
        assertTrue(providerFile.readText() == before)
    }

    @Test
    fun `wrapped serializable entity capability fails closed with ambiguous mod entrypoints`() {
        writeClosedFixture()
        val providerFile = source("com/example/ControllerCapabilities.java")
        val before = providerFile.readText()
        write("com/example/SecondEntry.java", """
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.ID)
            public class SecondEntry {
                public SecondEntry(IEventBus modEventBus) {
                }
            }
        """)

        val errors = mutableListOf<String>()
        val changes = WrappedSerializableEntityCapabilityMigration().apply(tempDir, dryRun = false, errors)

        assertTrue(errors.any { it.contains("requires one @Mod entrypoint") }, "errors=$errors")
        assertTrue(changes.isEmpty())
        assertTrue(providerFile.readText() == before)
        assertFalse(source("com/example/ExampleMod.java").readText().contains("registerAttachments"))
        assertFalse(source("com/example/SecondEntry.java").readText().contains("registerAttachments"))
    }

    @Test
    fun `wrapped serializable entity capability fails closed on unsupported field use`() {
        writeClosedFixture()
        val providerFile = source("com/example/ControllerCapabilities.java")
        val before = providerFile.readText()
        val userFile = source("com/example/ControllerUser.java")
        userFile.writeText(userFile.readText().replace(
            "value.ifPresent(Controller::sendData);",
            "value.ifPresent(Controller::sendData);\n        Object raw = ControllerCapabilities.CONTROLLER;"
        ))

        val errors = mutableListOf<String>()
        val changes = WrappedSerializableEntityCapabilityMigration().apply(tempDir, dryRun = false, errors)

        assertTrue(errors.any { it.contains("has an unsupported use") }, "errors=$errors")
        assertTrue(changes.isEmpty())
        assertTrue(providerFile.readText() == before)
        assertTrue(userFile.readText().contains("Object raw = ControllerCapabilities.CONTROLLER;"))
    }

    @Test
    fun `wrapped serializable entity capability applies no candidates when one graph is incomplete`() {
        writeClosedFixture()
        val primaryProvider = source("com/example/ControllerCapabilities.java")
        val primaryBefore = primaryProvider.readText()
        write(
            "com/example/Secondary.java",
            source("com/example/Controller.java").readText().replace("Controller", "Secondary")
        )
        write(
            "com/example/SecondaryCapabilities.java",
            primaryBefore
                .replace("ControllerCapabilities", "SecondaryCapabilities")
                .replace("Controller", "Secondary")
                .replace("CONTROLLER", "SECONDARY")
                .replace("\"controller\"", "\"secondary\"")
        )

        val errors = mutableListOf<String>()
        val changes = WrappedSerializableEntityCapabilityMigration().apply(tempDir, dryRun = false, errors)

        assertTrue(errors.any { it.contains("requires one exact @EventBusSubscriber delegate") }, "errors=$errors")
        assertTrue(changes.isEmpty())
        assertTrue(primaryProvider.readText() == primaryBefore)
        assertTrue(source("com/example/SecondaryCapabilities.java").readText().contains("CapabilityManager.get"))
        assertFalse(source("com/example/ExampleMod.java").readText().contains("registerAttachments"))
    }

    private fun writeClosedFixture(includeSentinel: Boolean = true, extraSerializeStatement: Boolean = false) {
        write("com/example/ExampleMod.java", """
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.ID)
            public class ExampleMod {
                public static final String ID = "example";

                public ExampleMod(IEventBus modEventBus, ModContainer container) {
                }

                public static net.minecraft.resources.ResourceLocation asResource(String path) {
                    return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ID, path);
                }
            }
        """)
        val sentinel = if (includeSentinel) """
                private static Controller EMPTY;

                public static Controller empty() {
                    return EMPTY != null ? EMPTY : (EMPTY = new Controller(null));
                }
        """ else ""
        write("com/example/Controller.java", """
            package com.example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.neoforged.neoforge.common.util.INBTSerializable;

            public class Controller implements INBTSerializable<CompoundTag> {
                private final AbstractMinecart minecart;
            $sentinel
                public Controller(AbstractMinecart minecart) {
                    this.minecart = minecart;
                }

                @Override
                public CompoundTag serializeNBT(HolderLookup.Provider provider) {
                    return new CompoundTag();
                }

                @Override
                public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
                }

                public void sendData() {
                }
            }
        """)
        val extra = if (extraSerializeStatement) "handler.sendData();" else ""
        write("com/example/ControllerCapabilities.java", """
            package com.example;

            import net.minecraft.core.Direction;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.capabilities.Capability;
            import net.neoforged.neoforge.capabilities.CapabilityManager;
            import net.neoforged.neoforge.capabilities.CapabilityToken;
            import net.neoforged.neoforge.capabilities.ICapabilitySerializable;
            import net.neoforged.neoforge.common.util.LazyOptional;
            import net.neoforged.neoforge.common.util.NonNullConsumer;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;

            public class ControllerCapabilities implements ICapabilitySerializable<CompoundTag> {
                public static class RemovalListener implements NonNullConsumer<LazyOptional<Controller>> {
                    private Level level;
                    private AbstractMinecart minecart;

                    public RemovalListener(Level level, AbstractMinecart minecart) {
                        this.level = level;
                        this.minecart = minecart;
                    }

                    @Override
                    public void accept(LazyOptional<Controller> ignored) {
                        onRemoved(level, minecart);
                    }
                }

                public static Capability<Controller> CONTROLLER = CapabilityManager.get(new CapabilityToken<>() {});

                public static void tick(Level level, AbstractMinecart cart) {
                    LazyOptional<Controller> optional = cart.getCapability(CONTROLLER);
                    optional.addListener(new RemovalListener(level, cart));
                    optional.ifPresent(Controller::sendData);
                }

                private static void onRemoved(Level level, AbstractMinecart minecart) {
                }

                public static void attach(AttachCapabilitiesEvent<Entity> event) {
                    Entity entity = event.getObject();
                    if (!(entity instanceof AbstractMinecart))
                        return;
                    ControllerCapabilities capability = new ControllerCapabilities((AbstractMinecart) entity);
                    ResourceLocation id = ExampleMod.asResource("controller");
                    event.addCapability(id, capability);
                    event.addListener(() -> {
                        if (capability.optional.isPresent())
                            capability.optional.invalidate();
                    });
                    entity.setInvulnerable(false);
                }

                private final LazyOptional<Controller> optional;
                private Controller handler;

                public ControllerCapabilities(AbstractMinecart minecart) {
                    handler = new Controller(minecart);
                    optional = LazyOptional.of(() -> handler);
                }

                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    if (capability == CONTROLLER)
                        return this.optional.cast();
                    return LazyOptional.empty();
                }

                @Override
                public CompoundTag serializeNBT(HolderLookup.Provider provider) {
                    $extra
                    return handler.serializeNBT(provider);
                }

                @Override
                public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
                    handler.deserializeNBT(provider, tag);
                }
            }
        """)
        write("com/example/CommonEvents.java", """
            package com.example;

            import net.minecraft.world.entity.Entity;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.event.AttachCapabilitiesEvent;
            import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
            import net.neoforged.fml.common.EventBusSubscriber;

            @EventBusSubscriber
            public class CommonEvents {
                @SubscribeEvent
                public static void existingJoinHandler(EntityJoinLevelEvent event) {
                }

                @SubscribeEvent
                public static void attachController(AttachCapabilitiesEvent<Entity> event) {
                    ControllerCapabilities.attach(event);
                }
            }
        """)
        write("com/example/ControllerUser.java", """
            package com.example;

            import net.minecraft.world.entity.vehicle.AbstractMinecart;
            import net.neoforged.neoforge.common.util.LazyOptional;

            public class ControllerUser {
                public static void use(AbstractMinecart minecart) {
                    LazyOptional<Controller> value = minecart.getCapability(ControllerCapabilities.CONTROLLER);
                    value.ifPresent(Controller::sendData);
                }
            }
        """)
        write("com/example/DirectControllerUser.java", """
            package com.example;

            import net.minecraft.world.entity.vehicle.AbstractMinecart;

            public class DirectControllerUser {
                public static void use(AbstractMinecart minecart) {
                    minecart.getCapability(ControllerCapabilities.CONTROLLER).ifPresent(Controller::sendData);
                }
            }
        """)
        write("com/example/OtherCapability.java", """
            package com.example;

            import com.modporter.generated.example.compat.Capability;

            public class OtherCapability {
                public static Capability<String> VALUE;
            }
        """)
    }

    private fun source(relative: String): Path = tempDir.resolve("src/main/java").resolve(relative)

    private fun write(relative: String, content: String) {
        val file = source(relative)
        file.parent.createDirectories()
        file.writeText(content.trimIndent())
    }
}
