#!/usr/bin/env bash
#
# E1.I9 (spec §10): the reverse of tools/release/sign.sh. Given a signed
# release APK and the cert fingerprint published in docs/SIGNING.md /
# SECURITY.md, confirms the APK was signed by the expected key and that
# the signature schemes match Skein's release policy (v2+v3 verified,
# v1 disabled, v4 present).
#
# This is what a downstream reproducer or an end user runs after building
# (or downloading) an APK, to confirm it is signed by the real Skein
# release key before installing it. It never touches a keystore or
# private key -- only the public certificate embedded in the signed APK.
#
# Usage:
#   tools/release/verify.sh <signed.apk> --fingerprint <sha256-hex>
#   tools/release/verify.sh <signed.apk> --sums <SHA256SUMS>
#
# Requires `apksigner` on PATH (ships in
# $ANDROID_HOME/build-tools/<version>/) and a portable sha256 tool
# (`sha256sum` on Linux, `shasum -a 256` on macOS).
#
# Written against `bash --version` 3.2 (macOS's system bash) as the floor.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/release/verify.sh <signed.apk> --fingerprint <sha256-hex>
       tools/release/verify.sh <signed.apk> --sums <SHA256SUMS>

Verify a signed release APK: check its signature schemes (v1 disabled,
v2/v3 verified, v4 idsig present) and confirm its signing certificate's
SHA-256 fingerprint matches the one published for this release.

Positional argument:
  signed.apk             Path to the signed APK to check.

One of these is required:
  --fingerprint <hex>    Expected signing certificate SHA-256 fingerprint,
                          as published in docs/SIGNING.md / SECURITY.md.
                          Case-insensitive, colons optional (both
                          "AA:BB:.." and "aabb.." are accepted).
  --sums <path>          Path to a SHA256SUMS file (as produced by
                          sign.sh) to also check the APK's own file hash
                          against, in addition to the cert fingerprint
                          check. Requires --fingerprint too, since
                          SHA256SUMS only covers the file bytes, not the
                          signing identity.

Other options:
  -h, --help             Show this help and exit.

Exit status is non-zero if the APK does not verify, if any expected
signature scheme is missing, or if the fingerprint doesn't match.

Example:
  tools/release/verify.sh skein-1.0.0-foss.apk \
    --fingerprint AA:BB:CC:...:FF \
    --sums SHA256SUMS
EOF
}

# --- Argument parsing -------------------------------------------------
apk=""
fingerprint=""
sums_file=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --fingerprint)
      [[ $# -ge 2 ]] || { echo "verify.sh: --fingerprint requires a value" >&2; exit 1; }
      fingerprint="$2"
      shift 2
      ;;
    --sums)
      [[ $# -ge 2 ]] || { echo "verify.sh: --sums requires a value" >&2; exit 1; }
      sums_file="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "verify.sh: unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -z "$apk" ]]; then
        apk="$1"
      else
        echo "verify.sh: unexpected extra argument: $1" >&2
        exit 1
      fi
      shift
      ;;
  esac
done

if [[ -z "$apk" ]]; then
  echo "verify.sh: signed APK path is required" >&2
  usage >&2
  exit 1
fi

if [[ -z "$fingerprint" ]]; then
  echo "verify.sh: --fingerprint is required" >&2
  usage >&2
  exit 1
fi

if [[ ! -f "$apk" ]]; then
  echo "verify.sh: FAIL -- signed APK not found: $apk" >&2
  exit 1
fi

if [[ -n "$sums_file" && ! -f "$sums_file" ]]; then
  echo "verify.sh: FAIL -- SHA256SUMS file not found: $sums_file" >&2
  exit 1
fi

if ! command -v apksigner >/dev/null 2>&1; then
  echo "verify.sh: FAIL -- apksigner not found on PATH (it ships in \$ANDROID_HOME/build-tools/<version>/)" >&2
  exit 1
fi

# Normalize a fingerprint for comparison: uppercase, strip colons/spaces.
normalize_fp() {
  tr '[:lower:]' '[:upper:]' <<<"$1" | tr -d ':[:space:]'
}

expected_fp_norm="$(normalize_fp "$fingerprint")"

# --- Signature scheme check. See sign.sh for why v2/v3 need
#     --min-sdk-version 24 to be exercised (v3 alone satisfies this app's
#     declared minSdkVersion, so a default `apksigner verify` never
#     exercises the v2 block even though it's present and valid), and why
#     v4 is checked structurally via its .idsig file rather than through
#     `apksigner verify` (this apksigner has no CLI-exposed v4 check). ---
echo "verify.sh: verifying $apk" >&2
if ! verify_output="$(apksigner verify --verbose --min-sdk-version 24 "$apk" 2>&1)"; then
  echo "$verify_output" >&2
  echo "verify.sh: FAIL -- apksigner verify failed" >&2
  exit 1
fi
echo "$verify_output"

assert_scheme() {
  local label="$1"
  local expected="$2"
  local line
  line="$(grep -F "$label" <<<"$verify_output" || true)"
  if [[ -z "$line" ]]; then
    echo "verify.sh: FAIL -- apksigner verify did not report '$label'" >&2
    exit 1
  fi
  if ! grep -qF "$label $expected" <<<"$line"; then
    echo "verify.sh: FAIL -- expected '$label $expected', got: $line" >&2
    exit 1
  fi
}

assert_scheme "Verified using v1 scheme (JAR signing):" "false"
assert_scheme "Verified using v2 scheme (APK Signature Scheme v2):" "true"
assert_scheme "Verified using v3 scheme (APK Signature Scheme v3):" "true"

idsig="${apk}.idsig"
if [[ -f "$idsig" ]]; then
  idsig_version="$(od -An -tu4 -N4 "$idsig" | tr -d '[:space:]')"
  if [[ "$idsig_version" != "2" ]]; then
    echo "verify.sh: FAIL -- $idsig does not look like a v4 signature file (version bytes: $idsig_version)" >&2
    exit 1
  fi
  echo "verify.sh: v4 idsig present and well-formed ($idsig)" >&2
else
  echo "verify.sh: WARNING -- no $idsig alongside the APK; v4 (incremental install) support cannot be confirmed" >&2
fi

echo "verify.sh: signature scheme check PASSED (v1=false, v2/v3=true)" >&2

# --- Certificate fingerprint check. ---
cert_output="$(apksigner verify --print-certs "$apk")"
actual_fp_raw="$(grep -m1 -i 'certificate SHA-256 digest' <<<"$cert_output" | sed -E 's/.*digest: *//')"
if [[ -z "$actual_fp_raw" ]]; then
  echo "verify.sh: FAIL -- could not read signing certificate SHA-256 digest from apksigner output" >&2
  exit 1
fi
actual_fp_norm="$(normalize_fp "$actual_fp_raw")"

if [[ "$actual_fp_norm" != "$expected_fp_norm" ]]; then
  echo "verify.sh: FAIL -- signing certificate fingerprint mismatch" >&2
  echo "  expected: $fingerprint" >&2
  echo "  actual:   $actual_fp_raw" >&2
  exit 1
fi

echo "verify.sh: signing certificate fingerprint matches: $actual_fp_raw" >&2

# --- Optional: SHA256SUMS file hash check. ---
if [[ -n "$sums_file" ]]; then
  sha_tool=()
  if command -v sha256sum >/dev/null 2>&1; then
    sha_tool=(sha256sum)
  elif command -v shasum >/dev/null 2>&1; then
    sha_tool=(shasum -a 256)
  else
    echo "verify.sh: FAIL -- neither sha256sum nor shasum found on PATH" >&2
    exit 1
  fi

  # Match the filename field exactly (awk splits on whitespace), not a
  # substring -- "skein-1.0.0-foss.apk" must not match the
  # "skein-1.0.0-foss.apk.idsig" line too.
  apk_basename="$(basename "$apk")"
  expected_sha="$(awk -v f="$apk_basename" '$2 == f { print $1; found=1 } END { exit !found }' "$sums_file" || true)"
  if [[ -z "$expected_sha" ]]; then
    echo "verify.sh: FAIL -- $apk_basename not listed in $sums_file" >&2
    exit 1
  fi

  apk_dir="$(dirname "$apk")"
  actual_sha="$(cd "$apk_dir" && "${sha_tool[@]}" "$apk_basename" | awk '{print $1}')"

  if [[ "$actual_sha" != "$expected_sha" ]]; then
    echo "verify.sh: FAIL -- SHA-256 mismatch for $apk_basename" >&2
    echo "  expected: $expected_sha" >&2
    echo "  actual:   $actual_sha" >&2
    exit 1
  fi
  echo "verify.sh: SHA-256 file hash matches $sums_file: $actual_sha" >&2
fi

echo "verify.sh: PASSED -- $apk is signed by the expected key and verifies" >&2
