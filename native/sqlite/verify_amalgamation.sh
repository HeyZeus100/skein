#!/usr/bin/env bash
#
# Verify (or regenerate-and-diff) the checked-in SQLCipher amalgamation.
# See docs/design/AMALGAMATION_POLICY.md for the policy this backs.
#
# Modes:
#
#   ./verify_amalgamation.sh              (default: --verify)
#       Fast (<1s): check native/sqlite/amalgamation/SHA256SUMS.txt against
#       the files actually on disk. No network, no toolchain. Safe to run on
#       every build/PR -- this is also what
#       native/sqlite/CMakeLists.txt §0 does at CMake configure time.
#
#   ./verify_amalgamation.sh --regenerate
#       Slow (roughly a minute): downloads the pinned SQLCipher release
#       tarball, verifies ITS sha256 against sqlcipher.sha256, runs the
#       documented `configure && make sqlite3.c` recipe (README.md §3), and
#       diffs the result byte-for-byte against the committed amalgamation.
#       Requires network access, tclsh, make, and a C compiler. This is what
#       the release-tag CI job runs
#       (.github/workflows/reproducible-build.yml, job
#       "amalgamation-integrity") -- intentionally NOT run on every PR
#       because of the toolchain + time cost.
#
#   ./verify_amalgamation.sh --write-hashes
#       Rewrites amalgamation/SHA256SUMS.txt from the files currently on
#       disk. Use this after a deliberate, reviewed SQLCipher bump, once the
#       new amalgamation files are in place and --regenerate confirms they
#       match upstream.
#
# Exits non-zero with a clear message on any mismatch.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AMALG_DIR="${SCRIPT_DIR}/amalgamation"
SHA_FILE="${AMALG_DIR}/SHA256SUMS.txt"
AMALG_FILES=(sqlite3.c sqlite3.h sqlite3ext.h)

# Pins -- must match native/sqlite/CMakeLists.txt §0 and sqlcipher.sha256.
# Bumping SQLCipher means updating all three together, plus the amalgamation
# files and SHA256SUMS.txt itself. See docs/design/AMALGAMATION_POLICY.md.
SQLCIPHER_VERSION="4.17.0"
SQLCIPHER_TAG="v4.17.0"
SQLCIPHER_COMMIT="f9788efa8ac4dfed75c03e4756b1666a1d0845da"
SQLCIPHER_TARBALL_SHA256="79c0e164b9c059e7487bf8f29272f601cca5f3312cc267461f81e349962a5058"
SQLCIPHER_URL="https://github.com/sqlcipher/sqlcipher/archive/refs/tags/${SQLCIPHER_TAG}.tar.gz"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

fail() {
  echo "::error::$*" >&2
  echo "ERROR: $*" >&2
  exit 1
}

verify_fast() {
  echo "Verifying amalgamation files against ${SHA_FILE}..."
  [[ -f "${SHA_FILE}" ]] || fail "Missing ${SHA_FILE}. See docs/design/AMALGAMATION_POLICY.md."

  local mismatch=0
  while read -r expected_hash filename; do
    [[ -z "${expected_hash}" ]] && continue
    [[ "${expected_hash}" == \#* ]] && continue
    local path="${AMALG_DIR}/${filename}"
    if [[ ! -f "${path}" ]]; then
      echo "MISSING: ${filename} (listed in SHA256SUMS.txt but not found)"
      mismatch=1
      continue
    fi
    local actual_hash
    actual_hash="$(sha256_of "${path}")"
    if [[ "${actual_hash}" != "${expected_hash}" ]]; then
      echo "MISMATCH: ${filename}"
      echo "  expected sha256 ${expected_hash}"
      echo "  actual   sha256 ${actual_hash}"
      mismatch=1
    fi
  done < <(grep -v '^[[:space:]]*#' "${SHA_FILE}" | grep -v '^[[:space:]]*$')

  if [[ "${mismatch}" -ne 0 ]]; then
    fail "Amalgamation SHA256SUMS.txt mismatch. This means amalgamation/*.{c,h} was modified without updating SHA256SUMS.txt (tampering, a bad merge, or a partial SQLCipher bump). Regenerate deliberately per native/sqlite/README.md §3 and docs/design/AMALGAMATION_POLICY.md, or restore the files from git if this wasn't intentional."
  fi
  echo "OK: amalgamation matches SHA256SUMS.txt"
}

verify_regenerate() {
  echo "Regenerating SQLCipher ${SQLCIPHER_VERSION} (${SQLCIPHER_COMMIT}) amalgamation from ${SQLCIPHER_URL}..."
  for tool in curl tar tclsh make cc; do
    command -v "${tool}" >/dev/null 2>&1 || fail "Missing required tool: ${tool} (needed to regenerate the amalgamation -- see README.md §3)"
  done

  local work
  work="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '${work}'" EXIT

  local tarball="${work}/sqlcipher-${SQLCIPHER_VERSION}.tar.gz"
  curl -sL -o "${tarball}" "${SQLCIPHER_URL}"

  local tarball_hash
  tarball_hash="$(sha256_of "${tarball}")"
  if [[ "${tarball_hash}" != "${SQLCIPHER_TARBALL_SHA256}" ]]; then
    fail "SQLCipher tarball sha256 mismatch: expected ${SQLCIPHER_TARBALL_SHA256}, got ${tarball_hash}. Refusing to build the amalgamation from an unverified source. Check sqlcipher.sha256 and the pin in this script."
  fi
  echo "OK: SQLCipher tarball sha256 verified"

  tar xzf "${tarball}" -C "${work}"
  local src="${work}/sqlcipher-${SQLCIPHER_VERSION}"
  [[ -d "${src}" ]] || fail "Expected extracted source dir ${src} not found after extracting the tarball"

  local configure_extra=()
  if [[ "$(uname -s)" == "Darwin" ]]; then
    # SQLCipher's configure misreports the libm probe as failing on Apple
    # Silicon because -lm is a no-op there. Linux CI omits this. See README.md §3.
    configure_extra+=(--disable-math)
  fi

  (
    cd "${src}"
    ./configure --with-tempstore=yes --fts5 --disable-tcl \
                --disable-load-extension "${configure_extra[@]}" \
                CFLAGS="-DSQLITE_HAS_CODEC -DSQLCIPHER_CRYPTO_OPENSSL"
    make sqlite3.c
  )

  local mismatch=0
  for f in "${AMALG_FILES[@]}"; do
    if ! diff -q "${src}/${f}" "${AMALG_DIR}/${f}" >/dev/null 2>&1; then
      echo "MISMATCH: regenerated ${f} differs from committed amalgamation/${f}"
      mismatch=1
    else
      echo "OK: ${f} matches the pinned SQLCipher source"
    fi
  done

  if [[ "${mismatch}" -ne 0 ]]; then
    fail "Regenerated amalgamation differs from the committed one. Either SQLCipher was bumped without regenerating amalgamation/, or the pin (commit ${SQLCIPHER_COMMIT} / tarball sha256 ${SQLCIPHER_TARBALL_SHA256}) is stale. Regenerate per native/sqlite/README.md §3, then run '$0 --write-hashes' and commit both the amalgamation and SHA256SUMS.txt."
  fi
  echo "OK: regenerated amalgamation is byte-identical to the committed one."
}

write_hashes() {
  echo "Writing ${SHA_FILE} from files currently on disk..."
  for f in "${AMALG_FILES[@]}"; do
    [[ -f "${AMALG_DIR}/${f}" ]] || fail "Cannot write hashes: ${AMALG_DIR}/${f} does not exist"
  done
  {
    echo "# SHA-256 of the checked-in SQLCipher ${SQLCIPHER_VERSION} amalgamation."
    echo "# Regenerated by verify_amalgamation.sh --write-hashes. See"
    echo "# docs/design/AMALGAMATION_POLICY.md and README.md §3."
    echo "#"
    echo "# Verify with (from this directory): shasum -a 256 -c SHA256SUMS.txt"
    for f in "${AMALG_FILES[@]}"; do
      printf '%s  %s\n' "$(sha256_of "${AMALG_DIR}/${f}")" "${f}"
    done
  } > "${SHA_FILE}"
  echo "OK: wrote $(wc -l < "${SHA_FILE}" | tr -d ' ') lines to ${SHA_FILE}"
}

mode="${1:---verify}"
case "${mode}" in
  --verify)
    verify_fast
    ;;
  --regenerate)
    verify_fast
    verify_regenerate
    ;;
  --write-hashes)
    write_hashes
    ;;
  *)
    echo "Usage: $0 [--verify|--regenerate|--write-hashes]" >&2
    exit 2
    ;;
esac
