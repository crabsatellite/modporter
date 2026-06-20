package com.modporter.integration

import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrictBenchmarkConfigTest {
    @Test
    fun `strict benchmark default cases include all required real mod providers`() {
        val requiredIds = Paths.get("src/test/resources/benchmarks/real-mods.tsv")
            .readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { columns -> columns.size >= 7 && columns[6].equals("true", ignoreCase = true) }
            .map { columns -> columns[0] }
            .toSet()

        val buildScript = Paths.get("build.gradle.kts").readText()
        val configuredCases = Regex(
            """defaultEnvironment\("MODPORTER_BENCHMARK_CASES",\s*"([^"]*)"\)"""
        ).find(buildScript)

        assertNotNull(configuredCases, "strictRealModBenchmark must configure MODPORTER_BENCHMARK_CASES explicitly")
        val defaultIds = configuredCases.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val missing = requiredIds - defaultIds
        assertTrue(
            missing.isEmpty(),
            "strictRealModBenchmark default cases must include every required benchmark id: missing ${missing.sorted()}"
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
