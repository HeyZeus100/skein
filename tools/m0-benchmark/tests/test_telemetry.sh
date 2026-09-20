#!/usr/bin/env bash
# Unit tests for tools/m0-benchmark/lib/telemetry.sh — PID resolution and
# pgrep parsing (the pure functions; sampler start/stop is I/O and is
# exercised by --dry-run / real hardware runs instead).

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="$TEST_DIR/../lib"
# shellcheck source=./assert.sh
source "$TEST_DIR/assert.sh"
# shellcheck source=../lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=../lib/telemetry.sh
source "$LIB_DIR/telemetry.sh"

PS_TEXT="USER       PID  %CPU %MEM COMMAND
u0_a214    1234   0.1  0.0 sshd
u0_a214   18042  97.3 12.4 /data/data/com.termux/files/home/skein-device/bin/llama-bench-vulkan
u0_a214   18099   0.0  0.0 ps"

test_resolve_pid_finds_matching_process() {
    local pid
    pid="$(telemetry_resolve_pid "$PS_TEXT" "llama-bench")"
    assert_eq "18042" "$pid"
}

test_resolve_pid_returns_nonzero_when_absent() {
    telemetry_resolve_pid "$PS_TEXT" "llama-cli" >/dev/null
    assert_false "no matching process must return non-zero, not PID 0" "$?"
}

test_resolve_pid_ignores_blank_lines() {
    local text="

$PS_TEXT

"
    local pid
    pid="$(telemetry_resolve_pid "$text" "llama-bench")"
    assert_eq "18042" "$pid"
}

PGREP_TEXT="18042 llama-bench-vulkan
18043 llama-bench-vulkan"

test_parse_pgrep_lists_every_pid() {
    local pids
    pids="$(telemetry_parse_pgrep "$PGREP_TEXT")"
    assert_eq "$(printf '18042\n18043')" "$pids"
}

test_parse_pgrep_empty_input_yields_nothing() {
    local pids
    pids="$(telemetry_parse_pgrep "")"
    assert_eq "" "$pids"
}

run_all_tests
exit $?
