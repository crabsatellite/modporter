# ModPorter

General-purpose Minecraft mod migration tool. Currently supports **Forge 1.20.1 → NeoForge 1.21.1**.

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

1. **TextReplacement** — Package renames, API changes, import migrations (~95 rules)
2. **AST** — Structural Java transformations (CustomPacketPayload, BaseEntityBlock codec)
3. **StructuralRefactor** — Event bus cleanup, mod-bus event extraction, obsolete method removal
4. **BuildSystem** — build.gradle rewrite (ForgeGradle → NeoForge moddev), source exclusions, dependency cleanup
5. **ResourceMigration** — mods.toml format, recipe/advancement JSON updates

### Tested With

| Mod | Source | Status |
|-----|--------|--------|
| ConstructionWand | Forge 1.20.1 | compileJava ✅ runServer ✅ |
| Radios | Forge 1.20.1 | compileJava ✅ runServer ✅ |
| LeaningTower | Forge 1.20.1 | compileJava ✅ runServer ✅ |
| VillagerTourism | Forge 1.20.1 | compileJava ✅ runServer ✅ |
| ping_system | Forge 1.20.1 | compileJava ✅ runServer ✅ |

### Limitations

Some Forge → NeoForge changes require manual intervention:
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

Requires: Java 17+, Kotlin 1.9.22
