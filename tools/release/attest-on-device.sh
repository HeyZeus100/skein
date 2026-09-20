#!/usr/bin/env bash
#
# E1.I9 (spec §10, OQ-8): STUB. This script is a placeholder for the
# StrongBox "second attestation" flow described in docs/SIGNING.md
# ("StrongBox second attestation"): a StrongBox-backed key resident on the
# maintainer's device signs SHA256SUMS as a hardware-bound attestation,
# independent of (and in addition to) the release APK's own software
# keystore signature.
#
# This is NOT implemented yet. Designing and implementing the on-device
# StrongBox signing flow (attestation format, Android Keystore StrongBox
# API usage, how the attestation gets published alongside a release) is
# out of scope for skein-6pf and is tracked as separate follow-up work --
# search bd for "attest-on-device" / StrongBox second attestation, or file
# a new bead before starting it.
#
# Do not run this script expecting it to do anything yet -- it only
# documents the intended interface and exits non-zero.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/release/attest-on-device.sh <SHA256SUMS>

NOT YET IMPLEMENTED. See docs/SIGNING.md ("StrongBox second attestation")
for the design intent: a StrongBox-backed key on the maintainer's device
signs the given SHA256SUMS file as a second, hardware-backed attestation,
independent of the release APK's own signing key.

TODO (tracked as follow-up work, not part of skein-6pf):
  - Decide the attestation output format (detached signature file?
    alongside SHA256SUMS as e.g. SHA256SUMS.strongbox-attestation?).
  - Generate (once) and document custody for a StrongBox-backed signing
    key in the Android Keystore, non-exportable by construction, on the
    attesting device.
  - Implement the actual on-device signing flow: this will need to run
    as an app or adb-driven Keystore operation on the device itself
    (a plain shell script on a development machine cannot invoke the
    Android Keystore StrongBox API directly), so this file's ultimate
    implementation likely wraps an `adb shell` invocation of an on-device
    helper, not pure shell.
  - Decide how a downstream user checks this attestation (a second
    published fingerprint? a verification script analogous to
    tools/release/verify.sh?).

Options:
  -h, --help    Show this help and exit.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

echo "attest-on-device.sh: NOT IMPLEMENTED -- see docs/SIGNING.md (StrongBox second attestation) and this file's TODOs" >&2
exit 1
