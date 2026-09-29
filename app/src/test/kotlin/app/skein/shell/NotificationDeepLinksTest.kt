package app.skein.shell

import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.skein.TestSkeinApplication
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.vault.ScriptedVaultKeyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
class NotificationDeepLinksTest {
    @Test
    fun `only exact content-free notification URIs and notification action are accepted`() {
        assertEquals(NotificationDestination.MODELS, notificationDestination(link("app://skein/models")))
        assertEquals(NotificationDestination.KNOWLEDGE, notificationDestination(link("app://skein/ingest")))
        val rejected =
            listOf(
                "app://skein/models?delete=true",
                "app://skein/models?",
                "app://skein/models#private-title",
                "app://skein/models#",
                "app://skein/models/0190a3c4-5b6d-7e8f-9a0b-1c2d3e4f5a6b",
                "app://skein/ingest/",
                "app://skein/%6dodels",
                "app://skein/models%2f..%2fdelete",
                "app://skein/./models",
                "app://skein//models",
                "app://skein:80/models",
                "app://user@skein/models",
                "app://skein.evil/models",
                "app://evil/models",
                "app:/skein/models",
                "APP://SKEIN/models",
                "https://skein/models",
                "file:///models",
                "intent://skein/models#Intent;action=delete;end",
                "app://skein/delete",
                "app://skein/ingest\n",
                "",
            )
        rejected.forEach { uri -> assertNull(uri, notificationDestination(link(uri))) }
        assertNull(notificationDestination(null))
        assertNull(notificationDestination(Intent(Intent.ACTION_MAIN)))
        assertNull(notificationDestination(link("app://skein/models").setAction(Intent.ACTION_SEND)))
        assertNull(notificationDestination(link("app://skein/models").setAction("delete")))
        // Extras can neither create an allowed route nor change an allowed route into an action/id.
        assertNull(notificationDestination(Intent(Intent.ACTION_MAIN).putExtra("destination", "models")))
        assertEquals(
            NotificationDestination.MODELS,
            notificationDestination(link("app://skein/models").putExtra("delete", "private document")),
        )
    }

    @Test
    fun `latest valid pending destination wins and hostile input does not replace it`() {
        val links = NotificationDeepLinks(UnlockManager(ScriptedVaultKeyProvider()))
        links.onCreate(link("app://skein/models"), restoring = false)
        links.onNewIntent(link("app://skein/ingest"))
        links.onNewIntent(link("app://skein/models?delete=true"))
        assertEquals(NotificationDestination.KNOWLEDGE, links.pending.value)
        links.clear()
        assertNull(links.pending.value)
    }

    @Test
    fun `retained instance never replays its initial intent and fresh restored instance drops it`() {
        val manager = UnlockManager(ScriptedVaultKeyProvider())
        val retained = NotificationDeepLinks(manager)
        retained.onCreate(link("app://skein/models"), restoring = false)
        retained.clear()
        retained.onCreate(link("app://skein/models"), restoring = true)
        assertNull(retained.pending.value)

        val restored = NotificationDeepLinks(manager)
        restored.onCreate(link("app://skein/models"), restoring = true)
        assertNull(restored.pending.value)
        restored.onNewIntent(link("app://skein/ingest"))
        assertEquals(NotificationDestination.KNOWLEDGE, restored.pending.value)
    }

    @Test
    fun `real lock clears stale pending without composition and a later locked request can wait`() {
        val manager = UnlockManager(ScriptedVaultKeyProvider())
        val links = NotificationDeepLinks(manager)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        runBlocking {
            manager.unlock(
                controller.get(),
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Unlock")
                    .setNegativeButtonText("Cancel")
                    .build(),
                VaultKeyProvider.Factor.BIOMETRIC,
            )
        }
        links.onNewIntent(link("app://skein/models"))
        controller.pause().stop()
        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }
        assertNull(links.pending.value)
        links.onNewIntent(link("app://skein/ingest"))
        assertEquals(NotificationDestination.KNOWLEDGE, links.pending.value)
        controller.destroy()
    }

    @Test
    fun `destroying retained owner clears pending memory`() {
        val manager = UnlockManager(ScriptedVaultKeyProvider())
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        val links =
            ViewModelProvider(owner, viewModelFactory { initializer { NotificationDeepLinks(manager) } })[
                NotificationDeepLinks::class.java,
            ]
        links.onNewIntent(link("app://skein/models"))
        owner.viewModelStore.clear()
        assertNull(links.pending.value)
    }

    private fun link(uri: String) = Intent(Intent.ACTION_MAIN, Uri.parse(uri))
}
