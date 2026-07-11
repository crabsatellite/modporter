package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Replaces legacy player PacketTarget values only when their complete local data flow is proven. */
class LegacyPacketTargetMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val changes = mutableListOf<Change>()
        val migratedSources = linkedMapOf<Path, String>()
        Files.walk(srcDir).filter { it.extension == "java" }.toList().forEach { file ->
            val source = file.readText()
            if (!source.contains("PacketDistributor.PLAYER.with")) return@forEach
            val parsed = parser.parse(source)
            val cu = parsed.result.orElseThrow {
                IllegalStateException("Cannot parse legacy packet target source $file: ${parsed.problems.joinToString()}")
            }
            LexicalPreservingPrinter.setup(cu)
            var rewrites = 0

            cu.findAll(VariableDeclarator::class.java).toList().forEach variableLoop@{ variable ->
                if (variable.typeAsString.substringAfterLast('.') != "PacketTarget") return@variableLoop
                val initializer = variable.initializer.orElse(null) ?: return@variableLoop
                val player = playerExpression(initializer) ?: return@variableLoop
                val callable = variable.findAncestor(CallableDeclaration::class.java).orElse(null)
                    ?: throw IllegalStateException("PacketTarget '${variable.nameAsString}' is outside a callable in $file")
                val sends = callable.findAll(MethodCallExpr::class.java).filter { call ->
                    call.nameAsString == "send" && call.arguments.size == 2 &&
                        (call.arguments[0] as? NameExpr)?.nameAsString == variable.nameAsString
                }
                val references = callable.findAll(NameExpr::class.java)
                    .filter { it.nameAsString == variable.nameAsString }
                if (references.size != sends.size) {
                    throw IllegalStateException(
                        "PacketTarget '${variable.nameAsString}' has non-send uses in $file; cannot preserve its distribution semantics"
                    )
                }
                if (sends.isEmpty()) {
                    throw IllegalStateException(
                        "PacketTarget '${variable.nameAsString}' has no source-proven channel sends in $file"
                    )
                }
                sends.forEach { rewritePlayerSend(it, player) }
                val declarationExpr = variable.parentNode.orElse(null)
                val declarationStatement = declarationExpr?.parentNode?.orElse(null) as? ExpressionStmt
                    ?: throw IllegalStateException("PacketTarget '${variable.nameAsString}' is not a standalone declaration in $file")
                declarationStatement.remove()
                rewrites += sends.size
            }

            cu.findAll(MethodCallExpr::class.java).toList().forEach callLoop@{ call ->
                if (call.nameAsString != "send" || call.arguments.size != 2) return@callLoop
                val player = playerExpression(call.arguments[0]) ?: return@callLoop
                rewritePlayerSend(call, player)
                rewrites++
            }

            val unresolvedTargets = cu.findAll(MethodCallExpr::class.java).filter {
                it.nameAsString == "with" && it.scope.orElse(null)?.toString() == "PacketDistributor.PLAYER"
            }
            if (unresolvedTargets.isNotEmpty()) {
                throw IllegalStateException(
                    "Unresolved legacy player PacketTarget expressions remain in $file: " +
                        unresolvedTargets.joinToString { it.toString() }
                )
            }

            if (rewrites == 0) return@forEach
            cu.imports.filter {
                !it.isStatic && it.nameAsString.endsWith(".PacketDistributor.PacketTarget")
            }.forEach { it.remove() }
            cu.addImport("net.neoforged.neoforge.network.PacketDistributor")
            val migrated = LexicalPreservingPrinter.print(cu)
            migratedSources[file] = migrated
            changes += Change(
                file = file,
                line = 1,
                description = "Inline legacy player PacketTarget sends into the 1.21 packet distributor API",
                before = "PacketDistributor.PLAYER.with(() -> player) + channel.send(target, payload)",
                after = "PacketDistributor.sendToPlayer(player, payload)",
                confidence = Confidence.HIGH,
                ruleId = "struct-packet-target-player-send"
            )
        }
        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changes
    }

    private fun playerExpression(expression: Expression): Expression? {
        val with = expression as? MethodCallExpr ?: return null
        if (with.nameAsString != "with" || with.scope.orElse(null)?.toString() != "PacketDistributor.PLAYER" ||
            with.arguments.size != 1) return null
        val lambda = with.arguments[0] as? LambdaExpr ?: return null
        val body = lambda.body as? ExpressionStmt ?: return null
        return body.expression.clone()
    }

    private fun rewritePlayerSend(call: MethodCallExpr, player: Expression) {
        val payload = call.arguments[1].clone()
        call.setScope(NameExpr("PacketDistributor"))
        call.setName("sendToPlayer")
        call.arguments.clear()
        call.addArgument(player.clone())
        call.addArgument(payload)
    }
}
