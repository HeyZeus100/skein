// `IngestAttempts` (bd skein-7v3, E5.I10). The in-memory stand-in for the
// `ingest_attempts` column that bd skein-7v3 / `POST_REVIEW_RESOLUTIONS.md`
// §1.3 place in migration 003 — which has NOT landed, and this bead adds no
// schema. Until it does, the bounded-retry rule ("re-queue a failing
// document at most three times, then drop it with a content-free
// `SkeinLog.w`") is enforced from this counter instead: one instance lives
// in `:app`'s `IngestScheduler` for the duration of an unlocked session and
// is reset on lock (LOCK_POLICY_INDEXING.md §4.5 — `IngestScheduler`'s
// in-memory state is reset to idle), so a poisoned document gets at most
// [maxAttempts] tries per session rather than forever. Follow-up bead
// skein-zx15 switches the pipeline to the persisted column once 003 lands.

package app.skein.core.rag.ingest

import us.aherrera.skein.core.model.DocId

/**
 * Per-document failure counter for [IngestPipeline]. Thread-safe; holds
 * only document ids and small integers — never content.
 *
 * @param maxAttempts failures after which [IngestPipeline] drops the queue entry (bd skein-7v3: 3).
 */
public class IngestAttempts(
    public val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
    }

    private val counts = HashMap<DocId, Int>()

    /** Records one more failure for [docId] and returns its new consecutive-failure count. */
    @Synchronized
    public fun recordFailure(docId: DocId): Int {
        val next = (counts[docId] ?: 0) + 1
        counts[docId] = next
        return next
    }

    /** Consecutive failures recorded for [docId] since its last [clear]. */
    @Synchronized
    public fun failures(docId: DocId): Int = counts[docId] ?: 0

    /** Forgets [docId] — called after a successful ingest or a drop. */
    @Synchronized
    public fun clear(docId: DocId) {
        counts.remove(docId)
    }

    /** Forgets every document — the lock-time reset. */
    @Synchronized
    public fun reset() {
        counts.clear()
    }

    public companion object {
        /** bd skein-7v3: "at most 3 times". */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3
    }
}
