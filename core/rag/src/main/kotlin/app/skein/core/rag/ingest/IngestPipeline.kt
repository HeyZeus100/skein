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
// ## Failure isolation and bounded retries (migration 008, skein-zx15)
//
// bd skein-7v3 wants a failing entity step to leave the document chunked
// and linked, and a persistently failing document dropped after three
// attempts via an `ingest_attempts` column. That column landed as
// `ingest_queue.attempts` in migration 008 (skein-zx15): an entity or
// vector failure is still warned (no content) and the entry still
// completes; a lexical or link failure increments the persisted counter
// via [VaultRepository.recordIngestFailure] and the entry is left queued
// and skipped for the rest of *this* run (so one poisoned document cannot
// starve the batch — [run]'s `skipped` set), and on the [MAX_ATTEMPTS]th
// consecutive failure the entry is dropped (`completeIngest`) with a
// content-free `SkeinLog.w`. Persisting the count (rather than the
// `IngestAttempts` in-memory counter skein-7v3 shipped as a stand-in)
// means the "at most 3 times" budget now survives a lock/unlock cycle or a
// process restart, not just one unlocked session.
//
// ## Revision hash and byte-offset stamping (migration 003 + 008)
//
// After loading the document, [ingest] reads
// [VaultRepository.currentRevision] — already captured synchronously by
// the repository on `createDocument`/`updateBody`/`updateFrontmatter`
// (skein-uo5n) — and passes its `revisionHash` straight through to
// [IngestSteps.indexLexical], which stamps it onto every
// `chunks.revision_hash` this pass writes and derives
// `chunks.byte_start`/`chunks.byte_end` from `Chunk.start`/`Chunk.end`
// against the same `body` string (skein-s9hm, folded into skein-zx15).
// This pipeline does not compute or upsert a revision itself — that
// remains the repository's job, and is idempotent there already (§1.4).
//
// Every warning goes through [warn], which defaults to `SkeinLog.w` and
// only ever carries step names, exception class names and counts.

package app.skein.core.rag.ingest

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.IngestItem
import app.skein.core.model.SkeinLog
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chunk.Chunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 * @param repository dequeues/completes `ingest_queue` entries, loads documents, and
 *   (migration 008) persists the per-document retry counter and current revision hash.
 * @param chunker cuts each body into the chunks whose `start`/`end` are the citation locators.
 * @param steps lexical and vector writes (`IngestSteps`).
 * @param links graph step (`EdgeUpserter.upsert` + `DanglingResolver.resolveFor` in `:app`).
 * @param entities entity step, or `null` while no span source exists (GLiNER, skein-eq1).
 * @param pace consulted before every dequeue and every document.
 * @param warn content-free diagnostics sink; `SkeinLog.w` by default.
 */
public class IngestPipeline(
    private val repository: VaultRepository,
    private val chunker: Chunker,
    private val steps: IngestSteps,
    private val links: LinkStep,
    private val entities: EntityStep? = null,
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
            return DocumentResult.Skipped
        }

        checkpoint()
        val chunks = chunker.chunk(body)

        // The repository already captured (or reused, if unchanged —
        // §1.4 idempotency) a revision for this exact content on the write
        // that queued this entry (skein-uo5n); the pipeline only reads it.
        // See the file header's "Revision hash and byte-offset stamping".
        checkpoint()
        val revisionHash = repository.currentRevision(document.id)?.revisionHash

        checkpoint()
        val chunkIds =
            try {
                steps.indexLexical(document.id, chunks, revisionHash, body)
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
        return DocumentResult.Indexed(chunkCount = chunkIds.size, vectorsPending = vectorsPending)
    }

    /**
     * A lexical or link failure: increment the persisted `ingest_queue.attempts`
     * counter ([VaultRepository.recordIngestFailure], migration 008) and
     * either leave the entry queued ([DocumentResult.Failed]) or — on the
     * [MAX_ATTEMPTS]th consecutive failure — drop it ([DocumentResult.Dropped]).
     * Both warnings carry the step name, the exception class and counts only.
     * Dropping deletes the row (`completeIngest`), which is itself what
     * forgets the count — no separate reset call is needed.
     */
    private suspend fun mandatoryStepFailed(
        item: IngestItem,
        step: String,
        cause: Throwable,
    ): DocumentResult {
        val failures = repository.recordIngestFailure(item.docId)
        val what = "$step step failed with ${cause.javaClass.simpleName}"
        if (failures < MAX_ATTEMPTS) {
            warn("ingest: $what (attempt $failures of $MAX_ATTEMPTS); entry left queued")
            return DocumentResult.Failed
        }
        warn("ingest: $what on attempt $failures of $MAX_ATTEMPTS; dropping the entry")
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

        /** Consecutive mandatory-step failures after which an entry is dropped (bd skein-7v3: "at most 3 times"). */
        public const val MAX_ATTEMPTS: Int = 3

        /** `SkeinLog` tag for the default [warn] sink. */
        public const val TAG: String = "IngestPipeline"
    }
}
