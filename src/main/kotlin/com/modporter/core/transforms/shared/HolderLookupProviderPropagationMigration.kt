package com.modporter.core.transforms.shared

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.type.Type
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * Threads the registry future required by migrated data-provider constructors
 * through project-local Java call graphs. Every value comes from an exact
 * parameter/local type or a typed GatherDataEvent parameter.
 */
class HolderLookupProviderPropagationMigration {
    private data class SourceUnit(
        val file: Path,
        val source: String,
        val compilationUnit: CompilationUnit,
        val lineOffsets: IntArray
    )

    private data class ConstructorTarget(
        val ownerQualified: String,
        val ownerSimple: String,
        val oldArity: Int,
        val providerIndex: Int
    )

    private data class CallableKey(
        val ownerQualified: String,
        val ownerSimple: String,
        val name: String,
        val arity: Int,
        val constructor: Boolean,
        val sourceId: String,
        val declarationOffset: Int
    )

    private data class CallableSite(
        val key: CallableKey,
        val unit: SourceUnit,
        val declaration: CallableDeclaration<*>
    )

    private data class InitialSite(
        val unit: SourceUnit,
        val creation: ObjectCreationExpr,
        val target: ConstructorTarget,
        val caller: CallableSite
    )

    private data class ConstructorReferenceSite(
        val unit: SourceUnit,
        val reference: MethodReferenceExpr,
        val cast: CastExpr,
        val target: ConstructorTarget,
        val caller: CallableSite
    )

    private data class CallSite(
        val unit: SourceUnit,
        val call: MethodCallExpr,
        val caller: CallableSite,
        val callee: CallableKey,
        val existingProvider: String?
    )

    private data class Edit(val offset: Int, val text: String, val removeLength: Int = 0)

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()

        val files = Files.walk(sourceRoot)
            .filter { it.extension == "java" }
            .toList()
        if (files.isEmpty()) return emptyList()
        val sources = files.associateWith { it.readText() }
        if (sources.values.none { it.contains("CompletableFuture") && it.contains("HolderLookup.Provider") }) {
            return emptyList()
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val parsed = linkedMapOf<Path, SourceUnit>()

        fun parse(file: Path): SourceUnit {
            parsed[file]?.let { return it }
            val source = sources.getValue(file)
            val result = parser.parse(source)
            if (!result.isSuccessful) {
                throw IllegalStateException(
                    "Cannot parse $file while threading HolderLookup.Provider: " +
                        result.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            return SourceUnit(file, source, result.result.orElseThrow(), lineOffsets(source))
                .also { parsed[file] = it }
        }

        sources.filterValues { source ->
            source.contains("CompletableFuture") && source.contains("HolderLookup.Provider") && source.contains("class ")
        }.keys.forEach(::parse)

        val constructorTargets = collectConstructorTargets(parsed.values)
        if (constructorTargets.isEmpty()) return emptyList()
        val targetsBySimpleName = constructorTargets.groupBy { it.ownerSimple }
        val ambiguousTargetNames = targetsBySimpleName.filterValues { targets ->
            targets.map { it.ownerQualified }.distinct().size > 1
        }.keys

        sources.forEach { (file, source) ->
            if (constructorTargets.any { source.contains("new ${it.ownerSimple}") }) parse(file)
        }

        val callableSites = linkedMapOf<CallableKey, CallableSite>()
        fun indexCallables(unit: SourceUnit) {
            unit.compilationUnit.findAll(MethodDeclaration::class.java).forEach { method ->
                val site = callableSite(unit, method) ?: return@forEach
                callableSites.putIfAbsent(site.key, site)
            }
            unit.compilationUnit.findAll(ConstructorDeclaration::class.java).forEach { constructor ->
                val site = callableSite(unit, constructor) ?: return@forEach
                callableSites.putIfAbsent(site.key, site)
            }
        }
        parsed.values.forEach(::indexCallables)

        val initialSites = mutableListOf<InitialSite>()
        parsed.values.forEach { unit ->
            unit.compilationUnit.findAll(ObjectCreationExpr::class.java).forEach { creation ->
                val simpleName = creation.type.nameAsString
                if (simpleName !in targetsBySimpleName) return@forEach
                if (simpleName in ambiguousTargetNames && creation.type.scope.isEmpty) {
                    throw IllegalStateException("Ambiguous migrated provider constructor type $simpleName in ${unit.file}")
                }
                val target = resolveConstructorTarget(unit, creation, targetsBySimpleName.getValue(simpleName))
                    ?: return@forEach
                if (creation.arguments.size != target.oldArity) return@forEach
                val caller = enclosingCallable(unit, creation) ?: throw IllegalStateException(
                    "Migrated provider constructor ${target.ownerQualified} is called outside a Java method or constructor in ${unit.file}"
                )
                initialSites += InitialSite(unit, creation, target, caller)
                callableSites.putIfAbsent(caller.key, caller)
            }
        }
        if (initialSites.isEmpty()) return emptyList()

        val constructorReferences = mutableListOf<ConstructorReferenceSite>()
        parsed.values.forEach { unit ->
            unit.compilationUnit.findAll(MethodReferenceExpr::class.java)
                .filter { it.identifier == "new" }
                .forEach { reference ->
                    val owner = methodReferenceOwner(reference)
                    val targets = constructorTargets.filter { owner == it.ownerSimple || owner == it.ownerQualified }
                    if (targets.isEmpty()) return@forEach
                    if (targets.size != 1) {
                        throw IllegalStateException("Ambiguous migrated constructor reference $reference in ${unit.file}")
                    }
                    val target = targets.single()
                    val cast = reference.parentNode.orElse(null) as? CastExpr
                        ?: throw IllegalStateException(
                            "Cannot prove the functional arity of migrated constructor reference $reference in ${unit.file}; " +
                                "an explicit DataProvider.Factory cast is required"
                        )
                    val normalizedCast = cast.type.asString().replace(Regex("\\s+"), "")
                    val expectedSimple = "DataProvider.Factory<${target.ownerSimple}>"
                    val expectedQualified = "net.minecraft.data.DataProvider.Factory<${target.ownerQualified}>"
                    if (normalizedCast != expectedSimple && normalizedCast != expectedQualified) {
                        throw IllegalStateException(
                            "Migrated constructor reference $reference in ${unit.file} has unsupported functional type ${cast.type}"
                        )
                    }
                    if (target.oldArity != 1) {
                        throw IllegalStateException(
                            "DataProvider.Factory constructor reference ${target.ownerQualified} must have exactly one legacy argument"
                        )
                    }
                    val caller = enclosingCallable(unit, reference) ?: throw IllegalStateException(
                        "Migrated provider constructor reference $reference is outside a Java method or constructor in ${unit.file}"
                    )
                    constructorReferences += ConstructorReferenceSite(unit, reference, cast, target, caller)
                    callableSites.putIfAbsent(caller.key, caller)
                }
        }

        val demanded = linkedSetOf<CallableKey>()
        val providerNames = linkedMapOf<CallableKey, String>()
        initialSites.forEach { site ->
            val provider = exactProviderExpression(site.caller, nodeStartOffset(site.unit, site.creation))
            if (provider == null) {
                demanded += site.caller.key
                providerNames.getOrPut(site.caller.key) { uniqueProviderName(site.caller) }
            }
        }
        constructorReferences.forEach { site ->
            val provider = exactProviderExpression(site.caller, nodeStartOffset(site.unit, site.reference))
            if (provider == null) {
                demanded += site.caller.key
                providerNames.getOrPut(site.caller.key) { uniqueProviderName(site.caller) }
            }
        }

        val callsByCallee = linkedMapOf<CallableKey, List<CallSite>>()
        val queue = ArrayDeque<CallableKey>()
        queue.addAll(demanded)
        val expanded = linkedSetOf<CallableKey>()
        while (queue.isNotEmpty()) {
            val callee = queue.removeFirst()
            if (!expanded.add(callee)) continue
            ensureCandidateCallersParsed(callee, sources, parsed, ::parse, ::indexCallables)
            val declaration = callableSites[callee]
                ?: throw IllegalStateException("Cannot resolve demanded Java callable ${callee.ownerQualified}.${callee.name}/${callee.arity}")
            val sameErasure = callableSites.keys.filter {
                it.ownerQualified == callee.ownerQualified &&
                    it.name == callee.name &&
                    it.arity == callee.arity &&
                    it.constructor == callee.constructor
            }
            if (sameErasure.size != 1) {
                throw IllegalStateException(
                    "Cannot resolve overloaded demanded Java callable ${callee.ownerQualified}.${callee.name}/${callee.arity} " +
                        "without argument type proof"
                )
            }
            if (declaration.declaration is MethodDeclaration && declaration.declaration.isAbstract) {
                throw IllegalStateException("Cannot thread HolderLookup.Provider through abstract method ${callee.ownerQualified}.${callee.name}")
            }
            val callSites = findCallSites(callee, parsed.values, callableSites)
            if (callSites.isEmpty()) {
                throw IllegalStateException(
                    "Cannot prove a project-local caller for ${callee.ownerQualified}.${callee.name}/${callee.arity}; " +
                        "no HolderLookup.Provider fallback is allowed"
                )
            }
            callsByCallee[callee] = callSites
            callSites.forEach { callSite ->
                if (callSite.existingProvider == null && demanded.add(callSite.caller.key)) {
                    providerNames[callSite.caller.key] = uniqueProviderName(callSite.caller)
                    queue.addLast(callSite.caller.key)
                }
            }
        }

        val reachable = linkedSetOf<CallableKey>()
        callsByCallee.forEach { (callee, calls) ->
            if (calls.any { it.existingProvider != null }) reachable += callee
        }
        var grew: Boolean
        do {
            grew = false
            callsByCallee.forEach { (callee, calls) ->
                if (callee !in reachable && calls.any { it.caller.key in reachable }) {
                    reachable += callee
                    grew = true
                }
            }
        } while (grew)
        val unreachable = demanded - reachable
        if (unreachable.isNotEmpty()) {
            throw IllegalStateException(
                "HolderLookup.Provider call graph has no exact typed source for: " +
                    unreachable.joinToString { "${it.ownerQualified}.${it.name}/${it.arity}" }
            )
        }

        val edits = linkedMapOf<Path, MutableList<Edit>>()
        fun addEdit(unit: SourceUnit, offset: Int, text: String) {
            edits.getOrPut(unit.file) { mutableListOf() } += Edit(offset, text)
        }

        demanded.forEach { key ->
            val site = callableSites.getValue(key)
            val closeParen = declarationCloseParen(site)
            val insertion = if (site.declaration.parameters.isEmpty()) {
                providerParameter(providerNames.getValue(key))
            } else {
                ", ${providerParameter(providerNames.getValue(key))}"
            }
            addEdit(site.unit, closeParen, insertion)
        }

        initialSites.forEach { site ->
            val provider = exactProviderExpression(site.caller, nodeStartOffset(site.unit, site.creation))
                ?: providerNames.getValue(site.caller.key)
            addArgumentEdit(site.unit, site.creation, site.target.providerIndex, provider, ::addEdit)
        }
        constructorReferences.forEach { site ->
            val provider = exactProviderExpression(site.caller, nodeStartOffset(site.unit, site.reference))
                ?: providerNames.getValue(site.caller.key)
            val argumentName = uniqueLambdaArgumentName(site.caller)
            val constructorArgs = mutableListOf(argumentName)
            constructorArgs.add(site.target.providerIndex, provider)
            val replacement = "(${site.cast.type}) $argumentName -> new ${site.target.ownerSimple}(${constructorArgs.joinToString(", ")})"
            val range = site.cast.range.orElseThrow()
            val start = positionOffset(site.unit, range.begin)
            val endExclusive = positionOffset(site.unit, range.end) + 1
            require(endExclusive > start)
            edits.getOrPut(site.unit.file) { mutableListOf() } +=
                Edit(start, replacement, removeLength = endExclusive - start)
        }
        callsByCallee.values.flatten().forEach { site ->
            val provider = site.existingProvider ?: providerNames.getValue(site.caller.key)
            addArgumentEdit(site.unit, site.call, site.call.arguments.size, provider, ::addEdit)
        }

        val changes = mutableListOf<Change>()
        edits.forEach { (file, fileEdits) ->
            val original = sources.getValue(file)
            val uniqueEdits = fileEdits.distinctBy { Triple(it.offset, it.text, it.removeLength) }.sortedByDescending { it.offset }
            require(uniqueEdits.groupBy { it.offset }.none { (_, sameOffset) -> sameOffset.map { it.text }.distinct().size > 1 }) {
                "Conflicting HolderLookup.Provider edits in $file"
            }
            var migrated = original
            uniqueEdits.forEach { edit ->
                migrated = migrated.substring(0, edit.offset) + edit.text +
                    migrated.substring(edit.offset + edit.removeLength)
            }
            val verification = parser.parse(migrated)
            if (!verification.isSuccessful) {
                throw IllegalStateException(
                    "Threaded HolderLookup.Provider source is not parseable in $file: " +
                        verification.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            if (!dryRun) file.writeText(migrated)
            changes += Change(
                file = file,
                line = 1,
                description = "Thread HolderLookup.Provider through exact project-local data-provider call graph",
                before = "provider constructor/helper call without a registry future",
                after = "typed registry future propagated through declarations and call sites",
                confidence = Confidence.HIGH,
                ruleId = "shared-holderlookup-provider-call-graph"
            )
        }
        return changes
    }

    private fun collectConstructorTargets(units: Collection<SourceUnit>): Set<ConstructorTarget> {
        val result = linkedSetOf<ConstructorTarget>()
        units.forEach { unit ->
            unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { !it.isInterface }
                .forEach { declaration ->
                    val ownerQualified = qualifiedName(unit.compilationUnit, declaration)
                    val constructors = declaration.constructors
                    constructors.forEach { constructor ->
                        val providerIndexes = constructor.parameters.withIndex()
                            .filter { isFutureHolderLookupProvider(it.value.type) }
                            .map { it.index }
                        if (providerIndexes.isEmpty()) return@forEach
                        if (providerIndexes.size != 1) {
                            throw IllegalStateException("Constructor $ownerQualified has multiple HolderLookup.Provider futures")
                        }
                        val oldArity = constructor.parameters.size - 1
                        if (constructors.any { it !== constructor && it.parameters.size == oldArity }) return@forEach
                        result += ConstructorTarget(
                            ownerQualified = ownerQualified,
                            ownerSimple = declaration.nameAsString,
                            oldArity = oldArity,
                            providerIndex = providerIndexes.single()
                        )
                    }
                }
        }
        return result
    }

    private fun resolveConstructorTarget(
        unit: SourceUnit,
        creation: ObjectCreationExpr,
        candidates: List<ConstructorTarget>
    ): ConstructorTarget? {
        val rendered = creation.type.asString()
        candidates.firstOrNull { it.ownerQualified == rendered }?.let { return it }
        val imported = unit.compilationUnit.imports
            .filter { !it.isStatic && !it.isAsterisk && it.name.identifier == creation.type.nameAsString }
            .map { it.nameAsString }
            .toSet()
        candidates.singleOrNull { it.ownerQualified in imported }?.let { return it }
        val packageName = unit.compilationUnit.packageDeclaration.map { it.nameAsString }.orElse("")
        candidates.singleOrNull { it.ownerQualified == "$packageName.${creation.type.nameAsString}" }?.let { return it }
        return candidates.singleOrNull()
    }

    private fun callableSite(unit: SourceUnit, declaration: CallableDeclaration<*>): CallableSite? {
        val owner = declaration.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null) ?: return null
        val ownerQualified = qualifiedName(unit.compilationUnit, owner)
        val constructor = declaration is ConstructorDeclaration
        return CallableSite(
            CallableKey(
                ownerQualified = ownerQualified,
                ownerSimple = owner.nameAsString,
                name = if (constructor) owner.nameAsString else declaration.nameAsString,
                arity = declaration.parameters.size,
                constructor = constructor,
                sourceId = unit.file.toAbsolutePath().normalize().toString(),
                declarationOffset = nodeStartOffset(unit, declaration)
            ),
            unit,
            declaration
        )
    }

    private fun enclosingCallable(unit: SourceUnit, node: Node): CallableSite? {
        var current: Node? = node.parentNode.orElse(null)
        while (current != null) {
            if (current is MethodDeclaration || current is ConstructorDeclaration) {
                return callableSite(unit, current as CallableDeclaration<*>)
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    private fun ensureCandidateCallersParsed(
        callee: CallableKey,
        sources: Map<Path, String>,
        parsed: MutableMap<Path, SourceUnit>,
        parse: (Path) -> SourceUnit,
        index: (SourceUnit) -> Unit
    ) {
        sources.forEach { (file, source) ->
            if (file in parsed) return@forEach
            if (!source.contains(callee.name) && !source.contains(callee.ownerSimple)) return@forEach
            val unit = parse(file)
            index(unit)
        }
    }

    private fun findCallSites(
        callee: CallableKey,
        units: Collection<SourceUnit>,
        callableSites: MutableMap<CallableKey, CallableSite>
    ): List<CallSite> {
        val result = mutableListOf<CallSite>()
        units.forEach { unit ->
            unit.compilationUnit.findAll(MethodReferenceExpr::class.java)
                .filter { it.identifier == callee.name && matchesMethodReferenceOwner(unit, it, callee) }
                .forEach {
                    throw IllegalStateException(
                        "Cannot thread HolderLookup.Provider through method reference $it in ${unit.file}; " +
                            "its functional signature is not explicit"
                    )
                }
            unit.compilationUnit.findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == callee.name && it.arguments.size == callee.arity }
                .filter { matchesMethodCallOwner(unit, it, callee) }
                .forEach { call ->
                    val caller = enclosingCallable(unit, call) ?: throw IllegalStateException(
                        "Call to ${callee.ownerQualified}.${callee.name} is outside a Java method or constructor in ${unit.file}"
                    )
                    callableSites.putIfAbsent(caller.key, caller)
                    result += CallSite(
                        unit = unit,
                        call = call,
                        caller = caller,
                        callee = callee,
                        existingProvider = exactProviderExpression(caller, nodeStartOffset(unit, call))
                    )
                }
        }
        return result
    }

    private fun matchesMethodCallOwner(unit: SourceUnit, call: MethodCallExpr, callee: CallableKey): Boolean {
        if (call.scope.isEmpty) {
            val callerOwner = call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .map { qualifiedName(unit.compilationUnit, it) }
                .orElse("")
            if (callerOwner == callee.ownerQualified) return true
            return unit.compilationUnit.imports.any {
                it.isStatic && !it.isAsterisk && it.nameAsString == "${callee.ownerQualified}.${callee.name}"
            }
        }
        val scope = call.scope.get().toString()
        if (scope == callee.ownerSimple || scope == callee.ownerQualified) return true
        if (scope == "this" && call.findAncestor(ClassOrInterfaceDeclaration::class.java)
                .map { qualifiedName(unit.compilationUnit, it) == callee.ownerQualified }.orElse(false)) {
            return true
        }
        return exactVariablesOfType(unit, call, callee.ownerQualified, callee.ownerSimple).contains(scope)
    }

    private fun matchesMethodReferenceOwner(unit: SourceUnit, reference: MethodReferenceExpr, callee: CallableKey): Boolean {
        val scope = reference.scope.toString()
        return scope == callee.ownerSimple || scope == callee.ownerQualified ||
            exactVariablesOfType(unit, reference, callee.ownerQualified, callee.ownerSimple).contains(scope)
    }

    private fun exactVariablesOfType(unit: SourceUnit, node: Node, qualified: String, simple: String): Set<String> {
        val callable = enclosingCallable(unit, node) ?: return emptySet()
        val before = nodeStartOffset(unit, node)
        val names = linkedSetOf<String>()
        callable.declaration.parameters
            .filter { typeMatchesOwner(unit.compilationUnit, it.type, qualified, simple) }
            .forEach { names += it.nameAsString }
        callable.declaration.findAll(VariableDeclarator::class.java)
            .filter { variable -> nodeStartOffset(unit, variable) < before }
            .filter { variable -> typeMatchesOwner(unit.compilationUnit, variable.type, qualified, simple) }
            .forEach { names += it.nameAsString }
        return names
    }

    private fun exactProviderExpression(callable: CallableSite, beforeOffset: Int): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        callable.declaration.parameters.forEach { parameter ->
            when {
                isFutureHolderLookupProvider(parameter.type) -> candidates += 1 to parameter.nameAsString
                isGatherDataEvent(callable.unit.compilationUnit, parameter.type) ->
                    candidates += 0 to "${parameter.nameAsString}.getLookupProvider()"
            }
        }
        callable.declaration.findAll(VariableDeclarator::class.java)
            .filter { nodeStartOffset(callable.unit, it) < beforeOffset }
            .filter { isFutureHolderLookupProvider(it.type) }
            .filter { enclosingCallable(callable.unit, it)?.declaration === callable.declaration }
            .forEach { variable ->
                val enriched = variable.initializer
                    .map { it.toString().contains(".getRegistryProvider(") || it.toString().endsWith(".getRegistryProvider()") }
                    .orElse(false)
                candidates += (if (enriched) 3 else 2) to variable.nameAsString
            }
        val bestPriority = candidates.maxOfOrNull { it.first } ?: return null
        val best = candidates.filter { it.first == bestPriority }.map { it.second }.distinct()
        if (best.size > 1) {
            throw IllegalStateException(
                "Ambiguous HolderLookup.Provider futures in ${callable.key.ownerQualified}.${callable.key.name}: " +
                    best.joinToString()
            )
        }
        return best.single()
    }

    private fun uniqueProviderName(callable: CallableSite): String {
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
            .findAll(callable.declaration.toString())
            .map { it.value }
            .toSet()
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "modporterLookupProvider" else "modporterLookupProvider$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private fun uniqueLambdaArgumentName(callable: CallableSite): String {
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
            .findAll(callable.declaration.toString())
            .map { it.value }
            .toSet()
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "modporterOutput" else "modporterOutput$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private fun declarationCloseParen(site: CallableSite): Int {
        val nameEnd = positionOffset(site.unit, site.declaration.name.range.orElseThrow().end) + 1
        val open = site.unit.source.indexOf('(', nameEnd)
        require(open >= 0) { "Cannot locate parameter list for ${site.key}" }
        val close = findMatchingParen(site.unit.source, open)
        require(close >= 0) { "Cannot close parameter list for ${site.key}" }
        return close
    }

    private fun addArgumentEdit(
        unit: SourceUnit,
        call: Node,
        index: Int,
        expression: String,
        addEdit: (SourceUnit, Int, String) -> Unit
    ) {
        val arguments = when (call) {
            is ObjectCreationExpr -> call.arguments
            is MethodCallExpr -> call.arguments
            else -> error("Unsupported call node ${call.javaClass.name}")
        }
        require(index in 0..arguments.size) { "Invalid provider argument index $index for $call" }
        if (index < arguments.size) {
            addEdit(unit, nodeStartOffset(unit, arguments[index]), "$expression, ")
            return
        }
        val closeParen = positionOffset(unit, call.range.orElseThrow().end)
        val prefix = if (arguments.isEmpty()) "" else ", "
        addEdit(unit, closeParen, "$prefix$expression")
    }

    private fun qualifiedName(cu: CompilationUnit, declaration: ClassOrInterfaceDeclaration): String {
        val names = mutableListOf<String>()
        var current: Node? = declaration
        while (current != null) {
            if (current is ClassOrInterfaceDeclaration) names.add(0, current.nameAsString)
            current = current.parentNode.orElse(null)
        }
        val nesting = names.joinToString(".")
        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        return if (packageName.isBlank()) nesting else "$packageName.$nesting"
    }

    private fun typeMatchesOwner(cu: CompilationUnit, type: Type, qualified: String, simple: String): Boolean {
        val rendered = normalizeType(type)
        if (rendered == qualified || rendered == simple) {
            if (rendered == qualified) return true
            val imports = cu.imports.filter { !it.isStatic && !it.isAsterisk && it.name.identifier == simple }
            if (imports.isNotEmpty()) return imports.singleOrNull()?.nameAsString == qualified
            val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
            return qualified == "$packageName.$simple"
        }
        return false
    }

    private fun isFutureHolderLookupProvider(type: Type): Boolean {
        val normalized = normalizeType(type)
        return normalized in setOf(
            "CompletableFuture<HolderLookup.Provider>",
            "java.util.concurrent.CompletableFuture<HolderLookup.Provider>",
            "CompletableFuture<net.minecraft.core.HolderLookup.Provider>",
            "java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider>"
        )
    }

    private fun isGatherDataEvent(cu: CompilationUnit, type: Type): Boolean {
        val normalized = normalizeType(type)
        if (normalized == "net.neoforged.neoforge.data.event.GatherDataEvent" ||
            normalized == "net.minecraftforge.data.event.GatherDataEvent") return true
        if (normalized != "GatherDataEvent") return false
        return cu.imports.any {
            !it.isStatic && it.nameAsString in setOf(
                "net.neoforged.neoforge.data.event.GatherDataEvent",
                "net.minecraftforge.data.event.GatherDataEvent"
            )
        }
    }

    private fun normalizeType(type: Type): String = type.asString().replace(Regex("\\s+"), "")

    private fun providerParameter(name: String): String =
        "java.util.concurrent.CompletableFuture<net.minecraft.core.HolderLookup.Provider> $name"

    private fun methodReferenceOwner(reference: MethodReferenceExpr): String = reference.scope.toString()

    private fun nodeStartOffset(unit: SourceUnit, node: Node): Int =
        positionOffset(unit, node.range.orElseThrow().begin)

    private fun positionOffset(unit: SourceUnit, position: Position): Int =
        unit.lineOffsets[position.line - 1] + position.column - 1

    private fun lineOffsets(source: String): IntArray {
        val result = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') result += index + 1 }
        return result.toIntArray()
    }

    private fun findMatchingParen(source: String, openParen: Int): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in openParen until source.length) {
            val char = source[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quote) quote = null
                continue
            }
            if (char == '"' || char == '\'') {
                quote = char
                continue
            }
            when (char) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }
}
