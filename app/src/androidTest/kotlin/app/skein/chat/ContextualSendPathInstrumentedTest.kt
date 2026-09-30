package app.skein.chat

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.ContextualEvidenceMode
import app.skein.core.model.ContextualRetrievalRequest
import app.skein.core.model.ContextualRetrievalResult
import app.skein.core.model.ContextualRetrievalService
import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FollowUpResolution
import app.skein.core.model.InferenceEngine
import app.skein.core.model.NewDocument
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.Token
import app.skein.core.model.TokenBudget
import app.skein.core.rag.ingest.IngestOutcome
import app.skein.core.rag.ingest.IngestPace
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.core.rag.retrieval.RetrievalServiceImpl
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.feature.chat.ChatTurnController
import app.skein.feature.chat.ChatTurnSnapshot
import app.skein.feature.chat.ChatTurnState
import app.skein.feature.chat.SendPipeline
import app.skein.ingest.IngestPipelines
import app.skein.testing.scriptedEngine
import app.skein.vault.DeviceVaultOpener
import app.skein.vault.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ordinary encrypted-runtime integration, never a model-quality or physical acceptance test.
 * Each case owns a unique synthetic cache vault; no Activity, global provider, model or owner
 * content is touched. Real opener/migrations/repository/FTS/ingest and chat queue/send/retrieval
 * execute; only the in-memory test key and scripted inference output are synthetic ports.
 */
@RunWith(AndroidJUnit4::class)
class ContextualSendPathInstrumentedTest {
    @Test
    fun persisted_citation_resolves_raw_followup_through_encrypted_send_path() =
        runBlocking {
            withFixture { f ->
                val source = f.seed()
                val chat = f.chat()
                f.send(chat, FIRST_QUERY)
                val before = f.session.repository.listMessages(chat)
                assertEquals(listOf(Role.USER, Role.ASSISTANT), before.map { it.role })
                assertEquals(listOf(1), before.last().citations!!.cited)

                val outcome = f.send(chat, FOLLOW_UP).outcome!!
                val request = f.requests.single()
                val result = f.results.single()
                assertEquals(ContextualEvidenceMode.INDEXED, request.evidenceMode)
                assertEquals(FOLLOW_UP, request.query)
                assertEquals(FIRST_QUERY, request.followUp!!.priorUserQuery)
                val pin = request.followUp!!.citedSources.single()
                assertEquals(source.id, pin.documentId)
                assertEquals(source.contentHash, pin.revisionHash)
                assertEquals(f.persona.id, pin.personaId)
                assertEquals(
                    source.contentHash,
                    f.session.repository
                        .currentRevision(source.id)!!
                        .revisionHash,
                )
                assertEquals(FollowUpResolution.RESOLVED, result.resolution)
                assertEquals("$FOLLOW_UP\nTransit case\n$FIRST_QUERY", result.recallQuery)
                assertFalse(result.recallQuery.contains(SCRIPTED_ANSWER))
                assertEquals(result.originalCandidates, result.evidence)
                assertEquals(2, f.engine.calls.get())
                assertEquals(FOLLOW_UP, outcome.userQuery)
                assertFalse(outcome.generationSkipped)
                val after = f.session.repository.listMessages(chat)
                assertEquals(before, after.take(before.size))
                assertEquals(FOLLOW_UP, after[2].contentMd)
                assertEquals(
                    source.id,
                    after
                        .last()
                        .citations!!
                        .retrieved
                        .single()
                        .documentId,
                )
                assertEquals(
                    source.contentHash,
                    after
                        .last()
                        .citations!!
                        .retrieved
                        .single()
                        .revisionHash,
                )
            }
        }

    @Test
    fun edited_and_reingested_source_cannot_repin_a_previous_encrypted_turn() =
        runBlocking {
            withFixture { f ->
                val source = f.seed()
                val chat = f.chat()
                f.send(chat, FIRST_QUERY)
                val before = f.session.repository.listMessages(chat)
                val changed =
                    f.session.repository.replaceBody(
                        source.id,
                        "The transit case storage location is berth nine. The case color is orange.",
                    )
                assertFalse(source.contentHash == changed.contentHash)
                f.ingest()
                assertEquals(
                    changed.contentHash,
                    f.session.repository
                        .currentRevision(source.id)!!
                        .revisionHash,
                )

                val result = f.send(chat, FOLLOW_UP)
                assertEquals(FollowUpResolution.SOURCE_CHANGED, f.results.single().resolution)
                f.assertAbstained(result)
                assertEquals(
                    before,
                    f.session.repository
                        .listMessages(chat)
                        .take(before.size),
                )
                assertEquals(
                    source.contentHash,
                    before
                        .last()
                        .citations!!
                        .retrieved
                        .single()
                        .revisionHash,
                )
            }
        }

    @Test
    fun committed_source_deletion_preserves_quotes_and_refuses_contextual_generation() =
        runBlocking {
            withFixture { f ->
                val source = f.seed()
                val chat = f.chat()
                f.send(chat, FIRST_QUERY)
                val before = f.session.repository.listMessages(chat)
                f.session.repository.deleteDocument(source.id)
                assertEquals(null, f.session.repository.getDocument(source.id))

                val result = f.send(chat, FOLLOW_UP)
                assertEquals(FollowUpResolution.SOURCE_UNAVAILABLE, f.results.single().resolution)
                f.assertAbstained(result)
                assertEquals(
                    before,
                    f.session.repository
                        .listMessages(chat)
                        .take(before.size),
                )
                assertEquals(
                    source.id,
                    before
                        .last()
                        .citations!!
                        .retrieved
                        .single()
                        .documentId,
                )
            }
        }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "context-send-").toFile()
        val key = TestKey()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var session: VaultSession? = null
        var fixture: Fixture? = null
        try {
            val opened =
                DeviceVaultOpener(
                    keyProvider = key,
                    paths = VaultPaths(directory),
                    attachmentsDir = File(directory, "attachments"),
                ).open()
            session = opened
            val active = Fixture(opened, opened.personaService.default(), scope)
            fixture = active
            block(active)
        } finally {
            fixture?.controller?.close()
            scope.cancel()
            try {
                session?.close()
            } finally {
                key.lock()
                check(directory.deleteRecursively()) { "Synthetic contextual vault cleanup failed" }
            }
        }
    }

    private class Fixture(
        val session: VaultSession,
        val persona: Persona,
        scope: CoroutineScope,
    ) {
        val engine = TestEngine()
        val requests = CopyOnWriteArrayList<ContextualRetrievalRequest>()
        val results = CopyOnWriteArrayList<ContextualRetrievalResult>()
        private val actual =
            RetrievalServiceImpl(
                session.indexStore,
                session.repository,
                null,
                legacyPersonaId = persona.id,
            )
        private val observed =
            object : ContextualRetrievalService by actual {
                override suspend fun retrieveContext(request: ContextualRetrievalRequest): ContextualRetrievalResult {
                    requests += request
                    return actual.retrieveContext(request).also { results += it }
                }
            }
        private val pipeline =
            SendPipeline(
                vaultRepository = session.repository,
                retrievalService = observed,
                promptAssembler = PromptAssemblerImpl(),
                engine = engine,
                personaProvider = { session.personaService.default() },
                personaById = session.personaService::get,
                budgetFor = { _, _ -> TokenBudget(16_384, 1_024, 8_192) },
                countTokens = { it.length / 4 },
            )
        val controller = ChatTurnController(session.repository, pipeline, 7L, { 7L }, { null }, scope)

        suspend fun seed(): Document {
            val source =
                session.repository.createDocument(
                    NewDocument(
                        DocumentKind.NOTE,
                        "Transit case",
                        "The transit case storage location is berth six. The case color is indigo.",
                        personaId = persona.id,
                    ),
                )
            ingest()
            return source
        }

        suspend fun ingest() {
            val result = IngestPipelines.forSession(session, { IngestPace.FULL }).run()
            assertTrue(result is IngestOutcome.Drained)
            assertTrue(session.indexStore.bm25("case", 8).isNotEmpty())
        }

        suspend fun chat(): DocId =
            session.repository.createDocument(NewDocument(DocumentKind.CHAT, "Chat", "", personaId = persona.id)).id

        suspend fun send(
            chat: DocId,
            query: String,
        ): ChatTurnSnapshot =
            withTimeout(10_000L) {
                val previousUserId = controller.state(chat).value.userMessageId
                controller.enqueue(chat, query).await()
                val completed =
                    controller.state(chat).first {
                        it.userMessageId != previousUserId &&
                            (it.turn is ChatTurnState.Done || it.turn is ChatTurnState.Failed)
                    }
                assertTrue("Synthetic turn failed before completion", completed.turn is ChatTurnState.Done)
                assertEquals(query, completed.outcome!!.userQuery)
                completed
            }

        fun assertAbstained(snapshot: ChatTurnSnapshot) {
            assertEquals(1, engine.calls.get())
            assertTrue(snapshot.outcome!!.generationSkipped)
            val saved = snapshot.outcome!!.assistantMessage!!
            assertEquals(Role.ASSISTANT, saved.role)
            assertTrue(saved.citations!!.retrieved.isEmpty())
            assertTrue(saved.citations!!.cited.isEmpty())
        }
    }

    private class TestEngine : InferenceEngine by scriptedEngine() {
        val calls = AtomicInteger()

        override fun stream(
            prompt: Prompt,
            params: SamplingParams,
        ): Flow<Token> {
            calls.incrementAndGet()
            return flowOf(Token.Text(SCRIPTED_ANSWER, 1), Token.Done(StopReason.EOS, 0, 1, 0, 0f))
        }
    }

    /** Test-owned random bytes only; no Keystore prompt/setup or existing-envelope operation is allowed. */
    private class TestKey : VaultKeyProvider {
        private val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        private var closed = false

        override fun isInitialised(): Boolean = true

        override fun currentKey(): ByteArray? = bytes.takeUnless { closed }

        override fun lock() {
            closed = true
            bytes.fill(0)
        }

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult = error("Synthetic contextual test never sets up a key")

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            existingMaster: ByteArray,
        ): SetupResult = error("Synthetic contextual test never recovers a key")

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult = error("Synthetic contextual test never prompts for authentication")

        override suspend fun rewrapAfterInvalidation(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RewrapResult = error("Synthetic contextual test never replaces an envelope")
    }

    private companion object {
        const val FIRST_QUERY = "What is the storage location?"
        const val FOLLOW_UP = "What is its color?"
        const val SCRIPTED_ANSWER = "The source describes a storage location [1]."
    }
}
