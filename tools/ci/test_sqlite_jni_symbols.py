"""Exact JNI gate tests; no native build or device required."""
import os
import pathlib
import re
import subprocess
import tempfile
import unittest


class SqliteJniSymbolsTest(unittest.TestCase):
    def test_declared_surface_and_missing_extra_negative_controls(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        expected = []
        for source, klass in (("SkeinSQLiteNative.kt", "SkeinSQLiteNativeImpl"),
                              ("RecoveryProofNative.kt", "RecoveryProofNative")):
            text = (root / "core/vault/src/main/kotlin/app/skein/core/vault/db" / source).read_text()
            for name in re.findall(r"^\s*external (?:override )?fun ([A-Za-z][A-Za-z0-9_]*)", text, re.M):
                expected.append(f"Java_app_skein_core_vault_db_{klass}_{name.replace('_', '_1')}")
        with tempfile.TemporaryDirectory(dir=root / "build/agent-logs") as directory:
            directory = pathlib.Path(directory)
            nm = directory / "fake-nm"
            nm.write_text("#!/usr/bin/env python3\nimport os\nprint(open(os.environ['SYMBOLS']).read())\n")
            nm.chmod(0o700)
            library = directory / "fixture.so"
            library.touch()
            cases = {
                "exact": (expected, 0),
                "missing_old": ([s for s in expected if not s.endswith("Impl_nativeOpen")], 1),
                "missing_new": ([s for s in expected if "RecoveryProofNative" not in s], 1),
                "extra_old": (expected + ["Java_app_skein_core_vault_db_SkeinSQLiteNativeImpl_surprise"], 1),
                "extra_new": (expected + ["Java_app_skein_core_vault_db_RecoveryProofNative_surprise"], 1),
                "extra_class": (expected + ["Java_unapproved_Native_method"], 1),
            }
            for name, (symbols, exit_code) in cases.items():
                with self.subTest(name=name):
                    listing = directory / name
                    listing.write_text("\n".join("000 T " + s for s in symbols))
                    run = subprocess.run(["bash", str(root / "tools/ci/sqlite-jni-symbols.sh"), str(library)],
                                         env={**os.environ, "NM": str(nm), "SYMBOLS": str(listing)},
                                         capture_output=True, text=True, check=False)
                    self.assertEqual(exit_code, run.returncode, run.stdout + run.stderr)


if __name__ == "__main__":
    unittest.main()
