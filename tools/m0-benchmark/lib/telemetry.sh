#!/usr/bin/env bash
# ADB telemetry sampler management for the M0 benchmark harness.
#
# Replaces the old standalone thermal-sampler.sh: thermal, memory and
# battery sampling now live together here so start/stop/cleanup is one
# lifecycle instead of three. PID resolution (telemetry_resolve_pid) and
# the pgrep-line parser (telemetry_parse_pgrep) are pure and unit tested;
# the sampler loop and process control are I/O and are exercised via
# --dry-run / real hardware runs instead.

if [[ -n "${_M0_TELEMETRY_SH_LOADED:-}" ]]; then
    return 0 2>/dev/null || exit 0
fi
_M0_TELEMETRY_SH_LOADED=1

SELF_DIR_TELEMETRY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SELF_DIR_TELEMETRY/common.sh"

TELEMETRY_INTERVAL_S="${TELEMETRY_INTERVAL_S:-1}"

# telemetry_resolve_pid <ps-output-text> <process-name-substring>
#
# Parses free-form `ps` style output (PID in column 2, matching Android's
# `ps -A` and Termux's `ps aux`, CMD as the last column) and echoes the
# first matching PID. Returns 1 with nothing echoed if no match is found —
# callers must treat that as "process not running", not as PID 0.
telemetry_resolve_pid() {
    local ps_text="$1" pattern="$2"
    local line
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        if [[ "$line" == *"$pattern"* ]]; then
            local pid
            pid="$(awk '{print $2}' <<<"$line")"
            if [[ "$pid" =~ ^[0-9]+$ ]]; then
                echo "$pid"
                return 0
            fi
        fi
    done <<<"$ps_text"
    return 1
}

# telemetry_parse_pgrep <pgrep--l-output>
#
# Echoes one PID per line from `pgrep -l` style output ("<pid> <name>").
# Used by cleanup to detect and kill orphaned benchmark processes.
telemetry_parse_pgrep() {
    local text="$1" line
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        awk '{print $1}' <<<"$line"
    done <<<"$text"
}

# telemetry_sample_once <ssh_host> — echoes one JSON telemetry line.
#
# Thermal + battery come from ADB (system telemetry, per handoff §6);
# process RSS for the benchmark binary comes from SSH (Termux-private
# process, not visible to `adb shell ps` in the sandboxed split).
telemetry_sample_once() {
    local ssh_host="$1" bench_pattern="${2:-llama-bench}"
    local ts headroom battery_pct battery_temp rss_kb

    ts="$(date -u +%s)"
    headroom="$(run_adb shell "cmd thermalservice headroom 2>/dev/null" 2>/dev/null | head -1 | tr -d '\r')"
    battery_pct="$(run_adb shell dumpsys battery 2>/dev/null | awk -F: '/level/ {gsub(/ /,"",$2); print $2; exit}')"
    battery_temp="$(run_adb shell dumpsys battery 2>/dev/null | awk -F: '/temperature/ {gsub(/ /,"",$2); print $2/10.0; exit}')"

    local ps_text pid
    ps_text="$(run_ssh "$ssh_host" ps aux 2>/dev/null)"
    pid="$(telemetry_resolve_pid "$ps_text" "$bench_pattern" || true)"
    if [[ -n "$pid" ]]; then
        rss_kb="$(run_ssh "$ssh_host" "grep VmRSS /proc/$pid/status 2>/dev/null" 2>/dev/null | awk '{print $2}')"
    fi

    printf '{"ts":%s,"headroom":"%s","battery_pct":"%s","battery_temp_c":"%s","bench_pid":"%s","rss_kb":"%s"}\n' \
        "$ts" "${headroom:-null}" "${battery_pct:-null}" "${battery_temp:-null}" "${pid:-null}" "${rss_kb:-null}"
}

# telemetry_start <ssh_host> <bench_pattern> <out_jsonl_path>
#
# Starts a background sampling loop and echoes its PID. Caller is
# responsible for telemetry_stop-ing it and must not assume the sampler
# exits on its own.
telemetry_start() {
    local ssh_host="$1" bench_pattern="$2" out_path="$3"
    (
        while true; do
            telemetry_sample_once "$ssh_host" "$bench_pattern" >>"$out_path" 2>/dev/null || true
            sleep "$TELEMETRY_INTERVAL_S"
        done
    ) &
    echo $!
}

# telemetry_stop <pid> — stop a sampler started by telemetry_start.
telemetry_stop() {
    local pid="$1"
    [[ -z "$pid" ]] && return 0
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" 2>/dev/null || true
}

# telemetry_ensure_no_orphan_bench <ssh_host> <bench_pattern>
#
# Post-cell cleanup: find and kill any lingering benchmark process in
# Termux so a timed-out/aborted cell never leaves the device busy for the
# next one (handoff §18: "SIGINT cleanup that stops samplers and leaves no
# orphan benchmark").
telemetry_ensure_no_orphan_bench() {
    local ssh_host="$1" bench_pattern="$2"
    local pgrep_text pids pid
    pgrep_text="$(run_ssh "$ssh_host" pgrep -l "$bench_pattern" 2>/dev/null || true)"
    pids="$(telemetry_parse_pgrep "$pgrep_text")"
    [[ -z "$pids" ]] && return 0

    m0_warn "orphan benchmark process(es) found in Termux — killing: $pids"
    while IFS= read -r pid; do
        [[ -z "$pid" ]] && continue
        run_ssh "$ssh_host" kill -9 "$pid" >/dev/null 2>&1 || true
    done <<<"$pids"
}
