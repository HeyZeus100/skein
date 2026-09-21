// skein-v9g (E3.I11) — JVM tests for the Settings › Security export flow's
// gates and sequencing. One behaviour per test, AAA structure.

package app.skein.feature.settings

import app.skein.core.vault.key.PassphraseStrength
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryExportStateTest {
    private val goodPassphrase = "export-this-key-please"
    private val envelope = ByteArray(96) { (it + 1).toByte() }

    private fun state(
        scope: CoroutineScope,
        reauth: suspend () -> Boolean = { true },
        build: suspend (CharArray) -> ByteArray? = { envelope },
        onReady: (ByteArray, String) -> Unit = { _, _ -> },
    ) = RecoveryExportState(
        scope = scope,
        onReauthenticate = reauth,
        onBuildExport = build,
        onEnvelopeReady = onReady,
        suggestedFileName = { "skein-recovery-2026-09-21.json" },
    )

    // ---- gates -----------------------------------------------------------

    @Test
    fun `the dialog starts closed`() =
        runTest {
            // Arrange & Act
            val export = state(this)
            // Assert
            assertEquals(RecoveryExportUiState.Closed, export.uiState)
        }

    @Test
    fun `opening moves to passphrase entry`() =
        runTest {
            // Arrange
            val export = state(this)
            // Act
            export.open()
            // Assert
            assertTrue(export.uiState is RecoveryExportUiState.Entering)
        }

    @Test
    fun `a passphrase below the floor cannot be exported`() =
        runTest {
            // Arrange
            val export = state(this)
            export.open()
            // Act
            export.passphrase = "short"
            export.confirmation = "short"
            // Assert
            assertFalse(export.canExport)
        }

    @Test
    fun `a mismatched confirmation cannot be exported`() =
        runTest {
            // Arrange
            val export = state(this)
            export.open()
            // Act
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase + "x"
            // Assert
            assertFalse(export.canExport)
        }

    @Test
    fun `a long matching passphrase can be exported`() =
        runTest {
            // Arrange
            val export = state(this)
            export.open()
            // Act
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Assert
            assertTrue(export.canExport)
        }

    @Test
    fun `the strength meter reports the floor for a short passphrase`() =
        runTest {
            // Arrange
            val export = state(this)
            // Act
            export.passphrase = "abc"
            // Assert
            assertEquals(PassphraseStrength.Score.TOO_SHORT, export.strength)
        }

    // ---- sequencing ------------------------------------------------------

    @Test
    fun `export re-authenticates before building anything`() =
        runTest {
            // Arrange
            val order = mutableListOf<String>()
            val export =
                state(
                    this@runTest,
                    reauth = {
                        order += "reauth"
                        true
                    },
                    build = {
                        order += "build"
                        envelope
                    },
                )
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(listOf("reauth", "build"), order)
        }

    @Test
    fun `a refused re-auth never reaches the key`() =
        runTest {
            // Arrange
            var buildCalls = 0
            val export =
                state(
                    this@runTest,
                    reauth = { false },
                    build = {
                        buildCalls++
                        envelope
                    },
                )
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(0, buildCalls)
        }

    @Test
    fun `a refused re-auth reports it without exporting`() =
        runTest {
            // Arrange
            val export = state(this@runTest, reauth = { false })
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(
                RecoveryExportUiState.Entering(RecoveryExportState.REAUTH_FAILED_MESSAGE),
                export.uiState,
            )
        }

    @Test
    fun `a locked vault is reported as a refusal, not a failure`() =
        runTest {
            // Arrange
            val export = state(this@runTest, build = { null })
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(
                RecoveryExportUiState.Entering(RecoveryExportState.LOCKED_MESSAGE),
                export.uiState,
            )
        }

    @Test
    fun `the built envelope is handed to the destination picker`() =
        runTest {
            // Arrange
            var handed: ByteArray? = null
            val export = state(this@runTest, onReady = { bytes, _ -> handed = bytes })
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertTrue(envelope contentEquals handed!!)
        }

    @Test
    fun `the suggested file name follows the skein-recovery-date convention`() =
        runTest {
            // Arrange
            var name: String? = null
            val export = state(this@runTest, onReady = { _, suggested -> name = suggested })
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals("skein-recovery-2026-09-21.json", name)
        }

    @Test
    fun `the default file name matches the documented pattern`() {
        // Arrange & Act
        val name = defaultRecoveryFileName()
        // Assert
        assertTrue(name.matches(Regex("""skein-recovery-\d{4}-\d{2}-\d{2}\.json""")))
    }

    @Test
    fun `the passphrase is cleared once the envelope is built`() =
        runTest {
            // Arrange
            val export = state(this@runTest)
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals("", export.passphrase)
        }

    @Test
    fun `a failed re-auth clears the passphrase too`() =
        runTest {
            // Arrange
            val export = state(this@runTest, reauth = { false })
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals("", export.passphrase)
        }

    @Test
    fun `a second export while one is in flight does not re-authenticate twice`() =
        runTest {
            // Arrange
            var reauthCalls = 0
            val export =
                state(
                    this@runTest,
                    reauth = {
                        reauthCalls++
                        true
                    },
                )
            export.open()
            export.passphrase = goodPassphrase
            export.confirmation = goodPassphrase
            // Act
            export.export()
            export.export()
            testScheduler.advanceUntilIdle()
            // Assert
            assertEquals(1, reauthCalls)
        }

    @Test
    fun `a written file reports Saved`() =
        runTest {
            // Arrange
            val export = state(this)
            // Act
            export.onSaved()
            // Assert
            assertEquals(RecoveryExportUiState.Saved, export.uiState)
        }

    @Test
    fun `a dismissed picker closes the dialog without a message`() =
        runTest {
            // Arrange
            val export = state(this)
            // Act
            export.onSaveCancelled()
            // Assert
            assertEquals(RecoveryExportUiState.Closed, export.uiState)
        }

    @Test
    fun `an unwritable destination returns to entry with a message`() =
        runTest {
            // Arrange
            val export = state(this)
            // Act
            export.onSaveFailed()
            // Assert
            assertEquals(
                RecoveryExportUiState.Entering(RecoveryExportState.WRITE_FAILED_MESSAGE),
                export.uiState,
            )
        }

    @Test
    fun `dismissing drops what was typed`() =
        runTest {
            // Arrange
            val export = state(this)
            export.open()
            export.passphrase = goodPassphrase
            // Act
            export.dismiss()
            // Assert
            assertEquals("", export.passphrase)
        }

    @Test
    fun `no message carries the passphrase`() =
        runTest {
            // Arrange & Act
            val messages =
                listOf(
                    RecoveryExportState.REAUTH_FAILED_MESSAGE,
                    RecoveryExportState.LOCKED_MESSAGE,
                    RecoveryExportState.WRITE_FAILED_MESSAGE,
                    RecoveryExportState.FAILED_MESSAGE,
                )
            // Assert
            assertTrue(messages.none { it.contains(goodPassphrase) })
        }
}
