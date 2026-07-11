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

class LegacyCriterionTriggerHierarchyMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generic criterion hierarchy preserves dispatch and test logic`() {
        val source = tempDir.resolve("src/main/java/example")
        source.createDirectories()
        source.resolve("BaseTrigger.java").writeText(
            """
            package example;
            import java.util.List;
            import java.util.Map;
            import java.util.Set;
            import java.util.function.Supplier;
            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
            import net.minecraft.advancements.critereon.ContextAwarePredicate;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.PlayerAdvancements;
            import net.minecraft.server.level.ServerPlayer;

            public abstract class BaseTrigger<T extends BaseTrigger.Instance> implements CriterionTrigger<T> {
                private final ResourceLocation id;
                protected final Map<PlayerAdvancements, Set<Listener<T>>> listeners = null;
                protected BaseTrigger(ResourceLocation id) { this.id = id; }
                @Override public ResourceLocation getId() { return id; }
                protected void trigger(ServerPlayer player, List<Supplier<Object>> values) {
                    for (Listener<T> listener : listeners.get(player.getAdvancements())) {
                        if (listener.getTriggerInstance().test(values)) listener.run(player.getAdvancements());
                    }
                }
                public abstract static class Instance extends AbstractCriterionTriggerInstance {
                    public Instance(ResourceLocation id, ContextAwarePredicate predicate) { super(id, predicate); }
                    protected abstract boolean test(List<Supplier<Object>> values);
                }
            }
            """.trimIndent()
        )
        source.resolve("ConcreteTrigger.java").writeText(
            """
            package example;
            import java.util.List;
            import java.util.function.Supplier;
            import com.google.gson.JsonObject;
            import net.minecraft.advancements.critereon.ContextAwarePredicate;
            import net.minecraft.advancements.critereon.DeserializationContext;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.level.ServerPlayer;

            public class ConcreteTrigger extends BaseTrigger<ConcreteTrigger.Instance> {
                public ConcreteTrigger(ResourceLocation id) { super(id); }
                public Instance createInstance(JsonObject json, DeserializationContext context) { return new Instance(getId()); }
                public void trigger(ServerPlayer player) { super.trigger(player, null); }
                public Instance instance() { return new Instance(getId()); }
                public static class Instance extends BaseTrigger.Instance {
                    public Instance(ResourceLocation id) { super(id, ContextAwarePredicate.ANY); }
                    @Override protected boolean test(List<Supplier<Object>> values) { return values == null; }
                }
            }
            """.trimIndent()
        )
        source.resolve("IntermediateBase.java").writeText(
            """
            package example;
            import java.util.List;
            import java.util.function.Supplier;
            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.critereon.ContextAwarePredicate;
            import net.minecraft.advancements.critereon.SimpleCriterionTrigger.SimpleInstance;
            import net.minecraft.resources.ResourceLocation;

            public abstract class IntermediateBase<T extends IntermediateBase.Instance> implements CriterionTrigger<T> {
                public abstract static class Instance extends SimpleCriterionTrigger.SimpleInstance {
                    public Instance(ResourceLocation id, ContextAwarePredicate predicate) { super(id, predicate); }
                    protected abstract boolean test(List<Supplier<Object>> values);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val base = source.resolve("BaseTrigger.java").readText()
        val child = source.resolve("ConcreteTrigger.java").readText()
        val intermediate = source.resolve("IntermediateBase.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(base.contains("abstract class BaseTrigger<T extends BaseTrigger.Instance>"), base)
        assertTrue(base.contains("listener.trigger().test(values)"), base)
        assertTrue(base.contains("implements SimpleInstance"), base)
        assertFalse(base.contains("AbstractCriterionTriggerInstance"), base)
        assertTrue(child.contains("Codec<Instance> codec()"), child)
        assertTrue(child.contains("Optional<ContextAwarePredicate> player"), child)
        assertTrue(child.contains("return values == null;"), child)
        assertTrue(child.contains("super.trigger(player, null);"), child)
        assertFalse(child.contains("createInstance(JsonObject"), child)
        assertFalse(child.contains("new Instance(getId())"), child)
        assertTrue(intermediate.contains("implements SimpleInstance"), intermediate)
        assertFalse(intermediate.contains("extends SimpleCriterionTrigger.SimpleInstance"), intermediate)
        assertFalse(intermediate.contains("super(id, predicate)"), intermediate)
    }
}
