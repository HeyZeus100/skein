// skein-v9g (E3.I11) — JVM tests for the restore-from-export flow:
// `restoreWith`'s two-stage mapping and `VaultRestoreState`'s phases.
// One behaviour per test, AAA structure.

package app.skein.feature.shell.auth

import app.skein.core.vault.key.PassphraseKeyExport
import app.skein.core.vault.key.SetupResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultRestoreStateTest {
    private val master = ByteArray(32) { (it + 11).toByte() }
    private val passphrase = "restore-this-vault-now".toCharArray()

    private fun recoveryFile(): ByteArray = PassphraseKeyExport.export(master, passphrase)

    // ---- restoreWith ----------------------------------------------------

    @Test
    fun `a correct passphrase hands the recovered master to setup`() =
        runTest {
            // Arrange
            var handed: ByteArray? = null
            // Act
            restoreWith(recoveryFile(), passphrase) { existing ->
                handed = existing.copyOf()
                SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true)
            }
            // Assert
            assertTrue(master contentEquals handed!!)
        }

    @Test
    fun `a successful restore reports Provisioned with the StrongBox flag`() =
        runTest {
            // Arrange & Act
            val result =
                restoreWith(recoveryFile(), passphrase) {
                    SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true)
                }
            // Assert
            assertEquals(RestoreResult.Provisioned(strongBoxBacked = true), result)
        }

    @Test
    fun `a StrongBox fallback reports Provisioned with false`() =
        runTest {
            // Arrange & Act
            val result =
                restoreWith(recoveryFile(), passphrase) { SetupResult.StrongBoxUnavailableFallback(1) }
            // Assert
            assertEquals(RestoreResult.Provisioned(strongBoxBacked = false), result)
        }

    @Test
    fun `the recovered master is zeroed before restoreWith returns`() =
        runTest {
            // Arrange — capture the SAME array, not a copy
            var handed: ByteArray? = null
            // Act
            restoreWith(recoveryFile(), passphrase) { existing ->
                handed = existing
                SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true)
            }
            // Assert
            assertTrue(handed!!.all { it == 0.toByte() })
        }

    @Test
    fun `a wrong passphrase never reaches setup`() =
        runTest {
            // Arrange
            var setupCalls = 0
            // Act
            restoreWith(recoveryFile(), "not-the-passphrase!".toCharArray()) {
                setupCalls++
                SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true)
            }
            // Assert
            assertEquals(0, setupCalls)
        }

    @Test
    fun `a wrong passphrase reports WrongPassphrase`() =
        runTest {
            // Arrange & Act
            val result =
                restoreWith(recoveryFile(), "not-the-passphrase!".toCharArray()) {
                    SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true)
                }
            // Assert
            assertEquals(RestoreResult.WrongPassphrase, result)
        }

    @Test
    fun `a file that is not a recovery export reports UnreadableFile`() =
        runTest {
            // Arrange
            val notAnExport = "just some text".toByteArray()
            // Act
            val result =
                restoreWith(notAnExport, passphrase) {
                    SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = true)
                }
            // Assert
            assertEquals(RestoreResult.UnreadableFile, result)
        }

    @Test
    fun `a newer-format file reports UnsupportedFile`() =
        runTest {
            // Arrange
            val newer =
                String(recoveryFile(), Charsets.US_ASCII)
                    .replace("\"version\":1", "\"version\":9")
                    .toByteArray(Charsets.US_ASCII)
            // Act
            val result = restoreWith(newer, passphrase) { SetupResult.Success(1, true) }
            // Assert
            assertEquals(RestoreResult.UnsupportedFile, result)
        }

    @Test
    fun `an existing envelope reports AlreadyInitialised`() =
        runTest {
            // Arrange & Act
            val result = restoreWith(recoveryFile(), passphrase) { SetupResult.AlreadyInitialised }
            // Assert
            assertEquals(RestoreResult.AlreadyInitialised, result)
        }

    @Test
    fun `an unreadable envelope is routed to unlock, not to a retry`() =
        runTest {
            // Arrange — the same non-destructive routing VaultSetupState uses
            val unreadable = SetupResult.Failed(EnvelopeUnreadable.REASON_CORRUPT)
            // Act
            val result = restoreWith(recoveryFile(), passphrase) { unreadable }
            // Assert
            assertEquals(RestoreResult.AlreadyInitialised, result)
        }

    @Test
    fun `a cancelled wrap prompt reports Cancelled`() =
        runTest {
            // Arrange & Act
            val result = restoreWith(recoveryFile(), passphrase) { SetupResult.UserCancelled }
            // Assert
            assertEquals(RestoreResult.Cancelled, result)
        }

    // ---- the state machine ----------------------------------------------

    private fun state(
        scope: kotlinx.coroutines.CoroutineScope,
        setup: suspend (ByteArray) -> SetupResult = { SetupResult.Success(1, true) },
        onProvisioned: (Boolean) -> Unit = {},
        onAlreadyInitialised: () -> Unit = {},
    ) = VaultRestoreState(scope, setup, onProvisioned, onAlreadyInitialised)

    @Test
    fun `the flow starts Idle`() =
        runTest {
            // Arrange & Act
            val restore = state(this)
            // Assert
            assertEquals(VaultRestoreUiState.Idle, restore.uiState)
        }

    @Test
    fun `beginning a file choice moves to ChoosingFile`() =
        runTest {
            // Arrange
            val restore = state(this)
            // Act
            restore.beginFileChoice()
            // Assert
            assertEquals(VaultRestoreUiState.ChoosingFile, restore.uiState)
        }

    @Test
    fun `a dismissed picker returns to Idle`() =
        runTest {
            // Arrange
            val restore = state(this)
            restore.beginFileChoice()
            // Act
            restore.onFileChoiceCancelled()
            // Assert
            assertEquals(VaultRestoreUiState.Idle, restore.uiState)
        }

    @Test
    fun `a chosen file moves to passphrase entry`() =
        runTest {
            // Arrange
            val restore = state(this)
            // Act
            restore.onFileChosen(recoveryFile())
            // Assert
            assertTrue(restore.uiState is VaultRestoreUiState.EnteringPassphrase)
        }

    @Test
    fun `submit is disabled until something is typed`() =
        runTest {
            // Arrange
            val restore = state(this)
            // Act
            restore.onFileChosen(recoveryFile())
            // Assert
            assertFalse(restore.canSubmit)
        }

    @Test
    fun `submit is enabled once a file and a passphrase are in hand`() =
        runTest {
            // Arrange
            val restore = state(this)
            restore.onFileChosen(recoveryFile())
            // Act
            restore.passphrase = String(passphrase)
            // Assert
            assertTrue(restore.canSubmit)
        }

    @Test
    fun `a successful submit calls onProvisioned`() =
        runTest {
            // Arrange
            var provisioned: Boolean? = null
            val restore = state(this, onProvisioned = { provisioned = it })
            restore.onFileChosen(recoveryFile())
            restore.passphrase = String(passphrase)
            // Act
            restore.submit()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(true, provisioned)
        }

    @Test
    fun `a successful submit clears the typed passphrase`() =
        runTest {
            // Arrange
            val restore = state(this)
            restore.onFileChosen(recoveryFile())
            restore.passphrase = String(passphrase)
            // Act
            restore.submit()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals("", restore.passphrase)
        }

    @Test
    fun `a wrong passphrase returns to entry with a message`() =
        runTest {
            // Arrange
            val restore = state(this)
            restore.onFileChosen(recoveryFile())
            restore.passphrase = "definitely-wrong-one"
            // Act
            restore.submit()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(
                VaultRestoreUiState.EnteringPassphrase(VaultRestoreState.WRONG_PASSPHRASE_MESSAGE),
                restore.uiState,
            )
        }

    @Test
    fun `an already-initialised device routes to unlock`() =
        runTest {
            // Arrange
            var routed = false
            val restore =
                state(
                    this@runTest,
                    setup = { SetupResult.AlreadyInitialised },
                    onAlreadyInitialised = { routed = true },
                )
            restore.onFileChosen(recoveryFile())
            restore.passphrase = String(passphrase)
            // Act
            restore.submit()
            testScheduler.advanceUntilIdle()
            // Assert
            assertTrue(routed)
        }

    @Test
    fun `a second submit while one is in flight does not run setup twice`() =
        runTest {
            // Arrange
            var setupCalls = 0
            val restore =
                state(
                    this@runTest,
                    setup = {
                        setupCalls++
                        SetupResult.Success(1, true)
                    },
                )
            restore.onFileChosen(recoveryFile())
            restore.passphrase = String(passphrase)
            // Act
            restore.submit()
            restore.submit()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(1, setupCalls)
        }

    @Test
    fun `cancelling from passphrase entry returns to Idle`() =
        runTest {
            // Arrange
            val restore = state(this)
            restore.onFileChosen(recoveryFile())
            restore.passphrase = String(passphrase)
            // Act
            restore.cancel()
            // Assert
            assertEquals(VaultRestoreUiState.Idle, restore.uiState)
        }

    @Test
    fun `no failure message carries the passphrase`() =
        runTest {
            // Arrange
            val restore = state(this)
            restore.onFileChosen(recoveryFile())
            restore.passphrase = "definitely-wrong-one"
            // Act
            restore.submit()
            testScheduler.advanceUntilIdle()
            // Assert
            val message = (restore.uiState as VaultRestoreUiState.EnteringPassphrase).message
            assertNull(message?.let { if (it.contains("definitely-wrong-one")) it else null })
        }
}
