import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest

import prepare_native_parity as preparation
from test_qualify_model_artifact import entry, string, token_array


def fixture(architecture="gemma4", name="Gemma 4 E4B it", template="<|turn>{{ content }}<turn|>",
            tokens=("<bos>", "<eos>", "<|turn>", "<turn|>", "hello"), types=(3, 3, 3, 3, 1), eos=1):
    fields = [
        entry("general.architecture", 8, string(architecture)),
        entry("general.name", 8, string(name)),
        entry("tokenizer.ggml.model", 8, string("llama")),
        entry("tokenizer.ggml.tokens", 9, token_array(tokens)),
        entry("tokenizer.ggml.token_type", 9, struct.pack("<IQ", 5, len(types)) +
              b"".join(struct.pack("<i", value) for value in types)),
        entry("tokenizer.chat_template", 8, string(template)),
        entry("tokenizer.ggml.eos_token_id", 4, struct.pack("<I", eos)),
    ]
    return b"GGUF" + struct.pack("<IQQ", 3, 0, len(fields)) + b"".join(fields)


class PrepareParityTest(unittest.TestCase):
    def prepare(self, data, profile="gemma4-e4b", digest=None, size=None):
        with tempfile.TemporaryDirectory() as temp:
            model = Path(temp) / "synthetic-metadata-only.gguf"
            model.write_bytes(data)
            return preparation.prepare(model, digest or hashlib.sha256(data).hexdigest(),
                                       len(data) if size is None else size, profile)

    def test_synthetic_gemma4_manifest_binds_full_bytes_template_vocabulary_and_controls(self):
        data = fixture()
        report = self.prepare(data)
        self.assertEqual("metadata-only-native-parity-controls", report["qualification"])
        self.assertEqual(hashlib.sha256(data).hexdigest(), report["model_sha256"])
        self.assertEqual(len(data), report["model_size_bytes"])
        self.assertEqual(hashlib.sha256(b"<|turn>{{ content }}<turn|>").hexdigest(), report["template_sha256"])
        self.assertEqual({"spelling": "<|turn>", "id": 2, "type": 3}, report["literal_control"])
        self.assertEqual([{"spelling": "<turn|>", "id": 3, "type": 3},
                          {"spelling": "<eos>", "id": 1, "type": 3}], report["eog_controls"])
        self.assertEqual(5, report["vocabulary_size"])
        self.assertEqual(1, report["declared_eos_id"])
        changed = self.prepare(fixture(types=(1, 3, 3, 3, 1)))
        self.assertNotEqual(report["tokenizer_metadata_sha256"], changed["tokenizer_metadata_sha256"])
        self.assertEqual(report["template_sha256"], changed["template_sha256"])

    def test_chatml_remains_explicit_and_same_eos_turn_end_is_deduplicated(self):
        data = fixture(architecture="llama", name="Synthetic ChatML", template="<|im_start|>{{ content }}<|im_end|>",
                       tokens=("<bos>", "<eos>", "<|im_start|>", "<|im_end|>", "hello"), eos=3)
        report = self.prepare(data, "chatml")
        self.assertEqual([{"spelling": "<|im_end|>", "id": 3, "type": 3}], report["eog_controls"])

    def test_hash_and_size_mismatches_refuse_a_manifest(self):
        with self.assertRaisesRegex(ValueError, "SHA256"):
            self.prepare(fixture(), digest="0" * 64)
        with self.assertRaisesRegex(ValueError, "size"):
            self.prepare(fixture(), size=1)

    def test_large_metadata_cannot_emit_an_unbounded_manifest(self):
        with self.assertRaisesRegex(ValueError, "16 KiB"):
            self.prepare(fixture(name="Gemma 4 E4B " + "x" * 16_384))

    def test_declared_eos_with_duplicate_vocabulary_spelling_is_rejected(self):
        for duplicate_type in (1, 3):
            data = fixture(tokens=("<bos>", "<eos>", "<|turn>", "<turn|>", "<eos>"),
                           types=(3, 3, 3, 3, duplicate_type))
            with self.subTest(duplicate_type=duplicate_type), self.assertRaisesRegex(ValueError, "EOS spelling is ambiguous"):
                self.prepare(data)

    def test_unsupported_identity_and_template_refuse_a_manifest(self):
        cases = [fixture(architecture="gemma3"), fixture(name="Gemma 4 E2B it"),
                 fixture(template="<start_of_turn>{{ content }}<end_of_turn>"),
                 fixture(template="<|turn>{{ content }}"), fixture(architecture="llama")]
        for data in cases:
            with self.subTest(data_hash=hashlib.sha256(data).hexdigest()), self.assertRaises(ValueError):
                self.prepare(data)
        with self.assertRaisesRegex(ValueError, "unsupported"):
            self.prepare(fixture(), "inferred")
        with self.assertRaisesRegex(ValueError, "unsupported ChatML"):
            self.prepare(fixture(), "chatml")

    def test_ambiguous_missing_normal_and_out_of_range_controls_fail(self):
        cases = [fixture(types=(3, 3, 1, 3, 1)), fixture(types=(3, 3, 3, 1, 1)),
                 fixture(types=(3, 1, 3, 3, 1)), fixture(eos=99), fixture(eos=2),
                 fixture(tokens=("<bos>", "<eos>", "<|turn>", "missing", "hello")),
                 fixture(tokens=("<bos>", "<eos>", "<|turn>", "<turn|>", "<|turn>")),
                 fixture(tokens=("<bos>", "<turn|>", "<|turn>", "<turn|>", "hello")),
                 fixture(tokens=("<bos>", "x" * 129, "<|turn>", "<turn|>", "hello"))]
        for data in cases:
            with self.subTest(data_hash=hashlib.sha256(data).hexdigest()), self.assertRaises(ValueError):
                self.prepare(data)

    def test_cli_preserves_existing_output_and_failed_inputs_leave_no_manifest(self):
        data = fixture()
        with tempfile.TemporaryDirectory() as temp:
            model, output = Path(temp) / "synthetic.gguf", Path(temp) / "controls.json"
            model.write_bytes(data)
            args = ["--model", str(model), "--expected-sha256", hashlib.sha256(data).hexdigest(),
                    "--expected-size", str(len(data)), "--profile", "gemma4-e4b", "--output", str(output)]
            preparation.main(args)
            original = output.read_bytes()
            self.assertEqual("gemma4-e4b", json.loads(original)["profile"])
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                preparation.main(args)
            self.assertEqual(original, output.read_bytes())
            args[-1] = str(Path(temp) / "failed.json")
            args[3] = "0" * 64
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                preparation.main(args)
            self.assertFalse(Path(args[-1]).exists())


if __name__ == "__main__":
    unittest.main()
