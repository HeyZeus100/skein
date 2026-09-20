#!/usr/bin/env python3
"""bd_bootstrap.py — seed a beads (bd) tracker from the Skein v1 plan document.

The plan document is the single source of truth. Every epic and issue in it
carries a fenced ```bd block:

    #### E4.I3 — `:inference` isolated service
    ```bd
    key: E4.I3
    milestone: M1
    type: task
    tier: opus
    hours: 16
    priority: 1
    labels: blocks-others
    deps: E0.I18, E4.I1
    ```
    **Description:** ...
    **Acceptance criteria:**
    - ...

Usage:
    python3 tools/bd_bootstrap.py PLAN.md --out skein-plan.json      # emit graph JSON only
    python3 tools/bd_bootstrap.py PLAN.md --apply                     # emit + `bd create --graph`
    python3 tools/bd_bootstrap.py PLAN.md --check                     # validate keys/deps/cycles, no output

Requires: Python 3.9+, `bd` 1.0.0+ on PATH for --apply. No third-party modules.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

HEADING_RE = re.compile(r"^#{3,5}\s+(E\d+(?:\.I\d+)?)\s+[—-]+\s+(.+?)\s*$")
BD_FENCE_OPEN = "```bd"
FENCE_CLOSE = "```"
VALID_TIERS = {"haiku", "sonnet", "opus", "fable"}
VALID_LABELS = {"parallel-safe", "needs-hardware", "needs-human-review", "blocks-others", "spike", "docs"}
VALID_MILESTONES = {"M0", "M0.5", "M1", "M2", "M3", "M4"}
VALID_TYPES = {"epic", "task", "feature", "bug", "chore", "decision"}


def parse_plan(text: str) -> list[dict]:
    lines = text.splitlines()
    issues: list[dict] = []
    i = 0
    current: dict | None = None
    while i < len(lines):
        line = lines[i]
        if line.startswith("#"):
            m = HEADING_RE.match(line)
            if m:
                current = {"key": m.group(1), "title": m.group(2).replace("`", "").strip(), "meta": {}, "description": "", "acceptance": ""}
                issues.append(current)
            else:
                current = None  # any other heading (or a code line starting with '#') ends the current issue
            i += 1
            continue
        # A bd fence must start at column 0; indented copies (e.g. inside this script's docstring) are ignored.
        if current is not None and line.rstrip() == BD_FENCE_OPEN:
            i += 1
            while i < len(lines) and lines[i].strip() != FENCE_CLOSE:
                kv = lines[i].split("#", 1)[0].strip()
                if ":" in kv:
                    k, v = kv.split(":", 1)
                    current["meta"][k.strip()] = v.strip()
                i += 1
            i += 1
            continue
        if current is not None and line.startswith("**Description:**"):
            buf = [line[len("**Description:**"):].strip()]
            i += 1
            while i < len(lines) and not lines[i].startswith("**") and not HEADING_RE.match(lines[i]) and not lines[i].startswith("#"):
                buf.append(lines[i])
                i += 1
            current["description"] = "\n".join(buf).strip()
            continue
        if current is not None and line.startswith("**Acceptance criteria:**"):
            buf = []
            i += 1
            while i < len(lines) and not lines[i].startswith("**") and not HEADING_RE.match(lines[i]) and not lines[i].startswith("#"):
                buf.append(lines[i])
                i += 1
            current["acceptance"] = "\n".join(buf).strip()
            continue
        i += 1
    return issues


def validate(issues: list[dict]) -> list[str]:
    errors: list[str] = []
    keys = {iss["key"] for iss in issues}
    dup = defaultdict(int)
    for iss in issues:
        dup[iss["key"]] += 1
    errors += [f"duplicate key {k}" for k, n in dup.items() if n > 1]
    for iss in issues:
        m = iss["meta"]
        k = iss["key"]
        if m.get("key") != k:
            errors.append(f"{k}: bd block key '{m.get('key')}' does not match heading")
        if m.get("type", "task") not in VALID_TYPES:
            errors.append(f"{k}: invalid type {m.get('type')}")
        if m.get("type") != "epic":
            if m.get("tier") not in VALID_TIERS:
                errors.append(f"{k}: invalid tier {m.get('tier')}")
            if m.get("milestone") not in VALID_MILESTONES:
                errors.append(f"{k}: invalid milestone {m.get('milestone')}")
            try:
                float(m.get("hours", ""))
            except ValueError:
                errors.append(f"{k}: hours missing/invalid")
            if not iss["acceptance"]:
                errors.append(f"{k}: missing acceptance criteria")
        if not iss["description"]:
            errors.append(f"{k}: missing description")
        for lab in split_list(m.get("labels", "")):
            if lab not in VALID_LABELS:
                errors.append(f"{k}: unknown label {lab}")
        for dep in split_list(m.get("deps", "")):
            if dep not in keys:
                errors.append(f"{k}: unknown dep {dep}")
            if dep == k:
                errors.append(f"{k}: depends on itself")
        if "." in k:
            parent = k.split(".")[0]
            if parent not in keys:
                errors.append(f"{k}: parent epic {parent} not found")
    # cycle detection (issue-level blocks edges + implicit epic<-child)
    adj: dict[str, list[str]] = defaultdict(list)
    for iss in issues:
        for dep in split_list(iss["meta"].get("deps", "")):
            adj[iss["key"]].append(dep)
        if "." in iss["key"]:
            adj[iss["key"].split(".")[0]].append(iss["key"])
    state: dict[str, int] = {}

    def dfs(n: str, stack: list[str]) -> None:
        state[n] = 1
        stack.append(n)
        for nxt in adj.get(n, []):
            if state.get(nxt, 0) == 1:
                errors.append("cycle: " + " -> ".join(stack[stack.index(nxt):] + [nxt]))
            elif state.get(nxt, 0) == 0:
                dfs(nxt, stack)
        stack.pop()
        state[n] = 2

    for iss in issues:
        if state.get(iss["key"], 0) == 0:
            dfs(iss["key"], [])
    return errors


def split_list(s: str) -> list[str]:
    return [x.strip() for x in s.split(",") if x.strip()]


def to_graph(issues: list[dict]) -> dict:
    nodes = []
    edges = []
    for iss in issues:
        m = iss["meta"]
        key = iss["key"]
        is_epic = m.get("type") == "epic"
        labels = split_list(m.get("labels", ""))
        if not is_epic:
            labels += [f"tier:{m['tier']}", f"milestone:{m['milestone']}"]
        node = {
            "key": key,
            "title": f"{key} {iss['title']}",
            "type": m.get("type", "task"),
            "priority": int(m.get("priority", 2)),
            "labels": labels,
            "description": iss["description"],
            "acceptance_criteria": iss["acceptance"],
            "metadata": {
                "plan_key": json.dumps(key),
                "milestone": json.dumps(m.get("milestone", "")),
                "tier": json.dumps(m.get("tier", "")),
                "hours": json.dumps(m.get("hours", "")),
            },
        }
        if not is_epic:
            node["estimate"] = int(round(float(m["hours"]) * 60))
            node["parent"] = key.split(".")[0]
            # `parent` alone builds the hierarchy but does not block (verified bd 1.0.0). An explicit
            # parent-child edge makes the epic depend on the child, so epics never show up in `bd ready`.
            edges.append({"from_key": key.split(".")[0], "to_key": key, "type": "parent-child"})
        nodes.append(node)
        for dep in split_list(m.get("deps", "")):
            edges.append({"from_key": key, "to_key": dep, "type": "blocks"})
    return {"commit_message": "Seed Skein v1 plan", "nodes": nodes, "edges": edges}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("plan", type=Path)
    ap.add_argument("--out", type=Path, default=Path("skein-plan.json"))
    ap.add_argument("--apply", action="store_true", help="run `bd create --graph` after writing JSON")
    ap.add_argument("--check", action="store_true", help="validate only")
    args = ap.parse_args()
    issues = parse_plan(args.plan.read_text(encoding="utf-8"))
    errors = validate(issues)
    n_epics = sum(1 for i in issues if i["meta"].get("type") == "epic")
    total_hours = sum(float(i["meta"].get("hours", 0) or 0) for i in issues if i["meta"].get("type") != "epic")
    print(f"parsed {len(issues)} nodes ({n_epics} epics, {len(issues) - n_epics} issues), {total_hours:.0f} estimated hours")
    if errors:
        print("VALIDATION ERRORS:")
        for e in errors:
            print("  -", e)
        return 1
    if args.check:
        return 0
    graph = to_graph(issues)
    args.out.write_text(json.dumps(graph, indent=1), encoding="utf-8")
    print(f"wrote {args.out} ({len(graph['nodes'])} nodes, {len(graph['edges'])} edges)")
    if args.apply:
        # NOTE (bd 1.0.0, verified 2026-09-19): `bd create --graph` ignores --dry-run (always creates),
        # and drops `acceptance_criteria` / `estimate` from nodes. We backfill both with `bd update`.
        res = subprocess.run(["bd", "create", "--graph", str(args.out)], text=True, capture_output=True)
        sys.stdout.write(res.stdout)
        sys.stderr.write(res.stderr)
        if res.returncode != 0:
            return res.returncode
        key_to_id: dict[str, str] = {}
        for line in res.stdout.splitlines():
            mm = re.match(r"^\s*(\S+)\s+->\s+(\S+)\s*$", line)
            if mm:
                key_to_id[mm.group(1)] = mm.group(2)
        ids_path = args.out.with_suffix(".ids.json")
        ids_path.write_text(json.dumps(key_to_id, indent=1, sort_keys=True), encoding="utf-8")
        print(f"wrote {ids_path} ({len(key_to_id)} key->id mappings)")
        missing = [iss["key"] for iss in issues if iss["key"] not in key_to_id]
        if missing:
            print("WARNING: no id returned for:", ", ".join(missing))
        for iss in issues:
            if iss["meta"].get("type") == "epic":
                continue
            bd_id = key_to_id.get(iss["key"])
            if not bd_id:
                continue
            cmd = ["bd", "update", bd_id, "--estimate", str(int(round(float(iss["meta"]["hours"]) * 60)))]
            if iss["acceptance"]:
                cmd += ["--acceptance", iss["acceptance"]]
            up = subprocess.run(cmd, text=True, capture_output=True)
            if up.returncode != 0:
                print(f"WARNING: backfill failed for {iss['key']} ({bd_id}): {up.stderr.strip()}")
        print("backfilled acceptance criteria + estimates")
        subprocess.run(["bd", "dep", "cycles"], check=False)
        subprocess.run(["bd", "stats"], check=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
