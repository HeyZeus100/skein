// skein-xtov.9 — "before" captures of a note tab (editor + backlinks drawer),
// full window inside SkeinTheme — the content `:app` hands `SkeinApp`'s
// `noteTabContent` slot, without the shell's command bar / tab strip above it.
// Backlinks are hand-built in the in-memory index the way `BacklinksStateTest`
// does (one chunk + one WIKILINK edge per linking note).
package app.skein.feature.editor.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.NewChunk
import app.skein.core.model.NewDocument
import app.skein.feature.editor.backlinks.BacklinksTestTags
import app.skein.feature.editor.notetab.NoteTab
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
class NoteTabScreenshotTest(
    private val spec: UxSpec,
) {
    @get:Rule(order = 0)
    val deviceRule = UxDeviceRule(spec)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<RoborazziActivity>()

    private var nextId = 1
    private val repo = InMemoryVaultRepository(clock = { 1_790_000_000_000L })
    private val index = InMemoryIndexStore()

    private val note: Document =
        runBlocking {
            val target = repo.note("Fold launch plan", NOTE_BODY)
            index.link(
                repo.note("Weekly review", "Shipped: citations.\n\nNext: fold transitions for [[Fold launch plan]]."),
                target,
            )
            index.link(
                repo.note("Meeting notes 26 Sep", "Decided: capture baselines before touching [[Fold launch plan]]."),
                target,
            )
            index.link(
                repo.note("Model picker ideas", "The chip must fit long names — see [[Fold launch plan]] checklist."),
                target,
            )
            target
        }

    private fun setContent() {
        composeRule.setContent {
            SkeinTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NoteTab(docId = note.id, vaultRepository = repo, indexStore = index)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun note() {
        setContent()
        composeRule.onRoot().captureUx(spec, "note")
    }

    @Test
    fun noteBacklinksOpen() {
        setContent()
        composeRule.onNodeWithTag(BacklinksTestTags.HEADER).performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureUx(spec, "note-backlinks")
    }

    /** Fixed ids: the note header shows the frontmatter id, so a random UUID would change every run. */
    private suspend fun InMemoryVaultRepository.note(
        title: String,
        body: String,
    ): Document =
        createDocument(
            NewDocument(
                id = "01926f3a-7c00-7000-8000-%012d".format(nextId++),
                kind = DocumentKind.NOTE,
                title = title,
                bodyMd = body,
            ),
        )

    private suspend fun InMemoryIndexStore.link(
        source: Document,
        target: Document,
    ) {
        val body = source.bodyMd.orEmpty()
        replaceChunks(source.id, listOf(NewChunk(ord = 0, text = body, tokenCount = body.length)), "ux-embedder", 1)
        replaceEdges(
            source.id,
            setOf(EdgeKind.WIKILINK),
            listOf(Edge(srcId = source.id, dstId = target.id, kind = EdgeKind.WIKILINK, createdAt = 0L)),
        )
    }

    companion object {
        private val NOTE_BODY =
            """
            Targets **M2** for the ask path. Owner smoke test on the Pixel 9 Pro Fold before tagging.

            ## Checklist

            - Import `qwen2.5-3b-instruct-abliterated-q3_k_m.gguf` and set it as default
            - Citations render as chips under the answer
            - Folding mid-generation keeps the draft and the stream

            ## Open questions

            1. Does the cover screen need its own navigation?
            2. Where does the context inspector live on the inner display?

            See [[Sync design]] and [[Weekly review]].
            """.trimIndent()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = uxSpecs()
    }
}
