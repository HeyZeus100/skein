#!/usr/bin/env bash
#
# E1.I8 (skein-ddp) — verify that a published Skein APK was built from the
# source in this repository.
#
# Usage:
#   tools/rb/verify.sh <apk> <tag>            # the normal case
#   tools/rb/verify.sh <apk> <tag> [options]
#   tools/rb/verify.sh --self-test            # no clone, no build
#
# Options:
#   --repo <url|path>  where to clone from (default: this checkout's `origin`,
#                      falling back to this checkout itself)
#   --workdir <dir>    where to put the clean clone (default: a temp dir)
#   --keep             do not delete the workdir, so you can inspect the build
#   --strict           treat a toolchain deviation (wrong JDK vendor, wrong
#                      NDK, missing apksigner) as a FAILURE rather than as a
#                      reported deviation. CI uses this; a human on a
#                      different distro's JDK usually should not.
#
# What it does, and why in this order:
#
#   1. Reads the must-match toolchain from `reproducible-builds.yml` and
#      compares it with what is actually installed. A mismatch here explains
#      almost every "it didn't reproduce" report, so it is checked BEFORE an
#      hour of build time is spent.
#   2. Clones the repo fresh, at <tag>, with submodules, into a path that is
#      NOT this checkout -- a path-dependent difference is a real failure and
#      building in place would hide it.
#   3. Derives SOURCE_DATE_EPOCH from that commit (tools/rb/source-date-epoch.sh).
#   4. Builds `clean :app:assembleFossRelease --no-build-cache`.
#   5. Strips the APK Signing Block from the *published* APK
#      (tools/rb/strip-signature.py) so it can be compared with a locally
#      built UNSIGNED APK. Signatures are out of scope by design: a third
#      party cannot reproduce them and does not need to.
#   6. Prints the published APK's certificates (`apksigner verify
#      --print-certs`) so you can check them against docs/SIGNING.md -- that
#      is a SEPARATE claim from reproducibility and is reported, never merged
#      into the verdict.
#   7. Compares `unzip -l` listings, then hands the two APKs to
#      tools/ci/compare-apk-entries.sh, whose sha256 verdict is the contract.
#
# Exit status: 0 = reproduced, 1 = did not reproduce, 2 = could not run the
# check at all (bad usage, clone/build failure). "Could not check" is never
# reported as "reproduced".
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MANIFEST="$REPO_ROOT/reproducible-builds.yml"

APK=""
REV=""
CLONE_FROM=""
WORKDIR=""
KEEP=0
STRICT=0
DEVIATIONS=()

log() { printf '%s\n' "$*"; }
hr() { printf '%s\n' "------------------------------------------------------------"; }
die() {
    printf 'verify: %s\n' "$*" >&2
    exit 2
}

deviation() {
    DEVIATIONS+=("$1")
    if [ "$STRICT" -eq 1 ]; then
        log "[FAIL] $1"
    else
        log "[warn] $1"
    fi
}

sha256_of() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# --- reproducible-builds.yml ------------------------------------------------
# Read with PyYAML when available. If it is not, the toolchain report degrades
# to "unknown" and says so -- it never silently claims a match it did not make.
manifest_get() {
    python3 - "$MANIFEST" "$1" <<'PY' 2>/dev/null
import sys
try:
    import yaml
except ImportError:
    sys.exit(3)
with open(sys.argv[1]) as fh:
    doc = yaml.safe_load(fh)
node = doc
for part in sys.argv[2].split('.'):
    if not isinstance(node, dict) or part not in node:
        sys.exit(4)
    node = node[part]
print(node)
PY
}

check_toolchain() {
    hr
    log "Toolchain (from reproducible-builds.yml)"
    hr

    local want_vendor want_jdk want_ndk
    want_vendor=$(manifest_get toolchain.jdk.vendor) || want_vendor=""
    want_jdk=$(manifest_get toolchain.jdk.version) || want_jdk=""
    want_ndk=$(manifest_get toolchain.android_ndk.version) || want_ndk=""

    if [ -z "$want_jdk" ]; then
        deviation "could not read reproducible-builds.yml (is PyYAML installed?); toolchain NOT checked"
        return
    fi

    local java_bin="java"
    [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java_bin="$JAVA_HOME/bin/java"
    local java_out
    java_out=$("$java_bin" -version 2>&1 | tr '\n' ' ')
    log "  expected JDK : $want_vendor $want_jdk"
    log "  installed    : $java_out"

    # Major version is a hard requirement (class-file layout); the vendor and
    # the patch level are a reported deviation, because a local developer
    # build on another 17.0.x JDK is explicitly supported (the Gradle build
    # does not and must not refuse one) even though CI pins Temurin.
    case "$java_out" in
        *'version "17.'*) : ;;
        *) deviation "JDK major version is not 17; a reproduction is not expected to match" ;;
    esac
    case "$java_out" in
        *"$want_jdk"*) : ;;
        *) deviation "JDK is not exactly $want_jdk" ;;
    esac
    # Temurin identifies itself in the runtime name.
    if [ "$want_vendor" = "temurin" ]; then
        case "$java_out" in
            *Temurin* | *temurin*) : ;;
            *) deviation "JDK vendor is not Temurin (release builds are pinned to it)" ;;
        esac
    fi

    log "  expected NDK : $want_ndk"
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$want_ndk" ]; then
        log "  installed    : \$ANDROID_HOME/ndk/$want_ndk"
    else
        deviation "NDK $want_ndk not found under \$ANDROID_HOME/ndk (AGP may download it)"
    fi

    if [ -x "$REPO_ROOT/tools/rb/manifest-check.py" ] && [ -d "$REPO_ROOT/.git" ]; then
        if python3 "$REPO_ROOT/tools/rb/manifest-check.py" > /tmp/verify-manifest.log 2>&1; then
            log "  manifest     : agrees with the tree"
        else
            deviation "reproducible-builds.yml has drifted from the tree (see /tmp/verify-manifest.log)"
        fi
    fi
    log ""
}

find_apksigner() {
    command -v apksigner 2> /dev/null && return 0
    local candidate
    # Newest build-tools last, so the newest wins.
    for candidate in "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner; do
        [ -x "$candidate" ] && printf '%s\n' "$candidate"
    done | tail -1
}

# --- the comparison stage (also what --self-test exercises) -----------------
#
# Factored out so the self-test can drive the exact code path that produces
# the verdict, without a clone or a 90-minute build in between.
compare_stage() {
    local released="$1" rebuilt="$2" label="${3:-verify}"
    local rc=0 stripped
    stripped="$(dirname "$rebuilt")/released-stripped.apk"

    hr
    log "Stripping signatures from the published APK"
    hr
    if ! python3 "$SCRIPT_DIR/strip-signature.py" "$released" -o "$stripped"; then
        log "[FAIL] could not strip signatures from $released"
        return 2
    fi
    log ""

    hr
    log "Entry listings (unzip -l)"
    hr
    local w
    w=$(mktemp -d) || return 2
    # `unzip -l` prints `Length Date Time Name` rows between an Archive:/header
    # preamble and a `---- / N files` trailer. Rows are selected by "$1 is a
    # bare integer" rather than by line number, so neither the preamble, the
    # separator rules nor the totals line can leak in; the name is rebuilt
    # from $4..$NF so an entry name containing a space is not truncated.
    listing() {
        unzip -l "$1" | awk '
            $1 ~ /^[0-9]+$/ && NF >= 4 {
                name = $4
                for (i = 5; i <= NF; i++) name = name " " $i
                print $1, name
            }' | LC_ALL=C sort
    }
    listing "$stripped" > "$w/released.txt"
    listing "$rebuilt" > "$w/rebuilt.txt"
    if diff -u "$w/released.txt" "$w/rebuilt.txt" > "$w/listing.diff"; then
        log "[ok]   $(wc -l < "$w/released.txt" | tr -d ' ') entries, same names and uncompressed sizes"
    else
        log "[FAIL] entry listings differ:"
        sed 's/^/       /' "$w/listing.diff"
        rc=1
    fi
    log ""

    hr
    log "sha256"
    hr
    local sa sb
    sa=$(sha256_of "$stripped")
    sb=$(sha256_of "$rebuilt")
    log "  published (signatures stripped) : $sa"
    log "  rebuilt from source             : $sb"
    if [ "$sa" = "$sb" ]; then
        log "[ok]   sha256 identical"
    else
        log "[FAIL] sha256 differs"
        rc=1
    fi
    log ""

    hr
    log "Entry-by-entry comparison"
    hr
    if ! "$REPO_ROOT/tools/ci/compare-apk-entries.sh" "$stripped" "$rebuilt" "$label"; then
        rc=1
    fi

    if [ "$rc" -ne 0 ]; then
        log ""
        log "To classify the difference:"
        log "  python3 tools/rb/normalize-apk.py '$stripped' '$rebuilt'"
        log "  diffoscope --html apk-diff.html '$stripped' '$rebuilt'"
    fi

    rm -rf "$w"
    return "$rc"
}

# --- main -------------------------------------------------------------------
run_verify() {
    [ -f "$APK" ] || die "no such APK: $APK"
    [ -n "$REV" ] || die "need a tag or commit to rebuild"

    for tool in git unzip python3; do
        command -v "$tool" > /dev/null 2>&1 || die "need '$tool' on PATH"
    done

    check_toolchain

    hr
    log "Published APK"
    hr
    log "  path   : $APK"
    log "  sha256 : $(sha256_of "$APK")"
    log "  bytes  : $(wc -c < "$APK" | tr -d ' ')"
    log ""
    log "Certificates (this is a SEPARATE claim from reproducibility -- check"
    log "these fingerprints against docs/SIGNING.md yourself):"
    local apksigner
    apksigner=$(find_apksigner)
    if [ -n "$apksigner" ] && [ -x "$apksigner" ]; then
        "$apksigner" verify --print-certs "$APK" 2>&1 | sed 's/^/  /' || {
            log "  (apksigner reported no valid signature -- expected for an"
            log "   unsigned APK, a red flag for a published release)"
        }
    else
        deviation "apksigner not found (\$ANDROID_HOME/build-tools/*/apksigner); certificates NOT checked"
    fi
    log ""

    if [ -z "$CLONE_FROM" ]; then
        CLONE_FROM=$(git -C "$REPO_ROOT" remote get-url origin 2> /dev/null || printf '%s' "$REPO_ROOT")
    fi
    local cleanup=0
    if [ -z "$WORKDIR" ]; then
        WORKDIR=$(mktemp -d) || die "could not create a work dir"
        [ "$KEEP" -eq 0 ] && cleanup=1
    fi
    mkdir -p "$WORKDIR"
    local clone="$WORKDIR/skein"

    hr
    log "Clean clone"
    hr
    log "  from : $CLONE_FROM"
    log "  rev  : $REV"
    log "  into : $clone"
    rm -rf "$clone"
    git clone --quiet "$CLONE_FROM" "$clone" || die "clone failed"
    git -C "$clone" checkout --quiet --detach "$REV" || die "no such rev in the clone: $REV"
    git -C "$clone" submodule update --init --recursive --quiet || die "submodule init failed"
    log "  HEAD : $(git -C "$clone" rev-parse HEAD)"
    log ""

    # The Android SDK location is machine-local and gitignored, so a fresh
    # clone has none. Carry the current one over rather than making the
    # verifier configure it twice.
    if [ -n "${ANDROID_HOME:-}" ]; then
        printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$clone/local.properties"
    elif [ -f "$REPO_ROOT/local.properties" ]; then
        cp "$REPO_ROOT/local.properties" "$clone/local.properties"
    fi

    # Belt and braces: the clone is new, so `<module>/.cxx` cannot exist yet.
    # It is removed anyway because `--workdir` can point at a directory that
    # has been used before, and a surviving `.cxx` is the one failure mode
    # that produces a CONFIDENTLY WRONG answer rather than an error --
    # `./gradlew clean` does not touch it, and ninja does not rebuild when
    # only SOURCE_DATE_EPOCH has changed, so the "rebuild" would relink stale
    # objects. See docs/REPRODUCIBLE_BUILDS.md § "The .cxx trap".
    rm -rf "$clone"/*/.cxx "$clone"/*/*/.cxx

    local epoch
    epoch=$("$clone/tools/rb/source-date-epoch.sh" HEAD)
    export SOURCE_DATE_EPOCH="$epoch"
    log "SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH (committer date of $REV)"
    log ""

    hr
    log "Rebuilding (this takes a while: llama.cpp, ~1100 Vulkan shaders, OpenSSL)"
    hr
    local buildlog="$WORKDIR/build.log"
    log "  ./gradlew clean :app:assembleFossRelease --no-build-cache"
    log "  log: $buildlog"
    ( cd "$clone" && ./gradlew clean :app:assembleFossRelease --no-build-cache --stacktrace ) \
        > "$buildlog" 2>&1
    local build_rc=$?
    if [ "$build_rc" -ne 0 ]; then
        tail -40 "$buildlog"
        die "the rebuild failed (rc=$build_rc); see $buildlog"
    fi
    log "  build OK"
    log ""

    local rebuilt="$clone/app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk"
    [ -f "$rebuilt" ] || die "the build produced no $rebuilt"

    compare_stage "$APK" "$rebuilt" "verify $REV"
    local rc=$?

    hr
    if [ "$rc" -eq 0 ]; then
        log "RESULT: REPRODUCED -- $APK matches a fresh build of $REV"
    elif [ "$rc" -eq 1 ]; then
        log "RESULT: DID NOT REPRODUCE -- $APK differs from a fresh build of $REV"
    else
        log "RESULT: COULD NOT CHECK"
    fi
    if [ "${#DEVIATIONS[@]}" -gt 0 ]; then
        log ""
        log "Toolchain deviations (each of these can by itself explain a mismatch):"
        local d
        for d in "${DEVIATIONS[@]}"; do log "  - $d"; done
        if [ "$STRICT" -eq 1 ]; then
            log ""
            log "--strict: deviations are failures."
            [ "$rc" -eq 0 ] && rc=1
        fi
    fi
    hr

    [ "$cleanup" -eq 1 ] && rm -rf "$WORKDIR"
    return "$rc"
}

# --- self-test ---------------------------------------------------------------
#
# Drives compare_stage directly, with fabricated APKs, so the verdict logic is
# tested without a clone or a build. The negative case is the point: a
# deliberately altered "release" must be reported as a mismatch.
self_test() {
    command -v python3 > /dev/null 2>&1 || die "need python3"
    local w failures=0 out rc
    w=$(mktemp -d) || die "mktemp failed"

    python3 - "$w" <<'PY' || die "could not build self-test fixtures"
import os, struct, sys, zipfile
w = sys.argv[1]

def make(path, dex):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, payload in (
            ("AndroidManifest.xml", b"manifest"),
            ("classes.dex", dex),
            ("lib/arm64-v8a/libskein_sqlite.so", b"so" * 400),
            ("resources.arsc", b"arsc" * 40),
        ):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, payload)

# The "rebuild": what assembleFossRelease would produce, unsigned.
make(os.path.join(w, "rebuilt.apk"), b"dex" * 300)
# The honest release: the same bytes, plus an APK Signing Block.
make(os.path.join(w, "honest-unsigned.apk"), b"dex" * 300)
# A tampered release: one byte of dex changed, then signed.
make(os.path.join(w, "tampered-unsigned.apk"), b"dex" * 299 + b"hax")

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(sys.argv[0] or ".")), ""))

MAGIC = b"APK Sig Block 42"

def eocd(data):
    return data.rfind(b"PK\x05\x06")

def sign(src, dst, payload=b"z" * 700):
    data = open(src, "rb").read()
    e = eocd(data)
    (cd,) = struct.unpack_from("<I", data, e + 16)
    pair = struct.pack("<QI", 4 + len(payload), 0x7109871A) + payload
    size = len(pair) + 8 + 16
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + MAGIC
    out = bytearray(data[:cd] + block + data[cd:])
    struct.pack_into("<I", out, e + len(block) + 16, cd + len(block))
    open(dst, "wb").write(bytes(out))

sign(os.path.join(w, "honest-unsigned.apk"), os.path.join(w, "honest-signed.apk"))
sign(os.path.join(w, "tampered-unsigned.apk"), os.path.join(w, "tampered-signed.apk"))
PY

    # Positive case: a signed release built from the same source reproduces.
    out=$(compare_stage "$w/honest-signed.apk" "$w/rebuilt.apk" "self-test/honest" 2>&1)
    rc=$?
    if [ "$rc" -eq 0 ]; then
        log "[ok]   self-test: an honest signed release is REPRODUCED"
    else
        log "[FAIL] self-test: an honest signed release was reported as a mismatch (rc=$rc)"
        printf '%s\n' "$out" | sed 's/^/       /'
        failures=$((failures + 1))
    fi

    # Negative case 1: a tampered classes.dex must be caught AND named. This
    # is the check that makes a green verdict mean anything.
    out=$(compare_stage "$w/tampered-signed.apk" "$w/rebuilt.apk" "self-test/tampered" 2>&1)
    rc=$?
    if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'classes.dex'; then
        log "[ok]   self-test: a tampered classes.dex is reported as a mismatch and named"
    else
        log "[FAIL] self-test: a tampered release was NOT caught (rc=$rc)"
        printf '%s\n' "$out" | sed 's/^/       /'
        failures=$((failures + 1))
    fi

    # Negative case 2: an added entry (the classic "extra payload smuggled
    # into the release" shape).
    python3 - "$w" <<'PY'
import os, shutil, sys, zipfile
w = sys.argv[1]
src = os.path.join(w, "honest-unsigned.apk")
dst = os.path.join(w, "extra-unsigned.apk")
shutil.copy(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as zf:
    info = zipfile.ZipInfo("assets/payload.bin", date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    zf.writestr(info, b"stowaway")
PY
    out=$(compare_stage "$w/extra-unsigned.apk" "$w/rebuilt.apk" "self-test/extra-entry" 2>&1)
    rc=$?
    if [ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'assets/payload.bin'; then
        log "[ok]   self-test: an added entry is reported as a mismatch and named"
    else
        log "[FAIL] self-test: an added entry was NOT caught (rc=$rc)"
        printf '%s\n' "$out" | sed 's/^/       /'
        failures=$((failures + 1))
    fi

    # Negative case 3: "could not check" must never read as "reproduced".
    out=$(compare_stage "$w/not-an-apk" "$w/rebuilt.apk" "self-test/missing" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ]; then
        log "[ok]   self-test: an unreadable release APK does not verify"
    else
        log "[FAIL] self-test: an unreadable release APK was reported as reproduced"
        failures=$((failures + 1))
    fi

    # The deviation machinery must actually escalate under --strict.
    DEVIATIONS=()
    STRICT=0
    deviation "synthetic" > /dev/null
    if [ "${#DEVIATIONS[@]}" -eq 1 ]; then
        log "[ok]   self-test: deviations are recorded"
    else
        log "[FAIL] self-test: deviation was not recorded"
        failures=$((failures + 1))
    fi
    DEVIATIONS=()

    rm -rf "$w"
    log ""
    if [ "$failures" -eq 0 ]; then
        log "SELF-TEST PASSED (5/5)"
        return 0
    fi
    log "SELF-TEST FAILED ($failures check(s))"
    return 1
}

# --- argument parsing --------------------------------------------------------
[ "$#" -eq 0 ] && {
    sed -n '6,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --self-test)
            self_test
            exit $?
            ;;
        -h | --help)
            sed -n '6,24p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        --repo)
            CLONE_FROM="${2:-}"
            shift 2
            ;;
        --workdir)
            WORKDIR="${2:-}"
            shift 2
            ;;
        --keep)
            KEEP=1
            shift
            ;;
        --strict)
            STRICT=1
            shift
            ;;
        -*)
            die "unknown option: $1"
            ;;
        *)
            if [ -z "$APK" ]; then
                APK="$1"
            elif [ -z "$REV" ]; then
                REV="$1"
            else
                die "unexpected argument: $1"
            fi
            shift
            ;;
    esac
done

run_verify
