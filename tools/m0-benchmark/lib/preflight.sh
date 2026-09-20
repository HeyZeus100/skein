#!/usr/bin/env bash
# Preflight checks for the M0 benchmark harness (handoff §17.6 / §21).
#
# Every function that parses device output is pure — it takes text already
# captured by run_adb/run_ssh and returns a decision — so it can be unit
# tested without a Fold attached. preflight_run() is the only orchestration
# function; it wires the pure parsers to run_adb/run_ssh (lib/common.sh),
# which are themselves mockable via M0_MOCK_DEVICE.

if [[ -n "${_M0_PREFLIGHT_SH_LOADED:-}" ]]; then
    return 0 2>/dev/null || exit 0
fi
_M0_PREFLIGHT_SH_LOADED=1

SELF_DIR_PREFLIGHT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SELF_DIR_PREFLIGHT/common.sh"
# shellcheck source=./thermal.sh
source "$SELF_DIR_PREFLIGHT/thermal.sh"

# preflight_parse_adb_devices <adb-devices--l-output>
#
# Echoes one "<serial> <state>" pair per connected entry, skipping the
# "List of devices attached" header and blank lines.
preflight_parse_adb_devices() {
    local text="$1"
    printf '%s\n' "$text" | while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        [[ "$line" == "List of devices attached"* ]] && continue
        local serial state
        serial="$(awk '{print $1}' <<<"$line")"
        state="$(awk '{print $2}' <<<"$line")"
        [[ -z "$serial" ]] && continue
        printf '%s %s\n' "$serial" "$state"
    done
}

# preflight_single_ready_device <adb-devices--l-output>
#
# Succeeds (echoes the serial, returns 0) only if exactly one device is
# present and its state is "device" (authorized, online). Any other
# situation — zero devices, multiple devices, unauthorized/offline state —
# fails closed with a reason on stderr, per handoff §17.6 item 1.
preflight_single_ready_device() {
    local text="$1"
    local parsed
    parsed="$(preflight_parse_adb_devices "$text")"

    if [[ -z "$parsed" ]]; then
        m0_err "no ADB device connected"
        return 1
    fi

    local count
    count="$(printf '%s\n' "$parsed" | grep -c . || true)"
    if (( count > 1 )); then
        m0_err "multiple ADB devices connected — refuse to guess which is the Fold:"
        m0_err "$parsed"
        return 1
    fi

    local serial state
    serial="$(awk '{print $1}' <<<"$parsed")"
    state="$(awk '{print $2}' <<<"$parsed")"
    if [[ "$state" != "device" ]]; then
        m0_err "ADB device '$serial' is not ready (state: '$state', want 'device')"
        return 1
    fi

    echo "$serial"
    return 0
}

# preflight_hash_from_manifest <manifest-text> <path-or-basename>
#
# Manifest lines look like `sha256sum` output: "<hash>  <path>". Echoes the
# hash whose recorded path ends with the given suffix (so callers can match
# on either a full remote path or just the basename).
preflight_hash_from_manifest() {
    local manifest="$1" suffix="$2"
    local line hash path
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        hash="$(awk '{print $1}' <<<"$line")"
        path="$(awk '{ $1=""; sub(/^ +/, ""); print }' <<<"$line")"
        if [[ "$path" == *"$suffix" ]]; then
            echo "$hash"
            return 0
        fi
    done <<<"$manifest"
    return 1
}

# preflight_hashes_equal <expected> <actual> — case-insensitive compare.
#
# Uses `tr` rather than bash 4's `${var,,}` — macOS ships bash 3.2, and this
# harness's whole premise is "runs on the Mac orchestrator".
preflight_hashes_equal() {
    local a b
    a="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    b="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
    [[ -n "$a" && "$a" == "$b" ]]
}

# preflight_check_formal_artifact <mode> <formal_m0_candidate-value>
#
# --formal must refuse to run against the Q3_K_M smoke artifact (a
# non-negotiable). formal_m0_candidate is the literal string "true"/"false"
# read from models.yaml via lib/models.py.
preflight_check_formal_artifact() {
    local mode="$1" formal_m0_candidate="$2"
    if [[ "$mode" != "formal" ]]; then
        return 0
    fi
    if [[ "$formal_m0_candidate" == "true" ]]; then
        return 0
    fi
    m0_err "refusing --formal run: selected model is not the formal M0 candidate" \
           "(formal_m0_candidate=$formal_m0_candidate) — Q3_K_M is smoke ONLY"
    return 1
}

# preflight_check_sha256_present <hash> <what>
#
# models.yaml intentionally ships the formal Q4_K_M entry with sha256: TBD
# until the artifact is acquired and pinned (skein-bxk). Refuse to proceed
# rather than silently benchmarking an unverified/placeholder artifact.
preflight_check_sha256_present() {
    local hash="$1" what="$2"
    if [[ -z "$hash" || "$hash" == "null" || "$hash" == "TBD" ]]; then
        m0_err "no pinned SHA-256 for $what — acquire and pin it in models.yaml first"
        return 1
    fi
    return 0
}

# preflight_run <mode> <ssh_host> <expected_serial_or_empty>
#
# Real orchestration: ADB device identity, SSH reachability, and starting
# thermal envelope. Hash verification is intentionally NOT done here — it
# needs a specific binary/model path per cell, so run.sh calls
# preflight_hash_from_manifest / preflight_hashes_equal directly per cell.
# Returns 0 and echoes the device serial on success; returns non-zero with a
# reason on stderr otherwise.
preflight_run() {
    local mode="$1" ssh_host="$2"

    local devices_text serial
    devices_text="$(run_adb devices -l)" || {
        m0_err "adb devices -l failed"
        return 1
    }
    serial="$(preflight_single_ready_device "$devices_text")" || return 1

    if ! run_ssh "$ssh_host" true >/dev/null 2>&1; then
        m0_err "SSH host '$ssh_host' is not reachable (expected the ADB-forwarded" \
               "skein-fold-agent alias — see docs/DEVICE_RUNNER.md)"
        return 1
    fi

    local headroom
    headroom="$(run_adb shell "cmd thermalservice headroom 2>/dev/null" 2>/dev/null | head -1 | tr -d '\r')"
    if ! thermal_gate_ok "$headroom" "$mode"; then
        m0_err "thermal starting envelope not OK for mode=$mode (headroom='$headroom')"
        return 1
    fi

    echo "$serial"
    return 0
}
