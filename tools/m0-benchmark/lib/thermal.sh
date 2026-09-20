#!/usr/bin/env bash
# Thermal gate state machine for the M0 benchmark harness.
#
# Headroom follows Android's `PowerManager.getThermalHeadroom()` convention:
# 0.0 = cool, 1.0 = the device's own throttling threshold. Values above 1.0
# mean the device is already throttling.
#
# All functions here are pure (given their inputs, no device I/O) so they can
# be unit tested directly. thermal_wait_for_cooldown is the one exception: it
# loops, but the thing it polls is injected as a callback function name, so
# tests can script a canned sequence of readings.

if [[ -n "${_M0_THERMAL_SH_LOADED:-}" ]]; then
    return 0 2>/dev/null || exit 0
fi
_M0_THERMAL_SH_LOADED=1

# Gate thresholds. --smoke uses a loose gate (per handoff: "Loose thermal
# gate" for the dry-run/smoke pipeline proof); --formal uses a strict gate.
THERMAL_SMOKE_MAX_HEADROOM="${THERMAL_SMOKE_MAX_HEADROOM:-0.90}"
THERMAL_FORMAL_MAX_HEADROOM="${THERMAL_FORMAL_MAX_HEADROOM:-0.50}"
THERMAL_COOLDOWN_TARGET_HEADROOM="${THERMAL_COOLDOWN_TARGET_HEADROOM:-0.40}"

# thermal_classify <headroom> — echoes COOL | WARM | HOT | UNKNOWN.
thermal_classify() {
    local headroom="$1"
    if [[ -z "$headroom" || "$headroom" == "null" ]]; then
        echo "UNKNOWN"
        return 0
    fi
    if ! awk -v h="$headroom" 'BEGIN { exit !(h == h + 0) }' 2>/dev/null; then
        echo "UNKNOWN"
        return 0
    fi
    if awk -v h="$headroom" 'BEGIN { exit !(h < 0.30) }'; then
        echo "COOL"
    elif awk -v h="$headroom" 'BEGIN { exit !(h < 0.70) }'; then
        echo "WARM"
    else
        echo "HOT"
    fi
}

# thermal_max_for_mode <smoke|formal> — echoes the gate threshold for mode.
thermal_max_for_mode() {
    case "$1" in
        smoke)  echo "$THERMAL_SMOKE_MAX_HEADROOM" ;;
        formal) echo "$THERMAL_FORMAL_MAX_HEADROOM" ;;
        *)      m0_err "thermal_max_for_mode: unknown mode '$1'" 2>/dev/null || echo "unknown mode '$1'" >&2
                return 1 ;;
    esac
}

# thermal_gate_ok <headroom> <mode> — return 0 if headroom is within the
# starting envelope for <mode> (smoke|formal), 1 otherwise. A missing/non-
# numeric headroom fails closed (returns 1) — never assume "probably fine".
thermal_gate_ok() {
    local headroom="$1" mode="$2" max
    max="$(thermal_max_for_mode "$mode")" || return 1
    if [[ -z "$headroom" || "$headroom" == "null" ]]; then
        return 1
    fi
    if ! awk -v h="$headroom" 'BEGIN { exit !(h == h + 0) }' 2>/dev/null; then
        return 1
    fi
    awk -v h="$headroom" -v m="$max" 'BEGIN { exit !(h <= m) }'
}

# thermal_wait_for_cooldown <mode> <read_fn> <max_wait_s> <poll_interval_s>
#
# Polls <read_fn> (a bash function name taking no args, echoing a headroom
# value) until the reading is within the cooldown target, or until
# <max_wait_s> elapses. Echoes the final headroom reading and returns 0 on
# success, 1 on timeout ("thermal-abort").
thermal_wait_for_cooldown() {
    local mode="$1" read_fn="$2" max_wait_s="$3" poll_interval_s="$4"
    local elapsed=0 headroom=""

    while true; do
        headroom="$("$read_fn")"
        if awk -v h="$headroom" -v t="$THERMAL_COOLDOWN_TARGET_HEADROOM" \
            'BEGIN { exit !(h == h + 0 && h <= t) }' 2>/dev/null; then
            echo "$headroom"
            return 0
        fi
        if (( elapsed >= max_wait_s )); then
            echo "$headroom"
            return 1
        fi
        sleep "$poll_interval_s" 2>/dev/null || true
        elapsed=$(( elapsed + poll_interval_s ))
    done
}
