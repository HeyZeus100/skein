#!/usr/bin/env python3
"""E1.I8 (skein-ddp) — keep `reproducible-builds.yml` honest.

    tools/rb/manifest-check.py            # check the repo's manifest
    tools/rb/manifest-check.py --self-test

`reproducible-builds.yml` is a *copy* of values that live elsewhere: the JDK
and NDK in `.tool-versions`, AGP and Kotlin in `gradle/libs.versions.toml`,
the Gradle distribution in `gradle/wrapper/gradle-wrapper.properties`, the
wrapper JAR's own sha256, the submodule pins in `native/llama/PINNED_COMMIT`,
the SQLCipher/sqlite-vec/OpenSSL tarball hashes in `native/sqlite/*.sha256`,
and the determinism settings in `app/build.gradle.kts`.

A copy that nothing checks is a lie waiting to happen: the moment someone
bumps AGP, the manifest a verifier is told to trust starts describing a build
that no longer exists, and every rebuild they attempt fails for a reason the
document actively misdirects them about. So this runs in CI, and a drift is
an error — fixed by editing the SOURCE, never by editing the manifest alone.

Exit status: 0 when every value agrees, 1 on any drift, 2 on a usage/IO error.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import sys
import tempfile
import tomllib

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class Checker:
    def __init__(self, repo: str, manifest_path: str):
        self.repo = repo
        self.manifest_path = manifest_path
        with open(manifest_path, "rb") as fh:
            self.m = yaml.safe_load(fh)
        self.failures: list[str] = []
        self.checks = 0

    # -- helpers -----------------------------------------------------------
    def read(self, rel: str) -> str:
        with open(os.path.join(self.repo, rel), encoding="utf-8") as fh:
            return fh.read()

    def expect(self, label: str, got, want, source: str) -> None:
        self.checks += 1
        if got == want:
            print(f"[ok]   {label} = {want!r} (matches {source})")
        else:
            print(f"[FAIL] {label}: manifest says {want!r}, {source} says {got!r}")
            self.failures.append(label)

    def expect_true(self, label: str, ok: bool, detail: str) -> None:
        self.checks += 1
        if ok:
            print(f"[ok]   {label}")
        else:
            print(f"[FAIL] {label}: {detail}")
            self.failures.append(label)

    # -- individual checks -------------------------------------------------
    def check_tool_versions(self) -> None:
        pins = {}
        for line in self.read(".tool-versions").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                pins[parts[0]] = parts[1]

        jdk = self.m["toolchain"]["jdk"]
        # asdf spells it "<vendor>-<version>", e.g. temurin-17.0.20+8.
        want = f"{jdk['vendor']}-{jdk['version']}"
        self.expect("jdk vendor-version", pins.get("java"), want, ".tool-versions")

        self.expect(
            "android-ndk version",
            pins.get("android-ndk"),
            self.m["toolchain"]["android_ndk"]["version"],
            ".tool-versions",
        )

    def check_ndk_in_gradle(self) -> None:
        want = self.m["toolchain"]["android_ndk"]["version"]
        for rel in ("core/vault/build.gradle.kts", "inference-service/build.gradle.kts"):
            found = re.findall(r'ndkVersion\s*=\s*"([^"]+)"', self.read(rel))
            self.expect_true(
                f"ndkVersion in {rel}",
                bool(found) and all(v == want for v in found),
                f"expected every ndkVersion to be {want!r}, found {found!r}",
            )

    def check_gradle(self) -> None:
        props = self.read("gradle/wrapper/gradle-wrapper.properties")
        # The properties file escapes the URL's colon (`https\://`).
        url = re.search(r"distributionUrl=(\S+)", props).group(1).replace("\\:", ":")
        g = self.m["toolchain"]["gradle"]
        self.expect("gradle distributionUrl", url, g["distribution_url"], "gradle-wrapper.properties")
        self.expect_true(
            "gradle version appears in distributionUrl",
            f"gradle-{g['version']}-" in url,
            f"url {url!r} does not contain gradle-{g['version']}-",
        )

        jar = os.path.join(self.repo, "gradle/wrapper/gradle-wrapper.jar")
        with open(jar, "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
        self.expect("gradle wrapper jar sha256", digest, g["wrapper_jar_sha256"], jar)

    def check_catalog(self) -> None:
        with open(os.path.join(self.repo, "gradle/libs.versions.toml"), "rb") as fh:
            catalog = tomllib.load(fh)
        versions = catalog["versions"]
        self.expect(
            "AGP version", versions.get("agp"), self.m["toolchain"]["agp"]["version"], "libs.versions.toml"
        )
        self.expect(
            "Kotlin version",
            versions.get("kotlin"),
            self.m["toolchain"]["kotlin"]["version"],
            "libs.versions.toml",
        )

    def check_sdk(self) -> None:
        src = self.read("app/build.gradle.kts")
        sdk = self.m["toolchain"]["android_sdk"]
        for key, pattern in (
            ("compile_sdk", r"compileSdk\s*=\s*(\d+)"),
            ("target_sdk", r"targetSdk\s*=\s*(\d+)"),
            ("min_sdk", r"minSdk\s*=\s*(\d+)"),
        ):
            found = re.search(pattern, src)
            self.expect(
                f"{key}", int(found.group(1)) if found else None, sdk[key], "app/build.gradle.kts"
            )

    def check_source_date_epoch(self) -> None:
        want = self.m["source_date_epoch"]["cmake_fallback_constant"]
        for rel in ("native/llama/CMakeLists.txt", "native/sqlite/CMakeLists.txt"):
            found = re.search(r'set\(_SOURCE_DATE_EPOCH\s+"(\d+)"\)', self.read(rel))
            self.expect(
                f"SOURCE_DATE_EPOCH fallback in {rel}",
                int(found.group(1)) if found else None,
                want,
                rel,
            )
        impl = self.m["source_date_epoch"]["implementation"]
        found = re.search(r"CMAKE_FALLBACK_CONSTANT=(\d+)", self.read(impl))
        self.expect(
            f"SOURCE_DATE_EPOCH fallback in {impl}",
            int(found.group(1)) if found else None,
            want,
            impl,
        )
        # The one thing that must never be true again: deriving the epoch
        # from CI push metadata rather than from the commit (see that
        # script's header). Comment lines are excluded so the workflow can
        # keep explaining *why* `pushed_at` is wrong without tripping this.
        wf_code = "\n".join(
            line
            for line in self.read(".github/workflows/reproducible-build.yml").splitlines()
            if not line.lstrip().startswith("#")
        )
        self.expect_true(
            "SOURCE_DATE_EPOCH is not derived from CI push metadata",
            "pushed_at" not in wf_code,
            "reproducible-build.yml still references `pushed_at`, which changes "
            "per push and cannot be reproduced locally",
        )
        self.expect_true(
            "the CI workflow derives SOURCE_DATE_EPOCH via the shared script",
            "tools/rb/source-date-epoch.sh --export" in wf_code,
            "reproducible-build.yml does not call tools/rb/source-date-epoch.sh; "
            "a second derivation would let CI and a local verifier disagree",
        )

    def check_submodule_pins(self) -> None:
        pins = {}
        for line in self.read("native/llama/PINNED_COMMIT").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            if len(parts) == 3:
                pins[parts[0]] = (parts[1], parts[2])

        for entry in self.m["native"]["submodules"]:
            got = pins.get(entry["path"])
            self.expect(
                f"submodule {entry['path']}",
                got,
                (entry["commit"], entry["tag"]),
                "native/llama/PINNED_COMMIT",
            )
        self.expect_true(
            "manifest lists every PINNED_COMMIT record",
            {e["path"] for e in self.m["native"]["submodules"]} == set(pins),
            f"manifest has {sorted(e['path'] for e in self.m['native']['submodules'])}, "
            f"PINNED_COMMIT has {sorted(pins)}",
        )

    def check_tarballs(self) -> None:
        for entry in self.m["native"]["tarballs"]:
            text = self.read(entry["source"])
            row = None
            for line in text.splitlines():
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                row = line.split()
                break
            self.expect_true(
                f"{entry['name']} provenance record parses ({entry['source']})",
                row is not None and len(row) >= 2,
                f"no `<sha256>  <filename>  <url>` record in {entry['source']}",
            )
            if not row:
                continue
            self.expect(f"{entry['name']} tarball sha256", row[0], entry["sha256"], entry["source"])
            self.expect_true(
                f"{entry['name']} version {entry['version']} matches the pinned filename",
                entry["version"] in row[1],
                f"filename {row[1]!r} does not contain version {entry['version']!r}",
            )

    def check_determinism_settings(self) -> None:
        src = self.read("app/build.gradle.kts")
        # Comment lines are stripped so a setting that only survives in prose
        # cannot satisfy the check.
        code = "\n".join(
            line for line in src.splitlines() if not line.strip().startswith("//")
        )
        patterns = {
            "android.buildTypes.release.vcsInfo.include": r"vcsInfo\s*\{\s*include\s*=\s*false",
            "android.dependenciesInfo.includeInApk": r"includeInApk\s*=\s*false",
            "android.dependenciesInfo.includeInBundle": r"includeInBundle\s*=\s*false",
        }
        declared = {s["key"] for s in self.m["determinism_settings"]}
        self.expect_true(
            "manifest declares every known determinism setting",
            declared == set(patterns),
            f"manifest declares {sorted(declared)}, checker knows {sorted(patterns)}",
        )
        for setting in self.m["determinism_settings"]:
            pattern = patterns.get(setting["key"])
            if pattern is None:
                self.expect_true(
                    f"determinism setting {setting['key']} is checkable",
                    False,
                    "no pattern in manifest-check.py knows how to verify this key; "
                    "add one rather than trusting the manifest",
                )
                continue
            self.expect_true(
                f"{setting['key']} is actually set in {setting['file']}",
                re.search(pattern, code, re.S) is not None,
                f"pattern {pattern!r} not found in {setting['file']}",
            )

    def check_artifact(self) -> None:
        art = self.m["artifact"]
        self.expect_true(
            "artifact path is the unsigned foss release APK",
            art["path"].endswith("app-foss-release-unsigned.apk"),
            f"unexpected artifact path {art['path']!r}",
        )
        self.expect_true(
            "--no-build-cache is part of the documented gradle args",
            "--no-build-cache" in art["gradle_args"],
            "the build cache would let a second build RESTORE R8/dex outputs "
            "instead of recomputing them, making the comparison vacuous",
        )
        wf = self.read(".github/workflows/reproducible-build.yml")
        self.expect_true(
            "the CI workflow passes --no-build-cache",
            "--no-build-cache" in wf,
            "reproducible-build.yml does not pass --no-build-cache",
        )

    def run(self) -> int:
        print(f"manifest-check: {os.path.relpath(self.manifest_path, self.repo)}")
        print(f"schema: {self.m.get('schema')}")
        print()
        self.check_tool_versions()
        self.check_ndk_in_gradle()
        self.check_gradle()
        self.check_catalog()
        self.check_sdk()
        self.check_source_date_epoch()
        self.check_submodule_pins()
        self.check_tarballs()
        self.check_determinism_settings()
        self.check_artifact()
        print()
        if self.failures:
            print(f"DRIFT: {len(self.failures)} of {self.checks} checks failed:")
            for name in self.failures:
                print(f"  - {name}")
            print()
            print(
                "Fix the SOURCE of truth, then update reproducible-builds.yml to "
                "match. Never the other way round."
            )
            return 1
        print(f"OK: {self.checks} checks, no drift.")
        return 0


# --------------------------------------------------------------------------
# self-test: prove it fails closed
# --------------------------------------------------------------------------


def self_test(repo: str) -> int:
    """Mutate one manifest value at a time; each must be caught and named."""
    manifest = os.path.join(repo, "reproducible-builds.yml")
    original = open(manifest, encoding="utf-8").read()

    failures = 0

    # Positive case first.
    rc = Checker(repo, manifest).run()
    print()
    if rc == 0:
        print("[ok]   self-test: the real manifest passes")
    else:
        print("[FAIL] self-test: the real manifest does NOT pass")
        failures += 1

    mutations = [
        ("jdk version", 'version: "17.0.20+8"', 'version: "17.0.21+9"'),
        ("ndk version", '"27.3.13750724"', '"26.1.10909125"'),
        (
            "wrapper jar sha256",
            "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d",
            "0" * 64,
        ),
        ("agp version", 'version: "9.4.1"', 'version: "9.9.9"'),
        ("kotlin version", 'version: "2.4.20"', 'version: "2.0.0"'),
        ("compile_sdk", "compile_sdk: 37", "compile_sdk: 36"),
        (
            "llama.cpp pin",
            "b29c606e28a01b1bc8c1351026a0fa6e616bf6c4",
            "a" * 40,
        ),
        (
            "sqlcipher tarball sha256",
            "79c0e164b9c059e7487bf8f29272f601cca5f3312cc267461f81e349962a5058",
            "b" * 64,
        ),
        (
            "sqlite-vec tarball sha256",
            "9823e737d9934dcbe85dff75d3fca81018a9beee803d70fa77b16faab5d61dc9",
            "c" * 64,
        ),
        ("SOURCE_DATE_EPOCH fallback", "cmake_fallback_constant: 1767225600", "cmake_fallback_constant: 0"),
    ]

    with tempfile.TemporaryDirectory() as tmp:
        mutated = os.path.join(tmp, "reproducible-builds.yml")
        for label, needle, replacement in mutations:
            if needle not in original:
                print(f"[FAIL] self-test: mutation anchor for {label} not found in the manifest")
                failures += 1
                continue
            with open(mutated, "w", encoding="utf-8") as fh:
                fh.write(original.replace(needle, replacement, 1))
            # Silence the per-check chatter; only the verdict matters here.
            buf = io_capture()
            with buf:
                rc = Checker(repo, mutated).run()
            if rc == 1:
                print(f"[ok]   self-test: a wrong {label} is caught")
            else:
                print(f"[FAIL] self-test: a wrong {label} was NOT caught")
                failures += 1

    # A determinism setting that is only *claimed* must not pass either.
    with tempfile.TemporaryDirectory() as tmp:
        fake_repo = os.path.join(tmp, "repo")
        shutil.copytree(
            repo,
            fake_repo,
            symlinks=True,
            ignore=shutil.ignore_patterns(
                ".git", "build", ".gradle", ".cxx", "third_party", ".beads"
            ),
        )
        app = os.path.join(fake_repo, "app/build.gradle.kts")
        src = open(app, encoding="utf-8").read()
        src = src.replace("include = false", "include = true", 1)
        open(app, "w", encoding="utf-8").write(src)
        buf = io_capture()
        with buf:
            rc = Checker(fake_repo, os.path.join(fake_repo, "reproducible-builds.yml")).run()
        if rc == 1:
            print("[ok]   self-test: vcsInfo silently flipped back on is caught")
        else:
            print("[FAIL] self-test: vcsInfo flipped back on was NOT caught")
            failures += 1

    print()
    if failures == 0:
        print(f"SELF-TEST PASSED ({len(mutations) + 2}/{len(mutations) + 2})")
        return 0
    print(f"SELF-TEST FAILED ({failures} check(s))")
    return 1


class io_capture:
    """Swallow stdout for a nested Checker run."""

    def __enter__(self):
        import io

        self._old = sys.stdout
        sys.stdout = io.StringIO()
        return self

    def __exit__(self, *exc):
        sys.stdout = self._old
        return False


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--manifest")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test(args.repo)
    manifest = args.manifest or os.path.join(args.repo, "reproducible-builds.yml")
    if not os.path.exists(manifest):
        print(f"manifest-check: no such file: {manifest}", file=sys.stderr)
        return 2
    return Checker(args.repo, manifest).run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
