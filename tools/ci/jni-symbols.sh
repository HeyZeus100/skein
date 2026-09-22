#!/usr/bin/env bash
# jni-symbols.sh -- the E4.I1 (bd skein-3aw) JNI surface gate.
#
# Two checks, both from that bead's acceptance criteria:
#
#   1. `nm -D libskein_llama.so | grep Java_` lists EXACTLY the externals
#      declared by `LlamaNative.kt`. Not "at least": a Java_ symbol with no
#      `external fun` is dead exported surface, and an `external fun` with no
#      Java_ symbol is an UnsatisfiedLinkError that only a device run would
#      otherwise find. Both directions are reported.
#
#   2. No `std::cout` / `printf` / `fprintf` / `__android_log_*` in the JNI
#      sources. llama.cpp's own logging is routed through `LlamaLogRedactor`
#      by `setLogCallback()`; a stray stdio call in this layer would print
#      prompt or token text straight past the redactor (spec §9 forbids
#      logging prompt/chunk content at any level, in any build).
#
# Usage:
#   tools/ci/jni-symbols.sh [<libskein_llama.so> ...]
#
# With no arguments every `libskein_llama.so` under `inference-service/build`
# is checked (all ABIs of whatever was last assembled), and it is an error to
# find none -- "no .so, no problem" would make this gate vacuous in CI.
#
# Env:
#   NM   -- nm binary to use. Defaults to the NDK's llvm-nm when ANDROID_NDK /
#           ANDROID_NDK_HOME / ANDROID_HOME point at one, else `nm`. A host nm
#           that cannot read an Android ELF is a hard error, not a skip.
#
# Exit: 0 both checks pass for every .so; 1 otherwise.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
kotlin_src="${repo_root}/inference-service/src/main/kotlin/app/skein/inference/service/LlamaNative.kt"
jni_dir="${repo_root}/native/llama/jni"
jni_prefix="Java_app_skein_inference_service_LlamaNative_"

fail=0

note() { printf 'jni-symbols: %s\n' "$1"; }
err() {
  printf 'jni-symbols: ERROR: %s\n' "$1" >&2
  fail=1
}

# --------------------------------------------------------------------------
# Locate an `nm` that understands Android ELF objects.
# --------------------------------------------------------------------------
find_nm() {
  if [[ -n "${NM:-}" ]]; then
    printf '%s' "${NM}"
    return
  fi
  local ndk
  for ndk in "${ANDROID_NDK:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_HOME:-}/ndk-bundle"; do
    [[ -n "${ndk}" && -d "${ndk}" ]] || continue
    local candidate
    # shellcheck disable=SC2044  # depth-limited, names are well-formed
    for candidate in "${ndk}"/toolchains/llvm/prebuilt/*/bin/llvm-nm; do
      [[ -x "${candidate}" ]] && { printf '%s' "${candidate}"; return; }
    done
  done
  # `$ANDROID_HOME/ndk/<version>/...` is the modern sdkmanager layout.
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    local candidate
    for candidate in "${ANDROID_HOME}"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-nm; do
      [[ -x "${candidate}" ]] && { printf '%s' "${candidate}"; return; }
    done
  fi
  printf 'nm'
}

nm_bin="$(find_nm)"
if ! command -v "${nm_bin}" >/dev/null 2>&1 && [[ ! -x "${nm_bin}" ]]; then
  err "no usable nm found (set NM=/path/to/llvm-nm)"
  exit 1
fi

# --------------------------------------------------------------------------
# Check 2 first: it needs no build output, so a source regression is reported
# even when someone runs this before assembling.
# --------------------------------------------------------------------------
if [[ ! -d "${jni_dir}" ]]; then
  err "missing ${jni_dir}"
else
  # `[c]out` etc. so this script's own grep pattern is not a self-hit when the
  # repo is grepped for the same strings.
  #
  # Whole-line comments are exempt (the second grep): the sources document
  # what they deliberately do NOT call, and a comment cannot print anything.
  # A comment *after* code on the same line is not exempt, so the usual way of
  # hiding a call does not work.
  banned='std::[c]out|std::[c]err|[f]printf[[:space:]]*\(|[[:space:]]printf[[:space:]]*\(|^printf[[:space:]]*\(|__android_log_'
  if hits="$(grep -nE "${banned}" "${jni_dir}"/*.cpp "${jni_dir}"/*.h 2>/dev/null |
    grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)')"; then
    err "forbidden direct output in the JNI sources (spec §9 -- route through LlamaLogRedactor):"
    printf '%s\n' "${hits}" >&2
  else
    note "no std::cout/printf/__android_log_* in ${jni_dir#"${repo_root}"/}"
  fi
fi

# --------------------------------------------------------------------------
# The declared surface: `external fun <name>` in LlamaNative.kt.
# --------------------------------------------------------------------------
if [[ ! -f "${kotlin_src}" ]]; then
  err "missing ${kotlin_src}"
  exit 1
fi

declared="$(
  grep -oE '^[[:space:]]*external fun [A-Za-z][A-Za-z0-9]*' "${kotlin_src}" |
    sed -E 's/^[[:space:]]*external fun //' | LC_ALL=C sort -u
)"
if [[ -z "${declared}" ]]; then
  err 'LlamaNative.kt declares no "external fun" -- did the file move?'
  exit 1
fi
declared_count="$(printf '%s\n' "${declared}" | wc -l | tr -d ' ')"
note "LlamaNative.kt declares ${declared_count} external fun(s)"

# JNI mangling: `_` in a Kotlin identifier becomes `_1`. The surface is
# camelCase by convention, so this is belt and braces rather than load-bearing.
mangle() { printf '%s' "${1//_/_1}"; }

# --------------------------------------------------------------------------
# Check 1, per .so.
# --------------------------------------------------------------------------
sos=("$@")
if [[ ${#sos[@]} -eq 0 ]]; then
  while IFS= read -r found; do
    sos+=("${found}")
  done < <(find "${repo_root}/inference-service/build" -name libskein_llama.so -type f 2>/dev/null | LC_ALL=C sort)
fi

if [[ ${#sos[@]} -eq 0 ]]; then
  err "no libskein_llama.so found -- assemble first, e.g. ./gradlew :inference-service:assembleDevDebug"
  exit 1
fi

for so in "${sos[@]}"; do
  if [[ ! -f "${so}" ]]; then
    err "not a file: ${so}"
    continue
  fi
  short="${so#"${repo_root}"/}"

  if ! raw="$("${nm_bin}" -D --defined-only "${so}" 2>/dev/null)"; then
    err "${short}: ${nm_bin} could not read this file"
    continue
  fi
  # A versioned .so prints `name@@VERSION` (the version script in
  # native/llama/CMakeLists.txt puts every export in SKEIN_LLAMA_1.0), so the
  # suffix is stripped before comparing.
  exported="$(
    printf '%s\n' "${raw}" | awk '{print $NF}' | sed -E 's/@@?[A-Za-z0-9_.]+$//' |
      grep -E "^${jni_prefix}" | sed -E "s/^${jni_prefix}//" | LC_ALL=C sort -u
  )" || true

  expected="$(while IFS= read -r name; do mangle "${name}"; printf '\n'; done <<<"${declared}" | LC_ALL=C sort -u)"

  missing="$(LC_ALL=C comm -23 <(printf '%s\n' "${expected}") <(printf '%s\n' "${exported}"))"
  extra="$(LC_ALL=C comm -13 <(printf '%s\n' "${expected}") <(printf '%s\n' "${exported}"))"

  if [[ -n "${missing}" ]]; then
    err "${short}: declared in LlamaNative.kt but NOT exported (UnsatisfiedLinkError on device):"
    printf '  %s\n' ${missing} >&2
  fi
  if [[ -n "${extra}" ]]; then
    err "${short}: exported by the .so but NOT declared in LlamaNative.kt:"
    printf '  %s\n' ${extra} >&2
  fi
  if [[ -z "${missing}" && -z "${extra}" ]]; then
    exported_count="$(printf '%s\n' "${exported}" | grep -c . || true)"
    note "${short}: ${exported_count} Java_ symbol(s), exactly the declared externals"
  fi
done

exit "${fail}"
