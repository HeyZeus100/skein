// skein-xtov.24.2 (AL-03): `windowSoftInputMode="adjustResize"` only pays off
// if Compose's own inset plumbing reacts once the window shrinks for the
// IME. `docs/ux/ADAPTIVE_LAYOUT_SPEC.md` §6.2/§6.7 says the shell root
// (`SkeinApp.kt`'s `.windowInsetsPadding(WindowInsets.safeDrawing)`) already
// unions `ime()` into the insets every pane — including this one — is
// padded by, so no per-screen IME handling is added here (that per-pane
// ownership move is AL-11; see `EdgeToEdgeSurface`'s KDoc for the same
// modifier pair reused below).
//
// Robolectric never runs the platform's real IME/`InputMethodManager`, so it
// cannot exercise `adjustResize` itself resizing the window — that is
// Test D, verified on the owner's Fold (`docs/ux/ANDROID_SKILLS_ASSESSMENT.md`
// §6.4: "IME correctness is ultimately a hardware-runner check"). What CAN
// be exercised on the JVM, and what this test pins down, is the Compose-side
// contract `adjustResize` depends on: once an IME inset is dispatched to the
// window (the same `ViewCompat.dispatchApplyWindowInsets` path
// `MainActivityComposeTest`'s status-bar test uses), the composer must stay
// fully above it and the transcript must shrink to make room — never get
// covered. Run at both Fold displays (`:testing-ui`'s `SkeinDevice`) since
// the spec calls out short-window IME geometry as its own risk (§6.3).
package app.skein.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.skein.core.model.Document
import app.skein.core.model.TokenBudget
import app.skein.feature.shell.layout.EdgeToEdgeSurface
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.testing.FakeRetrievalService
import app.skein.testing.fakeVault
import app.skein.testing.scriptedEngine
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class ChatComposerImeInsetTest(
    private val device: SkeinDevice,
) {
    // order = 0: the qualifiers must land before the compose rule's
    // ActivityScenarioRule launches the host activity (same reasoning as
    // ChatScreenshotTest's UxDeviceRule — Robolectric only picks up a
    // display-size change made before the window exists).
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(UxSpec(device, dark = false))

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `composer clears the IME and the transcript shrinks instead of being covered`() {
        lateinit var doc: Document
        val vault = fakeVault { doc = chat("Chat") }
        val pipeline =
            SendPipeline(
                vaultRepository = vault,
                retrievalService = FakeRetrievalService(emptyList()),
                promptAssembler = SimplePromptAssembler(),
                engine = scriptedEngine(),
                personaProvider = { null },
                budgetFor = {
                    _,
                    _,
                    ->
                    TokenBudget(contextLength = 16_384, reserveForAnswer = 1024, maxRetrievedTokens = 3072)
                },
                countTokens = { it.length / 4 },
            )

        composeRule.setContent {
            SkeinTheme {
                // The same modifier pair `SkeinApp`'s shell root applies
                // today (`.fillMaxSize().windowInsetsPadding(WindowInsets.
                // safeDrawing)`) — reused via `EdgeToEdgeSurface` rather than
                // re-derived, so this test exercises the real inset-owning
                // layer instead of a stand-in that could drift from it.
                EdgeToEdgeSurface { insetModifier ->
                    ChatScreen(
                        docId = doc.id,
                        vaultRepository = vault,
                        sendPipeline = pipeline,
                        tabController = TabController { _, _, _ -> "tab" },
                        wikilinkSuggest = { emptyList() },
                        modifier = insetModifier,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val decorView = composeRule.activity.window.decorView
        val windowHeightPx = decorView.height
        check(windowHeightPx > 0) { "decorView reported no height for $device — Robolectric window not laid out" }
        val imeTopPx = windowHeightPx - IME_INSET_PX

        // Baseline, no IME dispatched yet: both the composer and the
        // transcript extend well past where a keyboard this tall would
        // start — exactly what a real keyboard would cover if nothing
        // reacted to it (the Fold-smoke report this bead cites: "the status
        // row was hidden under the keyboard"). Asserted so the "clears the
        // IME" checks below actually prove something moved, rather than the
        // composer having always been high enough by coincidence.
        val composerBottomBeforeIme =
            composeRule
                .onNodeWithTag(COMPOSER_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val transcriptBottomBeforeIme =
            composeRule
                .onNodeWithTag(MESSAGE_LIST_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertTrue(
            "test setup: the composer (bottom=$composerBottomBeforeIme px) should sit below where the " +
                "simulated IME will start (top=$imeTopPx px) on $device, or this test proves nothing " +
                "once the inset is dispatched",
            composerBottomBeforeIme > imeTopPx,
        )

        // Simulate the IME opening: the same ViewCompat.dispatchApplyWindowInsets
        // path a real WindowInsetsAnimation/layout pass uses, and the one
        // MainActivityComposeTest's own status-bar inset test relies on —
        // Robolectric never generates a real IME inset on its own.
        composeRule.runOnUiThread {
            val imeInsets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, IME_INSET_PX))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(decorView, imeInsets)
        }
        composeRule.waitForIdle()

        val composerBottomAfterIme =
            composeRule
                .onNodeWithTag(COMPOSER_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val transcriptBottomAfterIme =
            composeRule
                .onNodeWithTag(MESSAGE_LIST_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.bottom

        assertTrue(
            "composer bottom ($composerBottomAfterIme px) must clear the simulated IME top " +
                "($imeTopPx px) on $device — it must never sit under the keyboard",
            composerBottomAfterIme <= imeTopPx + PX_TOLERANCE,
        )
        assertTrue(
            "transcript bottom ($transcriptBottomAfterIme px) must also clear the simulated IME top " +
                "($imeTopPx px) on $device — a covered transcript is as wrong as a covered composer",
            transcriptBottomAfterIme <= imeTopPx + PX_TOLERANCE,
        )
        assertTrue(
            "transcript must actually shrink once the IME rises on $device (before=" +
                "$transcriptBottomBeforeIme px, after=$transcriptBottomAfterIme px), not stay the same " +
                "size and end up underneath the keyboard",
            transcriptBottomAfterIme < transcriptBottomBeforeIme - PX_TOLERANCE,
        )
    }

    companion object {
        /** A plausible keyboard height in px, comfortably under both fold heights (2151-2423 px here). */
        const val IME_INSET_PX = 700

        /** Layout-pass rounding slack, not a tolerance for a real miss. */
        const val PX_TOLERANCE = 4

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> =
            listOf(SkeinDevice.FOLD_OUTER_524, SkeinDevice.FOLD_INNER_1007).map { arrayOf<Any>(it) }
    }
}
