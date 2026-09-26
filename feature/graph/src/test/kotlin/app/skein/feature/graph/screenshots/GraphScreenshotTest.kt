// skein-xtov.9 — "before" captures of the graph overlay. `:app` draws
// GraphScreen in `SkeinApp`'s overlay slot (full window, inside SkeinTheme),
// which is what is composed here, over a small wikilink neighbourhood built
// directly in the in-memory index (same fixture style as `GraphStateTest`).
package app.skein.feature.graph.screenshots

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewDocument
import app.skein.feature.graph.GraphScreen
import app.skein.feature.shell.theme.SkeinTheme
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.InMemoryVaultRepository
import com.github.takahirom.roborazzi.RoborazziActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

    @Test
    fun graph() {
        val repo = InMemoryVaultRepository(clock = { 0L })
        val index = InMemoryIndexStore()
        val center =
            runBlocking {
                val docs =
                    listOf(
                        "Fold launch plan" to DocumentKind.NOTE,
                        "Sync design" to DocumentKind.NOTE,
                        "Weekly review" to DocumentKind.NOTE,
                        "Meeting notes 26 Sep" to DocumentKind.NOTE,
                        "Reading list — local-first software" to DocumentKind.NOTE,
                        "Chat about quantisation" to DocumentKind.CHAT,
                        "Model picker ideas" to DocumentKind.NOTE,
                        "Fold posture checklist" to DocumentKind.NOTE,
                    ).associate { (title, kind) ->
                        title to repo.createDocument(NewDocument(kind = kind, title = title, bodyMd = title)).id
                    }

                fun id(title: String) = docs.getValue(title)
                link(
                    index,
                    id("Fold launch plan"),
                    id("Sync design"),
                    id("Weekly review"),
                    id("Fold posture checklist"),
                )
                link(index, id("Weekly review"), id("Meeting notes 26 Sep"), id("Model picker ideas"))
                link(index, id("Meeting notes 26 Sep"), id("Fold launch plan"))
                link(index, id("Sync design"), id("Reading list — local-first software"))
                link(index, id("Chat about quantisation"), id("Fold launch plan"), id("Model picker ideas"))
                id("Fold launch plan")
            }
        composeRule.setContent {
            SkeinTheme {
                GraphScreen(
                    docId = center,
                    vaultRepository = repo,
                    indexStore = index,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "graph")
    }

    private suspend fun link(
        index: InMemoryIndexStore,
        from: DocId,
        vararg to: DocId,
    ) {
        index.replaceEdges(
            from,
            setOf(EdgeKind.WIKILINK),
            to.map { Edge(srcId = from, dstId = it, kind = EdgeKind.WIKILINK, createdAt = 0L) },
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
