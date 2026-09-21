package app.skein.feature.timeline

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.FrontmatterKeys
import us.aherrera.skein.core.model.NewDocument
import us.aherrera.skein.core.model.Persona
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device Compose UI test for [TimelineScreen] and [TimelineRail]. Runs on
 * an emulator/device — no Robolectric — matching `:feature:editor`'s
 * `SkeinEditorInstrumentedTest`. bd `skein-k3b2` tracks the CI emulator
 * lane that will run this; until then it is compile-only.
 *
 * `InMemoryVaultRepository` honours persona and kind filters but ignores
 * `TimelineFilter.tag` (the real repository resolves tags via TAG edges),
 * so the tag case asserts the chip drives the filter rather than the row
 * count.
 */
@RunWith(AndroidJUnit4::class)
class TimelineScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun seeded_content_renders_a_row_per_document_with_its_title() {
        val vault = seededVault()
        showTimeline(vault.repo)

        vault.all.forEach { composeRule.onNodeWithTag(TimelineTestTags.entryRow(it.id)).assertIsDisplayed() }
        composeRule.onNodeWithText("Alpha note").assertIsDisplayed()
        composeRule.onNodeWithText("Beta chat").assertIsDisplayed()
    }

    @Test
    fun persona_menu_selection_reduces_the_list() {
        val vault = seededVault()
        showTimeline(vault.repo)

        composeRule.onNodeWithTag(TimelineTestTags.PERSONA_CHIP).performClick()
        composeRule.onNodeWithTag(TimelineTestTags.personaMenuItem("work")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.alpha.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.beta.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.gamma.id)).assertDoesNotExist()
    }

    @Test
    fun kind_chip_toggle_hides_chats() {
        val vault = seededVault()
        showTimeline(vault.repo)

        composeRule.onNodeWithTag(TimelineTestTags.kindChip(DocumentKind.CHAT)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.beta.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.alpha.id)).assertIsDisplayed()
    }

    @Test
    fun tag_chip_tap_selects_and_reselect_clears_the_tag_filter() {
        val vault = seededVault()
        val state = showTimeline(vault.repo)

        composeRule.onNodeWithTag(TimelineTestTags.tagChip("project")).performClick()
        composeRule.waitForIdle()
        assertEquals("project", state.filter.tag)

        composeRule.onNodeWithTag(TimelineTestTags.tagChip("project")).performClick()
        composeRule.waitForIdle()
        assertNull(state.filter.tag)
    }

    @Test
    fun empty_repository_shows_the_empty_state_instead_of_the_list() {
        showTimeline(InMemoryVaultRepository(clock = { 1L }))

        composeRule.onNodeWithTag(TimelineTestTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(TimelineTestTags.LIST).assertDoesNotExist()
    }

    @Test
    fun filters_with_no_matches_offer_clear_filters_which_restores_rows() {
        val vault = seededVault()
        val state = showTimeline(vault.repo)

        composeRule.runOnUiThread { state.setPersona("nobody") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TimelineTestTags.EMPTY).assertIsDisplayed()

        composeRule.onNodeWithTag(TimelineTestTags.CLEAR_FILTERS).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.alpha.id)).assertIsDisplayed()
    }

    @Test
    fun tap_and_long_press_route_to_their_callbacks() {
        val vault = seededVault()
        var clicked: Document? = null
        var longPressed: Document? = null
        showTimeline(vault.repo, onEntryClick = { clicked = it }, onEntryLongPress = { longPressed = it })

        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.alpha.id)).performClick()
        composeRule.waitForIdle()
        assertEquals(vault.alpha.id, clicked?.id)
        assertNull(longPressed)

        composeRule.onNodeWithTag(TimelineTestTags.entryRow(vault.beta.id)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(vault.beta.id, longPressed?.id)
    }

    @Test
    fun rail_renders_at_most_twenty_glyph_rows_and_no_titles() {
        val repo = InMemoryVaultRepository(clock = ticking())
        runBlocking { repeat(25) { repo.createDocument(note("Rail doc $it")) } }
        composeRule.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val state = remember { TimelineState(repo = repo, scope = scope) }
                TimelineRail(state = state, onEntryClick = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasTestTagPrefix(TimelineTestTags.RAIL_ENTRY_PREFIX)).assertCountEquals(20)
        composeRule.onAllNodes(hasText("Rail doc", substring = true)).assertCountEquals(0)
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private class SeededVault(
        val repo: InMemoryVaultRepository,
        val alpha: Document,
        val beta: Document,
        val gamma: Document,
    ) {
        val all: List<Document> get() = listOf(alpha, beta, gamma)
    }

    private fun seededVault(): SeededVault {
        val repo = InMemoryVaultRepository(clock = ticking())
        return runBlocking {
            val alpha = repo.createDocument(note("Alpha note", personaId = "work", tags = listOf("project")))
            val beta = repo.createDocument(NewDocument(kind = DocumentKind.CHAT, title = "Beta chat", bodyMd = "hi"))
            val gamma =
                repo.createDocument(
                    NewDocument(
                        kind = DocumentKind.AIOUT,
                        title = "Gamma output",
                        bodyMd = "out",
                        personaId = "research",
                    ),
                )
            SeededVault(repo, alpha, beta, gamma)
        }
    }

    private fun showTimeline(
        repo: InMemoryVaultRepository,
        onEntryClick: (Document) -> Unit = {},
        onEntryLongPress: (Document) -> Unit = {},
    ): TimelineState {
        var captured: TimelineState? = null
        composeRule.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val state =
                    remember {
                        TimelineState(
                            repo = repo,
                            scope = scope,
                            personaSource = flowOf(PERSONAS),
                        ).also { captured = it }
                    }
                TimelineScreen(state = state, onEntryClick = onEntryClick, onEntryLongPress = onEntryLongPress)
            }
        }
        composeRule.waitForIdle()
        return checkNotNull(captured)
    }

    private fun ticking(): () -> Long {
        val clock = AtomicLong(1_700_000_000_000L)
        return { clock.getAndAdd(60_000L) }
    }

    private fun note(
        title: String,
        personaId: String? = null,
        tags: List<String> = emptyList(),
    ): NewDocument =
        NewDocument(
            kind = DocumentKind.NOTE,
            title = title,
            bodyMd = "body of $title",
            personaId = personaId,
            frontmatter =
                if (tags.isEmpty()) {
                    JsonObject(emptyMap())
                } else {
                    buildJsonObject { put(FrontmatterKeys.TAGS, JsonArray(tags.map(::JsonPrimitive))) }
                },
        )

    private fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
        SemanticsMatcher("testTag starts with '$prefix'") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    private companion object {
        val PERSONAS =
            listOf(
                Persona(id = "work", name = "Work", systemPrompt = null, defaultModel = null, createdAt = 0L),
                Persona(id = "research", name = "Research", systemPrompt = null, defaultModel = null, createdAt = 0L),
            )
    }
}
