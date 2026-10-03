"""Host-only negative controls; no SDK, emulator, ADB, Gradle, or physical target is invoked."""
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import foldable_local as local
runner = local.load_module("local_fold_runner", "run-foldable-local.py")

RUN = "a" * 32


class LocalAdmissionTest(unittest.TestCase):
    def test_local_environment_cannot_impersonate_host_or_ci(self):
        for system, machine, environment in (("Linux", "arm64", {}), ("Darwin", "x86_64", {}),
                                              ("Darwin", "arm64", {"GITHUB_ACTIONS": "true"})):
            with self.subTest(system=system, machine=machine), self.assertRaises(RuntimeError):
                local.host_guard(system, machine, environment)
        local.host_guard("Darwin", "arm64", {})

    def test_run_identity_and_explicit_even_port(self):
        self.assertEqual("skein_foldable_local_" + RUN, local.avd_name(RUN))
        self.assertEqual("emulator-5580", local.serial_for_port(5580))
        for value in ("", "a" * 31, "g" * 32, "../other", "A" * 32):
            with self.assertRaises(RuntimeError):
                local.avd_name(value)
        for value in (5553, 5555, 5684, True, "5580"):
            with self.assertRaises(RuntimeError):
                local.serial_for_port(value)

    def test_actual_adapter_modules_must_belong_to_selected_repository(self):
        repository = Path(__file__).resolve().parents[2]
        self.assertEqual(4, len(local.execution_origin(repository, repository / "tools/ci/run-foldable-local.py")))
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, "do not belong"):
                local.execution_origin(Path(folder), repository / "tools/ci/run-foldable-local.py")
        with self.assertRaisesRegex(RuntimeError, "do not belong"):
            local.execution_origin(repository, Path("/unrelated/run-foldable-local.py"))

    def test_discovery_refuses_physical_offline_other_emulator_and_missing_owned_target(self):
        heading = "List of devices attached\n"
        good = "emulator-5580 device product:synthetic model:owned\n"
        self.assertEqual(0, local.exclusive_inventory(heading + good, "emulator-5580")["unexpected_count"])
        for raw in (heading, heading + good + "physical device\n", heading + good + "emulator-5554 offline\n",
                    heading + "emulator-5580 unauthorized\n", heading + "emulator-5580 offline\n",
                    heading + "emulator-5554 device\n", good):
            with self.subTest(raw=raw), self.assertRaises(RuntimeError):
                local.exclusive_inventory(raw, "emulator-5580")

    def test_owned_live_child_and_both_listener_groups_are_required(self):
        process = Mock(pid=101)
        process.poll.return_value = None
        leases = Mock()
        groups = {101: 101, 102: 101}
        ports = {5580: {101}, 5581: {102}}
        authority = local.OwnedEmulator(process, (5580, 5581), leases, ports.__getitem__, groups.__getitem__)
        authority.check()
        leases.check.assert_called_once()
        process.poll.return_value = 0
        with self.assertRaises(RuntimeError):
            authority.check()
        process.poll.return_value = None
        groups[101] = 999
        with self.assertRaises(RuntimeError):
            authority.check()
        groups[101] = 101
        groups[102] = 999
        with self.assertRaises(RuntimeError):
            authority.check()
        ports[5581] = set()
        with self.assertRaises(RuntimeError):
            authority.check()

    def test_leases_are_atomic_and_cannot_release_changed_owner(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            coord, evidence = root / "coord", root / "evidence"
            coord.mkdir(); evidence.mkdir()
            leases = local.Leases(coord, RUN, root, evidence)
            leases.acquire("build")
            with self.assertRaises(FileExistsError):
                leases.acquire("build")
            with self.assertRaises(RuntimeError):
                leases.check()
            leases.acquire("device")
            leases.check()
            path = coord / "device.lock/owner.json"
            original = path.read_text()
            changed = json.loads(original); changed["token"] = "b" * 32
            path.write_text(json.dumps(changed))
            with self.assertRaises(RuntimeError):
                leases.check()
            with self.assertRaises(RuntimeError):
                leases.release("device")
            self.assertTrue(path.exists())
            path.write_text(original)
            self.assertTrue(leases.release("device"))
            self.assertTrue(leases.release("build"))

    def test_generic_profile_requires_actual_hinge_and_exact_image(self):
        config = {"hw.device.name": "7.6in Foldable", "hw.device.manufacturer": "Generic",
                  "hw.sensor.hinge": "yes", "hw.sensor.hinge.count": "1", "image.sysdir.1": local.IMAGE + "/"}
        local.config_guard(config, "7.6in Foldable")
        for key, value in (("hw.device.name", "pixel_9_pro_fold"), ("hw.sensor.hinge", "no"),
                           ("hw.sensor.hinge.count", "0"), ("image.sysdir.1", local.IMAGE.replace("arm64-v8a", "x86_64")),
                           ("hw.device.manufacturer", "Google")):
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                local.config_guard(dict(config, **{key: value}), "7.6in Foldable")

    def test_installed_image_pin_requires_all_original_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = root / local.IMAGE
            image.mkdir(parents=True)
            rows = []
            for index in range(30):
                path = image / f"fixture-{index}"
                path.write_bytes(b"original")
                rows.append(dict(path=path.name, **local.receipt(path)))
            pin = root / "pin.json"
            local.write_json(pin, dict(passed=True, installed_metadata=dict(path=local.PACKAGE, revision=9, extension=13),
                                      files=rows, archive=dict(bytes=1778933980, sha1="16f5bceca236b2737008977c4aaf826e46a8de7d",
                                      sha256="d2e7c6076e5df54d3677605a324daf9edf813b2808c216bdb1687f6b2b6aed99")))
            self.assertEqual(30, len(local.verify_image(root, pin)))
            (image / "fixture-29").write_bytes(b"different")
            with self.assertRaisesRegex(RuntimeError, "installed image differs"):
                local.verify_image(root, pin)

    def test_existing_test_model_refuses_missing_changed_or_repointed_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            lock = root / "tools/models/test-model.lock"
            lock.parent.mkdir(parents=True)
            lock.write_text(f"sha256={local.MODEL_SHA}\nsize_bytes={local.MODEL_BYTES}\n")
            with self.assertRaises(OSError):
                local.verify_test_model(root)
            model = root / "app/src/androidTest/assets/tiny.gguf"
            model.parent.mkdir(parents=True)
            model.write_bytes(b"unqualified replacement")
            with self.assertRaisesRegex(RuntimeError, "existing test model"):
                local.verify_test_model(root)
            lock.write_text("sha256=" + "f" * 64 + f"\nsize_bytes={local.MODEL_BYTES}\n")
            with self.assertRaisesRegex(RuntimeError, "lock differs"):
                local.verify_test_model(root)


class LocalCleanupTest(unittest.TestCase):
    def subject(self):
        subject = object.__new__(runner.Runner)
        subject.stop_controller = Mock()
        subject.process = Mock(pid=123)
        subject.process.poll.return_value = 0
        subject.process.returncode = 0
        subject.state = {}
        subject.ports = (5580, 5581)
        subject.leases = Mock()
        subject.leases.release.return_value = True
        subject.gradle_unproven = False
        return subject

    def test_untracked_source_refuses_frozen_checkout(self):
        subject = self.subject()
        subject.args = Mock(expected_sha="a" * 40)
        subject.git = Mock(side_effect=["a" * 40, "?? app/src/main/kotlin/Unreviewed.kt"])
        with self.assertRaisesRegex(RuntimeError, "clean tracked checkout"):
            subject.source_check()
        self.assertEqual(("status", "--porcelain", "--untracked-files=all"), subject.git.call_args.args)

    def test_unproven_gradle_retains_build_lease_even_when_emulator_stopped(self):
        subject = self.subject()
        subject.gradle_unproven = True
        with patch.object(local, "listeners", return_value=set()):
            subject.cleanup()
        self.assertEqual(["device"], subject.state["released_matching_leases"])
        self.assertIn("retained_build_lease_reason", subject.state)
        subject.leases.release.assert_called_once_with("device")

    def test_surviving_port_owner_is_never_signalled_or_device_lease_released(self):
        subject = self.subject()
        with patch.object(local, "listeners", return_value={999}):
            subject.cleanup()
        self.assertFalse(subject.state["owned_ports_clear"])
        subject.leases.release.assert_called_once_with("build")
        subject.process.terminate.assert_not_called()
        subject.process.kill.assert_not_called()

    def test_successful_gradle_client_with_live_new_daemon_keeps_build_unproven(self):
        subject = self.subject()
        with tempfile.TemporaryDirectory() as folder:
            subject.evidence = Path(folder)
            subject.repo = Path(folder)
            subject.env = {"GRADLE_USER_HOME": str(Path(folder) / "unused-gradle-home")}
            subject.command = Mock(return_value=subprocess.CompletedProcess([], 0))
            subject.gradle_processes = Mock(side_effect=[{}, {"789": "789 exact new daemon identity"},
                                                         {"789": "789 same daemon after reparenting"}])
            with patch.object(local, "verify_test_model", return_value={}), patch.object(runner.time, "monotonic", side_effect=[0, 31]):
                with self.assertRaisesRegex(RuntimeError, "terminal state unproven"):
                    subject.gradle([":app:assembleDevDebug"], "build", 1800)
            self.assertTrue(subject.gradle_unproven)
            self.assertEqual({"789": "789 exact new daemon identity"}, subject.state["build_live_gradle_candidates"])


class LocalPreparationOrderingTest(unittest.TestCase):
    class ReachedAvdCreation(Exception):
        pass

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        coordination = root / "coordination"
        coordination.mkdir()
        self.subject = runner.Runner(SimpleNamespace(repository=root, sdk=root / "sdk", java_home=root / "java",
                                    console_port=5580, coordination=coordination, expected_sha="a" * 40,
                                    profile="pixel_fold"))
        self.subject.source_check = Mock()
        for name, value in (("host_guard", None), ("execution_origin", {}), ("verify_test_model", {}),
                            ("verify_image", []), ("receipt", dict(bytes=1, sha256="1" * 64)), ("listeners", set())):
            patcher = patch.object(local, name, return_value=value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def fake_sdk(self, argv, label, *args, **kwargs):
        if label == "create-owned-avd":
            raise self.ReachedAvdCreation()
        self.assertEqual("device-catalog", label)
        return subprocess.CompletedProcess(argv, 0, b"synthetic catalog", b"")

    def test_private_homes_exist_before_first_sdk_or_gradle_call(self):
        def catalog(argv, label, *args, **kwargs):
            for name in ("avd-home", "android-user"):
                self.assertTrue((self.subject.evidence / name).is_dir(), name + " must precede SDK invocation")
            return self.fake_sdk(argv, label, *args, **kwargs)
        def build(*args, **kwargs):
            for name in ("avd-home", "android-user"):
                self.assertTrue((self.subject.evidence / name).is_dir(), name + " must precede Gradle invocation")
        self.subject.command = Mock(side_effect=catalog)
        self.subject.gradle = Mock(side_effect=build)
        with self.assertRaises(self.ReachedAvdCreation):
            self.subject.execute()
        self.subject.gradle.assert_called_once()

    def test_gradle_created_user_home_and_debug_key_do_not_break_preparation(self):
        # Reproduce the actual SDK behavior using real temporary directories, no Gradle process.
        def build(*args, **kwargs):
            home = self.subject.evidence / "android-user"
            home.mkdir(parents=True, exist_ok=True)
            (home / "debug.keystore").write_bytes(b"synthetic fixture, not a signing key")
        self.subject.command = Mock(side_effect=self.fake_sdk)
        self.subject.gradle = Mock(side_effect=build)
        with self.assertRaises(self.ReachedAvdCreation):
            self.subject.execute()
        self.assertEqual(b"synthetic fixture, not a signing key",
                         (self.subject.evidence / "android-user/debug.keystore").read_bytes())

    def test_existing_run_and_private_home_are_never_reused_or_deleted(self):
        home = self.subject.evidence / "android-user"
        home.mkdir(parents=True)
        original = home / "debug.keystore"
        original.write_bytes(b"original preserved fixture")
        self.subject.command = Mock()
        self.subject.gradle = Mock()
        with self.assertRaises(FileExistsError):
            self.subject.execute()
        self.subject.command.assert_not_called()
        self.subject.gradle.assert_not_called()
        self.assertEqual(b"original preserved fixture", original.read_bytes())
        self.assertFalse((self.subject.args.coordination / "build.lock").exists())


class LocalProtocolTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.authority = Mock()
        self.calls = []
        self.avd = local.avd_name(RUN)
        self.qemu = "1"
        def fake(argv, **kwargs):
            self.calls.append((argv, kwargs))
            self.assertEqual(["/synthetic-sdk/platform-tools/adb", "-s", "emulator-5580"], argv[:3])
            self.assertEqual(subprocess.DEVNULL, kwargs.get("stdin", subprocess.DEVNULL))
            if argv[3:] == ["shell", "getprop", "ro.kernel.qemu"]:
                output = self.qemu + "\n"
            elif argv[3:] == ["emu", "avd", "name"]:
                output = self.avd + "\nOK\n"
            elif argv[3:] == ["emu", "fold"]:
                output = "OK\n"
            else:
                output = "synthetic diagnostic\n" if argv[3:] in [list(x) for x in local.protocol.DISPLAY_READS] else ""
            return subprocess.CompletedProcess(argv, 0, output, "")
        self.host = local.LocalBridge(self.authority, Path("/synthetic-sdk"), "emulator-5580", RUN, Path(temp.name), fake)

    def request(self, sequence, action, nonce):
        return dict(run_id=RUN, sequence=sequence, action=action, nonce=nonce * 32)

    def test_actual_authority_checked_before_every_targeted_command(self):
        self.host.guard()
        self.assertEqual(3, self.authority.check.call_count)
        self.authority.check.side_effect = RuntimeError("lease changed")
        before = len(self.calls)
        with self.assertRaises(RuntimeError):
            self.host.adb("emu", "fold")
        self.assertEqual(before, len(self.calls))

    def test_wrong_run_avd_and_non_emulator_cannot_mutate(self):
        for avd, qemu in (("skein_foldable_gate", "1"), ("skein_foldable_local_" + "b" * 32, "1"), (self.avd, "0")):
            self.avd, self.qemu = avd, qemu
            with self.assertRaises(local.protocol.ProtocolError):
                self.host.guard()
        self.assertFalse(any(argv[3:] == ["emu", "fold"] for argv, _ in self.calls))

    def test_readiness_is_required_and_replay_never_repeats_posture_mutation(self):
        with self.assertRaises(local.protocol.ProtocolError):
            self.host.handle(self.request(1, "fold", "b"))
        self.assertEqual([], self.calls)
        self.host.handle(self.request(0, "ready", "0"))
        request = self.request(1, "fold", "b")
        self.host.handle(request)
        self.host.handle(dict(request))
        self.assertEqual(1, sum(argv[3:] == ["emu", "fold"] for argv, _ in self.calls))
        with self.assertRaises(local.protocol.ProtocolError):
            self.host.handle(self.request(2, "unfold", "b"))


if __name__ == "__main__":
    unittest.main()
