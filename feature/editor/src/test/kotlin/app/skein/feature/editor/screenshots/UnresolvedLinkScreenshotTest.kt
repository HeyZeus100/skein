package app.skein.feature.editor.screenshots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.navigation.Destination
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.core.vault.transfer.ImportedLinkTargets
import app.skein.feature.editor.SKEIN_EDITOR_TEST_TAG
import app.skein.feature.editor.entries.KnowledgeHost
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.fakeVault
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Imported links are activated in the real note pane; no notice-only rendering fixture. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnresolvedLinkScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    @Test
    fun ambiguousImportedLink() = captureNotice("Roadmap", ambiguous = true, imported = true, state = "ambiguous")

    @Test
    fun missingImportedLink() = captureNotice("Project archive", ambiguous = false, imported = true, state = "missing")

    @Test
    fun deletedIdLink() =
        captureNotice(
            "01926f3a-7c00-7000-8000-000000000099",
            ambiguous = false,
            imported = false,
            state = "deleted",
        )

    private fun captureNotice(
        target: String,
        ambiguous: Boolean,
        imported: Boolean,
        state: String,
    ) {
        val vault = fakeVault(clock = { 1_790_000_000_000L }) { }
        val title = "Imported project notes"
        val label = if (imported) target else "Archived roadmap"
        val document =
            runBlocking {
                vault.createDocument(
                    NewDocument(
                        id = "01926f3a-7c00-7000-8000-000000000001",
                        kind = DocumentKind.NOTE,
                        title = title,
                        bodyMd =
                            "These notes came from the project folder.\n\n[[$target|$label]]" +
                                "\n\nNext: review the launch checklist.",
                        frontmatter =
                            buildJsonObject {
                                if (imported) {
                                    put(
                                        ImportedLinkTargets.UNRESOLVED,
                                        JsonArray(listOf(JsonPrimitive(target))),
                                    )
                                }
                                if (ambiguous) {
                                    put(
                                        ImportedLinkTargets.AMBIGUOUS,
                                        JsonArray(listOf(JsonPrimitive(target))),
                                    )
                                }
                            },
                    ),
                )
            }
        lateinit var shell: SkeinShellState
        composeRule.setContent {
            SkeinTheme { KnowledgeHost(vault, size = null, onShell = { shell = it }, clock = { 1_790_000_000_000L }) }
        }
        composeRule.runOnIdle {
            shell.navigate { switchTo(it, Destination.KNOWLEDGE) }
            shell.navigate { goTo(it, NoteKey(SkeinId.of(document.id))) }
        }
        composeRule.waitForIdle()
        val editor = composeRule.onNodeWithTag(SKEIN_EDITOR_TEST_TAG)
        val layouts = mutableListOf<TextLayoutResult>()
        editor
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        val layout = layouts.single()
        val offset =
            layout.layoutInput.text.text
                .lastIndexOf(label)
        val bounds = layout.getBoundingBox(offset)
        editor.performTouchInput { click(Offset(bounds.center.x, bounds.center.y)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NoteTabTestTags.LINK_NOTICE).assertIsDisplayed()
        composeRule.onRoot().captureUx(spec, "knowledge-link-$state")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(SkeinDevice.FOLD_OUTER_443, SkeinDevice.FOLD_OUTER_524, SkeinDevice.FOLD_INNER_1007_LAND)
                .flatMap { device ->
                    listOf(false, true).flatMap { dark ->
                        listOf(1f, 1.5f).map { scale -> arrayOf<Any>(UxSpec(device, dark, scale)) }
                    }
                }
    }
}
