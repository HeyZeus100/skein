package app.skein.feature.chat

import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceException
import app.skein.core.model.NewDocument
import app.skein.core.model.TokenBudget
import app.skein.feature.chat.entries.TEXT_MODEL
import app.skein.testing.FakeRetrievalService
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.scriptedEngine
import kotlinx.coroutines.runBlocking

internal const val ERROR_FIXTURE_QUERY = "Summarize these project notes."
internal const val ERROR_FIXTURE_REPLY = "Recovered answer."
internal const val ERROR_FIXTURE_NOW = 1_790_000_000_000L

internal class InferenceErrorFixture(
    var failure: InferenceException?,
) {
    val repository = InMemoryVaultRepository(clock = { ERROR_FIXTURE_NOW })
    val chat =
        runBlocking {
            repository
                .createDocument(
                    NewDocument(
                        DocumentKind.CHAT,
                        "Project summary",
                        null,
                        id = "01926f3a-7c00-7000-8000-000000000001",
                    ),
                ).also { ChatKnowledge.setEnabled(repository, it.id, false) }
        }
    var preparationAttempts = 0
        private set
    private val engine =
        scriptedEngine(
            ERROR_FIXTURE_QUERY to listOf(ERROR_FIXTURE_REPLY),
            "Shorter question" to listOf(ERROR_FIXTURE_REPLY),
        ).also { runBlocking { it.load(TEXT_MODEL).getOrThrow() } }
    val pipeline =
        SendPipeline(
            vaultRepository = repository,
            retrievalService = FakeRetrievalService(emptyList()),
            promptAssembler = SimplePromptAssembler(),
            engine = engine,
            personaProvider = { null },
            budgetFor = { _, _ -> TokenBudget(16_384, 1024, 3072) },
            countTokens = { it.length / 4 },
            warmUp = {
                preparationAttempts++
                failure?.let { throw it }
            },
        )
}
