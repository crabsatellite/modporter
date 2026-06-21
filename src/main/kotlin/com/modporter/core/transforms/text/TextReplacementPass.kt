package com.modporter.core.transforms.text

import com.modporter.core.pipeline.*
import com.modporter.mapping.MappingDatabase
import com.modporter.mapping.TextReplacement
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Pass 1: Text-based find-and-replace transformations.
 * Handles package renames, class renames, and other simple substitutions.
 * Order of replacements matters (more specific patterns first).
 */
class TextReplacementPass(
    private val mappingDb: MappingDatabase
) : Pass {
    override val name = "Text Replacement"
    override val order = 1

    override fun analyze(projectDir: Path): PassResult {
        return processFiles(projectDir, dryRun = true)
    }

    override fun apply(projectDir: Path): PassResult {
        return processFiles(projectDir, dryRun = false)
    }

    private fun processFiles(projectDir: Path, dryRun: Boolean): PassResult {
        // Combine explicit text replacements with auto-generated rules from class-renames
        val rules = buildRuleList()
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()

        val javaFiles = findJavaFiles(projectDir)
        logger.info { "Found ${javaFiles.size} Java files to process" }
        val legacyDyeableLeatherItemClasses = collectLegacyDyeableLeatherItemClasses(javaFiles)
        val legacyLootCodecOwners = collectLegacyLootCodecOwners(javaFiles)

        try {
            migrateLegacyCustomEnchantmentData(projectDir, javaFiles, changes, errors, dryRun)
        } catch (e: Exception) {
            errors.add("Error extracting legacy custom enchantment data: ${e.message}")
            logger.error(e) { "Error extracting legacy custom enchantment data" }
        }

        for (file in javaFiles) {
            try {
                val result = processFile(
                    projectDir,
                    file,
                    rules,
                    dryRun,
                    errors,
                    legacyDyeableLeatherItemClasses,
                    legacyLootCodecOwners
                )
                changes.addAll(result)
            } catch (e: Exception) {
                errors.add("Error processing ${file}: ${e.message}")
                logger.error(e) { "Error processing $file" }
            }
        }

        return PassResult(name, changes, errors)
    }

    private fun processFile(
        projectDir: Path,
        file: Path,
        rules: List<TextReplacement>,
        dryRun: Boolean,
        errors: MutableList<String>,
        legacyDyeableLeatherItemClasses: Set<String>,
        legacyLootCodecOwners: Set<String>
    ): List<Change> {
        val originalContent = file.readText()
        var content = originalContent
        val changes = mutableListOf<Change>()
        val tierIncorrectTagResources = mutableListOf<TierIncorrectTagResource>()

        val networkHooksOpenScreen = migrateNetworkHooksOpenScreen(content, file)
        content = networkHooksOpenScreen.content
        changes.addAll(networkHooksOpenScreen.changes)
        errors.addAll(networkHooksOpenScreen.errors)

        val beforeForgeInternalNames = content
        content = migrateForgeInternalNameDescriptors(content)
        if (content != beforeForgeInternalNames) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate Forge JVM internal names in mixin descriptors to NeoForge owners",
                    before = "Lnet/minecraftforge/...;",
                    after = "Lnet/neoforged/...;",
                    confidence = Confidence.HIGH,
                    ruleId = "forge-internal-name-descriptors"
                )
            )
        }

        val beforeInventoryRecipeHolder = content
        content = migrateInventoryRecipeHolderInterface(content)
        if (content != beforeInventoryRecipeHolder) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate container RecipeHolder interface to RecipeCraftingHolder",
                    before = "net.minecraft.world.inventory.RecipeHolder implements RecipeHolder",
                    after = "net.minecraft.world.inventory.RecipeCraftingHolder implements RecipeCraftingHolder",
                    confidence = Confidence.HIGH,
                    ruleId = "inventory-recipeholder-recipecraftingholder"
                )
            )
        }

        for (rule in rules) {
            val pattern = if (rule.isRegex) Regex(rule.pattern) else null
            val lines = content.lines()

            for ((lineIdx, line) in lines.withIndex()) {
                val matches = if (rule.isRegex) {
                    pattern!!.containsMatchIn(line)
                } else {
                    line.contains(rule.pattern)
                }

                if (matches) {
                    val newLine = if (rule.isRegex) {
                        pattern!!.replace(line, rule.replacement)
                    } else {
                        line.replace(rule.pattern, rule.replacement)
                    }

                    if (newLine != line) {
                        changes.add(
                            Change(
                                file = file,
                                line = lineIdx + 1,
                                description = rule.description,
                                before = line.trim(),
                                after = newLine.trim(),
                                confidence = Confidence.HIGH,
                                ruleId = rule.id
                            )
                        )
                    }
                }
            }

            // Apply replacement to full content
            content = if (rule.isRegex) {
                pattern!!.replace(content, rule.replacement)
            } else {
                content.replace(rule.pattern, rule.replacement)
            }
        }

        val beforeRemainingRegistryObjectWildcards = content
        content = migrateRemainingRegistryObjectWildcardHolders(content)
        if (content != beforeRemainingRegistryObjectWildcards) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate remaining RegistryObject wildcard holder parameters to DeferredHolder",
                    before = "RegistryObject<? extends T>",
                    after = "DeferredHolder<T, ? extends T>",
                    confidence = Confidence.HIGH,
                    ruleId = "registryobject-wildcard-deferredholder"
                )
            )
        }

        val beforeTagManager = content
        content = migrateRemovedTagManagerAccess(content)
        if (content != beforeTagManager) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate removed ITagManager item tag access to Registry.getTagOrEmpty",
                    before = "ITagManager<Item> tags = BuiltInRegistries.ITEM.tags(); tags.getTag(tag)",
                    after = "BuiltInRegistries.ITEM.getTagOrEmpty(tag)",
                    confidence = Confidence.HIGH,
                    ruleId = "itagmanager-item-gettagorempty"
                )
            )
        }

        val beforeCustomEnchantments = content
        content = migrateCustomEnchantmentResourceKeys(content)
        if (content != beforeCustomEnchantments) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate custom enchantment DeferredRegister entries to ResourceKey constants",
                    before = "DeferredRegister<Enchantment> ENCHANTMENTS and DeferredHolder entries",
                    after = "ResourceKey<Enchantment> constants resolved through Holder lookup",
                    confidence = Confidence.HIGH,
                    ruleId = "custom-enchantment-resourcekey-context"
                )
            )
        }

        val beforeEnchantmentCategoryRuntime = content
        content = migrateEnchantmentCategoryRuntimeChecks(content)
        if (content != beforeEnchantmentCategoryRuntime) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy EnchantmentCategory runtime checks to holder-based item support checks",
                    before = "Enchantment.category == EnchantmentCategory.* || enchantment.canEnchant(stack)",
                    after = "stack.supportsEnchantment(Holder<Enchantment>)",
                    confidence = Confidence.HIGH,
                    ruleId = "enchantment-category-runtime-holder-support"
                )
            )
        }

        val beforeParticleOptions = content
        content = migrateParticleOptionsCodecs(content)
        if (content != beforeParticleOptions) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate ParticleOptions deserializer implementation to MapCodec and StreamCodec",
                    before = "ParticleOptions.Deserializer",
                    after = "ParticleOptions CODEC/STREAM_CODEC",
                    confidence = Confidence.HIGH,
                    ruleId = "particle-options-codec-streamcodec"
                )
            )
        }

        val beforeParticleTypes = content
        content = migrateParticleTypeRegistrations(content)
        if (content != beforeParticleTypes) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate custom ParticleType registrations to codec and streamCodec overrides",
                    before = "new ParticleType<>(..., new Data.Deserializer())",
                    after = "new ParticleType<>(...) with codec() and streamCodec()",
                    confidence = Confidence.HIGH,
                    ruleId = "particle-type-codec-streamcodec"
                )
            )
        }

        val beforeParticleNetwork = content
        content = migrateParticleNetworkCodecs(content)
        if (content != beforeParticleNetwork) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate particle packet serialization to ParticleType streamCodec",
                    before = "ParticleOptions.writeToNetwork / ParticleType.getDeserializer",
                    after = "ParticleType.streamCodec encode/decode",
                    confidence = Confidence.HIGH,
                    ruleId = "particle-network-streamcodec"
                )
            )
        }

        val beforePartialNbt = content
        content = migratePartialNbtIngredients(content)
        if (content != beforePartialNbt) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate PartialNBTIngredient helpers to DataComponentIngredient",
                    before = "PartialNBTIngredient.of(... CompoundTag ...)",
                    after = "DataComponentIngredient.of(... DataComponents ...)",
                    confidence = Confidence.HIGH,
                    ruleId = "partial-nbt-ingredient-data-components"
                )
            )
        }

        val beforeSingleItemResult = content
        content = migrateSingleItemRecipeBuilderResults(content)
        if (content != beforeSingleItemResult) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate SingleItemRecipeBuilder.Result wrappers to RecipeOutput save calls",
                    before = "consumer.accept(stonecutting(...)); class Wrapper extends SingleItemRecipeBuilder.Result",
                    after = "stonecutting(output, ...); SingleItemRecipeBuilder.save(output, id)",
                    confidence = Confidence.HIGH,
                    ruleId = "single-item-recipe-result-recipeoutput"
                )
            )
        }

        val beforeDyeableLeather = content
        content = migrateDyeableLeatherItemColors(content, legacyDyeableLeatherItemClasses)
        if (content != beforeDyeableLeather) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate removed DyeableLeatherItem methods to DyedItemColor data components",
                    before = "DyeableLeatherItem + display/color CustomData",
                    after = "DataComponents.DYED_COLOR + DyedItemColor",
                    confidence = Confidence.HIGH,
                    ruleId = "dyeable-leatheritem-dyed-component"
                )
            )
        }

        val beforeTierSorting = content
        content = migrateTierSortingRegistryTiers(content, tierIncorrectTagResources, projectDir, file, errors)
        if (content != beforeTierSorting) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate TierSortingRegistry custom tiers to 1.21 SimpleTier incorrect-block tags",
                    before = "TierSortingRegistry.registerTier(new SimpleTier(miningLevel, ..., needsTag, repair), ...)",
                    after = "new SimpleTier(incorrectBlocksForDrops, ..., repair)",
                    confidence = Confidence.HIGH,
                    ruleId = "tiersortingregistry-simpletier-incorrect-tags"
                )
            )
        }
        ensureTierIncorrectTagResources(projectDir, tierIncorrectTagResources, changes, dryRun)

        val beforeLootCodecs = content
        content = migrateLootSerializerCodecs(content, legacyLootCodecOwners)
        if (content != beforeLootCodecs) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy loot serializers to 1.21 MapCodec-backed loot types",
                    before = "Loot Serializer<T> inner classes and new LootItem*Type(new Serializer())",
                    after = "public static MapCodec<T> CODEC and new LootItem*Type(T.CODEC)",
                    confidence = Confidence.HIGH,
                    ruleId = "loot-serializer-mapcodec"
                )
            )
        }

        val beforeConditionCodecs = content
        content = migrateNeoForgeConditionSerializerCodecs(content)
        if (content != beforeConditionCodecs) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate legacy NeoForge recipe condition serializers to MapCodec-backed ICondition",
                    before = "IConditionSerializer<T> inner class and ICondition#getID",
                    after = "ICondition#codec with public static MapCodec<T> CODEC",
                    confidence = Confidence.HIGH,
                    ruleId = "neoforge-condition-serializer-mapcodec"
                )
            )
        }

        val beforeJadeTooltipHelper = content
        content = migrateJadeTooltipElementHelper(content)
        if (content != beforeJadeTooltipHelper) {
            changes.add(
                Change(
                    file = file,
                    line = 1,
                    description = "Migrate Jade ITooltip element helper access to the static IElementHelper entrypoint",
                    before = "ITooltip variable getElementHelper() calls",
                    after = "IElementHelper.get()",
                    confidence = Confidence.HIGH,
                    ruleId = "jade-tooltip-elementhelper-static"
                )
            )
        }

        // Post-processing: clean up imports after all replacements
        content = cleanupImports(content, changes, file)

        if (!dryRun && content != originalContent) {
            file.writeText(content)
        }

        return changes
    }

    /**
     * Post-replacement import cleanup:
     * - Remove stale imports for classes that no longer exist in NeoForge
     * - Remove duplicate imports
     * - Add missing imports for classes introduced by replacements
     */
    private fun cleanupImports(content: String, changes: MutableList<Change>, file: Path): String {
        var result = content

        // Remove stale imports that reference classes removed or renamed in NeoForge
        val staleImports = listOf(
            "import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;",
            "import net.neoforged.neoforge.registries.ForgeRegistries;",
            "import net.neoforged.neoforge.registries.RegistryObject;",
            "import net.neoforged.neoforge.common.MinecraftForge;",
            "import net.neoforged.neoforge.network.simple.SimpleChannel;",
            "import net.neoforged.neoforge.network.NetworkRegistry;",
            "import net.neoforged.neoforge.network.NetworkDirection;",
            "import net.neoforged.neoforge.network.NetworkEvent;",
            "import net.neoforged.fml.common.Mod.EventBusSubscriber;",
            "import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;",
        )
        for (stale in staleImports) {
            if (result.contains(stale)) {
                result = removeImportLine(result, stale.removePrefix("import ").removeSuffix(";"))
            }
        }
        if (!result.contains("NetworkHooks.")) {
            result = removeImportLine(result, "net.neoforged.neoforge.network.NetworkHooks")
            result = removeImportLine(result, "net.minecraftforge.network.NetworkHooks")
        }

        if (!result.contains("ContextNbtProvider.")) {
            result = removeImportLine(result, "net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider")
        }
        if (result.contains("IGlobalLootModifier") || result.contains("GLOBAL_LOOT_MODIFIER_SERIALIZERS")) {
            result = result.replace("MapCodec<? extends ChunkGenerator>", "MapCodec<? extends IGlobalLootModifier>")
            result = result.replace("MapMapCodec", "MapCodec")
            if (!result.contains("ChunkGenerator")) {
                result = removeImportLine(result, "net.minecraft.world.level.chunk.ChunkGenerator")
            }
        }

        if (result.contains("import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;")) {
            result = result.replace(Regex("""\bLivingDamageEvent\.Post\b"""), "Post")
        }
        result = result.replace(
            Regex("""(?m)^[ \t]*//\s*(?:\[[^\]\r\n]+]\s*)?import\s+net\.(?:minecraftforge|neoforged)\.fml\.DistExecutor;\s*\r?\n"""),
            ""
        )
        result = result.replace(
            Regex("""(?m)^[ \t]*//\s*DistExecutor removed in NeoForge\s*\r?\n"""),
            ""
        )
        result = result.replace(
            "import net.neoforged.neoforge.event.entity.living.Post;",
            "import net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post;"
        )
        result = result.replace(
            Regex("""@Override\s*\r?\n\s*(@SuppressWarnings\("deprecation"\)\s*\r?\n\s*)@Override"""),
            "$1@Override"
        )

        // Add missing imports based on what symbols are used in the code
        val missingImports = mutableListOf<String>()

        if (result.contains("BuiltInRegistries.") && !result.contains("import net.minecraft.core.registries.BuiltInRegistries;")) {
            missingImports.add("import net.minecraft.core.registries.BuiltInRegistries;")
        }
        if (result.contains("NeoForgeRegistries.") && !result.contains("import net.neoforged.neoforge.registries.NeoForgeRegistries;")) {
            missingImports.add("import net.neoforged.neoforge.registries.NeoForgeRegistries;")
        }
        if (result.contains("IElementHelper.") && !result.contains("import snownee.jade.api.ui.IElementHelper;")) {
            missingImports.add("import snownee.jade.api.ui.IElementHelper;")
        }
        // ModContainer import is added by the AST pass when modifying @Mod constructors
        if (result.contains("DeferredHolder") && !result.contains("import net.neoforged.neoforge.registries.DeferredHolder;")) {
            missingImports.add("import net.neoforged.neoforge.registries.DeferredHolder;")
        }
        if (result.contains("@EventBusSubscriber") && !result.contains("import net.neoforged.fml.common.EventBusSubscriber;")) {
            missingImports.add("import net.neoforged.fml.common.EventBusSubscriber;")
        }
        if (result.contains("IPayloadContext") && !result.contains("import net.neoforged.neoforge.network.handling.IPayloadContext;")) {
            missingImports.add("import net.neoforged.neoforge.network.handling.IPayloadContext;")
        }
        if (result.contains("PacketDistributor.send") && !result.contains("import net.neoforged.neoforge.network.PacketDistributor;")) {
            missingImports.add("import net.neoforged.neoforge.network.PacketDistributor;")
        }
        if (result.contains("(ServerPlayer)") && !result.contains("import net.minecraft.server.level.ServerPlayer;")) {
            missingImports.add("import net.minecraft.server.level.ServerPlayer;")
        }
        if (result.contains("CustomPacketPayload") && !result.contains("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;")) {
            missingImports.add("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;")
        }
        if (result.contains("RenderGuiLayerEvent") && !result.contains("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;")) {
            missingImports.add("import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;")
        }
        if (result.contains("FastColor.ARGB32") && !result.contains("import net.minecraft.util.FastColor;")) {
            missingImports.add("import net.minecraft.util.FastColor;")
        }
        if (result.contains("SynchedEntityData.Builder") && !result.contains("import net.minecraft.network.syncher.SynchedEntityData;")) {
            missingImports.add("import net.minecraft.network.syncher.SynchedEntityData;")
        }
        if (result.contains("MapCodec") && !result.contains("import com.mojang.serialization.MapCodec;")) {
            missingImports.add("import com.mojang.serialization.MapCodec;")
        }
        if (result.contains("StreamCodec") && !result.contains("import net.minecraft.network.codec.StreamCodec;")) {
            missingImports.add("import net.minecraft.network.codec.StreamCodec;")
        }
        if (result.contains("RegistryFriendlyByteBuf") && !result.contains("import net.minecraft.network.RegistryFriendlyByteBuf;")) {
            missingImports.add("import net.minecraft.network.RegistryFriendlyByteBuf;")
        }
        if (result.contains("ByteBufCodecs.") && !result.contains("import net.minecraft.network.codec.ByteBufCodecs;")) {
            missingImports.add("import net.minecraft.network.codec.ByteBufCodecs;")
        }
        if (result.contains("CopyComponentsFunction.") && !result.contains("import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;")) {
            missingImports.add("import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;")
        }
        if (result.contains("CopyCustomDataFunction.") && !result.contains("import net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction;")) {
            missingImports.add("import net.minecraft.world.level.storage.loot.functions.CopyCustomDataFunction;")
        }
        if (result.contains("DataComponents.") && !result.contains("import net.minecraft.core.component.DataComponents;")) {
            missingImports.add("import net.minecraft.core.component.DataComponents;")
        }
        if (result.contains("DyedItemColor") && !result.contains("import net.minecraft.world.item.component.DyedItemColor;")) {
            missingImports.add("import net.minecraft.world.item.component.DyedItemColor;")
        }
        if (result.contains("DataComponentIngredient.") && !result.contains("import net.neoforged.neoforge.common.crafting.DataComponentIngredient;")) {
            missingImports.add("import net.neoforged.neoforge.common.crafting.DataComponentIngredient;")
        }
        if (result.contains("RecipeCategory.") && !result.contains("import net.minecraft.data.recipes.RecipeCategory;") &&
            !result.contains("import net.minecraft.data.recipes.*;")) {
            missingImports.add("import net.minecraft.data.recipes.RecipeCategory;")
        }
        if (result.contains("PotionContents") && !result.contains("import net.minecraft.world.item.alchemy.PotionContents;")) {
            missingImports.add("import net.minecraft.world.item.alchemy.PotionContents;")
        }
        if (result.contains("BlockBehaviour.Properties") && !result.contains("import net.minecraft.world.level.block.state.BlockBehaviour;")) {
            missingImports.add("import net.minecraft.world.level.block.state.BlockBehaviour;")
        }
        if (result.contains("Tesselator.getInstance()") && !result.contains("import com.mojang.blaze3d.vertex.Tesselator;")) {
            missingImports.add("import com.mojang.blaze3d.vertex.Tesselator;")
        }
        if (Regex("\\bMeshData\\b").containsMatchIn(result) &&
            !result.contains("import com.mojang.blaze3d.vertex.MeshData;") &&
            !result.contains("import com.mojang.blaze3d.vertex.*;")) {
            missingImports.add("import com.mojang.blaze3d.vertex.MeshData;")
        }
        if (result.contains("EntityTypeTags.") && !result.contains("import net.minecraft.tags.EntityTypeTags;")) {
            missingImports.add("import net.minecraft.tags.EntityTypeTags;")
        }
        if (result.contains("HolderLookup.Provider") && !result.contains("import net.minecraft.core.HolderLookup;")) {
            missingImports.add("import net.minecraft.core.HolderLookup;")
        }
        if (result.contains("SavedData.Factory") && !result.contains("import net.minecraft.world.level.saveddata.SavedData;")) {
            missingImports.add("import net.minecraft.world.level.saveddata.SavedData;")
        }
        if (result.contains("AdvancementHolder") && !result.contains("import net.minecraft.advancements.AdvancementHolder;")) {
            missingImports.add("import net.minecraft.advancements.AdvancementHolder;")
        }
        if (result.contains("ClientInformation.createDefault()") && !result.contains("import net.minecraft.server.level.ClientInformation;")) {
            missingImports.add("import net.minecraft.server.level.ClientInformation;")
        }
        if (result.contains("CommonListenerCookie.createInitial(") && !result.contains("import net.minecraft.server.network.CommonListenerCookie;")) {
            missingImports.add("import net.minecraft.server.network.CommonListenerCookie;")
        }
        if (result.contains("GameType.SURVIVAL") && !result.contains("import net.minecraft.world.level.GameType;")) {
            missingImports.add("import net.minecraft.world.level.GameType;")
        }
        if (result.contains("InteractionHand.MAIN_HAND") && !result.contains("import net.minecraft.world.InteractionHand;")) {
            missingImports.add("import net.minecraft.world.InteractionHand;")
        }
        if (result.contains("EquipmentSlot.MAINHAND") && !result.contains("import net.minecraft.world.entity.EquipmentSlot;")) {
            missingImports.add("import net.minecraft.world.entity.EquipmentSlot;")
        }
        if (result.contains("ParticleType<?>") && !result.contains("import net.minecraft.core.particles.ParticleType;")) {
            missingImports.add("import net.minecraft.core.particles.ParticleType;")
        }
        if (result.contains("SpawnPlacementTypes.") &&
            !result.contains("import net.minecraft.world.entity.SpawnPlacementTypes;") &&
            !result.contains("import net.minecraft.world.entity.*;")) {
            missingImports.add("import net.minecraft.world.entity.SpawnPlacementTypes;")
        }
        if (result.contains("DeferredHolder<Fluid,") && !result.contains("import net.minecraft.world.level.material.Fluid;")) {
            missingImports.add("import net.minecraft.world.level.material.Fluid;")
        }
        if (result.contains("@Nullable LivingEntity") && !result.contains("import net.minecraft.world.entity.LivingEntity;")) {
            missingImports.add("import net.minecraft.world.entity.LivingEntity;")
        }
        if (Regex("\\bLevelReader\\b").containsMatchIn(result) && !result.contains("import net.minecraft.world.level.LevelReader;")) {
            missingImports.add("import net.minecraft.world.level.LevelReader;")
        }
        if (Regex("\\bHolder<").containsMatchIn(result) && !result.contains("import net.minecraft.core.Holder;")) {
            missingImports.add("import net.minecraft.core.Holder;")
        }
        if (Regex("\\bRegistryAccess\\b").containsMatchIn(result) && !result.contains("import net.minecraft.core.RegistryAccess;")) {
            missingImports.add("import net.minecraft.core.RegistryAccess;")
        }
        if (result.contains("ResourceKey<Level>") && !result.contains("import net.minecraft.resources.ResourceKey;")) {
            missingImports.add("import net.minecraft.resources.ResourceKey;")
        }
        if (result.contains("ResourceKey<Level>") && !result.contains("import net.minecraft.world.level.Level;")) {
            missingImports.add("import net.minecraft.world.level.Level;")
        }
        if (Regex("\\bRegistries\\.").containsMatchIn(result) && !result.contains("import net.minecraft.core.registries.Registries;")) {
            missingImports.add("import net.minecraft.core.registries.Registries;")
        }
        if (result.contains("ServerLifecycleHooks.") && !result.contains("import net.neoforged.neoforge.server.ServerLifecycleHooks;")) {
            missingImports.add("import net.neoforged.neoforge.server.ServerLifecycleHooks;")
        }
        if (result.contains("RecipeHolder::value") && !result.contains("import net.minecraft.world.item.crafting.RecipeHolder;")) {
            missingImports.add("import net.minecraft.world.item.crafting.RecipeHolder;")
        }
        if (Regex("\\bSingleRecipeInput\\b").containsMatchIn(result) && !result.contains("import net.minecraft.world.item.crafting.SingleRecipeInput;")) {
            missingImports.add("import net.minecraft.world.item.crafting.SingleRecipeInput;")
        }
        if (Regex("\\bCacheableFunction\\b").containsMatchIn(result) && !result.contains("import net.minecraft.commands.CacheableFunction;")) {
            missingImports.add("import net.minecraft.commands.CacheableFunction;")
        }
        if (Regex("\\bCraftingInput\\b").containsMatchIn(result) && !result.contains("import net.minecraft.world.item.crafting.CraftingInput;")) {
            missingImports.add("import net.minecraft.world.item.crafting.CraftingInput;")
        }
        if (Regex("\\bRegistry<").containsMatchIn(result) && !result.contains("import net.minecraft.core.Registry;")) {
            missingImports.add("import net.minecraft.core.Registry;")
        }
        if (Regex("\\bSupplier<").containsMatchIn(result) && !result.contains("import java.util.function.Supplier;")) {
            missingImports.add("import java.util.function.Supplier;")
        }


        if (missingImports.isNotEmpty()) {
            // Insert after the last existing import line
            val lines = result.lines().toMutableList()
            val lastImportIdx = lines.indexOfLast { it.trimStart().startsWith("import ") }
            if (lastImportIdx >= 0) {
                for ((i, imp) in missingImports.distinct().withIndex()) {
                    lines.add(lastImportIdx + 1 + i, imp)
                }
                result = lines.joinToString("\n")
            } else {
                val packageIdx = lines.indexOfFirst { it.trimStart().startsWith("package ") }
                var insertIdx = if (packageIdx >= 0) packageIdx + 1 else 0
                if (packageIdx >= 0 && insertIdx < lines.size && lines[insertIdx].isNotBlank()) {
                    lines.add(insertIdx, "")
                    insertIdx++
                }
                for ((i, imp) in missingImports.distinct().withIndex()) {
                    lines.add(insertIdx + i, imp)
                }
                val afterImports = insertIdx + missingImports.distinct().size
                if (afterImports < lines.size && lines[afterImports].isNotBlank()) {
                    lines.add(afterImports, "")
                }
                result = lines.joinToString("\n")
            }
        }

        return dedupeImports(result)
    }

    private fun migrateRemainingRegistryObjectWildcardHolders(source: String): String {
        if (!source.contains("RegistryObject<")) return source
        return Regex("""RegistryObject\s*<\s*\?\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*>""")
            .replace(source) { match ->
                val type = match.groupValues[1]
                "DeferredHolder<$type, ? extends $type>"
            }
    }

    private fun migrateParticleOptionsCodecs(source: String): String {
        if (!source.contains("ParticleOptions.Deserializer") ||
            !source.contains("implements ParticleOptions") ||
            !source.contains("writeToNetwork")) {
            return source
        }

        val classMatch = Regex("""public\s+class\s+([A-Za-z_$][\w$]*)\s+implements\s+ParticleOptions\s*\{""").find(source)
            ?: return source
        val className = classMatch.groupValues[1]
        val fields = Regex("""(?m)^[ \t]*public\s+final\s+(int)\s+([A-Za-z_$][\w$]*);\s*$""")
            .findAll(source)
            .map { ParticleField(it.groupValues[1], it.groupValues[2]) }
            .toList()
        if (fields.isEmpty()) return source

        var result = source
        val recordParams = fields.joinToString(", ") { "${it.type} ${it.name}" }
        result = result.replace(
            Regex("""public\s+class\s+${Regex.escape(className)}\s+implements\s+ParticleOptions\s*\{"""),
            "public record $className($recordParams) implements ParticleOptions {"
        )
        result = Regex("""(?m)^[ \t]*public\s+final\s+int\s+[A-Za-z_$][\w$]*;\s*\r?\n""").replace(result, "")

        val constructorParams = fields.joinToString("""\s*,\s*""") { """${Regex.escape(it.type)}\s+${Regex.escape(it.name)}""" }
        val constructorAssignments = fields.joinToString("""\s*""") {
            """this\.${Regex.escape(it.name)}\s*=\s*${Regex.escape(it.name)}\s*;"""
        }
        result = Regex(
            """\r?\n\s*public\s+${Regex.escape(className)}\s*\(\s*$constructorParams\s*\)\s*\{\s*$constructorAssignments\s*\}\s*"""
        ).replace(result, "\n")

        val codecSource = particleCodecSource(className, fields)
        result = Regex(
            """(?s)\r?\n\s*public\s+static\s+Codec<${Regex.escape(className)}>\s+[A-Za-z_$][\w$]*\s*\(\s*\)\s*\{\s*return\s+RecordCodecBuilder\.create\s*\(\s*\(?\s*instance\s*\)?\s*->\s*instance\.group\s*\([\s\S]*?\.apply\s*\(\s*instance\s*,\s*${Regex.escape(className)}::new\s*\)\s*\)\s*;\s*\}\s*"""
        ).replace(result, "\n\n$codecSource\n")

        result = Regex(
            """(?s)\r?\n\s*@Override\s*\r?\n\s*public\s+void\s+writeToNetwork\s*\([^)]*\)\s*\{.*?\n\s*\}\s*"""
        ).replace(result, "\n")
        result = Regex(
            """(?s)\r?\n\s*@Nonnull\s*\r?\n\s*@Override\s*\r?\n\s*public\s+String\s+writeToString\s*\(\s*\)\s*\{.*?\n\s*\}\s*"""
        ).replace(result, "\n")
        result = Regex(
            """(?s)\r?\n\s*@Override\s*\r?\n\s*public\s+String\s+writeToString\s*\(\s*\)\s*\{.*?\n\s*\}\s*"""
        ).replace(result, "\n")
        result = removeParticleDeserializerClass(result, className)

        result = removeImportLine(result, "com.mojang.brigadier.StringReader")
        result = removeImportLine(result, "com.mojang.brigadier.exceptions.CommandSyntaxException")
        result = removeImportLine(result, "net.minecraft.network.FriendlyByteBuf")
        return result
    }

    private fun particleCodecSource(className: String, fields: List<ParticleField>): String {
        val codecFields = fields.joinToString(",\n") {
            "\t\tCodec.INT.fieldOf(\"${it.name}\").forGetter((obj) -> obj.${it.name})"
        }
        val streamFields = fields.joinToString(",\n") {
            "\t\tByteBufCodecs.VAR_INT, p -> p.${it.name}"
        }
        return """
	public static MapCodec<$className> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
$codecFields
	).apply(instance, $className::new));

	public static StreamCodec<? super RegistryFriendlyByteBuf, $className> STREAM_CODEC = StreamCodec.composite(
$streamFields,
		$className::new
	);
""".trimEnd()
    }

    private fun removeParticleDeserializerClass(source: String, className: String): String {
        val marker = "public static class Deserializer implements ParticleOptions.Deserializer<$className>"
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) return source
        val start = source.lastIndexOf('\n', markerIndex).let { if (it >= 0) it else markerIndex }
        val openBrace = source.indexOf('{', markerIndex)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return source
        val end = if (closeBrace + 1 < source.length && source[closeBrace + 1] == '\n') closeBrace + 2 else closeBrace + 1
        return source.removeRange(start, end)
    }

    private fun migrateParticleTypeRegistrations(source: String): String {
        if (!source.contains("new ParticleType<>(false, new") || !source.contains(".Deserializer()")) {
            return source
        }
        var result = source
        val customTypes = Regex("""new\s+([A-Za-z_$][\w$]*)\.Deserializer\s*\(\s*\)""")
            .findAll(result)
            .map { it.groupValues[1] }
            .toSet()

        result = Regex("""new\s+ParticleType<>\(\s*false\s*,\s*new\s+[A-Za-z_$][\w$]*\.Deserializer\s*\(\s*\)\s*\)""")
            .replace(result, "new ParticleType<>(false)")
        result = Regex("""DeferredHolder<ParticleType<([A-Za-z_$][\w$]*)>,\s*ParticleType<\1>>""")
            .replace(result, "DeferredHolder<ParticleType<?>, ParticleType<$1>>")
        result = Regex("""public\s+Codec<([A-Za-z_$][\w$]*)>\s+codec\s*\(\s*\)""")
            .replace(result, "public MapCodec<$1> codec()")
        result = Regex("""return\s+([A-Za-z_$][\w$]*)\.codec[A-Za-z_$][\w$]*\s*\(\s*\)\s*;""")
            .replace(result, "return $1.CODEC;")

        for (typeName in customTypes) {
            val streamSignature = "StreamCodec<? super RegistryFriendlyByteBuf, $typeName> streamCodec()"
            if (result.contains(streamSignature)) continue
            val codecBlock = Regex(
                """@Override\s*\r?\n\s*public\s+MapCodec<${Regex.escape(typeName)}>\s+codec\s*\(\s*\)\s*\{\s*return\s+${Regex.escape(typeName)}\.CODEC;\s*\}"""
            )
            result = codecBlock.replace(result) { match ->
                match.value + """


		@Override
		public StreamCodec<? super RegistryFriendlyByteBuf, $typeName> streamCodec() {
			return $typeName.STREAM_CODEC;
		}"""
            }
        }

        if (!Regex("""\bCodec\s*[<.]""").containsMatchIn(result)) {
            result = removeImportLine(result, "com.mojang.serialization.Codec")
        }
        return result
    }

    private fun migratePartialNbtIngredients(source: String): String {
        if (!source.contains("PartialNBTIngredient")) return source

        var result = source
        var changed = false

        result = Regex(
            """(?s)public\s+final\s+PartialNBTIngredient\s+([A-Za-z_$][\w$]*)\s*\(\s*Item\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+PartialNBTIngredient\.of\s*\(\s*\2\s*,\s*Util\.make\s*\(\s*\(\s*\)\s*->\s*\{\s*CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+CompoundTag\s*\(\s*\)\s*;\s*\3\.putInt\s*\(\s*ItemStack\.TAG_DAMAGE\s*,\s*\2\.getMaxDamage\s*\(\s*\)\s*\)\s*;\s*return\s+\3\s*;\s*\}\s*\)\s*\)\s*;\s*\}"""
        ).replace(result) { match ->
            changed = true
            val method = match.groupValues[1]
            val item = match.groupValues[2]
            "public final Ingredient $method(Item $item) {\n\t\treturn DataComponentIngredient.of(false, DataComponents.DAMAGE, $item.getMaxDamage(), $item);\n\t}"
        }

        result = Regex(
            """(?s)public\s+final\s+PartialNBTIngredient\s+([A-Za-z_$][\w$]*)\s*\(\s*Potion\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+PartialNBTIngredient\.of\s*\(\s*Items\.POTION\s*,\s*Util\.make\s*\(\s*\(\s*\)\s*->\s*\{\s*CompoundTag\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+CompoundTag\s*\(\s*\)\s*;\s*\3\.putString\s*\(\s*"Potion"\s*,\s*BuiltInRegistries\.POTION\.getKey\s*\(\s*\2\s*\)\.toString\s*\(\s*\)\s*\)\s*;\s*return\s+\3\s*;\s*\}\s*\)\s*\)\s*;\s*\}"""
        ).replace(result) { match ->
            changed = true
            val method = match.groupValues[1]
            val potion = match.groupValues[2]
            "public final Ingredient $method(Holder<Potion> $potion) {\n\t\treturn DataComponentIngredient.of(false, DataComponents.POTION_CONTENTS, new PotionContents($potion), Items.POTION);\n\t}"
        }

        if (!changed) return source
        if (!usesSymbolOutsideImports(result, "PartialNBTIngredient")) {
            result = removeImportLine(result, "net.neoforged.neoforge.common.crafting.PartialNBTIngredient")
        }
        if (!usesSymbolOutsideImports(result, "Util")) result = removeImportLine(result, "net.minecraft.Util")
        if (!usesSymbolOutsideImports(result, "CompoundTag")) result = removeImportLine(result, "net.minecraft.nbt.CompoundTag")
        if (!usesSymbolOutsideImports(result, "ItemStack")) result = removeImportLine(result, "net.minecraft.world.item.ItemStack")
        return result
    }

    private fun migrateSingleItemRecipeBuilderResults(source: String): String {
        if (!source.contains("SingleItemRecipeBuilder.Result") ||
            !source.contains(".accept(stonecutting(")) {
            return source
        }

        var result = source
        result = Regex("""(?m)^([ \t]*)([A-Za-z_$][\w$]*)\.accept\(\s*stonecutting\(([^;\r\n]+)\)\s*\);\s*$""")
            .replace(result) { match ->
                "${match.groupValues[1]}stonecutting(${match.groupValues[2]}, ${match.groupValues[3].trim()});"
            }

        result = Regex(
            """(?s)\r?\n\s*private\s+static\s+Wrapper\s+stonecutting\s*\(\s*ItemLike\s+input\s*,\s*ItemLike\s+output\s*\)\s*\{\s*return\s+stonecutting\s*\(\s*input\s*,\s*output\s*,\s*1\s*\)\s*;\s*\}\s*\r?\n\s*private\s+static\s+Wrapper\s+stonecutting\s*\(\s*ItemLike\s+input\s*,\s*ItemLike\s+output\s*,\s*int\s+count\s*\)\s*\{\s*return\s+new\s+Wrapper\s*\(\s*getIdFor\s*\(\s*input\.asItem\s*\(\s*\)\s*,\s*output\.asItem\s*\(\s*\)\s*\)\s*,\s*Ingredient\.of\s*\(\s*input\s*\)\s*,\s*output\.asItem\s*\(\s*\)\s*,\s*count\s*\)\s*;\s*\}\s*"""
        ).replace(result, """

	private static void stonecutting(RecipeOutput recipe, ItemLike input, ItemLike output) {
		stonecutting(recipe, input, output, 1);
	}

	private static void stonecutting(RecipeOutput recipe, ItemLike input, ItemLike output, int count) {
		SingleItemRecipeBuilder.stonecutting(Ingredient.of(input), RecipeCategory.BUILDING_BLOCKS, output.asItem(), count).unlockedBy("has_block", has(input)).save(recipe, getIdFor(input, output));
	}
""")

        result = result.replace(
            "private static ResourceLocation getIdFor(Item input, Item output)",
            "private static ResourceLocation getIdFor(ItemLike input, ItemLike output)"
        )
        result = result.replace("BuiltInRegistries.ITEM.getKey(input).getPath()", "BuiltInRegistries.ITEM.getKey(input.asItem()).getPath()")
        result = result.replace("BuiltInRegistries.ITEM.getKey(output).getPath()", "BuiltInRegistries.ITEM.getKey(output.asItem()).getPath()")
        result = removeSingleItemRecipeWrapper(result)

        if (!result.contains("Criterion<InventoryChangeTrigger.TriggerInstance> has(ItemLike item)")) {
            val insertAt = result.lastIndexOf("\n}")
            if (insertAt >= 0) {
                val hasMethod = """

	protected static net.minecraft.advancements.Criterion<net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance> has(ItemLike item) {
		return net.minecraft.advancements.CriteriaTriggers.INVENTORY_CHANGED.createCriterion(new net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance(
			java.util.Optional.empty(),
			net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance.Slots.ANY,
			java.util.Collections.singletonList(net.minecraft.advancements.critereon.ItemPredicate.Builder.item().of(item).build())
		));
	}
"""
                result = result.substring(0, insertAt) + hasMethod + result.substring(insertAt)
            }
        }

        if (!usesSymbolOutsideImports(result, "JsonObject")) result = removeImportLine(result, "com.google.gson.JsonObject")
        if (!usesSymbolOutsideImports(result, "RecipeSerializer")) result = removeImportLine(result, "net.minecraft.world.item.crafting.RecipeSerializer")
        if (!usesSymbolOutsideImports(result, "Item")) result = removeImportLine(result, "net.minecraft.world.item.Item")
        if (!usesSymbolOutsideImports(result, "Nullable")) result = removeImportLine(result, "org.jetbrains.annotations.Nullable")
        if (!usesSymbolOutsideImports(result, "Consumer")) result = removeImportLine(result, "java.util.function.Consumer")
        return result
    }

    private fun removeSingleItemRecipeWrapper(source: String): String {
        val classMarker = "public static class Wrapper extends SingleItemRecipeBuilder.Result"
        val markerIndex = source.indexOf(classMarker)
        if (markerIndex < 0) return source
        val commentStart = source.lastIndexOf("// Wrapper", markerIndex)
        val lineStart = source.lastIndexOf('\n', if (commentStart >= 0) commentStart else markerIndex).let {
            if (it >= 0) it else if (commentStart >= 0) commentStart else markerIndex
        }
        val openBrace = source.indexOf('{', markerIndex)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return source
        val end = if (closeBrace + 1 < source.length && source[closeBrace + 1] == '\n') closeBrace + 2 else closeBrace + 1
        return source.removeRange(lineStart, end)
    }

    private fun collectLegacyDyeableLeatherItemClasses(javaFiles: List<Path>): Set<String> =
        javaFiles.mapNotNull { file ->
            val source = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            Regex(
                """(?s)\bclass\s+([A-Za-z_$][\w$]*)\b[^{;]*\bimplements\b[^{;]*\b(?:net\.minecraft\.world\.item\.)?DyeableLeatherItem\b"""
            ).findAll(source).map { it.groupValues[1] }.toList()
        }.flatten().toSet()

    private fun migrateDyeableLeatherItemColors(
        source: String,
        legacyDyeableLeatherItemClasses: Set<String> = emptySet()
    ): String {
        val sourceHasLegacyColorMethods = source.contains("DataComponents.CUSTOM_DATA") &&
            Regex("""\bpublic\s+boolean\s+hasCustomColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(source) &&
            Regex("""\bpublic\s+void\s+setColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*,\s*int\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(source)
        val sourceHasKnownDyeableColorCallSites = legacyDyeableLeatherItemClasses.any { className ->
            source.contains("instanceof $className") && source.contains(".getColor(")
        }
        if (!source.contains("DyeableLeatherItem") && !sourceHasLegacyColorMethods && !sourceHasKnownDyeableColorCallSites) {
            return source
        }

        var result = source
        result = migrateDyeableLeatherGetColorCallSites(result)
        result = migrateDyeableLeatherInstanceofGetColorCallSites(result, legacyDyeableLeatherItemClasses)
        result = result.replace(Regex("""extends\s+ArmorItem\s+implements\s+DyeableLeatherItem"""), "extends ArmorItem")
        result = result.replace(Regex("""\s+implements\s+DyeableLeatherItem\b"""), "")
        result = result.replace(Regex("""implements\s+DyeableLeatherItem\s*,\s*"""), "implements ")
        result = result.replace(Regex(""",\s*DyeableLeatherItem\b"""), "")
        result = removeImportLine(result, "net.minecraft.nbt.CompoundTag")
        result = removeImportLine(result, "net.minecraft.world.item.DyeableLeatherItem")

        val resultHasLegacyColorMethods = result.contains("DataComponents.CUSTOM_DATA") &&
            Regex("""\bpublic\s+boolean\s+hasCustomColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(result) &&
            Regex("""\bpublic\s+void\s+setColor\s*\(\s*ItemStack\s+[A-Za-z_$][\w$]*\s*,\s*int\s+[A-Za-z_$][\w$]*\s*\)""").containsMatchIn(result)
        if (!resultHasLegacyColorMethods) {
            return result
        }

        if (!result.contains("DEFAULT_COLOR")) {
            val withDefaultColor = insertDyeableDefaultColor(result)
            if (withDefaultColor == result) return source
            result = withDefaultColor
        }

        val firstMethod = Regex("""\bpublic\s+boolean\s+hasCustomColor\s*\(""").find(result) ?: return result
        val insertAt = annotationStartBefore(result, firstMethod.range.first)
        var working = result
        var searchFrom = insertAt
        for (methodName in listOf("hasCustomColor", "getColor", "clearColor", "getColor", "removeColor", "setColor")) {
            val removed = removeFirstJavaMethodByName(working, methodName, searchFrom)
            working = removed.first
            searchFrom = removed.second ?: searchFrom
        }

        val componentMethods = """

	public boolean hasCustomColor(ItemStack stack) {
		return stack.has(DataComponents.DYED_COLOR);
	}

	public int getColor(ItemStack stack) {
		return DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);
	}

	public void clearColor(ItemStack stack) {
		this.removeColor(stack);
	}

	public int getColor(ItemStack stack, int type) {
		return type == 0 ? 0xFFFFFF : DyedItemColor.getOrDefault(stack, DEFAULT_COLOR);
	}

	public void removeColor(ItemStack stack) {
		stack.remove(DataComponents.DYED_COLOR);
	}

	public void setColor(ItemStack stack, int color) {
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(color, true));
	}
""".trimEnd()

        result = working.substring(0, insertAt) + componentMethods + working.substring(insertAt)
        if (!usesSymbolOutsideImports(result, "CompoundTag")) result = removeImportLine(result, "net.minecraft.nbt.CompoundTag")
        if (!usesSymbolOutsideImports(result, "CustomData")) result = removeImportLine(result, "net.minecraft.world.item.component.CustomData")
        return result
    }

    private fun migrateDyeableLeatherGetColorCallSites(source: String): String =
        Regex(
            """\(\(\s*(?:net\.minecraft\.world\.item\.)?DyeableLeatherItem\s*\)\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.getItem\(\)\s*\)\.getColor\(\s*\1\s*\)"""
        ).replace(source) { match ->
            "DyedItemColor.getOrDefault(${match.groupValues[1]}, DyedItemColor.LEATHER_COLOR)"
        }

    private fun migrateDyeableLeatherInstanceofGetColorCallSites(
        source: String,
        legacyDyeableLeatherItemClasses: Set<String>
    ): String {
        if (legacyDyeableLeatherItemClasses.isEmpty() || !source.contains(".getColor(")) return source
        var result = source
        legacyDyeableLeatherItemClasses.forEach { className ->
            val instanceofPattern = Regex(
                """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*(?:\([^)]*\))?)*)\.getItem\(\)\s+instanceof\s+(?:[A-Za-z_$][\w$]*\.)*${Regex.escape(className)}\s+([A-Za-z_$][\w$]*)"""
            )
            var cursor = 0
            while (true) {
                val match = instanceofPattern.find(result, cursor) ?: break
                val stackExpression = match.groupValues[1]
                val itemVariable = match.groupValues[2]
                val openBrace = result.indexOf('{', match.range.last)
                if (openBrace < 0) {
                    cursor = match.range.last + 1
                    continue
                }
                val closeBrace = findMatchingBrace(result, openBrace)
                if (closeBrace < 0) {
                    cursor = match.range.last + 1
                    continue
                }
                val body = result.substring(openBrace + 1, closeBrace)
                val colorCallPattern = Regex("""\b${Regex.escape(itemVariable)}\.getColor\(\s*${Regex.escape(stackExpression)}\s*\)""")
                val migratedBody = colorCallPattern.replace(body) {
                    "DyedItemColor.getOrDefault($stackExpression, DyedItemColor.LEATHER_COLOR)"
                }
                if (migratedBody == body) {
                    cursor = closeBrace + 1
                    continue
                }
                result = result.substring(0, openBrace + 1) + migratedBody + result.substring(closeBrace)
                cursor = openBrace + 1 + migratedBody.length
            }
        }
        return result
    }

    private fun insertDyeableDefaultColor(source: String): String {
        if (source.contains("DEFAULT_COLOR")) return source
        val classMatch = Regex(
            """(?m)^[ \t]*(?:(?:public|protected|private|abstract|final|static)\s+)*class\s+[A-Za-z_$][\w$]*\b[^{;]*\{"""
        ).find(source) ?: return source
        val insertAt = classMatch.range.last + 1
        return source.substring(0, insertAt) +
            "\n\tpublic static final int DEFAULT_COLOR = 0xFFBDCFD9;\n" +
            source.substring(insertAt)
    }

    private fun migrateTierSortingRegistryTiers(
        source: String,
        tierIncorrectTagResources: MutableList<TierIncorrectTagResource>,
        projectDir: Path,
        sourceFile: Path,
        errors: MutableList<String>
    ): String {
        if (!source.contains("TierSortingRegistry.registerTier(") &&
            !source.contains("TierSortingRegistry.isCorrectTierForDrops(")) {
            return source
        }

        var result = source
        var changed = false

        if (result.contains("TierSortingRegistry.registerTier(")) {
            val marker = "TierSortingRegistry.registerTier"
            val builder = StringBuilder()
            var cursor = 0

            while (true) {
                val markerIndex = result.indexOf(marker, cursor)
                if (markerIndex < 0) break
                val openParen = result.indexOf('(', markerIndex + marker.length)
                if (openParen < 0) break
                val closeParen = findMatchingDelimiter(result, openParen, '(', ')')
                if (closeParen < 0) break

                val inside = result.substring(openParen + 1, closeParen)
                val replacement = migrateTierSortingRegistryCall(inside, tierIncorrectTagResources, projectDir, sourceFile, errors)
                if (replacement == null) {
                    builder.append(result, cursor, closeParen + 1)
                } else {
                    builder.append(result, cursor, markerIndex)
                    builder.append(replacement)
                    changed = true
                }
                cursor = closeParen + 1
            }

            builder.append(result, cursor, result.length)
            result = builder.toString()
        }

        val beforeDropChecks = result
        result = migrateTierSortingRegistryDropChecks(result)
        if (result != beforeDropChecks) {
            changed = true
        }

        if (!changed) return source

        result = removeImportLine(result, "net.neoforged.neoforge.common.TierSortingRegistry")
        if (!usesSymbolOutsideImports(result, "List")) {
            result = removeImportLine(result, "java.util.List")
        }
        if (!usesSymbolOutsideImports(result, "BlockTags")) {
            result = removeImportLine(result, "net.minecraft.tags.BlockTags")
        }
        return result
    }

    private fun migrateTierSortingRegistryDropChecks(source: String): String {
        val marker = "TierSortingRegistry.isCorrectTierForDrops"
        if (!source.contains(marker)) return source

        val builder = StringBuilder()
        var cursor = 0
        while (true) {
            val markerIndex = source.indexOf(marker, cursor)
            if (markerIndex < 0) break
            val openParen = source.indexOf('(', markerIndex + marker.length)
            if (openParen < 0) break
            val closeParen = findMatchingDelimiter(source, openParen, '(', ')')
            if (closeParen < 0) break

            val args = splitTopLevelArguments(source.substring(openParen + 1, closeParen))
            if (args.size != 2) {
                builder.append(source, cursor, closeParen + 1)
            } else {
                builder.append(source, cursor, markerIndex)
                builder.append(tierSortingDropCheckExpression(args[0], args[1]))
            }
            cursor = closeParen + 1
        }
        builder.append(source, cursor, source.length)
        return builder.toString()
    }

    private fun tierSortingDropCheckExpression(tierExpression: String, stateExpression: String): String {
        val state = stateExpression.trim()
        val mineableTag = listOf(
            "MINEABLE_WITH_AXE",
            "MINEABLE_WITH_SHOVEL",
            "MINEABLE_WITH_HOE"
        ).joinToString(" : ") { tag ->
            "$state.is(net.minecraft.tags.BlockTags.$tag) ? net.minecraft.tags.BlockTags.$tag"
        } + " : net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE"
        return "(${tierExpression.trim()}).createToolProperties($mineableTag).isCorrectForDrops($state)"
    }

    private fun migrateTierSortingRegistryCall(
        callInside: String,
        tierIncorrectTagResources: MutableList<TierIncorrectTagResource>,
        projectDir: Path,
        sourceFile: Path,
        errors: MutableList<String>
    ): String? {
        val args = splitTopLevelArguments(callInside)
        if (args.isEmpty()) return null
        val simpleTier = args.first().trim()
        val simpleArgs = simpleTierConstructorArguments(simpleTier) ?: return null
        if (simpleArgs.size !in 6..7) return null

        val oldTagIndex = if (simpleArgs.size == 7) 5 else 0
        val oldNeedsTag = simpleArgs[oldTagIndex].trim()
        val toolName = customToolNameFromNeedsTag(oldNeedsTag)
        val miningLevel = if (simpleArgs.size == 7) {
            simpleArgs[0].trim().toIntOrNull()
        } else {
            miningLevelFromAfterTierList(args.getOrNull(2))
        }
        val incorrectTag = toolName?.let { incorrectToolTagExpression(oldNeedsTag, it) }
            ?: if (simpleArgs.size == 7) incorrectBlockTagForMiningLevel(simpleArgs[0].trim()) else oldNeedsTag
        if (toolName != null && miningLevel != null) {
            val namespace = namespaceFromTagExpression(oldNeedsTag, projectDir, sourceFile)
            if (namespace == null) {
                val relative = projectDir.relativize(sourceFile).toString().replace('\\', '/')
                errors.add("Cannot derive namespace for custom tool tier tag '$oldNeedsTag' in $relative")
                return null
            }
            tierIncorrectTagResources.add(
                TierIncorrectTagResource(
                    namespace = namespace,
                    path = "incorrect_for_${toolName}_tool",
                    vanillaReference = vanillaIncorrectTagForMiningLevel(miningLevel),
                    sourceTagExpression = oldNeedsTag
                )
            )
        }
        val uses = simpleArgs[if (simpleArgs.size == 7) 1 else 1].trim()
        val speed = simpleArgs[if (simpleArgs.size == 7) 2 else 2].trim()
        val attackDamage = simpleArgs[if (simpleArgs.size == 7) 3 else 3].trim()
        val enchantment = simpleArgs[if (simpleArgs.size == 7) 4 else 4].trim()
        val repair = repairIngredientForTool(toolName, simpleArgs[if (simpleArgs.size == 7) 6 else 5].trim())
        return "new SimpleTier($incorrectTag, $uses, $speed, $attackDamage, $enchantment, $repair)"
    }

    private fun simpleTierConstructorArguments(expression: String): List<String>? {
        val match = Regex("""new\s+SimpleTier\s*\(""").find(expression) ?: return null
        val openParen = expression.indexOf('(', match.range.first)
        if (openParen < 0) return null
        val closeParen = findMatchingDelimiter(expression, openParen, '(', ')')
        if (closeParen < 0) return null
        return splitTopLevelArguments(expression.substring(openParen + 1, closeParen))
    }

    private fun customToolNameFromNeedsTag(tagExpression: String): String? =
        Regex("""needs_([a-z0-9_]+)_tool""").find(tagExpression)?.groupValues?.get(1)

    private fun incorrectBlockTagForMiningLevel(levelText: String): String =
        when (levelText.toIntOrNull()) {
            null -> "BlockTags.INCORRECT_FOR_DIAMOND_TOOL"
            0 -> "BlockTags.INCORRECT_FOR_WOODEN_TOOL"
            1 -> "BlockTags.INCORRECT_FOR_STONE_TOOL"
            2 -> "BlockTags.INCORRECT_FOR_IRON_TOOL"
            3 -> "BlockTags.INCORRECT_FOR_DIAMOND_TOOL"
            else -> "BlockTags.INCORRECT_FOR_NETHERITE_TOOL"
        }

    private fun vanillaIncorrectTagForMiningLevel(level: Int): String =
        when (level) {
            0 -> "#minecraft:incorrect_for_wooden_tool"
            1 -> "#minecraft:incorrect_for_stone_tool"
            2 -> "#minecraft:incorrect_for_iron_tool"
            3 -> "#minecraft:incorrect_for_diamond_tool"
            else -> "#minecraft:incorrect_for_netherite_tool"
        }

    private fun miningLevelFromAfterTierList(afterTierExpression: String?): Int? {
        val tiers = afterTierExpression ?: return null
        return Regex("""\bTiers\.(WOOD|STONE|IRON|DIAMOND|NETHERITE)\b""")
            .findAll(tiers)
            .mapNotNull { match ->
                when (match.groupValues[1]) {
                    "WOOD" -> 0
                    "STONE" -> 1
                    "IRON" -> 2
                    "DIAMOND" -> 3
                    "NETHERITE" -> 4
                    else -> null
                }
            }
            .maxOrNull()
    }

    private fun namespaceFromTagExpression(tagExpression: String, projectDir: Path, sourceFile: Path): String? {
        Regex("""ResourceLocation\.fromNamespaceAndPath\(\s*"([a-z0-9_.-]+)"\s*,""")
            .find(tagExpression)?.let { return it.groupValues[1] }
        Regex("""new\s+ResourceLocation\(\s*"([a-z0-9_.-]+)"\s*,""")
            .find(tagExpression)?.let { return it.groupValues[1] }
        val prefixOwner = Regex("""\b([A-Za-z_$][\w$]*)\.prefix\s*\(""")
            .find(tagExpression)
            ?.groupValues
            ?.get(1)
            ?: return null
        return resolvePrefixNamespace(projectDir, sourceFile, prefixOwner)
    }

    private fun resolvePrefixNamespace(projectDir: Path, sourceFile: Path, simpleClassName: String): String? {
        val source = sourceFile.readText()
        val classFile = resolveJavaClassFile(projectDir, sourceFile, source, simpleClassName) ?: return null
        if (!classFile.exists()) return null
        val classSource = classFile.readText()
        val prefixMethod = Regex(
            """(?s)\bprefix\s*\(\s*String\s+[A-Za-z_$][\w$]*\s*\)\s*\{(.*?)\}"""
        ).find(classSource)?.groupValues?.get(1) ?: return null
        val namespaceToken = Regex("""ResourceLocation\.fromNamespaceAndPath\(\s*([A-Za-z_$][\w$]*)\s*,""")
            .find(prefixMethod)
            ?.groupValues
            ?.get(1)
            ?: Regex("""new\s+ResourceLocation\(\s*([A-Za-z_$][\w$]*)\s*,""")
                .find(prefixMethod)
                ?.groupValues
                ?.get(1)
            ?: return null
        return Regex("""\bstatic\s+final\s+String\s+${Regex.escape(namespaceToken)}\s*=\s*"([a-z0-9_.-]+)"""")
            .find(classSource)
            ?.groupValues
            ?.get(1)
    }

    private fun resolveJavaClassFile(
        projectDir: Path,
        sourceFile: Path,
        source: String,
        simpleClassName: String
    ): Path? {
        val imported = Regex("""(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.${Regex.escape(simpleClassName)}\s*;""")
            .find(source)
            ?.let { "${it.groupValues[1]}.$simpleClassName" }
        val fqn = imported ?: run {
            val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?: return null
            "$packageName.$simpleClassName"
        }
        val relative = fqn.replace('.', '/') + ".java"
        return listOf(
            projectDir.resolve("src/main/java").resolve(relative),
            projectDir.resolve("src/generated/java").resolve(relative),
            sourceFile.parent.resolve("$simpleClassName.java")
        ).firstOrNull { it.exists() }
    }

    private fun incorrectToolTagExpression(oldNeedsTag: String, toolName: String): String =
        oldNeedsTag.replace("needs_${toolName}_tool", "incorrect_for_${toolName}_tool")

    private fun repairIngredientForTool(toolName: String?, original: String): String = original

    private fun ensureTierIncorrectTagResources(
        projectDir: Path,
        specs: List<TierIncorrectTagResource>,
        changes: MutableList<Change>,
        dryRun: Boolean
    ) {
        if (specs.isEmpty()) return
        val resourceDirs = targetResourceDirs(projectDir)
        val emittedTargets = mutableSetOf<Path>()

        for (spec in specs.distinct()) {
            for (resourceDir in resourceDirs) {
                val target = resourceDir
                    .resolve("data")
                    .resolve(spec.namespace)
                    .resolve("tags/block")
                    .resolve("${spec.path}.json")
                if (!emittedTargets.add(target)) continue
                if (target.exists()) continue

                val content = """
{
  "values": [
    "${spec.vanillaReference}"
  ]
}
                """.trimIndent() + "\n"
                changes.add(
                    Change(
                        file = target,
                        line = 1,
                        description = "Generate 1.21 incorrect-block tag for migrated custom tool tier",
                        before = spec.sourceTagExpression,
                        after = "${spec.namespace}:${spec.path} -> ${spec.vanillaReference}",
                        confidence = Confidence.HIGH,
                        ruleId = "tier-incorrect-block-tag-resource"
                    )
                )
                if (!dryRun) {
                    target.parent.createDirectories()
                    target.writeText(content)
                }
            }
        }
    }

    private fun targetResourceDirs(projectDir: Path): List<Path> {
        val generated = projectDir.resolve("src/generated/resources")
        if (generated.exists()) return listOf(generated)
        return listOf(projectDir.resolve("src/main/resources"))
    }

    private data class TierIncorrectTagResource(
        val namespace: String,
        val path: String,
        val vanillaReference: String,
        val sourceTagExpression: String
    )

    private fun migrateEnchantmentCategoryRuntimeChecks(source: String): String {
        if (!source.contains("EnchantmentCategory") && !source.contains("new Enchantment[0]")) return source
        var result = source
        result = Regex("""EnchantmentHelper\.getEnchantments\(([^)\r\n]+)\)\.keySet\(\)\.toArray\(new\s+Enchantment\[\s*0\s*]\)""")
            .replace(result) { match ->
                "EnchantmentHelper.getEnchantments(${match.groupValues[1]}).keySet()"
            }
        result = Regex(
            """(?s)private\s+boolean\s+([A-Za-z_$][\w$]*)\s*\(\s*Enchantment\.\.\.\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*for\s*\(\s*Enchantment\s+([A-Za-z_$][\w$]*)\s*:\s*\2\s*\)\s*\{\s*if\s*\(\s*\3\.category\s*==\s*EnchantmentCategory\.[A-Za-z_$][\w$]*\s*\|\|\s*\3\.canEnchant\(([\s\S]*?)\)\s*\)\s*return\s+true;\s*\}\s*return\s+false;\s*\}"""
        ).replace(result) { match ->
            val methodName = match.groupValues[1]
            val itemStackExpression = match.groupValues[4].trim()
            """
private boolean $methodName(Iterable<net.minecraft.core.Holder<Enchantment>> enchantments) {
		for (net.minecraft.core.Holder<Enchantment> enchantment : enchantments) {
			if ($itemStackExpression.supportsEnchantment(enchantment))
				return true;
		}
		return false;
	}

	private boolean $methodName(net.minecraft.core.Holder<Enchantment> enchantment) {
		return this.$methodName(java.util.List.of(enchantment));
	}
            """.trimIndent()
        }
        return result
    }

    private data class LootCodecField(val key: String, val getter: String, val codecExpression: String)

    private fun collectLegacyLootCodecOwners(javaFiles: List<Path>): Set<String> {
        return javaFiles.mapNotNull { file ->
            val source = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            legacyLootCodecOwner(source)
        }.toSet()
    }

    private fun legacyLootCodecOwner(source: String): String? {
        val className = javaTopLevelTypeName(source) ?: return null
        return className.takeIf {
            lootConditionCodecFieldSource(source, className) != null ||
                lootConditionalFunctionCodecFieldSource(source, className) != null
        }
    }

    private fun migrateLootSerializerCodecs(source: String, codecOwners: Set<String> = emptySet()): String {
        if (!source.contains("LootItemConditionType") &&
            !source.contains("LootItemFunctionType") &&
            !source.contains("Serializer<")) {
            return source
        }

        var result = source
        result = migrateLootConditionSerializerCodec(result)
        result = migrateLootConditionalFunctionSerializerCodec(result)
        result = migrateLootTypeRegistryCodecConstructors(result, codecOwners)
        result = removeLegacyLootSerializerImports(result)
        return result
    }

    private fun migrateLootConditionSerializerCodec(source: String): String {
        if (!source.contains("implements LootItemCondition") ||
            !source.contains("Serializer<")) {
            return source
        }

        val className = javaTopLevelTypeName(source) ?: return source
        val serializerName = when {
            Regex("""class\s+ConditionSerializer\s+implements\s+(?:net\.minecraft\.world\.level\.storage\.loot\.)?Serializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(source) -> "ConditionSerializer"
            Regex("""class\s+Serializer\s+implements\s+(?:net\.minecraft\.world\.level\.storage\.loot\.)?Serializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(source) -> "Serializer"
            else -> return source
        }
        val codecField = lootConditionCodecFieldSource(source, className) ?: return source

        var result = insertStaticFieldAfterTypeOpen(source, className, codecField)
        result = removeInnerClassByName(result, serializerName)
        return result
    }

    private fun lootConditionCodecFieldSource(source: String, className: String): String? {
        val serializerName = when {
            Regex("""class\s+ConditionSerializer\s+implements\s+(?:net\.minecraft\.world\.level\.storage\.loot\.)?Serializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(source) -> "ConditionSerializer"
            Regex("""class\s+Serializer\s+implements\s+(?:net\.minecraft\.world\.level\.storage\.loot\.)?Serializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(source) -> "Serializer"
            else -> return null
        }
        val serializer = innerClassText(source, serializerName) ?: return null
        return inferLootConditionCodecField(source, serializer, className)
    }

    private fun inferLootConditionCodecField(source: String, serializer: String, className: String): String? {
        val deserializeReturn = Regex(
            """return\s+new\s+${Regex.escape(className)}\s*\(([\s\S]*?)\)\s*;"""
        ).find(serializer) ?: return null
        val constructorArgs = splitTopLevelArguments(deserializeReturn.groupValues[1])
        if (constructorArgs.isEmpty()) {
            val singleton = Regex("""public\s+static\s+final\s+${Regex.escape(className)}\s+INSTANCE\b""")
                .containsMatchIn(source)
            val value = if (singleton) "INSTANCE" else "new $className()"
            return """
	public static final MapCodec<$className> CODEC = MapCodec.unit($value);
            """.trimIndent()
        }

        inferWrappedStringLootConditionCodecField(serializer, className, constructorArgs)?.let { return it }

        val serializedMembers = serializedLootMembersByKey(serializer)
        val fields = constructorArgs.mapNotNull { lootConditionCodecField(it, serializedMembers) }
        if (fields.size != constructorArgs.size) return null

        val fieldLines = fields.joinToString(",\n") { field ->
            "\t\t${field.codecExpression}.forGetter(o -> o.${field.getter})"
        }
        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
$fieldLines
	).apply(instance, $className::new));
        """.trimIndent()
    }

    private fun serializedLootMembersByKey(serializer: String): Map<String, String> {
        val members = linkedMapOf<String, String>()
        Regex(
            """\b(?:json|object)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).findAll(serializer).forEach { match ->
            members[match.groupValues[1]] = match.groupValues[2]
        }
        Regex(
            """\b(?:json|object)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$.]*\.serialize\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\s*\)"""
        ).findAll(serializer).forEach { match ->
            members[match.groupValues[1]] = match.groupValues[2]
        }
        Regex(
            """\b(?:json|object)\.add\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$]*\.serialize\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\s*\)"""
        ).findAll(serializer).forEach { match ->
            members[match.groupValues[1]] = match.groupValues[2]
        }
        return members
    }

    private fun inferWrappedStringLootConditionCodecField(
        serializer: String,
        className: String,
        constructorArgs: List<String>
    ): String? {
        if (constructorArgs.size != 1) return null
        val deserialize = Regex(
            """([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.deserialize\s*\(\s*GsonHelper\.getAsString\s*\(\s*[^,]+,\s*"([^"]+)"\s*\)\s*\)"""
        ).find(constructorArgs.single()) ?: return null
        val converter = deserialize.groupValues[1]
        val key = deserialize.groupValues[2]
        val member = Regex(
            """\b(?:json|object)\.addProperty\s*\(\s*"${Regex.escape(key)}"\s*,\s*${Regex.escape(converter)}\.serialize\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\s*\)"""
        ).find(serializer)?.groupValues?.get(1) ?: return null
        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> instance.group(
		com.mojang.serialization.Codec.STRING.fieldOf("$key").forGetter(o -> $converter.serialize(o.$member))
	).apply(instance, value -> new $className($converter.deserialize(value))));
        """.trimIndent()
    }

    private fun lootConditionCodecField(argument: String, serializedMembers: Map<String, String>): LootCodecField? {
        Regex("""GsonHelper\.getAsString\s*\(\s*[^,]+,\s*"([^"]+)"""").find(argument)?.let { match ->
            val key = match.groupValues[1]
            val member = serializedMembers[key] ?: return null
            return LootCodecField(
                key = key,
                getter = member,
                codecExpression = """com.mojang.serialization.Codec.STRING.fieldOf("$key")"""
            )
        }
        Regex("""GsonHelper\.getAsBoolean\s*\(\s*[^,]+,\s*"([^"]+)"\s*,\s*([^)]+)\)""").find(argument)?.let { match ->
            val key = match.groupValues[1]
            val defaultValue = match.groupValues[2].trim()
            val member = serializedMembers[key] ?: return null
            return LootCodecField(
                key = key,
                getter = member,
                codecExpression = """com.mojang.serialization.Codec.BOOL.optionalFieldOf("$key", $defaultValue)"""
            )
        }
        Regex("""GsonHelper\.getAsObject\s*\(\s*[^,]+,\s*"([^"]+)"\s*,\s*[^,]+,\s*LootContext\.EntityTarget\.class\s*\)""")
            .find(argument)?.let { match ->
                val key = match.groupValues[1]
                val member = serializedMembers[key] ?: return null
                return LootCodecField(
                    key = key,
                    getter = member,
                    codecExpression = """LootContext.EntityTarget.CODEC.fieldOf("$key")"""
                )
            }
        return null
    }

    private fun migrateLootConditionalFunctionSerializerCodec(source: String): String {
        if (!source.contains("extends LootItemConditionalFunction") ||
            !source.contains("LootItemConditionalFunction.Serializer")) {
            return source
        }

        val className = javaTopLevelTypeName(source) ?: return source
        val codecField = lootConditionalFunctionCodecFieldSource(source, className) ?: return source

        var result = insertStaticFieldAfterTypeOpen(source, className, codecField)
        result = Regex("""(protected|public|private)\s+${Regex.escape(className)}\s*\(\s*LootItemCondition\[\]\s+([A-Za-z_$][\w$]*)""")
            .replace(result, "$1 $className(java.util.List<LootItemCondition> $2")
        result = Regex("""public\s+LootItemFunctionType\s+getType\s*\(\s*\)""")
            .replace(result, "public LootItemFunctionType<$className> getType()")
        result = removeInnerClassByName(result, "Serializer")
        return result
    }

    private fun lootConditionalFunctionCodecFieldSource(source: String, className: String): String? {
        val serializer = innerClassText(source, "Serializer") ?: return null
        if (!serializer.contains("extends LootItemConditionalFunction.Serializer<$className>")) return null
        return inferLootConditionalFunctionCodecField(serializer, className)
    }

    private fun inferLootConditionalFunctionCodecField(serializer: String, className: String): String? {
        if (Regex("""return\s+new\s+${Regex.escape(className)}\s*\(\s*conditions\s*\)\s*;""").containsMatchIn(serializer)) {
            return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).apply(instance, $className::new));
            """.trimIndent()
        }

        inferEntityTypeAndIntLootFunctionCodecField(serializer, className)?.let { return it }

        if (!serializer.contains("GsonHelper.getAsItem") ||
            !serializer.contains("\"default\"") ||
            !serializer.contains("success")) {
            return null
        }

        val itemMember = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"item"\s*,\s*BuiltInRegistries\.ITEM\.getKey\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).find(serializer)?.groupValues?.get(1) ?: return null
        val defaultMembers = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"default"\s*,\s*BuiltInRegistries\.ITEM\.getKey\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).findAll(serializer).map { it.groupValues[1] }.toList()
        val defaultMember = defaultMembers.lastOrNull() ?: return null
        val successMember = Regex("""if\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)""")
            .find(serializer)?.groupValues?.get(1) ?: return null

        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).and(instance.group(
		BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item").forGetter(o -> o.$successMember ? java.util.Optional.of(o.$itemMember) : java.util.Optional.empty()),
		BuiltInRegistries.ITEM.byNameCodec().fieldOf("default").forGetter(o -> o.$defaultMember)
	)).apply(instance, (conditions, item, oldItem) -> new $className(conditions, item.orElse(oldItem), oldItem, item.isPresent())));
        """.trimIndent()
    }

    private fun inferEntityTypeAndIntLootFunctionCodecField(serializer: String, className: String): String? {
        val deserializeReturn = Regex(
            """return\s+new\s+${Regex.escape(className)}\s*\(([\s\S]*?)\)\s*;"""
        ).find(serializer) ?: return null
        val constructorArgs = splitTopLevelArguments(deserializeReturn.groupValues[1])
        if (constructorArgs.size < 2 || constructorArgs.first().trim() != "conditions") return null

        val entityLocals = Regex(
            """EntityType\s*<\s*\?\s*>\s+([A-Za-z_$][\w$]*)\s*=\s*EntityType\.byString\s*\(\s*GsonHelper\.getAsString\s*\(\s*[^,]+,\s*"([^"]+)"\s*\)\s*\)[^;]*;"""
        ).findAll(serializer).associate { it.groupValues[1] to it.groupValues[2] }
        val intLocals = Regex(
            """int\s+([A-Za-z_$][\w$]*)\s*=\s*GsonHelper\.getAsInt\s*\(\s*[^,]+,\s*"([^"]+)"\s*\)\s*;"""
        ).findAll(serializer).associate { it.groupValues[1] to it.groupValues[2] }
        if (entityLocals.isEmpty() && intLocals.isEmpty()) return null

        val entityMembersByKey = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*EntityType\.getKey\s*\(\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)\.toString\s*\(\s*\)\s*\)"""
        ).findAll(serializer).associate { it.groupValues[1] to it.groupValues[2] }
        val intMembersByKey = Regex(
            """\b(?:object|json)\.addProperty\s*\(\s*"([^"]+)"\s*,\s*[A-Za-z_$][\w$]*\.([A-Za-z_$][\w$]*)\s*\)"""
        ).findAll(serializer).associate { it.groupValues[1] to it.groupValues[2] }

        val codecFields = constructorArgs.drop(1).map { argument ->
            val arg = argument.trim()
            entityLocals[arg]?.let { key ->
                val member = entityMembersByKey[key] ?: return null
                """		BuiltInRegistries.ENTITY_TYPE.byNameCodec().fieldOf("$key").forGetter(o -> o.$member)"""
            } ?: intLocals[arg]?.let { key ->
                val member = intMembersByKey[key] ?: return null
                """		com.mojang.serialization.Codec.INT.fieldOf("$key").forGetter(o -> o.$member)"""
            } ?: return null
        }
        if (codecFields.isEmpty()) return null

        return """
	public static final MapCodec<$className> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(instance -> commonFields(instance).and(instance.group(
${codecFields.joinToString(",\n")}
	)).apply(instance, $className::new));
        """.trimIndent()
    }

    private fun migrateLootTypeRegistryCodecConstructors(source: String, codecOwners: Set<String>): String {
        if (!source.contains("LootItemConditionType") && !source.contains("LootItemFunctionType")) return source
        var result = source
        result = Regex("""new\s+LootItemConditionType\s*\(\s*new\s+([A-Za-z_$][\w$]*)\.(?:ConditionSerializer|Serializer)\s*\(\s*\)\s*\)""")
            .replace(result) { match ->
                val owner = match.groupValues[1]
                if (lootCodecOwnerIsAvailable(result, owner, codecOwners)) {
                    "new LootItemConditionType($owner.CODEC)"
                } else {
                    match.value
                }
            }
        result = Regex("""new\s+LootItemFunctionType\s*\(\s*new\s+([A-Za-z_$][\w$]*)\.Serializer\s*\(\s*\)\s*\)""")
            .replace(result) { match ->
                val owner = match.groupValues[1]
                if (lootCodecOwnerIsAvailable(result, owner, codecOwners)) {
                    "new LootItemFunctionType<>($owner.CODEC)"
                } else {
                    match.value
                }
            }
        if (result.contains("new LootItemFunctionType<>(")) {
            result = result.replace(
                "DeferredRegister<LootItemFunctionType> FUNCTIONS",
                "DeferredRegister<LootItemFunctionType<?>> FUNCTIONS"
            )
        }
        result = Regex(
            """DeferredHolder<LootItemFunctionType,\s*LootItemFunctionType>\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.register\s*\(\s*"([^"]+)"\s*,\s*\(\)\s*->\s*new\s+LootItemFunctionType<>\s*\(\s*([A-Za-z_$][\w$]*)\.CODEC\s*\)\s*\)"""
        ).replace(result) { match ->
            "DeferredHolder<LootItemFunctionType<?>, LootItemFunctionType<${match.groupValues[4]}>> ${match.groupValues[1]} = ${match.groupValues[2]}.register(\"${match.groupValues[3]}\", () -> new LootItemFunctionType<>(${match.groupValues[4]}.CODEC))"
        }
        return result
    }

    private fun lootCodecOwnerIsAvailable(source: String, owner: String, codecOwners: Set<String>): Boolean {
        return owner in codecOwners ||
            Regex("""MapCodec\s*<\s*${Regex.escape(owner)}\s*>\s+CODEC\b""").containsMatchIn(source)
    }

    private fun removeLegacyLootSerializerImports(source: String): String {
        var result = source
        result = removeImportLine(result, "net.minecraft.world.level.storage.loot.Serializer")
        val importCandidates = listOf(
            "com.google.gson.JsonDeserializationContext" to "JsonDeserializationContext",
            "com.google.gson.JsonObject" to "JsonObject",
            "com.google.gson.JsonSerializationContext" to "JsonSerializationContext",
            "com.google.gson.JsonSyntaxException" to "JsonSyntaxException",
            "net.minecraft.util.GsonHelper" to "GsonHelper"
        )
        for ((importName, symbol) in importCandidates) {
            if (!usesSymbolOutsideImports(result, symbol)) {
                result = removeImportLine(result, importName)
            }
        }
        return result
    }

    private fun javaTopLevelTypeName(source: String): String? =
        Regex("""(?m)^\s*public\s+(?:final\s+)?(?:class|record)\s+([A-Za-z_$][\w$]*)\b""")
            .find(source)?.groupValues?.get(1)

    private fun insertStaticFieldAfterTypeOpen(source: String, className: String, fieldSource: String): String {
        if (Regex("""MapCodec<\s*${Regex.escape(className)}\s*>\s+CODEC""").containsMatchIn(source)) return source
        val typeMatch = Regex(
            """public\s+(?:final\s+)?(?:class|record)\s+${Regex.escape(className)}\b[\s\S]*?\{"""
        ).find(source) ?: return source
        val insertAt = typeMatch.range.last + 1
        return source.substring(0, insertAt) + "\n\n" + fieldSource.trimEnd() + "\n" + source.substring(insertAt)
    }

    private fun innerClassText(source: String, simpleName: String): String? {
        val match = Regex("""(?m)^[ \t]*(?:public|protected|private)?\s*static\s+class\s+${Regex.escape(simpleName)}\b""")
            .find(source) ?: return null
        val openBrace = source.indexOf('{', match.range.last)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return null
        return source.substring(match.range.first, closeBrace + 1)
    }

    private fun removeInnerClassByName(source: String, simpleName: String): String {
        val match = Regex("""(?m)^[ \t]*(?:public|protected|private)?\s*static\s+class\s+${Regex.escape(simpleName)}\b""")
            .find(source) ?: return source
        val start = source.lastIndexOf('\n', match.range.first).let { if (it >= 0) it else match.range.first }
        val openBrace = source.indexOf('{', match.range.last)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return source
        val end = if (closeBrace + 1 < source.length && source[closeBrace + 1] == '\n') closeBrace + 2 else closeBrace + 1
        return source.removeRange(start, end)
    }

    private fun migrateNeoForgeConditionSerializerCodecs(source: String): String {
        if (!source.contains("implements ICondition") || !source.contains("IConditionSerializer")) {
            return source
        }
        val className = javaTopLevelTypeName(source) ?: return source
        val serializer = innerClassText(source, "Serializer") ?: return source
        if (!Regex("""implements\s+IConditionSerializer<\s*${Regex.escape(className)}\s*>""")
                .containsMatchIn(serializer)) {
            return source
        }

        val codecValue = when {
            Regex("""public\s+static\s+final\s+${Regex.escape(className)}\s+INSTANCE\b""").containsMatchIn(source) -> "INSTANCE"
            Regex("""return\s+new\s+${Regex.escape(className)}\s*\(\s*\)\s*;""").containsMatchIn(serializer) -> "new $className()"
            else -> return source
        }

        var result = insertNeoForgeConditionCodecField(
            source,
            className,
            "public static final MapCodec<$className> CODEC = MapCodec.unit($codecValue);",
            insertAfterInstance = codecValue == "INSTANCE"
        )
        if (!result.contains("MapCodec<? extends ICondition> codec()")) {
            result = insertMethodAfterStaticCodec(
                result,
                className,
                """
	@Override
	public MapCodec<? extends ICondition> codec() {
		return CODEC;
	}
                """.trimIndent()
            )
        }
        result = removeFirstJavaMethodByName(result, "getID").first
        result = removeInnerClassByName(result, "Serializer")
        result = replaceUnusedResourceLocationIdConstantWithConditionId(result)
        result = removeImportLine(result, "net.neoforged.neoforge.common.conditions.IConditionSerializer")
        result = removeImportLine(result, "net.minecraftforge.common.crafting.conditions.IConditionSerializer")
        if (!usesSymbolOutsideImports(result, "JsonObject")) {
            result = removeImportLine(result, "com.google.gson.JsonObject")
        }
        if (!usesSymbolOutsideImports(result, "ResourceLocation")) {
            result = removeImportLine(result, "net.minecraft.resources.ResourceLocation")
        }
        return result
    }

    private fun insertNeoForgeConditionCodecField(
        source: String,
        className: String,
        fieldSource: String,
        insertAfterInstance: Boolean
    ): String {
        if (Regex("""MapCodec<\s*${Regex.escape(className)}\s*>\s+CODEC""").containsMatchIn(source)) return source
        if (insertAfterInstance) {
            val instanceField = Regex(
                """(?m)^[ \t]*(?:public|protected|private)\s+static\s+final\s+${Regex.escape(className)}\s+INSTANCE\s*=\s*[^;\r\n]+;\s*$"""
            ).find(source)
            if (instanceField != null) {
                val insertAt = instanceField.range.last + 1
                return source.substring(0, insertAt) + "\n" + fieldSource.trimEnd() + "\n" + source.substring(insertAt)
            }
        }
        return insertStaticFieldAfterTypeOpen(source, className, fieldSource)
    }

    private fun replaceUnusedResourceLocationIdConstantWithConditionId(source: String): String {
        val match = Regex(
            """(?m)^[ \t]*(?:private|protected|public)\s+static\s+final\s+ResourceLocation\s+([A-Za-z_$][\w$]*)\s*=\s*[^;\r\n]+;\s*\r?\n"""
        ).find(source) ?: return source
        val name = match.groupValues[1]
        val withoutField = source.removeRange(match.range)
        val bodyWithoutImports = withoutField.lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")
        if (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(bodyWithoutImports)) return source
        val path = inferResourceLocationPath(match.value) ?: return withoutField
        val indent = Regex("""(?m)^([ \t]*)""").find(match.value)?.groupValues?.get(1).orEmpty()
        val replacement = "${indent}public static final String CONDITION_ID = \"$path\";\n"
        return source.replaceRange(match.range, replacement)
    }

    private fun inferResourceLocationPath(declaration: String): String? {
        Regex("""\(\s*"[^"]+"\s*,\s*"([^"]+)"\s*\)""").find(declaration)?.let { return it.groupValues[1] }
        Regex("""\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.prefix\s*\(\s*"([^"]+)"\s*\)""")
            .find(declaration)?.let { return it.groupValues[1] }
        Regex("""ResourceLocation\.parse\s*\(\s*"[^":]+:([^"]+)"\s*\)""").find(declaration)?.let { return it.groupValues[1] }
        Regex("""new\s+ResourceLocation\s*\(\s*"[^"]+"\s*,\s*"([^"]+)"\s*\)""").find(declaration)?.let { return it.groupValues[1] }
        return null
    }

    private fun insertMethodAfterStaticCodec(source: String, className: String, methodSource: String): String {
        val codecMatch = Regex(
            """public\s+static\s+final\s+MapCodec<\s*${Regex.escape(className)}\s*>\s+CODEC\s*=\s*[\s\S]*?;"""
        ).find(source) ?: return source
        val insertAt = codecMatch.range.last + 1
        return source.substring(0, insertAt) + "\n\n" + methodSource.trimEnd() + "\n" + source.substring(insertAt)
    }

    private data class LegacyCustomEnchantmentRegistration(
        val file: Path,
        val ownerPackage: String,
        val ownerClass: String,
        val modId: String,
        val fieldName: String,
        val registryName: String,
        val className: String,
        val constructorArgs: List<String>
    )

    private data class LegacyEnchantmentCategorySpec(
        val ownerPackage: String,
        val ownerClass: String,
        val fieldName: String,
        val itemClassName: String?
    )

    private data class RegistryEntryRef(
        val ownerPackage: String,
        val ownerClass: String,
        val fieldName: String,
        val modId: String,
        val path: String
    ) {
        val id: String get() = "$modId:$path"
    }

    private data class LegacyItemTagSpec(
        val namespace: String,
        val path: String,
        val values: List<String>
    )

    private data class LegacyJavaContext(
        val packageName: String,
        val typeImports: Map<String, String>,
        val wildcardImports: List<String>,
        val staticFieldImports: Map<String, String>,
        val staticWildcardImports: List<String>
    )

    private data class LegacyReferenceIndex<T>(
        val byExact: Map<String, T>
    )

    private data class LegacyExpression(val text: String, val offset: Int)

    private data class LegacySourceSpan(val text: String, val offset: Int)

    private data class LinearIntValue(val base: Int, val perLevelAboveFirst: Int)

    private data class LinearFloatValue(val base: Double, val perLevelAboveFirst: Double)

    private data class LegacyEnchantmentDefinition(
        val registration: LegacyCustomEnchantmentRegistration,
        val supportedItems: String,
        val primaryItems: String?,
        val slots: List<String>,
        val weight: Int,
        val maxLevel: Int,
        val minCost: LinearIntValue,
        val maxCost: LinearIntValue,
        val anvilCost: Int,
        val exclusiveSet: List<String>,
        val effectsJson: String?,
        val itemTags: List<LegacyItemTagSpec>
    )

    private fun migrateLegacyCustomEnchantmentData(
        projectDir: Path,
        javaFiles: List<Path>,
        changes: MutableList<Change>,
        errors: MutableList<String>,
        dryRun: Boolean
    ) {
        if (javaFiles.isEmpty()) return
        val javaSources = javaFiles.map { it to it.readText() }
        val modIds = detectLegacyJavaModIds(javaSources)
        val classSources = indexJavaClassSources(javaSources)
        val registryEntries = collectLegacyRegistryEntries(javaSources, modIds)
        val itemRegistryEntries = collectLegacyItemRegistryEntries(javaSources, modIds)
        val categories = collectLegacyEnchantmentCategories(javaSources)
        val registrations = collectLegacyCustomEnchantmentRegistrations(javaSources, modIds, classSources, errors)
        if (registrations.isEmpty()) return

        val enchantmentRefs = legacyReferenceIndex(
            registrations,
            fieldName = { it.fieldName },
            ownerClass = { it.ownerClass },
            ownerPackage = { it.ownerPackage }
        )
        val writtenTags = linkedSetOf<String>()

        for (registration in registrations) {
            val definition = deriveLegacyEnchantmentDefinition(
                registration = registration,
                classSources = classSources,
                categories = categories,
                registryEntries = registryEntries,
                itemRegistryEntries = itemRegistryEntries,
                enchantmentRefs = enchantmentRefs,
                errors = errors
            ) ?: continue

            val target = projectDir.resolve("src/generated/resources/data/${registration.modId}/enchantment/${registration.registryName}.json")
            changes.add(Change(
                file = target,
                line = 1,
                description = "Create source-derived data-driven custom enchantment '${registration.modId}:${registration.registryName}'",
                before = "legacy Enchantment subclass registration",
                after = "data/${registration.modId}/enchantment/${registration.registryName}.json",
                confidence = Confidence.HIGH,
                ruleId = "text-custom-enchantment-data"
            ))
            if (!dryRun) {
                target.parent.createDirectories()
                target.writeText(legacyEnchantmentJson(definition))
            }

            for (tag in definition.itemTags) {
                val tagKey = "${tag.namespace}:${tag.path}"
                if (!writtenTags.add(tagKey)) continue
                val tagTarget = projectDir.resolve("src/generated/resources/data/${tag.namespace}/tags/item/${tag.path}.json")
                changes.add(Change(
                    file = tagTarget,
                    line = 1,
                    description = "Create item tag '$tagKey' for source-derived enchantment item support",
                    before = "legacy EnchantmentCategory predicate",
                    after = "data/${tag.namespace}/tags/item/${tag.path}.json",
                    confidence = Confidence.HIGH,
                    ruleId = "text-custom-enchantment-item-tag"
                ))
                if (!dryRun) {
                    tagTarget.parent.createDirectories()
                    tagTarget.writeText(legacyItemTagJson(tag.values))
                }
            }
        }
    }

    private fun legacyJavaPackageName(source: String): String =
        Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;""")
            .find(source)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    private fun legacyJavaContext(source: String): LegacyJavaContext {
        val typeImports = linkedMapOf<String, String>()
        val wildcardImports = mutableListOf<String>()
        val staticFieldImports = linkedMapOf<String, String>()
        val staticWildcardImports = mutableListOf<String>()
        Regex("""(?m)^\s*import\s+(static\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;""")
            .findAll(source)
            .forEach { match ->
                val imported = match.groupValues[2]
                val isStatic = match.groupValues[1].isNotBlank()
                if (imported.endsWith(".*")) {
                    val owner = imported.removeSuffix(".*")
                    if (isStatic) {
                        staticWildcardImports += owner
                    } else {
                        wildcardImports += owner
                    }
                } else if (isStatic) {
                    staticFieldImports[imported.substringAfterLast('.')] = imported
                } else {
                    typeImports[imported.substringAfterLast('.')] = imported
                }
            }
        return LegacyJavaContext(
            packageName = legacyJavaPackageName(source),
            typeImports = typeImports,
            wildcardImports = wildcardImports,
            staticFieldImports = staticFieldImports,
            staticWildcardImports = staticWildcardImports
        )
    }

    private fun <T> legacyReferenceIndex(
        refs: Iterable<T>,
        fieldName: (T) -> String,
        ownerClass: (T) -> String,
        ownerPackage: (T) -> String
    ): LegacyReferenceIndex<T> {
        val byExact = linkedMapOf<String, T>()
        refs.forEach { ref ->
            val field = fieldName(ref)
            val owner = ownerClass(ref)
            val pkg = ownerPackage(ref)
            if (pkg.isNotBlank()) {
                byExact["$pkg.$owner.$field"] = ref
            } else {
                byExact["$owner.$field"] = ref
            }
        }
        return LegacyReferenceIndex(byExact = byExact)
    }

    private fun <T> resolveLegacyReferenceExpression(
        expression: String,
        source: String,
        offset: Int,
        index: LegacyReferenceIndex<T>
    ): T? {
        val ref = expression.trim()
        index.byExact[ref]?.let { return it }
        val field = ref.substringAfterLast('.')
        val owner = ref.substringBeforeLast('.', missingDelimiterValue = "")
        val context = legacyJavaContext(source)

        if (owner.isEmpty()) {
            context.staticFieldImports[field]?.let { imported ->
                index.byExact[imported]?.let { return it }
            }
            context.staticWildcardImports
                .mapNotNull { staticOwner -> index.byExact["$staticOwner.$field"] }
                .distinct()
                .singleOrNull()
                ?.let { return it }
            val ownerClass = javaTypeNameContainingOffset(source, offset) ?: return null
            index.byExact["$ownerClass.$field"]?.let { return it }
            if (context.packageName.isNotBlank()) {
                index.byExact["${context.packageName}.$ownerClass.$field"]?.let { return it }
            }
            return null
        }

        if (owner.contains('.')) {
            return index.byExact[ref]
        }

        context.typeImports[owner]?.let { importedOwner ->
            index.byExact["$importedOwner.$field"]?.let { return it }
        }
        if (context.packageName.isNotBlank()) {
            index.byExact["${context.packageName}.$owner.$field"]?.let { return it }
        }
        context.wildcardImports
            .mapNotNull { packageName -> index.byExact["$packageName.$owner.$field"] }
            .distinct()
            .singleOrNull()
            ?.let { return it }
        return null
    }

    private fun resolveLegacyClassReference(
        typeName: String,
        source: String,
        classSources: Map<String, List<Pair<Path, String>>>
    ): String? {
        val trimmed = typeName.trim()
        if (trimmed.contains('.')) return trimmed
        val context = legacyJavaContext(source)
        context.typeImports[trimmed]?.let { imported ->
            if (classSources.containsKey(imported)) return imported
        }
        if (context.packageName.isNotBlank()) {
            val samePackage = "${context.packageName}.$trimmed"
            if (classSources.containsKey(samePackage)) return samePackage
        } else if (classSources.containsKey(trimmed)) {
            return trimmed
        }
        val wildcardMatches = context.wildcardImports
            .map { packageName -> "$packageName.$trimmed" }
            .filter { classSources.containsKey(it) }
            .distinct()
        return wildcardMatches.singleOrNull()
    }

    private fun detectLegacyJavaModIds(javaSources: List<Pair<Path, String>>): Map<String, String> {
        val ids = linkedMapOf<String, String>()
        for ((_, source) in javaSources) {
            val packageName = legacyJavaPackageName(source)
            Regex("""\bstatic\s+(?:final\s+)?String\s+([A-Za-z_$][\w$]*)\s*=\s*"([^"]+)"""")
                .findAll(source)
                .forEach { match ->
                    val ownerClass = javaTypeNameContainingOffset(source, match.range.first)
                        ?: return@forEach
                    ids["$ownerClass.${match.groupValues[1]}"] = match.groupValues[2]
                    if (packageName.isNotBlank()) {
                        ids["$packageName.$ownerClass.${match.groupValues[1]}"] = match.groupValues[2]
                    }
                }
            Regex("""@Mod\s*\(\s*"([^"]+)"\s*\)\s*(?:public|protected|private|abstract|final|\s)*class\s+([A-Za-z_$][\w$]*)""")
                .find(source)
                ?.let { match ->
                    ids[match.groupValues[2]] = match.groupValues[1]
                    if (packageName.isNotBlank()) {
                        ids["$packageName.${match.groupValues[2]}"] = match.groupValues[1]
                    }
                }
        }
        return ids
    }

    private fun javaTypeNameContainingOffset(source: String, offset: Int): String? {
        val typePattern = Regex(
            """\b(?:public|protected|private|abstract|final|static|\s)*(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b"""
        )
        for (match in typePattern.findAll(source)) {
            val openBrace = source.indexOf('{', match.range.last)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(source, openBrace) else -1
            if (openBrace >= 0 && closeBrace > openBrace && offset in openBrace..closeBrace) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun indexJavaClassSources(javaSources: List<Pair<Path, String>>): Map<String, List<Pair<Path, String>>> {
        val result = linkedMapOf<String, MutableList<Pair<Path, String>>>()
        val classPattern = Regex("""\b(?:public|protected|private|static|final|\s)*class\s+([A-Za-z_$][\w$]*)\b""")
        for ((file, source) in javaSources) {
            val packageName = legacyJavaPackageName(source)
            classPattern.findAll(source).forEach { match ->
                val className = match.groupValues[1]
                val key = if (packageName.isBlank()) className else "$packageName.$className"
                result.getOrPut(key) { mutableListOf() }.add(file to source)
            }
        }
        return result
    }

    private fun uniqueLegacyClassSource(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>
    ): Pair<Path, String>? {
        val matches = classSources[className].orEmpty()
        if (matches.map { it.first }.distinct().size != 1) return null
        return matches.first()
    }

    private fun sourceForLegacyClass(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>
    ): Pair<Path, String>? {
        val matches = classSources[className].orEmpty()
        if (matches.isEmpty()) {
            errors.add("Cannot derive custom enchantment data for ${className.substringAfterLast('.')}: class source not found")
            return null
        }
        if (matches.map { it.first }.distinct().size > 1) {
            errors.add("Cannot derive custom enchantment data for ${className.substringAfterLast('.')}: class name is ambiguous")
            return null
        }
        return matches.first()
    }

    private fun collectLegacyCustomEnchantmentRegistrations(
        javaSources: List<Pair<Path, String>>,
        modIds: Map<String, String>,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>
    ): List<LegacyCustomEnchantmentRegistration> {
        val id = """[A-Za-z_$][\w$]*"""
        val qualifiedId = """$id(?:\.$id)*"""
        val registerPattern = Regex(
            """(?s)(?:public\s+)?static\s+final\s+DeferredRegister\s*<\s*Enchantment\s*>\s+($id)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([^)]+?)\s*\)\s*;"""
        )
        val results = mutableListOf<LegacyCustomEnchantmentRegistration>()

        for ((file, source) in javaSources) {
            if (!source.contains("DeferredRegister") || !source.contains(".register(") || !source.contains("Enchantment")) continue
            val ownerPackage = legacyJavaPackageName(source)

            for (registerMatch in registerPattern.findAll(source)) {
                val registerField = registerMatch.groupValues[1]
                val modIdExpression = registerMatch.groupValues[2].trim()
                val modId = resolveLegacyModIdExpression(modIdExpression, modIds, source, registerMatch.range.first)
                if (modId == null) {
                    errors.add("Cannot derive custom enchantment data in ${file.fileName}: unresolved mod id expression '$modIdExpression'")
                    continue
                }

                val entryPattern = Regex(
                    """(?s)(?:public\s+)?static\s+final\s+(?:RegistryObject|DeferredHolder)\s*<[^;=]+>\s+($id)\s*=\s*${Regex.escape(registerField)}\.register\(\s*"([^"]+)"\s*,\s*(.*?)\s*\)\s*;"""
                )
                for (entryMatch in entryPattern.findAll(source)) {
                    val ownerClass = javaTypeNameContainingOffset(source, entryMatch.range.first)
                    if (ownerClass == null) {
                        errors.add("Cannot derive custom enchantment data for ${entryMatch.groupValues[1]}: declaring Java type is unresolved")
                        continue
                    }
                    val supplier = entryMatch.groupValues[3].trim()
                    val newMatch = Regex("""(?s)(?:\(\)\s*->\s*)?new\s+($qualifiedId)\s*\((.*)\)\s*$""").find(supplier)
                    val methodRefMatch = Regex("""^\s*($qualifiedId)::new\s*$""").find(supplier)
                    val className = newMatch?.groupValues?.get(1)
                        ?: methodRefMatch?.groupValues?.get(1)
                    if (className == null) {
                        errors.add("Cannot derive custom enchantment data for ${entryMatch.groupValues[1]}: unsupported supplier '$supplier'")
                        continue
                    }
                    val resolvedClassName = resolveLegacyClassReference(className, source, classSources)
                    if (resolvedClassName == null) {
                        errors.add("Cannot derive custom enchantment data for ${entryMatch.groupValues[1]}: class reference '$className' is unresolved")
                        continue
                    }
                    val constructorArgs = newMatch?.groupValues?.get(2)?.let { splitTopLevelArguments(it) }.orEmpty()
                    results.add(LegacyCustomEnchantmentRegistration(
                        file = file,
                        ownerPackage = ownerPackage,
                        ownerClass = ownerClass,
                        modId = modId,
                        fieldName = entryMatch.groupValues[1],
                        registryName = entryMatch.groupValues[2],
                        className = resolvedClassName,
                        constructorArgs = constructorArgs
                    ))
                }
            }
        }
        return results
    }

    private fun resolveLegacyModIdExpression(
        expression: String,
        modIds: Map<String, String>,
        source: String,
        offset: Int
    ): String? {
        val trimmed = expression.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) return trimmed.trim('"')
        modIds[trimmed]?.let { return it }

        if (!Regex("""^[A-Za-z_$][\w$]*$""").matches(trimmed)) return null
        val context = legacyJavaContext(source)
        context.staticFieldImports[trimmed]?.let { imported ->
            modIds[imported]?.let { return it }
        }
        context.staticWildcardImports
            .mapNotNull { importedOwner -> modIds["$importedOwner.$trimmed"] }
            .distinct()
            .singleOrNull()
            ?.let { return it }
        val ownerClass = javaTypeNameContainingOffset(source, offset) ?: return null
        modIds["$ownerClass.$trimmed"]?.let { return it }
        if (context.packageName.isNotBlank()) {
            modIds["${context.packageName}.$ownerClass.$trimmed"]?.let { return it }
        }
        return null
    }

    private fun collectLegacyEnchantmentCategories(
        javaSources: List<Pair<Path, String>>
    ): LegacyReferenceIndex<LegacyEnchantmentCategorySpec> {
        val id = """[A-Za-z_$][\w$]*"""
        val categoryPattern = Regex(
            """(?s)(?:public|private|protected)?\s*static\s+final\s+EnchantmentCategory\s+($id)\s*=\s*EnchantmentCategory\.create\(\s*[^,]+,\s*$id\s*->\s*[^;]*?\binstanceof\s+($id)\b[^;]*?\)\s*;"""
        )
        val categories = mutableListOf<LegacyEnchantmentCategorySpec>()
        for ((_, source) in javaSources) {
            val ownerPackage = legacyJavaPackageName(source)
            for (match in categoryPattern.findAll(source)) {
                val ownerClass = javaTypeNameContainingOffset(source, match.range.first)
                    ?: continue
                val spec = LegacyEnchantmentCategorySpec(
                    ownerPackage = ownerPackage,
                    ownerClass = ownerClass,
                    fieldName = match.groupValues[1],
                    itemClassName = match.groupValues[2]
                )
                categories += spec
            }
        }
        return legacyReferenceIndex(
            categories,
            fieldName = { it.fieldName },
            ownerClass = { it.ownerClass },
            ownerPackage = { it.ownerPackage }
        )
    }

    private fun collectLegacyRegistryEntries(
        javaSources: List<Pair<Path, String>>,
        modIds: Map<String, String>
    ): LegacyReferenceIndex<RegistryEntryRef> {
        val id = """[A-Za-z_$][\w$]*"""
        val registerPattern = Regex(
            """(?s)(?:public\s+)?static\s+final\s+DeferredRegister\s*<[^>]+>\s+($id)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([^)]+?)\s*\)\s*;"""
        )
        val entries = mutableListOf<RegistryEntryRef>()
        for ((_, source) in javaSources) {
            val ownerPackage = legacyJavaPackageName(source)
            for (registerMatch in registerPattern.findAll(source)) {
                val registerField = registerMatch.groupValues[1]
                val modId = resolveLegacyModIdExpression(
                    expression = registerMatch.groupValues[2],
                    modIds = modIds,
                    source = source,
                    offset = registerMatch.range.first
                ) ?: continue
                val entryPattern = Regex(
                    """(?s)(?:public\s+)?static\s+final\s+(?:RegistryObject|DeferredHolder|DeferredItem)\s*<[^;=]+>\s+($id)\s*=\s*${Regex.escape(registerField)}\.register\(\s*"([^"]+)""""
                )
                for (entryMatch in entryPattern.findAll(source)) {
                    val ownerClass = javaTypeNameContainingOffset(source, entryMatch.range.first)
                        ?: continue
                    val ref = RegistryEntryRef(
                        ownerPackage = ownerPackage,
                        ownerClass = ownerClass,
                        fieldName = entryMatch.groupValues[1],
                        modId = modId,
                        path = entryMatch.groupValues[2]
                    )
                    entries += ref
                }
            }
        }
        return legacyReferenceIndex(
            entries,
            fieldName = { it.fieldName },
            ownerClass = { it.ownerClass },
            ownerPackage = { it.ownerPackage }
        )
    }

    private fun collectLegacyItemRegistryEntries(
        javaSources: List<Pair<Path, String>>,
        modIds: Map<String, String>
    ): Map<String, List<String>> {
        val id = """[A-Za-z_$][\w$]*"""
        val registerPattern = Regex(
            """(?s)DeferredRegister\s*<\s*Item\s*>\s+($id)\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([^)]+?)\s*\)\s*;"""
        )
        val results = linkedMapOf<String, MutableList<String>>()
        for ((_, source) in javaSources) {
            for (registerMatch in registerPattern.findAll(source)) {
                val registerField = registerMatch.groupValues[1]
                val modId = resolveLegacyModIdExpression(
                    expression = registerMatch.groupValues[2],
                    modIds = modIds,
                    source = source,
                    offset = registerMatch.range.first
                ) ?: continue
                val entryPattern = Regex(
                    """(?s)${Regex.escape(registerField)}\.register\(\s*"([^"]+)"\s*,\s*(?:\(\)\s*->\s*)?new\s+($id)\s*\("""
                )
                for (entryMatch in entryPattern.findAll(source)) {
                    results.getOrPut(entryMatch.groupValues[2]) { mutableListOf() }.add("$modId:${entryMatch.groupValues[1]}")
                }
            }
        }
        return results
    }

    private fun deriveLegacyEnchantmentDefinition(
        registration: LegacyCustomEnchantmentRegistration,
        classSources: Map<String, List<Pair<Path, String>>>,
        categories: LegacyReferenceIndex<LegacyEnchantmentCategorySpec>,
        registryEntries: LegacyReferenceIndex<RegistryEntryRef>,
        itemRegistryEntries: Map<String, List<String>>,
        enchantmentRefs: LegacyReferenceIndex<LegacyCustomEnchantmentRegistration>,
        errors: MutableList<String>
    ): LegacyEnchantmentDefinition? {
        val (_, source) = sourceForLegacyClass(registration.className, classSources, errors) ?: return null
        val superArgs = extractLegacyConstructorSuperArgs(source, registration.className)
        if (superArgs == null || superArgs.size < 3) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: constructor super arguments are not structural")
            return null
        }

        val rarity = resolveLegacyRarity(registration.constructorArgs, superArgs[0].text)
        if (rarity == null) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: rarity is unresolved")
            return null
        }

        val slots = parseLegacyEquipmentSlots(superArgs[2].text)
        if (slots.isEmpty()) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: equipment slots are unresolved")
            return null
        }

        val itemTarget = deriveLegacyEnchantmentItemTarget(
            registration = registration,
            source = source,
            categoryExpression = superArgs[1],
            categories = categories,
            itemRegistryEntries = itemRegistryEntries,
            slots = slots,
            errors = errors
        ) ?: return null

        val maxLevel = parseLegacyIntReturnMethod(source, "getMaxLevel") ?: 1
        val minCost = parseLegacyCostMethod(source, "getMinCost", null) ?: LinearIntValue(1, 10)
        val maxCost = parseLegacyCostMethod(source, "getMaxCost", minCost) ?: LinearIntValue(minCost.base + 5, minCost.perLevelAboveFirst)
        val treasureOnly = legacyClassChainAny(registration.className, classSources, errors, registration) {
            legacyBooleanReturn(it, "isTreasureOnly") == true
        }
        val tradeable = legacyClassChainFirstBoolean(registration.className, classSources, errors, registration, "isTradeable")
        val discoverable = legacyClassChainFirstBoolean(registration.className, classSources, errors, registration, "isDiscoverable")
        val lootOnly = treasureOnly && tradeable == false && discoverable == false
        val weight = if (lootOnly) 1 else legacyRarityWeight(rarity)
        val anvilCost = if (lootOnly) 8 else legacyRarityAnvilCost(rarity)
        val effectErrorsBefore = errors.size
        val effectsJson = deriveLegacyEnchantmentEffects(source, registryEntries, errors, registration)
        if (errors.size > effectErrorsBefore) return null
        val exclusiveSet = deriveLegacyExclusiveSet(source, enchantmentRefs, errors, registration) ?: return null

        return LegacyEnchantmentDefinition(
            registration = registration,
            supportedItems = itemTarget.supportedItems,
            primaryItems = itemTarget.primaryItems,
            slots = itemTarget.slotGroups,
            weight = weight,
            maxLevel = maxLevel,
            minCost = minCost,
            maxCost = maxCost,
            anvilCost = anvilCost,
            exclusiveSet = exclusiveSet,
            effectsJson = effectsJson,
            itemTags = itemTarget.itemTags
        )
    }

    private data class LegacyItemTarget(
        val supportedItems: String,
        val primaryItems: String?,
        val slotGroups: List<String>,
        val itemTags: List<LegacyItemTagSpec>
    )

    private fun deriveLegacyEnchantmentItemTarget(
        registration: LegacyCustomEnchantmentRegistration,
        source: String,
        categoryExpression: LegacyExpression,
        categories: LegacyReferenceIndex<LegacyEnchantmentCategorySpec>,
        itemRegistryEntries: Map<String, List<String>>,
        slots: List<String>,
        errors: MutableList<String>
    ): LegacyItemTarget? {
        val canEnchantItemClass = Regex("""stack\.getItem\(\)\s+instanceof\s+([A-Za-z_$][\w$]*)""")
            .find(source)
            ?.groupValues
            ?.get(1)
        if (canEnchantItemClass == "ArmorItem") {
            return armorLegacyItemTarget(slots)
        }
        if (canEnchantItemClass != null) {
            return customItemLegacyTarget(registration, canEnchantItemClass, itemRegistryEntries, slots, errors)
        }

        val category = categoryExpression.text.trim()
        if (category == "EnchantmentCategory.ARMOR" || category.endsWith(".EnchantmentCategory.ARMOR")) {
            return armorLegacyItemTarget(slots)
        }
        if (category == "EnchantmentCategory.WEAPON") {
            return LegacyItemTarget("#minecraft:enchantable/weapon", null, slotGroupsForLegacySlots(slots, customHand = false), emptyList())
        }
        if (category == "EnchantmentCategory.DIGGER") {
            return LegacyItemTarget("#minecraft:enchantable/mining", null, slotGroupsForLegacySlots(slots, customHand = false), emptyList())
        }

        val customCategory = resolveLegacyReferenceExpression(category, source, categoryExpression.offset, categories)
        if (customCategory?.itemClassName != null) {
            return customItemLegacyTarget(registration, customCategory.itemClassName, itemRegistryEntries, slots, errors)
        }

        errors.add("Cannot derive custom enchantment data for ${registration.className}: item support expression '${categoryExpression.text}' is unresolved")
        return null
    }

    private fun armorLegacyItemTarget(slots: List<String>): LegacyItemTarget {
        val normalized = slots.toSet()
        val allArmor = setOf("HEAD", "CHEST", "LEGS", "FEET")
        return if (normalized.containsAll(allArmor)) {
            LegacyItemTarget(
                supportedItems = "#minecraft:enchantable/armor",
                primaryItems = "#minecraft:enchantable/chest_armor",
                slotGroups = listOf("armor"),
                itemTags = emptyList()
            )
        } else {
            val slot = when {
                "FEET" in normalized -> "feet"
                "LEGS" in normalized -> "legs"
                "CHEST" in normalized -> "chest"
                "HEAD" in normalized -> "head"
                else -> "armor"
            }
            val tag = when (slot) {
                "feet" -> "#minecraft:enchantable/foot_armor"
                "legs" -> "#minecraft:enchantable/leg_armor"
                "chest" -> "#minecraft:enchantable/chest_armor"
                "head" -> "#minecraft:enchantable/head_armor"
                else -> "#minecraft:enchantable/armor"
            }
            LegacyItemTarget(tag, null, listOf(slot), emptyList())
        }
    }

    private fun customItemLegacyTarget(
        registration: LegacyCustomEnchantmentRegistration,
        itemClassName: String,
        itemRegistryEntries: Map<String, List<String>>,
        slots: List<String>,
        errors: MutableList<String>
    ): LegacyItemTarget? {
        val itemIds = itemRegistryEntries[itemClassName].orEmpty()
            .filter { it.substringBefore(':') == registration.modId }
            .distinct()
        if (itemIds.isEmpty()) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: item class '$itemClassName' has no source registry entry")
            return null
        }
        val tagPath = if (itemIds.size == 1) {
            "enchantable/${itemIds.single().substringAfter(':')}"
        } else {
            "enchantable/${registration.registryName}"
        }
        val tag = LegacyItemTagSpec(registration.modId, tagPath, itemIds)
        return LegacyItemTarget(
            supportedItems = "#${registration.modId}:$tagPath",
            primaryItems = null,
            slotGroups = slotGroupsForLegacySlots(slots, customHand = true),
            itemTags = listOf(tag)
        )
    }

    private fun slotGroupsForLegacySlots(slots: List<String>, customHand: Boolean): List<String> {
        val normalized = slots.toSet()
        return when {
            normalized.containsAll(setOf("HEAD", "CHEST", "LEGS", "FEET")) -> listOf("armor")
            normalized == setOf("MAINHAND") -> listOf(if (customHand) "hand" else "mainhand")
            normalized == setOf("OFFHAND") -> listOf("offhand")
            normalized == setOf("MAINHAND", "OFFHAND") -> listOf("hand")
            normalized == setOf("FEET") -> listOf("feet")
            normalized == setOf("LEGS") -> listOf("legs")
            normalized == setOf("CHEST") -> listOf("chest")
            normalized == setOf("HEAD") -> listOf("head")
            else -> slots.map { it.lowercase() }
        }
    }

    private fun extractLegacyConstructorSuperArgs(source: String, className: String): List<LegacyExpression>? {
        val constructor = Regex("""(?s)\b${Regex.escape(className.substringAfterLast('.'))}\s*\([^)]*\)\s*\{""").find(source) ?: return null
        val openBrace = source.indexOf('{', constructor.range.last)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return null
        val body = source.substring(openBrace + 1, closeBrace)
        val superCall = Regex("""\bsuper\s*\(""").find(body) ?: return null
        val openParen = body.indexOf('(', superCall.range.first)
        val closeParen = findMatchingDelimiter(body, openParen, '(', ')')
        if (closeParen < 0) return null
        return splitTopLevelArgumentExpressions(
            body.substring(openParen + 1, closeParen),
            baseOffset = openBrace + 1 + openParen + 1
        )
    }

    private fun resolveLegacyRarity(constructorArgs: List<String>, rarityExpression: String): String? {
        Regex("""(?:Enchantment\.)?Rarity\.([A-Z_]+)""").find(rarityExpression)?.let { return it.groupValues[1] }
        constructorArgs.forEach { arg ->
            Regex("""(?:Enchantment\.)?Rarity\.([A-Z_]+)""").find(arg)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun parseLegacyEquipmentSlots(expression: String): List<String> =
        Regex("""EquipmentSlot\.([A-Z_]+)""").findAll(expression).map { it.groupValues[1] }.toList()

    private fun parseLegacyIntReturnMethod(source: String, methodName: String): Int? {
        val body = legacyMethodBody(source, methodName) ?: return null
        return Regex("""return\s+(-?\d+)\s*;""").find(body)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseLegacyCostMethod(source: String, methodName: String, minCost: LinearIntValue?): LinearIntValue? {
        val body = legacyMethodBody(source, methodName) ?: return null
        val expression = Regex("""return\s+([^;]+);""").find(body)?.groupValues?.get(1)?.trim() ?: return null
        if (methodName == "getMaxCost" && minCost != null) {
            Regex("""(?:this\.)?getMinCost\(\s*level\s*\)\s*\+\s*(\d+)""").find(expression)?.let {
                val add = it.groupValues[1].toInt()
                return LinearIntValue(minCost.base + add, minCost.perLevelAboveFirst)
            }
        }
        Regex("""(-?\d+)\s*\+\s*\(\s*level\s*-\s*1\s*\)\s*\*\s*(-?\d+)""").find(expression)?.let {
            return LinearIntValue(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("""\(\s*level\s*-\s*1\s*\)\s*\*\s*(-?\d+)\s*\+\s*(-?\d+)""").find(expression)?.let {
            return LinearIntValue(it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        Regex("""level\s*\*\s*(-?\d+)""").find(expression)?.let {
            val value = it.groupValues[1].toInt()
            return LinearIntValue(value, value)
        }
        Regex("""^-?\d+$""").find(expression)?.let {
            return LinearIntValue(expression.toInt(), 0)
        }
        return null
    }

    private fun legacyMethodBody(source: String, methodName: String): String? {
        return legacyMethodBodySpan(source, methodName)?.text
    }

    private fun legacyMethodBodySpan(source: String, methodName: String): LegacySourceSpan? {
        val declaration = findJavaMethodDeclaration(source, methodName) ?: return null
        val openBrace = source.indexOf('{', declaration.range.last)
        if (openBrace < 0) return null
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return null
        return LegacySourceSpan(
            text = source.substring(openBrace + 1, closeBrace),
            offset = openBrace + 1
        )
    }

    private fun legacyBooleanReturn(source: String, methodName: String): Boolean? {
        val body = legacyMethodBody(source, methodName) ?: return null
        return when (Regex("""return\s+(true|false)\s*;""").find(body)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun legacyClassChainAny(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration,
        predicate: (String) -> Boolean
    ): Boolean {
        val visited = mutableSetOf<String>()
        fun walk(name: String): Boolean {
            if (!visited.add(name)) return false
            val matches = classSources[name].orEmpty()
            if (matches.map { it.first }.distinct().size > 1) {
                errors.add("Cannot derive custom enchantment data for ${registration.className}: class chain source '$name' is ambiguous")
                return false
            }
            val source = uniqueLegacyClassSource(name, classSources)?.second ?: return false
            if (predicate(source)) return true
            val base = Regex("""\bclass\s+${Regex.escape(name.substringAfterLast('.'))}\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?: return false
            return walk(resolveLegacyClassReference(base, source, classSources) ?: return false)
        }
        return walk(className)
    }

    private fun legacyClassChainFirstBoolean(
        className: String,
        classSources: Map<String, List<Pair<Path, String>>>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration,
        methodName: String
    ): Boolean? {
        val visited = mutableSetOf<String>()
        fun walk(name: String): Boolean? {
            if (!visited.add(name)) return null
            val matches = classSources[name].orEmpty()
            if (matches.map { it.first }.distinct().size > 1) {
                errors.add("Cannot derive custom enchantment data for ${registration.className}: class chain source '$name' is ambiguous")
                return null
            }
            val source = uniqueLegacyClassSource(name, classSources)?.second ?: return null
            legacyBooleanReturn(source, methodName)?.let { return it }
            val base = Regex("""\bclass\s+${Regex.escape(name.substringAfterLast('.'))}\s+extends\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)""")
                .find(source)
                ?.groupValues
                ?.get(1)
                ?: return null
            return walk(resolveLegacyClassReference(base, source, classSources) ?: return null)
        }
        return walk(className)
    }

    private fun legacyRarityWeight(rarity: String): Int = when (rarity) {
        "COMMON" -> 10
        "UNCOMMON" -> 5
        "RARE" -> 2
        "VERY_RARE" -> 1
        else -> 1
    }

    private fun legacyRarityAnvilCost(rarity: String): Int = when (rarity) {
        "COMMON" -> 1
        "UNCOMMON" -> 2
        "RARE" -> 4
        "VERY_RARE" -> 8
        else -> 1
    }

    private fun deriveLegacyExclusiveSet(
        source: String,
        enchantmentRefs: LegacyReferenceIndex<LegacyCustomEnchantmentRegistration>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration
    ): List<String>? {
        val bodySpan = legacyMethodBodySpan(source, "checkCompatibility") ?: return emptyList()
        val body = bodySpan.text
        val results = linkedSetOf<String>()
        var unresolved = false
        Regex("""other\s*!=\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.get\(\)""")
            .findAll(body)
            .forEach { match ->
                val expression = match.groupValues[1]
                val ref = resolveLegacyReferenceExpression(
                    expression,
                    source,
                    bodySpan.offset + match.range.first,
                    enchantmentRefs
                )
                if (ref == null) {
                    unresolved = true
                    errors.add("Cannot derive custom enchantment data for ${registration.className}: exclusive enchantment reference '$expression' is unresolved")
                } else {
                    results.add("${ref.modId}:${ref.registryName}")
                }
            }
        Regex("""other\s*!=\s*Enchantments\.([A-Z0-9_]+)""")
            .findAll(body)
            .forEach { match -> results.add("minecraft:${match.groupValues[1].lowercase()}") }
        if (unresolved) return null
        return results.toList()
    }

    private fun deriveLegacyEnchantmentEffects(
        source: String,
        registryEntries: LegacyReferenceIndex<RegistryEntryRef>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration
    ): String? {
        val effects = linkedMapOf<String, MutableList<String>>()
        derivePostHurtIgniteEffect(source)?.let { effects.getOrPut("minecraft:post_attack") { mutableListOf() }.add(it) }
        derivePostHurtMobEffect(source, registryEntries, errors, registration)?.let { effects.getOrPut("minecraft:post_attack") { mutableListOf() }.add(it) }
        deriveDamageBonusEffect(source)?.let { effects.getOrPut("minecraft:damage") { mutableListOf() }.add(it) }

        val hasPostHurt = legacyMethodBody(source, "doPostHurt") != null
        val hasDamageBonus = legacyMethodBody(source, "getDamageBonus") != null
        if (hasPostHurt && !effects.containsKey("minecraft:post_attack")) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: doPostHurt body has no supported effect shape")
        }
        if (hasDamageBonus && !effects.containsKey("minecraft:damage")) {
            errors.add("Cannot derive custom enchantment data for ${registration.className}: getDamageBonus body has no supported value effect shape")
        }
        if (effects.isEmpty()) return null

        return buildString {
            append("{\n")
            effects.entries.forEachIndexed { index, (component, entries) ->
                if (index > 0) append(",\n")
                append("    \"").append(component).append("\": [\n")
                entries.forEachIndexed { entryIndex, entry ->
                    if (entryIndex > 0) append(",\n")
                    append(indentJson(entry, "      "))
                }
                append("\n    ]")
            }
            append("\n  }")
        }
    }

    private fun derivePostHurtIgniteEffect(source: String): String? {
        val body = legacyMethodBody(source, "doPostHurt") ?: return null
        if (!body.contains("setSecondsOnFire(")) return null
        val duration = Regex("""setSecondsOnFire\(\s*(\d+)\s*\+\s*\([^)]*nextInt\(\s*level\s*\)\s*\*\s*(\d+)\s*\)""")
            .find(body)
            ?.let { LinearFloatValue(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            ?: Regex("""setSecondsOnFire\(\s*(\d+)""").find(body)?.let { LinearFloatValue(it.groupValues[1].toDouble(), 0.0) }
            ?: return null
        val chance = legacyRandomChancePerLevel(source) ?: return null
        return """
{
  "affected": "attacker",
  "effect": {
    "type": "minecraft:ignite",
    "duration": ${legacyLevelBasedValueJson(duration)}
  },
  "enchanted": "victim",
  "requirements": ${legacyRandomChanceJson(chance)}
}
""".trimIndent()
    }

    private fun derivePostHurtMobEffect(
        source: String,
        registryEntries: LegacyReferenceIndex<RegistryEntryRef>,
        errors: MutableList<String>,
        registration: LegacyCustomEnchantmentRegistration
    ): String? {
        val body = legacyMethodBody(source, "doPostHurt") ?: return null
        val helperCall = legacyMethodCalls(body)
            .firstOrNull { call ->
                call.arguments.size >= 4 &&
                    call.arguments[3].contains("shouldHit") &&
                    legacyMethodBody(source, call.name)?.contains("new MobEffectInstance(") == true
            }
            ?: return null
        val helperName = helperCall.name
        val helperBodySpan = legacyMethodBodySpan(source, helperName) ?: return null
        val helperBody = helperBodySpan.text
        val effectRef = Regex("""new\s+MobEffectInstance\(\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.get\(\)""")
            .find(helperBody)
            ?: return null
        val effectId = resolveLegacyReferenceExpression(
            effectRef.groupValues[1],
            source,
            helperBodySpan.offset + effectRef.range.first,
            registryEntries
        )?.id
            ?: run {
                errors.add("Cannot derive custom enchantment data for ${registration.className}: mob effect reference '${effectRef.groupValues[1]}' is unresolved")
                return null
            }
        val durationTicks = helperCall.arguments[1].trim().toDoubleOrNull() ?: return null
        val amplifier = parseLegacyAmplifierExpression(helperCall.arguments[2].trim()) ?: return null
        val chance = legacyRandomChancePerLevel(source) ?: return null
        val durationSeconds = LinearFloatValue(durationTicks / 20.0, 0.0)
        return """
{
  "affected": "attacker",
  "effect": {
    "type": "minecraft:apply_mob_effect",
    "to_apply": "$effectId",
    "min_duration": ${legacyLevelBasedValueJson(durationSeconds)},
    "max_duration": ${legacyLevelBasedValueJson(durationSeconds)},
    "min_amplifier": ${legacyLevelBasedValueJson(amplifier)},
    "max_amplifier": ${legacyLevelBasedValueJson(amplifier)}
  },
  "enchanted": "victim",
  "requirements": ${legacyRandomChanceJson(chance)}
}
""".trimIndent()
    }

    private data class LegacyMethodCall(val name: String, val arguments: List<String>)

    private fun legacyMethodCalls(source: String): List<LegacyMethodCall> {
        val calls = mutableListOf<LegacyMethodCall>()
        val pattern = Regex("""\b([A-Za-z_$][\w$]*)\s*\(""")
        for (match in pattern.findAll(source)) {
            val name = match.groupValues[1]
            if (name in setOf("if", "for", "while", "switch", "catch", "new", "return", "super", "this")) continue
            val openParen = source.indexOf('(', match.range.first)
            if (openParen < 0) continue
            val closeParen = findMatchingDelimiter(source, openParen, '(', ')')
            if (closeParen < 0) continue
            calls.add(LegacyMethodCall(name, splitTopLevelArguments(source.substring(openParen + 1, closeParen))))
        }
        return calls
    }

    private fun parseLegacyAmplifierExpression(expression: String): LinearFloatValue? {
        Regex("""level\s*-\s*(\d+)""").find(expression)?.let {
            val subtract = it.groupValues[1].toDouble()
            return LinearFloatValue(1.0 - subtract, 1.0)
        }
        Regex("""level""").find(expression)?.let {
            return LinearFloatValue(1.0, 1.0)
        }
        expression.toDoubleOrNull()?.let { return LinearFloatValue(it, 0.0) }
        return null
    }

    private fun deriveDamageBonusEffect(source: String): String? {
        val body = legacyMethodBody(source, "getDamageBonus") ?: return null
        val expression = Regex("""return\s+([^;]+);""").find(body)?.groupValues?.get(1)?.trim() ?: return null
        val value = Regex("""(-?)\s*level\s*\*\s*([0-9]+(?:\.[0-9]+)?)[Ff]?""").find(expression)?.let {
            val sign = if (it.groupValues[1].contains("-")) -1.0 else 1.0
            it.groupValues[2].toDouble() * sign
        } ?: return null
        return """
{
  "effect": {
    "type": "minecraft:add",
    "value": ${legacyLevelBasedValueJson(LinearFloatValue(value, value))}
  }
}
""".trimIndent()
    }

    private fun legacyRandomChancePerLevel(source: String): LinearFloatValue? {
        Regex("""nextFloat\(\)\s*<\s*([0-9]+(?:\.[0-9]+)?)F?\s*\*\s*\(float\)\s*level""")
            .find(source)
            ?.let {
                val value = it.groupValues[1].toDouble()
                return LinearFloatValue(value, value)
            }
        Regex("""nextFloat\(\)\s*<\s*([0-9]+(?:\.[0-9]+)?)F?\s*\*\s*level""")
            .find(source)
            ?.let {
                val value = it.groupValues[1].toDouble()
                return LinearFloatValue(value, value)
            }
        return null
    }

    private fun legacyRandomChanceJson(chance: LinearFloatValue): String = """
{
  "chance": {
    "type": "minecraft:enchantment_level",
    "amount": ${legacyLevelBasedValueJson(chance)}
  },
  "condition": "minecraft:random_chance"
}
""".trimIndent()

    private fun legacyLevelBasedValueJson(value: LinearFloatValue): String {
        if (value.perLevelAboveFirst == 0.0) return formatJsonNumber(value.base)
        return """{
      "type": "minecraft:linear",
      "base": ${formatJsonNumber(value.base)},
      "per_level_above_first": ${formatJsonNumber(value.perLevelAboveFirst)}
    }"""
    }

    private fun legacyEnchantmentJson(definition: LegacyEnchantmentDefinition): String {
        val fields = mutableListOf<String>()
        fields.add("""  "anvil_cost": ${definition.anvilCost}""")
        fields.add("""  "description": {
    "translate": "enchantment.${definition.registration.modId}.${definition.registration.registryName}"
  }""")
        definition.effectsJson?.let { fields.add("""  "effects": $it""") }
        if (definition.exclusiveSet.isNotEmpty()) {
            fields.add("""  "exclusive_set": [
${definition.exclusiveSet.joinToString(",\n") { """    "$it"""" }}
  ]""")
        }
        fields.add("""  "max_cost": {
    "base": ${definition.maxCost.base},
    "per_level_above_first": ${definition.maxCost.perLevelAboveFirst}
  }""")
        fields.add("""  "max_level": ${definition.maxLevel}""")
        fields.add("""  "min_cost": {
    "base": ${definition.minCost.base},
    "per_level_above_first": ${definition.minCost.perLevelAboveFirst}
  }""")
        definition.primaryItems?.let { fields.add("""  "primary_items": "$it"""") }
        fields.add("""  "slots": [
${definition.slots.joinToString(",\n") { """    "$it"""" }}
  ]""")
        fields.add("""  "supported_items": "${definition.supportedItems}"""")
        fields.add("""  "weight": ${definition.weight}""")
        return "{\n" + fields.joinToString(",\n") + "\n}\n"
    }

    private fun legacyItemTagJson(values: List<String>): String =
        "{\n  \"values\": [\n" +
            values.joinToString(",\n") { "    \"$it\"" } +
            "\n  ]\n}\n"

    private fun indentJson(source: String, indent: String): String =
        source.lines().joinToString("\n") { line -> if (line.isBlank()) line else indent + line }

    private fun formatJsonNumber(value: Double): String {
        val text = if (value == value.toLong().toDouble()) "${value.toLong()}.0" else value.toString()
        return if (text == "-0.0") "0.0" else text
    }

    private fun migrateCustomEnchantmentResourceKeys(source: String): String {
        if (!source.contains("DeferredRegister<Enchantment>") ||
            !source.contains("DeferredHolder<Enchantment, Enchantment>") ||
            !source.contains("ENCHANTMENTS.register(")) {
            return source
        }

        val registerPattern = Regex(
            """public\s+static\s+final\s+DeferredRegister<Enchantment>\s+ENCHANTMENTS\s*=\s*DeferredRegister\.create\(\s*[^,]+,\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?)\s*\)\s*;""",
            RegexOption.DOT_MATCHES_ALL
        )
        val registerMatch = registerPattern.find(source) ?: return source
        val modIdExpression = registerMatch.groupValues[1]

        val entryPattern = Regex(
            """public\s+static\s+final\s+DeferredHolder<Enchantment,\s*Enchantment>\s+([A-Za-z_$][\w$]*)\s*=\s*ENCHANTMENTS\.register\(\s*"([^"]+)"\s*,\s*(?:[A-Za-z_$][\w$]*::new|\(\)\s*->\s*new\s+[A-Za-z_$][\w$]*\([^)]*\))\s*\)\s*;""",
            RegexOption.DOT_MATCHES_ALL
        )
        val entries = entryPattern.findAll(source).map { it.groupValues[1] to it.groupValues[2] }.toList()
        if (entries.isEmpty()) return source

        val helper = """
private static net.minecraft.core.Holder<Enchantment> holder(net.minecraft.world.level.Level level, net.minecraft.resources.ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key);
    }
        """.trimIndent()

        var result = registerPattern.replace(source, helper)
        result = entryPattern.replace(result) { match ->
            val name = match.groupValues[1]
            val registryName = match.groupValues[2]
            """
public static final net.minecraft.resources.ResourceKey<Enchantment> $name =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath($modIdExpression, "$registryName"));
            """.trimIndent()
        }
        val keys = entries.joinToString(", ") { it.first }
        if (keys.isNotBlank() && !Regex("""\bENCHANTMENTS\s*=\s*java\.util\.List\.of\(""").containsMatchIn(result)) {
            val listField = """

public static final java.util.List<net.minecraft.resources.ResourceKey<Enchantment>> ENCHANTMENTS =
            java.util.List.of($keys);
            """.trimEnd()
            val insertAt = result.lastIndexOf('}')
            if (insertAt >= 0) {
                result = result.substring(0, insertAt).trimEnd() + "\n" + listField + "\n" + result.substring(insertAt)
            }
        }

        for ((name, _) in entries) {
            result = Regex("""stack\.getEnchantmentLevel\(\s*${Regex.escape(name)}\.get\(\)\s*\)""")
                .replace(result, "stack.getEnchantmentLevel(holder(level, $name))")
            result = Regex("""stack\.enchant\(\s*${Regex.escape(name)}\.get\(\)\s*,""")
                .replace(result, "stack.enchant(holder(level, $name),")
            result = Regex(
                """public\s+static\s+boolean\s+([A-Za-z_$][\w$]*)\s*\(\s*Enchantment\s+([A-Za-z_$][\w$]*)\s*\)\s*\{\s*return\s+\2\s*==\s*${Regex.escape(name)}\.get\(\)\s*;\s*\}"""
            ).replace(result) { match ->
                val methodName = match.groupValues[1]
                val paramName = match.groupValues[2]
                """
public static boolean $methodName(net.minecraft.core.Holder<Enchantment> $paramName) {
        return $paramName.is($name);
    }
                """.trimIndent()
            }
        }

        return result
    }

    private fun migrateParticleNetworkCodecs(source: String): String {
        if (!source.contains("ParticleOptions") ||
            !source.contains("getDeserializer().fromNetwork") ||
            !source.contains("writeToNetwork")) {
            return source
        }

        var result = source
        result = Regex("""\bFriendlyByteBuf\b""").replace(result, "RegistryFriendlyByteBuf")
        result = result.replace(
            Regex("""return\s+particleType\.getDeserializer\(\)\.fromNetwork\(\s*particleType\s*,\s*buf\s*\)\s*;"""),
            "return particleType.streamCodec().decode(buf);"
        )
        result = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\.particleOptions\.writeToNetwork\(\s*buf\s*\)\s*;""")
            .replace(result, "writeParticle($1.particleOptions, buf);")

        if (!result.contains("void writeParticle(")) {
            val readParticlePattern = Regex(
                """(?s)(private\s+<T\s+extends\s+ParticleOptions>\s+T\s+readParticle\s*\(\s*ParticleType<T>\s+particleType\s*,\s*RegistryFriendlyByteBuf\s+buf\s*\)\s*\{\s*return\s+particleType\.streamCodec\(\)\.decode\(buf\);\s*\})"""
            )
            result = readParticlePattern.replace(result) { match ->
                match.value + """


	private <T extends ParticleOptions> void writeParticle(T particleOptions, RegistryFriendlyByteBuf buf) {
		@SuppressWarnings("unchecked")
		ParticleType<T> particleType = (ParticleType<T>) particleOptions.getType();
		particleType.streamCodec().encode(buf, particleOptions);
	}"""
            }
        }

        result = removeImportLine(result, "net.minecraft.network.FriendlyByteBuf")
        return result
    }

    private fun migrateJadeTooltipElementHelper(source: String): String {
        if (!source.contains(".getElementHelper")) return source
        if (!source.contains("import snownee.jade.api.ITooltip;") &&
            !source.contains("snownee.jade.api.ITooltip")) {
            return source
        }

        val identifier = """[A-Za-z_$][\w$]*"""
        val tooltipVariables = Regex("""\b(?:snownee\.jade\.api\.)?ITooltip\s+($identifier)\b""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        if (tooltipVariables.isEmpty()) return source

        var result = source
        for (variable in tooltipVariables) {
            result = Regex("""\b${Regex.escape(variable)}\s*\.\s*getElementHelper\s*\(\s*\)""")
                .replace(result, "IElementHelper.get()")
        }
        return result
    }

    private data class NetworkHooksOpenScreenMigration(
        val content: String,
        val changes: List<Change>,
        val errors: List<String>
    )

    private fun migrateNetworkHooksOpenScreen(source: String, file: Path): NetworkHooksOpenScreenMigration {
        if (!source.contains("NetworkHooks.openScreen")) {
            return NetworkHooksOpenScreenMigration(source, emptyList(), emptyList())
        }

        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val out = StringBuilder(source.length)
        var cursor = 0
        var searchFrom = 0
        val callName = "NetworkHooks.openScreen"

        while (true) {
            val callStart = source.indexOf(callName, searchFrom)
            if (callStart < 0) break
            val openParen = source.indexOf('(', callStart + callName.length)
            if (openParen < 0) {
                errors.add("Cannot safely migrate NetworkHooks.openScreen in ${file}:${source.lineNumberAt(callStart)}: missing argument list")
                break
            }
            val closeParen = findMatchingDelimiter(source, openParen, '(', ')')
            if (closeParen < 0) {
                errors.add("Cannot safely migrate NetworkHooks.openScreen in ${file}:${source.lineNumberAt(callStart)}: unbalanced argument list")
                break
            }

            val args = splitTopLevelArguments(source.substring(openParen + 1, closeParen))
            val replacement = when (args.size) {
                2 -> "(${args[0]}).openMenu(${args[1]})"
                3 -> {
                    val extraData = args[2]
                    when {
                        isNetworkHooksExtraDataWriter(extraData, source) ->
                            "(${args[0]}).openMenu(${args[1]}, $extraData)"
                        isNetworkHooksBlockPosExtra(extraData, source) ->
                            "(${args[0]}).openMenu(${args[1]}, buf -> buf.writeBlockPos($extraData))"
                        else -> null
                    }
                }
                else -> null
            }

            if (replacement == null) {
                errors.add(
                    "Cannot safely migrate NetworkHooks.openScreen in ${file}:${source.lineNumberAt(callStart)}: " +
                        "expected 2 args or a statically identifiable Consumer/FriendlyByteBuf writer or BlockPos third arg"
                )
                out.append(source, cursor, closeParen + 1)
            } else {
                out.append(source, cursor, callStart)
                out.append(replacement)
                changes.add(Change(
                    file = file,
                    line = source.lineNumberAt(callStart),
                    description = "Migrate NetworkHooks.openScreen with balanced argument parsing",
                    before = source.substring(callStart, closeParen + 1),
                    after = replacement,
                    confidence = Confidence.HIGH,
                    ruleId = "networkhooks-openscreen-balanced"
                ))
            }

            cursor = closeParen + 1
            searchFrom = closeParen + 1
        }

        out.append(source, cursor, source.length)
        return NetworkHooksOpenScreenMigration(out.toString(), changes, errors)
    }

    private fun migrateInventoryRecipeHolderInterface(source: String): String {
        val oldImport = "import net.minecraft.world.inventory.RecipeHolder;"
        if (!source.contains(oldImport)) return source
        return source
            .replace(oldImport, "import net.minecraft.world.inventory.RecipeCraftingHolder;")
            .let { result ->
                Regex("""(implements\s+[^{;\r\n]*?)\bRecipeHolder\b""")
                    .replace(result) { match -> "${match.groupValues[1]}RecipeCraftingHolder" }
            }
    }

    private fun migrateRemovedTagManagerAccess(source: String): String {
        if (!source.contains("ITagManager<")) return source
        val lines = removeImportLine(source, "net.neoforged.neoforge.registries.tags.ITagManager").lines()
        val out = mutableListOf<String>()
        var i = 0
        val id = """[A-Za-z_$][\w$]*"""
        val declaration = Regex("""^(\s*)ITagManager<\s*Item\s*>\s+($id)\s*=\s*BuiltInRegistries\.ITEM\.tags\(\)\s*;\s*$""")
        while (i < lines.size) {
            val decl = declaration.find(lines[i])
            if (decl != null && i + 2 < lines.size) {
                val varName = decl.groupValues[2]
                val nullGuard = Regex("""^\s*if\s*\(\s*${Regex.escape(varName)}\s*!=\s*null\s*\)\s*\{\s*$""")
                if (nullGuard.matches(lines[i + 1])) {
                    val loop = Regex("""^(\s*)${Regex.escape(varName)}\.getTag\((.*)\)\.stream\(\)\.forEach\(\s*\(\s*($id)\s*\)\s*->\s*(.*)\);\s*$""")
                        .find(lines[i + 2])
                    if (loop != null && i + 3 < lines.size && lines[i + 3].trim() == "}") {
                        val indent = loop.groupValues[1]
                        val tagExpression = loop.groupValues[2].trim()
                        val itemVariable = loop.groupValues[3]
                        val body = loop.groupValues[4].trim()
                        out.add("${indent}BuiltInRegistries.ITEM.getTagOrEmpty($tagExpression).forEach((holder) -> { Item $itemVariable = holder.value(); $body; });")
                        i += 4
                        continue
                    }

                    val emptyCheck = Regex("""^(\s*)if\s*\(\s*${Regex.escape(varName)}\.getTag\((.*)\)\.isEmpty\(\)\s*\)\s*\{\s*$""")
                        .find(lines[i + 2])
                    if (emptyCheck != null) {
                        val indent = emptyCheck.groupValues[1]
                        val tagExpression = emptyCheck.groupValues[2].trim()
                        out.add("${indent}if (!BuiltInRegistries.ITEM.getTagOrEmpty($tagExpression).iterator().hasNext()) {")
                        i += 3
                        while (i < lines.size) {
                            out.add(lines[i])
                            val copiedInnerClose = lines[i].trim() == "}"
                            i++
                            if (copiedInnerClose) break
                        }
                        if (i < lines.size && lines[i].trim() == "}") {
                            i++
                        }
                        continue
                    }
                }
            }
            out.add(lines[i])
            i++
        }
        return out.joinToString("\n")
    }

    private fun isNetworkHooksExtraDataWriter(expression: String, source: String): Boolean {
        val trimmed = stripOuterParentheses(expression.trim())
        if (trimmed.contains("->") || trimmed.contains("::")) return true
        if (trimmed.startsWith("new Consumer") || trimmed.startsWith("new java.util.function.Consumer")) return true
        val identifier = Regex("""^[A-Za-z_$][\w$]*$""").matchEntire(trimmed)?.value ?: return false
        return Regex("""\b(?:java\.util\.function\.)?Consumer\s*<[^;=]+>\s+${Regex.escape(identifier)}\b""")
            .containsMatchIn(source)
    }

    private fun isNetworkHooksBlockPosExtra(expression: String, source: String): Boolean {
        val trimmed = stripOuterParentheses(expression.trim())
        if (trimmed.startsWith("new BlockPos(") ||
            trimmed.startsWith("new net.minecraft.core.BlockPos(") ||
            trimmed.startsWith("BlockPos.") ||
            trimmed.startsWith("net.minecraft.core.BlockPos.") ||
            trimmed.endsWith(".getBlockPos()")) {
            return true
        }
        val identifier = Regex("""^[A-Za-z_$][\w$]*$""").matchEntire(trimmed)?.value ?: return false
        return Regex("""\b(?:net\.minecraft\.core\.)?BlockPos\s+${Regex.escape(identifier)}\b""")
            .containsMatchIn(source)
    }

    private fun stripOuterParentheses(expression: String): String {
        var result = expression
        while (result.startsWith("(") && result.endsWith(")")) {
            val closeParen = findMatchingDelimiter(result, 0, '(', ')')
            if (closeParen != result.lastIndex) break
            result = result.substring(1, result.lastIndex).trim()
        }
        return result
    }

    private fun removeImportLine(source: String, importName: String): String =
        Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n?""").replace(source, "")

    private fun usesSymbolOutsideImports(source: String, symbol: String): Boolean =
        source.lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .any { Regex("""\b${Regex.escape(symbol)}\b""").containsMatchIn(it) }

    private fun replaceFirstJavaMethodByName(source: String, methodName: String, replacement: String): String {
        val declaration = findJavaMethodDeclaration(source, methodName)
            ?: return source
        val start = annotationStartBefore(source, declaration.range.first)
        val openBrace = source.indexOf('{', declaration.range.last)
        if (openBrace < 0) return source
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return source
        var end = closeBrace + 1
        if (end < source.length && source[end] == '\r') end++
        if (end < source.length && source[end] == '\n') end++
        return source.substring(0, start) + replacement + "\n" + source.substring(end)
    }

    private fun removeFirstJavaMethodByName(source: String, methodName: String, searchFrom: Int = 0): Pair<String, Int?> {
        val declaration = findJavaMethodDeclaration(source, methodName, searchFrom)
            ?: return source to null
        val start = annotationStartBefore(source, declaration.range.first)
        val openBrace = source.indexOf('{', declaration.range.last)
        if (openBrace < 0) return source to null
        val closeBrace = findMatchingBrace(source, openBrace)
        if (closeBrace < 0) return source to null
        var end = closeBrace + 1
        if (end < source.length && source[end] == '\r') end++
        if (end < source.length && source[end] == '\n') end++
        return source.removeRange(start, end) to start
    }

    private fun findJavaMethodDeclaration(source: String, methodName: String, searchFrom: Int = 0): MatchResult? =
        Regex(
            """(?m)^[ \t]*(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:<[^>\r\n]+>\s+)?[\w.$<>\[\], ?]+\s+${Regex.escape(methodName)}\s*\("""
        ).find(source, searchFrom)

    private fun annotationStartBefore(source: String, declarationIndex: Int): Int {
        val lineStart = source.lastIndexOf('\n', declarationIndex).let { if (it < 0) 0 else it + 1 }
        val annotations = Regex("""(?m)(?:^[ \t]*@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*\r?\n)+[ \t]*$""")
            .findAll(source.substring(0, lineStart))
            .lastOrNull()
        return annotations?.range?.first ?: lineStart
    }

    private fun splitTopLevelArguments(source: String): List<String> {
        return splitTopLevelArgumentExpressions(source, baseOffset = 0).map { it.text }
    }

    private fun splitTopLevelArgumentExpressions(source: String, baseOffset: Int): List<LegacyExpression> {
        val args = mutableListOf<LegacyExpression>()
        var start = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var inChar = false
        var escaped = false

        fun addArgument(end: Int) {
            var trimmedStart = start
            var trimmedEnd = end
            while (trimmedStart < trimmedEnd && source[trimmedStart].isWhitespace()) trimmedStart++
            while (trimmedEnd > trimmedStart && source[trimmedEnd - 1].isWhitespace()) trimmedEnd--
            if (trimmedStart < trimmedEnd) {
                args += LegacyExpression(
                    text = source.substring(trimmedStart, trimmedEnd),
                    offset = baseOffset + trimmedStart
                )
            }
        }

        for (i in source.indices) {
            val c = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            if (inChar) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '\'') {
                    inChar = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '\'' -> inChar = true
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                ',' -> if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                    addArgument(i)
                    start = i + 1
                }
            }
        }
        addArgument(source.length)
        return args
    }

    private fun findMatchingDelimiter(source: String, openIndex: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        var inString = false
        var inChar = false
        var escaped = false
        var inLineComment = false
        var inBlockComment = false
        for (i in openIndex until source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') inBlockComment = false
                continue
            }
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            if (inChar) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '\'') {
                    inChar = false
                }
                continue
            }
            if (c == '/' && next == '/') {
                inLineComment = true
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                continue
            }
            if (c == '"') {
                inString = true
                continue
            }
            if (c == '\'') {
                inChar = true
                continue
            }
            if (c == openChar) depth++
            if (c == closeChar) {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }

    private fun findMatchingBrace(source: String, openBrace: Int): Int {
        var depth = 0
        var inString = false
        var inChar = false
        var escaped = false
        var inLineComment = false
        var inBlockComment = false
        var index = openBrace
        while (index < source.length) {
            val c = source[index]
            val next = source.getOrNull(index + 1)
            if (!inString && !inChar && !inLineComment && !inBlockComment &&
                c == '"' && next == '"' && source.getOrNull(index + 2) == '"'
            ) {
                index = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                continue
            }
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                index++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    index += 2
                    continue
                }
                index++
                continue
            }
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                index++
                continue
            }
            if (inChar) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '\'') {
                    inChar = false
                }
                index++
                continue
            }
            if (c == '/' && next == '/') {
                inLineComment = true
                index += 2
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                index += 2
                continue
            }
            if (c == '"') {
                inString = true
                index++
                continue
            }
            if (c == '\'') {
                inChar = true
                index++
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return index
            }
            index++
        }
        return -1
    }

    private data class ParticleField(
        val type: String,
        val name: String
    )

    private fun dedupeImports(content: String): String {
        val seen = mutableSetOf<String>()
        return content.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("import ") && !seen.add(trimmed)
            }
            .joinToString("\n")
    }

    private fun String.lineNumberAt(offset: Int): Int =
        take(offset.coerceIn(0, length)).count { it == '\n' } + 1

    /**
     * Build the complete rule list by combining explicit text-replacements.json rules
     * with auto-generated regex rules from class-renames.json.
     * Explicit rules come first (they include ordered package renames),
     * then class rename rules are appended.
     */
    private fun buildRuleList(): List<TextReplacement> {
        val explicitRules = mappingDb.getTextReplacements()
        val classRenameRules = mappingDb.getAllClassMappings()
            .filter { (forge, mapping) ->
                // Skip entries already covered by explicit text rules
                explicitRules.none { it.pattern.contains(forge) }
            }
            .map { (forge, mapping) ->
                TextReplacement(
                    id = "cls-auto-$forge",
                    pattern = "\\b${Regex.escape(forge)}\\b",
                    replacement = mapping.neoForgeClass,
                    description = "Class rename: $forge -> ${mapping.neoForgeClass}",
                    isRegex = true
                )
            }
        return explicitRules + classRenameRules
    }

    private fun migrateForgeInternalNameDescriptors(source: String): String {
        if (!source.contains("Lnet/minecraftforge/")) return source
        return listOf(
            "Lnet/minecraftforge/fml/" to "Lnet/neoforged/fml/",
            "Lnet/minecraftforge/eventbus/" to "Lnet/neoforged/bus/",
            "Lnet/minecraftforge/api/distmarker/" to "Lnet/neoforged/api/distmarker/",
            "Lnet/minecraftforge/forgespi/" to "Lnet/neoforged/neoforgespi/",
            "Lnet/minecraftforge/accesstransformer/" to "Lnet/neoforged/accesstransformer/",
            "Lnet/minecraftforge/coremod/" to "Lnet/neoforged/coremod/",
            "Lnet/minecraftforge/jarjar/" to "Lnet/neoforged/jarjar/",
            "Lnet/minecraftforge/" to "Lnet/neoforged/neoforge/"
        ).fold(source) { current, (oldOwner, newOwner) ->
            current.replace(oldOwner, newOwner)
        }
    }

    private fun findJavaFiles(projectDir: Path): List<Path> {
        return Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/") ||
                    rel.startsWith("src/references/") || rel.contains("/src/references/")
            }}
            .toList()
    }
}
