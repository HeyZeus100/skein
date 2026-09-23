// H2 seam (docs/design/SKEIN_HUB.md §3.3), lands with skein-cyq per the H0
// amendment so `ModelManager` has something to consume. Follows the
// `TokenCounter` precedent exactly (`core/inference/.../TokenCounter.kt`):
// a `public fun interface` with a single `public suspend fun`, no other
// members, declared here rather than widened onto the locked
// `InferenceEngine` contract (`E0.I10`) — the same reasoning `TokenCounter`
// itself documents. Lives in `.models` (not the root `app.skein.core.inference`
// package `TokenCounter` uses) because its parameter is this package's own
// `ManifestBinding`, not a type the root package has any reason to know
// about.
//
// `LlamaCppEngine.inspect` (skein-ktvz / folded into skein-1uw, not this
// bead's scope) is expected to implement this over
// `IInferenceService.inspect` — converting this package's `ManifestBinding`
// to the wire `app.skein.ipc.ManifestBinding` via `WireBindings.toWire`,
// calling the AIDL method, and returning `app.skein.ipc.ModelInspection`
// unchanged (same type on both sides of the AIDL boundary, per
// `core/ipc/.../Parcels.kt`'s own header). `ModelManager` never opens a
// GGUF itself; this seam is its only way to learn anything about one.

package app.skein.core.inference.models

import app.skein.ipc.ModelInspection

/**
 * Inspects a bound model — verified exactly as `load` verifies it, then
 * loaded WITHOUT a `llama_context` inside the isolated `:inference`
 * process, metadata read, freed (`docs/design/SKEIN_HUB.md` §3.3).
 *
 * `:core:inference` (and so `:app`) never parses a GGUF itself: this is
 * the seam through which `ModelManager` learns architecture,
 * quantisation, vision/chat-template presence and the rest, without ever
 * opening the file for anything but copying and hashing opaque bytes.
 */
public fun interface ModelInspector {
    public suspend fun inspect(binding: ManifestBinding): ModelInspection
}
