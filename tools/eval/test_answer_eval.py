"""Regression checks for evaluation integrity, never model-quality evidence."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import answer_eval as evaluate
import generate_answer_fixture as generator


class AnswerEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.cases, self.case_hash = evaluate.fixture("development")
        exported = "".join(json.dumps(evaluate.export_case(c), ensure_ascii=False, separators=(",", ":")) + "\n" for c in self.cases)
        self.manifest = dict(engine_path="isolated-apk", mode="supplied_evidence", run_id="unit-test-only",
                             case_set_sha256=self.case_hash, fixture_sha256=evaluate.sha(exported.encode()),
                             model_sha256="a"*64, template_sha256="b"*64, apk_sha256="c"*64,
                             build_sha="unit-test-only", llama_sha="unit-test-only", device="fake schema test",
                             sampling={"temperature": 0}, context={"actual": 4096}, seeds=[17, 41, 73])

    def result(self, case, seed=17):
        return dict(case_id=case["id"], seed=seed, run_id="unit-test-only", status="ok",
                    answer="Synthetic schema test", used_sources=[], citations=[])

    def test_fixture_is_reproducible_and_has_disjoint_reserved_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(generator, "OUT", Path(directory)):
                generator.build()
            for generated in Path(directory).iterdir():
                self.assertEqual(generated.read_bytes(), (evaluate.FIXTURES / generated.name).read_bytes())
        reserved, _ = evaluate.fixture("reserved")
        self.assertEqual(80, len(reserved))
        def question_with_history(case):
            return json.dumps([case["question"], case["history"]], sort_keys=True)
        self.assertTrue({question_with_history(c) for c in reserved}.isdisjoint({question_with_history(c) for c in self.cases}))

    def test_export_has_no_gold_and_excludes_deleted_other_space_and_general_sources(self):
        for split in ("development", "reserved"):
            cases, _ = evaluate.fixture(split)
            for case in cases:
                exported = evaluate.export_case(case)
                self.assertNotIn("gold", exported)
                self.assertNotIn("answer", exported)
                provided = {s["doc_id"] for s in exported["sources"]}
                self.assertTrue(provided.isdisjoint(case["gold"]["forbidden_doc_ids"]))
                self.assertTrue(set(case["gold"]["required_doc_ids"]) <= provided)

    def test_missing_and_unreviewed_outputs_stay_in_denominator(self):
        result = evaluate.score(self.cases, self.case_hash, self.manifest, [self.result(self.cases[0])], [])
        self.assertEqual(36, result["verified_correct_lower_bound"]["denominator"])
        self.assertEqual(0, result["verified_correct_lower_bound"]["numerator"])
        self.assertEqual(35, result["counts"]["missing"])
        self.assertEqual(1, result["counts"]["unreviewed"])
        self.assertFalse(result["quality_gate_eligible"])

    def test_duplicate_rows_wrong_fixture_and_mock_engine_are_rejected(self):
        row = self.result(self.cases[0])
        with self.assertRaises(ValueError):
            evaluate.score(self.cases, self.case_hash, self.manifest, [row, row], [])
        for mutation in ({"fixture_sha256": "d"*64}, {"engine_path": "fake"}):
            with self.assertRaises(ValueError):
                evaluate.score(self.cases, self.case_hash, dict(self.manifest, **mutation), [], [])

    def test_unknown_revision_and_fabricated_quote_are_structural_failures(self):
        case = self.cases[0]
        doc = case["documents"][0]
        row = self.result(case)
        row["citations"] = [dict(doc_id=doc["id"], revision_hash="e"*64)]
        row["exact_quotes"] = [dict(doc_id=doc["id"], revision_hash=doc["revision_sha256"], text="fabricated quote")]
        result = evaluate.score(self.cases, self.case_hash, self.manifest, [row], [])
        self.assertEqual(2, len(result["structural_failures"]))

    def test_reviewed_correctness_and_claim_counts_are_separate_from_source_membership(self):
        case = self.cases[0]
        row = self.result(case)
        grade = dict(case_id=case["id"], seed=17, reviewer="schema-test", notes="Synthetic review test only",
                     correct=True, behavior="answer", cited_claims=2, supported_cited_claims=1,
                     claims_requiring_citations=3, claims_with_citations=2)
        report = evaluate.score(self.cases, self.case_hash, self.manifest, [row], [grade])
        self.assertEqual(1, report["verified_correct_lower_bound"]["numerator"])
        self.assertEqual(0.5, report["cited_claim_support_reviewed"]["rate"])
        self.assertEqual(2/3, report["citation_coverage_reviewed"]["rate"])
        with self.assertRaises(ValueError):
            evaluate.score(self.cases, self.case_hash, self.manifest, [row], [dict(grade, supported_cited_claims=3)])

    def test_timeout_and_oom_do_not_disappear_from_report(self):
        outputs = [dict(self.result(self.cases[0]), status="timeout"),
                   dict(self.result(self.cases[1]), status="oom")]
        result = evaluate.score(self.cases, self.case_hash, self.manifest, outputs, [])
        self.assertEqual(1, result["counts"]["timeout"])
        self.assertEqual(1, result["counts"]["oom"])
        self.assertEqual(36, result["verified_correct_lower_bound"]["denominator"])


if __name__ == "__main__":
    unittest.main()
