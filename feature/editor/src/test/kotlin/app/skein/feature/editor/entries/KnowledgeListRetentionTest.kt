package app.skein.feature.editor.entries

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.DocumentKind
import app.skein.core.model.TimelineFilter
import app.skein.core.navigation.Destination
import app.skein.core.navigation.KnowledgeHomeKey
import app.skein.core.navigation.NoteKey
import app.skein.core.navigation.SkeinId
import app.skein.feature.shell.host.EntryChromeTestTags
import app.skein.feature.shell.host.PlaceholderEntry
import app.skein.feature.shell.host.SkeinShellHost
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.shell.host.navKindsOf
import app.skein.feature.shell.host.rememberSkeinShellState
import app.skein.feature.timeline.TimelineTestTags
import app.skein.testing.InMemoryIndexStore
import app.skein.testing.fakeVault
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1006dp-h1043dp-mdpi")
class KnowledgeListRetentionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var shell: SkeinShellState
    private lateinit var filters: KnowledgeListFilterState
    private lateinit var noteId: String
    private val vault = fakeVault { noteId = note("Retained note", "Body").id }

    @Test
    fun `production knowledge filter survives root and detail sidebar collapse and is scrubbed while stopped`() {
        rule.setContent {
            SkeinTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(EXPANDED)) {
                    val manager = remember { idleUnlockManager() }
                    shell = rememberSkeinShellState(manager)
                    val deps = remember { KnowledgeEntryDeps(vault, InMemoryIndexStore()) }
                    SkeinShellHost(
                        shell, navKindsOf(vault),
                        detailPlaceholder = { destination ->
                            if (destination == Destination.KNOWLEDGE) KnowledgeDetailPlaceholder(shell) else PlaceholderEntry(null)
                        },
                    ) { entry ->
                        if (entry == KnowledgeHomeKey) filters = viewModel { KnowledgeListFilterState() }
                        KnowledgeEntry(entry, shell, deps)
                    }
                }
            }
        }
        rule.runOnIdle { shell.navigate { switchTo(it, Destination.KNOWLEDGE) } }
        rule.onNodeWithTag(TimelineTestTags.kindChip(DocumentKind.ATTACHMENT)).performClick()
        rule.waitForIdle()
        val selected = KNOWLEDGE_KINDS - DocumentKind.ATTACHMENT
        assertEquals(selected, filters.filter.kinds)
        repeat(2) {
            rule.onNodeWithTag(EntryChromeTestTags.LIST_TOGGLE).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag(TimelineTestTags.kindChip(DocumentKind.ATTACHMENT)).assertIsNotSelected()
        rule.runOnIdle { shell.navigate { goTo(it, NoteKey(SkeinId.of(noteId))) } }
        repeat(2) {
            rule.onNodeWithTag(EntryChromeTestTags.LIST_TOGGLE).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag(TimelineTestTags.kindChip(DocumentKind.ATTACHMENT)).assertIsNotSelected()
        assertEquals(selected, filters.filter.kinds)
        val stoppedOwner = filters
        rule.runOnIdle { stoppedOwner.record(stoppedOwner.filter.copy(tag = "private filter sentinel")) }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Exercise the existing synchronous T3 lock hook without relying on recomposition.
        shell.stores.onLocked(42L)
        assertEquals(TimelineFilter(kinds = KNOWLEDGE_KINDS), stoppedOwner.filter)
        stoppedOwner.record(TimelineFilter(tag = "late collector sentinel"))
        assertEquals(TimelineFilter(kinds = KNOWLEDGE_KINDS), stoppedOwner.filter)
    }
}
