#!/usr/bin/env bash
#
# Real-World Mod Build Verification
# Clones open-source Forge 1.20.1 mods, converts them with Forge2Neo,
# and attempts compilation against NeoForge 1.21.1.
#
# Usage: ./scripts/test-real-mods.sh [--mod <0-5>] [--all]
#
# Requires: Java 21, internet access, ~1GB disk for NeoForge dependencies
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${PROJECT_DIR}/build/real-mod-test"
RESULTS_DIR="${WORK_DIR}/results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log() { echo -e "${BLUE}[TEST]${NC} $*"; }
ok()  { echo -e "${GREEN}[OK]${NC} $*"; }
warn(){ echo -e "${YELLOW}[WARN]${NC} $*"; }
err() { echo -e "${RED}[FAIL]${NC} $*"; }

# ─── Mod Candidate Registry ───────────────────────────────────────────

declare -A MOD_REPOS=(
    [0]="https://github.com/NeoForgeMDKs/MDK-Forge-1.20.1-ModDevGradle"
    [1]="https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X"
    [2]="https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X"
    [3]="https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X"
    [4]="https://github.com/martreedev/Minecraft-Forge-1.20.1-TPose-Animation-With-Keybind"
    [5]="https://github.com/Tutorials-By-Kaupenjoe/Forge-Tutorial-1.20.X"
)

declare -A MOD_BRANCHES=(
    [0]="main"
    [1]="3-customBlocks"
    [2]="10-tooltips"
    [3]="12-datagen"
    [4]="main"
    [5]="17-fullArmorEffect"
)

declare -A MOD_NAMES=(
    [0]="mdk-baseline"
    [1]="kaupenjoe-blocks"
    [2]="kaupenjoe-tooltips"
    [3]="kaupenjoe-datagen"
    [4]="tpose-capabilities"
    [5]="kaupenjoe-armor"
)

declare -A MOD_DIFFICULTY=(
    [0]="Trivial"
    [1]="Easy"
    [2]="Medium"
    [3]="Medium"
    [4]="Hard"
    [5]="Hard"
)

# ─── Functions ─────────────────────────────────────────────────────────

clone_mod() {
    local idx=$1
    local repo="${MOD_REPOS[$idx]}"
    local branch="${MOD_BRANCHES[$idx]}"
    local name="${MOD_NAMES[$idx]}"
    local dir="${WORK_DIR}/sources/${name}"

    if [[ -d "$dir" ]]; then
        log "Using cached clone: $name"
        return 0
    fi

    log "Cloning: $name (branch: $branch)"
    git clone --depth 1 --branch "$branch" "$repo" "$dir" 2>/dev/null || {
        err "Failed to clone $name"
        return 1
    }
    ok "Cloned: $name ($(find "$dir/src" -name '*.java' 2>/dev/null | wc -l) Java files)"
}

convert_mod() {
    local idx=$1
    local name="${MOD_NAMES[$idx]}"
    local src="${WORK_DIR}/sources/${name}"
    local out="${WORK_DIR}/converted/${name}"
    local report="${RESULTS_DIR}/${name}-report.md"

    log "Converting: $name"

    # Build forge2neo if needed
    cd "$PROJECT_DIR"
    ./gradlew installDist --no-daemon -q 2>/dev/null || {
        err "Failed to build forge2neo"
        return 1
    }

    local forge2neo="${PROJECT_DIR}/build/install/forge2neo/bin/forge2neo"

    # Run conversion
    if [[ -f "$forge2neo" ]]; then
        "$forge2neo" port --src "$src" --out "$out" --report "$report" 2>&1 | tee "${RESULTS_DIR}/${name}-convert.log"
    else
        ./gradlew run --no-daemon --args="port --src $src --out $out --report $report" 2>&1 | tee "${RESULTS_DIR}/${name}-convert.log"
    fi

    ok "Converted: $name -> $out"
}

check_forge_references() {
    local idx=$1
    local name="${MOD_NAMES[$idx]}"
    local dir="${WORK_DIR}/converted/${name}"
    local count=0

    local patterns=("net.minecraftforge" "ForgeRegistries\." "MinecraftForge\.EVENT_BUS")
    for pattern in "${patterns[@]}"; do
        local hits=$(grep -rl --include="*.java" "$pattern" "$dir/src" 2>/dev/null | wc -l)
        count=$((count + hits))
    done

    if [[ $count -eq 0 ]]; then
        ok "$name: No remaining Forge references in Java"
    else
        warn "$name: $count files still have Forge references"
        grep -rn --include="*.java" "net.minecraftforge" "$dir/src" 2>/dev/null | head -5 || true
    fi
    echo "$count"
}

compile_mod() {
    local idx=$1
    local name="${MOD_NAMES[$idx]}"
    local dir="${WORK_DIR}/converted/${name}"
    local log_file="${RESULTS_DIR}/${name}-build.log"

    log "Compiling: $name"

    if [[ ! -f "$dir/gradlew" ]]; then
        warn "$name: No Gradle wrapper, attempting to generate one"
        # Copy wrapper from MDK template if available
        local template_wrapper="${PROJECT_DIR}/src/test/resources/neoforge-mdk-template/gradle"
        if [[ -d "$template_wrapper" ]]; then
            cp -r "$template_wrapper" "$dir/"
            # Also need gradlew script
            echo '#!/bin/sh' > "$dir/gradlew"
            echo 'exec gradle "$@"' >> "$dir/gradlew"
            chmod +x "$dir/gradlew"
        fi
    fi

    cd "$dir"
    if [[ -f "$dir/gradlew" ]]; then
        chmod +x "$dir/gradlew"
        "$dir/gradlew" compileJava --no-daemon --stacktrace 2>&1 | tee "$log_file"
        local exit_code=${PIPESTATUS[0]}
    else
        warn "$name: No way to compile, skipping"
        echo "SKIPPED" > "$log_file"
        return 2
    fi

    if [[ $exit_code -eq 0 ]]; then
        ok "$name: COMPILATION SUCCESSFUL"
    else
        err "$name: COMPILATION FAILED"
        echo ""
        log "Error classification:"
        python3 "${SCRIPT_DIR}/classify-errors.py" "$log_file" 2>/dev/null || true
    fi
    return $exit_code
}

# ─── Main ──────────────────────────────────────────────────────────────

main() {
    local mods_to_test=()

    # Parse args
    if [[ "${1:-}" == "--all" ]]; then
        mods_to_test=(0 1 2 3 4 5)
    elif [[ "${1:-}" == "--mod" && -n "${2:-}" ]]; then
        mods_to_test=("$2")
    else
        # Default: test mods 0-2 (baseline + easy + medium)
        mods_to_test=(0 1 2)
    fi

    mkdir -p "$WORK_DIR/sources" "$WORK_DIR/converted" "$RESULTS_DIR"

    echo ""
    log "═══════════════════════════════════════════════════"
    log "  Real-World Mod Build Verification Experiment"
    log "═══════════════════════════════════════════════════"
    echo ""

    local total=${#mods_to_test[@]}
    local cloned=0 converted=0 clean=0 compiled=0

    for idx in "${mods_to_test[@]}"; do
        local name="${MOD_NAMES[$idx]}"
        local difficulty="${MOD_DIFFICULTY[$idx]}"
        echo ""
        log "───────────────────────────────────────────────"
        log "Mod #${idx}: ${name} (${difficulty})"
        log "───────────────────────────────────────────────"

        # Clone
        if clone_mod "$idx"; then
            cloned=$((cloned + 1))
        else
            continue
        fi

        # Convert
        if convert_mod "$idx"; then
            converted=$((converted + 1))
        else
            continue
        fi

        # Check Forge references
        local forge_refs
        forge_refs=$(check_forge_references "$idx")
        if [[ "$forge_refs" -eq 0 ]]; then
            clean=$((clean + 1))
        fi

        # Compile
        if compile_mod "$idx" 2>/dev/null; then
            compiled=$((compiled + 1))
        fi
    done

    # Summary
    echo ""
    log "═══════════════════════════════════════════════════"
    log "  RESULTS"
    log "═══════════════════════════════════════════════════"
    echo ""
    echo "  Mods tested:          $total"
    echo "  Cloned successfully:  $cloned / $total"
    echo "  Converted:            $converted / $total"
    echo "  Clean (no Forge refs): $clean / $total"
    echo "  Compiled:             $compiled / $total"
    echo ""

    # Generate summary report
    local report="${RESULTS_DIR}/summary_${TIMESTAMP}.md"
    cat > "$report" << EOF
# Real-World Mod Verification Report
**Date:** $(date '+%Y-%m-%d %H:%M:%S')

| Mod | Difficulty | Cloned | Converted | Clean | Compiled |
|-----|-----------|--------|-----------|-------|----------|
EOF

    for idx in "${mods_to_test[@]}"; do
        local name="${MOD_NAMES[$idx]}"
        echo "| ${name} | ${MOD_DIFFICULTY[$idx]} | - | - | - | - |" >> "$report"
    done

    echo "" >> "$report"
    echo "**Detailed reports in:** ${RESULTS_DIR}/" >> "$report"

    log "Report: $report"
    log "Detailed logs: $RESULTS_DIR/"
}

main "$@"
