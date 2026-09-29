#!/usr/bin/env python3
"""Opt-in emulator-only real SQLite retrieval diagnostic; never a hybrid quality pass."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = "app.skein.core.vault.test"
TEST_CLASS = "app.skein.core.vault.eval.RealRetrievalEvaluationTest"
REPORT_PATH = "files/artifacts/eval/retrieval.json"
MODES = {"lexical_only", "graph_only", "lexical_graph_default"}
RESULT_DIRECTORY = "core/vault/build/outputs/androidTest-results/connected"
APK_DIRECTORY = "core/vault/build/outputs/apk/androidTest/dev/debug"
REPOSITORY = Path(__file__).resolve().parents[2]


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


def validate_report(report, head, corpus_hash, gold_hash, queries):
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
        status = summary.get("ranking_gate_status")
        require(status in {"PASS", "FAIL", "INELIGIBLE"}, "missing diagnostic ranking status")
        statuses[mode["name"]] = status
    return statuses


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
        destination.with_name(destination.name + ".unavailable.log").write_bytes(
            getattr(error, "stderr", None) or (type(error).__name__ + "\n").encode())
        return False


def installed_apk_hash(adb):
    paths = command(adb + ["shell", "pm", "path", PACKAGE]).stdout.decode().splitlines()
    require(len(paths) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~-]+\.apk", paths[0]),
            "expected one retained installed retrieval test APK")
    digest = command(adb + ["shell", "sha256sum", paths[0][len("package:"):]]).stdout.decode().split()
    require(digest and re.fullmatch(r"[a-f0-9]{64}", digest[0]), "installed APK digest unavailable")
    return digest[0]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
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
        argv = ["./gradlew", "--no-daemon", "--max-workers=2", ":core:vault:connectedDevDebugAndroidTest", "--stacktrace",
                "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
                "-Pandroid.testInstrumentationRunnerArguments.class=" + TEST_CLASS,
                "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.eval=true",
                "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.repetitions=3",
                "-Pandroid.testInstrumentationRunnerArguments.skein.retrieval.revision=" + head]
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
            summary["report_collected"] = collect(
                adb, ["exec-out", "run-as", PACKAGE, "cat", REPORT_PATH], output / "retrieval.json")
            try:
                summary["installed_test_apk_sha256"] = installed_apk_hash(adb)
            except (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
                summary["installed_apk_verification_error"] = type(error).__name__
        require(summary["gradle_exit_code"] == 0, "instrumentation failed; inspect retained Gradle/XML logs")
        require(summary.get("installed_test_apk_sha256") == summary["built_test_apk_sha256"],
                "retained installed test APK differs from the prebuilt APK or is unavailable")
        require(sha256(apks[0]) == summary["built_test_apk_sha256"], "test APK changed during instrumentation")
        verify_junit(repo / RESULT_DIRECTORY)
        report = json.loads((output / "retrieval.json").read_text())
        queries = json.loads((repo / "testing/src/main/resources/eval/gold.json").read_text())["queries"]
        summary["diagnostic_ranking_gates"] = validate_report(
            report, head,
            sha256(repo / "testing/src/main/resources/eval/corpus.json"),
            sha256(repo / "testing/src/main/resources/eval/gold.json"),
            {query["id"]: query for query in queries})
        summary["complete"] = True
        print("Measured retrieval diagnostic retained; full hybrid gate INELIGIBLE.")
        print(json.dumps(summary["diagnostic_ranking_gates"], sort_keys=True))
    except Exception as error:
        summary["failure_type"] = type(error).__name__
        raise
    finally:
        if device_verified:
            collect(adb, ["logcat", "-d", "-v", "threadtime"], output / "logcat.txt")
        (output / "runner-summary.json").write_text(json.dumps(summary, indent=2) + "\n")


if __name__ == "__main__":
    main()
