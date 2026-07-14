package com.modporter.core.transforms.structural

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.ArrayAccessExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.SuperExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import java.nio.file.Path

/** Resolves legacy LazyOptional returns into exact nullable registration providers. */
internal class ExactLegacyLazyOptionalProviderResolver(sourceRoot: Path) {
    private val index = JavaProjectTypeIndex.build(sourceRoot)

    fun resolve(
        file: Path,
        ownerType: String,
        methodName: String,
        returnExpression: String,
        capabilityParameter: String,
        sideParameter: String
    ): String? {
        val methods = index.unit(file).findAll(MethodDeclaration::class.java).filter { method ->
            method.nameAsString == methodName &&
                exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null) == ownerType
        }
        if (methods.size > 1) {
            throw IllegalStateException("Ambiguous provider method $ownerType.$methodName")
        }
        val method = methods.singleOrNull() ?: return null
        val canonicalReturn = StaticJavaParser.parseExpression<Expression>(returnExpression).toString()
        val returns = method.findAll(ReturnStmt::class.java).mapNotNull { statement ->
            statement.expression.orElse(null)?.takeIf { it.toString() == canonicalReturn }
        }
        if (returns.size > 1) {
            throw IllegalStateException(
                "Ambiguous return expression '$canonicalReturn' in $ownerType.$methodName"
            )
        }
        val expression = returns.singleOrNull() ?: return null
        val provider = unwrapCast(expression) ?: expression
        if (!isLegacyLazyOptional(index.expressionRawType(provider, provider))) return null

        val capabilityReferences = provider.findAll(NameExpr::class.java).filter {
            it.nameAsString == capabilityParameter
        }
        capabilityReferences.forEach { reference ->
            val call = reference.parentNode.orElse(null) as? MethodCallExpr ?: return null
            val argumentIndex = call.arguments.indexOfFirst { it === reference }
            if (argumentIndex < 0 || !index.isExactProjectMethodArgumentUnused(call, argumentIndex)) return null
        }

        val root = exactRootName(provider) ?: return null
        if (!index.isExactInstanceFieldReference(root)) return null
        var rendered = provider.toString()
        if (capabilityReferences.isNotEmpty()) {
            rendered = Regex("""\b${Regex.escape(capabilityParameter)}\b""").replace(rendered, "null")
        }
        if (sideParameter != "side") {
            rendered = Regex("""\b${Regex.escape(sideParameter)}\b""").replace(rendered, "side")
        }
        rendered = if (rendered.startsWith("this.")) {
            rendered.replaceFirst("this.", "blockEntity.")
        } else {
            rendered.replaceFirst(root.nameAsString, "blockEntity.${root.nameAsString}")
        }
        return "$rendered.orElse(null)"
    }

    fun resolveGuardedTail(
        file: Path,
        ownerType: String,
        methodName: String,
        predicateMethod: String,
        capabilityParameter: String,
        sideParameter: String
    ): String? {
        val method = exactMethod(file, ownerType, methodName) ?: return null
        val statements = method.body.orElse(null)?.statements ?: return null
        val predicateIndex = statements.indexOfFirst { statement ->
            val branch = statement as? IfStmt ?: return@indexOfFirst false
            isNegatedPredicate(branch.condition, predicateMethod, capabilityParameter) &&
                returnsOnlyLegacySuper(branch.thenStmt)
        }
        if (predicateIndex < 0) return null
        val tail = statements.drop(predicateIndex + 1)
        val finalReturn = tail.lastOrNull() as? ReturnStmt ?: return null
        val finalExpression = finalReturn.expression.orElse(null) ?: return null
        val provider = resolve(
            file,
            ownerType,
            methodName,
            finalExpression.toString(),
            capabilityParameter,
            sideParameter
        ) ?: return null
        val intermediate = tail.dropLast(1)
        val remainingCapabilityReferences = intermediate.flatMap { statement ->
            statement.findAll(NameExpr::class.java).filter { it.nameAsString == capabilityParameter }
        }
        if (remainingCapabilityReferences.any { reference ->
                val call = reference.parentNode.orElse(null) as? MethodCallExpr ?: return@any true
                call.nameAsString != "getCapability" || call.scope.orElse(null) !is SuperExpr
            }
        ) return null
        val rendered = intermediate.map { statement ->
            rewriteGuardedProviderStatement(statement) ?: return null
        }
        return buildString {
            append("{\n")
            rendered.forEach { statement ->
                append(statement.prependIndent("                    "))
                append('\n')
            }
            append("                    return ")
            append(provider)
            append(";\n                }")
        }
    }

    fun rewriteInstanceExpression(
        file: Path,
        ownerType: String,
        methodName: String,
        expressionText: String,
        parameterRenames: Map<String, String>
    ): String? {
        val method = exactMethod(file, ownerType, methodName) ?: return null
        val canonical = runCatching {
            StaticJavaParser.parseExpression<Expression>(expressionText).toString()
        }.getOrNull() ?: return null
        val candidates = method.findAll(Expression::class.java).filter { it.toString() == canonical }
        if (candidates.isEmpty()) return null
        val rendered = candidates.mapNotNull { expression ->
            rewriteExactInstanceExpression(expression, parameterRenames)
        }.distinct()
        return rendered.singleOrNull()
    }

    private fun exactMethod(file: Path, ownerType: String, methodName: String): MethodDeclaration? {
        val methods = index.unit(file).findAll(MethodDeclaration::class.java).filter { method ->
            method.nameAsString == methodName &&
                exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null) == ownerType
        }
        if (methods.size > 1) throw IllegalStateException("Ambiguous provider method $ownerType.$methodName")
        return methods.singleOrNull()
    }

    private fun isNegatedPredicate(
        condition: Expression,
        predicateMethod: String,
        capabilityParameter: String
    ): Boolean {
        val unwrapped = if (condition is EnclosedExpr) condition.inner else condition
        val negated = unwrapped as? UnaryExpr ?: return false
        if (negated.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return false
        val predicate = (negated.expression as? EnclosedExpr)?.inner ?: negated.expression
        val call = predicate as? MethodCallExpr ?: return false
        if (call.nameAsString != predicateMethod || call.arguments.size != 1) return false
        val argument = call.arguments.single() as? NameExpr ?: return false
        if (argument.nameAsString != capabilityParameter) return false
        return call.scope.orElse(null) == null || call.scope.orElse(null) is com.github.javaparser.ast.expr.ThisExpr
    }

    private fun returnsOnlyLegacySuper(statement: Statement): Boolean {
        val returns = if (statement is ReturnStmt) listOf(statement) else statement.findAll(ReturnStmt::class.java)
        if (returns.size != 1) return false
        val call = returns.single().expression.orElse(null) as? MethodCallExpr ?: return false
        return call.nameAsString == "getCapability" && call.scope.orElse(null) is SuperExpr
    }

    private fun rewriteGuardedProviderStatement(statement: Statement): String? {
        val clone = statement.clone()
        val originalReturns = statement.findAll(ReturnStmt::class.java)
        val clonedReturns = clone.findAll(ReturnStmt::class.java)
        val originalCalls = statement.findAll(MethodCallExpr::class.java)
        val clonedCalls = clone.findAll(MethodCallExpr::class.java)
        val originalNames = statement.findAll(NameExpr::class.java)
        val clonedNames = clone.findAll(NameExpr::class.java)
        if (originalReturns.size != clonedReturns.size) return null
        if (originalCalls.size != clonedCalls.size) return null
        if (originalNames.size != clonedNames.size) return null
        originalReturns.zip(clonedReturns).forEach { (original, migrated) ->
            val call = original.expression.orElse(null) as? MethodCallExpr ?: return null
            if (call.nameAsString != "getCapability" || call.scope.orElse(null) !is SuperExpr) return null
            migrated.setExpression(NullLiteralExpr())
        }

        originalCalls.zip(clonedCalls).forEach { (original, migrated) ->
            if (index.isExactInstanceMethodCall(original)) migrated.setScope(NameExpr("blockEntity"))
        }
        originalNames.zip(clonedNames).forEach { (original, migrated) ->
            if (index.isExactInstanceFieldReference(original)) {
                migrated.replace(FieldAccessExpr(NameExpr("blockEntity"), migrated.nameAsString))
            }
        }
        return clone.toString()
    }

    private fun rewriteExactInstanceExpression(
        expression: Expression,
        parameterRenames: Map<String, String>
    ): String? {
        val clone = expression.clone()
        val originalCalls = expression.findAll(MethodCallExpr::class.java)
        val clonedCalls = clone.findAll(MethodCallExpr::class.java)
        val originalNames = expression.findAll(NameExpr::class.java)
        val clonedNames = clone.findAll(NameExpr::class.java)
        val originalThis = expression.findAll(ThisExpr::class.java)
        val clonedThis = clone.findAll(ThisExpr::class.java)
        if (originalCalls.size != clonedCalls.size ||
            originalNames.size != clonedNames.size ||
            originalThis.size != clonedThis.size
        ) return null

        originalThis.zip(clonedThis).forEach { (_, migrated) ->
            migrated.replace(NameExpr("blockEntity"))
        }
        originalCalls.zip(clonedCalls).forEach { (original, migrated) ->
            if (index.isExactInstanceMethodCall(original)) migrated.setScope(NameExpr("blockEntity"))
        }
        originalNames.zip(clonedNames).forEach { (original, migrated) ->
            val renamed = parameterRenames[original.nameAsString]
            when {
                renamed != null -> migrated.setName(renamed)
                index.isExactInstanceFieldReference(original) ->
                    migrated.replace(FieldAccessExpr(NameExpr("blockEntity"), migrated.nameAsString))
            }
        }
        return clone.toString()
    }

    private fun unwrapCast(expression: Expression): Expression? {
        val call = expression as? MethodCallExpr ?: return null
        if (call.nameAsString != "cast" || call.arguments.isNotEmpty()) return null
        return call.scope.orElse(null)
    }

    private fun exactRootName(expression: Expression): NameExpr? = when (expression) {
        is NameExpr -> expression
        is EnclosedExpr -> exactRootName(expression.inner)
        is ArrayAccessExpr -> exactRootName(expression.name)
        is MethodCallExpr -> expression.scope.orElse(null)?.let(::exactRootName)
        is FieldAccessExpr -> exactRootName(expression.scope)
        else -> null
    }

    private fun isLegacyLazyOptional(type: String?): Boolean =
        type == "net.minecraftforge.common.util.LazyOptional" ||
            type == "net.neoforged.neoforge.common.util.LazyOptional" ||
            type?.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.LazyOptional""")) == true
}
