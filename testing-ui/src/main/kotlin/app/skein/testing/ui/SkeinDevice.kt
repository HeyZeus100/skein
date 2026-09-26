// skein-xtov.23.14 (UT-0) / skein-xtov.23.15 (UT-1) — the measured device
// matrix (docs/ux/UX_TEST_PLAN.md §3.1), replacing the spike's provisional
// `FOLD_OUTER` (`w411dp-h923dp-port-420dpi`, `docs/ux/research/ROBORAZZI_SPIKE.md`
// §5) and the 1 dp narrower `FOLD_INNER`/`FOLD_LANDSCAPE`. Every screenshot
// test in every feature module shares this one table (`:testing-ui`) instead
// of a per-module copy.
package app.skein.testing.ui

/**
 * The device sizes UX captures are taken at (dp = px ÷ (dpi ÷ 160), rounded
 * as WindowManager rounds). Keys carry the portrait dp width so nobody has
 * to remember which density a name meant.
 *
 * The Fold outer (cover) display is 1080×2424 px: **443×994 dp at the stock
 * 390 dpi** ([FOLD_OUTER_443]) and **524×1175 dp at the owner's forced 330
 * dpi** ([FOLD_OUTER_524], measured while folded — the owner's real closed
 * Fold). The inner display is 2076×2152 px; the owner's forced density
 * override is 330, giving the **measured** 1007×1043 dp ([FOLD_INNER_1007])
 * and its landscape rotation ([FOLD_INNER_1007_LAND]). [FOLD_INNER_852] is
 * the stock 390 dpi profile (Android Studio's `pixel_9_pro_fold` =
 * Roborazzi's `RobolectricDeviceQualifiers.Pixel9ProFold`) — unchanged by
 * this correction.
 */
enum class SkeinDevice(
    val dir: String,
    val qualifiers: String,
) {
    /** Narrowest phone; unchanged from the spike. */
    PHONE("phone", "w360dp-h800dp-port-xhdpi"),

    /** Android's split-screen minimum on the inner display; header/chip stress only. */
    SPLIT_320("split-320", "w320dp-h1007dp-port-330dpi"),

    /** Closed Fold at stock density — the narrowest real outer width. */
    FOLD_OUTER_443("fold-outer-443", "w443dp-h994dp-port-390dpi"),

    /** The owner's closed Fold (measured while folded, forced 330 dpi). */
    FOLD_OUTER_524("fold-outer-524", "w524dp-h1175dp-port-330dpi"),

    /** Closed Fold landscape at stock density — compact height, must stay single-pane. */
    FOLD_OUTER_443_LAND("fold-outer-443-land", "w994dp-h443dp-land-390dpi"),

    /** Closed Fold landscape at the owner's density — the 600 dp height gate's real case. */
    FOLD_OUTER_524_LAND("fold-outer-524-land", "w1175dp-h524dp-land-330dpi"),

    /** Stock-density open Fold; the tightest two-pane layout (12 dp over the breakpoint). */
    FOLD_INNER_852("fold-inner-852", "w852dp-h883dp-port-390dpi"),

    /** Stock-density open Fold, landscape; tabletop posture happens here. */
    FOLD_INNER_852_LAND("fold-inner-852-land", "w883dp-h852dp-land-390dpi"),

    /** The owner's open Fold, portrait (measured, corrected from the spike's 1006 dp). */
    FOLD_INNER_1007("fold-inner-1007", "w1007dp-h1043dp-port-330dpi"),

    /** The owner's daily configuration (measured, corrected from the spike's 1006 dp). */
    FOLD_INNER_1007_LAND("fold-inner-1007-land", "w1043dp-h1007dp-land-330dpi"),

    /** Medium canary: one pane plus rail (a larger Display size, hypothetical). */
    MEDIUM_791("medium-791", "w791dp-h820dp-port-420dpi"),

    /** The only three-pane tier. */
    LARGE_1280("large-1280", "w1280dp-h800dp-land-xhdpi"),
}
