package app.skein.feature.shell.nav

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Saver.save] needs a [SaverScope] receiver; this fake accepts everything, for pure-JVM tests. */
private val fakeSaverScope = SaverScope { true }

private fun <Original, Saveable : Any> Saver<Original, Saveable>.saveWith(
    scope: SaverScope,
    value: Original,
): Saveable? = with(scope) { save(value) }

/** `E6.I3` state-transition tests for [NavState] in isolation from Compose UI. */
class NavStateTest {
    @Test
    fun `initial state has the drawer closed`() {
        val state = NavState()

        assertFalse(state.drawerOpen)
    }

    @Test
    fun `initial destination defaults to timeline`() {
        val state = NavState()

        assertEquals(Destination.TIMELINE, state.destination)
    }

    @Test
    fun `openDrawer opens the drawer`() {
        val state = NavState()

        state.openDrawer()

        assertTrue(state.drawerOpen)
    }

    @Test
    fun `closeDrawer closes an open drawer`() {
        val state = NavState(initialDrawerOpen = true)

        state.closeDrawer()

        assertFalse(state.drawerOpen)
    }

    @Test
    fun `closeDrawer on an already-closed drawer is a no-op`() {
        val state = NavState()

        state.closeDrawer()

        assertFalse(state.drawerOpen)
    }

    @Test
    fun `navigate switches the active destination`() {
        val state = NavState()

        state.navigate(Destination.GRAPH)

        assertEquals(Destination.GRAPH, state.destination)
    }

    @Test
    fun `navigate closes the drawer`() {
        val state = NavState(initialDrawerOpen = true)

        state.navigate(Destination.NOTES)

        assertFalse(state.drawerOpen)
    }

    @Test
    fun `setQuery updates the command bar query`() {
        val state = NavState()

        state.setQuery("hello")

        assertEquals("hello", state.query)
    }

    @Test
    fun `clear resets the query to empty`() {
        val state = NavState(initialQuery = "hello")

        state.clear()

        assertEquals("", state.query)
    }

    @Test
    fun `clear does not change the active destination`() {
        val state = NavState(initialDestination = Destination.SETTINGS, initialQuery = "hello")

        state.clear()

        assertEquals(Destination.SETTINGS, state.destination)
    }

    @Test
    fun `a query without a leading slash is not recognized as a command`() {
        val state = NavState(initialQuery = "search terms")

        assertFalse(state.isCommand)
    }

    @Test
    fun `a query with a leading slash is recognized as a command`() {
        val state = NavState(initialQuery = "/new note")

        assertTrue(state.isCommand)
    }

    @Test
    fun `saver round-trips drawer open, destination, and query`() {
        val original =
            NavState(
                initialDrawerOpen = true,
                initialDestination = Destination.PERSONAS,
                initialQuery = "/model qwen",
            )

        val saved = NavState.Saver.saveWith(fakeSaverScope, original)
        val restored = NavState.Saver.restore(saved!!)!!

        val expected = Triple(true, Destination.PERSONAS, "/model qwen")
        val actual = Triple(restored.drawerOpen, restored.destination, restored.query)
        assertEquals(expected, actual)
    }
}
