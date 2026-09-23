// `RetrievalServiceImpl` (skein-do6, E5.I13, spec §7.2 retrieve). The one
// call site that wires the landed recall stages (`LexicalRecall` E5.I6,
// `VectorRecall` E5.I7, `GraphRecall` E5.I11 — sibling
// `app.skein.core.rag.recall` package) into `PprRanker` (E5.I12, skein-dxj)
// and `RetrievedAssembler` (E5.I13 prep, skein-wqli), and implements the
// `RetrievalService` contract locked verbatim in `core/model/…/Retrieval.kt`
// (`E0.I12`, skein-x4f).
//
// ## Pipeline (plan `E5.I13` description, verbatim intent)
//
// 1. **Recall**, fanned out concurrently on [io] via `coroutineScope` +
//    `async` — all three stages are pure reads over `IndexStore`/
//    `VaultRepository`, so there is nothing to serialize.
// 2. **Union** into the `Map<CitationSourceKind, List<ScoredChunk>>`
//    `PprRanker.rank` and `RetrievedAssembler.assemble` both speak.
// 3. **Rank**: `PprRanker.rank(sources, personaId, k)`. The persona filter
//    is *not* re-implemented here — `PprRanker`'s own kdoc (step 2 of its
//    pipeline) already drops chunks whose document isn't shared or owned by
//    [PersonaId], before RRF fusion even runs, so this class only has to
//    forward [PersonaId] unchanged.
// 4. **Assemble**: `RetrievedAssembler.assemble(ranked, sources)` hydrates
//    title/kind/text via batched `getChunks`/`getDocument` calls and derives
//    `Retrieved.recalledBy`. Its output already preserves `PprRanker`'s
//    order and `k` cap; the trailing `take(k)` is defensive, not load-
//    bearing.
//
// ## Degradation (plan `E5.I13`: "Empty vault or embedder unavailable →
// degrade to lexical + graph only (logged once). Hard timeout 2 s per
// recall source (returns what is available).")
//
// - **No embedder.** [embedder] is nullable, mirroring `IngestPipelines`'s
//   `embedder: EmbedderService? = null` (E5.I10, skein-7v3) — the same
//   optionality convention used throughout ingest until skein-079 wires the
//   real embedder process up. `VectorRecall` is skipped outright (never
//   constructed) rather than called and made to fail; `ScoreFusion` already
//   treats a source key that isn't in the map as "no vectors yet" and
//   renormalizes over the surviving sources (see `ScoreFusion`'s file
//   header), so no special-casing is needed downstream. The skip is logged
//   exactly once per instance (`AtomicBoolean`), not once per call — an
//   instance built without an embedder would otherwise log identically on
//   every `retrieveContext`.
// - **Per-source timeout and failure.** Each recall stage runs under
//   `withTimeoutOrNull(recallTimeoutMillis)` inside its own `async` child,
//   and any exception the stage throws is caught *inside that same child*
//   (never at the `.await()` call site) so a slow or failing source cannot
//   cancel its siblings via `coroutineScope`'s structured-concurrency
//   propagation. Either failure mode degrades that one source to
//   `emptyList()` — "returns what is available" — and is logged once per
//   occurrence via [warn] (content-free: stage name and exception class
//   only, never the query or any chunk text — spec §9 / `SkeinLog`'s
//   `isSensitiveContent` guard).
// - **Empty vault.** Needs no code of its own: an empty `IndexStore` makes
//   every recall stage return `emptyList()` on its own terms, `PprRanker`
//   short-circuits on an empty candidate union, and `RetrievedAssembler`
//   short-circuits on an empty ranked list — the contract's "empty vault →
//   empty list, never throws" falls out of the stages' own contracts.
//
// ## Non-negotiables (skein-do6)
//
// This class does not modify `core/model`, the recall stages, `PprRanker`/
// `ScoreFusion`, or `RetrievedAssembler` — it only composes them.
package app.skein.core.rag.retrieval

import app.skein.core.model.CitationSourceKind
import app.skein.core.model.EmbedderService
import app.skein.core.model.IndexStore
import app.skein.core.model.PersonaId
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import app.skein.core.model.ScoredChunk
import app.skein.core.model.SkeinLog
import app.skein.core.model.VaultRepository
import app.skein.core.rag.rank.PprRanker
import app.skein.core.rag.rank.RankerConfig
import app.skein.core.rag.rank.RetrievedAssembler
import app.skein.core.rag.recall.GraphRecall
import app.skein.core.rag.recall.LexicalRecall
import app.skein.core.rag.recall.VectorRecall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Design spec §7.2's recall → rank → assemble pipeline over the real recall
 * stages and ranker. See the file header for the pipeline and its
 * degradation rules.
 *
 * @param index backs every recall stage and [RetrievedAssembler]'s chunk
 *   hydration.
 * @param repository backs [GraphRecall]'s title-seed lookup, [PprRanker]'s
 *   persona filter, and [RetrievedAssembler]'s document hydration.
 * @param embedder drives [VectorRecall]; `null` skips vector recall entirely
 *   (see file header).
 * @param config every `PprRanker`/`ScoreFusion` tunable — see
 *   [RankerConfig], tuned in `E10.I5`.
 * @param recallTimeoutMillis per-source hard timeout (plan `E5.I13`: 2 s).
 * @param io dispatcher the three recall stages fan out onto — `Dispatchers.IO`
 *   by default, matching `VaultRepositoryImpl`/`PersonaServiceImpl`'s own
 *   convention for this codebase's I/O-bound suspend functions.
 * @param warn content-free diagnostics sink; `SkeinLog.w` by default,
 *   overridable so a test can observe a degradation without a `SkeinLog`
 *   test hook.
 */
public class RetrievalServiceImpl(
    private val index: IndexStore,
    private val repository: VaultRepository,
    private val embedder: EmbedderService?,
    private val config: RankerConfig = RankerConfig.DEFAULT,
    private val recallTimeoutMillis: Long = DEFAULT_RECALL_TIMEOUT_MILLIS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = { SkeinLog.w(TAG, it) },
) : RetrievalService {
    private val lexicalRecall = LexicalRecall(index)
    private val graphRecall = GraphRecall(index, repository)
    private val ranker = PprRanker(index, repository, config)
    private val assembler = RetrievedAssembler(index, repository)

    /** Guards the "no embedder" degradation warning so it fires once per instance, not once per call. */
    private val loggedNoEmbedder = AtomicBoolean(false)

    override suspend fun retrieveContext(
        query: String,
        k: Int,
        personaId: PersonaId?,
    ): List<Retrieved> {
        if (k <= 0) return emptyList()

        val sources = recallAll(query)
        val ranked = ranker.rank(sources, personaId, k)
        return assembler.assemble(ranked, sources).take(k)
    }

    /** Spec §7.2 recall step: the three sources, fanned out concurrently on [io]. See file header. */
    private suspend fun recallAll(query: String): Map<CitationSourceKind, List<ScoredChunk>> =
        coroutineScope {
            val lexicalDeferred = async(io) { recallStage(STAGE_LEXICAL) { lexicalRecall.recall(query) } }
            val graphDeferred = async(io) { recallStage(STAGE_GRAPH) { graphRecall.recall(query) } }
            val vectorDeferred =
                embedder?.let { service ->
                    async(io) { recallStage(STAGE_VECTOR) { VectorRecall(index, service).recall(query) } }
                }

            val vector = vectorDeferred?.await() ?: noEmbedderDegraded()

            // Deterministic merge: one entry per source, keyed by the fixed
            // `CitationSourceKind` enum regardless of `async` completion order.
            linkedMapOf(
                CitationSourceKind.LEXICAL to lexicalDeferred.await(),
                CitationSourceKind.VECTOR to vector,
                CitationSourceKind.GRAPH to graphDeferred.await(),
            )
        }

    private fun noEmbedderDegraded(): List<ScoredChunk> {
        if (loggedNoEmbedder.compareAndSet(false, true)) {
            warn("recall stage=$STAGE_VECTOR skipped: no embedder loaded; degrading to lexical + graph")
        }
        return emptyList()
    }

    /**
     * Runs one recall [block] under [recallTimeoutMillis], degrading a
     * timeout or a thrown exception to `emptyList()` rather than letting
     * either propagate — this is what "hard timeout 2 s per recall source
     * (returns what is available)" means at the call site. Must be invoked
     * from inside the stage's own `async` child (see file header) so a
     * degraded source cannot cancel its siblings.
     */
    private suspend fun recallStage(
        name: String,
        block: suspend () -> List<ScoredChunk>,
    ): List<ScoredChunk> =
        try {
            withTimeoutOrNull(recallTimeoutMillis) { block() }
                ?: run {
                    warn("recall stage=$name timed out after ${recallTimeoutMillis}ms; degrading to emptyList")
                    emptyList()
                }
        } catch (t: Throwable) {
            rethrowIfCancelled(t)
            warn("recall stage=$name threw ${t.javaClass.simpleName}; degrading to emptyList")
            emptyList()
        }

    private fun rethrowIfCancelled(t: Throwable) {
        if (t is CancellationException) throw t
    }

    public companion object {
        /** Plan `E5.I13`: "Hard timeout 2 s per recall source". */
        public const val DEFAULT_RECALL_TIMEOUT_MILLIS: Long = 2_000L

        /** `SkeinLog` tag for the default [warn] sink. */
        public const val TAG: String = "RetrievalServiceImpl"

        private const val STAGE_LEXICAL: String = "lexical"
        private const val STAGE_VECTOR: String = "vector"
        private const val STAGE_GRAPH: String = "graph"
    }
}
