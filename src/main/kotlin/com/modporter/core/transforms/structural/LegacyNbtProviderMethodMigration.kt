package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.PatternExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Migrates the project-local CompoundTag lifecycle method family to provider-aware 1.21 signatures. */
internal class LegacyNbtProviderMethodMigration {
    private data class SourceUnit(
        val file: Path,
        val source: String,
        val compilationUnit: CompilationUnit,
        val lineOffsets: IntArray
    )

    private data class TargetMethod(
        val unit: SourceUnit,
        val method: MethodDeclaration,
        val owner: String,
        val providerIndex: Int,
        val providerName: String,
        val legacyArity: Int
    )

    private data class MethodShape(val name: String, val parameterTypes: List<String>)

    private data class ProjectType(
        val qualifiedName: String,
        val simpleName: String,
        val directSuperTypes: List<String>
    )

    private data class Edit(val start: Int, val endExclusive: Int, val replacement: String)

    fun migrate(
        projectDir: Path,
        dryRun: Boolean,
        allowContextProviderExpressions: Boolean = true
    ): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot)
            .filter { it.extension == "java" }
            .filter { !sourceRoot.relativize(it).toString().replace('\\', '/').startsWith("com/modporter/generated/") }
            .toList()
        if (files.isEmpty()) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val units = mutableListOf<SourceUnit>()
        val projectTypes = mutableListOf<ProjectType>()
        files.forEach { file ->
            val source = file.readText()
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful) {
                throw IllegalStateException(
                    "Cannot parse $file while migrating provider-aware NBT methods: " +
                    parsed.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            val unit = SourceUnit(file, source, parsed.result.orElseThrow(), lineOffsets(source))
            projectTypes += collectProjectTypes(listOf(unit))
            if (isNbtMigrationUnitSource(source)) units += unit
        }
        if (units.isEmpty()) return emptyList()

        val typeByQualifiedName = projectTypes.associateBy { it.qualifiedName }
        val typesBySimpleName = projectTypes.groupBy { it.simpleName }
        val allMethods = units.flatMap { unit ->
            unit.compilationUnit.findAll(MethodDeclaration::class.java).mapNotNull { method ->
                declaringTypeName(method)?.let { owner -> Triple(unit, method, owner) }
            }
        }
        val existingAnchors = allMethods.mapNotNull { (_, method, owner) ->
            methodShapeWithoutProvider(method)?.let { shape -> owner to shape }
        }.toMutableSet()
        val candidates = allMethods.mapNotNull { (unit, method, owner) ->
            targetProviderIndex(method)?.let { index ->
                TargetMethod(unit, method, owner, index, uniqueProviderName(method), method.parameters.size)
            }
        }
        val selected = candidates.filter { isDirectSemanticTarget(it.method) }.toMutableList()
        selected.forEach { target -> existingAnchors += target.owner to methodShape(target.method) }
        var expanded: Boolean
        do {
            expanded = false
            candidates.filterNot { it in selected }.forEach { candidate ->
                val shape = methodShape(candidate.method)
                val related = existingAnchors.any { (anchorOwner, anchorShape) ->
                    anchorShape == shape && areOverrideRelated(
                        candidate.owner,
                        anchorOwner,
                        typeByQualifiedName,
                        typesBySimpleName
                    )
                }
                if (related) {
                    selected += candidate
                    existingAnchors += candidate.owner to shape
                    expanded = true
                }
            }
        } while (expanded)
        val targets = selected
        val providerAwareTargets = allMethods.mapNotNull { (unit, method, owner) ->
            methodShapeWithoutProvider(method)?.let {
                val providerIndex = method.parameters.indexOfFirst { parameter -> isProvider(parameter.type) }
                TargetMethod(
                    unit,
                    method,
                    owner,
                    providerIndex,
                    existingProviderName(method) ?: return@let null,
                    method.parameters.size - 1
                )
            }
        }
        val familyTargets = targets + providerAwareTargets
        if (familyTargets.isEmpty()) return emptyList()
        val targetDeclarations = familyTargets.associateBy { it.method }
        val targetShapes = familyTargets.map { it.method.nameAsString to it.legacyArity }.toSet()
        val targetOwnersByShape = familyTargets.groupBy { it.method.nameAsString to it.legacyArity }
            .mapValues { (_, methods) -> methods.map { it.owner }.toSet() }
        val edits = linkedMapOf<SourceUnit, MutableList<Edit>>()

        targets.forEach { target ->
            val insertionOffset = parameterInsertionOffset(target.unit, target.method, target.providerIndex)
            val prefix = when {
                target.method.parameters.isEmpty() -> ""
                target.providerIndex == target.method.parameters.size -> ", "
                else -> ""
            }
            val suffix = if (target.providerIndex < target.method.parameters.size) ", " else ""
            edits.getOrPut(target.unit) { mutableListOf() } += Edit(
                insertionOffset,
                insertionOffset,
                prefix + providerParameter(target.providerName) + suffix
            )
        }

        units.forEach { unit ->
            unit.compilationUnit.findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                val shape = call.nameAsString to call.arguments.size
                if (shape !in targetShapes) return@calls
                val callable = enclosingCallable(call) ?: return@calls
                if (call.nameAsString != "getUpdateTag" &&
                    (call.arguments.isEmpty() || !isCompoundTagExpression(callable, call.arguments[0]))) return@calls
                if (!callResolvesToTargetFamily(
                        call,
                        callable,
                        targetOwnersByShape.getValue(shape),
                        allMethods,
                        typeByQualifiedName,
                        typesBySimpleName
                    )
                ) return@calls
                val provider = targetDeclarations[callable as? MethodDeclaration]?.providerName
                    ?: existingProviderName(callable)
                    ?: if (allowContextProviderExpressions) {
                        exactProviderExpressionAtCall(
                            call,
                            callable,
                            typeByQualifiedName,
                            typesBySimpleName
                        )
                    } else {
                        null
                    }
                    ?: return@calls
                val index = callProviderIndex(call.nameAsString, call.arguments.size) ?: return@calls
                val insertionOffset = argumentInsertionOffset(unit, call, index)
                val prefix = when {
                    call.arguments.isEmpty() -> ""
                    index == call.arguments.size -> ", "
                    else -> ""
                }
                val suffix = if (index < call.arguments.size) ", " else ""
                edits.getOrPut(unit) { mutableListOf() } += Edit(
                    insertionOffset,
                    insertionOffset,
                    prefix + provider + suffix
                )
            }
        }

        val changes = mutableListOf<Change>()
        edits.forEach { (unit, rawEdits) ->
            val fileEdits = rawEdits.distinct().sortedByDescending { it.start }
            var migrated = unit.source
            fileEdits.forEach { edit ->
                migrated = migrated.substring(0, edit.start) + edit.replacement + migrated.substring(edit.endExclusive)
            }
            val verification = parser.parse(migrated)
            if (!verification.isSuccessful) {
                throw IllegalStateException(
                    "Provider-aware NBT method migration produced invalid Java in ${unit.file}: " +
                        verification.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            if (!dryRun) unit.file.writeText(migrated)
            changes += Change(
                file = unit.file,
                line = 1,
                description = "Migrate CompoundTag lifecycle method family to HolderLookup.Provider signatures",
                before = "project NBT lifecycle declaration/call without registry provider",
                after = "provider-aware declaration and exact internal forwarding",
                confidence = Confidence.HIGH,
                ruleId = "struct-nbt-provider-method-family"
            )
        }
        return changes
    }

    private fun targetProviderIndex(method: MethodDeclaration): Int? {
        if (method.parameters.any { isProvider(it.type) }) return null
        return expectedProviderIndex(method.nameAsString, method.parameters.map { normalizeType(it.type) })
    }

    private fun expectedProviderIndex(name: String, legacyTypes: List<String>): Int? = when (name) {
        "read", "write" -> if (legacyTypes.size == 2 && isCompoundTag(legacyTypes[0]) && legacyTypes[1] == "boolean") 1 else null
        "writeSafe", "readClient", "writeClient" ->
            if (legacyTypes.size == 1 && isCompoundTag(legacyTypes[0])) 1 else null
        "getUpdateTag" -> if (legacyTypes.isEmpty()) 0 else null
        "writeToClipboard" -> if (legacyTypes.size == 2 && isCompoundTag(legacyTypes[0])) 0 else null
        "readFromClipboard" ->
            if (legacyTypes.size == 4 && isCompoundTag(legacyTypes[0]) && legacyTypes.last() == "boolean") 0 else null
        else -> null
    }

    private fun isDirectSemanticTarget(method: MethodDeclaration): Boolean {
        if (method.nameAsString in setOf("read", "write", "writeSafe", "writeToClipboard", "readFromClipboard")) {
            return true
        }
        if (method.nameAsString !in setOf("readClient", "writeClient")) return false
        val tagName = method.parameters.singleOrNull()?.nameAsString ?: return false
        val requiredCalls = if (method.nameAsString == "readClient") {
            setOf("read", "load", "loadAdditional", "loadWithComponents")
        } else {
            setOf("write", "save", "saveAdditional")
        }
        return method.findAll(MethodCallExpr::class.java).any { call ->
            call.nameAsString in requiredCalls && call.arguments.firstOrNull()?.toString() == tagName
        }
    }

    private fun methodShape(method: MethodDeclaration): MethodShape =
        MethodShape(method.nameAsString, method.parameters.map { normalizeType(it.type) })

    private fun methodShapeWithoutProvider(method: MethodDeclaration): MethodShape? {
        val providerIndexes = method.parameters.mapIndexedNotNull { index, parameter ->
            index.takeIf { isProvider(parameter.type) }
        }
        if (providerIndexes.size != 1) return null
        val providerIndex = providerIndexes.single()
        val legacyTypes = method.parameters.map { normalizeType(it.type) }.toMutableList().also { it.removeAt(providerIndex) }
        if (expectedProviderIndex(method.nameAsString, legacyTypes) != providerIndex) return null
        return MethodShape(method.nameAsString, legacyTypes)
    }

    private fun collectProjectTypes(units: List<SourceUnit>): List<ProjectType> = units.flatMap { unit ->
        unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { declaration ->
            val qualifiedName = declaration.fullyQualifiedName.orElse(null) ?: return@mapNotNull null
            ProjectType(
                qualifiedName = qualifiedName,
                simpleName = declaration.nameAsString,
                directSuperTypes = declaration.extendedTypes.map { it.nameWithScope }
            )
        }
    }

    private fun isNbtMigrationUnitSource(source: String): Boolean =
        source.contains("CompoundTag") ||
            source.contains(".readNbt(") ||
            listOf(
                "readClient(",
                "writeClient(",
                "writeSafe(",
                "getUpdateTag(",
                "writeToClipboard(",
                "readFromClipboard("
            ).any(source::contains)

    private fun declaringTypeName(method: MethodDeclaration): String? =
        method.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .flatMap { it.fullyQualifiedName }
            .orElse(null)

    private fun areOverrideRelated(
        first: String,
        second: String,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>
    ): Boolean = first == second ||
        inheritsFrom(first, second, typeByQualifiedName, typesBySimpleName) ||
        inheritsFrom(second, first, typeByQualifiedName, typesBySimpleName)

    private fun inheritsFrom(
        child: String,
        ancestor: String,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>,
        visited: MutableSet<String> = mutableSetOf()
    ): Boolean {
        if (!visited.add(child)) return false
        val type = typeByQualifiedName[child] ?: return false
        return type.directSuperTypes.any { rawSuper ->
            val resolved = resolveProjectType(rawSuper, type, typeByQualifiedName, typesBySimpleName) ?: return@any false
            resolved.qualifiedName == ancestor || inheritsFrom(
                resolved.qualifiedName,
                ancestor,
                typeByQualifiedName,
                typesBySimpleName,
                visited
            )
        }
    }

    private fun resolveProjectType(
        rawType: String,
        owner: ProjectType,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>
    ): ProjectType? {
        typeByQualifiedName[rawType]?.let { return it }
        val ownerPackage = owner.qualifiedName.substringBeforeLast('.', "")
        typeByQualifiedName[if (ownerPackage.isEmpty()) rawType else "$ownerPackage.$rawType"]?.let { return it }
        val simpleName = rawType.substringAfterLast('.')
        val candidates = typesBySimpleName[simpleName].orEmpty()
        if (candidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous project super type '$rawType' while propagating HolderLookup.Provider from ${owner.qualifiedName}"
            )
        }
        return candidates.singleOrNull()
    }

    private fun callResolvesToTargetFamily(
        call: MethodCallExpr,
        callable: CallableDeclaration<*>,
        targetOwners: Set<String>,
        allMethods: List<Triple<SourceUnit, MethodDeclaration, String>>,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>
    ): Boolean {
        val callerOwner = (callable as? MethodDeclaration)?.let(::declaringTypeName)
            ?: callable.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .flatMap { it.fullyQualifiedName }
                .orElse(null)
            ?: return false
        val scope = call.scope.orElse(null)
        if (call.nameAsString == "getUpdateTag" && callReceiverIsBlockEntity(
                scope,
                callable,
                callerOwner,
                typeByQualifiedName,
                typesBySimpleName
            )
        ) return true
        val receiverOwner = when (scope) {
            null, is ThisExpr, is SuperExpr -> callerOwner
            is NameExpr -> {
                val rawType = declaredTypeOfName(callable, scope.nameAsString)
                    ?: inferredLambdaParameterType(call, scope.nameAsString, allMethods)
                    ?: scope.nameAsString.takeIf { it.firstOrNull()?.isUpperCase() == true }
                    ?: return false
                resolveProjectTypeReference(
                    rawType,
                    callerOwner,
                    typeByQualifiedName,
                    typesBySimpleName
                ) ?: return false
            }
            else -> return false
        }
        return targetOwners.any { targetOwner ->
            receiverOwner == targetOwner || inheritsFrom(
                receiverOwner,
                targetOwner,
                typeByQualifiedName,
                typesBySimpleName
            )
        }
    }

    private fun exactProviderExpressionAtCall(
        call: MethodCallExpr,
        callable: CallableDeclaration<*>,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>
    ): String? {
        fun unique(expressions: List<String>, label: String): String? {
            val distinct = expressions.distinct()
            if (distinct.size > 1) {
                throw IllegalStateException(
                    "Ambiguous $label registry provider expressions for ${call.nameAsString}: $distinct"
                )
            }
            return distinct.singleOrNull()
        }

        val parsedParameters = callable.parameters.map { parameter ->
            normalizeType(parameter.type) to parameter.nameAsString
        }
        unique(parsedParameters.mapNotNull { (type, name) ->
            when (simpleErasedType(type)) {
                "HolderLookup.Provider", "Provider", "RegistryAccess" -> name
                else -> null
            }
        }, "direct")?.let { return it }
        unique(parsedParameters.mapNotNull { (type, name) ->
            when (simpleErasedType(type)) {
                "Level", "ServerLevel", "WorldGenLevel", "LevelAccessor", "ServerLevelAccessor", "LevelReader" ->
                    "$name.registryAccess()"
                "Inventory" -> "$name.player.registryAccess()"
                "BlockEntity" -> "$name.getLevel().registryAccess()"
                "Player", "ServerPlayer", "LivingEntity", "Entity" -> "$name.registryAccess()"
                "Minecraft" -> "$name.level.registryAccess()"
                else -> null
            }
        }, "typed parameter")?.let { return it }

        val callerOwner = callable.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .flatMap { it.fullyQualifiedName }
            .orElse(null)
            ?: return null
        val scope = call.scope.orElse(null)
        val receiverExpression = when (scope) {
            null, is ThisExpr, is SuperExpr -> "this"
            is NameExpr -> scope.nameAsString
            else -> null
        }
        if (receiverExpression != null && callReceiverIsBlockEntity(
                scope,
                callable,
                callerOwner,
                typeByQualifiedName,
                typesBySimpleName
            )
        ) {
            return "$receiverExpression.getLevel().registryAccess()"
        }
        if (projectTypeInheritsExternalBase(callerOwner, "BlockEntity", typeByQualifiedName, typesBySimpleName)) {
            return "this.getLevel().registryAccess()"
        }
        return null
    }

    private fun callReceiverIsBlockEntity(
        scope: Node?,
        callable: CallableDeclaration<*>,
        callerOwner: String,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>
    ): Boolean {
        val rawType = when (scope) {
            null, is ThisExpr, is SuperExpr -> callerOwner
            is NameExpr -> declaredTypeOfName(callable, scope.nameAsString) ?: return false
            else -> return false
        }
        if (simpleErasedType(rawType) == "BlockEntity") return true
        val resolved = resolveProjectTypeReference(rawType, callerOwner, typeByQualifiedName, typesBySimpleName)
            ?: return false
        return projectTypeInheritsExternalBase(resolved, "BlockEntity", typeByQualifiedName, typesBySimpleName)
    }

    private fun projectTypeInheritsExternalBase(
        typeName: String,
        baseSimpleName: String,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>,
        visited: MutableSet<String> = mutableSetOf()
    ): Boolean {
        if (!visited.add(typeName)) return false
        val type = typeByQualifiedName[typeName] ?: return simpleErasedType(typeName) == baseSimpleName
        return type.directSuperTypes.any { rawSuper ->
            if (simpleErasedType(rawSuper) == baseSimpleName) return@any true
            val resolved = resolveProjectType(rawSuper, type, typeByQualifiedName, typesBySimpleName)
                ?: return@any false
            projectTypeInheritsExternalBase(
                resolved.qualifiedName,
                baseSimpleName,
                typeByQualifiedName,
                typesBySimpleName,
                visited
            )
        }
    }

    private fun simpleErasedType(type: String): String =
        type.substringBefore('<').removeSuffix("[]").substringAfterLast('.')

    private fun declaredTypeOfName(callable: CallableDeclaration<*>, name: String): String? {
        val types = buildList {
            callable.parameters.filter { it.nameAsString == name }.forEach { add(normalizeType(it.type)) }
            callable.findAll(VariableDeclarator::class.java)
                .filter { it.nameAsString == name }
                .forEach { add(normalizeType(it.type)) }
            callable.findAll(PatternExpr::class.java)
                .filter { it.nameAsString == name }
                .forEach { add(normalizeType(it.type)) }
            callable.findAncestor(ClassOrInterfaceDeclaration::class.java).ifPresent { owner ->
                owner.fields.flatMap { it.variables }
                    .filter { it.nameAsString == name }
                    .forEach { add(normalizeType(it.type)) }
            }
        }.filterNot { it == "var" }.distinct()
        if (types.size > 1) {
            throw IllegalStateException("Ambiguous declared types for NBT call receiver '$name': $types")
        }
        return types.singleOrNull()
    }

    private fun inferredLambdaParameterType(
        call: MethodCallExpr,
        receiverName: String,
        allMethods: List<Triple<SourceUnit, MethodDeclaration, String>>
    ): String? {
        val lambda = call.findAncestor(LambdaExpr::class.java).orElse(null) ?: return null
        val lambdaParameterIndex = lambda.parameters.indexOfFirst { it.nameAsString == receiverName }
        if (lambdaParameterIndex < 0) return null
        val enclosingCall = lambda.findAncestor(MethodCallExpr::class.java).orElse(null) ?: return null
        val argumentIndex = enclosingCall.arguments.indexOfFirst { it === lambda || it.isAncestorOf(lambda) }
        if (argumentIndex < 0) return null
        val callableOwner = call.findAncestor(ClassOrInterfaceDeclaration::class.java)
            .flatMap { it.fullyQualifiedName }
            .orElse(null)
            ?: return null
        val declarations = allMethods.filter { (_, method, owner) ->
            owner == callableOwner &&
                method.nameAsString == enclosingCall.nameAsString &&
                method.parameters.size == enclosingCall.arguments.size
        }
        if (declarations.size != 1) return null
        val functionalType = normalizeType(declarations.single().second.parameters[argumentIndex].type)
        val genericArguments = functionalType.substringAfter('<', "")
            .substringBeforeLast('>', "")
            .takeIf { it.isNotBlank() }
            ?.let(::splitGenericArguments)
            ?: return null
        val simpleFunctionalType = functionalType.substringBefore('<').substringAfterLast('.')
        return when (simpleFunctionalType) {
            "Consumer", "Predicate" -> genericArguments.singleOrNull().takeIf { lambdaParameterIndex == 0 }
            "BiConsumer", "BiPredicate" -> genericArguments.getOrNull(lambdaParameterIndex)
            "Function" -> genericArguments.firstOrNull().takeIf { lambdaParameterIndex == 0 }
            else -> null
        }
    }

    private fun splitGenericArguments(text: String): List<String> {
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

    private fun resolveProjectTypeReference(
        rawType: String,
        ownerName: String,
        typeByQualifiedName: Map<String, ProjectType>,
        typesBySimpleName: Map<String, List<ProjectType>>
    ): String? {
        val erased = rawType.substringBefore('<').removeSuffix("[]")
        typeByQualifiedName[erased]?.let { return it.qualifiedName }
        val owner = typeByQualifiedName[ownerName] ?: return null
        return resolveProjectType(erased, owner, typeByQualifiedName, typesBySimpleName)?.qualifiedName
    }

    private fun callProviderIndex(name: String, arity: Int): Int? = when (name) {
        "read", "write" -> if (arity == 2) 1 else null
        "writeSafe", "readClient", "writeClient" -> if (arity == 1) 1 else null
        "getUpdateTag" -> if (arity == 0) 0 else null
        "writeToClipboard", "readFromClipboard" -> 0
        else -> null
    }

    private fun existingProviderName(callable: CallableDeclaration<*>): String? {
        val providers = callable.parameters.filter { isProvider(it.type) }.map { it.nameAsString }.distinct()
        if (providers.size > 1) {
            throw IllegalStateException("Ambiguous HolderLookup.Provider parameters in ${callable.nameAsString}")
        }
        return providers.singleOrNull()
    }

    private fun isCompoundTagExpression(callable: CallableDeclaration<*>, expression: Node): Boolean {
        if (expression.toString().replace(" ", "") in setOf("newCompoundTag()", "newnet.minecraft.nbt.CompoundTag()")) {
            return true
        }
        if (expression is MethodCallExpr && expression.nameAsString == "readNbt" && expression.arguments.isEmpty()) {
            val receiver = (expression.scope.orElse(null) as? NameExpr)?.nameAsString ?: return false
            val receiverType = declaredTypeOfName(callable, receiver)?.substringAfterLast('.')
            if (receiverType in setOf("FriendlyByteBuf", "RegistryFriendlyByteBuf")) return true
        }
        val name = expression.toString().takeIf { Regex("[A-Za-z_$][A-Za-z0-9_$]*").matches(it) } ?: return false
        if (callable.parameters.any { it.nameAsString == name && isCompoundTag(normalizeType(it.type)) }) return true
        return callable.findAll(com.github.javaparser.ast.body.VariableDeclarator::class.java).any {
            it.nameAsString == name && isCompoundTag(normalizeType(it.type))
        }
    }

    private fun enclosingCallable(node: Node): CallableDeclaration<*>? {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is MethodDeclaration || current is ConstructorDeclaration) {
                return current as CallableDeclaration<*>
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun parameterInsertionOffset(unit: SourceUnit, method: MethodDeclaration, index: Int): Int {
        if (index < method.parameters.size) return offset(unit, method.parameters[index].range.orElseThrow().begin)
        return closeParenOffset(unit, method)
    }

    private fun argumentInsertionOffset(unit: SourceUnit, call: MethodCallExpr, index: Int): Int {
        if (index < call.arguments.size) return offset(unit, call.arguments[index].range.orElseThrow().begin)
        return offset(unit, call.range.orElseThrow().end)
    }

    private fun closeParenOffset(unit: SourceUnit, method: MethodDeclaration): Int {
        val start = offset(unit, method.name.range.orElseThrow().end) + 1
        val open = unit.source.indexOf('(', start)
        require(open >= 0)
        var depth = 0
        for (index in open until unit.source.length) {
            when (unit.source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("Cannot find method parameter close parenthesis in ${unit.file}")
    }

    private fun uniqueProviderName(method: MethodDeclaration): String {
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*").findAll(method.toString()).map { it.value }.toSet()
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "modporterRegistries" else "modporterRegistries$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private fun providerParameter(name: String): String =
        "net.minecraft.core.HolderLookup.Provider $name"

    private fun normalizeType(type: Type): String = type.asString().replace(Regex("\\s+"), "")
    private fun isCompoundTag(type: String): Boolean = type == "CompoundTag" || type == "net.minecraft.nbt.CompoundTag"
    private fun isProvider(type: Type): Boolean = normalizeType(type) in setOf(
        "HolderLookup.Provider",
        "net.minecraft.core.HolderLookup.Provider"
    )

    private fun offset(unit: SourceUnit, position: Position): Int =
        unit.lineOffsets[position.line - 1] + position.column - 1

    private fun lineOffsets(source: String): IntArray {
        val result = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') result += index + 1 }
        return result.toIntArray()
    }
}
