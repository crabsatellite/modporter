package com.modporter.integration

import com.modporter.mapping.MappingDatabase
import com.modporter.resources.ResourceMigrationPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceMigrationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun setupResourceProject(): Path {
        val projectDir = tempDir.resolve("resmod")
        val resourceDir = projectDir.resolve("src/main/resources")

        // mods.toml
        resourceDir.resolve("META-INF").createDirectories()
        resourceDir.resolve("META-INF/mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="resmod"
            [[dependencies.resmod]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
        """.trimIndent())

        // Data folders with plural names
        val dataDir = resourceDir.resolve("data/resmod")
        dataDir.resolve("tags/blocks").createDirectories()
        dataDir.resolve("tags/items").createDirectories()
        dataDir.resolve("tags/entity_types").createDirectories()
        dataDir.resolve("recipes").createDirectories()
        dataDir.resolve("loot_tables/blocks").createDirectories()
        dataDir.resolve("advancements").createDirectories()

        // Sample files in those directories
        dataDir.resolve("tags/items/my_tag.json").writeText("""{"values":["resmod:item"]}""")
        dataDir.resolve("recipes/my_recipe.json").writeText("""{"type":"minecraft:crafting_shaped"}""")
        dataDir.resolve("loot_tables/blocks/my_block.json").writeText("""{"type":"minecraft:block"}""")
        dataDir.resolve("advancements/root.json").writeText("""{"criteria":{}}""")

        // pack.mcmeta
        resourceDir.resolve("pack.mcmeta").writeText("""{"pack":{"pack_format":15,"description":"Test"}}""")

        return projectDir
    }

    @Test
    fun `renames mods toml to neoforge mods toml`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val neoToml = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml")
        val oldToml = projectDir.resolve("src/main/resources/META-INF/mods.toml")

        assertTrue(neoToml.exists(), "neoforge.mods.toml should exist")
        assertFalse(oldToml.exists(), "Old mods.toml should be removed")
    }

    @Test
    fun `updates mods toml content`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val content = projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(content.contains("neoforge"), "Should reference neoforge instead of forge")
    }

    @Test
    fun `custom enchantment resource keys require source derived data json`() {
        val projectDir = tempDir.resolve("missing-enchantment-data")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public final class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final net.minecraft.resources.ResourceKey<Enchantment> FIRE_REACT =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "fire_react"));
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)
        val generated = projectDir.resolve("src/generated/resources/data/example/enchantment/fire_react.json")

        assertTrue(result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON") })
        assertTrue(result.changes.any { it.ruleId == "res-custom-enchantment-data" })
        assertFalse(generated.exists(), "Resource migration must not create default custom enchantment JSON")
    }

    @Test
    fun `custom enchantment resource keys do not infer mod id owner from java file names`() {
        val projectDir = tempDir.resolve("ambiguous-enchantment-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public static final String MODID = "example";
        """.trimIndent())
        srcDir.resolve("ModEnchantments.java").writeText("""
            package com.example;

            import net.minecraft.world.item.enchantment.Enchantment;

            public final class ModEnchantments {
                public static final net.minecraft.resources.ResourceKey<Enchantment> QUALIFIED =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "qualified"));
                public static final net.minecraft.resources.ResourceKey<Enchantment> SIMPLE =
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "simple"));
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertFalse(
            result.changes.any { it.ruleId == "res-custom-enchantment-data" },
            "Unsupported mod id constant ownership must not produce custom enchantment data requirements"
        )
        assertFalse(
            result.errors.any { it.contains("Missing source-derived data-driven custom enchantment JSON") },
            result.errors.joinToString("\n")
        )
    }

    @Test
    fun `renames data folders`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")

        // New singular folders should exist
        assertTrue(dataDir.resolve("tags/item").exists(), "tags/items -> tags/item")
        assertTrue(dataDir.resolve("tags/block").exists(), "tags/blocks -> tags/block")
        assertTrue(dataDir.resolve("recipe").exists(), "recipes -> recipe")
        assertTrue(dataDir.resolve("loot_table").exists(), "loot_tables -> loot_table")
        assertTrue(dataDir.resolve("advancement").exists(), "advancements -> advancement")
    }

    @Test
    fun `preserves files during folder rename`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val dataDir = projectDir.resolve("src/main/resources/data/resmod")

        // Files should be preserved in renamed folders
        assertTrue(dataDir.resolve("tags/item/my_tag.json").exists(), "Tag file should be preserved")
        assertTrue(dataDir.resolve("recipe/my_recipe.json").exists(), "Recipe file should be preserved")
    }

    @Test
    fun `folder rename merges plural tags into existing singular tags`() {
        val projectDir = setupResourceProject()
        val dataDir = projectDir.resolve("src/main/resources/data/resmod")
        dataDir.resolve("tags/item/enchantable").createDirectories()
        dataDir.resolve("tags/item/enchantable/tool.json").writeText("""{"values":["resmod:tool"]}""")

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        assertTrue(result.changes.any { it.ruleId == "res-folder-rename" })
        assertTrue(dataDir.resolve("tags/item/my_tag.json").exists(), "Plural item tag should be merged")
        assertTrue(dataDir.resolve("tags/item/enchantable/tool.json").exists(), "Existing singular tag should remain")
        assertFalse(dataDir.resolve("tags/items").exists(), "Old plural item tag directory should be removed")
    }

    @Test
    fun `common tool tag file paths migrate to 1_21 singular paths`() {
        val projectDir = setupResourceProject()
        val dataDir = projectDir.resolve("src/generated/resources/data/c")
        dataDir.resolve("tags/items/tools").createDirectories()
        dataDir.resolve("tags/items/tools/bows.json").writeText("""{"values":["resmod:bow"]}""")
        dataDir.resolve("tags/items/tools/shields.json").writeText("""{"values":["resmod:shield"]}""")

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        assertTrue(result.changes.any { it.ruleId == "res-common-tag-path" })
        assertTrue(dataDir.resolve("tags/item/tools/bow.json").exists(), "tools/bows should become tools/bow")
        assertTrue(dataDir.resolve("tags/item/tools/shield.json").exists(), "tools/shields should become tools/shield")
        assertFalse(dataDir.resolve("tags/item/tools/bows.json").exists())
        assertFalse(dataDir.resolve("tags/item/tools/shields.json").exists())
    }

    @Test
    fun `recipe string result is migrated to id object`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("string_result.json").writeText("""
            {"type":"minecraft:smelting","ingredient":{"item":"minecraft:water_bucket"},"result":"resmod:hot_water_bucket"}
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/string_result.json").readText()
        assertTrue(recipe.contains(""""result": {"""), "Recipe result should be an object")
        assertTrue(recipe.contains(""""id": "resmod:hot_water_bucket""""))
        assertFalse(recipe.contains(""""result":"resmod:hot_water_bucket""""))
    }

    @Test
    fun `custom recipe data fields migrate only from source derived serializer codecs`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/resmod")
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        javaDir.createDirectories()
        recipeDir.createDirectories()

        javaDir.resolve("ResMod.java").writeText("""
            package resmod;

            public final class ResMod {
                public static final String MODID = "resmod";
            }
        """.trimIndent())
        javaDir.resolve("ModRecipeSerializers.java").writeText("""
            package resmod;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public final class ModRecipeSerializers {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, ResMod.MODID);
                public static final DeferredHolder<RecipeSerializer<?>, HeatingRecipe.Serializer> HEATING = RECIPE_SERIALIZERS.register("heating", HeatingRecipe.Serializer::new);
                public static final DeferredHolder<RecipeSerializer<?>, IncubationRecipe.Serializer> INCUBATION = RECIPE_SERIALIZERS.register("incubation", IncubationRecipe.Serializer::new);
                public static final DeferredHolder<RecipeSerializer<?>, PlainRecipe.Serializer> PLAIN = RECIPE_SERIALIZERS.register("plain", PlainRecipe.Serializer::new);
            }
        """.trimIndent())
        javaDir.resolve("AbstractHeatingSerializer.java").writeText("""
            package resmod;

            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public class AbstractHeatingSerializer<T> implements RecipeSerializer<T> {
                private final MapCodec<T> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    ItemStack.CODEC.fieldOf("result").forGetter(recipe -> null)
                ).apply(instance, value -> null));
            }
        """.trimIndent())
        javaDir.resolve("HeatingRecipe.java").writeText("""
            package resmod;

            public final class HeatingRecipe {
                public static class Serializer extends AbstractHeatingSerializer<HeatingRecipe> {
                }
            }
        """.trimIndent())
        javaDir.resolve("IncubationRecipe.java").writeText("""
            package resmod;

            import com.mojang.serialization.MapCodec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.crafting.RecipeSerializer;

            public final class IncubationRecipe {
                public static class Serializer implements RecipeSerializer<IncubationRecipe> {
                    private static final MapCodec<IncubationRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        CompoundTag.CODEC.optionalFieldOf("tag").forGetter(recipe -> null)
                    ).apply(instance, value -> null));
                }
            }
        """.trimIndent())
        javaDir.resolve("PlainRecipe.java").writeText("""
            package resmod;

            import net.minecraft.world.item.crafting.RecipeSerializer;

            public final class PlainRecipe {
                public static class Serializer implements RecipeSerializer<PlainRecipe> {
                }
            }
        """.trimIndent())
        recipeDir.resolve("heating.json").writeText("""
            {
              "type": "resmod:heating",
              "ingredient": {
                "item": "minecraft:stone"
              },
              "result": "resmod:heated_stone"
            }
        """.trimIndent())
        recipeDir.resolve("incubation.json").writeText("""
            {
              "type": "resmod:incubation",
              "ingredient": {
                "item": "resmod:egg"
              },
              "tag": "{Hungry:1b,IsBaby:1b,MoaType:\"resmod:white\",Nested:{Value:2s},List:[1b,2b]}"
            }
        """.trimIndent())
        recipeDir.resolve("plain.json").writeText("""
            {
              "type": "resmod:plain",
              "result": "resmod:plain_result",
              "tag": "{ShouldStay:1b}"
            }
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val heating = projectDir.resolve("src/generated/resources/data/resmod/recipe/heating.json").readText()
        val incubation = projectDir.resolve("src/generated/resources/data/resmod/recipe/incubation.json").readText()
        val plain = projectDir.resolve("src/generated/resources/data/resmod/recipe/plain.json").readText()

        assertTrue(result.changes.any { it.ruleId == "res-recipe-result-entry-id" })
        assertTrue(result.changes.any { it.ruleId == "res-recipe-snbt-compound-tag" })
        assertTrue(heating.contains(""""result": {"""))
        assertTrue(heating.contains(""""id": "resmod:heated_stone""""))
        assertTrue(incubation.contains(""""tag": {"""))
        assertTrue(incubation.contains(""""Hungry": 1"""))
        assertTrue(incubation.contains(""""IsBaby": 1"""))
        assertTrue(incubation.contains(""""MoaType": "resmod:white""""))
        assertTrue(incubation.contains(""""Nested": {"""))
        assertTrue(incubation.contains(""""Value": 2"""))
        assertTrue(incubation.contains(""""List": ["""))
        assertTrue(plain.contains(""""result": "resmod:plain_result""""))
        assertTrue(plain.contains(""""tag": "{ShouldStay:1b}""""))
    }

    @Test
    fun `farmers delight cutting result array entries migrate to item stack object`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("cutting_result_array.json").writeText("""
            {
              "type": "farmersdelight:cutting",
              "ingredients": [
                {
                  "item": "resmod:cabbage"
                }
              ],
              "result": [
                {
                  "item": "resmod:sliced_cabbage",
                  "count": 2
                }
              ],
              "tool": {
                "tag": "forge:tools/knives"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/cutting_result_array.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-recipe-farmersdelight-cutting-result-stack" },
            "Farmers Delight cutting result stack migration rule should be recorded"
        )
        assertTrue(recipe.contains(""""item": "resmod:cabbage""""))
        assertTrue(recipe.contains(""""item": {"""))
        assertTrue(recipe.contains(""""id": "resmod:sliced_cabbage""""))
        assertTrue(recipe.contains(""""count": 2"""))
        assertTrue(recipe.contains(""""tag": "c:tools/knife""""))
        assertFalse(recipe.contains(""""item": "resmod:sliced_cabbage""""))
        assertFalse(recipe.contains("forge:tools/knives"))
    }

    @Test
    fun `conditional farmers delight cutting recipe is unwrapped and result stack migrated`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("conditional_cutting.json").writeText("""
            {
              "type": "neoforge:conditional",
              "recipes": [
                {
                  "neoforge:conditions": [
                    {
                      "type": "neoforge:mod_loaded",
                      "modid": "farmersdelight"
                    }
                  ],
                  "recipe": {
                    "type": "farmersdelight:cutting",
                    "ingredients": [
                      {
                        "item": "resmod:cabbage"
                      }
                    ],
                    "tool": {
                      "tag": "forge:tools/knives"
                    },
                    "result": [
                      {
                        "item": "resmod:sliced_cabbage",
                        "count": 2
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/conditional_cutting.json").readText()
        assertTrue(recipe.contains(""""neoforge:conditions":"""))
        assertTrue(recipe.contains(""""type": "farmersdelight:cutting""""))
        assertTrue(recipe.contains(""""item": {"""))
        assertTrue(recipe.contains(""""id": "resmod:sliced_cabbage""""))
        assertTrue(recipe.contains(""""count": 2"""))
        assertTrue(recipe.contains(""""tag": "c:tools/knife""""))
        assertFalse(recipe.contains(""""type": "neoforge:conditional""""))
        assertFalse(recipe.contains(""""item": "resmod:sliced_cabbage""""))
    }

    @Test
    fun `single conditional recipe is unwrapped with top level conditions`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        recipeDir.createDirectories()
        recipeDir.resolve("conditional_table.json").writeText("""
            {
              "type": "neoforge:conditional",
              "recipes": [
                {
                  "neoforge:conditions": [
                    {
                      "type": "neoforge:mod_loaded",
                      "modid": "farmersdelight"
                    }
                  ],
                  "recipe": {
                    "type": "minecraft:crafting_shapeless",
                    "ingredients": [
                      {
                        "tag": "forge:lumber"
                      }
                    ],
                    "result": {
                      "item": "resmod:conditional_table"
                    }
                  }
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/conditional_table.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-recipe-unwrap-single-conditional" },
            "Conditional recipe unwrap rule should be recorded"
        )
        assertTrue(recipe.contains(""""neoforge:conditions":"""))
        assertTrue(recipe.contains(""""type": "minecraft:crafting_shapeless""""))
        assertTrue(recipe.contains(""""tag": "c:lumber""""))
        assertTrue(recipe.contains(""""id": "resmod:conditional_table""""))
        assertFalse(recipe.contains(""""type": "neoforge:conditional""""))
        assertFalse(recipe.contains(""""recipes":"""))
        assertFalse(recipe.contains(""""recipe":"""))
    }

    @Test
    fun `loot table conditions remain vanilla conditions`() {
        val projectDir = setupResourceProject()
        val lootDir = projectDir.resolve("src/main/resources/data/resmod/loot_tables/blocks")
        lootDir.resolve("explosive_block.json").writeText("""
            {
              "type": "minecraft:block",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "resmod:explosive_block"
                    }
                  ],
                  "conditions": [
                    {
                      "condition": "minecraft:survives_explosion"
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val lootTable = projectDir.resolve("src/main/resources/data/resmod/loot_table/blocks/explosive_block.json").readText()
        assertTrue(lootTable.contains(""""conditions""""))
        assertTrue(lootTable.contains(""""condition": "minecraft:survives_explosion""""))
        assertFalse(lootTable.contains(""""neoforge:conditions""""))
    }

    @Test
    fun `global loot modifier conditions use loot condition codec wrapper key`() {
        val projectDir = setupResourceProject()
        val lootModifierDir = projectDir.resolve("src/main/resources/data/resmod/loot_modifiers")
        lootModifierDir.createDirectories()
        lootModifierDir.resolve("grass_seeds.json").writeText("""
            {
              "type": "resmod:grass_drops",
              "neoforge:conditions": [
                {
                  "condition": "minecraft:random_chance",
                  "chance": 0.0625
                },
                {
                  "condition": "minecraft:inverted",
                  "term": {
                    "condition": "minecraft:match_tool",
                    "predicate": {
                      "items": [
                        "minecraft:shears"
                      ]
                    }
                  }
                },
                {
                  "condition": "block_state_property",
                  "block": "minecraft:grass"
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lootModifier = projectDir.resolve("src/main/resources/data/resmod/loot_modifiers/grass_seeds.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-glm-loot-conditions-121" },
            "Global loot modifier condition migration rule should be recorded"
        )
        assertTrue(
            result.changes.any { it.ruleId == "res-legacy-resource-id-renames-121" },
            "Legacy resource id rename rule should be recorded"
        )
        assertTrue(lootModifier.contains(""""conditions":"""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:random_chance""""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:inverted""""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:match_tool""""))
        assertTrue(lootModifier.contains(""""condition": "minecraft:block_state_property""""))
        assertTrue(lootModifier.contains(""""block": "minecraft:short_grass""""))
        assertFalse(lootModifier.contains(""""neoforge:conditions""""))
        assertFalse(lootModifier.contains("minecraft:grass"))
    }

    @Test
    fun `model texture grass references migrate to short grass`() {
        val projectDir = setupResourceProject()
        val modelDir = projectDir.resolve("src/generated/resources/assets/resmod/models/block")
        modelDir.createDirectories()
        modelDir.resolve("mossy_log.json").writeText("""
            {
              "parent": "minecraft:block/cube_all",
              "textures": {
                "all": "minecraft:block/grass"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val model = modelDir.resolve("mossy_log.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-legacy-resource-id-renames-121" },
            "Legacy texture resource rename rule should be recorded"
        )
        assertTrue(model.contains(""""all": "minecraft:block/short_grass""""))
        assertFalse(model.contains("minecraft:block/grass"))
    }

    @Test
    fun `sound subtitles are added to english language file`() {
        val projectDir = setupResourceProject()
        val assetsDir = projectDir.resolve("src/main/resources/assets/resmod")
        assetsDir.resolve("lang").createDirectories()
        assetsDir.resolve("sounds.json").writeText("""
            {
              "taiko": {
                "category": "block",
                "subtitle": "resmod.sound.taiko",
                "sounds": ["resmod:taiko_1"]
              }
            }
        """.trimIndent())
        assetsDir.resolve("lang/en_us.json").writeText("""
            {
              "item.resmod.existing": "Existing"
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lang = assetsDir.resolve("lang/en_us.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-sound-subtitle-lang" })
        assertTrue(lang.contains(""""item.resmod.existing": "Existing""""))
        assertTrue(lang.contains(""""resmod.sound.taiko": "Taiko""""))
    }

    @Test
    fun `loot table looting enchant function is migrated to enchanted count increase`() {
        val projectDir = setupResourceProject()
        val lootDir = projectDir.resolve("src/main/resources/data/resmod/loot_tables/entities")
        lootDir.createDirectories()
        lootDir.resolve("deer.json").writeText("""
            {
              "type": "minecraft:entity",
              "pools": [
                {
                  "rolls": 1.0,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:leather",
                      "functions": [
                        {
                          "function": "minecraft:looting_enchant",
                          "count": {
                            "type": "minecraft:uniform",
                            "min": 0.0,
                            "max": 1.0
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lootTable = projectDir.resolve("src/main/resources/data/resmod/loot_table/entities/deer.json").readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-loot-looting-enchant-function" },
            "Loot function migration rule should be recorded"
        )
        assertTrue(lootTable.contains(""""function": "minecraft:enchanted_count_increase""""))
        assertTrue(lootTable.contains(""""enchantment": "minecraft:looting""""))
        assertFalse(lootTable.contains("minecraft:looting_enchant"))
    }

    @Test
    fun `loot table nested table entries and looting chance conditions use 1_21 schema`() {
        val projectDir = setupResourceProject()
        val lootDir = projectDir.resolve("src/generated/resources/data/resmod/loot_table/entities")
        lootDir.createDirectories()
        lootDir.resolve("troll.json").writeText("""
            {
              "type": "minecraft:entity",
              "pools": [
                {
                  "rolls": 1.0,
                  "conditions": [
                    {
                      "condition": "minecraft:random_chance_with_looting",
                      "chance": 0.025,
                      "looting_multiplier": 0.01
                    }
                  ],
                  "entries": [
                    {
                      "type": "minecraft:loot_table",
                      "name": "minecraft:entities/zombie",
                      "weight": 2
                    },
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:rotten_flesh"
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val lootTable = lootDir.resolve("troll.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-loot-table-entry-name-to-value" })
        assertTrue(result.changes.any { it.ruleId == "res-loot-random-chance-with-looting-121" })
        assertTrue(lootTable.contains(""""type": "minecraft:loot_table""""))
        assertTrue(lootTable.contains(""""value": "minecraft:entities/zombie""""))
        assertTrue(lootTable.contains(""""name": "minecraft:rotten_flesh""""))
        assertTrue(lootTable.contains(""""condition": "minecraft:random_chance_with_enchanted_bonus""""))
        assertTrue(lootTable.contains(""""enchantment": "minecraft:looting""""))
        assertTrue(lootTable.contains(""""unenchanted_chance": 0.025"""))
        assertTrue(lootTable.contains(""""base": 0.035"""))
        assertTrue(lootTable.contains(""""per_level_above_first": 0.01"""))
        assertFalse(lootTable.contains("minecraft:random_chance_with_looting"))
        assertFalse(lootTable.contains("looting_multiplier"))
    }

    @Test
    fun `banner pattern item tags materialize data driven banner patterns`() {
        val projectDir = setupResourceProject()
        val tagDir = projectDir.resolve("src/generated/resources/data/resmod/tags/banner_pattern/pattern_item")
        tagDir.createDirectories()
        tagDir.resolve("naga.json").writeText("""
            {
              "values": [
                "resmod:naga"
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val bannerPattern = projectDir.resolve("src/generated/resources/data/resmod/banner_pattern/naga.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-banner-pattern-data-resource" })
        assertTrue(bannerPattern.contains(""""asset_id": "resmod:naga""""))
        assertTrue(bannerPattern.contains(""""translation_key": "block.minecraft.banner.resmod.naga""""))
    }

    @Test
    fun `common item tags migrate to c namespace in recipes and advancements`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/main/resources/data/resmod/recipes")
        recipeDir.resolve("tagged.json").writeText("""
            {"type":"minecraft:crafting_shapeless","ingredients":[{"tag":"forge:ingots/iron"}],"result":{"item":"resmod:item"}}
        """.trimIndent())
        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("tagged.json").writeText("""
            {"criteria":{"has_iron":{"trigger":"minecraft:inventory_changed","conditions":{"items":[{"tag":"forge:ingots/iron"}]}}}}
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/main/resources/data/resmod/recipe/tagged.json").readText()
        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/tagged.json").readText()
        assertTrue(recipe.contains(""""tag": "c:ingots/iron""""))
        assertFalse(recipe.contains("forge:ingots/iron"))
        assertFalse(recipe.contains("neoforge:ingots/iron"))
        assertTrue(advancement.contains(""""items": "#c:ingots/iron""""))
        assertFalse(advancement.contains("#forge:ingots/iron"))
        assertFalse(advancement.contains("#neoforge:ingots/iron"))
    }

    @Test
    fun `tag references migrate to c namespace in generated data`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        recipeDir.createDirectories()
        recipeDir.resolve("table.json").writeText("""
            {"type":"minecraft:crafting_shaped","key":{"L":{"tag":"forge:lumber"}},"result":{"item":"resmod:table"}}
        """.trimIndent())
        recipeDir.resolve("lantern.json").writeText("""
            {"type":"minecraft:crafting_shaped","key":{"C":{"tag":"c:cobblestone"},"G":{"tag":"forge:glass"},"S":{"tag":"neoforge:stone"}},"result":{"item":"resmod:lantern"}}
        """.trimIndent())
        val advancementDir = projectDir.resolve("src/generated/resources/data/resmod/advancements")
        advancementDir.createDirectories()
        advancementDir.resolve("table.json").writeText("""
            {"criteria":{"has_lumber":{"trigger":"minecraft:inventory_changed","conditions":{"items":[{"items":"#forge:lumber"}]}}}}
        """.trimIndent())
        advancementDir.resolve("lantern.json").writeText("""
            {"criteria":{"has_cobblestone":{"trigger":"minecraft:inventory_changed","conditions":{"items":[{"items":"#c:cobblestone"},{"items":"#forge:glass"},{"items":"#neoforge:stone"}]}}}}
        """.trimIndent())
        val tagDir = projectDir.resolve("src/generated/resources/data/c/tags/items")
        tagDir.createDirectories()
        tagDir.resolve("lumber.json").writeText("""{"values":["#forge:lumber/sakura","#neoforge:lumber/maple"]}""")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/table.json").readText()
        val lanternRecipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/lantern.json").readText()
        val advancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/table.json").readText()
        val lanternAdvancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/lantern.json").readText()
        val tag = projectDir.resolve("src/generated/resources/data/c/tags/item/lumber.json").readText()
        assertTrue(recipe.contains(""""tag": "c:lumber""""))
        assertTrue(lanternRecipe.contains(""""tag": "c:cobblestones""""))
        assertTrue(lanternRecipe.contains(""""tag": "c:glass_blocks""""))
        assertTrue(lanternRecipe.contains(""""tag": "c:stones""""))
        assertTrue(advancement.contains(""""items": "#c:lumber""""))
        assertTrue(lanternAdvancement.contains(""""items": "#c:cobblestones""""))
        assertTrue(lanternAdvancement.contains(""""items": "#c:glass_blocks""""))
        assertTrue(lanternAdvancement.contains(""""items": "#c:stones""""))
        assertTrue(tag.contains(""""#c:lumber/sakura""""))
        assertTrue(tag.contains(""""#c:lumber/maple""""))
        assertFalse(recipe.contains("forge:lumber"))
        assertFalse(lanternRecipe.contains(""""tag": "c:cobblestone""""))
        assertFalse(lanternRecipe.contains(""""tag": "c:glass""""))
        assertFalse(lanternRecipe.contains(""""tag": "c:stone""""))
        assertFalse(recipe.contains("neoforge:lumber"))
        assertFalse(advancement.contains("#forge:lumber"))
        assertFalse(lanternAdvancement.contains("#c:cobblestone\""))
        assertFalse(lanternAdvancement.contains("#c:glass\""))
        assertFalse(lanternAdvancement.contains("#c:stone\""))
        assertFalse(tag.contains("#forge:"))
        assertFalse(tag.contains("#neoforge:"))
    }

    @Test
    fun `common gravel and sand tags migrate to 1_21 common tag paths`() {
        val projectDir = setupResourceProject()
        val tagDir = projectDir.resolve("src/generated/resources/data/resmod/tags/blocks/ore_magnet")
        tagDir.createDirectories()
        tagDir.resolve("safe_replace.json").writeText("""
            {
              "values": [
                "#c:gravel",
                "#c:sand",
                "#forge:gravel",
                "#forge:sand"
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val tag = projectDir.resolve("src/generated/resources/data/resmod/tags/block/ore_magnet/safe_replace.json").readText()
        assertTrue(tag.contains(""""#c:gravels""""))
        assertTrue(tag.contains(""""#c:sands""""))
        assertFalse(tag.contains("#c:gravel\""))
        assertFalse(tag.contains("#c:sand\""))
        assertFalse(tag.contains("#forge:"))
    }

    @Test
    fun `common leather string tags and mcfunction item nbt migrate to 1_21 syntax`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        val advancementDir = projectDir.resolve("src/generated/resources/data/resmod/advancements/recipes/tools")
        val functionDir = projectDir.resolve("src/main/resources/data/resmod/functions")
        recipeDir.createDirectories()
        advancementDir.createDirectories()
        functionDir.createDirectories()
        recipeDir.resolve("gloves.json").writeText("""
            {
              "type": "minecraft:crafting_shaped",
              "key": {
                "#": {
                  "tag": "forge:leather"
                },
                "S": {
                  "tag": "c:string"
                }
              },
              "pattern": [
                "#S#"
              ],
              "result": "resmod:gloves"
            }
        """.trimIndent())
        advancementDir.resolve("gloves.json").writeText("""
            {
              "criteria": {
                "has_string": {
                  "trigger": "minecraft:inventory_changed",
                  "conditions": {
                    "items": [
                      {
                        "items": "#c:string"
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent())
        functionDir.resolve("suit_up.mcfunction").writeText("""
            give @p resmod:hammer{Unbreakable:1,CustomFlag:1b}
            execute at @p run fill ~-1 ~ ~-1 ~1 ~1 ~1 minecraft:air replace #forge:stone
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/gloves.json").readText()
        val advancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/recipes/tools/gloves.json").readText()
        val function = projectDir.resolve("src/main/resources/data/resmod/function/suit_up.mcfunction").readText()

        assertTrue(result.changes.any { it.ruleId == "res-mcfunction-common-tag-reference" })
        assertTrue(result.changes.any { it.ruleId == "res-mcfunction-itemstack-components" })
        assertTrue(recipe.contains(""""tag": "c:leathers""""))
        assertTrue(recipe.contains(""""tag": "c:strings""""))
        assertTrue(advancement.contains(""""items": "#c:strings""""))
        assertTrue(function.contains("resmod:hammer["))
        assertTrue(function.contains("minecraft:unbreakable={}"))
        assertTrue(function.contains("minecraft:custom_data={CustomFlag:1b}"))
        assertTrue(function.contains("#c:stones"))
        assertFalse(recipe.contains("forge:leather"))
        assertFalse(recipe.contains("c:string\""))
        assertFalse(advancement.contains("#c:string\""))
        assertFalse(function.contains("{Unbreakable:1"))
        assertFalse(function.contains("#forge:stone"))
    }

    @Test
    fun `partial nbt recipe ingredients migrate to data component ingredients`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes")
        recipeDir.createDirectories()
        recipeDir.resolve("charged_staff.json").writeText("""
            {
              "type": "minecraft:crafting_shapeless",
              "ingredients": [
                [
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:strength\"}"
                  },
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:long_strength\"}"
                  }
                ],
                {
                  "type": "forge:partial_nbt",
                  "item": "resmod:charged_staff",
                  "nbt": "{Damage:9}"
                }
              ],
              "result": {
                "item": "resmod:charged_staff"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/charged_staff.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-recipe-partial-nbt-component-ingredient" })
        assertTrue(recipe.contains(""""type": "neoforge:compound""""))
        assertTrue(recipe.contains(""""type": "neoforge:components""""))
        assertTrue(recipe.contains(""""minecraft:potion_contents": {"""))
        assertTrue(recipe.contains(""""potion": "minecraft:strength""""))
        assertTrue(recipe.contains(""""minecraft:damage": 9"""))
        assertTrue(recipe.contains(""""items": "resmod:charged_staff""""))
        assertFalse(recipe.contains("partial_nbt"))
        assertFalse(recipe.contains(""""type": "neoforge:partial_nbt""""))
        assertFalse(recipe.contains(""""type": "forge:partial_nbt""""))
    }

    @Test
    fun `damaged scepter repair recipes use namespaced custom 1_21 recipe type`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes/equipment")
        val javaDir = projectDir.resolve("src/main/java/resmod/init")
        recipeDir.createDirectories()
        javaDir.createDirectories()
        javaDir.resolve("ModRecipes.java").writeText("""
            package resmod.init;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class ModRecipes {
                public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, "resmod");
            }
        """.trimIndent())
        recipeDir.resolve("zombie_scepter.json").writeText("""
            {
              "type": "minecraft:crafting_shapeless",
              "category": "equipment",
              "ingredients": [
                [
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:strength\"}"
                  },
                  {
                    "type": "forge:partial_nbt",
                    "item": "minecraft:potion",
                    "nbt": "{Potion:\"minecraft:long_strength\"}"
                  }
                ],
                {
                  "type": "forge:partial_nbt",
                  "item": "resmod:zombie_scepter",
                  "nbt": "{Damage:9}"
                },
                {
                  "item": "minecraft:rotten_flesh"
                }
              ],
              "result": {
                "item": "resmod:zombie_scepter"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/equipment/zombie_scepter.json").readText()
        val generatedSerializer = projectDir.resolve("src/main/java/com/modporter/generated/resmod/recipe/ModPorterScepterRepairRecipe.java").readText()
        val registry = javaDir.resolve("ModRecipes.java").readText()
        assertTrue(result.changes.any { it.ruleId == "res-recipe-partial-nbt-component-ingredient" })
        assertTrue(result.changes.any { it.ruleId == "res-damaged-scepter-repair-recipe-121" })
        assertTrue(result.changes.any { it.ruleId == "res-generate-scepter-repair-recipe-serializer" })
        assertTrue(result.changes.any { it.ruleId == "res-register-scepter-repair-recipe-serializer" })
        assertTrue(recipe.contains(""""type": "resmod:scepter_repair""""))
        assertTrue(recipe.contains(""""durability": 9"""))
        assertTrue(recipe.contains(""""repair_ingredients":"""))
        assertTrue(recipe.contains(""""type": "neoforge:compound""""))
        assertTrue(recipe.contains(""""type": "neoforge:components""""))
        assertTrue(recipe.contains(""""item": "minecraft:rotten_flesh""""))
        assertTrue(recipe.contains(""""scepter": "resmod:zombie_scepter""""))
        assertTrue(generatedSerializer.contains("class ModPorterScepterRepairRecipe extends CustomRecipe"))
        assertTrue(generatedSerializer.contains("return resmod.init.ModRecipes.MODPORTER_SCEPTER_REPAIR_RECIPE.get();"))
        assertTrue(registry.contains("import com.modporter.generated.resmod.recipe.ModPorterScepterRepairRecipe;"))
        assertTrue(registry.contains("RECIPE_SERIALIZERS.register(\"scepter_repair\", ModPorterScepterRepairRecipe.Serializer::new)"))
        assertFalse(recipe.contains("minecraft:crafting_shapeless"))
        assertFalse(recipe.contains(""""result":"""))
        assertFalse(recipe.contains("partial_nbt"))
    }

    @Test
    fun `uncrafting recipes use 1_21 input count with ingredient objects`() {
        val projectDir = setupResourceProject()
        val recipeDir = projectDir.resolve("src/generated/resources/data/resmod/recipes/uncrafting")
        recipeDir.createDirectories()
        recipeDir.resolve("tipped_arrow.json").writeText("""
            {
              "type": "resmod:uncrafting",
              "cost": 4,
              "input": {
                "count": 8,
                "ingredient": {
                  "item": "minecraft:tipped_arrow"
                }
              },
              "key": {
                "A": {
                  "item": "minecraft:arrow"
                }
              },
              "pattern": [
                "AAA",
                "A A",
                "AAA"
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val recipe = projectDir.resolve("src/generated/resources/data/resmod/recipe/uncrafting/tipped_arrow.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-uncrafting-recipe-input-count-121" })
        assertTrue(recipe.contains(""""input": {"""))
        assertTrue(recipe.contains(""""item": "minecraft:tipped_arrow""""))
        assertTrue(recipe.contains(""""input_count": 8"""))
        assertTrue(recipe.contains(""""A": {"""))
        assertTrue(recipe.contains(""""item": "minecraft:arrow""""))
        assertFalse(recipe.contains(""""ingredient":"""))
    }

    @Test
    fun `single conditional advancement is unwrapped with top level neoforge conditions`() {
        val projectDir = setupResourceProject()
        val advancementDir = projectDir.resolve("src/generated/resources/data/resmod/advancements/recipes/decorations")
        advancementDir.createDirectories()
        advancementDir.resolve("conditional_table.json").writeText("""
            {
              "advancements": [
                {
                  "advancement": {
                    "parent": "minecraft:recipes/root",
                    "criteria": {
                      "has_the_recipe": {
                        "conditions": {
                          "recipe": "resmod:conditional_table"
                        },
                        "trigger": "minecraft:recipe_unlocked"
                      }
                    },
                    "rewards": {
                      "recipes": [
                        "resmod:conditional_table"
                      ]
                    }
                  },
                  "conditions": [
                    {
                      "type": "resmod:enabled"
                    }
                  ]
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val advancement = projectDir.resolve("src/generated/resources/data/resmod/advancement/recipes/decorations/conditional_table.json").readText()
        assertTrue(result.changes.any { it.ruleId == "res-advancement-unwrap-single-conditional" })
        assertTrue(advancement.contains(""""neoforge:conditions": ["""))
        assertTrue(advancement.contains(""""parent": "minecraft:recipes/root""""))
        assertTrue(advancement.contains(""""conditions": {"""), "Criterion predicate conditions should stay vanilla")
        assertFalse(advancement.contains(""""advancements":"""))
        assertFalse(advancement.contains(""""advancement":"""))
    }

    @Test
    fun `code awarded advancement uses impossible trigger and id icon`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            class Awarder {
                private static final String ADVANCEMENT_ID = "resmod:manual";

                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("manual.json").writeText("""
            {"id":"resmod:manual","display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:manual"}}}
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/manual.json").readText()
        assertFalse(advancement.contains(""""id":"resmod:manual""""), "Top-level legacy id should be removed")
        assertTrue(advancement.contains(""""icon":{"id":"resmod:item"}"""), "Advancement icon should use id")
        assertTrue(advancement.contains(""""trigger": "minecraft:impossible""""), "Code-awarded criterion should use impossible trigger")
        assertFalse(advancement.contains(""""trigger":"resmod:manual""""))
    }

    @Test
    fun `code awarded advancement resolves qualified ids from declared class owner`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            class Awarder {
                private static final String ADVANCEMENT_ID = "resmod:qualified";

                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, Awarder.ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("qualified.json").writeText("""
            {"display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:qualified"}}}
        """.trimIndent())

        ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/qualified.json").readText()
        assertTrue(advancement.contains(""""trigger": "minecraft:impossible""""), advancement)
        assertFalse(advancement.contains(""""trigger":"resmod:qualified""""))
    }

    @Test
    fun `code awarded advancement does not infer constant owner from file name`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("Awarder.java").writeText("""
            package com.example;

            static final String ADVANCEMENT_ID = "resmod:manual";

            class Caller {
                void award(Object player) {
                    AdvancementHelper.tryAwardAdvancement(player, Awarder.ADVANCEMENT_ID, "code_triggered");
                }
            }
        """.trimIndent())

        val advancementDir = projectDir.resolve("src/main/resources/data/resmod/advancements")
        advancementDir.resolve("manual.json").writeText("""
            {"display":{"icon":{"item":"resmod:item"}},"criteria":{"code_triggered":{"trigger":"resmod:manual"}}}
        """.trimIndent())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val advancement = projectDir.resolve("src/main/resources/data/resmod/advancement/manual.json").readText()
        assertFalse(
            result.changes.any { it.ruleId == "res-advancement-code-awarded-trigger" },
            "Unsupported source shape must not be converted into code-awarded advancement evidence"
        )
        assertTrue(advancement.contains(""""trigger":"resmod:manual""""), advancement)
        assertFalse(advancement.contains("minecraft:impossible"), advancement)
    }

    @Test
    fun `forge item layers model loader is migrated without changing model structure`() {
        val projectDir = setupResourceProject()
        val modelDir = projectDir.resolve("src/main/resources/assets/resmod/models/item")
        modelDir.createDirectories()
        modelDir.resolve("layered.json").writeText("""
            {
              "loader": "forge:item_layers",
              "parent": "minecraft:item/generated",
              "textures": {
                "layer0": "minecraft:item/bucket",
                "layer1": "resmod:item/overlay"
              },
              "overrides": [
                {
                  "predicate": { "resmod:hot_steam": 1 },
                  "model": "resmod:item/layered_with_smoke"
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val model = projectDir.resolve("src/main/resources/assets/resmod/models/item/layered.json").readText()
        assertTrue(model.contains(""""loader": "neoforge:item_layers""""))
        assertTrue(model.contains(""""overrides":"""), "Existing predicate override structure should be preserved")
        assertTrue(model.contains(""""model": "resmod:item/layered_with_smoke""""))
        assertFalse(model.contains(""""loader": "forge:item_layers""""))
    }

    @Test
    fun `forge model extension metadata and loaders are migrated to neoforge namespace`() {
        val projectDir = setupResourceProject()
        val blockModelDir = projectDir.resolve("src/main/resources/assets/resmod/models/block")
        val itemModelDir = projectDir.resolve("src/main/resources/assets/resmod/models/item")
        blockModelDir.createDirectories()
        itemModelDir.createDirectories()
        blockModelDir.resolve("glowing_block.json").writeText("""
            {
              "loader": "forge:composite",
              "children": {
                "main": {
                  "parent": "resmod:block/main",
                  "elements": [
                    {
                      "faces": {
                        "north": {
                          "texture": "#all",
                          "forge_data": {
                            "block_light": 15,
                            "sky_light": 15
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent())
        itemModelDir.resolve("separate.json").writeText("""
            {
              "loader": "forge:separate_transforms",
              "base": {
                "parent": "minecraft:item/generated"
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val blockModel = blockModelDir.resolve("glowing_block.json").readText()
        val itemModel = itemModelDir.resolve("separate.json").readText()

        assertTrue(result.changes.any { it.ruleId == "res-model-extension-neoforge-namespace" })
        assertTrue(blockModel.contains(""""loader": "neoforge:composite""""))
        assertTrue(blockModel.contains(""""neoforge_data": {"""))
        assertTrue(itemModel.contains(""""loader": "neoforge:separate_transforms""""))
        assertFalse(blockModel.contains(""""forge_data""""))
        assertFalse(blockModel.contains(""""forge:composite""""))
        assertFalse(itemModel.contains(""""forge:separate_transforms""""))
    }

    @Test
    fun `registered item with texture gets missing generated model`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("ItemRegister.java").writeText("""
            package com.example;

            class ItemRegister {
                static final Registry ITEMS = null;

                static void register() {
                    ITEMS.register("sample_item", () -> new Item(new Item.Properties()));
                }
            }
        """.trimIndent())
        val textureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        textureDir.createDirectories()
        textureDir.resolve("sample_item.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val model = projectDir.resolve("src/main/resources/assets/resmod/models/item/sample_item.json").readText()
        assertTrue(model.contains(""""parent": "minecraft:item/generated""""))
        assertTrue(model.contains(""""layer0": "resmod:item/sample_item""""))
    }

    @Test
    fun `generated resources do not duplicate item models already in main resources`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("ItemRegister.java").writeText("""
            package com.example;

            class ItemRegister {
                static final Registry ITEMS = null;

                static void register() {
                    ITEMS.register("sample_item", () -> new Item(new Item.Properties()));
                }
            }
        """.trimIndent())
        val mainModelDir = projectDir.resolve("src/main/resources/assets/resmod/models/item")
        mainModelDir.createDirectories()
        mainModelDir.resolve("sample_item.json").writeText("""{"parent":"minecraft:item/generated"}""")
        val generatedTextureDir = projectDir.resolve("src/generated/resources/assets/resmod/textures/item")
        generatedTextureDir.createDirectories()
        generatedTextureDir.resolve("sample_item.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        assertFalse(projectDir.resolve("src/generated/resources/assets/resmod/models/item/sample_item.json").exists())
        assertTrue(mainModelDir.resolve("sample_item.json").exists())
    }

    @Test
    fun `hotbath descriptive item model uses bath herb texture when no direct texture exists`() {
        val projectDir = setupResourceProject()
        val javaDir = projectDir.resolve("src/main/java/com/example")
        javaDir.createDirectories()
        javaDir.resolve("ItemRegister.java").writeText("""
            package com.example;

            class ItemRegister {
                static final Registry ITEMS = null;

                static void register() {
                    ITEMS.register("descriptive_item", () -> new DescriptiveItem(new Item.Properties()));
                }
            }
        """.trimIndent())
        val textureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        textureDir.createDirectories()
        textureDir.resolve("bath_herb.png").writeText("png")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val model = projectDir.resolve("src/main/resources/assets/resmod/models/item/descriptive_item.json").readText()
        assertTrue(model.contains(""""layer0": "resmod:item/bath_herb""""))
    }

    @Test
    fun `item textures are resized to mipmap compatible dimensions`() {
        val projectDir = setupResourceProject()
        val textureDir = projectDir.resolve("src/main/resources/assets/resmod/textures/item")
        textureDir.createDirectories()
        val texture = textureDir.resolve("odd_sized.png")
        ImageIO.write(BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB), "png", texture.toFile())

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val resized = ImageIO.read(texture.toFile())
        assertEquals(32, resized.width)
        assertEquals(32, resized.height)
    }

    @Test
    fun `updates pack format`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        val packMcmeta = projectDir.resolve("src/main/resources/pack.mcmeta").readText()
        assertTrue(packMcmeta.contains("\"pack_format\": 48") || packMcmeta.contains("\"pack_format\":48"),
            "Pack format should be updated to 48 (data pack format)")
        assertTrue(packMcmeta.contains("supported_formats"), "Should include supported_formats range")
    }

    @Test
    fun `generated worldgen provider value min max object is flattened`() {
        val projectDir = tempDir.resolve("worldgenmod")
        val feature = projectDir.resolve(
            "src/generated/resources/data/sakura/worldgen/configured_feature/bamboo_column.json"
        )
        feature.parent.createDirectories()
        feature.writeText("""
            {
              "type": "minecraft:bamboo",
              "config": {
                "height": {
                  "type": "minecraft:biased_to_bottom",
                  "value": {
                    "max_inclusive": 18,
                    "min_inclusive": 9
                  }
                }
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val migrated = feature.readText()
        assertTrue(
            result.changes.any { it.ruleId == "res-worldgen-provider-value-flatten" },
            "Worldgen provider flatten rule should be recorded"
        )
        assertTrue(migrated.contains(""""type": "minecraft:biased_to_bottom""""))
        assertTrue(migrated.contains(""""max_inclusive": 18"""))
        assertTrue(migrated.contains(""""min_inclusive": 9"""))
        assertFalse(migrated.contains(""""value": {"""), "1.21.1 provider min/max fields should not remain nested")
    }

    @Test
    fun `generated dimension type spawn light provider value object is flattened`() {
        val projectDir = tempDir.resolve("dimensiontypemod")
        val dimensionType = projectDir.resolve(
            "src/generated/resources/data/resmod/dimension_type/example_type.json"
        )
        dimensionType.parent.createDirectories()
        dimensionType.writeText("""
            {
              "monster_spawn_light_level": {
                "type": "minecraft:uniform",
                "value": {
                  "max_inclusive": 7,
                  "min_inclusive": 0
                }
              }
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val migrated = dimensionType.readText()
        assertTrue(result.changes.any { it.ruleId == "res-worldgen-provider-value-flatten" })
        assertTrue(migrated.contains(""""type": "minecraft:uniform""""))
        assertTrue(migrated.contains(""""max_inclusive": 7"""))
        assertTrue(migrated.contains(""""min_inclusive": 0"""))
        assertFalse(migrated.contains(""""value": {"""))
    }

    @Test
    fun `generated no structure placement modifier value object is flattened`() {
        val projectDir = tempDir.resolve("placementmod")
        val placedFeature = projectDir.resolve(
            "src/generated/resources/data/resmod/worldgen/placed_feature/big_mushroom.json"
        )
        placedFeature.parent.createDirectories()
        placedFeature.writeText("""
            {
              "feature": "resmod:mushroom/big_mushroom",
              "placement": [
                {
                  "type": "resmod:no_structure",
                  "value": {
                    "additional_clearance": 0,
                    "occupies_surface": true,
                    "occupies_underground": false
                  }
                }
              ]
            }
        """.trimIndent())

        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        val migrated = placedFeature.readText()
        assertTrue(result.changes.any { it.ruleId == "res-no-structure-placement-flatten" })
        assertTrue(migrated.contains(""""type": "resmod:no_structure""""))
        assertTrue(migrated.contains(""""additional_clearance": 0"""))
        assertTrue(migrated.contains(""""occupies_surface": true"""))
        assertTrue(migrated.contains(""""occupies_underground": false"""))
        assertTrue(migrated.contains(""""occupies_vegetation": false"""))
        assertTrue(migrated.contains(""""structures_allowed": ["""))
        assertFalse(migrated.contains(""""value": {"""))
    }

    @Test
    fun `legacy Nitrogen fuel menu textures generate 1_21 sprites from source pixels`() {
        val projectDir = tempDir.resolve("nitrogenfuel")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "textures/gui/menu/altar.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(176, 0, 0xFF112233.toInt())
        source.setRGB(189, 13, 0xFF445566.toInt())
        source.setRGB(56, 35, 0xFF778899.toInt())
        source.setRGB(69, 48, 0xFF99AABB.toInt())
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        val icon = ImageIO.read(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").toFile())
        val background = ImageIO.read(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_background.png").toFile())
        assertTrue(result.changes.any { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertTrue(result.changes.any { it.ruleId == "res-nitrogen-fuel-background-sprite" })
        assertEquals(14, icon.width)
        assertEquals(14, icon.height)
        assertEquals(0xFF112233.toInt(), icon.getRGB(0, 0))
        assertEquals(0xFF445566.toInt(), icon.getRGB(13, 13))
        assertEquals(14, background.width)
        assertEquals(14, background.height)
        assertEquals(0xFF778899.toInt(), background.getRGB(0, 0))
        assertEquals(0xFF99AABB.toInt(), background.getRGB(13, 13))
    }

    @Test
    fun `legacy Nitrogen fuel sprite generation rejects unresolved qualified namespace constants`() {
        val projectDir = tempDir.resolve("nitrogenfuel-unresolved")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val textureDir = projectDir.resolve("src/main/resources/assets/example/textures/gui/menu")
        srcDir.createDirectories()
        textureDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())
        srcDir.resolve("ExampleFuelCategory.java").writeText("""
            package com.example;

            import com.aetherteam.nitrogen.integration.jei.categories.fuel.AbstractFuelCategory;
            import net.minecraft.resources.ResourceLocation;

            public class ExampleFuelCategory extends AbstractFuelCategory {
                public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MissingMod.MODID, "textures/gui/menu/altar.png");
            }
        """.trimIndent())

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(source, "png", textureDir.resolve("altar.png").toFile())

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(projectDir)

        assertTrue(
            result.errors.any {
                it.contains("Cannot resolve Nitrogen fuel texture namespace expression 'MissingMod.MODID'")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(result.changes.any { it.ruleId == "res-nitrogen-fuel-icon-sprite" })
        assertFalse(projectDir.resolve("src/main/resources/assets/example/textures/gui/sprites/modporter/nitrogen_fuel_altar_icon.png").exists())
    }

    @Test
    fun `dry run does not rename folders`() {
        val projectDir = setupResourceProject()
        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).analyze(projectDir)

        // Old folders should still exist
        val dataDir = projectDir.resolve("src/main/resources/data/resmod")
        assertTrue(dataDir.resolve("tags/items").exists(), "Dry run should not rename folders")
        assertTrue(dataDir.resolve("recipes").exists(), "Dry run should not rename folders")

        // But changes should be reported
        assertTrue(result.changeCount > 0, "Dry run should report changes")
    }

    @Test
    fun `handles project without resources dir`() {
        val projectDir = tempDir.resolve("nores")
        projectDir.createDirectories()
        val db = MappingDatabase.loadDefault()
        val result = ResourceMigrationPass(db).apply(projectDir)

        assertEquals(0, result.changeCount, "No resources = no changes")
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `handles generated resources dir`() {
        val projectDir = tempDir.resolve("genmod")
        val genDir = projectDir.resolve("src/generated/resources/data/genmod")
        genDir.resolve("tags/items").createDirectories()
        genDir.resolve("tags/items/gen_tag.json").writeText("""{"values":[]}""")

        val db = MappingDatabase.loadDefault()
        ResourceMigrationPass(db).apply(projectDir)

        assertTrue(genDir.resolve("tags/item").exists(), "Should also rename in generated resources")
    }
}
