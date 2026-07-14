package com.modporter.core.transforms.structural

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Migrates exact legacy BlockEntity block-capability queries to Level queries. */
internal class ExactBlockEntityCapabilityQueryMigration(
    private val sourceRoot: Path,
    private val plannedLazyOptionalType: (() -> String)? = null
) {
    fun migrate(dryRun: Boolean): List<Change> {
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        val index = JavaProjectTypeIndex.build(sourceRoot)
        val projectLazyOptionalType = exactProjectLazyOptionalType(files, index)
        val changes = mutableListOf<Change>()
        val pendingWrites = mutableListOf<Pair<Path, String>>()
        files.forEach files@{ file ->
            val source = file.readText()
            if (!source.contains("getCapability") || !source.contains(".BLOCK")) return@files
            val unit = index.unit(file)
            val candidateCalls = unit.findAll(MethodCallExpr::class.java).filter { call ->
                call.nameAsString == "getCapability" &&
                    call.arguments.size in 1..2 &&
                    isExactBuiltInBlockCapability(call.arguments.first(), unit)
            }
            if (candidateCalls.isEmpty()) return@files
            LexicalPreservingPrinter.setup(unit)
            var migratedCalls = 0
            candidateCalls.toList().forEach calls@{ call ->
                val receiver = try {
                    exactBlockEntityReceiver(call, index)
                } catch (error: IllegalStateException) {
                    throw IllegalStateException("${error.message} in $file", error)
                } ?: return@calls
                val invalidation = directInvalidationCall(call)
                if (invalidation != null) {
                    invalidation.replace(
                        StaticJavaParser.parseExpression<Expression>(
                            "${receiver}.invalidateCapabilities()"
                        )
                    )
                    migratedCalls++
                    return@calls
                }
                val receiverSource = stableReceiverSource(receiver)
                    ?: throw IllegalStateException(
                        "Block capability query '$call' uses a receiver that cannot be evaluated twice safely"
                    )
                val lazyOptionalType = exactLazyOptionalType(unit) ?: projectLazyOptionalType
                    ?: exactPlannedLazyOptionalType()
                    ?: throw IllegalStateException(
                        "Cannot preserve legacy LazyOptional semantics for .BLOCK capability queries in $file"
                    )
                val context = call.arguments.getOrNull(1)?.toString() ?: "null"
                val capability = call.arguments.first().toString()
                val levelName = uniqueLevelName(call)
                val levelQuery =
                    "java.util.Optional.ofNullable($receiverSource.getLevel())" +
                        ".map($levelName -> $levelName.getCapability(" +
                        "$capability, $receiverSource.getBlockPos(), $context)).orElse(null)"
                call.replace(
                    StaticJavaParser.parseExpression<Expression>(
                        "$lazyOptionalType.ofNullable($levelQuery)"
                    )
                )
                migratedCalls++
            }
            if (migratedCalls == 0) return@files
            val migrated = LexicalPreservingPrinter.print(unit)
            pendingWrites += file to migrated
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate exact BlockEntity block-capability queries to Level capability queries",
                before = "blockEntity.getCapability(BlockCapability, context)",
                after = "LazyOptional.ofNullable(blockEntity.getLevel().getCapability(...))",
                confidence = Confidence.HIGH,
                ruleId = "struct-exact-blockentity-capability-query"
            )
        }
        if (!dryRun) {
            pendingWrites.forEach { (file, migrated) -> file.writeText(migrated) }
        }
        return changes
    }

    private fun exactPlannedLazyOptionalType(): String? {
        val type = plannedLazyOptionalType?.invoke() ?: return null
        require(type.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.LazyOptional"""))) {
            "Invalid planned generated LazyOptional type '$type'"
        }
        return type
    }

    private fun directInvalidationCall(query: MethodCallExpr): MethodCallExpr? {
        val invalidation = query.parentNode.orElse(null) as? MethodCallExpr ?: return null
        if (invalidation.nameAsString != "invalidate" || invalidation.arguments.isNotEmpty()) return null
        return invalidation.takeIf { it.scope.orElse(null) === query }
    }

    private fun exactBlockEntityReceiver(
        call: MethodCallExpr,
        index: JavaProjectTypeIndex
    ): Expression? {
        val scope = call.scope.orElse(null)
        if (scope == null) {
            val owner = exactEnclosingNamedClass(call)?.fullyQualifiedName?.orElse(null)
                ?: throw IllegalStateException("Cannot resolve implicit block capability receiver for '$call'")
            if (!index.isTypeAssignableTo(owner, BLOCK_ENTITY_TYPE)) {
                throw IllegalStateException(
                    "Implicit .BLOCK capability query '$call' is not enclosed by a BlockEntity"
                )
            }
            return ThisExpr()
        }
        val receiverType = index.expressionType(scope, call)
        if (receiverType == null &&
            scope is NameExpr &&
            isExactClassLiteralBoundBlockEntityLambdaReceiver(scope, call, index)
        ) {
            return scope
        }
        if (receiverType == null) return null
        if (!index.isTypeAssignableTo(receiverType, BLOCK_ENTITY_TYPE)) {
            if (receiverType in NON_BLOCK_ENTITY_CAPABILITY_RECEIVERS) return null
            throw IllegalStateException(
                ".BLOCK capability receiver '$scope' has type '$receiverType', which is not proven to be a BlockEntity"
            )
        }
        return scope
    }

    private fun isExactClassLiteralBoundBlockEntityLambdaReceiver(
        receiver: NameExpr,
        query: MethodCallExpr,
        index: JavaProjectTypeIndex
    ): Boolean {
        val lambda = query.findAncestor(LambdaExpr::class.java).orElse(null) ?: return false
        val matchingParameters = lambda.parameters.filter { it.nameAsString == receiver.nameAsString }
        if (matchingParameters.size != 1) return false
        val parentCall = lambda.parentNode.orElse(null) as? MethodCallExpr ?: return false
        val lambdaIndex = parentCall.arguments.indexOfFirst { it === lambda }
        if (lambdaIndex < 0) return false
        val classLiterals = parentCall.arguments.take(lambdaIndex).filterIsInstance<ClassExpr>()
        if (classLiterals.size != 1) return false
        val type = index.declaredRawType(classLiterals.single().type, classLiterals.single()) ?: return false
        return index.isTypeAssignableTo(type, BLOCK_ENTITY_TYPE)
    }

    private fun stableReceiverSource(expression: Expression): String? = when (expression) {
        is NameExpr, is ThisExpr -> expression.toString()
        is FieldAccessExpr -> stableReceiverSource(expression.scope)?.let { "$it.${expression.nameAsString}" }
        else -> null
    }

    private fun uniqueLevelName(call: MethodCallExpr): String {
        val scope = call.findAncestor(CallableDeclaration::class.java).orElse(null)
            ?: call.findCompilationUnit().orElseThrow()
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
            .findAll(scope.toString())
            .map { it.value }
            .toSet()
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "modporterLevel" else "modporterLevel$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private fun exactLazyOptionalType(unit: CompilationUnit): String? {
        val candidates = unit.imports.filterNot { it.isStatic }.map { it.nameAsString }.filter { imported ->
            imported == "net.minecraftforge.common.util.LazyOptional" ||
                imported == "net.neoforged.neoforge.common.util.LazyOptional" ||
                imported.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.LazyOptional"""))
        }.distinct()
        if (candidates.size > 1) {
            throw IllegalStateException("Ambiguous LazyOptional imports: $candidates")
        }
        return candidates.singleOrNull()
    }

    private fun exactProjectLazyOptionalType(
        files: List<Path>,
        index: JavaProjectTypeIndex
    ): String? {
        val candidates = files.filter { it.fileName.toString() == "LazyOptional.java" }.mapNotNull { file ->
            val declaration = index.unit(file).findAll(ClassOrInterfaceDeclaration::class.java).singleOrNull { type ->
                type.nameAsString == "LazyOptional" &&
                    type.methods.any { method ->
                        method.nameAsString == "ofNullable" && method.isStatic && method.parameters.size == 1
                    }
            } ?: return@mapNotNull null
            declaration.fullyQualifiedName.orElse(null)?.takeIf { type ->
                type.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.LazyOptional"""))
            }
        }.distinct()
        if (candidates.size > 1) {
            throw IllegalStateException("Ambiguous generated LazyOptional adapters: $candidates")
        }
        return candidates.singleOrNull()
    }

    private fun isExactBuiltInBlockCapability(expression: Expression, unit: CompilationUnit): Boolean {
        val segments = qualifiedSegments(expression)
        if (segments.size < 2 || segments.last() != "BLOCK") return false
        if (segments.first() == "net" &&
            segments.dropLast(1).joinToString(".").startsWith(NEOFORGE_CAPABILITIES)
        ) {
            return true
        }
        val first = segments.first()
        return unit.imports.filterNot { it.isStatic }.map { it.nameAsString }.any { imported ->
            when (first) {
                "Capabilities" -> imported == NEOFORGE_CAPABILITIES ||
                    imported.matches(Regex("""com\.modporter\.generated\.[\w.]+\.compat\.Capabilities"""))
                "ItemHandler", "FluidHandler", "EnergyStorage" ->
                    imported == "$NEOFORGE_CAPABILITIES.$first" ||
                        imported.matches(
                            Regex("""com\.modporter\.generated\.[\w.]+\.compat\.Capabilities\.$first""")
                        )
                else -> false
            }
        }
    }

    private fun qualifiedSegments(expression: Expression): List<String> = when (expression) {
        is NameExpr -> listOf(expression.nameAsString)
        is FieldAccessExpr -> qualifiedSegments(expression.scope) + expression.nameAsString
        else -> emptyList()
    }

    private companion object {
        const val BLOCK_ENTITY_TYPE = "net.minecraft.world.level.block.entity.BlockEntity"
        const val NEOFORGE_CAPABILITIES = "net.neoforged.neoforge.capabilities.Capabilities"
        val NON_BLOCK_ENTITY_CAPABILITY_RECEIVERS = setOf(
            "java.lang.Object",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.server.level.ServerPlayer"
        )
    }
}
