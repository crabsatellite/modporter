# ModPorter

> **Work in progress / developer preview:** ModPorter is under active development. It is already useful for developers moving Forge 1.20.1 projects to NeoForge 1.21.1, and the current benchmark milestone demonstrates hands-off strict runtime ports for the benchmark set currently committed to this repository, including large public mods. That result is evidence for the rules covered so far, not a guarantee that the next untested Forge 1.20.1 mod will port without engineering work. A port is only considered successful when the automated build, dedicated server, GameTest server, client boot, client saved-world load, and log-clean gates pass.

## What ModPorter Is

ModPorter is a ruleset-driven migration project. Its value comes from collecting migration
patterns that have appeared in real mods, generalizing those patterns into deterministic source,
build, and resource transformations, and then protecting them with regression tests and strict
runtime gates.

Passing the currently configured benchmark mods does not mean an untested mod you try next is
guaranteed to work. A new mod may use Forge APIs, library surfaces, project layouts, data formats,
or runtime behaviors that none of the current benchmarks exercise yet. In that case the right next
step is not a one-off bypass for that mod, but a new general rule that captures the underlying
pattern and keeps all previously supported mods passing.

The intended outcome is cumulative engineering reuse: once a migration pattern is implemented and
gated, future ports should not repeat that porting work. As the ruleset grows, ModPorter should
become more broadly useful, while still remaining honest about its coverage. This is why the
project is positioned as a developer time-saver, not a universal one-click converter.

Contributions are welcome when they expand general migration coverage without regression. A useful
PR should add or improve deterministic rules, include focused tests, and preserve the existing
strict benchmark results. The long-term direction is not limited to Forge 1.20.1 -> NeoForge
1.21.1; the same gate-driven approach can support multiple Minecraft version migration pipelines.

General-purpose Minecraft mod migration tool. Currently supports **Forge 1.20.1 -> NeoForge 1.21.1**.

## Current Milestone

As of the `v0.3.0` release candidate, the strict real-mod gate has been locally verified for the public Git-backed benchmark snapshot below:

<!-- MODPORTER:BENCHMARK-SNAPSHOT:START -->
| Target | Provider | Source | Resolved Git commit | Strict gate status |
|--------|----------|--------|---------------------|--------------------|
| ConstructionWand | Git | `Theta-Dev/ConstructionWand`, `1.20` | `bc64f11a7d799e921995821878a031bcfde4e22a` | PASS |
| InstantWorldMirror | Git | `crabsatellite/InstantWorldMirror`, `1.20.1` | `eb51c07f0c3353d7b5cc3787642615a043e122bf` | PASS |
| HotBath | Git | `crabsatellite/hotBath`, `1.20.1` | `6b2d0925cf04d58fc6dc21e687ef98324ac6d8cc` | PASS |
| ShowerCore | Git | `crabsatellite/ShowerCore`, `1.20.1` with `hotbath` dependency | `0b25a6d04ca7daa6dbdfb25d94434a8e7f8edaee` | PASS |
| Sakura Mod | Git | `0999312/Sakura_mod`, `1.20.1` | `0201301e5aa371d7f0816b1d786ca89e99936912` | PASS |
| Twilight Forest | Git | `TeamTwilight/twilightforest`, `1.20.1` | `1bc3a4c21213bc443967e92125613cb9ef47891e` | PASS |
| The Aether | Git | `The-Aether-Team/The-Aether`, `1.20.1-develop` | `2f0be3a51bae2f434fbf5b5c0aecc56b50f921b7` | PASS |
| Beyond the Veil | Git | `valeriotor/Beyond-The-Veil`, `1.20` | `ce8dfdbca00d956516c23de989d45bd138ee6867` | PASS |
<!-- MODPORTER:BENCHMARK-SNAPSHOT:END -->

Each PASS means that benchmark target passed hands-off compile, dedicated server lifecycle, GameTest server, client boot, saved-world quick-load, and warning-clean runtime log gates at the resolved Git commit shown in the table. The table is a reproducible coverage record, not a fixed compatibility catalog and not a promise that an unlisted mod will pass. Remaining allowed log findings must be machine-evidenced as source-inherited behavior or external dependency behavior; benchmark-specific bypasses are not accepted.

This is a publishable **developer-preview milestone**, not a final compatibility guarantee. The benchmark harness is the source of truth: new mods should be treated as unsupported until they pass the strict gate, and failures should become deterministic migration rules or explicit evidence-backed allowlist entries. The committed benchmark manifest is Git-backed so the release gate is reproducible outside one maintainer machine; local source paths are reserved for explicit developer overrides.

## Quick Start

```bash
# Build the fat JAR
./gradlew shadowJar

# Port a mod
java -jar build/libs/modporter-0.3.0-all.jar port \
  --src /path/to/forge-mod \
  --out /path/to/output

# Optionally add a tool credit to supported mod metadata
java -jar build/libs/modporter-0.3.0-all.jar port \
  --src /path/to/forge-mod \
  --out /path/to/output \
  --add-tool-credit

# List available pipelines
java -jar build/libs/modporter-0.3.0-all.jar list

# Analyze a mod (dry run)
java -jar build/libs/modporter-0.3.0-all.jar analyze --src /path/to/forge-mod
```

## Pipeline: forge2neo

Migrates Forge 1.20.1 mods to NeoForge 1.21.1 using a 5-pass pipeline:

1. **TextReplacement** - Package renames, API changes, import migrations (~95 rules)
2. **AST** - Structural Java transformations (CustomPacketPayload, BaseEntityBlock codec)
3. **StructuralRefactor** - Event bus cleanup, mod-bus event extraction, obsolete method removal
4. **BuildSystem** - build.gradle rewrite (ForgeGradle -> NeoForge ModDev), Access Transformer and mixin metadata preservation, dependency cleanup, source-set hygiene
5. **ResourceMigration** - mods.toml format, recipe/advancement JSON updates

### Rule Policy

ModPorter should not use benchmark-specific shortcuts to make a particular mod pass. Production migration rules are expected to be source-structure or API-surface rules with regression coverage, and strict validation rejects incomplete behavior such as TODO migrations, commented-out source logic, source excludes, skipped structural parsing, and unverified runtime warnings.

The codebase does contain explicit adapters and dependency mappings for real modding APIs and libraries such as Nitrogen, Cumulus, Quark, JEI, Jade, Curios, and similar ecosystem surfaces. Those are treated as API compatibility rules, not per-target bypasses: they must be triggered by source/dependency evidence and covered by tests or strict benchmark gates.

### Verification Coverage

| Target | Automation level | Current signal |
|--------|------------------|----------------|
| Synthetic basic/event/config mods | Gated JUnit compile test | Converted code is compiled against the NeoForge 1.21.1 MDK when `FORGE2NEO_COMPILE_TEST=true` |
| Synthetic build/resource/capability/network fixtures | Default JUnit tests | Pipeline rewrites expected source/build/resource shapes and reports complex migrations |
| Real mod benchmark | Gated Gradle task | `realModBenchmark` fetches configured source providers, ports them in a temporary workspace, validates structure, and can optionally run compile/runtime gates |
| Strict real mod benchmark | Gated Gradle task | `strictRealModBenchmark` requires hands-off compile plus dedicated server lifecycle, GameTest server, client boot, client saved-world quick-load, and warning-clean logs |

See `docs/BENCHMARKING.md` for the current test modes and what each one proves.

Confidence filtering is currently a review/reporting control. Use `--dry-run --min-confidence high`
or `medium` to inspect a subset of proposed changes; apply mode requires the default `low`
threshold so the tool does not silently apply lower-confidence rewrites while hiding them from
the report.

### Optional Tool Credit

By default, ModPorter preserves mod author metadata and does not write itself into `authors`.
If you want the converted project to visibly acknowledge the migration tool, pass
`--add-tool-credit`. This appends a concise `Ported with ModPorter: https://github.com/crabsatellite/modporter`
entry to supported `credits` metadata, preserving existing credits and authors.

### Release Validation

A release candidate is ready only after the default tests, fat JAR build, and strict real-mod
benchmark pass locally:

```bash
./gradlew test
./gradlew shadowJar
./gradlew strictRealModBenchmark
```

The GitHub Actions workflow runs the default test suite and package build. The strict real-mod
benchmark is intentionally treated as a maintainer-run release gate because it needs large public
Git sources, Minecraft runtime launches, and longer machine time.

### Real Mod Benchmark

```bash
./gradlew realModBenchmark

# Strict success gate
./gradlew strictRealModBenchmark
```

```powershell
# Reproduce one configured target at a time
$env:MODPORTER_BENCHMARK_CASES="aether"; ./gradlew.bat strictRealModBenchmark --no-daemon
$env:MODPORTER_BENCHMARK_CASES="twilightforest"; ./gradlew.bat strictRealModBenchmark --no-daemon
```

`realModBenchmark` reads `src/test/resources/benchmarks/real-mods.tsv`, ports each available
target in a temporary workspace, deletes fetched/converted sources by default, and writes summaries
under `build/real-mod-benchmark/reports/`. Use it as the engineering loop for real mods; see
`docs/BENCHMARKING.md` for provider configuration and runtime gates. A mod should only be called
successfully ported when the strict runtime benchmark passes.

### Open Automated Migration Work

These Forge -> NeoForge areas still need broader automated coverage before ModPorter can claim general compatibility beyond the current benchmark set:
- Enchantment (now data-driven JSON, no longer extensible class)
- Ingredient (now final, needs ICustomIngredient)
- LazyOptional/Capability system (full rewrite required)
- Complex recipe serializer changes
- Loot table format changes

## Development

```bash
# Run tests
./gradlew test

# Build with coverage report
./gradlew test jacocoTestReport
```

Requires: Java 17+ for the tool itself. NeoForge 1.21.1 compile/runtime verification requires Java 21.
