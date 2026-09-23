package app.skein.feature.shell.nav

import app.skein.testing.FakeClock
import app.skein.testing.SkeinLogCaptureRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.VaultRepository
import us.aherrera.skein.testing.InMemoryVaultRepository

/** Counts [searchTitles] calls by delegation — [InMemoryVaultRepository] itself is `final`. */
private class CountingSearchRepository(
    private val delegate: VaultRepository,
) : VaultRepository by delegate {
    var searchCount: Int = 0
        private set

    override suspend fun searchTitles(
        prefix: String,
        limit: Int,
    ) = delegate.searchTitles(prefix, limit).also { searchCount++ }
}

/**
 * Drives [CommandBarState] directly — no Compose UI involved — via
 * `TestScope`/`runTest` so the 150ms search debounce is virtual time, the
 * same shape `EditorAutosaveTest` uses for `EditorState`'s 500ms autosave
 * debounce. Covers plan `E6.I4` slice A (bd `skein-ps0`): search ordering
 * (title hits before body hits), the debounce itself, Enter-opens-top-hit,
 * `/`-command dispatch, and the no-logging acceptance criterion.
 */
class CommandBarStateTest {
    @get:Rule
    val logCapture = SkeinLogCaptureRule()

    private fun openedPreviews() = mutableListOf<Pair<String, String>>()

    @Test
    fun `a blank query produces no results and does not search`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val state =
                CommandBarState(
                    vaultRepository = repo,
                    registry = CommandRegistry(),
                    onOpenPreview = { _, _ -> },
                    searchScope = backgroundScope,
                )

            state.onQueryChanged("")
            advanceTimeBy(200)
            runCurrent()

            assertTrue(state.results.isEmpty())
        }

    @Test
    fun `title hits are ordered before body hits and are not duplicated`() =
        runTest {
            // Increasing clock so `searchTitles`/`searchBodies`'s
            // `sortedByDescending { updatedAt }` has a deterministic order
            // (a real/system clock could tie for docs created microseconds
            // apart, making the assertion below flaky).
            val clock = FakeClock()
            val repo = InMemoryVaultRepository(clock = clock::now)
            repo.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = "Body only mentions qua", bodyMd = "qua appears here"),
            )
            clock.advanceBy(1)
            repo.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = "Quantum notes", bodyMd = "unrelated body"),
            )
            clock.advanceBy(1)
            repo.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = "Quantum notes with qua in body too",
                    bodyMd = "qua shows up here as well",
                ),
            )
            val state =
                CommandBarState(
                    vaultRepository = repo,
                    registry = CommandRegistry(),
                    onOpenPreview = { _, _ -> },
                    searchScope = backgroundScope,
                )

            state.onQueryChanged("qua")
            advanceTimeBy(200)
            runCurrent()

            val titles = state.results.map { it.title }
            assertEquals(
                listOf("Quantum notes with qua in body too", "Quantum notes", "Body only mentions qua"),
                titles,
            )
        }

    @Test
    fun `rapid typing within the debounce window issues a single search for the last value`() =
        runTest {
            val repo = CountingSearchRepository(InMemoryVaultRepository())
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "quasar", bodyMd = ""))
            val state =
                CommandBarState(
                    vaultRepository = repo,
                    registry = CommandRegistry(),
                    onOpenPreview = { _, _ -> },
                    searchScope = backgroundScope,
                )

            state.onQueryChanged("q")
            advanceTimeBy(50)
            runCurrent()
            state.onQueryChanged("qu")
            advanceTimeBy(50)
            runCurrent()
            state.onQueryChanged("qua")
            advanceTimeBy(200)
            runCurrent()

            assertEquals(1, repo.searchCount)
            assertEquals(listOf("quasar"), state.results.map { it.title })
        }

    @Test
    fun `a leading slash clears any in-flight text search results`() =
        runTest {
            val repo = InMemoryVaultRepository()
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "quasar", bodyMd = ""))
            val state =
                CommandBarState(
                    vaultRepository = repo,
                    registry = CommandRegistry(),
                    onOpenPreview = { _, _ -> },
                    searchScope = backgroundScope,
                )
            state.onQueryChanged("qua")
            advanceTimeBy(200)
            runCurrent()
            assertTrue(state.results.isNotEmpty())

            state.onQueryChanged("/new note")
            advanceTimeBy(200)
            runCurrent()

            assertTrue(state.results.isEmpty())
        }

    @Test
    fun `onSubmit in text mode opens the top result as a preview`() =
        runTest {
            val opened = openedPreviews()
            val repo = InMemoryVaultRepository()
            repo.createDocument(NewDocument(kind = DocumentKind.NOTE, title = "Quantum notes", bodyMd = ""))
            val state =
                CommandBarState(
                    vaultRepository = repo,
                    registry = CommandRegistry(),
                    onOpenPreview = { docId, title -> opened.add(docId to title) },
                    searchScope = backgroundScope,
                )
            state.onQueryChanged("qua")
            advanceTimeBy(200)
            runCurrent()

            val ran = state.onSubmit()

            assertTrue(ran)
            assertEquals(listOf("Quantum notes"), opened.map { it.second })
        }

    @Test
    fun `onSubmit in text mode with no results does nothing`() =
        runTest {
            val opened = openedPreviews()
            val repo = InMemoryVaultRepository()
            val state =
                CommandBarState(
                    vaultRepository = repo,
                    registry = CommandRegistry(),
                    onOpenPreview = { docId, title -> opened.add(docId to title) },
                    searchScope = backgroundScope,
                )
            state.onQueryChanged("nothing matches")
            advanceTimeBy(200)
            runCurrent()

            val ran = state.onSubmit()

            assertFalse(ran)
            assertTrue(opened.isEmpty())
        }

    @Test
    fun `onSubmit in command mode runs the matched command with its argument`() =
        runTest {
            val ranWith = mutableListOf<String>()
            val registry = CommandRegistry()
            registry.register(
                CommandScope.GLOBAL,
                listOf(Command(keyword = "new note", hint = "") { arg -> ranWith.add(arg) }),
            )
            val state =
                CommandBarState(
                    vaultRepository = InMemoryVaultRepository(),
                    registry = registry,
                    onOpenPreview = { _, _ -> },
                    searchScope = backgroundScope,
                )
            state.onQueryChanged("/new note Smoke test")

            val ran = state.onSubmit()

            assertTrue(ran)
            assertEquals(listOf("Smoke test"), ranWith)
        }

    @Test
    fun `onSubmit in command mode with no matching command does nothing`() =
        runTest {
            val state =
                CommandBarState(
                    vaultRepository = InMemoryVaultRepository(),
                    registry = CommandRegistry(),
                    onOpenPreview = { _, _ -> },
                    searchScope = backgroundScope,
                )
            state.onQueryChanged("/unknown")

            val ran = state.onSubmit()

            assertFalse(ran)
        }

    @Test
    fun `searching never logs the query text`() =
        runTest {
            val repo = InMemoryVaultRepository()
            repo.createDocument(
                NewDocument(
                    kind = DocumentKind.NOTE,
                    title = "a secret project name",
                    bodyMd = "confidential body text",
                ),
            )
            val state =
                CommandBarState(
                    vaultRepository = repo,
                    registry = CommandRegistry(),
                    onOpenPreview = { _, _ -> },
                    searchScope = backgroundScope,
                )

            state.onQueryChanged("secret project confidential")
            advanceTimeBy(200)
            runCurrent()
            state.onSubmit()

            val logged = logCapture.captured()
            assertTrue(
                "expected no log entry to mention the query or its hits, got: $logged",
                logged.none { entry ->
                    entry.message.contains("secret project confidential") ||
                        entry.message.contains("secret project name") ||
                        entry.message.contains("confidential body text")
                },
            )
        }
}
