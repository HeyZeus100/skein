// skein-xtov.23.8 (DS8, docs/ux/DESIGN_SYSTEM.md §15): one capture per
// structural component, each a column of its states, at the closed Fold's
// stock width (443 dp) and the owner's open Fold (1007 dp), light and dark,
// font scale 1.0 and 2.0 — goldens under ux-baselines/core-designsystem/ —
// each also swept for 48 dp touch targets and named actions (:testing-ui).
package app.skein.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.skein.core.designsystem.icons.SkeinIcons
import app.skein.core.designsystem.theme.SkeinSize
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.assertEveryActionIsNamed
import app.skein.testing.ui.assertTouchTargets
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
class SkeinComponentsScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private val rowActions =
        listOf(
            SkeinAction("Rename…", SkeinIcons.Rename) {},
            SkeinAction("Delete…", SkeinIcons.Delete, destructive = true) {},
        )

    private fun gallery(
        stateId: String,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        composeRule.setContent {
            SkeinTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag(GALLERY).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                }
            }
        }
        composeRule.onNodeWithTag(GALLERY).captureUx(spec, stateId)
        // §7.1 / §11.4 on every state, device and font scale: 48 dp touch targets, every action named.
        composeRule.assertTouchTargets()
        composeRule.assertEveryActionIsNamed()
    }

    /** A chat header: ☰, title, and the model label as the subtitle button. */
    @Composable
    private fun ChatBar(
        title: String,
        model: String,
        detail: String,
        isError: Boolean = false,
    ) = SkeinTopAppBar(
        title = title,
        subtitle = model,
        subtitleDetail = detail,
        subtitleIsError = isError,
        onSubtitleClick = {},
        navigationIcon = {
            IconButton(onClick = {}) { Icon(painterResource(SkeinIcons.Menu), contentDescription = "Open navigation") }
        },
        actions = { IconButton(onClick = {}) { Icon(painterResource(SkeinIcons.More), "More options") } },
    )

    @Test
    fun topAppBar() =
        gallery("component-top-app-bar") {
            ChatBar("Skein UX redesign", "Qwen 2.5 3B", "Local")
            ChatBar("Skein UX redesign", "Qwen 2.5 3B", "Starting… 42%")
            ChatBar("Skein UX redesign", "Qwen 2.5 3B", "Couldn't start", isError = true)
            ChatBar("New chat", "No model", "Add one")
            ChatBar("Mycology research: straw versus hardwood substrate trials", "Qwen 2.5 Coder 32B Instruct", "Local")
            SkeinTopAppBar(
                "Knowledge",
                actions = { IconButton(onClick = {}) { Icon(painterResource(SkeinIcons.Search), "Search") } },
                scrolled = true,
            )
        }

    @Test
    fun listRow() =
        gallery("component-list-row") {
            SkeinSectionHeader("Today")
            SkeinListRow(
                "Skein UX redesign",
                onClick = {},
                selected = true,
                trailingMeta = "2h",
                menuActions = rowActions,
            )
            SkeinListRow("RAG architecture", onClick = {}, trailingMeta = "9:41", menuActions = rowActions)
            SkeinSectionHeader("Notes")
            SkeinListRow(
                "Fold launch plan",
                onClick = {},
                leadingIcon = SkeinIcons.Note,
                supportingText = "Straw colonised in 14 days, hardwood in 21; the rest of this preview ellipsises",
                trailingMeta = "3 h",
                menuActions = rowActions,
            )
            SkeinListRow(
                "report.pdf",
                onClick = {},
                leadingIcon = SkeinIcons.Pdf,
                supportingText = "The first line of its text, wrapping to a second line, then ellipsised",
                supportingMaxLines = 2,
                minHeight = SkeinSize.rowThreeLine,
            )
        }

    @Test
    fun emptyState() =
        gallery("component-empty-state") {
            Box(Modifier.fillMaxWidth().height(560.dp)) {
                SkeinEmptyState(
                    headline = "Your knowledge starts here",
                    body = "Write a note or import a file. Skein can use them when you ask.",
                    primaryAction = SkeinAction("New note") {},
                    secondaryActions =
                        listOf(
                            SkeinAction("Import file", SkeinIcons.ImportFile) {},
                            SkeinAction("Search knowledge", SkeinIcons.Search) {},
                        ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

    @Test
    fun notice() =
        gallery("component-notice") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeinNotice(
                    "Choose a model to start",
                    body = "Skein runs models on this device. Add one to send messages.",
                    action = SkeinAction("Choose a model") {},
                )
                SkeinNotice("Imported “notes.pdf”", tone = SkeinNoticeTone.Success)
                SkeinNotice(
                    "Running low on room",
                    body = "Start a new chat to keep answers complete.",
                    tone = SkeinNoticeTone.Warning,
                )
                SkeinNotice(
                    "Couldn't import “notes.pdf”",
                    body = "The file is password-protected.",
                    tone = SkeinNoticeTone.Error,
                    action = SkeinAction("Choose another file") {},
                )
            }
        }

    @Test
    fun status() =
        gallery("component-status") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeinStatus(SkeinStatusKind.Ready, "Ready")
                SkeinStatus(SkeinStatusKind.Loading, "Starting… 42%")
                SkeinStatus(SkeinStatusKind.Error, "Couldn't start")
                SkeinStatus(SkeinStatusKind.Idle, "Loads when you send")
            }
        }

    @Test
    fun searchField() =
        gallery("component-search-field") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeinSearchField(query = "", onQueryChange = {}, placeholder = "Search chats")
                SkeinSearchField(query = "fold", onQueryChange = {}, placeholder = "Search chats")
            }
        }

    @Test
    fun segmentedControl() =
        gallery("component-segmented-control") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf("System", "Light", "Dark")
                options.forEach { selected ->
                    SkeinSegmentedControl(options = options, selected = selected, onSelect = {}, label = { it })
                }
            }
        }

    companion object {
        private const val GALLERY = "ds8-gallery"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(SkeinDevice.FOLD_OUTER_443, SkeinDevice.FOLD_INNER_1007)
                .flatMap { device ->
                    listOf(1f, 2f).flatMap { fontScale ->
                        listOf(false, true).map { dark -> arrayOf<Any>(UxSpec(device, dark, fontScale)) }
                    }
                }
    }
}
