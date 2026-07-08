package com.modporter.integration

import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrictBenchmarkConfigTest {
    @Test
    fun `strict benchmark default cases are derived from all available real mod providers`() {
        val rows = Paths.get("src/test/resources/benchmarks/real-mods.tsv")
            .readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { columns -> columns.size >= 7 }
            .toList()

        val availableIds = rows
            .asSequence()
            .filter { columns -> columns.size >= 7 && !columns[2].equals("missing", ignoreCase = true) }
            .map { columns -> columns[0] }
            .toSet()

        val buildScript = Paths.get("build.gradle.kts").readText()
        assertTrue(
            buildScript.contains("fun realModBenchmarkCaseIds(): String"),
            "strictRealModBenchmark must derive cases from real-mods.tsv instead of a hand-written list"
        )
        assertTrue(
            buildScript.contains("""!columns[2].equals("missing", ignoreCase = true)"""),
            "strictRealModBenchmark must include every available provider and exclude only missing providers"
        )
        assertTrue(
            buildScript.contains("""defaultEnvironment("MODPORTER_BENCHMARK_CASES", realModBenchmarkCaseIds())"""),
            "strictRealModBenchmark must use the manifest-derived default case list"
        )
        assertTrue(
            availableIds.isNotEmpty(),
            "Current strict default should cover every available real-mod benchmark in the manifest"
        )
        assertTrue(
            rows.none { columns -> columns[2].equals("local", ignoreCase = true) },
            "Committed real-mod benchmark providers must be reproducible Git sources; use MODPORTER_BENCHMARK_SOURCE_* for local experiments"
        )
        assertTrue(
            rows
                .filterNot { columns -> columns[2].equals("missing", ignoreCase = true) }
                .all { columns -> columns[2].equals("git", ignoreCase = true) },
            "Every available real-mod benchmark provider must be git-backed"
        )
    }

    @Test
    fun `strict benchmark default timeout covers large required real mod gates`() {
        val buildScript = Paths.get("build.gradle.kts").readText()
        val configuredTimeout = Regex(
            """defaultEnvironment\("MODPORTER_BENCHMARK_TIMEOUT_SECONDS",\s*"(\d+)"\)"""
        ).find(buildScript)

        assertNotNull(configuredTimeout, "strictRealModBenchmark must configure a bounded runtime timeout")
        val timeoutSeconds = configuredTimeout.groupValues[1].toLong()
        assertTrue(
            timeoutSeconds >= 540L,
            "strictRealModBenchmark default timeout must cover large required real-mod runtime gates"
        )
    }
}
