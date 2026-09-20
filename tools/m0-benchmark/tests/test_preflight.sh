#!/usr/bin/env bash
# Unit tests for tools/m0-benchmark/lib/preflight.sh.

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="$TEST_DIR/../lib"
# shellcheck source=./assert.sh
source "$TEST_DIR/assert.sh"
# shellcheck source=../lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=../lib/thermal.sh
source "$LIB_DIR/thermal.sh"
# shellcheck source=../lib/preflight.sh
source "$LIB_DIR/preflight.sh"

ADB_DEVICES_ONE_READY="List of devices attached
R58N123ABCD	device"

ADB_DEVICES_NONE="List of devices attached
"

ADB_DEVICES_UNAUTHORIZED="List of devices attached
R58N123ABCD	unauthorized"

ADB_DEVICES_TWO="List of devices attached
R58N123ABCD	device
EMULATOR5554	device"

test_parse_adb_devices_skips_header_and_blanks() {
    local parsed
    parsed="$(preflight_parse_adb_devices "$ADB_DEVICES_ONE_READY")"
    assert_eq "R58N123ABCD device" "$parsed"
}

test_single_ready_device_success() {
    local serial
    serial="$(preflight_single_ready_device "$ADB_DEVICES_ONE_READY" 2>/dev/null)"
    assert_true "exactly one ready device should succeed" "$?"
    assert_eq "R58N123ABCD" "$serial"
}

test_single_ready_device_fails_on_zero_devices() {
    preflight_single_ready_device "$ADB_DEVICES_NONE" 2>/dev/null
    assert_false "zero devices must fail" "$?"
}

test_single_ready_device_fails_on_unauthorized() {
    preflight_single_ready_device "$ADB_DEVICES_UNAUTHORIZED" 2>/dev/null
    assert_false "unauthorized device must fail, not be treated as ready" "$?"
}

test_single_ready_device_fails_on_multiple() {
    preflight_single_ready_device "$ADB_DEVICES_TWO" 2>/dev/null
    assert_false "multiple devices must fail closed rather than guess" "$?"
}

SHA_MANIFEST="de85775ae5ace0e70ffedef19582bf6572dac493565a69bc607c45326b6adcc8  /data/data/com.termux/files/home/skein-device/bin/llama-bench-cpu
cd5b6b4d4803b6281a66802f6aab82460b8a1421066ed54f505087c5cd7a929f  /data/data/com.termux/files/home/skein-device/bin/llama-bench-vulkan"

test_hash_from_manifest_matches_by_suffix() {
    local hash
    hash="$(preflight_hash_from_manifest "$SHA_MANIFEST" "llama-bench-vulkan")"
    assert_eq "cd5b6b4d4803b6281a66802f6aab82460b8a1421066ed54f505087c5cd7a929f" "$hash"
}

test_hash_from_manifest_no_match_returns_nonzero() {
    preflight_hash_from_manifest "$SHA_MANIFEST" "llama-bench-does-not-exist" >/dev/null
    assert_false "no matching manifest entry must return non-zero" "$?"
}

test_hashes_equal_case_insensitive() {
    preflight_hashes_equal "ABCDEF" "abcdef"
    assert_true "hash comparison must be case-insensitive" "$?"
}

test_hashes_equal_rejects_mismatch() {
    preflight_hashes_equal "abcdef" "123456"
    assert_false "different hashes must not compare equal" "$?"
}

test_hashes_equal_rejects_empty_expected() {
    preflight_hashes_equal "" "abcdef"
    assert_false "an empty expected hash must never 'match'" "$?"
}

test_check_formal_artifact_allows_smoke_mode_regardless() {
    preflight_check_formal_artifact "smoke" "false"
    assert_true "smoke mode never checks formal_m0_candidate" "$?"
}

test_check_formal_artifact_allows_formal_with_true_flag() {
    preflight_check_formal_artifact "formal" "true"
    assert_true "formal mode must accept a true formal_m0_candidate" "$?"
}

test_check_formal_artifact_refuses_formal_with_false_flag() {
    preflight_check_formal_artifact "formal" "false" 2>/dev/null
    assert_false "formal mode MUST refuse a smoke (false) artifact" "$?"
}

test_check_formal_artifact_refuses_formal_with_missing_flag() {
    preflight_check_formal_artifact "formal" "" 2>/dev/null
    assert_false "formal mode must refuse when formal_m0_candidate is unset" "$?"
}

test_check_sha256_present_rejects_tbd() {
    preflight_check_sha256_present "TBD" "model x" 2>/dev/null
    assert_false "a TBD placeholder hash must never pass preflight" "$?"
}

test_check_sha256_present_rejects_empty() {
    preflight_check_sha256_present "" "model x" 2>/dev/null
    assert_false "an empty hash must never pass preflight" "$?"
}

test_check_sha256_present_accepts_real_hash() {
    preflight_check_sha256_present "2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12" "model x"
    assert_true "a pinned 64-char hash must pass preflight" "$?"
}

# --- preflight_run orchestration, with adb/ssh fully mocked ------------------

M0_MOCK_DEVICE=1

mock_adb() {
    case "$1" in
        devices) printf 'List of devices attached\nR58N123ABCD\tdevice\n' ;;
        shell)
            shift
            case "$*" in
                *thermalservice*headroom*) echo "0.10" ;;
                *) echo "" ;;
            esac
            ;;
        *) echo "" ;;
    esac
}

mock_ssh() {
    case "$2" in
        true) return 0 ;;
        *) echo "" ;;
    esac
}

test_preflight_run_succeeds_with_healthy_mocks() {
    local serial
    serial="$(preflight_run "smoke" "skein-fold-agent" 2>/dev/null)"
    assert_true "preflight_run should succeed with a ready device, reachable ssh, cool device" "$?"
    assert_eq "R58N123ABCD" "$serial"
}

test_preflight_run_fails_when_ssh_unreachable() {
    # shellcheck disable=SC2317
    mock_ssh() { return 255; }
    preflight_run "smoke" "skein-fold-agent" >/dev/null 2>&1
    local status=$?
    # restore for any later test in this file
    mock_ssh() {
        case "$2" in
            true) return 0 ;;
            *) echo "" ;;
        esac
    }
    assert_false "preflight_run must fail when SSH is unreachable" "$status"
}

test_preflight_run_fails_when_too_hot_for_formal() {
    # shellcheck disable=SC2317
    mock_adb() {
        case "$1" in
            devices) printf 'List of devices attached\nR58N123ABCD\tdevice\n' ;;
            shell)
                shift
                case "$*" in
                    *thermalservice*headroom*) echo "0.85" ;;
                    *) echo "" ;;
                esac
                ;;
            *) echo "" ;;
        esac
    }
    preflight_run "formal" "skein-fold-agent" >/dev/null 2>&1
    local status=$?
    mock_adb() {
        case "$1" in
            devices) printf 'List of devices attached\nR58N123ABCD\tdevice\n' ;;
            shell)
                shift
                case "$*" in
                    *thermalservice*headroom*) echo "0.10" ;;
                    *) echo "" ;;
                esac
                ;;
            *) echo "" ;;
        esac
    }
    assert_false "preflight_run must refuse a formal run when headroom=0.85 (> strict gate)" "$status"
}

run_all_tests
exit $?
