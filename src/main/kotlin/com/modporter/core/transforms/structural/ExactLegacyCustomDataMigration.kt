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
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.SwitchEntry
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
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
        val receiver: Expression,
        val kind: ReceiverKind,
        val declarationStatement: ExpressionStmt,
        val ownerName: String?,
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
        val ownerName: String,
        val tagName: String,
        val tagExpression: String
    ) : DirectPlan

    private data class ElementAliasPlan(
        val call: MethodCallExpr,
        val variable: VariableDeclarator,
        val receiver: Expression,
        val declarationStatement: ExpressionStmt,
        val ownerName: String,
        val keyName: String,
        val rootName: String,
        val mutationStatements: List<ExpressionStmt>
    ) : FilePlan

    private data class ElementMutationPlan(
        val call: MethodCallExpr,
        val receiver: Expression,
        val statement: ExpressionStmt,
        val keyExpression: Expression,
        val keyName: String,
        val rootName: String,
        val childName: String,
        val mutationExpression: String
    ) : FilePlan

    private data class ElementReadLiftPlan(
        val call: MethodCallExpr,
        val receiver: Expression,
        val anchor: Statement,
        val keyExpression: Expression,
        val ownerName: String,
        val keyName: String,
        val rootName: String,
        val childName: String
    ) : FilePlan

    private data class SetTagPlan(
        val call: MethodCallExpr,
        val receiver: Expression,
        val statement: ExpressionStmt,
        val tagExpression: Expression,
        val ownerName: String,
        val tagName: String
    ) : FilePlan

    private data class AliasUse(
        val supported: Boolean,
        val mutationStatement: ExpressionStmt? = null
    )

    private data class ReceiverChain(
        val outermost: MethodCallExpr,
        val effect: ExactExternalTagContracts.Effect
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }
                .filter {
                    val source = it.readText()
                    source.contains(".getOrCreateTag(") ||
                        source.contains(".getOrCreateTagElement(") ||
                        source.contains(".setTag(")
                }
                .toList()
        }
        if (files.isEmpty()) return emptyList()

        val index = JavaProjectTypeIndex.build(sourceRoot)
        val projectTagEffects = ExactProjectTagEffects(sourceRoot, index)
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val migratedFiles = linkedMapOf<Path, String>()

        files.forEach { file ->
            val cu = index.unit(file)
            LexicalPreservingPrinter.setup(cu)
            val exact = ExactJavaSemantics(cu)
            val kinds = IdentityHashMap<MethodCallExpr, ReceiverKind>()
            val calls = cu.findAll(MethodCallExpr::class.java)
                .filter {
                    ((it.nameAsString == "getOrCreateTag" && it.arguments.isEmpty()) ||
                        (it.nameAsString == "getOrCreateTagElement" &&
                            it.arguments.size == 1) ||
                        (it.nameAsString == "setTag" && it.arguments.size == 1)) &&
                        it.scope.isPresent
                }
                .filter { call ->
                    receiverKind(index, call.scope.get(), call, exact)
                        ?.also { kinds[call] = it } != null
                }
            if (calls.isEmpty()) return@forEach

            val aliasPlans = mutableListOf<AliasPlan>()
            val directPlans = mutableListOf<DirectPlan>()
            val elementAliasPlans = mutableListOf<ElementAliasPlan>()
            val elementMutationPlans = mutableListOf<ElementMutationPlan>()
            val elementReadPlans = mutableListOf<ElementReadLiftPlan>()
            val setTagPlans = mutableListOf<SetTagPlan>()
            var complete = true
            calls.forEach { call ->
                val kind = kinds.getValue(call)
                val variable = call.parentNode.orElse(null) as? VariableDeclarator
                val plan: FilePlan? = if (call.nameAsString == "setTag") {
                    planSetTag(call, kind)
                } else if (call.nameAsString == "getOrCreateTagElement") {
                    planElement(call, variable, kind, exact, index, projectTagEffects)
                } else if (variable != null && variable.initializer.orElse(null) === call) {
                    planAlias(call, variable, kind, exact, index, projectTagEffects)
                } else {
                    planDirect(call, kind, exact, index, projectTagEffects)
                }
                when (plan) {
                    is AliasPlan -> aliasPlans += plan
                    is DirectPlan -> directPlans += plan
                    is ElementAliasPlan -> elementAliasPlans += plan
                    is ElementMutationPlan -> elementMutationPlans += plan
                    is ElementReadLiftPlan -> elementReadPlans += plan
                    is SetTagPlan -> setTagPlans += plan
                    else -> complete = false
                }
            }
            if (!complete) return@forEach

            aliasPlans.forEach { plan ->
                val effectiveReceiver = plan.ownerName?.let(::NameExpr) ?: plan.receiver
                if (plan.ownerName != null) {
                    insertBefore(
                        plan.declarationStatement,
                        StaticJavaParser.parseStatement(
                            "${receiverType(plan.kind)} ${plan.ownerName} = ${plan.receiver};"
                        )
                    )
                }
                plan.call.replace(StaticJavaParser.parseExpression(detachedTag(effectiveReceiver)))
                plan.mutationStatements
                    .distinctBy { System.identityHashCode(it) }
                    .sortedByDescending {
                        it.range.map { range -> range.begin.line * 10000 + range.begin.column }
                            .orElse(-1)
                    }
                    .forEach { statement ->
                        insertAfter(
                            statement,
                            writeBackStatement(effectiveReceiver.toString(), plan.variable.nameAsString)
                        )
                    }
            }
            elementAliasPlans.forEach { plan ->
                insertBefore(
                    plan.declarationStatement,
                    StaticJavaParser.parseStatement(
                        "$ITEM_STACK ${plan.ownerName} = ${plan.receiver};"
                    )
                )
                insertBefore(
                    plan.declarationStatement,
                    StaticJavaParser.parseStatement(
                        "String ${plan.keyName} = ${plan.call.arguments.single()};"
                    )
                )
                insertBefore(
                    plan.declarationStatement,
                    StaticJavaParser.parseStatement(
                        "net.minecraft.nbt.CompoundTag ${plan.rootName} = " +
                            "${detachedTag(NameExpr(plan.ownerName))};"
                    )
                )
                plan.call.replace(
                    StaticJavaParser.parseExpression(
                        "${plan.rootName}.getCompound(${plan.keyName})"
                    )
                )
                insertAfter(
                    plan.declarationStatement,
                    attachElementStatement(
                        plan.ownerName,
                        plan.rootName,
                        plan.keyName,
                        plan.variable.nameAsString
                    )
                )
                plan.mutationStatements
                    .distinctBy { System.identityHashCode(it) }
                    .sortedByDescending {
                        it.range.map { range -> range.begin.line * 10000 + range.begin.column }
                            .orElse(-1)
                    }
                    .forEach { statement ->
                        insertAfter(
                            statement,
                            attachElementStatement(
                                plan.ownerName,
                                plan.rootName,
                                plan.keyName,
                                plan.variable.nameAsString
                            )
                        )
                    }
            }
            elementMutationPlans.forEach { plan ->
                val migrated =
                    "$CUSTOM_DATA.update($DATA_COMPONENTS.CUSTOM_DATA, ${plan.receiver}, " +
                        "${plan.rootName} -> { String ${plan.keyName} = ${plan.keyExpression}; " +
                        "net.minecraft.nbt.CompoundTag ${plan.childName} = " +
                        "${plan.rootName}.getCompound(${plan.keyName}); " +
                        "${plan.mutationExpression}; " +
                        "${plan.rootName}.put(${plan.keyName}, ${plan.childName}); })"
                plan.statement.setExpression(StaticJavaParser.parseExpression(migrated))
            }
            elementReadPlans.forEach { plan ->
                insertBeforeStatement(
                    plan.anchor,
                    StaticJavaParser.parseStatement(
                        "$ITEM_STACK ${plan.ownerName} = ${plan.receiver};"
                    )
                )
                insertBeforeStatement(
                    plan.anchor,
                    StaticJavaParser.parseStatement(
                        "String ${plan.keyName} = ${plan.keyExpression};"
                    )
                )
                insertBeforeStatement(
                    plan.anchor,
                    StaticJavaParser.parseStatement(
                        "net.minecraft.nbt.CompoundTag ${plan.rootName} = " +
                            "${detachedTag(NameExpr(plan.ownerName))};"
                    )
                )
                insertBeforeStatement(
                    plan.anchor,
                    StaticJavaParser.parseStatement(
                        "net.minecraft.nbt.CompoundTag ${plan.childName} = " +
                            "${plan.rootName}.getCompound(${plan.keyName});"
                    )
                )
                insertBeforeStatement(
                    plan.anchor,
                    attachElementStatement(
                        plan.ownerName,
                        plan.rootName,
                        plan.keyName,
                        plan.childName
                    )
                )
                plan.call.replace(NameExpr(plan.childName))
            }
            setTagPlans.forEach { plan ->
                val migrated =
                    "net.minecraft.Util.make(${plan.receiver}, ${plan.ownerName} -> { " +
                        "net.minecraft.nbt.CompoundTag ${plan.tagName} = ${plan.tagExpression}; " +
                        "if (${plan.tagName} == null) { " +
                        "${plan.ownerName}.remove($DATA_COMPONENTS.CUSTOM_DATA); } else { " +
                        "${plan.ownerName}.set($DATA_COMPONENTS.CUSTOM_DATA, " +
                        "$CUSTOM_DATA.of(${plan.tagName})); } })"
                plan.statement.setExpression(StaticJavaParser.parseExpression(migrated))
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
                                "net.minecraft.Util.make(${plan.receiver}, ${plan.ownerName} -> " +
                                    "net.minecraft.Util.make(${detachedTag(NameExpr(plan.ownerName))}, " +
                                    "${plan.tagName} -> { ${plan.tagExpression}; " +
                                    "${writeBackCode(plan.ownerName, plan.tagName)} }))"
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
                        verified.problems.joinToString("; ") { it.verboseMessage } +
                        "\n$migrated"
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
        kind: ReceiverKind,
        exact: ExactJavaSemantics,
        index: JavaProjectTypeIndex,
        projectTagEffects: ExactProjectTagEffects
    ): AliasPlan? {
        if (!exact.exactTypeReference(
                variable.typeAsString,
                "CompoundTag",
                "net.minecraft.nbt.CompoundTag"
            )
        ) {
            return null
        }
        val receiver = call.scope.get()
        val declaration = variable.parentNode.orElse(null)
            ?.parentNode
            ?.orElse(null) as? ExpressionStmt ?: return null
        if (declaration.parentNode.orElse(null) !is BlockStmt &&
            declaration.parentNode.orElse(null) !is SwitchEntry
        ) {
            return null
        }
        val ownerName = if (receiver is NameExpr && isStableReceiver(receiver, call, exact)) {
            null
        } else {
            uniqueName(call, "modPorterCustomDataOwner")
        }

        val uses = exact.referencesTo(variable)
        val classified = uses.map {
            classifyAliasUse(it, exact, index, projectTagEffects)
        }
        if (classified.any { !it.supported }) return null
        return AliasPlan(
            call = call,
            variable = variable,
            receiver = receiver,
            kind = kind,
            declarationStatement = declaration,
            ownerName = ownerName,
            mutationStatements = classified.mapNotNull { it.mutationStatement }
        )
    }

    private fun planElement(
        call: MethodCallExpr,
        variable: VariableDeclarator?,
        kind: ReceiverKind,
        exact: ExactJavaSemantics,
        index: JavaProjectTypeIndex,
        projectTagEffects: ExactProjectTagEffects
    ): FilePlan? {
        if (kind != ReceiverKind.ITEM_STACK || call.arguments.size != 1) return null
        val receiver = call.scope.get()
        if (variable != null && variable.initializer.orElse(null) === call) {
            if (!exact.exactTypeReference(
                    variable.typeAsString,
                    "CompoundTag",
                    "net.minecraft.nbt.CompoundTag"
                )
            ) {
                return null
            }
            val declaration = variable.parentNode.orElse(null)
                ?.parentNode
                ?.orElse(null) as? ExpressionStmt ?: return null
            if (declaration.parentNode.orElse(null) !is BlockStmt &&
                declaration.parentNode.orElse(null) !is SwitchEntry
            ) {
                return null
            }
            val classified = exact.referencesTo(variable).map {
                classifyAliasUse(it, exact, index, projectTagEffects)
            }
            if (classified.any { !it.supported }) return null
            return ElementAliasPlan(
                call = call,
                variable = variable,
                receiver = receiver,
                declarationStatement = declaration,
                ownerName = uniqueName(call, "modPorterCustomDataOwner"),
                keyName = uniqueName(call, "modPorterCustomDataKey"),
                rootName = uniqueName(call, "modPorterCustomDataRoot"),
                mutationStatements = classified.mapNotNull { it.mutationStatement }
            )
        }

        val chain = receiverChain(call)
        if (chain?.effect == ExactExternalTagContracts.Effect.MUTATE) {
            val statement = standaloneStatement(chain.outermost) ?: return null
            val childName = uniqueName(call, "modPorterCustomDataChild")
            return ElementMutationPlan(
                call = call,
                receiver = receiver,
                statement = statement,
                keyExpression = call.arguments.single(),
                keyName = uniqueName(call, "modPorterCustomDataKey"),
                rootName = uniqueName(call, "modPorterCustomDataRoot"),
                childName = childName,
                mutationExpression = expressionWithCallReplacement(
                    chain.outermost,
                    call,
                    childName,
                    "getOrCreateTagElement"
                )
            )
        }

        val parentCall = call.parentNode.orElse(null) as? MethodCallExpr ?: return null
        if (parentCall.arguments.size != 1 ||
            parentCall.arguments.single() !== call ||
            parentCall.scope.isPresent
        ) {
            return null
        }
        val effect = argumentEffect(
            parentCall,
            0,
            exact,
            index,
            projectTagEffects
        )
        if (effect != ExactExternalTagContracts.Effect.READ) return null
        val anchor = parentCall.findAncestor(Statement::class.java).orElse(null) ?: return null
        if (anchor.parentNode.orElse(null) !is BlockStmt &&
            anchor.parentNode.orElse(null) !is SwitchEntry
        ) {
            return null
        }
        return ElementReadLiftPlan(
            call = call,
            receiver = receiver,
            anchor = anchor,
            keyExpression = call.arguments.single(),
            ownerName = uniqueName(call, "modPorterCustomDataOwner"),
            keyName = uniqueName(call, "modPorterCustomDataKey"),
            rootName = uniqueName(call, "modPorterCustomDataRoot"),
            childName = uniqueName(call, "modPorterCustomDataChild")
        )
    }

    private fun planSetTag(
        call: MethodCallExpr,
        kind: ReceiverKind
    ): SetTagPlan? {
        if (kind != ReceiverKind.ITEM_STACK || call.arguments.size != 1) return null
        val statement = standaloneStatement(call) ?: return null
        return SetTagPlan(
            call = call,
            receiver = call.scope.get(),
            statement = statement,
            tagExpression = call.arguments.single(),
            ownerName = uniqueName(call, "modPorterCustomDataOwner"),
            tagName = uniqueName(call, "modPorterCustomDataValue")
        )
    }

    private fun planDirect(
        call: MethodCallExpr,
        kind: ReceiverKind,
        exact: ExactJavaSemantics,
        index: JavaProjectTypeIndex,
        projectTagEffects: ExactProjectTagEffects
    ): DirectPlan? {
        val receiver = call.scope.get()
        val chain = receiverChain(call)
        if (chain != null) {
            return when (chain.effect) {
                ExactExternalTagContracts.Effect.MUTATE -> {
                    val statement = standaloneStatement(chain.outermost) ?: return null
                    val tagName = uniqueName(call, "modPorterCustomDataTag")
                    val ownerName = uniqueName(call, "modPorterCustomDataOwner")
                    DirectMutationPlan(
                        call,
                        receiver,
                        kind,
                        statement,
                        ownerName,
                        tagName,
                        expressionWithTag(chain.outermost, call, tagName)
                    )
                }
                ExactExternalTagContracts.Effect.READ -> DirectReadPlan(call, receiver)
            }
        }
        val direct = directlyScopedCall(call)
        if (direct != null &&
            direct.nameAsString in NESTED_TAG_GETTERS &&
            detachedNestedReadSupported(direct, exact, index, projectTagEffects)
        ) {
            return DirectReadPlan(call, receiver)
        }

        val parentCall = call.parentNode.orElse(null) as? MethodCallExpr
        if (parentCall != null && parentCall.arguments.any { it === call }) {
            val argumentIndex = parentCall.arguments.indexOfIdentity(call)
            val externalEffect = ExactExternalTagContracts.compoundTagArgumentEffect(
                parentCall,
                argumentIndex,
                exact,
                index
            ) ?: projectTagEffects.compoundTagArgumentEffect(parentCall, argumentIndex)
            return when {
                externalEffect == ExactExternalTagContracts.Effect.READ -> DirectReadPlan(call, receiver)
                externalEffect == ExactExternalTagContracts.Effect.MUTATE -> {
                    val statement = standaloneStatement(parentCall) ?: return null
                    val tagName = uniqueName(call, "modPorterCustomDataTag")
                    val ownerName = uniqueName(call, "modPorterCustomDataOwner")
                    DirectMutationPlan(
                        call,
                        receiver,
                        kind,
                        statement,
                        ownerName,
                        tagName,
                        expressionWithTag(parentCall, call, tagName)
                    )
                }
                else -> null
            }
        }
        return null
    }

    private fun classifyAliasUse(
        use: NameExpr,
        exact: ExactJavaSemantics,
        index: JavaProjectTypeIndex,
        projectTagEffects: ExactProjectTagEffects
    ): AliasUse {
        val chain = receiverChain(use)
        if (chain != null) {
            return when (chain.effect) {
                ExactExternalTagContracts.Effect.MUTATE ->
                    AliasUse(true, standaloneStatement(chain.outermost) ?: return AliasUse(false))
                ExactExternalTagContracts.Effect.READ -> AliasUse(true)
            }
        }
        val nested = directlyScopedCall(use)
        if (nested != null && nested.nameAsString in NESTED_TAG_GETTERS) {
            if (detachedNestedReadSupported(
                    nested,
                    exact,
                    index,
                    projectTagEffects
                )
            ) {
                return AliasUse(true)
            }
            val (parentCall, argumentIndex) = argumentConsumer(nested)
                ?: return AliasUse(false)
            return when (argumentEffect(
                parentCall,
                argumentIndex,
                exact,
                index,
                projectTagEffects
            )) {
                ExactExternalTagContracts.Effect.READ -> AliasUse(true)
                ExactExternalTagContracts.Effect.MUTATE ->
                    AliasUse(
                        true,
                        standaloneStatement(parentCall) ?: return AliasUse(false)
                    )
                null -> AliasUse(false)
            }
        }

        val parentCall = use.parentNode.orElse(null) as? MethodCallExpr
        if (parentCall != null && parentCall.arguments.any { it === use }) {
            val argumentIndex = parentCall.arguments.indexOfIdentity(use)
            val externalEffect = ExactExternalTagContracts.compoundTagArgumentEffect(
                parentCall,
                argumentIndex,
                exact,
                index
            ) ?: projectTagEffects.compoundTagArgumentEffect(parentCall, argumentIndex)
            return when {
                externalEffect == ExactExternalTagContracts.Effect.READ -> AliasUse(true)
                externalEffect == ExactExternalTagContracts.Effect.MUTATE ->
                    AliasUse(true, standaloneStatement(parentCall) ?: return AliasUse(false))
                else -> AliasUse(false)
            }
        }
        return AliasUse(false)
    }

    private fun directlyScopedCall(root: Node): MethodCallExpr? {
        val call = root.parentNode.orElse(null) as? MethodCallExpr ?: return null
        return call.takeIf { it.scope.map { scope -> scope === root }.orElse(false) }
    }

    private fun outermostScopedCall(first: MethodCallExpr): MethodCallExpr {
        var current = first
        while (true) {
            val parent = current.parentNode.orElse(null) as? MethodCallExpr ?: return current
            if (!parent.scope.map { it === current }.orElse(false)) return current
            current = parent
        }
    }

    private fun receiverChain(root: Node): ReceiverChain? {
        val first = directlyScopedCall(root) ?: return null
        ExactExternalTagContracts.compoundTagReceiverEffect(first.nameAsString)?.let { effect ->
            return ReceiverChain(outermostScopedCall(first), effect)
        }
        if (first.nameAsString !in NESTED_TAG_GETTERS) return null
        val outermost = outermostScopedCall(first)
        if (outermost === first) return null
        val effect = ExactExternalTagContracts.compoundTagReceiverEffect(outermost.nameAsString)
            ?: return null
        return ReceiverChain(outermost, effect)
    }

    private fun detachedNestedReadSupported(
        nestedCall: MethodCallExpr,
        exact: ExactJavaSemantics,
        index: JavaProjectTypeIndex,
        projectTagEffects: ExactProjectTagEffects
    ): Boolean {
        val variable = nestedCall.parentNode.orElse(null) as? VariableDeclarator ?: return false
        if (variable.initializer.orElse(null) !== nestedCall ||
            !(exact.exactTypeReference(
                variable.typeAsString,
                "CompoundTag",
                "net.minecraft.nbt.CompoundTag"
            ) || exact.exactTypeReference(
                variable.typeAsString,
                "ListTag",
                "net.minecraft.nbt.ListTag"
            ))
        ) {
            return false
        }
        return exact.referencesTo(variable).all { use ->
            detachedValueUseIsRead(use, exact, index, projectTagEffects)
        }
    }

    private fun detachedValueUseIsRead(
        use: NameExpr,
        exact: ExactJavaSemantics,
        index: JavaProjectTypeIndex,
        projectTagEffects: ExactProjectTagEffects
    ): Boolean {
        val forEach = use.parentNode.orElse(null) as? ForEachStmt
        if (forEach != null && forEach.iterable === use) return true

        receiverChain(use)?.let {
            return it.effect == ExactExternalTagContracts.Effect.READ
        }
        val nested = directlyScopedCall(use)
        if (nested != null && nested.nameAsString in NESTED_TAG_GETTERS) {
            val (parentCall, argumentIndex) = argumentConsumer(nested) ?: return false
            return argumentEffect(
                parentCall,
                argumentIndex,
                exact,
                index,
                projectTagEffects
            ) == ExactExternalTagContracts.Effect.READ
        }

        val parentCall = use.parentNode.orElse(null) as? MethodCallExpr ?: return false
        val argumentIndex = parentCall.arguments.indexOfIdentity(use)
        if (argumentIndex < 0) return false
        return argumentEffect(
            parentCall,
            argumentIndex,
            exact,
            index,
            projectTagEffects
        ) == ExactExternalTagContracts.Effect.READ
    }

    private fun argumentEffect(
        call: MethodCallExpr,
        argumentIndex: Int,
        exact: ExactJavaSemantics,
        index: JavaProjectTypeIndex,
        projectTagEffects: ExactProjectTagEffects
    ): ExactExternalTagContracts.Effect? =
        ExactExternalTagContracts.compoundTagArgumentEffect(
            call,
            argumentIndex,
            exact,
            index
        ) ?: projectTagEffects.compoundTagArgumentEffect(call, argumentIndex)

    private fun argumentConsumer(expression: Expression): Pair<MethodCallExpr, Int>? {
        var current: Node = expression
        while (true) {
            current = when (val parent = current.parentNode.orElse(null)) {
                is CastExpr -> parent.takeIf { it.expression === current } ?: break
                is EnclosedExpr -> parent.takeIf { it.inner === current } ?: break
                else -> break
            }
        }
        val call = current.parentNode.orElse(null) as? MethodCallExpr ?: return null
        val argumentIndex = call.arguments.indexOfIdentity(current as? Expression ?: return null)
        return (call to argumentIndex).takeIf { argumentIndex >= 0 }
    }

    private fun standaloneStatement(expression: Expression): ExpressionStmt? {
        val statement = expression.parentNode.orElse(null) as? ExpressionStmt ?: return null
        if (statement.expression !== expression) return null
        return statement.takeIf {
            it.parentNode.orElse(null) is BlockStmt ||
                it.parentNode.orElse(null) is SwitchEntry ||
                (it.parentNode.orElse(null) as? LambdaExpr)?.body === it ||
                (it.parentNode.orElse(null) as? IfStmt)?.let { branch ->
                    branch.thenStmt === it || branch.elseStmt.orElse(null) === it
                } == true
        }
    }

    private fun expressionWithTag(
        expression: MethodCallExpr,
        originalCall: MethodCallExpr,
        tagName: String
    ): String = expressionWithCallReplacement(
        expression,
        originalCall,
        tagName,
        "getOrCreateTag"
    )

    private fun expressionWithCallReplacement(
        expression: MethodCallExpr,
        originalCall: MethodCallExpr,
        replacementName: String,
        methodName: String
    ): String {
        val clone = expression.clone()
        val originalRange = originalCall.range.orElse(null)
        val candidates = clone.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == methodName &&
                (originalRange == null || it.range.orElse(null) == originalRange)
        }
        val target = candidates.singleOrNull() ?: throw IllegalStateException(
            "Cannot bind exact $methodName node inside '$expression'"
        )
        target.replace(NameExpr(replacementName))
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
        context: Node,
        exact: ExactJavaSemantics
    ): ReceiverKind? {
        val exactItem = exact.isProvablyType(receiver, "ItemStack", ITEM_STACK)
        val exactFluid = exact.isProvablyType(receiver, "FluidStack", FLUID_STACKS)
        if (exactItem xor exactFluid) {
            return if (exactItem) ReceiverKind.ITEM_STACK else ReceiverKind.FLUID_STACK
        }

        val indexedItem = index.isExpressionAssignableTo(receiver, context, ITEM_STACK)
        val indexedFluid = FLUID_STACKS.any {
            index.isExpressionAssignableTo(receiver, context, it)
        }
        return when {
            indexedItem && !indexedFluid -> ReceiverKind.ITEM_STACK
            indexedFluid && !indexedItem -> ReceiverKind.FLUID_STACK
            else -> null
        }
    }

    private fun detachedTag(receiver: Expression): String =
        "($receiver).getOrDefault($DATA_COMPONENTS.CUSTOM_DATA, $CUSTOM_DATA.EMPTY).copyTag()"

    private fun writeBackStatement(receiver: String, tagName: String): Statement =
        StaticJavaParser.parseStatement(writeBackCode(receiver, tagName))

    private fun attachElementStatement(
        ownerName: String,
        rootName: String,
        keyName: String,
        childName: String
    ): Statement =
        StaticJavaParser.parseStatement(
            "{ $rootName.put($keyName, $childName); " +
                "${writeBackCode(ownerName, rootName)} }"
        )

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
            is LambdaExpr -> {
                if (parent.body !== anchor) {
                    throw IllegalStateException("Custom-data mutation is not the lambda body")
                }
                val block = BlockStmt()
                block.addStatement(
                    StaticJavaParser.parseStatement("${anchor.expression};")
                )
                block.addStatement(statement)
                parent.setBody(block)
            }
            is IfStmt -> {
                val block = BlockStmt()
                block.addStatement(
                    StaticJavaParser.parseStatement("${anchor.expression};")
                )
                block.addStatement(statement)
                when {
                    parent.thenStmt === anchor -> parent.setThenStmt(block)
                    parent.elseStmt.orElse(null) === anchor -> parent.setElseStmt(block)
                    else -> throw IllegalStateException(
                        "Custom-data mutation is not an if branch"
                    )
                }
            }
            else -> throw IllegalStateException("Custom-data mutation is outside an ordered statement list")
        }
    }

    private fun insertBefore(anchor: ExpressionStmt, statement: Statement) {
        when (val parent = anchor.parentNode.orElse(null)) {
            is BlockStmt -> {
                val index = parent.statements.indexOfIdentity(anchor)
                if (index < 0) throw IllegalStateException("Cannot locate custom-data alias declaration")
                parent.statements.add(index, statement)
            }
            is SwitchEntry -> {
                val index = parent.statements.indexOfIdentity(anchor)
                if (index < 0) throw IllegalStateException("Cannot locate switch custom-data alias declaration")
                parent.statements.add(index, statement)
            }
            else -> throw IllegalStateException("Custom-data alias is outside an ordered statement list")
        }
    }

    private fun insertBeforeStatement(anchor: Statement, statement: Statement) {
        when (val parent = anchor.parentNode.orElse(null)) {
            is BlockStmt -> {
                val index = parent.statements.indexOfIdentity(anchor)
                if (index < 0) throw IllegalStateException("Cannot locate lifted tag-element statement")
                parent.statements.add(index, statement)
            }
            is SwitchEntry -> {
                val index = parent.statements.indexOfIdentity(anchor)
                if (index < 0) throw IllegalStateException("Cannot locate lifted switch tag-element statement")
                parent.statements.add(index, statement)
            }
            else -> throw IllegalStateException("Lifted tag element is outside an ordered statement list")
        }
    }

    private fun receiverType(kind: ReceiverKind): String = when (kind) {
        ReceiverKind.ITEM_STACK -> ITEM_STACK
        ReceiverKind.FLUID_STACK -> TARGET_FLUID_STACK
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
        const val TARGET_FLUID_STACK = "net.neoforged.neoforge.fluids.FluidStack"
        val FLUID_STACKS = setOf(
            "net.minecraftforge.fluids.FluidStack",
            TARGET_FLUID_STACK
        )
        val NESTED_TAG_GETTERS = setOf("get", "getCompound", "getList")
        const val DATA_COMPONENTS = "net.minecraft.core.component.DataComponents"
        const val CUSTOM_DATA = "net.minecraft.world.item.component.CustomData"
    }
}
