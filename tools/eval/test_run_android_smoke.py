import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from run_android_smoke import Emulator


class EmulatorSmokeSafetyTest(unittest.TestCase):
    def test_rejects_physical_serial_without_any_adb_command(self):
        with patch("run_android_smoke.command") as command:
            with self.assertRaises(ValueError):
                Emulator("52241FDKD000LV")
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
