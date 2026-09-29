import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import run_android_smoke
from run_android_smoke import Emulator


class EmulatorSmokeSafetyTest(unittest.TestCase):
    def test_optional_platform_lookup_failure_does_not_hide_missing_required_tools(self):
        workflow = Path(__file__).resolve().parents[2] / ".github/workflows/synthetic-smoke.yml"
        block = workflow.read_text().split("      - name: Configure SDK and KVM\n        run: |\n", 1)[1]
        lines = []
        for line in block.splitlines():
            if not line.startswith("          "):
                break
            lines.append(line[10:])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / "sdk"
            manager = sdk / "cmdline-tools/1/bin/sdkmanager"
            manager.parent.mkdir(parents=True)
            manager.write_text('#!/bin/sh\ncase "$*" in *android-37*) exit 1;; esac\nexit 0\n')
            manager.chmod(0o700)
            for path in (sdk / "platform-tools/adb", sdk / "build-tools/36.0.0/aapt2", root / "sudo"):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("#!/bin/sh\nexit 0\n")
                path.chmod(0o700)
            environment = {"PATH": f"{root}:/usr/bin:/bin", "ANDROID_HOME": str(sdk),
                           "GITHUB_PATH": str(root / "path")}
            result = subprocess.run(["/bin/bash", "-e", "-c", "\n".join(lines)], cwd=root,
                                    env=environment, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            (sdk / "platform-tools/adb").unlink()
            missing = subprocess.run(["/bin/bash", "-e", "-c", "\n".join(lines)], cwd=root,
                                     env=environment, capture_output=True, timeout=10)
            self.assertNotEqual(missing.returncode, 0)

    def test_workflow_passes_serial_when_each_script_line_has_a_fresh_shell(self):
        workflow = Path(__file__).resolve().parents[2] / ".github/workflows/synthetic-smoke.yml"
        block = workflow.read_text().split("          script: |\n", 1)[1]
        lines = []
        for line in block.splitlines():
            if not line.startswith("            "):
                break
            lines.append(line.strip().replace("${{ github.sha }}", "a" * 40))
        self.assertTrue(lines)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text("#!/bin/sh\nprintf 'List of devices attached\\nemulator-5554\\tdevice\\n'\n")
            adb.chmod(0o700)
            python = root / "python3"
            python.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$SMOKE_TEST_ARGS"\n')
            python.chmod(0o700)
            arguments = root / "arguments"
            for line in lines:
                subprocess.run(["/bin/sh", "-c", line], check=True, cwd=root, timeout=10,
                               env={"PATH": f"{root}:/usr/bin:/bin", "SMOKE_TEST_ARGS": str(arguments)})
            argv = arguments.read_text().splitlines()
            self.assertEqual(argv[argv.index("--serial") + 1], "emulator-5554")
            self.assertEqual(argv[argv.index("--expected-head") + 1], "a" * 40)

    def test_rejects_physical_serial_without_any_adb_command(self):
        with patch("run_android_smoke.command") as command:
            with self.assertRaises(ValueError):
                Emulator("52241FDKD000LV")
            command.assert_not_called()

    def test_ambiguous_emulator_selection_is_rejected_before_adb(self):
        with patch("run_android_smoke.command") as command:
            with self.assertRaises(ValueError):
                Emulator("emulator-5554\nemulator-5556")
            command.assert_not_called()

    def test_emulator_serial_still_requires_actual_emulator_property(self):
        with patch("run_android_smoke.command", return_value=Mock(stdout=b"0\n")) as command:
            with self.assertRaises(ValueError):
                Emulator("emulator-5554")
            self.assertEqual(command.call_args.args[0],
                             ["adb", "-s", "emulator-5554", "shell", "-T", "getprop", "ro.kernel.qemu"])

    def test_existing_staged_artifact_is_never_overwritten(self):
        with patch("run_android_smoke.command", return_value=Mock(stdout=b"1\n")):
            emulator = Emulator("emulator-5554")
        with patch.object(emulator, "shell", side_effect=["", subprocess.CalledProcessError(1, "test")]), \
                patch("run_android_smoke.command") as command:
            with self.assertRaises(subprocess.CalledProcessError):
                emulator.stage("app.skein", "/data/user/0/app.skein/files/synthetic-benchmark", "input/test", "absent")
            command.assert_not_called()

    def test_host_timeout_retains_partial_instrumentation_output(self):
        with patch("run_android_smoke.command", return_value=Mock(stdout=b"1\n")):
            emulator = Emulator("emulator-5554")
        component = "app.skein.test/androidx.test.runner.AndroidJUnitRunner"
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(emulator, "shell", return_value=f"instrumentation:{component} (target=app.skein)\n"), \
                patch("run_android_smoke.subprocess.run", side_effect=subprocess.TimeoutExpired("am", 1200, output=b"partial\n")):
            log = Path(directory) / "run.log"
            self.assertFalse(emulator.instrument(component, "app.skein", "Synthetic", {}, log))
            self.assertTrue(emulator.host_timed_out)
            self.assertEqual(log.read_bytes(), b"partial\n\nHOST_INSTRUMENTATION_TIMEOUT\n")

    def test_installed_apk_digest_hashes_binary_bytes_from_the_exact_package_path(self):
        installed_path = "/data/app/~~a+b==/app.skein.inference.service.test-abc_123==/base.apk"
        payload = b"PK\x00\xff\r\ninstalled APK bytes"
        with patch("run_android_smoke.command", return_value=Mock(stdout=b"1\n")):
            emulator = Emulator("emulator-5554")

        def read_apk(argv, **kwargs):
            self.assertEqual(argv, ["adb", "-s", "emulator-5554", "exec-out", "cat", installed_path])
            self.assertTrue(kwargs["check"])
            self.assertEqual(kwargs["timeout"], 180)
            kwargs["stdout"].write(payload)
            return Mock(returncode=0)

        with patch.object(emulator, "shell", return_value="package:" + installed_path + "\n") as shell, \
                patch("run_android_smoke.subprocess.run", side_effect=read_apk):
            self.assertEqual(emulator.installed_apk_sha256("app.skein.inference.service.test"),
                             hashlib.sha256(payload).hexdigest())
            shell.assert_called_once_with("pm", "path", "app.skein.inference.service.test")

    def test_installed_apk_digest_rejects_split_missing_and_unsafe_paths_before_reading(self):
        base = "/data/app/~~abc==/app.skein.inference.service.test-123==/base.apk"
        invalid = ["", "package:" + base + "\npackage:" + base.replace("base.apk", "split_x86_64.apk") + "\n",
                   "package:/data/app/../private/base.apk\n", "package:/data/user/0/app.skein/base.apk\n",
                   "package:/data/app/has space/base.apk\n", "package:/data/app/$(id)/base.apk\n",
                   "package:/data/app/anything/base.apk;id\n"]
        with patch("run_android_smoke.command", return_value=Mock(stdout=b"1\n")):
            emulator = Emulator("emulator-5554")
        for paths in invalid:
            with self.subTest(paths=paths), patch.object(emulator, "shell", return_value=paths), \
                    patch("run_android_smoke.subprocess.run") as read:
                with self.assertRaises(ValueError):
                    emulator.installed_apk_sha256("app.skein.inference.service.test")
                read.assert_not_called()

    def test_installed_apk_read_failure_does_not_hash_a_partial_download(self):
        with patch("run_android_smoke.command", return_value=Mock(stdout=b"1\n")):
            emulator = Emulator("emulator-5554")
        failures = (subprocess.CalledProcessError(1, "cat"), subprocess.TimeoutExpired("cat", 180))
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), \
                    patch.object(emulator, "shell", return_value="package:/data/app/example/base.apk\n"), \
                    patch("run_android_smoke.subprocess.run", side_effect=failure), \
                    patch("run_android_smoke.file_sha256") as digest:
                with self.assertRaises(type(failure)):
                    emulator.installed_apk_sha256("app.skein.inference.service.test")
                digest.assert_not_called()


class SmokeProvenanceTest(unittest.TestCase):
    def run_smoke(self, root, reported_test_hash=None, installed_native_hash=None):
        head = "a" * 40
        output = root / "output"
        files = {
            "tools/models/test-model.lock": "sha256=" + hashlib.sha256(b"model").hexdigest() +
                                            "\nsize_bytes=5\nlicense=Apache-2.0\n",
            "native/llama/tokenizer-patches/PINS.txt": "overlay pin",
            "inference-service/src/androidTest/assets/tiny.gguf": "model",
            "app/build/outputs/apk/dev/debug/app-dev-debug.apk": "app",
            "app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk": "app test",
            "inference-service/build/outputs/apk/androidTest/dev/debug/inference-service-dev-debug-androidTest.apk": "native test",
        }
        for path, content in files.items():
            destination = root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content)
        digest = lambda value: hashlib.sha256(value.encode()).hexdigest()
        device = Mock(host_timed_out=False)
        device.private_root.side_effect = lambda package: f"/data/user/0/{package}/files/synthetic-benchmark"
        device.stage.side_effect = lambda package, path, relative, source: path + "/" + relative
        device.installed_apk_sha256.return_value = installed_native_hash or digest("native test")
        device.instrument.return_value = True

        def collect(package, path, run_id, filenames, destination):
            if package == "app.skein":
                manifest = dict(build_sha=head, model_sha256=digest("model"), llama_sha="b" * 40,
                                tokenizer_overlay_sha256=digest("overlay pin"), apk_sha256=digest("app"),
                                test_apk_sha256=reported_test_hash or digest("app test"),
                                fixture_sha256=run_android_smoke.file_sha256(output / "development-input.jsonl"),
                                template_sha256="c" * 64, context=dict(allocated=1024))
                (destination / "run_manifest.json").write_text(json.dumps(manifest))
                (destination / "answers.jsonl").write_text(json.dumps(
                    dict(case_id="synthetic", seed=17, status="ok", generation_skipped=False,
                         generation=dict(isolated_count_consistency=True))) + "\n")
            else:
                (destination / "native-parity.json").write_text(json.dumps(
                    dict(model_sha256=digest("model"), template_sha256="c" * 64,
                         cases=[dict(status="ok") for _ in range(5)])))

        device.collect.side_effect = collect

        def command(argv, **kwargs):
            if argv == ["git", "rev-parse", "HEAD"]:
                return Mock(stdout=head.encode())
            if argv == ["git", "rev-parse", "HEAD:third_party/llama.cpp"]:
                return Mock(stdout=b"b" * 40)
            return Mock(stdout=b"")

        with patch.object(run_android_smoke, "__file__", str(root / "tools/eval/run_android_smoke.py")), \
                patch.object(Path, "cwd", return_value=root), \
                patch("sys.argv", ["smoke", "--serial", "emulator-5554", "--expected-head", head,
                                   "--output", str(output)]), \
                patch("run_android_smoke.Emulator", return_value=device), \
                patch("run_android_smoke.command", side_effect=command), \
                patch("run_android_smoke.fixture", return_value=([dict(id="synthetic")], "d" * 64)), \
                patch("run_android_smoke.export_case", side_effect=lambda case: case):
            error = None
            try:
                run_android_smoke.main()
            except ValueError as failure:
                error = failure
        return output, device, error

    def test_matching_app_test_and_installed_native_test_digests_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            output, device, error = self.run_smoke(Path(directory).resolve())
            self.assertIsNone(error)
            summary = json.loads((output / "smoke_summary.json").read_text())
            self.assertTrue(summary["complete"])
            self.assertEqual(summary["installed_apk_sha256"]["native_test"], summary["apk_sha256"]["native_test"])
            device.installed_apk_sha256.assert_called_once_with("app.skein.inference.service.test")
            self.assertEqual(device.instrument.call_count, 2)

    def test_wrong_returned_app_test_digest_fails_and_preserves_both_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            output, device, error = self.run_smoke(Path(directory).resolve(), reported_test_hash="0" * 64)
            self.assertRegex(str(error), "manifest provenance mismatch")
            summary = json.loads((output / "smoke_summary.json").read_text())
            self.assertFalse(summary["complete"])
            self.assertEqual(summary["error_code"], "ValueError")
            self.assertTrue((output / "isolated-apk/answers.jsonl").is_file())
            self.assertTrue((output / "native-parity/native-parity.json").is_file())

    def test_wrong_installed_native_digest_fails_before_parity_and_preserves_answer_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            output, device, error = self.run_smoke(Path(directory).resolve(), installed_native_hash="0" * 64)
            self.assertRegex(str(error), "installed native test APK provenance mismatch")
            summary = json.loads((output / "smoke_summary.json").read_text())
            self.assertFalse(summary["complete"])
            self.assertEqual(summary["installed_apk_sha256"]["native_test"], "0" * 64)
            self.assertTrue((output / "isolated-apk/answers.jsonl").is_file())
            self.assertTrue((output / "isolated-apk/run_manifest.json").is_file())
            self.assertEqual(device.instrument.call_count, 1)
            self.assertFalse((output / "native-parity").exists())


if __name__ == "__main__":
    unittest.main()
