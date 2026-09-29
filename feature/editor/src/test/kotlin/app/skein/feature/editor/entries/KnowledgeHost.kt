// skein-xtov.24.8 (AL-09a): the real `SkeinShellHost` over the Knowledge
// entries, as `:app`'s `NavShell` wires them, for the entry tests and goldens.
package app.skein.feature.editor.entries

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import app.skein.testing.InMemoryIndexStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy
import java.time.ZoneId
import java.time.ZoneOffset

internal val COMPACT = DpSize(524.dp, 1175.dp)
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
internal fun KnowledgeHost(
    vault: VaultRepository,
    size: DpSize?,
    onShell: (SkeinShellState) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneOffset.UTC,
    preparation: Flow<KnowledgePreparation> = flowOf(KnowledgePreparation()),
) {
    val content =
        @Composable {
            val manager = remember { idleUnlockManager() }
            val shell = rememberSkeinShellState(manager)
            onShell(shell)
            val deps =
                remember(vault, preparation) {
                    KnowledgeEntryDeps(
                        vault,
                        InMemoryIndexStore(),
                        clock = clock,
                        zone = zone,
                        preparation = preparation,
                    )
                }
            SkeinShellHost(
                shell = shell,
                resolveKinds = navKindsOf(vault),
                detailPlaceholder = { destination ->
                    when (destination) {
                        Destination.KNOWLEDGE -> KnowledgeDetailPlaceholder(shell)
                        else -> PlaceholderEntry(null)
                    }
                },
            ) { key ->
                when (key.destination) {
                    Destination.KNOWLEDGE -> KnowledgeEntry(key, shell, deps)
                    else -> PlaceholderEntry(key)
                }
            }
        }
    when (size) {
        null -> content()
        else -> DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size)) { content() }
    }
}
