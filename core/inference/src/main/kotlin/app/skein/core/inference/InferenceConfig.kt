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
 */
public data class InferenceConfig(
    val contextLengthCap: Int = 16_384,
    val safetyMargin: Int = 128,
    val maxRetrievedTokensCap: Int = 3_072,
    val retrievedFraction: Double = 0.40,
    val tokenCountCacheSize: Int = 256,
)
