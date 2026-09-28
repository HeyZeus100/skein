#!/usr/bin/env python3
"""Validate/export synthetic cases and report measured answers with human grades.

No network or model calls. Unreviewed answers never count as factually correct.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "testing/src/main/resources/eval/answers"
STATUSES = {"ok", "timeout", "oom", "error"}
BEHAVIORS = {"answer", "abstain", "explain_conflict"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fixture(split):
    manifest = json.loads((FIXTURES / "manifest.json").read_text())
    entry = manifest["splits"][split]
    data = (FIXTURES / entry["file"]).read_bytes()
    require(sha(data) == entry["sha256"], f"{split}: frozen fixture hash changed")
    cases = json.loads(data)["cases"]
    require(len(cases) == entry["cases"], "case count changed")
    require(dict(Counter(c["category"] for c in cases)) == entry["categories"], "category coverage changed")
    require(len({c["id"] for c in cases}) == len(cases), "duplicate case ID")
    for case in cases:
        docs = {d["id"]: d for d in case["documents"]}
        require(len(docs) == len(case["documents"]), "duplicate document ID")
        for doc in docs.values():
            UUID(doc["id"])
            require(doc["revision_sha256"] == sha(doc["body_md"].encode()), "incorrect revision hash")
            require(doc["state"] in {"current", "deleted"}, "invalid lifecycle state")
        gold = case["gold"]
        require(gold["behavior"] in BEHAVIORS, "invalid gold behavior")
        require(set(gold["required_doc_ids"]).isdisjoint(gold["forbidden_doc_ids"]), "contradictory labels")
        require(set(gold["required_doc_ids"] + gold["forbidden_doc_ids"]) <= docs.keys(), "unknown gold source")
        for key in gold["required_doc_ids"]:
            require(docs[key]["state"] == "current" and docs[key]["space"] == case["space"], "required source outside scope")
        require(all(h["role"] in {"user", "assistant"} for h in case["history"]), "unsupported history role")
    return cases, entry["sha256"]


def supplied(case):
    return [d for d in case["documents"] if case["knowledge"] and d["state"] == "current" and d["space"] == case["space"]]


def export_case(case):
    return dict(schema_version=1, case_id=case["id"], scope="knowledge" if case["knowledge"] else "general",
                query=case["question"], history=[dict(role=h["role"], content=h["content"]) for h in case["history"]],
                sources=[dict(chunk_id=i, doc_id=d["id"], revision_hash=d["revision_sha256"],
                              title=d["title"], text=d["body_md"], score=1.0, source_kind="NOTE",
                              byte_start=0, byte_end=len(d["body_md"].encode()))
                         for i, d in enumerate(supplied(case), 1)])


def rows(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]


def keyed(values):
    result = {}
    for value in values:
        key = (value["case_id"], value["seed"])
        require(key not in result, f"duplicate result/review: {key}")
        result[key] = value
    return result


def ratio(successes, total):
    if total == 0:
        return dict(numerator=successes, denominator=0, rate=None, wilson_95=None)
    p, z = successes / total, 1.959963984540054
    mid = (p + z*z/(2*total)) / (1 + z*z/total)
    radius = z * math.sqrt(p*(1-p)/total + z*z/(4*total*total)) / (1 + z*z/total)
    return dict(numerator=successes, denominator=total, rate=p, wilson_95=[mid-radius, mid+radius])


def score(cases, case_hash, manifest, outputs, reviews):
    require(manifest.get("engine_path") == "isolated-apk", "quality report requires isolated-apk provenance")
    require(manifest.get("mode") == "supplied_evidence", "this report scores supplied evidence, not retrieval")
    require(manifest.get("case_set_sha256") == case_hash, "run case set differs from frozen fixture")
    exported = "".join(json.dumps(export_case(c), ensure_ascii=False, separators=(",", ":")) + "\n" for c in cases)
    require(manifest.get("fixture_sha256") == sha(exported.encode()), "run input differs from exported fixture")
    for field in ("model_sha256", "template_sha256", "fixture_sha256", "apk_sha256"):
        value = manifest.get(field, "")
        require(len(value) == 64 and all(c in "0123456789abcdef" for c in value), f"missing/invalid {field}")
    for field in ("run_id", "build_sha", "llama_sha", "device", "sampling", "context"):
        require(manifest.get(field), f"missing provenance: {field}")
    seeds = manifest["seeds"]
    require(seeds and len(seeds) == len(set(seeds)) and all(type(s) is int for s in seeds), "invalid seeds")
    expected = {(c["id"], seed) for c in cases for seed in seeds}
    output = keyed(outputs)
    review = keyed(reviews)
    require(output.keys() <= expected and review.keys() <= output.keys(), "unknown case/seed or review without result")
    counters, structural = Counter(), []
    categories = {}
    claims = Counter()
    for case in cases:
        for seed in seeds:
            key = (case["id"], seed)
            row, grade = output.get(key), review.get(key)
            status = row["status"] if row else "missing"
            require(status in STATUSES | {"missing"}, "unknown result status")
            counters[status] += 1
            category = categories.setdefault(case["category"], Counter())
            category["attempted"] += 1
            if row:
                require(row.get("run_id") == manifest["run_id"], "mixed run IDs")
                require(isinstance(row.get("answer"), str), "missing answer text")
                require(all(isinstance(row.get(k), list) for k in ("used_sources", "citations")), "missing source metadata")
                allowed = {(d["id"], d["revision_sha256"]): d["body_md"] for d in supplied(case)}
                for field in ("used_sources", "citations", "exact_quotes"):
                    for source in row.get(field, []):
                        identity = (source["doc_id"], source["revision_hash"])
                        if identity not in allowed:
                            structural.append(dict(case_id=key[0], seed=seed, problem=f"{field}: source/revision was not supplied"))
                        elif field == "exact_quotes" and (not source.get("text") or source["text"] not in allowed[identity]):
                            structural.append(dict(case_id=key[0], seed=seed, problem="quote is not an exact source substring"))
            if status != "ok":
                continue  # Runtime failures stay in attempted denominators.
            if not grade:
                counters["unreviewed"] += 1
                continue
            require(grade.get("reviewer") and grade.get("notes"), "human review needs reviewer and evidence notes")
            require(type(grade.get("correct")) is bool, "review correctness must be boolean")
            require(grade.get("behavior") in BEHAVIORS, "review behavior invalid")
            counters["reviewed"] += 1
            category["reviewed"] += 1
            if grade["correct"]:
                counters["verified_correct"] += 1
                category["verified_correct"] += 1
                counters["verified_answerable_correct"] += int(case["gold"]["behavior"] != "abstain")
            if case["gold"]["behavior"] == "abstain":
                counters["reviewed_missing_evidence"] += 1
                counters["appropriate_abstention"] += int(grade["correct"] and grade["behavior"] == "abstain")
            else:
                counters["reviewed_answerable"] += 1
                counters["false_abstention"] += int(grade["behavior"] == "abstain")
            for numerator, denominator in [("supported_cited_claims", "cited_claims"), ("claims_with_citations", "claims_requiring_citations")]:
                a, b = grade.get(numerator), grade.get(denominator)
                require(type(a) is int and type(b) is int and 0 <= a <= b, "invalid claim annotation counts")
                claims[numerator] += a
                claims[denominator] += b
    missing_evidence = sum(c["gold"]["behavior"] == "abstain" for c in cases) * len(seeds)
    return dict(schema_version=1, run_id=manifest["run_id"], mode="supplied_evidence",
                quality_gate_eligible=counters["unreviewed"] == 0 and counters["missing"] == 0 and not structural,
                counts=dict(counters), structural_failures=structural,
                verified_correct_lower_bound=ratio(counters["verified_correct"], len(expected)),
                answerable_correct_lower_bound=ratio(counters["verified_answerable_correct"], len(expected) - missing_evidence),
                appropriate_abstention_lower_bound=ratio(counters["appropriate_abstention"], missing_evidence),
                false_abstention_reviewed=ratio(counters["false_abstention"], counters["reviewed_answerable"]),
                cited_claim_support_reviewed=ratio(claims["supported_cited_claims"], claims["cited_claims"]),
                citation_coverage_reviewed=ratio(claims["claims_with_citations"], claims["claims_requiring_citations"]),
                per_category={k: dict(v) for k, v in categories.items()},
                limitations=["A recorded source ID does not prove a claim is supported.",
                             "Quote metadata may omit prose quotations; reviewers must inspect the answer.",
                             "Supplied-evidence evaluation does not measure retrieval or lifecycle correctness.",
                             "Repeated seeds and templated cases are correlated; Wilson intervals are descriptive, not independent benchmark confidence."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate", "export", "score"])
    parser.add_argument("--split", choices=["development", "reserved"], default="development")
    parser.add_argument("--manifest")
    parser.add_argument("--results")
    parser.add_argument("--reviews")
    args = parser.parse_args()
    cases, case_hash = fixture(args.split)
    if args.command == "validate":
        other, _ = fixture("reserved" if args.split == "development" else "development")
        require({d["id"] for c in cases for d in c["documents"]}.isdisjoint({d["id"] for c in other for d in c["documents"]}), "split overlap")
        print(json.dumps(dict(split=args.split, cases=len(cases), case_set_sha256=case_hash)))
    elif args.command == "export":
        for case in cases:
            print(json.dumps(export_case(case), ensure_ascii=False, separators=(",", ":")))
    else:
        require(all([args.manifest, args.results, args.reviews]), "score needs --manifest, --results, --reviews")
        print(json.dumps(score(cases, case_hash, json.loads(Path(args.manifest).read_text()), rows(args.results), rows(args.reviews)), indent=2))


if __name__ == "__main__":
    main()
