#!/usr/bin/env python3
"""Validate/export the separately authored answer80 and report declared reviews.

No model, network, retrieval or device calls. No production threshold decisions.
The original answer_eval calculator and older fixture bytes remain unchanged.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
from uuid import UUID

import answer_eval as base

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / "testing/src/main/resources/eval/answers/heldout-20260930"


def validate_cases(cases, receipt):
    base.require(len(cases) == receipt["cases"] == 80, "expected all 80 heldout cases")
    base.require(dict(Counter(c["category"] for c in cases)) == receipt["categories"], "category coverage changed")
    base.require(len({c["id"] for c in cases}) == len(cases), "duplicate case ID")
    identities, questions = set(), set()
    for case in cases:
        question = json.dumps([case["question"], case["history"]], ensure_ascii=False, sort_keys=True)
        base.require(question not in questions, "duplicate question and history")
        questions.add(question)
        base.require(case["split"] == "heldout-20260930", "wrong split")
        base.require(type(case["knowledge"]) is bool, "invalid Knowledge scope")
        docs = {d["id"]: d for d in case["documents"]}
        base.require(len(docs) == len(case["documents"]), "duplicate document ID")
        base.require(identities.isdisjoint(docs), "document reused across heldout cases")
        identities.update(docs)
        for doc in docs.values():
            UUID(doc["id"])
            base.require(doc["revision_sha256"] == base.sha(doc["body_md"].encode()), "incorrect revision hash")
            base.require(doc["state"] in {"current", "deleted"}, "invalid lifecycle state")
        gold = case["gold"]
        base.require(gold["behavior"] in base.BEHAVIORS, "invalid gold behavior")
        for field in ("answer", "rationale", "rubric"):
            base.require(isinstance(gold[field], str) and gold[field].strip(), "empty gold explanation")
        for field in ("required_claims", "forbidden_claims"):
            base.require(gold[field] and all(isinstance(c, str) and c.strip() for c in gold[field]), "missing substantive claims")
        required, forbidden = set(gold["required_doc_ids"]), set(gold["forbidden_doc_ids"])
        base.require(required.isdisjoint(forbidden) and (required | forbidden) <= docs.keys(), "invalid source labels")
        allowed = {d["id"] for d in base.supplied(case)}
        base.require(required <= allowed, "required source outside supplied scope")
        spans = gold["supporting_spans"]
        base.require({s["doc_id"] for s in spans} == required, "missing required source span")
        for span in spans:
            doc = docs[span["doc_id"]]
            start, end = span["byte_start"], span["byte_end"]
            body = doc["body_md"].encode()
            base.require(type(start) is int and type(end) is int and 0 <= start < end <= len(body), "invalid byte span")
            base.require(body[start:end] == span["text"].encode(), "gold span is not exact UTF-8 source bytes")
            base.require(span["revision_sha256"] == doc["revision_sha256"], "gold span revision differs")
        if case["category"] == "conflict":
            base.require(gold["behavior"] == "explain_conflict" and required and required == allowed,
                         "conflict requires all competing source evidence")
        if not case["knowledge"]:
            base.require(not docs and not required and gold["reference_sources"], "general case requires references and no vault evidence")
        base.require(all(h["role"] in {"user", "assistant"} for h in case["history"]), "unsupported history role")
    for split in ("development", "reserved"):
        old, _ = base.fixture(split)
        base.require(identities.isdisjoint(d["id"] for c in old for d in c["documents"]), "old/new document overlap")
        old_questions = {json.dumps([c["question"], c["history"]], ensure_ascii=False, sort_keys=True) for c in old}
        base.require(questions.isdisjoint(old_questions), "old/new question overlap")


def fixture(require_frozen=False):
    manifest_bytes = (DIRECTORY / "manifest.json").read_bytes()
    receipt = json.loads(manifest_bytes)
    data = (DIRECTORY / "cases.json").read_bytes()
    base.require(base.sha(data) == receipt["sha256"], "heldout fixture hash changed")
    if require_frozen:
        base.require(receipt["status"] == "frozen" and receipt["independent_gold_review"]["status"] == "approved",
                     "independent gold review and freeze required before export or scoring")
    for path, digest in receipt["preserved_files"].items():
        base.require(base.sha((ROOT / path).read_bytes()) == digest, f"preserved file changed: {path}")
    cases = json.loads(data)["cases"]
    validate_cases(cases, receipt)
    return cases, receipt["sha256"], base.sha(manifest_bytes)


def export_bytes(cases):
    return "".join(json.dumps(base.export_case(c), ensure_ascii=False, separators=(",", ":")) + "\n" for c in cases).encode()


def score(cases, case_hash, freeze_hash, manifest, outputs, reviews):
    base.require(manifest.get("heldout_freeze_sha256") == freeze_hash, "run freeze receipt differs")
    commit = manifest.get("fixture_commit", "")
    base.require(len(commit) == 40 and all(c in "0123456789abcdef" for c in commit), "missing fixture commit provenance")
    indexed = {c["id"]: c for c in cases}
    for review in reviews:
        base.require(review.get("reviewer_type") in {"human", "ai"}, "declare human or AI review origin")
        if review.get("correct") and review.get("case_id") in indexed:
            base.require(review.get("behavior") == indexed[review["case_id"]]["gold"]["behavior"],
                         "correct grade contradicts expected behavior")
    report = base.score(cases, case_hash, manifest, outputs, reviews)
    report["structural_report_complete"] = report.pop("quality_gate_eligible")
    grades = base.keyed(reviews)
    ok = [o for o in outputs if o["status"] == "ok"]
    report["successful_outputs_human_reviewed"] = bool(ok) and all(
        grades.get((o["case_id"], o["seed"]), {}).get("reviewer_type") == "human" for o in ok)
    report["human_review_complete"] = report["structural_report_complete"] and report["successful_outputs_human_reviewed"]
    report["reviewer_types"] = dict(Counter(r["reviewer_type"] for r in reviews))
    report["quality_gate_status"] = "NOT_DECIDED_BY_THIS_TOOL"
    report["case_set_sha256"] = case_hash
    report["heldout_freeze_sha256"] = freeze_hash
    report["limitations"] += [
        "Completeness is not quality: an all-timeout run can be structurally complete with zero correctness.",
        "Reviewer identity and human/AI origin are declarations, not independently authenticated by this script.",
        "AI-reviewed scores are provisional; the human grading gate remains open.",
        "Supplied history and scope filtering do not execute production context resolution or authorization.",
        "No timing, model-default, retrieval, physical-device or production threshold gate is closed here.",
    ]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate", "export", "score"])
    parser.add_argument("--manifest")
    parser.add_argument("--results")
    parser.add_argument("--reviews")
    args = parser.parse_args()
    cases, case_hash, freeze_hash = fixture(require_frozen=args.command != "validate")
    if args.command == "validate":
        print(json.dumps(dict(cases=len(cases), case_set_sha256=case_hash, freeze_sha256=freeze_hash)))
    elif args.command == "export":
        print(export_bytes(cases).decode(), end="")
    else:
        base.require(all([args.manifest, args.results, args.reviews]), "score needs --manifest, --results, --reviews")
        print(json.dumps(score(cases, case_hash, freeze_hash, json.loads(Path(args.manifest).read_text()),
                               base.rows(args.results), base.rows(args.reviews)), indent=2))


if __name__ == "__main__":
    main()
