#!/usr/bin/env bash
#
# E1.I8 (skein-ddp) — the ONE derivation of SOURCE_DATE_EPOCH.
#
# Usage:
#   tools/rb/source-date-epoch.sh [<commit-ish>]   # prints the epoch
#   eval "$(tools/rb/source-date-epoch.sh --export)"
#   tools/rb/source-date-epoch.sh --self-test
#
# Why this is a script and not a line in build.gradle.kts:
# `SOURCE_DATE_EPOCH` is consumed by CMake — native/sqlite/CMakeLists.txt
# forwards it into the OpenSSL sub-build's environment, and
# native/llama/CMakeLists.txt reads it — and it is read from the *process
# environment* (`$ENV{SOURCE_DATE_EPOCH}`). AGP gives no supported way to
# inject an environment variable into the externalNativeBuild process, so the
# value has to be exported by whatever shell invokes `./gradlew`. Putting the
# derivation in exactly one place is what stops CI and a local verifier from
# quietly disagreeing about it.
#
# The derivation must be a function of the SOURCE. The obvious-looking
# `${{ github.event.repository.pushed_at }}` is not: it is the time of the
# *push*, so re-running the same workflow on the same tag yields a different
# value, and a local rebuild of that tag can never match. The committer date
# of the commit being built is stable for everyone who checks out that commit.
#
# Precedence:
#   1. An already-exported SOURCE_DATE_EPOCH wins, so a rebuild from a
#      git-less source export (F-Droid, a release tarball) can pin the value
#      the release was built with.
#   2. Otherwise `git log -1 --format=%ct <commit-ish>` (default HEAD).
#   3. Otherwise the constant native/{llama,sqlite}/CMakeLists.txt fall back
#      to, so "no git, nothing exported" is still deterministic rather than
#      wall-clock. It is NOT the same value as (2), which is exactly why a
#      verifier must export the derived value rather than rely on the
#      fallback.
set -euo pipefail

# Keep in sync with native/{llama,sqlite}/CMakeLists.txt (2026-01-01T00:00:00Z).
# tools/rb/manifest-check.sh asserts all three agree.
CMAKE_FALLBACK_CONSTANT=1767225600

derive() {
    local rev="${1:-HEAD}" epoch

    if [ -n "${SOURCE_DATE_EPOCH:-}" ]; then
        printf '%s\n' "$SOURCE_DATE_EPOCH"
        return 0
    fi

    # `git log -1 --format=%ct` is the COMMITTER date. The author date (%at)
    # is preserved across a rebase and would make two different commits share
    # an epoch; the committer date tracks the commit object, which is what
    # the tree is identified by.
    if epoch=$(git log -1 --format=%ct "$rev" 2> /dev/null) && [ -n "$epoch" ]; then
        printf '%s\n' "$epoch"
        return 0
    fi

    printf '%s\n' "$CMAKE_FALLBACK_CONSTANT"
}

self_test() {
    local failures=0 out

    # 1. An exported value wins, untouched.
    out=$(SOURCE_DATE_EPOCH=1234567890 derive HEAD)
    if [ "$out" = "1234567890" ]; then
        echo "[ok]   ambient SOURCE_DATE_EPOCH is honoured verbatim"
    else
        echo "[FAIL] ambient SOURCE_DATE_EPOCH not honoured (got '$out')"
        failures=$((failures + 1))
    fi

    # 2. Inside a git repo with nothing exported, the commit time is used and
    #    is a bare integer.
    out=$(unset SOURCE_DATE_EPOCH; derive HEAD)
    if printf '%s' "$out" | grep -Eq '^[0-9]+$'; then
        echo "[ok]   derived from git committer date: $out"
    else
        echo "[FAIL] derivation did not yield an integer (got '$out')"
        failures=$((failures + 1))
    fi

    # 3. It must equal git's own answer for HEAD — i.e. it is a function of
    #    the commit, not of the clock.
    local expected
    expected=$(git log -1 --format=%ct HEAD)
    if [ "$out" = "$expected" ]; then
        echo "[ok]   derived value equals \`git log -1 --format=%ct HEAD\`"
    else
        echo "[FAIL] derived '$out' != git's '$expected'"
        failures=$((failures + 1))
    fi

    # 4. Twice in a row, with a second of wall clock in between, must agree —
    #    the negative case for "somebody used date(1)".
    local a b
    a=$(unset SOURCE_DATE_EPOCH; derive HEAD)
    sleep 1
    b=$(unset SOURCE_DATE_EPOCH; derive HEAD)
    if [ "$a" = "$b" ]; then
        echo "[ok]   stable across wall-clock time"
    else
        echo "[FAIL] value changed with the wall clock ($a -> $b)"
        failures=$((failures + 1))
    fi

    # 5. Outside any git repo, with nothing exported, the CMake fallback
    #    constant is used rather than `date +%s`.
    # Cleaned up explicitly rather than with a RETURN trap: a trap set inside
    # a bash function outlives that function (same reason
    # tools/ci/compare-apk-entries.sh avoids one).
    local w
    w=$(mktemp -d)
    out=$(cd "$w" && unset SOURCE_DATE_EPOCH && GIT_CEILING_DIRECTORIES="$(dirname "$w")" derive HEAD)
    rm -rf "$w"
    if [ "$out" = "$CMAKE_FALLBACK_CONSTANT" ]; then
        echo "[ok]   falls back to the CMake constant outside a git repo"
    else
        echo "[FAIL] no-git fallback was '$out', expected $CMAKE_FALLBACK_CONSTANT"
        failures=$((failures + 1))
    fi

    echo
    if [ "$failures" -eq 0 ]; then
        echo "SELF-TEST PASSED (5/5)"
        return 0
    fi
    echo "SELF-TEST FAILED ($failures check(s))"
    return 1
}

case "${1:-}" in
    --self-test)
        self_test
        ;;
    --export)
        printf 'export SOURCE_DATE_EPOCH=%s\n' "$(derive "${2:-HEAD}")"
        ;;
    -h | --help)
        sed -n '3,9p' "$0" | sed 's/^# \{0,1\}//'
        ;;
    *)
        derive "${1:-HEAD}"
        ;;
esac
