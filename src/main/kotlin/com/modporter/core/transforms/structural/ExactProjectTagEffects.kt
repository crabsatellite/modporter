package com.modporter.core.transforms.structural

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.io.path.extension
import kotlin.streams.toList

internal class ExactProjectTagEffects(
    sourceRoot: Path,
    private val index: JavaProjectTypeIndex
) {
    private data class MethodKey(val owner: String, val name: String, val arity: Int)

    private enum class State {
        VISITING,
        READ,
        MUTATE,
        UNKNOWN
    }

    private data class ReceiverChain(
        val outermost: MethodCallExpr,
        val effect: ExactExternalTagContracts.Effect
    )

    private val methodsByKey: Map<MethodKey, List<MethodDeclaration>>
    private val states = IdentityHashMap<Parameter, State>()

    init {
        val methods = linkedMapOf<MethodKey, MutableList<MethodDeclaration>>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }.toList().forEach { file ->
                val cu = index.unit(file)
                cu.findAll(MethodDeclaration::class.java).forEach methodLoop@{ method ->
                    val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java)
                        .flatMap { it.fullyQualifiedName }
                        .orElse(null) ?: return@methodLoop
                    methods.getOrPut(
                        MethodKey(owner, method.nameAsString, method.parameters.size),
                        ::mutableListOf
                    ) += method
                }
            }
        }
        methodsByKey = methods
    }

    fun compoundTagArgumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int
    ): ExactExternalTagContracts.Effect? {
        val (method, parameter) = exactTargetParameter(call, argumentIndex) ?: return null
        if (index.declaredType(parameter.type, method) != COMPOUND_TAG) return null
        return when (parameterState(method, parameter)) {
            State.READ -> ExactExternalTagContracts.Effect.READ
            State.MUTATE -> ExactExternalTagContracts.Effect.MUTATE
            else -> null
        }
    }

    private fun exactTargetParameter(
        call: MethodCallExpr,
        argumentIndex: Int
    ): Pair<MethodDeclaration, Parameter>? {
        exactTarget(call)?.let { method ->
            method.parameters.getOrNull(argumentIndex)?.let { parameter ->
                if (index.declaredType(parameter.type, method) == COMPOUND_TAG) {
                    return method to parameter
                }
            }
        }

        val owner = index.projectMethodOwner(call, call.arguments.size)
            ?: call.takeIf { it.scope.isEmpty }
                ?.findAncestor(ClassOrInterfaceDeclaration::class.java)
                ?.flatMap { it.fullyQualifiedName }
                ?.orElse(null)
            ?: return null
        val expanded = methodsByKey[
            MethodKey(owner, call.nameAsString, call.arguments.size + 1)
        ].orEmpty().mapNotNull { method ->
            val providerIndices = method.parameters.indices.filter { parameterIndex ->
                index.declaredType(method.parameters[parameterIndex].type, method) ==
                    HOLDER_LOOKUP_PROVIDER
            }
            val providerIndex = providerIndices.singleOrNull() ?: return@mapNotNull null
            val nonProviderParameters = method.parameters.filterIndexed { index, _ ->
                index != providerIndex
            }
            val expectedTypes = nonProviderParameters.map { parameter ->
                index.declaredType(parameter.type, method) ?: return@mapNotNull null
            }
            val callerCu = call.findCompilationUnit().orElse(null) ?: return@mapNotNull null
            val callerExact = ExactJavaSemantics(callerCu)
            val argumentsMatch = call.arguments.zip(expectedTypes).all { (argument, expected) ->
                val legacyTagElement = (argument as? MethodCallExpr)?.let { tagCall ->
                    expected == COMPOUND_TAG &&
                        tagCall.nameAsString == "getOrCreateTagElement" &&
                        tagCall.arguments.size == 1 &&
                        tagCall.scope.map { ownerExpression ->
                            callerExact.isProvablyType(
                                ownerExpression,
                                "ItemStack",
                                ITEM_STACK
                            ) || index.isExpressionAssignableTo(
                                ownerExpression,
                                tagCall,
                                ITEM_STACK
                            )
                        }.orElse(false)
                } == true
                legacyTagElement ||
                    index.isExpressionAssignableTo(argument, call, expected)
            }
            if (!argumentsMatch) return@mapNotNull null
            val parameter = nonProviderParameters.getOrNull(argumentIndex)
                ?: return@mapNotNull null
            method to parameter
        }
        return expanded.singleOrNull()
    }

    private fun exactTarget(call: MethodCallExpr): MethodDeclaration? {
        val owner = index.projectMethodOwner(call, call.arguments.size) ?: return null
        val candidates = methodsByKey[
            MethodKey(owner, call.nameAsString, call.arguments.size)
        ].orEmpty()
        if (candidates.size == 1) return candidates.single()
        return candidates.filter { method ->
            val parameterTypes = method.parameters.map { parameter ->
                index.declaredType(parameter.type, method) ?: return@filter false
            }
            index.argumentsMatchTypes(call, parameterTypes)
        }.singleOrNull()
    }

    private fun parameterState(
        method: MethodDeclaration,
        parameter: Parameter
    ): State {
        states[parameter]?.let { state ->
            return if (state == State.VISITING) State.UNKNOWN else state
        }
        if (method.body.isEmpty && (method.isAbstract || method.isNative)) {
            states[parameter] = State.UNKNOWN
            return State.UNKNOWN
        }

        states[parameter] = State.VISITING
        val cu = method.findCompilationUnit().orElse(null) ?: run {
            states[parameter] = State.UNKNOWN
            return State.UNKNOWN
        }
        val exact = ExactJavaSemantics(cu)
        var mutates = false
        for (use in exact.referencesTo(parameter)) {
            val effect = useEffect(use, exact)
            if (effect == null) {
                states[parameter] = State.UNKNOWN
                return State.UNKNOWN
            }
            if (effect == ExactExternalTagContracts.Effect.MUTATE) mutates = true
        }
        return (if (mutates) State.MUTATE else State.READ).also {
            states[parameter] = it
        }
    }

    private fun useEffect(
        use: NameExpr,
        exact: ExactJavaSemantics
    ): ExactExternalTagContracts.Effect? {
        receiverChain(use)?.let { return it.effect }
        val direct = directlyScopedCall(use)
        if (direct != null && direct.nameAsString in NESTED_TAG_GETTERS) {
            val parentCall = direct.parentNode.orElse(null) as? MethodCallExpr ?: return null
            val argumentIndex = parentCall.arguments.indexOfIdentity(direct)
            if (argumentIndex < 0) return null
            return ExactExternalTagContracts.compoundTagArgumentEffect(
                parentCall,
                argumentIndex,
                exact,
                index
            ) ?: compoundTagArgumentEffect(parentCall, argumentIndex)
        }

        val parentCall = use.parentNode.orElse(null) as? MethodCallExpr ?: return null
        val argumentIndex = parentCall.arguments.indexOfIdentity(use)
        if (argumentIndex < 0) return null
        return ExactExternalTagContracts.compoundTagArgumentEffect(
            parentCall,
            argumentIndex,
            exact,
            index
        ) ?: compoundTagArgumentEffect(parentCall, argumentIndex)
    }

    private fun directlyScopedCall(root: NameExpr): MethodCallExpr? {
        val call = root.parentNode.orElse(null) as? MethodCallExpr ?: return null
        return call.takeIf { it.scope.map { scope -> scope === root }.orElse(false) }
    }

    private fun outermostScopedCall(first: MethodCallExpr): MethodCallExpr {
        var current = first
        while (true) {
            val parent = current.parentNode.orElse(null) as? MethodCallExpr ?: return current
            if (!parent.scope.map { it === current }.orElse(false)) return current
            current = parent
        }
    }

    private fun receiverChain(root: NameExpr): ReceiverChain? {
        val first = directlyScopedCall(root) ?: return null
        ExactExternalTagContracts.compoundTagReceiverEffect(first.nameAsString)?.let { effect ->
            return ReceiverChain(outermostScopedCall(first), effect)
        }
        if (first.nameAsString !in NESTED_TAG_GETTERS) return null
        val outermost = outermostScopedCall(first)
        if (outermost === first) return null
        val effect = ExactExternalTagContracts.compoundTagReceiverEffect(outermost.nameAsString)
            ?: return null
        return ReceiverChain(outermost, effect)
    }

    private fun <T> List<T>.indexOfIdentity(target: T): Int =
        indexOfFirst { it === target }

    private companion object {
        const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
        const val HOLDER_LOOKUP_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
        const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
        val NESTED_TAG_GETTERS = setOf("get", "getCompound", "getList")
    }
}
