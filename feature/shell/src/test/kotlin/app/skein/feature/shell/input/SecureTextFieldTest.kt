package app.skein.feature.shell.input

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.PlatformTextInputMethodTestOverride
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FIELD_TAG = "secureTextField"

/**
 * E3.I9 (threat model §9): asserts that focusing a [SecureTextField] sends
 * the platform IME machinery an [EditorInfo] with both
 * [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS] and (API 26+)
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] set.
 *
 * A real [android.view.inputmethod.InputMethodManager] never runs under
 * Robolectric, so this test substitutes a fake [PlatformTextInputSession] —
 * exactly the "test `InputConnection` interceptor" the acceptance criteria
 * calls for — via Compose UI test's own
 * [PlatformTextInputMethodTestOverride]. That override captures the
 * [PlatformTextInputMethodRequest] [SecureTextField] hands to the platform,
 * calls its real [PlatformTextInputMethodRequest.createInputConnection]
 * (exercising [SecureImeInterceptor] for real), and records the resulting
 * [EditorInfo] for assertion.
 *
 * Pinned to SDK 34 (bd memory `robolectric-sdk37-needs-java21`: SDK 35+
 * `android-all` jars need Java 21, this toolchain has Java 17).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureTextFieldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `focusing the field reports no-suggestions and no-personalized-learning to the IME`() {
        var capturedOutAttrs: EditorInfo? = null
        val fakeSession =
            object : PlatformTextInputSession {
                override val view: View
                    get() = composeRule.activity.window.decorView

                override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
                    val outAttrs = EditorInfo()
                    request.createInputConnection(outAttrs)
                    capturedOutAttrs = outAttrs
                    awaitCancellation()
                }
            }

        composeRule.setContent {
            PlatformTextInputMethodTestOverride(sessionHandler = fakeSession) {
                var text by remember { mutableStateOf("") }
                SecureTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        composeRule.onNodeWithTag(FIELD_TAG).requestFocus()
        composeRule.waitForIdle()

        val outAttrs = capturedOutAttrs
        assertNotNull("expected SecureTextField to have started an input session", outAttrs)
        assertTrue(
            "expected TYPE_TEXT_FLAG_NO_SUGGESTIONS in inputType",
            (outAttrs!!.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0,
        )
        assertTrue(
            "expected IME_FLAG_NO_PERSONALIZED_LEARNING in imeOptions",
            (outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
        )
    }

    @Test
    fun `applyNoLearningFlags sets both flags directly on a plain EditorInfo`() {
        val outAttrs = EditorInfo()

        applyNoLearningFlags(outAttrs)

        assertTrue(
            "expected TYPE_TEXT_FLAG_NO_SUGGESTIONS in inputType",
            (outAttrs.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0,
        )
        assertTrue(
            "expected IME_FLAG_NO_PERSONALIZED_LEARNING in imeOptions",
            (outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
        )
    }
}
