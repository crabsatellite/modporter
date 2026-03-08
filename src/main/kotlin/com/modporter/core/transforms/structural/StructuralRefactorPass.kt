package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.*
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.type.ClassOrInterfaceType
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Pass 3: Structural refactoring for complex API migrations.
 * Handles capability system, networking, and other multi-node transformations.
 */
class StructuralRefactorPass : Pass {
    override val name = "Structural Refactor"
    override val order = 3

    override fun analyze(projectDir: Path): PassResult = processFiles(projectDir, dryRun = true)
    override fun apply(projectDir: Path): PassResult = processFiles(projectDir, dryRun = false)

    private fun processFiles(projectDir: Path, dryRun: Boolean): PassResult {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val parser = JavaParser()

        val javaFiles = Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/")
            }}
            .toList()

        for (file in javaFiles) {
            try {
                val source = file.readText()
                val parseResult = parser.parse(source)
                if (!parseResult.isSuccessful) continue

                val cu = parseResult.result.orElse(null) ?: continue
                val fileChanges = mutableListOf<Change>()

                // Detect and transform capability patterns
                detectCapabilityPatterns(cu, file, fileChanges)

                // Detect and transform networking patterns
                detectNetworkingPatterns(cu, file, fileChanges)

                // Detect LazyOptional usage
                detectLazyOptionalUsage(cu, file, fileChanges)

                // Detect DistExecutor usage (removed in NeoForge)
                detectDistExecutorUsage(cu, file, fileChanges)

                changes.addAll(fileChanges)

                if (!dryRun && fileChanges.isNotEmpty()) {
                    file.writeText(cu.toString())
                }
            } catch (e: Exception) {
                errors.add("Structural analysis error in $file: ${e.message}")
            }
        }

        // Transform packet classes to implement CustomPacketPayload + generate ModNetwork.java
        try {
            val packetChanges = transformPacketClasses(projectDir, dryRun)
            changes.addAll(packetChanges)
        } catch (e: Exception) {
            errors.add("Packet class transformation error: ${e.message}")
        }

        // Remove EVENT_BUS.register(this) from @Mod classes without @SubscribeEvent methods
        try {
            val busChanges = removeEmptyEventBusRegistration(projectDir, dryRun)
            changes.addAll(busChanges)
        } catch (e: Exception) {
            errors.add("Event bus cleanup error: ${e.message}")
        }

        // Migrate FMLJavaModLoadingContext constructor to IEventBus/ModContainer
        try {
            val fmlChanges = migrateFMLJavaModLoadingContext(projectDir, dryRun)
            changes.addAll(fmlChanges)
        } catch (e: Exception) {
            errors.add("FMLJavaModLoadingContext migration error: ${e.message}")
        }

        // Clean up @EventBusSubscriber: remove from classes without @SubscribeEvent,
        // and remove deprecated bus= parameter
        try {
            val ebsChanges = cleanupEventBusSubscriber(projectDir, dryRun)
            changes.addAll(ebsChanges)
        } catch (e: Exception) {
            errors.add("EventBusSubscriber cleanup error: ${e.message}")
        }

        // Comment out orphaned SimpleChannel code (chain calls, INSTANCE.xxx methods)
        try {
            val netCleanupChanges = cleanupSimpleChannelRemnants(projectDir, dryRun)
            changes.addAll(netCleanupChanges)
        } catch (e: Exception) {
            errors.add("SimpleChannel cleanup error: ${e.message}")
        }

        // Fix static @SubscribeEvent with instance registration:
        // EVENT_BUS.register(this) -> EVENT_BUS.register(ClassName.class) when methods are static
        try {
            val staticBusChanges = fixStaticEventBusRegistration(projectDir, dryRun)
            changes.addAll(staticBusChanges)
        } catch (e: Exception) {
            errors.add("Static EventBus registration fix error: ${e.message}")
        }

        // Migrate Block.use() -> useWithoutItem() (1.20 -> 1.21 API change)
        try {
            val useChanges = migrateBlockUseMethod(projectDir, dryRun)
            changes.addAll(useChanges)
        } catch (e: Exception) {
            errors.add("Block.use() migration error: ${e.message}")
        }

        // Migrate ItemEntityPickupEvent.Pre API changes
        try {
            val pickupChanges = migrateItemPickupEvent(projectDir, dryRun)
            changes.addAll(pickupChanges)
        } catch (e: Exception) {
            errors.add("ItemEntityPickupEvent migration error: ${e.message}")
        }

        // Remove onAddedToWorld() override (removed in 1.21)
        try {
            val removedMethodChanges = removeObsoleteMethods(projectDir, dryRun)
            changes.addAll(removedMethodChanges)
        } catch (e: Exception) {
            errors.add("Obsolete method removal error: ${e.message}")
        }

        // Add codec() override for BaseEntityBlock subclasses
        try {
            val codecChanges = addBaseEntityBlockCodec(projectDir, dryRun)
            changes.addAll(codecChanges)
        } catch (e: Exception) {
            errors.add("BaseEntityBlock codec error: ${e.message}")
        }

        // Extract client-only @SubscribeEvent methods into @EventBusSubscriber(Dist.CLIENT) inner class
        try {
            val clientEventChanges = extractClientOnlyEventMethods(projectDir, dryRun)
            changes.addAll(clientEventChanges)
        } catch (e: Exception) {
            errors.add("Client event extraction error: ${e.message}")
        }

        return PassResult(name, changes, errors, skipped)
    }

    /**
     * Migrate Block.use(BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)
     * to Block.useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult) in 1.21.1.
     *
     * Changes: method name, visibility public->protected, remove InteractionHand param,
     * remove InteractionHand checks, update super calls.
     */
    /**
     * Fix EVENT_BUS.register(this) when @SubscribeEvent methods are static.
     * NeoForge 1.21.1 requires class registration for static methods.
     */
    /**
     * Clean up orphaned SimpleChannel code after field declarations are commented out.
     * Comments out chain calls (.named, .networkProtocolVersion, etc),
     * INSTANCE.messageBuilder calls, INSTANCE.send calls, and INSTANCE.xxx calls.
     */
    private fun cleanupSimpleChannelRemnants(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val text = javaFile.toFile().readText()

                // Only process files that have SimpleChannel TODO comments (indicating the field was removed)
                if (!text.contains("[forge2neo] SimpleChannel")) return@forEach

                var modified = text

                // Comment out orphaned chain calls: lines starting with .method(...)
                modified = modified.replace(
                    Regex("""^(\s+)(\.[a-zA-Z]+\([^)]*\)[^;\n]*;?)""", RegexOption.MULTILINE),
                    "$1// [forge2neo] $2 // SimpleChannel removed"
                )

                // Comment out INSTANCE.xxx calls (messageBuilder, send, etc)
                modified = modified.replace(
                    Regex("""^(\s+)(INSTANCE\.[^\n]+)""", RegexOption.MULTILINE),
                    "$1// [forge2neo] $2 // SimpleChannel removed"
                )

                // Comment out method bodies that reference INSTANCE
                // Already handled by the line-by-line approach above

                if (modified != text) {
                    if (!dryRun) {
                        javaFile.toFile().writeText(modified)
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Clean up orphaned SimpleChannel code in ${javaFile.fileName}",
                        before = "(orphaned SimpleChannel chain calls)",
                        after = "(commented out)",
                        confidence = Confidence.HIGH,
                        ruleId = "structural-simplechannel-cleanup"
                    ))
                }
            }

        return changes
    }

    private fun fixStaticEventBusRegistration(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val text = javaFile.toFile().readText()

                // Check if file has EVENT_BUS.register(this)
                if (!text.contains(".register(this)")) return@forEach

                // Detect outer class name
                val classNameMatch = Regex("""class\s+(\w+)""").find(text) ?: return@forEach
                val className = classNameMatch.groupValues[1]

                // Check if outer class (not inner classes) has @SubscribeEvent methods
                val lines = text.lines()
                var inOuterClass = false
                var braceDepth = 0
                var outerClassDepth = -1
                var hasOuterStaticSubscribeEvent = false
                var hasOuterInstanceSubscribeEvent = false

                for (i in lines.indices) {
                    val line = lines[i]
                    if (!inOuterClass && line.contains("class $className")) {
                        inOuterClass = true
                        outerClassDepth = braceDepth
                    }
                    braceDepth += line.count { it == '{' } - line.count { it == '}' }
                    if (inOuterClass && braceDepth <= outerClassDepth) {
                        break // exited outer class
                    }
                    // Only count @SubscribeEvent in the outer class (depth == outerClassDepth + 1)
                    if (inOuterClass && braceDepth == outerClassDepth + 1) {
                        if (line.trim().startsWith("@SubscribeEvent") && i + 1 < lines.size) {
                            val nextLine = lines[i + 1].trim()
                            if (nextLine.startsWith("public static")) {
                                hasOuterStaticSubscribeEvent = true
                            } else if (nextLine.startsWith("public ") || nextLine.startsWith("private ") || nextLine.startsWith("protected ")) {
                                hasOuterInstanceSubscribeEvent = true
                            }
                        }
                    }
                }

                var modified = text
                if (hasOuterStaticSubscribeEvent && !hasOuterInstanceSubscribeEvent) {
                    // All @SubscribeEvent methods are static: register the class
                    modified = text.replace(
                        Regex("""(EVENT_BUS|NeoForge\.EVENT_BUS)\.register\(this\)"""),
                        "$1.register(${className}.class)"
                    )
                    if (modified != text) {
                        changes.add(Change(
                            file = javaFile, line = 0,
                            description = "Fix static @SubscribeEvent: register(this) -> register(${className}.class)",
                            before = "EVENT_BUS.register(this)",
                            after = "EVENT_BUS.register(${className}.class)",
                            confidence = Confidence.HIGH,
                            ruleId = "structural-static-event-bus-register"
                        ))
                    }
                } else if (!hasOuterStaticSubscribeEvent && !hasOuterInstanceSubscribeEvent) {
                    // No @SubscribeEvent in outer class — remove the register(this) call
                    modified = modified.replace(
                        Regex("""[ \t]*(NeoForge\.)?EVENT_BUS\.register\(this\);\s*\r?\n"""),
                        ""
                    )
                    if (modified != text) {
                        changes.add(Change(
                            file = javaFile, line = 0,
                            description = "Remove EVENT_BUS.register(this) — no @SubscribeEvent methods in ${className}",
                            before = "EVENT_BUS.register(this)",
                            after = "// Removed: no @SubscribeEvent in outer class",
                            confidence = Confidence.HIGH,
                            ruleId = "structural-remove-event-bus-register"
                        ))
                    }
                }
                // If hasOuterInstanceSubscribeEvent, keep register(this) as-is

                if (modified != text && !dryRun) {
                    javaFile.toFile().writeText(modified)
                }
            }

        return changes
    }

    private fun migrateBlockUseMethod(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val text = javaFile.toFile().readText()

                // Match Block.use() override signature with 6 params (BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)
                val useMethodPattern = Regex(
                    """([ \t]*@(?:SuppressWarnings\([^)]*\)\s*\n\s*@)?Override\s*\n)?""" +
                    """([ \t]*)(?:public\s+)(@?\s*\w+(?:<[^>]+>)?\s+)use\s*\(\s*""" +
                    """(\w+)\s+(\w+)\s*,\s*""" +     // BlockState param
                    """(\w+)\s+(\w+)\s*,\s*""" +     // Level/World param
                    """(\w+)\s+(\w+)\s*,\s*""" +     // BlockPos param
                    """(\w+)\s+(\w+)\s*,\s*""" +     // Player param
                    """(\w+)\s+(\w+)\s*,\s*""" +     // InteractionHand param
                    """(\w+)\s+(\w+)\s*\)""",        // BlockHitResult param
                    RegexOption.MULTILINE
                )

                if (!useMethodPattern.containsMatchIn(text)) return@forEach

                var modified = text
                val match = useMethodPattern.find(modified) ?: return@forEach

                val indent = match.groupValues[2]
                val returnType = match.groupValues[3]
                val stateType = match.groupValues[4]; val stateName = match.groupValues[5]
                val levelType = match.groupValues[6]; val levelName = match.groupValues[7]
                val posType = match.groupValues[8]; val posName = match.groupValues[9]
                val playerType = match.groupValues[10]; val playerName = match.groupValues[11]
                val handName = match.groupValues[13]
                val hitType = match.groupValues[14]; val hitName = match.groupValues[15]

                // Build new method signature (without InteractionHand param)
                val newSig = "${indent}@Override\n${indent}protected ${returnType}useWithoutItem(" +
                    "$stateType $stateName, $levelType $levelName, $posType $posName, " +
                    "$playerType $playerName, $hitType $hitName)"

                modified = modified.replace(match.value, newSig)

                // Remove InteractionHand-related checks: if(hand==InteractionHand.MAIN_HAND) or similar
                // Handle "if ... && hand==InteractionHand.MAIN_HAND" by removing the hand condition
                modified = modified.replace(
                    Regex("""&&\s*$handName\s*==\s*InteractionHand\.\w+"""), ""
                )
                modified = modified.replace(
                    Regex("""$handName\s*==\s*InteractionHand\.\w+\s*&&\s*"""), ""
                )
                // Handle standalone if(hand==...) - more complex, replace with if(true) for now
                modified = modified.replace(
                    Regex("""if\s*\(\s*$handName\s*==\s*InteractionHand\.\w+\s*\)"""), "if(true)"
                )

                // Update super.use(...) calls to super.useWithoutItem(...) removing hand param
                modified = modified.replace(
                    Regex("""super\.use\(\s*$stateName\s*,\s*$levelName\s*,\s*$posName\s*,\s*$playerName\s*,\s*$handName\s*,\s*$hitName\s*\)"""),
                    "super.useWithoutItem($stateName, $levelName, $posName, $playerName, $hitName)"
                )

                // Remove unused InteractionHand import if no other references remain
                if (!modified.contains("InteractionHand") ||
                    modified.lines().none { it.trim().let { t -> !t.startsWith("import") && !t.startsWith("//") && t.contains("InteractionHand") } }) {
                    modified = modified.replace(
                        Regex("""^import\s+net\.minecraft\.world\.InteractionHand\s*;\s*\n""", RegexOption.MULTILINE),
                        ""
                    )
                }

                // Remove @SuppressWarnings("deprecation") that was before the old @Override
                // (useWithoutItem is not deprecated, so the suppression is no longer needed)

                if (modified != text) {
                    if (!dryRun) {
                        javaFile.toFile().writeText(modified)
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Migrate Block.use() to useWithoutItem() in ${javaFile.fileName}",
                        before = "public InteractionResult use(..., InteractionHand, BlockHitResult)",
                        after = "protected InteractionResult useWithoutItem(..., BlockHitResult)",
                        confidence = Confidence.HIGH,
                        ruleId = "structural-block-use-to-useWithoutItem"
                    ))
                }
            }

        return changes
    }

    /**
     * Detect ICapabilityProvider implementations and flag for migration.
     */
    private fun detectCapabilityPatterns(
        cu: CompilationUnit,
        file: Path,
        changes: MutableList<Change>
    ) {
        cu.findAll(ClassOrInterfaceDeclaration::class.java).forEach { classDecl ->
            // Check if implements ICapabilityProvider
            val hasCapProvider = classDecl.implementedTypes.any {
                it.nameAsString == "ICapabilityProvider"
            }

            if (hasCapProvider) {
                val line = classDecl.begin.map { it.line }.orElse(0)
                changes.add(Change(
                    file = file, line = line,
                    description = "CLASS IMPLEMENTS ICapabilityProvider - needs migration to RegisterCapabilitiesEvent pattern",
                    before = "class ${classDecl.nameAsString} implements ICapabilityProvider",
                    after = "// TODO: Remove ICapabilityProvider, register via RegisterCapabilitiesEvent",
                    confidence = Confidence.MEDIUM,
                    ruleId = "struct-capability-provider"
                ))
            }

            // Detect getCapability method overrides
            classDecl.methods.filter { it.nameAsString == "getCapability" }.forEach { method ->
                val methodLine = method.begin.map { it.line }.orElse(0)
                changes.add(Change(
                    file = file, line = methodLine,
                    description = "getCapability() override detected - migrate to RegisterCapabilitiesEvent provider lambda",
                    before = method.declarationAsString,
                    after = "// Register via: event.registerBlockEntity(Capabilities.XXX, MY_TYPE, (be, side) -> ...)",
                    confidence = Confidence.MEDIUM,
                    ruleId = "struct-capability-getcap"
                ))
            }
        }
    }

    /**
     * Detect SimpleChannel / networking patterns and flag for migration.
     */
    private fun detectNetworkingPatterns(
        cu: CompilationUnit,
        file: Path,
        changes: MutableList<Change>
    ) {
        // Detect SimpleChannel field declarations
        cu.findAll(FieldDeclaration::class.java).forEach { field ->
            val typeStr = field.elementType.toString()
            if (typeStr.contains("SimpleChannel") || typeStr.contains("EventNetworkChannel")) {
                val line = field.begin.map { it.line }.orElse(0)
                changes.add(Change(
                    file = file, line = line,
                    description = "SimpleChannel/EventNetworkChannel detected - migrate to CustomPacketPayload system",
                    before = field.toString().trim(),
                    after = "// TODO: Replace with record implementing CustomPacketPayload + RegisterPayloadHandlersEvent",
                    confidence = Confidence.MEDIUM,
                    ruleId = "struct-networking-channel"
                ))
            }
        }

        // Detect NetworkRegistry.newSimpleChannel calls
        cu.findAll(MethodCallExpr::class.java).forEach { call ->
            if (call.nameAsString == "newSimpleChannel" || call.nameAsString == "registerMessage") {
                val line = call.begin.map { it.line }.orElse(0)
                changes.add(Change(
                    file = file, line = line,
                    description = "Network registration call detected - migrate to RegisterPayloadHandlersEvent",
                    before = call.toString(),
                    after = "// Register via: event.registrar(MODID).playToServer(TYPE, CODEC, HANDLER)",
                    confidence = Confidence.MEDIUM,
                    ruleId = "struct-networking-register"
                ))
            }
        }
    }

    /**
     * Detect DistExecutor usage (removed in NeoForge 1.21.1).
     * DistExecutor.unsafeRunWhenOn/safeRunWhenOn should be replaced with direct if (FMLLoader.getDist() == Dist.CLIENT) checks.
     */
    private fun detectDistExecutorUsage(
        cu: CompilationUnit,
        file: Path,
        changes: MutableList<Change>
    ) {
        cu.findAll(MethodCallExpr::class.java).forEach { call ->
            val scope = call.scope.map { it.toString() }.orElse("")
            if (scope == "DistExecutor" || scope.endsWith(".DistExecutor")) {
                val line = call.begin.map { it.line }.orElse(0)
                changes.add(Change(
                    file = file, line = line,
                    description = "DistExecutor.${call.nameAsString}() REMOVED - replace with if (FMLLoader.getDist() == Dist.CLIENT) pattern",
                    before = call.toString(),
                    after = "// Replace with: if (FMLLoader.getDist() == Dist.CLIENT) { ... }",
                    confidence = Confidence.MEDIUM,
                    ruleId = "struct-dist-executor"
                ))
            }
        }
    }

    /**
     * Data class for packet class detection.
     */
    private data class PacketClassInfo(
        val file: Path,
        val className: String,
        val packageName: String,
        val encodeParamName: String,
        val encodeBufferName: String,
        val isPlayToClient: Boolean // heuristic: handler checks isClient
    )

    /**
     * Transform packet classes to implement CustomPacketPayload and generate ModNetwork.java.
     * Detects classes with static encode(ClassName, FriendlyByteBuf) + decode(FriendlyByteBuf) patterns.
     */
    private fun transformPacketClasses(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        // Detect mod ID
        val modId = detectModId(projectDir) ?: return changes

        // Detect mod main class (for registering ModNetwork)
        val modMainClass = detectModMainClass(projectDir)

        // Find packet classes
        val packetClasses = mutableListOf<PacketClassInfo>()
        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { file ->
                val content = file.readText()
                val encodeMatch = Regex(
                    """public\s+static\s+void\s+encode\s*\(\s*(\w+)\s+(\w+)\s*,\s*FriendlyByteBuf\s+(\w+)\s*\)"""
                ).find(content)
                val decodeMatch = Regex(
                    """public\s+static\s+(\w+)\s+decode\s*\(\s*FriendlyByteBuf\s+\w+\s*\)"""
                ).find(content)

                if (encodeMatch != null && decodeMatch != null) {
                    val className = encodeMatch.groupValues[1]
                    if (className == decodeMatch.groupValues[1]) {
                        val pkg = Regex("""package\s+([\w.]+)\s*;""").find(content)?.groupValues?.get(1) ?: ""
                        // Detect direction: S2C (playToClient) vs C2S (playToServer)
                        val isPlayToClient = className.startsWith("S2C") ||
                            className.contains("ToClient") ||
                            content.contains("PLAY_TO_CLIENT") ||
                            content.contains("ClientPacketHandler") ||
                            content.contains("DistExecutor")
                        packetClasses.add(PacketClassInfo(
                            file, className, pkg,
                            encodeMatch.groupValues[2],
                            encodeMatch.groupValues[3],
                            isPlayToClient
                        ))
                    }
                }
            }

        if (packetClasses.isEmpty()) return changes

        // Transform each packet class
        for (info in packetClasses) {
            if (dryRun) {
                changes.add(Change(
                    file = info.file, line = 1,
                    description = "Will transform ${info.className} to implement CustomPacketPayload",
                    before = "class ${info.className}",
                    after = "class ${info.className} implements CustomPacketPayload",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-packet-payload"
                ))
                continue
            }

            var content = info.file.readText()

            // Skip if already implements CustomPacketPayload
            if (content.contains("implements CustomPacketPayload")) continue

            // 1. Add imports
            val importBlock = buildString {
                if (!content.contains("import net.minecraft.network.codec.StreamCodec;"))
                    append("import net.minecraft.network.codec.StreamCodec;\n")
                if (!content.contains("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;"))
                    append("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;\n")
                if (!content.contains("import net.minecraft.resources.ResourceLocation;"))
                    append("import net.minecraft.resources.ResourceLocation;\n")
            }
            if (importBlock.isNotEmpty()) {
                // Insert after last existing import
                val lastImport = Regex("""^import\s+[^;]+;""", RegexOption.MULTILINE).findAll(content).lastOrNull()
                if (lastImport != null) {
                    val insertPos = lastImport.range.last + 1
                    content = content.substring(0, insertPos) + "\n" + importBlock + content.substring(insertPos)
                }
            }

            // 2. Add implements CustomPacketPayload
            content = content.replace(
                Regex("""(public\s+class\s+${info.className})\s*\{"""),
                "$1 implements CustomPacketPayload\n{"
            )

            // 3. Generate snake_case name for resource location
            val snakeName = info.className.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

            // 4. Add TYPE, STREAM_CODEC, type() after class opening brace
            val classBodyInsert = """
    public static final CustomPacketPayload.Type<${info.className}> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("$modId", "$snakeName"));

    public static final StreamCodec<FriendlyByteBuf, ${info.className}> STREAM_CODEC =
            StreamCodec.of(${info.className}::encode, ${info.className}::decode);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
"""
            val classOpenBrace = content.indexOf('{', content.indexOf("class ${info.className}"))
            if (classOpenBrace > 0) {
                content = content.substring(0, classOpenBrace + 1) + classBodyInsert + content.substring(classOpenBrace + 1)
            }

            // 5. Swap encode parameter order: encode(ClassName msg, FriendlyByteBuf buf) -> encode(FriendlyByteBuf buf, ClassName msg)
            content = content.replace(
                Regex("""encode\s*\(\s*${info.className}\s+(\w+)\s*,\s*FriendlyByteBuf\s+(\w+)\s*\)"""),
                "encode(FriendlyByteBuf $2, ${info.className} $1)"
            )

            info.file.writeText(content)
            changes.add(Change(
                file = info.file, line = 1,
                description = "Transformed ${info.className} to implement CustomPacketPayload with TYPE + STREAM_CODEC",
                before = "class ${info.className}",
                after = "class ${info.className} implements CustomPacketPayload",
                confidence = Confidence.HIGH,
                ruleId = "struct-packet-payload"
            ))
        }

        if (dryRun) return changes

        // 6. Generate ModNetwork.java
        val networkPkg = packetClasses.first().packageName
        val networkDir = srcDir.resolve(networkPkg.replace('.', '/'))
        val networkFile = networkDir.resolve("ModNetwork.java")
        if (!networkFile.exists()) {
            val registrations = packetClasses.joinToString("\n\n") { info ->
                val direction = if (info.isPlayToClient) "playToClient" else "playToServer"
                """        registrar.$direction(
                ${info.className}.TYPE,
                ${info.className}.STREAM_CODEC,
                ${info.className}::handle
        );"""
            }

            val networkContent = """package $networkPkg;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModNetwork {

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("$modId").versioned("1");

$registrations
    }
}
"""
            networkFile.writeText(networkContent)
            changes.add(Change(
                file = networkFile, line = 1,
                description = "Generated ModNetwork.java for packet registration via RegisterPayloadHandlersEvent",
                before = "(new file)",
                after = "ModNetwork with ${packetClasses.size} packet registrations",
                confidence = Confidence.HIGH,
                ruleId = "struct-generate-mod-network"
            ))

            // 7. Register ModNetwork in the mod main class
            if (modMainClass != null) {
                var mainContent = modMainClass.readText()
                if (!mainContent.contains("ModNetwork")) {
                    // Add import
                    val importLine = "import ${networkPkg}.ModNetwork;"
                    if (!mainContent.contains(importLine)) {
                        val lastImportMatch = Regex("""^import\s+[^;]+;""", RegexOption.MULTILINE)
                            .findAll(mainContent).lastOrNull()
                        if (lastImportMatch != null) {
                            val pos = lastImportMatch.range.last + 1
                            mainContent = mainContent.substring(0, pos) + "\n" + importLine + mainContent.substring(pos)
                        }
                    }

                    // Add event bus listener in constructor
                    val busRegex = Regex("""(NeoForge\.EVENT_BUS\.register\(this\);?)""")
                    val busMatch = busRegex.find(mainContent)
                    if (busMatch != null) {
                        val modBusLine = "\n        // Register network packets\n        modEventBus.addListener(ModNetwork::register);"
                        // Find modEventBus variable - look for IEventBus parameter
                        val hasModBus = mainContent.contains("modEventBus") || mainContent.contains("IEventBus")
                        if (hasModBus) {
                            mainContent = mainContent.replace(busMatch.value, busMatch.value + modBusLine)
                        }
                    }
                    // Alternative: if constructor takes ModContainer, look for that
                    if (!mainContent.contains("ModNetwork::register")) {
                        val containerMatch = Regex("""(modContainer\.registerConfig)""").find(mainContent)
                        if (containerMatch != null) {
                            val pos = mainContent.lastIndexOf('\n', containerMatch.range.first)
                            val regLine = "\n        // Register network packets via mod event bus\n        net.neoforged.fml.ModLoadingContext.get().getActiveContainer().getEventBus().addListener(ModNetwork::register);\n"
                            mainContent = mainContent.substring(0, pos) + regLine + mainContent.substring(pos)
                        }
                    }
                    modMainClass.writeText(mainContent)
                    changes.add(Change(
                        file = modMainClass, line = 1,
                        description = "Registered ModNetwork packet handler in main mod class",
                        before = "(no packet registration)",
                        after = "modEventBus.addListener(ModNetwork::register)",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-register-mod-network"
                    ))
                }
            }
        }

        // 8. Replace old SimpleChannel registration class (ModPackets, PacketHandler, etc.)
        // Find files that contain SimpleChannel or INSTANCE.messageBuilder and rewrite them
        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .filter { it.fileName.toString() != "ModNetwork.java" }
            .forEach { file ->
                val content = file.readText()
                // Detect old SimpleChannel registration classes
                val hasSimpleChannel = content.contains("SimpleChannel") ||
                    content.contains("NetworkRegistry.ChannelBuilder") ||
                    content.contains("NetworkRegistry.newSimpleChannel") ||
                    (content.contains("INSTANCE") && content.contains("messageBuilder"))
                if (!hasSimpleChannel) return@forEach

                val pkg = Regex("""package\s+([\w.]+)\s*;""").find(content)?.groupValues?.get(1) ?: return@forEach
                val className = file.fileName.toString().removeSuffix(".java")

                // Check if this class has send helper methods (sendToServer, sendToPlayer, etc.)
                val hasSendHelpers = content.contains("sendToServer") || content.contains("sendToPlayer") ||
                    content.contains("sendToAllClients") || content.contains("sendToTracking")

                val newContent = buildString {
                    appendLine("package $pkg;")
                    appendLine()
                    appendLine("import net.minecraft.server.level.ServerPlayer;")
                    appendLine("import net.neoforged.neoforge.network.PacketDistributor;")
                    appendLine("import net.minecraft.network.protocol.common.custom.CustomPacketPayload;")
                    appendLine()
                    appendLine("/**")
                    appendLine(" * [forge2neo] Replaced SimpleChannel registration class.")
                    appendLine(" * Packet registration is now handled by ModNetwork via RegisterPayloadHandlersEvent.")
                    appendLine(" * Helper methods preserved for compatibility.")
                    appendLine(" */")
                    appendLine("public class $className {")
                    appendLine()
                    appendLine("    // Registration is now handled by ModNetwork.register()")
                    appendLine("    public static void registerPackets() {")
                    appendLine("        // No-op: packets are registered via RegisterPayloadHandlersEvent in ModNetwork")
                    appendLine("    }")
                    appendLine()
                    appendLine("    public static void register() {")
                    appendLine("        registerPackets();")
                    appendLine("    }")
                    appendLine()
                    if (hasSendHelpers) {
                        appendLine("    public static <T extends CustomPacketPayload> void sendToServer(T msg) {")
                        appendLine("        PacketDistributor.sendToServer(msg);")
                        appendLine("    }")
                        appendLine()
                        appendLine("    public static <T extends CustomPacketPayload> void sendToPlayer(ServerPlayer player, T msg) {")
                        appendLine("        PacketDistributor.sendToPlayer(player, msg);")
                        appendLine("    }")
                        appendLine()
                        appendLine("    public static <T extends CustomPacketPayload> void sendToAllClients(T msg) {")
                        appendLine("        PacketDistributor.sendToAllPlayers(msg);")
                        appendLine("    }")
                        appendLine()
                    }
                    appendLine("}")
                }

                if (!dryRun) {
                    file.writeText(newContent)
                }
                changes.add(Change(
                    file = file, line = 1,
                    description = "Replaced old SimpleChannel registration class $className with NeoForge-compatible wrapper",
                    before = "(SimpleChannel registration)",
                    after = "(PacketDistributor wrapper + ModNetwork delegation)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-replace-modpackets"
                ))
            }

        return changes
    }

    /**
     * Detect mod ID from @Mod annotation.
     */
    private fun detectModId(projectDir: Path): String? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null
        try {
            val javaFiles = Files.walk(srcDir).filter { it.toString().endsWith(".java") }.toList()
            for (file in javaFiles) {
                val text = file.readText()
                val directMatch = Regex("""@Mod\s*\(\s*"(\w+)"\s*\)""").find(text)
                if (directMatch != null) return directMatch.groupValues[1]
                val constRef = Regex("""@Mod\s*\(\s*(\w+)\.(\w+)\s*\)""").find(text)
                if (constRef != null) {
                    val constName = constRef.groupValues[2]
                    val constVal = Regex("${constName}\\s*=\\s*\"(\\w+)\"").find(text)
                    if (constVal != null) return constVal.groupValues[1]
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Find the main @Mod-annotated class file.
     */
    private fun detectModMainClass(projectDir: Path): Path? {
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return null
        try {
            val javaFiles = Files.walk(srcDir).filter { it.toString().endsWith(".java") }.toList()
            for (file in javaFiles) {
                val text = file.readText()
                if (Regex("""@Mod\s*\(""").containsMatchIn(text)) return file
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Detect LazyOptional usage and flag for null-based replacement.
     */
    private fun detectLazyOptionalUsage(
        cu: CompilationUnit,
        file: Path,
        changes: MutableList<Change>
    ) {
        // Detect LazyOptional type usage
        cu.findAll(ClassOrInterfaceType::class.java).forEach { type ->
            if (type.nameAsString == "LazyOptional") {
                val line = type.begin.map { it.line }.orElse(0)
                changes.add(Change(
                    file = file, line = line,
                    description = "LazyOptional<T> -> @Nullable T (capabilities no longer use LazyOptional)",
                    before = type.toString(),
                    after = "@Nullable ${type.typeArguments.map { it.first().toString() }.orElse("T")}",
                    confidence = Confidence.MEDIUM,
                    ruleId = "struct-lazy-optional"
                ))
            }
        }

        // Detect .ifPresent() / .orElse() on LazyOptional
        cu.findAll(MethodCallExpr::class.java).forEach { call ->
            if (call.nameAsString == "ifPresent" || call.nameAsString == "orElse" ||
                call.nameAsString == "orElseThrow" || call.nameAsString == "resolve") {
                // Check if scope contains capability-related expressions
                val scopeStr = call.scope.map { it.toString() }.orElse("")
                if (scopeStr.contains("getCapability") || scopeStr.contains("LazyOptional") ||
                    scopeStr.contains("cap") || scopeStr.contains("handler")) {
                    val line = call.begin.map { it.line }.orElse(0)
                    changes.add(Change(
                        file = file, line = line,
                        description = "LazyOptional.${call.nameAsString}() -> null check pattern",
                        before = call.toString(),
                        after = "// Replace with: var result = ...; if (result != null) { ... }",
                        confidence = Confidence.LOW,
                        ruleId = "struct-lazy-optional-method"
                    ))
                }
            }
        }
    }

    /**
     * Remove NeoForge.EVENT_BUS.register(this) from @Mod classes that have no @SubscribeEvent methods.
     * In NeoForge 1.21.1, registering a class without @SubscribeEvent methods throws an IllegalArgumentException.
     */
    // Known mod-bus event types (fired on mod event bus, NOT game bus)
    private val modBusEventTypes = setOf(
        "EntityAttributeCreationEvent",
        "BuildCreativeModeTabContentsEvent",
        "EntityRenderersEvent",
        "RegisterEvent",
        "FMLCommonSetupEvent",
        "FMLClientSetupEvent",
        "FMLDedicatedServerSetupEvent",
        "RegisterColorHandlersEvent",
        "RegisterParticleProvidersEvent",
        "ModelEvent",
        "TextureAtlasStitchedEvent",
        "RegisterShadersEvent",
        "RegisterKeyMappingsEvent",
        "FMLLoadCompleteEvent",
        "InterModEnqueueEvent",
        "InterModProcessEvent"
    )

    private fun removeEmptyEventBusRegistration(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                val text = javaFile.toFile().readText()
                // Only process @Mod-annotated classes
                if (!text.contains("@Mod(") && !text.contains("@Mod\n")) return@forEach
                // Only process files with EVENT_BUS.register(this)
                if (!text.contains("EVENT_BUS.register(this)")) return@forEach

                // Check if any @SubscribeEvent methods handle game-bus events
                // If ALL @SubscribeEvent methods handle mod-bus events, remove EVENT_BUS.register(this)
                val hasGameBusEvents = if (!text.contains("@SubscribeEvent")) {
                    false
                } else {
                    // Find @SubscribeEvent method parameter types
                    val subscribePattern = Regex("""@SubscribeEvent\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:public\s+)?(?:static\s+)?(?:void\s+)\w+\s*\((\w+(?:\.\w+)*)""")
                    val eventTypes = subscribePattern.findAll(text).map { it.groupValues[1] }.toList()
                    eventTypes.any { eventType ->
                        // Check both the full type (e.g. EntityRenderersEvent.RegisterRenderers)
                        // and simple name against known mod-bus events
                        !modBusEventTypes.any { modEvent ->
                            eventType.contains(modEvent)
                        }
                    }
                }

                if (hasGameBusEvents) return@forEach

                // Remove the EVENT_BUS.register(this) line
                val modified = text.replace(
                    Regex("""^\s*(?:NeoForge|MinecraftForge)\.EVENT_BUS\.register\(this\);\s*\r?\n?""", RegexOption.MULTILINE),
                    ""
                )

                if (modified != text) {
                    if (!dryRun) {
                        javaFile.toFile().writeText(modified)
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Remove EVENT_BUS.register(this) — all @SubscribeEvent methods are mod-bus events",
                        before = "NeoForge.EVENT_BUS.register(this);",
                        after = "(removed)",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-remove-empty-eventbus-register"
                    ))
                }
            }

        return changes
    }

    /**
     * Migrate FMLJavaModLoadingContext constructor parameter to IEventBus + ModContainer.
     * In NeoForge 1.21.1, @Mod constructors receive IEventBus and ModContainer directly
     * instead of FMLJavaModLoadingContext.
     */
    private fun migrateFMLJavaModLoadingContext(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                var text = javaFile.toFile().readText()
                if (!text.contains("@Mod(") && !text.contains("@Mod\n")) return@forEach
                if (!text.contains("FMLJavaModLoadingContext")) return@forEach

                val original = text

                // Replace constructor parameter: FMLJavaModLoadingContext <name> -> IEventBus modEventBus
                val ctorParamPattern = Regex("""FMLJavaModLoadingContext\s+(\w+)""")
                val ctorMatch = ctorParamPattern.find(text)
                if (ctorMatch != null) {
                    val contextVarName = ctorMatch.groupValues[1]

                    // Replace the parameter type
                    text = text.replace(ctorMatch.value, "IEventBus modEventBus")

                    // Replace context.getModEventBus() -> modEventBus
                    text = text.replace("${contextVarName}.getModEventBus()", "modEventBus")

                    // Replace context.registerConfig -> modContainer.registerConfig
                    text = text.replace("${contextVarName}.registerConfig(", "modContainer.registerConfig(")

                    // Replace IEventBus <varName> = context.getModEventBus() assignment with modEventBus
                    val busAssignPattern = Regex("""IEventBus\s+(\w+)\s*=\s*modEventBus;\s*\r?\n""")
                    val busAssignMatch = busAssignPattern.find(text)
                    if (busAssignMatch != null) {
                        val busVarName = busAssignMatch.groupValues[1]
                        // Remove the redundant assignment and replace usages
                        text = text.replace(busAssignMatch.value, "")
                        text = text.replace(busVarName, "modEventBus")
                    }

                    // Remove FMLJavaModLoadingContext import
                    text = text.replace(Regex("""import\s+net\.neoforged\.fml\.javafmlmod\.FMLJavaModLoadingContext;\s*\r?\n"""), "")

                    // Remove ModLoadingContext import if present and unused
                    if (!text.contains("ModLoadingContext.")) {
                        text = text.replace(Regex("""import\s+net\.neoforged\.fml\.ModLoadingContext;\s*\r?\n"""), "")
                    }

                    // Ensure ModContainer import exists
                    if (!text.contains("import net.neoforged.fml.ModContainer;")) {
                        text = text.replace(
                            "import net.neoforged.fml.common.Mod;",
                            "import net.neoforged.fml.ModContainer;\nimport net.neoforged.fml.common.Mod;"
                        )
                    }
                }

                if (text != original) {
                    if (!dryRun) {
                        javaFile.toFile().writeText(text)
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = "Migrate FMLJavaModLoadingContext to IEventBus + ModContainer constructor",
                        before = "FMLJavaModLoadingContext context",
                        after = "IEventBus modEventBus, ModContainer modContainer",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-fml-context-to-eventbus"
                    ))
                }
            }

        return changes
    }

    /**
     * Clean up @EventBusSubscriber annotations:
     * 1. Remove @EventBusSubscriber from classes without any @SubscribeEvent methods
     * 2. Remove deprecated bus= parameter from @EventBusSubscriber
     */
    private fun cleanupEventBusSubscriber(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        Files.walk(srcDir)
            .filter { it.toString().endsWith(".java") }
            .forEach { javaFile ->
                var text = javaFile.toFile().readText()
                if (!text.contains("@EventBusSubscriber")) return@forEach

                val original = text
                // Check for uncommented @SubscribeEvent (not in // comments)
                val hasSubscribeEvent = text.lines().any { line ->
                    val trimmed = line.trim()
                    trimmed.contains("@SubscribeEvent") && !trimmed.startsWith("//")
                }

                if (!hasSubscribeEvent) {
                    // Remove @EventBusSubscriber annotation entirely (including parameters)
                    text = text.replace(Regex("""@EventBusSubscriber\([^)]*\)\s*\r?\n"""), "")
                    text = text.replace(Regex("""@EventBusSubscriber\s*\r?\n"""), "")
                    // Remove the import
                    text = text.replace(Regex("""import\s+net\.neoforged\.fml\.common\.EventBusSubscriber;\s*\r?\n"""), "")
                } else {
                    // Remove deprecated bus= parameter but keep annotation
                    // Handle: bus = EventBusSubscriber.Bus.MOD, (leading)
                    text = text.replace(Regex("""bus\s*=\s*EventBusSubscriber\.Bus\.MOD,\s*"""), "")
                    // Handle: , bus = EventBusSubscriber.Bus.MOD (trailing)
                    text = text.replace(Regex(""",\s*bus\s*=\s*EventBusSubscriber\.Bus\.MOD"""), "")
                }

                if (text != original) {
                    if (!dryRun) {
                        javaFile.toFile().writeText(text)
                    }
                    changes.add(Change(
                        file = javaFile,
                        line = 0,
                        description = if (!hasSubscribeEvent)
                            "Remove @EventBusSubscriber from class without @SubscribeEvent methods"
                        else
                            "Remove deprecated bus= parameter from @EventBusSubscriber",
                        before = "@EventBusSubscriber(..., bus = Bus.MOD)",
                        after = if (!hasSubscribeEvent) "(removed)" else "@EventBusSubscriber(...)",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-cleanup-eventbus-subscriber"
                    ))
                }
            }

        return changes
    }

    /**
     * Migrate ItemEntityPickupEvent.Pre API changes in NeoForge 1.21.1:
     * - event.setCanceled(true) -> event.setCanPickup(TriState.FALSE)
     * - event.getEntity() -> event.getPlayer() (within pickup event methods only)
     */
    private fun migrateItemPickupEvent(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()

        val javaFiles = Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/")
            }}
            .toList()

        for (file in javaFiles) {
            val content = file.readText()
            if (!content.contains("ItemEntityPickupEvent")) continue

            val lines = content.lines().toMutableList()
            var modified = false
            var inPickupMethod = false
            var braceDepth = 0
            var methodBraceStart = 0

            for (i in lines.indices) {
                val line = lines[i]
                val trimmed = line.trim()

                // Detect method with ItemEntityPickupEvent.Pre parameter
                if (trimmed.contains("ItemEntityPickupEvent.Pre") && trimmed.contains("(") &&
                    (trimmed.contains("void ") || trimmed.contains("public ") || trimmed.contains("private ") || trimmed.contains("protected "))) {
                    inPickupMethod = true
                    braceDepth = 0
                    // Count braces on this line
                    for (ch in line) {
                        if (ch == '{') braceDepth++
                        if (ch == '}') braceDepth--
                    }
                    methodBraceStart = braceDepth
                    continue
                }

                if (inPickupMethod) {
                    for (ch in line) {
                        if (ch == '{') braceDepth++
                        if (ch == '}') braceDepth--
                    }

                    // Replace event.setCanceled(true) -> event.setCanPickup(TriState.FALSE)
                    if (trimmed.contains(".setCanceled(true)")) {
                        val oldLine = lines[i]
                        lines[i] = line.replace(".setCanceled(true)", ".setCanPickup(TriState.FALSE)")
                        modified = true
                        changes.add(Change(
                            file = file,
                            line = i + 1,
                            description = "ItemEntityPickupEvent: setCanceled(true) -> setCanPickup(TriState.FALSE)",
                            before = oldLine.trim(),
                            after = lines[i].trim(),
                            confidence = Confidence.HIGH,
                            ruleId = "struct-pickup-event-setcanpickup"
                        ))
                    }

                    // Replace event.getEntity() -> event.getPlayer() within pickup event method
                    if (trimmed.contains(".getEntity()")) {
                        val oldLine = lines[i]
                        lines[i] = line.replace(".getEntity()", ".getPlayer()")
                        modified = true
                        changes.add(Change(
                            file = file,
                            line = i + 1,
                            description = "ItemEntityPickupEvent: getEntity() -> getPlayer()",
                            before = oldLine.trim(),
                            after = lines[i].trim(),
                            confidence = Confidence.HIGH,
                            ruleId = "struct-pickup-event-getplayer"
                        ))
                    }

                    // End of method
                    if (braceDepth <= 0) {
                        inPickupMethod = false
                    }
                }
            }

            // Add TriState import if setCanceled was replaced and import not present
            if (modified) {
                val hasTriStateImport = lines.any { it.contains("import net.neoforged.neoforge.common.util.TriState") }
                if (!hasTriStateImport) {
                    // Find last import line to insert after
                    val lastImportIdx = lines.indexOfLast { it.trim().startsWith("import ") }
                    if (lastImportIdx >= 0) {
                        lines.add(lastImportIdx + 1, "import net.neoforged.neoforge.common.util.TriState;")
                        changes.add(Change(
                            file = file,
                            line = lastImportIdx + 2,
                            description = "Add TriState import for ItemEntityPickupEvent migration",
                            before = "",
                            after = "import net.neoforged.neoforge.common.util.TriState;",
                            confidence = Confidence.HIGH,
                            ruleId = "struct-pickup-event-tristate-import"
                        ))
                    }
                }
            }

            if (modified && !dryRun) {
                val separator = if (content.contains("\r\n")) "\r\n" else "\n"
                file.writeText(lines.joinToString(separator))
            }
        }

        return changes
    }

    /**
     * Remove methods that were removed in 1.21:
     * - onAddedToWorld() / onRemovedFromWorld() (Entity lifecycle methods removed)
     */
    private fun removeObsoleteMethods(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val obsoleteMethods = listOf("onAddedToWorld", "onRemovedFromWorld")

        val javaFiles = Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/")
            }}
            .toList()

        for (file in javaFiles) {
            val content = file.readText()
            if (obsoleteMethods.none { content.contains(it) }) continue

            val lines = content.lines().toMutableList()
            var modified = false
            var i = 0

            while (i < lines.size) {
                val trimmed = lines[i].trim()

                for (methodName in obsoleteMethods) {
                    // Match @Override followed by method declaration
                    val isOverride = trimmed == "@Override" && i + 1 < lines.size &&
                        lines[i + 1].trim().let { next ->
                            next.contains("void $methodName(") || next.contains("void $methodName (")
                        }
                    val isDirectMethod = trimmed.contains("void $methodName(") || trimmed.contains("void $methodName (")

                    if (isOverride || isDirectMethod) {
                        val methodStart = i
                        // Find the opening brace
                        var j = if (isOverride) i + 1 else i
                        while (j < lines.size && !lines[j].contains("{")) j++
                        if (j >= lines.size) break

                        // Find matching closing brace
                        var depth = 0
                        var k = j
                        while (k < lines.size) {
                            for (ch in lines[k]) {
                                if (ch == '{') depth++
                                if (ch == '}') depth--
                            }
                            if (depth <= 0) break
                            k++
                        }

                        if (k < lines.size) {
                            // Comment out the entire method
                            for (idx in methodStart..k) {
                                lines[idx] = "// [forge2neo] ${lines[idx].trimStart()}"
                            }
                            lines.add(methodStart, "    // [forge2neo] Removed $methodName() - removed in NeoForge 1.21")
                            modified = true
                            changes.add(Change(
                                file = file,
                                line = methodStart + 1,
                                description = "Remove obsolete $methodName() override (removed in 1.21)",
                                before = "@Override void $methodName()",
                                after = "// Removed",
                                confidence = Confidence.HIGH,
                                ruleId = "struct-remove-obsolete-method"
                            ))
                            i = k + 2 // skip past the commented method + comment line
                            break
                        }
                    }
                }
                i++
            }

            if (modified && !dryRun) {
                val separator = if (content.contains("\r\n")) "\r\n" else "\n"
                file.writeText(lines.joinToString(separator))
            }
        }

        return changes
    }

    /**
     * Add codec() override for classes extending BaseEntityBlock.
     * In 1.21, BaseEntityBlock requires implementing codec() returning MapCodec.
     */
    private fun addBaseEntityBlockCodec(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()

        val javaFiles = Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/")
            }}
            .toList()

        for (file in javaFiles) {
            val content = file.readText()
            if (!content.contains("extends BaseEntityBlock")) continue
            if (content.contains("MapCodec<")) continue // Already has codec

            // Extract class name
            val classMatch = Regex("""class\s+(\w+)\s+extends\s+BaseEntityBlock""").find(content) ?: continue
            val className = classMatch.groupValues[1]

            // Find the opening brace of the class
            val classStart = content.indexOf('{', classMatch.range.first)
            if (classStart < 0) continue

            val codecMethod = """
    // [forge2neo] Added codec() - required by BaseEntityBlock in 1.21
    public static final com.mojang.serialization.MapCodec<$className> CODEC = simpleCodec($className::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
"""
            val newContent = content.substring(0, classStart + 1) + codecMethod + content.substring(classStart + 1)

            changes.add(Change(
                file = file,
                line = content.substring(0, classStart).count { it == '\n' } + 1,
                description = "Add codec() override for BaseEntityBlock ($className)",
                before = "class $className extends BaseEntityBlock",
                after = "class $className + CODEC field + codec() override",
                confidence = Confidence.MEDIUM,
                ruleId = "struct-base-entity-block-codec"
            ))

            if (!dryRun) {
                file.writeText(newContent)
            }
        }

        return changes
    }

    /**
     * Extract mod-bus @SubscribeEvent methods into static inner classes.
     * In NeoForge 1.21.1:
     * - @EventBusSubscriber (no bus specified) = game bus, requires static methods
     * - Instance registration via modEventBus.register(this) conflicts with @EventBusSubscriber
     * - Client-only events can't be registered on the server
     *
     * This method:
     * 1. Finds all @SubscribeEvent methods that handle mod-bus events
     * 2. Separates them into client-only vs common mod-bus events
     * 3. Creates inner classes with @EventBusSubscriber(bus=MOD) / @EventBusSubscriber(bus=MOD, value=Dist.CLIENT)
     * 4. Removes modEventBus.register(this) since inner classes auto-register
     */
    private fun extractClientOnlyEventMethods(projectDir: Path, dryRun: Boolean): List<Change> {
        val changes = mutableListOf<Change>()
        val clientOnlyEvents = setOf(
            "EntityRenderersEvent",
            "RegisterColorHandlersEvent",
            "RegisterParticleProvidersEvent",
            "ModelEvent",
            "TextureAtlasStitchedEvent",
            "RegisterShadersEvent",
            "RegisterKeyMappingsEvent"
        )

        val srcDir = projectDir.resolve("src/main/java")
        if (!srcDir.exists()) return changes

        Files.walk(srcDir)
            .filter { it.extension == "java" }
            .toList()
            .forEach { file ->
                val content = file.readText()
                if (!content.contains("@Mod(") && !content.contains("@Mod\"")) return@forEach
                if (!content.contains("@SubscribeEvent")) return@forEach

                val lines = content.lines().toMutableList()
                val separator = if (content.contains("\r\n")) "\r\n" else "\n"

                data class ExtractedMethod(val startLine: Int, val endLine: Int, val text: String, val isClientOnly: Boolean)
                val modBusMethods = mutableListOf<ExtractedMethod>()

                var i = 0
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith("@SubscribeEvent")) {
                        val annotLine = i
                        var methodLine = i + 1
                        while (methodLine < lines.size && lines[methodLine].trimStart().startsWith("@")) {
                            methodLine++
                        }
                        if (methodLine >= lines.size) { i++; continue }

                        val methodDecl = lines[methodLine]

                        // Determine if this is a mod-bus event
                        val isModBusEvent = modBusEventTypes.any { eventName ->
                            methodDecl.contains("$eventName.") || methodDecl.contains("$eventName ")
                        }

                        if (isModBusEvent) {
                            val isClientOnly = clientOnlyEvents.any { eventName ->
                                methodDecl.contains("$eventName.") || methodDecl.contains("$eventName ")
                            }

                            val openBraceIdx = (methodLine until lines.size).firstOrNull { lines[it].contains("{") }
                            if (openBraceIdx != null) {
                                var depth = 0
                                var methodEnd: Int = openBraceIdx
                                for (j in openBraceIdx until lines.size) {
                                    depth += lines[j].count { c -> c == '{' } - lines[j].count { c -> c == '}' }
                                    if (depth <= 0) {
                                        methodEnd = j
                                        break
                                    }
                                }
                                val methodText = lines.subList(annotLine, methodEnd + 1).joinToString(separator)
                                modBusMethods.add(ExtractedMethod(annotLine, methodEnd, methodText, isClientOnly))
                            }
                        }
                    }
                    i++
                }

                if (modBusMethods.isEmpty()) return@forEach

                val modidMatch = Regex("""public\s+static\s+final\s+String\s+(\w+)\s*=\s*"(\w+)"""").find(content)
                val modidRef = modidMatch?.groupValues?.get(1) ?: "\"${modidMatch?.groupValues?.get(2) ?: "modid"}\""

                val clientMethods = modBusMethods.filter { it.isClientOnly }
                val commonMethods = modBusMethods.filter { !it.isClientOnly }

                val innerClassLines = mutableListOf<String>()

                // Build common mod-bus inner class
                if (commonMethods.isNotEmpty()) {
                    innerClassLines.add("")
                    innerClassLines.add("    // [forge2neo] Mod-bus event handlers extracted to static inner class")
                    innerClassLines.add("    @EventBusSubscriber(modid = $modidRef, bus = EventBusSubscriber.Bus.MOD)")
                    innerClassLines.add("    public static class ModEvents {")
                    for (cm in commonMethods) {
                        for (ml in cm.text.lines()) {
                            val trimmed = ml.trimStart()
                            if (trimmed.startsWith("public void ") || trimmed.startsWith("public static void ")) {
                                innerClassLines.add("        ${trimmed.replace("public void ", "public static void ")}")
                            } else {
                                innerClassLines.add("        $trimmed")
                            }
                        }
                        innerClassLines.add("")
                    }
                    innerClassLines.add("    }")
                }

                // Build client-only inner class
                if (clientMethods.isNotEmpty()) {
                    innerClassLines.add("")
                    innerClassLines.add("    // [forge2neo] Client-only event handlers extracted to Dist.CLIENT inner class")
                    innerClassLines.add("    @EventBusSubscriber(modid = $modidRef, bus = EventBusSubscriber.Bus.MOD, value = net.neoforged.api.distmarker.Dist.CLIENT)")
                    innerClassLines.add("    public static class ClientModEvents {")
                    for (cm in clientMethods) {
                        for (ml in cm.text.lines()) {
                            val trimmed = ml.trimStart()
                            if (trimmed.startsWith("public void ") || trimmed.startsWith("public static void ")) {
                                innerClassLines.add("        ${trimmed.replace("public void ", "public static void ")}")
                            } else {
                                innerClassLines.add("        $trimmed")
                            }
                        }
                        innerClassLines.add("")
                    }
                    innerClassLines.add("    }")
                }

                // Remove original methods (in reverse order)
                for (cm in modBusMethods.sortedByDescending { it.startLine }) {
                    for (j in cm.endLine downTo cm.startLine) {
                        lines.removeAt(j)
                    }
                }

                // Remove modEventBus.register(this) since inner classes auto-register
                val modBusRegIdx = lines.indexOfFirst { it.contains("modEventBus.register(this)") }
                if (modBusRegIdx >= 0) {
                    lines.removeAt(modBusRegIdx)
                }

                // Check if outer class still has @SubscribeEvent methods; if not, remove @EventBusSubscriber
                val hasRemainingSubscribeEvent = lines.any { line ->
                    val t = line.trimStart()
                    t.startsWith("@SubscribeEvent") && !t.contains("class ")
                }
                if (!hasRemainingSubscribeEvent) {
                    val ebsIdx = lines.indexOfFirst { it.trimStart().startsWith("@EventBusSubscriber") && !it.contains("class ") }
                    if (ebsIdx >= 0 && ebsIdx < lines.indexOfFirst { it.contains("public class ") || it.contains("public final class ") }) {
                        lines.removeAt(ebsIdx)
                    }
                }

                // Find the class closing brace and insert inner classes before it
                var lastBrace = lines.size - 1
                while (lastBrace >= 0 && !lines[lastBrace].trimStart().startsWith("}")) {
                    lastBrace--
                }
                if (lastBrace >= 0) {
                    lines.addAll(lastBrace, innerClassLines)
                }

                val desc = buildString {
                    append("Extract ")
                    if (commonMethods.isNotEmpty()) append("${commonMethods.size} mod-bus")
                    if (commonMethods.isNotEmpty() && clientMethods.isNotEmpty()) append(" + ")
                    if (clientMethods.isNotEmpty()) append("${clientMethods.size} client-only")
                    append(" @SubscribeEvent method(s) to inner class(es)")
                }

                changes.add(Change(
                    file = file,
                    line = modBusMethods.first().startLine + 1,
                    description = desc,
                    before = "Instance @SubscribeEvent methods + modEventBus.register(this)",
                    after = "@EventBusSubscriber static inner class(es)",
                    confidence = Confidence.HIGH,
                    ruleId = "struct-extract-mod-bus-events"
                ))

                if (!dryRun) {
                    file.writeText(lines.joinToString(separator))
                }
            }

        return changes
    }
}
