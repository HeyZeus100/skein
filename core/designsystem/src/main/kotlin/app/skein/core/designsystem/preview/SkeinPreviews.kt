// skein-xtov.23.20 (ML-1, docs/ux/MAC_UX_LAB_PLAN.md §2.2): the Skein
// multipreview annotations. Defined once here so every feature module's
// `@Preview` functions render at the measured Fold windows instead of
// hand-picked `widthDp`/`heightDp` pairs with no density
// (`AUDIT_SHELL.md` P2-07). Each `device = "spec:…"` string must equal the
// matching `app.skein.testing.ui.SkeinDevice` qualifier string (only the
// `w`/`h`/density parts — a landscape window is width > height, no
// `orientation=` needed); `:testing-ui`'s
// `SkeinPreviewAnnotationsMatchSkeinDeviceTest` parses both and fails on any
// drift. In `src/main`, not `src/debug`: annotation classes compile to
// nothing at runtime (no bytecode a release build ships), and today's
// `src/main` previews (`ChatScreenPreviews.kt`, `SettingsScreenPreviews.kt`)
// need to see them before ML-2 moves those files to `src/debug`.
package app.skein.core.designsystem.preview

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Configuration.UI_MODE_TYPE_NORMAL
import androidx.compose.ui.tooling.preview.Preview

private const val NIGHT = UI_MODE_NIGHT_YES or UI_MODE_TYPE_NORMAL

/** Day-to-day: the owner's two real windows (closed and open daily), light. */
@Preview(
    name = "fold-outer-524",
    group = "fold",
    device = "spec:width=524dp,height=1175dp,dpi=330",
    showBackground = true,
)
@Preview(
    name = "fold-inner-1007-land",
    group = "fold",
    device = "spec:width=1043dp,height=1007dp,dpi=330",
    showBackground = true,
)
annotation class SkeinFoldPreviews

/** Screenshot tier T1 (`UX_TEST_PLAN.md` §3.3), light. */
@Preview(name = "phone", group = "t1", device = "spec:width=360dp,height=800dp,dpi=320", showBackground = true)
@Preview(
    name = "fold-outer-524",
    group = "t1",
    device = "spec:width=524dp,height=1175dp,dpi=330",
    showBackground = true,
)
@Preview(name = "fold-inner-852", group = "t1", device = "spec:width=852dp,height=883dp,dpi=390", showBackground = true)
@Preview(
    name = "fold-inner-1007-land",
    group = "t1",
    device = "spec:width=1043dp,height=1007dp,dpi=330",
    showBackground = true,
)
annotation class SkeinDevicePreviews

/** T1 devices, dark (stacking two multipreview annotations gives their union, not a cross product). */
@Preview(
    name = "phone · dark",
    group = "t1-dark",
    device = "spec:width=360dp,height=800dp,dpi=320",
    uiMode = NIGHT,
    showBackground = true,
)
@Preview(
    name = "fold-outer-524 · dark",
    group = "t1-dark",
    device = "spec:width=524dp,height=1175dp,dpi=330",
    uiMode = NIGHT,
    showBackground = true,
)
@Preview(
    name = "fold-inner-852 · dark",
    group = "t1-dark",
    device = "spec:width=852dp,height=883dp,dpi=390",
    uiMode = NIGHT,
    showBackground = true,
)
@Preview(
    name = "fold-inner-1007-land · dark",
    group = "t1-dark",
    device = "spec:width=1043dp,height=1007dp,dpi=330",
    uiMode = NIGHT,
    showBackground = true,
)
annotation class SkeinDarkPreviews

/** Every Compact window, including compact height and the split-screen minimum. */
@Preview(
    name = "split-320",
    group = "compact",
    device = "spec:width=320dp,height=1007dp,dpi=330",
    showBackground = true,
)
@Preview(name = "phone", group = "compact", device = "spec:width=360dp,height=800dp,dpi=320", showBackground = true)
@Preview(
    name = "fold-outer-443",
    group = "compact",
    device = "spec:width=443dp,height=994dp,dpi=390",
    showBackground = true,
)
@Preview(
    name = "fold-outer-524",
    group = "compact",
    device = "spec:width=524dp,height=1175dp,dpi=330",
    showBackground = true,
)
@Preview(
    name = "fold-outer-443-land",
    group = "compact",
    device = "spec:width=994dp,height=443dp,dpi=390",
    showBackground = true,
)
@Preview(
    name = "fold-outer-524-land",
    group = "compact",
    device = "spec:width=1175dp,height=524dp,dpi=330",
    showBackground = true,
)
annotation class SkeinCompactPreviews

/** Every Expanded Fold window, plus the Medium and Large canaries. */
@Preview(
    name = "fold-inner-852",
    group = "wide",
    device = "spec:width=852dp,height=883dp,dpi=390",
    showBackground = true,
)
@Preview(
    name = "fold-inner-852-land",
    group = "wide",
    device = "spec:width=883dp,height=852dp,dpi=390",
    showBackground = true,
)
@Preview(
    name = "fold-inner-1007",
    group = "wide",
    device = "spec:width=1007dp,height=1043dp,dpi=330",
    showBackground = true,
)
@Preview(
    name = "fold-inner-1007-land",
    group = "wide",
    device = "spec:width=1043dp,height=1007dp,dpi=330",
    showBackground = true,
)
@Preview(name = "medium-791", group = "wide", device = "spec:width=791dp,height=820dp,dpi=420", showBackground = true)
@Preview(name = "large-1280", group = "wide", device = "spec:width=1280dp,height=800dp,dpi=320", showBackground = true)
annotation class SkeinWidePreviews

/** Text scaling on the narrowest real outer width (tier T3). */
@Preview(
    name = "fold-outer-443 · 100%",
    group = "font",
    device = "spec:width=443dp,height=994dp,dpi=390",
    fontScale = 1.0f,
    showBackground = true,
)
@Preview(
    name = "fold-outer-443 · 150%",
    group = "font",
    device = "spec:width=443dp,height=994dp,dpi=390",
    fontScale = 1.5f,
    showBackground = true,
)
@Preview(
    name = "fold-outer-443 · 200%",
    group = "font",
    device = "spec:width=443dp,height=994dp,dpi=390",
    fontScale = 2.0f,
    showBackground = true,
)
annotation class SkeinFontScalePreviews
