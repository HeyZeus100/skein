// skein-xtov.23.19 (UT-5): self-test for the token-contrast helper, plus a
// check that it agrees with `:core:designsystem`'s own `SkeinColorContrastTest`
// on a real shipped pair — proof this reuses `WcagContrast`, not a
// reimplementation that happens to look similar.
package app.skein.testing.ui

import androidx.compose.ui.graphics.Color
import app.skein.core.designsystem.theme.SkeinColors
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ContrastAssertionsTest {
    @Test
    fun `fails a pair below its threshold`() {
        val check =
            ContrastCheck(
                foreground = Color(0xFFAAAAAA),
                background = Color(0xFFB0B0B0),
                minRatio = TEXT_CONTRAST_MIN,
                label = "near-identical greys",
            )

        val error = assertThrows(AssertionError::class.java) { assertContrast(listOf(check)) }

        assertThat(error.message).contains("near-identical greys")
    }

    @Test
    fun `passes a pair at or above its threshold`() {
        val check =
            ContrastCheck(
                foreground = Color.Black,
                background = Color.White,
                minRatio = TEXT_CONTRAST_MIN,
                label = "black on white",
            )

        assertContrast(listOf(check))
    }

    @Test
    fun `passes the real dark theme's body-text pair, reusing WcagContrast rather than reimplementing it`() {
        // Same pair as SkeinColorContrastTest's first PAIRS row, dark theme.
        val check =
            ContrastCheck(
                foreground = SkeinColors.dark.onSurface,
                background = SkeinColors.dark.surface,
                minRatio = TEXT_CONTRAST_MIN,
                label = "onSurface on surface (dark)",
            )

        assertContrast(listOf(check))
    }
}
