package com.modporter.mapping

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to verify the mapping database is complete and internally consistent.
 */
class MappingCompletenessTest {

    @Test
    fun `all text replacements have unique IDs`() {
        val db = MappingDatabase.loadDefault()
        val ids = db.getTextReplacements().map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys

        assertTrue(duplicates.isEmpty(),
            "Found duplicate text replacement IDs: $duplicates")
    }

    @Test
    fun `all text replacements have non-empty patterns and replacements`() {
        val db = MappingDatabase.loadDefault()
        db.getTextReplacements().forEach { rule ->
            assertTrue(rule.pattern.isNotBlank(), "Rule ${rule.id} has empty pattern")
            // Replacement can be empty for removal rules (e.g., removing deprecated API usage)
            assertTrue(rule.description.isNotBlank(), "Rule ${rule.id} has empty description")
        }
    }

    @Test
    fun `no text replacement pattern equals its replacement`() {
        val db = MappingDatabase.loadDefault()
        db.getTextReplacements().forEach { rule ->
            assertTrue(rule.pattern != rule.replacement,
                "Rule ${rule.id} has pattern equal to replacement: ${rule.pattern}")
        }
    }

    @Test
    fun `package renames are ordered correctly`() {
        // More specific packages must come BEFORE the generic net.minecraftforge
        val db = MappingDatabase.loadDefault()
        val pkgRules = db.getTextReplacements().filter { it.id.startsWith("pkg-") }

        val mainIdx = pkgRules.indexOfFirst { it.id == "pkg-forge-main" }
        assertTrue(mainIdx == pkgRules.lastIndex,
            "pkg-forge-main must be the LAST package rule to avoid double-replacement")

        // Verify all specific packages come before the generic one
        val specificPkgs = listOf("pkg-fml", "pkg-eventbus", "pkg-api-distmarker",
            "pkg-forgespi", "pkg-accesstransformer", "pkg-coremod", "pkg-jarjar")
        specificPkgs.forEach { id ->
            val idx = pkgRules.indexOfFirst { it.id == id }
            if (idx >= 0) {
                assertTrue(idx < mainIdx,
                    "$id (index $idx) must come before pkg-forge-main (index $mainIdx)")
            }
        }
    }

    @Test
    fun `class mappings have distinct source and target`() {
        val db = MappingDatabase.loadDefault()
        db.getAllClassMappings().forEach { (forge, mapping) ->
            assertTrue(forge != mapping.neoForgeClass,
                "Class mapping $forge maps to itself")
        }
    }

    @Test
    fun `class mappings do not have conflicting targets`() {
        val db = MappingDatabase.loadDefault()
        val targets = db.getAllClassMappings().values.groupBy { it.neoForgeClass }
        val conflicts = targets.filter { it.value.size > 1 }

        assertTrue(conflicts.isEmpty(),
            "Multiple classes map to the same target: ${conflicts.keys}")
    }

    @Test
    fun `method mappings reference known patterns`() {
        val db = MappingDatabase.loadDefault()
        db.getAllMethodMappings().forEach { (key, mapping) ->
            assertTrue(mapping.forgeClass.isNotBlank(), "Method mapping $key has empty forgeClass")
            assertTrue(mapping.forgeMethod.isNotBlank(), "Method mapping $key has empty forgeMethod")
            // neoForgeMethod can be empty for REMOVED methods
            assertTrue(mapping.description.isNotBlank(), "Method mapping $key has empty description")
        }
    }

    @Test
    fun `resource renames are unique and non-overlapping`() {
        val db = MappingDatabase.loadDefault()
        val renames = db.getAllResourceRenames()

        // No two source paths should be the same
        assertTrue(renames.size == renames.keys.distinct().size,
            "Resource renames have duplicate sources")

        // No target should equal any source (avoid cycles)
        val sources = renames.keys
        val targets = renames.values.toSet()
        val overlap = sources.intersect(targets)
        assertTrue(overlap.isEmpty(),
            "Resource rename has circular mapping: $overlap")
    }

    @Test
    fun `minimum mapping counts are met`() {
        val db = MappingDatabase.loadDefault()

        assertTrue(db.getTextReplacements().size >= 30,
            "Should have >= 30 text replacements, got ${db.getTextReplacements().size}")
        assertTrue(db.getAllClassMappings().size >= 40,
            "Should have >= 40 class mappings, got ${db.getAllClassMappings().size}")
        assertTrue(db.getAllMethodMappings().size >= 15,
            "Should have >= 15 method mappings, got ${db.getAllMethodMappings().size}")
        assertTrue(db.getAllResourceRenames().size >= 12,
            "Should have >= 12 resource renames, got ${db.getAllResourceRenames().size}")
    }

    @Test
    fun `IForgeXXX extension pattern is systematic`() {
        val db = MappingDatabase.loadDefault()
        val extensions = db.getAllClassMappings()
            .filter { it.key.startsWith("IForge") && it.key != "IForgeShearable" }

        extensions.forEach { (forge, mapping) ->
            val baseName = forge.removePrefix("IForge")
            val expected = "I${baseName}Extension"
            assertEquals(expected, mapping.neoForgeClass,
                "$forge should map to $expected, got ${mapping.neoForgeClass}")
        }
    }

    @Test
    fun `regex text replacements compile without error`() {
        val db = MappingDatabase.loadDefault()
        db.getTextReplacements().filter { it.isRegex }.forEach { rule ->
            try {
                Regex(rule.pattern)
            } catch (e: Exception) {
                throw AssertionError("Rule ${rule.id} has invalid regex '${rule.pattern}': ${e.message}")
            }
        }
    }
}
