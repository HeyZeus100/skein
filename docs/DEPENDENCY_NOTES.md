# Dependency Notes

Tracking and decision record for key dependencies that require active management or observation.

## Table of Contents

- [ONNX Runtime pin (1.27.x)](#onnx-runtime-pin-127x)

## ONNX Runtime pin (1.27.x)

### Why we're pinned

ONNX Runtime 1.29.0+ declares `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` in its merged manifest. Both permissions violate our non-negotiable principle: **no `INTERNET` permission in the manifest, verifiable via GrapheneOS's per-app network toggle.**

Our `checkManifestGuardsDevDebug` guard (enforced in the build) correctly rejected the version bump attempt in PR #6 (closed 2026-09-20).

### What we're waiting for

1. An ONNX Runtime release that drops `INTERNET` and `ACCESS_NETWORK_STATE` permissions (unlikely unless they split network features into a separate artifact).
2. Alternatively: availability of a network-free ONNX artifact or a separate module without those permissions.

### How to re-evaluate on future minors

1. Watch [ONNX Runtime GitHub releases](https://github.com/microsoft/onnxruntime/releases) for new minor versions.
2. When a new minor is available, temporarily bump the version in `gradle/libs.versions.toml`.
3. Run `./gradlew :app:assembleFossDebug` to trigger a full build.
4. Inspect the merged manifest:
   ```bash
   aapt2 dump badging build/outputs/apk/foss/debug/app-foss-debug.apk | grep permission
   ```
5. If `INTERNET` or `ACCESS_NETWORK_STATE` are absent, promote the version bump; update this document and close the tracking issue.
6. If present, revert the bump, update the "Last checked" date below, and continue waiting.

### Fallback options if we ever need to upgrade despite INTERNET permission

In order of preference:

1. **Llama.cpp GGUF embedding path** (per `skein-vfl`'s `OnnxSession` / embedder-service design): Switch the embedding runtime from ONNX to llama.cpp using GGUF quantized models. This is a clean architectural boundary and maintains on-device, network-free inference.
2. **Vendor + strip**: Clone the ONNX Runtime AAR, strip the `<uses-permission>` entries from its `AndroidManifest.xml`, and include the modified artifact in our repository.
3. **Network-sandboxed service** (least preferred): Upgrade to ONNX 1.29+ and accept the `INTERNET` permission, relying on GrapheneOS's per-app network toggle to block actual network access. This is not aligned with our design principle that permissions should not be present in the manifest.

### Enforcement mechanism

The build guard `checkManifestGuardsDevDebug` validates that the merged manifest of dev and foss builds contains no `INTERNET` permission. This is an automated, non-bypassable check.

### Last checked

**2026-09-20**: ONNX Runtime 1.29.0 declares both `INTERNET` and `ACCESS_NETWORK_STATE`. PR #6 closed, pin remains at 1.27.x.
