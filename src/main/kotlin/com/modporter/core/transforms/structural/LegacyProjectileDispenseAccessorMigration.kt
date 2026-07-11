package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
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

/** Migrates the removed projectile-dispense invokers to the target class's exact field-backed API. */
class LegacyProjectileDispenseAccessorMigration {
    private data class SourceUnit(val file: Path, val source: String, val cu: CompilationUnit)

    private data class Contract(
        val unit: SourceUnit,
        val declaration: ClassOrInterfaceDeclaration,
        val qualifiedName: String,
        val projectile: MethodDeclaration,
        val uncertainty: MethodDeclaration,
        val power: MethodDeclaration
    )

    private data class WrapperFlow(
        val unit: SourceUnit,
        val implementation: MethodDeclaration,
        val directionParameter: String
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val files = Files.walk(srcDir).use { stream -> stream.filter { it.extension == "java" }.toList() }
        val accessorUnits = files.mapNotNull { file ->
            val source = file.readText()
            if (!source.contains("ProjectileDispenseBehavior") || !source.contains("Invoker")) null
            else parse(parser, file, source)
        }
        val contracts = accessorUnits.flatMap(::findContracts)
        if (contracts.isEmpty()) return emptyList()

        val units = linkedMapOf<Path, SourceUnit>()
        accessorUnits.forEach { units[it.file] = it }
        contracts.forEach { contract ->
            val names = setOf(
                contract.projectile.nameAsString,
                contract.uncertainty.nameAsString,
                contract.power.nameAsString
            )
            files.forEach { file ->
                if (file in units) return@forEach
                val source = file.readText()
                if (names.any(source::contains)) units[file] = parse(parser, file, source)
            }
        }
        units.values.forEach { LexicalPreservingPrinter.setup(it.cu) }

        val modified = linkedSetOf<SourceUnit>()
        contracts.forEach { contract ->
            val flows = migrateCalls(contract, units.values.toList(), modified)
            flows.forEach { migrateWrapperFlow(it, modified) }
            migrateAccessor(contract)
            modified += contract.unit
        }

        val migratedSources = linkedMapOf<Path, String>()
        modified.forEach { unit ->
            val migrated = LexicalPreservingPrinter.print(unit.cu)
            if (migrated.contains("@Invoker(\"getProjectile\")") ||
                migrated.contains("@Invoker(\"getUncertainty\")") ||
                migrated.contains("@Invoker(\"getPower\")")) {
                throw IllegalStateException("Legacy projectile dispense invoker remains after migration in ${unit.file}")
            }
            migratedSources[unit.file] = migrated
        }
        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return migratedSources.keys.map { file ->
            Change(
                file = file,
                line = 1,
                description = "Migrate projectile dispenser invokers and direction flow to field-backed projectile APIs",
                before = "@Invoker projectile/getUncertainty/getPower",
                after = "@Accessor projectileItem/dispenseConfig with ProjectileItem.asProjectile",
                confidence = Confidence.HIGH,
                ruleId = "struct-projectile-dispense-field-accessors"
            )
        }
    }

    private fun findContracts(unit: SourceUnit): List<Contract> {
        if (!hasExactImport(unit.cu, "net.minecraft.core.dispenser.ProjectileDispenseBehavior")) return emptyList()
        return unit.cu.findAll(ClassOrInterfaceDeclaration::class.java).mapNotNull { declaration ->
            if (!declaration.isInterface || !isProjectileMixin(declaration)) return@mapNotNull null
            val invokers = declaration.methods.filter { method ->
                method.annotations.any { it.nameAsString == "Invoker" }
            }
            if (invokers.size != 3 || invokers.any { invokerTarget(it) == null }) {
                throw IllegalStateException(
                    "Projectile dispense accessor must contain exactly three explicit string-valued invokers in ${unit.file}"
                )
            }
            val byTarget = invokers.groupBy { invokerTarget(it)!! }
            val required = setOf("getProjectile", "getUncertainty", "getPower")
            if (byTarget.keys != required || byTarget.values.any { it.size != 1 }) {
                throw IllegalStateException(
                    "Projectile dispense accessor must contain exactly getProjectile/getUncertainty/getPower invokers in ${unit.file}"
                )
            }
            val projectile = byTarget.getValue("getProjectile").single()
            val uncertainty = byTarget.getValue("getUncertainty").single()
            val power = byTarget.getValue("getPower").single()
            requireSignature(unit, projectile, "Projectile", listOf("Level", "Position", "ItemStack"))
            requireSignature(unit, uncertainty, "float", emptyList())
            requireSignature(unit, power, "float", emptyList())
            listOf(
                "net.minecraft.world.entity.projectile.Projectile",
                "net.minecraft.world.level.Level",
                "net.minecraft.core.Position",
                "net.minecraft.world.item.ItemStack",
                "org.spongepowered.asm.mixin.gen.Invoker"
            ).forEach { owner ->
                if (!hasExactImport(unit.cu, owner)) {
                    throw IllegalStateException("Projectile dispense accessor lacks exact owner import $owner in ${unit.file}")
                }
            }
            val packageName = unit.cu.packageDeclaration.map { it.nameAsString }.orElse("")
            val qualifiedName = if (packageName.isEmpty()) declaration.nameAsString else "$packageName.${declaration.nameAsString}"
            Contract(unit, declaration, qualifiedName, projectile, uncertainty, power)
        }
    }

    private fun migrateCalls(
        contract: Contract,
        units: List<SourceUnit>,
        modified: MutableSet<SourceUnit>
    ): List<WrapperFlow> {
        val projectileCalls = mutableListOf<Pair<SourceUnit, MethodCallExpr>>()
        val uncertaintyCalls = mutableListOf<Pair<SourceUnit, MethodCallExpr>>()
        val powerCalls = mutableListOf<Pair<SourceUnit, MethodCallExpr>>()
        units.forEach { unit ->
            unit.cu.findAll(MethodCallExpr::class.java).forEach { call ->
                val target = when (call.nameAsString) {
                    contract.projectile.nameAsString -> projectileCalls
                    contract.uncertainty.nameAsString -> uncertaintyCalls
                    contract.power.nameAsString -> powerCalls
                    else -> null
                } ?: return@forEach
                if (hasExactAccessorReceiver(call, contract, unit)) target += unit to call
            }
        }
        if (projectileCalls.isEmpty() || uncertaintyCalls.isEmpty() || powerCalls.isEmpty()) {
            throw IllegalStateException("Projectile dispense accessor methods are not all used through exactly typed receivers in ${contract.unit.file}")
        }

        val flows = linkedMapOf<MethodDeclaration, WrapperFlow>()
        projectileCalls.forEach { (unit, call) ->
            if (call.arguments.size != 3) {
                throw IllegalStateException("Projectile invoker call must have exact Level/Position/ItemStack arguments in ${unit.file}")
            }
            val method = call.findAncestor(MethodDeclaration::class.java).orElseThrow {
                IllegalStateException("Projectile invoker call is outside a method in ${unit.file}")
            }
            val flow = flows.getOrPut(method) {
                val directionName = uniqueParameterName(method, "projectileDirection")
                method.addParameter("Direction", directionName)
                unit.cu.addImport("net.minecraft.core.Direction")
                WrapperFlow(unit, method, directionName)
            }
            val accessorCall = MethodCallExpr(call.scope.orElseThrow().clone(), contract.projectile.nameAsString)
            val replacement = MethodCallExpr(accessorCall, "asProjectile")
            call.arguments.forEach { replacement.addArgument(it.clone()) }
            replacement.addArgument(flow.directionParameter)
            call.replace(replacement)
            modified += unit
        }
        uncertaintyCalls.forEach { (unit, call) ->
            requireNoArgs(call, "uncertainty", unit.file)
            call.replace(MethodCallExpr(call.clone(), "uncertainty"))
            modified += unit
        }
        powerCalls.forEach { (unit, call) ->
            requireNoArgs(call, "power", unit.file)
            val config = MethodCallExpr(call.scope.orElseThrow().clone(), contract.uncertainty.nameAsString)
            call.replace(MethodCallExpr(config, "power"))
            modified += unit
        }
        return flows.values.toList()
    }

    private fun migrateWrapperFlow(flow: WrapperFlow, modified: MutableSet<SourceUnit>) {
        val anonymous = flow.implementation.findAncestor(com.github.javaparser.ast.expr.ObjectCreationExpr::class.java)
            .orElseThrow { IllegalStateException("Projectile wrapper implementation is not inside an anonymous class in ${flow.unit.file}") }
        if (!anonymous.anonymousClassBody.isPresent) {
            throw IllegalStateException("Projectile wrapper implementation has no anonymous class body in ${flow.unit.file}")
        }
        val ownerName = anonymous.typeAsString
        val owners = flow.unit.cu.findAll(ClassOrInterfaceDeclaration::class.java).filter { it.nameAsString == ownerName }
        if (owners.size != 1) {
            throw IllegalStateException("Projectile wrapper owner '$ownerName' is not uniquely declared in ${flow.unit.file}")
        }
        val owner = owners.single()
        val implementationTypes = flow.implementation.parameters.dropLast(1).map { it.typeAsString }
        val declarations = owner.methods.filter { method ->
            method.nameAsString == flow.implementation.nameAsString &&
                method.parameters.map { it.typeAsString } == implementationTypes &&
                !method.body.isPresent
        }
        if (declarations.size != 1) {
            throw IllegalStateException("Projectile wrapper abstract method is not uniquely declared in ${flow.unit.file}")
        }
        declarations.single().addParameter("Direction", flow.directionParameter)

        val oldArgumentCount = implementationTypes.size
        val calls = owner.findAll(MethodCallExpr::class.java).filter { call ->
            call.nameAsString == flow.implementation.nameAsString && call.arguments.size == oldArgumentCount &&
                (!call.scope.isPresent || call.scope.get() is ThisExpr) &&
                call.findAncestor(MethodDeclaration::class.java).orElse(null)?.parentNode?.orElse(null) === owner
        }
        if (calls.isEmpty()) {
            throw IllegalStateException("Projectile wrapper abstract method has no exact internal call in ${flow.unit.file}")
        }
        calls.forEach { call ->
            val caller = call.findAncestor(MethodDeclaration::class.java).orElseThrow {
                IllegalStateException("Projectile wrapper call is outside a method in ${flow.unit.file}")
            }
            val vectors = caller.parameters.filter { it.typeAsString == "Vec3" }
            if (vectors.size != 1 || !hasExactImport(flow.unit.cu, "net.minecraft.world.phys.Vec3")) {
                throw IllegalStateException("Projectile wrapper call requires one exactly typed Vec3 direction parameter in ${flow.unit.file}")
            }
            val vector = vectors.single().nameAsString
            val direction = MethodCallExpr(NameExpr("Direction"), "getNearest")
            direction.addArgument(FieldAccessExpr(NameExpr(vector), "x"))
            direction.addArgument(FieldAccessExpr(NameExpr(vector), "y"))
            direction.addArgument(FieldAccessExpr(NameExpr(vector), "z"))
            call.addArgument(direction)
        }
        flow.unit.cu.addImport("net.minecraft.core.Direction")
        modified += flow.unit
    }

    private fun migrateAccessor(contract: Contract) {
        replaceAnnotation(contract.projectile, "@Accessor(\"projectileItem\")")
        contract.projectile.setType(StaticJavaParser.parseType("ProjectileItem"))
        contract.projectile.parameters.clear()
        replaceAnnotation(contract.uncertainty, "@Accessor(\"dispenseConfig\")")
        contract.uncertainty.setType(StaticJavaParser.parseType("ProjectileItem.DispenseConfig"))
        contract.power.remove()
        removeImportIfUnused(contract.unit.cu, "org.spongepowered.asm.mixin.gen.Invoker", "Invoker", annotation = true)
        removeImportIfUnused(contract.unit.cu, "net.minecraft.core.Position", "Position")
        removeImportIfUnused(contract.unit.cu, "net.minecraft.world.entity.projectile.Projectile", "Projectile")
        removeImportIfUnused(contract.unit.cu, "net.minecraft.world.item.ItemStack", "ItemStack")
        removeImportIfUnused(contract.unit.cu, "net.minecraft.world.level.Level", "Level")
        contract.unit.cu.addImport("org.spongepowered.asm.mixin.gen.Accessor")
        contract.unit.cu.addImport("net.minecraft.world.item.ProjectileItem")
    }

    private fun hasExactAccessorReceiver(call: MethodCallExpr, contract: Contract, unit: SourceUnit): Boolean {
        val scope = call.scope.orElse(null) ?: return false
        if (scope is CastExpr) return scope.typeAsString == contract.declaration.nameAsString && importsContract(unit, contract)
        val name = scope as? NameExpr ?: return false
        if (!importsContract(unit, contract)) return false
        val methods = ancestorMethods(call)
        if (methods.isEmpty()) return false
        val variables = methods.flatMap { method ->
            method.findAll(VariableDeclarator::class.java).filter {
                it.nameAsString == name.nameAsString && it.typeAsString == contract.declaration.nameAsString &&
                    isLexicallyVisible(it, call)
            }
        }
        val parameters = methods.flatMap { method ->
            method.parameters.filter {
                it.nameAsString == name.nameAsString && it.typeAsString == contract.declaration.nameAsString
            }
        }
        val fields = ancestorClasses(call).flatMap { owner ->
            owner.fields.flatMap { it.variables }.filter {
                it.nameAsString == name.nameAsString && it.typeAsString == contract.declaration.nameAsString
            }
        }
        return variables.size + parameters.size + fields.size == 1
    }

    private fun isLexicallyVisible(variable: VariableDeclarator, use: MethodCallExpr): Boolean {
        val declaration = variable.parentNode.orElse(null) as? VariableDeclarationExpr ?: return false
        val statement = declaration.parentNode.orElse(null) as? ExpressionStmt ?: return false
        val block = statement.parentNode.orElse(null) as? BlockStmt ?: return false
        val declarationBegin = variable.range.orElse(null)?.begin ?: return false
        val useBegin = use.range.orElse(null)?.begin ?: return false
        return declarationBegin.isBefore(useBegin) && isAncestor(block, use)
    }

    private fun ancestorMethods(node: Node): List<MethodDeclaration> {
        val methods = mutableListOf<MethodDeclaration>()
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is MethodDeclaration) methods += current
            current = current.parentNode.orElse(null)
        }
        return methods
    }

    private fun ancestorClasses(node: Node): List<ClassOrInterfaceDeclaration> {
        val classes = mutableListOf<ClassOrInterfaceDeclaration>()
        var current = node.parentNode.orElse(null)
        while (current != null) {
            if (current is ClassOrInterfaceDeclaration) classes += current
            current = current.parentNode.orElse(null)
        }
        return classes
    }

    private fun isAncestor(ancestor: Node, node: Node): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parentNode.orElse(null)
        }
        return false
    }

    private fun importsContract(unit: SourceUnit, contract: Contract): Boolean {
        val packageName = unit.cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val contractPackage = contract.unit.cu.packageDeclaration.map { it.nameAsString }.orElse("")
        return packageName == contractPackage || hasExactImport(unit.cu, contract.qualifiedName)
    }

    private fun isProjectileMixin(declaration: ClassOrInterfaceDeclaration): Boolean =
        declaration.annotations.any { annotation ->
            annotation.nameAsString == "Mixin" && annotation.toString().replace(" ", "") == "@Mixin(ProjectileDispenseBehavior.class)"
        }

    private fun invokerTarget(method: MethodDeclaration): String? = method.annotations
        .firstOrNull { it.nameAsString == "Invoker" }
        ?.toString()
        ?.let { Regex("""@Invoker\(\s*\"([^\"]+)\"\s*\)""").matchEntire(it)?.groupValues?.get(1) }

    private fun requireSignature(unit: SourceUnit, method: MethodDeclaration, returnType: String, parameters: List<String>) {
        if (method.typeAsString != returnType || method.parameters.map { it.typeAsString } != parameters || method.body.isPresent) {
            throw IllegalStateException("Projectile dispense invoker '${method.nameAsString}' has unsupported signature in ${unit.file}")
        }
    }

    private fun requireNoArgs(call: MethodCallExpr, role: String, file: Path) {
        if (call.arguments.isNotEmpty() || !call.scope.isPresent) {
            throw IllegalStateException("Projectile $role invoker call has unsupported arguments or receiver in $file")
        }
    }

    private fun replaceAnnotation(method: MethodDeclaration, replacement: String) {
        val old = method.annotations.single { it.nameAsString == "Invoker" }
        old.replace(StaticJavaParser.parseAnnotation(replacement) as AnnotationExpr)
    }

    private fun uniqueParameterName(method: MethodDeclaration, base: String): String {
        val used = method.findAll(NameExpr::class.java).map { it.nameAsString }.toSet() +
            method.parameters.map { it.nameAsString }
        if (base !in used) return base
        var suffix = 2
        while ("$base$suffix" in used) suffix++
        return "$base$suffix"
    }

    private fun hasExactImport(cu: CompilationUnit, owner: String): Boolean =
        cu.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == owner }

    private fun removeImportIfUnused(
        cu: CompilationUnit,
        owner: String,
        simpleName: String,
        annotation: Boolean = false
    ) {
        val used = if (annotation) {
            cu.findAll(AnnotationExpr::class.java).any { it.nameAsString == simpleName }
        } else {
            cu.findAll(ClassOrInterfaceType::class.java).any { it.nameAsString == simpleName }
        }
        if (!used) {
            cu.imports.filter { !it.isStatic && !it.isAsterisk && it.nameAsString == owner }
                .toList().forEach { it.remove() }
        }
    }

    private fun parse(parser: JavaParser, file: Path, source: String): SourceUnit {
        val result = parser.parse(source)
        val cu = result.result.orElseThrow {
            IllegalStateException("Cannot parse projectile dispense accessor source $file: ${result.problems.joinToString()}")
        }
        return SourceUnit(file, source, cu)
    }
}
