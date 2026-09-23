package app.skein.feature.timeline

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.NewDocument
import app.skein.core.model.Persona
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives [TimelineState] directly — Compose-off — under `runTest`. The
 * state's coroutine scope is `backgroundScope` so its `stateIn` collectors
 * are torn down by the test framework, matching `EditorAutosaveTest`.
 * Settling uses `runCurrent()` rather than `advanceUntilIdle()`: the
 * latter deliberately stops once only `backgroundScope` work remains,
 * and every flow here emits at the current virtual time (no delays).
 *
 * `InMemoryVaultRepository` honours `TimelineFilter.personaId` and
 * `kinds` but ignores `tag` (the real `VaultRepositoryImpl` resolves tags
 * through TAG edges). Tag coverage here therefore asserts what the state
 * layer owns — that the tag is threaded into the `observeTimeline` call —
 * not the row count, which is the repository's contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineStateTest {
    @Test
    fun `subscribing observes the default filter with one page and no cursor`() =
        runTest {
            val recorder = RecordingRepository(newRepo())
            val state = TimelineState(repo = recorder, scope = backgroundScope)

            subscribe(state)

            val call = recorder.calls.single()
            assertEquals(TimelineFilter(), call.filter)
            assertEquals(TimelineState.DEFAULT_PAGE_SIZE, call.limit)
            assertNull(call.before)
        }

    @Test
    fun `entries are newest first`() =
        runTest {
            val repo = newRepo()
            repo.note("Older")
            repo.note("Newer")
            val state = TimelineState(repo = repo, scope = backgroundScope)

            subscribe(state)

            assertEquals(listOf("Newer", "Older"), state.entries.value.map { it.title })
        }

    @Test
    fun `changing the persona re-subscribes with the new filter and narrows entries`() =
        runTest {
            val repo = newRepo()
            repo.note("A-note", personaId = "persona-a")
            repo.note("B-note", personaId = "persona-b")
            val recorder = RecordingRepository(repo)
            val state = TimelineState(repo = recorder, scope = backgroundScope)
            subscribe(state)
            assertEquals(2, state.entries.value.size)

            state.setPersona("persona-b")
            runCurrent()

            assertEquals(2, recorder.calls.size)
            assertEquals(
                "persona-b",
                recorder.calls
                    .last()
                    .filter.personaId,
            )
            assertEquals(listOf("B-note"), state.entries.value.map { it.title })
        }

    @Test
    fun `selecting a tag threads it through TimelineFilter tag`() =
        runTest {
            val recorder = RecordingRepository(newRepo())
            val state = TimelineState(repo = recorder, scope = backgroundScope)
            subscribe(state)

            state.toggleTag("project")
            runCurrent()

            assertEquals(
                "project",
                recorder.calls
                    .last()
                    .filter.tag,
            )
            assertEquals("project", state.filter.tag)
        }

    @Test
    fun `toggling the selected tag again clears it`() =
        runTest {
            val state = TimelineState(repo = newRepo(), scope = backgroundScope)

            state.toggleTag("project")
            state.toggleTag("project")

            assertNull(state.filter.tag)
        }

    @Test
    fun `toggling a kind off removes it from the filter`() =
        runTest {
            val state = TimelineState(repo = newRepo(), scope = backgroundScope)

            state.toggleKind(DocumentKind.CHAT)

            assertEquals(DocumentKind.entries.toSet() - DocumentKind.CHAT, state.filter.kinds)
        }

    @Test
    fun `toggling the last remaining kind off resets to all kinds`() =
        runTest {
            val state =
                TimelineState(
                    repo = newRepo(),
                    scope = backgroundScope,
                    initial = TimelineFilter(kinds = setOf(DocumentKind.NOTE)),
                )

            state.toggleKind(DocumentKind.NOTE)

            assertEquals(DocumentKind.entries.toSet(), state.filter.kinds)
        }

    @Test
    fun `setting a filter to its current value does not re-subscribe`() =
        runTest {
            val recorder = RecordingRepository(newRepo())
            val state = TimelineState(repo = recorder, scope = backgroundScope)
            subscribe(state)
            assertEquals(1, recorder.calls.size)

            state.setPersona(null)
            state.setTag(null)
            state.clearFilters()
            runCurrent()

            assertEquals(1, recorder.calls.size)
        }

    @Test
    fun `an empty repository yields an empty list with nothing more to load`() =
        runTest {
            val state = TimelineState(repo = newRepo(), scope = backgroundScope)

            subscribe(state)

            assertTrue(state.entries.value.isEmpty())
            assertFalse(state.hasMore.value)
        }

    @Test
    fun `loadMore widens the window one page at a time until the vault is exhausted`() =
        runTest {
            val repo = newRepo()
            repeat(120) { repo.note("Doc $it") }
            val recorder = RecordingRepository(repo)
            val state = TimelineState(repo = recorder, scope = backgroundScope, pageSize = 50)
            subscribe(state)
            assertEquals(50, state.entries.value.size)
            assertTrue(state.hasMore.value)

            state.loadMore()
            runCurrent()
            assertEquals(100, recorder.calls.last().limit)
            assertEquals(100, state.entries.value.size)
            assertTrue(state.hasMore.value)

            state.loadMore()
            runCurrent()
            assertEquals(150, recorder.calls.last().limit)
            assertEquals(120, state.entries.value.size)
            assertFalse(state.hasMore.value)

            val callsBefore = recorder.calls.size
            state.loadMore()
            runCurrent()
            assertEquals("a partial page means there is nothing more to ask for", callsBefore, recorder.calls.size)
        }

    @Test
    fun `changing the filter resets the window to the first page`() =
        runTest {
            val repo = newRepo()
            repeat(120) { repo.note("Doc $it") }
            val state = TimelineState(repo = repo, scope = backgroundScope, pageSize = 50)
            subscribe(state)
            state.loadMore()
            runCurrent()
            assertEquals(100, state.limit)

            state.setPersona("someone")
            runCurrent()

            assertEquals(50, state.limit)
        }

    @Test
    fun `tags aggregate frontmatter tags of the visible entries and keep the selected tag`() =
        runTest {
            val repo = newRepo()
            repo.note("A", tags = listOf("project", "idea"))
            repo.note("B", tags = listOf("idea", "work"))
            val state = TimelineState(repo = repo, scope = backgroundScope)
            subscribe(state)
            assertEquals(listOf("idea", "project", "work"), state.tags.value)

            state.setTag("zzz")
            runCurrent()

            assertEquals(listOf("idea", "project", "work", "zzz"), state.tags.value)
        }

    @Test
    fun `caller-supplied tags seed the chip strip and can be replaced`() =
        runTest {
            val state =
                TimelineState(
                    repo = newRepo(),
                    scope = backgroundScope,
                    availableTags = setOf("seeded"),
                )
            subscribe(state)
            assertEquals(listOf("seeded"), state.tags.value)

            state.setAvailableTags(setOf("other"))
            runCurrent()

            assertEquals(listOf("other"), state.tags.value)
        }

    @Test
    fun `personas from the supplied flow are exposed for the persona dropdown`() =
        runTest {
            val persona = Persona(id = "p1", name = "Work", systemPrompt = null, defaultModel = null, createdAt = 0L)
            val state =
                TimelineState(
                    repo = newRepo(),
                    scope = backgroundScope,
                    personaSource = flowOf(listOf(persona)),
                )

            subscribe(state)

            assertEquals(listOf(persona), state.personas.value)
        }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newRepo(): InMemoryVaultRepository {
        val clock = AtomicLong(BASE_MILLIS)
        return InMemoryVaultRepository(clock = { clock.getAndAdd(1_000L) })
    }

    /** Subscribes every derived flow the screen would, then settles the scheduler. */
    private fun TestScope.subscribe(state: TimelineState) {
        backgroundScope.launch { state.entries.collect {} }
        backgroundScope.launch { state.hasMore.collect {} }
        backgroundScope.launch { state.tags.collect {} }
        backgroundScope.launch { state.personas.collect {} }
        runCurrent()
    }

    private suspend fun VaultRepository.note(
        title: String,
        personaId: String? = null,
        tags: List<String> = emptyList(),
    ): Document =
        createDocument(
            NewDocument(
                kind = DocumentKind.NOTE,
                title = title,
                bodyMd = "body of $title",
                personaId = personaId,
                frontmatter =
                    if (tags.isEmpty()) {
                        JsonObject(emptyMap())
                    } else {
                        buildJsonObject { put(FrontmatterKeys.TAGS, JsonArray(tags.map(::JsonPrimitive))) }
                    },
            ),
        )

    /** Records every `observeTimeline` call so tests can assert re-subscription (or its absence). */
    private class RecordingRepository(
        private val inner: VaultRepository,
    ) : VaultRepository by inner {
        data class Call(
            val filter: TimelineFilter,
            val limit: Int,
            val before: Long?,
        )

        val calls = mutableListOf<Call>()

        override fun observeTimeline(
            filter: TimelineFilter,
            limit: Int,
            before: Long?,
        ): Flow<List<Document>> {
            calls += Call(filter, limit, before)
            return inner.observeTimeline(filter, limit, before)
        }
    }

    private companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
    }
}
