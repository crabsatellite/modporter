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
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.BreakStmt
import com.github.javaparser.ast.stmt.ContinueStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
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
        val rooted: Boolean,
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

    private data class SeededOverrideFamilies(
        val changedFiles: Set<Path>,
        val methods: Set<MethodDeclaration>
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
        val providerDeclarationFiles = files.filterTo(linkedSetOf()) { file ->
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
            providerDeclarationFiles.toList(),
            files,
            ::unit,
            ::ensureLexical,
            ::releaseUnit,
            index
        )

        val seedTargets = collectProviderTargets(providerDeclarationFiles, ::unit, index)
        val seededFamilies = seedProviderDemandOverrideFamilies(
            seedTargets,
            files,
            sources,
            providerDeclarationFiles,
            ::unit,
            ::ensureLexical,
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
                if (!index.hasClosedProjectMethodHierarchy(owner) && method !in seededFamilies.methods) return@methods
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
        fun demandFor(file: Path, callable: CallableDeclaration<*>, use: com.github.javaparser.ast.Node): Demand {
            val method = callable as? MethodDeclaration ?: throw IllegalStateException(
                "HolderLookup.Provider propagation cannot change constructor '${callable.nameAsString}' in $file"
            )
            val owner = declaredOwner(method, file)
            val parameterTypes = resolvedParameterTypes(method, index) ?: throw IllegalStateException(
                "Cannot resolve complete parameter types for provider-demanded method $owner.${method.nameAsString}"
            )
            val key = MethodKey(owner, method.nameAsString, parameterTypes)
            val providerParameters = method.parameters.withIndex().filter { (_, parameter) ->
                index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
            }
            if (providerParameters.size > 1) {
                throw IllegalStateException("Method $owner.${method.nameAsString} has multiple HolderLookup.Provider parameters")
            }
            val existing = providerParameters.singleOrNull()
            val contextProvider = if (existing == null) exactCallableProviderExpression(method, use, index) else null
            val hadProvider = existing != null || contextProvider != null
            val canAddProvider = existing != null || contextProvider != null ||
                index.isProvenNonOverride(method)
            val providerIndex = existing?.index ?: method.parameters.size
            val providerName = existing?.value?.nameAsString ?: contextProvider ?: uniqueProviderName(method)
            val candidate = Demand(
                key = key,
                file = file,
                method = method,
                providerIndex = providerIndex,
                providerName = providerName,
                legacyArity = method.parameters.size - if (existing == null) 0 else 1,
                hadProvider = hadProvider,
                rooted = hadProvider || isExplicitProjectApiBoundary(method, index),
                canAddProvider = canAddProvider
            )
            demands[key]?.let { previous ->
                if (previous.providerName != candidate.providerName ||
                    previous.hadProvider != candidate.hadProvider ||
                    previous.rooted != candidate.rooted ||
                    previous.canAddProvider != candidate.canAddProvider
                ) {
                    throw IllegalStateException(
                        "Provider source for $owner.${method.nameAsString} is not valid at every demanded call site: " +
                            "'${previous.providerName}' versus '${candidate.providerName}'"
                    )
                }
                return previous
            }
            return candidate.also { demands[key] = it }
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
        val unseededDemands = mutableListOf<String>()
        candidateFiles.forEach { file ->
            val siteCountBefore = initialSites.size + initialReferenceSites.size
            unit(file).findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                val namedTargets = targetsByName[call.nameAsString].orEmpty()
                if (namedTargets.isEmpty()) return@calls
                val receiverType = index.methodCallReceiverType(call) ?: return@calls
                val matchingTargets = mostSpecificTargets(namedTargets.filter { target ->
                    target.legacyArity == call.arguments.size &&
                        index.isTypeAssignableTo(receiverType, target.key.owner) &&
                        index.argumentsMatchTypes(
                            call,
                            target.key.parameterTypes.filterIndexed { parameterIndex, _ ->
                                parameterIndex != target.providerIndex
                            }
                        )
                }, index)
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
                if (!index.hasClosedProjectMethodHierarchy(targetOwner) && target.method !in seededFamilies.methods) {
                    throw IllegalStateException(
                        "Cannot prove inherited overload closure for provider-aware project method " +
                            "$targetOwner.${call.nameAsString}/${target.key.declarationArity}"
                    )
                }
                val callable = enclosingCallable(call) ?: throw IllegalStateException(
                    "Call to $targetOwner.${call.nameAsString} is outside a callable in $file"
                )
                if (!hasDeclaredOrExactProviderRoot(callable, call, index)) {
                    unseededDemands += "'$call' in $file"
                    return@calls
                }
                initialSites += InitialSite(file, call, demandFor(file, callable, call), target)
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
                if (!index.hasClosedProjectMethodHierarchy(targetOwner) && target.method !in seededFamilies.methods) {
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
                if (!hasDeclaredOrExactProviderRoot(callable, reference, index)) {
                    unseededDemands += "'$reference' in $file"
                    return@references
                }
                initialReferenceSites += InitialReferenceSite(
                    file,
                    reference,
                    demandFor(file, callable, reference),
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
        if (unseededDemands.isNotEmpty()) {
            throw IllegalStateException(
                "Unseeded exact provider demands: ${unseededDemands.distinct()}"
            )
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
                val caller = demandFor(file, callable, call)
                protectedFiles.add(file)
                val siteKey = "$file:${call.range.orElseThrow()}:${callee.key}"
                projectCallSites[siteKey] = ProjectCallSite(file, call, caller, callee)
                if (!caller.hadProvider) queue.addLast(caller)
            }
        }

        val reachable = demands.values.filter { it.rooted }.mapTo(linkedSetOf()) { it.key }
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

        val unresolvedCalls = initialSites.filter { it.caller.key !in reachable }.map { it.call.toString() } +
            initialReferenceSites.filter { it.caller.key !in reachable }.map { it.reference.toString() }
        if (unresolvedCalls.isNotEmpty()) {
            throw IllegalStateException(
                "Exact provider demands do not reach a declared provider boundary: ${unresolvedCalls.distinct()}"
            )
        }

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
        changedFiles += seededFamilies.changedFiles
        changedFiles += initialSites.filter { it.caller.key in reachable }.map { it.file }
        changedFiles += initialReferenceSites.filter { it.caller.key in reachable }.map { it.file }
        changedFiles += demands.values.filter { !it.hadProvider && it.key in reachable }.map { it.file }
        changedFiles += projectCallSites.values
            .filter { it.caller.key in reachable && it.callee.key in reachable }
            .map { it.file }
        return writeChanges(changedFiles, unit = ::unit, dryRun = dryRun)
    }

    private fun collectProviderTargets(
        providerDeclarationFiles: Collection<Path>,
        unit: (Path) -> CompilationUnit,
        index: JavaProjectTypeIndex,
        provenFamilyMethods: Set<MethodDeclaration> = emptySet()
    ): List<ProviderTarget> = providerDeclarationFiles.flatMap { file ->
        unit(file).findAll(MethodDeclaration::class.java).mapNotNull { method ->
            val owner = declaredOwnerOrNull(method) ?: return@mapNotNull null
            val providers = method.parameters.withIndex().filter { (_, parameter) ->
                index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
            }
            if (providers.size > 1) {
                throw IllegalStateException(
                    "Method ${method.nameAsString} in $file has multiple HolderLookup.Provider parameters"
                )
            }
            val provider = providers.singleOrNull() ?: return@mapNotNull null
            if (!index.hasClosedProjectMethodHierarchy(owner) && method !in provenFamilyMethods) return@mapNotNull null
            val parameterTypes = resolvedParameterTypes(method, index) ?: return@mapNotNull null
            ProviderTarget(
                MethodKey(owner, method.nameAsString, parameterTypes),
                provider.index,
                method.parameters.size - 1,
                index.declaredType(method.type, method),
                method
            )
        }
    }

    private fun seedProviderDemandOverrideFamilies(
        targets: List<ProviderTarget>,
        files: List<Path>,
        sources: Map<Path, String>,
        providerDeclarationFiles: MutableSet<Path>,
        unit: (Path) -> CompilationUnit,
        ensureLexical: (Path) -> Unit,
        index: JavaProjectTypeIndex
    ): SeededOverrideFamilies {
        if (targets.isEmpty()) return SeededOverrideFamilies(emptySet(), emptySet())
        val seeded = linkedMapOf<String, List<JavaProjectTypeIndex.ExactProjectMethod>>()
        val changedFiles = linkedSetOf<Path>()
        val changedMethods = linkedSetOf<MethodDeclaration>()
        var currentTargets = targets
        while (true) {
            val targetsByName = currentTargets.groupBy { it.key.name }
            val newlySeeded = linkedMapOf<String, List<JavaProjectTypeIndex.ExactProjectMethod>>()
            files.filter { file -> targetsByName.keys.any { sources.getValue(file).contains("$it(") } }
                .forEach { file ->
                    unit(file).findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                        val receiverType = index.methodCallReceiverType(call) ?: return@calls
                        val matches = mostSpecificTargets(targetsByName[call.nameAsString].orEmpty().filter { target ->
                            target.legacyArity == call.arguments.size &&
                                index.isTypeAssignableTo(receiverType, target.key.owner) &&
                                index.argumentsMatchTypes(
                                    call,
                                    target.key.parameterTypes.filterIndexed { parameterIndex, _ ->
                                        parameterIndex != target.providerIndex
                                    }
                                )
                        }, index)
                        if (matches.isEmpty()) return@calls
                        if (matches.size > 1) {
                            throw IllegalStateException(
                                "Ambiguous provider family seed target '$call': ${matches.map { it.key }}"
                            )
                        }
                        val caller = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return@calls
                        if (hasStrictProviderRoot(caller, call, index)) return@calls
                        val family = index.exactProjectOverrideFamily(caller) { root ->
                            index.isProvenNonOverride(root) ||
                                ExactExternalProviderContracts.isProvenProjectHelperRoot(root, index)
                        } ?: return@calls
                        if (!familyHasExactProjectBoundaryOrAbstractApiRoot(family, files, sources, unit, index)) {
                            return@calls
                        }
                        val key = family.joinToString("|") {
                            "${it.owner}.${it.method.nameAsString}/${it.method.parameters.size}"
                        }
                        if (key !in seeded) newlySeeded.putIfAbsent(key, family)
                    }
                }
            if (newlySeeded.isEmpty()) break
            val roundMethods = linkedSetOf<MethodDeclaration>()
            val roundFiles = linkedSetOf<Path>()
            newlySeeded.forEach { (key, family) ->
                seeded[key] = family
                family.forEach { member ->
                    val providers = member.method.parameters.filter { parameter ->
                        index.declaredType(parameter.type, member.method) == HOLDER_LOOKUP_PROVIDER
                    }
                    if (providers.isNotEmpty()) {
                        throw IllegalStateException(
                            "Partially provider-aware project override family contains " +
                                "${member.owner}.${member.method.nameAsString}"
                        )
                    }
                    ensureLexical(member.file)
                    member.method.addParameter(
                        HOLDER_LOOKUP_PROVIDER,
                        uniqueProviderName(member.method)
                    )
                    providerDeclarationFiles.add(member.file)
                    changedFiles.add(member.file)
                    changedMethods.add(member.method)
                    roundFiles.add(member.file)
                    roundMethods.add(member.method)
                }
            }
            currentTargets = collectProviderTargets(
                roundFiles,
                unit,
                index,
                roundMethods
            ).filter { it.method in roundMethods }
        }
        return SeededOverrideFamilies(changedFiles, changedMethods)
    }

    private fun familyHasExactProjectBoundaryOrAbstractApiRoot(
        family: List<JavaProjectTypeIndex.ExactProjectMethod>,
        files: List<Path>,
        sources: Map<Path, String>,
        unit: (Path) -> CompilationUnit,
        index: JavaProjectTypeIndex
    ): Boolean {
        val owners = family.mapTo(linkedSetOf()) { it.owner }
        val methodName = family.first().method.nameAsString
        val parameterTypes = family.first().method.parameters.map { parameter ->
            index.declaredType(parameter.type, family.first().method) ?: return false
        }
        val boundaries = mutableListOf<Pair<MethodDeclaration, MethodCallExpr>>()
        files.filter { sources.getValue(it).contains("$methodName(") }.forEach { file ->
            unit(file).findAll(MethodCallExpr::class.java).forEach calls@{ call ->
                if (call.nameAsString != methodName || call.arguments.size != parameterTypes.size) return@calls
                val receiverType = index.methodCallReceiverType(call) ?: return@calls
                if (owners.none { owner -> index.isTypeAssignableTo(receiverType, owner) }) return@calls
                if (!index.argumentsMatchTypes(call, parameterTypes) &&
                    !index.resolvesToUniqueProjectMethodSignature(call, parameterTypes)
                ) return@calls
                val caller = call.findAncestor(MethodDeclaration::class.java).orElse(null) ?: return false
                val callerOwner = declaredOwnerOrNull(caller) ?: return false
                if (callerOwner in owners && caller.nameAsString == methodName) return@calls
                boundaries += caller to call
            }
        }
        if (boundaries.isNotEmpty()) return true
        val roots = family.filter { candidate ->
            family.none { other ->
                other !== candidate && index.isTypeAssignableTo(candidate.owner, other.owner)
            }
        }
        val root = roots.singleOrNull() ?: return false
        val owner = exactEnclosingNamedClass(root.method) as? com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
            ?: return false
        val isAbstractContract = root.method.isAbstract || (owner.isInterface && root.method.body.isEmpty)
        val isExternallyVisible = owner.isInterface || root.method.isPublic || root.method.isProtected
        return isAbstractContract && isExternallyVisible
    }

    private fun hasStrictProviderRoot(
        method: MethodDeclaration,
        use: com.github.javaparser.ast.Node,
        index: JavaProjectTypeIndex
    ): Boolean = method.parameters.any { parameter ->
        index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
    } || exactCallableProviderExpression(method, use, index) != null

    private fun mostSpecificTargets(
        targets: List<ProviderTarget>,
        index: JavaProjectTypeIndex
    ): List<ProviderTarget> = targets.filter { candidate ->
        targets.none { other ->
            other !== candidate &&
                index.isTypeAssignableTo(other.key.owner, candidate.key.owner) &&
                !index.isTypeAssignableTo(candidate.key.owner, other.key.owner)
        }
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
                val calls = uses.map { use ->
                    val call = use.findAncestor(MethodCallExpr::class.java).orElse(null) ?: return@methods
                    if (call.arguments.none { it === use || it.isAncestorOf(use) }) return@methods
                    call
                }
                val declaredAlternative = exactAlternativeDeclaredProviderExpression(
                    method,
                    provider.value,
                    uses,
                    index
                )
                val roots = calls.mapIndexed { callIndex, call ->
                    val use = uses[callIndex]
                    val otherRoots = call.arguments
                        .filterNot { it === use || it.isAncestorOf(use) }
                        .mapNotNull { exactRootParameter(it, method, index) }
                        .distinct()
                    if (otherRoots.size != 1) return@mapIndexed null
                    otherRoots.single()
                }.filterNotNull().distinct()
                val providerExpression = declaredAlternative ?: run {
                    if (roots.size != 1) return@methods
                    val rootName = roots.single()
                    val root = NameExpr(rootName)
                    val field = index.exactDirectFieldWithType(root, uses.first(), PROVIDER_SOURCE_TYPES)
                        ?: return@methods
                    when (field.second) {
                        HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS -> "$rootName.${field.first}"
                        else -> "$rootName.${field.first}.registryAccess()"
                    }
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
                if (!isSideEffectFreeProviderArgument(providerArgument, call, index)) {
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

    private fun exactAlternativeDeclaredProviderExpression(
        method: MethodDeclaration,
        provider: Parameter,
        uses: List<NameExpr>,
        index: JavaProjectTypeIndex
    ): String? {
        val typed = method.parameters.filterNot { it === provider }.mapNotNull { parameter ->
            val type = index.declaredType(parameter.type, method) ?: return@mapNotNull null
            Triple(parameter, type, parameter.nameAsString)
        }
        fun unique(candidates: List<String>): String? = candidates.distinct().singleOrNull()
        unique(typed.mapNotNull { (parameter, type, name) ->
            name.takeIf {
                type in setOf(HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS) &&
                    uses.all { use -> parameterProvenNonNullAt(parameter, method, use) }
            }
        })?.let { return it }
        return unique(typed.mapNotNull { (parameter, type, name) ->
            "$name.registryAccess()".takeIf {
                (LEVEL_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ||
                    ENTITY_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) }) &&
                    uses.all { use -> parameterProvenNonNullAt(parameter, method, use) }
            }
        })
    }

    private fun isSideEffectFreeProviderArgument(
        expression: Expression,
        use: MethodCallExpr,
        index: JavaProjectTypeIndex
    ): Boolean {
        if (expression is NameExpr) {
            return index.expressionType(expression, use) in setOf(HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS)
        }
        val accessor = expression as? MethodCallExpr ?: return false
        if (accessor.nameAsString != "registryAccess" || accessor.arguments.isNotEmpty()) return false
        val scope = accessor.scope.orElse(null) as? NameExpr ?: return false
        val receiverType = index.expressionType(scope, accessor) ?: return false
        return PROVIDER_ACCESSOR_RECEIVER_TYPES.any { expected ->
            index.isTypeAssignableTo(receiverType, expected)
        }
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
        use: com.github.javaparser.ast.Node,
        index: JavaProjectTypeIndex
    ): String? {
        ExactExternalProviderContracts.providerExpression(method, index)?.let { return it }
        val candidates = method.parameters.mapNotNull { parameter ->
            val type = index.declaredType(parameter.type, method) ?: return@mapNotNull null
            val name = parameter.nameAsString
            when (type) {
                HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS -> name
                INVENTORY -> "$name.player.registryAccess()"
                    .takeIf { parameterProvenNonNullAt(parameter, method, use) }
                MINECRAFT -> "$name.level.registryAccess()"
                    .takeIf {
                        parameterProvenNonNullAt(parameter, method, use) &&
                            expressionProvenNonNullAt("$name.level", method, use)
                    }
                in PONDER_SCENE_PROVIDER_TYPES -> "$name.world().getHolderLookupProvider()"
                    .takeIf { parameterProvenNonNullAt(parameter, method, use) }
                else -> "$name.registryAccess()".takeIf {
                    (LEVEL_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ||
                        ENTITY_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) }) &&
                        parameterProvenNonNullAt(parameter, method, use)
                } ?: "$name.world().getHolderLookupProvider()".takeIf {
                    PONDER_SCENE_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } &&
                        parameterProvenNonNullAt(parameter, method, use)
                }
            }
        }.toMutableList()
        method.parameters.forEach { parameter ->
            if (!parameterProvenNonNullAt(parameter, method, use)) return@forEach
            val field = index.exactDirectFieldWithType(
                NameExpr(parameter.nameAsString),
                use,
                CALLABLE_PROVIDER_SOURCE_TYPES
            ) ?: return@forEach
            providerExpression("${parameter.nameAsString}.${field.first}", field.second, index)?.let(candidates::add)
        }
        val localCandidates = exactVisibleLocalProviderExpressions(method, use, index)
        candidates += localCandidates
        if (!method.isStatic) {
            index.exactInstanceFieldWithType(method, CALLABLE_PROVIDER_SOURCE_TYPES)?.let { field ->
                providerExpression("this.${field.first}", field.second, index)?.let(candidates::add)
            }
        }
        val aliases = exactVisibleLocalProviderAliases(method, use, index)
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (source, targets) ->
                targets.distinct().singleOrNull()?.let { target -> source to target }
            }
            .toMap()
        val distinctCandidates = candidates.map { candidate -> aliases[candidate] ?: candidate }.distinct()
        if (distinctCandidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous exact HolderLookup.Provider sources in ${method.nameAsString}: $distinctCandidates"
            )
        }
        distinctCandidates.singleOrNull()?.let { return it }
        exactLocalMinecraftProviderExpression(method, use, index)?.let { return it }
        return null
    }

    private fun exactVisibleLocalProviderExpressions(
        method: MethodDeclaration,
        use: com.github.javaparser.ast.Node,
        index: JavaProjectTypeIndex
    ): List<String> {
        val useBegin = use.range.orElse(null)?.begin ?: return emptyList()
        return method.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
            val type = index.declaredType(variable.type, variable) ?: return@mapNotNull null
            val expression = when {
                type in setOf(HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS) -> variable.nameAsString
                LEVEL_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ||
                    ENTITY_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ->
                    "${variable.nameAsString}.registryAccess()"
                type in PONDER_SCENE_PROVIDER_TYPES ||
                    PONDER_SCENE_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ->
                    "${variable.nameAsString}.world().getHolderLookupProvider()"
                else -> return@mapNotNull null
            }
            val declarationEnd = variable.range.orElse(null)?.end ?: return@mapNotNull null
            if (declarationEnd >= useBegin) return@mapNotNull null
            val lexicalScope = variable.findAncestor(BlockStmt::class.java).orElse(null) ?: return@mapNotNull null
            if (!(lexicalScope === use || lexicalScope.isAncestorOf(use))) return@mapNotNull null
            expression
        }.distinct()
    }

    private fun exactVisibleLocalProviderAliases(
        method: MethodDeclaration,
        use: com.github.javaparser.ast.Node,
        index: JavaProjectTypeIndex
    ): List<Pair<String, String>> {
        val useBegin = use.range.orElse(null)?.begin ?: return emptyList()
        return method.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
            val type = index.declaredType(variable.type, variable) ?: return@mapNotNull null
            val target = providerExpression(variable.nameAsString, type, index) ?: return@mapNotNull null
            val declarationEnd = variable.range.orElse(null)?.end ?: return@mapNotNull null
            if (declarationEnd >= useBegin) return@mapNotNull null
            val lexicalScope = variable.findAncestor(BlockStmt::class.java).orElse(null) ?: return@mapNotNull null
            if (!(lexicalScope === use || lexicalScope.isAncestorOf(use))) return@mapNotNull null
            val reassigned = method.findAll(AssignExpr::class.java).any { assignment ->
                val targetName = assignment.target as? NameExpr ?: return@any false
                if (targetName.nameAsString != variable.nameAsString) return@any false
                val assignmentBegin = assignment.range.orElse(null)?.begin ?: return@any true
                assignmentBegin > declarationEnd && assignmentBegin < useBegin
            }
            if (reassigned) return@mapNotNull null
            val initializer = variable.initializer.orElse(null) ?: return@mapNotNull null
            if (initializer !is NameExpr && initializer !is FieldAccessExpr) return@mapNotNull null
            val initializerType = index.expressionType(initializer, variable) ?: return@mapNotNull null
            val source = providerExpression(initializer.toString(), initializerType, index) ?: return@mapNotNull null
            source to target
        }
    }

    private fun parameterProvenNonNullAt(
        parameter: Parameter,
        method: MethodDeclaration,
        use: com.github.javaparser.ast.Node
    ): Boolean {
        val name = parameter.nameAsString
        val nullableEvidence = parameter.annotations.any { it.name.identifier == "Nullable" } ||
            method.findAll(BinaryExpr::class.java).any { isNullComparison(it, name) }
        if (!nullableEvidence) return true

        return expressionProvenNonNullAt(name, method, use)
    }

    private fun expressionProvenNonNullAt(
        expression: String,
        method: MethodDeclaration,
        use: com.github.javaparser.ast.Node
    ): Boolean {
        var ancestor: com.github.javaparser.ast.Node? = use
        while (ancestor != null && ancestor !== method) {
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
            val guard = statement as? IfStmt ?: return@any false
            conditionTrueProvesNull(guard.condition, expression) && statementAlwaysExits(guard.thenStmt)
        }
    }

    private fun isNullComparison(expression: BinaryExpr, expected: String): Boolean =
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

    private fun hasDeclaredOrExactProviderRoot(
        callable: CallableDeclaration<*>,
        use: com.github.javaparser.ast.Node,
        index: JavaProjectTypeIndex
    ): Boolean {
        val method = callable as? MethodDeclaration ?: return false
        val declared = method.parameters.any { parameter ->
            index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
        }
        if (declared || exactCallableProviderExpression(method, use, index) != null) return true
        return index.isProvenNonOverride(method)
    }

    private fun isExplicitProjectApiBoundary(
        method: MethodDeclaration,
        index: JavaProjectTypeIndex
    ): Boolean = (method.isPublic || method.isProtected) && index.isProvenNonOverride(method)

    private fun exactLocalMinecraftProviderExpression(
        method: MethodDeclaration,
        use: com.github.javaparser.ast.Node,
        index: JavaProjectTypeIndex
    ): String? {
        val useBegin = use.range.orElse(null)?.begin ?: return null
        val candidates = method.findAll(VariableDeclarator::class.java).mapNotNull { variable ->
            if (index.declaredType(variable.type, variable) != MINECRAFT) return@mapNotNull null
            val initializer = variable.initializer.orElse(null) as? MethodCallExpr ?: return@mapNotNull null
            if (initializer.nameAsString != "getInstance" || initializer.arguments.isNotEmpty()) return@mapNotNull null
            val scope = initializer.scope.orElse(null)?.toString()?.replace(Regex("\\s+"), "")
                ?: return@mapNotNull null
            if (scope !in setOf("Minecraft", MINECRAFT)) return@mapNotNull null
            val declarationEnd = variable.range.orElse(null)?.end ?: return@mapNotNull null
            if (declarationEnd >= useBegin) return@mapNotNull null
            val lexicalScope = variable.findAncestor(BlockStmt::class.java).orElse(null) ?: return@mapNotNull null
            if (!(lexicalScope === use || lexicalScope.isAncestorOf(use))) return@mapNotNull null
            val reassigned = method.findAll(AssignExpr::class.java).any { assignment ->
                val target = assignment.target as? NameExpr ?: return@any false
                if (target.nameAsString != variable.nameAsString) return@any false
                val assignmentBegin = assignment.range.orElse(null)?.begin ?: return@any true
                assignmentBegin > declarationEnd && assignmentBegin < useBegin
            }
            if (reassigned) return@mapNotNull null
            if (!expressionProvenNonNullAt("${variable.nameAsString}.level", method, use)) return@mapNotNull null
            variable.nameAsString
        }.distinct()
        if (candidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous source-proven Minecraft registry providers in ${method.nameAsString}: $candidates"
            )
        }
        return candidates.singleOrNull()?.let { "$it.level.registryAccess()" }
    }

    private fun providerExpression(
        expression: String,
        type: String,
        index: JavaProjectTypeIndex
    ): String? = when {
        type in setOf(HOLDER_LOOKUP_PROVIDER, REGISTRY_ACCESS) -> expression
        type == INVENTORY -> "$expression.player.registryAccess()"
        LEVEL_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ||
            ENTITY_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ->
            "$expression.registryAccess()"
        PONDER_SCENE_PROVIDER_TYPES.any { expected -> index.isTypeAssignableTo(type, expected) } ->
            "$expression.world().getHolderLookupProvider()"
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
        const val MINECRAFT = "net.minecraft.client.Minecraft"
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
        val PONDER_SCENE_PROVIDER_TYPES = setOf(
            "net.createmod.ponder.api.scene.SceneBuilder",
            "net.createmod.ponder.foundation.PonderSceneBuilder"
        )
        val CALLABLE_PROVIDER_SOURCE_TYPES = LEVEL_PROVIDER_TYPES + ENTITY_PROVIDER_TYPES + setOf(
            HOLDER_LOOKUP_PROVIDER,
            REGISTRY_ACCESS,
            INVENTORY
        ) + PONDER_SCENE_PROVIDER_TYPES
        val PROVIDER_SOURCE_TYPES = setOf(
            HOLDER_LOOKUP_PROVIDER,
            REGISTRY_ACCESS,
        ) + LEVEL_PROVIDER_TYPES
        val PROVIDER_ACCESSOR_RECEIVER_TYPES = LEVEL_PROVIDER_TYPES + ENTITY_PROVIDER_TYPES
    }
}
