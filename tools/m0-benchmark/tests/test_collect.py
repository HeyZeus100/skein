#!/usr/bin/env python3
"""Unit tests for tools/m0-benchmark/lib/collect.py (stdlib unittest, no deps)."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

LIB_DIR = Path(__file__).resolve().parent.parent / "lib"
sys.path.insert(0, str(LIB_DIR))

import collect  # noqa: E402


def _base_fields(**overrides) -> dict:
    fields = {f: None for f in collect.SCHEMA_FIELDS}
    fields.update(
        {
            "device_build_fingerprint": "google/comet/comet:17/CP2A.260805.005",
            "android_version": "17",
            "android_security_patch": "2026-09-01",
            "llama_cpp_commit": "b29c606e28a01b1bc8c1351026a0fa6e616bf6c4",
            "binary_sha256": "de85775ae5ace0e70ffedef19582bf6572dac493565a69bc607c45326b6adcc8",
            "model_sha256": "2c5f9a121ae6695208e300c16acca303669afa4e18812061164dca9c97071b12",
            "model_quantization": "Q3_K_M",
            "backend": "vulkan",
            "cmdline": "llama-bench-vulkan -m model.gguf -p 1024 -n 128",
            "context_length": 1024,
            "gpu_layers": 99,
            "threads": 4,
            "prompt_target": 1024,
            "gen_target": 128,
            "repeat_index": 1,
            "thermal_start": 0.2,
            "thermal_end": 0.25,
            "pp_toks_per_s": 23.47,
            "tg_toks_per_s": 5.61,
            "ttft_ms": 120.0,
            "peak_rss_mb": 2048.0,
            "battery_state": "87",
            "charging_state": "true",
            "exit_status": 0,
            "timestamp_start": "2026-09-20T01:00:00Z",
            "timestamp_end": "2026-09-20T01:00:30Z",
        }
    )
    fields.update(overrides)
    return fields


class BuildResultTests(unittest.TestCase):
    def test_valid_success_result_round_trips(self):
        result = collect.build_result(_base_fields())
        self.assertEqual(result["backend"], "vulkan")
        self.assertNotIn("failure_reason", result)
        self.assertNotIn("is_dry_run", result)

    def test_missing_field_raises_schema_error(self):
        fields = _base_fields()
        del fields["pp_toks_per_s"]
        with self.assertRaises(collect.SchemaError):
            collect.build_result(fields)

    def test_failure_requires_failure_reason(self):
        fields = _base_fields(exit_status=1)
        with self.assertRaises(collect.SchemaError):
            collect.build_result(fields)

    def test_failure_with_reason_is_valid(self):
        fields = _base_fields(exit_status=124, pp_toks_per_s=None, tg_toks_per_s=None)
        result = collect.build_result(fields, failure_reason="timeout")
        self.assertEqual(result["failure_reason"], "timeout")
        self.assertEqual(result["exit_status"], 124)

    def test_dry_run_does_not_require_failure_reason_even_with_nulls(self):
        fields = _base_fields(
            exit_status=0,
            pp_toks_per_s=None,
            tg_toks_per_s=None,
            thermal_end=None,
        )
        result = collect.build_result(fields, is_dry_run=True)
        self.assertTrue(result["is_dry_run"])

    def test_required_field_null_on_non_dry_run_is_rejected(self):
        fields = _base_fields(backend=None)
        with self.assertRaises(collect.SchemaError):
            collect.build_result(fields)

    def test_optional_fields_may_be_null_on_a_successful_cell(self):
        # ttft_ms/peak_rss_mb aren't always measurable even on success.
        fields = _base_fields(ttft_ms=None, peak_rss_mb=None)
        result = collect.build_result(fields)
        self.assertIsNone(result["ttft_ms"])
        self.assertIsNone(result["peak_rss_mb"])


class WriteCellResultTests(unittest.TestCase):
    def test_writes_valid_json_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "cell" / "result.json"
            result = collect.build_result(_base_fields())
            collect.write_cell_result(result, out_path)
            self.assertTrue(out_path.exists())
            reloaded = json.loads(out_path.read_text())
            self.assertEqual(reloaded["backend"], "vulkan")

    def test_refuses_to_write_invalid_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "result.json"
            bad = _base_fields()
            del bad["cmdline"]
            with self.assertRaises(collect.SchemaError):
                collect.write_cell_result(bad, out_path)
            self.assertFalse(out_path.exists())


class NormalizeLlamaBenchTests(unittest.TestCase):
    def test_extracts_pp_and_tg_from_two_row_output(self):
        raw = json.dumps(
            [
                {"n_prompt": 512, "n_gen": 0, "avg_ts": 6.49},
                {"n_prompt": 0, "n_gen": 128, "avg_ts": 5.61},
            ]
        )
        metrics = collect.normalize_llama_bench(raw)
        self.assertAlmostEqual(metrics["pp_toks_per_s"], 6.49)
        self.assertAlmostEqual(metrics["tg_toks_per_s"], 5.61)

    def test_single_dict_row_is_accepted(self):
        raw = json.dumps({"n_prompt": 64, "n_gen": 0, "avg_ts": 23.47})
        metrics = collect.normalize_llama_bench(raw)
        self.assertAlmostEqual(metrics["pp_toks_per_s"], 23.47)
        self.assertIsNone(metrics["tg_toks_per_s"])

    def test_computes_from_avg_ns_when_avg_ts_absent(self):
        # 128 tokens in 20,000,000,000 ns (20s) => 6.4 tok/s.
        raw = json.dumps([{"n_prompt": 0, "n_gen": 128, "avg_ns": 20_000_000_000}])
        metrics = collect.normalize_llama_bench(raw)
        self.assertAlmostEqual(metrics["tg_toks_per_s"], 6.4)

    def test_invalid_json_raises_schema_error(self):
        with self.assertRaises(collect.SchemaError):
            collect.normalize_llama_bench("not json")


class AggregateTests(unittest.TestCase):
    """aggregate() must find cells one level below the given output_dir —
    output/<date>/<cell>/result.json — matching exactly what run.sh writes
    and what it tells the operator to run (`aggregate output/<date>`)."""

    def test_finds_cells_directly_under_output_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "output" / "2026-09-20"
            result = collect.build_result(_base_fields())
            collect.write_cell_result(result, output_dir / "cell-a" / "result.json")
            collect.write_cell_result(result, output_dir / "cell-b" / "result.json")

            report_path = Path(tmp) / "MEASUREMENTS.md"
            n = collect.aggregate(output_dir, report_path)

            self.assertEqual(n, 2)
            self.assertIn("vulkan", report_path.read_text())

    def test_does_not_find_cells_nested_one_level_too_deep(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "output"
            result = collect.build_result(_base_fields())
            # A result nested under output/<date>/<cell>/ — one level
            # deeper than aggregate expects when pointed at output/ itself.
            collect.write_cell_result(result, output_dir / "2026-09-20" / "cell-a" / "result.json")

            report_path = Path(tmp) / "MEASUREMENTS.md"
            n = collect.aggregate(output_dir, report_path)

            self.assertEqual(n, 0)

    def test_missing_output_dir_yields_empty_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "does-not-exist"
            report_path = Path(tmp) / "MEASUREMENTS.md"
            n = collect.aggregate(output_dir, report_path)
            self.assertEqual(n, 0)
            self.assertIn("No non-dry-run cells yet", report_path.read_text())


class RenderReportTests(unittest.TestCase):
    def test_empty_results_message(self):
        report = collect.render_report([])
        self.assertIn("No non-dry-run cells yet", report)

    def test_dry_run_only_results_treated_as_empty(self):
        result = collect.build_result(_base_fields(), is_dry_run=True)
        report = collect.render_report([result])
        self.assertIn("No non-dry-run cells yet", report)

    def test_real_result_appears_in_table(self):
        result = collect.build_result(_base_fields())
        report = collect.render_report([result])
        self.assertIn("vulkan", report)
        self.assertIn("23.5", report)  # pp_toks_per_s=23.47 formatted to 1 decimal

    def test_failed_cell_listed_separately(self):
        fields = _base_fields(exit_status=1, pp_toks_per_s=None, tg_toks_per_s=None)
        result = collect.build_result(fields, failure_reason="timeout")
        report = collect.render_report([result])
        self.assertIn("Failed cells", report)
        self.assertIn("timeout", report)


if __name__ == "__main__":
    unittest.main()
