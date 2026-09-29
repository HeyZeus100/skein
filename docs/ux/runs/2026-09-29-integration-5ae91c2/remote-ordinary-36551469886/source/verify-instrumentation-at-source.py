#!/usr/bin/env python3
"""Fail the ordinary lane on missing, failed, skipped or opt-in instrumentation."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

MODULES = ("app", "core/vault", "inference-service")
RETRIEVAL_CLASS = "app.skein.core.vault.eval.RealRetrievalEvaluationTest"


def review(repository):
    summary = {"schema_version": 1, "lane": "ordinary-instrumentation", "modules": {}, "errors": []}
    for module in MODULES:
        directory = repository / module / "build/outputs/androidTest-results/connected"
        paths = sorted(directory.rglob("*.xml"))
        result = {"files": [str(path.relative_to(repository)) for path in paths],
                  "tests": 0, "passed": 0, "failures": 0, "errors": 0, "skipped": 0, "nonpassing_cases": []}
        summary["modules"][module] = result
        seen = set()
        for path in paths:
            try:
                root = ET.parse(path).getroot()
            except (ET.ParseError, OSError) as error:
                summary["errors"].append(f"{module}: unreadable XML {path.name}: {type(error).__name__}")
                continue
            for case in root.iter("testcase"):
                identity = (case.get("classname"), case.get("name"))
                if not all(identity) or identity in seen:
                    summary["errors"].append(f"{module}: missing or duplicate testcase identity {identity}")
                seen.add(identity)
                result["tests"] += 1
                tags = [tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None]
                for tag in tags:
                    result[{"failure": "failures", "error": "errors", "skipped": "skipped"}[tag]] += 1
                if tags:
                    result["nonpassing_cases"].append({"classname": identity[0], "name": identity[1], "tags": tags})
                else:
                    result["passed"] += 1
                if identity[0] == RETRIEVAL_CLASS:
                    summary["errors"].append(f"{module}: opt-in retrieval class appeared in the ordinary lane")
        if not result["tests"]:
            summary["errors"].append(f"{module}: no actual instrumentation testcases")
        if result["nonpassing_cases"]:
            summary["errors"].append(f"{module}: actual XML contains failed, errored or skipped testcases")
    summary["passed"] = not summary["errors"]
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    summary = review(args.repository)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
