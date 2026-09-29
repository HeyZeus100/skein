import copy
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import Mock, patch

import run_real_retrieval as runner


HEAD = "a" * 40
FIXTURES = runner.REPOSITORY / "testing/src/main/resources/eval"
QUERIES = {row["id"]: row for row in json.loads((FIXTURES / "gold.json").read_text())["queries"]}


def measured_report():
    """Synthetic runner contract, not a retrieval score or replacement gold."""
    summary = dict(queries=76, answerable_queries=60, absence_queries=16,
                   recall_gate=0.75, ndcg_gate=0.60, scope_violations=0,
                   provenance_violations=0, invalid_anchors=0, nondeterministic_queries=0,
                   ranking_gate_status="FAIL")
    rows = [dict(id=key, category=query["category"], deterministic=True,
                 runs=[{}, {}, {}], scope_violation_chunk_ids=[],
                 provenance_violation_chunk_ids=[], invalid_anchor_chunk_ids=[])
            for key, query in QUERIES.items()]
    return dict(schema_version=1, status="MEASURED_DIAGNOSTIC", build_revision=HEAD,
                corpus_sha256=runner.sha256(FIXTURES / "corpus.json"),
                gold_sha256=runner.sha256(FIXTURES / "gold.json"), document_count=1000,
                overlay_document_count=72, query_count=76, full_hybrid_gate="INELIGIBLE",
                embedder=None, vector_count=0, repetitions=3, warmups_per_query_mode=1,
                modes=[dict(name=name, summary=copy.deepcopy(summary), queries=copy.deepcopy(rows))
                       for name in sorted(runner.MODES)])


class ReportContractTest(unittest.TestCase):
    def validate(self, report):
        return runner.validate_report(report, HEAD, runner.sha256(FIXTURES / "corpus.json"),
                                      runner.sha256(FIXTURES / "gold.json"), QUERIES)

    def test_failed_ranking_remains_valid_diagnostic_and_ineligible_hybrid(self):
        self.assertEqual(self.validate(measured_report()), {name: "FAIL" for name in runner.MODES})

    def test_rejects_report_or_provenance_configuration_drift(self):
        for field, value in [("status", "HARNESS_FAILED"), ("schema_version", 2),
                             ("build_revision", "b" * 40), ("corpus_sha256", "wrong"),
                             ("gold_sha256", "wrong"), ("full_hybrid_gate", "PASS"),
                             ("embedder", "injected"), ("vector_count", 1),
                             ("document_count", 999), ("overlay_document_count", 71),
                             ("query_count", 75), ("repetitions", 1), ("warmups_per_query_mode", 0)]:
            with self.subTest(field=field):
                report = measured_report()
                report[field] = value
                with self.assertRaises(ValueError):
                    self.validate(report)

    def test_requires_all_distinct_ablations(self):
        for modes in [[], ["lexical_only"] * 3, ["lexical_only", "graph_only", "unknown"]]:
            with self.subTest(modes=modes):
                report = measured_report()
                report["modes"] = [dict(report["modes"][0], name=name) for name in modes]
                with self.assertRaises(ValueError):
                    self.validate(report)

    def test_rejects_missing_duplicate_or_unrecognized_query(self):
        for mutation in (lambda rows: rows.pop(),
                         lambda rows: rows.append(rows[0]),
                         lambda rows: rows.__setitem__(0, rows[1]),
                         lambda rows: rows[0].update(id="unknown")):
            report = measured_report()
            mutation(report["modes"][0]["queries"])
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_rejects_changed_denominators_thresholds_and_hidden_integrity_failure(self):
        for key, value in [("queries", 75), ("answerable_queries", 59), ("absence_queries", 15),
                           ("recall_gate", 0.1), ("ndcg_gate", 0.1), ("scope_violations", 1),
                           ("provenance_violations", 1), ("invalid_anchors", 1),
                           ("nondeterministic_queries", 1), ("ranking_gate_status", "UNKNOWN")]:
            with self.subTest(key=key):
                report = measured_report()
                report["modes"][0]["summary"][key] = value
                with self.assertRaises(ValueError):
                    self.validate(report)

    def test_rejects_invalid_individual_rows_even_with_zero_summary_violations(self):
        for key, value in [("category", "invented"), ("deterministic", False), ("runs", [{}, {}]),
                           ("scope_violation_chunk_ids", ["source"]),
                           ("provenance_violation_chunk_ids", ["source"]),
                           ("invalid_anchor_chunk_ids", ["source"])]:
            with self.subTest(key=key):
                report = measured_report()
                report["modes"][0]["queries"][0][key] = value
                with self.assertRaises(ValueError):
                    self.validate(report)


class JunitEvidenceTest(unittest.TestCase):
    def test_only_one_executed_retrieval_case_is_accepted(self):
        case = f'<testcase classname="{runner.TEST_CLASS}" name="measured"/>'
        variants = [(case, True), ("", False), (case + case, False),
                    (case + '<testcase classname="Other"/>', False),
                    ('<testcase classname="Other"/>', False)]
        variants += [(case.replace("/>", f"><{tag}/></testcase>"), False)
                     for tag in ("failure", "error", "skipped")]
        for xml, valid in variants:
            with self.subTest(xml=xml), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "results.xml").write_text("<testsuite>" + xml + "</testsuite>")
                if valid:
                    runner.verify_junit(root)
                else:
                    with self.assertRaises(ValueError):
                        runner.verify_junit(root)

    def test_missing_xml_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            runner.verify_junit(Path(directory))


class RunnerIntegrationTest(unittest.TestCase):
    """Exercise runner control flow with a fake device boundary; never calls adb."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve()
        self.output = self.repo / "build/diagnostic/run"
        fixtures = self.repo / "testing/src/main/resources/eval"
        fixtures.mkdir(parents=True)
        for name in ("corpus.json", "gold.json"):
            (fixtures / name).write_bytes((FIXTURES / name).read_bytes())
        self.apk = self.repo / runner.APK_DIRECTORY / "vault-test.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"fake APK bytes for runner testing")
        self.report = measured_report()
        self.apk_digest = runner.sha256(self.apk)
        self.installed_digest = self.apk_digest
        self.qemu = b"1\n"
        self.prior_package = b""
        self.missing_report = False
        self.missing_installed_apk = False
        self.returncode = 0
        self.timeout = False
        self.git_head = HEAD
        self.untracked = b""
        self.case_tag = ""
        self.mutate_apk = False
        self.report_responses = []
        self.installed_path_failures = 0
        self.report_hash_mismatch = False
        self.commands = []
        for target, kwargs in [("REPOSITORY", {"new": self.repo}),
                               ("Path.cwd", {"return_value": self.repo}),
                               ("command", {"side_effect": self.command}),
                               ("run_logged", {"side_effect": self.run_logged})]:
            mock = patch("run_real_retrieval." + target, **kwargs)
            value = mock.start()
            self.addCleanup(mock.stop)
            if target == "run_logged":
                self.instrument = value

    def command(self, argv, **kwargs):
        self.commands.append(argv)
        if argv == ["git", "rev-parse", "HEAD"]:
            data = self.git_head.encode()
        elif argv == ["git", "diff", "--quiet", "HEAD", "--"]:
            data = b""
        elif argv == ["git", "ls-files", "--others", "--exclude-standard"]:
            data = self.untracked
        else:
            self.assertEqual(argv[:3], ["adb", "-s", "emulator-5554"])
            args = argv[3:]
            if args == ["shell", "getprop", "ro.kernel.qemu"]:
                data = self.qemu
            elif args == ["shell", "pm", "list", "packages", "-u", "--user", "0", runner.PACKAGE]:
                data = self.prior_package
            elif args == ["exec-out", "run-as", runner.PACKAGE, "cat", runner.REPORT_PATH]:
                if self.missing_report:
                    raise subprocess.CalledProcessError(1, argv, stderr=b"no report\n")
                data = self.report_responses.pop(0) if self.report_responses else json.dumps(self.report).encode()
                if isinstance(data, Exception):
                    raise data
            elif args == ["exec-out", "run-as", runner.PACKAGE, "sha256sum", runner.REPORT_PATH]:
                digest = "b" * 64 if self.report_hash_mismatch else hashlib.sha256(json.dumps(self.report).encode()).hexdigest()
                data = (digest + "  " + runner.REPORT_PATH + "\n").encode()
            elif args == ["shell", "pm", "path", runner.PACKAGE]:
                if self.missing_installed_apk or self.installed_path_failures:
                    self.installed_path_failures = max(0, self.installed_path_failures - 1)
                    raise subprocess.CalledProcessError(1, argv, output=b"", stderr=b"error: device offline\n")
                data = b"package:/data/app/~~fake/app.skein.core.vault.test-abc==/base.apk\n"
            elif args == ["shell", "sha256sum", "/data/app/~~fake/app.skein.core.vault.test-abc==/base.apk"]:
                data = (self.installed_digest + "  /data/app/base.apk\n").encode()
            elif args == ["logcat", "-d", "-v", "threadtime"]:
                data = b"fake logcat\n"
            elif args == ["shell", "am", "force-stop", runner.PACKAGE]:
                data = b""
            elif args == ["wait-for-device"]:
                data = b""
            else:
                self.fail(f"unexpected external operation: {argv}")
        return Mock(stdout=data, stderr=b"", returncode=0)

    def run_logged(self, argv, log, env):
        self.assertEqual(env["ANDROID_SERIAL"], "emulator-5554")
        log.write_text("instrumented runner output\n")
        xml = self.repo / runner.RESULT_DIRECTORY / "devDebug/TEST-retrieval.xml"
        xml.parent.mkdir(parents=True)
        xml.write_text(f'<testsuite><testcase classname="{runner.TEST_CLASS}">'
                       + self.case_tag + '</testcase></testsuite>')
        if self.mutate_apk:
            self.apk.write_bytes(b"changed during test run")
        if self.timeout:
            raise subprocess.TimeoutExpired(argv, 1500)
        return self.returncode

    def run_main(self, serial="emulator-5554"):
        runner.main(["--serial", serial, "--expected-head", HEAD, "--output", str(self.output)])

    def summary(self):
        return json.loads((self.output / "runner-summary.json").read_text())

    def test_failed_quality_is_retained_as_diagnostic_not_upgraded_to_hybrid_pass(self):
        self.run_main()
        summary = self.summary()
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["full_hybrid_gate"], "INELIGIBLE")
        self.assertEqual(summary["diagnostic_ranking_gates"], {name: "FAIL" for name in runner.MODES})
        self.assertEqual(summary["declared_build_revision"], HEAD)
        self.assertIn("not embedded APK attestation", summary["source_identity"])
        self.assertEqual(summary["installed_test_apk_sha256"], self.apk_digest)
        self.assertEqual(summary["post_instrumentation_test_apk_sha256"], self.apk_digest)
        self.assertEqual(summary["selected_collection_attempt"], 1)
        self.assertEqual(summary["report_sha256"], hashlib.sha256(json.dumps(self.report).encode()).hexdigest())
        argv = self.instrument.call_args.args[0]
        for arg in ["--no-daemon", "--max-workers=2", "-Pskein.retrievalEvaluation=true",
                    "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
                    "-Pandroid.testInstrumentationRunnerArguments.class=" + runner.TEST_CLASS,
                    "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.repetitions=3",
                    "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.eval=true",
                    "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.revision=" + HEAD]:
            self.assertIn(arg, argv)

    def test_refuses_physical_or_ambiguous_serial_before_any_command(self):
        for serial in ("52241FDKD000LV", "emulator-5554\nemulator-5556", ""):
            with self.subTest(serial=serial), self.assertRaisesRegex(ValueError, "explicit emulator serial"):
                self.run_main(serial)
        self.assertEqual(self.commands, [])
        self.instrument.assert_not_called()

    def test_refuses_non_emulator_property_before_any_mutation_or_collection(self):
        self.qemu = b"0\n"
        with self.assertRaisesRegex(ValueError, "requires an emulator"):
            self.run_main()
        self.instrument.assert_not_called()
        self.assertEqual([argv[3:] for argv in self.commands if argv[0] == "adb"],
                         [["shell", "getprop", "ro.kernel.qemu"]])

    def test_prior_test_package_or_retained_data_is_not_modified_or_collected(self):
        self.prior_package = b"package:app.skein.core.vault.test\n"
        with self.assertRaisesRegex(ValueError, "fresh emulator"):
            self.run_main()
        self.instrument.assert_not_called()
        self.assertFalse(self.summary()["complete"])
        self.assertFalse(any("logcat" in argv or "run-as" in argv for argv in self.commands))

    def test_source_mismatch_fails_before_adb(self):
        self.git_head = "b" * 40
        with self.assertRaisesRegex(ValueError, "reviewed revision"):
            self.run_main()
        self.assertFalse(any(argv[0] == "adb" for argv in self.commands))

    def test_untracked_source_fails_before_adb(self):
        self.untracked = b"core/vault/src/main/Unreviewed.kt\n"
        with self.assertRaisesRegex(ValueError, "untracked files"):
            self.run_main()
        self.assertFalse(any(argv[0] == "adb" for argv in self.commands))

    def test_existing_output_is_preserved_and_rejected(self):
        self.output.mkdir(parents=True)
        report = self.output / "retrieval.json"
        report.write_text("earlier evidence")
        with self.assertRaises(FileExistsError):
            self.run_main()
        self.assertEqual(report.read_text(), "earlier evidence")
        self.instrument.assert_not_called()

    def test_stale_agp_xml_is_preserved_and_rejected(self):
        xml = self.repo / runner.RESULT_DIRECTORY / "earlier.xml"
        xml.parent.mkdir(parents=True)
        xml.write_text("earlier evidence")
        with self.assertRaisesRegex(ValueError, "prior instrumentation XML"):
            self.run_main()
        self.assertEqual(xml.read_text(), "earlier evidence")
        self.instrument.assert_not_called()

    def test_gradle_failure_retains_raw_harness_failure_and_real_exit(self):
        self.returncode = 7
        self.report = dict(status="HARNESS_FAILED", phase="ingest", failure_type="AssertionError")
        with self.assertRaisesRegex(ValueError, "instrumentation failed"):
            self.run_main()
        self.assertEqual(json.loads((self.output / "retrieval.json").read_text()), self.report)
        self.assertEqual(self.summary()["gradle_exit_code"], 7)
        self.assertFalse(self.summary()["complete"])
        self.assertTrue((self.output / "logcat.txt").exists())

    def test_timeout_stops_only_selected_test_package_and_collects_failure_evidence(self):
        self.timeout = True
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_main()
        self.assertTrue(self.summary()["host_timeout"])
        self.assertFalse(self.summary()["complete"])
        self.assertTrue((self.output / "retrieval.json").exists())
        self.assertTrue((self.output / "logcat.txt").exists())
        self.assertIn(["adb", "-s", "emulator-5554", "shell", "am", "force-stop", runner.PACKAGE], self.commands)
        self.instrument.assert_called_once()

    def test_unavailable_report_preserves_failure_and_missing_marker(self):
        self.returncode = 1
        self.missing_report = True
        with self.assertRaisesRegex(ValueError, "instrumentation failed"):
            self.run_main()
        self.assertFalse(self.summary()["report_collected"])
        self.assertEqual((self.output / "retrieval.json.unavailable.log").read_bytes(), b"no report\n")
        self.assertFalse((self.output / "retrieval.json").exists())

    def test_different_installed_apk_is_rejected(self):
        self.installed_digest = "b" * 64
        with self.assertRaisesRegex(ValueError, "installed test APK"):
            self.run_main()
        self.assertFalse(self.summary()["complete"])
        self.assertEqual(len(self.summary()["collection_attempts"]), 1)

    def test_removed_test_package_cannot_pass(self):
        self.missing_installed_apk = True
        with self.assertRaisesRegex(ValueError, "installed test APK"):
            self.run_main()
        self.assertEqual(self.summary()["installed_apk_verification_error"], "CalledProcessError")

    def test_apk_rebuilt_during_instrumentation_is_rejected(self):
        self.mutate_apk = True
        with self.assertRaisesRegex(ValueError, "changed during instrumentation"):
            self.run_main()
        self.assertEqual(self.summary()["post_instrumentation_test_apk_sha256"], runner.sha256(self.apk))

    def test_zero_exit_truncated_json_recovers_without_repeating_instrumentation(self):
        self.report_responses = [b'{"schema_version":1,']
        self.run_main()
        self.instrument.assert_called_once()
        self.assertEqual(self.summary()["selected_collection_attempt"], 2)
        first = self.output / "collection-attempts/01"
        self.assertEqual((first / "report.stdout").read_bytes(), b'{"schema_version":1,')
        self.assertEqual(json.loads((first / "report.command.json").read_text())["exit_code"], 0)
        self.assertEqual(self.summary()["collection_attempts"][0]["errors"][0]["type"], "JSONDecodeError")
        self.assertEqual(json.loads((self.output / "retrieval.json").read_text()), self.report)

    def test_transport_disconnect_retains_partial_stdout_and_verbatim_stderr(self):
        self.report_responses = [subprocess.CalledProcessError(1, ["adb"], output=b'{"partial":',
                                                              stderr=b"error: transport closed\n")]
        self.installed_path_failures = 1
        self.run_main()
        self.instrument.assert_called_once()
        self.assertEqual(self.summary()["selected_collection_attempt"], 2)
        first = self.output / "collection-attempts/01"
        self.assertEqual((first / "report.stdout").read_bytes(), b'{"partial":')
        self.assertEqual((first / "report.stderr").read_bytes(), b"error: transport closed\n")
        self.assertEqual((first / "installed-path.stderr").read_bytes(), b"error: device offline\n")
        self.assertEqual(json.loads((first / "report.command.json").read_text())["exit_code"], 1)
        self.assertTrue((self.output / "collection-attempts/02/reconnect.command.json").exists())

    def test_persistent_truncated_json_fails_after_exactly_three_preserved_attempts(self):
        self.report_responses = [b'{"incomplete":'] * 3
        with self.assertRaisesRegex(ValueError, "complete retrieval report"):
            self.run_main()
        self.instrument.assert_called_once()
        self.assertEqual(len(self.summary()["collection_attempts"]), 3)
        self.assertIsNone(self.summary()["selected_collection_attempt"])
        self.assertFalse((self.output / "retrieval.json").exists())
        for number in range(1, 4):
            self.assertEqual((self.output / f"collection-attempts/{number:02d}/report.stdout").read_bytes(), b'{"incomplete":')
        self.assertEqual(self.summary()["post_instrumentation_test_apk_sha256"], self.apk_digest)

    def test_complete_json_with_wrong_device_digest_cannot_pass(self):
        self.report_hash_mismatch = True
        with self.assertRaisesRegex(ValueError, "complete retrieval report"):
            self.run_main()
        self.instrument.assert_called_once()
        self.assertFalse(self.summary()["report_collected"])
        self.assertEqual(len(self.summary()["collection_attempts"]), 3)

    def test_persistent_installed_apk_transport_failure_retains_all_errors(self):
        self.missing_installed_apk = True
        with self.assertRaisesRegex(ValueError, "installed test APK"):
            self.run_main()
        self.instrument.assert_called_once()
        self.assertTrue(self.summary()["report_collected"])
        self.assertIsNone(self.summary()["selected_collection_attempt"])
        for number in range(1, 4):
            self.assertEqual((self.output / f"collection-attempts/{number:02d}/installed-path.stderr").read_bytes(),
                             b"error: device offline\n")

    def test_skipped_real_xml_cannot_be_overridden_by_gradle_success(self):
        self.case_tag = "<skipped/>"
        with self.assertRaisesRegex(ValueError, "failed or was skipped"):
            self.run_main()
        self.assertFalse(self.summary()["complete"])


class ProcessAndWorkflowTest(unittest.TestCase):
    def test_real_host_timeout_preserves_output(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "timeout.log"
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.run_logged([sys.executable, "-c", "import time; print('partial', flush=True); time.sleep(30)"],
                                  log, os.environ.copy(), timeout=0.5)
            self.assertEqual(log.read_bytes(), b"partial\n\nHOST_INSTRUMENTATION_TIMEOUT\n")

    def test_timeout_kills_owned_descendants_even_after_wrapper_exits(self):
        process = Mock(pid=23456)
        process.wait.side_effect = [subprocess.TimeoutExpired("gradle", 1500), -15, -15]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(runner.subprocess, "Popen") as popen, patch.object(runner.os, "killpg") as kill:
            popen.return_value.__enter__.return_value = process
            with self.assertRaises(subprocess.TimeoutExpired):
                runner.run_logged(["fake-gradle"], Path(directory) / "log", {})
            self.assertEqual(kill.call_args_list[0].args, (23456, signal.SIGTERM))
            self.assertEqual(kill.call_args_list[1].args, (23456, signal.SIGKILL))
            self.assertTrue(popen.call_args.kwargs["start_new_session"])

    def test_workflow_discovery_guard_rejects_zero_tests(self):
        workflow = (runner.REPOSITORY / ".github/workflows/retrieval-diagnostic.yml").read_text()
        code = textwrap.dedent(workflow.split("python3 - <<'PY'\n", 1)[1].split("          PY", 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "tools/eval").mkdir(parents=True)
            result = subprocess.run([sys.executable, "-c", code], cwd=directory, capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"Discovered 0 retrieval runner tests", result.stdout)
            self.assertIn(b"No retrieval runner tests discovered", result.stderr)

    def test_workflow_single_command_preserves_selected_serial_and_exact_sha(self):
        workflow = (runner.REPOSITORY / ".github/workflows/retrieval-diagnostic.yml").read_text()
        block = workflow.split("          script: |\n", 1)[1]
        lines = []
        for line in block.splitlines():
            if not line.startswith("            "):
                break
            lines.append(line.strip().replace("${{ github.sha }}", HEAD))
        self.assertEqual(len(lines), 1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text("#!/bin/sh\nprintf 'List of devices attached\\nemulator-5554\\tdevice\\n'\n")
            adb.chmod(0o700)
            python = root / "python3"
            python.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$RETRIEVAL_TEST_ARGS"\n')
            python.chmod(0o700)
            arguments = root / "arguments"
            subprocess.run(["/bin/sh", "-c", lines[0]], check=True, cwd=root, timeout=10,
                           env={"PATH": f"{root}:/usr/bin:/bin", "RETRIEVAL_TEST_ARGS": str(arguments)})
            argv = arguments.read_text().splitlines()
            self.assertEqual(argv[argv.index("--serial") + 1], "emulator-5554")
            self.assertEqual(argv[argv.index("--expected-head") + 1], HEAD)
            self.assertEqual(argv[argv.index("--output") + 1], "build/retrieval-diagnostic/run")


if __name__ == "__main__":
    unittest.main()
