"""Synthetic local evidence negatives; the original CI fixture remains unchanged."""
import copy
import json
from pathlib import Path
import shutil
import unittest

import foldable_local as local
import test_verify_foldable as ci_fixture


class LocalEvidenceTest(unittest.TestCase):
    def setUp(self):
        fixture = ci_fixture.FoldableEvidenceReviewTest("test_counts_actual_cases_and_hashes_source_bound_artifacts")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.write_cases(fixture.cases)
        self.fixture = fixture
        self.repo, self.sha = fixture.repo, fixture.sha
        self.evidence = self.repo / "build/foldable-evidence"
        self.run_id = "a" * 32
        self.avd = local.avd_name(self.run_id)
        fixture.events[0]["avd"] = self.avd
        fixture.events_path.write_text("\n".join(json.dumps(row) for row in fixture.events))
        shutil.copytree(self.repo / "app", self.evidence / "snapshot/app")
        fixture.config.write_text(fixture.config.read_text().replace("x86_64", "arm64-v8a"))
        pin = self.repo / local.PIN
        pin.parent.mkdir(parents=True)
        real_repo = Path(__file__).resolve().parents[2]
        shutil.copyfile(real_repo / local.PIN, pin)
        published = json.loads(pin.read_text())
        sdk = json.loads(fixture.sdk_receipts.read_text())
        sdk["image_sysdir"] = local.IMAGE + "/"
        sdk["avd_config_sha256"] = local.digest(fixture.config)
        sdk["system_image_payloads"] = [path.replace("x86_64", "arm64-v8a") for path in sdk["system_image_payloads"]]
        sdk["files"] = [dict(row, path=local.IMAGE + "/" + row["path"]) for row in published["files"]] + [
            dict(path=path, bytes=1, sha256="1" * 64) for path in ("emulator/source.properties", "emulator/emulator", local.QEMU)]
        local.write_json(fixture.sdk_receipts, sdk)
        for path in (*local.SOURCE_FILES, "tools/models/test-model.lock"):
            destination = self.repo / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(real_repo / path, destination)
        model = dict(path="app/src/androidTest/assets/tiny.gguf", bytes=local.MODEL_BYTES, sha256=local.MODEL_SHA,
                     lock_sha256=local.digest(self.repo / "tools/models/test-model.lock"))
        self.execution = dict(
            host=dict(system="Darwin", machine="arm64"), expected_sha=self.sha, final_sha=self.sha,
            initial_tracked_status="", final_tracked_status="", image_pin_verified=True, gradle_exit=0, build_exit=0,
            owned_emulator_stopped=True, owned_ports_clear=True, controller_error=None, cleanup_error=None, error=None,
            physical_device_actions=0, avd=self.avd, run_id=self.run_id, serial="emulator-5554", console_port=5554,
            exclusive_inventory=dict(target_count=1, owned_online_count=1, unexpected_count=0),
            released_matching_leases=["device", "build"], image_pin=local.receipt(pin),
            source_files={path: local.receipt(self.repo / path) for path in local.SOURCE_FILES},
            executed_adapter_sources={path: local.receipt(self.repo / path) for path in local.SOURCE_FILES[:4]},
            initial_test_model=model, final_test_model=model, build_test_model=model, instrumentation_test_model=model,
            build_new_gradle_processes_terminated=True, instrumentation_new_gradle_processes_terminated=True,
            emulator_process=dict(pid=123, argv=["synthetic-emulator", "-avd", self.avd, "-ports", "5554,5555"]))
        for kind in ("build", "device"):
            local.write_json(self.evidence / (kind + "-lease.json"), dict(owner=local.OWNER, token=self.run_id,
                             worktree=str(self.repo), pid=321))
        (self.evidence / "commands").mkdir()
        for number, task in enumerate((":app:assembleDevDebug", ":app:connectedDevDebugAndroidTest")):
            path = self.evidence / "commands" / f"{number}.json"
            for suffix in (".stdout", ".stderr"):
                path.with_suffix(suffix).write_bytes(b"synthetic command output")
            argv = ["./gradlew", "--offline", "--no-daemon", "--max-workers=2", "-Pskein.foldableTests=true", "-x", ":app:fetchTestModel", task]
            if number:
                argv += ["-Pandroid.testInstrumentationRunnerArguments.skein.foldable.localMacArm=true",
                         "-Pandroid.testInstrumentationRunnerArguments.skein.foldable.runId=" + self.run_id]
            local.write_json(path, dict(argv=argv, exit=0, timeout_seconds=1800 if number == 0 else 2700,
                             retained_child=dict(pid=444, pgid=444, terminal_exit=0, cwd=str(self.repo)),
                             outputs={suffix: local.receipt(path.with_suffix(suffix))
                             for suffix in (".stdout", ".stderr")}))
        self.save()

    def save(self):
        local.write_json(self.evidence / "local-execution.json", self.execution)

    def review(self):
        return local.review_local(self.repo, self.sha, "pixel_9_pro_fold", self.evidence)

    def test_valid_synthetic_local_evidence_uses_same_cases_and_thresholds(self):
        result = self.review()
        self.assertTrue(result["passed"], result["errors"])
        self.assertEqual(2, len(result["cases"]))
        self.assertEqual(6, len(result["console_requests"]))
        self.assertEqual(5, len(result["geometry"]))
        self.assertEqual("UNRUN", result["physical_acceptance"])
        self.assertEqual(local.LANE, result["lane"])

    def test_wrong_avd_cannot_borrow_ci_or_another_local_run(self):
        for wrong in ("skein_foldable_gate", "skein_foldable_local_" + "b" * 32):
            self.fixture.events[0]["avd"] = wrong
            self.fixture.events_path.write_text("\n".join(json.dumps(row) for row in self.fixture.events))
            self.assertFalse(self.review()["passed"])

    def test_readiness_alone_never_satisfies_posture_or_geometry(self):
        self.fixture.events_path.write_text("\n".join(json.dumps(row) for row in
                                            [*self.fixture.events[:3], self.fixture.events[-1]]))
        result = self.review()
        self.assertFalse(result["passed"])
        self.assertTrue(result["transport_readiness"]["instrumentation_consumed_ack"])
        self.assertNotIn("console_requests", result)

    def test_geometry_failure_stays_failure_after_transport_passes(self):
        geometry = self.evidence / "window-geometry.jsonl"
        rows = [json.loads(line) for line in geometry.read_text().splitlines()]
        rows[0]["width_dp"] = 600
        geometry.write_text("\n".join(json.dumps(row) for row in rows))
        result = self.review()
        self.assertFalse(result["passed"])
        self.assertEqual(6, len(result["console_requests"]))

    def test_source_cleanup_host_and_grant_failures_cannot_be_reported_passed(self):
        original = copy.deepcopy(self.execution)
        for key, value in (("host", dict(system="Linux", machine="arm64")), ("final_sha", "f" * 40),
                           ("final_tracked_status", " M tracked.py"), ("gradle_exit", 1),
                           ("owned_ports_clear", False), ("owned_emulator_stopped", False),
                           ("released_matching_leases", ["device"]), ("controller_error", "timed out"),
                           ("serial", "emulator-5580"), ("image_pin_verified", False),
                           ("physical_device_actions", 1), ("instrumentation_new_gradle_processes_terminated", False),
                           ("final_test_model", None)):
            self.execution = dict(original, **{key: value}); self.save()
            with self.subTest(key=key):
                self.assertFalse(self.review()["passed"])

    def test_raw_command_sdk_source_and_lease_receipts_are_bound(self):
        cases = [self.evidence / "commands/0.stdout", self.repo / local.SOURCE_FILES[0]]
        for path in cases:
            old = path.read_bytes(); path.write_bytes(b"changed")
            self.assertFalse(self.review()["passed"])
            path.write_bytes(old)
        sdk = json.loads(self.fixture.sdk_receipts.read_text())
        sdk["files"][0]["sha256"] = "f" * 64
        local.write_json(self.fixture.sdk_receipts, sdk)
        self.assertFalse(self.review()["passed"])

    def test_original_ci_reviewer_never_admits_local_evidence(self):
        result = local.reviewer.review(self.repo, self.sha, "pixel_9_pro_fold")
        self.assertFalse(result["passed"])
        self.assertTrue(any("SDK" in error for error in result["errors"]))


if __name__ == "__main__":
    unittest.main()
