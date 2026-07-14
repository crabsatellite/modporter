package com.modporter.core.transforms.structural

/** Exact public type contracts for third-party APIs that are absent from the source project. */
internal object ExactExternalTypeContracts {
    data class RecordComponentSurface(
        val importOwner: String,
        val sourceType: String,
        val qualifiedType: String,
        val components: Set<String>
    )

    val assignableTypes = mapOf(
        "net.createmod.ponder.api.level.PonderLevel" to setOf(
            "net.createmod.catnip.levelWrappers.SchematicLevel",
            "net.createmod.catnip.levelWrappers.WrappedLevel",
            "net.minecraft.world.level.Level"
        ),
        "net.createmod.catnip.levelWrappers.SchematicLevel" to setOf(
            "net.createmod.catnip.levelWrappers.WrappedLevel",
            "net.minecraft.world.level.Level"
        ),
        "net.createmod.catnip.levelWrappers.WrappedLevel" to setOf(
            "net.minecraft.world.level.Level"
        )
    )

    val recordComponentSurfaces = listOf(
        RecordComponentSurface(
            importOwner = "com.dtteam.dynamictrees.block.branch.TrunkShellBlock",
            sourceType = "TrunkShellBlock.ShellMuse",
            qualifiedType = "com.dtteam.dynamictrees.block.branch.TrunkShellBlock.ShellMuse",
            components = setOf("state", "pos", "museOffset")
        )
    )

    private val closedMethodSurfaces = mapOf(
        "net.createmod.ponder.api.VirtualBlockEntity" to setOf(
            MethodSignature("isVirtual", emptyList()),
            MethodSignature("markVirtual", emptyList())
        )
    )

    fun hasClosedMethodSurface(owner: String): Boolean = owner in closedMethodSurfaces

    fun containsMethod(owner: String, name: String, parameterTypes: List<String>): Boolean =
        MethodSignature(name, parameterTypes) in closedMethodSurfaces[owner].orEmpty()

    private data class MethodSignature(
        val name: String,
        val parameterTypes: List<String>
    )
}
