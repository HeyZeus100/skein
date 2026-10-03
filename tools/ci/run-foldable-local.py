#!/usr/bin/env python3
"""Explicit macOS ARM disposable fold runtime. Requires the coordinator's serialized queue grant."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import threading
import time
import uuid

import foldable_local as local


class Runner:
    def __init__(self, args):
        self.args = args
        self.repo, self.sdk = args.repository.resolve(), args.sdk.resolve()
        self.run_id = uuid.uuid4().hex
        self.name, self.serial = local.avd_name(self.run_id), local.serial_for_port(args.console_port)
        self.ports = (args.console_port, args.console_port + 1)
        self.evidence = self.repo / "build/agent-logs" / ("fold-local-" + self.run_id)
        self.leases = local.Leases(args.coordination, self.run_id, self.repo, self.evidence)
        self.process = self.authority = self.thread = None
        self.gradle_unproven = False
        self.sequence = 0
        self.state = dict(schema_version=1, lane=local.LANE, run_id=self.run_id, avd=self.name,
                          serial=self.serial, console_port=args.console_port, expected_sha=args.expected_sha,
                          host=dict(system=platform.system(), machine=platform.machine()),
                          physical_device_actions=0, error=None, controller_error=None, cleanup_error=None)
        self.env = dict(os.environ, JAVA_HOME=str(args.java_home), ANDROID_HOME=str(self.sdk),
                        ANDROID_SDK_ROOT=str(self.sdk), ANDROID_SERIAL=self.serial,
                        ANDROID_AVD_HOME=str(self.evidence / "avd-home"),
                        ANDROID_USER_HOME=str(self.evidence / "android-user"))
        self.env["PATH"] = f"{args.java_home}/bin:{self.sdk}/platform-tools:{self.sdk}/emulator:" + self.env["PATH"]

    def save(self):
        local.write_json(self.evidence / "local-execution.json", self.state)

    def command(self, argv, label, timeout=30, check=True, payload=None, owned_process=False):
        self.sequence += 1
        stem = self.evidence / "commands" / f"{self.sequence:04d}-{label}"
        record = dict(argv=[str(value) for value in argv], timeout_seconds=timeout,
                      started_monotonic_ns=time.monotonic_ns(), stdin_mode="payload" if payload else "devnull")
        try:
            options = dict(input=payload) if payload is not None else dict(stdin=subprocess.DEVNULL)
            with stem.with_suffix(".stdout").open("wb") as out, stem.with_suffix(".stderr").open("wb") as err:
                if owned_process:
                    process = subprocess.Popen(record["argv"], cwd=self.repo, env=self.env, stdout=out, stderr=err,
                                               stdin=subprocess.DEVNULL, start_new_session=True)
                    record["retained_child"] = dict(pid=process.pid, pgid=os.getpgid(process.pid), cwd=str(self.repo))
                    local.write_json(stem.with_suffix(".json"), record)
                    try:
                        code = process.wait(timeout=timeout)
                    except subprocess.TimeoutExpired:
                        # Only stop/reap the retained client. The build lease remains held for any daemon.
                        process.kill()
                        process.wait(timeout=10)
                        record["retained_child"]["exit_after_timeout"] = process.returncode
                        raise
                    record["retained_child"]["terminal_exit"] = code
                    result = subprocess.CompletedProcess(record["argv"], code)
                else:
                    result = subprocess.run(record["argv"], cwd=self.repo, env=self.env, stdout=out, stderr=err,
                                            timeout=timeout, **options)
            record["exit"] = result.returncode
            result.stdout = stem.with_suffix(".stdout").read_bytes()
            result.stderr = stem.with_suffix(".stderr").read_bytes()
            if check and result.returncode:
                raise RuntimeError(f"{label} exited {result.returncode}; see retained output")
            return result
        except subprocess.TimeoutExpired:
            record["timed_out"] = True
            raise
        finally:
            record["finished_monotonic_ns"] = time.monotonic_ns()
            record["outputs"] = {suffix: local.receipt(stem.with_suffix(suffix)) for suffix in (".stdout", ".stderr")}
            local.write_json(stem.with_suffix(".json"), record)

    def git(self, *args):
        return self.command(["git", *args], "source").stdout.decode().strip()

    def source_check(self, final=False):
        sha = self.git("rev-parse", "HEAD")
        status = self.git("status", "--porcelain", "--untracked-files=all")
        self.state["final_sha" if final else "initial_sha"] = sha
        self.state["final_tracked_status" if final else "initial_tracked_status"] = status
        if sha != self.args.expected_sha or status:
            raise RuntimeError("source SHA/clean tracked checkout differs from coordinator freeze")

    def adb(self, args, check=True):
        self.authority.check()
        return self.command([self.sdk / "platform-tools/adb", "-s", self.serial, *args],
                            "owned-emulator-adb", timeout=10, check=check)

    def identity(self):
        qemu = self.adb(["shell", "getprop", "ro.kernel.qemu"]).stdout.decode().strip()
        name = self.adb(["emu", "avd", "name"]).stdout.decode().replace("\r", "").splitlines()
        sdk = self.adb(["shell", "getprop", "ro.build.version.sdk"]).stdout.decode().strip()
        abi = self.adb(["shell", "getprop", "ro.product.cpu.abi"]).stdout.decode().strip()
        if (qemu, name, sdk, abi) != ("1", [self.name, "OK"], "35", "arm64-v8a"):
            raise RuntimeError("owned emulator AVD/API/ABI identity mismatch")
        self.state["emulator_identity"] = dict(qemu=qemu, avd=self.name, api=sdk, abi=abi)

    def mutate(self, args, check=True):
        self.identity()
        return self.adb(args, check=check)

    def gradle(self, tasks, label, timeout, properties=()):
        self.state[label + "_test_model"] = local.verify_test_model(self.repo)
        before = self.gradle_processes(label + "-before")
        self.gradle_unproven = True
        try:
            result = self.command(["./gradlew", "--offline", "--no-daemon", "--max-workers=2", *tasks,
                                   "--stacktrace", "-Pskein.foldableTests=true", "-x", ":app:fetchTestModel", *properties],
                                  label, timeout=timeout, check=False, owned_process=True)
        finally:
            after = self.gradle_processes(label + "-after")
            candidates = sorted(set(after) - set(before))
            self.state[label + "_new_gradle_processes_after"] = {pid: after[pid] for pid in candidates}
            # These are candidates, not permission to signal a daemon; retain logs for coordinator review.
            gradle_home = Path(self.env.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
            for pid in candidates:
                for log in sorted((gradle_home / "daemon").glob(f"*/daemon-{pid}.out.log")):
                    target = self.evidence / (label + "-" + log.parent.name + "-" + log.name)
                    shutil.copyfile(log, target)
        self.state["build_exit" if label == "build" else "gradle_exit"] = result.returncode
        if result.returncode:
            raise RuntimeError(label + " failed; build lease retained until daemon termination is established")
        # A successful client is insufficient when a newly observed daemon still exists.
        # Conservatively retain while its PID remains a Gradle process; reparenting changes
        # PPID/PGID without proving termination. Never signal any of these candidates.
        deadline = time.monotonic() + 30
        while candidates:
            now = self.gradle_processes(label + "-terminal-check")
            candidates = [pid for pid in candidates if pid in now]
            if not candidates:
                break
            if time.monotonic() >= deadline:
                self.state[label + "_live_gradle_candidates"] = {pid: after[pid] for pid in candidates}
                raise RuntimeError("new Gradle process terminal state unproven; build lease retained")
            time.sleep(1)
        self.state[label + "_new_gradle_processes_terminated"] = True
        self.gradle_unproven = False

    def gradle_processes(self, label):
        result = self.command(["/bin/ps", "-axo", "pid=,ppid=,pgid=,lstart=,command="], label, timeout=10)
        rows = {}
        for line in result.stdout.decode().splitlines():
            if "GradleDaemon" in line or "GradleWrapperMain" in line or "org.gradle.launcher" in line:
                rows[line.split()[0]] = line.strip()
        local.write_json(self.evidence / (label + "-gradle-processes.json"), rows)
        return rows

    def inventory(self):
        self.authority.check()
        # Sole unscoped ADB command: read-only server inventory. All device commands use -s.
        result = self.command([self.sdk / "platform-tools/adb", "devices", "-l"], "private-inventory", timeout=10)
        self.state["exclusive_inventory"] = local.exclusive_inventory(result.stdout.decode(), self.serial)

    def execute(self):
        local.host_guard()
        self.state["executed_adapter_sources"] = local.execution_origin(self.repo, __file__)
        self.evidence.mkdir(parents=True, exist_ok=False)
        (self.evidence / "commands").mkdir()
        # SDK tools and Gradle may create files here (including the debug keystore).
        # Own both homes first, within the fresh run; never reuse another run's home.
        for name in ("avd-home", "android-user"):
            (self.evidence / name).mkdir()
        self.save()
        self.leases.acquire("build")
        self.source_check()
        self.state["initial_test_model"] = local.verify_test_model(self.repo)
        self.state["source_files"] = {path: local.receipt(self.repo / path) for path in local.SOURCE_FILES}
        image_files = local.verify_image(self.sdk, self.repo / local.PIN)
        self.state["image_pin_verified"] = True
        self.state["image_pin"] = local.receipt(self.repo / local.PIN)
        for port in self.ports:
            if local.listeners(port):
                raise RuntimeError("requested emulator port is already occupied")
        self.command([self.sdk / "cmdline-tools/latest/bin/avdmanager", "list", "device", "--compact"], "device-catalog", 60)
        self.gradle([":app:assembleDevDebug", ":app:assembleDevDebugAndroidTest"], "build", 1800)
        avd = self.evidence / "avd-home" / (self.name + ".avd")
        self.command([self.sdk / "cmdline-tools/latest/bin/avdmanager", "create", "avd", "--name", self.name,
                      "--package", local.PACKAGE, "--device", self.args.profile, "--path", avd],
                     "create-owned-avd", 120, payload=b"no\n")
        shutil.copyfile(avd / "config.ini", self.evidence / "avd-config.ini")
        config = {key.strip(): value.strip() for key, value in
                  (line.split("=", 1) for line in (avd / "config.ini").read_text().splitlines() if "=" in line)}
        local.config_guard(config, self.args.profile)
        sdk_files = image_files + [dict(path=path, **local.receipt(self.sdk / path)) for path in (
            "emulator/source.properties", "emulator/emulator", local.QEMU, "platform-tools/adb")]
        local.write_json(self.evidence / "sdk-runtime-receipts.json", dict(
            schema_version=1, attribution="host SDK files, not emulator process attestation or original failed image equality",
            avd_config_sha256=local.digest(self.evidence / "avd-config.ini"), image_sysdir=config["image.sysdir.1"],
            files=sdk_files, system_image_payloads=[row["path"] for row in image_files
                if row["path"].count("/") == local.IMAGE.count("/") + 1 and
                (row["path"].endswith(".img") or row["path"].rsplit("/", 1)[1].startswith("kernel-ranchu"))]))
        self.leases.acquire("device")
        for port in self.ports:
            if local.listeners(port):
                raise RuntimeError("requested port became occupied before launch")
        argv = [str(self.sdk / "emulator/emulator"), "-avd", self.name, "-ports", f"{self.ports[0]},{self.ports[1]}",
                "-memory", "3072", "-cores", "2", "-gpu", "swiftshader", "-noaudio", "-no-boot-anim",
                "-no-snapshot-load", "-no-snapshot-save"]  # Native headful GUI; never -no-window/Xvfb.
        with (self.evidence / "emulator.log").open("wb") as output:
            self.process = subprocess.Popen(argv, cwd=self.repo, env=self.env, stdin=subprocess.DEVNULL,
                                            stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        self.authority = local.OwnedEmulator(self.process, self.ports, self.leases)
        self.state["emulator_process"] = dict(pid=self.process.pid, argv=argv, launch_monotonic_ns=time.monotonic_ns())
        self.save()
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            self.authority.check(require_ports=False)
            if all(local.listeners(port) for port in self.ports):
                try:
                    self.identity()
                    if self.adb(["shell", "getprop", "sys.boot_completed"]).stdout.strip() == b"1":
                        break
                except (RuntimeError, subprocess.TimeoutExpired) as error:
                    self.state["last_boot_observation"] = str(error)
            time.sleep(5)
        else:
            raise RuntimeError("bounded 300s boot deadline unmet")
        self.inventory()
        for args in (["input", "keyevent", "KEYCODE_WAKEUP"], ["wm", "dismiss-keyguard"],
                     ["svc", "power", "stayon", "true"],
                     ["settings", "put", "system", "screen_off_timeout", "2147483647"]):
            self.mutate(["shell", *args], check=args != ["wm", "dismiss-keyguard"])
        # Fold tests exercise the setup gate, so they deliberately do not create a vault or a PIN.
        for suffix in ("outputs/androidTest-results/connected", "reports/androidTests/connected"):
            source = self.repo / "app/build" / suffix
            if source.exists():
                destination = self.evidence / "preexisting-output/app/build" / suffix
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(source), str(destination))
        (self.evidence / "console-run-id.txt").write_text(self.run_id + "\n")
        bridge = local.LocalBridge(self.authority, self.sdk, self.serial, self.run_id, self.evidence)
        def serve():
            try:
                bridge.serve(self.evidence / "controller.stop")
            except BaseException as error:
                self.state["controller_error"] = repr(error)
                bridge.event("fatal", reason=str(error))
        self.thread = threading.Thread(target=serve, name="owned-fold-bridge", daemon=True)
        self.thread.start()
        self.source_check()
        self.identity()
        self.inventory()
        self.state["instrumentation_started"] = True
        self.save()
        try:
            self.gradle([":app:connectedDevDebugAndroidTest"], "instrumentation", 2700, (
                "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
                "-Pandroid.testInstrumentationRunnerArguments.skein.foldable.localMacArm=true",
                "-Pandroid.testInstrumentationRunnerArguments.skein.foldable.runId=" + self.run_id,
                "-Pandroid.testInstrumentationRunnerArguments.class=app.skein.foldable.MainActivityFoldableGateTest"))
        finally:
            try:
                self.stop_controller()
            finally:
                self.collect()

    def stop_controller(self):
        if self.thread:
            (self.evidence / "controller.stop").touch()
            self.thread.join(timeout=60)
            if self.thread.is_alive():
                raise RuntimeError("controller did not stop within 60s; device lease retained")

    def collect(self):
        # Copy the fresh host outputs before any optional device read can fail.
        for suffix in ("outputs/androidTest-results/connected", "reports/androidTests/connected",
                       "outputs/apk/dev/debug", "outputs/apk/androidTest/dev/debug"):
            source = self.repo / "app/build" / suffix
            if source.exists():
                shutil.copytree(source, self.evidence / "snapshot/app/build" / suffix)
        errors = []
        for source, target in (("files/foldable-gate-metrics.jsonl", "window-geometry.jsonl"),
                               ("files/foldable-console-ready.json", "console-ready.json"),
                               (local.protocol.REQUEST, "final-request.json"), (local.protocol.ACK, "final-ack.json")):
            try:
                result = self.adb(["exec-out", "run-as", "app.skein", "cat", source], check=False)
                (self.evidence / target).write_bytes(result.stdout)
                if result.returncode:
                    errors.append(f"{source}: exit {result.returncode}")
            except (RuntimeError, subprocess.TimeoutExpired, OSError) as error:
                errors.append(f"{source}: {error}")
        try:
            self.adb(["logcat", "-d"], check=False)
        except (RuntimeError, subprocess.TimeoutExpired, OSError) as error:
            errors.append("logcat: " + str(error))
        self.source_check(final=True)
        self.state["final_test_model"] = local.verify_test_model(self.repo)
        if errors:
            raise RuntimeError("evidence collection incomplete: " + repr(errors))

    def cleanup(self):
        self.stop_controller()
        if self.process:
            if self.process.poll() is None:
                self.process.terminate()  # Only the retained child; never a PID lookup or global kill.
                try:
                    self.process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=10)
            self.state["emulator_exit"] = self.process.returncode
        self.state["owned_emulator_stopped"] = self.process is None or self.process.poll() is not None
        self.state["remaining_port_listeners"] = {str(port): sorted(local.listeners(port)) for port in self.ports}
        self.state["owned_ports_clear"] = not any(self.state["remaining_port_listeners"].values())
        self.state["released_matching_leases"] = []
        if self.state["owned_emulator_stopped"] and self.state["owned_ports_clear"]:
            if self.leases.release("device"):
                self.state["released_matching_leases"].append("device")
        if not self.gradle_unproven:
            if self.leases.release("build"):
                self.state["released_matching_leases"].append("build")
        else:
            self.state["retained_build_lease_reason"] = "Gradle terminal success unproven; coordinator must establish owned daemon termination"

    def run(self):
        try:
            self.execute()
        except BaseException as error:
            self.state["error"] = repr(error)
        finally:
            if self.evidence.exists():
                try:
                    self.cleanup()
                except BaseException as error:
                    self.state["cleanup_error"] = repr(error)
                self.save()
        result = local.review_local(self.repo, self.args.expected_sha, self.args.profile, self.evidence)
        if self.evidence.exists():
            local.write_json(self.evidence / "review.json", result)
        print(json.dumps(dict(evidence=str(self.evidence), passed=result["passed"], errors=result["errors"]), indent=2))
        return 0 if result["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--coordination", type=Path, required=True)
    parser.add_argument("--console-port", type=int, default=5580)
    parser.add_argument("--profile", choices=tuple(local.reviewer.PROFILES), required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--execute-after-coordinator-grant", required=True)
    args = parser.parse_args()
    if args.execute_after_coordinator_grant != args.expected_sha or not __import__("re").fullmatch("[0-9a-f]{40}", args.expected_sha):
        parser.error("explicit full source SHA and matching coordinator grant required")
    return Runner(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
