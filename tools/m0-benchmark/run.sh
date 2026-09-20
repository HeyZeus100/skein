#!/usr/bin/env bash
# Skein M0 benchmark orchestrator — Mac-driven, ADB + SSH-Termux topology.
#
# Implements the proven control flow from
# docs/Handoffs/skein-fold-m0-hardware-handoff.md Section 13:
#
#   preflight (ADB identity + skein-fold-agent SSH reachable)
#   -> verify binary/model SHA-256 against baseline
#   -> capture start thermal state via ADB
#   -> start ADB telemetry sampler
#   -> SSH into Termux, invoke llama-bench
#   -> collect stdout/stderr per-cell
#   -> concurrent thermal/memory/battery capture
#   -> stop samplers, ensure no orphan benchmark process
#   -> normalize + write per-cell JSON on the Mac
#   -> thermal cool-down gate before the next cell
#
# Usage:
#   ./run.sh --dry-run --smoke
#   ./run.sh --smoke
#   ./run.sh --formal --context 4096 --repeats 3
#   ./run.sh --formal --resume
#
# Flags:
#   --smoke              One CPU cell + one Vulkan cell on the Q3_K_M smoke
#                         artifact. Loose thermal gate. (default mode)
#   --formal             Full matrix on the Q4_K_M formal candidate. Strict
#                         thermal gate, >=3 repeats. Refuses a smoke artifact.
#   --dry-run            Validate transport/models.yaml/preflight and write a
#                         "dry" JSON marker per cell. Never executes inference
#                         and never calls the real adb/ssh binaries.
#   --resume             Resume from tools/m0-benchmark/output/<date>/.state.json,
#                         skipping already-completed cells.
#   --model <id>         Override the model id (must exist in models.yaml).
#   --backend cpu|vulkan Restrict to one backend (default: both).
#   --context <N...>     Context length(s), space-separated in one arg.
#   --repeats <N>        Repeats per (backend, context) cell.
#
# See tools/m0-benchmark/README.md and docs/DEVICE_RUNNER.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="$SCRIPT_DIR/lib"

# shellcheck source=lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=lib/thermal.sh
source "$LIB_DIR/thermal.sh"
# shellcheck source=lib/preflight.sh
source "$LIB_DIR/preflight.sh"
# shellcheck source=lib/telemetry.sh
source "$LIB_DIR/telemetry.sh"
# shellcheck source=lib/ssh-exec.sh
source "$LIB_DIR/ssh-exec.sh"

MODELS_YAML="$SCRIPT_DIR/models.yaml"
OUTPUT_ROOT="$SCRIPT_DIR/output"
MODELS_PY="$LIB_DIR/models.py"
COLLECT_PY="$LIB_DIR/collect.py"

SSH_HOST="${M0_SSH_HOST:-skein-fold-agent}"
BENCH_TIMEOUT_S="${M0_BENCH_TIMEOUT_S:-600}"
COOLDOWN_MAX_WAIT_S="${M0_COOLDOWN_MAX_WAIT_S:-300}"
COOLDOWN_POLL_INTERVAL_S="${M0_COOLDOWN_POLL_INTERVAL_S:-10}"
BENCH_PROCESS_PATTERN="llama-bench"

DEFAULT_SMOKE_MODEL_ID="qwen-2.5-3b-instruct-abliterated-q3km-smoke"
DEFAULT_FORMAL_MODEL_ID="qwen-2.5-3b-instruct-abliterated-q4km"
DEFAULT_FORMAL_CONTEXTS="4096 8192 16384"
DEFAULT_SMOKE_CONTEXT="1024"
DEFAULT_GEN_TARGET="128"

# ----- ARG PARSING -----------------------------------------------------------

MODE="smoke"          # smoke | formal
DRY_RUN=0
RESUME=0
OPT_MODEL=""
OPT_BACKEND=""         # cpu | vulkan | "" (both)
OPT_CONTEXT=""
OPT_REPEATS=""

usage() {
    grep -E '^# ?' "$0" | sed -n '2,32p' | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --smoke)     MODE="smoke"; shift ;;
        --formal)    MODE="formal"; shift ;;
        --dry-run)   DRY_RUN=1; shift ;;
        --resume)    RESUME=1; shift ;;
        --model)     OPT_MODEL="$2"; shift 2 ;;
        --backend)   OPT_BACKEND="$2"; shift 2 ;;
        --context)   OPT_CONTEXT="$2"; shift 2 ;;
        --repeats)   OPT_REPEATS="$2"; shift 2 ;;
        -h|--help)   usage; exit 0 ;;
        *) m0_err "unknown flag: $1"; usage; exit 1 ;;
    esac
done

if [[ -n "$OPT_BACKEND" && "$OPT_BACKEND" != "cpu" && "$OPT_BACKEND" != "vulkan" ]]; then
    m0_err "--backend must be 'cpu' or 'vulkan', got '$OPT_BACKEND'"
    exit 1
fi

# ----- DRY-RUN MOCKS ----------------------------------------------------------
#
# --dry-run NEVER calls the real `adb`/`ssh` binaries: M0_MOCK_DEVICE routes
# every call in lib/common.sh through these instead. This is what makes
# `--dry-run` verifiable with no Fold attached, and is the only sanctioned
# way this repo simulates hardware.

if (( DRY_RUN )); then
    M0_MOCK_DEVICE=1
    export M0_MOCK_DEVICE

    mock_adb() {
        case "$1" in
            devices)
                echo "List of devices attached"
                echo "MOCKSERIAL0001	device"
                ;;
            shell)
                shift
                case "$*" in
                    *thermalservice*headroom*) echo "0.20" ;;
                    *dumpsys\ battery*)        printf 'level: 87\nAC powered: true\ntemperature: 320\n' ;;
                    *sha256sum*)               echo "0000000000000000000000000000000000000000000000000000000000000000  mock" ;;
                    *getprop*)                 echo "mock-value" ;;
                    *) echo "" ;;
                esac
                ;;
            *) echo "" ;;
        esac
    }

    mock_ssh() {
        local host="$1"
        shift
        case "$*" in
            true)        return 0 ;;
            whoami)      echo "u0_a214" ;;
            *pgrep*)     echo "" ;;
            *ps\ aux*)   echo "" ;;
            *sha256sum*) echo "0000000000000000000000000000000000000000000000000000000000000000  mock" ;;
            *) echo "" ;;
        esac
    }
fi

_run_read_headroom() {
    run_adb shell "cmd thermalservice headroom 2>/dev/null" 2>/dev/null | head -1 | tr -d '\r'
}

# ----- TOOLING CHECKS ----------------------------------------------------------

require_cmd python3 || exit 1
require_cmd jq "brew install jq" || exit 1
if (( ! DRY_RUN )); then
    require_cmd adb "brew install --cask android-platform-tools" || exit 1
    require_cmd ssh || exit 1
fi

# ----- MODEL / BINARY MANIFEST LOOKUP -----------------------------------------

# model_field <json> <field> — echoes the string form of a field, or "" if
# absent/null. Deliberately NOT `.[$f] // empty`: jq's `//` treats `false`
# and `0` as falsy too, which would silently swallow
# `formal_m0_candidate: false` and any zero-valued field.
model_field() {
    local model_json="$1" field="$2"
    jq -r --arg f "$field" '.[$f] as $v | if $v == null then "" else ($v | tostring) end' <<<"$model_json"
}

model_id="${OPT_MODEL:-}"
if [[ -z "$model_id" ]]; then
    if [[ "$MODE" == "formal" ]]; then
        model_id="$DEFAULT_FORMAL_MODEL_ID"
    else
        model_id="$DEFAULT_SMOKE_MODEL_ID"
    fi
fi

model_json="$(python3 "$MODELS_PY" "$MODELS_YAML" model "$model_id")" || {
    m0_err "unknown model id '$model_id' — check tools/m0-benchmark/models.yaml"
    exit 1
}

formal_flag="$(model_field "$model_json" formal_m0_candidate)"
preflight_check_formal_artifact "$MODE" "$formal_flag" || exit 1

model_sha256="$(model_field "$model_json" sha256)"
model_device_path="$(model_field "$model_json" device_path)"
model_quant="$(model_field "$model_json" quantization)"

if (( ! DRY_RUN )); then
    preflight_check_sha256_present "$model_sha256" "model '$model_id'" || exit 1
fi

backends=()
if [[ -n "$OPT_BACKEND" ]]; then
    backends=("$OPT_BACKEND")
else
    backends=(cpu vulkan)
fi

if [[ "$MODE" == "formal" ]]; then
    read -r -a contexts <<<"${OPT_CONTEXT:-$DEFAULT_FORMAL_CONTEXTS}"
    repeats="${OPT_REPEATS:-3}"
    if (( ! DRY_RUN )) && (( repeats < 3 )); then
        m0_err "--formal requires at least 3 repeats for statistical validity (got $repeats)"
        exit 1
    fi
else
    read -r -a contexts <<<"${OPT_CONTEXT:-$DEFAULT_SMOKE_CONTEXT}"
    repeats="${OPT_REPEATS:-1}"
fi

# ----- OUTPUT / STATE ----------------------------------------------------------

RUN_DATE="$(date -u +%Y-%m-%d)"
if (( DRY_RUN )); then
    OUTPUT_DIR="$OUTPUT_ROOT/dryrun"
    (( RESUME )) || rm -rf "$OUTPUT_DIR"
else
    OUTPUT_DIR="$OUTPUT_ROOT/$RUN_DATE"
fi
mkdir -p "$OUTPUT_DIR"
STATE_FILE="$OUTPUT_DIR/.state.json"
if [[ ! -f "$STATE_FILE" || "$RESUME" != "1" ]]; then
    echo '{"completed": []}' > "$STATE_FILE"
fi

state_is_complete() {
    local cell_key="$1"
    jq -e --arg k "$cell_key" '.completed | index($k) != null' "$STATE_FILE" >/dev/null 2>&1
}

state_mark_complete() {
    local cell_key="$1"
    local tmp
    tmp="$(mktemp)"
    jq --arg k "$cell_key" '.completed += [$k] | .completed |= unique' "$STATE_FILE" > "$tmp"
    mv "$tmp" "$STATE_FILE"
}

# ----- CLEANUP -----------------------------------------------------------------

TELEMETRY_PID=""
cleanup() {
    if [[ -n "$TELEMETRY_PID" ]]; then
        telemetry_stop "$TELEMETRY_PID"
        TELEMETRY_PID=""
    fi
    telemetry_ensure_no_orphan_bench "$SSH_HOST" "$BENCH_PROCESS_PATTERN" || true
}
trap cleanup EXIT
trap 'm0_err "interrupted"; exit 130' INT TERM

# ----- PREFLIGHT ----------------------------------------------------------------

m0_info "preflight: mode=$MODE dry_run=$DRY_RUN model=$model_id backends=${backends[*]} contexts=${contexts[*]} repeats=$repeats"

device_serial="$(preflight_run "$MODE" "$SSH_HOST")" || {
    m0_err "preflight failed — see reasons above"
    exit 1
}
m0_info "preflight OK — device: $device_serial"

# ----- BUILD MATRIX --------------------------------------------------------------

declare -a CELLS=()
for backend in "${backends[@]}"; do
    python3 "$MODELS_PY" "$MODELS_YAML" binary --backend "$backend" --kind bench >/dev/null || {
        m0_err "no binary manifest entry for backend '$backend'"
        exit 1
    }
    for context in "${contexts[@]}"; do
        for (( rep=1; rep<=repeats; rep++ )); do
            CELLS+=("${backend}|${context}|${rep}")
        done
    done
done

m0_info "matrix: ${#CELLS[@]} cell(s)"

gpu_layers_for_backend() { [[ "$1" == "vulkan" ]] && echo "99" || echo "0"; }
threads_for_backend()    { echo "4"; }

# ----- RUN EACH CELL ---------------------------------------------------------

first_cell=1
for cell in "${CELLS[@]}"; do
    IFS='|' read -r backend context rep <<<"$cell"
    cell_key="${model_id}__${backend}__ctx${context}__rep${rep}"
    cell_dir="$OUTPUT_DIR/$cell_key"

    if (( RESUME )) && state_is_complete "$cell_key"; then
        m0_info "skip (resume): $cell_key already complete"
        continue
    fi

    cooldown_aborted=0
    if (( ! first_cell )) && (( ! DRY_RUN )); then
        m0_info "thermal cool-down gate before $cell_key"
        if ! thermal_wait_for_cooldown "$MODE" "_run_read_headroom" \
                "$COOLDOWN_MAX_WAIT_S" "$COOLDOWN_POLL_INTERVAL_S" >/dev/null; then
            m0_warn "cool-down timed out before $cell_key — recording thermal-abort"
            cooldown_aborted=1
        fi
    fi
    first_cell=0

    mkdir -p "$cell_dir"
    m0_info "▶ $cell_key"

    binary_json="$(python3 "$MODELS_PY" "$MODELS_YAML" binary --backend "$backend" --kind bench)"
    binary_path="$(model_field "$binary_json" path)"
    binary_sha256_expected="$(model_field "$binary_json" sha256)"
    llama_cpp_commit="$(model_field "$binary_json" llama_cpp_commit)"

    ts_start="$(now_iso8601)"
    exit_status=0
    failure_reason=""
    pp_tps="null"
    tg_tps="null"
    ttft_ms="null"
    peak_rss_mb="null"
    thermal_end="null"
    gpu_layers="$(gpu_layers_for_backend "$backend")"
    threads="$(threads_for_backend "$backend")"
    cmdline="$(ssh_build_bench_cmdline "$binary_path" "$model_device_path" "$backend" \
        "$context" "$DEFAULT_GEN_TARGET" "$threads" "$gpu_layers" 1)"

    thermal_start="$(_run_read_headroom)"
    [[ -z "$thermal_start" ]] && thermal_start="null"

    if (( DRY_RUN )); then
        m0_info "  dry-run cell — writing marker JSON, no inference executed"
    elif (( cooldown_aborted )); then
        exit_status=1
        failure_reason="thermal-abort"
    else
        # Hash verification (handoff §17.6 items 3/4) — binary via SSH
        # (Termux-private storage), model via ADB (shared storage).
        bin_hash_actual="$(run_ssh "$SSH_HOST" "sha256sum $binary_path" 2>/dev/null | awk '{print $1}')"
        if ! preflight_hashes_equal "$binary_sha256_expected" "$bin_hash_actual"; then
            exit_status=1
            failure_reason="binary-hash-mismatch"
        fi

        model_hash_actual="$(run_adb shell "sha256sum $model_device_path" 2>/dev/null | awk '{print $1}')"
        if [[ -z "$failure_reason" ]] && ! preflight_hashes_equal "$model_sha256" "$model_hash_actual"; then
            exit_status=1
            failure_reason="model-hash-mismatch"
        fi

        if [[ -z "$failure_reason" ]]; then
            telemetry_out="$cell_dir/telemetry.jsonl"
            : > "$telemetry_out"
            TELEMETRY_PID="$(telemetry_start "$SSH_HOST" "$BENCH_PROCESS_PATTERN" "$telemetry_out")"

            raw_status="$(ssh_exec_bench "$SSH_HOST" "$cmdline" "$BENCH_TIMEOUT_S" \
                "$cell_dir/stdout.log" "$cell_dir/stderr.log")"

            telemetry_stop "$TELEMETRY_PID"
            TELEMETRY_PID=""
            telemetry_ensure_no_orphan_bench "$SSH_HOST" "$BENCH_PROCESS_PATTERN"

            exit_status="$raw_status"
            if [[ "$exit_status" != "0" ]]; then
                failure_reason="$(ssh_classify_exit_status "$exit_status")"
            else
                metrics_json="$(python3 "$COLLECT_PY" normalize "$cell_dir/stdout.log")" || {
                    failure_reason="normalize-failed"
                    exit_status=1
                }
                if [[ -z "$failure_reason" ]]; then
                    pp_tps="$(jq -r '.pp_toks_per_s // "null"' <<<"$metrics_json")"
                    tg_tps="$(jq -r '.tg_toks_per_s // "null"' <<<"$metrics_json")"
                fi
            fi

            thermal_end="$(_run_read_headroom)"
            [[ -z "$thermal_end" ]] && thermal_end="null"
        fi
    fi

    ts_end="$(now_iso8601)"

    device_fingerprint="$(run_adb shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
    android_version="$(run_adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
    android_patch="$(run_adb shell getprop ro.build.version.security_patch 2>/dev/null | tr -d '\r')"
    battery_state="$(run_adb shell dumpsys battery 2>/dev/null | awk -F: '/level/ {gsub(/ /,"",$2); print $2; exit}')"
    charging_state="$(run_adb shell dumpsys battery 2>/dev/null | awk -F: '/AC powered/ {gsub(/ /,"",$2); print $2; exit}')"

    json_blob="$(jq -n \
        --arg device_build_fingerprint "${device_fingerprint:-unknown}" \
        --arg android_version "${android_version:-unknown}" \
        --arg android_security_patch "${android_patch:-unknown}" \
        --arg llama_cpp_commit "${llama_cpp_commit:-unknown}" \
        --arg binary_sha256 "${binary_sha256_expected:-}" \
        --arg model_sha256 "${model_sha256:-}" \
        --arg model_quantization "${model_quant:-unknown}" \
        --arg backend "$backend" \
        --arg cmdline "$cmdline" \
        --arg context_length "$context" \
        --arg gpu_layers "$gpu_layers" \
        --arg threads "$threads" \
        --arg prompt_target "$context" \
        --arg gen_target "$DEFAULT_GEN_TARGET" \
        --arg repeat_index "$rep" \
        --arg thermal_start "$thermal_start" \
        --arg thermal_end "$thermal_end" \
        --arg pp_toks_per_s "$pp_tps" \
        --arg tg_toks_per_s "$tg_tps" \
        --arg ttft_ms "$ttft_ms" \
        --arg peak_rss_mb "$peak_rss_mb" \
        --arg battery_state "${battery_state:-unknown}" \
        --arg charging_state "${charging_state:-unknown}" \
        --arg exit_status "$exit_status" \
        --arg timestamp_start "$ts_start" \
        --arg timestamp_end "$ts_end" \
        --arg failure_reason "$failure_reason" \
        --argjson is_dry_run "$( (( DRY_RUN )) && echo true || echo false )" \
        '
        def numOrNull: if . == "null" or . == "" then null else (tonumber? // null) end;
        {
            device_build_fingerprint: $device_build_fingerprint,
            android_version: $android_version,
            android_security_patch: $android_security_patch,
            llama_cpp_commit: $llama_cpp_commit,
            binary_sha256: $binary_sha256,
            model_sha256: $model_sha256,
            model_quantization: $model_quantization,
            backend: $backend,
            cmdline: $cmdline,
            context_length: ($context_length | numOrNull),
            gpu_layers: ($gpu_layers | numOrNull),
            threads: ($threads | numOrNull),
            prompt_target: ($prompt_target | numOrNull),
            gen_target: ($gen_target | numOrNull),
            repeat_index: ($repeat_index | numOrNull),
            thermal_start: ($thermal_start | numOrNull),
            thermal_end: ($thermal_end | numOrNull),
            pp_toks_per_s: ($pp_toks_per_s | numOrNull),
            tg_toks_per_s: ($tg_toks_per_s | numOrNull),
            ttft_ms: ($ttft_ms | numOrNull),
            peak_rss_mb: ($peak_rss_mb | numOrNull),
            battery_state: $battery_state,
            charging_state: $charging_state,
            exit_status: ($exit_status | numOrNull),
            timestamp_start: $timestamp_start,
            timestamp_end: $timestamp_end
        }
        + (if ($failure_reason | length) > 0 then {failure_reason: $failure_reason} else {} end)
        + (if $is_dry_run then {is_dry_run: true} else {} end)
        ')"

    echo "$json_blob" | python3 "$COLLECT_PY" write --out "$cell_dir/result.json"

    if [[ "$exit_status" == "0" ]] || (( DRY_RUN )); then
        m0_info "✓ $cell_key -> $cell_dir/result.json"
    else
        m0_warn "✗ $cell_key failed (${failure_reason:-unknown}) -> $cell_dir/result.json"
    fi

    state_mark_complete "$cell_key"
done

m0_info "matrix complete: ${#CELLS[@]} cell(s) written under $OUTPUT_DIR"
if (( ! DRY_RUN )); then
    m0_info "aggregate with: python3 $COLLECT_PY aggregate $OUTPUT_DIR"
fi
