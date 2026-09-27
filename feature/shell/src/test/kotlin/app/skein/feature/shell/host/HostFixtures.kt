// skein-xtov.24.7 (AL-08): shared fixtures for the shell host tests — a probe
// entry (a T3 ViewModel with an instance serial and a T2 counter, the spike's
// `EntryProbe`), and a key provider that records when the key is zeroed.
package app.skein.feature.shell.host

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.skein.core.model.AuthorizationToken
import app.skein.core.navigation.SkeinId
import app.skein.core.navigation.SkeinKey
import app.skein.core.navigation.contentKey
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockManager
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal val CHAT_A = SkeinId.of("0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6b")
internal val NOTE_B = SkeinId.of("0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6c")
internal val DRAFT_D = SkeinId.of("0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6d")

/** What the probe entries did, per content key. */
internal class ProbeLedger {
    private val serial = AtomicInteger()
    val created = CopyOnWriteArrayList<String>()
    val cleared = CopyOnWriteArrayList<String>()
    val compositions = AtomicInteger()

    fun next(contentKey: String): Int {
        created += contentKey
        return serial.incrementAndGet()
    }
}

internal class ProbeViewModel(
    private val contentKey: String,
    private val ledger: ProbeLedger,
    private val onCleared: () -> Unit,
) : ViewModel() {
    val serial = ledger.next(contentKey)

    override fun onCleared() {
        ledger.cleared += contentKey
        onCleared.invoke()
    }
}

internal fun probeTag(key: SkeinKey) = "probe/${key.contentKey}"

/** Shows "vm=<serial> t2=<count>"; a click bumps the T2 count. */
@Composable
internal fun Probe(
    key: SkeinKey,
    ledger: ProbeLedger,
    onCleared: () -> Unit = {},
) {
    val vm = viewModel { ProbeViewModel(key.contentKey, ledger, onCleared) }
    var t2 by rememberSaveable { mutableIntStateOf(0) }
    SideEffect { ledger.compositions.incrementAndGet() }
    Text("vm=${vm.serial} t2=$t2", Modifier.testTag(probeTag(key)).clickable { t2++ })
}

/** A [VaultKeyProvider] whose unlock always succeeds and whose [lock] records "key zeroed". */
internal class RecordingKeyProvider(
    private val events: MutableList<String> = CopyOnWriteArrayList(),
) : VaultKeyProvider {
    private val epoch = AtomicInteger()

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
    ): SetupResult = error("not used")

    override suspend fun setup(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        existingMaster: ByteArray,
    ): SetupResult = error("not used")

    override fun isInitialised(): Boolean = true

    override suspend fun unlock(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        factor: VaultKeyProvider.Factor,
    ): UnlockResult = UnlockResult.Success(AuthorizationToken(epoch.incrementAndGet().toLong()))

    override fun currentKey(): ByteArray? = null

    override fun lock() {
        events += "key zeroed"
    }

    override suspend fun rewrapAfterInvalidation(
        activity: FragmentActivity,
        prompt: BiometricPrompt.PromptInfo,
        survivingFactor: VaultKeyProvider.Factor,
    ): RewrapResult = error("not used")
}

internal fun UnlockManager.unlockFor(activity: FragmentActivity) {
    val prompt =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock")
            .setNegativeButtonText("Cancel")
            .build()
    runBlocking { unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC) }
}
