package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
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

/** Threads declared registry providers from project callers to exact FluidTank NBT calls. */
class ExactFluidTankProviderCallMigration {
    private data class MethodKey(val owner: String, val name: String, val declarationArity: Int)

    private data class Demand(
        val key: MethodKey,
        val file: Path,
        val method: MethodDeclaration,
        val providerIndex: Int,
        val providerName: String,
        val legacyArity: Int,
        val hadProvider: Boolean
    )

    private data class InitialSite(val file: Path, val call: MethodCallExpr, val caller: Demand)

    private data class ProjectCallSite(
        val file: Path,
        val call: MethodCallExpr,
        val caller: Demand,
        val callee: Demand
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        val sources = files.associateWith { it.readText() }
        val initialFiles = sources.filterValues { source ->
            source.contains(".writeToNBT(") || source.contains(".readFromNBT(")
        }.keys
        if (initialFiles.isEmpty()) return emptyList()

        val index = JavaProjectTypeIndex.build(sourceRoot)
        val lexicalUnits = linkedMapOf<Path, CompilationUnit>()
        fun unit(file: Path): CompilationUnit = lexicalUnits.getOrPut(file) {
            index.unit(file).also(LexicalPreservingPrinter::setup)
        }

        val demands = linkedMapOf<MethodKey, Demand>()
        fun demandFor(file: Path, callable: CallableDeclaration<*>): Demand {
            val method = callable as? MethodDeclaration ?: throw IllegalStateException(
                "FluidTank provider propagation cannot change constructor '${callable.nameAsString}' in $file"
            )
            val owner = method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration::class.java)
                .flatMap { it.fullyQualifiedName }.orElseThrow {
                    IllegalStateException("Cannot resolve owner of provider-demanded method ${method.nameAsString} in $file")
                }
            val key = MethodKey(owner, method.nameAsString, method.parameters.size)
            demands[key]?.let { return it }
            val providerParameters = method.parameters.withIndex().filter { (_, parameter) ->
                index.declaredType(parameter.type, method) == HOLDER_LOOKUP_PROVIDER
            }
            if (providerParameters.size > 1) {
                throw IllegalStateException("Method $owner.${method.nameAsString} has multiple HolderLookup.Provider parameters")
            }
            val existing = providerParameters.singleOrNull()
            val compoundParameters = method.parameters.withIndex().filter { (_, parameter) ->
                index.declaredType(parameter.type, method) == COMPOUND_TAG
            }
            if (existing == null && compoundParameters.size > 1) {
                throw IllegalStateException(
                    "Cannot choose an exact HolderLookup.Provider position for $owner.${method.nameAsString}; " +
                        "found ${compoundParameters.size} CompoundTag parameters"
                )
            }
            if (existing == null && method.annotations.any { it.nameAsString == "Override" }) {
                throw IllegalStateException(
                    "Cannot add HolderLookup.Provider to overriding method $owner.${method.nameAsString} without contract proof"
                )
            }
            val providerIndex = existing?.index ?: compoundParameters.singleOrNull()?.let { it.index + 1 }
                ?: method.parameters.size
            val providerName = existing?.value?.nameAsString ?: uniqueProviderName(method)
            return Demand(
                key = key,
                file = file,
                method = method,
                providerIndex = providerIndex,
                providerName = providerName,
                legacyArity = method.parameters.size - if (existing == null) 0 else 1,
                hadProvider = existing != null
            ).also { demands[key] = it }
        }

        val initialSites = mutableListOf<InitialSite>()
        initialFiles.forEach { file ->
            val cu = unit(file)
            cu.findAll(MethodCallExpr::class.java).forEach { call ->
                if (call.nameAsString !in NBT_METHODS || call.arguments.size != 1 || call.scope.isEmpty) return@forEach
                if (!index.isExpressionAssignableTo(call.scope.get(), call, FLUID_TANK)) return@forEach
                val callable = enclosingCallable(call) ?: throw IllegalStateException(
                    "Exact FluidTank NBT call '$call' is outside a callable in $file"
                )
                initialSites += InitialSite(file, call, demandFor(file, callable))
            }
        }
        if (initialSites.isEmpty()) return emptyList()

        val projectCallSites = linkedMapOf<String, ProjectCallSite>()
        val queue = ArrayDeque<Demand>()
        queue.addAll(initialSites.map { it.caller }.distinctBy { it.key })
        val expanded = linkedSetOf<MethodKey>()
        while (queue.isNotEmpty()) {
            val callee = queue.removeFirst()
            if (!expanded.add(callee.key)) continue
            if (callee.hadProvider) continue
            val callSites = findProjectCallSites(callee, sources, ::unit, index)
            if (callSites.isEmpty()) {
                throw IllegalStateException(
                    "Provider-demanded method ${callee.key.owner}.${callee.key.name}/${callee.legacyArity} " +
                        "has no exact project caller with a declared HolderLookup.Provider path"
                )
            }
            callSites.forEach { (file, call) ->
                val callable = enclosingCallable(call) ?: throw IllegalStateException(
                    "Call to ${callee.key.owner}.${callee.key.name} is outside a callable in $file"
                )
                val caller = demandFor(file, callable)
                val siteKey = "$file:${call.range.orElseThrow()}:${callee.key}"
                projectCallSites[siteKey] = ProjectCallSite(file, call, caller, callee)
                if (!caller.hadProvider) queue.addLast(caller)
            }
        }

        val reachable = demands.values.filter { it.hadProvider }.mapTo(linkedSetOf()) { it.key }
        var grew: Boolean
        do {
            grew = false
            projectCallSites.values.forEach { site ->
                if (site.caller.key in reachable && reachable.add(site.callee.key)) grew = true
            }
        } while (grew)
        val unreachable = demands.values.filter { !it.hadProvider && it.key !in reachable }
        if (unreachable.isNotEmpty()) {
            throw IllegalStateException(
                "FluidTank HolderLookup.Provider call graph has no exact declared source for: " +
                    unreachable.joinToString { "${it.key.owner}.${it.key.name}/${it.legacyArity}" }
            )
        }

        demands.values.filterNot { it.hadProvider }.forEach { demand ->
            demand.method.parameters.add(
                demand.providerIndex,
                Parameter(StaticJavaParser.parseType(HOLDER_LOOKUP_PROVIDER), demand.providerName)
            )
        }
        initialSites.forEach { site ->
            site.call.arguments.add(0, NameExpr(site.caller.providerName))
        }
        projectCallSites.values.forEach { site ->
            site.call.arguments.add(site.callee.providerIndex, NameExpr(site.caller.providerName))
        }

        val changedFiles = linkedSetOf<Path>()
        changedFiles += initialSites.map { it.file }
        changedFiles += demands.values.filterNot { it.hadProvider }.map { it.file }
        changedFiles += projectCallSites.values.map { it.file }
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val migratedSources = linkedMapOf<Path, String>()
        changedFiles.forEach { file ->
            val migrated = LexicalPreservingPrinter.print(unit(file))
            val verification = parser.parse(migrated)
            if (!verification.isSuccessful) {
                throw IllegalStateException(
                    "FluidTank provider call graph produced invalid Java in $file: ${verification.problems.joinToString()}"
                )
            }
            migratedSources[file] = migrated
        }
        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changedFiles.map { file ->
            Change(
                file = file,
                line = 1,
                description = "Thread declared HolderLookup.Provider through exact FluidTank NBT call graph",
                before = "project helper chain ending in FluidTank NBT without provider",
                after = "provider-aware declarations and exact project call sites",
                confidence = Confidence.HIGH,
                ruleId = "struct-fluidtank-exact-provider-call-graph"
            )
        }
    }

    private fun findProjectCallSites(
        callee: Demand,
        sources: Map<Path, String>,
        unit: (Path) -> CompilationUnit,
        index: JavaProjectTypeIndex
    ): List<Pair<Path, MethodCallExpr>> {
        val result = mutableListOf<Pair<Path, MethodCallExpr>>()
        sources.forEach { (file, source) ->
            if (!source.contains(callee.key.name)) return@forEach
            unit(file).findAll(MethodCallExpr::class.java)
                .filter { it.nameAsString == callee.key.name && it.arguments.size == callee.legacyArity }
                .filter { index.projectMethodOwner(it, callee.key.declarationArity) == callee.key.owner }
                .forEach { result += file to it }
        }
        return result
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
        val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*").findAll(method.toString()).map { it.value }.toSet()
        var suffix = 0
        while (true) {
            val candidate = if (suffix == 0) "modporterRegistries" else "modporterRegistries$suffix"
            if (candidate !in identifiers) return candidate
            suffix++
        }
    }

    private companion object {
        val NBT_METHODS = setOf("writeToNBT", "readFromNBT")
        const val FLUID_TANK = "net.neoforged.neoforge.fluids.capability.templates.FluidTank"
        const val HOLDER_LOOKUP_PROVIDER = "net.minecraft.core.HolderLookup.Provider"
        const val COMPOUND_TAG = "net.minecraft.nbt.CompoundTag"
    }
}
