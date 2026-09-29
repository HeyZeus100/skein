import hashlib
import importlib.util
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "verification_manifest", pathlib.Path(__file__).with_name("verification-manifest.py")
)
reviewer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reviewer)


class VerificationManifestTest(unittest.TestCase):
    def test_includes_nested_variant_records_without_rewriting_failed_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            records = {
                "feature/chat/build/test-results/testDebugUnitTest/TEST.xml": b'<testsuite><testcase><failure/></testcase></testsuite>',
                "feature/chat/build/test-results/roborazzi/debug/results-summary.json": b'{"changed":1}',
                "feature/chat/build/test-results/roborazzi/debug/results/one.json": b'{"type":"changed"}',
            }
            for name, data in records.items():
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            result = reviewer.manifest(root, "screenshots", "source")
            self.assertEqual(set(records), {f["path"] for f in result["files"]})
            for item in result["files"]:
                self.assertEqual(hashlib.sha256(records[item["path"]]).hexdigest(), item["sha256"])
                self.assertEqual(records[item["path"]], (root / item["path"]).read_bytes())
            unit = reviewer.manifest(root, "unit", "source")
            self.assertEqual(1, len(unit["files"]))
            self.assertEqual("source", unit["source_sha"])

    def test_instrumentation_retains_report_and_built_apk_hash_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            xml = root / "app/build/outputs/androidTest-results/connected/dev/TEST.xml"
            apk = root / "app/build/outputs/apk/dev/debug/app.apk"
            for path in (xml, apk):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"fixture")
            result = reviewer.manifest(root, "instrumentation", "source")
            self.assertEqual(str(xml.relative_to(root)), result["files"][0]["path"])
            self.assertEqual(hashlib.sha256(b"fixture").hexdigest(), result["built_apks"][0]["sha256"])
            self.assertIn("not independently measured", result["built_apk_attribution"])

    def test_missing_records_are_visible_as_empty_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual([], reviewer.manifest(pathlib.Path(directory), "unit", "source")["files"])


if __name__ == "__main__":
    unittest.main()
