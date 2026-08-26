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
        exact: ExactJavaSemantics
    ): Effect? {
        if (argumentIndex == 0 &&
            call.nameAsString == "of" &&
            call.scope.map {
                exact.exactStaticScope(it, "CustomData", CUSTOM_DATA)
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

    private const val CATNIP_NBT_HELPER = "net.createmod.catnip.nbt.NBTHelper"
    private const val CUSTOM_DATA = "net.minecraft.world.item.component.CustomData"
}
