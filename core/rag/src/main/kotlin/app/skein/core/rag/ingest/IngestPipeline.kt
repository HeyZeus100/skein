// `IngestPipeline` (bd skein-7v3, E5.I10; spec §7.1). Drains `ingest_queue`
// one entry at a time: load the document → `Chunker` → lexical rows (FTS5
// via triggers, `IngestSteps.indexLexical`) → graph edges ([LinkStep]:
// wikilink/tag/cite + dangling-link resolution) → entity edges
// ([EntityStep], only when a span source exists — GLiNER is skein-eq1) →
// vectors (`IngestSteps.indexVectors`, only when an embedder is loaded,
// else recorded as "pending") → `VaultRepository.completeIngest`.
//
// The pipeline is pure Kotlin over the `:core:model` contracts plus this
// module's own `Chunker`/`IngestSteps`. The two graph steps are small
// interfaces rather than direct dependencies so this module never sees
// `:core:vault` (`EdgeUpserter`, `DanglingResolver`, `EntityIndexer` live
// there); `:app` composes them, and `IngestPipelineTest` wires the real
// ones through a test-only dependency.
//
// ## Cancellation checkpoints (`LOCK_POLICY_INDEXING.md` §4.2)
//
// A lock cancels the `IngestWorker`'s coroutine. Every step boundary calls
// `ensureActive()` ([checkpoint]) and every suspend call into the index is
// itself a cancellation point, so the pipeline unwinds within one step of
// the cancel. The per-step `try/catch` blocks rethrow `CancellationException`
// before anything else — the failure-isolation catch must never swallow a
// lock-triggered cancel. `completeIngest` is the *last* call for an entry:
// a cancel anywhere before it leaves the queue row in place, and the next
// run redoes the (idempotent) steps for that document.
//
// ## Pacing (`ThermalGovernor`, E4.I9; battery per the amended plan)
//
// [pace] is consulted before every dequeue and before every document:
// `FULL` dequeues [FULL_BATCH] entries, `REDUCED` (thermal `Reduced`, or
// unplugged/low battery) [REDUCED_BATCH], `PAUSED` (thermal `Paused`) stops
// the run with [IngestOutcome.Paused] so the worker can `retry()`, and
// `LOCKED` (the session is no longer the one this run was authorised for)
// stops it with [IngestOutcome.Locked]. The thermal/lock mapping lives in
// `:app`; this class only understands the four paces.
//
// ## Failure isolation and bounded retries
//
// bd skein-7v3 wants a failing entity step to leave the document chunked
// and linked, and a persistently failing document dropped after three
// attempts via an `ingest_attempts` column — that column is migration 003,
// which has NOT landed, so no schema is touched here. What this class does
// today: an entity or vector failure is warned (no content) and the entry
// still completes; a lexical or link failure is counted in the injected
// [IngestAttempts] (in-memory, per session — see that file's header), the
// entry is left queued and skipped for the rest of *this* run (so one
// poisoned document cannot starve the batch), and on the
// [IngestAttempts.maxAttempts]th consecutive failure the entry is dropped
// (`completeIngest`) with a content-free `SkeinLog.w`. Follow-up bead
// skein-zx15 moves the counter into the column once 003 lands.
//
// Every warning goes through [warn], which defaults to `SkeinLog.w` and
// only ever carries step names, exception class names and counts.

package app.skein.core.rag.ingest

import app.skein.core.model.SkeinLog
import app.skein.core.rag.chunk.Chunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import us.aherrera.skein.core.model.DocId
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.IngestItem
import us.aherrera.skein.core.model.VaultRepository

/** What the caller asks the pipeline to do next — see the file header. */
public enum class IngestPace {
    /** Dequeue [IngestPipeline.FULL_BATCH] entries at a time. */
    FULL,

    /** Dequeue [IngestPipeline.REDUCED_BATCH] entries at a time (thermal `Reduced`, or unplugged). */
    REDUCED,

    /** Stop now and let the worker reschedule (thermal `Paused`). */
    PAUSED,

    /** Stop now and do not reschedule: the vault session this run was authorised for is gone. */
    LOCKED,
}

/** Why an [IngestPipeline.run] returned, with content-free counts for progress/notification surfaces. */
public sealed interface IngestOutcome {
    /** Entries fully indexed (lexical + links, plus vectors where an embedder exists) and completed. */
    public val processed: Int

    /** Of [processed], entries whose vectors were not written (no embedder, or the vector step failed). */
    public val vectorsPending: Int

    /** `ingest_queue` had nothing left (apart from entries skipped for failing in this run). */
    public data class Drained(
        override val processed: Int,
        override val vectorsPending: Int,
    ) : IngestOutcome

    /** Stopped on [IngestPace.PAUSED]; entries remain queued. */
    public data class Paused(
        override val processed: Int,
        override val vectorsPending: Int,
    ) : IngestOutcome

    /** Stopped on [IngestPace.LOCKED]; entries remain queued for the next unlocked session. */
    public data class Locked(
        override val processed: Int,
        override val vectorsPending: Int,
    ) : IngestOutcome
}

/** Result of [IngestPipeline.ingest] for one queue entry. */
public sealed interface DocumentResult {
    /** All mandatory steps succeeded and the entry was completed. */
    public data class Indexed(
        val chunkCount: Int,
        val vectorsPending: Boolean,
    ) : DocumentResult

    /** The document no longer exists or has no body (an attachment); the entry was completed. */
    public data object Skipped : DocumentResult

    /** A mandatory step (lexical or links) failed; the entry was left queued for a later run. */
    public data object Failed : DocumentResult

    /** A mandatory step failed for the [IngestAttempts.maxAttempts]th time; the entry was dropped. */
    public data object Dropped : DocumentResult
}

/** Spec §7.1 steps 5-6 for one document: wikilink/tag/cite edges plus dangling-link resolution. */
public fun interface LinkStep {
    public suspend fun link(document: Document)
}

/** Spec §7.1 step 4 for one document: entity spans → `ENTITY` edges. Absent until a span source exists. */
public fun interface EntityStep {
    public suspend fun index(document: Document)
}

/**
 * See the file header. Construct one per open vault session; [run] may be
 * called repeatedly (each call drains what is queued at that moment).
 *
 * @param repository dequeues/completes `ingest_queue` entries and loads documents.
 * @param chunker cuts each body into the chunks whose `start`/`end` are the citation locators.
 * @param steps lexical and vector writes (`IngestSteps`).
 * @param links graph step (`EdgeUpserter.upsert` + `DanglingResolver.resolveFor` in `:app`).
 * @param entities entity step, or `null` while no span source exists (GLiNER, skein-eq1).
 * @param attempts per-document failure counter shared across runs of one session (file header).
 * @param pace consulted before every dequeue and every document.
 * @param warn content-free diagnostics sink; `SkeinLog.w` by default.
 */
public class IngestPipeline(
    private val repository: VaultRepository,
    private val chunker: Chunker,
    private val steps: IngestSteps,
    private val links: LinkStep,
    private val entities: EntityStep? = null,
    private val attempts: IngestAttempts = IngestAttempts(),
    private val pace: () -> IngestPace = { IngestPace.FULL },
    private val warn: (String) -> Unit = { SkeinLog.w(TAG, it) },
) {
    /**
     * Drains `ingest_queue` under [pace] until it is empty, paused, or the
     * session is gone. [onProgress] is invoked after every completed entry
     * with the running `processed` count.
     */
    public suspend fun run(onProgress: (Int) -> Unit = {}): IngestOutcome {
        var processed = 0
        var vectorsPending = 0
        val skipped = HashSet<DocId>()

        while (true) {
            checkpoint()
            val batchSize =
                when (pace()) {
                    IngestPace.FULL -> FULL_BATCH
                    IngestPace.REDUCED -> REDUCED_BATCH
                    IngestPace.PAUSED -> return IngestOutcome.Paused(processed, vectorsPending)
                    IngestPace.LOCKED -> return IngestOutcome.Locked(processed, vectorsPending)
                }
            // Over-fetch by the number of entries already skipped so a
            // poisoned document at the head of the queue never hides the
            // healthy ones behind it.
            val batch =
                repository
                    .dequeueIngest(batchSize + skipped.size)
                    .filter { it.docId !in skipped }
                    .take(batchSize)
            if (batch.isEmpty()) return IngestOutcome.Drained(processed, vectorsPending)

            for (item in batch) {
                checkpoint()
                when (pace()) {
                    IngestPace.PAUSED -> return IngestOutcome.Paused(processed, vectorsPending)
                    IngestPace.LOCKED -> return IngestOutcome.Locked(processed, vectorsPending)
                    IngestPace.FULL, IngestPace.REDUCED -> Unit
                }
                when (val result = ingest(item)) {
                    is DocumentResult.Indexed -> {
                        processed++
                        if (result.vectorsPending) vectorsPending++
                        onProgress(processed)
                    }
                    DocumentResult.Skipped, DocumentResult.Dropped -> Unit
                    DocumentResult.Failed -> skipped += item.docId
                }
            }
        }
    }

    /**
     * Runs every step for one queue entry (file header order) and completes
     * it. Public so a caller can index a single entry — e.g. a test, or a
     * future "index this note now" action.
     */
    public suspend fun ingest(item: IngestItem): DocumentResult {
        val document = repository.getDocument(item.docId)
        val body = document?.bodyMd
        if (document == null || body == null) {
            // Deleted since it was queued (the row cascades anyway) or an
            // attachment (never queued by the triggers; indexed via its
            // derived note). Nothing to index; clear the entry.
            repository.completeIngest(item.docId, item.queuedAt)
            attempts.clear(item.docId)
            return DocumentResult.Skipped
        }

        checkpoint()
        val chunks = chunker.chunk(body)

        checkpoint()
        val chunkIds =
            try {
                steps.indexLexical(document.id, chunks)
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                return mandatoryStepFailed(item, "lexical", t)
            }

        checkpoint()
        try {
            links.link(document)
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            return mandatoryStepFailed(item, "link", t)
        }

        checkpoint()
        val entityStep = entities
        if (entityStep != null) {
            try {
                entityStep.index(document)
            } catch (t: Throwable) {
                rethrowIfCancelled(t)
                warn("ingest: entity step failed with ${t.javaClass.simpleName}; document stays chunked and linked")
            }
        }

        checkpoint()
        val vectorsPending =
            if (!steps.canIndexVectors) {
                true
            } else {
                try {
                    steps.indexVectors(chunkIds, chunks.map { it.embeddingText })
                    false
                } catch (t: Throwable) {
                    rethrowIfCancelled(t)
                    warn("ingest: vector step failed with ${t.javaClass.simpleName}; vectors left pending")
                    true
                }
            }

        checkpoint()
        // Last, so a cancel anywhere above leaves the entry queued; a no-op
        // when the document changed mid-run (`queued_at` advanced) — the
        // bumped entry is then re-ingested by the next dequeue.
        repository.completeIngest(document.id, item.queuedAt)
        attempts.clear(document.id)
        return DocumentResult.Indexed(chunkCount = chunkIds.size, vectorsPending = vectorsPending)
    }

    /**
     * A lexical or link failure: count it, and either leave the entry queued
     * ([DocumentResult.Failed]) or — on the [IngestAttempts.maxAttempts]th
     * consecutive failure — drop it ([DocumentResult.Dropped]). Both
     * warnings carry the step name, the exception class and counts only.
     */
    private suspend fun mandatoryStepFailed(
        item: IngestItem,
        step: String,
        cause: Throwable,
    ): DocumentResult {
        val failures = attempts.recordFailure(item.docId)
        val max = attempts.maxAttempts
        val what = "$step step failed with ${cause.javaClass.simpleName}"
        if (failures < max) {
            warn("ingest: $what (attempt $failures of $max); entry left queued")
            return DocumentResult.Failed
        }
        warn("ingest: $what on attempt $failures of $max; dropping the entry")
        attempts.clear(item.docId)
        repository.completeIngest(item.docId, item.queuedAt)
        return DocumentResult.Dropped
    }

    private suspend fun checkpoint() = currentCoroutineContext().ensureActive()

    private fun rethrowIfCancelled(t: Throwable) {
        if (t is CancellationException) throw t
    }

    public companion object {
        /** Entries dequeued per pass under [IngestPace.FULL] (bd skein-7v3: `Reduced` halves it to 16). */
        public const val FULL_BATCH: Int = 32

        /** Entries dequeued per pass under [IngestPace.REDUCED] (bd skein-7v3 acceptance: batch size 16). */
        public const val REDUCED_BATCH: Int = 16

        /** `SkeinLog` tag for the default [warn] sink. */
        public const val TAG: String = "IngestPipeline"
    }
}
