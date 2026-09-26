#!/usr/bin/env bash
# native-opt-level.sh -- every llama.cpp / ggml / JNI translation unit in every
# configured native build must be compiled at -O2 or -O3 (bd skein-gg11.24).
#
# Why this exists: AGP hands CMake `CMAKE_BUILD_TYPE=Debug` for debug variants,
# and the NDK's Debug flags are `-g -fno-limit-debug-info` with NO `-O` flag,
# so clang compiles at -O0. For ~900 upstream ggml/llama.cpp translation units
# that is a 50-100x slowdown of the inference kernels: on the Fold (Pixel 9
# Pro Fold, foss-debug from 2d8d193) one 106-token prefill batch had not
# finished after 98 s and was cancelled (skein-gg11.24). The debug APK is the
# one installed for every device smoke, so an unoptimised debug native build
# makes every on-device measurement meaningless. native/llama/CMakeLists.txt
# §4a now adds `-O2` for the Debug configuration; this gate keeps it there.
#
# Usage:
#   tools/ci/native-opt-level.sh [<dir-or-compile_commands.json> ...]
#
# With no arguments, every `compile_commands.json` under
# `inference-service/.cxx` is checked (every variant/ABI CMake has configured
# on this machine), skipping the host-side `vulkan-shaders-gen` code generator
# (a build tool, never shipped). It is an error to find no database, or a
# database with no llama.cpp/JNI translation units -- "nothing built, nothing
# wrong" would make this gate vacuous in CI.
#
# Only translation units under `third_party/llama.cpp/` or `native/llama/` are
# judged. The LAST `-O<level>` token on a command line wins (clang semantics);
# an accepted level is -O2 or -O3. No `-O` at all means -O0 and fails.
#
# Exit: 0 every judged TU is at -O2/-O3; 1 otherwise (or nothing to judge).

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

note() { printf 'native-opt-level: %s\n' "$*"; }
err() { printf 'native-opt-level: error: %s\n' "$*" >&2; }

if ! command -v python3 >/dev/null 2>&1; then
  err "python3 is required to read compile_commands.json"
  exit 1
fi

# Resolve arguments (directories or files) into a list of databases.
databases=()
if [ "$#" -eq 0 ]; then
  set -- "$repo_root/inference-service/.cxx"
fi
for arg in "$@"; do
  if [ -f "$arg" ]; then
    databases+=("$arg")
  elif [ -d "$arg" ]; then
    while IFS= read -r db; do
      databases+=("$db")
    done < <(find "$arg" -name compile_commands.json -not -path '*vulkan-shaders-gen*' 2>/dev/null | sort)
  else
    err "no such file or directory: $arg"
    exit 1
  fi
done

if [ "${#databases[@]}" -eq 0 ]; then
  err "no compile_commands.json found under: $*"
  err "configure a native build first (e.g. ./gradlew :inference-service:assembleFossDebug)"
  exit 1
fi

status=0
for db in "${databases[@]}"; do
  rel="${db#"$repo_root"/}"
  if ! python3 - "$db" <<'PY'
import json, re, sys

db_path = sys.argv[1]
with open(db_path) as fh:
    entries = json.load(fh)

JUDGED = re.compile(r"(/third_party/llama\.cpp/|/native/llama/)")
OPT = re.compile(r"^-O([0-3sz]|fast)?$")
ACCEPTED = {"-O2", "-O3"}

judged = 0
bad = []
for entry in entries:
    path = entry.get("file", "")
    if not JUDGED.search(path):
        continue
    judged += 1
    if "arguments" in entry:
        tokens = list(entry["arguments"])
    else:
        tokens = entry.get("command", "").split()
    level = None
    for tok in tokens:
        if OPT.match(tok):
            level = tok
    if level not in ACCEPTED:
        bad.append((path, level or "(none: -O0)"))

if judged == 0:
    print(f"native-opt-level: error: {db_path}: no llama.cpp/JNI translation units to judge", file=sys.stderr)
    sys.exit(1)
if bad:
    print(f"native-opt-level: error: {db_path}: {len(bad)} of {judged} llama.cpp/JNI translation units are not at -O2/-O3", file=sys.stderr)
    for path, level in bad[:8]:
        short = re.sub(r"^.*?(/third_party/|/native/)", r"\1", path)
        print(f"native-opt-level:   {short}  ->  {level}", file=sys.stderr)
    if len(bad) > 8:
        print(f"native-opt-level:   ... and {len(bad) - 8} more", file=sys.stderr)
    sys.exit(1)
print(f"native-opt-level: ok: {db_path}: {judged} llama.cpp/JNI translation units at -O2/-O3")
PY
  then
    status=1
  fi
done

if [ "$status" -ne 0 ]; then
  err "an unoptimised native build would ship -O0 inference kernels; see native/llama/CMakeLists.txt §4a"
fi
exit "$status"
