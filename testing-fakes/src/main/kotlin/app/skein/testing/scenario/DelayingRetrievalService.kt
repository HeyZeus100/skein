package app.skein.testing.scenario

import app.skein.core.model.PersonaId
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved

/**
 * Gives [delegate] (normally a `FakeRetrievalService`) a duration: waits [ms]
 * under [pacing] (a [Checkpoint.Retrieve] when gated), then returns the
 * delegate's results. Reports [ScenarioActivity.RetrievalStarted] and
 * [ScenarioActivity.RetrievalDone] (passage and distinct-document counts of
 * what it returned) to [onActivity]. Adds no retrieval behaviour of its own.
 */
public class DelayingRetrievalService(
    private val delegate: RetrievalService,
    private val ms: Long,
    private val pacing: Pacing,
    private val onActivity: (ScenarioActivity) -> Unit = {},
) : RetrievalService {
    override suspend fun retrieveContext(
        query: String,
        k: Int,
        personaId: PersonaId?,
    ): List<Retrieved> {
        onActivity(ScenarioActivity.RetrievalStarted)
        pacing.wait(ms, Checkpoint.Retrieve)
        return delegate.retrieveContext(query, k, personaId).also { results ->
            onActivity(ScenarioActivity.RetrievalDone(results.size, results.distinctBy { it.docId }.size))
        }
    }
}
