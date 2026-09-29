import importlib.util
import json
from pathlib import Path
import subprocess
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
        geometry.write_text("\n".join(json.dumps({"step": step, "width_dp": width, "height_dp": height,
                                                "density_dpi": 420, "activity_identity": 1})
                                     for step, width, height in (
                                         ("closed_before", 411, 797), ("flat", 841, 701),
                                         ("closed_after", 411, 797), ("outer_portrait", 411, 797),
                                         ("outer_landscape", 797, 411))))
        self.cases = [f'<testcase classname="{cls}" name="{name}"/>' for cls, name in sorted(reviewer.EXPECTED)]

    def write_cases(self, cases):
        self.xml.write_text('<testsuite tests="999" failures="0">' + "".join(cases) + '</testsuite>')

    def test_counts_actual_cases_and_hashes_source_bound_artifacts(self):
        self.write_cases(self.cases)
        result = reviewer.review(self.repo, self.sha)
        self.assertTrue(result["passed"])
        self.assertEqual(2, len(result["cases"]))
        self.assertEqual(4, len(result["artifacts"]))
        self.assertTrue(all(len(value) == 64 for value in result["artifacts"].values()))

    def test_empty_failure_skip_and_duplicate_cannot_pass(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.write_cases([self.cases[0].replace('/>', f'><{tag}/></testcase>'), self.cases[1]])
                self.assertFalse(reviewer.review(self.repo, self.sha)["passed"])
        self.write_cases(self.cases + self.cases[:1])
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
