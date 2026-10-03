"""macOS ARM authority for a newly owned emulator; no environment-only admission."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import time

IMAGE = "system-images/android-35/google_apis/arm64-v8a"
PACKAGE = IMAGE.replace("/", ";")
QEMU = "emulator/qemu/darwin-aarch64/qemu-system-aarch64"
PIN = "docs/ux/runs/2026-09-30-context-recovery-transport/local-sdk-google-apis-arm64/installation-result.json"
OWNER = "/root/fold_transport"
LANE = "local-macos-arm64-foldable"
MODEL_SHA = "741ad12b64088fedc17c33aacb22e48be1972ef36a39f03666dd68bd15614fb9"
MODEL_BYTES = 88202080
SOURCE_FILES = (
    "tools/ci/foldable_local.py", "tools/ci/run-foldable-local.py", "tools/ci/foldable-console-bridge.py",
    "tools/ci/verify-foldable.py", "app/src/foldableTest/kotlin/app/skein/foldable/FoldableDeviceControl.kt",
    "app/src/foldableTest/kotlin/app/skein/foldable/MainActivityFoldableGateTest.kt")


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


protocol = load_module("foldable_protocol", "foldable-console-bridge.py")
reviewer = load_module("foldable_reviewer", "verify-foldable.py")


def execution_origin(repository, runner_file):
    files = {"tools/ci/run-foldable-local.py": runner_file, "tools/ci/foldable_local.py": __file__,
             "tools/ci/foldable-console-bridge.py": protocol.__file__, "tools/ci/verify-foldable.py": reviewer.__file__}
    if any(Path(actual).resolve() != (repository / relative).resolve() for relative, actual in files.items()):
        raise RuntimeError("executed adapter modules do not belong to the frozen repository")
    return {relative: receipt(Path(actual)) for relative, actual in files.items()}


def digest(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def receipt(path):
    return dict(bytes=path.stat().st_size, sha256=digest(path))


def verify_test_model(repository, module="app"):
    lock = repository / "tools/models/test-model.lock"
    values = dict(line.split("=", 1) for line in lock.read_text().splitlines() if line and not line.startswith("#"))
    if values.get("sha256") != MODEL_SHA or values.get("size_bytes") != str(MODEL_BYTES):
        raise RuntimeError("test model lock differs from the authorized artifact")
    model = repository / module / "src/androidTest/assets/tiny.gguf"
    actual = receipt(model)
    if actual != dict(bytes=MODEL_BYTES, sha256=MODEL_SHA):
        raise RuntimeError("exact existing test model required; model acquisition is not authorized")
    return dict(path=str(model.relative_to(repository)), **actual, lock_sha256=digest(lock))


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def host_guard(system=None, machine=None, environment=None):
    system = platform.system() if system is None else system
    machine = platform.machine() if machine is None else machine
    environment = os.environ if environment is None else environment
    if system != "Darwin" or machine != "arm64" or environment.get("GITHUB_ACTIONS") == "true":
        raise RuntimeError("local lane requires actual macOS ARM; CI admission is separate")


def avd_name(run_id):
    if not re.fullmatch("[0-9a-f]{32}", run_id):
        raise RuntimeError("invalid local run ID")
    return "skein_foldable_local_" + run_id


def serial_for_port(port):
    if type(port) is not int or not 5554 <= port <= 5682 or port % 2:
        raise RuntimeError("explicit even emulator console port required")
    return f"emulator-{port}"


def exclusive_inventory(raw, serial):
    """The one server-level read refuses every other attached target, including offline ones."""
    lines = [line.strip() for line in raw.splitlines() if line.strip()]
    if not lines or lines[0] != "List of devices attached":
        raise RuntimeError("unrecognized adb inventory")
    rows = [line.split() for line in lines[1:]]
    if len(rows) != 1 or rows[0][:2] != [serial, "device"]:
        raise RuntimeError("Gradle discovery refused: sole owned emulator is not the only online target")
    return dict(target_count=1, owned_online_count=1, unexpected_count=0)


def listeners(port):
    result = subprocess.run(["/usr/sbin/lsof", "-t", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN"],
                            stdin=subprocess.DEVNULL, capture_output=True, timeout=5)
    if result.returncode not in (0, 1) or result.stderr:
        raise RuntimeError("cannot establish port ownership")
    return {int(value) for value in result.stdout.split()}


class Leases:
    def __init__(self, coordination, token, repository, evidence):
        self.coordination, self.token = coordination, token
        self.repository, self.evidence, self.paths = repository, evidence, []

    def acquire(self, kind):
        path = self.coordination / (kind + ".lock")
        path.mkdir()  # Atomic refusal; never removes another owner's lease.
        record = dict(owner=OWNER, token=self.token, pid=os.getpid(), worktree=str(self.repository),
                      purpose=LANE, created_unix=time.time())
        write_json(path / "owner.json", record)
        self.paths.append(path)
        write_json(self.evidence / (kind + "-lease.json"), record)

    def check(self):
        if {path.name for path in self.paths} != {"build.lock", "device.lock"}:
            raise RuntimeError("both local runtime leases required")
        for path in self.paths:
            record = json.loads((path / "owner.json").read_text())
            if record.get("owner") != OWNER or record.get("token") != self.token or record.get("pid") != os.getpid():
                raise RuntimeError("local lease ownership changed")

    def release(self, kind):
        path = self.coordination / (kind + ".lock")
        if path not in self.paths:
            return False
        record = json.loads((path / "owner.json").read_text())
        if record.get("owner") != OWNER or record.get("token") != self.token or record.get("pid") != os.getpid():
            raise RuntimeError("refusing to release another lease")
        (path / "owner.json").unlink()
        path.rmdir()
        self.paths.remove(path)
        return True


class OwnedEmulator:
    def __init__(self, process, ports, leases, port_reader=listeners, getpgid=os.getpgid):
        self.process, self.ports, self.leases = process, ports, leases
        self.port_reader, self.getpgid = port_reader, getpgid

    def check(self, require_ports=True):
        self.leases.check()
        if self.process.poll() is not None or self.getpgid(self.process.pid) != self.process.pid:
            raise RuntimeError("retained emulator child is no longer owned/live")
        if require_ports:
            for port in self.ports:
                pids = self.port_reader(port)
                if not pids or any(self.getpgid(pid) != self.process.pid for pid in pids):
                    raise RuntimeError("emulator listener absent or owned by another process group")


def verify_image(sdk, pin):
    published = json.loads(pin.read_text())
    if published.get("passed") is not True or published.get("installed_metadata") != {
            "path": PACKAGE, "revision": 9, "extension": 13}:
        raise RuntimeError("installed image qualification pin differs")
    if published.get("archive") != {"bytes": 1778933980, "sha1": "16f5bceca236b2737008977c4aaf826e46a8de7d",
                                    "sha256": "d2e7c6076e5df54d3677605a324daf9edf813b2808c216bdb1687f6b2b6aed99"}:
        raise RuntimeError("official image archive identity differs")
    rows = published["files"]
    if len(rows) != 30 or len({row["path"] for row in rows}) != 30:
        raise RuntimeError("incomplete installed image pin")
    observed = []
    for row in rows:
        relative = Path(row["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise RuntimeError("invalid pinned image path")
        actual = dict(path=row["path"], **receipt(sdk / IMAGE / relative))
        if actual != row:
            raise RuntimeError("installed image differs: " + row["path"])
        observed.append(dict(actual, path=IMAGE + "/" + row["path"]))
    return observed


def config_guard(config, profile):
    if profile not in reviewer.PROFILES or config.get("hw.device.name") != profile:
        raise RuntimeError("actual AVD profile differs")
    if config.get("hw.sensor.hinge") != "yes" or int(config.get("hw.sensor.hinge.count", "0")) < 1:
        raise RuntimeError("selected catalog profile has no hinge")
    if config.get("image.sysdir.1", "").rstrip("/") != IMAGE:
        raise RuntimeError("actual AVD image differs")
    if profile == "7.6in Foldable" and config.get("hw.device.manufacturer") != "Generic":
        raise RuntimeError("generic profile manufacturer differs")


class LocalBridge(protocol.ProtocolBridge):
    def __init__(self, authority, sdk, serial, run_id, evidence, run=subprocess.run):
        self.authority, self.sdk, self.local_run = authority, sdk, run
        super().__init__(serial, run_id, evidence, run=self.run_owned, display_diagnostics=True,
                         avd_name=avd_name(run_id))

    def run_owned(self, argv, **kwargs):
        self.authority.check()
        if argv[:3] != ["adb", "-s", self.serial]:
            raise protocol.ProtocolError("local bridge target changed")
        return self.local_run([str(self.sdk / "platform-tools/adb"), *argv[1:]], **kwargs)

    def guard(self):
        self.authority.check()
        if self.checked("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise protocol.ProtocolError("local target is not an emulator")
        if self.checked("emu", "avd", "name").replace("\r", "").splitlines() != [self.avd_name, "OK"]:
            raise protocol.ProtocolError("local target is not the newly owned AVD")


def review_local(repository, expected_sha, profile, evidence):
    try:
        execution = json.loads((evidence / "local-execution.json").read_text())
        run_id = (evidence / "console-run-id.txt").read_text().strip()
        result = reviewer.review_runtime(repository, expected_sha, profile, evidence, avd_name(run_id),
                                         IMAGE, QEMU, LANE, evidence / "snapshot")
        required = {"host": {"system": "Darwin", "machine": "arm64"}, "expected_sha": expected_sha,
                    "final_sha": expected_sha, "initial_tracked_status": "", "final_tracked_status": "",
                    "image_pin_verified": True, "gradle_exit": 0, "build_exit": 0,
                    "owned_emulator_stopped": True, "owned_ports_clear": True,
                    "controller_error": None, "cleanup_error": None, "error": None,
                    "physical_device_actions": 0, "avd": avd_name(run_id), "run_id": run_id,
                    "exclusive_inventory": {"target_count": 1, "owned_online_count": 1, "unexpected_count": 0}}
        required.update(build_new_gradle_processes_terminated=True, instrumentation_new_gradle_processes_terminated=True)
        for key, expected in required.items():
            if key not in execution or execution[key] != expected:
                result["errors"].append("local execution admission/cleanup missing or mismatched: " + key)
        if execution.get("released_matching_leases") != ["device", "build"]:
            result["errors"].append("matching runtime leases were not released")
        if execution.get("serial") != serial_for_port(execution.get("console_port")):
            result["errors"].append("local explicit serial/port mismatch")
        events = [json.loads(line) for line in (evidence / "console-events.jsonl").read_text().splitlines()]
        if not events or events[0].get("serial") != execution.get("serial"):
            result["errors"].append("local execution and transport serial differ")
        pin = repository / PIN
        published = json.loads(pin.read_text())
        sdk = json.loads((evidence / "sdk-runtime-receipts.json").read_text())
        by_path = {row["path"]: row for row in sdk["files"]}
        if execution.get("image_pin") != receipt(pin) or len(published["files"]) != 30:
            result["errors"].append("local exact image authority differs")
        for row in published["files"]:
            expected = dict(row, path=IMAGE + "/" + row["path"])
            if by_path.get(expected["path"]) != expected:
                result["errors"].append("local image payload differs from published pin: " + row["path"])
        for path, original in execution.get("source_files", {}).items():
            if receipt(repository / path) != original:
                result["errors"].append("local source file changed: " + path)
        if set(execution.get("source_files", {})) != set(SOURCE_FILES):
            result["errors"].append("local source file receipt inventory differs")
        if execution.get("executed_adapter_sources") != {path: execution["source_files"][path] for path in SOURCE_FILES[:4]}:
            result["errors"].append("executed adapter origin/source receipts differ")
        model = dict(path="app/src/androidTest/assets/tiny.gguf", bytes=MODEL_BYTES, sha256=MODEL_SHA,
                     lock_sha256=digest(repository / "tools/models/test-model.lock"))
        if any(execution.get(key) != model for key in ("initial_test_model", "build_test_model",
                                                     "instrumentation_test_model", "final_test_model")):
            result["errors"].append("local existing test model identity missing/changed")
        for kind in ("build", "device"):
            lease = json.loads((evidence / (kind + "-lease.json")).read_text())
            if (lease.get("owner") != OWNER or lease.get("token") != run_id or
                    lease.get("worktree") != str(repository) or type(lease.get("pid")) is not int or lease["pid"] <= 0):
                result["errors"].append("local " + kind + " lease identity mismatch")
        process = execution.get("emulator_process", {})
        argv = process.get("argv", [])
        if (type(process.get("pid")) is not int or process["pid"] <= 0 or "-no-window" in argv or
                "-avd" not in argv or argv[argv.index("-avd") + 1] != avd_name(run_id) or
                "-ports" not in argv or argv[argv.index("-ports") + 1] != f"{execution['console_port']},{execution['console_port'] + 1}"):
            result["errors"].append("local retained headful process identity missing/mismatched")
        gradle = []
        for command_path in sorted((evidence / "commands").glob("*.json")):
            command = json.loads(command_path.read_text())
            for suffix, original in command["outputs"].items():
                if suffix not in (".stdout", ".stderr") or receipt(command_path.with_suffix(suffix)) != original:
                    result["errors"].append("local command output changed: " + command_path.name)
            command_argv = command["argv"]
            if command_argv and command_argv[0] == "./gradlew":
                gradle.append(command)
        if (len(gradle) != 2 or any(command.get("exit") != 0 or command.get("timed_out") or
                not {"--offline", "--no-daemon", "--max-workers=2", "-Pskein.foldableTests=true", "-x", ":app:fetchTestModel"} <= set(command["argv"])
                for command in gradle)):
            result["errors"].append("two successful bounded offline local Gradle commands required")
        elif ("-Pandroid.testInstrumentationRunnerArguments.skein.foldable.localMacArm=true" not in gradle[1]["argv"] or
              "-Pandroid.testInstrumentationRunnerArguments.skein.foldable.runId=" + run_id not in gradle[1]["argv"] or
              any("skein.foldable.ci=" in value for command in gradle for value in command["argv"])):
            result["errors"].append("local instrumentation opt-in/run binding differs")
        for command, timeout in zip(gradle, (1800, 2700)):
            child = command.get("retained_child", {})
            if (command.get("timeout_seconds") != timeout or type(child.get("pid")) is not int or child["pid"] <= 0 or
                    child.get("pgid") != child["pid"] or child.get("terminal_exit") != 0 or child.get("cwd") != str(repository)):
                result["errors"].append("local Gradle child identity/terminal receipt differs")
        result["artifacts"][str((evidence / "local-execution.json").relative_to(repository))] = digest(evidence / "local-execution.json")
        result["platform_scope"] = "macOS ARM/API35 google_apis arm64 revision9 extension13; distinct from Linux x86_64 CI"
        result["physical_acceptance"] = "UNRUN"
        result["passed"] = not result["errors"]
        return result
    except (OSError, ValueError, KeyError, TypeError, IndexError, RuntimeError) as error:
        return dict(lane=LANE, passed=False, errors=["local evidence unavailable: " + str(error)])
