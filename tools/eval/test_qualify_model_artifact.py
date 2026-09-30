import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import qualify_model_artifact as qualification


def string(value):
    value = value.encode("utf-8") if isinstance(value, str) else value
    return struct.pack("<Q", len(value)) + value


def entry(key, kind, payload):
    return string(key) + struct.pack("<I", kind) + payload


def token_array(tokens):
    return struct.pack("<IQ", 8, len(tokens)) + b"".join(string(token) for token in tokens)


def fixture(extra=(), tokens=("<bos>", "<eos>", "<start_of_turn>", "hello"), types=(3, 3, 3, 1)):
    fields = [
        entry("general.architecture", 8, string("gemma4")),
        entry("tokenizer.ggml.model", 8, string("llama")),
        entry("tokenizer.ggml.tokens", 9, token_array(tokens)),
        entry("tokenizer.ggml.token_type", 9, struct.pack("<IQ", 5, len(types)) +
              b"".join(struct.pack("<i", value) for value in types)),
        entry("tokenizer.chat_template", 8, string("<start_of_turn>{{ content }}<eos>")),
        entry("tokenizer.ggml.eos_token_id", 4, struct.pack("<I", 1)),
        *extra,
    ]
    return b"GGUF" + struct.pack("<IQQ", 3, 0, len(fields)) + b"".join(fields)


class QualificationTest(unittest.TestCase):
    def test_full_hash_template_tokenizer_and_declared_stops_are_bound_to_bytes(self):
        data = fixture()
        with tempfile.TemporaryDirectory() as temp:
            model = Path(temp) / "public.gguf"
            model.write_bytes(data)
            report = qualification.qualify(model, hashlib.sha256(data).hexdigest(), len(data))
        self.assertEqual("identity-only", report["qualification"])
        self.assertEqual(hashlib.sha256(b"<start_of_turn>{{ content }}<eos>").hexdigest(),
                         report["template_sha256"])
        # Strip header and first non-tokenizer entry: remaining raw entries define the digest.
        offset = 24 + len(entry("general.architecture", 8, string("gemma4")))
        self.assertEqual(hashlib.sha256(data[offset:]).hexdigest(), report["tokenizer_metadata_sha256"])
        self.assertEqual({"id": 1, "text": "<eos>", "type": 3},
                         report["declared_token_ids"]["tokenizer.ggml.eos_token_id"])
        self.assertIn("<|im_start|>", report["requested_controls_absent"])
        self.assertEqual(["<eos>", "<start_of_turn>"], report["requested_controls_in_template"])
        self.assertIn("native_eog", report["unmeasured"])
        self.assertIn("answer_quality", report["unmeasured"])

    def test_hash_and_size_mismatches_fail_before_metadata_is_trusted(self):
        with tempfile.TemporaryDirectory() as temp:
            model = Path(temp) / "public.gguf"
            model.write_bytes(b"not a model")
            with self.assertRaisesRegex(ValueError, "full model SHA256"):
                qualification.qualify(model, "0" * 64)
            with self.assertRaisesRegex(ValueError, "model size"):
                qualification.qualify(model, hashlib.sha256(model.read_bytes()).hexdigest(), 1)

    def test_ambiguous_or_invalid_metadata_is_rejected(self):
        cases = [
            (fixture(extra=[entry("tokenizer.ggml.eos_token_id", 4, struct.pack("<I", 2))]), "duplicate"),
            (fixture(types=(3,)), "inconsistent"),
            (fixture(extra=[entry("tokenizer.ggml.eot_token_id", 4, struct.pack("<I", 99))]), "outside"),
            (fixture(extra=[entry("tokenizer.ggml.add_bos_token", 4, struct.pack("<I", 1))]), "scalar"),
            (fixture()[:-1], "truncated"),
            (b"GGUF" + struct.pack("<IQQ", 3, 0, 1) + struct.pack("<Q", 1024 * 1024 + 1), "string"),
            (fixture(extra=[entry("ignored.array", 9, struct.pack("<IQ", 4, 2_000_001))]), "array"),
            (fixture(extra=[entry("ignored.array", 9, struct.pack("<IQ", 9, 1))]), "element"),
        ]
        for data, message in cases:
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                qualification.metadata_report(data)

    def test_tokenizer_digest_changes_when_token_types_change_even_with_same_template(self):
        first = qualification.metadata_report(fixture())
        changed = qualification.metadata_report(fixture(types=(3, 1, 3, 1)))
        self.assertEqual(first["template_sha256"], changed["template_sha256"])
        self.assertNotEqual(first["tokenizer_metadata_sha256"], changed["tokenizer_metadata_sha256"])
        self.assertEqual(1, changed["declared_token_ids"]["tokenizer.ggml.eos_token_id"]["type"])

    def test_changed_file_is_not_published_as_qualified(self):
        data = fixture()
        with tempfile.TemporaryDirectory() as temp:
            model = Path(temp) / "public.gguf"
            model.write_bytes(data)
            original = qualification.metadata_report

            def change_after_read(header, controls):
                with model.open("ab") as output:
                    output.write(b"changed")
                return original(header, controls)

            with patch.object(qualification, "metadata_report", side_effect=change_after_read):
                with self.assertRaisesRegex(ValueError, "changed during"):
                    qualification.qualify(model, hashlib.sha256(data).hexdigest())

    def test_cli_emits_only_json_on_success_and_no_report_on_failure(self):
        data = fixture()
        with tempfile.TemporaryDirectory() as temp:
            model = Path(temp) / "public.gguf"
            model.write_bytes(data)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                qualification.main(["--model", str(model), "--expected-sha256", hashlib.sha256(data).hexdigest(),
                                    "--control-token", "hello"])
            report = json.loads(output.getvalue())
            self.assertIn({"id": 3, "text": "hello", "type": 1}, report["control_entries"])
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    qualification.main(["--model", str(model), "--expected-sha256", "0" * 64])
            self.assertEqual(2, failure.exception.code)
            self.assertEqual("", output.getvalue())


if __name__ == "__main__":
    unittest.main()
