#!/usr/bin/env python3
"""E1.I8 (skein-ddp) — EXPLAIN a difference between two APKs. Never fix one.

    tools/rb/normalize-apk.py <apk>                 # normalised view of one APK
    tools/rb/normalize-apk.py <apkA> <apkB>         # what differs, and how
    tools/rb/normalize-apk.py --self-test

READ THIS BEFORE USING IT
-------------------------
This script deliberately does **not** rewrite an APK. Skein's contract
(`E1.I8`) is that `:app:assembleFossRelease` is itself deterministic — the
whole-file sha256 of `app-foss-release-unsigned.apk` must already match
across machines. A "normalize then compare" step would turn every real
nondeterminism into a green check: re-sorting entries, zeroing timestamps or
recompressing would make a build that packs entries in a different order
compare equal to one that does not, and entry order *is* part of the archive
bytes. That is why the plan says, in as many words, that normalisation "is
NOT used — the build itself must be deterministic; that script exists only to
explain diffs".

So: nothing here is wired into the Gradle build, into
`.github/workflows/reproducible-build.yml`'s verdict, or into
`tools/rb/verify.sh`'s verdict. It is a human-facing lens for the moment the
verdict is already red and you need to know *why*.

Relationship to the other tools:

  tools/ci/compare-apk-entries.sh  the verdict: order, metadata, per-entry
                                   content, whole-file sha256
  tools/rb/strip-signature.py      makes a signed release APK comparable to a
                                   locally rebuilt unsigned one
  THIS SCRIPT                      classifies a diff: timestamp-only?
                                   order-only? compression-only? real payload?
  diffoscope                       the deep dive, once you know which entries
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import tempfile
import zipfile
from dataclasses import dataclass

METHOD_NAMES = {
    zipfile.ZIP_STORED: "stored",
    zipfile.ZIP_DEFLATED: "deflated",
}


@dataclass(frozen=True)
class Entry:
    name: str
    order: int
    method: int
    crc: int
    size: int
    compressed: int
    date_time: tuple
    sha256: str


def read_entries(path: str) -> list[Entry]:
    out = []
    with zipfile.ZipFile(path) as zf:
        for order, info in enumerate(zf.infolist()):
            digest = hashlib.sha256(zf.read(info.filename)).hexdigest()
            out.append(
                Entry(
                    name=info.filename,
                    order=order,
                    method=info.compress_type,
                    crc=info.CRC,
                    size=info.file_size,
                    compressed=info.compress_size,
                    date_time=info.date_time,
                    sha256=digest,
                )
            )
    return out


def render(entries: list[Entry]) -> str:
    lines = []
    for e in entries:
        lines.append(
            f"{e.order:5d}  {e.name}\n"
            f"         method={METHOD_NAMES.get(e.method, e.method)} "
            f"crc={e.crc:08x} size={e.size} compressed={e.compressed} "
            f"mtime={'%04d-%02d-%02d %02d:%02d:%02d' % e.date_time}\n"
            f"         sha256={e.sha256}"
        )
    return "\n".join(lines) + "\n"


def classify(a: list[Entry], b: list[Entry]) -> tuple[list[str], int]:
    """Explain A vs B. Returns (report lines, number of differences)."""
    lines: list[str] = []
    by_a = {e.name: e for e in a}
    by_b = {e.name: e for e in b}
    diffs = 0

    only_a = sorted(set(by_a) - set(by_b))
    only_b = sorted(set(by_b) - set(by_a))
    for name in only_a:
        lines.append(f"MEMBERSHIP  only in A: {name}")
        diffs += 1
    for name in only_b:
        lines.append(f"MEMBERSHIP  only in B: {name}")
        diffs += 1

    shared = [n for n in by_a if n in by_b]
    order_a = [e.name for e in a if e.name in by_b]
    order_b = [e.name for e in b if e.name in by_a]
    if order_a != order_b:
        lines.append(
            "ORDER       the shared entries are stored in a different order. "
            "This changes the archive bytes even when every entry matches, "
            "and is a genuine reproducibility failure -- do not 'normalise' it away."
        )
        diffs += 1

    for name in sorted(shared):
        ea, eb = by_a[name], by_b[name]
        reasons = []
        if ea.sha256 != eb.sha256:
            reasons.append(f"CONTENT (sha256 {ea.sha256[:16]}… vs {eb.sha256[:16]}…)")
        if ea.date_time != eb.date_time:
            reasons.append(f"TIMESTAMP ({ea.date_time} vs {eb.date_time})")
        if ea.method != eb.method:
            reasons.append(
                "METHOD ("
                f"{METHOD_NAMES.get(ea.method, ea.method)} vs "
                f"{METHOD_NAMES.get(eb.method, eb.method)})"
            )
        if ea.sha256 == eb.sha256 and ea.compressed != eb.compressed:
            reasons.append(
                f"COMPRESSION-ONLY (same bytes, {ea.compressed} vs "
                f"{eb.compressed} compressed -- a compression LEVEL or "
                "zlib-version difference, i.e. a toolchain mismatch)"
            )
        if reasons:
            lines.append(f"ENTRY       {name}: " + "; ".join(reasons))
            diffs += 1

    if diffs == 0:
        lines.append(
            "No entry-level difference. If the whole-file sha256 still differs, "
            "the diff is outside the entries: an APK Signing Block "
            "(run tools/rb/strip-signature.py first), a zip comment, or "
            "padding/alignment."
        )
    return lines, diffs


def self_test() -> int:
    import os

    failures = 0

    def build(path, entries, date_time=(1980, 1, 1, 0, 0, 0), method=zipfile.ZIP_DEFLATED):
        with zipfile.ZipFile(path, "w") as zf:
            for name, payload in entries:
                info = zipfile.ZipInfo(name, date_time=date_time)
                info.compress_type = method
                zf.writestr(info, payload)

    with tempfile.TemporaryDirectory() as tmp:
        base = [("AndroidManifest.xml", b"m"), ("classes.dex", b"d" * 200)]
        a = os.path.join(tmp, "a.apk")
        build(a, base)

        # Identical -> no diffs.
        b = os.path.join(tmp, "b.apk")
        build(b, base)
        _, n = classify(read_entries(a), read_entries(b))
        if n == 0:
            print("[ok]   identical APKs classify as no difference")
        else:
            print(f"[FAIL] identical APKs reported {n} difference(s)")
            failures += 1

        # Content-only.
        c = os.path.join(tmp, "c.apk")
        build(c, [("AndroidManifest.xml", b"m"), ("classes.dex", b"d" * 199 + b"X")])
        lines, n = classify(read_entries(a), read_entries(c))
        if n == 1 and any("CONTENT" in ln and "classes.dex" in ln for ln in lines):
            print("[ok]   a content difference is classified as CONTENT and named")
        else:
            print(f"[FAIL] content diff misclassified: {lines}")
            failures += 1

        # Timestamp-only.
        d = os.path.join(tmp, "d.apk")
        build(d, base, date_time=(2030, 5, 4, 3, 2, 1))
        lines, n = classify(read_entries(a), read_entries(d))
        if n == 2 and all("TIMESTAMP" in ln for ln in lines):
            print("[ok]   a timestamp-only difference is classified as TIMESTAMP")
        else:
            print(f"[FAIL] timestamp diff misclassified: {lines}")
            failures += 1

        # Order-only.
        e = os.path.join(tmp, "e.apk")
        build(e, list(reversed(base)))
        lines, n = classify(read_entries(a), read_entries(e))
        if any("ORDER" in ln for ln in lines):
            print("[ok]   a reordering is classified as ORDER, not hidden")
        else:
            print(f"[FAIL] reordering was not reported: {lines}")
            failures += 1

        # Membership.
        f = os.path.join(tmp, "f.apk")
        build(f, base + [("META-INF/rogue.txt", b"stowaway")])
        lines, n = classify(read_entries(a), read_entries(f))
        if any("MEMBERSHIP" in ln and "rogue" in ln for ln in lines):
            print("[ok]   an added entry is classified as MEMBERSHIP and named")
        else:
            print(f"[FAIL] added entry not reported: {lines}")
            failures += 1

        # Compression-only (same bytes, different method).
        g = os.path.join(tmp, "g.apk")
        build(g, base, method=zipfile.ZIP_STORED)
        lines, n = classify(read_entries(a), read_entries(g))
        if any("METHOD" in ln for ln in lines):
            print("[ok]   a compression-method change is classified as METHOD")
        else:
            print(f"[FAIL] method change not reported: {lines}")
            failures += 1

        # The contract this script must NOT break: it never writes an APK.
        before = sorted(os.listdir(tmp))
        classify(read_entries(a), read_entries(c))
        if sorted(os.listdir(tmp)) == before:
            print("[ok]   classification produces no files: nothing is normalised")
        else:
            print("[FAIL] classification wrote a file -- it must be read-only")
            failures += 1

    print()
    if failures == 0:
        print("SELF-TEST PASSED (7/7)")
        return 0
    print(f"SELF-TEST FAILED ({failures} check(s))")
    return 1


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apks", nargs="*", help="one APK to view, or two to compare")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if len(args.apks) == 1:
        sys.stdout.write(render(read_entries(args.apks[0])))
        return 0
    if len(args.apks) == 2:
        lines, diffs = classify(read_entries(args.apks[0]), read_entries(args.apks[1]))
        print("\n".join(lines))
        print()
        print(f"{diffs} difference(s) classified.")
        print(
            "This script explains; it does not decide. The verdict is "
            "tools/ci/compare-apk-entries.sh."
        )
        # Exit 0 either way: this is a diagnostic, and a CI step that treated
        # its exit code as a verdict would be exactly the mistake the header
        # warns about.
        return 0
    ap.error("need one or two APKs (or --self-test)")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
