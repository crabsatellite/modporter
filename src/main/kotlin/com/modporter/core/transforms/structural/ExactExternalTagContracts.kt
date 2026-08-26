package com.modporter.core.transforms.structural

import com.github.javaparser.ast.expr.MethodCallExpr

internal object ExactExternalTagContracts {
    enum class Effect {
        READ,
        MUTATE
    }

    fun compoundTagArgumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int,
        exact: ExactJavaSemantics,
        typeIndex: JavaProjectTypeIndex? = null
    ): Effect? {
        if (argumentIndex == 0 &&
            call.nameAsString == "of" &&
            call.scope.map {
                exact.exactStaticScope(it, "CustomData", CUSTOM_DATA)
            }.orElse(false)
        ) {
            return Effect.READ
        }
        if (call.scope.map {
                exact.exactStaticScope(it, "ItemStack", ITEM_STACK)
            }.orElse(false) &&
            ((call.nameAsString == "of" && argumentIndex == 0) ||
                (call.nameAsString == "parseOptional" &&
                    argumentIndex == call.arguments.lastIndex))
        ) {
            return Effect.READ
        }
        if (call.nameAsString == "readBlockState" &&
            argumentIndex == call.arguments.lastIndex &&
            call.scope.map {
                exact.exactStaticScope(it, "NbtUtils", NBT_UTILS)
            }.orElse(false)
        ) {
            return Effect.READ
        }
        val receiverType = typeIndex?.methodCallReceiverType(call)
        if (call.nameAsString == "deserializeNBT" &&
            argumentIndex == call.arguments.lastIndex &&
            receiverType in ITEM_STACK_HANDLERS
        ) {
            return Effect.READ
        }
        if (argumentIndex == 0 &&
            call.nameAsString in POTION_UTILS_READS &&
            call.scope.map {
                exact.exactStaticScope(it, "PotionUtils", POTION_UTILS)
            }.orElse(false)
        ) {
            return Effect.READ
        }
        if (argumentIndex != 0 ||
            !call.scope.map {
                exact.exactStaticScope(it, "NBTHelper", CATNIP_NBT_HELPER)
            }.orElse(false)
        ) {
            return null
        }
        return when (call.nameAsString) {
            "readEnum" -> Effect.READ
            "writeEnum" -> Effect.MUTATE
            else -> null
        }
    }

    fun compoundTagReceiverEffect(methodName: String): Effect? = when (methodName) {
        in MUTATOR_METHODS -> Effect.MUTATE
        in SAFE_READ_METHODS -> Effect.READ
        else -> null
    }

    private const val CATNIP_NBT_HELPER = "net.createmod.catnip.nbt.NBTHelper"
    private const val CUSTOM_DATA = "net.minecraft.world.item.component.CustomData"
    private const val POTION_UTILS = "net.minecraft.world.item.alchemy.PotionUtils"
    private const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    private const val NBT_UTILS = "net.minecraft.nbt.NbtUtils"
    private val ITEM_STACK_HANDLERS = setOf(
        "net.minecraftforge.items.ItemStackHandler",
        "net.neoforged.neoforge.items.ItemStackHandler"
    )
    private val POTION_UTILS_READS = setOf(
        "getPotion",
        "getCustomEffects",
        "getAllEffects",
        "getMobEffects",
        "getColor"
    )
    private val MUTATOR_METHODS = setOf(
        "put",
        "putByte",
        "putShort",
        "putInt",
        "putLong",
        "putUUID",
        "putFloat",
        "putDouble",
        "putString",
        "putByteArray",
        "putIntArray",
        "putLongArray",
        "putBoolean",
        "remove",
        "merge",
        "add",
        "addAll",
        "set",
        "clear",
        "replaceAll",
        "sort"
    )
    private val SAFE_READ_METHODS = setOf(
        "sizeInBytes",
        "getId",
        "getType",
        "size",
        "getUUID",
        "hasUUID",
        "getTagType",
        "contains",
        "getByte",
        "getShort",
        "getInt",
        "getLong",
        "getFloat",
        "getDouble",
        "getString",
        "getByteArray",
        "getIntArray",
        "getLongArray",
        "getBoolean",
        "toString",
        "isEmpty",
        "copy",
        "equals",
        "hashCode",
        "accept",
        "forEach"
    )
}
