// skein-xtov.24.9 (AL-09b): the real `SkeinShellHost` over the Graph entries,
// as `:app`'s `NavShell` wires them, for the entry tests and goldens.
package app.skein.feature.graph.entries

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.skein.core.model.IndexStore
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.Destination
import app.skein.core.navigation.destination
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import app.skein.feature.shell.host.PlaceholderEntry
import app.skein.feature.shell.host.SkeinShellHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.navKindsOf
import app.skein.feature.shell.host.rememberSkeinShellState
import java.lang.reflect.Proxy

/** The Fold's outer display: Compact, a drawer, one pane. */
internal val COMPACT = DpSize(524.dp, 1175.dp)

/** The Fold's inner display: Expanded, a rail, two panes. */
internal val EXPANDED = DpSize(1006.dp, 1043.dp)

/** The session stores register with an [UnlockManager]; these tests never unlock or lock through it. */
internal fun idleUnlockManager(): UnlockManager =
    UnlockManager(
        keyProvider =
            Proxy.newProxyInstance(
                VaultKeyProvider::class.java.classLoader,
                arrayOf(VaultKeyProvider::class.java),
            ) { _, method, _ ->
                if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            } as VaultKeyProvider,
    )

/** [size] overrides the window (live flips); null keeps the device's (the screenshot qualifiers). */
@Composable
internal fun GraphHost(
    vault: VaultRepository,
    indexStore: IndexStore,
    size: DpSize?,
    onShell: (SkeinShellState) -> Unit,
) {
    val content =
        @Composable {
            val manager = remember { idleUnlockManager() }
            val shell = rememberSkeinShellState(manager)
            onShell(shell)
            val deps = remember(vault, indexStore) { GraphEntryDeps(vault, indexStore) }
            SkeinShellHost(
                shell = shell,
                resolveKinds = navKindsOf(vault),
                detailPlaceholder = { PlaceholderEntry(null) },
            ) { key ->
                when (key.destination) {
                    Destination.GRAPH -> GraphEntry(key, shell, deps)
                    else -> PlaceholderEntry(key)
                }
            }
        }
    if (size == null) {
        content()
    } else {
        DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size)) { content() }
    }
}
