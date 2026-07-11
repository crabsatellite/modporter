package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
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

/** Migrates source-proven tracking-chunk PacketTarget helpers and every project call site atomically. */
class LegacyTrackingChunkPacketTargetMigration {
    private data class TypeInfo(
        val file: Path,
        val fqn: String,
        val packageName: String,
        val simpleName: String,
        val superName: String?,
        val imports: Map<String, String>,
        val wildcardImports: Set<String>,
        val declaredMethods: Set<String>
    )

    private data class TrackingHelper(
        val ownerFqn: String,
        val ownerFile: Path,
        val methodName: String,
        val levelExpression: Expression,
        val positionExpression: Expression
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val files = Files.walk(srcDir).use { stream ->
            stream.filter { it.extension == "java" }.toList()
        }
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val sourceByFile = files.associateWith { it.readText() }
        val typeIndex = buildTypeIndex(files, sourceByFile, parser)
        val resolvedSupers = typeIndex.mapValues { (_, info) -> resolveSuper(info, typeIndex) }
        val helpers = discoverHelpers(files, sourceByFile, parser, typeIndex)
        if (helpers.isEmpty()) return emptyList()

        val helpersByName = helpers.groupBy { it.methodName }
        val migratedSources = linkedMapOf<Path, String>()
        val rewritesByHelper = helpers.associateWith { 0 }.toMutableMap()
        val changeFiles = linkedSetOf<Path>()

        files.forEach { file ->
            val source = sourceByFile.getValue(file)
            val relevantNames = helpersByName.keys.filter { source.contains("$it(") || source.contains("::$it") }
            if (relevantNames.isEmpty()) return@forEach
            val cu = parse(parser, source, file)
            LexicalPreservingPrinter.setup(cu)
            var rewrites = 0

            cu.findAll(MethodReferenceExpr::class.java).forEach referenceLoop@{ reference ->
                if (reference.identifier !in relevantNames) return@referenceLoop
                throw IllegalStateException(
                    "Tracking PacketTarget helper '${reference.identifier}' escapes as a method reference in $file"
                )
            }

            cu.findAll(MethodCallExpr::class.java).toList().forEach callLoop@{ call ->
                val candidates = helpersByName[call.nameAsString] ?: return@callLoop
                if (call.arguments.isNotEmpty()) return@callLoop
                val ownerType = call.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
                    ?: throw IllegalStateException("Tracking PacketTarget call is outside a class in $file")
                if (!ownerType.isTopLevelType) {
                    throw IllegalStateException(
                        "Cannot prove tracking PacketTarget inheritance for nested type '${ownerType.nameAsString}' in $file"
                    )
                }
                val ownerFqn = compilationTypeFqn(cu, ownerType)
                val applicable = candidates.mapNotNull { helper ->
                    inheritanceDistance(ownerFqn, helper.ownerFqn, resolvedSupers)?.let { it to helper }
                }.sortedBy { it.first }

                if (applicable.isEmpty()) {
                    val info = typeIndex[ownerFqn]
                    if (info?.declaredMethods?.contains(call.nameAsString) == true) return@callLoop
                    if (call.scope.isPresent) return@callLoop
                    throw IllegalStateException(
                        "Cannot prove the owner of tracking PacketTarget call '${call.nameAsString}()' in $file"
                    )
                }
                if (applicable.size > 1 && applicable[0].first == applicable[1].first) {
                    throw IllegalStateException(
                        "Tracking PacketTarget call '${call.nameAsString}()' has multiple equally near owners in $file"
                    )
                }
                val helper = applicable.first().second
                val scope = call.scope.orElse(null)
                if (scope != null && scope !is ThisExpr) {
                    throw IllegalStateException(
                        "Tracking PacketTarget call '${call}' is not an unqualified current-instance call in $file"
                    )
                }
                val send = call.parentNode.orElse(null) as? MethodCallExpr
                    ?: throw IllegalStateException(
                        "Tracking PacketTarget call '${call}' is not consumed by a channel send in $file"
                    )
                if (send.nameAsString != "send" || send.arguments.size != 2 || send.arguments[0] !== call ||
                    !send.scope.isPresent) {
                    throw IllegalStateException(
                        "Tracking PacketTarget call '${call}' is not the first argument of a two-argument channel send in $file"
                    )
                }
                rewriteSend(send, helper)
                rewritesByHelper[helper] = rewritesByHelper.getValue(helper) + 1
                rewrites++
            }

            if (rewrites > 0) {
                cu.addImport("net.minecraft.server.level.ServerLevel")
                cu.addImport("net.minecraft.world.level.ChunkPos")
                cu.addImport("net.neoforged.neoforge.network.PacketDistributor")
                migratedSources[file] = LexicalPreservingPrinter.print(cu)
                changeFiles.add(file)
            }
        }

        helpers.forEach { helper ->
            if (rewritesByHelper.getValue(helper) == 0) {
                throw IllegalStateException(
                    "Tracking PacketTarget helper '${helper.ownerFqn}.${helper.methodName}' has no source-proven call sites"
                )
            }
            val source = migratedSources[helper.ownerFile] ?: sourceByFile.getValue(helper.ownerFile)
            val cu = parse(parser, source, helper.ownerFile)
            LexicalPreservingPrinter.setup(cu)
            val owner = cu.types.filterIsInstance<ClassOrInterfaceDeclaration>()
                .singleOrNull { compilationTypeFqn(cu, it) == helper.ownerFqn }
                ?: throw IllegalStateException("Cannot re-open tracking PacketTarget owner ${helper.ownerFqn}")
            val methods = owner.methods.filter { it.nameAsString == helper.methodName && it.parameters.isEmpty() }
            if (methods.size != 1) {
                throw IllegalStateException(
                    "Tracking PacketTarget helper '${helper.ownerFqn}.${helper.methodName}' changed during migration"
                )
            }
            methods.single().remove()
            if (cu.toString().contains("PacketDistributor.PacketTarget") ||
                cu.toString().contains("PacketDistributor.TRACKING_CHUNK")) {
                throw IllegalStateException("Legacy tracking PacketTarget API remains in ${helper.ownerFile}")
            }
            cu.imports.filter { imported ->
                !imported.isStatic && (
                    imported.nameAsString.endsWith(".PacketDistributor.PacketTarget") ||
                        imported.nameAsString.endsWith(".PacketDistributor") &&
                        !LexicalPreservingPrinter.print(cu).contains("PacketDistributor.")
                    )
            }.toList().forEach { it.remove() }
            migratedSources[helper.ownerFile] = LexicalPreservingPrinter.print(cu)
            changeFiles.add(helper.ownerFile)
        }

        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changeFiles.map { file ->
            Change(
                file = file,
                line = 1,
                description = "Migrate a source-proven tracking-chunk PacketTarget helper and its complete call graph",
                before = "channel.send(packetTarget(), payload)",
                after = "PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level, new ChunkPos(position), payload)",
                confidence = Confidence.HIGH,
                ruleId = "struct-packet-target-tracking-chunk-helper"
            )
        }
    }

    private fun buildTypeIndex(
        files: List<Path>,
        sourceByFile: Map<Path, String>,
        parser: JavaParser
    ): Map<String, TypeInfo> {
        val result = linkedMapOf<String, TypeInfo>()
        files.forEach { file ->
            val cu = parse(parser, sourceByFile.getValue(file), file)
            val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
            val imports = cu.imports.filter { !it.isStatic && !it.isAsterisk }
                .associate { it.name.identifier to it.nameAsString }
            val wildcardImports = cu.imports.filter { !it.isStatic && it.isAsterisk }
                .map { it.nameAsString }.toSet()
            cu.types.filterIsInstance<ClassOrInterfaceDeclaration>().forEach { type ->
                val fqn = if (packageName.isBlank()) type.nameAsString else "$packageName.${type.nameAsString}"
                if (result.containsKey(fqn)) {
                    throw IllegalStateException("Duplicate source type $fqn while indexing tracking PacketTarget helpers")
                }
                result[fqn] = TypeInfo(
                    file = file,
                    fqn = fqn,
                    packageName = packageName,
                    simpleName = type.nameAsString,
                    superName = type.extendedTypes.singleOrNull()?.nameWithScope,
                    imports = imports,
                    wildcardImports = wildcardImports,
                    declaredMethods = type.methods.map { it.nameAsString }.toSet()
                )
            }
        }
        return result
    }

    private fun discoverHelpers(
        files: List<Path>,
        sourceByFile: Map<Path, String>,
        parser: JavaParser,
        typeIndex: Map<String, TypeInfo>
    ): List<TrackingHelper> {
        val helpers = mutableListOf<TrackingHelper>()
        files.filter { sourceByFile.getValue(it).contains("PacketDistributor.TRACKING_CHUNK.with") }
            .forEach { file ->
                val cu = parse(parser, sourceByFile.getValue(file), file)
                cu.types.filterIsInstance<ClassOrInterfaceDeclaration>().forEach { type ->
                    val ownerFqn = compilationTypeFqn(cu, type)
                    type.methods.forEach methodLoop@{ method ->
                        val targetExpression = trackingTargetExpression(method, cu) ?: return@methodLoop
                        val chunkSource = chunkSource(targetExpression, type, file)
                            ?: throw IllegalStateException(
                                "Cannot derive exact level and position expressions from ${ownerFqn}.${method.nameAsString} in $file"
                            )
                        helpers += TrackingHelper(
                            ownerFqn = ownerFqn,
                            ownerFile = typeIndex[ownerFqn]?.file
                                ?: throw IllegalStateException("Tracking PacketTarget owner $ownerFqn is not indexed"),
                            methodName = method.nameAsString,
                            levelExpression = chunkSource.first,
                            positionExpression = chunkSource.second
                        )
                    }
                }
            }
        return helpers
    }

    private fun trackingTargetExpression(method: MethodDeclaration, cu: CompilationUnit): Expression? {
        val returnType = method.typeAsString
        val exactType = returnType == "PacketDistributor.PacketTarget" ||
            returnType == "PacketTarget" && cu.imports.any {
                !it.isStatic && it.nameAsString.endsWith(".PacketDistributor.PacketTarget")
            }
        if (!exactType || method.parameters.isNotEmpty() || method.isStatic) return null
        val statements = method.body.orElse(null)?.statements ?: return null
        if (statements.size != 1) return null
        val returned = (statements.single() as? ReturnStmt)?.expression?.orElse(null) ?: return null
        val with = returned as? MethodCallExpr ?: return null
        if (with.nameAsString != "with" || with.scope.orElse(null)?.toString() != "PacketDistributor.TRACKING_CHUNK" ||
            with.arguments.size != 1) return null
        return with.arguments.single()
    }

    private fun chunkSource(
        supplier: Expression,
        owner: ClassOrInterfaceDeclaration,
        file: Path
    ): Pair<Expression, Expression>? {
        val chunkCall = when (supplier) {
            is MethodReferenceExpr -> {
                if (supplier.scope !is ThisExpr) return null
                val methods = owner.methods.filter {
                    it.nameAsString == supplier.identifier && it.parameters.isEmpty() && !it.isStatic
                }
                if (methods.size != 1) {
                    throw IllegalStateException("Tracking chunk supplier '${supplier.identifier}' is not unique in $file")
                }
                val method = methods.single()
                if (method.typeAsString.substringAfterLast('.') != "LevelChunk") return null
                val statements = method.body.orElse(null)?.statements ?: return null
                if (statements.size != 1) return null
                (statements.single() as? ReturnStmt)?.expression?.orElse(null) as? MethodCallExpr
            }
            is LambdaExpr -> {
                if (supplier.parameters.isNotEmpty()) return null
                (supplier.body as? ExpressionStmt)?.expression as? MethodCallExpr
            }
            else -> null
        } ?: return null
        if (chunkCall.nameAsString != "getChunkAt" || chunkCall.arguments.size != 1) return null
        val level = chunkCall.scope.orElse(null) ?: return null
        return level.clone() to chunkCall.arguments.single().clone()
    }

    private fun rewriteSend(send: MethodCallExpr, helper: TrackingHelper) {
        val payload = send.arguments[1].clone()
        send.setScope(NameExpr("PacketDistributor"))
        send.setName("sendToPlayersTrackingChunk")
        send.arguments.clear()
        send.addArgument(CastExpr(ClassOrInterfaceType(null, "ServerLevel"), helper.levelExpression.clone()))
        send.addArgument(
            ObjectCreationExpr(
                null,
                ClassOrInterfaceType(null, "ChunkPos"),
                NodeList.nodeList(helper.positionExpression.clone())
            )
        )
        send.addArgument(payload)
    }

    private fun resolveSuper(info: TypeInfo, index: Map<String, TypeInfo>): String? {
        val raw = info.superName ?: return null
        if (raw in index) return raw
        info.imports[raw.substringBefore('<')]?.let { imported ->
            if (imported in index) return imported
        }
        val samePackage = if (info.packageName.isBlank()) raw else "${info.packageName}.$raw"
        if (samePackage in index) return samePackage
        info.wildcardImports.forEach { importedPackage ->
            val candidate = "$importedPackage.$raw"
            if (candidate in index) return candidate
        }
        return null
    }

    private fun inheritanceDistance(
        typeFqn: String,
        ancestorFqn: String,
        resolvedSupers: Map<String, String?>
    ): Int? {
        var current: String? = typeFqn
        var distance = 0
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (current == ancestorFqn) return distance
            current = resolvedSupers[current]
            distance++
        }
        return null
    }

    private fun compilationTypeFqn(cu: CompilationUnit, type: ClassOrInterfaceDeclaration): String {
        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        return if (packageName.isBlank()) type.nameAsString else "$packageName.${type.nameAsString}"
    }

    private fun parse(parser: JavaParser, source: String, file: Path): CompilationUnit {
        val result = parser.parse(source)
        return result.result.orElseThrow {
            IllegalStateException("Cannot parse tracking PacketTarget source $file: ${result.problems.joinToString()}")
        }
    }
}
