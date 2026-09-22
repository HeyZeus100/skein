// skein-4c7 (E4.I7) — 16K context cap and history-truncation budget.
//
// Design spec §6 / plan §4.3: `ContextBudget(engine, config)` computes the
// `TokenBudget` (locked contract type, `core/model/.../Retrieval.kt`)
// `PromptAssembler` (`E5.I15`, skein-82g) assembles within:
//
//   remainder          = contextLengthCap - reserveForAnswer - systemTokens - safetyMargin
//   maxRetrievedTokens = min(maxRetrievedTokensCap, remainder * retrievedFraction)
//   (the rest is left for history, computed downstream by the assembler the
//   same way `FakePromptAssembler` does: `contextLength - reserveForAnswer`
//   minus the system and retrieved-block costs it counts itself.)
//
// Coordinator decision, 2026-09-21 (skein-4c7): the documented formula and
// `safetyMargin = 128` are authoritative; with contextLength=16384,
// reserveForAnswer=1024, systemTokens=200 this pins maxRetrievedTokens=3072
// and a derived history budget of 11960 (see `ContextBudgetTest`).
//
// Token counts are expensive (round-trip through the isolated inference
// process once `skein-1uw` lands `TokenCounter` over
// `IInferenceService.tokenCount`), and `PromptAssembler` re-counts the same
// history text every turn to recompute `estimatedTokens` — so counts are
// memoized here in a small LRU cache keyed by a SHA-256 digest of the text,
// never by the text itself: this class must never log content (`SkeinLog`'s
// no-raw-content rule), and hashing means the cache's internal state can't
// leak it either.

package app.skein.core.inference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.aherrera.skein.core.model.TokenBudget
import java.security.MessageDigest

/**
 * Computes the [TokenBudget] a `PromptAssembler` assembles within, and
 * memoizes [TokenCounter] results by content hash so re-counting unchanged
 * history text on every turn is cheap.
 *
 * One instance is meant to live for the lifetime of a conversation (its
 * cache is what makes repeated turns cheap); a fresh instance starts cold.
 */
public class ContextBudget(
    private val tokenCounter: TokenCounter,
    private val config: InferenceConfig = InferenceConfig(),
) {
    private val mutex = Mutex()
    private val cache =
        object : LinkedHashMap<String, Int>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
                size > config.tokenCountCacheSize
        }
    private var hits = 0
    private var misses = 0

    /** Cumulative cache hits since construction. */
    public val cacheHits: Int get() = hits

    /** Cumulative cache misses (calls that reached [tokenCounter]) since construction. */
    public val cacheMisses: Int get() = misses

    /**
     * [text]'s token count, memoized by a content-hash key. Never logs
     * [text] and never stores it (only its digest and the resulting count).
     */
    public suspend fun countTokens(text: String): Int =
        mutex.withLock {
            val key = digestOf(text)
            val cached = cache[key]
            if (cached != null) {
                hits++
                return@withLock cached
            }
            val counted = tokenCounter.count(text)
            cache[key] = counted
            misses++
            counted
        }

    /**
     * [reserveForAnswer] is `SamplingParams.maxTokens`. [systemPrompt] is the
     * persona's system prompt (or `""`), counted via [countTokens] so it
     * benefits from and contributes to the same cache as history turns.
     */
    public suspend fun computeBudget(
        reserveForAnswer: Int,
        systemPrompt: String,
    ): TokenBudget {
        val systemTokens = countTokens(systemPrompt)
        val remainder = config.contextLengthCap - reserveForAnswer - systemTokens - config.safetyMargin
        val retrievedShare = (remainder * config.retrievedFraction).toInt()
        val maxRetrievedTokens = minOf(config.maxRetrievedTokensCap, retrievedShare).coerceAtLeast(0)
        return TokenBudget(
            contextLength = config.contextLengthCap - config.safetyMargin,
            reserveForAnswer = reserveForAnswer,
            maxRetrievedTokens = maxRetrievedTokens,
        )
    }

    private fun digestOf(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            hex.append(HEX_CHARS[(byte.toInt() shr 4) and 0xF])
            hex.append(HEX_CHARS[byte.toInt() and 0xF])
        }
        return hex.toString()
    }

    private companion object {
        private const val HEX_CHARS = "0123456789abcdef"
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
