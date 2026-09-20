#!/usr/bin/env python3
"""Unit tests for tools/m0-benchmark/lib/models.py's YAML-subset parser."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

LIB_DIR = Path(__file__).resolve().parent.parent / "lib"
sys.path.insert(0, str(LIB_DIR))

import models  # noqa: E402

SAMPLE = """\
# a comment line, and a trailing-comment case below
models:
  - id: model-a
    display_name: "Model A"  # inline comment
    formal_m0_candidate: false
    sha256: TBD
    expected_size_bytes: 12345
    capabilities: [text]

  - id: model-b
    display_name: 'Model B'
    formal_m0_candidate: true
    sha256: "abc123"
    matryoshka_dims: [768, 512, 256]

binaries:
  - backend: cpu
    kind: bench
    path: "/bin/llama-bench-cpu"
    sha256: "deadbeef"
"""


class ParseScalarTests(unittest.TestCase):
    def test_plain_string(self):
        self.assertEqual(models.parse_scalar("hello"), "hello")

    def test_quoted_strings(self):
        self.assertEqual(models.parse_scalar('"hello world"'), "hello world")
        self.assertEqual(models.parse_scalar("'hello'"), "hello")

    def test_booleans(self):
        self.assertIs(models.parse_scalar("true"), True)
        self.assertIs(models.parse_scalar("false"), False)

    def test_tbd_and_null_become_none(self):
        self.assertIsNone(models.parse_scalar("TBD"))
        self.assertIsNone(models.parse_scalar("null"))
        self.assertIsNone(models.parse_scalar("~"))

    def test_integers_and_floats(self):
        self.assertEqual(models.parse_scalar("12345"), 12345)
        self.assertEqual(models.parse_scalar("-5"), -5)
        self.assertEqual(models.parse_scalar("3.14"), 3.14)

    def test_bracketed_list(self):
        self.assertEqual(models.parse_scalar("[text]"), ["text"])
        self.assertEqual(models.parse_scalar("[768, 512, 256]"), [768, 512, 256])
        self.assertEqual(models.parse_scalar("[]"), [])


class ParseManifestTests(unittest.TestCase):
    def setUp(self):
        self.manifest = models.parse_manifest(SAMPLE)

    def test_top_level_keys_present(self):
        self.assertIn("models", self.manifest)
        self.assertIn("binaries", self.manifest)

    def test_model_count(self):
        self.assertEqual(len(self.manifest["models"]), 2)

    def test_model_a_fields(self):
        a = models.find(self.manifest["models"], id="model-a")
        self.assertIsNotNone(a)
        self.assertEqual(a["display_name"], "Model A")
        self.assertIs(a["formal_m0_candidate"], False)
        self.assertIsNone(a["sha256"])
        self.assertEqual(a["expected_size_bytes"], 12345)
        self.assertEqual(a["capabilities"], ["text"])

    def test_model_b_fields(self):
        b = models.find(self.manifest["models"], id="model-b")
        self.assertIsNotNone(b)
        self.assertIs(b["formal_m0_candidate"], True)
        self.assertEqual(b["sha256"], "abc123")
        self.assertEqual(b["matryoshka_dims"], [768, 512, 256])

    def test_binaries_parsed(self):
        entry = models.find(self.manifest["binaries"], backend="cpu", kind="bench")
        self.assertIsNotNone(entry)
        self.assertEqual(entry["sha256"], "deadbeef")

    def test_find_returns_none_for_missing(self):
        self.assertIsNone(models.find(self.manifest["models"], id="does-not-exist"))

    def test_inline_comment_stripped(self):
        a = models.find(self.manifest["models"], id="model-a")
        self.assertEqual(a["display_name"], "Model A")

    def test_list_item_outside_key_raises(self):
        with self.assertRaises(ValueError):
            models.parse_manifest("  - id: orphan\n")

    def test_mapping_outside_list_item_raises(self):
        with self.assertRaises(ValueError):
            models.parse_manifest("models:\n  loose_key: value\n")


class RealManifestTests(unittest.TestCase):
    """Sanity-check the actual tools/m0-benchmark/models.yaml this repo ships."""

    def setUp(self):
        manifest_path = Path(__file__).resolve().parent.parent / "models.yaml"
        self.manifest = models.load(manifest_path)

    def test_smoke_model_is_not_a_formal_candidate(self):
        smoke = models.find(
            self.manifest["models"], id="qwen-2.5-3b-instruct-abliterated-q3km-smoke"
        )
        self.assertIsNotNone(smoke)
        self.assertIs(smoke["formal_m0_candidate"], False)
        self.assertIsNotNone(smoke["sha256"])

    def test_formal_candidate_model_is_flagged_true(self):
        formal = models.find(
            self.manifest["models"], id="qwen-2.5-3b-instruct-abliterated-q4km"
        )
        self.assertIsNotNone(formal)
        self.assertIs(formal["formal_m0_candidate"], True)

    def test_all_four_binaries_present(self):
        for backend in ("cpu", "vulkan"):
            for kind in ("bench", "cli"):
                entry = models.find(self.manifest["binaries"], backend=backend, kind=kind)
                self.assertIsNotNone(entry, f"missing binary backend={backend} kind={kind}")
                self.assertIsNotNone(entry["sha256"])


if __name__ == "__main__":
    unittest.main()
