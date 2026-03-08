# Build Verification Experiment Design

## Objective

Prove that Forge2Neo can convert a Forge 1.20.1 mod's source code such that the output
**compiles successfully** against NeoForge 1.21.1. This is the ultimate correctness test:
a migration tool is only useful if the migrated code can actually build.

## Experiment Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Build Verification Pipeline                   │
│                                                                 │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐ │
│  │  Forge    │    │ Forge2Neo│    │ Build    │    │  Report  │ │
│  │  1.20.1   │───▶│ Pipeline │───▶│ System   │───▶│ Analysis │ │
│  │  Source   │    │ (5 Pass) │    │  Patch   │    │          │ │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘ │
│       ▲                                ▼              ▼        │
│       │                         ┌──────────┐   ┌──────────┐   │
│       │                         │ gradlew  │   │ Error    │   │
│  Test Fixture                   │ compile  │   │ Catalog  │   │
│  Mods (synthetic                │ Java     │   │ + Metrics│   │
│  + real-world)                  └──────────┘   └──────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Three-Layer Testing Strategy

### Layer 1: Synthetic Test Fixtures (In-Process, Fast)

Create minimal synthetic Forge 1.20.1 "mod" projects as test fixtures. Each fixture
exercises one specific API pattern. These are deterministic and run as JUnit tests.

| Fixture | API Pattern | Expected Transformation |
|---------|------------|------------------------|
| `basic-item-mod` | DeferredRegister, @Mod, items/blocks | Package renames, registry changes |
| `event-mod` | @EventBusSubscriber, SubscribeEvent | Bus subscriber annotation changes |
| `capability-mod` | ICapabilityProvider, LazyOptional | Capability→Attachment migration |
| `network-mod` | SimpleChannel, NetworkRegistry | CustomPacketPayload migration |
| `config-mod` | ForgeConfigSpec | ModConfigSpec migration |
| `resource-mod` | mods.toml, data folders, pack.mcmeta | Resource migration |
| `datagen-mod` | Data generation classes | Package + class renames |

Each fixture includes:
- `build.gradle` (Forge 1.20.1 MDK format)
- `src/main/java/...` (1-3 Java files exercising the pattern)
- `src/main/resources/META-INF/mods.toml`
- Expected output files for comparison

**Verification**: After Forge2Neo processes the fixture:
1. All `net.minecraftforge` references are gone
2. All class renames are applied
3. `build.gradle` references NeoForge
4. `neoforge.mods.toml` exists
5. No remaining Forge-specific patterns (validate command)

### Layer 2: Compilation Verification (Gradle, Medium Speed)

For each synthetic fixture, verify the converted code compiles against NeoForge 1.21.1:

```bash
# For each fixture:
1. Copy fixture to temp directory
2. Run forge2neo port --src <fixture> --out <output>
3. cd <output>
4. ./gradlew compileJava --no-daemon
5. Capture exit code + stderr
6. Parse compilation errors
7. Classify errors by category
```

**NeoForge 1.21.1 build.gradle.kts template** (what the BuildSystemPass should produce):
```kotlin
plugins {
    id 'java-library'
    id 'net.neoforged.moddev' version '2.0.140'
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = '21.1.219'
    parchment {
        minecraftVersion = '1.21.1'
        mappingsVersion = '2024.11.17'
    }
    runs {
        create("client") { client() }
        create("server") { server() }
    }
}

// settings.gradle only needs gradlePluginPortal() (ModDevGradle is published there)
```

### Layer 3: Real-World Mod Validation (External, Slow)

Test against actual open-source Forge 1.20.1 mods from GitHub:

**Candidate Selection Criteria**:
- Open source (MIT/Apache/similar)
- Forge 1.20.1 target
- < 20 source files (to keep build times manageable)
- Working build.gradle with Forge MDK
- Different API surface coverage

**Target Candidates** (confirmed by research):

| # | Mod | Java Files | Key APIs | License | Difficulty |
|---|-----|-----------|----------|---------|------------|
| 0 | [NeoForgeMDKs/MDK-Forge-1.20.1](https://github.com/NeoForgeMDKs/MDK-Forge-1.20.1-ModDevGradle) | 2 | @Mod, Config, build.gradle | Public | Trivial |
| 1 | [Kaupenjoe/Forge-Tutorial `3-customBlocks`](https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X/tree/3-customBlocks) | 4 | DeferredRegister, RegistryObject, ForgeRegistries | MIT | Easy |
| 2 | [Kaupenjoe/Forge-Tutorial `10-tooltips`](https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X/tree/10-tooltips) | 8 | IForgeItem, FuelItem, custom behaviors | MIT | Medium |
| 3 | [Kaupenjoe/Forge-Tutorial `12-datagen`](https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X/tree/12-datagen) | 17 | Datagen providers, tags, loot, recipes | MIT | Medium |
| 4 | [martreedev/TPose-Animation](https://github.com/martreedev/Minecraft-Forge-1.20.1-TPose-Animation-With-Keybind) | 11 | Capabilities, SimpleChannel, Events | None* | Hard |
| 5 | [Kaupenjoe/Forge-Tutorial `17-fullArmorEffect`](https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X/tree/17-fullArmorEffect) | 20 | ArmorMaterial, Tier, full datagen | MIT | Hard |

**Recommended test order**: 0 → 1 → 2 → 3 → 4 → 5 (increasing complexity)

**Process**:
```
git clone <mod-repo> --depth 1
forge2neo port --src . --out ./neoforge-out --report report.md
cd neoforge-out
./gradlew compileJava 2>&1 | tee build-log.txt
python3 classify_errors.py build-log.txt
```

## Error Classification System

When compilation fails, errors are classified to identify which pipeline pass
should have handled them:

| Category | Example Error | Responsible Pass |
|----------|--------------|-----------------|
| `MISSING_IMPORT` | `cannot find symbol: class IForgeItem` | Pass 1 (Text Replace) |
| `WRONG_PACKAGE` | `package net.minecraftforge.common does not exist` | Pass 1 (Text Replace) |
| `MISSING_METHOD` | `cannot find symbol: method getCapability(...)` | Pass 2 (AST) or Pass 3 (Structural) |
| `TYPE_MISMATCH` | `incompatible types: LazyOptional cannot be converted to...` | Pass 3 (Structural) |
| `MISSING_CLASS` | `cannot find symbol: class SimpleChannel` | Pass 1 or Pass 3 |
| `STRUCTURAL` | `method does not override superclass method` | Pass 3 (Structural) |
| `BUILD_SYSTEM` | `Could not resolve net.minecraftforge:forge` | Pass 4 (Build System) |
| `RESOURCE` | Resource file not found at expected path | Pass 5 (Resources) |
| `UNKNOWN` | Unclassifiable error | Manual review needed |

## Metrics

For each test mod, we track:

| Metric | Formula | Target |
|--------|---------|--------|
| **File Conversion Rate** | files_without_errors / total_files × 100 | > 90% |
| **Error Density** | total_errors / total_lines × 1000 | < 5 per KLOC |
| **Auto-Fix Rate** | auto_fixed_patterns / total_forge_patterns × 100 | > 80% |
| **Build Success** | binary: compileJava exits 0 | YES for synthetics |
| **Manual Effort** | errors requiring manual intervention | < 20% of total |

## Implementation Plan

### Phase 1: Synthetic Fixtures (this sprint)
1. Implement `BuildSystemPass` (Pass 4) — transforms build.gradle
2. Create 7 synthetic test fixture mods
3. Write `BuildVerificationTest.kt` — runs pipeline + validates output
4. Run all fixtures through pipeline and validate correctness

### Phase 2: Compilation Testing (requires NeoForge MDK)
1. Set up NeoForge 1.21.1 build environment
2. Write `build-experiment.sh` orchestration script
3. Run synthetic fixtures through actual `gradlew compileJava`
4. Collect and classify all compilation errors
5. Fix pipeline rules based on error analysis

### Phase 3: Real-World Validation
1. Clone 3-5 real Forge 1.20.1 mods
2. Run full pipeline on each
3. Attempt compilation
4. Iterate on rules until compilation succeeds
5. Document remaining manual-intervention patterns

## Feedback Loop

```
                    ┌──────────────────┐
                    │  Build Error     │
                    │  Classification  │
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
     ┌────────────┐  ┌────────────┐  ┌────────────┐
     │ Add mapping│  │ Add AST    │  │ Flag for   │
     │ rule to    │  │ transform  │  │ manual     │
     │ JSON db    │  │ pattern    │  │ review     │
     └────────────┘  └────────────┘  └────────────┘
              │              │              │
              └──────────────┼──────────────┘
                             ▼
                    ┌──────────────────┐
                    │  Re-run pipeline │
                    │  + rebuild       │
                    └──────────────────┘
```

Each build failure feeds directly back into improving the migration rules,
creating a virtuous cycle that drives toward 100% compilation success.
