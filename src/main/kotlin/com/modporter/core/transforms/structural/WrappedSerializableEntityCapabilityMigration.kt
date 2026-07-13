package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Migrates an entity capability provider that only wraps a serializable data
 * object. Every generated attachment decision is recovered from an exact
 * declaration, constructor, attach hook, serializer pair, and lifecycle
 * listener in the source project.
 */
internal class WrappedSerializableEntityCapabilityMigration {
    private data class MethodRange(val start: Int, val signatureStart: Int, val openBrace: Int, val closeBrace: Int)

    private data class EventOwnerProof(
        val file: Path,
        val methodName: String,
        val eventVariable: String
    )

    private data class ModEntryProof(
        val file: Path,
        val className: String,
        val busVariable: String
    )

    private data class Spec(
        val providerFile: Path,
        val packageName: String,
        val providerClass: String,
        val apiType: String,
        val fieldName: String,
        val entityType: String,
        val entityVariable: String,
        val eventVariable: String,
        val lazyOptionalQualifiedName: String,
        val idPath: String,
        val namespace: String,
        val sentinelFactory: String,
        val removalListenerClass: String,
        val removalCallback: String,
        val capabilityDeclarationRange: IntRange,
        val attachMethod: MethodRange,
        val constructorMethod: MethodRange,
        val getCapabilityMethod: MethodRange,
        val serializeMethod: MethodRange,
        val deserializeMethod: MethodRange,
        val listenerClassRange: IntRange,
        val optionalFieldRange: IntRange,
        val handlerFieldRange: IntRange,
        val postAttachSource: String,
        val eventOwnerFile: Path,
        val eventOwnerMethodName: String,
        val eventOwnerEventVariable: String,
        val modEntryFile: Path,
        val modEntryClass: String,
        val modBusVariable: String
    )

    fun apply(projectDir: Path, dryRun: Boolean, errors: MutableList<String>): List<Change> {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!sourceRoot.exists()) return emptyList()
        val javaFiles = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "java" }.toList()
        }
        val sources = javaFiles.associateWith { it.readText() }
        val sourcesBySimpleClass = sources.entries
            .mapNotNull { (file, source) -> topLevelClassName(source)?.let { it to (file to source) } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

        val candidates = sources.filterValues { source ->
            executable(source).contains("CapabilityManager.get") &&
                executable(source).contains("ICapabilitySerializable<CompoundTag>") &&
                executable(source).contains("AttachCapabilitiesEvent<Entity>")
        }
        if (candidates.isEmpty()) return emptyList()

        val specs = candidates.mapNotNull { (file, source) ->
            analyze(projectDir, file, source, sources, sourcesBySimpleClass, errors)
        }
        if (specs.size != candidates.size) return emptyList()

        val duplicateFields = specs.groupBy { "${it.providerClass}.${it.fieldName}" }.filterValues { it.size > 1 }
        if (duplicateFields.isNotEmpty()) {
            duplicateFields.keys.forEach { errors += "Ambiguous wrapped serializable entity capability declaration: $it" }
            return emptyList()
        }

        val modifiedSources = sources.toMutableMap()
        for (spec in specs) {
            val providerOriginal = modifiedSources.getValue(spec.providerFile)
            var providerModified = migrateProviderSource(providerOriginal, spec)
            providerModified = rewriteQueries(providerModified, spec, qualifiedOnly = false)
            providerModified = removeListenerRegistrations(providerModified, spec)
            providerModified = cleanupImports(providerModified, spec)
            modifiedSources[spec.providerFile] = providerModified

            for (javaFile in javaFiles) {
                if (javaFile == spec.providerFile) continue
                val original = modifiedSources.getValue(javaFile)
                var modified = rewriteQueries(original, spec, qualifiedOnly = true)
                if (javaFile == spec.eventOwnerFile) modified = migrateDelegatingEventOwner(modified, spec)
                if (javaFile == spec.modEntryFile) modified = registerAttachmentOnModBus(modified, spec)
                if (modified != original) {
                    modified = cleanupImports(modified, spec)
                }
                modifiedSources[javaFile] = modified
            }
        }

        val closureErrors = specs.mapNotNull { validateClosure(projectDir, modifiedSources, it) }
        if (closureErrors.isNotEmpty()) {
            errors.addAll(closureErrors)
            return emptyList()
        }

        val changes = mutableListOf<Change>()
        for ((file, original) in sources) {
            val modified = modifiedSources.getValue(file)
            if (modified == original) continue
            if (!dryRun) file.writeText(modified)
            val isProvider = specs.any { it.providerFile == file }
            changes += if (isProvider) {
                change(
                    file,
                    "Migrate wrapped serializable entity capability to a persistent data attachment",
                    "CapabilityManager + ICapabilitySerializable provider",
                    "AttachmentType + source-derived serializer and lifecycle hooks",
                    "struct-wrapped-serializable-entity-attachment"
                )
            } else {
                change(
                    file,
                    "Rewrite wrapped entity capability integration to attachment APIs",
                    "getCapability / AttachCapabilitiesEvent delegation",
                    "getData / EntityJoinLevelEvent / EntityLeaveLevelEvent",
                    "struct-wrapped-serializable-entity-attachment-uses"
                )
            }
        }
        return changes
    }

    private fun analyze(
        projectDir: Path,
        file: Path,
        source: String,
        sources: Map<Path, String>,
        sourcesBySimpleClass: Map<String, Pair<Path, String>>,
        errors: MutableList<String>
    ): Spec? {
        fun reject(reason: String): Spec? {
            errors += "Cannot migrate wrapped serializable entity capability in " +
                "${projectDir.relativize(file).invariantSeparatorsPathString}: $reason"
            return null
        }

        val providerClass = Regex(
            """\bpublic\s+class\s+([A-Za-z_$][\w$]*)\s+implements\s+ICapabilitySerializable\s*<\s*CompoundTag\s*>"""
        ).find(executable(source))?.groupValues?.get(1) ?: return reject("provider class shape is not exact")
        val packageName = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(source)?.groupValues?.get(1).orEmpty()

        val declaration = Regex(
            """(?s)\bpublic\s+static\s+(?:final\s+)?Capability\s*<\s*([A-Za-z_$][\w$]*)\s*>\s+([A-Z_$][A-Z0-9_$]*)\s*=\s*CapabilityManager\.get\s*\(\s*new\s+CapabilityToken\s*<>\s*\(\s*\)\s*\{\s*}\s*\)\s*;"""
        ).find(source) ?: return reject("capability declaration is missing or ambiguous")
        val apiType = declaration.groupValues[1]
        val fieldName = declaration.groupValues[2]
        if (Regex.escape(fieldName).let { Regex("""\b$it\b""") }.findAll(executable(source)).count() < 2) {
            return reject("capability field is not connected to its provider")
        }

        val optionalField = Regex(
            """(?m)^\s*private\s+final\s+LazyOptional\s*<\s*${Regex.escape(apiType)}\s*>\s+([A-Za-z_$][\w$]*)\s*;\s*$"""
        ).find(source) ?: return reject("single final LazyOptional field is required")
        val handlerField = Regex(
            """(?m)^\s*private\s+(?:final\s+)?${Regex.escape(apiType)}\s+([A-Za-z_$][\w$]*)\s*;\s*$"""
        ).find(source) ?: return reject("wrapped serializable value field is required")
        val optionalName = optionalField.groupValues[1]
        val handlerName = handlerField.groupValues[1]
        val lazyOptionalQualifiedName = Regex("""(?m)^\s*import\s+([\w.]+\.LazyOptional)\s*;\s*$""")
            .findAll(source)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
            .singleOrNull() ?: return reject("LazyOptional type import is missing or ambiguous")

        val constructor = findMethod(
            source,
            Regex("""\bpublic\s+${Regex.escape(providerClass)}\s*\(\s*([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\s*\)""")
        ) ?: return reject("provider constructor is missing")
        val constructorSignature = source.substring(constructor.signatureStart, constructor.openBrace)
        val constructorMatch = Regex(
            """\bpublic\s+${Regex.escape(providerClass)}\s*\(\s*([A-Za-z_$][\w$]*)\s+([A-Za-z_$][\w$]*)\s*\)"""
        ).find(constructorSignature) ?: return reject("provider constructor signature is ambiguous")
        val entityType = constructorMatch.groupValues[1]
        val constructorEntity = constructorMatch.groupValues[2]
        val constructorBody = normalize(source.substring(constructor.openBrace + 1, constructor.closeBrace))
        val assignmentA = "$handlerName=new$apiType($constructorEntity);"
        val assignmentB = "$optionalName=LazyOptional.of(()->$handlerName);"
        if (constructorBody != assignmentA + assignmentB && constructorBody != assignmentB + assignmentA) {
            return reject("provider constructor contains behavior beyond exact wrapper initialization")
        }

        val getCapability = findMethod(
            source,
            Regex("""\bpublic\s+<T>\s+LazyOptional<T>\s+getCapability\s*\(\s*Capability<T>\s+([A-Za-z_$][\w$]*)\s*,\s*Direction\s+([A-Za-z_$][\w$]*)\s*\)""")
        ) ?: return reject("exact getCapability implementation is required")
        val getSignature = source.substring(getCapability.signatureStart, getCapability.openBrace)
        val capabilityParameter = Regex("""Capability<T>\s+([A-Za-z_$][\w$]*)""")
            .find(getSignature)?.groupValues?.get(1) ?: return reject("capability parameter is ambiguous")
        val getBody = normalize(source.substring(getCapability.openBrace + 1, getCapability.closeBrace))
        val expectedGetBody = "if($capabilityParameter==$fieldName)returnthis.$optionalName.cast();returnLazyOptional.empty();"
        if (getBody != expectedGetBody) return reject("getCapability is not an exact field-to-wrapper projection")

        val serialize = findMethod(
            source,
            Regex("""\bpublic\s+CompoundTag\s+serializeNBT\s*\([^)]*\)""")
        ) ?: return reject("serializeNBT method is missing")
        val serializeBody = normalize(source.substring(serialize.openBrace + 1, serialize.closeBrace))
        if (serializeBody !in setOf("return$handlerName.serializeNBT();", "return$handlerName.serializeNBT(provider);")) {
            return reject("serializeNBT must delegate only to the wrapped value")
        }

        val deserialize = findMethod(
            source,
            Regex("""\bpublic\s+void\s+deserializeNBT\s*\([^)]*CompoundTag\s+([A-Za-z_$][\w$]*)\s*\)""")
        ) ?: return reject("deserializeNBT method is missing")
        val deserializeSignature = source.substring(deserialize.signatureStart, deserialize.openBrace)
        val tagParameter = Regex("""CompoundTag\s+([A-Za-z_$][\w$]*)""")
            .find(deserializeSignature)?.groupValues?.get(1) ?: return reject("deserializeNBT tag parameter is ambiguous")
        val deserializeBody = normalize(source.substring(deserialize.openBrace + 1, deserialize.closeBrace))
        if (deserializeBody !in setOf("$handlerName.deserializeNBT($tagParameter);", "$handlerName.deserializeNBT(provider,$tagParameter);")) {
            return reject("deserializeNBT must delegate only to the wrapped value")
        }

        val apiSource = sourcesBySimpleClass[apiType]?.second
            ?: return reject("wrapped value type $apiType is not uniquely declared in project source")
        if (!Regex(
                """\bpublic\s+${Regex.escape(apiType)}\s*\(\s*${Regex.escape(entityType)}\s+[A-Za-z_$][\w$]*\s*\)"""
            ).containsMatchIn(executable(apiSource))) {
            return reject("wrapped value constructor does not accept the proven entity type $entityType")
        }
        val sentinelFactory = uniqueSentinelFactory(apiSource, apiType)
            ?: return reject("wrapped value requires one exact static sentinel factory backed by new $apiType(null)")

        val attach = findMethod(
            source,
            Regex("""\bpublic\s+static\s+void\s+attach\s*\(\s*AttachCapabilitiesEvent<Entity>\s+([A-Za-z_$][\w$]*)\s*\)""")
        ) ?: return reject("AttachCapabilitiesEvent<Entity> method is missing")
        val attachSignature = source.substring(attach.signatureStart, attach.openBrace)
        val eventVariable = Regex("""AttachCapabilitiesEvent<Entity>\s+([A-Za-z_$][\w$]*)""")
            .find(attachSignature)?.groupValues?.get(1) ?: return reject("attach event parameter is ambiguous")
        val attachBody = source.substring(attach.openBrace + 1, attach.closeBrace)
        val entityBinding = Regex(
            """\bEntity\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(eventVariable)}\.getObject\s*\(\s*\)\s*;"""
        ).find(attachBody) ?: return reject("attach method must bind event.getObject() to Entity")
        val entityVariable = entityBinding.groupValues[1]
        if (!Regex(
                """if\s*\(\s*!\s*\(\s*${Regex.escape(entityVariable)}\s+instanceof\s+${Regex.escape(entityType)}\s*\)\s*\)\s*return\s*;"""
            ).containsMatchIn(attachBody)) {
            return reject("attach guard must prove the constructor entity type")
        }
        val providerBinding = Regex(
            """\b${Regex.escape(providerClass)}\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+${Regex.escape(providerClass)}\s*\(\s*\(\s*${Regex.escape(entityType)}\s*\)\s*${Regex.escape(entityVariable)}\s*\)\s*;"""
        ).find(attachBody) ?: return reject("attach method must instantiate the exact provider from the guarded entity")
        val providerVariable = providerBinding.groupValues[1]
        val idBinding = Regex(
            """\bResourceLocation\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+)\s*;"""
        ).findAll(attachBody).toList().singleOrNull() ?: return reject("attach method requires one exact ResourceLocation binding")
        val idVariable = idBinding.groupValues[1]
        val idExpression = idBinding.groupValues[2].trim()
        if (!Regex(
                """\b${Regex.escape(eventVariable)}\.addCapability\s*\(\s*${Regex.escape(idVariable)}\s*,\s*${Regex.escape(providerVariable)}\s*\)\s*;"""
            ).containsMatchIn(attachBody)) {
            return reject("resource id and provider are not connected by addCapability")
        }
        val resolvedId = resolveResourceId(idExpression, sourcesBySimpleClass)
            ?: return reject("resource namespace/path cannot be proven from $idExpression")

        val listenerInvocation = invocationRange(attachBody, "$eventVariable.addListener")
            ?: return reject("attach lifecycle invalidation listener is missing")
        val listenerSource = attachBody.substring(listenerInvocation)
        val expectedPrefix = normalize(
            """
            Entity $entityVariable = $eventVariable.getObject();
            if (!($entityVariable instanceof $entityType)) return;
            $providerClass $providerVariable = new $providerClass(($entityType) $entityVariable);
            ResourceLocation $idVariable = $idExpression;
            $eventVariable.addCapability($idVariable, $providerVariable);
            """.trimIndent()
        )
        if (normalize(attachBody.substring(0, listenerInvocation.first)) != expectedPrefix) {
            return reject("attach method contains behavior outside the proven provider setup before invalidation")
        }
        val expectedListener = normalize(
            "$eventVariable.addListener(() -> { " +
                "if ($providerVariable.$optionalName.isPresent()) " +
                "$providerVariable.$optionalName.invalidate(); });"
        )
        if (normalize(listenerSource) != expectedListener) {
            return reject("attach lifecycle listener is not an exact wrapper invalidation")
        }
        val postAttach = attachBody.substring(listenerInvocation.last + 1).trim()
        if (Regex("""\b${Regex.escape(providerVariable)}\b""").containsMatchIn(executable(postAttach))) {
            return reject("provider local escapes the attach lifecycle block")
        }

        val removal = analyzeRemovalListener(source, apiType, entityType)
            ?: return reject("LazyOptional removal listener cannot be reduced to one exact entity-leave callback")
        val eventOwner = findEventOwnerProof(sources, file, providerClass)
            ?: return reject("requires one exact @EventBusSubscriber delegate for $providerClass.attach")
        val modEntry = findModEntryProof(sources, sourcesBySimpleClass, resolvedId.first)
            ?: return reject("requires one @Mod entrypoint for namespace ${resolvedId.first} with a proven IEventBus")
        val unsupportedUse = findUnsupportedCapabilityFieldUse(
            projectDir,
            sources,
            file,
            providerClass,
            fieldName,
            declaration.range,
            getCapability
        )
        if (unsupportedUse != null) return reject(unsupportedUse)

        return Spec(
            providerFile = file,
            packageName = packageName,
            providerClass = providerClass,
            apiType = apiType,
            fieldName = fieldName,
            entityType = entityType,
            entityVariable = entityVariable,
            eventVariable = eventVariable,
            lazyOptionalQualifiedName = lazyOptionalQualifiedName,
            idPath = resolvedId.second,
            namespace = resolvedId.first,
            sentinelFactory = sentinelFactory,
            removalListenerClass = removal.first,
            removalCallback = removal.second,
            capabilityDeclarationRange = declaration.range,
            attachMethod = attach,
            constructorMethod = constructor,
            getCapabilityMethod = getCapability,
            serializeMethod = serialize,
            deserializeMethod = deserialize,
            listenerClassRange = removal.third,
            optionalFieldRange = lineRange(source, optionalField.range),
            handlerFieldRange = lineRange(source, handlerField.range),
            postAttachSource = postAttach,
            eventOwnerFile = eventOwner.file,
            eventOwnerMethodName = eventOwner.methodName,
            eventOwnerEventVariable = eventOwner.eventVariable,
            modEntryFile = modEntry.file,
            modEntryClass = modEntry.className,
            modBusVariable = modEntry.busVariable
        )
    }

    private fun migrateProviderSource(source: String, spec: Spec): String {
        val serializer = """
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "${spec.namespace}");

    public static final Supplier<AttachmentType<${spec.apiType}>> ${spec.fieldName} =
        ATTACHMENT_TYPES.register("${spec.idPath}", () -> AttachmentType.builder(${spec.apiType}::${spec.sentinelFactory})
            .serialize(new IAttachmentSerializer<CompoundTag, ${spec.apiType}>() {
                @Override
                public ${spec.apiType} read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
                    ${spec.apiType} value = new ${spec.apiType}((${spec.entityType}) holder);
                    value.deserializeNBT(provider, tag);
                    return value;
                }

                @Override
                public CompoundTag write(${spec.apiType} value, HolderLookup.Provider provider) {
                    return value.serializeNBT(provider);
                }
            }).build());

    public static void registerAttachments(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
""".trimEnd()
        val attach = """
    public static void attach(EntityJoinLevelEvent ${spec.eventVariable}) {
        Entity ${spec.entityVariable} = ${spec.eventVariable}.getEntity();
        if (!(${spec.entityVariable} instanceof ${spec.entityType}))
            return;
        if (!${spec.eventVariable}.loadedFromDisk()) {
            ${spec.apiType} value = new ${spec.apiType}((${spec.entityType}) ${spec.entityVariable});
            ${spec.entityVariable}.setData(${spec.fieldName}, value);
        }
${indentBody(spec.postAttachSource, 2)}
    }

    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ${spec.entityType} typedEntity))
            return;
        ${spec.removalCallback}(event.getLevel(), typedEntity);
    }
""".trimEnd()
        val edits = listOf(
            spec.listenerClassRange to "",
            methodFullRange(spec.deserializeMethod) to "",
            methodFullRange(spec.serializeMethod) to "",
            methodFullRange(spec.getCapabilityMethod) to "",
            methodFullRange(spec.constructorMethod) to "",
            spec.handlerFieldRange to "",
            spec.optionalFieldRange to "",
            methodFullRange(spec.attachMethod) to attach,
            spec.capabilityDeclarationRange to serializer
        ).sortedByDescending { it.first.first }
        var result = source
        for ((range, replacement) in edits) {
            result = result.replaceRange(range, replacement)
        }
        result = Regex(
            """\bpublic\s+class\s+${Regex.escape(spec.providerClass)}\s+implements\s+ICapabilitySerializable\s*<\s*CompoundTag\s*>"""
        ).replace(result, "public class ${spec.providerClass}")
        result = addImport(result, "java.util.function.Supplier")
        result = addImport(result, "net.minecraft.core.HolderLookup")
        result = addImport(result, "net.neoforged.bus.api.IEventBus")
        result = addImport(result, "net.neoforged.neoforge.attachment.AttachmentType")
        result = addImport(result, "net.neoforged.neoforge.attachment.IAttachmentHolder")
        result = addImport(result, "net.neoforged.neoforge.attachment.IAttachmentSerializer")
        result = addImport(result, "net.neoforged.neoforge.event.entity.EntityJoinLevelEvent")
        result = addImport(result, "net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent")
        result = addImport(result, "net.neoforged.neoforge.registries.DeferredRegister")
        result = addImport(result, "net.neoforged.neoforge.registries.NeoForgeRegistries")
        return result
    }

    private fun rewriteQueries(source: String, spec: Spec, qualifiedOnly: Boolean): String {
        val pattern = capabilityQueryPattern(spec.providerClass, spec.fieldName, qualifiedOnly)
        val result = pattern.replace(source) { match ->
            "LazyOptional.ofNullable(${match.groupValues[1]}.getData(${match.groupValues[2]}.get()))" +
                ".filter(value -> value != ${spec.apiType}.${spec.sentinelFactory}())"
        }
        return if (result == source) source else addImport(result, spec.lazyOptionalQualifiedName)
    }

    private fun removeListenerRegistrations(source: String, spec: Spec): String = Regex(
        """(?m)^\s*[A-Za-z_$][\w$]*\.addListener\s*\(\s*new\s+${Regex.escape(spec.removalListenerClass)}\s*\([^;]+;\s*\r?\n"""
    ).replace(source, "")

    private fun migrateDelegatingEventOwner(source: String, spec: Spec): String {
        if (!source.contains("${spec.providerClass}.attach(")) return source
        val method = findMethod(
            source,
            Regex(
                """\bpublic\s+static\s+void\s+${Regex.escape(spec.eventOwnerMethodName)}\s*\(\s*AttachCapabilitiesEvent<Entity>\s+${Regex.escape(spec.eventOwnerEventVariable)}\s*\)"""
            )
        ) ?: return source
        val body = normalize(source.substring(method.openBrace + 1, method.closeBrace))
        val signature = source.substring(method.signatureStart, method.openBrace)
        val eventVar = spec.eventOwnerEventVariable
        if (body != "${spec.providerClass}.attach($eventVar);") return source
        var result = source.replaceRange(
            method.signatureStart until method.openBrace,
            signature.replace("AttachCapabilitiesEvent<Entity>", "EntityJoinLevelEvent")
        )
        val updatedMethod = findMethod(
            result,
            Regex(
                """\bpublic\s+static\s+void\s+${Regex.escape(spec.eventOwnerMethodName)}\s*\(\s*EntityJoinLevelEvent\s+${Regex.escape(eventVar)}\s*\)"""
            )
        ) ?: return source
        val leaveMethod = """

    @SubscribeEvent
    public static void on${spec.providerClass}EntityLeave(EntityLeaveLevelEvent event) {
        ${spec.providerClass}.onEntityLeave(event);
    }
""".trimEnd()
        result = result.substring(0, updatedMethod.closeBrace + 1) + leaveMethod + result.substring(updatedMethod.closeBrace + 1)
        result = addImport(result, "net.neoforged.neoforge.event.entity.EntityJoinLevelEvent")
        result = addImport(result, "net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent")
        return result
    }

    private fun registerAttachmentOnModBus(source: String, spec: Spec): String {
        if (source.contains("${spec.providerClass}.registerAttachments(")) return source
        val constructor = findMethod(
            source,
            Regex(
                """\bpublic\s+${Regex.escape(spec.modEntryClass)}\s*\([^)]*\bIEventBus\s+${Regex.escape(spec.modBusVariable)}\b[^)]*\)"""
            )
        ) ?: return source
        val call = "\n        ${spec.providerClass}.registerAttachments(${spec.modBusVariable});"
        var result = source.substring(0, constructor.openBrace + 1) + call + source.substring(constructor.openBrace + 1)
        if (packageName(source) != spec.packageName) {
            result = addImport(result, "${spec.packageName}.${spec.providerClass}")
        }
        return result
    }

    private fun findEventOwnerProof(
        sources: Map<Path, String>,
        providerFile: Path,
        providerClass: String
    ): EventOwnerProof? {
        val signaturePattern = Regex(
            """\bpublic\s+static\s+void\s+([A-Za-z_$][\w$]*)\s*\(\s*AttachCapabilitiesEvent<Entity>\s+([A-Za-z_$][\w$]*)\s*\)"""
        )
        val proofs = mutableListOf<EventOwnerProof>()
        for ((file, source) in sources) {
            if (file == providerFile || !source.contains("$providerClass.attach(")) continue
            val ownerClass = topLevelClassName(source) ?: continue
            val subscriberClass = Regex(
                """(?s)@(?:[A-Za-z_$][\w$]*\.)*EventBusSubscriber(?:\s*\([^)]*\))?\s*public\s+(?:final\s+|abstract\s+)?class\s+${Regex.escape(ownerClass)}\b"""
            ).containsMatchIn(executable(source))
            if (!subscriberClass) continue
            for (method in findMethods(source, signaturePattern)) {
                val signature = source.substring(method.signatureStart, method.openBrace)
                val match = signaturePattern.find(signature) ?: continue
                val methodName = match.groupValues[1]
                val eventVariable = match.groupValues[2]
                val annotations = source.substring(method.start, method.signatureStart)
                if (!Regex("""@(?:[A-Za-z_$][\w$]*\.)*SubscribeEvent\b""").containsMatchIn(annotations)) continue
                val body = normalize(source.substring(method.openBrace + 1, method.closeBrace))
                if (body != "$providerClass.attach($eventVariable);") continue
                if (Regex("""\bon${Regex.escape(providerClass)}EntityLeave\s*\(""").containsMatchIn(executable(source))) {
                    continue
                }
                proofs += EventOwnerProof(file, methodName, eventVariable)
            }
        }
        return proofs.singleOrNull()
    }

    private fun findModEntryProof(
        sources: Map<Path, String>,
        sourcesBySimpleClass: Map<String, Pair<Path, String>>,
        namespace: String
    ): ModEntryProof? {
        val proofs = mutableListOf<ModEntryProof>()
        for ((file, source) in sources) {
            val className = topLevelClassName(source) ?: continue
            val masked = executable(source)
            val modAnnotation = Regex("""@Mod\s*\(\s*([^)]+?)\s*\)""")
                .findAll(source)
                .filter { masked[it.range.first] == '@' }
                .toList()
                .singleOrNull() ?: continue
            val declaredNamespace = resolveString(modAnnotation.groupValues[1].trim(), className, sourcesBySimpleClass)
                ?: continue
            if (declaredNamespace != namespace) continue
            val constructorPattern = Regex(
                """\bpublic\s+${Regex.escape(className)}\s*\([^)]*\bIEventBus\s+([A-Za-z_$][\w$]*)\b[^)]*\)"""
            )
            for (constructor in findMethods(source, constructorPattern)) {
                val signature = source.substring(constructor.signatureStart, constructor.openBrace)
                val busVariable = constructorPattern.find(signature)?.groupValues?.get(1) ?: continue
                proofs += ModEntryProof(file, className, busVariable)
            }
        }
        return proofs.singleOrNull()
    }

    private fun findUnsupportedCapabilityFieldUse(
        projectDir: Path,
        sources: Map<Path, String>,
        providerFile: Path,
        providerClass: String,
        fieldName: String,
        declarationRange: IntRange,
        getCapabilityMethod: MethodRange
    ): String? {
        for ((file, source) in sources) {
            val masked = executable(source).toCharArray()
            if (file == providerFile) {
                blank(masked, declarationRange)
                blank(masked, methodFullRange(getCapabilityMethod))
                capabilityQueryPattern(providerClass, fieldName, qualifiedOnly = false)
                    .findAll(String(masked)).map { it.range }.toList().forEach { blank(masked, it) }
                if (Regex("""\b${Regex.escape(fieldName)}\b""").containsMatchIn(String(masked))) {
                    return "capability field $fieldName has an unsupported provider-local use"
                }
            } else {
                capabilityQueryPattern(providerClass, fieldName, qualifiedOnly = true)
                    .findAll(String(masked)).map { it.range }.toList().forEach { blank(masked, it) }
                if (Regex("""\b${Regex.escape(providerClass)}\s*\.\s*${Regex.escape(fieldName)}\b""")
                        .containsMatchIn(String(masked))) {
                    val relative = projectDir.relativize(file).invariantSeparatorsPathString
                    return "capability field $providerClass.$fieldName has an unsupported use in $relative"
                }
            }
        }
        return null
    }

    private fun validateClosure(projectDir: Path, sources: Map<Path, String>, spec: Spec): String? {
        val provider = sources.getValue(spec.providerFile)
        val providerCode = executable(provider)
        val relativeProvider = projectDir.relativize(spec.providerFile).invariantSeparatorsPathString
        fun failure(reason: String) = "Cannot close wrapped serializable entity capability migration in $relativeProvider: $reason"

        if (!provider.contains("Supplier<AttachmentType<${spec.apiType}>> ${spec.fieldName}")) {
            return failure("attachment declaration was not generated")
        }
        if (Regex("""\bpublic\s+class\s+${Regex.escape(spec.providerClass)}\s+implements\s+ICapabilitySerializable\b""")
                .containsMatchIn(providerCode)) {
            return failure("legacy serializable provider declaration remains")
        }
        if (providerCode.contains("CapabilityManager.get") || providerCode.contains("AttachCapabilitiesEvent<Entity>")) {
            return failure("legacy provider or attach API remains")
        }
        if (Regex("""\bnew\s+${Regex.escape(spec.removalListenerClass)}\s*\(""").containsMatchIn(providerCode)) {
            return failure("legacy LazyOptional removal listener registration remains")
        }
        for ((file, source) in sources) {
            val qualifiedOnly = file != spec.providerFile
            if (capabilityQueryPattern(spec.providerClass, spec.fieldName, qualifiedOnly).containsMatchIn(executable(source))) {
                val relative = projectDir.relativize(file).invariantSeparatorsPathString
                return failure("legacy getCapability query remains in $relative")
            }
        }

        val eventOwner = sources.getValue(spec.eventOwnerFile)
        if (!Regex(
                """\bpublic\s+static\s+void\s+${Regex.escape(spec.eventOwnerMethodName)}\s*\(\s*EntityJoinLevelEvent\s+${Regex.escape(spec.eventOwnerEventVariable)}\s*\)"""
            ).containsMatchIn(executable(eventOwner)) ||
            !eventOwner.contains("${spec.providerClass}.onEntityLeave(event);")) {
            return failure("entity join/leave event delegation was not closed")
        }

        val modEntry = sources.getValue(spec.modEntryFile)
        if (!modEntry.contains("${spec.providerClass}.registerAttachments(${spec.modBusVariable});")) {
            return failure("attachment register is not wired to the proven mod event bus")
        }
        return null
    }

    private fun capabilityQueryPattern(providerClass: String, fieldName: String, qualifiedOnly: Boolean): Regex {
        val owner = Regex.escape(providerClass)
        val field = Regex.escape(fieldName)
        val fieldReference = if (qualifiedOnly) "$owner\\.$field" else "(?:$owner\\.)?$field"
        val receiver = "[A-Za-z_$][\\w$]*(?:(?:\\.[A-Za-z_$][\\w$]*)|(?:\\.[A-Za-z_$][\\w$]*\\(\\)))*"
        return Regex("""(?<![A-Za-z0-9_$])($receiver)\.getCapability\s*\(\s*($fieldReference)\s*\)""")
    }

    private fun blank(chars: CharArray, range: IntRange) {
        for (index in range) {
            if (index in chars.indices && chars[index] != '\r' && chars[index] != '\n') chars[index] = ' '
        }
    }

    private fun cleanupImports(source: String, spec: Spec): String {
        var result = source
        val candidates = listOf(
            "net.neoforged.neoforge.capabilities.Capability",
            "net.neoforged.neoforge.capabilities.CapabilityManager",
            "net.neoforged.neoforge.capabilities.CapabilityToken",
            "net.neoforged.neoforge.capabilities.ICapabilitySerializable",
            "net.neoforged.neoforge.event.AttachCapabilitiesEvent",
            "net.minecraft.core.Direction",
            "net.minecraft.resources.ResourceLocation",
            "net.neoforged.neoforge.common.util.NonNullConsumer"
        )
        for (candidate in candidates) {
            val simple = candidate.substringAfterLast('.')
            val withoutImport = removeImport(result, candidate)
            if (!Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(executable(withoutImport))) {
                result = withoutImport
            }
        }
        result = removeGeneratedCapabilityImportsWhenUnused(result)
        return result
    }

    private fun analyzeRemovalListener(source: String, apiType: String, entityType: String): Triple<String, String, IntRange>? {
        val declaration = Regex(
            """\bpublic\s+static\s+class\s+([A-Za-z_$][\w$]*)\s+implements\s+NonNullConsumer\s*<\s*LazyOptional\s*<\s*${Regex.escape(apiType)}\s*>\s*>"""
        ).find(source) ?: return null
        val listenerClass = declaration.groupValues[1]
        val openBrace = source.indexOf('{', declaration.range.last)
        val closeBrace = matching(source, openBrace, '{', '}')
        if (openBrace < 0 || closeBrace < 0) return null
        val classSource = source.substring(declaration.range.first, closeBrace + 1)
        val levelField = Regex("""\bprivate\s+Level\s+([A-Za-z_$][\w$]*)\s*;""").find(classSource)?.groupValues?.get(1)
            ?: return null
        val entityField = Regex("""\bprivate\s+${Regex.escape(entityType)}\s+([A-Za-z_$][\w$]*)\s*;""")
            .find(classSource)?.groupValues?.get(1) ?: return null
        val accept = findMethod(
            classSource,
            Regex("""\bpublic\s+void\s+accept\s*\(\s*LazyOptional\s*<\s*${Regex.escape(apiType)}\s*>\s+[A-Za-z_$][\w$]*\s*\)""")
        ) ?: return null
        val acceptBody = normalize(classSource.substring(accept.openBrace + 1, accept.closeBrace))
        val callback = Regex(
            """^([A-Za-z_$][\w$]*)\(${Regex.escape(levelField)},${Regex.escape(entityField)}\);$"""
        ).matchEntire(acceptBody)?.groupValues?.get(1) ?: return null
        val classStart = lineStartIncludingAnnotations(source, declaration.range.first)
        var classEnd = closeBrace + 1
        while (classEnd < source.length && (source[classEnd] == '\r' || source[classEnd] == '\n')) classEnd++
        return Triple(listenerClass, callback, classStart until classEnd)
    }

    private fun uniqueSentinelFactory(source: String, apiType: String): String? {
        val matches = Regex(
            """(?s)\bpublic\s+static\s+${Regex.escape(apiType)}\s+([A-Za-z_$][\w$]*)\s*\(\s*\)\s*\{(.*?)\}"""
        ).findAll(source).filter { match ->
            normalize(match.groupValues[2]).contains("new$apiType(null)")
        }.map { it.groupValues[1] }.toList()
        return matches.singleOrNull()
    }

    private fun resolveResourceId(
        expression: String,
        sourcesBySimpleClass: Map<String, Pair<Path, String>>
    ): Pair<String, String>? {
        val factory = Regex("""^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)\(\s*"([^"]+)"\s*\)$""")
            .matchEntire(expression)
        if (factory != null) {
            val owner = factory.groupValues[1]
            val method = factory.groupValues[2]
            val path = factory.groupValues[3]
            val source = sourcesBySimpleClass[owner]?.second ?: return null
            val methodMatch = Regex(
                """(?s)\b${Regex.escape(method)}\s*\(\s*String\s+[A-Za-z_$][\w$]*\s*\)\s*\{(.*?)\}"""
            ).find(source) ?: return null
            val namespaceExpression = Regex(
                """(?:ResourceLocation\.fromNamespaceAndPath|new\s+ResourceLocation)\s*\(\s*([^,]+)\s*,"""
            ).find(methodMatch.groupValues[1])?.groupValues?.get(1)?.trim() ?: return null
            return resolveString(namespaceExpression, owner, sourcesBySimpleClass)?.let { it to path }
        }
        val direct = Regex(
            """^(?:ResourceLocation\.fromNamespaceAndPath|new\s+ResourceLocation)\s*\(\s*([^,]+)\s*,\s*"([^"]+)"\s*\)$"""
        ).matchEntire(expression) ?: return null
        return resolveString(direct.groupValues[1].trim(), null, sourcesBySimpleClass)
            ?.let { it to direct.groupValues[2] }
    }

    private fun resolveString(
        expression: String,
        ownerHint: String?,
        sourcesBySimpleClass: Map<String, Pair<Path, String>>
    ): String? {
        Regex("""^"([^"]+)"$""").matchEntire(expression)?.let { return it.groupValues[1] }
        val qualified = Regex("""^([A-Za-z_$][\w$]*)\.([A-Za-z_$][\w$]*)$""").matchEntire(expression)
        val owner = qualified?.groupValues?.get(1) ?: ownerHint ?: return null
        val field = qualified?.groupValues?.get(2) ?: expression
        val source = sourcesBySimpleClass[owner]?.second ?: return null
        return Regex(
            """\bstatic\s+final\s+String\s+${Regex.escape(field)}\s*=\s*"([^"]+)""""
        ).find(source)?.groupValues?.get(1)
    }

    private fun findMethod(source: String, signature: Regex): MethodRange? = findMethods(source, signature).singleOrNull()

    private fun findMethods(source: String, signature: Regex): List<MethodRange> {
        val masked = executable(source)
        return signature.findAll(masked).mapNotNull { match ->
            val openBrace = source.indexOf('{', match.range.last + 1)
            if (openBrace < 0) return@mapNotNull null
            val semicolon = source.indexOf(';', match.range.last + 1)
            if (semicolon in 0 until openBrace) return@mapNotNull null
            val closeBrace = matching(source, openBrace, '{', '}')
            if (closeBrace < 0) return@mapNotNull null
            MethodRange(
                start = lineStartIncludingAnnotations(source, match.range.first),
                signatureStart = match.range.first,
                openBrace = openBrace,
                closeBrace = closeBrace
            )
        }.toList()
    }

    private fun methodFullRange(method: MethodRange): IntRange {
        var end = method.closeBrace + 1
        return method.start until end
    }

    private fun lineRange(source: String, range: IntRange): IntRange {
        val start = source.lastIndexOf('\n', range.first).let { if (it < 0) 0 else it + 1 }
        var end = source.indexOf('\n', range.last + 1).let { if (it < 0) source.length else it + 1 }
        if (end < start) end = range.last + 1
        return start until end
    }

    private fun lineStartIncludingAnnotations(source: String, index: Int): Int {
        var start = source.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
        while (start > 0) {
            val previousEnd = start - 1
            val previousStart = source.lastIndexOf('\n', previousEnd - 1).let { if (it < 0) 0 else it + 1 }
            val previous = source.substring(previousStart, previousEnd).trim()
            if (!previous.startsWith("@")) break
            start = previousStart
        }
        return start
    }

    private fun invocationRange(source: String, prefix: String): IntRange? {
        val start = executable(source).indexOf(prefix)
        if (start < 0) return null
        val openParen = source.indexOf('(', start + prefix.length)
        val closeParen = matching(source, openParen, '(', ')')
        if (openParen < 0 || closeParen < 0) return null
        var end = closeParen + 1
        while (end < source.length && source[end].isWhitespace()) end++
        if (end >= source.length || source[end] != ';') return null
        return start..end
    }

    private fun matching(source: String, open: Int, openChar: Char, closeChar: Char): Int {
        if (open !in source.indices || source[open] != openChar) return -1
        val masked = executable(source)
        var depth = 0
        for (index in open until masked.length) {
            when (masked[index]) {
                openChar -> depth++
                closeChar -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun executable(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        var state = 0
        while (index < chars.size) {
            when (state) {
                0 -> when {
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '/' -> {
                        chars[index] = ' '; chars[index + 1] = ' '; index += 2; state = 1; continue
                    }
                    chars[index] == '/' && index + 1 < chars.size && chars[index + 1] == '*' -> {
                        chars[index] = ' '; chars[index + 1] = ' '; index += 2; state = 2; continue
                    }
                    chars[index] == '"' -> { chars[index] = ' '; state = 3 }
                    chars[index] == '\'' -> { chars[index] = ' '; state = 4 }
                }
                1 -> if (chars[index] == '\n' || chars[index] == '\r') state = 0 else chars[index] = ' '
                2 -> if (chars[index] == '*' && index + 1 < chars.size && chars[index + 1] == '/') {
                    chars[index] = ' '; chars[index + 1] = ' '; index++; state = 0
                } else if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
                3 -> if (chars[index] == '\\' && index + 1 < chars.size) {
                    chars[index] = ' '; chars[index + 1] = ' '; index++
                } else if (chars[index] == '"') { chars[index] = ' '; state = 0 }
                else -> if (chars[index] == '\\' && index + 1 < chars.size) {
                    chars[index] = ' '; chars[index + 1] = ' '; index++
                } else if (chars[index] == '\'') { chars[index] = ' '; state = 0 }
            }
            index++
        }
        return String(chars)
    }

    private fun normalize(source: String): String = executable(source).filterNot(Char::isWhitespace)

    private fun topLevelClassName(source: String): String? = Regex(
        """\bpublic\s+(?:final\s+|abstract\s+)?(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)"""
    ).find(executable(source))?.groupValues?.get(1)

    private fun packageName(source: String): String = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
        .find(source)?.groupValues?.get(1).orEmpty()

    private fun addImport(source: String, qualifiedName: String): String {
        val importLine = "import $qualifiedName;"
        if (source.lineSequence().any { it.trim() == importLine }) return source
        val imports = Regex("""(?m)^import\s+[^;]+;\s*$""").findAll(source).toList()
        if (imports.isNotEmpty()) {
            val end = imports.last().range.last + 1
            return source.substring(0, end) + System.lineSeparator() + importLine + source.substring(end)
        }
        val packageMatch = Regex("""(?m)^package\s+[^;]+;\s*$""").find(source) ?: return source
        val end = packageMatch.range.last + 1
        return source.substring(0, end) + System.lineSeparator() + System.lineSeparator() + importLine + source.substring(end)
    }

    private fun removeImport(source: String, qualifiedName: String): String = Regex(
        """(?m)^\s*import\s+${Regex.escape(qualifiedName)}\s*;\s*\r?\n"""
    ).replace(source, "")

    private fun removeGeneratedCapabilityImportsWhenUnused(source: String): String {
        var result = source
        for (simple in listOf("Capability", "CapabilityManager", "CapabilityToken")) {
            val importPattern = Regex(
                """(?m)^\s*import\s+com\.modporter\.generated\.[\w$.]+\.compat\.${Regex.escape(simple)}\s*;\s*\r?\n"""
            )
            val withoutImport = importPattern.replace(result, "")
            if (!Regex("""\b${Regex.escape(simple)}\b""").containsMatchIn(executable(withoutImport))) {
                result = withoutImport
            }
        }
        return result
    }

    private fun indentBody(source: String, levels: Int): String {
        if (source.isBlank()) return ""
        val indent = "    ".repeat(levels)
        return source.lines().joinToString(System.lineSeparator()) { line ->
            if (line.isBlank()) "" else indent + line.trimStart()
        }
    }

    private fun change(file: Path, description: String, before: String, after: String, ruleId: String): Change =
        Change(file, 1, description, before, after, Confidence.HIGH, ruleId)
}
