#!/usr/bin/env bash
# no-content-logging.sh -- the E4.I3 (bd skein-nxk) content-logging gate.
#
# Acceptance criterion: "No logging of prompt/token text at any level (grep of
# the service sources for `SkeinLog` calls with content variables in CI)".
#
# WHY A GREP AND NOT A CODE REVIEW. The isolated service is the one process in
# Skein that holds decrypted prompts, retrieved document text and generated
# tokens in memory at once. `SkeinLog` already refuses to log at DEBUG in
# release builds, and `LlamaLogRedactor` already strips llama.cpp's own output
# -- but neither can see a first-party `SkeinLog.w(TAG, "prompt=$prompt")`
# somebody adds while debugging and forgets to remove. Spec §9 makes that a
# hard rule at EVERY level, in EVERY build, so it needs a control that runs on
# every push rather than a convention.
#
# WHAT IT FLAGS. Any `SkeinLog.<level>(...)` call in the checked sources whose
# arguments mention an identifier from the content vocabulary below. It is
# deliberately blunt: a false positive is renamed or restructured in a minute,
# and the alternative -- a clever matcher that "understands" which `$text` is
# safe -- is the kind of control that quietly stops working.
#
# Usage:
#   tools/ci/no-content-logging.sh [<dir> ...]
#
# With no arguments the two isolated services' main sources are checked.
#
# Exit: 0 clean; 1 if any call matches.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [ "$#" -gt 0 ]; then
  roots=("$@")
else
  roots=(
    "${repo_root}/inference-service/src/main/kotlin"
    "${repo_root}/embedder-service/src/main/kotlin"
  )
fi

# Identifiers that name model input or output. `piece` and `token` are the
# streaming path; `prompt`, `messages`, `contents` and `rendered` are the
# templating path; `chunk`, `document` and `snippet` are the RAG path that
# reaches the service as retrieved context; `plaintext` and `secret` are
# belt-and-braces.
content_words='prompt|piece|pieces|token|tokens|text|texts|content|contents|rendered|message|messages|chunk|chunks|document|documents|snippet|excerpt|plaintext|secret|passphrase|key'

fail=0

note() { printf 'no-content-logging: %s\n' "$1"; }
err() {
  printf 'no-content-logging: ERROR: %s\n' "$1" >&2
  fail=1
}

checked=0
for root in "${roots[@]}"; do
  [ -d "${root}" ] || continue
  while IFS= read -r file; do
    checked=$((checked + 1))
    # Only the ARGUMENTS of a SkeinLog call are examined, so a file that merely
    # has a `prompt` variable somewhere is not flagged -- only one that puts it
    # in a log line. `TAG` and fixed strings pass; an interpolation or a bare
    # identifier from the vocabulary does not.
    while IFS= read -r hit; do
      line_no="${hit%%:*}"
      code="${hit#*:}"
      args="${code#*SkeinLog.}"
      args="${args#*(}"
      # Strip double-quoted literal text that contains no interpolation, so a
      # fixed message like "prompt too long" is not mistaken for a value.
      stripped="$(printf '%s' "${args}" | sed -E 's/"[^"$]*"//g')"
      if printf '%s' "${stripped}" | grep -Eq "(\\\$\{?[A-Za-z_][A-Za-z0-9_.]*)?\b(${content_words})\b"; then
        err "${file}:${line_no}: SkeinLog call references model content"
        printf '    %s\n' "${code}" >&2
      fi
    done < <(grep -nE 'SkeinLog\.[a-z]+\(' "${file}" || true)
  done < <(find "${root}" -name '*.kt' -type f)
done

if [ "${checked}" -eq 0 ]; then
  err "no Kotlin sources were checked; the gate would be vacuous"
fi

if [ "${fail}" -eq 0 ]; then
  note "clean: ${checked} file(s), no SkeinLog call references model content"
fi
exit "${fail}"
