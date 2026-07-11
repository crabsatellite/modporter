package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.type.Type
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

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

    fun expressionType(expression: Expression, use: Node): String? {
        val owner = use.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: throw IllegalStateException("Expression '$expression' is outside a declared type")
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index expression owner '${owner.nameAsString}'")
        val resolved = resolveExpression(expression, use, ownerInfo, emptyMap()) ?: return null
        return resolved.name.takeIf { resolved.arrayDepth == 0 }
    }

    fun isExpressionAssignableTo(expression: Expression, use: Node, expectedType: String): Boolean {
        val owner = use.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: throw IllegalStateException("Expression '$expression' is outside a declared type")
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index expression owner '${owner.nameAsString}'")
        val actual = resolveExpression(expression, use, ownerInfo, emptyMap()) ?: return false
        return isAssignableTo(actual, expectedType, mutableSetOf())
    }

    fun declaredType(type: Type, use: Node): String? {
        val owner = use.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: throw IllegalStateException("Type '$type' is outside a declared type")
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index type owner '${owner.nameAsString}'")
        val resolved = resolveType(parseType(type.asString()), ownerInfo, emptyMap()) ?: return null
        return resolved.name.takeIf { resolved.arrayDepth == 0 }
    }

    fun projectMethodOwner(call: MethodCallExpr, declarationArity: Int = call.arguments.size): String? {
        val owner = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: return null
        val ownerInfo = typeInfo(owner)
            ?: throw IllegalStateException("Cannot index call owner '${owner.nameAsString}'")
        val scope = call.scope.orElse(null)
        val receiver = if (scope == null) {
            TypeRef(ownerInfo.qualifiedName)
        } else {
            resolveExpression(scope, call, ownerInfo, emptyMap()) ?: if (scope is NameExpr) {
                resolveType(TypeRef(scope.nameAsString), ownerInfo, emptyMap())
            } else {
                null
            } ?: return null
        }
        return resolveProjectMethodOwner(receiver, call.nameAsString, declarationArity, mutableSetOf())
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
            is ObjectCreationExpr -> resolveType(parseType(expression.typeAsString), owner, substitutions)
            is ThisExpr -> TypeRef(owner.qualifiedName)
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
            return resolveProjectMethod(
                TypeRef(owner.qualifiedName, owner.typeParameters.mapNotNull(substitutions::get)),
                call.nameAsString,
                call.arguments.size,
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
        return resolveProjectMethod(scope, call.nameAsString, call.arguments.size, mutableSetOf())
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
        resolveLambdaParameter(name, use, owner, substitutions)?.let { return it }
        val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null)
        if (callable != null) {
            val parameters = callable.parameters.filter { it.nameAsString == name }.map {
                resolveType(parseType(it.typeAsString), owner, substitutions)
            }
            val locals = callable.findAll(VariableDeclarator::class.java).filter { variable ->
                variable.nameAsString == name && visibleBefore(variable, use)
            }.map { resolveType(parseType(it.typeAsString), owner, substitutions) }
            exactSingle((parameters + locals).filterNotNull(), "local or parameter '$name'")?.let { return it }
        }
        return resolveField(TypeRef(owner.qualifiedName), name, mutableSetOf())
    }

    private fun resolveLambdaParameter(
        name: String,
        use: Node,
        owner: TypeInfo,
        substitutions: Map<String, TypeRef>
    ): TypeRef? {
        val lambda = use.findAncestor(LambdaExpr::class.java).orElse(null) ?: return null
        val parameterIndex = lambda.parameters.indexOfFirst { it.nameAsString == name }
        if (parameterIndex < 0) return null
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
            parentCall.nameAsString,
            parentCall.arguments.size,
            argumentIndex,
            parameterIndex,
            mutableSetOf()
        )
    }

    private fun resolveProjectFunctionalParameter(
        receiver: TypeRef,
        methodName: String,
        arity: Int,
        argumentIndex: Int,
        lambdaParameterIndex: Int,
        visited: MutableSet<String>
    ): TypeRef? {
        if (!visited.add(receiver.toString())) return null
        val info = loadType(receiver.name) ?: return null
        val substitutions = substitutions(info, receiver)
        val methods = info.declaration.methods.filter {
            it.nameAsString == methodName && it.parameters.size == arity
        }
        if (methods.size > 1) throw IllegalStateException("Ambiguous method '$methodName/$arity' on ${receiver.name}")
        methods.singleOrNull()?.let { method ->
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
                    methodName,
                    arity,
                    argumentIndex,
                    lambdaParameterIndex,
                    visited
                )
            },
            "functional parameter '$methodName/$arity' inherited by ${receiver.name}"
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

    private fun resolveType(raw: TypeRef, owner: TypeInfo, substitutions: Map<String, TypeRef>): TypeRef? {
        substitutions[raw.name]?.let { return it.copy(arrayDepth = it.arrayDepth + raw.arrayDepth) }
        owner.typeParameterBounds[raw.name]?.let { bound ->
            return resolveType(bound, owner, substitutions)?.let {
                it.copy(arrayDepth = it.arrayDepth + raw.arrayDepth)
            }
        }
        val qualified = qualify(raw.name, owner.unit, owner.qualifiedName.substringBeforeLast('.', "")) ?: return null
        val arguments = mutableListOf<TypeRef>()
        raw.arguments.forEach { argument ->
            arguments += resolveType(argument, owner, substitutions) ?: return null
        }
        return TypeRef(qualified, arguments, raw.arrayDepth)
    }

    private fun qualify(name: String, unit: CompilationUnit, packageName: String): String? {
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
            "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float", "Integer", "Long",
            "Number", "Object", "Short", "String", "Throwable", "Void"
        ).associateWith { "java.lang.$it" }
        private val knownFields = mapOf(
            "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen" to mapOf("menu" to TypeRef("T"))
        )
        private val knownMethods = mapOf(
            "net.minecraft.world.entity.player.Player" to mapOf(
                ("getMainHandItem" to 0) to TypeRef("net.minecraft.world.item.ItemStack")
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
