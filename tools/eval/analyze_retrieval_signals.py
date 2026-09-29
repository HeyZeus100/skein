#!/usr/bin/env python3
"""Development-only, query-level rejection sweeps over preserved production results.

No reranking or relabelling: a rejected query loses its entire original result
list. This cannot measure independent validation, embeddings or answer accuracy.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

# Grammatical function words only; no corpus topics, identifiers or answer terms.
STOP_WORDS = frozenset("""a an the of for to in on at by with from is are was were
be been being do does did can could would should will shall may might must i me
my we our you your he she it they them their this that these those what where
when who why how which and or but if as than then there here not no""".split())


def terms(text):
    return set(re.findall(r"[^\W_]+", text.lower())) - STOP_WORDS


def coverage(query, result):
    wanted = terms(query)
    present = terms(result["text"])
    return len(wanted & present) / len(wanted) if wanted else 0.0


def summarize(rows, threshold, feature):
    answerable = [row for row in rows if row["answerable"]]
    absent = [row for row in rows if not row["answerable"]]
    rejected = lambda row: not row["candidate_count"] or row[feature] < threshold
    return {
        "threshold": threshold,
        "answerable_queries": len(answerable),
        "absence_queries": len(absent),
        "rejected_absence_queries": sum(rejected(row) for row in absent),
        "falsely_rejected_answerable_queries": sum(rejected(row) for row in answerable),
        "recall_at_8_after_query_gate": (
            sum(0.0 if rejected(row) else row["recall_at_8"] for row in answerable) / len(answerable)
            if answerable else None),
        "ndcg_at_8_after_query_gate": (
            sum(0.0 if rejected(row) else row["ndcg_at_8"] for row in answerable) / len(answerable)
            if answerable else None),
    }


def analyze(report_path, gold_path):
    report_bytes, gold_bytes = report_path.read_bytes(), gold_path.read_bytes()
    report, gold = json.loads(report_bytes), json.loads(gold_bytes)
    if report["gold_sha256"] != hashlib.sha256(gold_bytes).hexdigest():
        raise ValueError("gold differs from measured report")
    if gold["split"] != "development" or report["split"] != "development":
        raise ValueError("threshold exploration is restricted to development data")
    queries = {query["id"]: query for query in gold["queries"]}
    modes = {}
    for mode in report["modes"]:
        if len(mode["queries"]) != len(queries) or {row["id"] for row in mode["queries"]} != set(queries):
            raise ValueError("missing or duplicate development queries")
        rows = []
        for measured in mode["queries"]:
            hits = measured["runs"][0]["results"]
            rows.append(dict(
                id=measured["id"], category=measured["category"], answerable=measured["answerable"],
                candidate_count=len(hits), recall_at_8=measured["recall_at_8"], ndcg_at_8=measured["ndcg_at_8"],
                max_lexical_score=max((hit["recall_scores"].get("LEXICAL", 0.0) for hit in hits), default=0.0),
                max_query_coverage=max((coverage(queries[measured["id"]]["query"], hit) for hit in hits), default=0.0),
            ))
        modes[mode["name"]] = {
            "rows": rows,
            "coverage_sweep": [summarize(rows, threshold, "max_query_coverage") for threshold in (0, .25, .5, .75, 1)],
            "raw_bm25_sweep": [summarize(rows, threshold, "max_lexical_score") for threshold in (0, .00001, 1, 5, 10, 15, 20)],
            "categories_at_coverage_half": {
                category: summarize([row for row in rows if row["category"] == category], .5, "max_query_coverage")
                for category in sorted({row["category"] for row in rows})
            },
        }
    return {
        "schema_version": 1, "purpose": "development calibration, not independent validation",
        "source_report_sha256": hashlib.sha256(report_bytes).hexdigest(),
        "gold_sha256": report["gold_sha256"], "declared_build_revision": report["build_revision"],
        "selection": "query-level gate; original rankings and gold retained",
        "stop_words": sorted(STOP_WORDS), "modes": modes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--gold", type=Path, default=Path("testing/src/main/resources/eval/gold.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.report, args.gold)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    main()
