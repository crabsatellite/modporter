package com.modporter.core.transforms.build

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * Reifies annotation-driven GameTest discovery into source-level registrations.
 * The migration deliberately requires a closed, statically resolvable holder set.
 */
internal class GameTestReflectionMigration {
    private data class JavaType(
        val file: Path,
        val packageName: String,
        val simpleName: String,
        val fqn: String,
        val imports: Map<String, String>,
        val staticImports: Map<String, String>,
        val source: String
    )

    private data class Candidate(
        val type: JavaType,
        val helperFqn: String,
        val groupAnnotationFqn: String,
        val markerKeyLiteral: String
    )

    private data class GroupValues(
        val namespaceExpression: String,
        val pathExpression: String
    )

    private data class TestMethod(
        val name: String,
        val templateExpression: String,
        val batchExpression: String,
        val rotationStepsExpression: String,
        val timeoutTicksExpression: String,
        val setupTicksExpression: String,
        val requiredExpression: String,
        val requiredSuccessesExpression: String,
        val attemptsExpression: String
    )

    private data class Holder(
        val type: JavaType,
        val group: GroupValues,
        val tests: List<TestMethod>
    )

    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return emptyList()
        val types = Files.walk(srcDir)
            .filter { it.extension == "java" }
            .toList()
            .mapNotNull(::parseJavaType)
        val byFqn = types.associateBy(JavaType::fqn)
        val candidates = types.mapNotNull { findCandidate(it, byFqn) }
        if (candidates.isEmpty()) return emptyList()

        val changes = mutableListOf<Change>()
        for (candidate in candidates) {
            val holderTypes = resolveClosedHolderSet(candidate, types, byFqn)
            require(holderTypes.isNotEmpty()) {
                "${candidate.type.fqn}.getTestsFrom has no statically resolvable Class<?> holder set"
            }
            val groupDefinition = byFqn[candidate.groupAnnotationFqn]
                ?: error("Cannot resolve source declaration for ${candidate.groupAnnotationFqn}")
            val groupDefaults = parseAnnotationDefaults(groupDefinition, byFqn)
            val holders = holderTypes.map { holderType ->
                val group = parseGroupValues(holderType, candidate.groupAnnotationFqn, groupDefaults, byFqn)
                val tests = parseGameTests(holderType, candidate.helperFqn, byFqn)
                require(tests.isNotEmpty()) {
                    "${holderType.fqn} is in the GameTest holder set but has no valid @GameTest methods"
                }
                Holder(holderType, group, tests)
            }

            val rewrittenCandidate = renderCandidate(candidate, holders)
            if (!dryRun) candidate.type.file.writeText(rewrittenCandidate)
            changes += Change(
                file = candidate.type.file,
                line = 1,
                description = "Replace reflective GameTest discovery with statically generated annotation registrations",
                before = "Class.getDeclaredMethods and Method.invoke GameTest discovery",
                after = "closed holder branches and direct typed test method references",
                confidence = Confidence.HIGH,
                ruleId = "build-gametest-static-registration"
            )

            changes += rewriteWrapperReturns(candidate, types, dryRun)
        }
        return changes
    }

    private fun parseJavaType(file: Path): JavaType? {
        val source = file.readText()
        val executable = maskJavaCommentsAndLiterals(source)
        val packageName = Regex("""(?m)^\s*package\s+([A-Za-z_$][\w$.]*)\s*;""")
            .find(executable)?.groupValues?.get(1) ?: return null
        val simpleName = Regex(
            """\bpublic\s+(?:(?:abstract|final|sealed|non-sealed)\s+)*(?:class|interface|record|@interface)\s+([A-Za-z_$][\w$]*)\b"""
        ).find(executable)?.groupValues?.get(1) ?: return null
        val imports = Regex("""(?m)^\s*import\s+(?!static\b)([A-Za-z_$][\w$.]*)\s*;""")
            .findAll(executable)
            .associate { match -> match.groupValues[1].substringAfterLast('.') to match.groupValues[1] }
        val staticImports = Regex("""(?m)^\s*import\s+static\s+([A-Za-z_$][\w$.]*)\s*;""")
            .findAll(executable)
            .map { it.groupValues[1] }
            .filterNot { it.endsWith(".*") }
            .associateBy { it.substringAfterLast('.') }
        return JavaType(file, packageName, simpleName, "$packageName.$simpleName", imports, staticImports, source)
    }

    private fun findCandidate(type: JavaType, byFqn: Map<String, JavaType>): Candidate? {
        val executable = maskJavaCommentsAndLiterals(type.source)
        val extendsTestFunction = Regex(
            """\bclass\s+${Regex.escape(type.simpleName)}\s+extends\s+(?:net\.minecraft\.gametest\.framework\.)?TestFunction\b"""
        ).containsMatchIn(executable)
        if (!extendsTestFunction ||
            !executable.contains("Class::getDeclaredMethods") ||
            !executable.contains("method.invoke(") ||
            !Regex("""\bgetTestsFrom\s*\(\s*Class\s*<\s*\?\s*>\s*\.\.\.""").containsMatchIn(executable)
        ) return null

        val helperName = Regex(
            """getParameterTypes\s*\(\s*\)\s*\[\s*0\s*]\s*!=\s*([A-Za-z_$][\w$.]*)\.class"""
        ).find(executable)?.groupValues?.get(1)
            ?: error("Cannot prove the required GameTest helper type in ${type.fqn}")
        val groupName = Regex(
            """getAnnotation\s*\(\s*([A-Za-z_$][\w$.]*)\.class\s*\)"""
        ).findAll(executable)
            .map { it.groupValues[1] }
            .firstOrNull { it.substringAfterLast('.') != "GameTest" }
            ?: error("Cannot prove the class-level GameTest group annotation in ${type.fqn}")
        val markerKeyLiteral = Regex(
            """putString\s*\(\s*(\"(?:\\.|[^\"\\])*\")\s*,\s*fullName\s*\)"""
        ).find(type.source)?.groupValues?.get(1)
            ?: error("Cannot prove the persistent GameTest function key in ${type.fqn}")

        val helperFqn = resolveTypeName(type, helperName, byFqn)
            ?: error("Cannot resolve GameTest helper $helperName from ${type.fqn}")
        val groupFqn = resolveTypeName(type, groupName, byFqn)
            ?: error("Cannot resolve GameTest group annotation $groupName from ${type.fqn}")
        return Candidate(type, helperFqn, groupFqn, markerKeyLiteral)
    }

    private fun resolveClosedHolderSet(
        candidate: Candidate,
        types: List<JavaType>,
        byFqn: Map<String, JavaType>
    ): List<JavaType> {
        val holders = linkedMapOf<String, JavaType>()
        var calls = 0
        val callPattern = Regex(
            """\b${Regex.escape(candidate.type.simpleName)}\s*\.\s*getTestsFrom\s*\("""
        )
        for (caller in types) {
            val executable = maskJavaCommentsAndLiterals(caller.source)
            for (call in callPattern.findAll(executable)) {
                calls++
                val openParen = executable.indexOf('(', call.range.first)
                val closeParen = findMatching(executable, openParen, '(', ')')
                require(closeParen > openParen) { "Unbalanced getTestsFrom call in ${caller.fqn}" }
                val arguments = splitTopLevel(caller.source.substring(openParen + 1, closeParen))
                require(arguments.isNotEmpty()) { "Dynamic empty getTestsFrom call in ${caller.fqn}" }
                for (argument in arguments) {
                    val classNames = when {
                        Regex("""^[A-Za-z_$][\w$.]*\.class$""").matches(argument.trim()) ->
                            listOf(argument.trim().removeSuffix(".class"))
                        Regex("""^[A-Za-z_$][\w$]*$""").matches(argument.trim()) ->
                            resolveClassArray(caller, argument.trim())
                        else -> error(
                            "${caller.fqn} passes a non-static GameTest holder expression to " +
                                "${candidate.type.simpleName}.getTestsFrom: ${argument.trim()}"
                        )
                    }
                    require(classNames.isNotEmpty()) {
                        "Cannot resolve GameTest holder array ${argument.trim()} in ${caller.fqn}"
                    }
                    for (className in classNames) {
                        val fqn = resolveTypeName(caller, className, byFqn)
                            ?: error("Cannot resolve GameTest holder $className from ${caller.fqn}")
                        val holder = byFqn[fqn]
                            ?: error("GameTest holder $fqn is not declared in project source")
                        holders.putIfAbsent(fqn, holder)
                    }
                }
            }
        }
        require(calls > 0) {
            "No source call sites found for ${candidate.type.fqn}.getTestsFrom"
        }
        return holders.values.toList()
    }

    private fun resolveClassArray(type: JavaType, variable: String): List<String> {
        val executable = maskJavaCommentsAndLiterals(type.source)
        val declaration = Regex(
            """\bClass\s*<\s*\?\s*>\s*\[\s*]\s+${Regex.escape(variable)}\s*=\s*\{"""
        ).find(executable) ?: return emptyList()
        val openBrace = executable.indexOf('{', declaration.range.first)
        val closeBrace = findMatching(executable, openBrace, '{', '}')
        if (closeBrace <= openBrace) return emptyList()
        return splitTopLevel(type.source.substring(openBrace + 1, closeBrace)).map { entry ->
            val match = Regex("""^([A-Za-z_$][\w$.]*)\.class$""").matchEntire(entry.trim())
                ?: error("GameTest holder array $variable in ${type.fqn} contains a dynamic entry: ${entry.trim()}")
            match.groupValues[1]
        }
    }

    private fun parseAnnotationDefaults(
        annotationType: JavaType,
        byFqn: Map<String, JavaType>
    ): Map<String, String> {
        val executable = maskJavaCommentsAndLiterals(annotationType.source)
        require(Regex("""@interface\s+${Regex.escape(annotationType.simpleName)}\b""").containsMatchIn(executable)) {
            "${annotationType.fqn} is not a source annotation declaration"
        }
        return Regex(
            """\b[A-Za-z_$][\w$<>.?\[\]]*\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*(?:default\s+([^;]+))?\s*;"""
        ).findAll(annotationType.source).associate { match ->
            match.groupValues[1] to qualifyAnnotationExpression(
                annotationType,
                match.groupValues[2].trim(),
                byFqn
            )
        }
    }

    private fun parseGroupValues(
        holder: JavaType,
        groupAnnotationFqn: String,
        defaults: Map<String, String>,
        byFqn: Map<String, JavaType>
    ): GroupValues {
        val annotationSimpleName = groupAnnotationFqn.substringAfterLast('.')
        val executable = maskJavaCommentsAndLiterals(holder.source)
        val classHeader = Regex(
            """\b(?:public\s+)?(?:final\s+|abstract\s+)?class\s+${Regex.escape(holder.simpleName)}\b"""
        ).find(executable) ?: error("Cannot find top-level class declaration for ${holder.fqn}")
        val annotation = Regex(
            """@(?:${Regex.escape(groupAnnotationFqn)}|${Regex.escape(annotationSimpleName)})\b"""
        ).findAll(executable.substring(0, classHeader.range.first)).lastOrNull()
            ?: error("${holder.fqn} is not annotated with @$annotationSimpleName")
        val annotationStart = annotation.range.first
        var cursor = annotation.range.last + 1
        while (cursor < executable.length && executable[cursor].isWhitespace()) cursor++
        val values = if (executable.getOrNull(cursor) == '(') {
            val close = findMatching(executable, cursor, '(', ')')
            require(close > cursor && close < classHeader.range.first) {
                "Unbalanced @$annotationSimpleName on ${holder.fqn}"
            }
            parseNamedAnnotationArguments(holder.source.substring(cursor + 1, close))
        } else {
            emptyMap()
        }
        require(annotationStart < classHeader.range.first)
        val path = values["path"]?.let { qualifyAnnotationExpression(holder, it, byFqn) }
            ?: defaults["path"].orEmpty()
        val namespace = values["namespace"]?.let { qualifyAnnotationExpression(holder, it, byFqn) }
            ?: defaults["namespace"].orEmpty()
        require(path.isNotBlank()) { "${holder.fqn} has no statically defined @$annotationSimpleName path" }
        require(namespace.isNotBlank()) { "${holder.fqn} has no statically defined @$annotationSimpleName namespace" }
        return GroupValues(namespace, path)
    }

    private fun parseGameTests(
        holder: JavaType,
        helperFqn: String,
        byFqn: Map<String, JavaType>
    ): List<TestMethod> {
        val executable = maskJavaCommentsAndLiterals(holder.source)
        val annotationPattern = Regex("""@(?:net\.minecraft\.gametest\.framework\.)?GameTest\b""")
        return annotationPattern.findAll(executable).map { annotation ->
            var cursor = annotation.range.last + 1
            while (cursor < executable.length && executable[cursor].isWhitespace()) cursor++
            val arguments = if (executable.getOrNull(cursor) == '(') {
                val close = findMatching(executable, cursor, '(', ')')
                require(close > cursor) { "Unbalanced @GameTest on ${holder.fqn}" }
                val parsed = parseNamedAnnotationArguments(holder.source.substring(cursor + 1, close))
                cursor = close + 1
                parsed
            } else {
                emptyMap()
            }
            val method = Regex(
                """\G\s*(?:public\s+)?static\s+void\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)"""
            ).find(executable, cursor)
                ?: error("@GameTest in ${holder.fqn} is not followed by a static void method")
            require(method.range.first == cursor) {
                "@GameTest in ${holder.fqn} has unsupported declarations before its method"
            }
            val parameters = splitTopLevel(holder.source.substring(
                executable.indexOf('(', method.range.first) + 1,
                findMatching(executable, executable.indexOf('(', method.range.first), '(', ')')
            ))
            require(parameters.size == 1) {
                "${holder.fqn}.${method.groupValues[1]} must have exactly one GameTest helper parameter"
            }
            val parameterType = Regex(
                """^(?:final\s+)?([A-Za-z_$][\w$.]*)\s+[A-Za-z_$][\w$]*$"""
            ).matchEntire(parameters.single().trim())?.groupValues?.get(1)
                ?: error("Cannot parse GameTest parameter on ${holder.fqn}.${method.groupValues[1]}")
            val resolvedParameter = resolveTypeName(holder, parameterType, byFqn)
            require(resolvedParameter == helperFqn) {
                "${holder.fqn}.${method.groupValues[1]} uses $resolvedParameter instead of $helperFqn"
            }
            val qualifiedArguments = arguments.mapValues { (_, value) ->
                qualifyAnnotationExpression(holder, value, byFqn)
            }
            val template = qualifiedArguments["template"] ?: "\"\""
            require(template != "\"\"" && template.isNotBlank()) {
                "${holder.fqn}.${method.groupValues[1]} must provide a non-empty GameTest template"
            }
            TestMethod(
                name = method.groupValues[1],
                templateExpression = template,
                batchExpression = qualifiedArguments["batch"] ?: "\"defaultBatch\"",
                rotationStepsExpression = qualifiedArguments["rotationSteps"] ?: "0",
                timeoutTicksExpression = qualifiedArguments["timeoutTicks"] ?: "100",
                setupTicksExpression = qualifiedArguments["setupTicks"] ?: "0L",
                requiredExpression = qualifiedArguments["required"] ?: "true",
                requiredSuccessesExpression = qualifiedArguments["requiredSuccesses"] ?: "1",
                attemptsExpression = qualifiedArguments["attempts"] ?: "1"
            )
        }.toList()
    }

    private fun renderCandidate(candidate: Candidate, holders: List<Holder>): String {
        val type = candidate.type
        val helper = candidate.helperFqn
        return buildString {
            appendLine("package ${type.packageName};")
            appendLine()
            appendLine("import java.util.ArrayList;")
            appendLine("import java.util.Collection;")
            appendLine("import java.util.Comparator;")
            appendLine("import java.util.HashMap;")
            appendLine("import java.util.List;")
            appendLine("import java.util.Map;")
            appendLine("import java.util.function.Consumer;")
            appendLine()
            appendLine("import net.minecraft.core.BlockPos;")
            appendLine("import net.minecraft.gametest.framework.GameTestHelper;")
            appendLine("import net.minecraft.gametest.framework.StructureUtils;")
            appendLine("import net.minecraft.gametest.framework.TestFunction;")
            appendLine("import net.minecraft.world.level.block.Rotation;")
            appendLine("import net.minecraft.world.level.block.entity.StructureBlockEntity;")
            appendLine()
            appendLine("/**")
            appendLine(" * Typed GameTest descriptors generated from the project's source annotations.")
            appendLine(" */")
            appendLine("public class ${type.simpleName} {")
            appendLine("\tpublic static final Map<String, ${type.simpleName}> NAMES_TO_FUNCTIONS = new HashMap<>();")
            appendLine()
            appendLine("\tpublic final String fullName;")
            appendLine("\tpublic final String simpleName;")
            appendLine("\tpublic final TestFunction testFunction;")
            appendLine()
            appendLine("\tprivate ${type.simpleName}(String fullName, String simpleName, String batchName, String structureName,")
            appendLine("\t\t\t\t\t\t\t Rotation rotation, int maxTicks, long setupTicks, boolean required,")
            appendLine("\t\t\t\t\t\t\t int maxAttempts, int requiredSuccesses, Consumer<GameTestHelper> function) {")
            appendLine("\t\tthis.fullName = fullName;")
            appendLine("\t\tthis.simpleName = simpleName;")
            appendLine("\t\tthis.testFunction = new TestFunction(")
            appendLine("\t\t\tbatchName, structureName, structureName, rotation, maxTicks, setupTicks,")
            appendLine("\t\t\trequired, false, maxAttempts, requiredSuccesses, true,")
            appendLine("\t\t\thelper -> {")
            appendLine("\t\t\t\tStructureBlockEntity structure = (StructureBlockEntity) helper.getBlockEntity(BlockPos.ZERO);")
            appendLine("\t\t\t\tstructure.getPersistentData().putString(${candidate.markerKeyLiteral}, fullName);")
            appendLine("\t\t\t\tfunction.accept(helper);")
            appendLine("\t\t\t}")
            appendLine("\t\t);")
            appendLine("\t\tNAMES_TO_FUNCTIONS.put(fullName, this);")
            appendLine("\t}")
            appendLine()
            appendLine("\tpublic static Collection<TestFunction> getTestsFrom(Class<?>... classes) {")
            appendLine("\t\tList<TestFunction> tests = new ArrayList<>();")
            appendLine("\t\tfor (Class<?> owner : classes) {")
            for (holder in holders) {
                appendLine("\t\t\tif (owner == ${holder.type.fqn}.class) {")
                for (test in holder.tests) {
                    appendLine("\t\t\t\ttests.add(of(")
                    append("\t\t\t\t\t\t\"").append(holder.type.fqn).append("\", \"")
                        .append(holder.type.simpleName).appendLine("\",")
                    append("\t\t\t\t\t\t").append(holder.group.namespaceExpression).append(", ")
                        .append(holder.group.pathExpression).appendLine(",")
                    append("\t\t\t\t\t\t\"").append(test.name).append("\", ")
                        .append(test.templateExpression).append(", ").append(test.batchExpression).appendLine(",")
                    append("\t\t\t\t\t\t").append(test.rotationStepsExpression).append(", ")
                        .append(test.timeoutTicksExpression).append(", ").append(test.setupTicksExpression).appendLine(",")
                    append("\t\t\t\t\t\t").append(test.requiredExpression).append(", ")
                        .append(test.attemptsExpression).append(", ")
                        .append(test.requiredSuccessesExpression).appendLine(",")
                    appendLine("\t\t\t\t\t\t${holder.type.fqn}::${test.name}")
                    appendLine("\t\t\t\t).testFunction);")
                }
                appendLine("\t\t\t\tcontinue;")
                appendLine("\t\t\t}")
            }
            appendLine("\t\t\tthrow new IllegalArgumentException(\"No statically generated GameTest descriptors for \" + owner.getName());")
            appendLine("\t\t}")
            appendLine("\t\treturn tests.stream()")
            appendLine("\t\t\t.sorted(Comparator.comparing(TestFunction::testName))")
            appendLine("\t\t\t.toList();")
            appendLine("\t}")
            appendLine()
            appendLine("\tprivate static ${type.simpleName} of(String ownerName, String ownerSimpleName,")
            appendLine("\t\t\t\t\t\t\t\t String namespace, String path, String methodName, String template,")
            appendLine("\t\t\t\t\t\t\t\t String batch, int rotationSteps, int maxTicks, long setupTicks,")
            appendLine("\t\t\t\t\t\t\t\t boolean required, int maxAttempts, int requiredSuccesses,")
            appendLine("\t\t\t\t\t\t\t\t Consumer<$helper> function) {")
            appendLine("\t\tString structure = namespace + \":gametest/\" + path + \"/\" + template;")
            appendLine("\t\tString fullName = ownerName + \".\" + methodName;")
            appendLine("\t\tString simpleName = ownerSimpleName + '.' + methodName;")
            appendLine("\t\treturn new ${type.simpleName}(")
            appendLine("\t\t\tfullName, simpleName, batch, structure,")
            appendLine("\t\t\tStructureUtils.getRotationForRotationSteps(rotationSteps), maxTicks, setupTicks,")
            appendLine("\t\t\trequired, maxAttempts, requiredSuccesses,")
            appendLine("\t\t\thelper -> function.accept($helper.of(helper))")
            appendLine("\t\t);")
            appendLine("\t}")
            appendLine("}")
        }
    }

    private fun rewriteWrapperReturns(candidate: Candidate, types: List<JavaType>, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        for (type in types) {
            if (type.file == candidate.type.file) continue
            val executable = maskJavaCommentsAndLiterals(type.source)
            if (!Regex("""\b${Regex.escape(candidate.type.simpleName)}\b""").containsMatchIn(executable)) continue
            val variables = Regex(
                """\b${Regex.escape(candidate.type.simpleName)}\s+([A-Za-z_$][\w$]*)\s*="""
            ).findAll(executable).map { it.groupValues[1] }.toSet()
            if (variables.isEmpty()) continue
            var modified = type.source
            var count = 0
            for (variable in variables) {
                val returnPattern = Regex("""\breturn\s+${Regex.escape(variable)}\s*;""")
                val matches = returnPattern.findAll(maskJavaCommentsAndLiterals(modified)).toList()
                for (match in matches.asReversed()) {
                    modified = modified.replaceRange(match.range, "return $variable.testFunction;")
                    count++
                }
            }
            if (count == 0 || modified == type.source) continue
            if (!dryRun) {
                type.file.parent.createDirectories()
                type.file.writeText(modified)
            }
            changes += Change(
                file = type.file,
                line = 1,
                description = "Unwrap statically generated GameTest descriptors at TestFunction return sites",
                before = "return annotation descriptor wrapper",
                after = "return wrapper.testFunction",
                confidence = Confidence.HIGH,
                ruleId = "build-gametest-wrapper-return"
            )
        }
        return changes
    }

    private fun qualifyAnnotationExpression(
        lexicalOwner: JavaType,
        expression: String,
        byFqn: Map<String, JavaType>
    ): String {
        if (expression.isBlank()) return expression
        var result = expression

        val typeQualifierPattern = Regex("""(?<![\w$.])([A-Z][A-Za-z0-9_$]*)\s*\.""")
        val typeMatches = typeQualifierPattern.findAll(maskJavaCommentsAndLiterals(result)).toList()
        for (match in typeMatches.asReversed()) {
            val simpleName = match.groupValues[1]
            val resolved = lexicalOwner.imports[simpleName]
                ?: "${lexicalOwner.packageName}.$simpleName".takeIf(byFqn::containsKey)
                ?: continue
            val range = match.groups[1]!!.range
            result = result.replaceRange(range, resolved)
        }

        val constantPattern = Regex("""(?<![\w$.])([A-Z][A-Z0-9_$]*)\b(?!\s*\.)""")
        val constantMatches = constantPattern.findAll(maskJavaCommentsAndLiterals(result)).toList()
        for (match in constantMatches.asReversed()) {
            val name = match.groupValues[1]
            val resolved = lexicalOwner.staticImports[name]
                ?: if (declaresStaticField(lexicalOwner, name)) "${lexicalOwner.fqn}.$name" else null
                ?: error(
                    "Cannot resolve annotation constant $name from lexical owner ${lexicalOwner.fqn}"
                )
            result = result.replaceRange(match.groups[1]!!.range, resolved)
        }
        return result
    }

    private fun declaresStaticField(type: JavaType, name: String): Boolean =
        Regex(
            """\bstatic\s+(?:final\s+)?[A-Za-z_$][\w$<>.?\[\]]*\s+${Regex.escape(name)}\s*="""
        ).containsMatchIn(maskJavaCommentsAndLiterals(type.source))

    private fun resolveTypeName(type: JavaType, name: String, byFqn: Map<String, JavaType>): String? {
        if ('.' in name) return name
        type.imports[name]?.let { return it }
        val samePackage = "${type.packageName}.$name"
        if (byFqn.isEmpty() || byFqn.containsKey(samePackage)) return samePackage
        return null
    }

    private fun parseNamedAnnotationArguments(source: String): Map<String, String> {
        if (source.isBlank()) return emptyMap()
        return splitTopLevel(source).associate { argument ->
            val equals = findTopLevelEquals(argument)
            require(equals > 0) { "Annotation argument is not explicitly named: ${argument.trim()}" }
            argument.substring(0, equals).trim() to argument.substring(equals + 1).trim()
        }
    }

    private fun findTopLevelEquals(value: String): Int {
        var paren = 0
        var brace = 0
        var bracket = 0
        var angle = 0
        var inString = false
        var quote = '\u0000'
        var escaped = false
        value.forEachIndexed { index, char ->
            if (inString) {
                if (char == quote && !escaped) inString = false
                escaped = char == '\\' && !escaped
                if (char != '\\') escaped = false
            } else {
                when (char) {
                    '\'', '"' -> { inString = true; quote = char }
                    '(' -> paren++
                    ')' -> paren--
                    '{' -> brace++
                    '}' -> brace--
                    '[' -> bracket++
                    ']' -> bracket--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    '=' -> if (paren == 0 && brace == 0 && bracket == 0 && angle == 0) return index
                }
            }
        }
        return -1
    }

    private fun splitTopLevel(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var paren = 0
        var brace = 0
        var bracket = 0
        var angle = 0
        var inString = false
        var quote = '\u0000'
        var escaped = false
        value.forEachIndexed { index, char ->
            if (inString) {
                if (char == quote && !escaped) inString = false
                escaped = char == '\\' && !escaped
                if (char != '\\') escaped = false
            } else {
                when (char) {
                    '\'', '"' -> { inString = true; quote = char }
                    '(' -> paren++
                    ')' -> paren--
                    '{' -> brace++
                    '}' -> brace--
                    '[' -> bracket++
                    ']' -> bracket--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    ',' -> if (paren == 0 && brace == 0 && bracket == 0 && angle == 0) {
                        result += value.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
        }
        result += value.substring(start).trim()
        return result.filter(String::isNotEmpty)
    }

    private fun findMatching(source: String, openIndex: Int, open: Char, close: Char): Int {
        if (source.getOrNull(openIndex) != open) return -1
        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun maskJavaCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        fun mask(start: Int, endExclusive: Int) {
            for (position in start until endExclusive.coerceAtMost(chars.size)) {
                if (chars[position] != '\n' && chars[position] != '\r') chars[position] = ' '
            }
        }
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                    mask(index, end)
                    index = end
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
                    mask(index, end)
                    index = end
                }
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3).let { if (it < 0) source.length else it + 3 }
                    mask(index, end)
                    index = end
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    var end = index + 1
                    var escaped = false
                    while (end < source.length) {
                        val char = source[end]
                        if (char == quote && !escaped) {
                            end++
                            break
                        }
                        escaped = char == '\\' && !escaped
                        if (char != '\\') escaped = false
                        end++
                    }
                    mask(index, end)
                    index = end
                }
                else -> index++
            }
        }
        return String(chars)
    }
}
