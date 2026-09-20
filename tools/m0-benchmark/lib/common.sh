#!/usr/bin/env bash
# Shared helpers for the M0 benchmark harness.
#
# Sourced by run.sh and the other tools/m0-benchmark/lib/*.sh files. Never
# executed directly. Provides logging helpers and the ONE sanctioned choke
# point for talking to the device (run_adb / run_ssh), so every real or
# mocked device call is auditable in one place.

if [[ -n "${_M0_COMMON_SH_LOADED:-}" ]]; then
    return 0 2>/dev/null || exit 0
fi
_M0_COMMON_SH_LOADED=1

m0_log()  { printf '%s\n' "$*" >&2; }
m0_info() { m0_log "[info]  $*"; }
m0_warn() { m0_log "[warn]  $*"; }
m0_err()  { m0_log "[error] $*"; }

# --- Mockable device transport ----------------------------------------------
#
# M0_MOCK_DEVICE=1 routes every device call through mock_adb()/mock_ssh(),
# which the caller must define (run.sh does this for --dry-run; test files
# define their own canned versions). No other file in this harness may call
# the `adb` or `ssh` binaries directly — always go through run_adb/run_ssh so
# --dry-run and unit tests can prove they never touch real hardware.

: "${M0_MOCK_DEVICE:=0}"

run_adb() {
    if [[ "$M0_MOCK_DEVICE" == "1" ]]; then
        mock_adb "$@"
    else
        command adb "$@"
    fi
}

# run_ssh <host> <remote-command...>
run_ssh() {
    local host="$1"
    shift
    if [[ "$M0_MOCK_DEVICE" == "1" ]]; then
        mock_ssh "$host" "$@"
    else
        command ssh "$host" "$@"
    fi
}

# json_escape <string> — minimal JSON string escaping for hand-built JSON.
json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\n'/\\n}"
    s="${s//$'\t'/\\t}"
    printf '%s' "$s"
}

# require_cmd <cmd> [hint] — fail with a clear reason if a tool is missing.
require_cmd() {
    local cmd="$1" hint="${2:-}"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        m0_err "required command not found: $cmd${hint:+ ($hint)}"
        return 1
    fi
    return 0
}

# now_iso8601 — UTC timestamp in the format used across the JSON schema.
now_iso8601() {
    date -u +%Y-%m-%dT%H:%M:%SZ
}
