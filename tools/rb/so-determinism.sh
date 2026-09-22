#!/usr/bin/env bash
#
# E1.I8 followup (bd skein-ylux) — is `libskein_llama.so` a function of its
# sources?
#
# Usage:
#   tools/rb/so-determinism.sh                    # 2 cold builds, compare
#   tools/rb/so-determinism.sh -n 20              # 20 cold builds, compare
#   tools/rb/so-determinism.sh --negative-control # reintroduce the defect
#   tools/rb/so-determinism.sh --self-test        # no build, no SDK needed
#
# Options:
#   -n, --builds <N>     how many cold builds to compare (default 2, min 2)
#       --abi <abi>      which packaged ABI to hash (default arm64-v8a)
#       --keep <dir>     copy every built .so into <dir> for `cmp`
#       --negative-control
#                        build with -Pskein.llama.shaderPatches=false, i.e.
#                        with native/llama/patches/* NOT applied. This puts
#                        the known defect back and the run is EXPECTED to
#                        fail; it is how a green run from this script is
#                        shown to mean something.
#       --self-test      exercise the verdict logic on synthetic inputs
#
# Why this exists, and why at the .so and not at the APK:
#
# The two-runner comparison in .github/workflows/reproducible-build.yml hashes
# a ~100 MB APK at the end of a 90-minute build. When it disagrees, the report
# says "one entry differs" and somebody has to work out whether that is a
# supply-chain alarm. bd skein-ylux was exactly that: NDK r27c's glslc
# (`shaderc v2022.3`) mis-folds an `int -> float -> float16_t` constant in
# `tri.comp` / `diag.comp` and emits an `OpConstant %float` holding
# uninitialised memory, so the embedded SPIR-V — and the .so — differed
# between builds roughly one time in seven.
#
# Building the library twice IN ONE JOB and comparing catches that class of
# defect where it is diagnosable: one artifact, 25 MB, and `cmp -l` points at
# the offending bytes. It is a different question from the two-runner check
# (which asks "would somebody ELSE get these bytes?") and does not replace it.
#
# Exit status: 0 = every build produced the same sha256. 1 = they did not
# (or, under --negative-control, they did, which means the check has stopped
# detecting the defect it was written for). 2 = could not run the check at
# all. "Could not check" is never reported as "reproduced".
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BUILDS=2
ABI="arm64-v8a"
KEEP_DIR=""
NEGATIVE=0
SELF_TEST=0

die() {
    echo "so-determinism: $*" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        -n | --builds)
            BUILDS="${2:-}"
            shift 2 || die "--builds needs a value"
            ;;
        --abi)
            ABI="${2:-}"
            shift 2 || die "--abi needs a value"
            ;;
        --keep)
            KEEP_DIR="${2:-}"
            shift 2 || die "--keep needs a directory"
            ;;
        --negative-control)
            NEGATIVE=1
            shift
            ;;
        --self-test)
            SELF_TEST=1
            shift
            ;;
        -h | --help)
            sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
    esac
done

case "$BUILDS" in
    '' | *[!0-9]*) die "--builds must be an integer, got '$BUILDS'" ;;
esac
[ "$BUILDS" -ge 2 ] || die "--builds must be at least 2, got $BUILDS"

# -----------------------------------------------------------------------------
# The verdict, factored out so --self-test can exercise it without a build.
# -----------------------------------------------------------------------------
# Reads one "<n> <sha256>" record per line on stdin. Prints the table, then
# the verdict. Returns 0 iff every record carries the same hash.
verdict() {
    local label="$1"
    local records distinct
    records="$(cat)"
    [ -n "$records" ] || {
        echo "[FAIL] $label: no hashes recorded at all"
        return 1
    }
    # shellcheck disable=SC2001  # sed is clearer than a read loop here
    echo "$records" | sed 's/^/  /'
    distinct="$(echo "$records" | awk '{print $2}' | sort -u | wc -l | tr -d ' ')"
    if [ "$distinct" = "1" ]; then
        echo "[ok]   $label: $(echo "$records" | wc -l | tr -d ' ') build(s), 1 distinct sha256"
        return 0
    fi
    echo "[FAIL] $label: $distinct distinct sha256 across $(echo "$records" | wc -l | tr -d ' ') build(s)"
    return 1
}

# -----------------------------------------------------------------------------
# --self-test: prove the verdict FAILS when it should, before a green run from
# it is allowed to mean anything. No Android SDK, no NDK, no network.
# -----------------------------------------------------------------------------
if [ "$SELF_TEST" -eq 1 ]; then
    failures=0

    out="$(printf '1 aaa\n2 aaa\n3 aaa\n' | verdict "identical")"
    rc=$?
    if [ "$rc" -eq 0 ] && echo "$out" | grep -q '^\[ok\]'; then
        echo "[ok]   identical hashes are accepted"
    else
        echo "[FAIL] identical hashes were not accepted:"
        echo "$out"
        failures=$((failures + 1))
    fi

    out="$(printf '1 aaa\n2 bbb\n' | verdict "one flake")"
    rc=$?
    if [ "$rc" -ne 0 ] && echo "$out" | grep -q '^\[FAIL\]'; then
        echo "[ok]   a single differing hash is rejected"
    else
        echo "[FAIL] a differing hash was NOT rejected:"
        echo "$out"
        failures=$((failures + 1))
    fi

    out="$(printf '1 aaa\n2 aaa\n3 ccc\n4 aaa\n' | verdict "late flake")"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "[ok]   a flake in the middle of a run is rejected"
    else
        echo "[FAIL] a mid-run flake was NOT rejected"
        failures=$((failures + 1))
    fi

    out="$(printf '' | verdict "empty")"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "[ok]   no hashes at all is a failure, not a pass"
    else
        echo "[FAIL] an empty run was reported as reproduced"
        failures=$((failures + 1))
    fi

    # The option the negative control depends on must still exist, or
    # --negative-control would silently become a second positive run.
    if grep -q 'SKEIN_LLAMA_SHADER_PATCHES' "$REPO_ROOT/native/llama/CMakeLists.txt" \
        && grep -q 'skein.llama.shaderPatches' "$REPO_ROOT/inference-service/build.gradle.kts"; then
        echo "[ok]   the --negative-control switch is still plumbed end to end"
    else
        echo "[FAIL] SKEIN_LLAMA_SHADER_PATCHES / skein.llama.shaderPatches is no longer plumbed"
        failures=$((failures + 1))
    fi

    # Every hash the shader patches pin must be a full sha256, or the
    # configure-time guard in native/llama/CMakeLists.txt §5a is decorative.
    if [ -f "$REPO_ROOT/native/llama/patches/PINS.txt" ] \
        && ! grep -Ev '^[[:space:]]*(#|$)' "$REPO_ROOT/native/llama/patches/PINS.txt" \
            | grep -qvE '^(patch|before|after)[[:space:]]+[0-9a-f]{64}[[:space:]]+[^[:space:]]+$'; then
        echo "[ok]   native/llama/patches/PINS.txt parses and pins full sha256s"
    else
        echo "[FAIL] native/llama/patches/PINS.txt is missing or malformed"
        failures=$((failures + 1))
    fi

    echo
    if [ "$failures" -eq 0 ]; then
        echo "SELF-TEST PASSED (6/6)"
        exit 0
    fi
    echo "SELF-TEST FAILED ($failures check(s))"
    exit 1
fi

# -----------------------------------------------------------------------------
# The real thing.
# -----------------------------------------------------------------------------
cd "$REPO_ROOT" || die "cannot cd to $REPO_ROOT"
[ -x ./gradlew ] || die "no ./gradlew in $REPO_ROOT"

SO_REL="inference-service/build/intermediates/stripped_native_libs/fossRelease/stripFossReleaseDebugSymbols/out/lib/$ABI/libskein_llama.so"

# One derivation of SOURCE_DATE_EPOCH, shared with CI and verify.sh. Without
# it the two builds would differ for a reason that is not the one under test.
if [ -z "${SOURCE_DATE_EPOCH:-}" ]; then
    SOURCE_DATE_EPOCH="$("$SCRIPT_DIR/source-date-epoch.sh" HEAD)" \
        || die "could not derive SOURCE_DATE_EPOCH"
fi
export SOURCE_DATE_EPOCH

GRADLE_EXTRA=()
if [ "$NEGATIVE" -eq 1 ]; then
    GRADLE_EXTRA+=("-Pskein.llama.shaderPatches=false")
fi

if [ -n "$KEEP_DIR" ]; then
    mkdir -p "$KEEP_DIR" || die "cannot create --keep dir $KEEP_DIR"
fi

LOG_DIR="$(mktemp -d)" || die "cannot create a temp dir"

echo "so-determinism: $BUILDS cold build(s) of :inference-service:assembleFossRelease"
echo "  repo                 $REPO_ROOT"
echo "  abi                  $ABI"
echo "  SOURCE_DATE_EPOCH    $SOURCE_DATE_EPOCH"
echo "  shader patches       $([ "$NEGATIVE" -eq 1 ] && echo 'OFF (negative control)' || echo ON)"
echo "  build logs           $LOG_DIR"
echo

records=""
i=1
while [ "$i" -le "$BUILDS" ]; do
    # `./gradlew clean` alone is not enough: AGP's externalNativeBuild keeps
    # its CMake/Ninja tree in `<module>/.cxx`, OUTSIDE `build/`, and a stale
    # one makes a "clean" build reuse objects (docs/REPRODUCIBLE_BUILDS.md,
    # "The .cxx trap"). Both are wiped, every iteration.
    rm -rf inference-service/.cxx core/vault/.cxx

    # Never pipe gradle into another command: this is bash, but the scripts
    # here are run under zsh too and zsh has no PIPESTATUS, so a pipeline
    # would silently report the tail's status. Redirect, then read $?.
    # `${a[@]+"${a[@]}"}` rather than `"${a[@]}"`: macOS still ships bash 3.2,
    # where expanding an empty array under `set -u` is an error.
    ./gradlew clean :inference-service:assembleFossRelease --no-build-cache \
        ${GRADLE_EXTRA[@]+"${GRADLE_EXTRA[@]}"} > "$LOG_DIR/build-$i.log" 2>&1
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "build $i FAILED (rc=$rc); tail of $LOG_DIR/build-$i.log:" >&2
        tail -30 "$LOG_DIR/build-$i.log" >&2
        exit 2
    fi
    [ -f "$SO_REL" ] || die "build $i produced no $SO_REL"

    h="$(shasum -a 256 "$SO_REL" | cut -d' ' -f1)"
    [ -n "$h" ] || die "could not hash $SO_REL"
    if [ -n "$KEEP_DIR" ]; then
        cp "$SO_REL" "$KEEP_DIR/libskein_llama.$i.$(echo "$h" | cut -c1-8).so"
    fi
    records="$records$i $h
"
    echo "  build $i  $h"
    i=$((i + 1))
done

echo
if printf '%s' "$records" | verdict "libskein_llama.so ($ABI)"; then
    ok=0
else
    ok=1
fi

echo
if [ "$NEGATIVE" -eq 1 ]; then
    if [ "$ok" -eq 1 ]; then
        echo "NEGATIVE CONTROL PASSED: with native/llama/patches/* not applied the"
        echo "check detected the nondeterminism it was written for (bd skein-ylux)."
        exit 0
    fi
    echo "NEGATIVE CONTROL FAILED: $BUILDS unpatched builds agreed, so this run"
    echo "proves nothing. The defect is intermittent (~1 build in 7 was measured"
    echo "on a GitHub runner, and closer to 1 in 2 on a 16-core laptop) -- raise"
    echo "--builds and re-run before concluding the check is broken."
    exit 1
fi

if [ "$ok" -eq 0 ]; then
    echo "libskein_llama.so is a function of its sources across $BUILDS cold builds."
    exit 0
fi
cat >&2 <<'EOF'

libskein_llama.so is NOT deterministic. This is a release blocker: it makes
the two-runner check in .github/workflows/reproducible-build.yml fail at
random and a third-party rebuilder unable to reproduce a genuine release.

To find it, re-run with --keep <dir> and then:

    cmp -l <dir>/libskein_llama.1.*.so <dir>/libskein_llama.2.*.so

Map the differing offsets to a section with `llvm-readelf -S`. If they land in
.rodata, search the build's SPIR-V for the surrounding bytes --

    inference-service/.cxx/*/*/arm64-v8a/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders.spv

-- which names the shader, then compile that one shader in a loop with the
NDK's glslc to confirm. That is how bd skein-ylux was found; the write-up is
in native/llama/README.md §4.
EOF
exit 1
