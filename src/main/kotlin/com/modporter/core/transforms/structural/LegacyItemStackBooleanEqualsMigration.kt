package com.modporter.core.transforms.structural

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/** Migrates Forge's removed ItemStack.equals(other, compareCaps) overload with exact receiver typing. */
class LegacyItemStackBooleanEqualsMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val files = Files.walk(sourceRoot).use { stream -> stream.filter { it.extension == "java" }.toList() }
        val candidates = files.filter { file ->
            val source = file.readText()
            source.contains(".equals") && Regex(""",\s*(?:true|false)\s*\)""").containsMatchIn(source)
        }
        if (candidates.isEmpty()) return emptyList()
        val index = JavaProjectTypeIndex.build(sourceRoot)
        val migratedSources = linkedMapOf<Path, String>()
        val changes = mutableListOf<Change>()

        candidates.forEach { file ->
            val cu = index.unit(file)
            LexicalPreservingPrinter.setup(cu)
            var rewrites = 0
            cu.findAll(MethodCallExpr::class.java).toList().forEach { call ->
                if (call.nameAsString != "equals" || call.arguments.size != 2 ||
                    call.arguments[1] !is BooleanLiteralExpr || !call.scope.isPresent) return@forEach
                val receiver = call.scope.get()
                val receiverType = index.expressionType(receiver, call)
                if (receiverType != ITEM_STACK) {
                    val exactImport = cu.imports.any {
                        !it.isStatic && !it.isAsterisk && it.nameAsString == ITEM_STACK
                    }
                    if (receiverType == null && exactImport) {
                        throw IllegalStateException(
                            "Cannot prove two-argument equals receiver '$receiver' is ItemStack in $file"
                        )
                    }
                    return@forEach
                }
                val other = call.arguments[0].clone()
                val compareCaps = (call.arguments[1] as BooleanLiteralExpr).value
                val replacement = if (!compareCaps) {
                    MethodCallExpr(NameExpr("ItemStack"), "matches")
                        .addArgument(receiver.clone())
                        .addArgument(other)
                } else {
                    val name = receiver as? NameExpr ?: throw IllegalStateException(
                        "compareCaps=true ItemStack receiver must be a single-evaluation named value in $file"
                    )
                    val otherName = other as? NameExpr ?: throw IllegalStateException(
                        "compareCaps=true ItemStack argument must be a single-evaluation named value in $file"
                    )
                    EnclosedExpr(
                        StaticJavaParser.parseExpression<Expression>(
                            "${name.nameAsString}.getCount() == ${otherName.nameAsString}.getCount() && " +
                                "ItemStack.isSameItem(${name.nameAsString}, ${otherName.nameAsString})"
                        )
                    )
                }
                call.replace(replacement)
                rewrites++
            }
            if (rewrites == 0) return@forEach
            cu.addImport(ITEM_STACK)
            val exactRemnants = cu.findAll(MethodCallExpr::class.java).filter { call ->
                call.nameAsString == "equals" && call.arguments.size == 2 &&
                    call.arguments[1] is BooleanLiteralExpr && call.scope.isPresent &&
                    index.expressionType(call.scope.get(), call) == ITEM_STACK
            }
            if (exactRemnants.isNotEmpty()) {
                throw IllegalStateException("Legacy ItemStack boolean equals remains after exact migration in $file")
            }
            val migrated = LexicalPreservingPrinter.print(cu)
            migratedSources[file] = migrated
            changes += Change(
                file = file,
                line = 1,
                description = "Migrate exact ItemStack boolean comparison semantics",
                before = "stack.equals(other, compareCaps)",
                after = "ItemStack.matches/isSameItem with exact count semantics",
                confidence = Confidence.HIGH,
                ruleId = "struct-item-stack-boolean-equals"
            )
        }
        if (!dryRun) migratedSources.forEach { (file, source) -> file.writeText(source) }
        return changes
    }

    private companion object {
        const val ITEM_STACK = "net.minecraft.world.item.ItemStack"
    }
}
