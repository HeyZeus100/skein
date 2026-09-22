#!/usr/bin/env bash
# worktree-gc.sh — list agent worktrees whose work is already on main, and
# optionally remove them the safe way.
#
# Usage:
#   tools/loop/worktree-gc.sh [--root <dir>] [--base <ref>] [--apply]
#
# For every git worktree under --root (default: this session's scratchpad
# worktrees directory) it reports one of:
#   MERGED     the worktree's HEAD is an ancestor of <base> and the tree is
#              clean  → safe to remove
#   DIRTY      uncommitted changes (never removed; read the diff first —
#              handoff §2.3)
#   UNMERGED   committed work not on <base> (never removed)
# With --apply, MERGED worktrees are removed with `git worktree remove --force`
# (which also drops the gitdir under .git/worktrees — an `rm -rf` of the
# directory alone would strand it) and their local claude/agent-* branch is
# deleted. Remote branches are left alone; delete those explicitly.
#
# Exit codes: 0 (report or apply succeeded), 1 (usage / not a repo).
set -euo pipefail

root="/private/tmp/claude-501/-Users-andrewherrera/2a94e1c2-1397-433c-b622-a3763240b60e/scratchpad/worktrees"
base="main"; apply=0
while [ $# -gt 0 ]; do
  case "$1" in
    --root) root="$2"; shift 2 ;;
    --base) base="$2"; shift 2 ;;
    --apply) apply=1; shift ;;
    *) sed -n '2,22p' "$0"; exit 1 ;;
  esac
done

repo=$(git rev-parse --show-toplevel)
base_sha=$(git rev-parse "$base")
echo "worktree-gc: base $base @ $(git rev-parse --short "$base_sha"); root $root"

merged=0; dirty=0; unmerged=0
while IFS= read -r wt; do
  [ -d "$wt/.git" ] || [ -f "$wt/.git" ] || continue
  name=$(basename "$wt")
  head=$(git -C "$wt" rev-parse HEAD 2>/dev/null || echo "?")
  branch=$(git -C "$wt" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
  status=$(git -C "$wt" status --porcelain 2>/dev/null | grep -v 'local.properties' | grep -v '^?? \.agent-logs/' || true)
  if [ -n "$status" ]; then
    echo "  DIRTY     $name ($branch @ ${head:0:7}) — $(printf '%s\n' "$status" | wc -l | tr -d ' ') change(s), read the diff first"
    dirty=$((dirty+1))
  elif git merge-base --is-ancestor "$head" "$base_sha" 2>/dev/null; then
    echo "  MERGED    $name ($branch @ ${head:0:7})"
    merged=$((merged+1))
    if [ "$apply" -eq 1 ]; then
      git -C "$repo" worktree remove --force "$wt" && echo "            removed worktree"
      if [ "$branch" != "HEAD" ] && [ "$branch" != "?" ]; then
        git -C "$repo" branch -D "$branch" >/dev/null 2>&1 && echo "            deleted local branch $branch" || true
      fi
    fi
  else
    echo "  UNMERGED  $name ($branch @ ${head:0:7}) — $(git -C "$wt" log --oneline "$base_sha..$head" 2>/dev/null | wc -l | tr -d ' ') commit(s) not on $base"
    unmerged=$((unmerged+1))
  fi
done < <(find "$root" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)

echo "worktree-gc: $merged merged, $dirty dirty, $unmerged unmerged$( [ "$apply" -eq 1 ] && echo ' (merged ones removed)' || echo ' (dry run; add --apply)')"
git -C "$repo" worktree prune
