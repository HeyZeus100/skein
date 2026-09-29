#!/usr/bin/env python3
"""Validate/export this host-only demo with the existing answer fixture functions."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / "tools/eval"))
import answer_eval  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate", "export"])
    args = parser.parse_args()
    # Select separate demo data; preserve the repository's fixtures and validators.
    answer_eval.FIXTURES = HERE
    cases, case_hash = answer_eval.fixture("demo")
    note_hashes = set()
    for line in (HERE / "source-notes.sha256").read_text().splitlines():
        expected, relative = line.split("  ", 1)
        note = (HERE / relative).resolve()
        note.relative_to((HERE.parent / "notes").resolve())
        answer_eval.require(answer_eval.sha(note.read_bytes()) == expected, "source note changed")
        note_hashes.add(expected)
    for case in cases:
        for document in case["documents"]:
            answer_eval.require(document["revision_sha256"] in note_hashes, "source is not a hashed demo note")
    exported = "".join(
        json.dumps(answer_eval.export_case(case), ensure_ascii=False, separators=(",", ":")) + "\n"
        for case in cases
    ).encode("utf-8")
    if args.command == "export":
        sys.stdout.buffer.write(exported)
    else:
        answer_eval.require((HERE / "input.jsonl").read_bytes() == exported, "exported input differs")
        print(json.dumps({
            "split": "demo", "cases": len(cases), "case_set_sha256": case_hash,
            "fixture_sha256": hashlib.sha256(exported).hexdigest(),
            "runtime_status": "NOT RUN", "mode": "supplied_evidence",
        }, indent=2))


if __name__ == "__main__":
    main()
