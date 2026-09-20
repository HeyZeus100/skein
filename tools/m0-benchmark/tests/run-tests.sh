#!/usr/bin/env bash
# Runs every unit test for the M0 benchmark harness: the bash-junit-style
# test_*.sh files (lib/preflight.sh, lib/thermal.sh, lib/telemetry.sh,
# lib/ssh-exec.sh) and the Python stdlib unittest suite (lib/models.py,
# lib/collect.py).
#
# No integration tests against the real Fold — every device call here is
# either a pure function or fully mocked. See docs/DEVICE_RUNNER.md for
# what only runs against real hardware.
#
# Usage: bash tools/m0-benchmark/tests/run-tests.sh

set -uo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

overall_status=0

echo "== bash unit tests =="
for test_file in "$TEST_DIR"/test_*.sh; do
    [[ -f "$test_file" ]] || continue
    echo "-- $(basename "$test_file")"
    bash "$test_file"
    status=$?
    if [[ "$status" -ne 0 ]]; then
        overall_status=1
    fi
done

echo ""
echo "== python unit tests =="
if command -v python3 >/dev/null 2>&1; then
    python3 -m unittest discover -s "$TEST_DIR" -p 'test_*.py' -v
    status=$?
    if [[ "$status" -ne 0 ]]; then
        overall_status=1
    fi
else
    echo "python3 not found — skipping Python unit tests" >&2
    overall_status=1
fi

echo ""
if [[ "$overall_status" -eq 0 ]]; then
    echo "ALL TESTS PASSED"
else
    echo "TESTS FAILED" >&2
fi
exit "$overall_status"
