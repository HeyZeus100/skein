package app.skein.feature.shell.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The hamburger-drawer destinations, in drawer order. Only destinations
 * with a real screen belong here: Notes, Graph and Personas were
 * placeholders that rendered their enum name, so they are gone until
 * their screens exist (UX-P0-03, AL-01).
 */
enum class Destination { TIMELINE, SETTINGS }

/**
 * `@Stable` state holder for the persistent nav layer (plan `E6.I3`):
 * hamburger-drawer open/closed, the active [Destination], and the command
 * bar's query text. Deliberately holds no knowledge of slash-command
 * *execution* (out of scope) — just enough state for [CommandBar] to render
 * a "recognized a leading `/`" hint via [isCommand].
 */
@Stable
class NavState(
    initialDrawerOpen: Boolean = false,
    initialDestination: Destination = Destination.TIMELINE,
    initialQuery: String = "",
) {
    var drawerOpen: Boolean by mutableStateOf(initialDrawerOpen)
        private set

    var destination: Destination by mutableStateOf(initialDestination)
        private set

    private var queryState: String by mutableStateOf(initialQuery)

    /** Command bar text (spec §8.4). Backed by [queryState] — `query` can't also be the setter's name (JVM clash with [setQuery]). */
    val query: String get() = queryState

    /** `≡` tap — reveals the drawer. */
    fun openDrawer() {
        drawerOpen = true
    }

    /** Drawer scrim tap / swipe-to-dismiss / back — hides the drawer without changing the destination. */
    fun closeDrawer() {
        drawerOpen = false
    }

    /** Selecting a drawer entry: switches the active destination and closes the drawer. */
    fun navigate(destination: Destination) {
        this.destination = destination
        drawerOpen = false
    }

    /** Command bar text changes on every keystroke. */
    fun setQuery(query: String) {
        queryState = query
    }

    /** Resets the command bar query (e.g. after dismissing search, or after a command "runs"). */
    fun clear() {
        queryState = ""
    }

    /** `/` leading character recognized: [CommandBar] shows the "press ⏎ to run" hint. Execution is out of scope. */
    val isCommand: Boolean get() = query.startsWith("/")

    companion object {
        val Saver: Saver<NavState, List<Any?>> =
            Saver(
                save = { listOf(it.drawerOpen, it.destination.name, it.query) },
                restore = { values ->
                    @Suppress("UNCHECKED_CAST")
                    NavState(
                        initialDrawerOpen = values[0] as Boolean,
                        initialDestination = Destination.valueOf(values[1] as String),
                        initialQuery = values[2] as String,
                    )
                },
            )
    }
}

@Composable
fun rememberNavState(initialDestination: Destination = Destination.TIMELINE): NavState =
    rememberSaveable(saver = NavState.Saver) {
        NavState(initialDestination = initialDestination)
    }
