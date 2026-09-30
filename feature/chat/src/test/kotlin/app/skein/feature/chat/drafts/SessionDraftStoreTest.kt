package app.skein.feature.chat.drafts

import app.skein.core.model.ChatDraft
import app.skein.core.model.ChatDraftKey
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Role
import app.skein.core.model.VaultRepository
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Virtual time throughout: no IO dispatcher, wall-clock sleeps, or mixed deadline clocks. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDraftStoreTest {
    @Test
    fun `load and two second debounce preserve selection and write only changed drafts`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.repo.base.writeDraft(KEY, ChatDraft("Recovered", 3, 1))
            val state = f.store.state(KEY)
            assertEquals(DraftLoadState.Loading, state.value)
            runCurrent()
            assertEquals(ChatDraft("Recovered", 3, 1), state.ready().draft)
            f.store.update(KEY, ChatDraft("New draft", 2, 5))
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(0, f.repo.writes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(ChatDraft("New draft", 2, 5), f.repo.base.readDraft(KEY))
            f.store.update(KEY, ChatDraft("New draft", 2, 5))
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, f.repo.writes)
            assertFalse(state.value.toString().contains("New draft"))
        }

    @Test
    fun `load failure never becomes a writable empty draft and explicit retry restores it`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.repo.base.writeDraft(KEY, ChatDraft("recover me"))
            f.repo.beforeRead = { error("private read detail") }
            val state = f.store.state(KEY)
            runCurrent()
            assertEquals(DraftLoadState.LoadError, state.value)
            assertNull(f.store.update(KEY, ChatDraft("overwrite")))
            assertTrue(f.store.flushOnStop())
            assertEquals(0, f.repo.writes)
            f.repo.beforeRead = {}
            assertTrue(f.store.retryLoad(KEY))
            runCurrent()
            assertEquals("recover me", state.ready().draft.text)
        }

    @Test
    fun `draft keys isolate Spaces and existing chats`() =
        runTest {
            val f = Fixture(backgroundScope)
            val chat = f.repo.base.createDocument(NewDocument(DocumentKind.CHAT, "Owned chat", null))
            val keys = listOf(KEY, KEY.copy(spaceId = "space-b"), ChatDraftKey.Existing(chat.id))
            val states = keys.map(f.store::state)
            runCurrent()
            keys.forEachIndexed { i, key -> f.store.update(key, ChatDraft("draft $i")) }
            assertTrue(f.store.flushOnStop())
            keys.forEachIndexed { i, key ->
                assertEquals(
                    "draft $i",
                    f.repo.base
                        .readDraft(key)
                        ?.text,
                )
                assertEquals("draft $i", states[i].ready().draft.text)
            }
        }

    @Test
    fun `late writer admission rejects queued stop and debounce after Locking`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.store.state(KEY)
            runCurrent()
            f.store.update(KEY, ChatDraft("must not arrive late"))
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            f.repo.beforeTransaction = {
                entered.complete(Unit)
                proceed.await()
            }
            val stop = async { f.store.flushOnStop() }
            entered.await()
            f.phase = Phase.LOCKING
            proceed.complete(Unit)
            assertFalse(stop.await())
            advanceTimeBy(2_000)
            runCurrent()
            assertNull(f.repo.base.readDraft(KEY))
            assertNull(f.store.update(KEY, ChatDraft("late edit")))
            assertEquals(0, f.repo.writes)
        }

    @Test
    fun `lock flush writes all dirty keys in one transaction and refuses wrong epoch or phase`() =
        runTest {
            val f = Fixture(backgroundScope)
            val second = KEY.copy(draftId = "01926f3a-7c00-7000-8000-000000000003")
            f.store.state(KEY)
            f.store.state(second)
            runCurrent()
            f.store.update(KEY, ChatDraft("one"))
            f.store.update(second, ChatDraft("two"))
            assertFalse(f.store.onLocking(EPOCH, 100))
            f.phase = Phase.LOCKING
            assertFalse(f.store.onLocking(EPOCH + 1, 100))
            assertTrue(f.store.onLocking(EPOCH, 100))
            assertEquals(1, f.repo.transactions)
            assertEquals(2, f.repo.writes)
            f.phase = Phase.LOCKED
            assertFalse(f.store.onLocking(EPOCH, 100))
            assertFalse(f.store.flushOnStop())
        }

    @Test
    fun `failed batch rolls back all drafts and reports save failure without exposing details`() =
        runTest {
            val f = Fixture(backgroundScope)
            val second = KEY.copy(spaceId = "second")
            val firstState = f.store.state(KEY)
            val secondState = f.store.state(second)
            runCurrent()
            f.store.update(KEY, ChatDraft("one"))
            f.store.update(second, ChatDraft("two"))
            f.repo.beforeWrite = { if (it == second) error("private write detail") }
            f.phase = Phase.LOCKING
            assertFalse(f.store.onLocking(EPOCH, 100))
            assertNull(f.repo.base.readDraft(KEY))
            assertNull(f.repo.base.readDraft(second))
            assertTrue((firstState.value as DraftLoadState.Ready).saveFailed)
            assertTrue((secondState.value as DraftLoadState.Ready).saveFailed)
            assertFalse(firstState.value.toString().contains("private"))
        }

    @Test
    fun `lock timeout rolls back within its budget and no stale work reaches another epoch`() =
        runTest {
            val f = Fixture(backgroundScope)
            val state = f.store.state(KEY)
            runCurrent()
            f.store.update(KEY, ChatDraft("lock-time sentinel"))
            f.repo.beforeWrite = { awaitCancellation() }
            f.phase = Phase.LOCKING
            val lock = async { f.store.onLocking(EPOCH, 100) }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertFalse(lock.await())
            assertEquals(100L, testScheduler.currentTime)
            assertNull(f.repo.base.readDraft(KEY))
            f.phase = Phase.LOCKED
            f.store.onLocked(EPOCH)
            assertEquals(DraftLoadState.Closed, state.value)
            f.phase = Phase.UNLOCKED
            f.currentEpoch++
            f.repo.beforeWrite = {}
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse(f.store.flushOnStop())
            assertNull(f.repo.base.readDraft(KEY))
        }

    @Test
    fun `a flush of an older revision cannot mark a newer edit clean`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.store.state(KEY)
            runCurrent()
            f.store.update(KEY, ChatDraft("older"))
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            f.repo.beforeWrite = {
                entered.complete(Unit)
                proceed.await()
            }
            val flush = async { f.store.flushOnStop() }
            entered.await()
            f.store.update(KEY, ChatDraft("newer"))
            proceed.complete(Unit)
            assertTrue(flush.await())
            f.repo.beforeWrite = {}
            assertEquals(
                "older",
                f.repo.base
                    .readDraft(KEY)
                    ?.text,
            )
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(
                "newer",
                f.repo.base
                    .readDraft(KEY)
                    ?.text,
            )
        }

    @Test
    fun `commit failure rolls back first chat creation and keeps draft available`() =
        runTest {
            val f = Fixture(backgroundScope)
            val state = f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(KEY, ChatDraft("send me"))!!
            f.store.flushOnStop()
            val failed =
                runCatching {
                    f.store.commitSend(KEY, capture.version) {
                        f.repo.createDocument(NewDocument(DocumentKind.CHAT, "New chat", null))
                        error("failed before commit")
                    }
                }
            assertTrue(failed.isFailure)
            assertNull(f.repo.base.findByTitle("New chat"))
            assertEquals(
                "send me",
                f.repo.base
                    .readDraft(KEY)
                    ?.text,
            )
            assertEquals(capture, state.ready())
        }

    @Test
    fun `cancelled admission rolls back USER and retains the draft`() =
        runTest {
            val f = Fixture(backgroundScope)
            val state = f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(KEY, ChatDraft("keep on cancellation"))!!
            f.store.flushOnStop()
            val entered = CompletableDeferred<Unit>()
            val send =
                launch {
                    f.store.commitSend(KEY, capture.version) {
                        val chat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Cancelled chat", null))
                        f.repo.appendMessage(chat.id, NewMessage(Role.USER, capture.draft.text))
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            send.cancelAndJoin()
            assertNull(f.repo.base.findByTitle("Cancelled chat"))
            assertEquals(capture.draft, f.repo.base.readDraft(KEY))
            assertEquals(capture, state.ready())
        }

    @Test
    fun `send waiting behind a flush rechecks phase before creating anything`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(KEY, ChatDraft("waiting send"))!!
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            f.repo.beforeWrite = {
                entered.complete(Unit)
                proceed.await()
            }
            val flush = async { f.store.flushOnStop() }
            entered.await()
            var appendCalls = 0
            val send =
                async {
                    runCatching { f.store.commitSend(KEY, capture.version) { appendCalls++ } }
                }
            runCurrent()
            f.phase = Phase.LOCKING
            proceed.complete(Unit)
            assertTrue(flush.await())
            assertTrue(send.await().isFailure)
            assertEquals(0, appendCalls)
            assertEquals(capture.draft, f.repo.base.readDraft(KEY))
        }

    @Test
    fun `send gate prevents a queued flush from restoring consumed text after commit`() =
        runTest {
            val f = Fixture(backgroundScope)
            val state = f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(KEY, ChatDraft("consume once"))!!
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val send =
                async {
                    f.store.commitSend(KEY, capture.version) {
                        entered.complete(Unit)
                        proceed.await()
                        val chat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Sent", null))
                        f.repo.appendMessage(chat.id, NewMessage(Role.USER, capture.draft.text))
                        chat
                    }
                }
            entered.await()
            val staleFlush = async { f.store.flushOnStop() }
            runCurrent()
            proceed.complete(Unit)
            val chat = send.await()
            assertTrue(staleFlush.await())
            assertNull(f.repo.base.readDraft(KEY))
            assertEquals("", state.ready().draft.text)
            assertEquals(
                "consume once",
                f.repo.base
                    .listMessages(chat.id)
                    .single()
                    .contentMd,
            )
            assertTrue(runCatching { f.store.commitSend(KEY, capture.version) {} }.isFailure)
            advanceTimeBy(2_000)
            runCurrent()
            assertNull(f.repo.base.readDraft(KEY))
        }

    @Test
    fun `send re-dirties a newer edit already persisted before deletion`() =
        runTest {
            val f = Fixture(backgroundScope)
            val state = f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(KEY, ChatDraft("sent text"))!!
            f.store.update(KEY, ChatDraft("newer text"))
            f.store.flushOnStop()
            val beforeAck = state.ready().version
            f.store.commitSend(KEY, capture.version) { Unit }
            assertNull(f.repo.base.readDraft(KEY))
            assertEquals("newer text", state.ready().draft.text)
            assertTrue(state.ready().version > beforeAck)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(
                "newer text",
                f.repo.base
                    .readDraft(KEY)
                    ?.text,
            )
        }

    @Test
    fun `Locking just before commit acknowledgment consumes memory without a new write`() =
        runTest {
            val f = Fixture(backgroundScope)
            val state = f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(KEY, ChatDraft("sent"))!!
            f.store.flushOnStop()
            f.store.commitSend(KEY, capture.version) { f.phase = Phase.LOCKING }
            assertEquals("", state.ready().draft.text)
            assertTrue(f.store.onLocking(EPOCH, 100))
            assertNull(f.repo.base.readDraft(KEY))
            assertEquals(1, f.repo.writes)
        }

    @Test
    fun `cancellation after repository commit still acknowledges consumed memory`() =
        runTest {
            val f = Fixture(backgroundScope)
            val state = f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(KEY, ChatDraft("sent"))!!
            f.store.flushOnStop()
            f.repo.afterTransaction = {
                currentCoroutineContext().cancel()
                yield()
            }
            val send = launch { f.store.commitSend(KEY, capture.version) { Unit } }
            send.join()
            assertTrue(send.isCancelled)
            assertEquals("", state.ready().draft.text)
            assertNull(f.repo.base.readDraft(KEY))
            f.repo.afterTransaction = {}
            f.phase = Phase.LOCKING
            assertTrue(f.store.onLocking(EPOCH, 100))
            assertNull(f.repo.base.readDraft(KEY))
        }

    @Test
    fun `close scrubs every subscriber and late loads cannot repopulate them`() =
        runTest {
            val f = Fixture(backgroundScope)
            val first = f.store.state(KEY)
            runCurrent()
            f.store.update(KEY, ChatDraft("visible secret"))
            val secondKey = KEY.copy(spaceId = "second")
            val started = CompletableDeferred<Unit>()
            val completeRead = CompletableDeferred<Unit>()
            f.repo.base.writeDraft(secondKey, ChatDraft("late secret"))
            f.repo.beforeRead = {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    completeRead.await()
                }
            }
            val second = f.store.state(secondKey)
            started.await()
            f.store.close()
            completeRead.complete(Unit)
            runCurrent()
            assertEquals(DraftLoadState.Closed, first.value)
            assertEquals(DraftLoadState.Closed, second.value)
            assertEquals(DraftLoadState.Closed, f.store.state(KEY.copy(spaceId = "unknown")).value)
            assertNull(f.store.update(KEY, ChatDraft("cannot return")))
            assertFalse(f.store.retryLoad(secondKey))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(0, f.repo.writes)
        }

    @Test
    fun `committed discard closes existing holders and preserves unrelated existing and new drafts`() =
        runTest {
            val f = Fixture(backgroundScope)
            val chat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Deleted", null))
            val otherChat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Kept", null))
            val key = ChatDraftKey.Existing(chat.id)
            val otherKey = ChatDraftKey.Existing(otherChat.id)
            val old = f.store.state(key)
            val other = f.store.state(otherKey)
            val fresh = f.store.state(KEY)
            runCurrent()
            f.store.update(key, ChatDraft("discarded"))
            f.store.update(otherKey, ChatDraft("kept existing"))
            f.store.update(KEY, ChatDraft("kept new"))
            assertTrue(f.store.flushOnStop())
            f.repo.transaction {
                f.repo.deleteDocument(chat.id)
                f.repo.afterTransactionCommit { f.store.discardCommitted(chat.id) }
            }
            f.store.discardCommitted(chat.id)
            assertEquals(DraftLoadState.Closed, old.value)
            assertEquals(DraftLoadState.Closed, f.store.state(key).value)
            assertNull(f.store.update(key, ChatDraft("late keystroke")))
            assertFalse(f.store.retryLoad(key))
            assertNull(f.repo.readDraft(key))
            assertEquals("kept existing", other.ready().draft.text)
            assertEquals("kept new", fresh.ready().draft.text)
            f.store.update(KEY, ChatDraft("new remains writable"))
            f.phase = Phase.LOCKING
            assertTrue(f.store.onLocking(EPOCH, 100))
            assertNull(f.repo.readDraft(key))
            assertEquals("new remains writable", f.repo.readDraft(KEY)?.text)
            assertEquals("kept existing", f.repo.readDraft(otherKey)?.text)
        }

    @Test
    fun `late noncancellable read cannot reopen a committed discarded key`() =
        runTest {
            val f = Fixture(backgroundScope)
            val chat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Deleted", null))
            val key = ChatDraftKey.Existing(chat.id)
            f.repo.writeDraft(key, ChatDraft("old plaintext"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.repo.beforeRead = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val old = f.store.state(key)
            entered.await()
            f.repo.transaction {
                f.repo.deleteDocument(chat.id)
                f.repo.afterTransactionCommit { f.store.discardCommitted(chat.id) }
            }
            assertEquals(DraftLoadState.Closed, old.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(DraftLoadState.Closed, old.value)
            assertEquals(DraftLoadState.Closed, f.store.state(key).value)
            assertFalse(f.store.retryLoad(key))
            assertNull(f.repo.readDraft(key))
        }

    @Test
    fun `queued mixed flush skips committed deletion and still saves unrelated new draft`() =
        runTest {
            val f = Fixture(backgroundScope)
            val chat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Deleted", null))
            val key = ChatDraftKey.Existing(chat.id)
            val old = f.store.state(key)
            f.store.state(KEY)
            runCurrent()
            f.store.update(key, ChatDraft("stale pending save"))
            f.store.update(KEY, ChatDraft("new draft"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.repo.beforeTransaction = {
                entered.complete(Unit)
                release.await()
            }
            val flush = async { f.store.flushOnStop() }
            entered.await()
            f.repo.base.transaction {
                f.repo.base.deleteDocument(chat.id)
                f.repo.base.afterTransactionCommit { f.store.discardCommitted(chat.id) }
            }
            release.complete(Unit)
            assertTrue(flush.await())
            assertEquals(DraftLoadState.Closed, old.value)
            assertNull(f.repo.readDraft(key))
            assertEquals("new draft", f.repo.readDraft(KEY)?.text)
            advanceTimeBy(5_000)
            runCurrent()
            assertNull(f.repo.readDraft(key))
        }

    @Test
    fun `committed discard revokes send waiting for flush without appending user`() =
        runTest {
            val f = Fixture(backgroundScope)
            val chat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Deleted", null))
            val key = ChatDraftKey.Existing(chat.id)
            val old = f.store.state(key)
            f.store.state(KEY)
            runCurrent()
            val capture = f.store.update(key, ChatDraft("stale send"))!!
            f.store.update(KEY, ChatDraft("unrelated"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.repo.beforeTransaction = {
                entered.complete(Unit)
                release.await()
            }
            val flush = async { f.store.flushOnStop() }
            entered.await()
            var appendCalls = 0
            val send = launch { f.store.commitSend(key, capture.version) { appendCalls++ } }
            runCurrent()
            f.repo.base.transaction {
                f.repo.base.deleteDocument(chat.id)
                f.repo.base.afterTransactionCommit { f.store.discardCommitted(chat.id) }
            }
            send.join()
            assertTrue(send.isCancelled)
            release.complete(Unit)
            assertTrue(flush.await())
            assertEquals(0, appendCalls)
            assertEquals(DraftLoadState.Closed, old.value)
            assertTrue(runCatching { f.store.commitSend(key, capture.version) { appendCalls++ } }.isFailure)
            assertEquals(0, appendCalls)
            assertEquals("unrelated", f.repo.readDraft(KEY)?.text)
            assertNull(f.repo.readDraft(key))
        }

    @Test
    fun `delete queued behind in-flight save cascades its commit and rollback keeps draft writable`() =
        runTest {
            val f = Fixture(backgroundScope)
            val chat = f.repo.createDocument(NewDocument(DocumentKind.CHAT, "Deleted", null))
            val key = ChatDraftKey.Existing(chat.id)
            val old = f.store.state(key)
            runCurrent()
            f.store.update(key, ChatDraft("save before delete"))
            assertTrue(
                runCatching {
                    f.repo.transaction {
                        f.repo.deleteDocument(chat.id)
                        f.repo.afterTransactionCommit { f.store.discardCommitted(chat.id) }
                        error("rollback")
                    }
                }.isFailure,
            )
            assertEquals("save before delete", old.ready().draft.text)
            assertTrue(f.store.update(key, ChatDraft("still writable")) != null)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.repo.beforeWrite = {
                entered.complete(Unit)
                release.await()
            }
            val save = async { f.store.flushOnStop() }
            entered.await()
            val deletion =
                async {
                    f.repo.transaction {
                        f.repo.deleteDocument(chat.id)
                        f.repo.afterTransactionCommit { f.store.discardCommitted(chat.id) }
                    }
                }
            runCurrent()
            assertFalse(deletion.isCompleted)
            release.complete(Unit)
            assertTrue(save.await())
            deletion.await()
            assertEquals(DraftLoadState.Closed, old.value)
            assertNull(f.repo.readDraft(key))
            assertNull(f.repo.getDocument(chat.id))
            advanceTimeBy(5_000)
            runCurrent()
            assertNull(f.repo.readDraft(key))
        }

    private enum class Phase { UNLOCKED, LOCKING, LOCKED }

    private class Fixture(
        scope: CoroutineScope,
    ) {
        val repo = ProbeRepository()
        var phase = Phase.UNLOCKED
        var currentEpoch = EPOCH
        val store =
            SessionDraftStore(
                repository = repo,
                epoch = EPOCH,
                unlockedEpoch = { currentEpoch.takeIf { phase == Phase.UNLOCKED } },
                lockingEpoch = { currentEpoch.takeIf { phase == Phase.LOCKING } },
                sessionScope = scope,
            )
    }

    private class ProbeRepository(
        val base: InMemoryVaultRepository = InMemoryVaultRepository(),
    ) : VaultRepository by base {
        var writes = 0
        var transactions = 0
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend (ChatDraftKey) -> Unit = {}
        var beforeTransaction: suspend () -> Unit = {}
        var afterTransaction: suspend () -> Unit = {}

        override suspend fun readDraft(key: ChatDraftKey): ChatDraft? {
            beforeRead()
            return base.readDraft(key)
        }

        override suspend fun writeDraft(
            key: ChatDraftKey,
            draft: ChatDraft,
        ) {
            beforeWrite(key)
            writes++
            base.writeDraft(key, draft)
        }

        override suspend fun <T> transaction(block: suspend () -> T): T {
            beforeTransaction()
            transactions++
            val value = base.transaction(block)
            afterTransaction()
            return value
        }
    }

    private fun kotlinx.coroutines.flow.StateFlow<DraftLoadState>.ready(): DraftSnapshot =
        (value as DraftLoadState.Ready).snapshot

    private companion object {
        const val EPOCH = 4L
        val KEY = ChatDraftKey.New("space-a", "01926f3a-7c00-7000-8000-000000000002")
    }
}
