// skein-xtov.23.19 (UT-5, docs/ux/UX_TEST_PLAN.md §9 / §14): semantics-tree
// accessibility sweep assertions, so every feature module's screen tests can
// run the same checks instead of writing them ad hoc per screen (compare
// `feature/settings/.../SettingsNoDeadControlsTest.kt`, a one-off before this
// bead). Each `assert*` throws a plain `AssertionError` naming every
// offending node (test tag / text / content description / bounds), matching
// how Compose's own `assert*` extensions fail.
package app.skein.testing.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val DEFAULT_MIN_TOUCH_TARGET_DP = 48

/** DESIGN_SYSTEM.md §7.1 / UX_TEST_PLAN.md §9: the default touch-target floor. */
val MIN_TOUCH_TARGET: Dp = DEFAULT_MIN_TOUCH_TARGET_DP.dp

/**
 * Every clickable or toggleable node's *touch* bounds — not its visual size —
 * must be at least [min] on each side. Reads [SemanticsNode.touchBoundsInRoot],
 * the same property `assertTouchHeightIsEqualTo` reads, so a
 * `Modifier.minimumInteractiveComponentSize()` (or any other touch-bounds
 * expansion) is honoured for free: this helper never needs to special-case
 * that modifier, only the property it changes.
 *
 * Throws [AssertionError] naming every node smaller than [min].
 */
fun SemanticsNodeInteractionsProvider.assertTouchTargets(min: Dp = MIN_TOUCH_TARGET) {
    val actionable = hasClickAction().or(isToggleable())
    val nodes = onAllNodes(actionable, useUnmergedTree = true).fetchSemanticsNodes()
    val violations =
        nodes.mapNotNull { node ->
            val density = node.layoutInfo.density
            val bounds = node.touchBoundsInRoot
            val width = with(density) { bounds.width.toDp() }
            val height = with(density) { bounds.height.toDp() }
            if (width < min || height < min) {
                "${node.describe()} touch bounds ${width.value}x${height.value}dp, need >= ${min.value}dp"
            } else {
                null
            }
        }
    failIfAny("assertTouchTargets", violations)
}

private val LETTER = Regex("\\p{L}")

private val hasLongClickAction =
    SemanticsMatcher("has OnLongClick action") { it.config.contains(SemanticsActions.OnLongClick) }

private val hasCustomAction =
    SemanticsMatcher("has a custom accessibility action") {
        it.config.getOrNull(SemanticsActions.CustomActions)?.isNotEmpty() == true
    }

/**
 * Every node with an `OnClick`, `OnLongClick` or custom accessibility action
 * must have a merged content description or text containing at least one
 * letter (`\p{L}`) — UX_TEST_PLAN.md §9's "Accessible names" row. Catches
 * glyph-only labels (an icon button with no `contentDescription`, or one set
 * to a bare symbol like "$"/"⚹") that TalkBack would read as nothing useful.
 *
 * Merged tree by design: a clickable row whose *child* Text supplies the
 * label is legitimately named, and that's exactly what merging expresses.
 */
fun SemanticsNodeInteractionsProvider.assertEveryActionIsNamed() {
    val actionable = hasClickAction().or(hasLongClickAction).or(hasCustomAction)
    val nodes = onAllNodes(actionable, useUnmergedTree = false).fetchSemanticsNodes()
    val violations =
        nodes.mapNotNull { node ->
            val text =
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    ?.joinToString(" ") { it.text }
                    .orEmpty()
            val description =
                node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    ?.joinToString(" ")
                    .orEmpty()
            if (LETTER.containsMatchIn(text) || LETTER.containsMatchIn(description)) null else node.describe()
        }
    failIfAny("assertEveryActionIsNamed", violations)
}

/**
 * No text node is ellipsized or clipped at the current font scale, and no
 * text node's right edge exceeds the root's width (UX_TEST_PLAN.md §9's
 * "Font scale 200%" row). Reads each node's own `GetTextLayoutResult`
 * semantics action directly — the same mechanism TalkBack/ATF would use — so
 * this sees real layout overflow, not just a guess from `maxLines`.
 *
 * @param intentionallyEllipsized nodes this matches are exempt (the spec's
 *   "unless the node is intentionally ellipsized *and* its full text is
 *   reachable" carve-out) — a caller marks those nodes however it likes (a
 *   test tag convention, a distinct content description, etc.) and passes a
 *   matcher for them. Defaults to matching nothing, i.e. no exemptions.
 */
fun SemanticsNodeInteractionsProvider.assertNoTextOverflow(
    intentionallyEllipsized: SemanticsMatcher = SemanticsMatcher("none") { false },
) {
    val rootWidth = onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right
    val hasTextLayout =
        SemanticsMatcher("has GetTextLayoutResult action") { it.config.contains(SemanticsActions.GetTextLayoutResult) }
    val nodes = onAllNodes(hasTextLayout, useUnmergedTree = true).fetchSemanticsNodes()
    val violations =
        nodes.mapNotNull { node ->
            if (intentionallyEllipsized.matches(node)) return@mapNotNull null
            val getLayout =
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
            val results = mutableListOf<TextLayoutResult>()
            if (!getLayout(results)) return@mapNotNull null
            val overflowed = results.any { it.hasVisualOverflow }
            val offRight = node.boundsInRoot.right > rootWidth + 0.5f
            when {
                overflowed -> "${node.describe()} is ellipsized or clipped (hasVisualOverflow)"
                offRight -> "${node.describe()} right edge ${node.boundsInRoot.right} exceeds root width $rootWidth"
                else -> null
            }
        }
    failIfAny("assertNoTextOverflow", violations)
}

/**
 * A click-recorder a test wires into a screen's own callback parameters
 * (e.g. `onClick = { doTheThing(); recorder.recordClick() }`) so
 * [assertNoDeadControls] can tell a live control from one whose handler is
 * `{}` (or otherwise never reaches anything observable).
 *
 * **Limits — read before relying on this.** It proves a matched node's
 * `OnClick` action ran and told this recorder about it. It does *not* prove
 * the click did anything a user would call useful, and it cannot see a click
 * whose handler forgot to call [recordClick] — a handler that calls
 * [recordClick] and nothing else "passes" though it may still be inert.
 * Treat a pass as "no wired control silently no-opped," not as "every
 * button truly works." A screen with no injectable callbacks at all (its
 * `onClick` is buried inside the composable, not a parameter) can't be
 * checked this way without changing that composable's signature.
 */
class ClickRecorder {
    var count: Int = 0
        private set

    fun recordClick() {
        count++
    }
}

/**
 * Invokes every enabled node [matcher] selects (default: every clickable
 * node)'s own `OnClick` semantics action directly — no touch dispatch, no
 * hit-testing, so it works the same under Robolectric as anywhere — and
 * asserts [recorder]'s count moved for each one. See [ClickRecorder]'s doc
 * for exactly what a pass here does and doesn't prove.
 */
fun SemanticsNodeInteractionsProvider.assertNoDeadControls(
    recorder: ClickRecorder,
    matcher: SemanticsMatcher = hasClickAction(),
) {
    val nodes = onAllNodes(matcher.and(isEnabled()), useUnmergedTree = true).fetchSemanticsNodes()
    val violations =
        nodes.mapNotNull { node ->
            val onClick = node.config.getOrNull(SemanticsActions.OnClick)?.action
            if (onClick == null) {
                "${node.describe()} matched but has no OnClick action to invoke"
            } else {
                val before = recorder.count
                onClick()
                if (recorder.count == before) node.describe() else null
            }
        }
    failIfAny("assertNoDeadControls", violations)
}

private fun failIfAny(
    name: String,
    violations: List<String>,
) {
    if (violations.isNotEmpty()) {
        throw AssertionError("$name: ${violations.size} node(s):\n" + violations.joinToString("\n"))
    }
}

/** Test tag / text / content description / bounds, for a clear failure message. */
private fun SemanticsNode.describe(): String {
    val tag = config.getOrNull(SemanticsProperties.TestTag)
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    return buildString {
        append("node")
        if (tag != null) append(" tag=\"").append(tag).append('"')
        if (!text.isNullOrBlank()) append(" text=\"").append(text).append('"')
        if (!description.isNullOrBlank()) append(" desc=\"").append(description).append('"')
        append(" bounds=").append(boundsInRoot)
    }
}
