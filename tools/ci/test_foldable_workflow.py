"""Guard the opt-in lane's dispatch, platform and dependency verification contract."""
from pathlib import Path
import hashlib
import json
import os
import shlex
import subprocess
import sys
import tempfile
import tomllib
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import yaml

ROOT = Path(__file__).resolve().parents[2]


class FoldableWorkflowContractTest(unittest.TestCase):
    def test_linux_emulator_library_is_installed_before_the_version_probe(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/foldable.yml").read_text())
        steps = workflow["jobs"]["foldable"]["steps"]
        names = [step.get("name") for step in steps]
        install = names.index("Install Linux emulator runtime library")
        self.assertLess(install, names.index("SDK tools and exact fold profile"))
        commands = [shlex.split(line) for line in steps[install]["run"].splitlines() if line.strip()]
        self.assertEqual(["sudo", "apt-get", "update"], commands[0])
        self.assertEqual(["sudo", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install",
                          "--yes", "--no-install-recommends", "libpulse0", "xvfb", "x11-utils",
                          "libxkbcommon-x11-0", "libxcb-cursor0"], commands[1])

    def test_exact_and_compatibility_profiles_are_explicit_and_labelled(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/foldable.yml").read_text())
        # PyYAML's YAML 1.1 resolver treats the GitHub `on` key as a boolean.
        triggers = workflow.get("on", workflow.get(True))
        self.assertEqual({"workflow_dispatch"}, set(triggers))
        profile = triggers["workflow_dispatch"]["inputs"]["profile"]
        self.assertEqual("pixel_9_pro_fold", profile["default"])
        self.assertEqual(["pixel_9_pro_fold", "pixel_fold", "7.6in Foldable"], profile["options"])
        job = workflow["jobs"]["foldable"]
        self.assertIn("inputs.profile", job["name"])
        steps = job["steps"]
        emulator = next(step for step in steps if "android-emulator-runner@" in step.get("uses", ""))
        self.assertEqual("${{ inputs.profile }}", emulator["with"]["profile"])
        self.assertEqual("${{ env.SKEIN_FOLD_AVD_NAME }}", emulator["with"]["avd-name"])
        helper = (ROOT / "tools/ci/run-foldable-emulator.sh").read_text()
        helper_tokens = shlex.split(helper.replace("\\\n", ""), comments=True)
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.skein.foldable.ci=true", helper_tokens)
        self.assertIn("-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true", helper_tokens)
        self.assertEqual("${{ env.SKEIN_FOLD_EMULATOR_OPTIONS }}", emulator["with"]["emulator-options"])
        options = shlex.split(job["env"]["SKEIN_FOLD_EMULATOR_OPTIONS"])
        self.assertNotIn("-grpc", options)
        self.assertNotIn("-no-window", options)
        self.assertEqual(["-gpu", "swiftshader_indirect", "-noaudio", "-no-boot-anim", "-camera-back", "none"], options)
        self.assertEqual("skein_foldable_gate", job["env"]["SKEIN_FOLD_AVD_NAME"])
        self.assertIn("compatibility", job["name"])
        sdk = next(step["run"] for step in steps if step.get("name") == "SDK tools and exact fold profile")
        tokens = shlex.split(sdk)
        self.assertIn("platforms;android-37.0", tokens)
        self.assertNotIn("platforms;android-37", tokens)
        self.assertIn('tool_bin="$ANDROID_HOME/cmdline-tools/latest/bin"', sdk)
        self.assertNotIn("find ", sdk)
        for step in steps:
            if "./gradlew" in step.get("run", ""):
                self.assertIn("--max-workers=2", step["run"])
        upload = next(step for step in steps if "upload-artifact@" in step.get("uses", ""))
        self.assertEqual("always()", upload["if"])
        self.assertIn("inputs.profile", upload["with"]["name"])

    def test_scoped_xvfb_is_started_before_emulator_and_always_cleaned(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/foldable.yml").read_text())
        steps = workflow["jobs"]["foldable"]["steps"]
        names = [step.get("name") for step in steps]
        self.assertLess(names.index("Start owned virtual display for Qt emulator"),
                        names.index("Run production gate on foldable emulator"))
        cleanup = steps[names.index("Stop owned virtual display")]
        self.assertEqual("always()", cleanup["if"])
        self.assertEqual("bash tools/ci/run-foldable-emulator.sh stop-display", cleanup["run"])
        helper = (ROOT / "tools/ci/run-foldable-emulator.sh").read_text()
        self.assertIn('["Xvfb", "-displayfd", str(display.fileno())', helper)
        self.assertIn('"-nolisten", "tcp"', helper)
        self.assertIn("--display-diagnostics", helper)
        self.assertNotIn("pkill", "\n".join(line for line in helper.splitlines() if not line.startswith("#")))
        rejected = subprocess.run(["bash", "tools/ci/run-foldable-emulator.sh", "start-display"], cwd=ROOT,
                                  env={**os.environ, "GITHUB_ACTIONS": "false"}, capture_output=True)
        self.assertEqual(2, rejected.returncode)

    def test_failed_display_owner_publication_terminates_only_the_owned_child(self):
        source = (ROOT / "tools/ci/run-foldable-emulator.sh").read_text()
        body = source.split('xvfb_pid="$(python3', 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        for timeout in (False, True):
            with self.subTest(timeout=timeout):
                class FakeFile:
                    def __enter__(self):
                        return self

                    def __exit__(self, *args):
                        pass

                    def fileno(self):
                        return 7

                class FakePath:
                    def __init__(self, path):
                        self.path = str(path)

                    def with_name(self, name):
                        return self

                    def open(self, mode):
                        return FakeFile()

                    def read_text(self):
                        self_case.assertEqual("/proc/12345/stat", self.path)
                        return "12345 (Xvfb) " + " ".join(["S"] + ["0"] * 18 + ["555"])

                    def write_text(self, value):
                        raise OSError("synthetic owner publication failure")

                self_case = self
                with patch("pathlib.Path", FakePath), patch("subprocess.Popen") as launch, \
                        patch.object(sys, "argv", ["start", "owner.json"]):
                    child = launch.return_value
                    child.pid = 12345
                    child.wait.side_effect = [subprocess.TimeoutExpired("Xvfb", 5), 0] if timeout else [0]
                    with self.assertRaisesRegex(OSError, "synthetic owner publication failure"):
                        exec(compile(body, "display-start", "exec"), {})
                    self.assertEqual(["Xvfb", "-displayfd", "7", "-screen", "0", "2560x2560x24", "-nolisten", "tcp"],
                                     launch.call_args.args[0])
                    self.assertEqual((7,), launch.call_args.kwargs["pass_fds"])
                    child.terminate.assert_called_once_with()
                    self.assertEqual(1 if timeout else 0, child.kill.call_count)
                    self.assertEqual([5, 2] if timeout else [5], [call.kwargs["timeout"] for call in child.wait.call_args_list])

    def test_owned_display_cleanup_never_signals_a_reused_pid_and_bounds_escalation(self):
        # Execute the actual cleanup body with fake /proc and kill; no display/device is started.
        source = (ROOT / "tools/ci/run-foldable-emulator.sh").read_text()
        body = source.split("stop_display() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        for mode, expected in (("stale", []), ("normal", ["TERM"]),
                               ("stalled", ["TERM", "KILL"]), ("reused_after_term", ["TERM"])):
            with self.subTest(mode=mode):
                state = dict(live=True, ticks="other" if mode == "stale" else "555", written=None)
                owner = dict(pid=12345, start_ticks="555", owner_token="fixture-only")
                class FakePath:
                    def __init__(self, path):
                        self.path = str(path)

                    def exists(self):
                        return True

                    def read_text(self):
                        if self.path == "owner.json":
                            return json.dumps(owner)
                        self_case.assertEqual("/proc/12345/stat", self.path)
                        if not state["live"]:
                            raise FileNotFoundError(self.path)
                        return "12345 (Xvfb) " + " ".join(["S"] + ["0"] * 18 + [state["ticks"]])

                    def with_name(self, name):
                        self_case.assertEqual("xvfb-cleanup.json", name)
                        return self

                    def write_text(self, value):
                        state["written"] = json.loads(value)

                self_case = self
                signals = []
                def kill(descriptor, signal):
                    self.assertEqual(999, descriptor)
                    signals.append(signal.name.removeprefix("SIG"))
                    if mode == "reused_after_term":
                        state["ticks"] = "new-process"
                    elif mode == "normal" or signal.name == "SIGKILL":
                        state["live"] = False
                with patch("pathlib.Path", FakePath), patch("os.pidfd_open", return_value=999, create=True), \
                        patch("signal.pidfd_send_signal", kill, create=True), patch("os.close"), \
                        patch("time.monotonic", side_effect=iter(range(100))), patch("time.sleep"), \
                        patch.object(sys, "argv", ["cleanup", "owner.json"]):
                    with self.assertRaises(SystemExit) as result:
                        exec(compile(body, "display-cleanup", "exec"), {})
                self.assertEqual(0, result.exception.code)
                self.assertEqual(expected, signals)
                self.assertFalse(state["written"]["owned_process_still_live"])

    def test_sdk_receipts_hash_actual_image_and_kernel_bytes_and_refuse_missing_payloads(self):
        source = (ROOT / "tools/ci/run-foldable-emulator.sh").read_text()
        body = source.split("# Host SDK attribution", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        scratch = ROOT / "build/agent-logs"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory)
            sdk = root / "sdk"
            package = sdk / "system-images/android-35/google_apis/x86_64"
            package.mkdir(parents=True)
            evidence = root / "build/foldable-evidence"
            evidence.mkdir(parents=True)
            config = evidence / "avd-config.ini"
            config.write_text("image.sysdir.1=system-images/android-35/google_apis/x86_64/\n")
            image_properties = "AndroidVersion.ApiLevel=35\nSystemImage.Abi=x86_64\nSystemImage.TagId=google_apis\n"
            (package / "source.properties").write_text(image_properties)
            for name in ("emulator/source.properties", "emulator/emulator",
                         "emulator/qemu/linux-x86_64/qemu-system-x86_64"):
                path = sdk / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"synthetic emulator bytes")
            payloads = ("system.img", "vendor.img", "ramdisk.img", "userdata.img", "encryptionkey.img",
                        "kernel-ranchu", "kernel-ranchu-64")
            for name in payloads:
                (package / name).write_bytes(("synthetic " + name).encode())

            def run():
                return subprocess.run([sys.executable, "-c", body], cwd=root,
                                      env={**os.environ, "ANDROID_HOME": str(sdk)}, capture_output=True, text=True)

            success = run()
            self.assertEqual(0, success.returncode, success.stderr)
            receipt = json.loads((evidence / "sdk-runtime-receipts.json").read_text())
            self.assertEqual({str((package / name).relative_to(sdk)) for name in payloads}, set(receipt["system_image_payloads"]))
            self.assertEqual(hashlib.sha256(config.read_bytes()).hexdigest(), receipt["avd_config_sha256"])
            for row in receipt["files"]:
                actual = (sdk / row["path"]).read_bytes()
                self.assertEqual(len(actual), row["bytes"])
                self.assertEqual(hashlib.sha256(actual).hexdigest(), row["sha256"])
            (package / "system.img").write_bytes(b"different synthetic system bytes")
            self.assertEqual(0, run().returncode)
            changed = json.loads((evidence / "sdk-runtime-receipts.json").read_text())
            self.assertNotEqual(receipt["files"], changed["files"])
            for missing in ("system.img", "vendor.img", "ramdisk.img", "kernel-ranchu*"):
                with self.subTest(missing=missing):
                    paths = sorted(package.glob(missing))
                    saved = {path: path.read_bytes() for path in paths}
                    for path in paths:
                        path.unlink()
                    self.assertNotEqual(0, run().returncode)
                    for path, data in saved.items():
                        path.write_bytes(data)
            for suffix in ("image.sysdir.2=another-package\n", "kernel.path=/unrecorded/kernel\n"):
                config.write_text("image.sysdir.1=system-images/android-35/google_apis/x86_64/\n" + suffix)
                self.assertNotEqual(0, run().returncode)
            config.write_text("image.sysdir.1=system-images/android-35/google_atd/arm64-v8a/\n")
            self.assertNotEqual(0, run().returncode)

    def test_spaced_catalog_id_is_exact_and_missing_profile_never_falls_back(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/foldable.yml").read_text())
        sdk = next(step["run"] for step in workflow["jobs"]["foldable"]["steps"]
                   if step.get("name") == "SDK tools and exact fold profile")
        # Execute only the real allowlist/catalog shell lines against a host fixture: no SDK/device calls.
        script = "set -euo pipefail\n" + "\n".join(line for line in sdk.splitlines()
                                                   if line.startswith(("case ", "grep ")))
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "build/foldable-evidence/device-profiles.txt"
            catalog.parent.mkdir(parents=True)
            catalog.write_text('id: 62 or "7.6in Foldable"\n    OEM : Generic\n')
            for profile, expected in (("7.6in Foldable", 0), ("62", 2),
                                      ("7.6in.Foldable", 2), ("pixel_9_pro_fold", 1)):
                with self.subTest(profile=profile):
                    result = subprocess.run(["bash", "-c", script], cwd=directory,
                                            env={**os.environ, "SKEIN_FOLD_PROFILE": profile}, capture_output=True)
                    self.assertEqual(expected, result.returncode, result.stderr)

    def test_espresso_pin_and_test_only_network_manifest_are_verified(self):
        catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
        version = catalog["versions"]["androidx-test-espresso-device"]
        self.assertEqual("1.1.0", version)
        ns = {"m": "https://schema.gradle.org/dependency-verification"}
        metadata = ET.parse(ROOT / "gradle/verification-metadata.xml")
        component = metadata.find(
            f"m:components/m:component[@group='androidx.test.espresso'][@name='espresso-device'][@version='{version}']", ns)
        artifacts = {node.get("name"): node.find("m:sha256", ns).get("value")
                     for node in component.findall("m:artifact", ns)}
        self.assertEqual("be57100db268c03247f365a31209f9c2b83b7f3b3ea9f7f2334c40ecb835c010",
                         artifacts["espresso-device-1.1.0.aar"])
        self.assertEqual("673a610fed1dd0aaf66e9e8d3eb11a4b60ca5c933585d7b842e498e9bfd6309e",
                         artifacts["espresso-device-1.1.0.pom"])
        manifest = ET.parse(ROOT / "app/src/foldableTest/AndroidManifest.xml")
        permissions = {node.get("{http://schemas.android.com/apk/res/android}name")
                       for node in manifest.findall("uses-permission")}
        self.assertEqual({"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                          "android.permission.ACCESS_LOCAL_NETWORK"}, permissions)


if __name__ == "__main__":
    unittest.main()
