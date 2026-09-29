#!/usr/bin/env python3
"""Opt-in tiny-model emulator smoke. Never accepts a physical device or assesses truth."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from answer_eval import export_case, fixture
from prepare_android_benchmark import file_sha256


def require(condition, message):
    if not condition:
        raise ValueError(message)


def command(argv, **kwargs):
    return subprocess.run(argv, check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=kwargs.pop("timeout", 120), **kwargs)


class Emulator:
    def __init__(self, serial):
        require(re.fullmatch(r"emulator-[0-9]+", serial), "smoke requires an explicit emulator serial")
        self.prefix = ["adb", "-s", serial]
        self.host_timed_out = False
        require(self.shell("getprop", "ro.kernel.qemu").strip() == "1", "physical devices are forbidden")

    def shell(self, *args):
        return command(self.prefix + ["shell", "-T", *args]).stdout.decode("utf-8")

    def install(self, apk):
        command(self.prefix + ["install", "-r", str(apk)], timeout=180)

    def installed_apk_sha256(self, package):
        require(re.fullmatch(r"[A-Za-z0-9_.]+", package), "unsafe package name")
        paths = self.shell("pm", "path", package).splitlines()
        # This lane builds and installs one monolithic APK per package. A base
        # digest alone cannot identify a split installation, so refuse it.
        require(len(paths) == 1, "expected exactly one installed APK; splits are unsupported")
        match = re.fullmatch(r"package:(/data/app/(?:[A-Za-z0-9_+=.~\-]+/)+base\.apk)", paths[0])
        require(match is not None, "unexpected installed APK path")
        installed_path = match.group(1)
        require(not any(part in (".", "..") for part in installed_path.split("/")),
                "unsafe installed APK path")
        # Hash the installed bytes on the host, without assuming Android has a
        # hashing utility or buffering an entire APK in memory. exec-out keeps
        # binary bytes intact; only this package's public installed APK is read.
        with tempfile.TemporaryDirectory(prefix="skein-installed-apk-") as directory:
            downloaded = Path(directory) / "base.apk"
            with downloaded.open("wb") as stream:
                subprocess.run(self.prefix + ["exec-out", "cat", installed_path], check=True,
                               stdout=stream, stderr=subprocess.PIPE, timeout=180)
            return file_sha256(downloaded)

    def private_root(self, package):
        root = self.shell("run-as", package, "pwd").strip()
        require(re.fullmatch(r"/data/(?:user/\d+|data)/" + re.escape(package), root),
                "unexpected app-private root")
        return root + "/files/synthetic-benchmark"

    def stage(self, package, root, relative, source):
        require(re.fullmatch(r"[A-Za-z0-9_./-]+", relative) and ".." not in relative.split("/"),
                "unsafe staging path")
        destination = root + "/" + relative
        self.shell("run-as", package, "mkdir", "-p", str(Path(destination).parent))
        # No overwrite, even for a prior synthetic run. CI uses a fresh emulator.
        self.shell("run-as", package, "test", "!", "-e", destination)
        with Path(source).open("rb") as stream:
            command(self.prefix + ["shell", "-T", "run-as", package, "dd", "of=" + destination],
                    stdin=stream, timeout=180)
        return destination

    def instrument(self, component, target, test_class, arguments, log):
        registered = self.shell("pm", "list", "instrumentation").splitlines()
        require(f"instrumentation:{component} (target={target})" in registered,
                "instrumentation component does not match expected target")
        argv = self.prefix + ["shell", "am", "instrument", "-w", "-e", "class", test_class,
                              "-e", "synthetic_enabled", "true"]
        for key, value in arguments.items():
            argv += ["-e", key, value]
        try:
            result = subprocess.run(argv + [component], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=1_200, check=False)
            log.write_bytes(result.stdout)
            return result.returncode == 0 and re.search(rb"OK \(1 test\)", result.stdout) is not None
        except subprocess.TimeoutExpired as error:
            self.host_timed_out = True
            log.write_bytes((error.stdout or b"") + b"\nHOST_INSTRUMENTATION_TIMEOUT\n")
            return False

    def collect(self, package, root, run_id, filenames, output):
        for name in filenames:
            try:
                content = command(self.prefix + ["shell", "-T", "run-as", package, "cat",
                                                f"{root}/output/{run_id}/{name}"]).stdout
                (output / name).write_bytes(content)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                # Missing rows remain missing. Never synthesize successful answers.
                (output / (name + ".missing")).write_text("result file unavailable\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    require(Path.cwd().resolve() == repo, "run from the repository root")
    head = command(["git", "rev-parse", "HEAD"]).stdout.decode().strip()
    require(re.fullmatch(r"[a-f0-9]{40}", args.expected_head) and head == args.expected_head,
            "source head differs from the reviewed release")
    command(["git", "diff", "--quiet", "HEAD", "--"])
    device = Emulator(args.serial)  # All mutations occur only after the emulator check.
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    summary = dict(schema_version=1, mode="tiny-model-smoke", quality_assessed=False,
                   build_sha=head, complete=False,
                   profile=dict(name="tiny-structural-v2", context_length=1024, threads=2,
                                max_tokens=4, case_timeout_ms=60000, seeds=[17]))
    try:
        lock = dict(line.split("=", 1) for line in (repo / "tools/models/test-model.lock").read_text().splitlines()
                    if line and not line.startswith("#"))
        model = repo / "inference-service/src/androidTest/assets/tiny.gguf"
        require(file_sha256(model) == lock["sha256"] and model.stat().st_size == int(lock["size_bytes"]),
                "tiny model differs from the repository pin")
        apks = dict(app=repo / "app/build/outputs/apk/dev/debug/app-dev-debug.apk",
                    app_test=repo / "app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk",
                    native_test=repo / "inference-service/build/outputs/apk/androidTest/dev/debug/inference-service-dev-debug-androidTest.apk")
        summary["apk_sha256"] = {key: file_sha256(path) for key, path in apks.items()}
        summary["model_sha256"] = lock["sha256"]
        llama_sha = command(["git", "rev-parse", "HEAD:third_party/llama.cpp"]).stdout.decode().strip()
        summary["llama_sha"] = llama_sha
        overlay_sha = file_sha256(repo / "native/llama/tokenizer-patches/PINS.txt")
        summary["tokenizer_overlay_sha256"] = overlay_sha
        for apk in apks.values():
            device.install(apk)
        app_root = device.private_root("app.skein")
        native_package = "app.skein.inference.service.test"
        native_root = device.private_root(native_package)
        cases, case_hash = fixture("development")
        fixture_file = output / "development-input.jsonl"
        fixture_file.write_text("".join(json.dumps(export_case(case), ensure_ascii=False, separators=(",", ":")) + "\n"
                                      for case in cases), encoding="utf-8")
        sampling_file = output / "sampling.json"
        sampling_file.write_text(json.dumps(dict(temperature=0.0, top_k=1, top_p=1.0, min_p=0.0,
                                                  repeat_penalty=1.0, max_tokens=4, stop=[])))
        prepared = output / "prepared"
        run_id = "tiny-smoke-" + head[:12]
        command([sys.executable, "tools/eval/prepare_android_benchmark.py", "--model", str(model),
                 "--model-sha256", lock["sha256"], "--model-license", lock["license"], "--apk", str(apks["app"]),
                 "--fixture", str(fixture_file), "--case-set-sha256", case_hash, "--build-sha", head,
                 "--llama-sha", llama_sha, "--tokenizer-overlay-sha256", overlay_sha,
                 "--run-id", run_id, "--output-dir", str(prepared),
                 "--device-root", app_root, "--context-length", "1024", "--threads", "2",
                 "--case-timeout-ms", "60000",
                 "--sampling", str(sampling_file), "--seeds", "17"])
        device.stage("app.skein", app_root, f"models/{lock['sha256']}/model.gguf", model)
        device.stage("app.skein", app_root, f"input/{run_id}.jsonl", prepared / "input.jsonl")
        config_path = device.stage("app.skein", app_root, f"input/{run_id}.json", prepared / "config.json")
        app_output = output / "isolated-apk"
        app_output.mkdir()
        summary["answer_instrumentation_passed"] = device.instrument(
            "app.skein.test/androidx.test.runner.AndroidJUnitRunner", "app.skein",
            "app.skein.benchmark.SyntheticAnswerBenchmarkTest", {"synthetic_config": config_path},
            app_output / "instrumentation.log")
        device.collect("app.skein", app_root, run_id, ("run_manifest.json", "answers.jsonl"), app_output)
        require(not device.host_timed_out, "instrumentation deadline expired; no second device workload is started")
        installed_native_sha = device.installed_apk_sha256(native_package)
        summary["installed_apk_sha256"] = {"native_test": installed_native_sha}
        require(installed_native_sha == summary["apk_sha256"]["native_test"],
                "installed native test APK provenance mismatch")
        native_model = device.stage(native_package, native_root, f"models/{lock['sha256']}/model.gguf", model)
        native_output = output / "native-parity"
        native_output.mkdir()
        summary["parity_instrumentation_passed"] = device.instrument(
            native_package + "/androidx.test.runner.AndroidJUnitRunner", native_package,
            "app.skein.inference.service.SyntheticTemplateParityTest",
            dict(synthetic_model_file=native_model, synthetic_model_sha256=lock["sha256"], synthetic_run_id=run_id),
            native_output / "instrumentation.log")
        device.collect(native_package, native_root, run_id, ("native-parity.json",), native_output)
        answers = [json.loads(line) for line in (app_output / "answers.jsonl").read_text().splitlines()]
        expected = {(case["id"], 17) for case in cases}
        actual = {(row["case_id"], row["seed"]) for row in answers}
        summary["answer_statuses"] = {status: sum(row["status"] == status for row in answers)
                                      for status in ("ok", "timeout", "oom", "error")}
        summary["missing_case_seeds"] = sorted(expected - actual)
        manifest = json.loads((app_output / "run_manifest.json").read_text())
        parity = json.loads((native_output / "native-parity.json").read_text())
        require(manifest["build_sha"] == head and manifest["model_sha256"] == lock["sha256"]
                and manifest["llama_sha"] == llama_sha
                and manifest["tokenizer_overlay_sha256"] == overlay_sha
                and manifest["apk_sha256"] == summary["apk_sha256"]["app"]
                and manifest["test_apk_sha256"] == summary["apk_sha256"]["app_test"]
                and manifest["fixture_sha256"] == file_sha256(fixture_file)
                and manifest["context"]["allocated"] > 0, "manifest provenance mismatch")
        require(parity["model_sha256"] == lock["sha256"]
                and parity["template_sha256"] == manifest["template_sha256"], "native template provenance mismatch")
        require(len(answers) == len(expected) and actual == expected, "missing or duplicate case/seed rows")
        require(all(row["status"] == "ok" for row in answers), "runtime failure; inspect retained answer rows")
        require(any(not row["generation_skipped"] and row["generation"] is not None for row in answers),
                "smoke did not exercise generation")
        require(all(row["generation_skipped"] or row["generation"]["isolated_count_consistency"]
                    for row in answers), "prompt count consistency missing")
        require(len(parity["cases"]) == 5 and all(row["status"] == "ok" for row in parity["cases"]),
                "native token parity failure")
        require(summary["answer_instrumentation_passed"] and summary["parity_instrumentation_passed"],
                "instrumentation failed; inspect retained logs")
        summary["complete"] = True
    except Exception as error:
        summary["error_code"] = type(error).__name__
        raise
    finally:
        (output / "smoke_summary.json").write_text(json.dumps(summary, indent=2) + "\n")


if __name__ == "__main__":
    main()
