#!/usr/bin/env bash
# Unit tests for tools/m0-benchmark/lib/thermal.sh (thermal gate state machine).

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="$TEST_DIR/../lib"
# shellcheck source=./assert.sh
source "$TEST_DIR/assert.sh"
# shellcheck source=../lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=../lib/thermal.sh
source "$LIB_DIR/thermal.sh"

test_classify_cool() {
    assert_eq "COOL" "$(thermal_classify 0.10)"
}

test_classify_warm() {
    assert_eq "WARM" "$(thermal_classify 0.50)"
}

test_classify_hot() {
    assert_eq "HOT" "$(thermal_classify 0.95)"
}

test_classify_unknown_on_missing() {
    assert_eq "UNKNOWN" "$(thermal_classify "")"
    assert_eq "UNKNOWN" "$(thermal_classify "null")"
    assert_eq "UNKNOWN" "$(thermal_classify "not-a-number")"
}

test_gate_ok_smoke_loose() {
    thermal_gate_ok "0.85" "smoke"
    assert_true "0.85 headroom should pass the loose smoke gate" "$?"
}

test_gate_blocks_smoke_when_over_threshold() {
    thermal_gate_ok "0.95" "smoke"
    assert_false "0.95 headroom should fail even the loose smoke gate" "$?"
}

test_gate_ok_formal_strict() {
    thermal_gate_ok "0.40" "formal"
    assert_true "0.40 headroom should pass the strict formal gate" "$?"
}

test_gate_blocks_formal_at_smoke_level() {
    thermal_gate_ok "0.85" "formal"
    assert_false "0.85 headroom should fail the strict formal gate" "$?"
}

test_gate_fails_closed_on_missing_headroom() {
    thermal_gate_ok "" "smoke"
    assert_false "empty headroom must fail closed, not assume OK" "$?"
    thermal_gate_ok "null" "formal"
    assert_false "'null' headroom must fail closed" "$?"
}

test_gate_rejects_unknown_mode() {
    thermal_gate_ok "0.10" "bogus-mode"
    assert_false "unknown mode must not silently pass" "$?"
}

# Fake headroom reader used by the cooldown state-machine tests below: each
# call pops the next value off a script array so we can simulate a device
# cooling down over several polls without any real sleep/adb.
#
# thermal_wait_for_cooldown invokes the reader via `$("$read_fn")`, which
# forks a subshell — a plain variable index would be mutated only in that
# subshell and never advance in the parent, looping forever. Persist the
# index in a file instead, which does survive across the subshell calls.
_FAKE_HEADROOM_IDX_FILE="$(mktemp)"
trap 'rm -f "$_FAKE_HEADROOM_IDX_FILE"' EXIT

_set_fake_headroom_script() {
    _FAKE_HEADROOM_SCRIPT=("$@")
    echo 0 > "$_FAKE_HEADROOM_IDX_FILE"
}

_fake_read_headroom() {
    local idx v
    idx="$(cat "$_FAKE_HEADROOM_IDX_FILE")"
    v="${_FAKE_HEADROOM_SCRIPT[$idx]}"
    if (( idx < ${#_FAKE_HEADROOM_SCRIPT[@]} - 1 )); then
        echo $((idx + 1)) > "$_FAKE_HEADROOM_IDX_FILE"
    fi
    echo "$v"
}

test_cooldown_succeeds_once_target_reached() {
    _set_fake_headroom_script 0.80 0.60 0.35
    local result
    result="$(thermal_wait_for_cooldown "formal" "_fake_read_headroom" 60 0)"
    local status=$?
    assert_true "cooldown should succeed once headroom drops to target" "$status"
    assert_eq "0.35" "$result"
}

test_cooldown_times_out_if_never_cools() {
    _set_fake_headroom_script 0.90
    thermal_wait_for_cooldown "formal" "_fake_read_headroom" 1 1 >/dev/null
    assert_false "cooldown must time out (thermal-abort) if headroom never drops" "$?"
}

run_all_tests
exit $?
