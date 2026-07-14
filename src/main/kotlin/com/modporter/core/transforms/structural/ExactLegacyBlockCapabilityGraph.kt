package com.modporter.core.transforms.structural

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Resolves legacy custom block capability tokens and their predicate override families. */
internal class ExactLegacyBlockCapabilityGraph private constructor(
    private val sourceRoot: Path,
    private val index: JavaProjectTypeIndex,
    private val tokenDeclarations: List<TokenDeclaration>,
    private val anchors: List<PredicateAnchor>
) {
    enum class ProviderStrategy {
        RECEIVER_LAZY_OPTIONAL,
        BRANCH_RETURN
    }

    data class PredicateContract(
        val methodName: String,
        val receiverName: String?,
        val targetExpression: String,
        val providerStrategy: ProviderStrategy,
        val providerApiType: String?,
        val providerMethodTypeParameterCount: Int?
    )

    private data class ExternalContract(val targetExpression: String)

    private data class TokenDeclaration(
        val file: Path,
        val owner: String,
        val fieldName: String,
        val apiType: String,
        val targetExpression: String
    )

    private data class PredicateAnchor(
        val owner: String,
        val methodName: String,
        val parameterIndex: Int,
        val arity: Int,
        val targetExpression: String,
        val providerStrategy: ProviderStrategy,
        val providerApiType: String?
    )

    fun contractsUsedBy(file: Path, ownerType: String, methodName: String): List<PredicateContract> {
        val candidates = index.unit(file).findAll(MethodDeclaration::class.java).filter { method ->
            method.nameAsString == methodName &&
                exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null) == ownerType &&
                method.parameters.count { parameter ->
                    hasCapabilitySyntax(parameter.type) &&
                        isLegacyCapabilityType(index.declaredRawType(parameter.type, method))
                } == 1
        }
        if (candidates.size > 1) {
            throw IllegalStateException("Ambiguous legacy capability methods $ownerType.$methodName")
        }
        val method = candidates.singleOrNull() ?: return emptyList()
        val capabilityParameter = method.parameters.single { parameter ->
            hasCapabilitySyntax(parameter.type) &&
                isLegacyCapabilityType(index.declaredRawType(parameter.type, method))
        }
        val capabilityParameterIndex = method.parameters.indexOf(capabilityParameter)
        val matching = method.findAll(MethodCallExpr::class.java).flatMap { call ->
            val matchingAnchors = anchors.filter { anchor ->
                if (call.nameAsString != anchor.methodName || call.arguments.size != anchor.arity) return@filter false
                val argument = call.arguments.getOrNull(anchor.parameterIndex) as? NameExpr ?: return@filter false
                if (argument.nameAsString != capabilityParameter.nameAsString) return@filter false
                val receiverType = index.methodCallReceiverType(call) ?: return@filter false
                index.isTypeAssignableTo(receiverType, anchor.owner) ||
                    index.isTypeAssignableTo(anchor.owner, receiverType)
            }
            if (matchingAnchors.isEmpty()) return@flatMap emptyList()
            val receiverName = exactPredicateReceiverName(call, ownerType, methodName)
            matchingAnchors.map { anchor ->
                if (anchor.providerStrategy == ProviderStrategy.RECEIVER_LAZY_OPTIONAL && receiverName == null) {
                    throw IllegalStateException(
                        "External block capability predicate ${call.nameAsString} in $ownerType.$methodName " +
                            "does not use an exact instance-field receiver"
                    )
                }
                val providerMethodTypeParameterCount = if (
                    anchor.providerStrategy == ProviderStrategy.RECEIVER_LAZY_OPTIONAL
                ) {
                    val providerCall = exactExternalProviderCall(call, receiverName!!)
                        ?: throw IllegalStateException(
                            "External block capability predicate ${call.nameAsString} in $ownerType.$methodName " +
                                "does not guard one exact LazyOptional provider return"
                        )
                    index.projectMethodTypeParameterCount(providerCall)
                        ?: throw IllegalStateException(
                            "Cannot resolve external capability provider method $providerCall in $ownerType.$methodName"
                        )
                } else {
                    null
                }
                if (providerMethodTypeParameterCount != null && providerMethodTypeParameterCount !in 0..1) {
                    throw IllegalStateException(
                        "External capability provider in $ownerType.$methodName has " +
                            "$providerMethodTypeParameterCount method type parameters"
                    )
                }
                PredicateContract(
                    anchor.methodName,
                    receiverName,
                    anchor.targetExpression,
                    anchor.providerStrategy,
                    anchor.providerApiType,
                    providerMethodTypeParameterCount
                )
            }
        }.distinct()
        val conflicting = matching.groupBy { it.methodName }.filterValues { contracts ->
            contracts.map { it.targetExpression }.distinct().size > 1
        }
        if (conflicting.isNotEmpty()) {
            throw IllegalStateException(
                "Ambiguous external block capability contracts in $ownerType.$methodName at parameter " +
                    "$capabilityParameterIndex: $conflicting"
            )
        }
        val duplicateTargets = matching.groupBy { it.targetExpression }.filterValues { contracts ->
            contracts.map { Triple(it.receiverName, it.methodName, it.providerStrategy) }.distinct().size > 1
        }
        if (duplicateTargets.isNotEmpty()) {
            throw IllegalStateException(
                "Ambiguous external block capability provider branches in $ownerType.$methodName: $duplicateTargets"
            )
        }
        return matching
    }

    private fun exactExternalProviderCall(
        predicateCall: MethodCallExpr,
        receiverName: String
    ): MethodCallExpr? {
        val branch = predicateCall.findAncestor(com.github.javaparser.ast.stmt.IfStmt::class.java)
            .orElse(null)
            ?: return null
        if (branch.condition.findAll(MethodCallExpr::class.java).none { it === predicateCall }) return null
        val returns = branch.thenStmt.findAll(com.github.javaparser.ast.stmt.ReturnStmt::class.java)
        if (returns.size != 1) return null
        val providerCall = returns.single().expression.orElse(null) as? MethodCallExpr ?: return null
        if (providerCall.arguments.isNotEmpty()) return null
        val providerReceiver = when (val scope = providerCall.scope.orElse(null)) {
            is NameExpr -> scope.nameAsString
            is FieldAccessExpr -> scope.nameAsString.takeIf { scope.scope is ThisExpr }
            else -> null
        }
        if (providerReceiver != receiverName) return null
        val providerType = index.expressionRawType(providerCall, providerCall)
        if (providerType != "net.minecraftforge.common.util.LazyOptional" &&
            providerType != "net.neoforged.neoforge.common.util.LazyOptional" &&
            providerType?.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.LazyOptional""")) != true
        ) {
            return null
        }
        return providerCall
    }

    fun migrateDeclarationsAndPredicates(dryRun: Boolean): List<Change> {
        if (anchors.isEmpty()) return emptyList()
        val index = JavaProjectTypeIndex.build(sourceRoot)
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        val changes = mutableListOf<Change>()
        files.forEach { file ->
            val unit = index.unit(file)
            LexicalPreservingPrinter.setup(unit)
            var changed = false
            unit.findAll(FieldDeclaration::class.java).forEach { field ->
                field.variables.forEach { variable ->
                    val owner = exactEnclosingNamedClass(variable)?.fullyQualifiedName?.orElse(null)
                        ?: return@forEach
                    val declaration = tokenDeclarations.firstOrNull {
                        it.file == file.toAbsolutePath().normalize() &&
                            it.owner == owner &&
                            it.fieldName == variable.nameAsString
                    } ?: return@forEach
                    variable.setType(
                        StaticJavaParser.parseType(
                            "net.neoforged.neoforge.capabilities.BlockCapability<${declaration.apiType}, net.minecraft.core.Direction>"
                        )
                    )
                    variable.setInitializer(StaticJavaParser.parseExpression(declaration.targetExpression))
                    changed = true
                }
            }
            unit.findAll(MethodDeclaration::class.java).forEach { method ->
                val owner = exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null) ?: return@forEach
                val matching = anchors.filter { anchor ->
                    anchor.methodName == method.nameAsString &&
                        anchor.arity == method.parameters.size &&
                        anchor.parameterIndex in method.parameters.indices &&
                        (index.isTypeAssignableTo(owner, anchor.owner) ||
                            index.isTypeAssignableTo(anchor.owner, owner))
                }
                if (matching.isEmpty()) return@forEach
                val indexes = matching.map { it.parameterIndex }.distinct()
                if (indexes.size != 1) {
                    throw IllegalStateException(
                        "Conflicting legacy block capability predicate positions for $owner.${method.nameAsString}: $indexes"
                    )
                }
                val parameter = method.parameters[indexes.single()]
                if (!isLegacyCapabilityType(index.declaredRawType(parameter.type, method))) return@forEach
                val argument = parameter.type.asString()
                    .substringAfter('<', "?")
                    .substringBeforeLast('>', "?")
                    .ifBlank { "?" }
                parameter.setType(
                    StaticJavaParser.parseType(
                        "net.neoforged.neoforge.capabilities.BlockCapability<$argument, net.minecraft.core.Direction>"
                    )
                )
                changed = true
            }
            if (!changed) return@forEach
            var migrated = LexicalPreservingPrinter.print(unit)
            migrated = cleanupLegacyTokenImports(migrated)
            if (!dryRun) file.writeText(migrated)
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate exact legacy block capability token and predicate contracts",
                before = "Capability token declaration or predicate parameter",
                after = "target BlockCapability declaration and predicate family",
                confidence = Confidence.HIGH,
                ruleId = "struct-exact-legacy-block-capability-contract"
            )
        }
        return changes
    }

    private fun cleanupLegacyTokenImports(source: String): String {
        val body = Regex("""(?m)^[ \t]*import\s+[^\r\n]+;\s*\r?\n""").replace(source, "")
        var result = source
        if (!Regex("""\bCapabilityManager\b""").containsMatchIn(body)) {
            result = Regex(
                """(?m)^[ \t]*import\s+(?:net\.minecraftforge\.common\.capabilities|net\.neoforged\.neoforge\.capabilities|com\.modporter\.generated\.[\w.]+\.compat)\.CapabilityManager;\s*\r?\n"""
            ).replace(result, "")
        }
        if (!Regex("""\bCapabilityToken\b""").containsMatchIn(body)) {
            result = Regex(
                """(?m)^[ \t]*import\s+(?:net\.minecraftforge\.common\.capabilities|net\.neoforged\.neoforge\.capabilities|com\.modporter\.generated\.[\w.]+\.compat)\.CapabilityToken;\s*\r?\n"""
            ).replace(result, "")
        }
        if (!Regex("""(?<!Block)\bCapability\s*<""").containsMatchIn(body)) {
            result = Regex(
                """(?m)^[ \t]*import\s+(?:net\.minecraftforge\.common\.capabilities|net\.neoforged\.neoforge\.capabilities|com\.modporter\.generated\.[\w.]+\.compat)\.Capability;\s*\r?\n"""
            ).replace(result, "")
        }
        return result
    }

    companion object {
        private val externalContracts = mapOf(
            "dan200.computercraft.api.peripheral.IPeripheral" to ExternalContract(
                "dan200.computercraft.api.peripheral.PeripheralCapability.get()"
            )
        )
        fun build(sourceRoot: Path): ExactLegacyBlockCapabilityGraph {
            if (!Files.isDirectory(sourceRoot)) {
                return ExactLegacyBlockCapabilityGraph(
                    sourceRoot,
                    JavaProjectTypeIndex.build(sourceRoot),
                    emptyList(),
                    emptyList()
                )
            }
            val files = Files.walk(sourceRoot).use { stream ->
                stream.filter { it.extension == "java" }.toList()
            }
            val index = JavaProjectTypeIndex.build(sourceRoot)
            val declarations = mutableListOf<TokenDeclaration>()
            files.forEach { file ->
                val normalizedFile = file.toAbsolutePath().normalize()
                index.unit(file).findAll(FieldDeclaration::class.java).forEach { field ->
                    field.variables.forEach { variable ->
                        if (!hasCapabilitySyntax(variable.type)) return@forEach
                        val type = index.declaredTypeWithArguments(variable.type, variable) ?: return@forEach
                        if (!isLegacyCapabilityType(type.first) || type.second.size != 1) return@forEach
                        val contract = externalContracts[type.second.single()] ?: return@forEach
                        if (!isExactLegacyCapabilityTokenInitializer(variable.initializer.orElse(null), variable, index)) {
                            return@forEach
                        }
                        val owner = exactEnclosingNamedClass(variable)?.fullyQualifiedName?.orElse(null)
                            ?: throw IllegalStateException(
                                "Cannot resolve owner of legacy capability token ${variable.nameAsString}"
                            )
                        declarations += TokenDeclaration(
                            normalizedFile,
                            owner,
                            variable.nameAsString,
                            type.second.single(),
                            contract.targetExpression
                        )
                    }
                }
            }
            val anchors = mutableListOf<PredicateAnchor>()
            files.forEach { file ->
                index.unit(file).findAll(MethodDeclaration::class.java).forEach { method ->
                    if (!method.type.isPrimitiveType ||
                        method.type.asPrimitiveType().type != PrimitiveType.Primitive.BOOLEAN
                    ) {
                        return@forEach
                    }
                    val capabilityParameters = method.parameters.mapIndexedNotNull { parameterIndex, parameter ->
                        parameterIndex.takeIf {
                            hasCapabilitySyntax(parameter.type) &&
                                isLegacyCapabilityType(index.declaredRawType(parameter.type, method))
                        }
                    }
                    if (capabilityParameters.size != 1) return@forEach
                    val parameterIndex = capabilityParameters.single()
                    val parameterName = method.parameters[parameterIndex].nameAsString
                    val owner = exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null) ?: return@forEach
                    val comparedExpressions = method.findAll(BinaryExpr::class.java).mapNotNull { comparison ->
                        if (comparison.operator !in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS)) {
                            return@mapNotNull null
                        }
                        comparedExpression(comparison.left, comparison.right, parameterName)
                    }
                    val comparedFields = comparedExpressions.mapNotNull(::fieldName)
                    val declaration = declarations.singleOrNull { it.owner == owner && it.fieldName in comparedFields }
                    val builtIns = comparedExpressions.mapNotNull { expression ->
                        exactBuiltInBlockCapabilityExpression(expression, method, index)
                    }.distinct()
                    if (builtIns.size > 1) {
                        throw IllegalStateException(
                            "Ambiguous built-in block capability predicate $owner.${method.nameAsString}: $builtIns"
                        )
                    }
                    val builtIn = builtIns.singleOrNull()
                    if (declaration != null && builtIn != null) {
                        throw IllegalStateException(
                            "Predicate $owner.${method.nameAsString} mixes external and built-in capability contracts"
                        )
                    }
                    val targetExpression = declaration?.targetExpression ?: builtIn
                    if (targetExpression != null) {
                        anchors += PredicateAnchor(
                            owner,
                            method.nameAsString,
                            parameterIndex,
                            method.parameters.size,
                            targetExpression,
                            if (declaration != null) {
                                ProviderStrategy.RECEIVER_LAZY_OPTIONAL
                            } else {
                                ProviderStrategy.BRANCH_RETURN
                            },
                            declaration?.apiType
                        )
                    }
                }
            }
            return ExactLegacyBlockCapabilityGraph(sourceRoot, index, declarations, anchors)
        }

        private fun comparedExpression(
            left: Expression,
            right: Expression,
            parameterName: String
        ): Expression? = when {
                left is NameExpr && left.nameAsString == parameterName -> right
                right is NameExpr && right.nameAsString == parameterName -> left
                else -> null
            }

        private fun fieldName(expression: Expression): String? = when (expression) {
                is NameExpr -> expression.nameAsString
                is FieldAccessExpr -> expression.nameAsString
                else -> null
            }

        private fun exactBuiltInBlockCapabilityExpression(
            expression: Expression,
            use: com.github.javaparser.ast.Node,
            index: JavaProjectTypeIndex
        ): String? {
            val segments = qualifiedExpressionSegments(expression)
            if (segments.size < 3 || segments.last() != "BLOCK") return null
            val ownerText = segments.dropLast(2).joinToString(".")
            val ownerType = runCatching {
                index.declaredRawType(StaticJavaParser.parseType(ownerText), use)
            }.getOrNull()
            val exactOwner = ownerType == "net.neoforged.neoforge.capabilities.Capabilities" ||
                ownerType?.matches(
                    Regex("""com\.modporter\.generated\.[\w.]+\.compat\.Capabilities""")
                ) == true
            return expression.toString().takeIf { exactOwner }
        }

        private fun qualifiedExpressionSegments(expression: Expression): List<String> = when (expression) {
            is NameExpr -> listOf(expression.nameAsString)
            is FieldAccessExpr -> qualifiedExpressionSegments(expression.scope) + expression.nameAsString
            else -> emptyList()
        }

        private fun exactPredicateReceiverName(
            call: MethodCallExpr,
            ownerType: String,
            enclosingMethod: String
        ): String? = when (val scope = call.scope.orElse(null)) {
            null, is ThisExpr -> null
            is NameExpr -> scope.nameAsString
            is FieldAccessExpr -> scope.nameAsString.takeIf { scope.scope is ThisExpr }
                ?: throw IllegalStateException(
                    "Capability predicate ${call.nameAsString} in $ownerType.$enclosingMethod uses " +
                        "an unsupported receiver $scope"
                )
            else -> throw IllegalStateException(
                "Capability predicate ${call.nameAsString} in $ownerType.$enclosingMethod uses " +
                    "an unsupported receiver $scope"
            )
        }

        private fun isExactLegacyCapabilityTokenInitializer(
            initializer: Expression?,
            use: com.github.javaparser.ast.Node,
            index: JavaProjectTypeIndex
        ): Boolean {
            val call = initializer as? MethodCallExpr ?: return false
            if (call.nameAsString != "get" || call.arguments.size != 1) return false
            val managerScope = call.scope.orElse(null) ?: return false
            val managerType = runCatching {
                index.declaredRawType(StaticJavaParser.parseType(managerScope.toString()), use)
            }.getOrNull()
            if (!isLegacyCapabilityManagerType(managerType)) return false
            val token = call.arguments.single() as? ObjectCreationExpr ?: return false
            if (!token.anonymousClassBody.isPresent) return false
            return isLegacyCapabilityTokenType(index.declaredRawType(token.type, use))
        }

        private fun isLegacyCapabilityManagerType(type: String?): Boolean =
            type == "net.minecraftforge.common.capabilities.CapabilityManager" ||
                type == "net.neoforged.neoforge.capabilities.CapabilityManager" ||
                type?.matches(
                    Regex("""com\.modporter\.generated\.[\w.]+\.compat\.CapabilityManager""")
                ) == true

        private fun isLegacyCapabilityTokenType(type: String?): Boolean =
            type == "net.minecraftforge.common.capabilities.CapabilityToken" ||
                type == "net.neoforged.neoforge.capabilities.CapabilityToken" ||
                type?.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.CapabilityToken""")) == true

        private fun isLegacyCapabilityType(type: String?): Boolean =
            type == "net.minecraftforge.common.capabilities.Capability" ||
                type == "net.neoforged.neoforge.capabilities.Capability" ||
                type?.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.Capability""")) == true

        private fun hasCapabilitySyntax(type: Type): Boolean =
            type is ClassOrInterfaceType && type.nameAsString == "Capability"
    }
}
