#!/usr/bin/env python3
"""Bind retained raw unit/screenshot records to their host-declared checkout.

This inventories evidence; it never converts failures or skips to passes and does
not attest installed APK bytes. Consumers must inspect the retained raw records.
"""
import hashlib
import json
import pathlib
import subprocess
import sys


def manifest(root, lane, source):
    patterns = ["**/build/test-results/**/*.xml"]
    if lane == "screenshots":
        patterns += ["**/build/test-results/roborazzi/*.json"]
    paths = sorted({p for pattern in patterns for p in root.glob(pattern) if p.is_file()})
    return {
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
    if lane not in ("unit", "screenshots"):
        raise SystemExit("expected unit or screenshots lane")
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
