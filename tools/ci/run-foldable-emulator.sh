#!/usr/bin/env bash
# Only the coordinator dispatches this opt-in disposable-emulator lane.
set -euo pipefail
# connectedAndroidTest can enumerate multiple devices. Keep this helper on the disposable CI host,
# where the workflow creates the sole target; local execution must not discover the held Fold.
test "${GITHUB_ACTIONS:-}" = true || { echo 'This helper requires the disposable CI emulator host' >&2; exit 2; }
mkdir -p build/foldable-evidence
# Keep the actual generated hardware identity/posture configuration, including on test failure.
# ANDROID_AVD_HOME is exported by the pinned emulator action; never substitute a device ID.
test "${SKEIN_FOLD_AVD_NAME:-}" = skein_foldable_gate
cp -f "${ANDROID_AVD_HOME:?}/$SKEIN_FOLD_AVD_NAME.avd/config.ini" build/foldable-evidence/avd-config.ini
# Fail closed before any device operation if the target is not an emulator.
serial="${ANDROID_SERIAL:-emulator-5554}"
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing non-emulator target' >&2; exit 2; }
export ANDROID_SERIAL="$serial"
adb_bounded() { timeout --kill-after=5s 15s adb -s "$serial" "$@"; }
identity_guard() {
  test "${GITHUB_ACTIONS:-}" = true || return 2
  test "$(adb_bounded shell getprop ro.kernel.qemu | tr -d '\r')" = 1 || return 2
  test "$(adb_bounded emu avd name | tr -d '\r')" = $'skein_foldable_gate\nOK' || return 2
}
guarded_adb() { identity_guard && adb_bounded "$@"; }
identity_guard
for _ in $(seq 1 60); do
  if test "$(adb_bounded shell getprop sys.boot_completed | tr -d '\r')" = 1; then break; fi
  sleep 2
done
guarded_adb shell input keyevent KEYCODE_WAKEUP
guarded_adb shell wm dismiss-keyguard
guarded_adb shell svc power stayon true
guarded_adb shell settings put system screen_off_timeout 2147483647
adb_bounded shell getprop > build/foldable-evidence/emulator-properties.txt
adb_bounded shell wm size > build/foldable-evidence/initial-window.txt
adb_bounded shell wm density >> build/foldable-evidence/initial-window.txt
# A fresh ID separates this instrumentation process from every previous attempt.
run_id="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
stop_file="build/foldable-evidence/console-stop-$run_id"
printf '%s\n' "$run_id" > build/foldable-evidence/console-run-id.txt
controller_pid=''
controller_result=0
stop_controller() {
  if test -n "$controller_pid"; then
    touch "$stop_file"
    # Normal cleanup needs at most four bounded adb calls; never leave a stalled child behind.
    for _ in $(seq 1 120); do
      kill -0 "$controller_pid" 2>/dev/null || break
      sleep 0.5
    done
    if kill -0 "$controller_pid" 2>/dev/null; then
      controller_result=1
      printf '%s\n' 'Controller stop deadline exceeded; terminating owned child' >> build/foldable-evidence/console-controller.log
      kill -TERM "$controller_pid" 2>/dev/null || true
      for _ in $(seq 1 10); do
        kill -0 "$controller_pid" 2>/dev/null || break
        sleep 0.2
      done
      if kill -0 "$controller_pid" 2>/dev/null; then kill -KILL "$controller_pid" 2>/dev/null || true; fi
    fi
    wait "$controller_pid" || controller_result=$?
    controller_pid=''
  fi
}
trap stop_controller EXIT
trap 'stop_controller; exit 130' INT
trap 'stop_controller; exit 143' TERM
python3 tools/ci/foldable-console-bridge.py --serial "$serial" --run-id "$run_id" \
  --evidence build/foldable-evidence --stop-file "$stop_file" \
  > build/foldable-evidence/console-controller.log 2>&1 &
controller_pid=$!
set +e
# Keep private geometry output available after AGP's task, until this disposable AVD is torn down.
./gradlew --max-workers=2 :app:connectedDevDebugAndroidTest --stacktrace \
  -Pskein.foldableTests=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.skein.foldable.ci=true \
  "-Pandroid.testInstrumentationRunnerArguments.skein.foldable.runId=$run_id" \
  -Pandroid.testInstrumentationRunnerArguments.class=app.skein.foldable.MainActivityFoldableGateTest \
  > build/foldable-evidence/gradle.log 2>&1
result=$?
stop_controller
if test "$controller_result" -ne 0; then result=1; fi
# Preserve failures too. Never clear logcat or replace a prior review with a synthetic success.
adb_bounded logcat -d -v threadtime > build/foldable-evidence/logcat.txt
adb_bounded exec-out run-as app.skein cat files/foldable-gate-metrics.jsonl > build/foldable-evidence/window-geometry.jsonl 2> build/foldable-evidence/window-geometry-error.txt
adb_bounded exec-out run-as app.skein cat files/foldable-device-states.txt > build/foldable-evidence/runtime-device-states.txt 2> build/foldable-evidence/runtime-device-states-error.txt
adb_bounded exec-out run-as app.skein cat files/foldable-console-request.json > build/foldable-evidence/console-final-request.json 2> build/foldable-evidence/console-final-request-error.txt
adb_bounded exec-out run-as app.skein cat files/foldable-console-ack.json > build/foldable-evidence/console-final-ack.json 2> build/foldable-evidence/console-final-ack-error.txt
cat build/foldable-evidence/gradle.log
exit "$result"
