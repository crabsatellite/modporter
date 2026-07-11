package com.modporter.transforms

import com.modporter.core.transforms.text.LegacyTagManagerMigration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyTagManagerMigrationTest {
    private val imports = """
        import net.minecraft.core.registries.BuiltInRegistries;
        import net.minecraft.tags.TagKey;
        import net.minecraft.world.item.Item;
        import net.neoforged.neoforge.registries.tags.ITag;
        import net.neoforged.neoforge.registries.tags.ITagManager;
    """.trimIndent()

    @Test
    fun `known empty direct iteration and materialized tags preserve item values`() {
        val source = """
            package example;
            $imports
            class TagUsers {
                boolean hidden(TagKey<Item> tag) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    return !tags.isKnownTagName(tag) || tags.getTag(tag).isEmpty();
                }
                void visit(TagKey<Item> tag) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    tags.getTag(tag).forEach(item -> use(item));
                }
                Item[] collect(TagKey<Item> tag) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    if (tags.isKnownTagName(tag)) {
                        ITag<Item> values = tags.getTag(tag);
                        if (!values.isEmpty()) {
                            Item[] result = new Item[values.size()];
                            int i = 0;
                            for (Item item : values) result[i++] = item;
                            return result;
                        }
                    }
                    return new Item[0];
                }
            }
        """.trimIndent()

        val migrated = LegacyTagManagerMigration().migrate(source)

        assertFalse(migrated.contains("ITagManager"), migrated)
        assertFalse(migrated.contains("ITag<Item>"), migrated)
        assertTrue(migrated.contains("!BuiltInRegistries.ITEM.getTagOrEmpty(tag).iterator().hasNext()"), migrated)
        assertTrue(migrated.contains("BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach"), migrated)
        assertTrue(migrated.contains("Item item = itemHolder.value();"), migrated)
        assertTrue(migrated.contains("java.util.List<Item> values"), migrated)
        assertTrue(migrated.contains(".map(net.minecraft.core.Holder::value).toList()"), migrated)
        assertTrue(migrated.contains("BuiltInRegistries.ITEM.getTag(tag).isPresent()"), migrated)
    }

    @Test
    fun `unsupported manager consumers hard fail`() {
        val source = """
            package example;
            $imports
            class UnsupportedTags {
                void useTag(TagKey<Item> tag) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    consume(tags.getTag(tag));
                }
            }
        """.trimIndent()

        assertFailsWith<IllegalStateException> {
            LegacyTagManagerMigration().migrate(source)
        }
        assertTrue(source.contains("consume(tags.getTag(tag));"))
    }

    @Test
    fun `materialized legacy tags reject mutating consumers`() {
        val source = """
            package example;
            $imports
            class MutatingTags {
                void mutate(TagKey<Item> tag) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                    ITag<Item> values = tags.getTag(tag);
                    values.clear();
                }
            }
        """.trimIndent()

        val error = assertFailsWith<IllegalStateException> {
            LegacyTagManagerMigration().migrate(source)
        }
        assertTrue(error.message.orEmpty().contains("non-read-only uses"))
    }

    @Test
    fun `custom manager owners do not satisfy exact import proof`() {
        val source = """
            package example;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;
            class ITagManager<T> {}
            class CustomTags {
                void visit(TagKey<Item> tag) {
                    ITagManager<Item> tags = BuiltInRegistries.ITEM.tags();
                }
            }
        """.trimIndent()

        assertFailsWith<IllegalStateException> {
            LegacyTagManagerMigration().migrate(source)
        }
    }
}
