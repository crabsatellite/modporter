#!/usr/bin/env bash
#
# Build Verification Experiment
# Tests that Forge 1.20.1 mods converted by Forge2Neo can compile against NeoForge 1.21.1
#
# Usage: ./scripts/build-experiment.sh [--mod-dir <path>] [--all-fixtures]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${PROJECT_DIR}/build/experiment"
RESULTS_DIR="${WORK_DIR}/results"
FIXTURES_DIR="${WORK_DIR}/fixtures"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="${RESULTS_DIR}/report_${TIMESTAMP}.md"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() { echo -e "${BLUE}[EXPERIMENT]${NC} $*"; }
ok()  { echo -e "${GREEN}[OK]${NC} $*"; }
warn(){ echo -e "${YELLOW}[WARN]${NC} $*"; }
err() { echo -e "${RED}[FAIL]${NC} $*"; }

# ─── Setup ─────────────────────────────────────────────────────────────

setup() {
    log "Setting up experiment workspace..."
    mkdir -p "$WORK_DIR" "$RESULTS_DIR" "$FIXTURES_DIR"

    # Build forge2neo tool
    log "Building forge2neo..."
    cd "$PROJECT_DIR"
    ./gradlew installDist --no-daemon -q 2>/dev/null || {
        err "Failed to build forge2neo"
        exit 1
    }

    FORGE2NEO="${PROJECT_DIR}/build/install/forge2neo/bin/forge2neo"
    if [[ ! -f "$FORGE2NEO" ]]; then
        # Fallback: run via Gradle
        FORGE2NEO="$PROJECT_DIR/gradlew run --args"
        log "Using Gradle run fallback"
    fi
    ok "forge2neo built successfully"
}

# ─── Synthetic Fixture Generators ──────────────────────────────────────

create_basic_item_fixture() {
    local dir="${FIXTURES_DIR}/basic-item-mod"
    log "Creating fixture: basic-item-mod"
    mkdir -p "$dir/src/main/java/com/example/basicmod"
    mkdir -p "$dir/src/main/resources/META-INF"

    cat > "$dir/build.gradle" << 'GRADLE'
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'java'
}

group = 'com.example'
version = '1.0.0'

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: 'official', version: '1.20.1'
    runs {
        client { workingDirectory project.file('run') }
        server { workingDirectory project.file('run') }
    }
}

repositories {
    maven { url 'https://maven.minecraftforge.net/' }
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
}
GRADLE

    cat > "$dir/settings.gradle" << 'SETTINGS'
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
    }
}
rootProject.name = 'basic-item-mod'
SETTINGS

    cat > "$dir/gradle.properties" << 'PROPS'
org.gradle.jvmargs=-Xmx3G
minecraft_version=1.20.1
forge_version=47.2.0
mod_id=basicmod
PROPS

    cat > "$dir/src/main/java/com/example/basicmod/BasicMod.java" << 'JAVA'
package com.example.basicmod;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.item.Item;

@Mod("basicmod")
public class BasicMod {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, "basicmod");

    public static final RegistryObject<Item> MY_ITEM =
        ITEMS.register("my_item", () -> new Item(new Item.Properties()));

    public BasicMod() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
    }
}
JAVA

    cat > "$dir/src/main/resources/META-INF/mods.toml" << 'TOML'
modLoader="javafml"
loaderVersion="[47,)"
[[mods]]
modId="basicmod"
version="1.0.0"
displayName="Basic Item Mod"
[[dependencies.basicmod]]
modId="forge"
mandatory=true
versionRange="[47,)"
ordering="NONE"
side="BOTH"
TOML

    echo "$dir"
}

create_event_fixture() {
    local dir="${FIXTURES_DIR}/event-mod"
    log "Creating fixture: event-mod"
    mkdir -p "$dir/src/main/java/com/example/eventmod"
    mkdir -p "$dir/src/main/resources/META-INF"

    cat > "$dir/build.gradle" << 'GRADLE'
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'java'
}
minecraft {
    mappings channel: 'official', version: '1.20.1'
}
dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
}
GRADLE

    cat > "$dir/settings.gradle" << 'SETTINGS'
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
    }
}
SETTINGS

    cat > "$dir/src/main/java/com/example/eventmod/EventHandler.java" << 'JAVA'
package com.example.eventmod;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStartingEvent;

@Mod.EventBusSubscriber(modid = "eventmod")
public class EventHandler {
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        System.out.println("Server starting!");
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        System.out.println("Player logged in: " + event.getEntity().getName().getString());
    }
}
JAVA

    cat > "$dir/src/main/resources/META-INF/mods.toml" << 'TOML'
modLoader="javafml"
loaderVersion="[47,)"
[[mods]]
modId="eventmod"
version="1.0.0"
[[dependencies.eventmod]]
modId="forge"
mandatory=true
versionRange="[47,)"
TOML

    echo "$dir"
}

create_config_fixture() {
    local dir="${FIXTURES_DIR}/config-mod"
    log "Creating fixture: config-mod"
    mkdir -p "$dir/src/main/java/com/example/configmod"

    cat > "$dir/build.gradle" << 'GRADLE'
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'java'
}
minecraft {
    mappings channel: 'official', version: '1.20.1'
}
dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
}
GRADLE

    cat > "$dir/settings.gradle" << 'SETTINGS'
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
    }
}
SETTINGS

    cat > "$dir/src/main/java/com/example/configmod/ModConfig.java" << 'JAVA'
package com.example.configmod;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue MAX_ITEMS;
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("general");
        MAX_ITEMS = BUILDER.defineInRange("maxItems", 64, 1, 1000);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
JAVA

    echo "$dir"
}

# ─── Convert and Validate ──────────────────────────────────────────────

convert_mod() {
    local src_dir="$1"
    local mod_name="$(basename "$src_dir")"
    local out_dir="${WORK_DIR}/converted/${mod_name}"

    log "Converting: ${mod_name}"

    # Run forge2neo
    cd "$PROJECT_DIR"
    if [[ -f "${PROJECT_DIR}/build/install/forge2neo/bin/forge2neo" ]]; then
        "${PROJECT_DIR}/build/install/forge2neo/bin/forge2neo" port \
            --src "$src_dir" \
            --out "$out_dir" \
            --report "${RESULTS_DIR}/${mod_name}-report.md" 2>&1 || true
    else
        ./gradlew run --no-daemon --args="port --src $src_dir --out $out_dir --report ${RESULTS_DIR}/${mod_name}-report.md" 2>&1 || true
    fi

    echo "$out_dir"
}

validate_no_forge_refs() {
    local dir="$1"
    local mod_name="$(basename "$dir")"
    local count=0

    local patterns=("net.minecraftforge" "ForgeRegistries." "MinecraftForge.EVENT_BUS")

    for pattern in "${patterns[@]}"; do
        local hits=$(grep -r --include="*.java" -l "$pattern" "$dir" 2>/dev/null | wc -l)
        if [[ $hits -gt 0 ]]; then
            warn "${mod_name}: Found '$pattern' in $hits files"
            count=$((count + hits))
        fi
    done

    if [[ $count -eq 0 ]]; then
        ok "${mod_name}: No remaining Forge references in Java source"
    else
        err "${mod_name}: $count files still have Forge references"
    fi

    return $count
}

validate_build_files() {
    local dir="$1"
    local mod_name="$(basename "$dir")"
    local issues=0

    # Check build.gradle
    if [[ -f "$dir/build.gradle" ]]; then
        if grep -q "net.minecraftforge" "$dir/build.gradle"; then
            err "${mod_name}: build.gradle still references net.minecraftforge"
            issues=$((issues + 1))
        fi
        if grep -q "net.neoforged" "$dir/build.gradle"; then
            ok "${mod_name}: build.gradle references NeoForge"
        else
            warn "${mod_name}: build.gradle may be missing NeoForge references"
        fi
    fi

    # Check mods.toml
    if [[ -f "$dir/src/main/resources/META-INF/neoforge.mods.toml" ]]; then
        ok "${mod_name}: neoforge.mods.toml exists"
    else
        if [[ -f "$dir/src/main/resources/META-INF/mods.toml" ]]; then
            err "${mod_name}: mods.toml not renamed to neoforge.mods.toml"
            issues=$((issues + 1))
        fi
    fi

    return $issues
}

attempt_compilation() {
    local dir="$1"
    local mod_name="$(basename "$dir")"
    local log_file="${RESULTS_DIR}/${mod_name}-build.log"

    log "Attempting compilation: ${mod_name}"

    if [[ ! -f "$dir/gradlew" && ! -f "$dir/build.gradle" ]]; then
        warn "${mod_name}: No build system found, skipping compilation"
        return 1
    fi

    cd "$dir"

    # Try compileJava
    if [[ -f "$dir/gradlew" ]]; then
        chmod +x "$dir/gradlew"
        "$dir/gradlew" compileJava --no-daemon 2>&1 | tee "$log_file"
        local exit_code=${PIPESTATUS[0]}
    else
        warn "${mod_name}: No Gradle wrapper, skipping actual compilation"
        echo "SKIPPED: No Gradle wrapper available" > "$log_file"
        return 2
    fi

    if [[ $exit_code -eq 0 ]]; then
        ok "${mod_name}: Compilation SUCCESSFUL"
    else
        err "${mod_name}: Compilation FAILED (exit code: $exit_code)"
        # Classify errors
        classify_errors "$log_file" "$mod_name"
    fi

    return $exit_code
}

classify_errors() {
    local log_file="$1"
    local mod_name="$2"

    log "Classifying errors for ${mod_name}:"

    local missing_imports=$(grep -c "cannot find symbol" "$log_file" 2>/dev/null || echo 0)
    local missing_packages=$(grep -c "package .* does not exist" "$log_file" 2>/dev/null || echo 0)
    local type_errors=$(grep -c "incompatible types" "$log_file" 2>/dev/null || echo 0)
    local method_errors=$(grep -c "cannot find symbol.*method" "$log_file" 2>/dev/null || echo 0)
    local override_errors=$(grep -c "does not override" "$log_file" 2>/dev/null || echo 0)

    echo "  MISSING_IMPORT:  $missing_imports"
    echo "  MISSING_PACKAGE: $missing_packages"
    echo "  TYPE_MISMATCH:   $type_errors"
    echo "  MISSING_METHOD:  $method_errors"
    echo "  OVERRIDE_ERROR:  $override_errors"
}

# ─── Report Generation ─────────────────────────────────────────────────

generate_report() {
    local total_mods=$1
    local converted=$2
    local no_forge_refs=$3
    local compiled=$4

    cat > "$REPORT_FILE" << EOF
# Build Verification Experiment Report
**Date:** $(date '+%Y-%m-%d %H:%M:%S')
**Tool Version:** Forge2Neo v0.1.0

## Summary

| Metric | Value |
|--------|-------|
| Total test mods | $total_mods |
| Successfully converted | $converted |
| No remaining Forge refs | $no_forge_refs |
| Compilation success | $compiled |

## Conversion Rate
- Source transform: $((converted * 100 / total_mods))%
- Clean output: $((no_forge_refs * 100 / total_mods))%
- Build success: $((compiled * 100 / total_mods))%

## Per-Mod Results
$(for f in "${RESULTS_DIR}"/*-report.md; do
    if [[ -f "$f" ]]; then
        echo "### $(basename "$f" -report.md)"
        echo '```'
        head -20 "$f"
        echo '```'
        echo ""
    fi
done)

## Error Analysis
$(for f in "${RESULTS_DIR}"/*-build.log; do
    if [[ -f "$f" ]]; then
        local mod=$(basename "$f" -build.log)
        echo "### $mod build log"
        echo '```'
        tail -30 "$f"
        echo '```'
        echo ""
    fi
done)
EOF

    log "Report written to: $REPORT_FILE"
}

# ─── Main ──────────────────────────────────────────────────────────────

main() {
    log "═══════════════════════════════════════════════"
    log "  Forge2Neo Build Verification Experiment"
    log "═══════════════════════════════════════════════"
    echo ""

    setup

    # Create fixtures
    local fixtures=()
    fixtures+=($(create_basic_item_fixture))
    fixtures+=($(create_event_fixture))
    fixtures+=($(create_config_fixture))

    local total=${#fixtures[@]}
    local converted=0
    local no_forge=0
    local compiled=0

    # Convert and validate each fixture
    for fixture in "${fixtures[@]}"; do
        local mod_name="$(basename "$fixture")"
        echo ""
        log "───────────────────────────────────────────────"
        log "Processing: $mod_name"
        log "───────────────────────────────────────────────"

        # Convert
        local out_dir
        out_dir=$(convert_mod "$fixture")
        if [[ -d "$out_dir" ]]; then
            converted=$((converted + 1))

            # Validate no Forge references
            if validate_no_forge_refs "$out_dir"; then
                no_forge=$((no_forge + 1))
            fi

            # Validate build files
            validate_build_files "$out_dir"

            # Attempt compilation (if Gradle wrapper available)
            if attempt_compilation "$out_dir" 2>/dev/null; then
                compiled=$((compiled + 1))
            fi
        else
            err "Conversion failed for $mod_name"
        fi
    done

    # Generate report
    echo ""
    log "═══════════════════════════════════════════════"
    log "  RESULTS"
    log "═══════════════════════════════════════════════"
    echo ""
    echo "  Total mods tested:    $total"
    echo "  Successfully converted: $converted / $total"
    echo "  No Forge references:    $no_forge / $total"
    echo "  Compilation success:    $compiled / $total"
    echo ""

    generate_report "$total" "$converted" "$no_forge" "$compiled"
}

main "$@"
