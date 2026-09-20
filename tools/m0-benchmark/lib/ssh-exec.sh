#!/usr/bin/env bash
# SSH execution wrapper — invokes llama-bench inside Termux and captures
# structured JSON output (handoff §13: "SSH into Termux and invoke selected
# llama-bench binary").
#
# ssh_build_bench_cmdline is pure (string building only) and unit tested.
# ssh_exec_bench does real I/O through run_ssh (lib/common.sh), which is
# mockable via M0_MOCK_DEVICE — never called directly in --dry-run mode.

if [[ -n "${_M0_SSH_EXEC_SH_LOADED:-}" ]]; then
    return 0 2>/dev/null || exit 0
fi
_M0_SSH_EXEC_SH_LOADED=1

SELF_DIR_SSH_EXEC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SELF_DIR_SSH_EXEC/common.sh"

# ssh_build_bench_cmdline <binary_path> <model_path> <backend> <n_prompt> \
#     <n_gen> <threads> <gpu_layers> <repeats>
#
# Builds the exact llama-bench command line run remotely. `backend` is
# recorded for provenance only — GPU layer count is what actually selects
# CPU (0) vs Vulkan (>0) behavior in llama-bench.
ssh_build_bench_cmdline() {
    local binary_path="$1" model_path="$2" backend="$3" n_prompt="$4" \
          n_gen="$5" threads="$6" gpu_layers="$7" repeats="$8"

    printf '%q -m %q -p %q -n %q -t %q -ngl %q -r %q --output json' \
        "$binary_path" "$model_path" "$n_prompt" "$n_gen" "$threads" "$gpu_layers" "$repeats"
}

# ssh_exec_bench <ssh_host> <cmdline> <timeout_s> <stdout_path> <stderr_path>
#
# Runs <cmdline> on the Termux side wrapped in `timeout`, so a hung
# benchmark can't block the matrix forever. Writes stdout/stderr to the
# given per-cell files and returns the remote exit status (256 on ssh
# transport failure vs. distinguishable via SSH_EXEC_TRANSPORT_FAILED).
ssh_exec_bench() {
    local ssh_host="$1" cmdline="$2" timeout_s="$3" stdout_path="$4" stderr_path="$5"
    local remote_cmd="timeout ${timeout_s}s ${cmdline}"
    local status=0

    run_ssh "$ssh_host" "$remote_cmd" >"$stdout_path" 2>"$stderr_path" || status=$?
    echo "$status"
}

# ssh_classify_exit_status <status>
#
# Maps an exit status to a machine-readable failure_reason (or "ok").
# 124 is GNU coreutils `timeout`'s SIGTERM-timeout code; 137 = killed by
# SIGKILL (OOM killer on Linux/Android commonly manifests this way).
ssh_classify_exit_status() {
    local status="$1"
    case "$status" in
        0)   echo "ok" ;;
        124) echo "timeout" ;;
        137) echo "oom-or-sigkill" ;;
        255) echo "ssh-transport-error" ;;
        *)   echo "nonzero-exit" ;;
    esac
}
