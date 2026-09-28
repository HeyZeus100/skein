import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from prepare_android_benchmark import gguf_template_metadata


def string(value):
    return struct.pack("<Q", len(value)) + value


class RawTemplateMetadataTest(unittest.TestCase):
    def test_preparation_requires_and_retains_complete_overlay_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "public.gguf"
            model.write_bytes(b"GGUF" + struct.pack("<IQQ", 3, 0, 1) +
                              string(b"tokenizer.chat_template") + struct.pack("<I", 8) + string(b"{{ content }}"))
            apk, fixture = root / "app.apk", root / "input.jsonl"
            apk.write_bytes(b"synthetic apk")
            fixture.write_text('{"case_id":"synthetic-only"}\n')
            output = root / "prepared"
            arguments = [sys.executable, str(Path(__file__).with_name("prepare_android_benchmark.py")),
                         "--model", str(model), "--model-sha256", hashlib.sha256(model.read_bytes()).hexdigest(),
                         "--model-license", "Apache-2.0", "--apk", str(apk), "--fixture", str(fixture),
                         "--case-set-sha256", "b" * 64, "--build-sha", "c" * 40, "--llama-sha", "d" * 40,
                         "--run-id", "overlay-test", "--context-length", "1024", "--output-dir", str(output)]
            for extra in ([], ["--tokenizer-overlay-sha256", "short"]):
                rejected = subprocess.run(arguments + extra, capture_output=True, timeout=10)
                self.assertNotEqual(rejected.returncode, 0)
                self.assertFalse(output.exists())
            accepted = subprocess.run(arguments + ["--tokenizer-overlay-sha256", "e" * 64],
                                      capture_output=True, timeout=10)
            self.assertEqual(accepted.returncode, 0, accepted.stderr.decode())
            config = json.loads((output / "config.json").read_text())
            self.assertEqual(config["tokenizer_overlay_sha256"], "e" * 64)
            self.assertEqual(config["llama_sha"], "d" * 40)

    def inspect(self, data):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "public.gguf"
            path.write_bytes(data)
            return gguf_template_metadata(path)

    def test_hashes_exact_raw_bytes_without_normalizing_whitespace(self):
        template = b"{{ message }}\n\n"
        header = b"GGUF" + struct.pack("<IQQ", 3, 0, 2)
        array = string(b"tokenizer.ggml.tokens") + struct.pack("<IIQ", 9, 8, 2) + string(b"a") + string(b"b")
        metadata = string(b"tokenizer.chat_template") + struct.pack("<I", 8) + string(template)
        result = self.inspect(header + array + metadata)
        self.assertEqual(hashlib.sha256(template).hexdigest(), result["template_sha256"])
        self.assertEqual(len(template), result["template_bytes"])

    def test_rejects_huge_lengths_and_missing_template(self):
        header = b"GGUF" + struct.pack("<IQQ", 3, 0, 1)
        for suffix in (struct.pack("<Q", 2**63), string(b"other") + struct.pack("<I", 8) + string(b"x")):
            with self.assertRaises(ValueError):
                self.inspect(header + suffix)

    def test_rejects_duplicate_template_and_nested_array(self):
        entry = string(b"tokenizer.chat_template") + struct.pack("<I", 8) + string(b"x")
        with self.assertRaises(ValueError):
            self.inspect(b"GGUF" + struct.pack("<IQQ", 3, 0, 2) + entry + entry)
        with self.assertRaises(ValueError):
            self.inspect(b"GGUF" + struct.pack("<IQQ", 3, 0, 1) + string(b"array") + struct.pack("<IIQ", 9, 9, 1))


if __name__ == "__main__":
    unittest.main()
