"""Integrity regressions only: no fixture answers are generated or graded here."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import heldout_answer_eval as evaluate


class HeldoutAnswerEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.cases, self.digest, self.freeze = evaluate.fixture()
        self.receipt = json.loads((evaluate.DIRECTORY / "manifest.json").read_text())
        self.manifest = dict(
            engine_path="isolated-apk", mode="supplied_evidence", run_id="integrity-test-only",
            case_set_sha256=self.digest, heldout_freeze_sha256=self.freeze, fixture_commit="d" * 40,
            fixture_sha256=evaluate.base.sha(evaluate.export_bytes(self.cases)),
            model_sha256="a" * 64, template_sha256="b" * 64, apk_sha256="c" * 64,
            build_sha="schema-test", llama_sha="schema-test", device="synthetic schema test",
            sampling={"temperature": 0}, context={"actual": 4096}, seeds=[17, 41, 73],
        )

    def row(self, case=None, seed=17, status="ok"):
        return dict(case_id=(case or self.cases[0])["id"], seed=seed,
                    run_id="integrity-test-only", status=status, answer="Schema test only",
                    used_sources=[], citations=[])

    def grade(self, reviewer_type="ai"):
        return dict(case_id=self.cases[0]["id"], seed=17, reviewer="integrity-test-only",
                    reviewer_type=reviewer_type, notes="Synthetic annotation, not measured model quality",
                    correct=False, behavior="answer", cited_claims=0, supported_cited_claims=0,
                    claims_requiring_citations=0, claims_with_citations=0)

    def score(self, outputs, reviews=(), manifest=None):
        return evaluate.score(self.cases, self.digest, self.freeze, manifest or self.manifest, outputs, reviews)

    def test_full_fixture_integrity_and_gold_free_export(self):
        self.assertEqual(80, len(self.cases))
        for case, line in zip(self.cases, evaluate.export_bytes(self.cases).decode().splitlines()):
            exported = json.loads(line)
            self.assertEqual({"schema_version", "case_id", "scope", "query", "history", "sources"}, set(exported))
            self.assertTrue(set(case["gold"]["required_doc_ids"]) <= {d["doc_id"] for d in exported["sources"]})
            if not case["knowledge"]:
                self.assertEqual([], exported["sources"])
            for source in exported["sources"]:
                self.assertEqual(len(source["text"].encode()), source["byte_end"])

    def test_hash_drift_and_pending_peer_review_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            original = (evaluate.DIRECTORY / "cases.json").read_bytes()
            (target / "cases.json").write_bytes(original + b" ")
            (target / "manifest.json").write_text(json.dumps(self.receipt))
            with patch.object(evaluate, "DIRECTORY", target):
                with self.assertRaisesRegex(ValueError, "hash changed"):
                    evaluate.fixture()
                (target / "cases.json").write_bytes(original)
                pending = copy.deepcopy(self.receipt)
                pending["status"] = "draft"
                (target / "manifest.json").write_text(json.dumps(pending))
                with self.assertRaisesRegex(ValueError, "gold review and freeze"):
                    evaluate.fixture(require_frozen=True)

    def test_missing_utf8_span_and_scope_leak_are_rejected(self):
        for problem in ("span", "scope"):
            cases = copy.deepcopy(self.cases)
            if problem == "span":
                cases[0]["gold"]["supporting_spans"][0]["byte_end"] -= 1
            else:
                cases[0]["documents"][0]["space"] = "unauthorized"
            with self.assertRaises(ValueError):
                evaluate.validate_cases(cases, self.receipt)

    def test_conflict_cannot_lose_one_side_of_required_evidence(self):
        cases = copy.deepcopy(self.cases)
        conflict = next(c for c in cases if c["category"] == "conflict")
        conflict["gold"]["required_doc_ids"] = conflict["gold"]["required_doc_ids"][:1]
        conflict["gold"]["supporting_spans"] = conflict["gold"]["supporting_spans"][:1]
        with self.assertRaisesRegex(ValueError, "all competing source evidence"):
            evaluate.validate_cases(cases, self.receipt)

    def test_missing_unreviewed_and_timeout_results_stay_in_full_denominator(self):
        report = self.score([self.row(), self.row(self.cases[1], status="timeout")])
        self.assertEqual(240, report["verified_correct_lower_bound"]["denominator"])
        self.assertEqual(238, report["counts"]["missing"])
        self.assertEqual(1, report["counts"]["unreviewed"])
        self.assertEqual(1, report["counts"]["timeout"])
        self.assertFalse(report["structural_report_complete"])
        self.assertFalse(report["human_review_complete"])

    def test_all_timeouts_are_zero_correctness_and_never_a_quality_pass(self):
        outputs = [self.row(c, seed, "timeout") for c in self.cases for seed in self.manifest["seeds"]]
        report = self.score(outputs)
        self.assertTrue(report["structural_report_complete"])
        self.assertEqual(0, report["verified_correct_lower_bound"]["numerator"])
        self.assertFalse(report["human_review_complete"])
        self.assertNotIn("quality_gate_eligible", report)
        self.assertEqual("NOT_DECIDED_BY_THIS_TOOL", report["quality_gate_status"])

    def test_ai_review_does_not_claim_human_grading(self):
        report = self.score([self.row()], [self.grade()])
        self.assertEqual({"ai": 1}, report["reviewer_types"])
        self.assertFalse(report["human_review_complete"])
        self.assertTrue(self.score([self.row()], [self.grade("human")])["human_review_complete"])
        with self.assertRaisesRegex(ValueError, "declare human or AI"):
            self.score([self.row()], [self.grade(None)])

    def test_changed_provenance_duplicate_rows_and_inconsistent_grade_fail(self):
        for change in ({"heldout_freeze_sha256": "e" * 64}, {"fixture_commit": "short"}, {"engine_path": "fake"}):
            with self.assertRaises(ValueError):
                self.score([], manifest=dict(self.manifest, **change))
        with self.assertRaises(ValueError):
            self.score([self.row(), self.row()])
        with self.assertRaisesRegex(ValueError, "contradicts expected behavior"):
            self.score([self.row()], [dict(self.grade(), correct=True, behavior="abstain")])


if __name__ == "__main__":
    unittest.main()
