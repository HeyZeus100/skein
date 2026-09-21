package app.skein

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.feature.editor.notetab.NoteTabTestTags
import app.skein.feature.graph.GraphTestTags
import app.skein.feature.shell.testing.ShellTestTags
import app.skein.system.SecurityPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import java.time.Duration

/**
 * E10.I1's sample Compose UI test, extended by skein-2ige: a
 * Robolectric-hosted Compose rule exercising [MainActivity]'s real content
 * the way a device would render it. The activity is now gated on the vault
 * (`SkeinApplication.vault`), so the host application is
 * [TestSkeinApplication] — an in-memory vault behind a scripted key
 * provider — and each test launches the activity itself (an
 * `ActivityScenario` + [createEmptyComposeRule]) so it can script the
 * setup / unlock outcomes first. Pinned to SDK 34 (bd memory
 * `robolectric-sdk37-needs-java21`).
 *
 * skein-ank2 added the first-run gate: the scripted provider's
 * `initialised` flag stands in for the key envelope on disk, so the tests
 * below cover fresh install → setup → unlock → shell, second launch →
 * unlock only, and the corrupt-envelope message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestSkeinApplication::class)
class MainActivityComposeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: TestSkeinApplication
        get() = ApplicationProvider.getApplicationContext()

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitContentDescription(description: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `MainActivity shows the biometric unlock screen while the vault is locked`() {
        app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT).assertExists()
        }
    }

    // ---- skein-ank2: first-run gate ---------------------------------------------

    @Test
    fun `a fresh install shows the vault setup screen instead of the unlock prompt`() {
        app.keyProvider.initialised = false

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT).assertDoesNotExist()
        }
    }

    // skein-v9g (E3.I11): the recovery / device-migration entry point.

    @Test
    fun `a fresh install offers the restore-from-export entry point`() {
        app.keyProvider.initialised = false

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.VAULT_RESTORE_BUTTON).assertExists()
        }
    }

    @Test
    fun `the restore entry point does not run a generating setup`() {
        app.keyProvider.initialised = false

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_RESTORE_BUTTON).assertExists()

            // Opening the picker must not mint a fresh master behind the user's back.
            assertEquals(0, app.keyProvider.setupCalls.get())
        }
    }

    @Test
    fun `a fresh install never calls unlock before setup has run`() {
        app.keyProvider.initialised = false

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)

            assertEquals(0, app.keyProvider.setupCalls.get())
        }
    }

    @Test
    fun `a provisioned vault goes straight to unlock without calling setup`() {
        app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT)

            assertEquals(0, app.keyProvider.setupCalls.get())
        }
    }

    @Test
    fun `completing setup on a fresh install unlocks, opens the vault, and lands in the shell`() {
        app.keyProvider.initialised = false

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            assertEquals(1, app.keyProvider.setupCalls.get())
        }
    }

    @Test
    fun `the first open seeds the default persona`() {
        app.keyProvider.initialised = false

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            val personas = runBlocking { app.personaService.observeAll().first() }
            assertTrue("expected the first persona to exist once the shell is up", personas.isNotEmpty())
        }
    }

    @Test
    fun `setup refused as already initialised routes to unlock without a second setup`() {
        app.keyProvider.initialised = false
        app.keyProvider.nextSetup = { SetupResult.AlreadyInitialised }
        app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT)

            assertEquals(1, app.keyProvider.setupCalls.get())
        }
    }

    @Test
    fun `a cancelled setup stays on the setup screen with a retry`() {
        app.keyProvider.initialised = false
        app.keyProvider.nextSetup = { SetupResult.UserCancelled }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
            awaitTag(ShellTestTags.VAULT_SETUP_RETRY_BUTTON)

            composeRule.onNodeWithTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT).assertDoesNotExist()
        }
    }

    @Test
    fun `a StrongBox fallback during setup is recorded for Settings`() {
        app.keyProvider.initialised = false
        app.keyProvider.nextSetup = { SetupResult.StrongBoxUnavailableFallback(masterKeyVersion = 1) }
        val prefs = SecurityPrefs(app)

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.VAULT_SETUP_ROOT)
            composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_BEGIN_BUTTON).performClick()
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                runBlocking { prefs.strongBoxUnavailableFallback.first() }
            }
        }
    }

    @Test
    fun `a corrupt key envelope shows the non-destructive message and never offers setup`() {
        app.keyProvider.nextUnlock = { UnlockResult.Failed("key envelope corrupt") }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)

            composeRule.onNodeWithText("Nothing has been changed", substring = true).assertExists()
            composeRule.onNodeWithTag(ShellTestTags.VAULT_SETUP_ROOT).assertDoesNotExist()
        }
    }

    @Test
    fun `an ordinary unlock failure shows the generic message, not the envelope one`() {
        app.keyProvider.nextUnlock = { UnlockResult.Failed("cipher init failed: KeyStoreException") }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_MESSAGE)

            composeRule.onNodeWithText("Authentication failed.").assertExists()
        }
    }

    @Test
    fun `MainActivity renders the Skein shell once the vault is unlocked and open`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertExists()
        }
    }

    @Test
    fun `the CommandBar renders the model status once unlocked`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            // CommandBar composes this as "<name> · ●", so substring-match
            // rather than exact so a future icon or separator change doesn't
            // break the test.
            composeRule.onNodeWithText("qwen", substring = true).assertExists()
        }
    }

    @Test
    fun `MainActivity does not render the shell while the vault is locked`() {
        app.keyProvider.nextUnlock = { UnlockResult.UserCancelled }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.BIOMETRIC_UNLOCK_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertDoesNotExist()
        }
    }

    @Test
    fun `a vault that fails to open shows the gate's failure state with a retry`() {
        app.failOpenWith = "wrong vault key"

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(VaultGateTestTags.OPEN_FAILED)

            composeRule.onNodeWithTag(VaultGateTestTags.RETRY).assertExists()
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `the unlocked shell shows the timeline instead of No tabs open`() {
        runBlocking {
            app.repository.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = "Seeded Note", bodyMd = "content"),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                composeRule.onAllNodesWithContentDescription("Note").fetchSemanticsNodes().isNotEmpty()
            }

            // skein-64y9: a fresh launch (no tabs open, compact/single-pane
            // width) now lands on the timeline instead of `TabHost`'s "No
            // tabs open" placeholder — the seeded note shows up as a
            // `TimelineRail` glyph, accessible via its kind label.
            composeRule.onNodeWithContentDescription("Note").assertExists()
            composeRule.onNodeWithText("No tabs open", substring = true).assertDoesNotExist()
        }
    }

    // ---- skein-0td0: graph overlay opens the tapped node as a real tab --------

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `the unlocked shell shows the graph overlay once graphDocId is set`() {
        runBlocking {
            app.repository.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = "Graph Me", bodyMd = "content"),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            awaitContentDescription("Note")

            composeRule.onNodeWithContentDescription("Note").performClick()
            awaitTag(NoteTabTestTags.GRAPH_BUTTON)
            composeRule.onNodeWithTag(NoteTabTestTags.GRAPH_BUTTON).performClick()

            awaitTag(GraphTestTags.CANVAS)
            composeRule.onNodeWithTag(GraphTestTags.CANVAS).assertExists()
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `tapping a graph node opens it as a NOTE tab and dismisses the overlay`() {
        runBlocking {
            app.repository.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = "Graph Me", bodyMd = "content"),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)
            awaitContentDescription("Note")

            composeRule.onNodeWithContentDescription("Note").performClick()
            awaitTag(NoteTabTestTags.GRAPH_BUTTON)
            composeRule.onNodeWithTag(NoteTabTestTags.GRAPH_BUTTON).performClick()
            awaitTag(GraphTestTags.CANVAS)

            // The seeded document is the graph's only (center) node, so a
            // plain tap on the canvas — its default gesture target, per
            // `GraphViewInstrumentedTest` — deterministically lands on it.
            composeRule.onNodeWithTag(GraphTestTags.CANVAS).performClick()

            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                composeRule.onAllNodesWithTag(GraphTestTags.CANVAS).fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag(GraphTestTags.CANVAS).assertDoesNotExist()
            composeRule.onNodeWithTag(NoteTabTestTags.ROOT).assertExists()
        }
    }

    // ---- skein-qsux (E3.I14 cont'd): lock-policy Settings wiring --------------

    @Test
    fun `changing the idle timeout in Settings persists it and updates UnlockManager's live policy`() {
        runBlocking { SecurityPrefs(app).clearAllForTest() }

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            // Open the hamburger drawer and navigate to Settings (E6.I3's
            // CommandBar `≡` / NavDrawer "Settings" entry).
            composeRule.onNodeWithContentDescription("Open navigation drawer").performClick()
            composeRule.onNodeWithText("Settings", substring = true).performClick()
            composeRule.onRoot().printToLog("SKEIN_DEBUG")

            // Settings › Security's IdleTimeoutRow defaults to 5 minutes
            // (SecurityPrefs.DEFAULT_IDLE_TIMEOUT_MINUTES) until wired
            // through MainActivity's rememberSettingsViewModel call — this
            // assertion is the regression guard for that wiring actually
            // being present.
            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                composeRule.onAllNodesWithText("5 minutes").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("5 minutes").performClick()
            composeRule.onNodeWithText("1 minute").performClick()

            // Persisted to the real DataStore-backed SecurityPrefs...
            val prefs = SecurityPrefs(app)
            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                runBlocking { prefs.idleTimeoutMinutes.first() } == 1
            }
            assertEquals(1, runBlocking { prefs.idleTimeoutMinutes.first() })

            // ...and reflected in the real UnlockManager's live policy, via
            // TestSkeinApplication's mirror of VaultServices.forDevice's
            // `combine(...)` collection — the same live-collection path
            // production wiring uses, not a write from MainActivity itself.
            composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
                app.vault.unlockManager.policy.value.idleTimeout == Duration.ofMinutes(1)
            }
        }
    }

    @Test
    fun `retrying after a failed open brings the shell up once the vault opens`() {
        app.failOpenWith = "wrong vault key"

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag(VaultGateTestTags.RETRY)
            app.failOpenWith = null
            composeRule.onNodeWithTag(VaultGateTestTags.RETRY).performClick()
            awaitTag(ShellTestTags.SKEIN_SHELL_ROOT)

            composeRule.onNodeWithTag(ShellTestTags.SKEIN_SHELL_ROOT).assertExists()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
