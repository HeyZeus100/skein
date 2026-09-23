#!/usr/bin/env bash
# elf-alignment.sh -- the 16 KiB ELF LOAD-segment alignment guard (bd
# skein-xth5, docs/research/POCKETPAL_RECON.md §8.1 entry B-8, finding
# PP-48). Modelled on tools/ci/jni-symbols.sh's house style (same nm/readelf
# discovery pattern, same note/err/exit conventions).
#
# Upstream issue https://github.com/a-ghorbani/pocketpal-ai/issues/512
# reports a SIGBUS at model-load time on Android 16 / 16 KiB-page-size
# devices, diagnosed (a reporter's hypothesis, not confirmed against
# Skein's own binaries by this script's author -- that is exactly what this
# script exists to confirm) as native libraries whose ELF `PT_LOAD` segments
# are 4 KiB-aligned. Skein builds `libskein_llama.so` and
# `libskein_sqlite_jni.so` itself under the NDK and mmaps GGUF models over a
# pinned descriptor -- precisely the operation that would fault. This is a
# one-line-per-segment build-artifact assertion, not a runtime check.
#
# (The bead text names `libskein_sqlite.so`; the file the build actually
# produces and CI actually cares about -- the one with a JNI surface gate of
# its own, tools/ci/sqlite-jni-symbols.sh -- is `libskein_sqlite_jni.so`.
# Corrected here and noted on skein-xth5.)
#
# Usage:
#   tools/ci/elf-alignment.sh [<lib.so> ...]
#
# With no arguments, every `libskein_llama.so` under
# `inference-service/build` and every `libskein_sqlite_jni.so` under
# `core/vault/build` is checked (all ABIs of whatever was last assembled),
# and it is an error to find none of either -- "no .so, no problem" would
# make this gate vacuous in CI.
#
# Env:
#   READELF -- llvm-readelf binary to use. Defaults to the NDK's
#              llvm-readelf when ANDROID_NDK / ANDROID_NDK_HOME / ANDROID_HOME
#              point at one, else `readelf` (GNU readelf also understands
#              `-l` / PT_LOAD). A host tool that cannot read an Android ELF
#              is a hard error, not a skip.
#   MIN_ALIGN -- minimum required p_align in bytes. Defaults to 16384 (16 KiB).
#
# Exit: 0 every PT_LOAD segment in every found .so meets MIN_ALIGN; 1 otherwise.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
min_align="${MIN_ALIGN:-16384}"

fail=0

note() { printf 'elf-alignment: %s\n' "$1"; }
err() {
  printf 'elf-alignment: ERROR: %s\n' "$1" >&2
  fail=1
}

# --------------------------------------------------------------------------
# Locate a readelf that understands Android ELF objects. Same search order
# as jni-symbols.sh's find_nm, generalised to llvm-readelf.
# --------------------------------------------------------------------------
find_readelf() {
  if [[ -n "${READELF:-}" ]]; then
    printf '%s' "${READELF}"
    return
  fi
  local ndk
  for ndk in "${ANDROID_NDK:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_HOME:-}/ndk-bundle"; do
    [[ -n "${ndk}" && -d "${ndk}" ]] || continue
    local candidate
    # shellcheck disable=SC2044  # depth-limited, names are well-formed
    for candidate in "${ndk}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
      [[ -x "${candidate}" ]] && { printf '%s' "${candidate}"; return; }
    done
  done
  # `$ANDROID_HOME/ndk/<version>/...` is the modern sdkmanager layout --
  # what AGP auto-installs on CI for the ndkVersion pinned in
  # inference-service/build.gradle.kts and core/vault/build.gradle.kts.
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    local candidate
    for candidate in "${ANDROID_HOME}"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
      [[ -x "${candidate}" ]] && { printf '%s' "${candidate}"; return; }
    done
  fi
  printf 'readelf'
}

readelf_bin="$(find_readelf)"
if ! command -v "${readelf_bin}" >/dev/null 2>&1 && [[ ! -x "${readelf_bin}" ]]; then
  err "no usable readelf found (set READELF=/path/to/llvm-readelf)"
  exit 1
fi

# --------------------------------------------------------------------------
# Collect the libraries to check.
# --------------------------------------------------------------------------
sos=("$@")
if [[ ${#sos[@]} -eq 0 ]]; then
  while IFS= read -r found; do
    sos+=("${found}")
  done < <(
    {
      find "${repo_root}/inference-service/build" -name libskein_llama.so -type f 2>/dev/null
      find "${repo_root}/core/vault/build" -name libskein_sqlite_jni.so -type f 2>/dev/null
    } | LC_ALL=C sort
  )
fi

if [[ ${#sos[@]} -eq 0 ]]; then
  err "no libskein_llama.so or libskein_sqlite_jni.so found -- assemble first, e.g. ./gradlew assembleFossDebug"
  exit 1
fi

# --------------------------------------------------------------------------
# Per-library, per-segment check.
# --------------------------------------------------------------------------
for so in "${sos[@]}"; do
  if [[ ! -f "${so}" ]]; then
    err "not a file: ${so}"
    continue
  fi
  short="${so#"${repo_root}"/}"
  # ABI is the parent directory name under lib/<abi>/ AGP always produces
  # (stripped_native_libs/.../out/lib/<abi>/libfoo.so and the merged
  # jniLibs layout share that shape).
  abi="$(basename "$(dirname "${so}")")"

  if ! raw="$("${readelf_bin}" -l "${so}" 2>&1)"; then
    err "${short} (${abi}): ${readelf_bin} could not read this file"
    printf '%s\n' "${raw}" >&2
    continue
  fi

  # Program header lines look like (llvm-readelf -l):
  #   LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x001234 0x001234 R   0x4000
  # Fields, right to left: Align, Flags, MemSiz, FileSiz, PhysAddr, VirtAddr, Offset.
  # llvm-readelf may also wrap onto a continuation line depending on address
  # width; awk over the whole line and take the last whitespace-separated
  # field, which is always Align for a LOAD row on both GNU and LLVM readelf.
  load_lines="$(printf '%s\n' "${raw}" | grep -E '^[[:space:]]*LOAD' || true)"
  if [[ -z "${load_lines}" ]]; then
    err "${short} (${abi}): no PT_LOAD segments found -- not a valid executable ELF?"
    continue
  fi

  seg_index=0
  lib_ok=1
  while IFS= read -r line; do
    seg_index=$((seg_index + 1))
    align_field="$(printf '%s' "${line}" | awk '{print $NF}')"
    # Align prints as a hex literal (`0x4000`, `0x1000`); decimal `1`/`0` for
    # a segment with no alignment requirement is possible in principle but
    # not something a LOAD segment should ever report.
    if [[ ! "${align_field}" =~ ^0x[0-9A-Fa-f]+$ ]]; then
      err "${short} (${abi}): PT_LOAD #${seg_index}: could not parse alignment from: ${line}"
      lib_ok=0
      continue
    fi
    align_dec=$((align_field))
    if ((align_dec < min_align)); then
      err "${short} (${abi}): PT_LOAD #${seg_index} p_align=${align_dec} (${align_field}) < ${min_align} -- a SIGBUS risk on 16 KiB-page devices (PP-48, #512)"
      lib_ok=0
    fi
  done <<<"${load_lines}"

  if ((lib_ok)); then
    seg_count="$(printf '%s\n' "${load_lines}" | grep -c . || true)"
    note "${short} (${abi}): ${seg_count} PT_LOAD segment(s), all p_align >= ${min_align}: PASS"
  fi
done

exit "${fail}"
