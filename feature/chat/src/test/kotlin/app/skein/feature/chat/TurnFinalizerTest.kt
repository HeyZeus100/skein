package app.skein.feature.chat

import app.skein.core.model.AnswerScope
import app.skein.core.model.DocumentKind
import app.skein.core.model.Message
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.StopReason
import app.skein.core.model.TokenBudget
import app.skein.core.model.VaultRepository
import app.skein.core.rag.chat.Segment
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TurnFinalizerTest {
    @Test
    fun `postcommit cancellation cannot append a second answer on Stop or lock`() =
        runTest {
            val actual = InMemoryVaultRepository()
            val repository =
                object : VaultRepository by actual {
                    override suspend fun afterTransactionCommit(action: () -> Unit) {
                        val owner = currentCoroutineContext()[Job]!!
                        actual.afterTransactionCommit {
                            action()
                            owner.cancel()
                        }
                    }
                }
            val snapshot = answer(actual)
            val finalizer = TurnFinalizer(repository)
            val first = async { finalizer.persist(snapshot) { true } }
            try {
                first.await()
            } catch (_: CancellationException) {
            }
            assertEquals(2, actual.listMessages(snapshot.context.turn.chatId).size)
            val saved = finalizer.persist(snapshot) { true }
            assertNotNull((saved as TurnPersistResult.Committed).message)
            assertEquals(2, actual.listMessages(snapshot.context.turn.chatId).size)
        }

    @Test
    fun `cancelled partial write rolls back and cannot persist after phase closes`() =
        runTest {
            val actual = InMemoryVaultRepository()
            val snapshot = answer(actual)
            val repository =
                object : VaultRepository by actual {
                    override suspend fun appendMessage(
                        chatDocId: String,
                        input: NewMessage,
                    ): Message {
                        val pending = actual.appendMessage(chatDocId, input)
                        delay(1_000L)
                        return pending
                    }
                }
            val finalizer = TurnFinalizer(repository)
            withTimeoutOrNull(100L) { finalizer.persist(snapshot) { true } }
            assertEquals(listOf(Role.USER), actual.listMessages(snapshot.context.turn.chatId).map { it.role })
            assertEquals(TurnPersistResult.Refused, finalizer.persist(snapshot) { false })
            assertEquals(1, actual.listMessages(snapshot.context.turn.chatId).size)
        }

    private suspend fun answer(repository: VaultRepository): TurnAnswerSnapshot {
        val chat = repository.createDocument(NewDocument(DocumentKind.CHAT, "Chat", ""))
        val user = repository.appendMessage(chat.id, NewMessage(Role.USER, "question"))
        val turn = PreparedChatTurn(user, null, AnswerScope.GENERAL, SamplingParams(), null)
        val assembled =
            PromptAssemblerImpl().assemble(
                null,
                emptyList(),
                emptyList(),
                "question",
                TokenBudget(
                    4096,
                    512,
                    0,
                ),
                {
                    it.length
                },
                AnswerScope.GENERAL,
            )
        return TurnAnswerSnapshot(
            TurnPromptContext(turn, emptyList(), assembled, null),
            "visible",
            listOf(Segment.Text("visible")),
            StopReason.CANCELLED,
        )
    }
}
