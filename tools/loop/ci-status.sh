#!/usr/bin/env bash
# ci-status.sh — the GitHub Actions state of main, in one screen.
#
# Usage:
#   tools/loop/ci-status.sh [--branch <name>] [--limit <n>] [--require-green]
#
# Prints the most recent runs (workflow, status/conclusion, commit) for the
# branch. With --require-green it exits 2 unless the newest COMPLETED run of
# every workflow on that branch concluded "success" (cancelled runs — the
# concurrency group cancels a run when a newer push lands — are skipped, not
# counted as red). Use it at the top of every dispatch wave and after every
# push; the handoff (§4.9, §7.4) says no dispatch while main is red.
#
# Why this exists: CI on main was red on every push from 2026-09-20 to
# 2026-09-21 and nobody looked (bd memory verification-metadata-linux-classifier).
#
# Exit codes: 0 ok, 2 red (with --require-green), 1 gh not available.
set -euo pipefail

branch="main"; limit=8; require=0; ignore=""
while [ $# -gt 0 ]; do
  case "$1" in
    --branch) branch="$2"; shift 2 ;;
    --limit) limit="$2"; shift 2 ;;
    --require-green) require=1; shift ;;
    --ignore) ignore="${ignore:+$ignore,}$2"; shift 2 ;;   # workflow-name substring(s) to exclude from the verdict, e.g. a device-gated lane
    *) sed -n '2,16p' "$0"; exit 1 ;;
  esac
done

command -v gh >/dev/null || { echo "ci-status: gh CLI not found" >&2; exit 1; }
json=$(gh run list --branch "$branch" --limit "$limit" \
  --json workflowName,status,conclusion,headSha,createdAt,url 2>/dev/null) || { echo "ci-status: gh run list failed (auth?)" >&2; exit 1; }

echo "ci-status: branch $branch, newest $limit runs"
verdict=$(python3 - "$json" "$require" "$ignore" <<'PY'
import json, sys
runs = json.loads(sys.argv[1])
require = sys.argv[2] == "1"
ignored = [s for s in sys.argv[3].split(",") if s]
for r in runs:
    print("  {}  {:26}  {:11} {:9}  {}  {}".format(
        r["createdAt"][5:16], r["workflowName"][:26], r["status"],
        r["conclusion"] or "-", r["headSha"][:7], r["url"]))
if not require:
    sys.exit(0)
newest = {}
for r in runs:  # newest first
    if r["status"] != "completed" or r["conclusion"] == "cancelled":
        continue
    if any(s in r["workflowName"] for s in ignored):
        continue
    newest.setdefault(r["workflowName"], r)
red = ["{}: {} @ {}".format(w, r["conclusion"], r["headSha"][:7])
       for w, r in newest.items() if r["conclusion"] != "success"]
if not newest:
    print("VERDICT NO_COMPLETED_RUNS")
elif red:
    print("VERDICT RED " + "; ".join(red))
else:
    print("VERDICT GREEN " + ", ".join("{} @ {}".format(w, r["headSha"][:7]) for w, r in newest.items()))
PY
)
printf '%s\n' "$verdict" | grep -v '^VERDICT '
[ "$require" -eq 1 ] || exit 0
case "$(printf '%s\n' "$verdict" | grep '^VERDICT ' | cut -d' ' -f2-)" in
  GREEN*) echo "ci-status: $(printf '%s\n' "$verdict" | grep '^VERDICT ' | cut -d' ' -f2-)"; exit 0 ;;
  NO_COMPLETED_RUNS) echo "ci-status: no completed, non-cancelled run yet — wait for one before dispatching" >&2; exit 2 ;;
  *) echo "ci-status: $(printf '%s\n' "$verdict" | grep '^VERDICT ' | cut -d' ' -f2-)" >&2; exit 2 ;;
esac
