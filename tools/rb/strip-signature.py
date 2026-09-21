#!/usr/bin/env python3
"""E1.I8 (skein-ddp) — turn a *signed* release APK back into the bytes a
verifier can compare against their own `:app:assembleFossRelease` output.

    tools/rb/strip-signature.py <signed.apk> -o <stripped.apk>
    tools/rb/strip-signature.py <apk> --manifest      # canonical entry table
    tools/rb/strip-signature.py --self-test

Why this is needed at all
-------------------------
The unit of reproducibility is the UNSIGNED APK
(`app-foss-release-unsigned.apk`). A third party cannot reproduce a signature
— they do not have the key — and does not need to: they rebuild the unsigned
APK, check that it matches the released APK's *payload*, and check the
signature separately against the published certificate
(`apksigner verify --print-certs`, see docs/SIGNING.md).

What signing actually adds
--------------------------
`apksigner` v2/v3 does not touch a single local file entry or central
directory record. It inserts one opaque region — the **APK Signing Block** —
between the last local file entry and the central directory, and bumps the
"offset of start of central directory" field in the End Of Central Directory
record to account for it. Removing that region and restoring the offset gives
back the original unsigned bytes exactly, which is why this script's headline
mode is byte-exact rather than a re-zip.

v1 (JAR) signing is different: it *adds entries*
(`META-INF/MANIFEST.MF`, `META-INF/*.SF`, `META-INF/*.RSA|DSA|EC`). Skein's
`minSdk` is 30, so apksigner signs v2+v3 only and no such entries exist; if
they ever do, `--drop-v1` removes them, but the result is a REBUILT zip whose
byte layout is apksigner's, not AGP's. That output is only meaningful for the
entry-wise comparison (`--manifest`), and the script says so rather than
pretending the whole-file sha256 still means something.

This script never *normalises* anything. Reordering entries or rewriting
timestamps to force a match would hide exactly the nondeterminism the
reproducible-build check exists to catch (see `tools/rb/normalize-apk.py`,
which is diagnostic-only for the same reason).
"""

from __future__ import annotations

import argparse
import hashlib
import os
import struct
import sys
import tempfile
import zipfile

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
EOCD_SIG = b"PK\x05\x06"
EOCD64_LOCATOR_SIG = b"PK\x06\x07"

# v1 (JAR) signature entries. Matched case-insensitively on the basename,
# exactly as the Android PackageParser does.
V1_SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")
V1_MANIFEST_NAME = "META-INF/MANIFEST.MF"


class StripError(Exception):
    pass


def _find_eocd(data: bytes) -> int:
    """Offset of the End Of Central Directory record.

    Scanned backwards because the record is variable length (it carries a
    trailing comment). The 64 KiB window is the maximum a comment can be.
    """
    window = min(len(data), 0xFFFF + 22)
    start = len(data) - window
    idx = data.rfind(EOCD_SIG, start)
    if idx < 0:
        raise StripError("no End Of Central Directory record: not a zip/APK")
    return idx


def _cd_offset(data: bytes, eocd: int) -> int:
    (offset,) = struct.unpack_from("<I", data, eocd + 16)
    if offset == 0xFFFFFFFF:
        raise StripError(
            "Zip64 APK: the central-directory offset is in a Zip64 EOCD record. "
            "Skein's release APK is ~100 MB and never hits this; refusing to "
            "guess rather than silently mis-strip."
        )
    return offset


def find_signing_block(data: bytes) -> tuple[int, int] | None:
    """Return (start, end) of the APK Signing Block, or None if unsigned.

    Layout, per the APK Signature Scheme v2 spec:

        uint64  size-of-block (excluding this very field)
        ...     id-value pairs ...
        uint64  size-of-block (repeated)
        16B     "APK Sig Block 42"

    so the footer sits at cd_offset-24 and the block starts at
    cd_offset - size - 8.
    """
    eocd = _find_eocd(data)
    cd = _cd_offset(data, eocd)
    if cd < 24:
        return None
    if data[cd - 16 : cd] != APK_SIG_BLOCK_MAGIC:
        return None
    (size_footer,) = struct.unpack_from("<Q", data, cd - 24)
    start = cd - size_footer - 8
    if start < 0:
        raise StripError("APK Signing Block footer size runs off the front of the file")
    (size_header,) = struct.unpack_from("<Q", data, start)
    if size_header != size_footer:
        raise StripError(
            f"APK Signing Block size fields disagree ({size_header} vs {size_footer})"
        )
    return (start, cd)


def v1_signature_entries(path: str) -> list[str]:
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
    hits = []
    for name in names:
        upper = name.upper()
        if upper == V1_MANIFEST_NAME:
            hits.append(name)
        elif upper.startswith("META-INF/") and upper.endswith(V1_SIGNATURE_SUFFIXES):
            hits.append(name)
    return hits


def strip(src: str, dst: str, drop_v1: bool = False) -> dict:
    """Write `src` minus its signatures to `dst`. Returns a small report."""
    with open(src, "rb") as fh:
        data = fh.read()

    report = {"signing_block_bytes": 0, "v1_entries": [], "rebuilt": False}

    block = find_signing_block(data)
    if block is not None:
        start, end = block
        report["signing_block_bytes"] = end - start
        eocd = _find_eocd(data)
        out = bytearray(data[:start] + data[end:])
        # The central directory moved back by exactly the block's length;
        # every other EOCD field (counts, CD size) is unchanged.
        new_eocd = eocd - (end - start)
        struct.pack_into("<I", out, new_eocd + 16, start)
        data = bytes(out)

    with open(dst, "wb") as fh:
        fh.write(data)

    v1 = v1_signature_entries(dst)
    if v1:
        report["v1_entries"] = v1
        if not drop_v1:
            raise StripError(
                "APK carries v1 (JAR) signature entries: "
                + ", ".join(v1)
                + "\nRe-run with --drop-v1. NOTE: that rebuilds the zip, so only "
                "the entry-wise comparison (--manifest) stays meaningful."
            )
        _rebuild_without(dst, set(v1))
        report["rebuilt"] = True

    return report


def _rebuild_without(path: str, drop: set[str]) -> None:
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(path)))
    os.close(fd)
    try:
        with zipfile.ZipFile(path) as src, zipfile.ZipFile(tmp, "w") as dst:
            for info in src.infolist():
                if info.filename in drop:
                    continue
                # Copy the entry's own compression settings; do not renormalise.
                dst.writestr(info, src.read(info.filename))
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def manifest(path: str) -> str:
    """A canonical, signature-free entry table.

    Sorted by name so it diffs cleanly, and carrying the fields that make a
    difference explainable: compression method, CRC, sizes and the content
    sha256. Stored ORDER is deliberately not encoded here — order is checked
    by tools/ci/compare-apk-entries.sh, which compares it unsorted precisely
    because a reordering is a real failure.
    """
    drop = {n for n in v1_signature_entries(path)}
    rows = []
    with zipfile.ZipFile(path) as zf:
        for info in sorted(zf.infolist(), key=lambda i: i.filename):
            if info.filename in drop:
                continue
            digest = hashlib.sha256(zf.read(info.filename)).hexdigest()
            rows.append(
                f"{info.filename}\tmethod={info.compress_type}\t"
                f"crc={info.CRC:08x}\tsize={info.file_size}\tsha256={digest}"
            )
    return "\n".join(rows) + "\n"


# --------------------------------------------------------------------------
# self-test — the negative case first, per this repo's CI convention
# --------------------------------------------------------------------------


def _make_zip(path: str, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, payload in entries.items():
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, payload)


def _fake_sign(unsigned: str, signed: str, payload: bytes = b"x" * 512) -> None:
    """Insert a well-formed APK Signing Block, exactly as apksigner would.

    A real signature is not needed — this script only ever removes the block,
    never validates it — but the *framing* must be real or the test proves
    nothing about the parser.
    """
    with open(unsigned, "rb") as fh:
        data = fh.read()
    eocd = _find_eocd(data)
    cd = _cd_offset(data, eocd)

    # one id-value pair: uint64 length, uint32 id, value
    pair = struct.pack("<QI", 4 + len(payload), 0x7109871A) + payload
    size = len(pair) + 8 + 16  # trailing size field + magic
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + APK_SIG_BLOCK_MAGIC

    out = bytearray(data[:cd] + block + data[cd:])
    new_eocd = eocd + len(block)
    struct.pack_into("<I", out, new_eocd + 16, cd + len(block))
    with open(signed, "wb") as fh:
        fh.write(bytes(out))


def self_test() -> int:
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        unsigned = os.path.join(tmp, "unsigned.apk")
        signed = os.path.join(tmp, "signed.apk")
        out = os.path.join(tmp, "stripped.apk")

        _make_zip(
            unsigned,
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex" * 100,
                "lib/arm64-v8a/libskein_sqlite.so": b"so" * 500,
                "resources.arsc": b"arsc" * 50,
            },
        )
        original = open(unsigned, "rb").read()

        # 0. An unsigned APK has no block, and stripping is a faithful copy.
        if find_signing_block(original) is None:
            print("[ok]   an unsigned APK is reported as having no signing block")
        else:
            print("[FAIL] phantom signing block found in an unsigned APK")
            failures += 1

        # 1. THE contract: sign -> strip must return the original bytes.
        _fake_sign(unsigned, signed)
        signed_bytes = open(signed, "rb").read()
        if signed_bytes == original:
            print("[FAIL] self-test fixture did not actually change the APK")
            failures += 1
        report = strip(signed, out)
        if open(out, "rb").read() == original:
            print(
                "[ok]   sign -> strip round-trips byte-for-byte "
                f"({report['signing_block_bytes']} block bytes removed)"
            )
        else:
            print("[FAIL] stripped APK does not equal the original unsigned APK")
            failures += 1

        # 2. Negative case: a deliberately ALTERED payload must survive the
        #    strip as a difference. This is the check that makes verify.sh's
        #    verdict trustworthy — if stripping normalised content away, a
        #    tampered release would compare equal to a clean rebuild.
        tampered = os.path.join(tmp, "tampered.apk")
        tampered_signed = os.path.join(tmp, "tampered-signed.apk")
        tampered_out = os.path.join(tmp, "tampered-stripped.apk")
        _make_zip(
            tampered,
            {
                "AndroidManifest.xml": b"manifest",
                "classes.dex": b"dex" * 99 + b"EVIL",
                "lib/arm64-v8a/libskein_sqlite.so": b"so" * 500,
                "resources.arsc": b"arsc" * 50,
            },
        )
        _fake_sign(tampered, tampered_signed)
        strip(tampered_signed, tampered_out)
        if open(tampered_out, "rb").read() != original:
            print("[ok]   a tampered classes.dex still differs after stripping")
        else:
            print("[FAIL] stripping erased a real payload difference")
            failures += 1
        if "EVIL" in manifest(tampered_out) or manifest(tampered_out) != manifest(out):
            print("[ok]   the entry manifest reports the tampered entry")
        else:
            print("[FAIL] entry manifest did not report the tampered entry")
            failures += 1

        # 3. Negative case: a differently-sized signing block must not shift
        #    the result — stripping is size-independent.
        signed2 = os.path.join(tmp, "signed2.apk")
        out2 = os.path.join(tmp, "stripped2.apk")
        _fake_sign(unsigned, signed2, payload=b"y" * 4096)
        strip(signed2, out2)
        if open(out2, "rb").read() == original:
            print("[ok]   a 4 KiB signing block strips to the same bytes")
        else:
            print("[FAIL] strip result depends on the signing block's size")
            failures += 1

        # 4. v1 (JAR) signature entries are detected and refused by default.
        v1 = os.path.join(tmp, "v1.apk")
        _make_zip(
            v1,
            {
                "AndroidManifest.xml": b"manifest",
                "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
                "META-INF/CERT.SF": b"Signature-Version: 1.0\n",
                "META-INF/CERT.RSA": b"\x30\x82",
            },
        )
        found = sorted(v1_signature_entries(v1))
        expected = ["META-INF/CERT.RSA", "META-INF/CERT.SF", "META-INF/MANIFEST.MF"]
        if found == expected:
            print("[ok]   v1 signature entries are all detected")
        else:
            print(f"[FAIL] v1 detection returned {found}, expected {expected}")
            failures += 1
        try:
            strip(v1, os.path.join(tmp, "v1-stripped.apk"))
            print("[FAIL] a v1-signed APK was stripped silently instead of refused")
            failures += 1
        except StripError:
            print("[ok]   a v1-signed APK is refused unless --drop-v1 is given")
        v1_out = os.path.join(tmp, "v1-dropped.apk")
        strip(v1, v1_out, drop_v1=True)
        if not v1_signature_entries(v1_out):
            print("[ok]   --drop-v1 removes every v1 entry")
        else:
            print("[FAIL] --drop-v1 left v1 entries behind")
            failures += 1

        # 5. Garbage in must not silently produce garbage out.
        junk = os.path.join(tmp, "junk.bin")
        with open(junk, "wb") as fh:
            fh.write(b"not a zip at all")
        try:
            find_signing_block(open(junk, "rb").read())
            print("[FAIL] a non-zip was accepted")
            failures += 1
        except StripError:
            print("[ok]   a non-zip input is rejected with a named error")

    print()
    if failures == 0:
        print("SELF-TEST PASSED (9/9)")
        return 0
    print(f"SELF-TEST FAILED ({failures} check(s))")
    return 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk", nargs="?", help="signed APK to strip")
    ap.add_argument("-o", "--output", help="where to write the stripped APK")
    ap.add_argument(
        "--manifest",
        action="store_true",
        help="print a canonical, signature-free entry table instead of stripping",
    )
    ap.add_argument(
        "--drop-v1",
        action="store_true",
        help="also remove v1 (JAR) signature entries; REBUILDS the zip",
    )
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.apk:
        ap.error("need an APK (or --self-test)")

    try:
        if args.manifest:
            sys.stdout.write(manifest(args.apk))
            return 0
        if not args.output:
            ap.error("need -o/--output")
        report = strip(args.apk, args.output, drop_v1=args.drop_v1)
    except StripError as exc:
        print(f"strip-signature: {exc}", file=sys.stderr)
        return 2

    print(f"wrote {args.output}")
    print(f"  APK Signing Block removed: {report['signing_block_bytes']} bytes")
    if report["v1_entries"]:
        print(f"  v1 entries dropped: {', '.join(report['v1_entries'])}")
    if report["rebuilt"]:
        print(
            "  WARNING: the zip was REBUILT to drop v1 entries. Its whole-file\n"
            "  sha256 is apksigner's layout, not AGP's — compare entry-wise\n"
            "  (--manifest / tools/ci/compare-apk-entries.sh) only."
        )
    with open(args.output, "rb") as fh:
        print(f"  sha256: {hashlib.sha256(fh.read()).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
