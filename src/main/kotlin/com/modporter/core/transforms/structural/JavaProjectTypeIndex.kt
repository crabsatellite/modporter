package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CharLiteralExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LongLiteralExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.type.Type
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

internal fun exactEnclosingNamedClass(node: Node): ClassOrInterfaceDeclaration? {
    var cursor: Node? = node
    while (cursor != null) {
        when (cursor) {
            is ClassOrInterfaceDeclaration -> return cursor
            is TypeDeclaration<*> -> return null
            is ObjectCreationExpr -> if (cursor.anonymousClassBody.isPresent) return null
        }
        cursor = cursor.parentNode.orElse(null)
    }
    return null
}

/** Lazily resolves project-source types from explicit Java structure, without heuristic fallback. */
internal class JavaProjectTypeIndex private constructor(private val sourceRoot: Path) {
    private data class TypeRef(
        val name: String,
        val arguments: List<TypeRef> = emptyList(),
        val arrayDepth: Int = 0
    )

    private data class TypeInfo(
        val qualifiedName: String,
        val unit: CompilationUnit,
        val declaration: ClassOrInterfaceDeclaration,
        val typeParameters: List<String>,
        val typeParameterBounds: Map<String, TypeRef>,
        val directSupers: List<TypeRef>
    )

    private val parser = JavaParser(
        ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    )
    private val unitsByFile = linkedMapOf<Path, CompilationUnit>()
    private val typesByQualifiedName = linkedMapOf<String, TypeInfo>()

    internal val loadedSourceCount: Int
        get() = unitsByFile.size

    fun unit(file: Path): CompilationUnit = parseFile(file)

    fun release(file: Path) {
        val normalized = file.toAbsolutePath().normalize()
        val unit = unitsByFile.remove(normalized) ?: return
        typesByQualifiedName.entries.removeIf { (_, info) -> info.unit === unit }
    }

    fun expressionType(expression: Expression, use: Node): String? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index expression owner '${owner.nameAsString}'")
        val resolved = resolveExpression(expression, use, ownerInfo, emptyMap()) ?: return null
        return resolved.name.takeIf { resolved.arrayDepth == 0 }
    }

    fun isExpressionAssignableTo(expression: Expression, use: Node, expectedType: String): Boolean {
        val owner = exactEnclosingNamedClass(use) ?: return false
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index expression owner '${owner.nameAsString}'")
        val actual = resolveExpression(expression, use, ownerInfo, emptyMap()) ?: return false
        return isAssignableTo(actual, expectedType, mutableSetOf())
    }

    fun declaredType(type: Type, use: Node): String? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index type owner '${owner.nameAsString}'")
        val resolved = resolveType(parseType(type.asString()), ownerInfo, emptyMap()) ?: return null
        return resolved.name.takeIf { resolved.arrayDepth == 0 }
    }

    fun declaredTypeWithArguments(type: Type, use: Node): Pair<String, List<String>>? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index type owner '${owner.nameAsString}'")
        val resolved = resolveType(parseType(type.asString()), ownerInfo, emptyMap()) ?: return null
        if (resolved.arrayDepth != 0 || resolved.arguments.any { it.arrayDepth != 0 }) return null
        return resolved.name to resolved.arguments.map { it.name }
    }

    fun projectMethodOwner(call: MethodCallExpr, declarationArity: Int = call.arguments.size): String? {
        val receiver = methodCallReceiverTypeRef(call) ?: return null
        return resolveProjectMethodOwner(receiver, call.nameAsString, declarationArity, mutableSetOf())
    }

    fun methodCallReceiverType(call: MethodCallExpr): String? = methodCallReceiverTypeRef(call)?.name

    fun isTypeAssignableTo(actualType: String, expectedType: String): Boolean =
        isAssignableTo(TypeRef(actualType), expectedType, mutableSetOf())

    fun exactDirectFieldWithType(
        root: NameExpr,
        use: Node,
        acceptedTypes: Set<String>
    ): Pair<String, String>? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner) ?: return null
        val rootType = resolveExpression(root, use, ownerInfo, emptyMap()) ?: return null
        val rootInfo = loadType(rootType.name) ?: return null
        val substitutions = substitutions(rootInfo, rootType)
        val matches = rootInfo.declaration.fields.filterNot { it.isStatic }.flatMap { declaration ->
            declaration.variables.mapNotNull { field ->
                val type = resolveType(parseType(field.typeAsString), rootInfo, substitutions)
                    ?: return@mapNotNull null
                (field.nameAsString to type.name).takeIf { (_, name) ->
                    acceptedTypes.any { accepted -> isAssignableTo(type, accepted, mutableSetOf()) } ||
                        name in acceptedTypes
                }
            }
        }.distinct()
        if (matches.size > 1) {
            throw IllegalStateException(
                "Ambiguous direct registry-provider fields on ${rootType.name}: $matches"
            )
        }
        return matches.singleOrNull()
    }

    fun exactInstanceFieldWithType(use: Node, acceptedTypes: Set<String>): Pair<String, String>? {
        val owner = exactEnclosingNamedClass(use) ?: return null
        val ownerInfo = typeInfo(owner) ?: return null
        val matches = collectFieldsWithTypes(TypeRef(ownerInfo.qualifiedName), acceptedTypes, mutableSetOf()).distinct()
        collapseEquivalentPlayerInventoryFields(ownerInfo, matches)?.let { return it }
        if (matches.size > 1) {
            throw IllegalStateException(
                "Ambiguous inherited registry-provider fields on ${ownerInfo.qualifiedName}: $matches"
            )
        }
        return matches.singleOrNull()
    }

    fun exactVisibleLocalInitializer(
        name: String,
        use: Node,
        callable: CallableDeclaration<*>
    ): Expression? {
        val candidates = callable.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
            if (variable.nameAsString != name || !variable.initializer.isPresent || !visibleBefore(variable, use)) {
                return@mapNotNull null
            }
            lexicalScopeDistance(variable, use)?.let { distance -> variable to distance }
        }
        val innermostScopeDistance = candidates.minOfOrNull { it.second } ?: return null
        val visible = candidates.filter { it.second == innermostScopeDistance }
        if (visible.size > 1) {
            throw IllegalStateException("Ambiguous visible local initializer '$name' at $use")
        }
        return visible.singleOrNull()?.first?.initializer?.orElse(null)
    }

    private fun collapseEquivalentPlayerInventoryFields(
        owner: TypeInfo,
        matches: List<Pair<String, String>>
    ): Pair<String, String>? {
        if (matches.size != 2) return null
        val player = matches.singleOrNull { it.second in setOf(
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.server.level.ServerPlayer"
        ) } ?: return null
        val inventory = matches.singleOrNull {
            it.second == "net.minecraft.world.entity.player.Inventory"
        } ?: return null
        val declarations = collectProjectDeclarations(TypeRef(owner.qualifiedName), mutableSetOf())
        val playerAssignments = declarations.flatMap { declaration ->
            declaration.findAll(AssignExpr::class.java).mapNotNull { assignment ->
                if (!isExactInstanceFieldTarget(assignment.target, assignment, player.first, owner)) {
                    return@mapNotNull null
                }
                val value = assignment.value as? FieldAccessExpr ?: return@mapNotNull null
                if (value.nameAsString != "player") return@mapNotNull null
                val root = value.scope as? NameExpr ?: return@mapNotNull null
                val rootType = resolveExpression(root, assignment, owner, emptyMap()) ?: return@mapNotNull null
                if (!isAssignableTo(rootType, "net.minecraft.world.entity.player.Inventory", mutableSetOf())) {
                    return@mapNotNull null
                }
                assignment.findAncestor(CallableDeclaration::class.java).orElse(null)?.let {
                    Triple(it, root.nameAsString, assignment)
                }
            }
        }
        val inventoryAssignments = declarations.flatMap { declaration ->
            declaration.findAll(AssignExpr::class.java).mapNotNull { assignment ->
                if (!isExactInstanceFieldTarget(assignment.target, assignment, inventory.first, owner)) {
                    return@mapNotNull null
                }
                val root = assignment.value as? NameExpr ?: return@mapNotNull null
                val rootType = resolveExpression(root, assignment, owner, emptyMap()) ?: return@mapNotNull null
                if (!isAssignableTo(rootType, "net.minecraft.world.entity.player.Inventory", mutableSetOf())) {
                    return@mapNotNull null
                }
                assignment.findAncestor(CallableDeclaration::class.java).orElse(null)?.let {
                    Triple(it, root.nameAsString, assignment)
                }
            }
        }
        if (playerAssignments.size != 1 || inventoryAssignments.size != 1) return null
        val playerAssignment = playerAssignments.single()
        val inventoryAssignment = inventoryAssignments.single()
        return player.takeIf {
            playerAssignment.first === inventoryAssignment.first &&
                playerAssignment.second == inventoryAssignment.second
        }
    }

    private fun isExactInstanceFieldTarget(
        expression: Expression,
        use: Node,
        expectedName: String,
        owner: TypeInfo
    ): Boolean {
        return when (expression) {
            is FieldAccessExpr -> expression.nameAsString == expectedName && expression.scope is ThisExpr
            is NameExpr -> {
                if (expression.nameAsString != expectedName) return false
                val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
                if (callable.parameters.any { it.nameAsString == expectedName }) return false
                val shadowingLocal = callable.findAll(VariableDeclarator::class.java).any { variable ->
                    variable.nameAsString == expectedName && visibleBefore(variable, use) &&
                        lexicalScopeDistance(variable, use) != null
                }
                if (shadowingLocal) return false
                val shadowingLambda = use.findAncestor(LambdaExpr::class.java).orElse(null)?.parameters
                    ?.any { it.nameAsString == expectedName } == true
                !shadowingLambda && resolveField(TypeRef(owner.qualifiedName), expectedName, mutableSetOf()) != null
            }
            else -> false
        }
    }

    private fun collectProjectDeclarations(
        receiver: TypeRef,
        visited: MutableSet<String>
    ): List<ClassOrInterfaceDeclaration> {
        if (!visited.add(receiver.toString())) return emptyList()
        val info = loadType(receiver.name) ?: return emptyList()
        val substitutions = substitutions(info, receiver)
        return listOf(info.declaration) + resolvedSupers(info, substitutions).flatMap { superType ->
            collectProjectDeclarations(superType, visited)
        }
    }

    private fun collectFieldsWithTypes(
        receiver: TypeRef,
        acceptedTypes: Set<String>,
        visited: MutableSet<String>
    ): List<Pair<String, String>> {
        if (!visited.add(receiver.toString())) return emptyList()
        val info = loadType(receiver.name) ?: return emptyList()
        val substitutions = substitutions(info, receiver)
        val direct = info.declaration.fields.filterNot { it.isStatic }.flatMap { declaration ->
            declaration.variables.mapNotNull { field ->
                val type = resolveType(parseType(field.typeAsString), info, substitutions)
                    ?: return@mapNotNull null
                (field.nameAsString to type.name).takeIf { (_, name) ->
                    acceptedTypes.any { accepted -> isAssignableTo(type, accepted, mutableSetOf()) } ||
                        name in acceptedTypes
                }
            }
        }
        return direct + resolvedSupers(info, substitutions).flatMap { superType ->
            collectFieldsWithTypes(superType, acceptedTypes, visited)
        }
    }

    private fun methodCallReceiverTypeRef(call: MethodCallExpr): TypeRef? {
        val owner = exactEnclosingNamedClass(call) ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index call owner '${owner.nameAsString}'")
        val scope = call.scope.orElse(null)
        val receiver = if (scope == null) {
            TypeRef(ownerInfo.qualifiedName)
        } else {
            resolveExpression(scope, call, ownerInfo, emptyMap()) ?: if (scope is NameExpr) {
                if (hasVisibleLexicalValueBinding(scope.nameAsString, scope)) null
                else resolveType(TypeRef(scope.nameAsString), ownerInfo, emptyMap())
            } else {
                null
            } ?: return null
        }
        return receiver
    }

    fun argumentsMatchProjectMethodExcluding(
        call: MethodCallExpr,
        declarationOwner: String,
        declarationArity: Int,
        excludedParameterIndex: Int
    ): Boolean = argumentsMatchProjectMethod(
        call,
        declarationOwner,
        declarationArity,
        excludedParameterIndex
    )

    fun argumentsMatchProjectMethod(
        call: MethodCallExpr,
        declarationOwner: String,
        declarationArity: Int
    ): Boolean = argumentsMatchProjectMethod(call, declarationOwner, declarationArity, null)

    fun argumentsMatchTypes(call: MethodCallExpr, expectedTypes: List<String>): Boolean {
        if (call.arguments.size != expectedTypes.size) return false
        return expressionsMatchTypes(call.arguments.toList(), call, expectedTypes)
    }

    fun argumentsMatchTypesExcluding(
        call: MethodCallExpr,
        expectedTypes: List<String>,
        excludedArgumentIndex: Int
    ): Boolean {
        if (excludedArgumentIndex !in call.arguments.indices) return false
        return expressionsMatchTypes(
            call.arguments.filterIndexed { index, _ -> index != excludedArgumentIndex },
            call,
            expectedTypes
        )
    }

    private fun expressionsMatchTypes(
        expressions: List<Expression>,
        use: Node,
        expectedTypes: List<String>
    ): Boolean {
        if (expressions.size != expectedTypes.size) return false
        val caller = exactEnclosingNamedClass(use) ?: return false
        val callerInfo = typeInfo(caller) ?: return false
        return expressions.zip(expectedTypes).all { (argument, expected) ->
            val actual = resolveExpression(argument, use, callerInfo, emptyMap()) ?: return@all false
            isAssignableTo(actual, expected, mutableSetOf())
        }
    }

    private fun argumentsMatchProjectMethod(
        call: MethodCallExpr,
        declarationOwner: String,
        declarationArity: Int,
        excludedParameterIndex: Int?
    ): Boolean {
        val caller = exactEnclosingNamedClass(call)
            ?: throw IllegalStateException("Call '$call' is outside an exact named type")
        val callerInfo = typeInfo(caller)
            ?: throw IllegalStateException("Cannot index call owner '${caller.nameAsString}'")
        val actualTypes = call.arguments.map { argument ->
            resolveExpression(argument, call, callerInfo, emptyMap()) ?: return false
        }
        return hasMatchingProjectMethod(
            TypeRef(declarationOwner),
            call.nameAsString,
            declarationArity,
            excludedParameterIndex,
            actualTypes,
            mutableSetOf()
        )
    }

    private fun hasMatchingProjectMethod(
        receiver: TypeRef,
        name: String,
        declarationArity: Int,
        excludedParameterIndex: Int?,
        actualTypes: List<TypeRef>,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(receiver.toString())) return false
        val declarationInfo = loadType(receiver.name) ?: return false
        val substitutions = substitutions(declarationInfo, receiver)
        val methods = declarationInfo.declaration.methods.filter {
            it.nameAsString == name && it.parameters.size == declarationArity
        }
        val matching = methods.filter { method ->
            if (excludedParameterIndex != null && excludedParameterIndex !in method.parameters.indices) {
                return@filter false
            }
            val expectedTypes = method.parameters.mapIndexedNotNull { index, parameter ->
                if (index == excludedParameterIndex) null
                else resolveType(parseType(parameter.typeAsString), declarationInfo, substitutions)
                    ?: return@filter false
            }
            expectedTypes.size == actualTypes.size && actualTypes.zip(expectedTypes).all { (actual, expected) ->
                isAssignableTo(actual, expected.name, mutableSetOf())
            }
        }
        if (matching.size > 1) {
            throw IllegalStateException(
                "Ambiguous typed method '$name/$declarationArity' on ${receiver.name}"
            )
        }
        if (matching.size == 1) return true
        return resolvedSupers(declarationInfo, substitutions).any { superType ->
            hasMatchingProjectMethod(
                superType,
                name,
                declarationArity,
                excludedParameterIndex,
                actualTypes,
                visited
            )
        }
    }

    fun hasClosedProjectMethodHierarchy(qualifiedName: String): Boolean =
        hasClosedProjectMethodHierarchy(qualifiedName, mutableSetOf())

    fun isProvenNonOverride(method: MethodDeclaration): Boolean {
        if (method.isPrivate) return true
        if (method.annotations.any { it.nameAsString == "Override" }) return false
        val ownerDeclaration = exactEnclosingNamedClass(method) ?: return false
        val owner = typeInfo(ownerDeclaration) ?: return false
        if (!hasClosedProjectMethodHierarchy(owner.qualifiedName)) return false
        val parameterTypes = method.parameters.map { parameter ->
            resolveType(parseType(parameter.typeAsString), owner, emptyMap()) ?: return false
        }
        return resolvedSupers(owner, emptyMap()).none { superType ->
            hierarchyDeclaresMatchingMethod(
                superType,
                method.nameAsString,
                parameterTypes,
                mutableSetOf()
            )
        }
    }

    private fun hierarchyDeclaresMatchingMethod(
        receiver: TypeRef,
        name: String,
        parameterTypes: List<TypeRef>,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(receiver.toString())) return false
        val info = loadType(receiver.name) ?: return true
        val substitutions = substitutions(info, receiver)
        val sameArity = info.declaration.methods.filter {
            !it.isPrivate && it.nameAsString == name && it.parameters.size == parameterTypes.size
        }
        if (sameArity.any { candidate ->
                val candidateTypes = candidate.parameters.map { parameter ->
                    resolveType(parseType(parameter.typeAsString), info, substitutions) ?: return true
                }
                candidateTypes.zip(parameterTypes).all { (candidateType, targetType) ->
                    candidateType.name == targetType.name && candidateType.arrayDepth == targetType.arrayDepth
                }
            }
        ) return true
        return resolvedSupers(info, substitutions).any { superType ->
            hierarchyDeclaresMatchingMethod(superType, name, parameterTypes, visited)
        }
    }

    private fun hasClosedProjectMethodHierarchy(
        qualifiedName: String,
        visited: MutableSet<String>
    ): Boolean {
        if (!visited.add(qualifiedName)) return true
        val info = loadType(qualifiedName) ?: return qualifiedName.startsWith("java.lang.")
        return info.directSupers.all { raw ->
            val resolved = resolveType(raw, info, emptyMap()) ?: return@all false
            resolved.name.startsWith("java.lang.") ||
                (loadType(resolved.name) != null && hasClosedProjectMethodHierarchy(resolved.name, visited))
        }
    }

    private fun resolveExpression(
        expression: Expression,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        return when (expression) {
            is EnclosedExpr -> resolveExpression(expression.inner, use, owner, substitutions)
            is ArrayAccessExpr -> {
                val array = resolveExpression(expression.name, use, owner, substitutions) ?: return null
                if (array.arrayDepth == 0) {
                    throw IllegalStateException("Array access '$expression' has a non-array declared receiver")
                }
                array.copy(arrayDepth = array.arrayDepth - 1)
            }
            is CastExpr -> resolveType(parseType(expression.typeAsString), owner, substitutions)
            is BooleanLiteralExpr -> TypeRef("boolean")
            is CharLiteralExpr -> TypeRef("char")
            is DoubleLiteralExpr -> TypeRef(if (expression.value.lowercase().endsWith("f")) "float" else "double")
            is IntegerLiteralExpr -> TypeRef("int")
            is LongLiteralExpr -> TypeRef("long")
            is StringLiteralExpr -> TypeRef("java.lang.String")
            is ObjectCreationExpr -> resolveType(parseType(expression.typeAsString), owner, substitutions)
            is ThisExpr -> TypeRef(owner.qualifiedName)
            is TypeExpr -> resolveType(parseType(expression.typeAsString), owner, substitutions)
            is NameExpr -> resolveName(expression.nameAsString, use, owner, substitutions)
            is FieldAccessExpr -> {
                val scope = resolveExpression(expression.scope, use, owner, substitutions) ?: return null
                resolveField(scope, expression.nameAsString, mutableSetOf())
            }
            is MethodCallExpr -> resolveMethodCall(expression, use, owner, substitutions)
            else -> null
        }
    }

    private fun resolveMethodCall(
        call: MethodCallExpr,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        val scopeExpression = call.scope.orElse(null)
        if (scopeExpression == null) {
            return resolveProjectMethodCall(
                TypeRef(owner.qualifiedName, owner.typeParameters.mapNotNull(substitutions::get)),
                call,
                owner,
                substitutions,
                mutableSetOf()
            )
        }
        val scope = resolveExpression(scopeExpression, use, owner, substitutions) ?: return null
        if (scope.name == "java.util.List" && call.nameAsString == "get" && call.arguments.size == 1) {
            return scope.arguments.singleOrNull()
                ?: throw IllegalStateException("Raw List receiver has no exact element type for '$call'")
        }
        if (scope.name == "java.util.Map" && call.nameAsString == "values" && call.arguments.isEmpty()) {
            val valueType = scope.arguments.getOrNull(1)
                ?: throw IllegalStateException("Raw Map receiver has no exact value type for '$call'")
            return TypeRef("java.util.Collection", listOf(valueType))
        }
        if (scope.name == "java.util.Optional" && call.nameAsString == "get" && call.arguments.isEmpty()) {
            return scope.arguments.singleOrNull()
                ?: throw IllegalStateException("Raw Optional receiver has no exact value type for '$call'")
        }
        knownMethods[scope.name]?.get(call.nameAsString to call.arguments.size)?.let { return it }
        return resolveProjectMethodCall(scope, call, owner, substitutions, mutableSetOf())
    }

    private fun resolveProjectMethodCall(
        receiver: TypeRef,
        call: MethodCallExpr,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: MutableSet<String>
    ): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val methods = info.declaration.methods.filter {
            it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
        }
        val actualTypes = call.arguments.map { argument ->
            resolveExpression(argument, call, callerOwner, callerSubstitutions) ?: return null
        }
        val matching = methods.filter { method ->
            val expected = method.parameters.map { parameter ->
                resolveType(parseType(parameter.typeAsString), info, substitutions) ?: return@filter false
            }
            actualTypes.zip(expected).all { (actual, target) ->
                isAssignableTo(actual, target.name, mutableSetOf())
            }
        }
        if (matching.size > 1) {
            throw IllegalStateException("Ambiguous typed method '${call.nameAsString}/${call.arguments.size}' on ${receiver.name}")
        }
        matching.singleOrNull()?.let { method ->
            return resolveType(parseType(method.typeAsString), info, substitutions)
        }
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull {
                resolveProjectMethodCall(it, call, callerOwner, callerSubstitutions, visited)
            },
            "method '${call.nameAsString}/${call.arguments.size}' inherited by ${receiver.name}"
        )
    }

    private fun resolveProjectMethodOwner(
        receiver: TypeRef,
        name: String,
        arity: Int,
        visited: MutableSet<String>
    ): String? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val methods = info.declaration.methods.filter { it.nameAsString == name && it.parameters.size == arity }
        if (methods.size > 1) throw IllegalStateException("Ambiguous method '$name/$arity' on ${receiver.name}")
        if (methods.size == 1) return info.qualifiedName
        val substitutions = substitutions(info, receiver)
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull {
                resolveProjectMethodOwner(it, name, arity, visited)
            },
            "method owner '$name/$arity' inherited by ${receiver.name}"
        )
    }

    private fun isAssignableTo(actual: TypeRef, expectedType: String, visited: MutableSet<String>): Boolean {
        if (actual.arrayDepth != 0) return false
        if (actual.name == expectedType) return true
        if (!visited.add(actual.toString())) return false
        val info = loadType(actual.name) ?: return false
        val substitutions = substitutions(info, actual)
        return resolvedSupers(info, substitutions).any { isAssignableTo(it, expectedType, visited) }
    }

    private fun resolveProjectMethod(
        receiver: TypeRef,
        name: String,
        arity: Int,
        visited: MutableSet<String>
    ): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val methods = info.declaration.methods.filter { it.nameAsString == name && it.parameters.size == arity }
        if (methods.size > 1) {
            throw IllegalStateException("Ambiguous method '$name/$arity' on ${receiver.name}")
        }
        methods.singleOrNull()?.let { method ->
            return resolveType(parseType(method.typeAsString), info, substitutions)
        }
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull { resolveProjectMethod(it, name, arity, visited) },
            "method '$name/$arity' inherited by ${receiver.name}"
        )
    }

    private fun resolveName(
        name: String,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        enclosingLambdaBinding(name, use)?.let { (lambda, parameterIndex) ->
            return resolveLambdaParameter(lambda, parameterIndex, owner, substitutions)
        }
        val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null)
        if (callable != null) {
            val parameters = callable.parameters.filter { it.nameAsString == name }
            if (parameters.size > 1) {
                throw IllegalStateException("Ambiguous callable parameter '$name' at $use")
            }
            parameters.singleOrNull()?.let { parameter ->
                return resolveType(parseType(parameter.typeAsString), owner, substitutions)
            }
            val localCandidates = callable.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
                if (variable.nameAsString != name || !visibleBefore(variable, use)) return@mapNotNull null
                lexicalScopeDistance(variable, use)?.let { distance -> variable to distance }
            }
            val innermostScopeDistance = localCandidates.minOfOrNull { it.second }
            val locals = localCandidates.filter { it.second == innermostScopeDistance }
            if (locals.size > 1) {
                throw IllegalStateException("Ambiguous visible local '$name' at $use")
            }
            locals.singleOrNull()?.let { (variable, _) ->
                return resolveType(parseType(variable.typeAsString), owner, substitutions)
            }
        }
        return resolveField(TypeRef(owner.qualifiedName), name, mutableSetOf())
    }

    private fun hasVisibleLexicalValueBinding(name: String, use: Node): Boolean {
        if (enclosingLambdaBinding(name, use) != null) return true
        val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
        if (callable.parameters.any { it.nameAsString == name }) return true
        return callable.findAll(VariableDeclarator::class.java).any { variable ->
            variable.nameAsString == name && visibleBefore(variable, use) &&
                lexicalScopeDistance(variable, use) != null
        }
    }

    private fun enclosingLambdaBinding(name: String, use: Node): Pair<LambdaExpr, Int>? {
        var cursor: Node? = use.parentNode.orElse(null)
        while (cursor != null && cursor !is CallableDeclaration<*>) {
            if (cursor is LambdaExpr) {
                val parameterIndex = cursor.parameters.indexOfFirst { it.nameAsString == name }
                if (parameterIndex >= 0) return cursor to parameterIndex
            }
            cursor = cursor.parentNode.orElse(null)
        }
        return null
    }

    private fun resolveLambdaParameter(
        lambda: LambdaExpr,
        parameterIndex: Int,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        val parentCall = lambda.findAncestor(MethodCallExpr::class.java).orElse(null) ?: return null
        val argumentIndex = parentCall.arguments.indexOfFirst { it === lambda || it.isAncestorOf(lambda) }
        if (argumentIndex < 0) return null
        val scopeExpression = parentCall.scope.orElse(null)
        val scope = if (scopeExpression == null) {
            TypeRef(owner.qualifiedName, owner.typeParameters.mapNotNull(substitutions::get))
        } else {
            resolveExpression(scopeExpression, parentCall, owner, substitutions) ?: return null
        }
        val external = when {
            parentCall.nameAsString == "forEach" && argumentIndex == 0 &&
                scope.name in setOf("java.lang.Iterable", "java.util.Collection", "java.util.List") ->
                scope.arguments.singleOrNull()
                    ?: throw IllegalStateException("Raw ${scope.name} has no exact lambda element type for '$parentCall'")
            parentCall.nameAsString == "forEach" && argumentIndex == 0 && scope.name == "java.util.Map" ->
                scope.arguments.getOrNull(parameterIndex)
                    ?: throw IllegalStateException("Raw Map has no exact lambda parameter type for '$parentCall'")
            parentCall.nameAsString == "ifPresent" && argumentIndex == 0 && scope.name == "java.util.Optional" &&
                parameterIndex == 0 -> scope.arguments.singleOrNull()
            else -> null
        }
        return external ?: resolveProjectFunctionalParameter(
            scope,
            parentCall,
            argumentIndex,
            parameterIndex,
            owner,
            substitutions,
            mutableSetOf()
        )
    }

    private fun resolveProjectFunctionalParameter(
        receiver: TypeRef,
        call: MethodCallExpr,
        argumentIndex: Int,
        lambdaParameterIndex: Int,
        callerOwner: TypeInfo,
        callerSubstitutions: Map<String, TypeRef>,
        visited: MutableSet<String>
    ): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val methods = info.declaration.methods.filter {
            it.nameAsString == call.nameAsString && it.parameters.size == call.arguments.size
        }
        val matching = methods.filter candidates@{ method ->
            call.arguments.withIndex().filter { it.index != argumentIndex }.all { (index, argument) ->
                val actual = resolveExpression(argument, call, callerOwner, callerSubstitutions)
                    ?: return@candidates false
                val expected = resolveType(parseType(method.parameters[index].typeAsString), info, substitutions)
                    ?: return@candidates false
                isAssignableTo(actual, expected.name, mutableSetOf())
            }
        }
        if (matching.size > 1) {
            throw IllegalStateException(
                "Ambiguous typed functional method '${call.nameAsString}/${call.arguments.size}' on ${receiver.name}"
            )
        }
        matching.singleOrNull()?.let { method ->
            val functionalType = resolveType(
                parseType(method.parameters[argumentIndex].typeAsString),
                info,
                substitutions
            ) ?: return null
            return when (functionalType.name) {
                "java.util.function.Consumer", "java.util.function.Predicate", "java.util.function.Function" ->
                    functionalType.arguments.firstOrNull().takeIf { lambdaParameterIndex == 0 }
                "java.util.function.BiConsumer", "java.util.function.BiPredicate" ->
                    functionalType.arguments.getOrNull(lambdaParameterIndex)
                else -> null
            }
        }
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull {
                resolveProjectFunctionalParameter(
                    it,
                    call,
                    argumentIndex,
                    lambdaParameterIndex,
                    callerOwner,
                    callerSubstitutions,
                    visited
                )
            },
            "functional parameter '${call.nameAsString}/${call.arguments.size}' inherited by ${receiver.name}"
        )
    }

    private fun resolveField(receiver: TypeRef, name: String, visited: MutableSet<String>): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        knownFields[receiver.name]?.get(name)?.let { field ->
            val generic = receiver.arguments.singleOrNull()
            return substitute(field, if (generic == null) emptyMap() else mapOf("T" to generic))
        }
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val fields = info.declaration.fields.flatMap { it.variables }.filter { it.nameAsString == name }
        if (fields.size > 1) throw IllegalStateException("Ambiguous field '$name' on ${receiver.name}")
        fields.singleOrNull()?.let { field ->
            return resolveType(parseType(field.typeAsString), info, substitutions)
        }
        return exactSingle(
            resolvedSupers(info, substitutions).mapNotNull { resolveField(it, name, visited) },
            "field '$name' inherited by ${receiver.name}"
        )
    }

    private fun resolvedSupers(info: TypeInfo, substitutions: Map<String, TypeRef>): List<TypeRef> =
        info.directSupers.mapNotNull { raw -> resolveType(raw, info, substitutions) }

    private fun resolveType(
        raw: TypeRef,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? = resolveType(raw, owner, substitutions, mutableSetOf())

    private fun resolveType(
        raw: TypeRef,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>,
        resolvingBounds: MutableSet<String>
    ): TypeRef? {
        substitutions[raw.name]?.let { return it.copy(arrayDepth = it.arrayDepth + raw.arrayDepth) }
        owner.typeParameterBounds[raw.name]?.let { bound ->
            if (!resolvingBounds.add(raw.name)) return null
            val resolved = resolveType(bound, owner, substitutions, resolvingBounds)
            resolvingBounds.remove(raw.name)
            return resolved?.let {
                it.copy(arrayDepth = it.arrayDepth + raw.arrayDepth)
            }
        }
        val qualified = qualify(raw.name, owner.unit, owner.qualifiedName.substringBeforeLast('.', "")) ?: return null
        val arguments = mutableListOf<TypeRef>()
        raw.arguments.forEach { argument ->
            arguments += resolveType(argument, owner, substitutions, resolvingBounds) ?: return null
        }
        return TypeRef(qualified, arguments, raw.arrayDepth)
    }

    private fun qualify(name: String, unit: CompilationUnit, packageName: String): String? {
        if (name in primitiveTypes) return name
        if (name.contains('.')) {
            val first = name.substringBefore('.')
            unit.imports.singleOrNull {
                !it.isStatic && !it.isAsterisk && it.name.identifier == first
            }?.nameAsString?.let { imported ->
                return imported + name.removePrefix(first)
            }
            val samePackage = if (packageName.isEmpty()) name else "$packageName.$name"
            if (loadType(samePackage) != null) return samePackage
            if (name.substringBefore('.').firstOrNull()?.isLowerCase() == true) return name
            return null
        }
        unit.findAll(ClassOrInterfaceDeclaration::class.java)
            .filter { it.nameAsString == name }
            .mapNotNull { it.fullyQualifiedName.orElse(null) }
            .distinct()
            .let { declarations ->
                if (declarations.size > 1) {
                    throw IllegalStateException("Ambiguous compilation-unit type '$name': $declarations")
                }
                declarations.singleOrNull()?.let { return it }
            }
        unit.imports.singleOrNull { !it.isStatic && !it.isAsterisk && it.name.identifier == name }
            ?.nameAsString?.let { return it }
        val samePackage = if (packageName.isEmpty()) name else "$packageName.$name"
        if (loadType(samePackage) != null) return samePackage
        implicitJavaLangTypes[name]?.let { return it }
        val wildcardMatches = unit.imports.filter { !it.isStatic && it.isAsterisk }
            .mapNotNull { import ->
                val qualified = "${import.nameAsString}.$name"
                qualified.takeIf { knownExternalTypes[name] == qualified || loadType(qualified) != null }
            }
            .distinct()
        if (wildcardMatches.size > 1) {
            throw IllegalStateException("Ambiguous wildcard project type '$name': $wildcardMatches")
        }
        return wildcardMatches.singleOrNull()
    }

    private fun substitutions(info: TypeInfo, receiver: TypeRef): Map<String, TypeRef> {
        if (receiver.arguments.isNotEmpty() && receiver.arguments.size != info.typeParameters.size) {
            throw IllegalStateException("Generic arity mismatch for ${receiver.name}: ${receiver.arguments}")
        }
        return info.typeParameters.mapIndexedNotNull { index, name ->
            receiver.arguments.getOrNull(index)?.let { name to it }
        }.toMap()
    }

    private fun substitute(type: TypeRef, substitutions: Map<String, TypeRef>): TypeRef =
        substitutions[type.name]?.let { it.copy(arrayDepth = it.arrayDepth + type.arrayDepth) }
            ?: TypeRef(type.name, type.arguments.map { substitute(it, substitutions) }, type.arrayDepth)

    private fun visibleBefore(variable: VariableDeclarator, use: Node): Boolean {
        val declaration = variable.range.orElse(null)?.begin ?: return false
        val usePosition = use.range.orElse(null)?.begin ?: return false
        if (!declaration.isBefore(usePosition)) return false
        val callable = variable.findAncestor(CallableDeclaration::class.java).orElse(null) ?: return false
        return callable === use.findAncestor(CallableDeclaration::class.java).orElse(null)
    }

    private fun lexicalScopeDistance(variable: VariableDeclarator, use: Node): Int? {
        val scopeKinds = setOf(
            "BlockStmt", "CatchClause", "ForEachStmt", "ForStmt", "LambdaExpr", "SwitchEntry", "TryStmt"
        )
        var scope: Node? = variable.parentNode.orElse(null)
        while (scope != null && scope !is CallableDeclaration<*> && scope.javaClass.simpleName !in scopeKinds) {
            scope = scope.parentNode.orElse(null)
        }
        scope ?: return null
        var distance = 0
        var cursor: Node? = use
        while (cursor != null) {
            if (cursor === scope) return distance
            if (cursor is CallableDeclaration<*> && scope !is CallableDeclaration<*>) return null
            cursor = cursor.parentNode.orElse(null)
            distance++
        }
        return null
    }

    private fun typeInfo(declaration: ClassOrInterfaceDeclaration): TypeInfo? {
        val qualified = declaration.fullyQualifiedName.orElse(null) ?: return null
        return typesByQualifiedName[qualified] ?: run {
            registerUnit(declaration.findCompilationUnit().orElse(null) ?: return null)
            typesByQualifiedName[qualified]
        }
    }

    private fun loadType(qualifiedName: String): TypeInfo? {
        typesByQualifiedName[qualifiedName]?.let { return it }
        val source = sourceFileFor(qualifiedName) ?: return null
        parseFile(source)
        return typesByQualifiedName[qualifiedName]
    }

    private fun sourceFileFor(qualifiedName: String): Path? {
        val parts = qualifiedName.split('.')
        if (parts.any { !JAVA_IDENTIFIER.matches(it) }) return null
        for (end in parts.size downTo 1) {
            val candidate = sourceRoot.resolve(parts.take(end).joinToString("/") + ".java").normalize()
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    private fun parseFile(file: Path): CompilationUnit {
        val normalized = file.toAbsolutePath().normalize()
        unitsByFile[normalized]?.let { return it }
        val result = parser.parse(normalized.readText())
        val unit = result.result.orElseThrow {
            IllegalStateException("Cannot parse Java type index source $normalized: ${result.problems.joinToString()}")
        }
        unitsByFile[normalized] = unit
        registerUnit(unit)
        return unit
    }

    private fun registerUnit(unit: CompilationUnit) {
        unit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { declaration ->
            val qualified = declaration.fullyQualifiedName.orElse(null) ?: return@forEach
            if (typesByQualifiedName.containsKey(qualified)) return@forEach
            val bounds = declaration.typeParameters.mapNotNull { parameter ->
                parameter.typeBound.firstOrNull()?.let { parameter.nameAsString to parseType(it.asString()) }
            }.toMap()
            typesByQualifiedName[qualified] = TypeInfo(
                qualifiedName = qualified,
                unit = unit,
                declaration = declaration,
                typeParameters = declaration.typeParameters.map { it.nameAsString },
                typeParameterBounds = bounds,
                directSupers = (declaration.extendedTypes + declaration.implementedTypes).map { parseType(it.asString()) }
            )
        }
    }

    private fun <T> exactSingle(values: List<T>, label: String): T? {
        val distinct = values.distinct()
        if (distinct.size > 1) throw IllegalStateException("Ambiguous $label: $distinct")
        return distinct.singleOrNull()
    }

    companion object {
        private val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val primitiveTypes = setOf("boolean", "byte", "char", "double", "float", "int", "long", "short", "void")
        private val knownExternalTypes = mapOf(
            "ItemStack" to "net.minecraft.world.item.ItemStack",
            "Player" to "net.minecraft.world.entity.player.Player",
            "AbstractContainerScreen" to "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
            "List" to "java.util.List",
            "Collection" to "java.util.Collection",
            "Map" to "java.util.Map",
            "Optional" to "java.util.Optional"
        )
        private val implicitJavaLangTypes = setOf(
            "Boolean", "Byte", "Character", "Class", "Comparable", "Double", "Enum", "Float", "Integer", "Long",
            "Number", "Object", "Short", "String", "Throwable", "Void"
        ).associateWith { "java.lang.$it" }
        private val knownFields = mapOf(
            "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen" to mapOf("menu" to TypeRef("T"))
        )
        private val knownMethods = mapOf(
            "net.minecraft.world.entity.player.Player" to mapOf(
                ("getMainHandItem" to 0) to TypeRef("net.minecraft.world.item.ItemStack")
            ),
            "net.minecraft.nbt.CompoundTag" to mapOf(
                ("getCompound" to 1) to TypeRef("net.minecraft.nbt.CompoundTag")
            )
        )

        fun build(sourceRoot: Path): JavaProjectTypeIndex =
            JavaProjectTypeIndex(sourceRoot.toAbsolutePath().normalize())

        private fun parseType(raw: String): TypeRef {
            var text = raw.trim()
            var arrayDepth = 0
            while (text.endsWith("[]")) {
                text = text.removeSuffix("[]").trimEnd()
                arrayDepth++
            }
            val open = text.indexOf('<')
            if (open < 0) return TypeRef(text, arrayDepth = arrayDepth)
            val close = text.lastIndexOf('>')
            if (close < open) throw IllegalStateException("Malformed generic type '$raw'")
            return TypeRef(
                text.substring(0, open).trim(),
                splitArguments(text.substring(open + 1, close)).map(::parseType),
                arrayDepth
            )
        }

        private fun splitArguments(text: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { index, char ->
                when (char) {
                    '<' -> depth++
                    '>' -> depth--
                    ',' -> if (depth == 0) {
                        result += text.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
            result += text.substring(start).trim()
            return result.filter { it.isNotEmpty() }
        }
    }
}
