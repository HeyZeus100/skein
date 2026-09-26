// skein-xtov.23.7 (DS7): per-state, light/dark captures of the new
// `components` package. Not part of the `ux-baselines/` committed-golden
// convention (`ux-baselines/README.md` names seven feature modules;
// `:core:designsystem` isn't one of them) — these are component-level dev
// captures. `captureUx` composes the screen either way and only writes a
// file when a run explicitly passes `-Proborazzi.test.record` (or
// `compare`/`verify`), so a plain `test` run exercises every state harmlessly.
//
// `SkeinRenameDialog` has no capture here (ceiling, not an oversight):
// composing it under `@GraphicsMode(NATIVE)` — required for a real Roborazzi
// pixel capture — hangs `setContent` itself with
// `AppNotIdleException: Compose did not get idle after 662588 attempts in 60
// SECONDS`, before any interaction of this test's own runs and regardless of
// the field's initial selection (tried both fully-selected and collapsed).
// The one variable that differs from every other passing text-field capture
// in this repo (`ChatScreenshotTest`'s composer, `NoteTabEditorContrastTest`'s
// editor) is that this field lives inside an `AlertDialog`'s own Android
// window, not the host activity's — an `OutlinedTextField`-inside-a-`Dialog`
// combination this repo's Robolectric/Roborazzi setup has apparently never
// exercised before. `SkeinRenameDialogTest` (no `GraphicsMode` override,
// i.e. Robolectric's legacy renderer) already covers every behaviour this
// component has — confirm-disabled rules, the blank/unchanged messages,
// `maxLength`, both callbacks — so nothing here is untested, only unphotographed.
// Upgrade path: an on-device/instrumented capture (no Robolectric idling
// involved), or revisit once `RobolectricIdlingStrategy` handles a focused
// field inside a Dialog window.
package app.skein.core.designsystem.components.screenshots

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onRoot
import app.skein.core.designsystem.components.SkeinDestructiveDialog
import app.skein.core.designsystem.components.SkeinSnackbarHost
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeinOverlaysScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    @Test
    fun destructiveDialog() {
        composeRule.setContent {
            SkeinTheme {
                SkeinDestructiveDialog(
                    title = "Delete “Skein UX redesign”?",
                    consequence =
                        "This removes the conversation and its messages from Skein. " +
                            "Notes you saved from it are kept.",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "destructive-dialog")
    }

    @Test
    fun snackbarWithAction() {
        composeRule.setContent {
            SkeinTheme {
                val hostState = remember { SnackbarHostState() }
                SkeinSnackbarHost(hostState)
                LaunchedEffect(Unit) {
                    hostState.showSnackbar(message = "Deleted “Skein UX redesign”", actionLabel = "Undo")
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "snackbar-with-action")
    }

    @Test
    fun snackbarNoAction() {
        composeRule.setContent {
            SkeinTheme {
                val hostState = remember { SnackbarHostState() }
                SkeinSnackbarHost(hostState)
                LaunchedEffect(Unit) { hostState.showSnackbar(message = "Couldn't export “Fold launch plan”") }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "snackbar-no-action")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(
                arrayOf<Any>(UxSpec(SkeinDevice.PHONE, dark = false)),
                arrayOf<Any>(UxSpec(SkeinDevice.PHONE, dark = true)),
            )
    }
}
