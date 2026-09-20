#!/usr/bin/env bash
# Unit tests for tools/m0-benchmark/lib/ssh-exec.sh — command-line building
# and exit-status classification (both pure; ssh_exec_bench itself is I/O
# and is exercised by --dry-run / real hardware runs instead).

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="$TEST_DIR/../lib"
# shellcheck source=./assert.sh
source "$TEST_DIR/assert.sh"
# shellcheck source=../lib/common.sh
source "$LIB_DIR/common.sh"
# shellcheck source=../lib/ssh-exec.sh
source "$LIB_DIR/ssh-exec.sh"

test_build_cmdline_cpu() {
    local cmdline
    cmdline="$(ssh_build_bench_cmdline \
        "/data/data/com.termux/files/home/skein-device/bin/llama-bench-cpu" \
        "/sdcard/skein-bench/models/model.gguf" "cpu" 1024 128 4 0 1)"
    assert_eq \
        "/data/data/com.termux/files/home/skein-device/bin/llama-bench-cpu -m /sdcard/skein-bench/models/model.gguf -p 1024 -n 128 -t 4 -ngl 0 -r 1 --output json" \
        "$cmdline"
}

test_build_cmdline_vulkan_sets_gpu_layers() {
    local cmdline
    cmdline="$(ssh_build_bench_cmdline \
        "/data/data/com.termux/files/home/skein-device/bin/llama-bench-vulkan" \
        "/sdcard/skein-bench/models/model.gguf" "vulkan" 4096 128 4 99 3)"
    case "$cmdline" in
        *"-ngl 99"*) assert_true "vulkan cmdline should carry -ngl 99" 0 ;;
        *) assert_true "vulkan cmdline should carry -ngl 99" 1 ;;
    esac
    case "$cmdline" in
        *"-r 3"*) assert_true "cmdline should carry the repeat count" 0 ;;
        *) assert_true "cmdline should carry the repeat count" 1 ;;
    esac
}

test_build_cmdline_quotes_paths_with_spaces() {
    local cmdline
    cmdline="$(ssh_build_bench_cmdline "/bin/bench" "/sdcard/my models/model.gguf" "cpu" 1 1 1 0 1)"
    case "$cmdline" in
        *"my\\ models"*) assert_true "space in model path should be shell-escaped" 0 ;;
        *) assert_true "space in model path should be shell-escaped" 1 ;;
    esac
}

test_classify_exit_status_ok() {
    assert_eq "ok" "$(ssh_classify_exit_status 0)"
}

test_classify_exit_status_timeout() {
    assert_eq "timeout" "$(ssh_classify_exit_status 124)"
}

test_classify_exit_status_oom() {
    assert_eq "oom-or-sigkill" "$(ssh_classify_exit_status 137)"
}

test_classify_exit_status_ssh_transport_error() {
    assert_eq "ssh-transport-error" "$(ssh_classify_exit_status 255)"
}

test_classify_exit_status_generic_nonzero() {
    assert_eq "nonzero-exit" "$(ssh_classify_exit_status 1)"
}

run_all_tests
exit $?
