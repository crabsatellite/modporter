package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.SwitchEntry
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * Migrates legacy live ItemStack/FluidStack CompoundTag access only when the
 * complete local use graph proves whether the tag is read or mutated.
 */
internal class ExactLegacyCustomDataMigration {
    private enum class ReceiverKind {
        ITEM_STACK,
        FLUID_STACK
    }

    private sealed interface FilePlan

    private data class AliasPlan(
        val call: MethodCallExpr,
        val variable: VariableDeclarator,
        val receiver: NameExpr,
        val mutationStatements: List<ExpressionStmt>
    ) : FilePlan

    private sealed interface DirectPlan : FilePlan {
        val call: MethodCallExpr
    }

    private data class DirectReadPlan(
        override val call: MethodCallExpr,
        val receiver: Expression
    ) : DirectPlan

    private data class DirectMutationPlan(
        override val call: MethodCallExpr,
        val receiver: Expression,
        val kind: ReceiverKind,
        val statement: ExpressionStmt,
        val tagName: String,
        val tagExpression: String
    ) : DirectPlan

    private data class AliasUse(
        val supported: Boolean,
        val mutationStatement: ExpressionStmt? = null
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { it.readText().contains(".getOrCreateTag(") }
                .toList()
        }
        if (files.isEmpty()) return emptyList()

        val index = JavaProjectTypeIndex.build(sourceRoot)
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val migratedFiles = linkedMapOf<Path, String>()

        files.forEach { file ->
            val cu = index.unit(file)
            LexicalPreservingPrinter.setup(cu)
            val exact = ExactJavaSemantics(cu)
            val kinds = linkedMapOf<MethodCallExpr, ReceiverKind>()
            val calls = cu.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == "getOrCreateTag" && it.arguments.isEmpty() && it.scope.isPresent }
                .filter { call ->
                    receiverKind(index, call.scope.get(), call)?.also { kinds[call] = it } != null
                }
            if (calls.isEmpty()) return@forEach

            val aliasPlans = mutableListOf<AliasPlan>()
            val directPlans = mutableListOf<DirectPlan>()
            var complete = true
            calls.forEach { call ->
                val kind = kinds.getValue(call)
                val variable = call.parentNode.orElse(null) as? VariableDeclarator
                val plan: FilePlan? = if (variable != null && variable.initializer.orElse(null) === call) {
                    planAlias(call, variable, exact)
                } else {
                    planDirect(call, kind, exact)
                }
                when (plan) {
                    is AliasPlan -> aliasPlans += plan
                    is DirectPlan -> directPlans += plan
                    else -> complete = false
                }
            }
            if (!complete) return@forEach

            aliasPlans.forEach { plan ->
                plan.call.replace(StaticJavaParser.parseExpression(detachedTag(plan.receiver)))
                plan.mutationStatements
                    .distinctBy { System.identityHashCode(it) }
                    .sortedByDescending {
                        it.range.map { range -> range.begin.line * 10000 + range.begin.column }
                            .orElse(-1)
                    }
                    .forEach { statement ->
                        insertAfter(
                            statement,
                            writeBackStatement(plan.receiver.toString(), plan.variable.nameAsString)
                        )
                    }
            }
            directPlans.forEach { plan ->
                when (plan) {
                    is DirectReadPlan ->
                        plan.call.replace(StaticJavaParser.parseExpression(detachedTag(plan.receiver)))
                    is DirectMutationPlan -> {
                        val migratedExpression = when (plan.kind) {
                            ReceiverKind.ITEM_STACK ->
                                "$CUSTOM_DATA.update($DATA_COMPONENTS.CUSTOM_DATA, " +
                                    "${plan.receiver}, ${plan.tagName} -> ${plan.tagExpression})"
                            ReceiverKind.FLUID_STACK ->
                                "net.minecraft.Util.make(${detachedTag(plan.receiver)}, ${plan.tagName} -> { " +
                                    "${plan.tagExpression}; " +
                                    "${writeBackCode(plan.receiver.toString(), plan.tagName)} })"
                        }
                        plan.statement.setExpression(StaticJavaParser.parseExpression(migratedExpression))
                    }
                }
            }

            val migrated = LexicalPreservingPrinter.print(cu)
            val verified = parser.parse(migrated)
            if (!verified.isSuccessful) {
                throw IllegalStateException(
                    "Exact legacy custom-data migration produced invalid Java in $file: " +
                        verified.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            migratedFiles[file] = migrated
        }

        if (!dryRun) migratedFiles.forEach { (file, source) -> file.writeText(source) }
        return migratedFiles.keys.map { file ->
            Change(
                file = file,
                line = 1,
                description = "Migrate exact legacy live custom-data access with explicit write-back",
                before = "ItemStack/FluidStack.getOrCreateTag() local data flow",
                after = "CUSTOM_DATA snapshot plus proven mutation write-back",
                confidence = Confidence.HIGH,
                ruleId = "struct-exact-legacy-custom-data-graph"
            )
        }
    }

    private fun planAlias(
        call: MethodCallExpr,
        variable: VariableDeclarator,
        exact: ExactJavaSemantics
    ): AliasPlan? {
        if (!exact.exactTypeReference(
                variable.typeAsString,
                "CompoundTag",
                "net.minecraft.nbt.CompoundTag"
            )
        ) {
            return null
        }
        val receiver = call.scope.get() as? NameExpr ?: return null
        if (!isStableReceiver(receiver, call, exact)) return null

        val uses = exact.referencesTo(variable)
        val classified = uses.map { classifyAliasUse(it, exact) }
        if (classified.any { !it.supported }) return null
        return AliasPlan(
            call = call,
            variable = variable,
            receiver = receiver,
            mutationStatements = classified.mapNotNull { it.mutationStatement }
        )
    }

    private fun planDirect(
        call: MethodCallExpr,
        kind: ReceiverKind,
        exact: ExactJavaSemantics
    ): DirectPlan? {
        val receiver = call.scope.get()
        val rooted = rootedCall(call)
        if (rooted != null && rooted !== call) {
            return when {
                rooted.nameAsString in MUTATOR_METHODS -> {
                    val statement = standaloneStatement(rooted) ?: return null
                    if (kind == ReceiverKind.FLUID_STACK &&
                        (receiver !is NameExpr || !isStableReceiver(receiver, call, exact))
                    ) {
                        return null
                    }
                    val tagName = uniqueName(call, "modPorterCustomDataTag")
                    DirectMutationPlan(
                        call,
                        receiver,
                        kind,
                        statement,
                        tagName,
                        expressionWithTag(rooted, call, tagName)
                    )
                }
                rooted.nameAsString in SAFE_READ_METHODS -> DirectReadPlan(call, receiver)
                else -> null
            }
        }

        val parentCall = call.parentNode.orElse(null) as? MethodCallExpr
        if (parentCall != null && parentCall.arguments.any { it === call }) {
            val argumentIndex = parentCall.arguments.indexOfIdentity(call)
            val externalEffect = ExactExternalTagContracts.compoundTagArgumentEffect(
                parentCall,
                argumentIndex,
                exact
            )
            return when {
                externalEffect == ExactExternalTagContracts.Effect.READ ||
                    isExactPotionUtilsRead(parentCall, exact) -> DirectReadPlan(call, receiver)
                externalEffect == ExactExternalTagContracts.Effect.MUTATE -> {
                    val statement = standaloneStatement(parentCall) ?: return null
                    if (kind == ReceiverKind.FLUID_STACK &&
                        (receiver !is NameExpr || !isStableReceiver(receiver, call, exact))
                    ) {
                        return null
                    }
                    val tagName = uniqueName(call, "modPorterCustomDataTag")
                    DirectMutationPlan(
                        call,
                        receiver,
                        kind,
                        statement,
                        tagName,
                        expressionWithTag(parentCall, call, tagName)
                    )
                }
                else -> null
            }
        }
        return null
    }

    private fun classifyAliasUse(use: NameExpr, exact: ExactJavaSemantics): AliasUse {
        val rooted = rootedCall(use)
        if (rooted != null) {
            return when {
                rooted.nameAsString in MUTATOR_METHODS ->
                    AliasUse(true, standaloneStatement(rooted) ?: return AliasUse(false))
                rooted.nameAsString in SAFE_READ_METHODS -> AliasUse(true)
                else -> AliasUse(false)
            }
        }

        val parentCall = use.parentNode.orElse(null) as? MethodCallExpr
        if (parentCall != null && parentCall.arguments.any { it === use }) {
            val argumentIndex = parentCall.arguments.indexOfIdentity(use)
            val externalEffect = ExactExternalTagContracts.compoundTagArgumentEffect(
                parentCall,
                argumentIndex,
                exact
            )
            return when {
                externalEffect == ExactExternalTagContracts.Effect.READ ||
                    isExactPotionUtilsRead(parentCall, exact) -> AliasUse(true)
                externalEffect == ExactExternalTagContracts.Effect.MUTATE ->
                    AliasUse(true, standaloneStatement(parentCall) ?: return AliasUse(false))
                else -> AliasUse(false)
            }
        }
        return AliasUse(false)
    }

    private fun rootedCall(root: Node): MethodCallExpr? {
        var current = root.parentNode.orElse(null) as? MethodCallExpr ?: return null
        if (!current.scope.map { it === root }.orElse(false)) return null
        while (true) {
            val parent = current.parentNode.orElse(null) as? MethodCallExpr ?: return current
            if (!parent.scope.map { it === current }.orElse(false)) return current
            current = parent
        }
    }

    private fun standaloneStatement(expression: Expression): ExpressionStmt? {
        val statement = expression.parentNode.orElse(null) as? ExpressionStmt ?: return null
        if (statement.expression !== expression) return null
        return statement.takeIf {
            it.parentNode.orElse(null) is BlockStmt || it.parentNode.orElse(null) is SwitchEntry
        }
    }

    private fun expressionWithTag(
        expression: MethodCallExpr,
        originalCall: MethodCallExpr,
        tagName: String
    ): String {
        val clone = expression.clone()
        val originalRange = originalCall.range.orElse(null)
        val candidates = clone.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "getOrCreateTag" &&
                it.arguments.isEmpty() &&
                (originalRange == null || it.range.orElse(null) == originalRange)
        }
        val target = candidates.singleOrNull() ?: throw IllegalStateException(
            "Cannot bind exact getOrCreateTag node inside '$expression'"
        )
        target.replace(NameExpr(tagName))
        return clone.toString()
    }

    private fun isStableReceiver(
        receiver: NameExpr,
        context: Node,
        exact: ExactJavaSemantics
    ): Boolean {
        val declaration = exact.declarationOf(receiver)
        if (declaration !is Parameter && declaration !is VariableDeclarator) return false
        if (declaration is VariableDeclarator &&
            declaration.parentNode.orElse(null) is FieldDeclaration
        ) {
            return false
        }
        val callable = context.findAncestor(CallableDeclaration::class.java).orElse(null)
            ?: return false
        val reassigned = callable.findAll(AssignExpr::class.java).any { assignment ->
            val target = assignment.target as? NameExpr ?: return@any false
            exact.declarationOf(target) === declaration
        }
        if (reassigned) return false
        return callable.findAll(UnaryExpr::class.java).none { unary ->
            unary.operator in setOf(
                UnaryExpr.Operator.PREFIX_INCREMENT,
                UnaryExpr.Operator.PREFIX_DECREMENT,
                UnaryExpr.Operator.POSTFIX_INCREMENT,
                UnaryExpr.Operator.POSTFIX_DECREMENT
            ) && (unary.expression as? NameExpr)?.let {
                exact.declarationOf(it) === declaration
            } == true
        }
    }

    private fun receiverKind(
        index: JavaProjectTypeIndex,
        receiver: Expression,
        context: Node
    ): ReceiverKind? = when {
        index.isExpressionAssignableTo(receiver, context, ITEM_STACK) -> ReceiverKind.ITEM_STACK
        FLUID_STACKS.any { index.isExpressionAssignableTo(receiver, context, it) } ->
            ReceiverKind.FLUID_STACK
        else -> null
    }

    private fun isExactPotionUtilsRead(
        call: MethodCallExpr,
        exact: ExactJavaSemantics
    ): Boolean =
        call.nameAsString in POTION_UTILS_READS &&
            call.scope.map {
                exact.exactStaticScope(it, "PotionUtils", POTION_UTILS)
            }.orElse(false)

    private fun detachedTag(receiver: Expression): String =
        "($receiver).getOrDefault($DATA_COMPONENTS.CUSTOM_DATA, $CUSTOM_DATA.EMPTY).copyTag()"

    private fun writeBackStatement(receiver: String, tagName: String): Statement =
        StaticJavaParser.parseStatement(writeBackCode(receiver, tagName))

    private fun writeBackCode(receiver: String, tagName: String): String =
        "if (($tagName).isEmpty()) { ($receiver).remove($DATA_COMPONENTS.CUSTOM_DATA); } " +
            "else { ($receiver).set($DATA_COMPONENTS.CUSTOM_DATA, $CUSTOM_DATA.of($tagName)); }"

    private fun insertAfter(anchor: ExpressionStmt, statement: Statement) {
        when (val parent = anchor.parentNode.orElse(null)) {
            is BlockStmt -> {
                val index = parent.statements.indexOfIdentity(anchor)
                if (index < 0) throw IllegalStateException("Cannot locate custom-data mutation statement")
                parent.statements.add(index + 1, statement)
            }
            is SwitchEntry -> {
                val index = parent.statements.indexOfIdentity(anchor)
                if (index < 0) throw IllegalStateException("Cannot locate switch custom-data mutation statement")
                parent.statements.add(index + 1, statement)
            }
            else -> throw IllegalStateException("Custom-data mutation is outside an ordered statement list")
        }
    }

    private fun <T : Node> List<T>.indexOfIdentity(target: T): Int =
        indexOfFirst { it === target }

    private fun uniqueName(node: Node, base: String): String {
        val cu = node.findCompilationUnit().orElse(null) ?: return base
        return uniqueName(cu, base)
    }

    private fun uniqueName(cu: CompilationUnit, base: String): String {
        val used = cu.findAll(SimpleName::class.java).mapTo(linkedSetOf()) { it.asString() }
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) base else "$base$suffix"
            if (candidate !in used) return candidate
            suffix++
        }
    }

    private companion object {
        const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
        val FLUID_STACKS = setOf(
            "net.minecraftforge.fluids.FluidStack",
            "net.neoforged.neoforge.fluids.FluidStack"
        )
        const val DATA_COMPONENTS = "net.minecraft.core.component.DataComponents"
        const val CUSTOM_DATA = "net.minecraft.world.item.component.CustomData"
        const val POTION_UTILS = "net.minecraft.world.item.alchemy.PotionUtils"

        val MUTATOR_METHODS = setOf(
            "put",
            "putByte",
            "putShort",
            "putInt",
            "putLong",
            "putUUID",
            "putFloat",
            "putDouble",
            "putString",
            "putByteArray",
            "putIntArray",
            "putLongArray",
            "putBoolean",
            "remove",
            "merge",
            "add",
            "addAll",
            "set",
            "clear",
            "replaceAll",
            "sort"
        )
        val SAFE_READ_METHODS = setOf(
            "sizeInBytes",
            "getId",
            "getType",
            "size",
            "getUUID",
            "hasUUID",
            "getTagType",
            "contains",
            "getByte",
            "getShort",
            "getInt",
            "getLong",
            "getFloat",
            "getDouble",
            "getString",
            "getByteArray",
            "getIntArray",
            "getLongArray",
            "getBoolean",
            "toString",
            "isEmpty",
            "copy",
            "equals",
            "hashCode",
            "accept"
        )
        val POTION_UTILS_READS = setOf(
            "getPotion",
            "getCustomEffects",
            "getAllEffects",
            "getMobEffects",
            "getColor"
        )
    }
}
