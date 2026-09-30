import glob
import hashlib
import importlib.util
import pathlib
import re
import tempfile
import unittest

import yaml

spec = importlib.util.spec_from_file_location(
    "verification_manifest", pathlib.Path(__file__).with_name("verification-manifest.py")
)
reviewer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reviewer)


class VerificationManifestTest(unittest.TestCase):
    def instrumentation_fixture(self):
        xml = {
            "app/build/outputs/androidTest-results/connected/debug/flavors/dev/TEST.xml": b'<testsuite><testcase/></testsuite>',
            "core/vault/build/outputs/androidTest-results/connected/debug/flavors/dev/TEST.xml": b'<testsuite><testcase><failure/></testcase></testsuite>',
            "inference-service/build/outputs/androidTest-results/connected/debug/flavors/dev/TEST.xml": b'<testsuite><testcase><skipped/></testcase></testsuite>',
        }
        apks = {
            "app/build/outputs/apk/dev/debug/app.apk": b"app fixture",
            "app/build/outputs/apk/androidTest/dev/debug/app-test.apk": b"app test fixture",
            "core/vault/build/outputs/apk/androidTest/dev/debug/vault-test.apk": b"vault test fixture",
            "inference-service/build/outputs/apk/androidTest/dev/debug/service-test.apk": b"service test fixture",
        }
        reports = {
            f"{module}/build/reports/androidTests/connected/debug/flavors/dev/index.html": b"report fixture"
            for module in ("app", "core/vault", "inference-service")
        }
        metadata = {
            "build/instrumentation-review.json": b'{"passed":false}',
            "build/instrumentation-verification.json": b'{"source_sha":"fixture"}',
            "app/build/outputs/androidTest-results/connected/lane-logcat/logcat.txt": b"log fixture",
        }
        current = {**xml, **apks, **reports, **metadata}
        excluded = {
            "docs/ux/runs/previous/raw/" + name: data for name, data in current.items()
        }
        excluded.update({
            "feature/editor/build/outputs/androidTest-results/connected/TEST.xml": b"unrelated XML",
            "feature/editor/build/outputs/apk/debug/unrelated.apk": b"unrelated APK",
            "feature/editor/build/reports/androidTests/connected/index.html": b"unrelated report",
        })
        return current, excluded, set(xml), set(apks), set(xml) | set(reports) | set(metadata)

    def write_records(self, root, records):
        for name, data in records.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def test_instrumentation_excludes_historical_and_unrelated_module_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            current, excluded, xml, apks, _ = self.instrumentation_fixture()
            records = {**current, **excluded}
            self.write_records(root, records)
            result = reviewer.manifest(root, "instrumentation", "source")
            self.assertEqual(xml | {"build/instrumentation-review.json"}, {f["path"] for f in result["files"]})
            self.assertEqual(apks, {f["path"] for f in result["built_apks"]})
            for item in result["files"] + result["built_apks"]:
                self.assertEqual(hashlib.sha256(records[item["path"]]).hexdigest(), item["sha256"])
            for name, data in records.items():
                self.assertEqual(data, (root / name).read_bytes())

    def test_historical_evidence_cannot_fill_missing_instrumentation_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            _, excluded, _, _, _ = self.instrumentation_fixture()
            self.write_records(root, excluded)
            result = reviewer.manifest(root, "instrumentation", "source")
            self.assertEqual([], result["files"])
            self.assertEqual([], result["built_apks"])

    def test_instrumentation_upload_retains_current_reports_and_logcat_only(self):
        repository = pathlib.Path(__file__).resolve().parents[2]
        workflow = yaml.safe_load((repository / ".github/workflows/emulator.yml").read_text())
        upload = next(
            step for job in workflow["jobs"].values() for step in job["steps"]
            if step.get("with", {}).get("name") == "connected-test-reports"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            current, excluded, _, _, expected = self.instrumentation_fixture()
            self.write_records(root, {**current, **excluded})
            selected = {
                str(pathlib.Path(path).relative_to(root))
                for pattern in upload["with"]["path"].splitlines()
                for path in glob.glob(str(root / pattern), recursive=True)
                if pathlib.Path(path).is_file()
            }
            self.assertEqual(expected, selected)

    def test_includes_nested_variant_records_without_rewriting_failed_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            self.write_records(root, {"feature/chat/build.gradle.kts": b"// fixture project"})
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

    def test_unit_and_screenshots_only_inventory_current_module_build_roots(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            projects = {
                "build.gradle.kts": b"// root project",
                "app/build.gradle.kts": b"// Android project",
                "core/vault/build.gradle.kts": b"// nested project",
                "feature/chat/build.gradle.kts": b"// screenshot project",
                "build-logic/guards/build.gradle.kts": b"// composite project",
                "testing/build.gradle": b"// Groovy project",
            }
            xml = {
                f"{pathlib.PurePosixPath(project).parent}/build/test-results/testDebugUnitTest/TEST.xml".removeprefix("./"):
                    b'<testsuite><testcase><failure/></testcase><testcase><skipped/></testcase></testsuite>'
                for project in projects
            }
            screenshots = {
                "feature/chat/build/test-results/roborazzi/debug/results-summary.json": b'{"changed":1}',
                "feature/chat/build/test-results/roborazzi/debug/results/one.json": b'{"type":"changed"}',
            }
            current = {**xml, **screenshots}
            excluded = {}
            # Archive even the project declarations, so an adjacent build file
            # alone cannot make retained historical evidence look current.
            for prefix in ("docs/ux/runs/previous/raw/", "build/agent-logs/previous/",
                           "feature/chat/build/agent-logs/previous/"):
                excluded.update({prefix + name: data for name, data in {**projects, **current}.items()})
            excluded.update({
                "docs/build.gradle.kts": b"// archived declaration, not a project",
                "docs/build/test-results/test/TEST.xml": b"historical XML",
                "feature/not-a-project/build/test-results/test/TEST.xml": b"no project declaration",
            })
            records = {**projects, **current, **excluded}
            self.write_records(root, records)

            for lane, expected in (("unit", xml), ("screenshots", current)):
                with self.subTest(lane=lane):
                    result = reviewer.manifest(root, lane, "source")
                    self.assertEqual(set(expected), {item["path"] for item in result["files"]})
                    self.assertEqual([], result["built_apks"])
                    for item in result["files"]:
                        self.assertEqual(len(records[item["path"]]), item["bytes"])
                        self.assertEqual(hashlib.sha256(records[item["path"]]).hexdigest(), item["sha256"])
            for name, data in records.items():
                self.assertEqual(data, (root / name).read_bytes())

    def test_historical_unit_and_screenshot_evidence_cannot_fill_missing_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            records = {"feature/chat/build.gradle.kts": b"// current module without output"}
            for prefix in ("docs/ux/runs/previous/", "build/agent-logs/previous/"):
                records.update({
                    prefix + "feature/chat/build.gradle.kts": b"// archived project",
                    prefix + "feature/chat/build/test-results/test/TEST.xml": b"old XML",
                    prefix + "feature/chat/build/test-results/roborazzi/results.json": b'{"changed":0}',
                })
            self.write_records(root, records)
            for lane in ("unit", "screenshots"):
                with self.subTest(lane=lane):
                    self.assertEqual([], reviewer.manifest(root, lane, "source")["files"])

    def test_supported_layout_covers_every_current_declared_project(self):
        repository = pathlib.Path(__file__).resolve().parents[2]
        expected = {repository / "build"}
        for build in (repository, repository / "build-logic"):
            settings = (build / "settings.gradle.kts").read_text()
            expected.update(
                build.joinpath(*project.strip(":").split(":"), "build")
                for project in re.findall(r'"(:[\w:-]+)"', settings)
            )
        self.assertEqual(expected, set(reviewer.module_output_roots(repository)))

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
