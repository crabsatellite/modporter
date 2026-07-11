package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Preserves legacy config-screen factories through NeoForge's direct factory extension point. */
class LegacyConfigScreenFactoryMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!Files.exists(srcDir)) return emptyList()
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val migratedSources = linkedMapOf<Path, String>()
        val changes = mutableListOf<Change>()

        Files.walk(srcDir).use { stream -> stream.filter { it.extension == "java" }.toList() }.forEach { file ->
            val source = file.readText()
            if (!source.contains("ConfigScreenHandler.ConfigScreenFactory")) return@forEach
            val parsed = parser.parse(source)
            val cu = parsed.result.orElseThrow {
                IllegalStateException("Cannot parse legacy config screen factory in $file: ${parsed.problems.joinToString()}")
            }
            val exactImport = cu.imports.any {
                !it.isStatic && it.nameAsString == "net.neoforged.neoforge.client.ConfigScreenHandler"
            }
            if (!exactImport) {
                throw IllegalStateException("Legacy config screen factory has no exact NeoForge owner import in $file")
            }
            LexicalPreservingPrinter.setup(cu)
            val factories = cu.findAll(ObjectCreationExpr::class.java).filter {
                it.typeAsString == "ConfigScreenHandler.ConfigScreenFactory"
            }
            if (factories.isEmpty()) {
                throw IllegalStateException("Legacy config screen factory import has no executable factory construction in $file")
            }
            factories.toList().forEach { factory -> migrateFactory(factory, file) }

            cu.imports.filter {
                !it.isStatic && it.nameAsString == "net.neoforged.neoforge.client.ConfigScreenHandler"
            }.toList().forEach { it.remove() }
            cu.addImport("net.neoforged.neoforge.client.gui.IConfigScreenFactory")
            val migrated = LexicalPreservingPrinter.print(cu)
            if (migrated.contains("ConfigScreenHandler.ConfigScreenFactory")) {
                throw IllegalStateException("Legacy config screen factory remains after migration in $file")
            }
            migratedSources[file] = migrated
            changes += Change(
                file = file,
                line = 1,
                description = "Preserve config screen factory registration through NeoForge's direct factory extension point",
                before = "ConfigScreenHandler.ConfigScreenFactory",
                after = "IConfigScreenFactory",
                confidence = Confidence.HIGH,
                ruleId = "struct-config-screen-factory-extension-point"
            )
        }

        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changes
    }

    private fun migrateFactory(factory: ObjectCreationExpr, file: Path) {
        if (factory.anonymousClassBody.isPresent || factory.arguments.size != 1 ||
            factory.arguments.single() !is LambdaExpr) {
            throw IllegalStateException("Legacy config screen factory constructor is not one direct lambda in $file")
        }
        val supplier = factory.parentNode.orElse(null) as? ExpressionStmt
            ?: throw IllegalStateException("Legacy config screen factory is not the body of a supplier lambda in $file")
        val supplierLambda = supplier.parentNode.orElse(null) as? LambdaExpr
            ?: throw IllegalStateException("Legacy config screen factory has no supplier lambda in $file")
        if (supplierLambda.parameters.isNotEmpty() || supplierLambda.body !== supplier) {
            throw IllegalStateException("Legacy config screen supplier has unsupported parameters or body in $file")
        }
        val register = supplierLambda.parentNode.orElse(null) as? MethodCallExpr
            ?: throw IllegalStateException("Legacy config screen supplier is not an extension-point argument in $file")
        if (register.nameAsString != "registerExtensionPoint" || register.arguments.size != 2 ||
            register.arguments[1] !== supplierLambda) {
            throw IllegalStateException("Legacy config screen supplier is not the second registerExtensionPoint argument in $file")
        }
        val classExpr = register.arguments[0] as? ClassExpr
            ?: throw IllegalStateException("Legacy config screen extension point has no class literal in $file")
        if (classExpr.typeAsString != "ConfigScreenHandler.ConfigScreenFactory") {
            throw IllegalStateException("Legacy config screen extension point class does not match its factory in $file")
        }
        register.setArgument(
            0,
            StaticJavaParser.parseExpression<Expression>("IConfigScreenFactory.class")
        )
        supplierLambda.setBody(ExpressionStmt(factory.arguments.single().clone()))
    }
}
