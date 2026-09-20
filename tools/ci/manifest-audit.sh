#!/usr/bin/env bash
#
# E3.I1 (spec §9): defense-in-depth against merged-manifest surprises from
# third-party libraries. `ManifestPolicyTest` (app/src/test/kotlin/app/skein/
# ManifestPolicyTest.kt) asserts the same facts against the pre-merge *debug*
# manifest via Robolectric; this script asserts them against the actual
# built `foss` *release* APK via `aapt2`, which is what ships.
#
# Usage:
#   tools/ci/manifest-audit.sh [path/to/app-foss-release-unsigned.apk]
#
# With no argument, it builds `:app:assembleFossRelease` and audits the
# resulting APK. Requires `aapt2` on PATH (ships in
# $ANDROID_HOME/build-tools/<version>/aapt2) and, when building, a
# configured Android SDK (ANDROID_HOME / local.properties).
#
# Written against `bash --version` 3.2 (macOS's system bash) as the floor:
# no `mapfile`/`readarray`, and `awk` calls stick to POSIX features (no
# gawk-only 3-arg `match()`) since macOS ships the one-true-awk, not gawk.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

apk_path="${1:-}"

if [[ -z "$apk_path" ]]; then
  echo "manifest-audit: no APK given, building :app:assembleFossRelease" >&2
  ./gradlew :app:assembleFossRelease
  apk_path="$(find app/build/outputs/apk/foss/release -maxdepth 1 -name '*.apk' | head -n1)"
fi

if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "manifest-audit: FAIL -- could not find a release APK (looked for: '${apk_path}')" >&2
  exit 1
fi

if ! command -v aapt2 >/dev/null 2>&1; then
  echo "manifest-audit: FAIL -- aapt2 not found on PATH (it ships in \$ANDROID_HOME/build-tools/<version>/)" >&2
  exit 1
fi

echo "manifest-audit: auditing $apk_path" >&2

badging="$(aapt2 dump badging "$apk_path")"
xmltree="$(aapt2 dump xmltree "$apk_path" --file AndroidManifest.xml)"

failures=0

fail() {
  echo "manifest-audit: FAIL -- $1" >&2
  failures=$((failures + 1))
}

# Value of a unique, application-level attribute in the xmltree dump (e.g.
# "true", a "@0x..." resource reference, or "" if the attribute is absent).
# aapt2 prefixes every attribute with its full namespace URI
# ("http://schemas.android.com/apk/res/android:allowBackup(0x...)=..."), so
# we match on ":$attr(0x" rather than anchoring on "android:$attr".
app_attr_value() {
  local attr="$1"
  printf '%s\n' "$xmltree" \
    | grep -m1 ":${attr}(0x[0-9a-f]*)=" \
    | sed -E "s/.*:${attr}\\(0x[0-9a-f]*\\)=//"
}

# --- No network, ever (spec §2.1/§2.2; also enforced at build time by the
#     ManifestGuardTask Gradle guard from E1.I2). ---
if grep -q "uses-permission: name='android.permission.INTERNET'" <<<"$badging"; then
  fail "android.permission.INTERNET is present"
fi
if grep -q "uses-permission: name='android.permission.ACCESS_NETWORK_STATE'" <<<"$badging"; then
  fail "android.permission.ACCESS_NETWORK_STATE is present"
fi
if [[ -n "$(app_attr_value networkSecurityConfig)" ]]; then
  fail "android:networkSecurityConfig is present (should be absent -- no network)"
fi

# --- Permission set is exactly {POST_NOTIFICATIONS, USE_BIOMETRIC}, plus the
#     androidx-injected DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION shim (see
#     ManifestPolicyTest for why that one is an accepted addition). ---
declared_permissions="$(
  grep -o "uses-permission: name='[^']*'" <<<"$badging" \
    | sed -E "s/.*name='([^']*)'/\1/" \
    | sort -u
)"
expected_permissions="$(printf '%s\n' \
  "android.permission.POST_NOTIFICATIONS" \
  "android.permission.USE_BIOMETRIC" \
  "app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" \
  | sort -u
)"
if [[ "$declared_permissions" != "$expected_permissions" ]]; then
  fail "permission set is [$(tr '\n' ' ' <<<"$declared_permissions")], expected [$(tr '\n' ' ' <<<"$expected_permissions")]"
fi

# --- Backup / extraction posture. ---
if grep -Eq "^application-debuggable" <<<"$badging"; then
  fail "release APK is debuggable"
fi
if [[ "$(app_attr_value allowBackup)" != "true" ]]; then
  fail "android:allowBackup is not true"
fi
if [[ -z "$(app_attr_value dataExtractionRules)" ]]; then
  fail "android:dataExtractionRules is absent"
fi
if [[ -n "$(app_attr_value fullBackupContent)" ]]; then
  fail "android:fullBackupContent is present (should be absent)"
fi
if [[ "$(app_attr_value hasFragileUserData)" != "true" ]]; then
  fail "android:hasFragileUserData is not true"
fi

# --- Exported component allowlist: only the launcher activity today (extend
#     when E2.I6/VaultDocumentsProvider or E6.I17/SkeinVoiceInteractionService
#     land -- see ManifestPolicyTest). Walks the xmltree with the current
#     element's tag/name, matching a component's own exported/isolatedProcess
#     attributes (which aapt2 always emits before any child element). ---
exported_components="$(
  awk '
    /^ *E: / {
      line = $0
      sub(/^ *E: /, "", line)
      sub(/ \(line=.*/, "", line)
      element = line
      name = ""
    }
    /android:name\(0x01010003\)=/ && name == "" {
      match($0, /"[^"]*"/)
      name = substr($0, RSTART + 1, RLENGTH - 2)
    }
    /android:exported\(0x01010010\)=true/ &&
      (element == "activity" || element == "service" || element == "provider" || element == "receiver") {
      print element ":" name
    }
  ' <<<"$xmltree" | sort -u
)"
expected_exported_components="activity:app.skein.MainActivity"
if [[ "$exported_components" != "$expected_exported_components" ]]; then
  fail "exported component set is [$(tr '\n' ' ' <<<"$exported_components")], expected [$expected_exported_components]"
fi

# --- Isolated services stay isolated and unexported. ---
service_flags="$(
  awk '
    function flush() {
      if (element == "service" &&
          (name == "app.skein.inference.service.InferenceService" ||
           name == "app.skein.embedder.service.EmbedderService")) {
        print name ":" exported ":" isolated
      }
    }
    /^ *E: / {
      flush()
      line = $0
      sub(/^ *E: /, "", line)
      sub(/ \(line=.*/, "", line)
      element = line
      name = ""
      exported = "false"
      isolated = "false"
    }
    /android:name\(0x01010003\)=/ && name == "" {
      match($0, /"[^"]*"/)
      name = substr($0, RSTART + 1, RLENGTH - 2)
    }
    /android:exported\(0x01010010\)=true/ { exported = "true" }
    /android:isolatedProcess\(0x010103a9\)=true/ { isolated = "true" }
    END { flush() }
  ' <<<"$xmltree"
)"
for service in InferenceService EmbedderService; do
  line="$(grep "$service" <<<"$service_flags" || true)"
  if [[ -z "$line" ]]; then
    fail "$service not found in the merged manifest"
    continue
  fi
  IFS=':' read -r _name exported isolated <<<"$line"
  if [[ "$isolated" != "true" ]]; then
    fail "$service is missing android:isolatedProcess=true"
  fi
  if [[ "$exported" != "false" ]]; then
    fail "$service is exported"
  fi
done

if [[ "$failures" -gt 0 ]]; then
  echo "manifest-audit: $failures check(s) failed" >&2
  exit 1
fi

echo "manifest-audit: PASS"
