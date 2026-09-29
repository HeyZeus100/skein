#!/usr/bin/env bash
# Only the coordinator dispatches this opt-in disposable-emulator lane.
set -euo pipefail
# connectedAndroidTest can enumerate multiple devices. Keep this helper on the disposable CI host,
# where the workflow creates the sole target; local execution must not discover the held Fold.
test "${GITHUB_ACTIONS:-}" = true || { echo 'This helper requires the disposable CI emulator host' >&2; exit 2; }
mkdir -p build/foldable-evidence
# Fail closed before any device operation if the target is not an emulator.
serial="${ANDROID_SERIAL:-emulator-5554}"
case "$serial" in emulator-*) ;; *) echo 'Refusing non-emulator target' >&2; exit 2 ;; esac
export ANDROID_SERIAL="$serial"
test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
for _ in $(seq 1 60); do
  if test "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1; then break; fi
  sleep 2
done
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$serial" shell wm dismiss-keyguard
adb -s "$serial" shell svc power stayon true
adb -s "$serial" shell settings put system screen_off_timeout 2147483647
adb -s "$serial" shell getprop > build/foldable-evidence/emulator-properties.txt
adb -s "$serial" shell wm size > build/foldable-evidence/initial-window.txt
adb -s "$serial" shell wm density >> build/foldable-evidence/initial-window.txt
set +e
./gradlew --max-workers=2 :app:connectedDevDebugAndroidTest --stacktrace -Pskein.foldableTests=true -Pandroid.experimental.androidTest.enableEmulatorControl=true -Pandroid.testInstrumentationRunnerArguments.class=app.skein.foldable.MainActivityFoldableGateTest > build/foldable-evidence/gradle.log 2>&1
result=$?
# Preserve failures too. Never clear logcat or replace a prior review with a synthetic success.
adb -s "$serial" logcat -d -v threadtime > build/foldable-evidence/logcat.txt
adb -s "$serial" exec-out run-as app.skein cat files/foldable-gate-metrics.jsonl > build/foldable-evidence/window-geometry.jsonl 2> build/foldable-evidence/window-geometry-error.txt
cat build/foldable-evidence/gradle.log
exit "$result"
