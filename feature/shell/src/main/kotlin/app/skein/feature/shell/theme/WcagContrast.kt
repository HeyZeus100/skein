package app.skein.feature.shell.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x relative-luminance contrast ratio, computed directly from packed
 * `0xAARRGGBB` values (see [SkeinColorHex]). Pure arithmetic — no Compose or
 * Android APIs — so it can run as a plain JVM unit test with no Robolectric.
 */
internal object WcagContrast {
    /** Contrast ratio of two colors, always ≥ 1.0 (order of arguments doesn't matter). */
    fun ratio(
        a: Long,
        b: Long,
    ): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = max(la, lb)
        val darker = min(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
