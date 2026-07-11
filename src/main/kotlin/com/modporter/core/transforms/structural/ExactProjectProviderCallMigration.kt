package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Closes missing HolderLookup.Provider arguments across exact project method call graphs. */
internal class ExactProjectProviderCallMigration {
    private data class MethodKey(val owner: String, val name: String, val parameterTypes: List<String>) {
        val declarationArity: Int get() = parameterTypes.size
    }

    private data class ProviderTarget(
        val key: MethodKey,
        val providerIndex: Int,
        val legacyArity: Int,
        val returnType: String?,
        val method: MethodDeclaration
    )

    private data class FunctionalSignature(
        val parameterTypes: List<String>,
        val returnType: String
    )

    private data class Demand(
        val key: MethodKey,
        val file: Path,
        val method: MethodDeclaration,
        val providerIndex: Int,
        val providerName: String,
        val legacyArity: Int,
        val hadProvider: Boolean,
        val canAddProvider: Boolean
    )

    private data class InitialSite(
        val file: Path,
        val call: MethodCallExpr,
        val caller: Demand,
        val callee: ProviderTarget
    )

    private data class InitialReferenceSite(
        val file: Path,
        val reference: MethodReferenceExpr,
        val caller: Demand,
        val callee: ProviderTarget,
        val unboundInstance: Boolean
    )

    private data class ProjectCallSite(
        val file: Path,
        val call: MethodCallExpr,
        val caller: Demand,
        val callee: Demand
    )

    private data class DischargedTarget(
        val key: MethodKey,
        val providerIndex: Int,
        val file: Path
    )

    private data class PendingUnusedProvider(
        val target: DischargedTarget,
        val method: MethodDeclaration
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        if (files.isEmpty()) return emptyList()
        val sources = files.associateWith(Path::readText)
        if (sources.values.none { it.contains("HolderLookup.Provider") }) return emptyList()
        val providerDeclarationFiles = files.filter { file ->
            sources.getValue(file).contains("HolderLookup.Provider")
        }

        val index = JavaProjectTypeIndex.build(sourceRoot)
        val units = linkedMapOf<Path, CompilationUnit>()
        val lexicalFiles = linkedSetOf<Path>()
        fun unit(file: Path): CompilationUnit = units.getOrPut(file) {
            index.unit(file)
        }
        fun ensureLexical(file: Path) {
            if (lexicalFiles.add(file)) LexicalPreservingPrinter.setup(unit(file))
        }
        fun releaseUnit(file: Path) {
            if (file in lexicalFiles) return
            units.remove(file)
            index.release(file)
        }

        val preChangedFiles = dischargeParameterOwnedProviders(
            providerDeclarationFiles,
            files,
            ::unit,
            ::ensureLexical,
            ::releaseUnit,
            index
        )

        val providerTargets = mutableListOf<ProviderTarget>()
        providerDeclarationFiles.forEach { file ->
            unit(file).findAll(MethodDeclaration::class.java).forEach methods@{ method ->
                val owner = declaredOwnerOrNull(method) ?: return@methods
                val providerParameters = method.parameters.withIndex().filter { (_, parameter) ->
                    index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
                }
                if (providerParameters.size > 1) {
                    throw IllegalStateException(
                        "Method ${method.nameAsString} in $file has multiple HolderLookup.Provider parameters"
                    )
                }
                val provider = providerParameters.singleOrNull() ?: return@methods
                if (!index.hasClosedProjectMethodHierarchy(owner)) return@methods
                val parameterTypes = resolvedParameterTypes(method, index) ?: return@methods
                val key = MethodKey(owner, method.nameAsString, parameterTypes)
                val target = ProviderTarget(
                    key,
                    provider.index,
                    method.parameters.size - 1,
                    index.declaredType(method.type, method),
                    method
                )
                providerTargets += target
            }
        }
        if (providerTargets.isEmpty()) {
            return writeChanges(preChangedFiles, unit = ::unit, dryRun = dryRun)
        }
        val methodsByOwnerAndName = providerDeclarationFiles.flatMap { file ->
            unit(file).findAll(MethodDeclaration::class.java).mapNotNull { method ->
                declaredOwnerOrNull(method)?.let { owner ->
                    (owner to method.nameAsString) to method
                }
            }
        }.groupBy({ it.first }, { it.second })

        val demands = linkedMapOf<MethodKey, Demand>()
        fun demandFor(file: Path, callable: CallableDeclaration<*>): Demand {
            val method = callable as? MethodDeclaration ?: throw IllegalStateException(
                "HolderLookup.Provider propagation cannot change constructor '${callable.nameAsString}' in $file"
            )
            val owner = declaredOwner(method, file)
            val parameterTypes = resolvedParameterTypes(method, index) ?: throw IllegalStateException(
                "Cannot resolve complete parameter types for provider-demanded method $owner.${method.nameAsString}"
            )
            val key = MethodKey(owner, method.nameAsString, parameterTypes)
            demands[key]?.let { return it }
            val providerParameters = method.parameters.withIndex().filter { (_, parameter) ->
                index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
            }
            if (providerParameters.size > 1) {
                throw IllegalStateException("Method $owner.${method.nameAsString} has multiple HolderLookup.Provider parameters")
            }
            val existing = providerParameters.singleOrNull()
            val contextProvider = if (existing == null) exactCallableProviderExpression(method, index) else null
            val canAddProvider = existing != null || contextProvider != null ||
                index.isProvenNonOverride(method)
            val providerIndex = existing?.index ?: method.parameters.size
            val providerName = existing?.value?.nameAsString ?: contextProvider ?: uniqueProviderName(method)
            return Demand(
                key = key,
                file = file,
                method = method,
                providerIndex = providerIndex,
                providerName = providerName,
                legacyArity = method.parameters.size - if (existing == null) 0 else 1,
                hadProvider = existing != null || contextProvider != null,
                canAddProvider = canAddProvider
            ).also { demands[key] = it }
        }

        val targetsByName = providerTargets.groupBy { it.key.name }
        val targetNames = targetsByName.keys
        val candidateFiles = files.filter { file ->
            val source = sources.getValue(file)
            targetNames.any { name -> source.contains("$name(") || source.contains("::$name") }
        }
        val initialSites = mutableListOf<InitialSite>()
        val targetsByOwnerAndName = providerTargets.groupBy { it.key.owner to it.key.name }
        val initialReferenceSites = mutableListOf<InitialReferenceSite>()
        candidateFiles.forEach { file ->
            val siteCountBefore = initialSites.size + initialReferenceSites.size
            unit(file).findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                val namedTargets = targetsByName[call.nameAsString].orEmpty()
                if (namedTargets.isEmpty()) return@calls
                val receiverType = index.methodCallReceiverType(call) ?: return@calls
                val matchingTargets = namedTargets.filter { target ->
                    target.legacyArity == call.arguments.size &&
                        index.isTypeAssignableTo(receiverType, target.key.owner) &&
                        index.argumentsMatchTypes(
                            call,
                            target.key.parameterTypes.filterIndexed { parameterIndex, _ ->
                                parameterIndex != target.providerIndex
                            }
                        )
                }
                if (matchingTargets.isEmpty()) return@calls
                if (index.argumentsMatchProjectMethod(call, receiverType, call.arguments.size)) return@calls
                if (matchingTargets.any {
                        index.argumentsMatchProjectMethod(call, it.key.owner, call.arguments.size)
                    }
                ) return@calls
                if (matchingTargets.size > 1) {
                    throw IllegalStateException(
                        "Ambiguous typed provider-aware project call '$call': " +
                            matchingTargets.joinToString { it.key.toString() }
                    )
                }
                val target = matchingTargets.single()
                val targetOwner = target.key.owner
                if (!index.hasClosedProjectMethodHierarchy(targetOwner)) {
                    throw IllegalStateException(
                        "Cannot prove inherited overload closure for provider-aware project method " +
                            "$targetOwner.${call.nameAsString}/${target.key.declarationArity}"
                    )
                }
                val callable = enclosingCallable(call) ?: throw IllegalStateException(
                    "Call to $targetOwner.${call.nameAsString} is outside a callable in $file"
                )
                if (!hasDeclaredOrExactProviderRoot(callable, index)) return@calls
                initialSites += InitialSite(file, call, demandFor(file, callable), target)
            }
            unit(file).findAll(MethodReferenceExpr::class.java).forEach references@{ reference ->
                val targetOwner = index.expressionType(reference.scope, reference) ?: return@references
                val candidates = targetsByOwnerAndName[targetOwner to reference.identifier].orEmpty()
                if (candidates.isEmpty()) return@references
                val functionalSignature = exactMethodReferenceSignature(reference, index) ?: return@references
                val ownerMethods = methodsByOwnerAndName[targetOwner to reference.identifier].orEmpty()
                if (candidates.size != 1 || ownerMethods.size != 1) {
                    throw IllegalStateException(
                        "Cannot resolve overloaded provider-aware method reference $reference on $targetOwner"
                    )
                }
                val target = candidates.single()
                if (!index.hasClosedProjectMethodHierarchy(targetOwner)) {
                    throw IllegalStateException(
                        "Cannot prove inherited overload closure for provider-aware method reference $reference"
                    )
                }
                val isTypeScope = reference.scope is TypeExpr
                val isStatic = target.method.isStatic
                val expectedParameterTypes = buildList {
                    if (isTypeScope && !isStatic) add(target.key.owner)
                    addAll(target.key.parameterTypes.filterIndexed { index, _ -> index != target.providerIndex })
                }
                if (functionalSignature.parameterTypes.size != expectedParameterTypes.size) return@references
                if (!functionalSignature.parameterTypes.zip(expectedParameterTypes).all { (actual, expected) ->
                        index.isTypeAssignableTo(actual, expected)
                    }
                ) return@references
                val targetReturnType = target.returnType ?: return@references
                if (targetReturnType == "void") {
                    if (functionalSignature.returnType != "void") return@references
                } else if (!index.isTypeAssignableTo(targetReturnType, functionalSignature.returnType)) {
                    return@references
                }
                if (!isTypeScope && isStatic) {
                    throw IllegalStateException("Static provider-aware method reference must use its declared type: $reference")
                }
                val callable = reference.findAncestor(CallableDeclaration::class.java).orElse(null)
                    ?: throw IllegalStateException("Provider-aware method reference '$reference' is outside a callable in $file")
                if (!hasDeclaredOrExactProviderRoot(callable, index)) return@references
                initialReferenceSites += InitialReferenceSite(
                    file,
                    reference,
                    demandFor(file, callable),
                    target,
                    unboundInstance = isTypeScope && !isStatic
                )
            }
            if (initialSites.size + initialReferenceSites.size == siteCountBefore &&
                file !in providerDeclarationFiles && file !in preChangedFiles
            ) {
                releaseUnit(file)
            }
        }
        if (initialSites.isEmpty() && initialReferenceSites.isEmpty()) {
            return writeChanges(preChangedFiles, unit = ::unit, dryRun = dryRun)
        }

        val projectCallSites = linkedMapOf<String, ProjectCallSite>()
        val protectedFiles = linkedSetOf<Path>().apply {
            addAll(providerDeclarationFiles)
            addAll(preChangedFiles)
            addAll(initialSites.map { it.file })
            addAll(initialReferenceSites.map { it.file })
        }
        val queue = ArrayDeque<Demand>()
        queue.addAll((initialSites.map { it.caller } + initialReferenceSites.map { it.caller }).distinctBy { it.key })
        val expanded = linkedSetOf<MethodKey>()
        while (queue.isNotEmpty()) {
            val callee = queue.removeFirst()
            if (!expanded.add(callee.key) || callee.hadProvider) continue
            val callSites = findProjectCallSites(callee, sources, ::unit, ::releaseUnit, protectedFiles, index)
            if (callSites.isEmpty()) {
                continue
            }
            callSites.forEach { (file, call) ->
                val callable = enclosingCallable(call) ?: throw IllegalStateException(
                    "Call to ${callee.key.owner}.${callee.key.name} is outside a callable in $file"
                )
                val caller = demandFor(file, callable)
                protectedFiles.add(file)
                val siteKey = "$file:${call.range.orElseThrow()}:${callee.key}"
                projectCallSites[siteKey] = ProjectCallSite(file, call, caller, callee)
                if (!caller.hadProvider) queue.addLast(caller)
            }
        }

        val reachable = demands.values.filter { it.hadProvider }.mapTo(linkedSetOf()) { it.key }
        var grew: Boolean
        do {
            grew = false
            demands.values.filterNot { it.hadProvider || it.key in reachable }.forEach { demand ->
                val incoming = projectCallSites.values.filter { it.callee.key == demand.key }
                if (demand.canAddProvider && incoming.isNotEmpty() && incoming.all { it.caller.key in reachable }) {
                    if (reachable.add(demand.key)) grew = true
                }
            }
        } while (grew)

        demands.values.filter { !it.hadProvider && it.key in reachable }.forEach { demand ->
            ensureLexical(demand.file)
            demand.method.parameters.add(
                demand.providerIndex,
                Parameter(StaticJavaParser.parseType(HOLDER_LOOKUP_PROVIDER), demand.providerName)
            )
        }
        initialSites.filter { it.caller.key in reachable }.forEach { site ->
            ensureLexical(site.file)
            site.call.arguments.add(
                site.callee.providerIndex,
                StaticJavaParser.parseExpression(site.caller.providerName)
            )
        }
        initialReferenceSites.filter { it.caller.key in reachable }.forEach { site ->
            ensureLexical(site.file)
            replaceMethodReference(site)
        }
        projectCallSites.values.filter { it.caller.key in reachable && it.callee.key in reachable }.forEach { site ->
            ensureLexical(site.file)
            site.call.arguments.add(
                site.callee.providerIndex,
                StaticJavaParser.parseExpression(site.caller.providerName)
            )
        }

        val changedFiles = linkedSetOf<Path>()
        changedFiles += preChangedFiles
        changedFiles += initialSites.filter { it.caller.key in reachable }.map { it.file }
        changedFiles += initialReferenceSites.filter { it.caller.key in reachable }.map { it.file }
        changedFiles += demands.values.filter { !it.hadProvider && it.key in reachable }.map { it.file }
        changedFiles += projectCallSites.values
            .filter { it.caller.key in reachable && it.callee.key in reachable }
            .map { it.file }
        return writeChanges(changedFiles, unit = ::unit, dryRun = dryRun)
    }

    private fun replaceMethodReference(site: InitialReferenceSite) {
        val legacyArguments = (0 until site.callee.legacyArity).map { index ->
            uniqueLambdaName(site.caller.method, "modporterArg$index")
        }
        val methodArguments = legacyArguments.toMutableList()
        methodArguments.add(site.callee.providerIndex, site.caller.providerName)
        val lambdaParameters = mutableListOf<String>()
        val invocationReceiver = if (site.unboundInstance) {
            uniqueLambdaName(site.caller.method, "modporterValue").also(lambdaParameters::add)
        } else {
            site.reference.scope.toString()
        }
        lambdaParameters += legacyArguments
        val invocation = "$invocationReceiver.${site.callee.key.name}(${methodArguments.joinToString(", ")})"
        val parameterText = when (lambdaParameters.size) {
            0 -> "()"
            1 -> lambdaParameters.single()
            else -> lambdaParameters.joinToString(", ", "(", ")")
        }
        site.reference.replace(StaticJavaParser.parseExpression("$parameterText -> $invocation"))
    }

    private fun exactMethodReferenceSignature(
        reference: MethodReferenceExpr,
        index: JavaProjectTypeIndex
    ): FunctionalSignature? {
        val variable = reference.findAncestor(VariableDeclarator::class.java).orElse(null)
        if (variable != null && variable.initializer.orElse(null)?.let { it === reference || it.isAncestorOf(reference) } == true) {
            val (type, arguments) = index.declaredTypeWithArguments(variable.type, variable) ?: return null
            return when (type) {
                "java.util.function.Supplier" -> arguments.singleOrNull()?.let {
                    FunctionalSignature(emptyList(), it)
                }
                "java.util.function.Consumer" -> arguments.singleOrNull()?.let {
                    FunctionalSignature(listOf(it), "void")
                }
                "java.util.function.Function" -> arguments.takeIf { it.size == 2 }?.let {
                    FunctionalSignature(listOf(it[0]), it[1])
                }
                "java.util.function.Predicate" -> arguments.singleOrNull()?.let {
                    FunctionalSignature(listOf(it), "boolean")
                }
                "java.util.function.UnaryOperator" -> arguments.singleOrNull()?.let {
                    FunctionalSignature(listOf(it), it)
                }
                "java.util.function.BiConsumer" -> arguments.takeIf { it.size == 2 }?.let {
                    FunctionalSignature(it, "void")
                }
                "java.util.function.BiFunction" -> arguments.takeIf { it.size == 3 }?.let {
                    FunctionalSignature(it.take(2), it[2])
                }
                "java.util.function.BiPredicate" -> arguments.takeIf { it.size == 2 }?.let {
                    FunctionalSignature(it, "boolean")
                }
                "java.util.function.BinaryOperator" -> arguments.singleOrNull()?.let {
                    FunctionalSignature(listOf(it, it), it)
                }
                else -> null
            }
        }
        return null
    }

    private fun dischargeParameterOwnedProviders(
        providerDeclarationFiles: List<Path>,
        allFiles: List<Path>,
        unit: (Path) -> CompilationUnit,
        ensureLexical: (Path) -> Unit,
        releaseUnit: (Path) -> Unit,
        index: JavaProjectTypeIndex
    ): Set<Path> {
        val discharged = mutableListOf<DischargedTarget>()
        val pendingUnused = mutableListOf<PendingUnusedProvider>()
        providerDeclarationFiles.forEach { file ->
            unit(file).findAll(MethodDeclaration::class.java).forEach methods@{ method ->
                val owner = declaredOwnerOrNull(method) ?: return@methods
                val providers = method.parameters.withIndex().filter { (_, parameter) ->
                    index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
                }
                if (providers.size != 1) return@methods
                if (!index.isProvenNonOverride(method)) return@methods
                val provider = providers.single()
                val parameterTypes = resolvedParameterTypes(method, index) ?: return@methods
                if (!index.hasClosedProjectMethodHierarchy(owner)) return@methods
                val uses = method.findAll(NameExpr::class.java).filter {
                    it.nameAsString == provider.value.nameAsString
                }
                if (uses.isEmpty()) {
                    if (method.parameters.any { index.declaredType(it.type, method) == COMPOUND_TAG }) return@methods
                    pendingUnused += PendingUnusedProvider(
                        DischargedTarget(
                            MethodKey(owner, method.nameAsString, parameterTypes),
                            provider.index,
                            file
                        ),
                        method
                    )
                    return@methods
                }
                val roots = uses.map { use ->
                    val call = use.findAncestor(MethodCallExpr::class.java).orElse(null) ?: return@methods
                    if (call.arguments.none { it === use || it.isAncestorOf(use) }) return@methods
                    val otherRoots = call.arguments
                        .filterNot { it === use || it.isAncestorOf(use) }
                        .mapNotNull { exactRootParameter(it, method, index) }
                        .distinct()
                    if (otherRoots.size != 1) return@methods
                    otherRoots.single()
                }.distinct()
                if (roots.size != 1) return@methods
                val rootName = roots.single()
                val root = NameExpr(rootName)
                val field = index.exactDirectFieldWithType(root, uses.first(), PROVIDER_SOURCE_TYPES) ?: return@methods
                val providerExpression = when (field.second) {
                    HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS -> "$rootName.${field.first}"
                    else -> "$rootName.${field.first}.registryAccess()"
                }
                ensureLexical(file)
                uses.forEach { use -> use.replace(StaticJavaParser.parseExpression(providerExpression)) }
                method.parameters.removeAt(provider.index)
                discharged += DischargedTarget(
                    MethodKey(owner, method.nameAsString, parameterTypes),
                    provider.index,
                    file
                )
            }
        }
        if (pendingUnused.isNotEmpty()) {
            val pendingByName = pendingUnused.groupBy { it.target.key.name }
            val approved = linkedSetOf<PendingUnusedProvider>()
            allFiles.filter { file ->
                val source = file.readText()
                pendingByName.keys.any { source.contains("$it(") }
            }.forEach { file ->
                unit(file).findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                    val candidates = pendingByName[call.nameAsString].orEmpty().filter { pending ->
                        call.arguments.size == pending.target.key.declarationArity - 1
                    }
                    if (candidates.isEmpty()) return@calls
                    val receiverType = index.methodCallReceiverType(call) ?: return@calls
                    val matches = candidates.filter { pending ->
                        index.isTypeAssignableTo(receiverType, pending.target.key.owner) &&
                            index.argumentsMatchTypes(
                                call,
                                pending.target.key.parameterTypes.filterIndexed { parameterIndex, _ ->
                                    parameterIndex != pending.target.providerIndex
                                }
                            ) &&
                            !index.argumentsMatchProjectMethod(call, pending.target.key.owner, call.arguments.size)
                    }
                    if (matches.size > 1) {
                        throw IllegalStateException("Ambiguous unused provider target for '$call': ${matches.map { it.target.key }}")
                    }
                    matches.singleOrNull()?.let(approved::add)
                }
                if (file !in providerDeclarationFiles && file !in discharged.map { it.file }) releaseUnit(file)
            }
            approved.forEach { pending ->
                ensureLexical(pending.target.file)
                pending.method.parameters.removeAt(pending.target.providerIndex)
                discharged += pending.target
            }
        }
        if (discharged.isEmpty()) return emptySet()

        val changedFiles = discharged.mapTo(linkedSetOf()) { it.file }
        val dischargedNames = discharged.mapTo(linkedSetOf()) { it.key.name }
        allFiles.filter { file ->
            val source = file.readText()
            dischargedNames.any { source.contains("$it(") }
        }.forEach { file ->
            var fileChanged = false
            unit(file).findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                val receiverType = index.methodCallReceiverType(call) ?: return@calls
                val targets = discharged.filter { target ->
                    target.key.name == call.nameAsString &&
                        call.arguments.size == target.key.declarationArity &&
                        index.isTypeAssignableTo(receiverType, target.key.owner) &&
                        index.argumentsMatchTypesExcluding(
                            call,
                            target.key.parameterTypes.filterIndexed { parameterIndex, _ ->
                                parameterIndex != target.providerIndex
                            },
                            target.providerIndex
                        )
                }
                if (targets.isEmpty()) return@calls
                if (targets.size > 1) {
                    throw IllegalStateException("Ambiguous discharged provider call '$call': ${targets.map { it.key }}")
                }
                val target = targets.single()
                val providerArgument = call.arguments[target.providerIndex]
                if (index.expressionType(providerArgument, call) != HOLDER_LOOKUP_PROVIDER) return@calls
                if (providerArgument !is NameExpr) {
                    throw IllegalStateException(
                        "Cannot remove side-effect-unknown provider expression '$providerArgument' from $call"
                    )
                }
                ensureLexical(file)
                call.arguments.removeAt(target.providerIndex)
                changedFiles.add(file)
                fileChanged = true
            }
            if (!fileChanged && file !in changedFiles) releaseUnit(file)
        }
        return changedFiles
    }

    private fun exactRootParameter(
        expression: Expression,
        method: MethodDeclaration,
        index: JavaProjectTypeIndex,
        visited: MutableSet<String> = mutableSetOf()
    ): String? = when (expression) {
        is EnclosedExpr -> exactRootParameter(expression.inner, method, index, visited)
        is CastExpr -> exactRootParameter(expression.expression, method, index, visited)
        is FieldAccessExpr -> exactRootParameter(expression.scope, method, index, visited)
        is MethodCallExpr -> expression.scope.orElse(null)?.let { exactRootParameter(it, method, index, visited) }
        is NameExpr -> {
            val name = expression.nameAsString
            if (method.parameters.any { it.nameAsString == name }) {
                name
            } else if (visited.add(name)) {
                index.exactVisibleLocalInitializer(name, expression, method)
                    ?.let { exactRootParameter(it, method, index, visited) }
            } else {
                null
            }
        }
        else -> null
    }

    private fun writeChanges(
        changedFiles: Set<Path>,
        unit: (Path) -> CompilationUnit,
        dryRun: Boolean
    ): List<Change> {
        if (changedFiles.isEmpty()) return emptyList()
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val migratedSources = linkedMapOf<Path, String>()
        changedFiles.forEach { file ->
            val migrated = LexicalPreservingPrinter.print(unit(file))
            val verification = parser.parse(migrated)
            if (!verification.isSuccessful) {
                throw IllegalStateException(
                    "Project provider call graph produced invalid Java in $file: ${verification.problems.joinToString()}"
                )
            }
            migratedSources[file] = migrated
        }
        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changedFiles.map { file ->
            Change(
                file = file,
                line = 1,
                description = "Close exact HolderLookup.Provider arguments across project method call graphs",
                before = "provider-aware project method called without its declared provider",
                after = "provider argument threaded from an exact declared source",
                confidence = Confidence.HIGH,
                ruleId = "struct-project-exact-provider-call-graph"
            )
        }
    }

    private fun findProjectCallSites(
        callee: Demand,
        sources: Map<Path, String>,
        unit: (Path) -> CompilationUnit,
        releaseUnit: (Path) -> Unit,
        protectedFiles: Set<Path>,
        index: JavaProjectTypeIndex
    ): List<Pair<Path, MethodCallExpr>> {
        val result = mutableListOf<Pair<Path, MethodCallExpr>>()
        sources.forEach { (file, source) ->
            if (!source.contains(callee.key.name)) return@forEach
            val sizeBefore = result.size
            unit(file).findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == callee.key.name && it.arguments.size == callee.legacyArity }
                .filter { call ->
                    val receiverType = index.methodCallReceiverType(call) ?: return@filter false
                    index.isTypeAssignableTo(receiverType, callee.key.owner) &&
                        index.argumentsMatchTypes(call, callee.key.parameterTypes)
                }
                .forEach { result += file to it }
            if (result.size == sizeBefore && file !in protectedFiles) releaseUnit(file)
        }
        return result
    }

    private fun declaredOwner(method: MethodDeclaration, file: Path): String =
        declaredOwnerOrNull(method)
            ?: throw IllegalStateException("Cannot resolve owner of ${method.nameAsString} in $file")

    private fun declaredOwnerOrNull(method: MethodDeclaration): String? =
        exactEnclosingNamedClass(method)?.fullyQualifiedName?.orElse(null)

    private fun resolvedParameterTypes(
        method: MethodDeclaration,
        index: JavaProjectTypeIndex
    ): List<String>? {
        val types = mutableListOf<String>()
        method.parameters.forEach { parameter ->
            types += index.declaredType(parameter.type, method) ?: return null
        }
        return types
    }

    private fun exactCallableProviderExpression(
        method: MethodDeclaration,
        index: JavaProjectTypeIndex
    ): String? {
        val candidates = method.parameters.mapNotNull { parameter ->
            val type = index.declaredType(parameter.type, method) ?: return@mapNotNull null
            val name = parameter.nameAsString
            when (type) {
                HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS -> name
                in LEVEL_PROVIDER_TYPES, in ENTITY_PROVIDER_TYPES -> "$name.registryAccess()"
                INVENTORY -> "$name.player.registryAccess()"
                else -> null
            }
        }.distinct()
        if (candidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous exact HolderLookup.Provider parameter sources in ${method.nameAsString}: $candidates"
            )
        }
        candidates.singleOrNull()?.let { return it }
        if (method.isStatic) return null
        val field = index.exactInstanceFieldWithType(method, CALLABLE_PROVIDER_SOURCE_TYPES) ?: return null
        return providerExpression("this.${field.first}", field.second)
    }

    private fun hasDeclaredOrExactProviderRoot(
        callable: CallableDeclaration<*>,
        index: JavaProjectTypeIndex
    ): Boolean {
        val method = callable as? MethodDeclaration ?: return false
        val declared = method.parameters.any { parameter ->
            index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
        }
        if (declared || exactCallableProviderExpression(method, index) != null) return true
        return index.isProvenNonOverride(method)
    }

    private fun providerExpression(expression: String, type: String): String? = when (type) {
        HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS -> expression
        in LEVEL_PROVIDER_TYPES, in ENTITY_PROVIDER_TYPES -> "$expression.registryAccess()"
        INVENTORY -> "$expression.player.registryAccess()"
        else -> null
    }

    private fun enclosingCallable(call: MethodCallExpr): CallableDeclaration<*>? {
        var node = call.parentNode.orElse(null)
        while (node != null) {
            if (node is CallableDeclaration<*>) return node
            node = node.parentNode.orElse(null)
        }
        return null
    }

    private fun uniqueProviderName(method: MethodDeclaration): String {
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
            .findAll(method.toString()).map { it.value }.toSet()
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "modporterRegistries" else "modporterRegistries$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private fun uniqueLambdaName(method: MethodDeclaration, base: String): String {
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
            .findAll(method.toString()).map { it.value }.toSet()
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) base else "$base$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private companion object {
        const val HOLDER_LOOKUP_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
        const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
        const val REGISTRY_ACCESS = "net.minecraft.core.RegistryAccess"
        const val INVENTORY = "net.minecraft.world.entity.player.Inventory"
        val LEVEL_PROVIDER_TYPES = setOf(
            "net.minecraft.world.level.Level",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.world.level.WorldGenLevel",
            "net.minecraft.world.level.LevelAccessor",
            "net.minecraft.world.level.ServerLevelAccessor"
        )
        val ENTITY_PROVIDER_TYPES = setOf(
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.server.level.ServerPlayer"
        )
        val CALLABLE_PROVIDER_SOURCE_TYPES = LEVEL_PROVIDER_TYPES + ENTITY_PROVIDER_TYPES + setOf(
            HOLDER_LOOKUP_PROVIDER,
            REGISTRY_ACCESS,
            INVENTORY
        )
        val PROVIDER_SOURCE_TYPES = setOf(
            HOLDER_LOOKUP_PROVIDER,
            REGISTRY_ACCESS,
        ) + LEVEL_PROVIDER_TYPES
    }
}
