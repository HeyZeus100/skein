#!/usr/bin/env bash
#
# E3.I1 (spec §9): defense-in-depth against merged-manifest surprises from
# third-party libraries. `ManifestPolicyTest` (app/src/test/kotlin/app/skein/
# ManifestPolicyTest.kt) asserts the same facts against the pre-merge *debug*
# manifest via Robolectric; this script asserts them against an actual built
# `foss` APK (debug or release) via `aapt2`, which is what ships (release) or
# what CI assembles on every push/PR (debug -- see .github/workflows/ci.yml).
#
# Usage:
#   tools/ci/manifest-audit.sh [path/to/app-foss-<debug|release>[-unsigned].apk]
#   tools/ci/manifest-audit.sh --self-test
#
# With no argument, it builds `:app:assembleFossRelease` and audits the
# resulting APK. Requires `aapt2` on PATH (ships in
# $ANDROID_HOME/build-tools/<version>/aapt2) and, when building, a
# configured Android SDK (ANDROID_HOME / local.properties).
#
# The debug/release build type is auto-detected from the APK path (a
# case-insensitive substring match on "debug" or "release" -- this matches
# both Gradle's own output paths, e.g. app/build/outputs/apk/foss/debug/
# app-foss-debug.apk, and AGP's merged-manifest intermediates directory
# names, e.g. app/build/intermediates/merged_manifests/fossRelease/). The
# exported-component allowlist below is build-type-specific: debug legally
# carries one extra exported activity (androidx.compose.ui.tooling's debug-
# only PreviewActivity) that must never appear in a release build. If the
# release signing config ever blocks `:app:assembleFossRelease` (it does
# not today -- E1.I8's signed pipeline is still pending and the `release`
# build type has no signingConfig, so AGP happily produces an unsigned
# release APK), point this script at the merged manifest XML under
# app/build/intermediates/merged_manifests/fossRelease/ instead and say so
# in the run's log -- `aapt2 dump badging/xmltree` both also accept a bare
# manifest XML wrapped how AGP already produces it inside that directory's
# APK, so no separate code path is needed as long as an APK (signed,
# unsigned, or debug) is what's handed to this script.
#
# --self-test runs the exact same checks below against an embedded fixture
# manifest dump (no aapt2, no Gradle) that is byte-for-byte the current
# real fossRelease exported/permission/backup posture plus one deliberately
# added exported receiver (`app.skein.debug.RogueReceiver`) that is not on
# any allowlist. It proves the negative path from this issue's acceptance
# criteria: a genuinely new exported component must still fail the script.
# Exit 1 is the *correct* (self-test passed) outcome.
#
# Written against `bash --version` 3.2 (macOS's system bash) as the floor:
# no `mapfile`/`readarray`, and `awk` calls stick to POSIX features (no
# gawk-only 3-arg `match()`) since macOS ships the one-true-awk, not gawk.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# Fixture manifest dumps for --self-test: a release-shaped app.skein
# manifest (matching today's real `:app:assembleFossRelease` output
# verbatim) with one extra exported receiver spliced in. Kept as functions
# (not top-level heredocs) so `set -u` never sees them before use.
self_test_fixture_badging() {
  cat <<'FIXTURE_BADGING'
package: name='app.skein' versionCode='1' versionName='0.1.0' platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'30'
targetSdkVersion:'37'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='android.permission.USE_FINGERPRINT'
uses-permission: name='app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
application: label='Skein' icon=''
launchable-activity: name='app.skein.MainActivity'  label='' icon=''
main
other-services
FIXTURE_BADGING
}

self_test_fixture_xmltree() {
  cat <<'FIXTURE_XMLTREE'
N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=1
    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="0.1.0" (Raw: "0.1.0")
    A: package="app.skein" (Raw: "app.skein")
      E: uses-permission (line=22)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")
      E: uses-permission (line=23)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.USE_BIOMETRIC" (Raw: "android.permission.USE_BIOMETRIC")
      E: uses-permission (line=26)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.USE_FINGERPRINT" (Raw: "android.permission.USE_FINGERPRINT")
      E: uses-permission (line=32)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
      E: application (line=39)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.skein.SkeinApplication" (Raw: "app.skein.SkeinApplication")
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=true
        A: http://schemas.android.com/apk/res/android:fullBackupContent(0x010104eb)=@0x7f100000
        A: http://schemas.android.com/apk/res/android:hasFragileUserData(0x0101059a)=true
        A: http://schemas.android.com/apk/res/android:dataExtractionRules(0x0101063e)=@0x7f100001
          E: activity (line=50)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.skein.MainActivity" (Raw: "app.skein.MainActivity")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
              E: intent-filter (line=53)
                  E: action (line=54)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
                  E: category (line=56)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
          E: service (line=64)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.skein.inference.service.InferenceService" (Raw: "app.skein.inference.service.InferenceService")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
            A: http://schemas.android.com/apk/res/android:isolatedProcess(0x010103a9)=true
          E: service (line=71)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.skein.embedder.service.EmbedderService" (Raw: "app.skein.embedder.service.EmbedderService")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
            A: http://schemas.android.com/apk/res/android:isolatedProcess(0x010103a9)=true
          E: provider (line=94)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.skein.core.vault.provider.VaultDocumentsProvider" (Raw: "app.skein.core.vault.provider.VaultDocumentsProvider")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.MANAGE_DOCUMENTS" (Raw: "android.permission.MANAGE_DOCUMENTS")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
          E: receiver (line=150)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.skein.debug.RogueReceiver" (Raw: "app.skein.debug.RogueReceiver")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
FIXTURE_XMLTREE
}

self_test=0
apk_path="${1:-}"

if [[ "$apk_path" == "--self-test" ]]; then
  self_test=1
fi

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

# Auto-detects debug vs. release from the APK (or merged-manifest
# intermediates directory) path via a case-insensitive substring match.
# Fails closed -- an unrecognized path is a hard error, not a silent
# default -- because the exported-component allowlist genuinely differs
# per build type (debug legally exports PreviewActivity; release must not).
detect_variant() {
  local path_lower
  path_lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  if [[ "$path_lower" == *debug* ]]; then
    printf 'debug\n'
  elif [[ "$path_lower" == *release* ]]; then
    printf 'release\n'
  else
    echo "manifest-audit: FAIL -- cannot infer build variant (debug/release) from path '$1';" \
      "expected 'debug' or 'release' to appear in it" >&2
    exit 1
  fi
}

if [[ "$self_test" -eq 1 ]]; then
  variant="release"
  echo "manifest-audit: --self-test -- auditing the embedded fixture manifest" \
    "(real fossRelease posture + 1 rogue exported receiver); expecting FAIL" >&2
  badging="$(self_test_fixture_badging)"
  xmltree="$(self_test_fixture_xmltree)"
else
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

  variant="$(detect_variant "$apk_path")"
  echo "manifest-audit: auditing $apk_path (variant: $variant)" >&2

  badging="$(aapt2 dump badging "$apk_path")"
  xmltree="$(aapt2 dump xmltree "$apk_path" --file AndroidManifest.xml)"
fi

failures=0

fail() {
  echo "manifest-audit: FAIL -- $1" >&2
  failures=$((failures + 1))
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

# --- Permission set is exactly {POST_NOTIFICATIONS, USE_BIOMETRIC}, plus
#     two androidx-injected additions accepted as safe-by-construction
#     (mirrors ManifestPolicyTest's ANDROIDX_INJECTED_PERMISSIONS): the
#     DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION shim, and USE_FINGERPRINT
#     (androidx.biometric:1.1.0, maxSdkVersion=28 -- a no-op given this
#     app's minSdk=30, but aapt2 badging surfaces it regardless of
#     maxSdkVersion since that only gates the runtime grant). Identical
#     across debug and release -- verified against both real APKs. ---
declared_permissions="$(
  grep -o "uses-permission: name='[^']*'" <<<"$badging" \
    | sed -E "s/.*name='([^']*)'/\1/" \
    | sort -u
)"
expected_permissions="$(printf '%s\n' \
  "android.permission.POST_NOTIFICATIONS" \
  "android.permission.USE_BIOMETRIC" \
  "android.permission.USE_FINGERPRINT" \
  "app.skein.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" \
  | sort -u
)"
if [[ "$declared_permissions" != "$expected_permissions" ]]; then
  fail "permission set is [$(tr '\n' ' ' <<<"$declared_permissions")], expected [$(tr '\n' ' ' <<<"$expected_permissions")]"
fi

# --- Backup / extraction posture. `debuggable` is release-only: a debug
#     APK is expected to be debuggable, that's not a posture regression. ---
if [[ "$variant" == "release" ]]; then
  if grep -Eq "^application-debuggable" <<<"$badging"; then
    fail "release APK is debuggable"
  fi
fi
if [[ "$(app_attr_value allowBackup)" != "true" ]]; then
  fail "android:allowBackup is not true"
fi
if [[ -z "$(app_attr_value dataExtractionRules)" ]]; then
  fail "android:dataExtractionRules is absent"
fi
# E3.I7 (docs/design/POST_REVIEW_RESOLUTIONS.md §4.3, skein-7ki2) supersedes
# E3.I1's original "fullBackupContent must be absent": dataExtractionRules
# is API 31+ only and minSdk here is 30, so fullBackupContent is required
# to give pre-31 devices (incl. Seedvault D2D on API 30) the same exclusion
# posture via backup_rules_legacy.xml -- see ManifestPolicyTest's
# "fullBackupContent points at the API 30 legacy rules resource" test. This
# script only checks presence (not the exact @xml/backup_rules_legacy
# value), same as it already does for dataExtractionRules, since aapt2
# dump xmltree doesn't resolve resource IDs back to symbolic @xml/... names.
if [[ -z "$(app_attr_value fullBackupContent)" ]]; then
  fail "android:fullBackupContent is absent (required for the API<=30 fallback, E3.I7)"
fi
if [[ "$(app_attr_value hasFragileUserData)" != "true" ]]; then
  fail "android:hasFragileUserData is not true"
fi

# --- Exported component allowlist, per build type (mirrors
#     ManifestPolicyTest's EXPECTED_EXPORTED_COMPONENTS / DOCUMENTS_PROVIDER
#     / DEBUG_ONLY_EXPORTED_COMPONENTS -- do not let this drift from that
#     test again; that's exactly how this script went stale, skein-rfz2):
#       - release: the launcher activity + the vault DocumentsProvider
#         (E2.I6 / skein-75x, POST_REVIEW_RESOLUTIONS.md §4.3).
#       - debug: the release set plus androidx.compose.ui.tooling's
#         PreviewActivity, exported only because ui-tooling is a
#         debugImplementation-only dependency (app/build.gradle.kts) that
#         merges it into *debug* variant manifests for Android Studio's
#         interactive/deploy preview. It never reaches a release build.
#     Walks the xmltree with the current element's tag/name, matching a
#     component's own exported/isolatedProcess attributes (which aapt2
#     always emits before any child element). ---
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
release_exported_components="$(printf '%s\n' \
  "activity:app.skein.MainActivity" \
  "provider:app.skein.core.vault.provider.VaultDocumentsProvider" \
  | sort -u
)"
case "$variant" in
  release)
    expected_exported_components="$release_exported_components"
    ;;
  debug)
    expected_exported_components="$(
      printf '%s\nactivity:androidx.compose.ui.tooling.PreviewActivity\n' "$release_exported_components" | sort -u
    )"
    ;;
esac
if [[ "$exported_components" != "$expected_exported_components" ]]; then
  fail "exported component set is [$(tr '\n' ' ' <<<"$exported_components")], expected ($variant) [$(tr '\n' ' ' <<<"$expected_exported_components")]"
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
