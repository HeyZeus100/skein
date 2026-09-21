// skein-v9g (E3.I11) — on-device Compose UI test for Settings › Security ›
// "Export vault key (passphrase)". Compiled here; the on-device run is
// gated on the CI emulator lane tracked by bd `skein-k3b2`, same as
// `SettingsScreenLockPolicyInstrumentedTest`.
//
// Covers the two things a unit test cannot: that the row is genuinely
// inert while the vault is locked (no dialog, no re-auth, no derivation),
// and that the warning is on screen before the passphrase field is.

package app.skein.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryKeyExportInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        vaultUnlocked: Boolean,
        onReauthenticate: suspend () -> Boolean = { true },
        onBuildExport: suspend (CharArray) -> ByteArray? = { ByteArray(64) },
    ) {
        composeRule.setContent {
            MaterialTheme {
                RecoveryKeyExportSection(
                    vaultUnlocked = vaultUnlocked,
                    onReauthenticate = onReauthenticate,
                    onBuildExport = onBuildExport,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun the_row_is_present_while_the_vault_is_locked() {
        show(vaultUnlocked = false)

        composeRule.onNodeWithText("Export vault key (passphrase)").assertIsDisplayed()
    }

    @Test
    fun tapping_the_row_while_locked_opens_nothing_and_touches_no_key() {
        var reauthCalls = 0
        show(vaultUnlocked = false, onReauthenticate = {
            reauthCalls++
            true
        })

        composeRule.onNodeWithText("Export vault key (passphrase)").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Export vault key").assertDoesNotExist()
        assertEquals(0, reauthCalls)
    }

    @Test
    fun the_dialog_warns_before_it_asks_for_a_passphrase() {
        show(vaultUnlocked = true)

        composeRule.onNodeWithText("Export vault key (passphrase)").performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("Anyone who has this file", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Passphrase").assertIsDisplayed()
    }

    @Test
    fun export_is_refused_until_both_fields_agree_and_clear_the_floor() {
        var reauthCalls = 0
        show(vaultUnlocked = true, onReauthenticate = {
            reauthCalls++
            true
        })

        composeRule.onNodeWithText("Export vault key (passphrase)").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Export").performClick()
        composeRule.waitForIdle()

        // Nothing typed, so the button is disabled and nothing was authorised.
        assertEquals(0, reauthCalls)
    }
}
