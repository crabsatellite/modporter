package com.modporter.core.transforms.structural

import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr

/** Exact target-platform callback contracts that already own a registry provider source. */
internal object ExactExternalProviderContracts {
    data class CallRewrite(val targetMethodName: String, val providerParameterIndex: Int)

    private data class Contract(
        val owner: String,
        val methodName: String,
        val returnType: String,
        val parameterTypes: List<String>,
        val providerParameterIndex: Int? = null,
        val instanceProviderExpression: String? = null
    )

    private data class CallContract(
        val owner: String,
        val legacyMethodName: String,
        val targetMethodName: String,
        val legacyParameterTypes: List<String>,
        val providerParameterIndex: Int
    )

    private val contracts = listOf(
        Contract(
            owner = "net.minecraft.world.item.Item",
            methodName = "use",
            returnType = "InteractionResultHolder<ItemStack>",
            parameterTypes = listOf(
                "net.minecraft.world.level.Level",
                "net.minecraft.world.entity.player.Player",
                "net.minecraft.world.InteractionHand"
            ),
            providerParameterIndex = 0
        ),
        Contract(
            owner = "net.minecraft.world.level.block.Block",
            methodName = "setPlacedBy",
            returnType = "void",
            parameterTypes = listOf(
                "net.minecraft.world.level.Level",
                "net.minecraft.core.BlockPos",
                "net.minecraft.world.level.block.state.BlockState",
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.item.ItemStack"
            ),
            providerParameterIndex = 0
        ),
        Contract(
            owner = "net.minecraft.world.entity.Entity",
            methodName = "addAdditionalSaveData",
            returnType = "void",
            parameterTypes = listOf("net.minecraft.nbt.CompoundTag"),
            instanceProviderExpression = "this.registryAccess()"
        ),
        Contract(
            owner = "net.minecraft.world.entity.Entity",
            methodName = "readAdditionalSaveData",
            returnType = "void",
            parameterTypes = listOf("net.minecraft.nbt.CompoundTag"),
            instanceProviderExpression = "this.registryAccess()"
        ),
        Contract(
            owner = "net.neoforged.neoforge.entity.IEntityWithComplexSpawn",
            methodName = "writeSpawnData",
            returnType = "void",
            parameterTypes = listOf("net.minecraft.network.RegistryFriendlyByteBuf"),
            providerParameterIndex = 0
        )
    )

    private val projectHelperNonOverrideContracts = listOf(
        CallContract(
            owner = "net.minecraft.world.entity.Entity",
            legacyMethodName = "writeAdditional",
            targetMethodName = "writeAdditional",
            legacyParameterTypes = listOf("net.minecraft.nbt.CompoundTag", "boolean"),
            providerParameterIndex = 1
        )
    )

    private val callContracts = listOf(
        CallContract(
            owner = "net.minecraft.world.level.block.entity.BlockEntity",
            legacyMethodName = "load",
            targetMethodName = "loadAdditional",
            legacyParameterTypes = listOf("net.minecraft.nbt.CompoundTag"),
            providerParameterIndex = 1
        ),
        CallContract(
            owner = "net.minecraft.world.level.block.entity.BlockEntity",
            legacyMethodName = "saveAdditional",
            targetMethodName = "saveAdditional",
            legacyParameterTypes = listOf("net.minecraft.nbt.CompoundTag"),
            providerParameterIndex = 1
        ),
        CallContract(
            owner = "net.minecraft.world.level.block.entity.BlockEntity",
            legacyMethodName = "getUpdateTag",
            targetMethodName = "getUpdateTag",
            legacyParameterTypes = emptyList(),
            providerParameterIndex = 0
        ),
        CallContract(
            owner = "net.createmod.catnip.nbt.NBTHelper",
            legacyMethodName = "readItemList",
            targetMethodName = "readItemList",
            legacyParameterTypes = listOf("net.minecraft.nbt.ListTag"),
            providerParameterIndex = 1
        ),
        CallContract(
            owner = "net.createmod.catnip.nbt.NBTHelper",
            legacyMethodName = "writeItemList",
            targetMethodName = "writeItemList",
            legacyParameterTypes = listOf("java.lang.Iterable"),
            providerParameterIndex = 1
        )
    )

    fun containsLegacyCall(source: String): Boolean = callContracts.any { contract ->
        source.contains("${contract.legacyMethodName}(")
    }

    fun providerExpression(method: MethodDeclaration, index: JavaProjectTypeIndex): String? {
        val returnType = index.declaredType(method.type, method) ?: return null
        val parameterTypes = method.parameters.map { parameter ->
            index.declaredType(parameter.type, method) ?: return null
        }
        val owner = exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null)
        return providerExpressionForExactSurface(
            methodName = method.nameAsString,
            returnType = returnType,
            parameterTypes = parameterTypes,
            parameterNames = method.parameters.map { it.nameAsString },
            isStatic = method.isStatic,
            ownerMatches = { expected -> owner != null && index.isTypeAssignableTo(owner, expected) }
        )
    }

    fun providerExpressionForExactSurface(
        methodName: String,
        returnType: String,
        parameterTypes: List<String>,
        parameterNames: List<String>,
        isStatic: Boolean,
        ownerMatches: (String) -> Boolean
    ): String? {
        if (parameterTypes.size != parameterNames.size) {
            throw IllegalStateException("Exact external provider surface has inconsistent parameters for $methodName")
        }
        val registryBuffers = parameterTypes.withIndex().filter { (_, type) ->
            sameType(type, "net.minecraft.network.RegistryFriendlyByteBuf")
        }
        if (registryBuffers.size > 1) {
            throw IllegalStateException(
                "Ambiguous RegistryFriendlyByteBuf provider sources in $methodName"
            )
        }
        registryBuffers.singleOrNull()?.let { buffer ->
            return "${parameterNames[buffer.index]}.registryAccess()"
        }
        if (isStatic && sameType(returnType, "void") && sameTypes(parameterTypes, PONDER_SCENE_CALLBACK_PARAMETERS)) {
            return "${parameterNames[0]}.world().getHolderLookupProvider()"
        }
        if (isStatic) return null
        val matches = contracts.filter { contract ->
            contract.methodName == methodName &&
                sameType(returnType, contract.returnType) &&
                sameTypes(parameterTypes, contract.parameterTypes) &&
                ownerMatches(contract.owner)
        }
        if (matches.size > 1) {
            throw IllegalStateException(
                "Ambiguous exact external provider contracts for $methodName$parameterTypes"
            )
        }
        val contract = matches.singleOrNull() ?: return null
        contract.instanceProviderExpression?.let { return it }
        val providerIndex = contract.providerParameterIndex ?: throw IllegalStateException(
            "Exact external provider contract for $methodName has no provider source"
        )
        return if (
            sameType(parameterTypes[providerIndex], "net.minecraft.world.level.Level") ||
            sameType(parameterTypes[providerIndex], "net.minecraft.network.RegistryFriendlyByteBuf")
        ) {
            "${parameterNames[providerIndex]}.registryAccess()"
        } else {
            throw IllegalStateException(
                "Unsupported provider source in exact external contract for $methodName"
            )
        }
    }

    private fun sameTypes(actual: List<String>, expected: List<String>): Boolean =
        actual.size == expected.size && actual.zip(expected).all { (left, right) -> sameType(left, right) }

    private fun sameType(actual: String, expected: String): Boolean {
        val normalizedActual = actual.replace(Regex("""\s+"""), "")
        val normalizedExpected = expected.replace(Regex("""\s+"""), "")
        return normalizedActual == normalizedExpected ||
            normalizedActual == normalizedExpected.substringAfterLast('.')
    }

    fun isProvenProjectHelperRoot(method: MethodDeclaration, index: JavaProjectTypeIndex): Boolean {
        if (method.isStatic || method.annotations.any { it.nameAsString == "Override" }) return false
        val owner = exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null) ?: return false
        val parameterTypes = method.parameters.map { parameter ->
            index.declaredType(parameter.type, method) ?: return false
        }
        val matches = projectHelperNonOverrideContracts.filter { contract ->
            contract.legacyMethodName == method.nameAsString &&
                contract.legacyParameterTypes == parameterTypes &&
                index.isTypeAssignableTo(owner, contract.owner)
        }
        if (matches.size > 1) {
            throw IllegalStateException("Ambiguous external non-override contracts for $owner.${method.nameAsString}")
        }
        return matches.size == 1
    }

    fun callRewrite(call: MethodCallExpr, index: JavaProjectTypeIndex): CallRewrite? {
        val receiverType = index.methodCallReceiverType(call) ?: return null
        if (index.argumentsMatchProjectMethod(call, receiverType, call.arguments.size)) return null
        val matches = callContracts.filter { contract ->
            contract.legacyMethodName == call.nameAsString &&
                contract.legacyParameterTypes.size == call.arguments.size &&
                index.isTypeAssignableTo(receiverType, contract.owner) &&
                index.argumentsMatchTypes(call, contract.legacyParameterTypes)
        }
        if (matches.size > 1) {
            throw IllegalStateException("Ambiguous exact external provider call contracts for '$call'")
        }
        return matches.singleOrNull()?.let { contract ->
            CallRewrite(contract.targetMethodName, contract.providerParameterIndex)
        }
    }

    private val PONDER_SCENE_CALLBACK_PARAMETERS = listOf(
        "net.createmod.ponder.api.scene.SceneBuilder",
        "net.createmod.ponder.api.scene.SceneBuildingUtil"
    )
}
