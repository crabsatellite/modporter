package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Inlines exact legacy BlockSource interface adapters into the target BlockSource record construction. */
class LegacyBlockSourceAdapterMigration {
    private data class Adapter(
        val file: Path,
        val cu: CompilationUnit,
        val declaration: ClassOrInterfaceDeclaration,
        val qualifiedName: String
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val files = Files.walk(srcDir).use { stream -> stream.filter { it.extension == "java" }.toList() }
        val adapters = files.mapNotNull { file ->
            val source = file.readText()
            if (!source.contains("implements BlockSource")) null else findAdapter(parser, file, source)
        }
        if (adapters.isEmpty()) return emptyList()

        val migratedSources = linkedMapOf<Path, String>()
        val deleted = linkedSetOf<Path>()
        val changes = mutableListOf<Change>()
        adapters.forEach { adapter ->
            val callerFiles = files.filter { it != adapter.file && it.readText().contains(adapter.declaration.nameAsString) }
            if (callerFiles.isEmpty()) {
                throw IllegalStateException("Legacy BlockSource adapter has no construction sites in ${adapter.file}")
            }
            var constructions = 0
            callerFiles.forEach { file ->
                val source = migratedSources[file] ?: file.readText()
                val cu = parse(parser, file, source)
                if (!importsAdapter(cu, adapter)) {
                    throw IllegalStateException("Legacy BlockSource adapter reference has no exact owner import in $file")
                }
                LexicalPreservingPrinter.setup(cu)
                val creations = cu.findAll(ObjectCreationExpr::class.java).filter {
                    it.typeAsString == adapter.declaration.nameAsString
                }
                if (creations.isEmpty()) {
                    throw IllegalStateException("Legacy BlockSource adapter import has unsupported non-constructor use in $file")
                }
                creations.toList().forEach { creation ->
                    inlineConstruction(cu, creation, file)
                    constructions++
                }
                cu.imports.filter {
                    !it.isStatic && !it.isAsterisk && it.nameAsString == adapter.qualifiedName
                }.toList().forEach { it.remove() }
                val migrated = LexicalPreservingPrinter.print(cu)
                if (migrated.contains(adapter.declaration.nameAsString)) {
                    throw IllegalStateException("Legacy BlockSource adapter remains after construction migration in $file")
                }
                migratedSources[file] = migrated
            }
            if (constructions == 0) {
                throw IllegalStateException("Legacy BlockSource adapter was not constructed in ${adapter.file}")
            }
            val unsupportedReferences = files.filter { file ->
                file != adapter.file && file !in migratedSources && file.readText().contains(adapter.declaration.nameAsString)
            }
            if (unsupportedReferences.isNotEmpty()) {
                throw IllegalStateException(
                    "Legacy BlockSource adapter has unsupported references: ${unsupportedReferences.joinToString()}"
                )
            }
            deleted.add(adapter.file)
            changes += Change(
                file = adapter.file,
                line = 1,
                description = "Inline exact legacy BlockSource adapter semantics into target record construction",
                before = "custom class implements BlockSource",
                after = "new BlockSource(serverLevel, pos, state, null)",
                confidence = Confidence.HIGH,
                ruleId = "struct-block-source-adapter-record"
            )
        }

        if (!dryRun) {
            migratedSources.forEach { (file, source) -> file.writeText(source) }
            deleted.forEach { it.deleteExisting() }
        }
        return changes
    }

    private fun findAdapter(parser: JavaParser, file: Path, source: String): Adapter {
        val cu = parse(parser, file, source)
        if (!hasExactImport(cu, "net.minecraft.core.dispenser.BlockSource")) {
            throw IllegalStateException("Legacy BlockSource adapter has no exact target BlockSource owner import in $file")
        }
        val declarations = cu.findAll(ClassOrInterfaceDeclaration::class.java).filter { declaration ->
            !declaration.isInterface && declaration.implementedTypes.any { it.nameAsString == "BlockSource" }
        }
        if (declarations.size != 1) {
            throw IllegalStateException("Legacy BlockSource adapter class is not unique in $file")
        }
        val declaration = declarations.single()
        if (declaration.extendedTypes.isNotEmpty() || declaration.members.any {
                it.isClassOrInterfaceDeclaration || it.isInitializerDeclaration
            }) {
            throw IllegalStateException("Legacy BlockSource adapter has unsupported inheritance or nested state in $file")
        }
        val constructors = declaration.constructors
        val primary = constructors.singleOrNull { constructor ->
            constructor.parameters.size == 3 &&
                constructor.parameters[1].typeAsString == "BlockPos" &&
                constructor.parameters[2].typeAsString == "Direction"
        } ?: throw IllegalStateException("Legacy BlockSource adapter lacks one exact three-argument constructor in $file")
        val contextType = primary.parameters[0].typeAsString
        validateFieldsAndConstructors(declaration, primary, contextType, file)
        validateMethods(declaration, file)
        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val qualifiedName = if (packageName.isEmpty()) declaration.nameAsString else "$packageName.${declaration.nameAsString}"
        return Adapter(file, cu, declaration, qualifiedName)
    }

    private fun validateFieldsAndConstructors(
        declaration: ClassOrInterfaceDeclaration,
        primary: ConstructorDeclaration,
        contextType: String,
        file: Path
    ) {
        val fields = declaration.fields.flatMap { it.variables }
        if (fields.size != 3 || fields.map { it.typeAsString }.toSet() != setOf("BlockPos", contextType, "Direction")) {
            throw IllegalStateException("Legacy BlockSource adapter fields are not exact position/context/facing state in $file")
        }
        val assignments = normalize(primary.body.toString())
        val parameterNames = primary.parameters.map { it.nameAsString }
        val expectedAssignments = fields.all { field ->
            val matchingParameter = primary.parameters.singleOrNull { it.typeAsString == field.typeAsString }
                ?: return@all false
            assignments.contains("this.${field.nameAsString}=${matchingParameter.nameAsString};")
        }
        if (!expectedAssignments || primary.body.statements.size != 3) {
            throw IllegalStateException("Legacy BlockSource adapter primary constructor has additional behavior in $file")
        }
        val secondary = declaration.constructors.filter { it !== primary }
        if (secondary.size > 1) {
            throw IllegalStateException("Legacy BlockSource adapter has unsupported constructor overloads in $file")
        }
        secondary.singleOrNull()?.let { constructor ->
            if (constructor.parameters.map { it.typeAsString } != listOf(contextType, "BlockPos") ||
                normalize(constructor.body.toString()) != "{this(${constructor.parameters[0].nameAsString},${constructor.parameters[1].nameAsString},null);}") {
                throw IllegalStateException("Legacy BlockSource adapter convenience constructor has unsupported behavior in $file")
            }
        }
        if (parameterNames.distinct().size != 3) {
            throw IllegalStateException("Legacy BlockSource adapter constructor parameters are ambiguous in $file")
        }
    }

    private fun validateMethods(declaration: ClassOrInterfaceDeclaration, file: Path) {
        val methods = declaration.methods
        val required = setOf("x", "y", "z", "getPos", "getBlockState", "getEntity", "getLevel")
        if (methods.map { it.nameAsString }.toSet() != required || methods.size != required.size) {
            throw IllegalStateException("Legacy BlockSource adapter methods are not the exact legacy interface surface in $file")
        }
        val fields = declaration.fields.flatMap { it.variables }
        val position = fields.single { it.typeAsString == "BlockPos" }.nameAsString
        val context = fields.single { it.typeAsString != "BlockPos" && it.typeAsString != "Direction" }.nameAsString
        val facing = fields.single { it.typeAsString == "Direction" }.nameAsString
        fun body(name: String): String = normalize(methods.single { it.nameAsString == name }.body.orElseThrow().toString())
        val expectedState =
            "{if($context.state.hasProperty(BlockStateProperties.FACING)&&$facing!=null)" +
                "return$context.state.setValue(BlockStateProperties.FACING,$facing);return$context.state;}"
        val levelPattern = Regex(
            """\{MinecraftServer([A-Za-z_$][\w$]*)=$context\.world\.getServer\(\);""" +
                """return\1!=null\?\1\.getLevel\($context\.world\.dimension\(\)\):null;\}"""
        )
        if (body("x") != "{return(double)this.$position.getX()+0.5D;}" ||
            body("y") != "{return(double)this.$position.getY()+0.5D;}" ||
            body("z") != "{return(double)this.$position.getZ()+0.5D;}" ||
            body("getPos") !in setOf("{return$position;}", "{returnthis.$position;}") ||
            body("getBlockState") != expectedState ||
            body("getEntity") != "{returnnull;}" ||
            !levelPattern.matches(body("getLevel"))) {
            throw IllegalStateException("Legacy BlockSource adapter method bodies contain unsupported semantics in $file")
        }
        if (methods.single { it.nameAsString == "getEntity" }.typeParameters.size != 1 ||
            methods.single { it.nameAsString == "getEntity" }.parameters.isNotEmpty()) {
            throw IllegalStateException("Legacy BlockSource adapter entity accessor signature is unsupported in $file")
        }
    }

    private fun inlineConstruction(
        cu: CompilationUnit,
        creation: ObjectCreationExpr,
        file: Path
    ) {
        if (creation.anonymousClassBody.isPresent || creation.arguments.size !in 2..3 ||
            creation.arguments.any { it !is NameExpr }) {
            throw IllegalStateException("Legacy BlockSource adapter construction requires two or three named arguments in $file")
        }
        val variable = creation.parentNode.orElse(null) as? VariableDeclarator
            ?: throw IllegalStateException("Legacy BlockSource adapter must initialize a local variable in $file")
        if (variable.initializer.orElse(null) !== creation) {
            throw IllegalStateException("Legacy BlockSource adapter is not the complete local initializer in $file")
        }
        val declaration = variable.parentNode.orElse(null)?.parentNode?.orElse(null) as? ExpressionStmt
            ?: throw IllegalStateException("Legacy BlockSource adapter local is not a statement in $file")
        val block = declaration.parentNode.orElse(null) as? BlockStmt
            ?: throw IllegalStateException("Legacy BlockSource adapter local is not in a block in $file")
        val context = creation.arguments[0].toString()
        val position = creation.arguments[1].toString()
        val direction = creation.arguments.getOrNull(2)?.toString()
        val used = block.findAll(NameExpr::class.java).map { it.nameAsString }.toMutableSet()
        fun unique(base: String): String {
            if (base !in used) {
                used += base
                return base
            }
            var suffix = 2
            while ("$base$suffix" in used) suffix++
            return "$base$suffix".also { used += it }
        }
        val server = unique("blockSourceServer")
        val level = unique("blockSourceLevel")
        val state = unique("blockSourceState")
        val statements = mutableListOf<String>()
        statements += "MinecraftServer $server = $context.world.getServer();"
        statements += "ServerLevel $level = $server != null ? $server.getLevel($context.world.dimension()) : null;"
        statements += "BlockState $state = $context.state;"
        if (direction != null) {
            statements += "if ($state.hasProperty(BlockStateProperties.FACING) && $direction != null) $state = $state.setValue(BlockStateProperties.FACING, $direction);"
        }
        val index = block.statements.indexOf(declaration)
        statements.forEachIndexed { offset, statement ->
            block.addStatement(index + offset, StaticJavaParser.parseStatement(statement))
        }
        creation.replace(
            StaticJavaParser.parseExpression<ObjectCreationExpr>("new BlockSource($level, $position, $state, null)")
        )
        cu.addImport("net.minecraft.server.MinecraftServer")
        cu.addImport("net.minecraft.server.level.ServerLevel")
        cu.addImport("net.minecraft.world.level.block.state.BlockState")
        cu.addImport("net.minecraft.world.level.block.state.properties.BlockStateProperties")
        cu.addImport("net.minecraft.core.dispenser.BlockSource")
    }

    private fun importsAdapter(cu: CompilationUnit, adapter: Adapter): Boolean {
        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val adapterPackage = adapter.cu.packageDeclaration.map { it.nameAsString }.orElse("")
        return packageName == adapterPackage || hasExactImport(cu, adapter.qualifiedName)
    }

    private fun hasExactImport(cu: CompilationUnit, owner: String): Boolean =
        cu.imports.any { !it.isStatic && !it.isAsterisk && it.nameAsString == owner }

    private fun normalize(value: String): String = value.replace(Regex("""\s+"""), "")

    private fun parse(parser: JavaParser, file: Path, source: String): CompilationUnit {
        val result = parser.parse(source)
        return result.result.orElseThrow {
            IllegalStateException("Cannot parse legacy BlockSource adapter source $file: ${result.problems.joinToString()}")
        }
    }
}
