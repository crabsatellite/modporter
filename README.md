# ModPorter

> **Work in progress:** ModPorter is under active development. It is already usable by developers and can automate a meaningful part of Forge 1.20.1 to NeoForge 1.21.1 migration work, but it is not yet a fully tested or fully hands-off porter. A port is only considered successful when the automated build, runtime, world-load, and log-clean gates pass; unresolved complex migrations are tracked as failing gates and unimplemented automated capabilities.

General-purpose Minecraft mod migration tool. Currently supports **Forge 1.20.1 -> NeoForge 1.21.1**.

## Quick Start

```bash
# Build the fat JAR
./gradlew shadowJar

# Port a mod
java -jar build/libs/modporter-0.2.0-all.jar port \
  --src /path/to/forge-mod \
  --out /path/to/output

# List available pipelines
java -jar build/libs/modporter-0.2.0-all.jar list

# Analyze a mod (dry run)
java -jar build/libs/modporter-0.2.0-all.jar analyze --src /path/to/forge-mod
```

## Pipeline: forge2neo

Migrates Forge 1.20.1 mods to NeoForge 1.21.1 using a 5-pass pipeline:

1. **TextReplacement** - Package renames, API changes, import migrations (~95 rules)
2. **AST** - Structural Java transformations (CustomPacketPayload, BaseEntityBlock codec)
3. **StructuralRefactor** - Event bus cleanup, mod-bus event extraction, obsolete method removal
4. **BuildSystem** - build.gradle rewrite (ForgeGradle -> NeoForge ModDev), source exclusions, dependency cleanup
5. **ResourceMigration** - mods.toml format, recipe/advancement JSON updates

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

### Real Mod Benchmark

```bash
./gradlew realModBenchmark

# Strict success gate
./gradlew strictRealModBenchmark
```

`realModBenchmark` reads `src/test/resources/benchmarks/real-mods.tsv`, ports each available
target in a temporary workspace, deletes fetched/converted sources by default, and writes summaries
under `build/real-mod-benchmark/reports/`. Use it as the engineering loop for real mods; see
`docs/BENCHMARKING.md` for provider configuration and runtime gates. A mod should only be called
successfully ported when the strict runtime benchmark passes.

### Open Automated Migration Work

These Forge -> NeoForge areas remain strict-gate blockers until structural/API rules and tests cover them:
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
