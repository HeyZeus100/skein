#!/usr/bin/env bash
# handback-check.sh — verify an agent hand-back against git before merging it.
#
# Usage:
#   tools/loop/handback-check.sh <topic-sha-or-branch> [--base <ref>] [--expect-tests] [--expect <path> ...]
#
# Prints the files the topic branch changed relative to the merge base with
# main, then applies the checks the coordinator used to do by hand:
#   --expect-tests      fail unless at least one changed file is a test source
#                       (src/test, src/androidTest, or *Test.kt)
#   --expect <path>     fail unless <path> (substring match) is among the
#                       changed files; repeatable — use it for every file the
#                       hand-back claims to have written
#   --base <ref>        compare against this ref instead of main (default main)
#
# Exit codes: 0 all checks passed, 2 a check failed, 1 usage / git error.
#
# Why this exists: on 2026-09-21 an agent reported "AC tests written,
# verification PASS" for a commit that contained zero test files
# (bd skein-fsn; bd memory verify-claimed-tests-exist). Report text is not
# evidence; the diff is.
set -euo pipefail

usage() { sed -n '2,20p' "$0"; exit 1; }

[ $# -ge 1 ] || usage
topic="$1"; shift
base="main"
expect_tests=0
expects=()
while [ $# -gt 0 ]; do
  case "$1" in
    --base) base="$2"; shift 2 ;;
    --expect-tests) expect_tests=1; shift ;;
    --expect) expects+=("$2"); shift 2 ;;
    *) echo "handback-check: unknown argument $1" >&2; usage ;;
  esac
done

git rev-parse --verify --quiet "$topic^{commit}" >/dev/null || { echo "handback-check: $topic is not a commit" >&2; exit 1; }
mb=$(git merge-base "$base" "$topic")
# macOS ships bash 3.2 (no `mapfile`); keep the list in a newline-separated string.
if git merge-base --is-ancestor "$topic" "$base"; then
  # Already merged: the merge-base IS the topic, so diff the commit against its
  # own first parent instead (post-hoc audit of a single hand-back commit).
  status=$(git diff --name-status "$topic^" "$topic")
  echo "handback-check: $topic is already on $base — showing that commit's own changes (vs $(git rev-parse --short "$topic^"))"
else
  status=$(git diff --name-status "$mb" "$topic")
  echo "handback-check: $topic vs merge-base $(git rev-parse --short "$mb") of $base"
fi
# A deleted file is not evidence of anything the hand-back claims to have
# written — the fabricated fsn commit deleted its only test while claiming
# "AC tests" — so only added/modified/renamed/copied paths count.
files=$(printf '%s\n' "$status" | awk '$1 !~ /^D/ { print $NF }')
count=$(printf '%s\n' "$files" | grep -c . || true)
deleted=$(printf '%s\n' "$status" | awk '$1 ~ /^D/ { print $NF }')
echo "handback-check: $count added/modified file(s)$( [ -n "$deleted" ] && printf ', %s deleted (ignored)' "$(printf '%s\n' "$deleted" | grep -c .)" )"
printf '%s\n' "$status" | sed 's/^/  /'

rc=0
if [ "$expect_tests" -eq 1 ]; then
  if printf '%s\n' "$files" | grep -qE '(^|/)src/(test|androidTest)/|Test\.kt$'; then
    echo "handback-check: OK — test sources present"
  else
    echo "handback-check: FAIL — hand-back claims tests but the diff has no test sources" >&2
    rc=2
  fi
fi
for e in ${expects[@]+"${expects[@]}"}; do
  if printf '%s\n' "$files" | grep -qF -- "$e"; then
    echo "handback-check: OK — found $e"
  else
    echo "handback-check: FAIL — claimed file not in diff: $e" >&2
    rc=2
  fi
done
exit "$rc"
