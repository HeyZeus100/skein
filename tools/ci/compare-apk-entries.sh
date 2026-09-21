#!/usr/bin/env bash
#
# skein-8jtj (E1.I8 / skein-ddp prerequisite): compare two APKs entry by
# entry and fail on any difference.
#
# Usage:
#   tools/ci/compare-apk-entries.sh <apkA> <apkB> [label]
#   tools/ci/compare-apk-entries.sh --self-test
#
# Exit status: 0 when the two APKs are byte-identical, 1 otherwise.
#
# Why this exists rather than a bare `sha256sum a.apk b.apk`:
# .github/workflows/reproducible-build.yml's contract *is* the whole-file
# sha256, but when that hash differs a bare "the hashes differ" tells a
# reproducer nothing actionable. The four checks below localise a diff to a
# named entry, which is how this job's one real historical failure
# (`META-INF/version-control-info.textproto` -- AGP's embedded git HEAD sha,
# now disabled via `vcsInfo { include = false }` in app/build.gradle.kts)
# was actually diagnosed. Checks run in order of increasing cost and all of
# them run even after one fails, so a single invocation reports everything.
#
# `diffoscope` would subsume this, but it is not installed on the CI runner
# (nor required to be): `unzip`, `zipinfo` and a sha256 tool are enough to
# localise a diff to an entry and are present out of the box on both
# ubuntu-latest and a macOS dev box. If you have diffoscope locally, running
# it on the two APKs is a strictly better *second* step once this script has
# told you which entries to look at.
#
# Portability: uses `shasum -a 256` or `sha256sum`, whichever exists, and
# avoids awk interval expressions where a plain character class will do.
set -uo pipefail

# --- sha256 helper (macOS ships shasum, Linux ships sha256sum) -------------
if command -v sha256sum > /dev/null 2>&1; then
    _sha256() { sha256sum "$@" | awk '{print $1}'; }
    _sha256_stdin() { sha256sum | awk '{print $1}'; }
elif command -v shasum > /dev/null 2>&1; then
    _sha256() { shasum -a 256 "$@" | awk '{print $1}'; }
    _sha256_stdin() { shasum -a 256 | awk '{print $1}'; }
else
    echo "compare-apk-entries: need sha256sum or shasum on PATH" >&2
    exit 2
fi

for _tool in unzip zipinfo; do
    command -v "$_tool" > /dev/null 2>&1 || {
        echo "compare-apk-entries: need '$_tool' on PATH" >&2
        exit 2
    }
done

# --- entry listings --------------------------------------------------------

# Entry names in *stored* order. Central-directory order is part of the
# archive bytes, so a reordering is a real reproducibility failure even when
# every entry's content matches -- hence this is compared unsorted.
entry_order() { unzip -Z1 "$1"; }

# Per-entry zip metadata, sorted by name so this check reports metadata
# drift independently of the ordering check above.
#
# `zipinfo -T -l` prints one row per entry as:
#   perms  zipver  os  uncompressed  t/b  compressed  method  YYYYMMDD.HHMMSS  name
#    $1      $2    $3      $4         $5      $6        $7           $8         $9..
# plus an "Archive:"/"Zip file size:" header and an "N files, ..." trailer.
# Selecting rows by "$8 looks like a -T timestamp" is what keeps those
# header/trailer lines out of the table -- matching on $1 (the permission
# string) does not, because zipinfo prints a 7-character DOS/FAT permission
# field (`-rw----`) for APK entries rather than the 10-character unix one.
# The name is reassembled from $9..$NF so an entry name containing a space
# is not silently truncated.
entry_meta() {
    zipinfo -T -l "$1" | awk '
        NF >= 9 && $8 ~ /^[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]\.[0-9][0-9][0-9][0-9][0-9][0-9]$/ {
            name = $9
            for (i = 9 + 1; i <= NF; i++) name = name " " $i
            printf "%s\t%s\tuncompressed=%s\tcompressed=%s\tmethod=%s\tmtime=%s\n", \
                name, $1, $4, $6, $7, $8
        }' | LC_ALL=C sort
}

# Per-entry content sha256, sorted by name.
#
# Entries are streamed out of the archive with `unzip -p` rather than
# extracted to a directory: extracting would collapse two entries whose
# names differ only in case on a case-insensitive filesystem (macOS), and
# would drop the distinction between a zero-byte file entry and a directory
# entry. `unzip -p` treats its file argument as a *pattern*, so glob
# metacharacters in an entry name are escaped first.
entry_hashes() {
    local apk="$1" name pattern
    entry_order "$apk" | while IFS= read -r name; do
        pattern=$(printf '%s\n' "$name" | sed 's/[][*?\\]/\\&/g')
        printf '%s\t%s\n' "$(unzip -p "$apk" "$pattern" 2> /dev/null | _sha256_stdin)" "$name"
    done | LC_ALL=C sort -k2
}

# --- the comparison ---------------------------------------------------------
compare_apks() {
    local A="$1" B="$2" label="${3:-compare}"
    local rc=0 w
    w=$(mktemp -d) || return 2
    # Cleaned up explicitly at the end rather than via a RETURN trap: this
    # function is called four times by --self-test, and a RETURN trap set
    # inside a function outlives that function in bash.

    local shaA shaB
    shaA=$(_sha256 "$A")
    shaB=$(_sha256 "$B")

    echo "=== $label ==="
    echo "A: $A"
    echo "   sha256=$shaA bytes=$(wc -c < "$A" | tr -d ' ')"
    echo "B: $B"
    echo "   sha256=$shaB bytes=$(wc -c < "$B" | tr -d ' ')"
    echo

    # 1. Entry membership and stored order.
    entry_order "$A" > "$w/orderA"
    entry_order "$B" > "$w/orderB"
    if diff -u "$w/orderA" "$w/orderB" > "$w/order.diff"; then
        echo "[ok]   entry list and stored order identical ($(wc -l < "$w/orderA" | tr -d ' ') entries)"
    else
        echo "[FAIL] entry list or stored order differs:"
        sed 's/^/       /' "$w/order.diff"
        rc=1
    fi

    # 2. Per-entry zip metadata. AGP normalizes every entry's mtime to the
    #    DOS epoch (1980-01-01 00:00:00), so a real wall-clock timestamp
    #    leaking into the archive surfaces here rather than as a content diff.
    entry_meta "$A" > "$w/metaA"
    entry_meta "$B" > "$w/metaB"
    if diff -u "$w/metaA" "$w/metaB" > "$w/meta.diff"; then
        echo "[ok]   per-entry zip metadata identical (size/method/mtime)"
        echo "       distinct mtimes: $(awk -F'mtime=' '{print $2}' "$w/metaA" | LC_ALL=C sort -u | tr '\n' ' ')"
    else
        echo "[FAIL] per-entry zip metadata differs:"
        sed 's/^/       /' "$w/meta.diff"
        rc=1
    fi

    # 3. Per-entry content hashes -- the table this issue actually wants.
    entry_hashes "$A" > "$w/hashA"
    entry_hashes "$B" > "$w/hashB"
    if diff -u "$w/hashA" "$w/hashB" > "$w/hash.diff"; then
        echo "[ok]   all $(wc -l < "$w/hashA" | tr -d ' ') entry contents identical"
    else
        echo "[FAIL] entry contents differ. Differing entries:"
        LC_ALL=C join -t'	' -j 2 -o 0,1.1,2.1 \
            <(LC_ALL=C sort -t'	' -k2 "$w/hashA") \
            <(LC_ALL=C sort -t'	' -k2 "$w/hashB") 2> /dev/null \
            | awk -F'\t' '$2 != $3 {printf "       %s\n         A %s\n         B %s\n", $1, $2, $3}'
        echo "       (entries present in only one APK are listed by check 1 above)"
        rc=1
    fi

    # 4. The contract: whole-file sha256.
    if [ "$shaA" = "$shaB" ]; then
        echo "[ok]   whole-file sha256 identical"
    else
        echo "[FAIL] whole-file sha256 differs ($shaA vs $shaB)"
        rc=1
    fi

    echo
    if [ "$rc" -eq 0 ]; then
        echo "RESULT: IDENTICAL -- $label"
    else
        echo "RESULT: DIFFERENT -- $label"
    fi
    rm -rf "$w"
    return "$rc"
}

# --- self-test (the negative case) -----------------------------------------
#
# Mirrors tools/ci/manifest-audit.sh's --self-test: proves the comparator
# fails closed. Without this, "the reproducible-build job is green" and "the
# reproducible-build job cannot go red" are indistinguishable.
self_test() {
    command -v zip > /dev/null 2>&1 || {
        echo "self-test: need 'zip' on PATH" >&2
        exit 2
    }
    local w rc out
    w=$(mktemp -d) || exit 2
    # Double-quoted so the path is baked into the trap string now; a
    # single-quoted '$w' would be expanded when the trap fires, by which
    # point this function's local `w` is out of scope (and `set -u` aborts).
    # shellcheck disable=SC2064
    trap "rm -rf '$w'" EXIT

    mkdir -p "$w/src/dir"
    printf 'classes\n' > "$w/src/classes.dex"
    printf 'manifest\n' > "$w/src/AndroidManifest.xml"
    printf 'lib\n' > "$w/src/dir/libskein_sqlite.so"

    ( cd "$w/src" && zip -q -X -r "$w/a.zip" . )
    cp "$w/a.zip" "$w/b.zip"

    local failures=0

    # Positive case: a byte-identical copy must pass.
    if out=$(compare_apks "$w/a.zip" "$w/b.zip" "self-test/identical" 2>&1); then
        echo "[ok]   self-test: identical archives compare equal"
    else
        echo "[FAIL] self-test: identical archives reported as different"
        echo "$out" | sed 's/^/       /'
        failures=$((failures + 1))
    fi

    # Negative case A: one entry's *content* changed, everything else equal.
    printf 'classes-ROGUE\n' > "$w/src/classes.dex"
    rm -f "$w/c.zip"
    ( cd "$w/src" && zip -q -X -r "$w/c.zip" . )
    out=$(compare_apks "$w/a.zip" "$w/c.zip" "self-test/rogue-entry" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q 'classes.dex'; then
        echo "[ok]   self-test: a changed entry is detected and named"
    else
        echo "[FAIL] self-test: a changed entry was NOT detected (rc=$rc)"
        echo "$out" | sed 's/^/       /'
        failures=$((failures + 1))
    fi

    # Negative case B: an extra entry (membership/order change).
    printf 'classes\n' > "$w/src/classes.dex"
    printf 'stowaway\n' > "$w/src/META-INF-rogue.txt"
    rm -f "$w/d.zip"
    ( cd "$w/src" && zip -q -X -r "$w/d.zip" . )
    out=$(compare_apks "$w/a.zip" "$w/d.zip" "self-test/extra-entry" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q 'META-INF-rogue.txt'; then
        echo "[ok]   self-test: an added entry is detected and named"
    else
        echo "[FAIL] self-test: an added entry was NOT detected (rc=$rc)"
        echo "$out" | sed 's/^/       /'
        failures=$((failures + 1))
    fi

    # Negative case C: identical content, different entry mtime. This is the
    # check that a bare per-entry content hash would miss entirely.
    rm -f "$w/META-INF-rogue.txt" "$w/src/META-INF-rogue.txt" "$w/e.zip"
    touch -t 203001010101.01 "$w/src/classes.dex"
    ( cd "$w/src" && zip -q -X -r "$w/e.zip" . )
    out=$(compare_apks "$w/a.zip" "$w/e.zip" "self-test/mtime-only" 2>&1)
    rc=$?
    if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -q 'zip metadata differs'; then
        echo "[ok]   self-test: an mtime-only change is detected"
    else
        echo "[FAIL] self-test: an mtime-only change was NOT detected (rc=$rc)"
        echo "$out" | sed 's/^/       /'
        failures=$((failures + 1))
    fi

    echo
    if [ "$failures" -eq 0 ]; then
        echo "SELF-TEST PASSED (4/4)"
        return 0
    fi
    echo "SELF-TEST FAILED ($failures check(s) did not behave as specified)"
    return 1
}

# --- entry point ------------------------------------------------------------
case "${1:-}" in
    --self-test)
        self_test
        ;;
    -h | --help | "")
        sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        if [ "$#" -lt 2 ]; then
            echo "compare-apk-entries: need two APK paths (see --help)" >&2
            exit 2
        fi
        for f in "$1" "$2"; do
            [ -f "$f" ] || {
                echo "compare-apk-entries: no such file: $f" >&2
                exit 2
            }
        done
        compare_apks "$1" "$2" "${3:-compare}"
        ;;
esac
