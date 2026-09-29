import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("verify_instrumentation", Path(__file__).with_name("verify-instrumentation.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class InstrumentationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.paths = {}
        for module in verifier.MODULES:
            path = self.repo / module / "build/outputs/androidTest-results/connected/TEST-suite.xml"
            path.parent.mkdir(parents=True)
            self.paths[module] = path
            self.write(module, '<testcase classname="ContractTest" name="contract"/>')

    def write(self, module, cases):
        self.paths[module].write_text('<testsuite tests="999" failures="0">' + cases + '</testsuite>')

    def test_requires_actual_cases_from_every_module(self):
        summary = verifier.review(self.repo)
        self.assertTrue(summary["passed"])
        self.assertEqual([row["passed"] for row in summary["modules"].values()], [1, 1, 1])
        self.paths["app"].unlink()
        self.assertFalse(verifier.review(self.repo)["passed"])

    def test_suite_counters_cannot_hide_any_nonpassing_case(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.write("core/vault", f'<testcase classname="ContractTest" name="contract"><{tag}/></testcase>')
                summary = verifier.review(self.repo)
                self.assertFalse(summary["passed"])
                self.assertEqual(summary["modules"]["core/vault"]["passed"], 0)
                self.assertEqual(summary["modules"]["core/vault"]["nonpassing_cases"][0]["tags"], [tag])

    def test_opt_in_class_is_rejected_even_if_passing(self):
        self.write("core/vault", f'<testcase classname="{verifier.RETRIEVAL_CLASS}" name="evaluate"/>')
        self.assertIn("opt-in retrieval", " ".join(verifier.review(self.repo)["errors"]))

    def test_empty_malformed_duplicate_or_unnamed_testcases_fail(self):
        for cases in ('', '<testcase', '<testcase/>',
                      '<testcase classname="C" name="same"/>' * 2):
            with self.subTest(cases=cases):
                self.write("app", cases)
                self.assertFalse(verifier.review(self.repo)["passed"])


if __name__ == "__main__":
    unittest.main()
