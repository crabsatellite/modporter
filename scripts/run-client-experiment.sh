#!/usr/bin/env bash
#
# runClient Verification Experiment
# Converts a Forge 1.20.1 mod with Forge2Neo, then attempts runClient
# to verify the mod loads and runs without crashes.
#
# Usage: ./scripts/run-client-experiment.sh --src <forge-mod-dir>
#
# Requirements:
# - Java 21
# - Internet access (downloads NeoForge + Minecraft assets ~1GB first run)
# - Display/GPU (Minecraft client needs OpenGL)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${PROJECT_DIR}/build/runclient-experiment"
RESULTS_DIR="${WORK_DIR}/results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log() { echo -e "${BLUE}[RUNCLIENT]${NC} $*"; }
ok()  { echo -e "${GREEN}[OK]${NC} $*"; }
warn(){ echo -e "${YELLOW}[WARN]${NC} $*"; }
err() { echo -e "${RED}[FAIL]${NC} $*"; }

# ─── Functions ─────────────────────────────────────────────────────────

build_forge2neo() {
    log "Building forge2neo..."
    cd "$PROJECT_DIR"
    export JAVA_HOME="${JAVA_HOME_17:-/c/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot}"
    ./gradlew installDist --no-daemon -q 2>/dev/null || {
        err "Failed to build forge2neo"
        exit 1
    }
    ok "forge2neo built"
}

convert_mod() {
    local src="$1"
    local out="${WORK_DIR}/converted"
    local report="${RESULTS_DIR}/conversion-report.md"

    log "Converting mod: $src → $out"
    rm -rf "$out"
    mkdir -p "$RESULTS_DIR"

    cd "$PROJECT_DIR"
    local f2n="${PROJECT_DIR}/build/install/forge2neo/bin/forge2neo"
    "$f2n" port --src "$src" --out "$out" --report "$report" 2>&1 | tee "${RESULTS_DIR}/conversion.log"

    ok "Conversion complete. Report: $report"
    echo "$out"
}

verify_structure() {
    local dir="$1"
    local issues=0

    log "Verifying converted project structure..."

    # Check build.gradle
    if [[ -f "$dir/build.gradle" ]]; then
        if grep -q "net.neoforged" "$dir/build.gradle"; then
            ok "build.gradle references NeoForge"
        else
            err "build.gradle missing NeoForge references"
            issues=$((issues + 1))
        fi
    fi

    # Check neoforge.mods.toml
    local toml="$dir/src/main/resources/META-INF/neoforge.mods.toml"
    if [[ -f "$toml" ]]; then
        ok "neoforge.mods.toml exists"

        # Verify key fields
        if grep -q 'modId="neoforge"' "$toml" || grep -q "modId=\"neoforge\"" "$toml"; then
            ok "neoforge dependency declared"
        else
            warn "neoforge dependency may be missing"
        fi

        if grep -q 'loaderVersion="\[1,' "$toml"; then
            ok "loaderVersion is [1,)"
        else
            warn "loaderVersion may need updating (expected [1,))"
        fi

        if grep -q 'type="required"' "$toml"; then
            ok "mandatory→type migration done"
        elif grep -q 'mandatory=' "$toml"; then
            err "Still has mandatory= field (should be type=)"
            issues=$((issues + 1))
        fi
    else
        err "neoforge.mods.toml not found"
        issues=$((issues + 1))
    fi

    # Check for remaining Forge references in Java
    local forge_refs=$(grep -rl --include="*.java" "net.minecraftforge" "$dir/src" 2>/dev/null | wc -l)
    if [[ $forge_refs -eq 0 ]]; then
        ok "No net.minecraftforge references in Java source"
    else
        err "$forge_refs files still reference net.minecraftforge"
        issues=$((issues + 1))
    fi

    # Check pack.mcmeta
    local pack="$dir/src/main/resources/pack.mcmeta"
    if [[ -f "$pack" ]]; then
        if grep -q '"pack_format": 48' "$pack" || grep -q '"pack_format":48' "$pack"; then
            ok "pack_format is 48"
        else
            warn "pack_format may need updating (expected 48)"
        fi
    fi

    # Check data folder depluralisation
    if [[ -d "$dir/src/main/resources/data" ]]; then
        for old in "tags/items" "tags/blocks" "recipes" "loot_tables" "advancements"; do
            if find "$dir/src/main/resources/data" -type d -name "$(basename $old)" 2>/dev/null | grep -q .; then
                warn "Old plural folder still exists: $old"
            fi
        done
    fi

    return $issues
}

attempt_compile() {
    local dir="$1"
    log "Attempting compilation..."

    cd "$dir"
    if [[ ! -f "$dir/gradlew" ]]; then
        warn "No Gradle wrapper. Attempting to use system Gradle."
        gradle compileJava --no-daemon 2>&1 | tee "${RESULTS_DIR}/compile.log"
    else
        chmod +x "$dir/gradlew"
        "$dir/gradlew" compileJava --no-daemon --stacktrace 2>&1 | tee "${RESULTS_DIR}/compile.log"
    fi

    local rc=${PIPESTATUS[0]}
    if [[ $rc -eq 0 ]]; then
        ok "Compilation SUCCESSFUL"
    else
        err "Compilation FAILED (exit code: $rc)"
        python3 "${SCRIPT_DIR}/classify-errors.py" "${RESULTS_DIR}/compile.log" 2>/dev/null || true
    fi
    return $rc
}

attempt_runclient() {
    local dir="$1"
    local timeout_secs=${2:-120}

    log "Attempting runClient (timeout: ${timeout_secs}s)..."
    log "The client will launch. Check for crash logs."

    cd "$dir"
    chmod +x "$dir/gradlew" 2>/dev/null || true

    # Run in background so we can monitor and kill properly
    # On Windows, 'timeout' command may not propagate signals to child JVMs
    "$dir/gradlew" runClient --no-daemon 2>&1 | tee "${RESULTS_DIR}/runclient.log" &
    local gradle_pid=$!

    # Wait up to timeout_secs, checking every 5s for crash or successful load
    local elapsed=0
    local mod_loaded=false
    while [[ $elapsed -lt $timeout_secs ]]; do
        sleep 5
        elapsed=$((elapsed + 5))

        # Check if process already exited
        if ! kill -0 "$gradle_pid" 2>/dev/null; then
            break
        fi

        # Check for mod loading success in log
        local latest_log="$dir/run/logs/latest.log"
        if [[ -f "$latest_log" ]]; then
            if grep -q "Mod loading complete" "$latest_log" 2>/dev/null; then
                ok "Mod loading complete (after ${elapsed}s)"
                mod_loaded=true
                # Give it a few more seconds to stabilize, then kill
                sleep 10
                break
            fi
        fi

        # Check for early crash
        if [[ -d "$dir/run/crash-reports" ]] && ls -A "$dir/run/crash-reports" 2>/dev/null | grep -q .; then
            err "CLIENT CRASHED during startup"
            break
        fi

        log "  Waiting... (${elapsed}/${timeout_secs}s)"
    done

    # Kill the Gradle process tree
    if kill -0 "$gradle_pid" 2>/dev/null; then
        log "Killing Gradle process tree (pid=$gradle_pid)..."
        # Try graceful kill first, then force
        kill "$gradle_pid" 2>/dev/null || true
        sleep 3
        kill -9 "$gradle_pid" 2>/dev/null || true
        # Also kill any remaining java processes from runClient
        # (Gradle spawns a separate JVM for the client)
        pkill -f "net.minecraft" 2>/dev/null || true
    fi
    wait "$gradle_pid" 2>/dev/null || true

    # Check for crash reports
    if [[ -d "$dir/run/crash-reports" ]] && ls -A "$dir/run/crash-reports" 2>/dev/null | grep -q .; then
        err "CLIENT CRASHED — crash report found"
        local crash=$(ls -t "$dir/run/crash-reports/"*.txt 2>/dev/null | head -1)
        if [[ -n "$crash" ]]; then
            log "Crash report:"
            head -50 "$crash"
            cp "$crash" "${RESULTS_DIR}/crash-report.txt"
        fi
        return 1
    fi

    # Check logs for mod loading success
    local latest_log="$dir/run/logs/latest.log"
    if [[ -f "$latest_log" ]]; then
        if grep -q "Mod loading complete" "$latest_log" 2>/dev/null; then
            ok "Mod loading complete"
        elif grep -q "Loading.*complete" "$latest_log" 2>/dev/null; then
            ok "Loading appears complete"
        fi

        # Check for fatal errors (skip common harmless ones)
        if grep -i "FATAL\|Critical error\|crashed" "$latest_log" 2>/dev/null | grep -v "shader" | head -5; then
            err "Fatal errors found in client log"
            cp "$latest_log" "${RESULTS_DIR}/client-latest.log"
            return 1
        fi

        cp "$latest_log" "${RESULTS_DIR}/client-latest.log"
    fi

    if [[ "$mod_loaded" == "true" ]]; then
        ok "runClient SUCCESS — mod loaded without crashes"
        return 0
    elif [[ $elapsed -ge $timeout_secs ]]; then
        warn "Client ran for ${timeout_secs}s without crashing (timeout)"
        return 0
    else
        warn "Client exited early — check logs"
        return 1
    fi
}

# ─── Report ────────────────────────────────────────────────────────────

generate_report() {
    local src="$1"
    local compile_ok="$2"
    local runclient_ok="$3"

    cat > "${RESULTS_DIR}/report_${TIMESTAMP}.md" << EOF
# runClient Verification Report
**Date:** $(date '+%Y-%m-%d %H:%M:%S')
**Source mod:** $src

## Results

| Step | Status |
|------|--------|
| Conversion | OK |
| Structure validation | OK |
| Compilation | $(if [[ "$compile_ok" == "0" ]]; then echo "PASS"; else echo "FAIL"; fi) |
| runClient | $(if [[ "$runclient_ok" == "0" ]]; then echo "PASS"; else echo "FAIL/SKIP"; fi) |

## Files
- Conversion report: conversion-report.md
- Compile log: compile.log
- runClient log: runclient.log (if attempted)
- Crash report: crash-report.txt (if crashed)
EOF
}

# ─── Main ──────────────────────────────────────────────────────────────

main() {
    local src=""
    local skip_runclient=false
    local timeout=120

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --src) src="$2"; shift 2 ;;
            --skip-runclient) skip_runclient=true; shift ;;
            --timeout) timeout="$2"; shift 2 ;;
            *) echo "Unknown option: $1"; exit 1 ;;
        esac
    done

    if [[ -z "$src" ]]; then
        echo "Usage: $0 --src <forge-mod-dir> [--skip-runclient] [--timeout 120]"
        exit 1
    fi

    log "═══════════════════════════════════════════════════"
    log "  Forge2Neo runClient Verification Experiment"
    log "═══════════════════════════════════════════════════"
    echo ""

    mkdir -p "$WORK_DIR" "$RESULTS_DIR"

    # Step 1: Build forge2neo
    build_forge2neo

    # Step 2: Convert
    local out
    out=$(convert_mod "$src")

    # Step 3: Verify structure
    verify_structure "$out"

    # Step 4: Compile
    local compile_rc=1
    if attempt_compile "$out"; then
        compile_rc=0
    fi

    # Step 5: runClient (optional)
    local runclient_rc=1
    if [[ "$skip_runclient" == "false" && "$compile_rc" -eq 0 ]]; then
        if attempt_runclient "$out" "$timeout"; then
            runclient_rc=0
        fi
    else
        if [[ "$compile_rc" -ne 0 ]]; then
            warn "Skipping runClient because compilation failed"
        else
            warn "runClient skipped by --skip-runclient flag"
        fi
    fi

    # Report
    generate_report "$src" "$compile_rc" "$runclient_rc"

    echo ""
    log "═══════════════════════════════════════════════════"
    log "  EXPERIMENT COMPLETE"
    log "═══════════════════════════════════════════════════"
    echo "  Results in: $RESULTS_DIR/"
}

main "$@"
