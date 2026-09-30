#!/usr/bin/env bash
# sqlite-jni-symbols.sh -- the sqlite JNI surface gate (bd skein-8ryv).
#
# Modelled on check 1 of tools/ci/jni-symbols.sh (the llama.cpp JNI surface
# gate): `nm -D libskein_sqlite_jni.so | grep Java_` must list EXACTLY the
# `external fun`s declared on the two explicitly permitted classes
# `SkeinSQLiteNativeImpl` and `RecoveryProofNative`. Not "at least": a Java_ symbol with no
# `external fun` is dead exported surface, and an `external fun` with no
# matching Java_ symbol is an UnsatisfiedLinkError that only a device run
# would otherwise find (exactly the failure mode in skein-8ryv, where the C
# side exported `..._SkeinSQLiteNative_<name>` instead of
# `..._SkeinSQLiteNativeImpl_<name>`). Both directions are reported.
#
# Usage:
#   tools/ci/sqlite-jni-symbols.sh [<libskein_sqlite_jni.so> ...]
#
# With no arguments every `libskein_sqlite_jni.so` under `core/vault/build`
# is checked (all ABIs of whatever was last assembled), and it is an error to
# find none -- "no .so, no problem" would make this gate vacuous in CI.
#
# Env:
#   NM   -- nm binary to use. Defaults to the NDK's llvm-nm when ANDROID_NDK /
#           ANDROID_NDK_HOME / ANDROID_HOME point at one, else `nm`. A host nm
#           that cannot read an Android ELF is a hard error, not a skip.
#
# Exit: 0 the check passes for every .so; 1 otherwise.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
kotlin_sources=(
  "${repo_root}/core/vault/src/main/kotlin/app/skein/core/vault/db/SkeinSQLiteNative.kt"
  "${repo_root}/core/vault/src/main/kotlin/app/skein/core/vault/db/RecoveryProofNative.kt"
)
jni_classes=(SkeinSQLiteNativeImpl RecoveryProofNative)

fail=0

note() { printf 'sqlite-jni-symbols: %s\n' "$1"; }
err() {
  printf 'sqlite-jni-symbols: ERROR: %s\n' "$1" >&2
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
# Exact declared surface from the two explicitly permitted native classes.
# --------------------------------------------------------------------------
expected=""
for i in "${!kotlin_sources[@]}"; do
  kotlin_src="${kotlin_sources[$i]}"
  if [[ ! -f "${kotlin_src}" ]]; then
    err "missing ${kotlin_src}"
    exit 1
  fi
  declared="$(
    grep -oE '^[[:space:]]*external (override )?fun [A-Za-z][A-Za-z0-9_]*' "${kotlin_src}" |
      sed -E 's/^[[:space:]]*external (override )?fun //' | LC_ALL=C sort -u
  )"
  if [[ -z "${declared}" ]]; then
    err "${jni_classes[$i]} declares no external fun"
    exit 1
  fi
  while IFS= read -r name; do
    expected+="Java_app_skein_core_vault_db_${jni_classes[$i]}_${name//_/_1}"$'\n'
  done <<<"${declared}"
done
expected="$(printf '%s' "${expected}" | LC_ALL=C sort -u)"

# --------------------------------------------------------------------------
# Check, per .so.
# --------------------------------------------------------------------------
sos=("$@")
if [[ ${#sos[@]} -eq 0 ]]; then
  while IFS= read -r found; do
    sos+=("${found}")
  done < <(find "${repo_root}/core/vault/build" -name libskein_sqlite_jni.so -type f 2>/dev/null | LC_ALL=C sort)
fi

if [[ ${#sos[@]} -eq 0 ]]; then
  err "no libskein_sqlite_jni.so found -- assemble first, e.g. ./gradlew :core:vault:assembleFossDebug"
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
  # A versioned .so prints `name@@VERSION`; the suffix is stripped before
  # comparing.
  exported="$(
    printf '%s\n' "${raw}" | awk '{print $NF}' | sed -E 's/@@?[A-Za-z0-9_.]+$//' |
      grep -E "^Java_" | LC_ALL=C sort -u
  )" || true

  missing="$(LC_ALL=C comm -23 <(printf '%s\n' "${expected}") <(printf '%s\n' "${exported}"))"
  extra="$(LC_ALL=C comm -13 <(printf '%s\n' "${expected}") <(printf '%s\n' "${exported}"))"

  if [[ -n "${missing}" ]]; then
    err "${short}: declared on a permitted native class but NOT exported (UnsatisfiedLinkError on device):"
    printf '  %s\n' ${missing} >&2
  fi
  if [[ -n "${extra}" ]]; then
    err "${short}: exported by the .so but NOT declared on either permitted native class:"
    printf '  %s\n' ${extra} >&2
  fi
  if [[ -z "${missing}" && -z "${extra}" ]]; then
    exported_count="$(printf '%s\n' "${exported}" | grep -c . || true)"
    note "${short}: ${exported_count} Java_ symbol(s), exactly the declared externals"
  fi
done

exit "${fail}"
