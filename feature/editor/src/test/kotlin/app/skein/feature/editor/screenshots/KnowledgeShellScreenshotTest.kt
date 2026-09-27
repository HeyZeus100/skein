// skein-xtov.24.8 (AL-09a): goldens for the Knowledge destination in the
// NavDisplay shell on the owner's Fold — outer (524 dp: drawer, one pane) and
// inner (1007 dp: rail, list │ item), light and dark. Recorded with
// `tools/ux/shots record editor --tests
// "app.skein.feature.editor.screenshots.KnowledgeShellScreenshotTest"`.
package app.skein.feature.editor.screenshots

import androidx.compose.ui.test.onRoot
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.FrontmatterKeys
import app.skein.core.model.NewDocument
import app.skein.core.model.Role
import app.skein.core.navigation.Destination
import app.skein.core.navigation.FileKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.editor.entries.KnowledgeHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.testing.FakeClock
import app.skein.testing.fakeVault
import app.skein.testing.ui.SkeinDevice
import app.skein.testing.ui.UxDeviceRule
import app.skein.testing.ui.UxSpec
import app.skein.testing.ui.captureUx
import app.skein.testing.ui.skeinComposeRule
import app.skein.testing.ui.uxSpecs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

private val NOW = Instant.parse("2026-09-26T16:00:00Z").toEpochMilli()
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val LAUNCH_PLAN_BODY =
    "Targets M2 for the ask path. Owner smoke test on the Pixel 9 Pro Fold before tagging.\n\nSee [[Sync design]]."

private fun tags(vararg values: String) =
    JsonObject(
        mapOf(
            FrontmatterKeys.TAGS to JsonArray(values.map { JsonPrimitive(it) }),
        ),
    )

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnowledgeShellScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = skeinComposeRule()

    private val clock = FakeClock()
    private lateinit var launchPlan: Document
    private lateinit var pdf: Document
    private val vault =
        fakeVault(clock = clock::now) {
            clock.set(NOW - 3 * DAY)
            note("Sync design", "No network permission, so sync is export/import only.", frontmatter = tags("design"))
            clock.set(NOW - DAY - 2 * HOUR)
            note(
                "Weekly review",
                "Shipped: citations, backlinks drawer. Next: fold transitions.",
                frontmatter = tags("review"),
            )
            clock.set(NOW - DAY)
            chat("Chat about quantisation", Role.USER to "Is Q3_K_M good enough?")
            clock.set(NOW - 5 * HOUR)
            pdf = attachment("adaptive-layouts.pdf", "application/pdf", ByteArray(2048) { it.toByte() })
            note(
                "Adaptive layouts",
                "Canonical layouts: list-detail, supporting pane and feed. Pick panes by window size class.",
                frontmatter = JsonObject(mapOf(FrontmatterKeys.SOURCE to JsonPrimitive(pdf.id))),
            )
            clock.set(NOW - 3 * HOUR)
        }.also { vault ->
            // A fixed id: the note's properties chip shows it.
            launchPlan =
                runBlocking {
                    vault.createDocument(
                        NewDocument(
                            kind = DocumentKind.NOTE,
                            title = "Fold launch plan",
                            bodyMd = LAUNCH_PLAN_BODY,
                            frontmatter = tags("launch", "fold"),
                            id = "01926f3a-7c00-7000-8000-000000000001",
                        ),
                    )
                }
        }
    private lateinit var shell: SkeinShellState

    private fun show() {
        assumeTrue(spec.fontScale == 1f)
        assumeTrue(spec.device == SkeinDevice.FOLD_OUTER_524 || spec.device == SkeinDevice.FOLD_INNER_1007)
        composeRule.setContent {
            SkeinTheme {
                KnowledgeHost(
                    vault,
                    size = null,
                    onShell = { shell = it },
                    clock = { NOW },
                )
            }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.KNOWLEDGE) } }
        composeRule.waitForIdle()
    }

    /** Outer: the list; inner: the list │ "Pick a note or file". Chats are not listed. */
    @Test
    fun list() {
        show()
        composeRule.onRoot().captureUx(spec, "shell-knowledge-list")
    }

    /** Outer: the note, full screen with ←; inner: the list │ the note. */
    @Test
    fun note() {
        show()
        composeRule.runOnIdle { shell.navigate { goTo(it, NoteKey(SkeinId.of(launchPlan.id))) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "shell-knowledge-note")
    }

    /** The minimal file viewer: name, type and size, then the extracted text. */
    @Test
    fun file() {
        show()
        composeRule.runOnIdle { shell.navigate { goTo(it, FileKey(SkeinId.of(pdf.id))) } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "shell-knowledge-file")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
