#!/usr/bin/env bash
#
# E1.I9 (spec §10): signs a release APK with v2/v3/v4 signature schemes,
# verifies the result, and publishes the SHA-256 fingerprints a downstream
# user needs to check their download against (docs/SIGNING.md).
#
# This script is run by a human, on a machine that holds the release
# keystore. CI never runs this and never has the keystore -- see
# .github/workflows/reproducible-build.yml, which only verifies the
# *unsigned* build is reproducible. The signed APK is produced locally and
# uploaded to the release by hand (E8.I2).
#
# Usage:
#   tools/release/sign.sh [unsigned.apk] --keystore <path> --alias <name> --out <path>
#
# Options:
#   --keystore <path>   Path to the release .jks/.keystore file (required).
#                        Never checked into git -- see .gitignore.
#   --alias <name>       Key alias inside the keystore (required).
#   --out <path>         Path to write the signed APK to (required). The
#                         v4 idsig is written alongside as "<path>.idsig",
#                         and SHA256SUMS is written next to it too.
#   -h, --help            Print this help and exit 0.
#
# The keystore password is read interactively from the terminal with
# local echo disabled (never accepted as a command-line argument, and
# never left in a file or environment variable) and is forwarded to
# apksigner over apksigner's own stdin (`--ks-pass stdin`), never via
# `--ks-pass pass:...`, which would leak the password in `ps` output.
#
# Requires `apksigner` on PATH (ships in
# $ANDROID_HOME/build-tools/<version>/) and a portable sha256 tool
# (`sha256sum` on Linux, `shasum -a 256` on macOS).
#
# Written against `bash --version` 3.2 (macOS's system bash) as the floor:
# no `mapfile`/`readarray`.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/release/sign.sh [unsigned.apk] --keystore <path> --alias <name> --out <path>

Sign a release APK with v2+v3+v4 signature schemes (v1 disabled), verify
the result, and emit SHA256SUMS plus the signing certificate's SHA-256
fingerprint.

Positional argument:
  unsigned.apk          Path to the unsigned APK to sign. Defaults to
                         "unsigned.apk" in the current directory.

Required options:
  --keystore <path>     Path to the release keystore (.jks/.keystore).
  --alias <name>        Key alias inside the keystore.
  --out <path>          Path to write the signed APK to. The v4 idsig is
                         written to "<path>.idsig" and SHA256SUMS is
                         written in the same directory as <path>.

Other options:
  -h, --help            Show this help and exit.

You will be prompted for the keystore password on stdin. It is never
accepted as a command-line argument or logged, and it never appears in
`ps` output.

Example:
  tools/release/sign.sh build/outputs/apk/foss/release/app-foss-release-unsigned.apk \
    --keystore /secure/media/skein-release.jks \
    --alias skein-release \
    --out dist/skein-1.0.0-foss.apk
EOF
}

# --- Argument parsing -------------------------------------------------
apk_in=""
keystore=""
alias_name=""
out=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --keystore)
      [[ $# -ge 2 ]] || { echo "sign.sh: --keystore requires a value" >&2; exit 1; }
      keystore="$2"
      shift 2
      ;;
    --alias)
      [[ $# -ge 2 ]] || { echo "sign.sh: --alias requires a value" >&2; exit 1; }
      alias_name="$2"
      shift 2
      ;;
    --out)
      [[ $# -ge 2 ]] || { echo "sign.sh: --out requires a value" >&2; exit 1; }
      out="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "sign.sh: unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -z "$apk_in" ]]; then
        apk_in="$1"
      else
        echo "sign.sh: unexpected extra argument: $1" >&2
        exit 1
      fi
      shift
      ;;
  esac
done

apk_in="${apk_in:-unsigned.apk}"

if [[ -z "$keystore" || -z "$alias_name" || -z "$out" ]]; then
  echo "sign.sh: --keystore, --alias, and --out are all required" >&2
  usage >&2
  exit 1
fi

if [[ ! -f "$apk_in" ]]; then
  echo "sign.sh: FAIL -- unsigned APK not found: $apk_in" >&2
  exit 1
fi

if [[ ! -f "$keystore" ]]; then
  echo "sign.sh: FAIL -- keystore not found: $keystore" >&2
  exit 1
fi

if ! command -v apksigner >/dev/null 2>&1; then
  echo "sign.sh: FAIL -- apksigner not found on PATH (it ships in \$ANDROID_HOME/build-tools/<version>/)" >&2
  exit 1
fi

sha_tool=()
if command -v sha256sum >/dev/null 2>&1; then
  sha_tool=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  sha_tool=(shasum -a 256)
else
  echo "sign.sh: FAIL -- neither sha256sum nor shasum found on PATH" >&2
  exit 1
fi

out_dir="$(dirname "$out")"
mkdir -p "$out_dir"

# --- Password prompt: never on argv, never logged, never in a file. ---
# stty -echo hides input; we always restore it, even on error/interrupt.
keystore_password=""
prompt_password() {
  local prompt="$1"
  local reply=""
  if [[ -t 0 ]]; then
    stty -echo
    trap 'stty echo' EXIT INT TERM
    read -r -p "$prompt" reply
    stty echo
    trap - EXIT INT TERM
    printf '\n' >&2
  else
    # Non-interactive stdin (e.g. piped, as in a scripted dry run): still
    # read a line, but never from argv or the environment, and never echo
    # it back.
    read -r reply
  fi
  printf '%s' "$reply"
}

keystore_password="$(prompt_password "Keystore password for '$alias_name': ")"
if [[ -z "$keystore_password" ]]; then
  echo "sign.sh: FAIL -- empty keystore password" >&2
  exit 1
fi

# apksigner is given the password via its own stdin (--ks-pass stdin /
# --key-pass stdin), which never appears in `ps` output (unlike
# --ks-pass pass:...) and is never written to a file or the environment.
# We assume the key password equals the keystore password, which is the
# case for a keystore created with `keytool -genkeypair` the way
# docs/SIGNING.md documents (no -keypass given, so it defaults to
# -storepass); apksigner is told to read both from the two lines we feed
# it on stdin.
echo "sign.sh: signing $apk_in -> $out" >&2
if ! printf '%s\n%s\n' "$keystore_password" "$keystore_password" | apksigner sign \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled true \
  --ks "$keystore" \
  --ks-key-alias "$alias_name" \
  --ks-pass stdin \
  --key-pass stdin \
  --in "$apk_in" \
  --out "$out"; then
  echo "sign.sh: FAIL -- apksigner sign failed" >&2
  exit 1
fi
keystore_password=""

if [[ ! -f "$out" ]]; then
  echo "sign.sh: FAIL -- signed APK was not produced: $out" >&2
  exit 1
fi

if [[ ! -f "${out}.idsig" ]]; then
  echo "sign.sh: FAIL -- v4 idsig was not produced: ${out}.idsig" >&2
  exit 1
fi

# --- Verify the signature schemes we expect, and nothing we don't. ---
#
# `apksigner verify` only reports "Verified using vN scheme: true" for the
# *minimal* scheme(s) actually needed to satisfy the requested SDK range --
# not merely for every scheme block physically present in the file. Since
# this app's minSdkVersion (30) is already within APK Signature Scheme v3's
# native range (28+), a default `apksigner verify` on the signed APK will
# only ever report v3=true, and will report v2=false even though the v2
# signature block was written and is valid (apksigner doesn't need it to
# satisfy API 30+, so it doesn't exercise it). To actually exercise v2's
# signature we ask apksigner to verify against a broader range starting at
# API 24 (v2's own minimum, introduced in Android Nougat) -- see `apksigner
# verify --help` ("Use --min-sdk-version [...] to verify the APK against a
# custom range of API Levels").
#
# APK Signature Scheme v4 (introduced in Android 11 / API 30, for
# incremental installs) has no equivalent CLI check in this apksigner
# version: `apksigner verify` never reports "Verified using v4 scheme: true"
# regardless of the requested range, on any build-tools version tested
# while writing this script (36.0.0, 37.0.0) -- v4 verification only
# happens on-device during an incremental install. We instead confirm v4
# structurally: the "${OUT}.idsig" file must exist, be non-empty, and begin
# with the little-endian uint32 file-format version apksigner writes for
# APK Signature Scheme v4 (version 2 as of this writing). See
# docs/SIGNING.md for more on this.
echo "sign.sh: verifying $out" >&2
verify_output="$(apksigner verify --verbose --min-sdk-version 24 "$out")"
echo "$verify_output"

assert_scheme() {
  local label="$1"
  local expected="$2"
  local line
  line="$(grep -F "$label" <<<"$verify_output" || true)"
  if [[ -z "$line" ]]; then
    echo "sign.sh: FAIL -- apksigner verify did not report '$label'" >&2
    exit 1
  fi
  if ! grep -qF "$label $expected" <<<"$line"; then
    echo "sign.sh: FAIL -- expected '$label $expected', got: $line" >&2
    exit 1
  fi
}

assert_scheme "Verified using v1 scheme (JAR signing):" "false"
assert_scheme "Verified using v2 scheme (APK Signature Scheme v2):" "true"
assert_scheme "Verified using v3 scheme (APK Signature Scheme v3):" "true"

echo "sign.sh: signature scheme check PASSED (v1=false, v2/v3=true)" >&2

# v4: structural check (see comment above for why apksigner verify can't
# confirm this one). The V4 file format's first 4 bytes are a
# little-endian uint32 version number; apksigner currently writes 2.
idsig_version="$(od -An -tu4 -N4 "${out}.idsig" | tr -d '[:space:]')"
if [[ "$idsig_version" != "2" ]]; then
  echo "sign.sh: FAIL -- ${out}.idsig does not look like a v4 signature file (version bytes: $idsig_version)" >&2
  exit 1
fi
echo "sign.sh: v4 idsig structural check PASSED (${out}.idsig, format version $idsig_version)" >&2

# --- SHA-256 sums for the signed APK and its idsig. ---
sums_file="${out_dir}/SHA256SUMS"
(
  cd "$out_dir"
  "${sha_tool[@]}" "$(basename "$out")" "$(basename "${out}.idsig")"
) >"$sums_file"

echo "sign.sh: wrote $sums_file" >&2
cat "$sums_file" >&2

# --- Signing certificate SHA-256 fingerprint, for AllowedAPKSigningKeys
#     and docs/SIGNING.md. ---
echo "sign.sh: signing certificate SHA-256 fingerprint(s):" >&2
apksigner verify --print-certs "$out" | grep 'SHA-256'

echo "sign.sh: done. Signed APK: $out" >&2
echo "sign.sh: idsig: ${out}.idsig" >&2
echo "sign.sh: sums: $sums_file" >&2
