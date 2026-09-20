#!/usr/bin/env bash
# Tiny bash-junit-style assertion framework for the M0 harness unit tests.
#
# Each test_*.sh file sources this, defines functions named `test_*`, and
# ends with `run_all_tests`. run-tests.sh runs every test_*.sh file as its
# own subprocess (via `bash <file>`), so files never leak globals, mock
# functions, or trap state into each other.

TESTS_RUN=0
TESTS_FAILED=0
CURRENT_TEST=""

assert_eq() {
    local expected="$1" actual="$2" msg="${3:-}"
    TESTS_RUN=$((TESTS_RUN + 1))
    if [[ "$expected" != "$actual" ]]; then
        TESTS_FAILED=$((TESTS_FAILED + 1))
        echo "FAIL: ${CURRENT_TEST} ${msg} — expected '${expected}', got '${actual}'" >&2
        return 1
    fi
    return 0
}

assert_true() {
    local msg="${1:-command should succeed}" status="$2"
    TESTS_RUN=$((TESTS_RUN + 1))
    if [[ "$status" -ne 0 ]]; then
        TESTS_FAILED=$((TESTS_FAILED + 1))
        echo "FAIL: ${CURRENT_TEST} ${msg} — exit status was $status" >&2
        return 1
    fi
    return 0
}

assert_false() {
    local msg="${1:-command should fail}" status="$2"
    TESTS_RUN=$((TESTS_RUN + 1))
    if [[ "$status" -eq 0 ]]; then
        TESTS_FAILED=$((TESTS_FAILED + 1))
        echo "FAIL: ${CURRENT_TEST} ${msg} — expected non-zero exit, got 0" >&2
        return 1
    fi
    return 0
}

# run_all_tests — discovers every test_* function defined in THIS process
# (i.e. in the file that sourced assert.sh) and runs them in declaration
# order, then prints a summary and exits 0 (all passed) or 1 (any failed).
run_all_tests() {
    local fn
    for fn in $(declare -F | awk '{print $3}' | grep '^test_' | sort); do
        CURRENT_TEST="$(basename "${BASH_SOURCE[1]:-$0}"):$fn"
        "$fn"
    done
    echo "  ${BASH_SOURCE[1]:-$0}: ${TESTS_RUN} run, ${TESTS_FAILED} failed"
    [[ "$TESTS_FAILED" -eq 0 ]]
}
