import hashlib
import struct
import tempfile
import unittest
from pathlib import Path

from prepare_android_benchmark import gguf_template_metadata


def string(value):
    return struct.pack("<Q", len(value)) + value


class RawTemplateMetadataTest(unittest.TestCase):
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
