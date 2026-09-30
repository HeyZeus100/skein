import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("verify_foldable", Path(__file__).with_name("verify-foldable.py"))
reviewer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reviewer)


class FoldableEvidenceReviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", self.repo], check=True)
        subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "-q", "--allow-empty", "-m", "fixture"], cwd=self.repo, check=True)
        self.sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.repo, text=True).strip()
        self.xml = self.repo / "app/build/outputs/androidTest-results/connected/TEST-fold.xml"
        self.xml.parent.mkdir(parents=True)
        for folder in ("dev/debug", "androidTest/dev/debug"):
            apk = self.repo / "app/build/outputs/apk" / folder / "fixture.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"fixture-apk")
        geometry = self.repo / "build/foldable-evidence/window-geometry.jsonl"
        geometry.parent.mkdir(parents=True)
        self.config = geometry.with_name("avd-config.ini")
        self.write_config("pixel_9_pro_fold")
        geometry.write_text("\n".join(json.dumps({"step": step, "width_dp": width, "height_dp": height,
                                                "density_dpi": 420, "activity_identity": 1,
                                                "geometry_observer": "activity", "orientation": 2 if step == "outer_landscape" else 1,
                                                "window_bounds_px": [0, 0, round(width * 420 / 160), round(height * 420 / 160)]})
                                     for step, width, height in (
                                         ("closed_before", 411, 797), ("flat", 841, 701),
                                         ("closed_after", 411, 797), ("outer_portrait", 411, 797),
                                         ("outer_landscape", 797, 411))))
        self.cases = [f'<testcase classname="{cls}" name="{name}"/>' for cls, name in sorted(reviewer.EXPECTED)]
        run_id = "a" * 32
        geometry.with_name("console-run-id.txt").write_text(run_id + "\n")
        self.events = [{"event": "started", "run_id": run_id, "serial": "emulator-5554", "avd": "skein_foldable_gate"}]
        for sequence, action in enumerate(("fold", "unfold", "fold", "unfold", "fold", "unfold"), 1):
            request = {"run_id": run_id, "sequence": sequence, "nonce": f"{sequence:032x}", "action": action}
            self.events += [{"event": "request", **request},
                            {"event": "console", "run_id": run_id, "sequence": sequence, "action": action,
                             "returncode": 0, "stdout": "OK\n", "stderr": ""},
                            {"event": "ack", **request, "status": "ok"}]
        self.events.append({"event": "stopped", "run_id": run_id})
        self.events_path = geometry.with_name("console-events.jsonl")
        self.events_path.write_text("\n".join(json.dumps(row) for row in self.events))
        self.diagnostics = geometry.with_name("display-diagnostics.jsonl")
        samples = []
        for request in (event for event in self.events if event["event"] == "request"):
            samples.append({**{key: request[key] for key in ("run_id", "sequence", "nonce", "action")},
                            "schema_version": 1, "phase": "console_ok_before_ack",
                            "started_monotonic_ns": request["sequence"] * 100,
                            "finished_monotonic_ns": request["sequence"] * 100 + 1,
                            "reads": [dict(command=command, returncode=0, stdout=output, stderr="")
                                      for command, output in (
                                          (["shell", "wm", "folded-area"], "Folded area: 0,0,884,2208\n"),
                                          (["shell", "cmd", "device_state", "print-state"], "1\n"),
                                          (["shell", "dumpsys", "display"], "synthetic display diagnostic\n"))]})
        self.diagnostics.write_text("\n".join(json.dumps(row) for row in samples))

    def write_cases(self, cases):
        self.xml.write_text('<testsuite tests="999" failures="0">' + "".join(cases) + '</testsuite>')

    def write_config(self, profile):
        manufacturer = "Generic" if profile == "7.6in Foldable" else "Google"
        self.config.write_text(f"hw.device.name = {profile}\nhw.device.manufacturer = {manufacturer}\n"
                               "hw.sensor.hinge = yes\nhw.sensor.hinge.count = 1\n"
                               "image.sysdir.1 = system-images/android-35/google_apis/x86_64/\n")
        image_dir = "system-images/android-35/google_apis/x86_64"
        payloads = [f"{image_dir}/{name}" for name in ("system.img", "vendor.img", "ramdisk.img", "kernel-ranchu")]
        paths = ["emulator/source.properties", "emulator/emulator",
                 "emulator/qemu/linux-x86_64/qemu-system-x86_64", f"{image_dir}/source.properties", *payloads]
        self.sdk_receipts = self.config.with_name("sdk-runtime-receipts.json")
        self.sdk_receipts.write_text(json.dumps(dict(
            schema_version=1, attribution="host SDK files, not emulator process attestation or original failed image equality",
            avd_config_sha256=hashlib.sha256(self.config.read_bytes()).hexdigest(), image_sysdir=image_dir + "/",
            system_image_payloads=payloads, files=[dict(path=path, bytes=1, sha256="1" * 64) for path in paths])))

    def test_counts_actual_cases_and_hashes_source_bound_artifacts(self):
        self.write_cases(self.cases)
        result = reviewer.review(self.repo, self.sha)
        self.assertTrue(result["passed"])
        self.assertEqual(2, len(result["cases"]))
        self.assertEqual(9, len(result["artifacts"]))
        self.assertTrue(all(len(value) == 64 for value in result["artifacts"].values()))

    def test_sdk_image_receipts_cannot_be_missing_metadata_only_or_bound_to_another_config(self):
        self.write_cases(self.cases)
        original = json.loads(self.sdk_receipts.read_text())
        import copy
        missing = copy.deepcopy(original)
        missing["system_image_payloads"] = []
        duplicate = copy.deepcopy(original)
        duplicate["files"].append(duplicate["files"][0])
        wrong_config = copy.deepcopy(original)
        wrong_config["avd_config_sha256"] = "f" * 64
        bad_hash = copy.deepcopy(original)
        bad_hash["files"][-1]["sha256"] = "unknown"
        for changed in (missing, duplicate, wrong_config, bad_hash):
            self.sdk_receipts.write_text(json.dumps(changed))
            self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.sdk_receipts.unlink()
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_display_diagnostics_cannot_be_missing_stale_partial_or_failed(self):
        self.write_cases(self.cases)
        original = [json.loads(line) for line in self.diagnostics.read_text().splitlines()]
        import copy
        mutations = []
        missing = copy.deepcopy(original[:-1])
        mutations.append(missing)
        for key, value in (("run_id", "f" * 32), ("nonce", "f" * 32), ("error", "read timed out"),
                           ("started_monotonic_ns", True), ("phase", "after_unrelated_action")):
            changed = copy.deepcopy(original)
            changed[0][key] = value
            mutations.append(changed)
        changed = copy.deepcopy(original)
        changed[0]["reads"][0]["command"] = ["shell", "wm", "size", "884x2208"]
        mutations.append(changed)
        for rows in mutations:
            self.diagnostics.write_text("\n".join(json.dumps(row) for row in rows))
            self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.diagnostics.unlink()
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_empty_failure_skip_and_duplicate_cannot_pass(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.write_cases([self.cases[0].replace('/>', f'><{tag}/></testcase>'), self.cases[1]])
                self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.write_cases(self.cases + self.cases[:1])
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_generic_profile_is_explicit_and_preserves_pixel_acceptance_gate(self):
        self.write_cases(self.cases)
        self.write_config("7.6in Foldable")
        result = reviewer.review(self.repo, self.sha, "7.6in Foldable")
        self.assertTrue(result["passed"])
        self.assertEqual("generic deprecated foldable compatibility", result["profile_scope"])
        self.assertIn("Pixel 9 Pro Fold profile acceptance", result["remaining_gates"])
        output = self.repo / "review.json"
        command = subprocess.run([sys.executable, str(Path(__file__).with_name("verify-foldable.py")),
                                  "--repository", str(self.repo), "--expected-sha", self.sha,
                                  "--profile", "7.6in Foldable", "--output", str(output)],
                                 capture_output=True, text=True)
        self.assertEqual(0, command.returncode, command.stderr)
        self.assertEqual("7.6in Foldable", json.loads(output.read_text())["profile"])
        self.assertFalse(reviewer.review(self.repo, self.sha, "pixel_fold")["passed"])
        self.assertFalse(reviewer.review(self.repo, self.sha, "62")["passed"])

    def test_missing_hinge_or_wrong_actual_profile_cannot_pass(self):
        self.write_cases(self.cases)
        self.write_config("pixel_fold")
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.write_config("pixel_9_pro_fold")
        self.config.write_text(self.config.read_text().replace("hinge = yes", "hinge = no"))
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.config.unlink()
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_geometry_must_show_wider_inner_window_and_same_activity_per_journey(self):
        self.write_cases(self.cases)
        path = self.repo / "build/foldable-evidence/window-geometry.jsonl"
        original = [json.loads(line) for line in path.read_text().splitlines()]
        for key, value in (("width_dp", 411), ("activity_identity", 2)):
            with self.subTest(key=key):
                rows = [dict(row) for row in original]
                next(row for row in rows if row["step"] == "flat")[key] = value
                path.write_text("\n".join(json.dumps(row) for row in rows))
                self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_failed_mismatched_duplicate_or_unstopped_console_cannot_pass(self):
        self.write_cases(self.cases)
        for index, key, value in ((3, "nonce", "f" * 32), (3, "status", "error"),
                                  (2, "returncode", 1), (1, "run_id", "f" * 32),
                                  (-1, "event", "fatal"), (0, "avd", "wrong_avd")):
            with self.subTest(index=index, key=key):
                rows = [dict(row) for row in self.events]
                rows[index][key] = value
                self.events_path.write_text("\n".join(json.dumps(row) for row in rows))
                self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.events_path.write_text("\n".join(json.dumps(row) for row in self.events[:-1] + self.events[2:3] + self.events[-1:]))
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.events_path.unlink()
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_non_activity_missing_or_contradictory_window_geometry_cannot_pass(self):
        self.write_cases(self.cases)
        path = self.repo / "build/foldable-evidence/window-geometry.jsonl"
        original = [json.loads(line) for line in path.read_text().splitlines()]
        for step, key, value in (("closed_before", "geometry_observer", "targetContext"),
                                 ("closed_before", "window_bounds_px", None),
                                 ("closed_before", "window_bounds_px", [0, 0, 2208, 2208]),
                                 ("flat", "window_bounds_px", [0, 0, 1000, 2000]),
                                 ("outer_landscape", "window_bounds_px", [0, 0, 2000, 2000]),
                                 ("outer_landscape", "orientation", 1)):
            with self.subTest(step=step, key=key):
                rows = [dict(row) for row in original]
                next(row for row in rows if row["step"] == step)[key] = value
                path.write_text("\n".join(json.dumps(row) for row in rows))
                self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_ack_before_console_or_non_alternating_posture_cannot_pass(self):
        self.write_cases(self.cases)
        rows = [dict(row) for row in self.events]
        rows[2], rows[3] = rows[3], rows[2]
        self.events_path.write_text("\n".join(json.dumps(row) for row in rows))
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        rows = [dict(row) for row in self.events]
        for row in rows[4:7]:
            row["action"] = "fold"
        self.events_path.write_text("\n".join(json.dumps(row) for row in rows))
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])

    def test_wrong_source_missing_case_and_malformed_xml_cannot_pass(self):
        self.write_cases(self.cases)
        self.assertFalse(reviewer.review(self.repo, "0" * 40)["passed"])
        self.write_cases(self.cases[:1])
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.xml.write_text("<not-xml")
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])


if __name__ == "__main__":
    unittest.main()
