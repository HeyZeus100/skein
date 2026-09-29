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
VALIDATION_FIXTURE = json.loads((FIXTURES / "rejection-validation.json").read_text())
INDEPENDENT_FIXTURE = json.loads((FIXTURES / "rejection-validation-20260928.json").read_text())
POLICY = dict(version="lexical-query-coverage-v1", minimum_query_coverage=.5, semantic_vector_policy="uncalibrated_bypass")


def reserved_summary(rows):
    """All-empty synthetic runner fixture: six positives miss, six absences reject."""
    positives = sum(row["answerable"] for row in rows)
    return dict(queries=len(rows), answerable_queries=positives, absence_queries=len(rows)-positives,
                labelled_evidence_spans=positives, covered_evidence_spans=0,
                rejected_absence_queries=len(rows)-positives, falsely_rejected_answerable_queries=positives,
                timed_samples=3*len(rows), recall_queries_scored=positives, ndcg_queries_scored=positives,
                scope_violations=0, provenance_violations=0, invalid_anchors=0, duplicate_results=0,
                nondeterministic_queries=0, macro_recall_at_8=0 if positives else None,
                macro_ndcg_at_8=0 if positives else None, mrr=0 if positives else None,
                absence_rejection_rate=1 if positives<len(rows) else None, false_rejection_rate=1 if positives else None,
                p50_ms=1, p95_ms=1, recall_gate=.75, ndcg_gate=.60,
                ranking_gate_status="FAIL" if positives else "NOT_APPLICABLE")


def reserved_report(name, policy, fixture=VALIDATION_FIXTURE, fixture_hash=runner.VALIDATION_FIXTURE_SHA256):
    rows = []
    for query in fixture["queries"]:
        positive = bool(query["relevant"])
        rows.append(dict(id=query["id"], category=query["category"], space_alias=query["persona_id"], answerable=positive,
                         deterministic=True, duplicate_results=0, scope_violation_chunk_ids=[],
                         provenance_violation_chunk_ids=[], invalid_anchor_chunk_ids=[], rejected=True,
                         labelled_spans=int(positive), covered_spans=0, grades=[],
                         recall_at_8=0 if positive else None, dcg_at_8=0 if positive else None,
                         ideal_dcg_at_8=7 if positive else None, ndcg_at_8=0 if positive else None,
                         reciprocal_rank=0 if positive else None,
                         runs=[dict(elapsed_ms=1, fingerprint=hashlib.sha256(b"").hexdigest(), results=[]) for _ in range(3)]))
    return dict(schema_version=1, status="MEASURED_DIAGNOSTIC", split=fixture["split"], blind_benchmark=False,
                fixture_sha256=fixture_hash, document_count=len(fixture["documents"]), chunk_count=len(fixture["documents"]),
                query_count=len(fixture["queries"]), validated_answer_spans=sum(bool(q["relevant"]) for q in fixture["queries"]), embedder=None, entity_extractor=None, vector_count=0,
                full_hybrid_gate="INELIGIBLE", warmups_per_query=1, repetitions=3, validation_status="FAIL",
                mode=dict(name=name, configuration=dict(evidence_policy=copy.deepcopy(policy)), queries=rows,
                          summary=reserved_summary(rows), categories={category:reserved_summary([r for r in rows if r["category"]==category])
                                                                     for category in {r["category"] for r in rows}}),
                returned_source_kinds={row["id"]:[[],[],[]] for row in rows})


def add_reserved_result(report):
    """Synthetic structural result, deliberately assigned zero gain; never measured gold."""
    value = report["rejection_validation"]["production_policy"]
    row = next(row for row in value["mode"]["queries"] if row["answerable"])
    item = dict(chunk_id=1, doc_id=VALIDATION_FIXTURE["documents"][0]["id"], doc_title="Runner contract",
                text="Synthetic runner boundary bytes", revision_hash="b"*64, byte_start=0, byte_end=10,
                score=1, recall_scores={"LEXICAL":2}, recalled_by=["LEXICAL"])
    for run in row["runs"]:
        run["results"] = [copy.deepcopy(item)]
    row.update(grades=[0], rejected=False)
    value["returned_source_kinds"][row["id"]] = [["NOTE"] for _ in range(3)]
    for summary in (value["mode"]["summary"], value["mode"]["categories"][row["category"]]):
        summary["falsely_rejected_answerable_queries"] -= 1
        summary["false_rejection_rate"] = summary["falsely_rejected_answerable_queries"] / summary["answerable_queries"]
    return row


def measured_report():
    """Synthetic runner contract, not a retrieval score or replacement gold."""
    rows = []
    for key, query in QUERIES.items():
        positive = any(e["grade"] == 3 for e in query["relevant"])
        rows.append(dict(id=key, category=query["category"], space_alias=query["persona_id"] or "default",
                         answerable=positive, rejected=True, labelled_spans=int(positive), covered_spans=0,
                         recall_at_8=0 if positive else None, ndcg_at_8=0 if positive else None,
                         reciprocal_rank=0 if positive else None, deterministic=True,
                         runs=[dict(elapsed_ms=1, results=[]) for _ in range(3)], scope_violation_chunk_ids=[],
                         provenance_violation_chunk_ids=[], invalid_anchor_chunk_ids=[]))
    summary = reserved_summary(rows)
    return dict(schema_version=1, status="MEASURED_DIAGNOSTIC", build_revision=HEAD,
                corpus_sha256=runner.sha256(FIXTURES / "corpus.json"),
                gold_sha256=runner.sha256(FIXTURES / "gold.json"), document_count=1000,
                overlay_document_count=72, query_count=76, full_hybrid_gate="INELIGIBLE",
                embedder=None, vector_count=0, repetitions=3, warmups_per_query_mode=1,
                evidence_policy=copy.deepcopy(POLICY), validation_policy_freeze=HEAD,
                independent_validation={
                    "production_policy":reserved_report("production_policy", POLICY, INDEPENDENT_FIXTURE, runner.INDEPENDENT_FIXTURE_SHA256),
                    "ungated_control":reserved_report("ungated_control", {"version":"disabled_control"}, INDEPENDENT_FIXTURE, runner.INDEPENDENT_FIXTURE_SHA256)},
                rejection_validation={"production_policy":reserved_report("production_policy", POLICY),
                                      "ungated_control":reserved_report("ungated_control", {"version":"disabled_control"})},
                modes=[dict(name=name, summary=copy.deepcopy(summary), queries=copy.deepcopy(rows),
                            categories={category: reserved_summary([r for r in rows if r["category"] == category])
                                        for category in {r["category"] for r in rows}})
                       for name in sorted(runner.MODES)])


class ReportContractTest(unittest.TestCase):
    def validate(self, report):
        return runner.validate_report(report, HEAD, runner.sha256(FIXTURES / "corpus.json"),
                                      runner.sha256(FIXTURES / "gold.json"), QUERIES, VALIDATION_FIXTURE,
                                      INDEPENDENT_FIXTURE, POLICY, HEAD)

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

    def test_reserved_failure_is_diagnostic_but_both_named_reports_and_policy_are_required(self):
        self.validate(measured_report())
        for field in ("evidence_policy", "rejection_validation"):
            report = measured_report()
            del report[field]
            with self.assertRaises(ValueError):
                self.validate(report)

        for key in ("production_policy", "ungated_control"):
            report = measured_report()
            del report["rejection_validation"][key]
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_frozen_policy_metadata_cannot_drift(self):
        for key, value in [("version","changed"),("minimum_query_coverage",.75),
                          ("minimum_query_coverage",float("nan")),("semantic_vector_policy","claimed_calibrated")]:
            report = measured_report()
            report["evidence_policy"][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate(report)

    def test_consistent_reserved_quality_pass_is_accepted(self):
        report = measured_report()
        labels = {query["id"]: query for query in VALIDATION_FIXTURE["queries"]}
        for value in report["rejection_validation"].values():
            for row in value["mode"]["queries"]:
                if not row["answerable"]:
                    continue
                item = dict(chunk_id=1, doc_id=labels[row["id"]]["relevant"][0]["doc_id"],
                            doc_title="Synthetic contract", text="Structural mock", revision_hash="b" * 64,
                            byte_start=0, byte_end=10, score=1, recall_scores={"LEXICAL": 2}, recalled_by=["LEXICAL"])
                for run in row["runs"]:
                    run["results"] = [copy.deepcopy(item)]
                row.update(covered_spans=1, recall_at_8=1, dcg_at_8=7, ndcg_at_8=1,
                           reciprocal_rank=1, grades=[3], rejected=False)
                value["returned_source_kinds"][row["id"]] = [["NOTE"] for _ in range(3)]
            for summary in [value["mode"]["summary"], *value["mode"]["categories"].values()]:
                if summary["answerable_queries"]:
                    summary.update(covered_evidence_spans=summary["answerable_queries"],
                                   falsely_rejected_answerable_queries=0, false_rejection_rate=0,
                                   macro_recall_at_8=1, macro_ndcg_at_8=1, mrr=1, ranking_gate_status="PASS")
            value["validation_status"] = "PASS"
        self.validate(report)

    def test_reserved_actual_result_signal_shape_and_repeat_arrays_are_checked(self):
        report = measured_report()
        add_reserved_result(report)
        self.validate(report)
        for mutation in (lambda item:item.update(doc_id="outside-fixture"),
                         lambda item:item.update(revision_hash=None),lambda item:item.update(byte_end=-1),
                         lambda item:item.update(score=float("inf")),lambda item:item.update(recall_scores={}),
                         lambda item:item.update(recalled_by=["VECTOR"],recall_scores={"VECTOR":1})):
            report = measured_report()
            row = add_reserved_result(report)
            for run in row["runs"]:
                mutation(run["results"][0])
            with self.assertRaises(ValueError):
                self.validate(report)
        report = measured_report()
        row = add_reserved_result(report)
        row["runs"][1]["results"][0]["text"] = "different repeated bytes"
        with self.assertRaises(ValueError):
            self.validate(report)
        report = measured_report()
        row = add_reserved_result(report)
        report["rejection_validation"]["production_policy"]["returned_source_kinds"][row["id"]][1]=["CHAT"]
        with self.assertRaises(ValueError):
            self.validate(report)

    def test_reserved_metadata_and_configuration_drift_fails(self):
        for key, value in [("schema_version",2),("status","PASS"),("fixture_sha256","changed"),
                          ("blind_benchmark",True),("document_count",7),("chunk_count",5),("query_count",11),
                          ("validated_answer_spans",5),("embedder","injected"),("entity_extractor","fake"),
                          ("vector_count",1),("full_hybrid_gate","PASS"),("warmups_per_query",0),("repetitions",2)]:
            report = measured_report()
            report["rejection_validation"]["production_policy"][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate(report)
        for name in ("production_policy","ungated_control"):
            report = measured_report()
            report["rejection_validation"][name]["mode"]["configuration"]["evidence_policy"]={}
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_reserved_missing_duplicate_or_changed_query_identity_fails(self):
        for mutation in (lambda rows:rows.pop(),lambda rows:rows.__setitem__(0,rows[1]),
                         lambda rows:rows[0].update(category="wrong"),lambda rows:rows[0].update(space_alias="work"),
                         lambda rows:rows[0].update(answerable=not rows[0]["answerable"])):
            report = measured_report()
            mutation(report["rejection_validation"]["production_policy"]["mode"]["queries"])
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_reserved_security_counts_and_actual_repetitions_cannot_be_hidden(self):
        for key, value in [("deterministic",False),("duplicate_results",1),("scope_violation_chunk_ids",[1]),
                          ("provenance_violation_chunk_ids",[1]),("invalid_anchor_chunk_ids",[1]),("rejected",False)]:
            report = measured_report()
            report["rejection_validation"]["production_policy"]["mode"]["queries"][0][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.validate(report)
        for mutation in (lambda row:row["runs"].pop(),lambda row:row["runs"][1].update(fingerprint="b"*64),
                         lambda row:row["runs"][1].update(elapsed_ms=float("nan")),
                         lambda row:row["runs"][1].update(results=[dict(chunk_id=1)])):
            report = measured_report()
            mutation(report["rejection_validation"]["production_policy"]["mode"]["queries"][0])
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_reserved_kinds_aggregates_and_outcome_status_are_checked(self):
        for mutation in (lambda v:v.update(returned_source_kinds={}),
                         lambda v:v["returned_source_kinds"].__setitem__(next(iter(v["returned_source_kinds"])),[["CHAT"],[],[]]),
                         lambda v:v["mode"]["summary"].update(falsely_rejected_answerable_queries=0),
                         lambda v:v["mode"]["summary"].update(p95_ms=500),
                         lambda v:v["mode"]["categories"].pop(next(iter(v["mode"]["categories"]))),
                         lambda v:v.update(validation_status="PASS")):
            report = measured_report()
            mutation(report["rejection_validation"]["production_policy"])
            with self.assertRaises(ValueError):
                self.validate(report)

    def test_reserved_ideal_dcg_cannot_drift_when_all_retrieved_gains_are_zero(self):
        report = measured_report()
        for value in report["rejection_validation"].values():
            for row in value["mode"]["queries"]:
                if row["answerable"]:
                    row["ideal_dcg_at_8"] = 1
        with self.assertRaisesRegex(ValueError, "reserved ideal DCG"):
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


class SourceOracleTest(unittest.TestCase):
    def retained_report(self):
        path = runner.REPOSITORY / "docs/eval/runs/2026-09-28-retrieval-repair-7601a20/retrieval/retrieval.json"
        return json.loads(path.read_text())["rejection_validation"]

    def test_original_raw_evidence_is_validated_without_upgrading_reserved_failure(self):
        retained = self.retained_report()
        reviewed = [runner.validate_sources(section, VALIDATION_FIXTURE) for section in retained.values()]
        self.assertEqual(sum(value["reviewed_source_occurrences"] for value in reviewed), 324)
        self.assertTrue(all(value["validation_status"] == "FAIL" for value in retained.values()))

    def test_tampered_actual_source_bytes_anchors_revision_or_gold_credit_fail(self):
        for field, value in (("text", "invented source"), ("byte_end", 2), ("revision_hash", "f" * 64),
                             ("doc_title", "invented title")):
            section = self.retained_report()["ungated_control"]
            row = next(row for row in section["mode"]["queries"] if row["runs"][0]["results"])
            for run in row["runs"]:
                run["results"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                runner.validate_sources(section, VALIDATION_FIXTURE)
        section = self.retained_report()["ungated_control"]
        row = next(row for row in section["mode"]["queries"] if row["covered_spans"] == 1)
        row["grades"] = [0] * len(row["grades"])
        with self.assertRaisesRegex(ValueError, "grades differ"):
            runner.validate_sources(section, VALIDATION_FIXTURE)


class IndependentValidationTest(unittest.TestCase):
    def test_freeze_preserves_source_spans_scope_and_inventory_without_executing_retrieval(self):
        self.assertEqual(runner.sha256(FIXTURES / "rejection-validation-20260928.json"), runner.INDEPENDENT_FIXTURE_SHA256)
        docs = {d["id"]: d for d in INDEPENDENT_FIXTURE["documents"]}
        queries = INDEPENDENT_FIXTURE["queries"]
        self.assertEqual(len(docs), 12)
        self.assertEqual(len({q["id"] for q in queries}), 24)
        self.assertEqual(sum(bool(q["relevant"]) for q in queries), 12)
        self.assertEqual({d["persona_id"] for d in docs.values()}, {"default", "work", "research"})
        for query in queries:
            for label in query["relevant"]:
                source = docs[label["doc_id"]]
                self.assertIn(label["evidence"], source["body_md"])
                self.assertIn(query["answer"], label["evidence"])
                self.assertEqual(source["persona_id"], query["persona_id"])
                self.assertNotIn(source["id"], query["forbidden_doc_ids"])
            self.assertEqual(query["answer"] is not None, bool(query["relevant"]))
            for forbidden in query["forbidden_doc_ids"]:
                self.assertNotEqual(docs[forbidden]["persona_id"], query["persona_id"])

    def test_missing_new_validation_or_changed_freeze_cannot_pass(self):
        for mutation in (lambda report: report.pop("independent_validation"),
                         lambda report: report.update(validation_policy_freeze="b" * 40),
                         lambda report: report["independent_validation"]["production_policy"].update(fixture_sha256="wrong")):
            report = measured_report()
            mutation(report)
            with self.assertRaises(ValueError):
                ReportContractTest().validate(report)

    def test_ablations_and_ungated_control_are_not_quality_acceptance_gates(self):
        report = measured_report()
        default = next(mode for mode in report["modes"] if mode["name"] == "lexical_graph_default")
        default["summary"]["ranking_gate_status"] = "PASS"
        report["rejection_validation"]["production_policy"]["validation_status"] = "PASS"
        report["independent_validation"]["production_policy"]["validation_status"] = "PASS"
        self.assertEqual(runner.quality_gates(report)["status"], "PASS")
        for field in ("rejection_validation", "independent_validation"):
            changed = copy.deepcopy(report)
            changed[field]["production_policy"]["validation_status"] = "FAIL"
            self.assertEqual(runner.quality_gates(changed)["status"], "FAIL")
        default["summary"]["rejected_absence_queries"] = 15
        self.assertEqual(runner.quality_gates(report)["gates"]["development_absence"], "FAIL")


class RunnerIntegrationTest(unittest.TestCase):
    """Exercise runner control flow with a fake device boundary; never calls adb."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve()
        self.output = self.repo / "build/diagnostic/run"
        fixtures = self.repo / "testing/src/main/resources/eval"
        fixtures.mkdir(parents=True)
        for name in ("corpus.json", "gold.json", "rejection-validation.json", "rejection-validation-20260928.json"):
            (fixtures / name).write_bytes((FIXTURES / name).read_bytes())
        frozen = self.repo / "tools/eval/retrieval-policy-freeze.json"
        frozen.parent.mkdir(parents=True)
        frozen.write_text(json.dumps(dict(schema_version=1, source_revision=HEAD, evidence_policy=POLICY,
                                         validation_fixture_sha256=runner.INDEPENDENT_FIXTURE_SHA256)))
        self.apk = self.repo / runner.APK_DIRECTORY / "vault-test.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"fake APK bytes for runner testing")
        self.report = measured_report()
        self.apk_digest = runner.sha256(self.apk)
        self.installed_digest = self.apk_digest
        self.installed_digest_responses = []
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
        elif argv == ["git", "merge-base", "--is-ancestor", HEAD, HEAD]:
            data = b""
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
                digest = self.installed_digest_responses.pop(0) if self.installed_digest_responses else self.installed_digest
                data = (digest + "  /data/app/base.apk\n").encode()
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

    def run_main(self, serial="emulator-5554", *flags):
        runner.main(["--serial", serial, "--expected-head", HEAD, "--output", str(self.output), *flags])

    def summary(self):
        return json.loads((self.output / "runner-summary.json").read_text())

    def test_quality_enforcement_fails_after_retaining_complete_measured_evidence(self):
        with self.assertRaisesRegex(ValueError, "quality gate FAIL"):
            self.run_main("emulator-5554", "--require-quality")
        self.assertTrue(self.summary()["complete"])
        self.assertEqual(self.summary()["quality_gate"]["status"], "FAIL")
        self.assertTrue((self.output / "retrieval.json").is_file())

    def test_hybrid_enforcement_cannot_upgrade_diagnostic_integrity(self):
        with self.assertRaisesRegex(ValueError, "full-hybrid gate INELIGIBLE"):
            self.run_main("emulator-5554", "--require-hybrid")
        self.assertTrue(self.summary()["complete"])
        self.assertEqual(self.summary()["full_hybrid_gate"], "INELIGIBLE")

    def test_missing_policy_freeze_fails_before_instrumentation(self):
        (self.repo / "tools/eval/retrieval-policy-freeze.json").unlink()
        with self.assertRaises(FileNotFoundError):
            self.run_main()
        self.instrument.assert_not_called()
        self.assertFalse(any(argv[0] == "adb" for argv in self.commands))

    def test_failed_quality_is_retained_as_diagnostic_not_upgraded_to_hybrid_pass(self):
        self.run_main()
        summary = self.summary()
        self.assertTrue(summary["complete"])
        self.assertEqual(summary["full_hybrid_gate"], "INELIGIBLE")
        self.assertEqual(summary["diagnostic_ranking_gates"], {name: "FAIL" for name in runner.MODES})
        self.assertEqual(summary["diagnostic_rejection_validation"], {name: "FAIL" for name in ("production_policy", "ungated_control")})
        self.assertEqual(summary["diagnostic_rejection_ranking_gates"], {name: "FAIL" for name in ("production_policy", "ungated_control")})
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

    def test_reserved_fixture_hash_cannot_be_changed(self):
        fixture = self.repo / "testing/src/main/resources/eval/rejection-validation.json"
        fixture.write_bytes(fixture.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "reserved validation fixture changed"):
            self.run_main()
        self.assertFalse(self.summary()["complete"])

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

    def test_installed_mismatch_is_terminal_even_when_report_is_partial(self):
        self.report_responses = [b'{"partial":']
        self.installed_digest_responses = ["b" * 64, self.apk_digest]
        with self.assertRaisesRegex(ValueError, "installed test APK differs"):
            self.run_main()
        self.instrument.assert_called_once()
        self.assertEqual(len(self.summary()["collection_attempts"]), 1)
        self.assertTrue(self.summary()["installed_apk_identity_mismatch"])
        self.assertEqual(self.summary()["installed_test_apk_sha256"], "b" * 64)
        self.assertEqual(self.installed_digest_responses, [self.apk_digest])
        self.assertFalse(any("wait-for-device" in argv for argv in self.commands))

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
    def test_failed_logcat_collection_retains_partial_bytes_and_stderr(self):
        for error in (subprocess.CalledProcessError(1, ["adb"], output=b"partial logcat\n", stderr=b"offline\n"),
                      subprocess.TimeoutExpired(["adb"], 60, output=b"partial logcat\n", stderr=b"offline\n")):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "logcat.txt"
                with patch.object(runner, "command", side_effect=error):
                    self.assertFalse(runner.collect(["adb"], ["logcat"], destination))
                self.assertEqual((Path(directory) / "logcat.txt.partial").read_bytes(), b"partial logcat\n")
                self.assertEqual((Path(directory) / "logcat.txt.unavailable.log").read_bytes(), b"offline\n")

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
            lines.append(line.strip().replace("${{ github.sha }}", HEAD)
                         .replace("${{ inputs.require_quality && '--require-quality' || '' }}", "--require-quality"))
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
            self.assertIn("--require-quality", argv)


if __name__ == "__main__":
    unittest.main()
