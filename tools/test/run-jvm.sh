#!/usr/bin/env bash
#
# E10.I1 (plan): the fast, no-device test lane an agent should run after
# almost any change. Runs every module's plain JVM unit tests
# (`:testing:test`, `:core:model:test`, ...) plus `:app`'s and
# `:feature:shell`'s Robolectric-backed `testDevDebugUnitTest`, ktlint, and
# the isolation/manifest/dependency guard tasks — everything `ci.yml`'s
# "Unit tests, lint, assemble" job runs except the Android Lint pass and the
# APK assemble, which are slower and not test-tier work. Does NOT touch an
# emulator; see `run-emulator.sh` for the instrumented lane.
#
# Usage:
#   tools/test/run-jvm.sh
#
# Requires JAVA_HOME set to a JDK 17 (see docs/TESTING.md / bd memory
# `local-toolchain-gotcha-usr-bin-java-is-a`); does not set it for you since
# the correct path is machine-specific.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

./gradlew ktlintCheck test testDevDebugUnitTest --stacktrace
