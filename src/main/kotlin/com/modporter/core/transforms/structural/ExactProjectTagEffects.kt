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
        val method = exactTarget(call) ?: return null
        val parameter = method.parameters.getOrNull(argumentIndex) ?: return null
        if (index.declaredType(parameter.type, method) != COMPOUND_TAG) return null
        return when (parameterState(method, parameter)) {
            State.READ -> ExactExternalTagContracts.Effect.READ
            State.MUTATE -> ExactExternalTagContracts.Effect.MUTATE
            else -> null
        }
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
        val rooted = rootedCall(use)
        if (rooted != null) {
            ExactExternalTagContracts.compoundTagReceiverEffect(rooted.nameAsString)
                ?.let { return it }
            val parentCall = rooted.parentNode.orElse(null) as? MethodCallExpr ?: return null
            val argumentIndex = parentCall.arguments.indexOfIdentity(rooted)
            if (argumentIndex < 0) return null
            return ExactExternalTagContracts.compoundTagArgumentEffect(
                parentCall,
                argumentIndex,
                exact
            ) ?: compoundTagArgumentEffect(parentCall, argumentIndex)
        }

        val parentCall = use.parentNode.orElse(null) as? MethodCallExpr ?: return null
        val argumentIndex = parentCall.arguments.indexOfIdentity(use)
        if (argumentIndex < 0) return null
        return ExactExternalTagContracts.compoundTagArgumentEffect(
            parentCall,
            argumentIndex,
            exact
        ) ?: compoundTagArgumentEffect(parentCall, argumentIndex)
    }

    private fun rootedCall(root: NameExpr): MethodCallExpr? {
        var current = root.parentNode.orElse(null) as? MethodCallExpr ?: return null
        if (!current.scope.map { it === root }.orElse(false)) return null
        while (true) {
            val parent = current.parentNode.orElse(null) as? MethodCallExpr ?: return current
            if (!parent.scope.map { it === current }.orElse(false)) return current
            current = parent
        }
    }

    private fun <T> List<T>.indexOfIdentity(target: T): Int =
        indexOfFirst { it === target }

    private companion object {
        const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    }
}
