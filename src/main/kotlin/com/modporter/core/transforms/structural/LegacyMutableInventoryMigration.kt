package com.modporter.core.transforms.structural

import com.modporter.core.pipeline.Change
import java.nio.file.Path

internal class LegacyMutableInventoryMigration {
    fun migrate(projectDir: Path, dryRun: Boolean): List<Change> =
        LegacyMutableRecipeWrapperMigration().migrate(projectDir, dryRun) +
            LegacyItemHandlerSetItemMigration().migrate(projectDir, dryRun)
}
