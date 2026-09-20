#!/usr/bin/env python3
"""JSON result schema, normalizer, and per-cell writer for the M0 harness.

Every benchmark cell — successful, dry-run, or failed — writes exactly one
JSON file matching SCHEMA_FIELDS (handoff §14 "Provenance per cell").
Failed cells (OOM, timeout, thermal-abort) are valid results: they carry
`exit_status` != 0 and a `failure_reason`. There are no silent retries and
no cell is skipped just because it failed.

Also provides `aggregate`, which walks a completed output directory and
writes docs/MEASUREMENTS.md — this replaces the old, now-deleted
collect-results.py, updated for the new per-cell schema and directory
layout (output/<date>/<cell>/result.json instead of output/<ts>-<model>-...).
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]

# Handoff §14 "Provenance per cell", plus `failure_reason` (required only
# when exit_status != 0) and `is_dry_run` (set by --dry-run cells).
SCHEMA_FIELDS = [
    "device_build_fingerprint",
    "android_version",
    "android_security_patch",
    "llama_cpp_commit",
    "binary_sha256",
    "model_sha256",
    "model_quantization",
    "backend",
    "cmdline",
    "context_length",
    "gpu_layers",
    "threads",
    "prompt_target",
    "gen_target",
    "repeat_index",
    "thermal_start",
    "thermal_end",
    "pp_toks_per_s",
    "tg_toks_per_s",
    "ttft_ms",
    "peak_rss_mb",
    "battery_state",
    "charging_state",
    "exit_status",
    "timestamp_start",
    "timestamp_end",
]

# Fields that may legitimately be null/absent on a valid result (e.g. a
# failed cell has no throughput numbers; a dry-run cell has no real
# telemetry). Every other schema field must be present in the dict passed
# to build_result, even if its value is None.
OPTIONAL_WHEN_NULL = {
    "pp_toks_per_s",
    "tg_toks_per_s",
    "ttft_ms",
    "peak_rss_mb",
    "thermal_end",
}


class SchemaError(ValueError):
    pass


def build_result(fields: dict[str, Any], *, failure_reason: str | None = None,
                  is_dry_run: bool = False) -> dict[str, Any]:
    """Build one schema-conformant cell result dict.

    Raises SchemaError if a required field is missing from `fields`.
    """
    missing = [f for f in SCHEMA_FIELDS if f not in fields]
    if missing:
        raise SchemaError(f"missing required schema field(s): {', '.join(missing)}")

    result = {f: fields[f] for f in SCHEMA_FIELDS}

    exit_status = result.get("exit_status")
    is_failure = exit_status not in (0, "0", None) if not is_dry_run else False
    if is_failure and not failure_reason:
        raise SchemaError("exit_status != 0 requires a failure_reason")
    if failure_reason:
        result["failure_reason"] = failure_reason
    if is_dry_run:
        result["is_dry_run"] = True

    validate_result(result)
    return result


def validate_result(result: dict[str, Any]) -> None:
    """Validate a result dict against the schema. Raises SchemaError."""
    missing = [f for f in SCHEMA_FIELDS if f not in result]
    if missing:
        raise SchemaError(f"missing required schema field(s): {', '.join(missing)}")

    for f in SCHEMA_FIELDS:
        if f in OPTIONAL_WHEN_NULL:
            continue
        if result[f] is None:
            raise SchemaError(f"field '{f}' must not be null on a valid result")

    exit_status = result.get("exit_status")
    is_dry_run = bool(result.get("is_dry_run"))
    if not is_dry_run and exit_status not in (0, "0") and "failure_reason" not in result:
        raise SchemaError("exit_status != 0 requires a failure_reason")


def write_cell_result(result: dict[str, Any], out_path: Path) -> None:
    """Validate and write one cell result as pretty-printed JSON."""
    validate_result(result)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")


def normalize_llama_bench(raw_stdout: str) -> dict[str, float | None]:
    """Extract pp/tg throughput from `llama-bench --output json` stdout.

    llama-bench's JSON mode emits an array of per-configuration objects.
    Each object has n_prompt/n_gen (one of which is 0) and an average
    tokens/sec field. Field names have shifted across llama.cpp releases
    (avg_ts vs. avg_ns-derived), so this accepts either and returns
    {"pp_toks_per_s": ..., "tg_toks_per_s": ...} with None for whichever
    side wasn't measured.
    """
    pp_tps: float | None = None
    tg_tps: float | None = None

    try:
        rows = json.loads(raw_stdout)
    except json.JSONDecodeError as exc:
        raise SchemaError(f"could not parse llama-bench JSON output: {exc}") from exc

    if isinstance(rows, dict):
        rows = [rows]

    for row in rows:
        n_prompt = row.get("n_prompt", 0) or 0
        n_gen = row.get("n_gen", 0) or 0
        tps = row.get("avg_ts")
        if tps is None and row.get("avg_ns"):
            # avg_ns is nanoseconds per token batch; tokens/sec = n / (ns/1e9)
            n_tokens = n_prompt or n_gen
            if n_tokens and row["avg_ns"] > 0:
                tps = n_tokens / (row["avg_ns"] / 1e9)

        if n_prompt and not n_gen:
            pp_tps = tps
        elif n_gen and not n_prompt:
            tg_tps = tps

    return {"pp_toks_per_s": pp_tps, "tg_toks_per_s": tg_tps}


def _load_cell_results(output_dir: Path) -> list[dict[str, Any]]:
    """Load every result.json under output_dir.

    output_dir is expected to be a single run's directory —
    output/<date>/ or output/dryrun/ — with cells directly inside it
    (output/<date>/<cell>/result.json), matching what run.sh writes and
    what it prints as the "aggregate with: ..." hint. Glob one level deep
    only; do not point this at the output/ root across multiple dates.
    """
    results = []
    if not output_dir.exists():
        return results
    for result_path in sorted(output_dir.glob("*/result.json")):
        try:
            results.append(json.loads(result_path.read_text()))
        except json.JSONDecodeError:
            continue
    return results


def render_report(results: list[dict[str, Any]]) -> str:
    lines: list[str] = [
        "# M0 Measurements — Skein",
        "",
        "> Generated by `tools/m0-benchmark/lib/collect.py aggregate`.",
        "> Every row cites its source cell directory in `tools/m0-benchmark/output/`.",
        "",
    ]

    real_results = [r for r in results if not r.get("is_dry_run")]
    if not real_results:
        lines += [
            "**No non-dry-run cells yet.** Run `tools/m0-benchmark/run.sh --smoke` "
            "(then `--formal` once the Q4_K_M artifact is pinned) on a "
            "device-connected workstation first.",
            "",
        ]
        return "\n".join(lines)

    lines += [
        "## Results",
        "",
        "| Backend | Model quant | Context | Repeat | PP tok/s | TG tok/s | "
        "TTFT (ms) | Peak RSS (MB) | Exit | Thermal start → end |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in real_results:
        def fmt(v, digits=1):
            return f"{v:.{digits}f}" if isinstance(v, (int, float)) else "—"

        lines.append(
            "| {backend} | {quant} | {ctx} | {rep} | {pp} | {tg} | {ttft} | "
            "{rss} | {exit} | {t0} → {t1} |".format(
                backend=r.get("backend", "—"),
                quant=r.get("model_quantization", "—"),
                ctx=r.get("context_length", "—"),
                rep=r.get("repeat_index", "—"),
                pp=fmt(r.get("pp_toks_per_s")),
                tg=fmt(r.get("tg_toks_per_s")),
                ttft=fmt(r.get("ttft_ms"), 0),
                rss=fmt(r.get("peak_rss_mb"), 0),
                exit=r.get("exit_status", "—"),
                t0=r.get("thermal_start", "—"),
                t1=r.get("thermal_end", "—"),
            )
        )

    failed = [r for r in real_results if r.get("exit_status") not in (0, "0")]
    if failed:
        lines += ["", "## Failed cells", ""]
        for r in failed:
            lines.append(
                f"- backend={r.get('backend')} context={r.get('context_length')} "
                f"repeat={r.get('repeat_index')}: {r.get('failure_reason', 'unknown')}"
            )

    lines.append("")
    return "\n".join(lines)


def aggregate(output_dir: Path, report_path: Path) -> int:
    results = _load_cell_results(output_dir)
    report = render_report(results)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report)
    return len(results)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_agg = sub.add_parser("aggregate", help="write docs/MEASUREMENTS.md from output/")
    p_agg.add_argument("output_dir", type=Path)
    p_agg.add_argument("--report", type=Path, default=REPO_ROOT / "docs" / "MEASUREMENTS.md")

    p_write = sub.add_parser(
        "write", help="read a JSON field blob on stdin, validate it, write one cell result"
    )
    p_write.add_argument("--out", type=Path, required=True)

    p_norm = sub.add_parser(
        "normalize", help="extract pp/tg tok/s from a llama-bench --output json stdout log"
    )
    p_norm.add_argument("stdout_path", type=Path)

    args = ap.parse_args(argv)

    if args.cmd == "aggregate":
        n = aggregate(args.output_dir, args.report)
        print(f"wrote {args.report} ({n} cells)")
        return 0

    if args.cmd == "write":
        try:
            payload = json.loads(sys.stdin.read())
        except json.JSONDecodeError as exc:
            print(f"invalid JSON on stdin: {exc}", file=sys.stderr)
            return 1
        failure_reason = payload.pop("failure_reason", None)
        is_dry_run = bool(payload.pop("is_dry_run", False))
        try:
            result = build_result(payload, failure_reason=failure_reason, is_dry_run=is_dry_run)
        except SchemaError as exc:
            print(f"schema error: {exc}", file=sys.stderr)
            return 1
        write_cell_result(result, args.out)
        print(f"wrote {args.out}")
        return 0

    if args.cmd == "normalize":
        try:
            raw = args.stdout_path.read_text()
            metrics = normalize_llama_bench(raw)
        except (OSError, SchemaError) as exc:
            print(json.dumps({"error": str(exc)}))
            return 1
        print(json.dumps(metrics))
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
