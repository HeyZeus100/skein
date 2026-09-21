#!/usr/bin/env bash
#
# bd skein-bpt (E5.I2) — fetch the three `tokenizer.json` artifacts
# `app.skein.core.rag.tokenizers` is tested against, gzip them, and print the
# repo / revision / SHA-256 triple for each so
# `core/rag/src/test/resources/tokenizers/MANIFEST.md` can be re-derived.
#
# Scope: ONLY `tokenizer.json`. Model weights (GGUF / ONNX) are bd skein-bxk.
# `special_tokens_map.json` / `tokenizer_config.json` are not needed — see
# MANIFEST.md "Not fetched".
#
# Usage:
#   tools/tokenizers/fetch_tokenizers.sh core/rag/src/test/resources/tokenizers
#
set -euo pipefail

DEST="${1:?usage: fetch_tokenizers.sh <dest-dir>}"
mkdir -p "$DEST"

sha256() {
  if command -v shasum > /dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

fetch() {
  local repo="$1" out="$2"
  local headers="$DEST/.headers"
  echo "=== $repo :: tokenizer.json"
  curl -sSL --fail --max-time 300 -D "$headers" -o "$DEST/$out" \
    "https://huggingface.co/$repo/resolve/main/tokenizer.json"

  local revision
  revision=$(grep -i '^x-repo-commit:' "$headers" | tail -1 | tr -d '\r' | awk '{print $2}')
  rm -f "$headers"

  local license
  license=$(curl -sSL --fail --max-time 60 "https://huggingface.co/api/models/$repo" |
    python3 -c 'import json,sys; print((json.load(sys.stdin).get("cardData") or {}).get("license"))')

  # -n keeps the name and timestamp out of the gzip header, so the .gz is
  # byte-reproducible and its hash is stable across re-runs.
  gzip -9 -n -c "$DEST/$out" > "$DEST/$out.gz"

  printf '  revision:     %s\n' "$revision"
  printf '  license:      %s\n' "$license"
  printf '  raw bytes:    %s\n' "$(wc -c < "$DEST/$out" | tr -d ' ')"
  printf '  raw sha256:   %s\n' "$(sha256 "$DEST/$out")"
  printf '  gzip bytes:   %s\n' "$(wc -c < "$DEST/$out.gz" | tr -d ' ')"
  printf '  gzip sha256:  %s\n' "$(sha256 "$DEST/$out.gz")"

  # Only the .gz is checked in.
  rm -f "$DEST/$out"
}

fetch "nomic-ai/nomic-embed-text-v1.5" "nomic-embed-text-v1.5.tokenizer.json"
fetch "cross-encoder/ms-marco-MiniLM-L6-v2" "ms-marco-MiniLM-L-6-v2.tokenizer.json"
fetch "GG-QandV/gliner_small-v2.5-onnx" "gliner-small-v2.5-deberta-v3.tokenizer.json"
