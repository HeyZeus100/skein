import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from run_android_smoke import Emulator


class EmulatorSmokeSafetyTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
