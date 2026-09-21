package app.skein.feature.editor.backlinks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Edge
import us.aherrera.skein.core.model.EdgeKind
import us.aherrera.skein.core.model.NewChunk
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.testing.InMemoryIndexStore
import us.aherrera.skein.testing.InMemoryVaultRepository

/**
 * On-device Compose UI test for [BacklinksDrawer]. Runs on an
 * emulator/device — no Robolectric — matching the shape of
 * `:feature:editor`'s `SkeinEditorInstrumentedTest`. bd `skein-k3b2` tracks
 * the CI emulator lane that will run this; this worktree only compiles it
 * (bd `skein-9jj` acceptance: "compile only, gated on skein-k3b2").
 */
@RunWith(AndroidJUnit4::class)
class BacklinksDrawerInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun expanding_the_drawer_shows_a_row_for_each_backlinking_document() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val (target, sourceOne, sourceTwo) =
            runBlocking {
                val target = repo.note("Target Note")
                val sourceOne = repo.note("Source One", body = "See also [[Target Note]].")
                val sourceTwo = repo.note("Source Two", body = "Also related: [[Target Note]].")
                index.link(sourceOne, target.id)
                index.link(sourceTwo, target.id)
                Triple(target, sourceOne, sourceTwo)
            }

        composeRule.setContent {
            MaterialTheme {
                val state =
                    rememberBacklinksState(
                        docId = target.id,
                        vaultRepository = repo,
                        indexStore = index,
                    )
                BacklinksDrawer(state = state, initiallyExpanded = true)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BacklinksTestTags.row(sourceOne.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(BacklinksTestTags.row(sourceTwo.id)).assertIsDisplayed()
    }

    @Test
    fun tapping_a_backlink_row_invokes_onOpen_with_the_source_document_id() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val (target, source) =
            runBlocking {
                val target = repo.note("Target Note")
                val source = repo.note("Source One", body = "See also [[Target Note]].")
                index.link(source, target.id)
                target to source
            }
        var opened: String? = null

        composeRule.setContent {
            MaterialTheme {
                val state =
                    rememberBacklinksState(
                        docId = target.id,
                        vaultRepository = repo,
                        indexStore = index,
                        onOpen = { opened = it },
                    )
                BacklinksDrawer(state = state, initiallyExpanded = true)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BacklinksTestTags.row(source.id)).performClick()

        assertEquals(source.id, opened)
    }

    @Test
    fun the_drawer_starts_collapsed_with_only_the_header_visible() {
        val repo = InMemoryVaultRepository()
        val index = InMemoryIndexStore()
        val target = runBlocking { repo.note("Target Note") }

        composeRule.setContent {
            MaterialTheme {
                val state = rememberBacklinksState(docId = target.id, vaultRepository = repo, indexStore = index)
                BacklinksDrawer(state = state)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BacklinksTestTags.HEADER).assertIsDisplayed()
    }

    private suspend fun InMemoryVaultRepository.note(
        title: String,
        body: String = "body of $title",
    ): Document = createDocument(NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = body))

    /** Mimics the observable effect of `EdgeUpserter.upsert` for one document — see `BacklinksStateTest`. */
    private suspend fun InMemoryIndexStore.link(
        source: Document,
        dstId: String,
    ) {
        val body = source.bodyMd.orEmpty()
        replaceChunks(
            docId = source.id,
            chunks = listOf(NewChunk(ord = 0, text = body, tokenCount = body.length)),
            embedderId = "test-embedder",
            embedderVersion = 1,
        )
        replaceEdges(
            srcId = source.id,
            kinds = setOf(EdgeKind.WIKILINK),
            edges = listOf(Edge(srcId = source.id, dstId = dstId, kind = EdgeKind.WIKILINK, createdAt = 0L)),
        )
    }
}
