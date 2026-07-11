package com.modporter.core.transforms.text

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt

/** Replaces removed Forge item tag managers with exact vanilla registry holder operations. */
class LegacyTagManagerMigration {
    private data class Manager(
        val name: String,
        val declaration: ExpressionStmt,
        val callable: CallableDeclaration<*>,
        val registry: String,
        val valueType: String
    )

    fun migrate(source: String): String {
        val executable = maskCommentsAndLiterals(source)
        if (!executable.contains("ITagManager<")) return source
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = parser.parse(source)
        val cu = parsed.result.orElseThrow {
            IllegalStateException("Cannot parse removed ITagManager source: ${parsed.problems.joinToString()}")
        }
        requireExactImports(cu)
        val managers = collectManagers(cu)
        if (managers.isEmpty()) {
            throw IllegalStateException("ITagManager is executable but has no exact BuiltInRegistries declaration")
        }
        managers.forEach { migrateManager(it) }

        var result = cu.toString()
        val remaining = executableBody(result)
        if (Regex("""\bITagManager\b""").containsMatchIn(remaining)) {
            throw IllegalStateException("Unsupported ITagManager usage remains after structural migration")
        }
        result = removeImport(result, "net.neoforged.neoforge.registries.tags.ITagManager")
        if (!Regex("""\bITag\b""").containsMatchIn(executableBody(result))) {
            result = removeImport(result, "net.neoforged.neoforge.registries.tags.ITag")
        }
        return result
    }

    private fun requireExactImports(cu: CompilationUnit) {
        val imports = cu.imports.filter { !it.isStatic }.map { it.nameAsString }.toSet()
        if ("net.neoforged.neoforge.registries.tags.ITagManager" !in imports ||
            "net.minecraft.core.registries.BuiltInRegistries" !in imports ||
            "net.minecraft.world.item.Item" !in imports) {
            throw IllegalStateException("ITagManager migration lacks exact item registry API imports")
        }
    }

    private fun collectManagers(cu: CompilationUnit): List<Manager> =
        cu.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
            if (variable.typeAsString.replace(" ", "") != "ITagManager<Item>") return@mapNotNull null
            val initializer = variable.initializer.orElse(null) as? MethodCallExpr ?: return@mapNotNull null
            if (initializer.nameAsString != "tags" || initializer.arguments.isNotEmpty() ||
                initializer.scope.orElse(null)?.toString() != "BuiltInRegistries.ITEM") {
                throw IllegalStateException(
                    "ITagManager '${variable.nameAsString}' is not initialized from exact BuiltInRegistries.ITEM.tags()"
                )
            }
            val declarationExpr = variable.parentNode.orElse(null) as? VariableDeclarationExpr
                ?: throw IllegalStateException("ITagManager '${variable.nameAsString}' is not a local declaration")
            val statement = declarationExpr.parentNode.orElse(null) as? ExpressionStmt
                ?: throw IllegalStateException("ITagManager '${variable.nameAsString}' is not a statement local")
            val callable = variable.findAncestor(CallableDeclaration::class.java).orElse(null)
                ?: throw IllegalStateException("ITagManager '${variable.nameAsString}' is outside a callable")
            Manager(variable.nameAsString, statement, callable, "BuiltInRegistries.ITEM", "Item")
        }

    private fun migrateManager(manager: Manager) {
        migrateMaterializedTags(manager)
        migrateKnownOrEmptyReturns(manager)
        migrateManagerCalls(manager)
        removeNullGuards(manager)

        val remaining = manager.callable.findAll(NameExpr::class.java)
            .filter { it.nameAsString == manager.name }
        if (remaining.isNotEmpty()) {
            throw IllegalStateException(
                "Unsupported ITagManager '${manager.name}' references remain: ${remaining.joinToString { it.toString() }}"
            )
        }
        manager.declaration.remove()
    }

    private fun migrateMaterializedTags(manager: Manager) {
        manager.callable.findAll(VariableDeclarator::class.java).toList().forEach { variable ->
            if (variable.typeAsString.replace(" ", "") != "ITag<Item>") return@forEach
            val getTag = variable.initializer.orElse(null) as? MethodCallExpr ?: return@forEach
            if (!isManagerCall(getTag, manager, "getTag") || getTag.arguments.size != 1) return@forEach
            requireReadOnlyMaterializedTagUses(variable, manager.callable)
            val tag = getTag.arguments.single().toString()
            variable.setType(StaticJavaParser.parseType("java.util.List<Item>"))
            variable.setInitializer(
                StaticJavaParser.parseExpression<Expression>(
                    "java.util.stream.StreamSupport.stream(${manager.registry}.getTagOrEmpty($tag).spliterator(), false)" +
                        ".map(net.minecraft.core.Holder::value).toList()"
                )
            )
        }
    }

    private fun requireReadOnlyMaterializedTagUses(
        variable: VariableDeclarator,
        callable: CallableDeclaration<*>
    ) {
        val unsupported = callable.findAll(NameExpr::class.java)
            .filter { it.nameAsString == variable.nameAsString }
            .filterNot { reference ->
                when (val parent = reference.parentNode.orElse(null)) {
                    is MethodCallExpr -> parent.scope.orElse(null) === reference &&
                        parent.arguments.isEmpty() && parent.nameAsString in setOf("isEmpty", "size")
                    is ForEachStmt -> parent.iterable === reference
                    else -> false
                }
            }
        if (unsupported.isNotEmpty()) {
            throw IllegalStateException(
                "ITag '${variable.nameAsString}' has unsupported non-read-only uses: " +
                    unsupported.joinToString { it.parentNode.orElse(null).toString() }
            )
        }
    }

    private fun migrateKnownOrEmptyReturns(manager: Manager) {
        manager.callable.findAll(ReturnStmt::class.java).toList().forEach { statement ->
            val expression = statement.expression.orElse(null) as? BinaryExpr ?: return@forEach
            if (expression.operator != BinaryExpr.Operator.OR) return@forEach
            val left = expression.left as? UnaryExpr ?: return@forEach
            if (left.operator != UnaryExpr.Operator.LOGICAL_COMPLEMENT) return@forEach
            val known = left.expression as? MethodCallExpr ?: return@forEach
            val empty = expression.right as? MethodCallExpr ?: return@forEach
            val getTag = empty.scope.orElse(null) as? MethodCallExpr ?: return@forEach
            if (!isManagerCall(known, manager, "isKnownTagName") || known.arguments.size != 1 ||
                empty.nameAsString != "isEmpty" || empty.arguments.isNotEmpty() ||
                !isManagerCall(getTag, manager, "getTag") || getTag.arguments.size != 1) {
                return@forEach
            }
            val knownTag = known.arguments.single().toString()
            val valueTag = getTag.arguments.single().toString()
            if (knownTag != valueTag) {
                throw IllegalStateException("ITagManager known/empty predicate uses different tag expressions")
            }
            statement.setExpression(
                StaticJavaParser.parseExpression<Expression>(
                    "!${manager.registry}.getTagOrEmpty($knownTag).iterator().hasNext()"
                )
            )
        }
    }

    private fun migrateManagerCalls(manager: Manager) {
        val calls = manager.callable.findAll(MethodCallExpr::class.java).toList()
            .filter { (it.scope.orElse(null) as? NameExpr)?.nameAsString == manager.name }
        calls.forEach { call ->
            when (call.nameAsString) {
                "isKnownTagName" -> {
                    if (call.arguments.size != 1) unsupported(call)
                    val tag = call.arguments.single().toString()
                    call.replace(
                        StaticJavaParser.parseExpression<Expression>(
                            "${manager.registry}.getTag($tag).isPresent()"
                        )
                    )
                }
                "getTag" -> migrateGetTagCall(call, manager)
                else -> unsupported(call)
            }
        }
    }

    private fun migrateGetTagCall(call: MethodCallExpr, manager: Manager) {
        if (call.arguments.size != 1) unsupported(call)
        val tag = call.arguments.single().toString()
        val registryTags = StaticJavaParser.parseExpression<Expression>(
            "${manager.registry}.getTagOrEmpty($tag)"
        )
        val parent = call.parentNode.orElse(null) as? MethodCallExpr ?: unsupported(call)
        when (parent.nameAsString) {
            "isEmpty" -> {
                if (parent.arguments.isNotEmpty() || parent.scope.orElse(null) !== call) unsupported(call)
                parent.replace(
                    StaticJavaParser.parseExpression<Expression>(
                        "!${manager.registry}.getTagOrEmpty($tag).iterator().hasNext()"
                    )
                )
            }
            "forEach" -> {
                if (parent.scope.orElse(null) !== call) unsupported(call)
                migrateForEachLambda(parent, manager.valueType)
                call.replace(registryTags)
            }
            "stream" -> {
                if (parent.arguments.isNotEmpty() || parent.scope.orElse(null) !== call) unsupported(call)
                val forEach = parent.parentNode.orElse(null) as? MethodCallExpr ?: unsupported(call)
                if (forEach.nameAsString != "forEach" || forEach.scope.orElse(null) !== parent) unsupported(call)
                migrateForEachLambda(forEach, manager.valueType)
                parent.replace(registryTags)
            }
            else -> unsupported(call)
        }
    }

    private fun migrateForEachLambda(forEach: MethodCallExpr, valueType: String) {
        val lambda = forEach.arguments.singleOrNull() as? LambdaExpr ?: unsupported(forEach)
        if (lambda.parameters.size != 1) unsupported(forEach)
        val valueName = lambda.parameters.single().nameAsString
        val occupied = lambda.findAll(NameExpr::class.java).map { it.nameAsString }.toMutableSet()
        var holderName = "${valueName}Holder"
        var suffix = 2
        while (holderName in occupied) holderName = "${valueName}Holder${suffix++}"

        val holderParameter = Parameter(
            StaticJavaParser.parseType("net.minecraft.core.Holder<$valueType>"),
            holderName
        )
        lambda.setParameter(0, holderParameter)
        lambda.isEnclosingParameters = true
        val body = when (val originalBody = lambda.body) {
            is ExpressionStmt -> BlockStmt().addStatement(originalBody.clone())
            is BlockStmt -> originalBody.clone()
            else -> unsupported(forEach)
        }
        body.addStatement(
            0,
            StaticJavaParser.parseStatement("$valueType $valueName = $holderName.value();")
        )
        lambda.setBody(body)
    }

    private fun removeNullGuards(manager: Manager) {
        manager.callable.findAll(IfStmt::class.java).toList().forEach { statement ->
            if (statement.elseStmt.isPresent || !isManagerNotNull(statement.condition, manager.name)) return@forEach
            statement.replace(statement.thenStmt.clone())
        }
    }

    private fun isManagerNotNull(expression: Expression, managerName: String): Boolean {
        val binary = expression as? BinaryExpr ?: return false
        if (binary.operator != BinaryExpr.Operator.NOT_EQUALS) return false
        return binary.left is NameExpr && (binary.left as NameExpr).nameAsString == managerName &&
            binary.right is NullLiteralExpr ||
            binary.right is NameExpr && (binary.right as NameExpr).nameAsString == managerName &&
            binary.left is NullLiteralExpr
    }

    private fun isManagerCall(call: MethodCallExpr, manager: Manager, name: String): Boolean =
        call.nameAsString == name &&
            (call.scope.orElse(null) as? NameExpr)?.nameAsString == manager.name

    private fun unsupported(node: Any): Nothing =
        throw IllegalStateException("Unsupported ITagManager operation: $node")

    private fun removeImport(source: String, importName: String): String =
        Regex("""(?m)^[ \t]*import\s+${Regex.escape(importName)};\s*\r?\n?""").replace(source, "")

    private fun executableBody(source: String): String = maskCommentsAndLiterals(source).lines()
        .filterNot { it.trimStart().startsWith("import ") }
        .joinToString("\n")

    private fun maskCommentsAndLiterals(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        var state = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> { out.setCharAt(i, ' '); out.setCharAt(i + 1, ' '); i += 2; state = 1; continue }
                    c == '/' && next == '*' -> { out.setCharAt(i, ' '); out.setCharAt(i + 1, ' '); i += 2; state = 2; continue }
                    c == '"' && source.startsWith("\"\"\"", i) -> { repeat(3) { out.setCharAt(i + it, ' ') }; i += 3; state = 5; continue }
                    c == '"' -> { out.setCharAt(i, ' '); i++; state = 3; continue }
                    c == '\'' -> { out.setCharAt(i, ' '); i++; state = 4; continue }
                }
                1 -> if (c == '\n' || c == '\r') state = 0 else out.setCharAt(i, ' ')
                2 -> { if (c == '*' && next == '/') { out.setCharAt(i, ' '); out.setCharAt(i + 1, ' '); i += 2; state = 0; continue }; if (c != '\n' && c != '\r') out.setCharAt(i, ' ') }
                3, 4 -> { if (c == '\\') { out.setCharAt(i, ' '); if (next != null && next != '\n' && next != '\r') out.setCharAt(i + 1, ' '); i += 2; continue }; if ((state == 3 && c == '"') || (state == 4 && c == '\'')) state = 0; if (c != '\n' && c != '\r') out.setCharAt(i, ' ') }
                5 -> { if (source.startsWith("\"\"\"", i)) { repeat(3) { out.setCharAt(i + it, ' ') }; i += 3; state = 0; continue }; if (c != '\n' && c != '\r') out.setCharAt(i, ' ') }
            }
            i++
        }
        return out.toString()
    }
}
