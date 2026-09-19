#!/usr/bin/env bash
# Thermal sampler — writes one JSON line per second while running.
#
# Captures thermal headroom (0.0 = cool, 1.0 = throttling threshold),
# per-zone temps from thermalservice, CPU frequency, and battery temp.
#
# Usage: ./thermal-sampler.sh <output.jsonl>
#
# Runs until killed. Called from run.sh with `&` and killed after the cell finishes.

set -euo pipefail

OUT="${1:?output file required}"
INTERVAL="${THERMAL_INTERVAL_S:-1}"

while true; do
    ts="$(date -u +%s)"

    headroom="$(adb shell "cmd thermalservice headroom 2>/dev/null" 2>/dev/null | head -1 | tr -d '\r' || echo null)"

    battery_temp="$(adb shell dumpsys battery 2>/dev/null | awk -F: '/temperature/ {gsub(/ /,"",$2); print $2/10.0}' || echo null)"

    # Skin + SoC thermal zones via sysfs (requires root or /sys reads to be world-readable)
    soc_temp="$(adb shell "cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null" 2>/dev/null | tr -d '\r' || echo null)"

    printf '{"ts":%s,"headroom":"%s","battery_temp_c":"%s","soc_temp_raw":"%s"}\n' \
        "$ts" "$headroom" "$battery_temp" "$soc_temp" \
        >> "$OUT"

    sleep "$INTERVAL"
done
