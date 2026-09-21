#!/usr/bin/env bash
# check-submodules.sh -- enforce native/llama/PINNED_COMMIT (E1.I4 / skein-ca2)
#
# `libskein_llama.so`'s bytes are defined by the revisions of the submodules
# under `third_party/`. This script is the gate that keeps those revisions from
# drifting: it compares each submodule's checked-out commit AND the commit the
# superproject's index records against `native/llama/PINNED_COMMIT`, and fails
# if either disagrees.
#
# Both comparisons matter and catch different mistakes:
#   * working tree vs PINNED_COMMIT -- a developer ran `git submodule update
#     --remote`, or forgot to run `git submodule update` after pulling a pin
#     bump. The .so they build is not the one the pin describes.
#   * gitlink vs PINNED_COMMIT -- somebody committed a moved submodule pointer
#     without editing PINNED_COMMIT (or vice versa). The tree would build
#     differently on a fresh clone than it does for the author.
#
# On CI the checkout must fetch submodules (`submodules: true`), otherwise the
# working-tree check is skipped with a warning and only the gitlink is
# verified -- which is still enough to catch an unreviewed pin move.
#
# Usage:  tools/ci/check-submodules.sh
# Exit:   0 all pins match; 1 a mismatch or a malformed pin file.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
pin_file="${repo_root}/native/llama/PINNED_COMMIT"

if [[ ! -f "${pin_file}" ]]; then
  echo "check-submodules: missing ${pin_file}" >&2
  exit 1
fi

fail=0
checked=0

# Read `<path> <40-hex> <tag>` records; skip blanks and `#` comments.
while read -r sm_path sm_commit sm_tag _rest; do
  [[ -z "${sm_path}" || "${sm_path}" == \#* ]] && continue

  if [[ ! "${sm_commit}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "check-submodules: ${sm_path}: '${sm_commit}' is not a 40-hex commit" >&2
    fail=1
    continue
  fi
  checked=$((checked + 1))

  # --- what the superproject records (the gitlink) ---------------------------
  # Read the *index*, not HEAD: in CI the two are identical, while locally the
  # index is what a `git commit` is about to record -- so a developer staging a
  # submodule move without touching PINNED_COMMIT is caught before the commit
  # rather than after. `git ls-files -s` prints: <mode> <sha> <stage>\t<path>,
  # and gitlinks have mode 160000.
  gitlink="$(git -C "${repo_root}" ls-files -s -- "${sm_path}" | awk '$1 == "160000" { print $2 }')"
  if [[ -z "${gitlink}" ]]; then
    echo "check-submodules: FAIL ${sm_path}" >&2
    echo "  no gitlink at this path in HEAD -- is it registered as a submodule?" >&2
    fail=1
  elif [[ "${gitlink}" != "${sm_commit}" ]]; then
    echo "check-submodules: FAIL ${sm_path} (recorded submodule pointer)" >&2
    echo "  index records   ${gitlink}" >&2
    echo "  PINNED_COMMIT   ${sm_commit}  (${sm_tag})" >&2
    echo "  A submodule was moved without updating native/llama/PINNED_COMMIT" >&2
    echo "  (or the other way round). Both must change in the same commit." >&2
    fail=1
  fi

  # --- what is actually checked out -----------------------------------------
  if [[ ! -e "${repo_root}/${sm_path}/.git" ]]; then
    echo "check-submodules: WARN ${sm_path} is not checked out;" \
         "skipping working-tree check (run 'git submodule update --init --recursive')" >&2
    continue
  fi
  head="$(git -C "${repo_root}/${sm_path}" rev-parse HEAD)"
  if [[ "${head}" != "${sm_commit}" ]]; then
    echo "check-submodules: FAIL ${sm_path} (working tree)" >&2
    echo "  checked out     ${head}" >&2
    echo "  PINNED_COMMIT   ${sm_commit}  (${sm_tag})" >&2
    echo "  Run: git submodule update --init --recursive" >&2
    fail=1
  fi
done < "${pin_file}"

if (( checked == 0 )); then
  echo "check-submodules: ${pin_file} lists no submodules -- refusing to pass vacuously" >&2
  exit 1
fi

if (( fail )); then
  exit 1
fi

echo "check-submodules: OK (${checked} submodule pin(s) match native/llama/PINNED_COMMIT)"
