package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.TryStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Migrates direct tracking-chunk sends only when the supplier has an exact LevelChunk declaration. */
class LegacyDirectTrackingChunkTargetMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val migratedSources = linkedMapOf<Path, String>()
        val changes = mutableListOf<Change>()
        val files = Files.walk(srcDir).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }

        files.forEach { file ->
            val source = file.readText()
            if (!source.contains("PacketDistributor.TRACKING_CHUNK.with")) return@forEach
            val cu = parse(parser, source, file)
            if (cu.imports.none {
                    !it.isStatic && it.nameAsString == "net.neoforged.neoforge.network.PacketDistributor"
                }) {
                throw IllegalStateException(
                    "Direct tracking chunk target has no exact NeoForge PacketDistributor import in $file"
                )
            }
            LexicalPreservingPrinter.setup(cu)
            var rewrites = 0

            cu.findAll(MethodCallExpr::class.java).toList().forEach targetLoop@{ target ->
                if (!isTrackingChunkTarget(target)) return@targetLoop
                val lambda = target.arguments.singleOrNull() as? LambdaExpr ?: return@targetLoop
                val supplier = (lambda.body as? ExpressionStmt)?.expression
                    ?: throw IllegalStateException("Tracking chunk supplier must be an expression lambda in $file: $target")
                if (lambda.parameters.isNotEmpty()) {
                    throw IllegalStateException("Tracking chunk supplier unexpectedly declares parameters in $file: $target")
                }
                val send = target.parentNode.orElse(null) as? MethodCallExpr
                    ?: throw IllegalStateException("Direct tracking chunk target is not consumed by a channel send in $file: $target")
                if (send.nameAsString != "send" || send.arguments.size != 2 || send.arguments[0] !== target ||
                    !send.scope.isPresent) {
                    throw IllegalStateException(
                        "Direct tracking chunk target is not the first argument of a two-argument channel send in $file: $target"
                    )
                }
                val chunk = resolveLevelChunkExpression(supplier, target, cu, file)
                    ?: throw IllegalStateException(
                        "Cannot resolve tracking chunk supplier '$supplier' to an exact LevelChunk declaration in $file"
                    )
                rewriteSend(send, chunk)
                rewrites++
            }

            if (rewrites > 0) {
                cu.addImport("net.minecraft.server.level.ServerLevel")
                cu.addImport("net.neoforged.neoforge.network.PacketDistributor")
                migratedSources[file] = LexicalPreservingPrinter.print(cu)
                changes += Change(
                    file = file,
                    line = 1,
                    description = "Migrate direct tracking-chunk sends from an exactly typed LevelChunk supplier",
                    before = "channel.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), payload)",
                    after = "PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) chunk.getLevel(), chunk.getPos(), payload)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-packet-target-tracking-chunk-direct"
                )
            }
        }

        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changes
    }

    private fun isTrackingChunkTarget(call: MethodCallExpr): Boolean =
        call.nameAsString == "with" && call.arguments.size == 1 &&
            call.scope.orElse(null)?.toString() == "PacketDistributor.TRACKING_CHUNK"

    private fun resolveLevelChunkExpression(
        rawExpression: Expression,
        use: MethodCallExpr,
        cu: CompilationUnit,
        file: Path
    ): Expression? {
        val expression = unwrap(rawExpression)
        val imported = cu.imports.any {
            !it.isStatic && (
                it.nameAsString == "net.minecraft.world.level.chunk.LevelChunk" ||
                    it.isAsterisk && it.nameAsString == "net.minecraft.world.level.chunk"
                )
        }
        fun isLevelChunk(typeName: String): Boolean =
            typeName == "net.minecraft.world.level.chunk.LevelChunk" || typeName == "LevelChunk" && imported

        if (expression is FieldAccessExpr && expression.scope is ThisExpr) {
            val owner = use.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
                ?: throw IllegalStateException("Tracking chunk field access is outside a class in $file")
            val fields = owner.fields.flatMap { it.variables }.filter { it.nameAsString == expression.nameAsString }
            if (fields.size != 1) {
                throw IllegalStateException(
                    "Tracking chunk field '${expression.nameAsString}' is not uniquely declared in $file"
                )
            }
            return expression.clone().takeIf { isLevelChunk(fields.single().typeAsString) }
        }
        val name = expression as? NameExpr ?: return null
        val callable = use.findAncestor(CallableDeclaration::class.java).orElse(null)
            ?: throw IllegalStateException("Tracking chunk variable '${name.nameAsString}' is outside a callable in $file")
        val useBegin = use.range.orElse(null)?.begin
        val precedingLocals = callable.findAll(VariableDeclarator::class.java).filter { variable ->
            variable.nameAsString == name.nameAsString &&
                variable.range.orElse(null)?.begin?.let { begin -> useBegin == null || begin.isBefore(useBegin) } != false
        }
        val localVisibility = precedingLocals.associateWith { localVisibilityAt(it, use) }
        val unknownLocals = localVisibility.filterValues { it == null }.keys
        if (unknownLocals.isNotEmpty()) {
            throw IllegalStateException(
                "Tracking chunk variable '${name.nameAsString}' has an unsupported local declaration scope in $file"
            )
        }
        val locals = localVisibility.filterValues { it == true }.keys.toList()
        if (locals.size > 1) {
            throw IllegalStateException(
                "Tracking chunk variable '${name.nameAsString}' has multiple preceding local declarations in $file"
            )
        }
        if (locals.size == 1) return name.clone().takeIf { isLevelChunk(locals.single().typeAsString) }

        val parameters = callable.parameters.filter { it.nameAsString == name.nameAsString }
        if (parameters.size == 1) return name.clone().takeIf { isLevelChunk(parameters.single().typeAsString) }
        if (parameters.size > 1) {
            throw IllegalStateException("Tracking chunk parameter '${name.nameAsString}' is ambiguous in $file")
        }

        val owner = use.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: throw IllegalStateException("Tracking chunk variable '${name.nameAsString}' is outside a class in $file")
        val fields = owner.fields.flatMap { it.variables }.filter { it.nameAsString == name.nameAsString }
        if (fields.size > 1) {
            throw IllegalStateException("Tracking chunk field '${name.nameAsString}' is ambiguous in $file")
        }
        return fields.singleOrNull()?.let { field -> name.clone().takeIf { isLevelChunk(field.typeAsString) } }
    }

    private fun localVisibilityAt(variable: VariableDeclarator, use: MethodCallExpr): Boolean? {
        val declaration = variable.parentNode.orElse(null) as? VariableDeclarationExpr ?: return null
        val parent = declaration.parentNode.orElse(null) ?: return null
        val scope: Node = when (parent) {
            is ExpressionStmt -> parent.parentNode.orElse(null) as? BlockStmt ?: return null
            is ForStmt -> parent
            is ForEachStmt -> parent
            is TryStmt -> return if (containsNode(parent.tryBlock, use)) true else false
            else -> return null
        }
        return containsNode(scope, use)
    }

    private fun containsNode(ancestor: Node, node: Node): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun rewriteSend(send: MethodCallExpr, chunk: Expression) {
        val payload = send.arguments[1].clone()
        val level = MethodCallExpr(chunk.clone(), "getLevel")
        val position = MethodCallExpr(chunk.clone(), "getPos")
        send.setScope(NameExpr("PacketDistributor"))
        send.setName("sendToPlayersTrackingChunk")
        send.arguments.clear()
        send.addArgument(CastExpr(ClassOrInterfaceType(null, "ServerLevel"), level))
        send.addArgument(position)
        send.addArgument(payload)
    }

    private fun unwrap(expression: Expression): Expression =
        if (expression is EnclosedExpr) unwrap(expression.inner) else expression

    private fun parse(parser: JavaParser, source: String, file: Path): CompilationUnit {
        val result = parser.parse(source)
        return result.result.orElseThrow {
            IllegalStateException("Cannot parse direct tracking chunk target source $file: ${result.problems.joinToString()}")
        }
    }
}
