#!/usr/bin/env bash
# Skein M0 benchmark orchestrator
#
# Runs the benchmark matrix on a connected Pixel 9 Pro Fold and writes
# per-cell JSON to output/. Aggregation to MEASUREMENTS.md is done separately
# via collect-results.py.
#
# Usage:
#   ./run.sh --all
#   ./run.sh --model qwen-2.5-3b-abl --prompt medium-1k --n-gen 256

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/output"
DEVICE_MODEL_DIR="/sdcard/skein-bench/models"
DEVICE_PROMPT_DIR="/sdcard/skein-bench/prompts"
DEVICE_LLAMA_BENCH="/data/data/com.termux/files/home/llama.cpp/build/bin/llama-bench"

mkdir -p "$OUTPUT_DIR"

# ----- ARG PARSING -----------------------------------------------------------

MODE="single"
MODEL=""
PROMPT=""
N_GEN="128"
N_PREFILL="512"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)              MODE="all"; shift ;;
        --model)            MODEL="$2"; shift 2 ;;
        --prompt)           PROMPT="$2"; shift 2 ;;
        --n-gen)            N_GEN="$2"; shift 2 ;;
        --n-prefill)        N_PREFILL="$2"; shift 2 ;;
        -h|--help)
            grep -E '^# ' "$0" | sed 's/^# //'
            exit 0
            ;;
        *) echo "Unknown flag: $1" >&2; exit 1 ;;
    esac
done

# ----- ENVIRONMENT CAPTURE ---------------------------------------------------

capture_env() {
    local out_json="$1"
    {
        echo "{"
        echo "  \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
        echo "  \"host\": \"$(uname -mrs)\","
        echo "  \"adb_devices\": \"$(adb devices | tail -n +2 | grep -v '^$' | tr '\n' ',')\","
        echo "  \"device_soc\": \"$(adb shell getprop ro.hardware 2>/dev/null || echo unknown)\","
        echo "  \"device_model\": \"$(adb shell getprop ro.product.model 2>/dev/null || echo unknown)\","
        echo "  \"os_build\": \"$(adb shell getprop ro.build.id 2>/dev/null || echo unknown)\","
        echo "  \"os_security_patch\": \"$(adb shell getprop ro.build.version.security_patch 2>/dev/null || echo unknown)\","
        echo "  \"kernel\": \"$(adb shell uname -a 2>/dev/null || echo unknown)\","
        echo "  \"battery_pct\": \"$(adb shell dumpsys battery 2>/dev/null | grep level | awk '{print $2}' || echo unknown)\","
        echo "  \"thermal_headroom\": \"$(adb shell dumpsys thermalservice 2>/dev/null | grep -m1 headroom || echo unknown)\""
        echo "}"
    } > "$out_json"
}

# ----- DEVICE PRECHECK -------------------------------------------------------

precheck() {
    if ! command -v adb >/dev/null; then
        echo "ERROR: adb not found. brew install --cask android-platform-tools" >&2
        exit 1
    fi
    if ! adb devices | grep -qE 'device$'; then
        echo "ERROR: no device connected via adb" >&2
        exit 1
    fi
    if ! adb shell "[ -x $DEVICE_LLAMA_BENCH ]" 2>/dev/null; then
        echo "ERROR: llama-bench not found on device at $DEVICE_LLAMA_BENCH" >&2
        echo "Build llama.cpp in Termux first — see README.md" >&2
        exit 1
    fi
    echo "✓ device ready: $(adb shell getprop ro.product.model)"
}

# ----- RUN A SINGLE CELL -----------------------------------------------------

run_cell() {
    local model_id="$1"
    local prompt_id="$2"
    local n_gen="$3"
    local n_prefill="$4"

    local timestamp
    timestamp="$(date -u +%Y%m%d-%H%M%S)"
    local cell_dir="$OUTPUT_DIR/${timestamp}-${model_id}-${prompt_id}-g${n_gen}"
    mkdir -p "$cell_dir"

    echo "▶ ${model_id} × ${prompt_id} × n_gen=${n_gen}"

    capture_env "$cell_dir/env.json"

    # Start thermal sampler in background
    "$SCRIPT_DIR/thermal-sampler.sh" "$cell_dir/thermal.jsonl" &
    local thermal_pid=$!
    trap "kill $thermal_pid 2>/dev/null || true" EXIT

    # Run 3 iterations for statistical validity
    for run in 1 2 3; do
        echo "  · run $run/3"
        adb shell "$DEVICE_LLAMA_BENCH \
            -m $DEVICE_MODEL_DIR/${model_id}.gguf \
            -p $n_prefill \
            -n $n_gen \
            -r 1 \
            --output json" \
            > "$cell_dir/run-${run}.json" 2> "$cell_dir/run-${run}.stderr" || {
                echo "    FAILED — see $cell_dir/run-${run}.stderr"
        }
        # cooldown between runs to isolate thermal effects
        sleep 30
    done

    kill $thermal_pid 2>/dev/null || true
    trap - EXIT

    echo "✓ cell complete → $cell_dir"
}

# ----- MAIN ------------------------------------------------------------------

precheck

if [[ "$MODE" == "all" ]]; then
    # Full matrix — takes ~2 hours
    for model in qwen-2.5-3b-instruct-abliterated-q4km gemma-4-e4b-q4km gemma-4-e2b-q4km; do
        for prompt_len in 128 512 2048 4096; do
            run_cell "$model" "p${prompt_len}" 256 "$prompt_len"
        done
    done
    echo ""
    echo "Full matrix complete. Aggregate with: python3 collect-results.py"
else
    [[ -z "$MODEL" || -z "$PROMPT" ]] && { echo "Missing --model or --prompt" >&2; exit 1; }
    run_cell "$MODEL" "$PROMPT" "$N_GEN" "$N_PREFILL"
fi
