// skein-4c7 (E4.I7) — coordinator decision, 2026-09-21: `contextLengthCap`
// defaults to 16 384 per design spec §6 line 239 ("16K tokens in v1, verify
// in M0"), with a KDoc pointer to `docs/MEASUREMENTS.md`'s
// `context_length_cap` (skein-5hr) as the future override. No hardware
// number is invented here.

package app.skein.core.inference

/**
 * Tuning knobs for [ContextBudget] (design spec §6, plan §4.3, `E4.I7`).
 *
 * @param contextLengthCap the effective context window in tokens. Defaults
 *   to 16 384 (design spec §6: "16K tokens in v1, verify in M0"). Once
 *   `docs/MEASUREMENTS.md`'s `context_length_cap` (`skein-5hr`) is written,
 *   callers should source this value from there instead of relying on this
 *   default.
 * @param safetyMargin tokens withheld as headroom against token-count
 *   estimation error; folded into the `TokenBudget.contextLength`
 *   [ContextBudget] returns.
 * @param maxRetrievedTokensCap hard ceiling on the retrieved-context block,
 *   regardless of how large [retrievedFraction] of the remainder would be.
 * @param retrievedFraction the fraction of the post-reserve/system/margin
 *   remainder offered to retrieved context, before [maxRetrievedTokensCap]
 *   is applied.
 * @param tokenCountCacheSize bound on the content-hash token-count LRU
 *   cache [ContextBudget] keeps over its [TokenCounter].
 * @param threads the `LoadRequest.threads` `app.skein.core.inference.engine.LlamaCppEngine`
 *   sends (`skein-1uw`, plan `E4.I4`: "`threads`, `gpuLayers` via
 *   `InferenceConfig`"). `docs/MEASUREMENTS.md`'s `threads` row (`skein-5hr`)
 *   is the intended source and **does not exist yet**, so no number is
 *   invented here: 4 is the value the M0 benchmark actually ran with
 *   (`tools/m0-benchmark/run.sh`, `threads_for_backend() { echo "4"; }`), and
 *   it is the only thread count this repository has ever measured anything at.
 *   `skein-brwf` replaces it from PP-59 and additionally splits generation
 *   threads from prompt-batch threads (OfflineLLM OL-29) — which needs a new
 *   `LoadRequest` field the contract owner has to add, since the landed
 *   `app.skein.ipc.LoadRequest` carries one `threads` only.
 * @param gpuLayers the `LoadRequest.gpuLayers` the engine sends. **0, and a
 *   deliberate 0.** `:inference` is an `isolatedProcess`, which may not be
 *   granted GPU access at all, so an engine that assumed offload would be
 *   assuming a permission it cannot check; and OfflineLLM escalation E-2
 *   (epic `skein-gg11` plan revision 1) found that llama.cpp still initialised
 *   the Vulkan backend at `n_gpu_layers = 0` until `skein-gg11.1` restricted
 *   `llama_model_params.devices` in the JNI (OL-01). Raising this is a
 *   `docs/MEASUREMENTS.md` decision (`inference_backend` / `gpu_layers`,
 *   `skein-5hr`/`skein-9cg`) taken per model, never a client default.
 */
public data class InferenceConfig(
    val contextLengthCap: Int = 16_384,
    val safetyMargin: Int = 128,
    val maxRetrievedTokensCap: Int = 3_072,
    val retrievedFraction: Double = 0.40,
    val tokenCountCacheSize: Int = 256,
    val threads: Int = DEFAULT_THREADS,
    val gpuLayers: Int = 0,
) {
    public companion object {
        /** See [InferenceConfig.threads]: the M0 benchmark's thread count, not an invented one. */
        public const val DEFAULT_THREADS: Int = 4
    }
}
