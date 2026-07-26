package com.modporter.core.transforms.build

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

internal object ResidualRemovedApiGate {
    private const val POTION_UTILS = "net.minecraft.world.item.alchemy.PotionUtils"
    private const val POTIONS = "net.minecraft.world.item.alchemy.Potions"
    private const val POTION = "net.minecraft.world.item.alchemy.Potion"

    fun scan(projectDir: Path): List<String> {
        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val findings = mutableListOf<String>()
        Files.walk(projectDir).use { paths ->
            paths.filter { it.extension == "java" }
                .filter { file ->
                    val relative = projectDir.relativize(file).toString().replace('\\', '/')
                    !relative.startsWith("build/") &&
                        !relative.contains("/build/") &&
                        !relative.startsWith("src/references/") &&
                        !relative.contains("/src/references/")
                }
                .forEach { file ->
                    val source = file.readText()
                    if (!couldContainRemovedPotionApi(source)) return@forEach
                    val parsed = parser.parse(source)
                    val cu = parsed.result.orElse(null)
                    if (cu == null) {
                        findings +=
                            "Residual removed potion API scan could not parse " +
                                "${projectDir.relativize(file)}: " +
                                parsed.problems.joinToString("; ") { it.verboseMessage }
                        return@forEach
                    }
                    val categories = removedPotionApiCategories(cu)
                    categories.forEach { category ->
                        findings +=
                            "Residual removed Minecraft 1.20.1 $category API in " +
                                projectDir.relativize(file).toString().replace('\\', '/')
                    }
                }
        }
        return findings
    }

    private fun couldContainRemovedPotionApi(source: String): Boolean =
        source.contains("PotionUtils") ||
            source.contains("Potions.EMPTY") ||
            source.contains("Potion.byName") ||
            source.contains("$POTIONS.EMPTY") ||
            source.contains("$POTION.byName")

    private fun removedPotionApiCategories(cu: CompilationUnit): Set<String> {
        val categories = linkedSetOf<String>()
        if (cu.imports.any {
                it.nameAsString == POTION_UTILS ||
                    it.isStatic && it.nameAsString.startsWith("$POTION_UTILS.")
            }
        ) {
            categories += "PotionUtils"
        }
        if (cu.imports.any {
                it.isStatic && it.nameAsString == "$POTIONS.EMPTY"
            }
        ) {
            categories += "Potions.EMPTY"
        }
        if (cu.imports.any {
                it.isStatic && it.nameAsString == "$POTION.byName"
            }
        ) {
            categories += "Potion.byName"
        }

        cu.findAll(FieldAccessExpr::class.java)
            .filter { it.nameAsString == "EMPTY" }
            .filter { exactStaticScope(cu, it.scope, "Potions", POTIONS) }
            .forEach { categories += "Potions.EMPTY" }

        cu.findAll(MethodCallExpr::class.java).forEach { call ->
            val scope = call.scope.orElse(null) ?: return@forEach
            if (call.nameAsString == "byName" && exactStaticScope(cu, scope, "Potion", POTION)) {
                categories += "Potion.byName"
            }
            if (exactStaticScope(cu, scope, "PotionUtils", POTION_UTILS)) {
                categories += "PotionUtils"
            }
        }
        return categories
    }

    private fun exactStaticScope(
        cu: CompilationUnit,
        scope: Expression,
        simpleName: String,
        fqn: String
    ): Boolean {
        val declaredTypes = cu.findAll(TypeDeclaration::class.java).map { it.nameAsString }.toSet()
        val declaredValues =
            cu.findAll(Parameter::class.java).map { it.nameAsString }.toSet() +
                cu.findAll(VariableDeclarator::class.java).map { it.nameAsString }
        val text = scope.toString()
        if (text == fqn) {
            val root = fqn.substringBefore('.')
            return root !in declaredTypes && root !in declaredValues
        }
        return text == simpleName &&
            simpleName !in declaredTypes &&
            simpleName !in declaredValues &&
            cu.imports.any {
                !it.isStatic && !it.isAsterisk && it.nameAsString == fqn
            }
    }
}
