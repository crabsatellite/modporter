package com.modporter.core.transforms.structural

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.BreakStmt
import com.github.javaparser.ast.stmt.ContinueStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.ast.stmt.ExpressionStmt

/** Proves nullable source expressions non-null only from dominating Java control flow. */
internal object ExactNullabilityProof {
    fun parameterProvenNonNullAt(
        parameter: Parameter,
        callable: CallableDeclaration<*>,
        use: Node
    ): Boolean {
        val expression = parameter.nameAsString
        val nullableEvidence = parameter.annotations.any { it.name.identifier == "Nullable" } ||
            callable.findAll(BinaryExpr::class.java).any { isNullComparison(it, expression) }
        return !nullableEvidence || expressionProvenNonNullAt(expression, callable, use)
    }

    fun expressionProvenNonNullAt(
        expression: String,
        callable: CallableDeclaration<*>,
        use: Node
    ): Boolean {
        var ancestor: Node? = use
        while (ancestor != null && ancestor !== callable) {
            val parent = ancestor.parentNode.orElse(null)
            if (parent is IfStmt) {
                val inThen = parent.thenStmt === ancestor || parent.thenStmt.isAncestorOf(use)
                val elseStmt = parent.elseStmt.orElse(null)
                val inElse = elseStmt != null && (elseStmt === ancestor || elseStmt.isAncestorOf(use))
                if (inThen && conditionTrueProvesNonNull(parent.condition, expression)) return true
                if (inElse && conditionFalseProvesNonNull(parent.condition, expression)) return true
            }
            ancestor = parent
        }

        val block = use.findAncestor(BlockStmt::class.java).orElse(null) ?: return false
        val containing = block.statements.firstOrNull { it === use || it.isAncestorOf(use) } ?: return false
        val useIndex = block.statements.indexOf(containing)
        return block.statements.take(useIndex).any { statement ->
            when (statement) {
                is IfStmt -> conditionTrueProvesNull(statement.condition, expression) &&
                    statementAlwaysExits(statement.thenStmt)
                is ExpressionStmt -> exactRequireNonNullExpression(statement, callable) == expression
                else -> false
            }
        }
    }

    private fun exactRequireNonNullExpression(
        statement: ExpressionStmt,
        callable: CallableDeclaration<*>
    ): String? {
        val call = statement.expression as? MethodCallExpr ?: return null
        if (call.nameAsString != "requireNonNull" || call.arguments.size != 1) return null
        val compilationUnit = callable.findCompilationUnit().orElse(null) ?: return null
        val exactOwner = when (call.scope.orElse(null)?.toString()?.replace(Regex("\\s+"), "")) {
            "java.util.Objects" -> true
            "Objects" -> compilationUnit.imports.any { import ->
                !import.isStatic &&
                    ((import.nameAsString == "java.util.Objects" && !import.isAsterisk) ||
                        (import.nameAsString == "java.util" && import.isAsterisk))
            }
            null -> compilationUnit.imports.any { import ->
                import.isStatic &&
                    ((import.nameAsString == "java.util.Objects.requireNonNull" && !import.isAsterisk) ||
                        (import.nameAsString == "java.util.Objects" && import.isAsterisk))
            }
            else -> false
        }
        return call.arguments.single().let(::normalizedExpression).takeIf { exactOwner }
    }

    fun isNullComparison(expression: BinaryExpr, expected: String): Boolean =
        expression.operator in setOf(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS) &&
            ((normalizedExpression(expression.left) == expected && expression.right is NullLiteralExpr) ||
                (normalizedExpression(expression.right) == expected && expression.left is NullLiteralExpr))

    private fun normalizedExpression(expression: Expression): String =
        expression.toString().replace(Regex("\\s+"), "")

    private fun conditionTrueProvesNonNull(expression: Expression, name: String): Boolean = when (expression) {
        is EnclosedExpr -> conditionTrueProvesNonNull(expression.inner, name)
        is UnaryExpr -> expression.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT &&
            conditionFalseProvesNonNull(expression.expression, name)
        is BinaryExpr -> when (expression.operator) {
            BinaryExpr.Operator.NOT_EQUALS -> isNullComparison(expression, name)
            BinaryExpr.Operator.AND -> conditionTrueProvesNonNull(expression.left, name) ||
                conditionTrueProvesNonNull(expression.right, name)
            else -> false
        }
        else -> false
    }

    private fun conditionFalseProvesNonNull(expression: Expression, name: String): Boolean = when (expression) {
        is EnclosedExpr -> conditionFalseProvesNonNull(expression.inner, name)
        is UnaryExpr -> expression.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT &&
            conditionTrueProvesNonNull(expression.expression, name)
        is BinaryExpr -> when (expression.operator) {
            BinaryExpr.Operator.EQUALS -> isNullComparison(expression, name)
            BinaryExpr.Operator.OR -> conditionFalseProvesNonNull(expression.left, name) ||
                conditionFalseProvesNonNull(expression.right, name)
            else -> false
        }
        else -> false
    }

    private fun conditionTrueProvesNull(expression: Expression, name: String): Boolean = when (expression) {
        is EnclosedExpr -> conditionTrueProvesNull(expression.inner, name)
        is UnaryExpr -> expression.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT &&
            conditionTrueProvesNonNull(expression.expression, name)
        is BinaryExpr -> expression.operator == BinaryExpr.Operator.EQUALS && isNullComparison(expression, name)
        else -> false
    }

    private fun statementAlwaysExits(statement: Statement): Boolean = when (statement) {
        is ReturnStmt, is ThrowStmt, is ContinueStmt, is BreakStmt -> true
        is BlockStmt -> statement.statements.lastOrNull()?.let(::statementAlwaysExits) == true
        else -> false
    }
}
