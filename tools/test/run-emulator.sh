#!/usr/bin/env bash
#
# E10.I1 (plan): the instrumented lane — real device or emulator behavior
# (native `.so` loading, real `WorkManager`, `DocumentsProvider` handshakes)
# that the JVM/Robolectric lanes cannot exercise. Mirrors what
# `.github/workflows/emulator.yml` runs on its `workflow_dispatch` + nightly
# schedule; this script targets an already-running emulator/device (an
# already-booted AVD, or a connected physical device via `adb devices`)
# rather than booting one itself.
#
# Usage:
#   tools/test/run-emulator.sh
#
# Requires: a running emulator or connected device visible to `adb devices`,
# and the Android SDK on PATH / `local.properties`. Uses the `dev` flavor
# (not `foss`): only `dev` ships an x86_64 native lib set
# (app/build.gradle.kts productFlavors), so it is what boots on an x86_64
# emulator.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if ! command -v adb >/dev/null 2>&1; then
  echo "run-emulator.sh: adb not on PATH; is the Android SDK's platform-tools directory on PATH?" >&2
  exit 1
fi

if [[ -z "$(adb devices | tail -n +2 | grep -w device || true)" ]]; then
  echo "run-emulator.sh: no device/emulator visible to 'adb devices'. Boot an AVD or connect a device first." >&2
  exit 1
fi

./gradlew connectedDevDebugAndroidTest --stacktrace
