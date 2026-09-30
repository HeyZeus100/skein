#!/usr/bin/env bash
# Only the coordinator dispatches this opt-in disposable-emulator lane.
set -euo pipefail
# connectedAndroidTest can enumerate multiple devices. Keep this helper on the disposable CI host,
# where the workflow creates the sole target; local execution must not discover the held Fold.
test "${GITHUB_ACTIONS:-}" = true || { echo 'This helper requires the disposable CI emulator host' >&2; exit 2; }
mkdir -p build/foldable-evidence
# The display belongs to this workflow only. Linux process-start time rejects
# stale ownership and pidfd signals cannot target a reused PID. No global pkill/X reset.
display_owner=build/foldable-evidence/xvfb-owner.json
stop_display() {
  python3 - "$display_owner" <<'PY'
import json, os, pathlib, signal, sys, time
path = pathlib.Path(sys.argv[1])
if not path.exists():
    raise SystemExit(0)
owner = json.loads(path.read_text())
pid = owner["pid"]
def matches():
    try:
        fields = pathlib.Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()
        return fields[19] == owner["start_ticks"] and fields[0] != "Z"
    except FileNotFoundError:
        return False
signals = []
if matches():
    try:
        descriptor = os.pidfd_open(pid)
    except ProcessLookupError:
        descriptor = None
    if descriptor is not None:
        try:
            if matches():
                signal.pidfd_send_signal(descriptor, signal.SIGTERM)
                signals.append("TERM")
            deadline = time.monotonic() + 5
            while matches() and time.monotonic() < deadline:
                time.sleep(0.1)
            if matches():
                signal.pidfd_send_signal(descriptor, signal.SIGKILL)
                signals.append("KILL")
                deadline = time.monotonic() + 2
                while matches() and time.monotonic() < deadline:
                    time.sleep(0.1)
        except ProcessLookupError:
            pass  # The owned process exited between observation and pidfd signal.
        finally:
            os.close(descriptor)
result = dict(owner, cleanup_signals=signals, owned_process_still_live=matches())
path.with_name("xvfb-cleanup.json").write_text(json.dumps(result, sort_keys=True) + "\n")
raise SystemExit(1 if result["owned_process_still_live"] else 0)
PY
}
if test "${1:-}" = stop-display; then stop_display; exit; fi
if test "${1:-}" = start-display; then
  test ! -e "$display_owner"
  : "${GITHUB_ENV:?}"
  # -displayfd lets Xvfb choose an unused display; no collision with an existing server.
  xvfb_pid="$(python3 - "$display_owner" <<'PY'
import json, pathlib, subprocess, sys, uuid
owner = pathlib.Path(sys.argv[1])
with owner.with_name("xvfb-display.txt").open("wb") as display, owner.with_name("xvfb.log").open("wb") as log:
    process = subprocess.Popen(
        ["Xvfb", "-displayfd", str(display.fileno()), "-screen", "0", "2560x2560x24", "-nolisten", "tcp"],
        stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, pass_fds=(display.fileno(),))
    try:
        fields = pathlib.Path(f"/proc/{process.pid}/stat").read_text().rsplit(") ", 1)[1].split()
        if fields[0] == "Z":
            raise RuntimeError("owned Xvfb exited before ownership publication")
        owner.write_text(json.dumps(dict(
            pid=process.pid, start_ticks=fields[19], owner_token=uuid.uuid4().hex,
            scope="workflow-owned Xvfb, no emulator geometry override"), sort_keys=True) + "\n")
        print(process.pid)
    except BaseException:
        # Until publication succeeds, Popen retains the unreaped child identity.
        # A metadata error must not leave an unrecorded display running.
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)
        raise
PY
  )"
  trap 'stop_display' EXIT
  ready=false
  for _ in $(seq 1 50); do
    kill -0 "$xvfb_pid" 2>/dev/null || break
    number="$(cat build/foldable-evidence/xvfb-display.txt)"
    if [[ "$number" =~ ^[0-9]+$ ]] && timeout --kill-after=1s 1s xdpyinfo -display ":$number" \
      >build/foldable-evidence/xvfb-display-info.txt 2>build/foldable-evidence/xvfb-display-error.txt; then
      ready=true
      break
    fi
    sleep 0.1
  done
  test "$ready" = true
  printf 'DISPLAY=:%s\n' "$number" >> "$GITHUB_ENV"
  printf '%s\n' "${SKEIN_FOLD_EMULATOR_OPTIONS:?}" > build/foldable-evidence/emulator-options.txt
  trap - EXIT
  exit
fi
test "$#" = 0
# Refuse accidental headless fallback before the first device command.
[[ "${DISPLAY:-}" =~ ^:[0-9]+$ ]]
test "$(cat build/foldable-evidence/xvfb-display.txt)" = "${DISPLAY#:}"
[[ " ${SKEIN_FOLD_EMULATOR_OPTIONS:-} " != *" -no-window "* ]]
python3 - "$display_owner" <<'PY'
import json, pathlib, sys
owner = json.loads(pathlib.Path(sys.argv[1]).read_text())
fields = pathlib.Path(f"/proc/{owner['pid']}/stat").read_text().rsplit(") ", 1)[1].split()
assert fields[19] == owner["start_ticks"] and fields[0] != "Z", "owned Xvfb is not live"
PY
timeout --kill-after=1s 2s xdpyinfo -display "$DISPLAY" >/dev/null
# Keep the actual generated hardware identity/posture configuration, including on test failure.
# ANDROID_AVD_HOME is exported by the pinned emulator action; never substitute a device ID.
test "${SKEIN_FOLD_AVD_NAME:-}" = skein_foldable_gate
cp -f "${ANDROID_AVD_HOME:?}/$SKEIN_FOLD_AVD_NAME.avd/config.ini" build/foldable-evidence/avd-config.ini
# Host SDK attribution for this run. Earlier failed evidence did not retain these
# image-package bytes; a version string alone cannot prove identical runtime bits.
python3 - <<'PY'
import hashlib, json, os, pathlib
sdk = pathlib.Path(os.environ["ANDROID_HOME"]).resolve(strict=True)
config_path = pathlib.Path("build/foldable-evidence/avd-config.ini")
def properties(text):
    result = {}
    for line in text.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = (part.strip() for part in line.split("=", 1))
            if key in result:
                raise ValueError(f"duplicate SDK/config key: {key}")
            result[key] = value
    return result
config = properties(config_path.read_text())
image_sysdir = config["image.sysdir.1"]
image_relative = pathlib.PurePosixPath(image_sysdir)
# The action creates this exact API/tag/ABI. Never substitute another installed package.
if image_relative != pathlib.PurePosixPath("system-images/android-35/google_apis/x86_64"):
    raise ValueError("unexpected configured system-image package")
if any(key.startswith("image.sysdir.") and key != "image.sysdir.1" for key in config):
    raise ValueError("additional system-image search path is unsupported")
overrides = ("kernel.path", "disk.ramdisk.path", "disk.systemPartition.initPath",
             "disk.vendorPartition.initPath", "disk.systemPartition.path", "disk.vendorPartition.path")
if any(config.get(key) for key in overrides):
    raise ValueError("explicit boot-payload overrides need separate reviewed attribution")
package = sdk / image_relative
if package.resolve(strict=True) != package:
    raise ValueError("system-image package must be the recorded SDK directory")
image_properties = properties((package / "source.properties").read_text())
for key, expected in {"AndroidVersion.ApiLevel": "35", "SystemImage.Abi": "x86_64",
                      "SystemImage.TagId": "google_apis"}.items():
    if image_properties.get(key) != expected:
        raise ValueError(f"system-image metadata mismatch: {key}")
payloads = sorted(set(package.glob("*.img")) | set(package.glob("kernel-ranchu*")))
names = {path.name for path in payloads}
if not {"system.img", "vendor.img", "ramdisk.img"} <= names:
    raise ValueError("required API35 system/vendor/ramdisk payload is missing")
if not any(name.startswith("kernel-ranchu") for name in names):
    raise ValueError("configured package has no ranchu kernel payload")
paths = ["emulator/source.properties", "emulator/emulator",
         "emulator/qemu/linux-x86_64/qemu-system-x86_64",
         str(image_relative / "source.properties")]
paths += [str(path.relative_to(sdk)) for path in payloads]
receipts = []
for relative in paths:
    path = sdk / relative
    if not path.is_file() or path.resolve(strict=True) != path or path.stat().st_size == 0:
        raise ValueError(f"SDK receipt requires a nonempty regular file: {relative}")
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    row = dict(path=relative, bytes=path.stat().st_size, sha256=digest)
    if path.name == "source.properties":
        row["contents"] = path.read_text()
    receipts.append(row)
pathlib.Path("build/foldable-evidence/sdk-runtime-receipts.json").write_text(json.dumps(dict(
    schema_version=1,
    attribution="host SDK files, not emulator process attestation or original failed image equality",
    image_sysdir=image_sysdir, avd_config_sha256=hashlib.sha256(config_path.read_bytes()).hexdigest(),
    system_image_payloads=[str(path.relative_to(sdk)) for path in payloads],
    files=receipts), indent=2) + "\n")
PY
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
  --evidence build/foldable-evidence --stop-file "$stop_file" --display-diagnostics \
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
adb_bounded exec-out run-as app.skein cat files/foldable-console-ready.json > build/foldable-evidence/console-ready.json 2> build/foldable-evidence/console-ready-error.txt
adb_bounded exec-out run-as app.skein cat files/foldable-console-request.json > build/foldable-evidence/console-final-request.json 2> build/foldable-evidence/console-final-request-error.txt
adb_bounded exec-out run-as app.skein cat files/foldable-console-ack.json > build/foldable-evidence/console-final-ack.json 2> build/foldable-evidence/console-final-ack-error.txt
cat build/foldable-evidence/gradle.log
exit "$result"
