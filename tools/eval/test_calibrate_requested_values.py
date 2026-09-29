"""Guard development-only calibration inputs, including contamination boundaries."""
import json
from pathlib import Path
import tempfile
import unittest

from calibrate_requested_values import calibrate

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'docs/eval/runs/2026-09-28-retrieval-repair-890ad71/retrieval.json'
GOLD = ROOT / 'testing/src/main/resources/eval/gold.json'
CONTROLS = ROOT / 'core/rag/src/test/resources/eval/requested-value-development.json'


class CalibrationBoundaryTest(unittest.TestCase):
    def rejects(self, mutation):
        report = json.loads(REPORT.read_bytes())
        mutation(report)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            path.write_text(json.dumps(report))
            with self.assertRaises(ValueError):
                calibrate(path, GOLD, CONTROLS)

    def test_refuses_validation_input(self):
        self.rejects(lambda r: r.update(split='independent_validation'))

    def test_refuses_vectors(self):
        self.rejects(lambda r: r.update(vector_count=1))

    def test_refuses_previously_gated_candidates(self):
        self.rejects(lambda r: r.update(evidence_policy={'version': 'lexical-query-coverage-v1'}))

    def test_refuses_missing_query(self):
        self.rejects(lambda r: r['modes'][0]['queries'].pop())

    def test_refuses_duplicated_query(self):
        self.rejects(lambda r: r['modes'][0]['queries'].__setitem__(0, r['modes'][0]['queries'][1]))

    def test_refuses_duplicated_mode(self):
        self.rejects(lambda r: r['modes'].__setitem__(0, r['modes'][1]))

    def test_refuses_changed_gold(self):
        self.rejects(lambda r: r.update(gold_sha256='0' * 64))


if __name__ == '__main__':
    unittest.main()
