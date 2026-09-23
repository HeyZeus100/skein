package app.skein.feature.shell.nav

import app.skein.feature.shell.tabs.TabKind
import app.skein.feature.shell.tabs.TabState
import app.skein.feature.shell.tabs.TabsState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * `/new note [title]` (plan `E6.I4` slice A, bd `skein-ps0`, coordinator
 * note 2026-09-22): creates a NOTE via `VaultRepository.createDocument`
 * and opens it pinned on the fake [TabsState] the same acceptance
 * criteria describe ("fake tab controller asserts").
 */
class BuiltinCommandsTest {
    @Test
    fun `running the command with a title creates a NOTE with that title`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val tabsState = TabsState()
            val command = newNoteCommand(repo, personaId = null, tabsState = tabsState)

            command.run("Smoke test")

            val created = repo.searchTitles("Smoke test").single()
            assertEquals("Smoke test", created.title)
            assertEquals(DocumentKind.NOTE, created.kind)
        }

    @Test
    fun `running the command opens the created note pinned, not as a preview`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val tabsState = TabsState()
            val command = newNoteCommand(repo, personaId = null, tabsState = tabsState)

            command.run("Smoke test")

            val tab = tabsState.activeTab
            assertTrue(tab != null && tab.pinned)
            assertEquals(TabState.PINNED, tab?.state)
            assertEquals(TabKind.NOTE, tab?.kind)
        }

    @Test
    fun `a blank title falls back to Untitled`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val tabsState = TabsState()
            val command = newNoteCommand(repo, personaId = null, tabsState = tabsState)

            command.run("")

            assertEquals("Untitled", tabsState.activeTab?.title)
        }

    @Test
    fun `a whitespace-only title falls back to Untitled`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val tabsState = TabsState()
            val command = newNoteCommand(repo, personaId = null, tabsState = tabsState)

            command.run("   ")

            assertEquals("Untitled", tabsState.activeTab?.title)
        }

    @Test
    fun `a null personaId creates a note with no persona`() =
        runTest {
            val repo = InMemoryVaultRepository()
            val tabsState = TabsState()
            val command = newNoteCommand(repo, personaId = null, tabsState = tabsState)

            command.run("Smoke test")

            val created = repo.searchTitles("Smoke test").single()
            assertNull(created.personaId)
        }
}
