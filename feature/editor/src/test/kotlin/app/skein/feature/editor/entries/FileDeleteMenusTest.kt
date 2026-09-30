package app.skein.feature.editor.entries

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skein.core.designsystem.theme.SkeinTheme
import app.skein.core.model.Document
import app.skein.core.model.FileLifecycle
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.Destination
import app.skein.feature.shell.host.SkeinShellState
import app.skein.feature.timeline.TimelineTestTags
import app.skein.testing.fakeVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileDeleteMenusTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun `file row and header actions request shared confirmation without storage mutation`() {
        lateinit var file: Document
        lateinit var extracted: Document
        val vault =
            fakeVault {
                file = attachment("Disposable report.pdf", "application/pdf", byteArrayOf(1))
                extracted =
                    note(
                        "Extracted report",
                        "Disposable text",
                        frontmatter = JsonObject(mapOf("source" to JsonPrimitive(file.id))),
                    )
            }
        val capable =
            Proxy.newProxyInstance(
                VaultRepository::class.java.classLoader,
                arrayOf(VaultRepository::class.java, FileLifecycle::class.java),
            ) { _, method, args ->
                check(method.declaringClass != FileLifecycle::class.java) { "Menu must only request confirmation" }
                method.invoke(vault, *(args ?: emptyArray()))
            } as VaultRepository
        val requests = mutableListOf<String>()
        lateinit var shell: SkeinShellState
        composeRule.setContent {
            SkeinTheme { KnowledgeHost(capable, COMPACT, { shell = it }, onDelete = { requests += it }) }
        }
        composeRule.runOnIdle { shell.navigate { switchTo(it, Destination.KNOWLEDGE) } }
        composeRule.onNodeWithTag(TimelineTestTags.entryMenu(extracted.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(TimelineTestTags.entryMenu(file.id)).performClick()
        composeRule.onNodeWithText("Delete…").performClick()
        composeRule.runOnIdle { assertEquals(listOf(file.id), requests) }
        composeRule.onNodeWithTag(TimelineTestTags.entryRow(file.id)).performClick()
        composeRule.onNodeWithTag(FileRouteTestTags.DELETE_MENU).performClick()
        composeRule.onNodeWithText("Delete…").performClick()
        composeRule.runOnIdle { assertEquals(listOf(file.id, file.id), requests) }
        assertEquals(file, runBlocking { vault.getDocument(file.id) })
        assertEquals(extracted, runBlocking { vault.getDocument(extracted.id) })
    }
}
