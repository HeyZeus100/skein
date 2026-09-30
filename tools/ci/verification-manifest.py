#!/usr/bin/env python3
"""Bind retained raw verification records to their host-declared checkout.

This inventories evidence; it never converts failures or skips to passes and does
not attest installed APK bytes. Consumers must inspect the retained raw records.
"""
import hashlib
import json
import pathlib
import subprocess
import sys

INSTRUMENTATION_MODULES = ("app", "core/vault", "inference-service")
# Skein's supported project layout: root/top-level projects, core and feature
# modules, and the build-logic composite's projects. New deeper module layouts
# must extend this inventory; never discover projects inside retained evidence.
MODULE_DIRECTORY_PATTERNS = ("*", "core/*", "feature/*", "build-logic/*")
NON_PROJECT_ROOTS = {"build", "docs", ".git", "third_party"}


def module_output_roots(root):
    candidates = {root} | {
        directory
        for pattern in MODULE_DIRECTORY_PATTERNS
        for directory in root.glob(pattern)
    }
    modules = {
        directory for directory in candidates
        if directory.is_dir()
        and not set(directory.relative_to(root).parts) & NON_PROJECT_ROOTS
        and any((directory / name).is_file() for name in ("build.gradle.kts", "build.gradle"))
    }
    return sorted(directory / "build" for directory in modules)


def manifest(root, lane, source):
    patterns = []
    apk_patterns = []
    if lane == "instrumentation":
        # Committed evidence under docs/ can contain earlier build-output paths.
        patterns = [
            f"{module}/build/outputs/androidTest-results/connected/**/*.xml"
            for module in INSTRUMENTATION_MODULES
        ] + ["build/instrumentation-review.json"]
        apk_patterns = [f"{module}/build/outputs/apk/**/*.apk" for module in INSTRUMENTATION_MODULES]
    else:
        for output in module_output_roots(root):
            relative = output.relative_to(root)
            patterns.append(f"{relative}/test-results/**/*.xml")
            if lane == "screenshots":
                patterns.append(f"{relative}/test-results/roborazzi/**/*.json")
    paths = sorted({p for pattern in patterns for p in root.glob(pattern) if p.is_file()})
    built_apks = sorted({p for pattern in apk_patterns for p in root.glob(pattern) if p.is_file()})
    return {
        "built_apks": [
            {"path": str(p.relative_to(root)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in built_apks if p.is_file()
        ],
        "built_apk_attribution": "host build outputs; installed bytes were not independently measured here",
        "lane": lane,
        "source_sha": source,
        "source_attribution": "host-declared git checkout, not APK attestation",
        "files": [
            {
                "path": str(p.relative_to(root)),
                "bytes": p.stat().st_size,
                "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
            }
            for p in paths
        ],
    }


def main():
    lane, output = sys.argv[1:]
    if lane not in ("unit", "screenshots", "instrumentation"):
        raise SystemExit("expected unit, screenshots or instrumentation lane")
    root = pathlib.Path.cwd()
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    result = manifest(root, lane, source)
    target = pathlib.Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, indent=2) + "\n")
    if not result["files"]:
        raise SystemExit("no verification evidence found")


if __name__ == "__main__":
    main()
