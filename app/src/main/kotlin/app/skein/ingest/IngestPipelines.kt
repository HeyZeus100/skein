// E5.I10 (skein-7v3) — composes an `IngestPipeline` over an open
// `VaultSession`: the real `Chunker` (over `ApproximateTokenizer` until the
// embedder's own `tokenizer.json` is available — skein-079, follow-up
// skein-hwsa), `IngestSteps` (lexical always; vectors only when an
// `EmbedderService` is supplied) and the graph steps from `:core:vault`
// (`EdgeUpserter` + `DanglingResolver`, E5.I8/E5.I8b). The entity step stays
// `null` until a span source exists (`EntityIndexer` is landed, GLiNER —
// skein-eq1 — is not). Diagnostics go through `SkeinLog` (the pipeline's and
// the steps' default sink), never `android.util.Log`.
//
// skein-zx15: `IngestPipeline` no longer takes an `IngestAttempts` — its
// bounded-retry counter is `ingest_queue.attempts` (migration 008), read
// and written through `session.repository` itself, so there is nothing left
// for this factory to thread through.

package app.skein.ingest

import app.skein.core.model.EmbedderService
import app.skein.core.rag.chunk.Chunker
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.rag.ingest.IngestPipeline
import app.skein.core.rag.ingest.IngestSteps
import app.skein.core.rag.ingest.LinkStep
import app.skein.core.rag.tokenizers.ApproximateTokenizer
import app.skein.core.vault.extract.DanglingResolver
import app.skein.core.vault.extract.EdgeUpserter
import app.skein.vault.VaultSession

object IngestPipelines {
    /**
     * A pipeline for [session]. [embedder] is `null` until skein-079 lands
     * (vectors are then recorded as pending — `IngestSteps` file header).
     */
    fun forSession(
        session: VaultSession,
        pace: () -> IngestPace,
        embedder: EmbedderService? = null,
    ): IngestPipeline {
        val upserter = EdgeUpserter(session.repository, session.indexStore)
        val resolver = DanglingResolver(session.repository, session.indexStore)
        return IngestPipeline(
            repository = session.repository,
            chunker = Chunker(ApproximateTokenizer),
            steps = IngestSteps(session.indexStore, embedder),
            links =
                LinkStep { document ->
                    upserter.upsert(document)
                    resolver.resolveFor(document)
                },
            entities = null,
            pace = pace,
        )
    }
}
