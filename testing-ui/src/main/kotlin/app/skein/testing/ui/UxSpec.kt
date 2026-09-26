// skein-xtov.23.15 (UT-1) — moved from the seven per-module copies of
// `UxScreenshots.kt` (docs/ux/research/ROBORAZZI_SPIKE.md); logic unchanged
// except that the closed Fold ("FOLD_OUTER") now names two real sizes
// (skein-xtov.23.14 / UT-0), so every place that captured one image on the
// cover display now captures two.
// skein-xtov.23.16 (UT-2) — `suffix` replaced by [nameSuffix]/[theme]/
// [fontScaleTag], the §3.5 naming grammar (`<state-id>__<theme>__fs<NNN>`,
// always both axes present, never the old conditional `-dark`/`-font150`).
package app.skein.testing.ui

import org.junit.Assume.assumeTrue
import kotlin.math.roundToInt

/** One capture configuration: a device, light/dark, and a font scale. */
data class UxSpec(
    val device: SkeinDevice,
    val dark: Boolean,
    val fontScale: Float = 1f,
) {
    /** `light` | `dark` (docs/ux/UX_TEST_PLAN.md §3.5). */
    val theme: String get() = if (dark) "dark" else "light"

    /** `fs100` | `fs150` | `fs200` (§3.5; every font scale this plan uses is a whole percent). */
    val fontScaleTag: String get() = "fs${(fontScale * 100).roundToInt()}"

    /**
     * The `__<theme>__fs<NNN>` tail every capture's file name carries, always
     * both axes (unlike the spike's `-dark`/`-font150`, which omitted the
     * default). No `mod` support yet — no screenshot test captures one
     * (§3.2's `ime`/`bars`/`rtl`/`tabletop`/`book`/`kbd`); add a `mod: String?`
     * parameter to [captureUx] and append `__<mod>` here when the first one does.
     */
    val nameSuffix: String get() = "__${theme}__$fontScaleTag"

    /** Phone and both Fold cover sizes: the single-pane, "closed Fold" postures. */
    val isFolded: Boolean
        get() =
            device == SkeinDevice.PHONE ||
                device == SkeinDevice.FOLD_OUTER_443 ||
                device == SkeinDevice.FOLD_OUTER_524

    /** One of the five main devices ([DEFAULT_DEVICES]) at font scale 1. */
    val isStandard: Boolean get() = fontScale == 1f && device in DEFAULT_DEVICES

    override fun toString(): String = device.dir + nameSuffix
}

/**
 * The devices every screenshot test captures by default: unchanged from the
 * spike's four (`phone`, the Fold cover, the Fold inner portrait and
 * landscape) except that the cover is now both measured sizes
 * (skein-xtov.23.14).
 */
val DEFAULT_DEVICES: List<SkeinDevice> =
    listOf(
        SkeinDevice.PHONE,
        SkeinDevice.FOLD_OUTER_443,
        SkeinDevice.FOLD_OUTER_524,
        SkeinDevice.FOLD_INNER_1007,
        SkeinDevice.FOLD_INNER_1007_LAND,
    )

/** Skips this parameterisation unless it is one of the [DEFAULT_DEVICES] at font scale 1. */
fun UxSpec.assumeStandard() = assumeTrue(isStandard)

/** [DEFAULT_DEVICES], light and dark, plus [extra], as `ParameterizedRobolectricTestRunner` parameters. */
fun uxSpecs(vararg extra: UxSpec): List<Array<Any>> =
    (DEFAULT_DEVICES.flatMap { listOf(UxSpec(it, dark = false), UxSpec(it, dark = true)) } + extra)
        .map { arrayOf<Any>(it) }

/** Both Fold cover sizes at font scale 1.5, light and dark (skein-xtov.23.14: was the cover only). */
val UX_FONT_150: Array<UxSpec> =
    arrayOf(
        UxSpec(SkeinDevice.FOLD_OUTER_443, dark = false, 1.5f),
        UxSpec(SkeinDevice.FOLD_OUTER_443, dark = true, 1.5f),
        UxSpec(SkeinDevice.FOLD_OUTER_524, dark = false, 1.5f),
        UxSpec(SkeinDevice.FOLD_OUTER_524, dark = true, 1.5f),
    )
