plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application
    jacoco
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.modporter"
version = "0.2.0"

repositories {
    mavenCentral()
}

val testKitRuntimeOnly by configurations.creating

dependencies {
    // Java AST parsing
    implementation("com.github.javaparser:javaparser-core:3.25.8")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.8")

    // CLI framework
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // JSON serialization for mapping rules
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines for parallel processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testCompileOnly(gradleTestKit())
    testKitRuntimeOnly(gradleTestKit())
}

application {
    mainClass.set("com.modporter.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
    filter {
        excludeTestsMatching("com.modporter.integration.CompilationVerificationTest")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
    }
}

tasks.register<Test>("testKitTest") {
    description = "Runs Gradle TestKit-backed integration tests in an isolated task"
    group = "verification"

    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + testKitRuntimeOnly
    filter {
        includeTestsMatching("com.modporter.integration.CompilationVerificationTest")
    }
    shouldRunAfter(tasks.test)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
    }
}

fun Test.defaultEnvironment(name: String, value: String) {
    if (System.getenv(name).isNullOrBlank()) {
        environment(name, value)
    }
}

tasks.register<Test>("realModBenchmark") {
    description = "Ports real mod benchmark targets from src/test/resources/benchmarks/real-mods.tsv"
    group = "verification"

    useJUnitPlatform()
    environment("MODPORTER_REAL_MOD_TEST", "true")
    filter {
        includeTestsMatching("com.modporter.integration.RealModBenchmarkTest")
    }
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "skipped", "failed", "standard_out", "standard_error")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}

tasks.register<Test>("strictRealModBenchmark") {
    description = "Ports real mod benchmark targets and requires strict runtime success gates"
    group = "verification"

    useJUnitPlatform()
    environment("MODPORTER_REAL_MOD_TEST", "true")
    defaultEnvironment("MODPORTER_BENCHMARK_STRICT_RUNTIME", "true")
    defaultEnvironment("MODPORTER_BENCHMARK_CASES", "constructionwand,instantworldmirror,hotbath,showercore,sakura,twilightforest,aether,beyondtheveil")
    defaultEnvironment("MODPORTER_BENCHMARK_TIMEOUT_SECONDS", "540")
    filter {
        includeTestsMatching("com.modporter.integration.RealModBenchmarkTest")
    }
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "skipped", "failed", "standard_out", "standard_error")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.modporter.cli.MainKt"
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}
