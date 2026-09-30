package app.skein

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.session.UnlockState
import app.skein.feature.shell.testing.ShellTestTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity routing over scripted key loss; every file below is a Robolectric fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
class MainActivityKeyMaterialGoneTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestSkeinApplication
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `missing key does not retry authenticate open setup or delete data`() {
        val calls = AtomicInteger()
        val fixtures = createFixtures()
        app.keyProvider.nextUnlock = {
            calls.incrementAndGet()
            UnlockResult.KeyMaterialGone(VaultKeyProvider.Factor.BIOMETRIC)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            compose.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).assertExists()
            compose.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RETRY_BUTTON).assertDoesNotExist()
            compose.onNodeWithTag(ShellTestTags.VAULT_SETUP_ROOT).assertDoesNotExist()
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_ROOT).assertDoesNotExist()
            compose.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertDoesNotExist()
            compose.waitForIdle()

            assertEquals(1, calls.get())
            assertEquals(0, app.keyProvider.setupCalls.get())
            assertEquals(UnlockState.Locked, app.vault.unlockManager.state.value)
            assertNull(app.vault.session.value)
            assertNull(app.keyProvider.currentKey())
            assertTrue(app.deletedKeystoreAliases.isEmpty())
            assertFixturesIntact(fixtures)
        }
    }

    @Test
    fun `key-loss reset explains the reason and cancel at either step preserves every fixture`() {
        val fixtures = createFixtures()
        app.keyProvider.nextUnlock = { UnlockResult.KeyMaterialGone(VaultKeyProvider.Factor.BIOMETRIC) }

        ActivityScenario.launch(MainActivity::class.java).use {
            openReset()
            compose.onNodeWithText("fingerprint or face key is unavailable", substring = true).assertExists()
            compose.onNodeWithText("key file cannot be read", substring = true).assertDoesNotExist()
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CONTINUE_BUTTON).assertIsNotEnabled()
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CANCEL_BUTTON).performClick()
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            assertFixturesIntact(fixtures)

            openReset()
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CONFIRM_FIELD).performTextInput("RESET")
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CONTINUE_BUTTON).performClick()
            awaitTag(ShellTestTags.VAULT_RESET_FINAL_BUTTON)
            assertTrue(app.deletedKeystoreAliases.isEmpty())
            assertFixturesIntact(fixtures)
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CANCEL_BUTTON).performClick()
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            assertTrue(app.deletedKeystoreAliases.isEmpty())
            assertFixturesIntact(fixtures)

            // A later different failure must not inherit the missing-key explanation.
            openReset()
            app.keyProvider.nextUnlock = { UnlockResult.Failed("key envelope corrupt") }
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CANCEL_BUTTON).performClick()
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)
            openReset()
            compose.onNodeWithText("key file cannot be read", substring = true).assertExists()
            compose.onNodeWithText("fingerprint or face key is unavailable", substring = true).assertDoesNotExist()
            assertFixturesIntact(fixtures)
        }
    }

    @Test
    fun `only final key-loss reset confirmation deletes vault files and keeps model and preferences`() {
        val fixtures = createFixtures()
        app.keyProvider.nextUnlock = { UnlockResult.KeyMaterialGone(VaultKeyProvider.Factor.DEVICE_CREDENTIAL) }

        ActivityScenario.launch(MainActivity::class.java).use {
            openReset()
            compose.onNodeWithText("device credential key is unavailable", substring = true).assertExists()
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CONFIRM_FIELD).performTextInput("RESET")
            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_CONTINUE_BUTTON).performClick()
            awaitTag(ShellTestTags.VAULT_RESET_FINAL_BUTTON)
            assertFixturesIntact(fixtures)
            assertTrue(app.deletedKeystoreAliases.isEmpty())

            compose.onNodeWithTag(ShellTestTags.VAULT_RESET_FINAL_BUTTON).performClick()
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            fixtures.forEach { (file, bytes) ->
                if (file.path.contains("/models/") || file.path.contains("/datastore/")) {
                    assertArrayEquals(bytes, file.readBytes())
                } else {
                    assertFalse(file.path, file.exists())
                }
            }
            assertEquals(2, app.deletedKeystoreAliases.size)
            assertEquals(0, app.keyProvider.setupCalls.get())
        }
    }

    private fun openReset() {
        awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON)
        compose.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_RESET_BUTTON).performClick()
        awaitTag(ShellTestTags.VAULT_RESET_ROOT)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun createFixtures(): Map<File, ByteArray> =
        listOf(
            File(app.filesDir, "keys/key-envelope.v1"),
            File(app.filesDir, "vault.db"),
            File(app.filesDir, "attachments/disposable-fixture"),
            File(app.cacheDir, "staging_export/disposable-fixture"),
            File(app.filesDir, "models/disposable-model"),
            File(app.filesDir, "datastore/disposable-preference"),
        ).associateWith { file ->
            val bytes = "synthetic key-loss fixture: ${file.name}".toByteArray()
            file.parentFile!!.mkdirs()
            file.writeBytes(bytes)
            bytes
        }

    private fun assertFixturesIntact(fixtures: Map<File, ByteArray>) {
        fixtures.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
    }
}
