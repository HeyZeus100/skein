#!/usr/bin/env python3
"""Opt-in emulator-only real SQLite retrieval diagnostic; never a hybrid quality pass."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import xml.etree.ElementTree as ET

from retrieval_source_review import validate_sources

PACKAGE = "app.skein.core.vault.test"
TEST_CLASS = "app.skein.core.vault.eval.RealRetrievalEvaluationTest"
REPORT_PATH = "files/artifacts/eval/retrieval.json"
MODES = {"lexical_only", "graph_only", "lexical_graph_default"}
RESULT_DIRECTORY = "core/vault/build/outputs/androidTest-results/connected"
APK_DIRECTORY = "core/vault/build/outputs/apk/androidTest/dev/debug"
REPOSITORY = Path(__file__).resolve().parents[2]
VALIDATION_FIXTURE_SHA256 = "bf162254094103310102e28ad90b4c945bf24dd7f8f9a706039b2fe3a826b57c"
INDEPENDENT_FIXTURE_SHA256 = "4f79b2ddcf42dedd6b7f83c10402855f683fcebc3045d9e4668f6da2951759e8"
FRESH40_FIXTURE = "testing/src/main/resources/eval/rejection-validation-20260930.json"
FRESH40_SHA256 = "653b79ce9ab1c36776b5876d1b5d687a1d335f811f388b832fd0d9d258952674"
CANDIDATE_SOURCE_ROOTS = ("core/rag/src/main", "core/model/src/main",
                          "core/vault/src/main/kotlin/app/skein/core/vault/index",
                          "core/vault/src/main/kotlin/app/skein/core/vault/repository")
EVALUATOR_SOURCES = (
    "testing/src/main/kotlin/app/skein/testing/eval/FreshContextualValidationFixture.kt",
    "testing/src/main/kotlin/app/skein/testing/eval/ExpandedRetrievalMetrics.kt",
    "testing/src/main/kotlin/app/skein/testing/eval/FreshContextualValidationReport.kt",
    "testing/src/test/kotlin/app/skein/testing/eval/FreshContextualValidationFixtureTest.kt",
    "testing/src/test/kotlin/app/skein/testing/eval/ExpandedRetrievalMetricsTest.kt",
    "testing/src/test/kotlin/app/skein/testing/eval/FreshContextualValidationReportTest.kt",
    "core/vault/src/retrievalEvaluation/kotlin/app/skein/core/vault/eval/FreshContextualValidationEvaluation.kt",
    "core/vault/src/retrievalEvaluation/kotlin/app/skein/core/vault/eval/RealRetrievalEvaluationTest.kt",
    "docs/eval/FRESH_CONTEXTUAL_VALIDATION_20260930.md",
    "tools/eval/run_real_retrieval.py", "tools/eval/test_run_real_retrieval.py",
    ".github/workflows/retrieval-diagnostic.yml", FRESH40_FIXTURE,
)
# Historical policy remains useful to validate immutable old artifacts. Current
# runs take their exact policy metadata from the coordinator's frozen manifest.
MINIMUM_QUERY_COVERAGE = .5
POLICY_SOURCES = {"core/rag/src/main/kotlin/app/skein/core/rag/retrieval/LexicalEvidenceGate.kt",
                  "core/rag/src/main/kotlin/app/skein/core/rag/retrieval/RequestedValue.kt"}
HISTORICAL_POLICY = dict(version="lexical-query-coverage-v1", minimum_query_coverage=.5,
                         semantic_vector_policy="uncalibrated_bypass")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(argv, timeout=60, **kwargs):
    return subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout, check=True, **kwargs)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def equal_metric(actual, expected, label):
    require((actual is None and expected is None) or
            (finite_number(actual) and finite_number(expected) and math.isclose(actual, expected, abs_tol=1e-10)),
            "reserved validation metric mismatch: " + label)


def validate_metric_summary(summary, rows):
    require(summary.get("recall_gate") == .75 and summary.get("ndcg_gate") == .60,
            "reserved validation quality thresholds changed")
    answerable = [row for row in rows if row["answerable"]]
    absence = [row for row in rows if not row["answerable"]]
    samples = sorted(run["elapsed_ms"] for row in rows for run in row["runs"])
    counts = dict(queries=len(rows), answerable_queries=len(answerable), absence_queries=len(absence),
                  labelled_evidence_spans=sum(row["labelled_spans"] for row in rows),
                  covered_evidence_spans=sum(row["covered_spans"] for row in rows),
                  rejected_absence_queries=sum(row["rejected"] for row in absence),
                  falsely_rejected_answerable_queries=sum(row["rejected"] for row in answerable),
                  timed_samples=len(samples), recall_queries_scored=len(answerable), ndcg_queries_scored=len(answerable),
                  scope_violations=0, provenance_violations=0, invalid_anchors=0, duplicate_results=0,
                  nondeterministic_queries=0)
    for key, value in counts.items():
        require(type(summary.get(key)) is int and summary[key] == value, "reserved validation count mismatch: " + key)
    mean = lambda values: sum(values) / len(values) if values else None
    metrics = dict(macro_recall_at_8=mean([row["recall_at_8"] for row in answerable]),
                   macro_ndcg_at_8=mean([row["ndcg_at_8"] for row in answerable]),
                   mrr=mean([row["reciprocal_rank"] for row in answerable]),
                   absence_rejection_rate=mean([row["rejected"] for row in absence]),
                   false_rejection_rate=mean([row["rejected"] for row in answerable]),
                   p50_ms=samples[math.ceil(len(samples) * .50) - 1],
                   p95_ms=samples[math.ceil(len(samples) * .95) - 1], recall_gate=.75, ndcg_gate=.60)
    for key, value in metrics.items():
        equal_metric(summary.get(key), value, key)
    expected = "NOT_APPLICABLE" if not answerable else (
        "PASS" if metrics["macro_recall_at_8"] >= .75 and metrics["macro_ndcg_at_8"] >= .60 else "FAIL")
    require(summary.get("ranking_gate_status") == expected, "reserved validation ranking status disagrees with rows")


def validate_rejection_report(report, name, policy, fixture, fixture_hash=VALIDATION_FIXTURE_SHA256):
    expected = dict(schema_version=1, status="MEASURED_DIAGNOSTIC", split=fixture["split"],
                    blind_benchmark=False, fixture_sha256=fixture_hash,
                    document_count=len(fixture["documents"]), chunk_count=len(fixture["documents"]),
                    query_count=len(fixture["queries"]),
                    validated_answer_spans=sum(bool(q["relevant"]) for q in fixture["queries"]),
                    embedder=None, entity_extractor=None, vector_count=0, full_hybrid_gate="INELIGIBLE",
                    warmups_per_query=1, repetitions=3)
    for key, value in expected.items():
        require(key in report and type(report[key]) is type(value) and report[key] == value,
                "reserved validation metadata mismatch: " + key)
    mode = report.get("mode", {})
    require(mode.get("name") == name and mode.get("configuration", {}).get("evidence_policy") == policy,
            "reserved validation policy configuration mismatch")
    labels = {query["id"]: query for query in fixture["queries"]}
    documents = {document["id"] for document in fixture["documents"]}
    rows = mode.get("queries", [])
    require(len(rows) == len(labels) and {row["id"] for row in rows} == set(labels), "reserved validation queries missing or duplicated")
    kinds = report.get("returned_source_kinds", {})
    require(set(kinds) == set(labels), "reserved validation source-kind query inventory mismatch")
    for row in rows:
        label = labels[row["id"]]
        answerable = bool(label["relevant"])
        require(row.get("category") == label["category"] and row.get("space_alias") == label["persona_id"]
                and row.get("answerable") is answerable, "reserved validation query identity mismatch")
        require(row.get("deterministic") is True and type(row.get("duplicate_results")) is int
                and row["duplicate_results"] == 0,
                "reserved validation determinism/duplicate failure")
        for key in ("scope_violation_chunk_ids", "provenance_violation_chunk_ids", "invalid_anchor_chunk_ids"):
            require(row.get(key) == [], "reserved validation integrity/security failure: " + key)
        runs = row.get("runs", [])
        source_kinds = kinds[row["id"]]
        require(len(runs) == 3 and len(source_kinds) == 3, "reserved validation repetition count mismatch")
        for run, run_kinds in zip(runs, source_kinds):
            require(finite_number(run.get("elapsed_ms")) and run["elapsed_ms"] >= 0,
                    "reserved validation duration invalid")
            results = run.get("results", [])
            require(len(results) <= 8 and len({item["chunk_id"] for item in results}) == len(results),
                    "reserved validation duplicate/excess result")
            require(run_kinds == ["NOTE"] * len(results), "reserved validation returned non-source or missing kind")
            require(results == runs[0].get("results") and run.get("fingerprint") == runs[0].get("fingerprint")
                    and isinstance(run.get("fingerprint"), str)
                    and re.fullmatch(r"[a-f0-9]{64}", run.get("fingerprint", "")),
                    "reserved validation raw arrays are nondeterministic")
            for item in results:
                require(type(item.get("chunk_id")) is int and item["chunk_id"] > 0 and item.get("doc_id") in documents,
                        "reserved validation result identity invalid")
                source = next(d for d in fixture["documents"] if d["id"] == item["doc_id"])
                require(source["persona_id"] == label["persona_id"]
                        and item["doc_id"] not in label.get("forbidden_doc_ids", []),
                        "reserved validation returned forbidden Space source")
                require(isinstance(item.get("doc_title"), str) and isinstance(item.get("text"), str)
                        and isinstance(item.get("revision_hash"), str)
                        and re.fullmatch(r"[a-f0-9]{64}", item.get("revision_hash", ""))
                        and type(item.get("byte_start")) is int and type(item.get("byte_end")) is int
                        and 0 <= item["byte_start"] < item["byte_end"], "reserved validation anchor invalid")
                channels, scores = item.get("recalled_by", []), item.get("recall_scores", {})
                require(channels and len(channels) == len(set(channels)) and set(channels) <= {"LEXICAL", "GRAPH"}
                        and set(scores) == set(channels) and all(finite_number(score) for score in scores.values())
                        and finite_number(item.get("score")), "reserved validation raw recall signals invalid")
        require(row.get("rejected") is (not runs[0]["results"]), "reserved validation rejection disagrees with results")
        require(type(row.get("labelled_spans")) is int and row["labelled_spans"] == int(answerable)
                and type(row.get("covered_spans")) is int and 0 <= row["covered_spans"] <= int(answerable),
                "reserved validation evidence counts invalid")
        grades = row.get("grades", [])
        require(len(grades) == len(runs[0]["results"]) and all(type(g) is int and 0 <= g <= 3 for g in grades),
                "reserved validation grades invalid")
        require(row["covered_spans"] == int(3 in grades), "reserved validation grade/evidence coverage mismatch")
        equal_metric(row.get("recall_at_8"), row["covered_spans"] if answerable else None, "query recall")
        dcg = sum((2 ** grade - 1) / math.log2(i + 2) for i, grade in enumerate(grades)) if answerable else None
        equal_metric(row.get("dcg_at_8"), dcg, "query DCG")
        ideal = row.get("ideal_dcg_at_8")
        require((finite_number(ideal) and ideal > 0) if answerable else ideal is None, "reserved validation ideal DCG invalid")
        # Six one-chunk source documents and one grade-3 answer per positive
        # query pin the reserved ideal gain independently of returned results.
        equal_metric(ideal, 7 if answerable else None, "reserved ideal DCG")
        equal_metric(row.get("ndcg_at_8"), dcg / ideal if answerable else None, "query nDCG")
        equal_metric(row.get("reciprocal_rank"),
                     next((1 / (i + 1) for i, grade in enumerate(grades) if grade == 3), 0) if answerable else None,
                     "query reciprocal rank")
    validate_metric_summary(mode["summary"], rows)
    categories = {label["category"] for label in labels.values()}
    require(set(mode.get("categories", {})) == categories, "reserved validation category inventory mismatch")
    for category in categories:
        validate_metric_summary(mode["categories"][category], [row for row in rows if row["category"] == category])
    passed = all(row["covered_spans"] == row["labelled_spans"] if row["answerable"] else row["rejected"] for row in rows)
    require(report.get("validation_status") == ("PASS" if passed else "FAIL"),
            "reserved validation status disagrees with measured rows")


def validate_report(report, head, corpus_hash, gold_hash, queries, validation_fixture,
                    independent_fixture=None, expected_policy=HISTORICAL_POLICY, policy_freeze=None,
                    experimental_policy=None):
    require(report.get("schema_version") == 1, "unexpected retrieval report schema")
    require(report.get("status") == "MEASURED_DIAGNOSTIC", "retrieval did not produce a measured diagnostic")
    require(report.get("build_revision") == head, "retrieval report source revision mismatch")
    require(report.get("corpus_sha256") == corpus_hash and report.get("gold_sha256") == gold_hash,
            "retrieval report fixture hashes mismatch")
    require(report.get("document_count") == 1000 and report.get("overlay_document_count") == 72
            and report.get("query_count") == 76, "retrieval corpus/query counts mismatch")
    require(report.get("full_hybrid_gate") == "INELIGIBLE" and report.get("embedder") is None
            and report.get("vector_count") == 0, "unexpected hybrid eligibility or vector configuration")
    require(report.get("repetitions") == 3 and report.get("warmups_per_query_mode") == 1,
            "unexpected retrieval repetition configuration")
    policy = report.get("evidence_policy", {})
    require(policy == expected_policy, "missing or invalid evidence policy")
    names = {"production_policy", "ungated_control"}
    if experimental_policy is not None:
        names.add("experimental_candidate")
        require(report.get("experimental_policy") == experimental_policy, "experimental policy metadata mismatch")
    validations = report.get("rejection_validation", {})
    require(set(validations) == names, "missing reserved validation configuration")
    validate_rejection_report(validations["production_policy"], "production_policy", policy, validation_fixture)
    validate_rejection_report(validations["ungated_control"], "ungated_control", {"version": "disabled_control"}, validation_fixture)
    if experimental_policy is not None:
        validate_rejection_report(validations["experimental_candidate"], "experimental_candidate", experimental_policy,
                                  validation_fixture)
    if independent_fixture is not None:
        require(report.get("validation_policy_freeze") == policy_freeze, "validation policy freeze mismatch")
        fresh = report.get("independent_validation", {})
        require(set(fresh) == names, "missing independent validation configuration")
        validate_rejection_report(fresh["production_policy"], "production_policy", policy,
                                  independent_fixture, INDEPENDENT_FIXTURE_SHA256)
        validate_rejection_report(fresh["ungated_control"], "ungated_control", {"version": "disabled_control"},
                                  independent_fixture, INDEPENDENT_FIXTURE_SHA256)
        if experimental_policy is not None:
            validate_rejection_report(fresh["experimental_candidate"], "experimental_candidate", experimental_policy,
                                      independent_fixture, INDEPENDENT_FIXTURE_SHA256)
    modes = report.get("modes", [])
    require(len(modes) == 3 and {mode["name"] for mode in modes} == MODES, "missing retrieval ablation")
    statuses = {}
    for mode in modes:
        summary = mode["summary"]
        require(summary.get("queries") == 76 and summary.get("answerable_queries") == 60
                and summary.get("absence_queries") == 16, "retrieval metric denominators mismatch")
        require(len(mode.get("queries", [])) == 76
                and {row["id"] for row in mode["queries"]} == set(queries), "missing or duplicate query rows")
        for row in mode["queries"]:
            require(row.get("category") == queries[row["id"]]["category"], "query category mismatch")
            require(row.get("deterministic") is True and len(row.get("runs", [])) == 3,
                    "missing or nondeterministic retrieval repetitions")
            for key in ("scope_violation_chunk_ids", "provenance_violation_chunk_ids", "invalid_anchor_chunk_ids"):
                require(row.get(key) == [], "retrieval query integrity/security gate failed: " + key)
        require(summary.get("recall_gate") == 0.75 and summary.get("ndcg_gate") == 0.60,
                "retrieval quality thresholds changed")
        for key in ("scope_violations", "provenance_violations", "invalid_anchors", "nondeterministic_queries"):
            require(summary.get(key) == 0, "retrieval integrity/security gate failed: " + key)
        rows = mode["queries"]
        for row in rows:
            label = queries[row["id"]]
            answerable = any(evidence["grade"] == 3 for evidence in label["relevant"])
            require(row.get("answerable") is answerable
                    and row.get("space_alias") == (label.get("persona_id") or "default"), "development query identity mismatch")
            require(type(row.get("rejected")) is bool and type(row.get("labelled_spans")) is int
                    and row["labelled_spans"] == int(answerable)
                    and type(row.get("covered_spans")) is int and 0 <= row["covered_spans"] <= int(answerable),
                    "development evidence counts invalid")
            require(type(row.get("duplicate_results")) is int and row["duplicate_results"] == 0,
                    "development duplicate result")
            for run in row["runs"]:
                require(finite_number(run.get("elapsed_ms")) and run["elapsed_ms"] >= 0
                        and isinstance(run.get("results"), list), "development sample invalid")
                require(run["results"] == row["runs"][0]["results"]
                        and run.get("fingerprint") == row["runs"][0].get("fingerprint")
                        and isinstance(run.get("fingerprint"), str)
                        and re.fullmatch(r"[a-f0-9]{64}", run["fingerprint"]),
                        "development raw arrays are nondeterministic")
                ids = [item["chunk_id"] for item in run["results"]]
                require(len(ids) <= 8 and len(ids) == len(set(ids)), "development duplicate/excess result")
            require(row["rejected"] == (not row["runs"][0]["results"]), "development rejection disagrees with results")
        validate_metric_summary(summary, rows)
        categories = {query["category"] for query in queries.values()}
        require(set(mode.get("categories", {})) == categories, "development category inventory mismatch")
        for category in categories:
            validate_metric_summary(mode["categories"][category], [row for row in rows if row["category"] == category])
        status = summary.get("ranking_gate_status")
        require(status in {"PASS", "FAIL", "INELIGIBLE"}, "missing diagnostic ranking status")
        statuses[mode["name"]] = status
    return statuses


def quality_gates(report):
    """Quality status is distinct from successful collection and full-hybrid eligibility."""
    default = next(mode for mode in report["modes"] if mode["name"] == "lexical_graph_default")
    summary = default["summary"]
    gates = dict(development_ranking=summary["ranking_gate_status"],
                 development_absence="PASS" if summary["rejected_absence_queries"] == summary["absence_queries"] else "FAIL",
                 reserved_regression=report["rejection_validation"]["production_policy"]["validation_status"],
                 independent_validation=report["independent_validation"]["production_policy"]["validation_status"])
    return dict(status="PASS" if all(value == "PASS" for value in gates.values()) else "FAIL", gates=gates)


def load_policy_freeze(path):
    frozen = json.loads(path.read_text())
    require(frozen.get("schema_version") == 1 and re.fullmatch(r"[a-f0-9]{40}", frozen.get("source_revision", "")),
            "a full frozen policy revision is required")
    require(frozen.get("validation_fixture_sha256") == INDEPENDENT_FIXTURE_SHA256,
            "frozen policy identifies a different validation fixture")
    require(isinstance(frozen.get("evidence_policy"), dict) and frozen["evidence_policy"].get("version"),
            "frozen policy metadata is required")
    require(isinstance(frozen.get("experimental_policy"), dict) and frozen["experimental_policy"].get("version"),
            "frozen experimental policy metadata is required")
    require(set(frozen.get("policy_sources", {})) == POLICY_SOURCES
            and all(re.fullmatch(r"[a-f0-9]{64}", value) for value in frozen["policy_sources"].values()),
            "frozen policy source hashes are required")
    return frozen


def verify_source_freeze(repo, revision, head, paths, *, directories=False):
    """Pin actual tracked bytes before device access, including additions/removals in candidate roots."""
    require(isinstance(revision, str) and re.fullmatch(r"[a-f0-9]{40}", revision),
            "a full lowercase frozen source revision is required")
    command(["git", "merge-base", "--is-ancestor", revision, head], cwd=repo)
    frozen = command(["git", "ls-tree", "-r", "--name-only", revision, "--", *paths], cwd=repo).stdout.decode().splitlines()
    current = command(["git", "ls-files", "--", *paths], cwd=repo).stdout.decode().splitlines()
    require(frozen and set(frozen) == set(current), "frozen source file inventory changed")
    if not directories:
        require(set(frozen) == set(paths), "frozen evaluator source inventory is incomplete")
    records = []
    for relative in sorted(frozen):
        raw = command(["git", "show", revision + ":" + relative], cwd=repo).stdout
        digest = hashlib.sha256(raw).hexdigest()
        require((repo / relative).is_file() and sha256(repo / relative) == digest,
                "source changed after freeze: " + relative)
        records.append(dict(path=relative, bytes=len(raw), sha256=digest))
    return dict(revision=revision, files=records)


def validate_fresh40_freeze(repo, head, candidate, evaluator):
    require(sha256(repo / FRESH40_FIXTURE) == FRESH40_SHA256, "fresh40 frozen fixture changed")
    return dict(fixture_sha256=FRESH40_SHA256,
                candidate=verify_source_freeze(repo, candidate, head, CANDIDATE_SOURCE_ROOTS, directories=True),
                evaluator=verify_source_freeze(repo, evaluator, head, EVALUATOR_SOURCES))


def fresh40_arguments(enabled, candidate, evaluator):
    if not enabled:
        require(not candidate and not evaluator, "fresh40 freeze arguments require explicit --fresh40")
        return []
    require(all(isinstance(value, str) and re.fullmatch(r"[a-f0-9]{40}", value)
                for value in (candidate, evaluator)), "fresh40 requires both full frozen source revisions")
    prefix = "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval."
    return [prefix + "fresh40=true", prefix + "fresh40CandidateFreeze=" + candidate,
            prefix + "fresh40EvaluatorFreeze=" + evaluator]


def validate_fresh40_report(report, head, candidate, evaluator, fixture):
    """Check the full preregistered denominator; component-only context never earns application credit."""
    require(isinstance(report, dict), "requested fresh40 report is missing")
    expected = dict(schema_version=1, fixture_protocol_version=2, fixture_sha256=FRESH40_SHA256,
                    build_revision=head, candidate_freeze=candidate, evaluator_freeze=evaluator,
                    status="MEASURED_DIAGNOSTIC", expanded_ranking_gate="INELIGIBLE",
                    repetitions=3, warmups_per_query_mode=1)
    for key, value in expected.items():
        require(type(report.get(key)) is type(value) and report[key] == value, "fresh40 metadata mismatch: " + key)
    labels = {query["id"]: query for query in fixture["queries"]}
    contexts = {key for key, query in labels.items() if "conversation_context" in query["execution_requirements"]}
    require(len(labels) == 40 and len(contexts) == 6, "fresh40 fixture protocol changed")
    app = report.get("application_context", {})
    require(app.get("status") == "UNEXECUTED" and type(app.get("executed")) is int and app["executed"] == 0
            and type(app.get("total")) is int and app["total"] == 6
            and len(app.get("required_query_ids", [])) == 6 and set(app["required_query_ids"]) == contexts,
            "fresh40 component context cannot claim application acceptance")
    modes = report.get("modes", {})
    require(set(modes) == {"raw_string_baseline", "contextual_candidate"}, "fresh40 modes missing or substituted")
    verified = {}
    for name, mode in modes.items():
        rows = mode.get("queries", [])
        require(len(rows) == 40 and {row["id"] for row in rows} == set(labels), "fresh40 rows missing or duplicated")
        counts = dict(queries=40, answerable_queries=22, absence_queries=18, labelled_evidence_spans=24,
                      attempted=0, completed=0, scored=0, covered_evidence_spans=0, all_span_successes=0,
                      correct_absence_rejections=0, false_absence_admissions=0, strict_successes=0,
                      provenance_violations=0, execution_errors=0, timeouts=0, unexecuted=0)
        nondeterministic = 0
        for row in rows:
            label = labels[row["id"]]
            spans = sum(e["grade"] == 3 for e in label["relevant"])
            answerable = spans > 0
            require(row.get("category") == label["category"] and row.get("raw_query") == label["query"]
                    and row.get("answerable") is answerable and type(row.get("required_spans")) is int
                    and row["required_spans"] == spans, "fresh40 query/label identity changed")
            status = row.get("status")
            require(status in {"EXECUTED", "ERROR", "TIMEOUT", "UNEXECUTED"}
                    and type(row.get("attempted")) is bool, "fresh40 outcome is missing")
            counts["attempted"] += int(row["attempted"])
            if row["id"] in contexts:
                require(status == "UNEXECUTED" and row["attempted"] is False and row.get("warmup") is None
                        and row.get("samples") == [] and row.get("application_context_status") == "UNEXECUTED",
                        "fresh40 component result credited as real application context")
            if status != "EXECUTED":
                require(row.get("score") is None, "fresh40 unexecuted/error row received scoring credit")
                counts[{"ERROR": "execution_errors", "TIMEOUT": "timeouts", "UNEXECUTED": "unexecuted"}[status]] += 1
                continue
            require(row["attempted"] is True and type(row.get("deterministic")) is bool,
                    "fresh40 executed outcome lacks attempt/determinism")
            samples = row.get("samples", [])
            warmup = row.get("warmup")
            require(len(samples) == 3 and isinstance(warmup, dict), "fresh40 successful sampling is incomplete")
            for sample in [warmup, *samples]:
                require(sample.get("status") == "EXECUTED" and isinstance(sample.get("evidence"), list)
                        and finite_number(sample.get("elapsed_ms")) and sample["elapsed_ms"] >= 0,
                        "fresh40 failed/invalid sample reported as executed")
            score = row.get("score")
            require(isinstance(score, dict) and type(score.get("covered_spans")) is int
                    and type(score.get("total_spans")) is int and score["total_spans"] == spans
                    and 0 <= score["covered_spans"] <= spans
                    and isinstance(score.get("provenance_violations"), list), "fresh40 score shape invalid")
            for key in ("all_spans_covered", "correct_absence_rejection", "false_absence_admission"):
                require(type(score.get(key)) is bool, "fresh40 score boolean invalid: " + key)
            violations = len(score["provenance_violations"])
            empty = all(not sample["evidence"] for sample in samples)
            require(score["all_spans_covered"] is (answerable and score["covered_spans"] == spans),
                    "fresh40 all-span coverage disagrees with required spans")
            require(score["correct_absence_rejection"] is (not answerable and empty and violations == 0),
                    "fresh40 absence rejection disagrees with successful raw outputs")
            require(score["false_absence_admission"] is (not answerable and not empty),
                    "fresh40 false admission disagrees with successful raw outputs")
            counts["completed"] += 1
            counts["scored"] += 1
            counts["covered_evidence_spans"] += score["covered_spans"]
            counts["provenance_violations"] += violations
            counts["correct_absence_rejections"] += int(score["correct_absence_rejection"])
            counts["false_absence_admissions"] += int(score["false_absence_admission"])
            valid = violations == 0 and row["deterministic"]
            nondeterministic += int(not row["deterministic"])
            counts["all_span_successes"] += int(valid and answerable and score["all_spans_covered"])
            counts["strict_successes"] += int(valid and (
                score["all_spans_covered"] if answerable else score["correct_absence_rejection"]))
        summary = mode.get("summary", {})
        for key, value in counts.items():
            require(type(summary.get(key)) is int and summary[key] == value, "fresh40 summary mismatch: " + key)
        hard = "FAIL" if counts["provenance_violations"] or nondeterministic else (
            "INCOMPLETE" if counts["unexecuted"] else "PASS")
        require(summary.get("hard_provenance_gate") == hard, "fresh40 hard provenance status mismatch")
        verified[name] = dict(counts, hard_provenance_gate=hard, nondeterministic_queries=nondeterministic,
                              strict_success_rate=counts["strict_successes"] / 40)
    return dict(status="INCOMPLETE" if any(v["unexecuted"] for v in verified.values()) else (
        "PASS" if all(v["strict_successes"] == 40 for v in verified.values()) else "FAIL"),
        modes=verified, application_context="UNEXECUTED", expanded_ranking_gate="INELIGIBLE",
        limitation="Structural row/count review; raw source, member and span evidence also requires independent review.")


def verify_junit(directory):
    """Require the actual AGP testcase, not an empty/success-looking runner exit."""
    cases = []
    for path in directory.rglob("*.xml"):
        for case in ET.parse(path).getroot().iter("testcase"):
            cases.append(case)
    require(len(cases) == 1 and cases[0].get("classname") == TEST_CLASS,
            "expected only one real retrieval instrumentation testcase in AGP XML")
    require(not any(cases[0].find(tag) is not None for tag in ("failure", "error", "skipped")),
            "retrieval instrumentation failed or was skipped")


def run_logged(argv, log, env, timeout=1500):
    """Own the Gradle process group, including its single-use daemon, on timeout."""
    with log.open("wb") as stream:
        with subprocess.Popen(argv, stdout=stream, stderr=subprocess.STDOUT,
                              env=env, start_new_session=True) as process:
            try:
                return process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    pass
                # A descendant may outlive an exited wrapper; terminate the
                # owned group even if wait() already reaped the wrapper.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                stream.write(b"\nHOST_INSTRUMENTATION_TIMEOUT\n")
                raise


def collect(adb, arguments, destination):
    try:
        destination.write_bytes(command(adb + arguments).stdout)
        return True
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
        partial = getattr(error, "stdout", None)
        if partial:
            destination.with_name(destination.name + ".partial").write_bytes(partial)
        destination.with_name(destination.name + ".unavailable.log").write_bytes(
            getattr(error, "stderr", None) or (type(error).__name__ + "\n").encode())
        return False


def recorded_command(argv, destination):
    """Keep transport evidence, including partial stdout on nonzero exit/timeout."""
    metadata = dict(argv=argv)
    stdout, stderr = b"", b""
    try:
        result = command(argv)
        stdout, stderr = result.stdout, result.stderr
        metadata["exit_code"] = result.returncode
        return result
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
        stdout = getattr(error, "stdout", None) or b""
        stderr = getattr(error, "stderr", None) or b""
        metadata.update(error_type=type(error).__name__, exit_code=getattr(error, "returncode", None))
        raise
    finally:
        destination.with_suffix(".stdout").write_bytes(stdout)
        destination.with_suffix(".stderr").write_bytes(stderr)
        destination.with_suffix(".command.json").write_text(json.dumps(metadata, indent=2) + "\n")


def installed_apk_hash(adb, directory):
    paths = recorded_command(adb + ["shell", "pm", "path", PACKAGE], directory / "installed-path").stdout.decode().splitlines()
    require(len(paths) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~-]+\.apk", paths[0]),
            "expected one retained installed retrieval test APK")
    digest = recorded_command(adb + ["shell", "sha256sum", paths[0][len("package:"):]],
                              directory / "installed-sha256").stdout.decode().split()
    require(digest and re.fullmatch(r"[a-f0-9]{64}", digest[0]), "installed APK digest unavailable")
    return digest[0]


def collect_evidence(adb, output, summary):
    """Retry only read-only evidence collection; instrumentation is never repeated."""
    errors = (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError, UnicodeError)
    summary.update(report_collected=False, selected_collection_attempt=None, collection_attempts=[])
    for number in range(1, 4):
        directory = output / "collection-attempts" / f"{number:02d}"
        directory.mkdir(parents=True)
        attempt = dict(number=number, directory=str(directory.relative_to(output)), errors=[])
        summary["collection_attempts"].append(attempt)
        if number > 1:
            try:
                recorded_command(adb + ["wait-for-device"], directory / "reconnect")
                qemu = recorded_command(adb + ["shell", "getprop", "ro.kernel.qemu"], directory / "emulator")
                require(qemu.stdout.strip() == b"1", "collection retry requires the selected emulator")
            except errors as error:
                attempt["errors"].append(dict(phase="reconnect", type=type(error).__name__, detail=str(error)))
                continue
        try:
            raw = recorded_command(adb + ["exec-out", "run-as", PACKAGE, "cat", REPORT_PATH], directory / "report").stdout
            attempt["host_report_sha256"] = hashlib.sha256(raw).hexdigest()
            # A successful adb exit can still return a truncated stream during
            # transport teardown. Require both valid JSON and device-byte identity.
            report = json.loads(raw)
            require(isinstance(report, dict), "retrieval report must be a JSON object")
            digest = recorded_command(adb + ["exec-out", "run-as", PACKAGE, "sha256sum", REPORT_PATH],
                                      directory / "report-sha256").stdout.decode().split()
            require(digest and re.fullmatch(r"[a-f0-9]{64}", digest[0]), "device report digest unavailable")
            attempt["device_report_sha256"] = digest[0]
            require(digest[0] == attempt["host_report_sha256"], "collected report differs from device bytes")
            (output / "retrieval.json").write_bytes(raw)
            attempt["report_verified"] = True
            summary.update(report_collected=True, report_collection_attempt=number,
                           report_sha256=digest[0])
        except errors as error:
            attempt["errors"].append(dict(phase="report", type=type(error).__name__, detail=str(error)))
        try:
            attempt["installed_test_apk_sha256"] = installed_apk_hash(adb, directory)
            if attempt["installed_test_apk_sha256"] != summary["built_test_apk_sha256"]:
                # An observed identity mismatch is terminal even when the same
                # attempt's report was incomplete. Never retry it into a pass.
                summary.update(installed_test_apk_sha256=attempt["installed_test_apk_sha256"],
                               installed_apk_identity_mismatch=True)
                return
        except errors as error:
            attempt["errors"].append(dict(phase="installed_apk", type=type(error).__name__, detail=str(error)))
            summary["installed_apk_verification_error"] = type(error).__name__
        if attempt.get("report_verified") and attempt.get("installed_test_apk_sha256"):
            summary.update(selected_collection_attempt=number,
                           collection_recovered=number > 1,
                           installed_test_apk_sha256=attempt["installed_test_apk_sha256"])
            summary.pop("installed_apk_verification_error", None)
            return
    if not summary["report_collected"]:
        stderr = directory / "report.stderr"
        (output / "retrieval.json.unavailable.log").write_bytes(
            stderr.read_bytes() if stderr.exists() and stderr.stat().st_size else b"No complete verified report collected\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--policy-freeze", default="tools/eval/retrieval-policy-freeze.json")
    parser.add_argument("--require-quality", action="store_true", help="fail after collecting genuine quality failures")
    parser.add_argument("--require-hybrid", action="store_true", help="also require a measured full-hybrid pass")
    parser.add_argument("--fresh40", action="store_true", help="explicitly measure the frozen additive fresh40 adapter")
    parser.add_argument("--candidate-freeze", default="")
    parser.add_argument("--evaluator-freeze", default="")
    args = parser.parse_args(argv)
    fresh_args = fresh40_arguments(args.fresh40, args.candidate_freeze, args.evaluator_freeze)
    repo = REPOSITORY
    require(Path.cwd().resolve() == repo, "run from the repository root")
    require(re.fullmatch(r"emulator-[0-9]+", args.serial), "an explicit emulator serial is required")
    require(re.fullmatch(r"[a-f0-9]{40}", args.expected_head), "a full source revision is required")
    head = command(["git", "rev-parse", "HEAD"]).stdout.decode().strip()
    require(head == args.expected_head, "source differs from the reviewed revision")
    command(["git", "diff", "--quiet", "HEAD", "--"])
    require(not command(["git", "ls-files", "--others", "--exclude-standard"]).stdout.strip(),
            "source contains untracked files; use a clean isolated checkout")
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", args.serial]
    summary = dict(schema_version=1, mode="real-retrieval-diagnostic", declared_build_revision=head,
                   source_identity="host-declared clean checkout; not embedded APK attestation",
                   full_hybrid_gate="INELIGIBLE", complete=False, host_timeout=False)
    device_verified = False
    try:
        frozen = load_policy_freeze(repo / args.policy_freeze)
        command(["git", "merge-base", "--is-ancestor", frozen["source_revision"], head])
        for relative, digest in frozen["policy_sources"].items():
            require(sha256(repo / relative) == digest, "policy source changed since freeze: " + relative)
        summary["policy_freeze"] = frozen
        summary["require_quality"] = args.require_quality
        summary["require_hybrid"] = args.require_hybrid
        if args.fresh40:
            summary["fresh40_freeze"] = validate_fresh40_freeze(
                repo, head, args.candidate_freeze, args.evaluator_freeze)
        require(not any((repo / RESULT_DIRECTORY).rglob("*.xml")),
                "prior instrumentation XML exists; use a fresh isolated build directory")
        apks = list((repo / APK_DIRECTORY).glob("*.apk"))
        require(len(apks) == 1, "build exactly one dev vault instrumentation APK before running")
        summary["built_test_apk_sha256"] = sha256(apks[0])
        require(command(adb + ["shell", "getprop", "ro.kernel.qemu"]).stdout.strip() == b"1",
                "retrieval workflow requires an emulator")
        # A fresh test package prevents a failed launch from reusing an older JSON.
        # `pm path` exits 1 for an absent package. Listing also includes any
        # uninstalled package with retained data (-u), and succeeds when empty.
        require(not command(adb + ["shell", "pm", "list", "packages", "-u", "--user", "0", PACKAGE]).stdout.strip(),
                "use a fresh emulator without a prior retrieval test package")
        device_verified = True
        env = dict(os.environ, ANDROID_SERIAL=args.serial)
        argv = ["./gradlew", "--no-daemon", "--max-workers=2", "-Pskein.retrievalEvaluation=true",
                ":core:vault:connectedDevDebugAndroidTest", "--stacktrace",
                "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
                "-Pandroid.testInstrumentationRunnerArguments.class=" + TEST_CLASS,
                "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.eval=true",
                "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.repetitions=3",
                "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.revision=" + head,
                "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.validationFreeze=" + frozen["source_revision"]]
        argv += fresh_args
        try:
            summary["gradle_exit_code"] = run_logged(argv, output / "instrumentation-gradle.log", env)
        except subprocess.TimeoutExpired:
            summary["host_timeout"] = True
            # Only the fresh, explicitly selected emulator package is stopped.
            # Do not uninstall or clear data: partial failure evidence is kept.
            collect(adb, ["shell", "am", "force-stop", PACKAGE], output / "timeout-force-stop.log")
            raise
        finally:
            # Retain the actual report even when Gradle reports an assertion failure.
            try:
                summary["post_instrumentation_test_apk_sha256"] = sha256(apks[0])
            except OSError as error:
                summary["post_instrumentation_test_apk_error"] = dict(type=type(error).__name__, detail=str(error))
            collect_evidence(adb, output, summary)
        require(summary["gradle_exit_code"] == 0, "instrumentation failed; inspect retained Gradle/XML logs")
        require(not summary.get("installed_apk_identity_mismatch"),
                "retained installed test APK differs from the prebuilt APK")
        require(summary["selected_collection_attempt"] is not None,
                "unable to collect complete retrieval report and installed test APK evidence")
        require(summary.get("installed_test_apk_sha256") == summary["built_test_apk_sha256"],
                "retained installed test APK differs from the prebuilt APK or is unavailable")
        require(summary.get("post_instrumentation_test_apk_sha256") == summary["built_test_apk_sha256"],
                "test APK changed during instrumentation")
        verify_junit(repo / RESULT_DIRECTORY)
        report = json.loads((output / "retrieval.json").read_text())
        queries = json.loads((repo / "testing/src/main/resources/eval/gold.json").read_text())["queries"]
        validation_fixture = repo / "testing/src/main/resources/eval/rejection-validation.json"
        require(sha256(validation_fixture) == VALIDATION_FIXTURE_SHA256, "reserved validation fixture changed")
        independent_fixture = repo / "testing/src/main/resources/eval/rejection-validation-20260928.json"
        require(sha256(independent_fixture) == INDEPENDENT_FIXTURE_SHA256, "independent validation fixture changed")
        summary["diagnostic_ranking_gates"] = validate_report(
            report, head,
            sha256(repo / "testing/src/main/resources/eval/corpus.json"),
            sha256(repo / "testing/src/main/resources/eval/gold.json"),
            {query["id"]: query for query in queries}, json.loads(validation_fixture.read_text()),
            json.loads(independent_fixture.read_text()), frozen["evidence_policy"], frozen["source_revision"],
            frozen["experimental_policy"])
        summary["diagnostic_rejection_validation"] = {
            name: value["validation_status"] for name, value in report["rejection_validation"].items()}
        summary["diagnostic_rejection_ranking_gates"] = {
            name: value["mode"]["summary"]["ranking_gate_status"] for name, value in report["rejection_validation"].items()}
        summary["diagnostic_independent_validation"] = {
            name: value["validation_status"] for name, value in report["independent_validation"].items()}
        summary["validation_source_review"] = {
            section: {name: validate_sources(value, fixture) for name, value in report[section].items()}
            for section, fixture in (("rejection_validation", json.loads(validation_fixture.read_text())),
                                     ("independent_validation", json.loads(independent_fixture.read_text())))
        }
        summary["quality_gate"] = quality_gates(report)
        if args.fresh40:
            summary["fresh_contextual_validation"] = validate_fresh40_report(
                report.get("fresh_contextual_validation"), head, args.candidate_freeze, args.evaluator_freeze,
                json.loads((repo / FRESH40_FIXTURE).read_text()))
            summary["quality_gate"]["gates"]["fresh_contextual_validation"] = summary["fresh_contextual_validation"]["status"]
            summary["quality_gate"]["status"] = "PASS" if all(
                value == "PASS" for value in summary["quality_gate"]["gates"].values()) else "FAIL"
        else:
            require("fresh_contextual_validation" not in report, "fresh40 ran without explicit opt-in")
        summary["complete"] = True
        print("Measured retrieval diagnostic retained; full hybrid gate INELIGIBLE.")
        print(json.dumps(summary["diagnostic_ranking_gates"], sort_keys=True))
        print(json.dumps(summary["diagnostic_rejection_validation"], sort_keys=True))
        print(json.dumps(summary["quality_gate"], sort_keys=True))
        require(not args.require_quality or summary["quality_gate"]["status"] == "PASS",
                "retrieval quality gate FAIL; complete measured evidence retained")
        require(not args.require_hybrid or report["full_hybrid_gate"] == "PASS",
                "full-hybrid gate INELIGIBLE; complete measured evidence retained")
    except Exception as error:
        summary["failure_type"] = type(error).__name__
        raise
    finally:
        if device_verified:
            collect(adb, ["logcat", "-d", "-v", "threadtime"], output / "logcat.txt")
        (output / "runner-summary.json").write_text(json.dumps(summary, indent=2) + "\n")


if __name__ == "__main__":
    main()
