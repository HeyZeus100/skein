import importlib.util
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
                                                "density_dpi": 420, "activity_identity": 1})
                                     for step, width, height in (
                                         ("closed_before", 411, 797), ("flat", 841, 701),
                                         ("closed_after", 411, 797), ("outer_portrait", 411, 797),
                                         ("outer_landscape", 797, 411))))
        self.cases = [f'<testcase classname="{cls}" name="{name}"/>' for cls, name in sorted(reviewer.EXPECTED)]

    def write_cases(self, cases):
        self.xml.write_text('<testsuite tests="999" failures="0">' + "".join(cases) + '</testsuite>')

    def write_config(self, profile):
        manufacturer = "Generic" if profile == "7.6in Foldable" else "Google"
        self.config.write_text(f"hw.device.name = {profile}\nhw.device.manufacturer = {manufacturer}\n"
                               "hw.sensor.hinge = yes\nhw.sensor.hinge.count = 1\n")

    def test_counts_actual_cases_and_hashes_source_bound_artifacts(self):
        self.write_cases(self.cases)
        result = reviewer.review(self.repo, self.sha)
        self.assertTrue(result["passed"])
        self.assertEqual(2, len(result["cases"]))
        self.assertEqual(5, len(result["artifacts"]))
        self.assertTrue(all(len(value) == 64 for value in result["artifacts"].values()))

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

    def test_wrong_source_missing_case_and_malformed_xml_cannot_pass(self):
        self.write_cases(self.cases)
        self.assertFalse(reviewer.review(self.repo, "0" * 40)["passed"])
        self.write_cases(self.cases[:1])
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.xml.write_text("<not-xml")
        self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])


if __name__ == "__main__":
    unittest.main()
