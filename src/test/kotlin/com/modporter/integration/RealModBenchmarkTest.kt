package com.modporter.integration

import com.modporter.core.pipeline.Pipeline
import com.modporter.core.pipeline.PipelineResult
import com.modporter.core.pipeline.Confidence
import com.modporter.mapping.MappingDatabase
import com.modporter.registry.PipelineOptions
import com.modporter.registry.PipelineRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * Real-mod benchmark harness.
 *
 * The benchmark downloads/copies source mods into a temporary work area, ports them, records a
 * report, and deletes the temporary sources/converted outputs by default.
 *
 * Optional gates:
 *   MODPORTER_BENCHMARK_STRICT=true       fail if optional sources/providers are missing
 *   MODPORTER_BENCHMARK_STRICT_RUNTIME=true
 *                                           require hands-off compile plus server/client/world runtime gates
 *   MODPORTER_BENCHMARK_HANDS_OFF=true    fail if medium/low-confidence changes remain
 *   MODPORTER_BENCHMARK_COMPILE=true      run compileJava in converted projects
 *   MODPORTER_BENCHMARK_RUNSERVER=true    run runServer, wait for ready, then terminate cleanly
 *   MODPORTER_BENCHMARK_RUNGAMETESTSERVER=true
 *                                           run runGameTestServer to exercise server lifecycle
 *   MODPORTER_BENCHMARK_RUNCLIENT=true    run runClient until title-screen readiness markers
 *   MODPORTER_BENCHMARK_RUNCLIENTWORLD=true
 *                                           run runClientWorld and quick-load the smoke save
 *   MODPORTER_BENCHMARK_LOG_CLEAN=true    fail runtime gates on non-allowlisted WARN lines
 *   MODPORTER_BENCHMARK_KEEP_WORK=true    keep temporary sources/outputs for debugging
 *   MODPORTER_BENCHMARK_CASES=a,b         run only the listed manifest ids
 *   MODPORTER_BENCHMARK_PROGRESS_GRACE_SECONDS=75
 *                                           after a progress marker, fail fast if final markers never arrive
 *   MODPORTER_BENCHMARK_CLIENT_WORLD=path use a prepared vanilla save for runClientWorld
 *   MODPORTER_BENCHMARK_MINECRAFT_SERVER_JAR=path
 *                                           override the vanilla server jar used to generate the smoke save
 *   MODPORTER_BENCHMARK_JAVA21=path       override the Java 21 executable for vanilla server generation
 */
class RealModBenchmarkTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "MODPORTER_REAL_MOD_TEST", matches = "true")
    fun `real mod benchmark manifest`() {
        val repoRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val reportRoot = repoRoot.resolve("build/real-mod-benchmark").toAbsolutePath().normalize()
        val tempRoot = reportRoot.resolve("tmp")
        val sourceRoot = tempRoot.resolve("sources")
        val workRoot = tempRoot.resolve("work")
        val artifactRoot = tempRoot.resolve("artifacts")
        val reportsDir = reportRoot.resolve("reports")

        reportsDir.createDirectories()
        deleteDirectory(reportRoot.resolve("work"), reportRoot) // legacy output from older benchmark versions
        resetDirectory(tempRoot, reportRoot)
        sourceRoot.createDirectories()
        workRoot.createDirectories()
        artifactRoot.createDirectories()

        val options = BenchmarkOptions.fromEnvironment()
        val cases = selectCases(
            loadCases(repoRoot.resolve("src/test/resources/benchmarks/real-mods.tsv")),
            options.caseIds
        )
        assertTrue(cases.isNotEmpty(), "Benchmark manifest should contain at least one case")

        val outcomes = try {
            val clientSmokeWorldFixture = if (options.runClientWorld) {
                prepareClientSmokeWorldFixture(tempRoot, reportsDir, options)
            } else {
                ClientSmokeWorldFixture(null, CheckResult.notRun("MODPORTER_BENCHMARK_RUNCLIENTWORLD=false"))
            }
            cases.map { case ->
                val publishArtifacts = cases.any { it.dependencies.contains(case.id) }
                runCase(
                    case,
                    repoRoot,
                    sourceRoot,
                    workRoot,
                    artifactRoot,
                    reportsDir,
                    options,
                    publishArtifacts,
                    clientSmokeWorldFixture
                )
            }
        } finally {
            if (!options.keepWork) {
                deleteDirectory(tempRoot, reportRoot)
            }
        }

        val reportPath = reportsDir.resolve(options.reportFileName())
        reportPath.writeText(renderReport(outcomes, options))

        println("Real mod benchmark report: $reportPath")

        val failures = outcomes.filter { it.status == Status.FAIL || (options.strict && it.status == Status.SKIP) }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("Real mod benchmark failures:")
                failures.forEach { appendLine("- ${it.case.id}: ${it.note}") }
                appendLine("Report: $reportPath")
            }
        )
    }

    @Test
    fun `runtime progress gate terminates stalled client world without full timeout`(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("client-world.log")
        val outerTimeoutSeconds = 20L
        val started = System.nanoTime()

        val result = runRuntimeProcess(
            stalledClientWorldCommand(tempDir),
            tempDir,
            logFile,
            timeoutSeconds = outerTimeoutSeconds,
            runtimeLogPolicy = RuntimeLogPolicy.clientWorld(failOnWarnings = false, progressGraceSeconds = 1)
        )

        val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        assertTrue(result.status == CheckStatus.FAIL, "Expected stalled client-world gate to fail")
        assertTrue(
            result.note.contains("progress gate 'client start' waited 1s"),
            "Expected progress gate failure note, got: ${result.note}"
        )
        assertTrue(
            elapsedSeconds < outerTimeoutSeconds,
            "Progress gate should fail before the outer timeout; elapsed=${elapsedSeconds}s"
        )
    }

    @Test
    fun `runtime log audit allows OpenAL invalid-name only during client shutdown`(@TempDir tempDir: Path) {
        val earlyLog = tempDir.resolve("early-openal.log")
        earlyLog.writeText("[10:23:15] [Sound engine/ERROR] [mojang/OpenAlUtil]: Stop: Invalid name parameter.\n")

        val shutdownLog = tempDir.resolve("shutdown-openal.log")
        shutdownLog.writeText("""
            [10:23:14] [Render thread/INFO] [minecraft/Minecraft]: Stopping!
            [10:23:15] [Sound engine/ERROR] [mojang/OpenAlUtil]: Stop: Invalid name parameter.
        """.trimIndent() + "\n")

        assertTrue(
            auditRuntimeLog(earlyLog, failOnWarnings = false).findings.any { it.contains("fatal") },
            "OpenAL invalid-name before client shutdown must remain fatal"
        )
        assertTrue(
            auditRuntimeLog(shutdownLog, failOnWarnings = false).findings.isEmpty(),
            "OpenAL invalid-name after Minecraft shutdown is benchmark harness noise"
        )
    }

    @Test
    fun `runtime log audit allows local connection closed only during client shutdown`(@TempDir tempDir: Path) {
        val earlyLog = tempDir.resolve("early-closed-channel.log")
        earlyLog.writeText("""
            [10:08:22] [Netty Server IO #1/ERROR] [minecraft/Connection]: Exception caught in connection
            java.nio.channels.ClosedChannelException: null
        """.trimIndent() + "\n")

        val shutdownLog = tempDir.resolve("shutdown-closed-channel.log")
        shutdownLog.writeText("""
            [10:08:22] [Render thread/INFO] [minecraft/Minecraft]: Stopping!
            [10:08:22] [Netty Server IO #1/ERROR] [minecraft/Connection]: Exception caught in connection
            java.nio.channels.ClosedChannelException: null
        """.trimIndent() + "\n")

        assertTrue(
            auditRuntimeLog(earlyLog, failOnWarnings = false).findings.any { it.contains("fatal") },
            "Connection errors before client shutdown must remain fatal"
        )
        assertTrue(
            auditRuntimeLog(shutdownLog, failOnWarnings = false).findings.isEmpty(),
            "Local ClosedChannelException after Minecraft shutdown is benchmark harness noise"
        )
    }

    @Test
    fun `runtime log audit allows Mojang profile lookup network timeout`(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("profile-lookup.log")
        logFile.writeText("""
            [14:20:49] [Download-1/WARN] [mojang/YggdrasilMinecraftSessionService]: Couldn't look up profile properties for 380df991-f603-344c-a090-369bad2a924a
            com.mojang.authlib.exceptions.MinecraftClientException: Failed to read from https://sessionserver.mojang.com/session/minecraft/profile/380df991f603344ca090369bad2a924a?unsigned=false due to Read timed out
        """.trimIndent() + "\n")

        assertTrue(
            auditRuntimeLog(logFile, failOnWarnings = true).findings.isEmpty(),
            "Profile lookup timeout is vanilla session-server network noise, not a mod migration signal"
        )
    }

    @Test
    fun `runtime log audit allows external dependency optional mixin warning only with project absence evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        val logFile = tempDir.resolve("external-mixin.log")
        logFile.writeText("""
            [05:05:53] [main/DEBUG] [mixin/]: Registering mixin config: quark_integrations.mixins.json source=SecureJarResource(quark)
            [05:05:54] [main/WARN] [mixin/]: Error loading class: noobanidus/mods/lootr/common/impl/LootrServiceRegistry (java.lang.ClassNotFoundException: noobanidus.mods.lootr.common.impl.LootrServiceRegistry)
            [05:05:54] [main/WARN] [mixin/]: @Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found quark_integrations.mixins.json:lootr.LootrServiceRegistryMixin from mod (unknown)
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(audit.findings.isEmpty(), "Expected external dependency mixin warning to be allowlisted: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("config 'quark_integrations.mixins.json' was loaded from dependency 'quark'") },
            "Expected machine evidence in allowed issues, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps external mixin warning when project owns mixin config`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        val resources = projectDir.resolve("src/main/resources")
        resources.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        resources.resolve("quark_integrations.mixins.json").writeText("{}\n")
        val logFile = tempDir.resolve("owned-mixin.log")
        logFile.writeText("""
            [05:05:53] [main/DEBUG] [mixin/]: Registering mixin config: quark_integrations.mixins.json source=SecureJarResource(quark)
            [05:05:54] [main/WARN] [mixin/]: @Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found quark_integrations.mixins.json:lootr.LootrServiceRegistryMixin from mod (unknown)
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(
            audit.findings.any { it.contains("@Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found") },
            "Project-owned mixin config must keep warning fatal"
        )
    }

    @Test
    fun `runtime log audit allows external mixin warning with classpath config evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        val moddevDir = projectDir.resolve("build/moddev")
        val dependencyJar = tempDir.resolve("quark.jar")
        moddevDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        createJar(
            dependencyJar,
            mapOf(
                "quark_integrations.mixins.json" to "{}",
                "META-INF/neoforge.mods.toml" to """
                    [[mods]]
                    modId = "quark"
                """.trimIndent()
            )
        )
        moddevDir.resolve("gameTestServerLegacyClasspath.txt").writeText("$dependencyJar\n")
        val logFile = tempDir.resolve("external-mixin-classpath.log")
        logFile.writeText("""
            [05:05:54] [main/WARN] [mixin/]: Error loading class: noobanidus/mods/lootr/common/impl/LootrServiceRegistry (java.lang.ClassNotFoundException: noobanidus.mods.lootr.common.impl.LootrServiceRegistry)
            [05:05:54] [main/WARN] [mixin/]: @Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found quark_integrations.mixins.json:lootr.LootrServiceRegistryMixin from mod (unknown)
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(audit.findings.isEmpty(), "Expected classpath-proven external mixin warning to be allowlisted: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("config 'quark_integrations.mixins.json' was found in external runtime jar for dependency 'quark'") },
            "Expected classpath evidence in allowed issues, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps external mixin warning when target source references missing class`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        val moddevDir = projectDir.resolve("build/moddev")
        val javaDir = projectDir.resolve("src/main/java/example")
        val dependencyJar = tempDir.resolve("quark.jar")
        moddevDir.createDirectories()
        javaDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        javaDir.resolve("Integration.java").writeText("""
            package example;
            class Integration {
                String target = "noobanidus.mods.lootr.common.impl.LootrServiceRegistry";
            }
        """.trimIndent() + "\n")
        createJar(
            dependencyJar,
            mapOf(
                "quark_integrations.mixins.json" to "{}",
                "META-INF/neoforge.mods.toml" to """
                    [[mods]]
                    modId = "quark"
                """.trimIndent()
            )
        )
        moddevDir.resolve("gameTestServerLegacyClasspath.txt").writeText("$dependencyJar\n")
        val logFile = tempDir.resolve("external-mixin-owned-target.log")
        logFile.writeText("""
            [05:05:54] [main/WARN] [mixin/]: @Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found quark_integrations.mixins.json:lootr.LootrServiceRegistryMixin from mod (unknown)
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(
            audit.findings.any { it.contains("@Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found") },
            "Project-owned target references must keep warning fatal"
        )
    }

    @Test
    fun `runtime log audit allows external mixin warning with loaded mod dependency evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                implementation "maven.modrinth:quark:4.1-480"
            }
        """.trimIndent() + "\n")
        val logFile = tempDir.resolve("external-mixin-loaded-mod.log")
        logFile.writeText("""
            [05:05:50] [main/INFO] [ne.ne.fm.lo.mo.ModDiscoverer/]:
                 Mod List:
                    Quark 4.1-480 (quark)
                    The Aether 0.0NONE (aether)
            [05:05:54] [main/WARN] [mixin/]: @Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found quark_integrations.mixins.json:lootr.LootrServiceRegistryMixin from mod (unknown)
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(audit.findings.isEmpty(), "Expected loaded-mod dependency evidence to allow external mixin warning: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("runtime Mod List and active build dependency evidence") },
            "Expected loaded-mod dependency evidence in allowed issues, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps loaded mod mixin warning without active dependency evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                // implementation "maven.modrinth:quark:4.1-480"
            }
        """.trimIndent() + "\n")
        val logFile = tempDir.resolve("external-mixin-loaded-mod-no-dep.log")
        logFile.writeText("""
            [05:05:50] [main/INFO] [ne.ne.fm.lo.mo.ModDiscoverer/]:
                 Mod List:
                    Quark 4.1-480 (quark)
                    The Aether 0.0NONE (aether)
            [05:05:54] [main/WARN] [mixin/]: @Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found quark_integrations.mixins.json:lootr.LootrServiceRegistryMixin from mod (unknown)
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(
            audit.findings.any { it.contains("@Mixin target noobanidus.mods.lootr.common.impl.LootrServiceRegistry was not found") },
            "Loaded-mod name alone must not allowlist missing mixin target warnings"
        )
    }

    @Test
    fun `runtime log audit allows external dependency config correction with active dependency evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                implementation "maven.modrinth:quark:4.1-480"
            }
        """.trimIndent() + "\n")
        val logFile = tempDir.resolve("external-config-correction.log")
        logFile.writeText("""
            [06:14:06] [modloading-worker-0/DEBUG] [ne.ne.fm.co.ConfigTracker/CONFIG]: Config file quark-common.toml for quark tracking
            [06:14:06] [modloading-worker-0/WARN] [ne.ne.fm.co.ConfigTracker/CONFIG]: Configuration file ${projectDir.resolve("run/config/quark-common.toml")} is not correct. Correcting
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(audit.findings.isEmpty(), "Expected external config correction to be allowlisted: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("config 'quark-common.toml' is tracked for loaded external mod 'quark'") },
            "Expected active dependency evidence in allowed issues, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps target config correction warning`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/example")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=example\n")
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                implementation "local:example:1.0"
            }
        """.trimIndent() + "\n")
        val logFile = tempDir.resolve("target-config-correction.log")
        logFile.writeText("""
            [06:14:06] [modloading-worker-0/DEBUG] [ne.ne.fm.co.ConfigTracker/CONFIG]: Config file example-common.toml for example tracking
            [06:14:06] [modloading-worker-0/WARN] [ne.ne.fm.co.ConfigTracker/CONFIG]: Configuration file ${projectDir.resolve("run/config/example-common.toml")} is not correct. Correcting
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(
            audit.findings.any { it.contains("Configuration file") && it.contains("example-common.toml") },
            "Target mod config corrections must remain fatal"
        )
    }

    private fun createJar(jar: Path, entries: Map<String, String>) {
        jar.parent?.createDirectories()
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            for ((name, content) in entries) {
                output.putNextEntry(JarEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    @Test
    fun `runtime log audit allows external dependency mob category warning only without source reference`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        val logFile = tempDir.resolve("external-mob-category.log")
        logFile.writeText("""
            [05:06:10] [Server thread/WARN] [ne.ne.ne.se.ServerLifecycleHooks/]: Detected quark:stoneling that was registered with CREATURE mob category but was added under MONSTER mob category for aether:skyroot_forest biome! Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(audit.findings.isEmpty(), "Expected external dependency mob warning to be allowlisted: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("entity 'quark:stoneling' is not the target mod namespace") },
            "Expected machine evidence in allowed issues, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit summarizes repeated allowed issues without hiding evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/example")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=example\n")
        val logFile = tempDir.resolve("repeated-external-mob-category.log")
        logFile.writeText("""
            [05:06:10] [Server thread/WARN] [ne.ne.ne.se.ServerLifecycleHooks/]: Detected external:scout that was registered with CREATURE mob category but was added under MONSTER mob category for example:highlands biome! Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.
            [05:06:11] [Server thread/WARN] [ne.ne.ne.se.ServerLifecycleHooks/]: Detected external:burrower that was registered with CREATURE mob category but was added under MONSTER mob category for example:highlands biome! Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.
            [05:06:12] [Server thread/WARN] [ne.ne.ne.se.ServerLifecycleHooks/]: Detected external:scout that was registered with CREATURE mob category but was added under MONSTER mob category for example:grove biome! Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.
            [05:06:13] [Server thread/WARN] [ne.ne.ne.se.ServerLifecycleHooks/]: Detected external:scout that was registered with CREATURE mob category but was added under MONSTER mob category for example:thicket biome! Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(audit.findings.isEmpty(), "Expected repeated external dependency warnings to remain allowlisted")
        assertTrue(
            audit.allowedIssues.size == 2,
            "Repeated warnings should be grouped by identical machine evidence, got: ${audit.allowedIssues}"
        )
        assertTrue(
            audit.allowedIssues.any {
                it.contains("3 occurrences") &&
                    it.contains("entity 'external:scout' is not the target mod namespace")
            },
            "Expected repeated external entity evidence to be summarized with count, got: ${audit.allowedIssues}"
        )
        assertTrue(
            audit.allowedIssues.any {
                it.startsWith("line ") &&
                    it.contains("entity 'external:burrower' is not the target mod namespace")
            },
            "Single warnings should retain their direct line evidence, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps external mob category warning when target source references entity`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        val javaDir = projectDir.resolve("src/main/java/example")
        javaDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        javaDir.resolve("Integration.java").writeText("""
            package example;

            final class Integration {
                static final String ENTITY = "quark:stoneling";
            }
        """.trimIndent())
        val logFile = tempDir.resolve("referenced-mob-category.log")
        logFile.writeText("""
            [05:06:10] [Server thread/WARN] [ne.ne.ne.se.ServerLifecycleHooks/]: Detected quark:stoneling that was registered with CREATURE mob category but was added under MONSTER mob category for aether:skyroot_forest biome! Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(
            audit.findings.any { it.contains("Detected quark:stoneling") },
            "Target source references to the entity must keep warning fatal"
        )
    }

    @Test
    fun `runtime log audit allows external dependency creative tab duplicate warning with dependency evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                implementation "maven.modrinth:quark:4.1-480"
            }
        """.trimIndent() + "\n")
        val logFile = tempDir.resolve("external-creative-duplicate.log")
        logFile.writeText("""
            [05:05:50] [main/INFO] [ne.ne.fm.lo.mo.ModDiscoverer/]:
                 Mod List:
                    Quark 4.1-480 (quark)
                    The Aether 0.0NONE (aether)
            [07:58:51] [Render thread/WARN] [me.je.li.pl.va.in.ItemStackListFactory/]: 52 duplicate items were found in 'Tools & Utilities' creative tab's: displayItems
            This may indicate that these types of item need a subtype interpreter added to JEI:
            [quark:seed_pouch, quark:pathfinders_quill]
            [07:58:51] [Render thread/DEBUG] [zeta/]: minecraft:combat - 0/0/0 - 0/0
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(audit.findings.isEmpty(), "Expected external dependency creative duplicate warning to be allowlisted: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("duplicate item ids [quark:seed_pouch, quark:pathfinders_quill] belong to external dependency namespace(s) quark") },
            "Expected machine evidence in allowed issues, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps external creative tab duplicate warning when target source references item`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/aether")
        val javaDir = projectDir.resolve("src/main/java/example")
        javaDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=aether\n")
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                implementation "maven.modrinth:quark:4.1-480"
            }
        """.trimIndent() + "\n")
        javaDir.resolve("Integration.java").writeText("""
            package example;

            final class Integration {
                static final String ITEM = "quark:seed_pouch";
            }
        """.trimIndent())
        val logFile = tempDir.resolve("referenced-external-creative-duplicate.log")
        logFile.writeText("""
            [07:58:51] [Render thread/WARN] [me.je.li.pl.va.in.ItemStackListFactory/]: 52 duplicate items were found in 'Tools & Utilities' creative tab's: displayItems
            [quark:seed_pouch, quark:pathfinders_quill]
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir)

        assertTrue(
            audit.findings.any { it.contains("duplicate items were found") },
            "Target source references to the item must keep warning fatal"
        )
    }

    @Test
    fun `runtime log audit allowlists source inherited creative tab duplicate only with source evidence`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/example")
        val sourceDir = tempDir.resolve("sources/example")
        writeSourceInheritedCreativeDuplicateEvidence(projectDir, includeSubtypeFix = false)
        writeSourceInheritedCreativeDuplicateEvidence(sourceDir, includeSubtypeFix = false)
        val logFile = tempDir.resolve("example-client-world.log")
        logFile.writeText(sourceInheritedCreativeDuplicateLog())

        assertTrue(projectHasCreativeDuplicateItemSourceShape(projectDir, "example:variant_tool"))
        assertTrue(projectHasCreativeDuplicateItemSourceShape(sourceDir, "example:variant_tool"))
        assertTrue(!projectRegistersJeiSubtypeForItem(projectDir, "example:variant_tool"))
        assertTrue(detectBenchmarkModId(projectDir) == "example")
        assertTrue(creativeTabDuplicateWarning(logFile.readText().lines(), 0)?.itemIds == listOf("example:variant_tool"))
        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir, inputSourceDir = sourceDir)

        assertTrue(audit.findings.isEmpty(), "Expected source-inherited issue to be allowlisted: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("input and converted sources duplicate creative-tab item id(s) example:variant_tool") },
            "Expected allowlist evidence trace, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps source inherited creative tab duplicate warning without evidence`(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("example-client-world.log")
        logFile.writeText(sourceInheritedCreativeDuplicateLog())

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = tempDir.resolve("missing-project"))

        assertTrue(
            audit.findings.any { it.contains("duplicate items were found") },
            "Expected missing evidence to keep warning fatal to the strict gate"
        )
    }

    @Test
    fun `runtime log audit rejects source inherited creative tab duplicate allowlist after subtype fix`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/example")
        val sourceDir = tempDir.resolve("sources/example")
        writeSourceInheritedCreativeDuplicateEvidence(projectDir, includeSubtypeFix = true)
        writeSourceInheritedCreativeDuplicateEvidence(sourceDir, includeSubtypeFix = false)
        val logFile = tempDir.resolve("example-client-world.log")
        logFile.writeText(sourceInheritedCreativeDuplicateLog())

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir, inputSourceDir = sourceDir)

        assertTrue(
            audit.findings.any { it.contains("duplicate items were found") },
            "Expected subtype registration to invalidate the upstream-issue allowlist"
        )
    }

    @Test
    fun `runtime log audit allowlists source inherited missing sound definition`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/example")
        val sourceDir = tempDir.resolve("sources/example")
        writeMissingSoundEvidence(projectDir, includeMissingEventInSoundsJson = false)
        writeMissingSoundEvidence(sourceDir, includeMissingEventInSoundsJson = false)
        val logFile = tempDir.resolve("missing-sound-inherited.log")
        logFile.writeText("""
            [06:14:12] [Render thread/WARN] [minecraft/SoundEngine]: Missing sound for event: example:entity.sentry.ambient
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir, inputSourceDir = sourceDir)

        assertTrue(audit.findings.isEmpty(), "Expected source-inherited missing sound to be allowlisted: ${audit.findings}")
        assertTrue(
            audit.allowedIssues.any { it.contains("input and converted sources declare sound event 'example:entity.sentry.ambient'") },
            "Expected source-inheritance evidence in allowed issues, got: ${audit.allowedIssues}"
        )
    }

    @Test
    fun `runtime log audit keeps missing sound warning when input resource defined it`(@TempDir tempDir: Path) {
        val projectDir = tempDir.resolve("work/example")
        val sourceDir = tempDir.resolve("sources/example")
        writeMissingSoundEvidence(projectDir, includeMissingEventInSoundsJson = false)
        writeMissingSoundEvidence(sourceDir, includeMissingEventInSoundsJson = true)
        val logFile = tempDir.resolve("missing-sound-regression.log")
        logFile.writeText("""
            [06:14:12] [Render thread/WARN] [minecraft/SoundEngine]: Missing sound for event: example:entity.sentry.ambient
        """.trimIndent() + "\n")

        val audit = auditRuntimeLog(logFile, failOnWarnings = true, projectDir = projectDir, inputSourceDir = sourceDir)

        assertTrue(
            audit.findings.any { it.contains("Missing sound for event: example:entity.sentry.ambient") },
            "Porter-lost sound definitions must remain fatal"
        )
    }

    @Test
    fun `process scope matcher recognizes benchmark java command lines`(@TempDir tempDir: Path) {
        val sakuraDir = tempDir.resolve("work/sakura").toAbsolutePath().normalize()
        val hotbathDir = tempDir.resolve("work/hotbath").toAbsolutePath().normalize()
        val commandLine = """
            "C:\Program Files\Java\jdk-21\bin\java.exe"
            -Dfml.modFolders=sakura%%$sakuraDir\build\classes\java\main;sakura%%$sakuraDir\build\resources\main
            -cp $sakuraDir\build\moddev\artifacts\neoforge-21.1.219.jar
            net.neoforged.devlaunch.Main
        """.trimIndent()

        assertTrue(processCommandMatchesScope(commandLine, sakuraDir))
        assertTrue(!processCommandMatchesScope(commandLine, hotbathDir))
    }

    @Test
    fun `benchmark gametest smoke harness is staged only when project has no gametests`(@TempDir tempDir: Path) {
        val projectWithoutTests = tempDir.resolve("without-tests")
        projectWithoutTests.createDirectories()
        projectWithoutTests.resolve("gradle.properties").writeText("mod_id=resmod\n")

        val issues = stageBenchmarkGameTestSmoke(projectWithoutTests)

        assertTrue(issues.isEmpty(), "Expected smoke harness staging to succeed: $issues")
        val stagedSmokeHarness = projectWithoutTests.resolve(
            "src/main/java/com/modporter/generated/resmod/benchmark/ModPorterBenchmarkGameTests.java"
        )
        assertTrue(stagedSmokeHarness.exists())
        assertTrue(!projectWithoutTests.resolve("src/main/java/com/modporter/benchmark/ModPorterBenchmarkGameTests.java").exists())
        assertTrue(projectWithoutTests.resolve("src/main/resources/gameteststructures/modporter_benchmark_empty.snbt").exists())
        assertTrue(
            stagedSmokeHarness.readText().contains("package com.modporter.generated.resmod.benchmark;")
        )
        assertTrue(
            stagedSmokeHarness.readText().contains("""@GameTestHolder("resmod")""")
        )

        val projectWithTests = tempDir.resolve("with-tests")
        val existingTestDir = projectWithTests.resolve("src/main/java/com/example")
        existingTestDir.createDirectories()
        existingTestDir.resolve("ExistingGameTests.java").writeText("""
            package com.example;

            final class ExistingGameTests {
                @GameTest(template = "empty")
                static void existing(Object helper) {
                }
            }
        """.trimIndent())

        val existingIssues = stageBenchmarkGameTestSmoke(projectWithTests)

        assertTrue(existingIssues.isEmpty(), "Expected existing GameTest detection to succeed: $existingIssues")
        assertTrue(!projectWithTests.resolve("src/main/java/com/modporter/benchmark/ModPorterBenchmarkGameTests.java").exists())
        assertTrue(!projectWithTests.resolve("src/main/java/com/modporter/generated/with_tests/benchmark/ModPorterBenchmarkGameTests.java").exists())
    }

    @Test
    fun `benchmark client world harness is staged in per-mod package`(@TempDir tempDir: Path) {
        tempDir.resolve("gradle.properties").writeText("mod_id=resmod\n")
        tempDir.resolve("build.gradle").writeText(
            """
            neoForge {
                runs {
                    clientWorld {
                        client()
                    }
                }
            }
            """.trimIndent()
        )

        val issues = stageClientWorldHarness(tempDir)
        val harness = tempDir.resolve("src/main/java/com/modporter/generated/resmod/benchmark/ModPorterClientWorldHarness.java")

        assertTrue(issues.isEmpty(), "Expected client-world harness staging to succeed: $issues")
        assertTrue(harness.exists())
        assertTrue(!tempDir.resolve("src/main/java/com/modporter/benchmark/ModPorterClientWorldHarness.java").exists())
        assertTrue(harness.readText().contains("package com.modporter.generated.resmod.benchmark;"))
        assertTrue(harness.readText().contains("Creative tab browse complete"))
        assertTrue(harness.readText().contains("tabContainsNamespace(tab, modId)"))
        assertTrue(harness.readText().contains("observeMenuItems(menu, browseRow);"))
        assertTrue(harness.readText().contains("expectedItems = itemKeys(menu.items);"))
        assertTrue(harness.readText().contains("stableMenuItems"))
        assertTrue(!harness.readText().contains("expectedItems = itemKeys(tab.getDisplayItems());"))
        assertTrue(tempDir.resolve("build.gradle").readText().contains("systemProperty 'modporter.benchmark.clientWorld'"))
        assertTrue(tempDir.resolve("build.gradle").readText().contains("systemProperty 'modporter.benchmark.modId', 'resmod'"))
    }

    @Test
    fun `benchmark dependency artifact publish rejects staged runtime harnesses`(@TempDir tempDir: Path) {
        val harness = tempDir.resolve(
            "src/main/java/com/modporter/generated/resmod/benchmark/ModPorterClientWorldHarness.java"
        )
        harness.parent.createDirectories()
        harness.writeText("package com.modporter.generated.resmod.benchmark;\n")

        val result = publishBenchmarkArtifacts(
            BenchmarkCase(
                id = "resmod",
                displayName = "Resource Mod",
                provider = "local",
                location = ".",
                ref = "-",
                subdir = ".",
                required = true
            ),
            outputDir = tempDir,
            artifactRoot = tempDir.resolve("artifacts"),
            reportsDir = tempDir.resolve("reports"),
            timeoutSeconds = 1L
        )

        assertTrue(result.status == CheckStatus.FAIL, "Expected harness source to block artifact publish")
        assertTrue(result.note.contains("runtime harness source"), result.note)
    }

    @Test
    fun `client smoke world staging disables early display for deterministic runtime gate`(@TempDir tempDir: Path) {
        val fixtureWorld = tempDir.resolve("fixture-world")
        fixtureWorld.resolve("region").createDirectories()
        fixtureWorld.resolve("level.dat").writeText("fixture")

        val projectDir = tempDir.resolve("project")
        projectDir.createDirectories()

        val issues = stageClientSmokeWorld(projectDir, fixtureWorld)

        assertTrue(issues.isEmpty(), "Expected smoke world staging to succeed: $issues")
        assertTrue(projectDir.resolve("run/saves/$CLIENT_SMOKE_WORLD/level.dat").exists())
        assertTrue(projectDir.resolve("run/options.txt").readText().contains("onboardAccessibility:false"))
        val fmlConfig = projectDir.resolve("run/config/fml.toml").readText()
        assertTrue(fmlConfig.contains("earlyWindowControl = false"))
        assertTrue(fmlConfig.contains("versionCheck = false"))
        assertTrue(fmlConfig.contains("[dependencyOverrides]"))
    }

    @Test
    fun `converted project validation rejects main source excludes`(@TempDir tempDir: Path) {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modLoader=\"javafml\"\n")
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }

            sourceSets.main.java {
                exclude 'com/example/Broken.java'
            }
            """.trimIndent()
        )

        val issues = validateConvertedProject(tempDir, PipelineResult(emptyList(), dryRun = false))

        assertTrue(
            issues.any { it.contains("Build file excludes main Java sources") },
            "Expected main source excludes to fail converted-project validation; got: $issues"
        )
    }

    @Test
    fun `strict converted project validation rejects skipped structural parsing`(@TempDir tempDir: Path) {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modLoader=\"javafml\"\n")
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }
            """.trimIndent()
        )

        val result = PipelineResult(
            passResults = listOf(
                com.modporter.core.pipeline.PassResult(
                    passName = "Structural Refactor",
                    changes = emptyList(),
                    skipped = listOf("Could not parse src/main/java/com/example/Broken.java")
                )
            ),
            dryRun = false
        )
        val issues = validateConvertedProject(tempDir, result, strictBenchmarkOptions())

        assertTrue(
            issues.any { it.contains("Strict source-shape gate failed") },
            "Expected strict source-shape skipped parsing to fail converted-project validation; got: $issues"
        )
    }

    @Test
    fun `converted project validation rejects invalid recipe holder var generic`(@TempDir tempDir: Path) {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modLoader=\"javafml\"\n")
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }
            """.trimIndent()
        )
        val sourceFile = tempDir.resolve("src/main/java/com/example/Ported.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example;

            import net.minecraft.world.item.crafting.RecipeHolder;

            public class Ported {
                void recipes(Iterable<?> recipes) {
                    for (RecipeHolder<var> recipeHolder : recipes) {
                    }
                }
            }
            """.trimIndent()
        )

        val issues = validateConvertedProject(tempDir, PipelineResult(emptyList(), dryRun = false))

        assertTrue(
            issues.any { it.contains("Converted source contains invalid migration artifacts") },
            "Expected invalid RecipeHolder<var> to fail converted-project validation; got: $issues"
        )
    }

    @Test
    fun `converted project validation rejects public top level type filename mismatch`(@TempDir tempDir: Path) {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modLoader=\"javafml\"\n")
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }
            """.trimIndent()
        )
        val sourceFile = tempDir.resolve("src/main/java/com/example/AetherBlockPathTypes.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example;

            public class AetherPathType {
            }
            """.trimIndent()
        )

        val issues = validateConvertedProject(tempDir, PipelineResult(emptyList(), dryRun = false))

        assertTrue(
            issues.any { it.contains("Converted source contains invalid migration artifacts") && it.contains("public type AetherPathType") },
            "Expected public type/file mismatch to fail converted-project validation; got: $issues"
        )
    }

    @Test
    fun `converted project validation rejects build migration placeholders`(@TempDir tempDir: Path) {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modLoader=\"javafml\"\n")
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }

            dependencies {
                // TODO: Update for NeoForge 1.21.1 - implementation "curse.maven:jade-324717:4631193"
            }
            """.trimIndent()
        )

        val issues = validateConvertedProject(tempDir, PipelineResult(emptyList(), dryRun = false))

        assertTrue(
            issues.any { it.contains("Build files contain migration placeholders") },
            "Expected build migration placeholders to fail converted-project validation; got: $issues"
        )
    }

    @Test
    fun `converted project validation rejects commented out source logic`(@TempDir tempDir: Path) {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modLoader=\"javafml\"\n")
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }
            """.trimIndent()
        )
        val sourceFile = tempDir.resolve("src/main/java/com/example/Ported.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example;

            public class Ported {
                void tick() {
                    // [forge2neo] if (Compat.isLoaded()) { // excluded: compat unavailable
                    runCoreLogic();
                }

                void runCoreLogic() {}
            }
            """.trimIndent()
        )

        val issues = validateConvertedProject(tempDir, PipelineResult(emptyList(), dryRun = false))

        assertTrue(
            issues.any { it.contains("Converted source contains bypassed or commented-out logic") },
            "Expected commented-out source logic to fail converted-project validation; got: $issues"
        )
    }

    @Test
    fun `converted project validation rejects block commented event handlers`(@TempDir tempDir: Path) {
        tempDir.resolve("src/main/resources/META-INF").createDirectories()
        tempDir.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modLoader=\"javafml\"\n")
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'net.neoforged.moddev'
            }
            """.trimIndent()
        )
        val sourceFile = tempDir.resolve("src/main/java/com/example/Ported.java")
        sourceFile.parent.createDirectories()
        sourceFile.writeText(
            """
            package com.example;

            public class Ported {
                /*
                @SubscribeEvent
                public void onBrewingRecipeRegister(RegisterBrewingRecipesEvent event) {
                    event.getBuilder().addRecipe(recipe);
                }
                */

                void runCoreLogic() {}
            }
            """.trimIndent()
        )

        val issues = validateConvertedProject(tempDir, PipelineResult(emptyList(), dryRun = false))

        assertTrue(
            issues.any { it.contains("Converted source contains bypassed or commented-out logic") },
            "Expected block-commented source logic to fail converted-project validation; got: $issues"
        )
    }

    private fun runCase(
        case: BenchmarkCase,
        repoRoot: Path,
        sourceRoot: Path,
        workRoot: Path,
        artifactRoot: Path,
        reportsDir: Path,
        options: BenchmarkOptions,
        publishArtifacts: Boolean,
        clientSmokeWorldFixture: ClientSmokeWorldFixture
    ): BenchmarkOutcome {
        val prepared = prepareSource(case, repoRoot, sourceRoot, reportsDir, options)
        if (prepared.status != null || prepared.path == null) {
            return BenchmarkOutcome(
                case = case,
                source = prepared.sourceLabel,
                outputDir = null,
                status = prepared.status ?: Status.FAIL,
                note = prepared.note
            )
        }

        val outputDir = workRoot.resolve(case.id)
        return try {
            resetDirectory(outputDir, workRoot)
            copyProjectFiltered(prepared.path, outputDir)

            val pipelineResult = runPipeline(outputDir)
            val structureIssues = validateConvertedProject(outputDir, pipelineResult, options)

            val mediumChanges = pipelineResult.passResults.sumOf { it.mediumConfidence }
            val lowChanges = pipelineResult.passResults.sumOf { it.lowConfidence }
            val reviewChanges = mediumChanges + lowChanges
            if (options.handsOff && reviewChanges > 0) {
                structureIssues.add("Hands-off gate failed: $reviewChanges medium/low-confidence changes require review")
            }
            var compile = CheckResult.notRun("MODPORTER_BENCHMARK_COMPILE=false")
            if (structureIssues.isEmpty() && options.compile) {
                structureIssues.addAll(stageBenchmarkDependencies(case, artifactRoot, outputDir))
            }
            if (structureIssues.isEmpty() && options.compile) {
                compile = runGradleTask(outputDir, reportsDir, case.id, "compileJava", options.timeoutSeconds)
            }

            var artifactPublish = CheckResult.notRun("no benchmark dependents")
            if (structureIssues.isEmpty() && options.compile && publishArtifacts && compile.status == CheckStatus.PASS) {
                artifactPublish = publishBenchmarkArtifacts(case, outputDir, artifactRoot, reportsDir, options.timeoutSeconds)
            }

            var runServer = CheckResult.notRun("MODPORTER_BENCHMARK_RUNSERVER=false")
            if (structureIssues.isEmpty() && compile.passedOrNotRun && artifactPublish.passedOrNotRun && options.runServer) {
                prepareDedicatedServerRuntime(outputDir)
                runServer = runRuntimeGradleTask(
                    outputDir,
                    reportsDir,
                    case.id,
                    "runServer",
                    options.timeoutSeconds,
                    RuntimeLogPolicy.serverReady(options.logClean),
                    prepared.path
                )
            }

            var runGameTestServer = CheckResult.notRun("MODPORTER_BENCHMARK_RUNGAMETESTSERVER=false")
            if (structureIssues.isEmpty() && compile.passedOrNotRun && artifactPublish.passedOrNotRun &&
                runServer.passedOrNotRun && options.runGameTestServer) {
                structureIssues.addAll(stageBenchmarkGameTestSmoke(outputDir))
            }
            if (structureIssues.isEmpty() && compile.passedOrNotRun && artifactPublish.passedOrNotRun &&
                runServer.passedOrNotRun && options.runGameTestServer) {
                prepareDedicatedServerRuntime(outputDir)
                runGameTestServer = runRuntimeGradleTask(
                    outputDir,
                    reportsDir,
                    case.id,
                    "runGameTestServer",
                    options.timeoutSeconds,
                    RuntimeLogPolicy.gameTestServer(options.logClean),
                    prepared.path
                )
            }

            var runClient = CheckResult.notRun("MODPORTER_BENCHMARK_RUNCLIENT=false")
            if (structureIssues.isEmpty() && compile.passedOrNotRun && artifactPublish.passedOrNotRun &&
                runServer.passedOrNotRun && runGameTestServer.passedOrNotRun && options.runClient) {
                runClient = runRuntimeGradleTask(
                    outputDir,
                    reportsDir,
                    case.id,
                    "runClient",
                    options.timeoutSeconds,
                    RuntimeLogPolicy.clientStart(options.logClean),
                    prepared.path
                )
            }

            var runClientWorld = CheckResult.notRun("MODPORTER_BENCHMARK_RUNCLIENTWORLD=false")
            if (structureIssues.isEmpty() && compile.passedOrNotRun && artifactPublish.passedOrNotRun &&
                runServer.passedOrNotRun && runGameTestServer.passedOrNotRun && runClient.passedOrNotRun &&
                options.runClientWorld) {
                if (clientSmokeWorldFixture.result.status == CheckStatus.FAIL || clientSmokeWorldFixture.path == null) {
                    runClientWorld = CheckResult(CheckStatus.FAIL, "Client smoke world fixture failed: ${clientSmokeWorldFixture.result.note}")
                } else {
                    val harnessIssues = stageClientWorldHarness(outputDir)
                    if (harnessIssues.isNotEmpty()) {
                        runClientWorld = CheckResult(CheckStatus.FAIL, harnessIssues.joinToString("; "))
                    } else {
                        val worldIssues = stageClientSmokeWorld(outputDir, clientSmokeWorldFixture.path)
                        if (worldIssues.isNotEmpty()) {
                            runClientWorld = CheckResult(CheckStatus.FAIL, worldIssues.joinToString("; "))
                        } else {
                            runClientWorld = runRuntimeGradleTask(
                                outputDir,
                                reportsDir,
                                case.id,
                                "runClientWorld",
                                options.timeoutSeconds,
                                RuntimeLogPolicy.clientWorld(options.logClean, options.progressGraceSeconds),
                                prepared.path
                            )
                        }
                    }
                }
            }

            val failedChecks = mutableListOf<String>()
            failedChecks.addAll(structureIssues)
            if (compile.status == CheckStatus.FAIL) failedChecks.add("compileJava failed: ${compile.note}")
            if (artifactPublish.status == CheckStatus.FAIL) {
                failedChecks.add("benchmark artifact publish failed: ${artifactPublish.note}")
            }
            if (runServer.status == CheckStatus.FAIL) failedChecks.add("runServer failed: ${runServer.note}")
            if (runGameTestServer.status == CheckStatus.FAIL) {
                failedChecks.add("runGameTestServer failed: ${runGameTestServer.note}")
            }
            if (runClient.status == CheckStatus.FAIL) failedChecks.add("runClient failed: ${runClient.note}")
            if (runClientWorld.status == CheckStatus.FAIL) {
                failedChecks.add("runClientWorld failed: ${runClientWorld.note}")
            }

            val allowedRuntimeIssues = listOf(runServer, runGameTestServer, runClient, runClientWorld)
                .mapNotNull { it.allowedRuntimeIssueNote() }
            val status = if (failedChecks.isEmpty()) Status.PASS else Status.FAIL
            val note = if (failedChecks.isEmpty()) {
                if (allowedRuntimeIssues.isEmpty()) "OK" else "OK; ${allowedRuntimeIssues.joinToString("; ")}"
            } else {
                failedChecks.joinToString("; ")
            }

            BenchmarkOutcome(
                case = case,
                source = prepared.sourceLabel,
                outputDir = outputDir.toString(),
                status = status,
                note = note,
                pipelineResult = pipelineResult,
                compile = compile,
                runServer = runServer,
                runGameTestServer = runGameTestServer,
                runClient = runClient,
                runClientWorld = runClientWorld
            )
        } finally {
            terminateScopedJavaProcesses(outputDir)
        }
    }

    private fun prepareSource(
        case: BenchmarkCase,
        repoRoot: Path,
        sourceRoot: Path,
        reportsDir: Path,
        options: BenchmarkOptions
    ): PreparedSource {
        val localOverride = System.getenv(localSourceOverrideKey(case.id))?.takeIf { it.isNotBlank() }
        if (localOverride != null) {
            return prepareLocalSource(case, repoRoot, localOverride, "env:${localSourceOverrideKey(case.id)}")
        }

        val gitOverride = System.getenv(gitOverrideKey(case.id))?.takeIf { it.isNotBlank() }
        if (gitOverride != null) {
            val ref = System.getenv(refOverrideKey(case.id))?.takeIf { it.isNotBlank() } ?: case.ref
            return prepareGitSource(case, gitOverride, ref, sourceRoot, reportsDir, options.timeoutSeconds)
        }

        return when (case.provider) {
            "local" -> prepareLocalSource(case, repoRoot, case.location, "local:${case.location}")
            "git" -> prepareGitSource(case, case.location, case.ref, sourceRoot, reportsDir, options.timeoutSeconds)
            "missing" -> {
                val status = if (case.required) Status.FAIL else Status.SKIP
                PreparedSource(null, "missing:${case.id}", status, "No source provider configured")
            }
            else -> PreparedSource(null, case.provider, Status.FAIL, "Unknown source provider '${case.provider}'")
        }
    }

    private fun prepareLocalSource(
        case: BenchmarkCase,
        repoRoot: Path,
        rawPath: String,
        label: String
    ): PreparedSource {
        val path = Path.of(rawPath)
        val source = if (path.isAbsolute) path.normalize() else repoRoot.resolve(path).normalize()
        if (!source.exists() || !source.isDirectory()) {
            val status = if (case.required) Status.FAIL else Status.SKIP
            return PreparedSource(null, label, status, "Source missing: $source")
        }
        val subdir = resolveSubdir(source, case.subdir)
        return if (subdir.exists() && subdir.isDirectory()) {
            PreparedSource(subdir, label, null, "OK")
        } else {
            PreparedSource(null, label, Status.FAIL, "Configured subdir missing: $subdir")
        }
    }

    private fun prepareGitSource(
        case: BenchmarkCase,
        url: String,
        ref: String,
        sourceRoot: Path,
        reportsDir: Path,
        timeoutSeconds: Long
    ): PreparedSource {
        val cloneDir = sourceRoot.resolve(case.id)
        resetDirectory(cloneDir, sourceRoot)
        val logFile = reportsDir.resolve("${case.id}-fetch.log")
        val command = mutableListOf("git", "clone", "--depth", "1")
        if (ref != "-") command.addAll(listOf("--branch", ref))
        command.addAll(listOf(url, cloneDir.toString()))

        val result = runProcess(command, sourceRoot, logFile, timeoutSeconds)
        if (result.status != CheckStatus.PASS) {
            return PreparedSource(
                path = null,
                sourceLabel = "git:$url#$ref",
                status = Status.FAIL,
                note = "git clone failed: ${result.note}"
            )
        }

        val subdir = resolveSubdir(cloneDir, case.subdir)
        return if (subdir.exists() && subdir.isDirectory()) {
            PreparedSource(subdir, "git:$url#$ref", null, "OK")
        } else {
            PreparedSource(null, "git:$url#$ref", Status.FAIL, "Configured subdir missing: $subdir")
        }
    }

    private fun resolveSubdir(root: Path, subdir: String): Path =
        if (subdir == "." || subdir == "-") root else root.resolve(subdir).normalize()

    private fun runPipeline(projectDir: Path): PipelineResult {
        val pipelineDef = PipelineRegistry.get("forge2neo")
            ?: error("forge2neo pipeline is not registered")
        val mappingDb = MappingDatabase.load(pipelineDef.mappingsPrefix)
        return Pipeline(
            passes = pipelineDef.passFactory(mappingDb, PipelineOptions()),
            dryRun = false,
            pipelineName = pipelineDef.displayName
        ).run(projectDir)
    }

    private fun validateConvertedProject(
        projectDir: Path,
        result: PipelineResult,
        options: BenchmarkOptions = BenchmarkOptions.fromEnvironment()
    ): MutableList<String> {
        val issues = mutableListOf<String>()
        if (result.totalErrors > 0) {
            issues.add("Pipeline reported ${result.totalErrors} errors")
        }
        if (options.strict || options.handsOff) {
            val skippedTransforms = result.passResults.flatMap { passResult ->
                passResult.skipped.map { skipped -> "${passResult.passName}: $skipped" }
            }
            if (skippedTransforms.isNotEmpty()) {
                issues.add(
                    "Strict source-shape gate failed: migration passes skipped parsing/transforms " +
                        "(${skippedTransforms.size}): ${skippedTransforms.take(8).joinToString(", ")}"
                )
            }
        }

        val srcDir = projectDir.resolve("src")
        if (srcDir.exists()) {
            val forgeHits = findActiveForgeReferences(srcDir)
            if (forgeHits.isNotEmpty()) {
                issues.add("Active Forge source references remain: ${forgeHits.take(5).joinToString(", ")}")
            }
            val bypassMarkers = findCommentedBypassMarkers(projectDir.resolve("src/main/java"))
            if (bypassMarkers.isNotEmpty()) {
                issues.add(
                    "Converted source contains bypassed or commented-out logic (${bypassMarkers.size}): " +
                        bypassMarkers.take(8).joinToString(", ")
                )
            }
            val invalidMigrationArtifacts = findInvalidSourceMigrationArtifacts(projectDir.resolve("src/main/java"))
            if (invalidMigrationArtifacts.isNotEmpty()) {
                issues.add(
                    "Converted source contains invalid migration artifacts (${invalidMigrationArtifacts.size}): " +
                        invalidMigrationArtifacts.take(8).joinToString(", ")
                )
            }
        }

        val buildFiles = listOf(projectDir.resolve("build.gradle"), projectDir.resolve("build.gradle.kts"))
            .filter { it.exists() }
        if (buildFiles.isEmpty()) {
            issues.add("No build.gradle or build.gradle.kts after conversion")
        } else {
            val buildPlaceholders = findBuildMigrationPlaceholders(projectDir, buildFiles)
            if (buildPlaceholders.isNotEmpty()) {
                issues.add(
                    "Build files contain migration placeholders (${buildPlaceholders.size}): " +
                        buildPlaceholders.take(8).joinToString(", ")
                )
            }
            val activeBuild = buildFiles.joinToString("\n") { activeCode(it.readText()) }
            if (!activeBuild.contains("net.neoforged.moddev") && !activeBuild.contains("neoForge")) {
                issues.add("Build file does not reference NeoForge ModDev")
            }
            if (activeBuild.contains("net.minecraftforge.gradle") || activeBuild.contains("net.minecraftforge:forge")) {
                issues.add("Build file still references ForgeGradle/Forge dependency")
            }
            val excludedMainSources = mainSourceExcludes(activeBuild)
            if (excludedMainSources.isNotEmpty()) {
                issues.add(
                    "Build file excludes main Java sources (${excludedMainSources.size}): " +
                        excludedMainSources.take(8).joinToString(", ")
                )
            }
        }

        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        val oldModsToml = metaInf.resolve("mods.toml")
        val neoModsToml = metaInf.resolve("neoforge.mods.toml")
        if (oldModsToml.exists()) {
            issues.add("mods.toml was not renamed to neoforge.mods.toml")
        }
        if (metaInf.exists() && !neoModsToml.exists()) {
            issues.add("neoforge.mods.toml is missing")
        }

        return issues
    }

    private fun mainSourceExcludes(buildText: String): List<String> =
        Regex("""exclude\s+['"]([^'"]+\.java|[^'"]+/\*\*)['"]""")
            .findAll(buildText)
            .map { it.groupValues[1] }
            .filterNot { it.startsWith("references/") }
            .distinct()
            .toList()

    private fun findActiveForgeReferences(srcDir: Path): List<String> {
        val patterns = listOf(
            "net.minecraftforge" to Regex("""\bnet\.minecraftforge\b"""),
            "MinecraftForge." to Regex("""\bMinecraftForge\."""),
            "ForgeRegistries." to Regex("""(?<!Neo)\bForgeRegistries\.""")
        )
        val hits = mutableListOf<String>()
        val stream = Files.walk(srcDir)
        try {
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .filter { !srcDir.relativize(it).toString().replace('\\', '/').startsWith("references/") }
                .forEach { file ->
                    val active = activeCode(file.readText())
                    for ((label, pattern) in patterns) {
                        if (pattern.containsMatchIn(active)) {
                            hits.add("${srcDir.relativize(file)} contains $label")
                        }
                    }
                }
        } finally {
            stream.close()
        }
        return hits
    }

    private fun findCommentedBypassMarkers(srcDir: Path): List<String> {
        if (!srcDir.exists()) return emptyList()
        val markerPatterns = listOf(
            Regex("""//\s*\[forge2neo]""", RegexOption.IGNORE_CASE),
            Regex("""//\s*TODO:\s*\[forge2neo]""", RegexOption.IGNORE_CASE),
            Regex("""//\s*TODO:\s*(NetworkHooks|DeserializationContext|BowlFoodItem|MissingMappingsEvent|IIngredientSerializer|GuiOverlayManager|RenderGuiOverlayEvent).*removed""", RegexOption.IGNORE_CASE),
            Regex("""/\*\s*TODO:\s*[^*]*(removed|forge2neo)[^*]*\*/""", RegexOption.IGNORE_CASE),
            Regex("""//\s*Phase check removed""", RegexOption.IGNORE_CASE),
            Regex("""//\s*\[forge2neo].*(excluded|unavailable|fallback|SimpleChannel removed|Phase check removed|REMOVED|TODO)""", RegexOption.IGNORE_CASE),
            Regex("""//\s*\[forge2neo]\s*(import|return|if|for|while|switch|try|catch|@Override|\w+\s*\()""")
        )
        val hits = mutableListOf<String>()
        val stream = Files.walk(srcDir)
        try {
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .filter { !srcDir.relativize(it).toString().replace('\\', '/').startsWith("references/") }
                .forEach { file ->
                    val text = file.readText()
                    Regex("""(?s)/\*.*?\*/""").findAll(text).forEach { block ->
                        val codeLikeBypass = Regex("""@\s*SubscribeEvent|\[forge2neo]|BrewingRecipesEvent|BrewingRecipeRegister""")
                            .containsMatchIn(block.value) &&
                            Regex("""\b(public|private|protected|if|return|event\.|import)\b""")
                                .containsMatchIn(block.value)
                        if (codeLikeBypass) {
                            val lineNumber = text.substring(0, block.range.first).count { it == '\n' } + 1
                            hits.add("${srcDir.relativize(file)}:$lineNumber")
                        }
                    }
                    text.lines().forEachIndexed { index, line ->
                        if (markerPatterns.any { it.containsMatchIn(line) }) {
                            hits.add("${srcDir.relativize(file)}:${index + 1}")
                        }
                    }
                }
        } finally {
            stream.close()
        }
        return hits
    }

    private fun findInvalidSourceMigrationArtifacts(srcDir: Path): List<String> {
        if (!srcDir.exists()) return emptyList()
        val markerPatterns = listOf(
            "RecipeHolder<var>" to Regex("""\bRecipeHolder\s*<\s*var\s*>""")
        )
        val hits = mutableListOf<String>()
        val stream = Files.walk(srcDir)
        try {
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .filter { !srcDir.relativize(it).toString().replace('\\', '/').startsWith("references/") }
                .forEach { file ->
                    val active = activeCode(file.readText())
                    for ((label, pattern) in markerPatterns) {
                        pattern.findAll(active).forEach { match ->
                            val lineNumber = active.substring(0, match.range.first).count { it == '\n' } + 1
                            hits.add("${srcDir.relativize(file)}:$lineNumber contains $label")
                        }
                    }
                    active.lines().forEachIndexed { index, line ->
                        val match = Regex("""^public\s+(?:abstract\s+|final\s+)?(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)\b""")
                            .find(line)
                            ?: return@forEachIndexed
                        val publicType = match.groupValues[1]
                        val fileStem = file.fileName.toString().substringBeforeLast('.')
                        if (publicType != fileStem) {
                            hits.add("${srcDir.relativize(file)}:${index + 1} public type $publicType does not match file $fileStem")
                        }
                    }
                }
        } finally {
            stream.close()
        }
        return hits
    }

    private fun findBuildMigrationPlaceholders(projectDir: Path, buildFiles: List<Path>): List<String> {
        val markerPatterns = listOf(
            Regex("""TODO:\s*Update for NeoForge""", RegexOption.IGNORE_CASE),
            Regex("""\[(?:forge2neo|modporter)\]""", RegexOption.IGNORE_CASE),
            Regex("""//\s*(implementation|compileOnly|runtimeOnly|annotationProcessor)\b.*\b(excluded|unavailable|removed)\b""", RegexOption.IGNORE_CASE)
        )
        return buildFiles.flatMap { file ->
            file.readText().lines().mapIndexedNotNull { index, line ->
                if (markerPatterns.any { it.containsMatchIn(line) }) {
                    "${projectDir.relativize(file)}:${index + 1}"
                } else {
                    null
                }
            }
        }
    }

    private fun activeCode(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), "")
            .lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*")
            }
            .joinToString("\n")

    private fun runGradleTask(
        projectDir: Path,
        reportsDir: Path,
        caseId: String,
        task: String,
        timeoutSeconds: Long
    ): CheckResult {
        val command = gradleCommand(projectDir)
            ?: return CheckResult(CheckStatus.FAIL, "No Gradle wrapper found in converted output")
        val logFile = reportsDir.resolve("$caseId-$task.log")

        return runProcess(
            command + listOf(task, "--no-daemon", "--stacktrace"),
            projectDir,
            logFile,
            timeoutSeconds,
            scopedProcessDir = projectDir
        )
    }

    private fun runRuntimeGradleTask(
        projectDir: Path,
        reportsDir: Path,
        caseId: String,
        task: String,
        timeoutSeconds: Long,
        runtimeLogPolicy: RuntimeLogPolicy,
        inputSourceDir: Path? = null
    ): CheckResult {
        val command = gradleCommand(projectDir)
            ?: return CheckResult(CheckStatus.FAIL, "No Gradle wrapper found in converted output")
        val logFile = reportsDir.resolve("$caseId-$task.log")
        return runRuntimeProcess(
            command + listOf(task, "--no-daemon", "--stacktrace"),
            projectDir,
            logFile,
            timeoutSeconds,
            runtimeLogPolicy,
            inputSourceDir
        )
    }

    private fun runProcess(
        command: List<String>,
        workingDir: Path,
        logFile: Path,
        timeoutSeconds: Long,
        scopedProcessDir: Path? = null
    ): CheckResult {
        logFile.parent?.createDirectories()
        val processBuilder = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
        configureJavaHome(processBuilder)

        val process = processBuilder.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            terminateProcessTree(process, forcibly = true)
            val terminated = scopedProcessDir?.let(::terminateScopedJavaProcesses).orEmpty()
            return CheckResult(
                CheckStatus.FAIL,
                "Timed out after ${timeoutSeconds}s; log=$logFile${scopedCleanupNote(terminated)}"
            )
        }
        settleRuntimeProcess(process)
        val terminated = scopedProcessDir?.let(::terminateScopedJavaProcesses).orEmpty()

        val exitCode = process.exitValue()
        return if (exitCode == 0) {
            CheckResult(CheckStatus.PASS, "Passed; log=$logFile${scopedCleanupNote(terminated)}")
        } else {
            CheckResult(
                CheckStatus.FAIL,
                "Exited $exitCode; log=$logFile${scopedCleanupNote(terminated)}; tail=${logTail(logFile)}"
            )
        }
    }

    private fun runRuntimeProcess(
        command: List<String>,
        workingDir: Path,
        logFile: Path,
        timeoutSeconds: Long,
        runtimeLogPolicy: RuntimeLogPolicy,
        inputSourceDir: Path? = null
    ): CheckResult {
        logFile.parent?.createDirectories()
        Files.deleteIfExists(logFile)
        val processBuilder = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
        configureJavaHome(processBuilder)

        val process = processBuilder.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var ready = false
        var stopSent = false
        var progressReachedAt: Long? = null
        var progressTimeoutTriggered = false
        var terminateRequestedAt: Long? = null
        var runtimeControlError: String? = null
        var scopedCleanup = emptyList<Long>()

        while (System.nanoTime() < deadline) {
            val now = System.nanoTime()
            if (!ready && runtimeLogPolicy.ready(logFile)) {
                ready = true
                if (runtimeLogPolicy.stopCommand != null) {
                    try {
                        process.outputStream.bufferedWriter().use { writer ->
                            writer.write(runtimeLogPolicy.stopCommand)
                            writer.newLine()
                            writer.flush()
                        }
                        stopSent = true
                    } catch (ex: Exception) {
                        runtimeControlError = "Failed to send runtime stop command: ${ex.message}"
                        terminateProcessTree(process, forcibly = false)
                    }
                } else if (runtimeLogPolicy.terminateAfterReady) {
                    terminateProcessTree(process, forcibly = false)
                    terminateRequestedAt = now
                }
            } else if (!ready && progressReachedAt == null && runtimeLogPolicy.progress(logFile)) {
                progressReachedAt = now
            }

            val progressAt = progressReachedAt
            val progressGrace = runtimeLogPolicy.progressGraceSeconds
            if (!ready && !progressTimeoutTriggered && progressAt != null && progressGrace != null &&
                now - progressAt > TimeUnit.SECONDS.toNanos(progressGrace)
            ) {
                progressTimeoutTriggered = true
                terminateProcessTree(process, forcibly = false)
                terminateRequestedAt = now
            }

            if (!process.isAlive) break
            val terminateAt = terminateRequestedAt
            if (terminateAt != null && System.nanoTime() - terminateAt > TimeUnit.SECONDS.toNanos(20)) {
                terminateProcessTree(process, forcibly = true)
                process.waitFor(5, TimeUnit.SECONDS)
                break
            }
            Thread.sleep(500)
        }

        if (process.isAlive) {
            terminateProcessTree(process, forcibly = true)
            process.waitFor(5, TimeUnit.SECONDS)
            scopedCleanup = terminateScopedJavaProcesses(workingDir)
            if (process.isAlive || !runtimeLogPolicy.terminateAfterReady || !ready) {
                settleRuntimeProcess(process)
                return CheckResult(
                    CheckStatus.FAIL,
                    "Timed out after ${timeoutSeconds}s; ready=$ready; stopSent=$stopSent; " +
                        "log=$logFile${scopedCleanupNote(scopedCleanup)}; tail=${logTail(logFile)}"
                )
            }
        }
        settleRuntimeProcess(process)
        scopedCleanup = (scopedCleanup + terminateScopedJavaProcesses(workingDir)).distinct()

        if (runtimeControlError != null) {
            return CheckResult(
                CheckStatus.FAIL,
                "$runtimeControlError; log=$logFile${scopedCleanupNote(scopedCleanup)}; tail=${logTail(logFile)}"
            )
        }

        val audit = auditRuntimeLog(logFile, runtimeLogPolicy.failOnWarnings, workingDir, inputSourceDir)
        if (audit.findings.isNotEmpty()) {
            return CheckResult(
                CheckStatus.FAIL,
                "Runtime log gate failed; log=$logFile${scopedCleanupNote(scopedCleanup)}; " +
                    "findings=${audit.findings.joinToString(" | ")}"
            )
        }

        val missingReady = runtimeLogPolicy.missingReadyChecks(logFile)
        if (missingReady.isNotEmpty()) {
            val progressNote = if (progressTimeoutTriggered) {
                " after progress gate '${runtimeLogPolicy.progressLabel}' waited ${runtimeLogPolicy.progressGraceSeconds}s"
            } else {
                ""
            }
            return CheckResult(
                CheckStatus.FAIL,
                "Runtime did not reach required marker(s)$progressNote: ${missingReady.joinToString(", ")}; " +
                    "log=$logFile${scopedCleanupNote(scopedCleanup)}; tail=${logTail(logFile)}"
            )
        }

        val missingShutdown = runtimeLogPolicy.missingShutdownChecks(logFile)
        if (missingShutdown.isNotEmpty()) {
            return CheckResult(
                CheckStatus.FAIL,
                "Runtime did not reach shutdown marker(s): ${missingShutdown.joinToString(", ")}; " +
                    "log=$logFile${scopedCleanupNote(scopedCleanup)}; tail=${logTail(logFile)}"
            )
        }

        val exitCode = process.exitValue()
        if (exitCode != 0 && runtimeLogPolicy.requireZeroExit) {
            return CheckResult(
                CheckStatus.FAIL,
                "Exited $exitCode; log=$logFile${scopedCleanupNote(scopedCleanup)}; tail=${logTail(logFile)}"
            )
        }

        val allowedIssueNote = if (audit.allowedIssues.isEmpty()) {
            ""
        } else {
            "; allowedRuntimeIssues=${audit.allowedIssues.joinToString(" | ")}"
        }
        return CheckResult(
            CheckStatus.PASS,
            "Runtime gate passed; log=$logFile${scopedCleanupNote(scopedCleanup)}$allowedIssueNote"
        )
    }

    private fun settleRuntimeProcess(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }

    private fun stalledClientWorldCommand(tempDir: Path): List<String> {
        val lines = listOf(
            "[Render thread/INFO] [minecraft/ReloadableResourceManager]: Reloading ResourceManager: vanilla, mod_resources",
            "[Render thread/INFO] [mojang/Library]: OpenAL initialized on device Test",
            "[Render thread/INFO] [minecraft/SoundEngine]: Sound engine started"
        )
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return if (isWindows) {
            val script = tempDir.resolve("stall-client-world.ps1")
            script.writeText(
                lines.joinToString(System.lineSeparator()) { "Write-Output '$it'" } +
                    System.lineSeparator() +
                    "Start-Sleep -Seconds 20" +
                    System.lineSeparator()
            )
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
        } else {
            val script = tempDir.resolve("stall-client-world.sh")
            script.writeText(
                "#!/bin/sh\n" +
                    lines.joinToString("\n") { "printf '%s\\n' '$it'" } +
                    "\nsleep 20\n"
            )
            listOf("sh", script.toString())
        }
    }

    private fun sourceInheritedCreativeDuplicateLog(): String = """
        [Render thread/WARN] [net.neoforged.neoforge.common.CreativeModeTabRegistry/]: duplicate items were found in 'Example Equipment' creative tab's: displayItems
        [Render thread/WARN] [net.neoforged.neoforge.common.CreativeModeTabRegistry/]: [example:variant_tool]
    """.trimIndent() + "\n"

    private fun writeSourceInheritedCreativeDuplicateEvidence(projectDir: Path, includeSubtypeFix: Boolean) {
        val sourceRoot = projectDir.resolve("src/main/java")
        val initDir = sourceRoot.resolve("example/init")
        val jeiDir = sourceRoot.resolve("example/compat/jei")
        initDir.createDirectories()
        jeiDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=example\n")

        initDir.resolve("ExampleCreativeTabs.java").writeText("""
            package example.init;

            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.CreativeModeTab;
            import net.minecraft.world.item.ItemStack;

            public class ExampleCreativeTabs {
                public static final String EQUIPMENT = "Example Equipment";

                private static void createVariantToolOutputs(CreativeModeTab.Output output) {
                    output.accept(ExampleItems.VARIANT_TOOL.get());
                    ItemStack namedTool = new ItemStack(ExampleItems.VARIANT_TOOL.get());
                    namedTool.setHoverName(Component.translatable("item.example.variant_tool.desc"));
                    output.accept(namedTool);
                }
            }
        """.trimIndent())

        val jeiBody = if (includeSubtypeFix) {
            """
            package example.compat.jei;

            import mezz.jei.api.IModPlugin;
            import mezz.jei.api.registration.ISubtypeRegistration;
            import example.compat.jei.subtype.VariantToolSubtypeInterpreter;
            import example.init.ExampleItems;

            public class ExampleJeiCompat implements IModPlugin {
                @Override
                public void registerItemSubtypes(ISubtypeRegistration registration) {
                    registration.registerSubtypeInterpreter(ExampleItems.VARIANT_TOOL.get(), VariantToolSubtypeInterpreter.INSTANCE);
                }
            }
            """.trimIndent()
        } else {
            """
            package example.compat.jei;

            import mezz.jei.api.IModPlugin;
            import mezz.jei.api.registration.IRecipeTransferRegistration;

            public class ExampleJeiCompat implements IModPlugin {
                @Override
                public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
                }
            }
            """.trimIndent()
        }
        jeiDir.resolve("ExampleJeiCompat.java").writeText(jeiBody)
    }

    private fun writeMissingSoundEvidence(projectDir: Path, includeMissingEventInSoundsJson: Boolean) {
        val javaDir = projectDir.resolve("src/main/java/example")
        val resourceDir = projectDir.resolve("src/generated/resources/assets/example")
        javaDir.createDirectories()
        resourceDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=example\n")
        javaDir.resolve("ExampleSoundEvents.java").writeText("""
            package example;

            import net.minecraft.sounds.SoundEvent;

            public final class ExampleSoundEvents {
                public static final SoundEvent SENTRY_AMBIENT = register("entity.sentry.ambient");

                private static SoundEvent register(String id) {
                    return null;
                }
            }
        """.trimIndent() + "\n")
        val soundsJson = if (includeMissingEventInSoundsJson) {
            """
            {
              "entity.sentry.ambient": {
                "sounds": [
                  "example:entity/sentry/ambient"
                ]
              },
              "entity.sentry.hurt": {
                "sounds": [
                  "example:entity/sentry/hurt"
                ]
              }
            }
            """.trimIndent()
        } else {
            """
            {
              "entity.sentry.hurt": {
                "sounds": [
                  "example:entity/sentry/hurt"
                ]
              }
            }
            """.trimIndent()
        }
        resourceDir.resolve("sounds.json").writeText(soundsJson + "\n")
    }

    private fun terminateProcessTree(process: Process, forcibly: Boolean) {
        val descendants = process.toHandle().descendants().iterator().asSequence().toList().asReversed()
        for (handle in descendants) {
            if (forcibly) {
                handle.destroyForcibly()
            } else {
                handle.destroy()
            }
        }
        for (handle in descendants) {
            runCatching { handle.onExit().get(2, TimeUnit.SECONDS) }
        }
        if (forcibly) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
        runCatching { process.toHandle().onExit().get(2, TimeUnit.SECONDS) }
    }

    private fun terminateScopedJavaProcesses(scopeDir: Path): List<Long> {
        val handles = scopedJavaProcesses(scopeDir)
        if (handles.isEmpty()) return emptyList()

        for (handle in handles.asReversed()) {
            handle.destroy()
        }
        for (handle in handles) {
            runCatching { handle.onExit().get(2, TimeUnit.SECONDS) }
        }

        val survivors = handles.filter { it.isAlive }
        for (handle in survivors.asReversed()) {
            handle.destroyForcibly()
        }
        for (handle in survivors) {
            runCatching { handle.onExit().get(2, TimeUnit.SECONDS) }
        }

        return handles.map { it.pid() }
    }

    private fun scopedJavaProcesses(scopeDir: Path): List<ProcessHandle> {
        val currentPid = ProcessHandle.current().pid()
        val stream = ProcessHandle.allProcesses()
        val handles = try {
            stream.iterator().asSequence()
                .filter { it.pid() != currentPid }
                .filter { processHandleMatchesScope(it, scopeDir) }
                .toList()
        } finally {
            stream.close()
        }
        val windowsHandles = if (isWindows()) windowsScopedJavaProcesses(scopeDir, currentPid) else emptyList()
        return (handles + windowsHandles).distinctBy { it.pid() }
    }

    private fun processHandleMatchesScope(handle: ProcessHandle, scopeDir: Path): Boolean {
        val info = handle.info()
        val commandText = buildString {
            info.command().ifPresent { append(it).append(' ') }
            info.commandLine().ifPresent { append(it).append(' ') }
            info.arguments().ifPresent { args -> append(args.joinToString(" ")) }
        }
        if (!commandText.contains("java", ignoreCase = true)) return false
        return processCommandMatchesScope(commandText, scopeDir)
    }

    private fun processCommandMatchesScope(commandText: String, scopeDir: Path): Boolean {
        val normalizedCommand = normalizeProcessPathText(commandText)
        return scopePathCandidates(scopeDir).any { scope ->
            scope.isNotBlank() && normalizedCommand.contains(scope)
        }
    }

    private fun scopePathCandidates(scopeDir: Path): Set<String> =
        buildSet {
            add(normalizeProcessPathText(scopeDir.toAbsolutePath().normalize().toString()))
            runCatching {
                add(normalizeProcessPathText(scopeDir.toRealPath().toString()))
            }
        }

    private fun normalizeProcessPathText(text: String): String =
        text.replace('\\', '/').lowercase()

    private fun scopedCleanupNote(pids: List<Long>): String =
        if (pids.isEmpty()) "" else "; terminated scoped java pids=${pids.joinToString(",")}"

    private fun windowsScopedJavaProcesses(scopeDir: Path, currentPid: Long): List<ProcessHandle> {
        val scopeLiterals = scopePathCandidates(scopeDir)
            .filter { it.isNotBlank() }
            .joinToString(",") { "'${it.replace("'", "''")}'" }
        if (scopeLiterals.isBlank()) return emptyList()

        val script = """
            ${'$'}scopes = @($scopeLiterals)
            Get-CimInstance Win32_Process |
                Where-Object { ${'$'}_.Name -like 'java*' -and ${'$'}_.CommandLine } |
                ForEach-Object {
                    ${'$'}cmd = ${'$'}_.CommandLine.Replace('\', '/').ToLowerInvariant()
                    foreach (${ '$' }scope in ${'$'}scopes) {
                        if (${ '$' }scope -and ${'$'}cmd.Contains(${ '$' }scope)) {
                            ${'$'}_.ProcessId
                            break
                        }
                    }
                }
        """.trimIndent()

        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return emptyList()
        }
        if (process.exitValue() != 0) return emptyList()

        return process.inputStream.bufferedReader().readLines()
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it != currentPid }
            .mapNotNull { pid -> ProcessHandle.of(pid).orElse(null) }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun stageBenchmarkDependencies(
        case: BenchmarkCase,
        artifactRoot: Path,
        outputDir: Path
    ): List<String> {
        if (case.dependencies.isEmpty()) return emptyList()

        val issues = mutableListOf<String>()
        val libsDir = outputDir.resolve("libs")
        libsDir.createDirectories()

        for (dependencyId in case.dependencies) {
            val dependencyDir = artifactRoot.resolve(dependencyId)
            if (!dependencyDir.exists() || !dependencyDir.isDirectory()) {
                issues.add("Benchmark dependency '$dependencyId' was not published before ${case.id}")
                continue
            }

            val jars = listJarFiles(dependencyDir)
            if (jars.isEmpty()) {
                issues.add("Benchmark dependency '$dependencyId' published no jar files")
                continue
            }

            for (jar in jars) {
                Files.copy(jar, libsDir.resolve(jar.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
            }

            val primaryJar = preferredRuntimeJar(jars)
            for (alias in dependencyJarAliases(outputDir, dependencyId)) {
                Files.copy(primaryJar, libsDir.resolve(alias), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        return issues
    }

    private fun publishBenchmarkArtifacts(
        case: BenchmarkCase,
        outputDir: Path,
        artifactRoot: Path,
        reportsDir: Path,
        timeoutSeconds: Long
    ): CheckResult {
        val harnessSources = findBenchmarkHarnessSources(outputDir)
        if (harnessSources.isNotEmpty()) {
            val relativeHarnesses = harnessSources.joinToString(", ") {
                outputDir.relativize(it).toString().replace('\\', '/')
            }
            return CheckResult(
                CheckStatus.FAIL,
                "benchmark artifact publish blocked: runtime harness source(s) must not enter dependency jars: $relativeHarnesses"
            )
        }

        val jarResult = runGradleTask(outputDir, reportsDir, case.id, "jar", timeoutSeconds)
        if (jarResult.status != CheckStatus.PASS) {
            return CheckResult(CheckStatus.FAIL, "jar failed while publishing dependency artifact: ${jarResult.note}")
        }

        val jars = listJarFiles(outputDir.resolve("build/libs"))
        if (jars.isEmpty()) {
            return CheckResult(CheckStatus.FAIL, "jar passed but build/libs contains no jars")
        }

        val artifactDir = artifactRoot.resolve(case.id)
        resetDirectory(artifactDir, artifactRoot)
        for (jar in jars) {
            Files.copy(jar, artifactDir.resolve(jar.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
        }

        return CheckResult(CheckStatus.PASS, "Published ${jars.size} jar(s) to $artifactDir")
    }

    private fun findBenchmarkHarnessSources(projectDir: Path): List<Path> {
        val generatedRoot = projectDir.resolve("src/main/java/com/modporter/generated")
        if (!generatedRoot.exists()) return emptyList()
        val stream = Files.walk(generatedRoot)
        return try {
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .filter { path -> path.toString().replace('\\', '/').contains("/benchmark/") }
                .toList()
                .sortedBy { it.toString() }
        } finally {
            stream.close()
        }
    }

    private fun prepareClientSmokeWorldFixture(
        tempRoot: Path,
        reportsDir: Path,
        options: BenchmarkOptions
    ): ClientSmokeWorldFixture {
        val override = System.getenv("MODPORTER_BENCHMARK_CLIENT_WORLD")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
        if (override != null) {
            val issues = validateSmokeWorld(override)
            return if (issues.isEmpty()) {
                ClientSmokeWorldFixture(override, CheckResult(CheckStatus.PASS, "Using client smoke world override at $override"))
            } else {
                ClientSmokeWorldFixture(null, CheckResult(CheckStatus.FAIL, issues.joinToString("; ")))
            }
        }

        val serverJar = locateMinecraftServerJar()
            ?: return ClientSmokeWorldFixture(
                null,
                CheckResult(
                    CheckStatus.FAIL,
                    "Could not find minecraft_1.21.1_server.jar; set MODPORTER_BENCHMARK_CLIENT_WORLD to a prepared vanilla save"
                )
            )

        val fixtureRoot = tempRoot.resolve("client-smoke-world")
        val serverDir = fixtureRoot.resolve("server")
        resetDirectory(serverDir, tempRoot)
        serverDir.resolve("eula.txt").writeText("eula=true\n")
        serverDir.resolve("server.properties").writeText(
            """
            online-mode=false
            level-name=$CLIENT_SMOKE_WORLD
            gamemode=creative
            force-gamemode=true
            enable-status=false
            motd=ModPorter client smoke world
            view-distance=2
            simulation-distance=2
            spawn-protection=0
            """.trimIndent() + "\n"
        )

        val result = runRuntimeProcess(
            listOf(java21Executable(), "-jar", serverJar.toString(), "--nogui"),
            serverDir,
            reportsDir.resolve("client-smoke-world-vanilla-server.log"),
            options.timeoutSeconds,
            RuntimeLogPolicy.standaloneServerStop(failOnWarnings = false)
        )
        if (result.status != CheckStatus.PASS) {
            return ClientSmokeWorldFixture(null, CheckResult(CheckStatus.FAIL, "Vanilla smoke world generation failed: ${result.note}"))
        }

        val world = serverDir.resolve(CLIENT_SMOKE_WORLD)
        val issues = validateSmokeWorld(world)
        return if (issues.isEmpty()) {
            ClientSmokeWorldFixture(world, CheckResult(CheckStatus.PASS, "Generated vanilla client smoke world at $world"))
        } else {
            ClientSmokeWorldFixture(null, CheckResult(CheckStatus.FAIL, issues.joinToString("; ")))
        }
    }

    private fun stageClientSmokeWorld(projectDir: Path, fixtureWorld: Path): List<String> {
        val fixtureIssues = validateSmokeWorld(fixtureWorld)
        if (fixtureIssues.isNotEmpty()) return fixtureIssues

        val savesDir = projectDir.resolve("run/saves")
        val clientWorld = savesDir.resolve(CLIENT_SMOKE_WORLD)
        savesDir.createDirectories()
        resetDirectory(clientWorld, projectDir)
        copyDirectoryForRuntimeWorld(fixtureWorld, clientWorld)
        stageClientRuntimeOptions(projectDir)
        stageBenchmarkFmlConfig(projectDir)
        return emptyList()
    }

    private fun stageClientRuntimeOptions(projectDir: Path) {
        val runDir = projectDir.resolve("run")
        runDir.createDirectories()
        val optionsFile = runDir.resolve("options.txt")
        val options = linkedMapOf(
            "onboardAccessibility" to "false",
            "skipMultiplayerWarning" to "true",
            "tutorialStep" to "none",
            "pauseOnLostFocus" to "false",
            "fullscreen" to "false"
        )
        val existing = linkedMapOf<String, String>()
        if (optionsFile.exists()) {
            Files.readAllLines(optionsFile)
                .filter { it.isNotBlank() && ":" in it }
                .forEach { line ->
                    val key = line.substringBefore(":")
                    val value = line.substringAfter(":")
                    existing[key] = value
                }
        }
        options.forEach { (key, value) -> existing[key] = value }
        optionsFile.writeText(existing.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key:$value" })
    }

    private fun stageBenchmarkFmlConfig(projectDir: Path) {
        val configDir = projectDir.resolve("run/config")
        configDir.createDirectories()
        val fmlConfig = configDir.resolve("fml.toml")
        fmlConfig.writeText(benchmarkFmlConfigContent())
    }

    private fun benchmarkFmlConfigContent(): String = """
        disableConfigWatcher = false
        earlyWindowControl = false
        maxThreads = -1
        versionCheck = false
        defaultConfigPath = "defaultconfigs"
        disableOptimizedDFU = true
        earlyWindowProvider = "fmlearlywindow"
        earlyWindowWidth = 854
        earlyWindowHeight = 480
        earlyWindowFBScale = 1
        earlyWindowMaximized = false
        earlyWindowSkipGLVersions = []
        earlyWindowSquir = false

        [dependencyOverrides]
    """.trimIndent() + "\n"

    private fun stageBenchmarkGameTestSmoke(projectDir: Path): List<String> {
        if (projectHasGameTests(projectDir)) return emptyList()

        return try {
            val modId = detectBenchmarkModId(projectDir) ?: "minecraft"
            val harnessPackage = benchmarkHarnessPackage(projectDir)
            val sourceDir = projectDir.resolve("src/main/java/${harnessPackage.replace('.', '/')}")
            sourceDir.createDirectories()
            sourceDir.resolve("ModPorterBenchmarkGameTests.java").writeText("""
                package $harnessPackage;

                import net.minecraft.gametest.framework.GameTest;
                import net.minecraft.gametest.framework.GameTestHelper;
                import net.neoforged.neoforge.gametest.GameTestHolder;
                import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

                @GameTestHolder("$modId")
                @PrefixGameTestTemplate(false)
                public final class ModPorterBenchmarkGameTests {
                    private ModPorterBenchmarkGameTests() {
                    }

                    @GameTest(template = "modporter_benchmark_empty", timeoutTicks = 20)
                    public static void modporterSmoke(GameTestHelper helper) {
                        helper.succeed();
                    }
                }
            """.trimIndent() + "\n")

            val structureDir = projectDir.resolve("src/main/resources/gameteststructures")
            structureDir.createDirectories()
            val structure = structureDir.resolve("modporter_benchmark_empty.snbt")
            if (!structure.exists()) {
                structure.writeText(BENCHMARK_EMPTY_GAMETEST_STRUCTURE_SNBT)
            }
            emptyList()
        } catch (ex: Exception) {
            listOf("Failed to stage benchmark GameTest smoke harness: ${ex.message}")
        }
    }

    private fun projectHasGameTests(projectDir: Path): Boolean {
        val roots = listOf(
            projectDir.resolve("src/main/java"),
            projectDir.resolve("src/main/kotlin"),
            projectDir.resolve("src/test/java"),
            projectDir.resolve("src/test/kotlin")
        ).filter { it.exists() }

        for (root in roots) {
            val stream = Files.walk(root)
            try {
                if (stream.anyMatch { path ->
                        Files.isRegularFile(path) &&
                            (path.toString().endsWith(".java") || path.toString().endsWith(".kt")) &&
                            path.readText().contains("@GameTest")
                    }) {
                    return true
                }
            } finally {
                stream.close()
            }
        }
        return false
    }

    private fun detectBenchmarkModId(projectDir: Path): String? {
        val gradleProperties = projectDir.resolve("gradle.properties")
        if (gradleProperties.exists()) {
            Regex("""(?m)^mod_id\s*=\s*([A-Za-z0-9_.-]+)\s*$""")
                .find(gradleProperties.readText())
                ?.let { return it.groupValues[1] }
        }

        val modsToml = listOf(
            projectDir.resolve("src/main/resources/META-INF/neoforge.mods.toml"),
            projectDir.resolve("src/main/resources/META-INF/mods.toml")
        ).firstOrNull { it.exists() }
        if (modsToml != null) {
            Regex("""modId\s*=\s*"([^"]+)"""")
                .findAll(modsToml.readText())
                .map { it.groupValues[1] }
                .firstOrNull { it != "minecraft" && it != "neoforge" && it != "forge" }
                ?.let { return it }
        }

        val srcDir = projectDir.resolve("src/main/java")
        if (srcDir.exists()) {
            val stream = Files.walk(srcDir)
            val files = try {
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                    .toList()
            } finally {
                stream.close()
            }
            for (file in files) {
                val text = file.readText()
                Regex("""@Mod\s*\(\s*"([^"]+)"""").find(text)?.let { return it.groupValues[1] }
                val className = file.fileName.toString().removeSuffix(".java")
                val constRef = Regex("""@Mod\s*\(\s*$className\.(\w+)\s*\)""").find(text)
                if (constRef != null) {
                    Regex("""static\s+final\s+String\s+${Regex.escape(constRef.groupValues[1])}\s*=\s*"([^"]+)"""")
                        .find(text)
                        ?.let { return it.groupValues[1] }
                }
            }
        }

        return null
    }

    private fun stageClientWorldHarness(projectDir: Path): List<String> {
        val issues = mutableListOf<String>()
        val harnessPackage = benchmarkHarnessPackage(projectDir)
        val sourceDir = projectDir.resolve("src/main/java/${harnessPackage.replace('.', '/')}")
        sourceDir.createDirectories()
        sourceDir.resolve("ModPorterClientWorldHarness.java").writeText(clientWorldHarnessSource(harnessPackage))

        val buildFile = projectDir.resolve("build.gradle")
        if (!buildFile.exists()) {
            issues.add("runClientWorld benchmark harness requires generated build.gradle at $buildFile")
            return issues
        }

        val modId = detectBenchmarkModId(projectDir) ?: projectDir.fileName.toString()
        val marker = "systemProperty 'modporter.benchmark.clientWorld', '$CLIENT_SMOKE_WORLD'"
        val modIdMarker = "systemProperty 'modporter.benchmark.modId', '$modId'"
        val content = buildFile.readText()
        if (content.contains(marker) && content.contains(modIdMarker)) return issues
        val patched = content.replace(
            Regex("""(?m)^(\s*clientWorld\s*\{\s*\r?\n\s*client\(\)\s*\r?\n)"""),
            "\$1            $marker\r\n            $modIdMarker\r\n"
        )
        if (patched == content) {
            issues.add("Could not inject runClientWorld benchmark system property into $buildFile")
        } else {
            buildFile.writeText(patched)
        }
        return issues
    }

    private fun benchmarkHarnessPackage(projectDir: Path): String {
        val rawId = detectBenchmarkModId(projectDir) ?: projectDir.fileName.toString()
        return "com.modporter.generated.${sanitizeJavaPackageSegment(rawId)}.benchmark"
    }

    private fun sanitizeJavaPackageSegment(value: String): String {
        val sanitized = value.lowercase()
            .replace(Regex("""[^a-z0-9_]"""), "_")
            .trim('_')
            .ifBlank { "mod" }
        return if (sanitized.first().isDigit()) "m$sanitized" else sanitized
    }

    private fun validateSmokeWorld(world: Path): List<String> {
        val issues = mutableListOf<String>()
        if (!world.exists() || !world.isDirectory()) {
            issues.add("Client smoke world does not exist at $world")
            return issues
        }
        if (!world.resolve("level.dat").exists()) {
            issues.add("Client smoke world is missing level.dat at $world")
        }
        if (!world.resolve("region").exists()) {
            issues.add("Client smoke world is missing region data at $world")
        }
        return issues
    }

    private fun prepareDedicatedServerRuntime(projectDir: Path) {
        val runDir = projectDir.resolve("run")
        runDir.createDirectories()
        runDir.resolve("eula.txt").writeText("eula=true\n")
        val serverProperties = runDir.resolve("server.properties")
        if (!serverProperties.exists()) {
            serverProperties.writeText(
                """
                online-mode=true
                level-name=world
                enable-status=false
                motd=ModPorter benchmark
                """.trimIndent() + "\n"
            )
        }
    }

    private fun copyDirectoryForRuntimeWorld(source: Path, output: Path) {
        val excludedFiles = setOf("session.lock")
        val stream = Files.walk(source)
        try {
            stream.forEach { path ->
                val relative = source.relativize(path)
                if (relative.any { excludedFiles.contains(it.name) }) return@forEach
                val target = output.resolve(relative.toString())
                when {
                    Files.isDirectory(path) -> target.createDirectories()
                    Files.isRegularFile(path) -> {
                        target.parent?.createDirectories()
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        } finally {
            stream.close()
        }
    }

    private fun listJarFiles(dir: Path): List<Path> {
        if (!dir.exists() || !dir.isDirectory()) return emptyList()
        val jars = mutableListOf<Path>()
        val stream = Files.list(dir)
        try {
            stream.forEach { path ->
                if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar", ignoreCase = true)) {
                    jars.add(path)
                }
            }
        } finally {
            stream.close()
        }
        return jars.sortedBy { it.fileName.toString() }
    }

    private fun preferredRuntimeJar(jars: List<Path>): Path =
        jars.firstOrNull { jar ->
            val name = jar.fileName.toString()
            !name.contains("-sources", ignoreCase = true) &&
                !name.contains("-javadoc", ignoreCase = true) &&
                !name.contains("-dev", ignoreCase = true)
        } ?: jars.first()

    private fun dependencyJarAliases(projectDir: Path, dependencyId: String): Set<String> {
        val buildText = listOf(projectDir.resolve("build.gradle"), projectDir.resolve("build.gradle.kts"))
            .filter { it.exists() }
            .joinToString("\n") { it.readText() }

        val aliases = linkedSetOf<String>()
        val coordinateRegex = Regex("""fg\.deobf\(\s*['"]([^:'"]+):([^:'"]+):([^'"]+)['"]\s*\)""")
        coordinateRegex.findAll(buildText).forEach { match ->
            val group = match.groupValues[1]
            val artifact = match.groupValues[2]
            val version = match.groupValues[3]
            if (matchesDependency(dependencyId, group) || matchesDependency(dependencyId, artifact)) {
                aliases.add("$artifact-$version.jar")
                aliases.add("$artifact.jar")
            }
        }

        return aliases
    }

    private fun matchesDependency(dependencyId: String, candidate: String): Boolean {
        val dependencyKey = dependencyId.lowercase().replace(Regex("[^a-z0-9]"), "")
        val candidateKey = candidate.lowercase().replace(Regex("[^a-z0-9]"), "")
        return dependencyKey.isNotBlank() &&
            (dependencyKey == candidateKey || dependencyKey.contains(candidateKey) || candidateKey.contains(dependencyKey))
    }

    private fun gradleCommand(projectDir: Path): List<String>? {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val bat = projectDir.resolve("gradlew.bat")
        val sh = projectDir.resolve("gradlew")
        return when {
            isWindows && bat.exists() -> listOf(bat.toString())
            sh.exists() -> listOf(if (isWindows) sh.toString() else "./gradlew")
            else -> null
        }
    }

    private fun configureJavaHome(processBuilder: ProcessBuilder) {
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() } ?: return
        val javaBin = Path.of(javaHome, "bin")
        if (!javaBin.exists()) return

        val env = processBuilder.environment()
        env["JAVA_HOME"] = javaHome

        val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val existingPath = env[pathKey].orEmpty()
        env[pathKey] = if (existingPath.isBlank()) {
            javaBin.toString()
        } else {
            javaBin.toString() + File.pathSeparator + existingPath
        }
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
        val executable = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val javaPath = javaHome?.let { Path.of(it, "bin", executable) }
        return if (javaPath != null && javaPath.exists()) javaPath.toString() else "java"
    }

    private fun java21Executable(): String {
        val executable = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val directOverride = System.getenv("MODPORTER_BENCHMARK_JAVA21")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
        if (directOverride != null && directOverride.exists()) return directOverride.toString()

        val homeOverride = listOf("MODPORTER_BENCHMARK_JAVA21_HOME", "JAVA21_HOME", "JDK21_HOME")
            .asSequence()
            .mapNotNull { System.getenv(it)?.takeIf { value -> value.isNotBlank() } }
            .map { Path.of(it).toAbsolutePath().normalize() }
            .map { it.resolve("bin").resolve(executable) }
            .firstOrNull { it.exists() }
        if (homeOverride != null) return homeOverride.toString()

        val candidates = sequenceOf(
            Path.of("C:/Program Files/Java"),
            Path.of("C:/Program Files/Eclipse Adoptium"),
            Path.of(System.getProperty("user.home"), ".jdks")
        ).flatMap { root ->
            val files = root.toFile().listFiles { file -> file.isDirectory && file.name.contains("21") }.orEmpty()
            files.asSequence()
        }.map { it.toPath().resolve("bin").resolve(executable) }

        return candidates.firstOrNull { it.exists() }?.toString() ?: javaExecutable()
    }

    private fun locateMinecraftServerJar(): Path? {
        val override = System.getenv("MODPORTER_BENCHMARK_MINECRAFT_SERVER_JAR")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
        if (override != null && override.exists()) return override

        val userHome = Path.of(System.getProperty("user.home"))
        val artifact = userHome.resolve(".gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_server.jar")
        return artifact.takeIf { it.exists() }
    }

    private fun serverReachedReadyState(logFile: Path): Boolean {
        if (!logFile.exists()) return false
        val log = logFile.readText()
        return log.contains("Done (") || log.contains("For help, type")
    }

    private fun auditRuntimeLog(
        logFile: Path,
        failOnWarnings: Boolean,
        projectDir: Path? = null,
        inputSourceDir: Path? = null
    ): RuntimeLogAudit {
        if (!logFile.exists()) return RuntimeLogAudit(listOf("log file missing: $logFile"), emptyList())

        val findings = mutableListOf<String>()
        val allowedIssues = mutableListOf<String>()
        val evidenceCache = RuntimeLogEvidenceCache(projectDir, inputSourceDir)
        var clientShutdownStarted = false
        val lines = logFile.readText().lines()
        lines.forEachIndexed { index, line ->
            if (line.contains("[minecraft/Minecraft]: Stopping!")) {
                clientShutdownStarted = true
            }
            if (isAllowedRuntimeLogNoise(lines, index, clientShutdownStarted)) return@forEachIndexed
            val trimmed = line.trim()
            val fatal = runtimeFatalPatterns.any { it.containsMatchIn(line) }
            val warning = failOnWarnings && runtimeWarningPatterns.any { it.containsMatchIn(line) }
            if (fatal || warning) {
                val allowedIssue = allowedKnownUpstreamRuntimeIssue(lines, index, evidenceCache)
                if (allowedIssue != null) {
                    allowedIssues.add("line ${index + 1}: $allowedIssue")
                    return@forEachIndexed
                }
                val kind = if (fatal) "fatal" else "warning"
                findings.add("$kind line ${index + 1}: ${trimmed.take(240)}")
            }
        }

        return RuntimeLogAudit(findings.take(12), summarizeAllowedRuntimeIssues(allowedIssues))
    }

    private fun summarizeAllowedRuntimeIssues(allowedIssues: List<String>): List<String> {
        val grouped = linkedMapOf<String, MutableList<Int>>()
        val unparsed = mutableListOf<String>()
        val pattern = Regex("""^line\s+(\d+):\s+(.+)$""")
        allowedIssues.distinct().forEach { issue ->
            val match = pattern.matchEntire(issue)
            if (match == null) {
                unparsed.add(issue)
            } else {
                grouped.getOrPut(match.groupValues[2]) { mutableListOf() }
                    .add(match.groupValues[1].toInt())
            }
        }

        return grouped.map { (issue, lines) ->
            val sortedLines = lines.distinct().sorted()
            if (sortedLines.size == 1) {
                "line ${sortedLines.single()}: $issue"
            } else {
                "${summarizeLineNumbers(sortedLines)}: $issue"
            }
        } + unparsed.distinct()
    }

    private fun summarizeLineNumbers(lines: List<Int>): String {
        if (lines.size == 1) return "line ${lines.single()}"
        val ranges = mutableListOf<String>()
        var start = lines.first()
        var previous = start
        for (line in lines.drop(1)) {
            if (line == previous + 1) {
                previous = line
            } else {
                ranges.add(if (start == previous) "$start" else "$start-$previous")
                start = line
                previous = line
            }
        }
        ranges.add(if (start == previous) "$start" else "$start-$previous")

        val rendered = if (ranges.size <= 6) {
            ranges.joinToString(", ")
        } else {
            ranges.take(3).joinToString(", ") + ", ..., " + ranges.takeLast(2).joinToString(", ")
        }
        return "lines $rendered (${lines.size} occurrences)"
    }

    private fun isAllowedRuntimeLogNoise(lines: List<String>, index: Int, clientShutdownStarted: Boolean): Boolean {
        val line = lines[index]
        return allowedRuntimeLogFragments.any { line.contains(it) } ||
            clientShutdownStarted && line.contains("[mojang/OpenAlUtil]: Stop: Invalid name parameter.") ||
            clientShutdownStarted &&
            line.contains("[minecraft/Connection]: Exception caught in connection") &&
            lines.getOrNull(index + 1)?.contains("java.nio.channels.ClosedChannelException") == true
    }

    private fun allowedKnownUpstreamRuntimeIssue(
        lines: List<String>,
        index: Int,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        externalDependencyMissingMixinEvidence(lines, index, evidenceCache)?.let {
            return "external dependency optional mixin target warning ($it)"
        }
        externalDependencyMobCategoryEvidence(lines[index], evidenceCache)?.let {
            return "external dependency mob-category warning ($it)"
        }
        externalDependencyConfigCorrectionEvidence(lines, index, evidenceCache)?.let {
            return "external dependency config correction warning ($it)"
        }
        externalDependencyCreativeTabDuplicateEvidence(lines, index, evidenceCache)?.let {
            return "external dependency creative-tab duplicate item warning ($it)"
        }
        sourceInheritedCreativeTabDuplicateEvidence(lines, index, evidenceCache)?.let {
            return "source-inherited creative-tab duplicate item warning ($it)"
        }
        sourceInheritedMissingSoundEvidence(lines[index], evidenceCache)?.let {
            return "source-inherited missing sound definition warning ($it)"
        }
        return null
    }

    private fun externalDependencyMissingMixinEvidence(
        lines: List<String>,
        index: Int,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        val project = evidenceCache.projectDir ?: return null
        val config = missingMixinConfigName(lines, index) ?: return null
        if (evidenceCache.containsFileNamed(config)) {
            return null
        }

        val targetClass = missingMixinTargetClassName(lines, index)
        if (targetClass != null && evidenceCache.containsAnyText(targetClass, targetClass.replace('.', '/'))) {
            return null
        }

        val sourceMod = mixinConfigSourceMod(lines, index, config)?.let { sourceMod ->
            val targetMod = evidenceCache.targetMod ?: detectBenchmarkModId(project)
            if (targetMod != null && sourceMod == targetMod) return null
            "config '$config' was loaded from dependency '$sourceMod' and is absent from converted/input project resources"
        } ?: evidenceCache.externalClasspathConfigSource(config)
            ?: mixinConfigLoadedDependencyEvidence(lines, config, evidenceCache)
            ?: return null

        return sourceMod
    }

    private fun mixinConfigLoadedDependencyEvidence(
        lines: List<String>,
        config: String,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        val ownerMod = loadedModIdForMixinConfig(lines, config) ?: return null
        val targetMod = evidenceCache.targetMod
        if (targetMod != null && ownerMod == targetMod) return null
        if (!evidenceCache.buildFileContainsDependencyId(ownerMod)) return null
        return "config '$config' is attributed to loaded external mod '$ownerMod' by runtime Mod List and active build dependency evidence"
    }

    private fun loadedModIdForMixinConfig(lines: List<String>, config: String): String? {
        val loadedModIds = loadedRuntimeModIds(lines)
        val stem = config
            .removeSuffix(".json")
            .removeSuffix(".mixins")
            .removeSuffix(".mixin")
        return loadedModIds.firstOrNull { modId ->
            stem == modId ||
                stem.startsWith("$modId.") ||
                stem.startsWith("${modId}_") ||
                stem.startsWith("${modId}-")
        }
    }

    private fun loadedRuntimeModIds(lines: List<String>): Set<String> {
        val pattern = Regex("""\(([a-z0-9_.-]+)\)\s*$""")
        return lines.mapNotNull { line -> pattern.find(line)?.groupValues?.get(1) }
            .filterNot { it == "unknown" }
            .toSet()
    }

    private fun missingMixinTargetClassName(lines: List<String>, index: Int): String? {
        val windowStart = (index - 1).coerceAtLeast(0)
        val windowEnd = (index + 2).coerceAtMost(lines.lastIndex)
        val pattern = Regex("""@Mixin target ([^ ]+) was not found [^:\s]+\.mixins\.json:""")
        for (lineIndex in windowStart..windowEnd) {
            pattern.find(lines[lineIndex])?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun missingMixinConfigName(lines: List<String>, index: Int): String? {
        val windowStart = (index - 1).coerceAtLeast(0)
        val windowEnd = (index + 2).coerceAtMost(lines.lastIndex)
        val pattern = Regex("""@Mixin target [^ ]+ was not found ([^:\s]+\.mixins\.json):""")
        for (lineIndex in windowStart..windowEnd) {
            pattern.find(lines[lineIndex])?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun mixinConfigSourceMod(lines: List<String>, index: Int, config: String): String? {
        val pattern = Regex("""Registering mixin config: ${Regex.escape(config)} source=SecureJarResource\(([^)]+)\)""")
        return lines.take(index + 1)
            .takeLast(300)
            .asReversed()
            .firstNotNullOfOrNull { line -> pattern.find(line)?.groupValues?.get(1) }
    }

    private fun externalDependencyMobCategoryEvidence(
        line: String,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        evidenceCache.projectDir ?: return null
        val match = Regex("""Detected ([a-z0-9_.-]+:[a-z0-9_./-]+) that was registered with [A-Z_]+ mob category but was added under [A-Z_]+ mob category""")
            .find(line)
            ?: return null
        val entityId = match.groupValues[1]
        val targetMod = evidenceCache.targetMod
        if (targetMod != null && entityId.substringBefore(':') == targetMod) return null
        if (evidenceCache.containsText(entityId)) return null
        return "entity '$entityId' is not the target mod namespace and is absent from converted/input project sources"
    }

    private fun externalDependencyConfigCorrectionEvidence(
        lines: List<String>,
        index: Int,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        evidenceCache.projectDir ?: return null
        val config = correctedConfigFileName(lines[index]) ?: return null
        val ownerMod = configTrackerOwnerMod(lines, index, config)
            ?: loadedModIdForConfig(lines, config)
            ?: return null
        val targetMod = evidenceCache.targetMod
        if (targetMod != null && ownerMod == targetMod) return null
        if (!evidenceCache.buildFileContainsDependencyId(ownerMod)) return null
        return "config '$config' is tracked for loaded external mod '$ownerMod' with active build dependency evidence"
    }

    private fun correctedConfigFileName(line: String): String? =
        Regex("""Configuration file .*[\\/ ]([a-z0-9_.-]+-(?:client|common|server)\.toml) is not correct\. Correcting""")
            .find(line)
            ?.groupValues
            ?.get(1)

    private fun configTrackerOwnerMod(lines: List<String>, index: Int, config: String): String? {
        val pattern = Regex("""Config file ${Regex.escape(config)} for ([a-z0-9_.-]+) tracking""")
        return lines.take(index + 1)
            .takeLast(80)
            .asReversed()
            .firstNotNullOfOrNull { line -> pattern.find(line)?.groupValues?.get(1) }
    }

    private fun loadedModIdForConfig(lines: List<String>, config: String): String? {
        val stem = config
            .removeSuffix(".toml")
            .removeSuffix("-client")
            .removeSuffix("-common")
            .removeSuffix("-server")
        return loadedRuntimeModIds(lines).firstOrNull { modId -> stem == modId }
    }

    private fun sourceInheritedMissingSoundEvidence(
        line: String,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        val match = Regex("""Missing sound for event: ([a-z0-9_.-]+):([a-z0-9_./-]+)""")
            .find(line)
            ?: return null
        val namespace = match.groupValues[1]
        val soundPath = match.groupValues[2]
        val project = evidenceCache.projectDir ?: return null
        val sourceDir = evidenceCache.inputSourceDir ?: benchmarkInputSourceDir(project) ?: return null
        val targetMod = evidenceCache.targetMod ?: return null
        if (namespace != targetMod) return null
        if (!sourceDeclaresSoundEvent(project, namespace, soundPath)) return null
        if (!sourceDeclaresSoundEvent(sourceDir, namespace, soundPath)) return null
        if (!hasSoundDefinitionFile(project, namespace) || !hasSoundDefinitionFile(sourceDir, namespace)) return null
        if (resourceDefinesSoundEvent(project, namespace, soundPath)) return null
        if (resourceDefinesSoundEvent(sourceDir, namespace, soundPath)) return null
        return "input and converted sources declare sound event '$namespace:$soundPath' but their sounds.json resources omit it"
    }

    private fun externalDependencyCreativeTabDuplicateEvidence(
        lines: List<String>,
        index: Int,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        val warning = creativeTabDuplicateWarning(lines, index) ?: return null
        val targetMod = evidenceCache.targetMod ?: return null
        if (warning.itemIds.any { it.substringBefore(':') == targetMod }) return null
        if (warning.itemIds.any { evidenceCache.containsText(it) }) return null

        val loadedModIds = loadedRuntimeModIds(lines)
        val namespaces = warning.itemIds.map { it.substringBefore(':') }.distinct()
        val namespaceEvidence = namespaces.map { namespace ->
            val activeDependency = evidenceCache.buildFileContainsDependencyId(namespace)
            val loadedMod = namespace in loadedModIds
            when {
                activeDependency && loadedMod -> "$namespace: active build dependency and loaded runtime mod"
                activeDependency -> "$namespace: active build dependency"
                loadedMod -> "$namespace: loaded runtime mod"
                else -> return null
            }
        }
        return "duplicate item ids [${warning.itemIds.joinToString(", ")}] belong to external dependency namespace(s) " +
            "${namespaces.joinToString(", ")} with ${namespaceEvidence.joinToString("; ")} and are absent from converted/input project sources"
    }

    private fun sourceInheritedCreativeTabDuplicateEvidence(
        lines: List<String>,
        index: Int,
        evidenceCache: RuntimeLogEvidenceCache
    ): String? {
        val warning = creativeTabDuplicateWarning(lines, index) ?: return null
        val project = evidenceCache.projectDir ?: return null
        val sourceDir = evidenceCache.inputSourceDir ?: benchmarkInputSourceDir(project) ?: return null
        val targetMod = evidenceCache.targetMod ?: return null
        if (warning.itemIds.any { it.substringBefore(':') != targetMod }) return null
        if (warning.itemIds.any { !projectHasCreativeDuplicateItemSourceShape(project, it) }) return null
        if (warning.itemIds.any { !projectHasCreativeDuplicateItemSourceShape(sourceDir, it) }) return null
        if (warning.itemIds.any { projectRegistersJeiSubtypeForItem(project, it) }) return null
        return "input and converted sources duplicate creative-tab item id(s) ${warning.itemIds.joinToString(", ")} " +
            "and converted source has no JEI subtype registration for those item id(s)"
    }

    private fun creativeTabDuplicateWarning(lines: List<String>, index: Int): CreativeTabDuplicateWarning? {
        val duplicatePattern = Regex("""(?:\b\d+\s+)?duplicate items were found in '([^']+)' creative tab's: displayItems""")
        val duplicateIndex = when {
            duplicatePattern.containsMatchIn(lines[index]) -> index
            else -> ((index - 3).coerceAtLeast(0) until index)
                .lastOrNull { duplicatePattern.containsMatchIn(lines[it]) }
        } ?: return null
        val tabName = duplicatePattern.find(lines[duplicateIndex])?.groupValues?.get(1) ?: return null
        val itemLines = if (duplicateIndex == index) {
            lines.drop(index + 1).take(4)
        } else {
            listOf(lines[index])
        }
        val itemIdPattern = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")
        val itemLine = itemLines.firstOrNull { itemIdPattern.containsMatchIn(it) } ?: return null
        val itemIds = itemIdPattern.findAll(itemLine)
            .map { it.value }
            .distinct()
            .toList()
        if (itemIds.isEmpty()) return null
        return CreativeTabDuplicateWarning(tabName, itemIds)
    }

    private data class CreativeTabDuplicateWarning(
        val tabName: String,
        val itemIds: List<String>
    )

    private inner class RuntimeLogEvidenceCache(
        val projectDir: Path?,
        val inputSourceDir: Path?
    ) {
        val targetMod: String? by lazy { projectDir?.let { detectBenchmarkModId(it) } }
        private val fileNameMemo = mutableMapOf<String, Boolean>()
        private val textMemo = mutableMapOf<String, Boolean>()
        private val externalConfigMemo = mutableMapOf<String, String?>()
        private val dependencyIdMemo = mutableMapOf<String, Boolean>()

        fun containsFileNamed(fileName: String): Boolean =
            fileNameMemo.getOrPut(fileName) {
                projectDir?.let { projectContainsFileNamed(it, fileName) } == true ||
                    inputSourceDir?.let { projectContainsFileNamed(it, fileName) } == true
            }

        fun containsText(text: String): Boolean =
            textMemo.getOrPut(text) {
                projectDir?.let { projectContainsText(it, text) } == true ||
                    inputSourceDir?.let { projectContainsText(it, text) } == true
            }

        fun containsAnyText(vararg texts: String): Boolean =
            texts.any { containsText(it) }

        fun externalClasspathConfigSource(config: String): String? =
            externalConfigMemo.getOrPut(config) {
                val project = projectDir ?: return@getOrPut null
                val source = runtimeClasspathConfigSource(project, config, targetMod)
                    ?: return@getOrPut null
                "config '$config' was found in external runtime jar for dependency '$source' and is absent from converted/input project resources"
            }

        fun buildFileContainsDependencyId(id: String): Boolean =
            dependencyIdMemo.getOrPut(id) {
                projectDir?.let { activeBuildFileContainsDependencyId(it, id) } == true
            }
    }

    private fun activeBuildFileContainsDependencyId(projectDir: Path, id: String): Boolean {
        val buildFiles = listOf(
            projectDir.resolve("build.gradle"),
            projectDir.resolve("build.gradle.kts")
        ).filter { it.exists() }
        val pattern = Regex("""(^|[:'"(,\s])${Regex.escape(id)}($|[:'"),\s])""")
        for (buildFile in buildFiles) {
            val activeText = buildFile.readText()
                .lines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            if (pattern.containsMatchIn(activeText)) return true
        }
        return false
    }

    private fun runtimeClasspathConfigSource(projectDir: Path, config: String, targetMod: String?): String? {
        for (jar in runtimeClasspathJars(projectDir)) {
            val modId = modIdFromJar(jar)
            if (targetMod != null && modId == targetMod) continue
            if (jarContainsEntryNamed(jar, config)) return modId ?: jar.fileName.toString()
        }
        return null
    }

    private fun runtimeClasspathJars(projectDir: Path): List<Path> {
        val moddev = projectDir.resolve("build/moddev")
        if (!moddev.exists()) return emptyList()

        val classpathFiles = mutableListOf<Path>()
        Files.walk(moddev).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith("Classpath.txt") }
                .forEach { classpathFiles.add(it) }
        }

        val jars = linkedSetOf<Path>()
        for (classpathFile in classpathFiles) {
            for (line in classpathFile.readText().lines()) {
                for (entry in line.split(File.pathSeparatorChar)) {
                    val raw = entry.trim().trim('"')
                    if (!raw.endsWith(".jar", ignoreCase = true)) continue
                    val jar = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() ?: continue
                    if (jar.exists()) jars.add(jar)
                }
            }
        }
        return jars.toList()
    }

    private fun jarContainsEntryNamed(jar: Path, fileName: String): Boolean =
        runCatching {
            ZipFile(jar.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == fileName) return true
                }
            }
            false
        }.getOrDefault(false)

    private fun modIdFromJar(jar: Path): String? =
        readJarEntryText(jar, "META-INF/neoforge.mods.toml")?.let { parseTomlModId(it) }
            ?: readJarEntryText(jar, "META-INF/mods.toml")?.let { parseTomlModId(it) }
            ?: readJarEntryText(jar, "fabric.mod.json")?.let { parseJsonModId(it) }

    private fun readJarEntryText(jar: Path, entryName: String): String? =
        runCatching {
            ZipFile(jar.toFile()).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }.getOrNull()

    private fun parseTomlModId(text: String): String? =
        Regex("""(?m)^\s*modId\s*=\s*["']([^"']+)["']""")
            .find(text)
            ?.groupValues
            ?.get(1)

    private fun parseJsonModId(text: String): String? =
        Regex(""""id"\s*:\s*"([^"]+)"""")
            .find(text)
            ?.groupValues
            ?.get(1)

    private fun projectContainsFileNamed(projectDir: Path, fileName: String): Boolean {
        if (!projectDir.exists()) return false
        val roots = listOf(
            projectDir.resolve("src/main/resources"),
            projectDir.resolve("src/generated/resources")
        ).filter { it.exists() }
        for (root in roots) {
            Files.walk(root).use { files ->
                if (files.anyMatch { Files.isRegularFile(it) && it.fileName.toString() == fileName }) return true
            }
        }
        return false
    }

    private fun projectContainsText(projectDir: Path, text: String): Boolean {
        if (!projectDir.exists()) return false
        val roots = listOf(
            projectDir.resolve("src/main/java"),
            projectDir.resolve("src/main/kotlin"),
            projectDir.resolve("src/main/resources"),
            projectDir.resolve("src/generated/resources")
        ).filter { it.exists() }
        for (root in roots) {
            Files.walk(root).use { files ->
                if (files.anyMatch { Files.isRegularFile(it) && runCatching { it.readText().contains(text) }.getOrDefault(false) }) {
                    return true
                }
            }
        }
        return false
    }

    private fun sourceDeclaresSoundEvent(projectDir: Path, namespace: String, soundPath: String): Boolean {
        if (!projectDir.exists()) return false
        val roots = listOf(
            projectDir.resolve("src/main/java"),
            projectDir.resolve("src/main/kotlin"),
            projectDir.resolve("src/generated/java")
        ).filter { it.exists() }
        val soundLiteral = Regex("""["'](?:${Regex.escape(namespace)}:)?${Regex.escape(soundPath)}["']""")
        for (root in roots) {
            Files.walk(root).use { files ->
                if (files.anyMatch { file ->
                        Files.isRegularFile(file) &&
                            (file.fileName.toString().endsWith(".java") || file.fileName.toString().endsWith(".kt")) &&
                            runCatching {
                                val source = activeCode(file.readText())
                                source.contains("SoundEvent") && soundLiteral.containsMatchIn(source)
                            }.getOrDefault(false)
                    }) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasSoundDefinitionFile(projectDir: Path, namespace: String): Boolean =
        soundDefinitionFiles(projectDir, namespace).any { it.exists() }

    private fun resourceDefinesSoundEvent(projectDir: Path, namespace: String, soundPath: String): Boolean {
        val keyPattern = Regex("""(?m)^\s*"${Regex.escape(soundPath)}"\s*:""")
        return soundDefinitionFiles(projectDir, namespace)
            .filter { it.exists() }
            .any { file -> runCatching { keyPattern.containsMatchIn(file.readText()) }.getOrDefault(false) }
    }

    private fun soundDefinitionFiles(projectDir: Path, namespace: String): List<Path> =
        listOf(
            projectDir.resolve("src/main/resources/assets/$namespace/sounds.json"),
            projectDir.resolve("src/generated/resources/assets/$namespace/sounds.json")
        )

    private fun benchmarkInputSourceDir(projectDir: Path): Path? {
        val caseId = projectDir.fileName?.toString() ?: return null
        val tmpRoot = projectDir.parent?.parent ?: return null
        val candidate = tmpRoot.resolve("sources").resolve(caseId)
        return candidate.takeIf { it.exists() }
    }

    private fun projectHasCreativeDuplicateItemSourceShape(projectDir: Path, itemId: String): Boolean {
        if (!projectDir.exists()) return false
        val patterns = itemReferencePatterns(itemId)
        for (file in javaAndKotlinSourceFiles(projectDir)) {
            val relativePath = projectDir.relativize(file).toString().replace('\\', '/').lowercase()
            val active = activeCode(file.readText())
            val creativeContext = relativePath.contains("creative") ||
                relativePath.contains("tab") ||
                active.contains("CreativeModeTab") ||
                active.contains("output.accept") ||
                active.contains("displayItems")
            if (!creativeContext) continue
            val referenceCount = patterns.sumOf { pattern -> pattern.findAll(active).count() }
            if (referenceCount >= 2) return true
        }
        return false
    }

    private fun projectRegistersJeiSubtypeForItem(projectDir: Path, itemId: String): Boolean {
        if (!projectDir.exists()) return false
        val patterns = itemReferencePatterns(itemId)
        for (file in javaAndKotlinSourceFiles(projectDir)) {
            val active = activeCode(file.readText())
            if (!active.contains("registerSubtypeInterpreter")) continue
            if (patterns.any { it.containsMatchIn(active) }) return true
        }
        return false
    }

    private fun itemReferencePatterns(itemId: String): List<Regex> {
        val namespace = itemId.substringBefore(':')
        val path = itemId.substringAfter(':')
        val constant = path.uppercase().replace(Regex("""[^A-Z0-9]"""), "_")
        return listOf(
            Regex("""["']${Regex.escape(namespace)}:${Regex.escape(path)}["']"""),
            Regex("""["']${Regex.escape(path)}["']"""),
            Regex("""\b${Regex.escape(constant)}\b""")
        )
    }

    private fun javaAndKotlinSourceFiles(projectDir: Path): List<Path> {
        val roots = listOf(
            projectDir.resolve("src/main/java"),
            projectDir.resolve("src/main/kotlin"),
            projectDir.resolve("src/generated/java"),
            projectDir.resolve("src/generated/kotlin")
        ).filter { it.exists() }
        val files = mutableListOf<Path>()
        for (root in roots) {
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".java") || it.fileName.toString().endsWith(".kt") }
                    .forEach { files.add(it) }
            }
        }
        return files
    }

    private fun logTail(logFile: Path, lines: Int = 40): String {
        if (!logFile.exists()) return "(no log)"
        return logFile.readText().lines().takeLast(lines).joinToString("\\n")
    }

    private fun localSourceOverrideKey(id: String): String =
        "MODPORTER_BENCHMARK_SOURCE_" + envId(id)

    private fun gitOverrideKey(id: String): String =
        "MODPORTER_BENCHMARK_GIT_" + envId(id)

    private fun refOverrideKey(id: String): String =
        "MODPORTER_BENCHMARK_REF_" + envId(id)

    private fun envId(id: String): String =
        id.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    private fun copyProjectFiltered(source: Path, output: Path) {
        val excludedDirs = setOf(".git", ".gradle", "build", "bin", "run", "out", ".idea", ".vscode")
        val stream = Files.walk(source)
        try {
            stream.forEach { path ->
                val relative = source.relativize(path)
                if (relative.any { excludedDirs.contains(it.name) }) return@forEach
                val target = output.resolve(relative.toString())
                when {
                    Files.isDirectory(path) -> target.createDirectories()
                    Files.isRegularFile(path) -> {
                        target.parent?.createDirectories()
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        } finally {
            stream.close()
        }
    }

    private fun resetDirectory(dir: Path, root: Path) {
        deleteDirectory(dir, root)
        dir.createDirectories()
    }

    private fun deleteDirectory(dir: Path, root: Path) {
        val normalized = dir.toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedRoot)) {
            "Refusing to delete directory outside benchmark root: $normalized"
        }
        if (normalized.exists()) {
            normalized.toFile().deleteRecursively()
        }
    }

    private fun loadCases(manifest: Path): List<BenchmarkCase> {
        require(manifest.exists()) { "Benchmark manifest not found: $manifest" }
        return manifest.readText().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\t')
                require(parts.size >= 7) { "Invalid benchmark manifest line: $line" }
                val dependencies = parts.getOrNull(7)
                    .orEmpty()
                    .takeUnless { it == "-" }
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                BenchmarkCase(
                    id = parts[0],
                    displayName = parts[1],
                    provider = parts[2],
                    location = parts[3],
                    ref = parts[4],
                    subdir = parts[5],
                    required = parts[6].equals("true", ignoreCase = true),
                    dependencies = dependencies
                )
            }
    }

    private fun selectCases(cases: List<BenchmarkCase>, caseIds: List<String>?): List<BenchmarkCase> {
        if (caseIds == null) return cases
        val byId = cases.associateBy { it.id }
        val missing = caseIds.filterNot { it in byId }
        require(missing.isEmpty()) {
            "Unknown MODPORTER_BENCHMARK_CASES id(s): ${missing.joinToString(", ")}"
        }
        val selected = linkedMapOf<String, BenchmarkCase>()
        val visiting = mutableSetOf<String>()
        fun addWithDependencies(id: String) {
            if (id in selected) return
            require(visiting.add(id)) { "Cyclic benchmark dependency involving '$id'" }
            val case = byId.getValue(id)
            for (dependency in case.dependencies) {
                require(dependency in byId) { "Unknown benchmark dependency '$dependency' for '${case.id}'" }
                addWithDependencies(dependency)
            }
            visiting.remove(id)
            selected[id] = case
        }
        caseIds.forEach(::addWithDependencies)
        return selected.values.toList()
    }

    private fun renderReport(outcomes: List<BenchmarkOutcome>, options: BenchmarkOptions): String =
        buildString {
            appendLine("# Real Mod Benchmark Report")
            appendLine()
            appendLine("| Option | Value |")
            appendLine("|--------|-------|")
            appendLine("| strict | ${options.strict} |")
            appendLine("| strictRuntime | ${options.strictRuntime} |")
            appendLine("| handsOff | ${options.handsOff} |")
            appendLine("| compile | ${options.compile} |")
            appendLine("| runServer | ${options.runServer} |")
            appendLine("| runGameTestServer | ${options.runGameTestServer} |")
            appendLine("| runClient | ${options.runClient} |")
            appendLine("| runClientWorld | ${options.runClientWorld} |")
            appendLine("| logClean | ${options.logClean} |")
            appendLine("| keepWork | ${options.keepWork} |")
            appendLine("| timeoutSeconds | ${options.timeoutSeconds} |")
            appendLine("| progressGraceSeconds | ${options.progressGraceSeconds} |")
            appendLine("| requested cases | ${options.caseIds?.joinToString(",") ?: "all"} |")
            appendLine("| resolved cases | ${outcomes.joinToString(",") { it.case.id }} |")
            appendLine()
            appendLine("| Mod | Status | Changes | High | Medium | Low | Compile | runServer | runGameTestServer | runClient | runClientWorld | Note |")
            appendLine("|-----|--------|---------|------|--------|-----|---------|-----------|-------------------|-----------|----------------|------|")
            for (outcome in outcomes) {
                val result = outcome.pipelineResult
                appendLine("| ${outcome.case.displayName} | ${outcome.status} | " +
                    "${result?.totalChanges ?: "-"} | " +
                    "${result?.passResults?.sumOf { it.highConfidence } ?: "-"} | " +
                    "${result?.passResults?.sumOf { it.mediumConfidence } ?: "-"} | " +
                    "${result?.passResults?.sumOf { it.lowConfidence } ?: "-"} | " +
                    "${outcome.compile.status} | ${outcome.runServer.status} | " +
                    "${outcome.runGameTestServer.status} | ${outcome.runClient.status} | " +
                    "${outcome.runClientWorld.status} | ${outcome.note.replace("|", "\\|")} |")
            }
            appendLine()
            appendLine("## Sources")
            appendLine()
            for (outcome in outcomes) {
                appendLine("- `${outcome.case.id}`: `${outcome.source}`")
                if (outcome.case.dependencies.isNotEmpty()) {
                    appendLine("  Dependencies: `${outcome.case.dependencies.joinToString(",")}`")
                }
                if (outcome.outputDir != null) {
                    val suffix = if (options.keepWork) "" else " (deleted after run)"
                    appendLine("  Output: `${outcome.outputDir}`$suffix")
                }
            }
            appendLine()
            appendReviewChanges(outcomes, Confidence.MEDIUM, "Medium Confidence")
            appendReviewChanges(outcomes, Confidence.LOW, "Low Confidence")
        }

    private fun StringBuilder.appendReviewChanges(
        outcomes: List<BenchmarkOutcome>,
        confidence: Confidence,
        title: String
    ) {
        val outcomesWithChanges = outcomes.mapNotNull { outcome ->
            val changes = outcome.pipelineResult?.passResults
                ?.flatMap { passResult ->
                    passResult.changes
                        .filter { it.confidence == confidence }
                        .map { passResult.passName to it }
                }
                .orEmpty()
            if (changes.isEmpty()) null else outcome to changes
        }
        if (outcomesWithChanges.isEmpty()) return

        appendLine()
        appendLine("## $title Changes")
        appendLine()
        for ((outcome, changes) in outcomesWithChanges) {
            appendLine("### ${outcome.case.displayName}")
            for ((passName, change) in changes) {
                appendLine("- `${change.file}:${change.line}` `${change.ruleId}` in `$passName`")
                appendLine("  - ${change.description}")
                appendLine("  - Before: `${change.before.replace("`", "'")}`")
                appendLine("  - After: `${change.after.replace("`", "'")}`")
            }
            appendLine()
        }
    }

    data class BenchmarkCase(
        val id: String,
        val displayName: String,
        val provider: String,
        val location: String,
        val ref: String,
        val subdir: String,
        val required: Boolean,
        val dependencies: List<String> = emptyList()
    )

    data class BenchmarkOptions(
        val strictRuntime: Boolean,
        val strict: Boolean,
        val handsOff: Boolean,
        val compile: Boolean,
        val runServer: Boolean,
        val runGameTestServer: Boolean,
        val runClient: Boolean,
        val runClientWorld: Boolean,
        val logClean: Boolean,
        val keepWork: Boolean,
        val caseIds: List<String>?,
        val timeoutSeconds: Long,
        val progressGraceSeconds: Long
    ) {
        companion object {
            fun fromEnvironment(): BenchmarkOptions {
                val strictRuntime = envFlag("MODPORTER_BENCHMARK_STRICT_RUNTIME")
                val runServer = strictRuntime || envFlag("MODPORTER_BENCHMARK_RUNSERVER")
                val runGameTestServer = strictRuntime || envFlag("MODPORTER_BENCHMARK_RUNGAMETESTSERVER")
                val runClient = strictRuntime || envFlag("MODPORTER_BENCHMARK_RUNCLIENT")
                val runClientWorld = strictRuntime || envFlag("MODPORTER_BENCHMARK_RUNCLIENTWORLD")
                val runtimeRequested = runServer || runGameTestServer || runClient || runClientWorld
                return BenchmarkOptions(
                    strictRuntime = strictRuntime,
                    strict = strictRuntime || envFlag("MODPORTER_BENCHMARK_STRICT"),
                    handsOff = strictRuntime || envFlag("MODPORTER_BENCHMARK_HANDS_OFF"),
                    compile = runtimeRequested || envFlag("MODPORTER_BENCHMARK_COMPILE"),
                    runServer = runServer,
                    runGameTestServer = runGameTestServer,
                    runClient = runClient,
                    runClientWorld = runClientWorld,
                    logClean = strictRuntime || envFlag("MODPORTER_BENCHMARK_LOG_CLEAN"),
                    keepWork = envFlag("MODPORTER_BENCHMARK_KEEP_WORK"),
                    caseIds = System.getenv("MODPORTER_BENCHMARK_CASES")
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() },
                    timeoutSeconds = System.getenv("MODPORTER_BENCHMARK_TIMEOUT_SECONDS")?.toLongOrNull() ?: 180L,
                    progressGraceSeconds = System.getenv("MODPORTER_BENCHMARK_PROGRESS_GRACE_SECONDS")
                        ?.toLongOrNull()
                        ?: 75L
                )
            }

            private fun envFlag(name: String): Boolean =
                System.getenv(name)?.equals("true", ignoreCase = true) == true
        }

        fun reportFileName(): String = buildString {
            append("summary")
            if (strict) append("-strict")
            if (strictRuntime) append("-strict-runtime")
            if (handsOff) append("-hands-off")
            if (compile) append("-compile")
            if (runServer) append("-runserver")
            if (runGameTestServer) append("-rungametestserver")
            if (runClient) append("-runclient")
            if (runClientWorld) append("-runclientworld")
            if (logClean) append("-log-clean")
            append(".md")
        }
    }

    private fun strictBenchmarkOptions(): BenchmarkOptions =
        BenchmarkOptions(
            strictRuntime = true,
            strict = true,
            handsOff = true,
            compile = true,
            runServer = true,
            runGameTestServer = true,
            runClient = true,
            runClientWorld = true,
            logClean = true,
            keepWork = false,
            caseIds = null,
            timeoutSeconds = 180L,
            progressGraceSeconds = 75L
        )

    data class PreparedSource(
        val path: Path?,
        val sourceLabel: String,
        val status: Status?,
        val note: String
    )

    data class ClientSmokeWorldFixture(
        val path: Path?,
        val result: CheckResult
    )

    data class BenchmarkOutcome(
        val case: BenchmarkCase,
        val source: String,
        val outputDir: String?,
        val status: Status,
        val note: String,
        val pipelineResult: PipelineResult? = null,
        val compile: CheckResult = CheckResult.notRun("not requested"),
        val runServer: CheckResult = CheckResult.notRun("not requested"),
        val runGameTestServer: CheckResult = CheckResult.notRun("not requested"),
        val runClient: CheckResult = CheckResult.notRun("not requested"),
        val runClientWorld: CheckResult = CheckResult.notRun("not requested")
    )

    data class CheckResult(
        val status: CheckStatus,
        val note: String
    ) {
        val passedOrNotRun: Boolean
            get() = status == CheckStatus.PASS || status == CheckStatus.NOT_RUN

        companion object {
            fun notRun(note: String): CheckResult = CheckResult(CheckStatus.NOT_RUN, note)
        }
    }

    data class RuntimeLogAudit(
        val findings: List<String>,
        val allowedIssues: List<String>
    )

    private fun CheckResult.allowedRuntimeIssueNote(): String? {
        if (status != CheckStatus.PASS) return null
        val marker = "allowedRuntimeIssues="
        val index = note.indexOf(marker)
        return if (index >= 0) note.substring(index) else null
    }

    data class RuntimeLogPolicy(
        val readyChecks: List<RuntimeMarkerCheck>,
        val shutdownChecks: List<RuntimeMarkerCheck> = emptyList(),
        val stopCommand: String? = null,
        val terminateAfterReady: Boolean = false,
        val requireZeroExit: Boolean = true,
        val failOnWarnings: Boolean = false,
        val progressChecks: List<RuntimeMarkerCheck> = emptyList(),
        val progressLabel: String = "progress",
        val progressGraceSeconds: Long? = null
    ) {
        fun ready(logFile: Path): Boolean = missingReadyChecks(logFile).isEmpty()

        fun progress(logFile: Path): Boolean =
            progressChecks.isNotEmpty() && missingChecks(logFile, progressChecks).isEmpty()

        fun missingReadyChecks(logFile: Path): List<String> =
            missingChecks(logFile, readyChecks)

        fun missingShutdownChecks(logFile: Path): List<String> =
            missingChecks(logFile, shutdownChecks)

        private fun missingChecks(logFile: Path, checks: List<RuntimeMarkerCheck>): List<String> {
            if (checks.isEmpty()) return emptyList()
            if (!logFile.exists()) return checks.map { it.label }
            val log = logFile.readText()
            return checks.filterNot { check -> check.patterns.any { it.containsMatchIn(log) } }
                .map { it.label }
        }

        companion object {
            fun serverReady(failOnWarnings: Boolean): RuntimeLogPolicy =
                RuntimeLogPolicy(
                    readyChecks = listOf(
                        RuntimeMarkerCheck("dedicated server ready", listOf(Regex("""Done \("""))),
                        RuntimeMarkerCheck("command prompt ready", listOf(Regex("""For help, type "help"""")))
                    ),
                    terminateAfterReady = true,
                    requireZeroExit = false,
                    failOnWarnings = failOnWarnings
                )

            fun standaloneServerStop(failOnWarnings: Boolean): RuntimeLogPolicy =
                RuntimeLogPolicy(
                    readyChecks = listOf(
                        RuntimeMarkerCheck("dedicated server ready", listOf(Regex("""Done \("""))),
                        RuntimeMarkerCheck("command prompt ready", listOf(Regex("""For help, type "help"""")))
                    ),
                    shutdownChecks = listOf(
                        RuntimeMarkerCheck("server stopped", listOf(Regex("""Stopping server|ThreadedAnvilChunkStorage.*All chunks are saved|All chunks are saved|BUILD SUCCESSFUL""")))
                    ),
                    stopCommand = "stop",
                    requireZeroExit = false,
                    failOnWarnings = failOnWarnings
                )

            fun gameTestServer(failOnWarnings: Boolean): RuntimeLogPolicy =
                RuntimeLogPolicy(
                    readyChecks = listOf(
                        RuntimeMarkerCheck("game test server ran", listOf(Regex("""(?:GAME TESTS COMPLETE|All \d+ required tests passed|Done \()""")))
                    ),
                    shutdownChecks = listOf(
                        RuntimeMarkerCheck("server stopped", listOf(Regex("""Stopping server|BUILD SUCCESSFUL""")))
                    ),
                    failOnWarnings = failOnWarnings
                )

            fun clientStart(failOnWarnings: Boolean): RuntimeLogPolicy =
                RuntimeLogPolicy(
                    readyChecks = clientStartChecks,
                    terminateAfterReady = true,
                    requireZeroExit = false,
                    failOnWarnings = failOnWarnings
                )

            fun clientWorld(failOnWarnings: Boolean, progressGraceSeconds: Long): RuntimeLogPolicy =
                RuntimeLogPolicy(
                    readyChecks = clientStartChecks + listOf(
                        RuntimeMarkerCheck("integrated server started", listOf(Regex("""Starting integrated minecraft server version"""))),
                        RuntimeMarkerCheck("spawn prepared", listOf(Regex("""Time elapsed: \d+ ms"""))),
                        RuntimeMarkerCheck("player joined loaded world", listOf(Regex("""logged in with entity id"""))),
                        RuntimeMarkerCheck("creative tab browse complete", listOf(Regex("""\[ModPorterBenchmark] Creative tab browse complete:""")))
                    ),
                    terminateAfterReady = true,
                    requireZeroExit = false,
                    failOnWarnings = failOnWarnings,
                    progressChecks = clientStartChecks,
                    progressLabel = "client start",
                    progressGraceSeconds = progressGraceSeconds
                )
        }
    }

    data class RuntimeMarkerCheck(
        val label: String,
        val patterns: List<Regex>
    )

    enum class Status { PASS, FAIL, SKIP }
    enum class CheckStatus { PASS, FAIL, NOT_RUN }

    companion object {
        private const val CLIENT_SMOKE_WORLD = "modporter_smoke_world"

        private val BENCHMARK_EMPTY_GAMETEST_STRUCTURE_SNBT = """
{
  DataVersion: 3953,
  size: [1, 1, 1],
  entities: [],
  blocks: [],
  palette: []
}
""".trimIndent()

        private fun clientWorldHarnessSource(harnessPackage: String): String = """
            package $harnessPackage;

            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.List;
            import java.util.Locale;
            import java.util.Set;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.components.Button;
            import net.minecraft.client.gui.components.events.GuiEventListener;
            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.CreativeModeTab;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.common.CreativeModeTabRegistry;

            @EventBusSubscriber(value = Dist.CLIENT)
            public final class ModPorterClientWorldHarness {
                private static boolean attempted;
                private static int ticks;
                private static int titleTicks;
                private static int confirmTicks;
                private static String lastWaitingScreen = "";
                private static BrowseState browseState = BrowseState.WAITING_FOR_WORLD;
                private static List<CreativeModeTab> targetTabs = List.of();
                private static int targetTabIndex;
                private static int browseRow;
                private static Set<Integer> expectedItems = Set.of();
                private static Set<Integer> observedItems = Set.of();
                private static Set<Integer> stableMenuItems = Set.of();
                private static int stableMenuTicks;
                private static String currentTabName = "";

                private enum BrowseState {
                    WAITING_FOR_WORLD,
                    OPEN_SCREEN,
                    DISCOVER_TABS,
                    SELECT_TAB,
                    BROWSE_TAB,
                    COMPLETE,
                    FAILED
                }

                private ModPorterClientWorldHarness() {
                }

                @SubscribeEvent
                public static void onClientTick(ClientTickEvent.Post event) {
                    String world = System.getProperty("modporter.benchmark.clientWorld");
                    if (world == null || world.isBlank()) {
                        return;
                    }

                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.level != null && minecraft.player != null && minecraft.gameMode != null) {
                        runCreativeInventoryBrowse(minecraft);
                        return;
                    }

                    ticks++;
                    Screen screen = minecraft.screen;
                    if (screen == null) {
                        return;
                    }

                    if (!attempted) {
                        if (dismissInitialScreen(screen)) {
                            return;
                        }

                        if (confirmBlockingScreen(screen)) {
                            return;
                        }

                        if (!(screen instanceof TitleScreen)) {
                            logWaitingScreen(screen, "Waiting for TitleScreen before opening world");
                            return;
                        }

                        titleTicks++;
                        if (titleTicks < 20) {
                            return;
                        }

                        attempted = true;
                        minecraft.execute(() -> {
                            try {
                                Screen currentScreen = minecraft.screen;
                                String screenName = currentScreen == null ? "<none>" : currentScreen.getClass().getName();
                                System.out.println("[ModPorterBenchmark] Opening client smoke world from screen " + screenName + ": " + world);
                                minecraft.createWorldOpenFlows().openWorld(world, () -> {});
                            } catch (Throwable throwable) {
                                System.err.println("[ModPorterBenchmark] Failed to request client smoke world load");
                                throwable.printStackTrace();
                            }
                        });
                        return;
                    }

                    autoConfirm(screen);
                }

                private static void runCreativeInventoryBrowse(Minecraft minecraft) {
                    if (browseState == BrowseState.COMPLETE || browseState == BrowseState.FAILED) {
                        return;
                    }

                    if (!minecraft.gameMode.hasInfiniteItems()) {
                        fail("client smoke world is not in creative mode; gameMode=" + minecraft.gameMode.getPlayerMode());
                        return;
                    }

                    if (browseState == BrowseState.WAITING_FOR_WORLD) {
                        ticks++;
                        if (ticks < 40) {
                            return;
                        }
                        browseState = BrowseState.OPEN_SCREEN;
                    }

                    if (browseState == BrowseState.OPEN_SCREEN) {
                        if (!(minecraft.screen instanceof CreativeModeInventoryScreen)) {
                            System.out.println("[ModPorterBenchmark] Opening creative inventory screen");
                            minecraft.setScreen(new CreativeModeInventoryScreen(
                                minecraft.player,
                                minecraft.player.connection.enabledFeatures(),
                                minecraft.options.operatorItemsTab().get()
                            ));
                            return;
                        }
                        browseState = BrowseState.DISCOVER_TABS;
                    }

                    if (!(minecraft.screen instanceof CreativeModeInventoryScreen screen)) {
                        return;
                    }

                    if (browseState == BrowseState.DISCOVER_TABS) {
                        String modId = System.getProperty("modporter.benchmark.modId", "").trim();
                        if (modId.isEmpty()) {
                            fail("missing modporter.benchmark.modId system property");
                            return;
                        }

                        targetTabs = CreativeModeTabRegistry.getSortedCreativeModeTabs().stream()
                            .filter(CreativeModeTab::hasAnyItems)
                            .filter(tab -> tabContainsNamespace(tab, modId))
                            .toList();
                        if (targetTabs.isEmpty()) {
                            fail("no non-empty creative tabs found containing item namespace '" + modId + "'");
                            return;
                        }

                        System.out.println("[ModPorterBenchmark] Found " + targetTabs.size() + " creative tab(s) containing item namespace " + modId);
                        targetTabIndex = 0;
                        browseState = BrowseState.SELECT_TAB;
                    }

                    if (browseState == BrowseState.SELECT_TAB) {
                        CreativeModeTab tab = targetTabs.get(targetTabIndex);
                        if (!selectCreativeTab(screen, tab)) {
                            return;
                        }
                        observedItems = new HashSet<>();
                        expectedItems = Set.of();
                        stableMenuItems = Set.of();
                        stableMenuTicks = 0;
                        browseRow = 0;
                        browseState = BrowseState.BROWSE_TAB;
                        return;
                    }

                    if (browseState == BrowseState.BROWSE_TAB) {
                        browseSelectedTab(minecraft, screen);
                    }
                }

                private static boolean selectCreativeTab(CreativeModeInventoryScreen screen, CreativeModeTab tab) {
                    List<CreativeModeTab> sortedTabs = CreativeModeTabRegistry.getSortedCreativeModeTabs().stream()
                        .filter(CreativeModeTab::hasAnyItems)
                        .toList();
                    int tabIndex = sortedTabs.indexOf(tab);
                    if (tabIndex < 0) {
                        fail("target creative tab disappeared before selection: " + describeTab(tab));
                        return false;
                    }

                    int pageStart = (tabIndex / 10) * 10;
                    int pageEnd = Math.min(pageStart + 10, sortedTabs.size());
                    CreativeTabsScreenPage page = new CreativeTabsScreenPage(new ArrayList<>(sortedTabs.subList(pageStart, pageEnd)));
                    screen.setCurrentPage(page);

                    currentTabName = describeTab(tab);
                    int tabX = tab.isAlignedRight() ? screen.getXSize() - 27 * (7 - page.getColumn(tab)) + 1 : 27 * page.getColumn(tab);
                    int tabY = page.isTop(tab) ? -32 : screen.getYSize();
                    double mouseX = screen.getGuiLeft() + tabX + 13.0D;
                    double mouseY = screen.getGuiTop() + tabY + 16.0D;
                    screen.mouseReleased(mouseX, mouseY, 0);
                    System.out.println("[ModPorterBenchmark] Selected creative tab " + currentTabName + " with " + tab.getDisplayItems().size() + " item stack(s)");
                    return true;
                }

                private static boolean tabContainsNamespace(CreativeModeTab tab, String namespace) {
                    ResourceLocation tabName = CreativeModeTabRegistry.getName(tab);
                    if (tabName != null && namespace.equals(tabName.getNamespace())) {
                        return true;
                    }
                    for (ItemStack stack : tab.getDisplayItems()) {
                        if (stack.isEmpty()) {
                            continue;
                        }
                        ResourceLocation itemName = BuiltInRegistries.ITEM.getKey(stack.getItem());
                        if (itemName != null && namespace.equals(itemName.getNamespace())) {
                            return true;
                        }
                    }
                    return false;
                }

                private static void browseSelectedTab(Minecraft minecraft, CreativeModeInventoryScreen screen) {
                    CreativeModeInventoryScreen.ItemPickerMenu menu = screen.getMenu();
                    if (expectedItems.isEmpty()) {
                        Set<Integer> currentItems = itemKeys(menu.items);
                        if (currentItems.isEmpty()) {
                            fail("creative tab has no displayed menu items after selection: " + currentTabName);
                            return;
                        }
                        if (!currentItems.equals(stableMenuItems)) {
                            stableMenuItems = currentItems;
                            stableMenuTicks = 0;
                            return;
                        }
                        stableMenuTicks++;
                        if (stableMenuTicks < 2) {
                            return;
                        }
                        expectedItems = itemKeys(menu.items);
                    }
                    int totalRows = Math.max(1, (menu.items.size() + 8) / 9);
                    int maxRow = Math.max(0, totalRows - 5);
                    if (browseRow <= maxRow) {
                        float scroll = maxRow == 0 ? 0.0F : (float) browseRow / (float) maxRow;
                        menu.scrollTo(scroll);
                        observeMenuItems(menu, browseRow);
                        if (browseRow == 0 || browseRow == maxRow || browseRow % 5 == 0) {
                            System.out.println("[ModPorterBenchmark] Browsed creative tab " + currentTabName + " row " + browseRow + "/" + maxRow);
                        }
                        browseRow++;
                        return;
                    }

                    if (!observedItems.containsAll(expectedItems)) {
                        Set<Integer> missing = new HashSet<>(expectedItems);
                        missing.removeAll(observedItems);
                        fail("creative tab browse missed " + missing.size() + " item stack(s) in " + currentTabName);
                        return;
                    }

                    System.out.println("[ModPorterBenchmark] Creative tab verified: " + currentTabName + " items=" + expectedItems.size());
                    targetTabIndex++;
                    if (targetTabIndex >= targetTabs.size()) {
                        browseState = BrowseState.COMPLETE;
                        System.out.println("[ModPorterBenchmark] Creative tab browse complete: tabs=" + targetTabs.size());
                        minecraft.stop();
                    } else {
                        browseState = BrowseState.SELECT_TAB;
                    }
                }

                private static void observeMenuItems(CreativeModeInventoryScreen.ItemPickerMenu menu, int firstRow) {
                    for (int slot = 0; slot < Math.min(45, menu.slots.size()); slot++) {
                        observeStack(menu.getSlot(slot).getItem());
                    }

                    int firstIndex = Math.max(0, firstRow * 9);
                    int lastIndex = Math.min(menu.items.size(), firstIndex + 45);
                    for (int index = firstIndex; index < lastIndex; index++) {
                        observeStack(menu.items.get(index));
                    }
                }

                private static void observeStack(ItemStack stack) {
                    if (!stack.isEmpty()) {
                        observedItems.add(ItemStack.hashItemAndComponents(stack));
                    }
                }

                private static Set<Integer> itemKeys(Iterable<ItemStack> stacks) {
                    Set<Integer> keys = new HashSet<>();
                    for (ItemStack stack : stacks) {
                        if (!stack.isEmpty()) {
                            keys.add(ItemStack.hashItemAndComponents(stack));
                        }
                    }
                    return keys;
                }

                private static String describeTab(CreativeModeTab tab) {
                    ResourceLocation name = CreativeModeTabRegistry.getName(tab);
                    return name == null ? tab.getDisplayName().getString() : name.toString();
                }

                private static void fail(String message) {
                    if (browseState == BrowseState.FAILED) {
                        return;
                    }
                    browseState = BrowseState.FAILED;
                    System.err.println("[ModPorterBenchmark] Creative tab browse failed: " + message);
                    Minecraft.getInstance().stop();
                }

                private static boolean dismissInitialScreen(Screen screen) {
                    String screenName = screen.getClass().getName();
                    if ("net.minecraft.client.gui.screens.AccessibilityOnboardingScreen".equals(screenName)) {
                        logWaitingScreen(screen, "Closing first-run accessibility onboarding");
                        screen.onClose();
                        return true;
                    }
                    return false;
                }

                private static void autoConfirm(Screen screen) {
                    confirmTicks++;
                    if (confirmTicks % 20 != 1) {
                        return;
                    }

                    System.out.println("[ModPorterBenchmark] Client world load screen: " + screen.getClass().getName());
                    Button button = selectProceedButton(screen);
                    if (button != null) {
                        press(button);
                    }
                }

                private static boolean confirmBlockingScreen(Screen screen) {
                    String screenName = screen.getClass().getName();
                    if (!"net.minecraft.client.gui.screens.BackupConfirmScreen".equals(screenName)) {
                        return false;
                    }

                    confirmTicks++;
                    if (confirmTicks % 20 != 1) {
                        return true;
                    }

                    logWaitingScreen(screen, "Confirming backup/experimental world screen");
                    Button button = selectProceedButton(screen);
                    if (button != null) {
                        press(button);
                    }
                    return true;
                }

                private static Button selectProceedButton(Screen screen) {
                    java.util.List<Button> buttons = new java.util.ArrayList<>();
                    for (GuiEventListener child : screen.children()) {
                        if (child instanceof Button button && button.active) {
                            buttons.add(button);
                        }
                    }
                    if (buttons.isEmpty()) {
                        return null;
                    }

                    String screenName = screen.getClass().getName();
                    if ("net.minecraft.client.gui.screens.BackupConfirmScreen".equals(screenName)) {
                        for (Button button : buttons) {
                            String message = normalized(button);
                            if (message.contains("i know") || message.contains("skip") || message.contains("proceed")) {
                                return button;
                            }
                        }
                        return buttons.size() > 1 ? buttons.get(1) : buttons.get(0);
                    }

                    for (Button button : buttons) {
                        String message = normalized(button);
                        if (message.contains("cancel") || message.contains("backup")) {
                            continue;
                        }
                        if (message.contains("continue") || message.contains("done") || message.contains("proceed") ||
                            message.contains("yes") || message.contains("load") || message.contains("play")) {
                            return button;
                        }
                    }

                    for (Button button : buttons) {
                        String message = normalized(button);
                        if (!message.contains("cancel") && !message.contains("backup")) {
                            return button;
                        }
                    }
                    return null;
                }

                private static String normalized(Button button) {
                    return button.getMessage().getString().toLowerCase(Locale.ROOT);
                }

                private static void press(Button button) {
                    System.out.println("[ModPorterBenchmark] Auto-confirming client world load screen via button: " + button.getMessage().getString());
                    button.onPress();
                }

                private static void logWaitingScreen(Screen screen, String message) {
                    String screenName = screen.getClass().getName();
                    if (!screenName.equals(lastWaitingScreen) || ticks % 100 == 0) {
                        lastWaitingScreen = screenName;
                        System.out.println("[ModPorterBenchmark] " + message + "; current screen: " + screenName);
                    }
                }
            }
        """.trimIndent() + "\n"

        private val clientStartChecks = listOf(
            RuntimeMarkerCheck("client resources loaded", listOf(Regex("""Reloading ResourceManager"""))),
            RuntimeMarkerCheck("client audio initialized", listOf(Regex("""OpenAL initialized"""))),
            RuntimeMarkerCheck("client sound engine started", listOf(Regex("""Sound engine started""")))
        )

        private val runtimeFatalPatterns = listOf(
            Regex("""\[[^\]]*/(?:ERROR|FATAL)]"""),
            Regex("""---- Minecraft Crash Report ----"""),
            Regex("""Crash report saved"""),
            Regex("""Failed to start the minecraft server"""),
            Regex("""Failed to start Minecraft server"""),
            Regex("""Starting integrated server"""),
            Regex("""ModLoadingException"""),
            Regex("""Mod loading has failed"""),
            Regex("""has failed to load correctly"""),
            Regex("""encountered an error while dispatching"""),
            Regex("""Exception caught during firing event"""),
            Regex("""\[ModPorterBenchmark] Failed to request client smoke world load"""),
            Regex("""\[ModPorterBenchmark] Creative tab browse failed:""")
        )

        private val runtimeWarningPatterns = listOf(
            Regex("""\[[^\]]*/WARN]"""),
            Regex("""^WARN\s""")
        )

        private val allowedRuntimeLogFragments = listOf(
            "Advanced terminal features are not available in this environment",
            "[minecraft/Commands]: Ambiguity between arguments",
            "could not be read. If this is a development environment you can ignore this message",
            "Assets URL 'union:",
            "Failed to process update information",
            "Class version 65 required is higher than the class version supported by the current version of Mixin",
            // Upstream dependency noise from Patchouli/MMLib jars, not emitted by converted project source.
            "Could not locate JEI keybindings, lookups in books may not work",
            "File mmlib:sounds/presented_by_zaia.ogg does not exist, cannot add it to event mysterious_mountain_lib:presented_by_zaia",
            "Missing subtitle translation{key='mmlib.sound.presented_by_zaia'",
            "Missing sound for event: minecraft:item.goat_horn.play",
            "Missing sound for event: minecraft:entity.goat.screaming.horn_break",
            "Shader rendertype_entity_translucent_emissive could not find sampler named Sampler2",
            "[mojang/YggdrasilMinecraftSessionService]: Couldn't look up profile properties",
            "Deprecated Gradle features were used in this build",
            "You can use '--warning-mode all'",
            "For more on this, please refer to https://docs.gradle.org/"
        )
    }
}
