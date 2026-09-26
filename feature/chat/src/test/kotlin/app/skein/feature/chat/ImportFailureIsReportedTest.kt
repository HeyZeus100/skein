package app.skein.feature.chat

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import app.skein.feature.shell.theme.SkeinTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream

/**
 * UX-P0-12 / K-P0-7 (Stage H2 image half, skein-xtov.22): attaching an image
 * in chat threw `ImportServiceImpl.importImage`'s uncaught
 * `UnsupportedOperationException` and crashed the app. The picker no longer
 * offers images, and a failed import is reported instead of thrown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFailureIsReportedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `an image that reaches the importer is reported, not thrown, and the picker offers no images`() {
        val picked = Uri.parse("content://test/photo.jpg")
        shadowOf(composeRule.activity.contentResolver).registerInputStream(picked, ByteArrayInputStream(byteArrayOf(1)))
        val offered = mutableListOf<String>()
        val registryOwner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry =
                    object : ActivityResultRegistry() {
                        override fun <I, O> onLaunch(
                            requestCode: Int,
                            contract: ActivityResultContract<I, O>,
                            input: I,
                            options: ActivityOptionsCompat?,
                        ) {
                            offered += (input as Array<*>).map { it.toString() }
                            dispatchResult(requestCode, picked)
                        }
                    }
            }

        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                SkeinTheme {
                    ChatBottomBar(
                        isGenerating = false,
                        onSend = {},
                        onCancel = {},
                        wikilinkSuggest = { emptyList() },
                        onAttach = { _, _, _ -> throw UnsupportedOperationException("image import not implemented") },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ATTACH_BUTTON_TEST_TAG).performClick()
        composeRule.waitForIdle()

        assertThat(offered).isNotEmpty()
        assertThat(offered.filter { it == "*/*" || it.startsWith("image/") }).isEmpty()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(
            "Skein can't import images yet. Attach a text file or PDF.",
        )
    }
}
