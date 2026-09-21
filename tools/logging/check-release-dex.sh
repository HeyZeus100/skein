#!/usr/bin/env bash
#
# E1.I11 (skein-4je) AC(a): "SkeinLog.d/i calls are absent from the release
# dex (dexdump or apkanalyzer grep on a release build shows zero call
# sites)". `SkeinLog.d`/`SkeinLog.i` are `@JvmStatic` (plain `public static
# void` methods) so `app/proguard-rules.pro`'s
# `-assumenosideeffects class app.skein.core.model.SkeinLog` rule removes
# every *invocation* of them once R8 runs (it does not remove the method
# declarations themselves -- this file's `-keep class ** { *; }` keeps
# those, on purpose, see that file's header comment). This script
# disassembles the release APK's dex with `dexdump` and greps the
# disassembly for an `invoke-{static,virtual}` referencing
# `Lapp/skein/core/model/SkeinLog;.d:` or `...;.i:` -- i.e. an actual call
# site, not the method's own definition header (which dexdump prints in a
# different, non-matching form: `(in Lapp/skein/core/model/SkeinLog;)` /
# `name: 'd'` on separate lines).
#
# Usage:
#   tools/logging/check-release-dex.sh [path/to/app-foss-release.apk]
#
# With no argument, builds `:app:assembleFossRelease` and checks the
# resulting APK. Requires `dexdump` on PATH or under
# $ANDROID_HOME/build-tools/<version>/ and `unzip`.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

apk_path="${1:-}"

if [[ -z "$apk_path" ]]; then
  echo "check-release-dex: no APK given, building :app:assembleFossRelease" >&2
  ./gradlew :app:assembleFossRelease
  apk_path="$(find app/build/outputs/apk/foss/release -maxdepth 1 -name '*.apk' | head -n1)"
fi

if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "check-release-dex: FAIL -- could not find a release APK (looked for: '${apk_path}')" >&2
  exit 1
fi

dexdump_bin="$(command -v dexdump || true)"
if [[ -z "$dexdump_bin" && -n "${ANDROID_HOME:-}" ]]; then
  dexdump_bin="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -name dexdump 2>/dev/null | sort -V | tail -n1)"
fi
if [[ -z "$dexdump_bin" ]]; then
  echo "check-release-dex: FAIL -- dexdump not found on PATH or under \$ANDROID_HOME/build-tools" >&2
  exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

if ! unzip -o -q "$apk_path" 'classes*.dex' -d "$workdir"; then
  echo "check-release-dex: FAIL -- could not extract classes*.dex from $apk_path" >&2
  exit 1
fi

echo "check-release-dex: auditing $apk_path (dexdump: $dexdump_bin)" >&2

hit_count=0
hits_file="$workdir/hits.txt"
: > "$hits_file"
for dex in "$workdir"/classes*.dex; do
  [[ -f "$dex" ]] || continue
  "$dexdump_bin" -d "$dex" \
    | grep -E 'Lapp/skein/core/model/SkeinLog;\.(d|i):' \
    >> "$hits_file" || true
done

hit_count="$(wc -l < "$hits_file" | tr -d ' ')"

if [[ "$hit_count" -gt 0 ]]; then
  echo "check-release-dex: FAIL -- $hit_count SkeinLog.d/i call site(s) found in the release dex:" >&2
  cat "$hits_file" >&2
  exit 1
fi

echo "check-release-dex: PASS -- 0 SkeinLog.d/i call sites in the release dex ($apk_path)"
