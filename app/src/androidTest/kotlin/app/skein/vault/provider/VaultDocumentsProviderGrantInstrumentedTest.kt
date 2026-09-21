// `E10.I9` (bd `skein-fubu`): the `DocumentsContract`-level grant audit
// against the REAL, manifest-registered `VaultDocumentsProvider` (authority
// `us.aherrera.skein.documents`, `app/src/main/AndroidManifest.xml`) rather
// than a directly-instantiated instance (`core/vault`'s
// `VaultDocumentsProviderTest` already covers the direct-instantiation
// cursor/pipe plumbing). This test drives the provider the way DocumentsUI
// would: `ContentResolver` + `DocumentsContract` Uris, through a real
// `UnlockManager` → `VaultBootstrap` bring-up over a throwaway on-device
// SQLCipher file (the same rig `VaultBootstrapInstrumentedTest` uses).
//
// IMPORTANT — this class's assertions were authored against the AUTHORITATIVE
// design, not the (incompatible) grant-flow description that appeared in one
// iteration of this issue's task prompt. Three sources agree and this test
// follows them:
//   - `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` `E2.I6`: "clients
//     cannot take persistable grants (no `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`
//     is ever offered; `E10.I9` tests it)."
//   - `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.2: "Persistable grants
//     remain refused (`FLAG_GRANT_PERSISTABLE_URI_PERMISSION` is never
//     offered...)"; §4.4 lists `PicklePersistabilityTest`: "a co-installed
//     test app requests `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`... and calls
//     `takePersistableUriPermission`; the call throws `SecurityException`."
//   - `VaultDocumentsProvider`'s own class header and `ProviderCursors`'
//     file header: no `Root.FLAG_SUPPORTS_IS_CHILD`, no persistable grants,
//     ever. A prompt draft describing "takePersistableUriPermission → read
//     works ... after re-unlock the persisted grant still works" would
//     contradict all three and was NOT implemented; see bd `skein-fubu`'s
//     close notes.
//
// A genuinely cross-app assertion — a URI outside the two
// `<grant-uri-permission>` `pathPrefix`es being refused BY THE SYSTEM to a
// different UID — cannot be exercised from a self-instrumented test in this
// same APK: AOSP's `checkGrantUriPermissionLocked` (and the equivalent
// same-process `ContentProvider` permission short-circuit) both skip the
// `<grant-uri-permission>` pattern match entirely whenever the calling UID
// equals the provider's own app UID, because Android trusts an app to manage
// its own provider's resources without restriction. That assertion is
// `@Ignore`d below pending `skein-sz0t` (a co-installed `testing/clientapp`
// harness), which is also what bd `skein-fubu`'s own description originally
// asked for ("A second test APK... plays a co-installed app").
//
// Compiled by `compileFossDebugAndroidTestKotlin`; the on-device run is
// gated on the emulator lane tracked by bd `skein-k3b2`, like every other
// `*InstrumentedTest` in the repo. KDoc on each test notes the API levels
// (30..latest) the design intends this to hold on; only one emulator API
// image runs in any given CI job, so the level itself is not parametrized
// here (there is exactly one behavior to prove, not per-level variants).

package app.skein.vault.provider

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.DocumentsContract
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.MainActivity
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.provider.VaultDocumentsProvider
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.vault.BringUpResult
import app.skein.vault.DeviceVaultOpener
import app.skein.vault.DocumentsProviderPort
import app.skein.vault.VaultBootstrap
import app.skein.vault.VaultServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import us.aherrera.skein.core.model.AuthorizationToken
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.NewDocument
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class VaultDocumentsProviderGrantInstrumentedTest {
    /** Test-only provider: a random in-memory key, unlock always succeeds. Mirrors `VaultBootstrapInstrumentedTest`. */
    private class RandomKeyVaultKeyProvider : VaultKeyProvider {
        @Volatile
        private var master: ByteArray? = null
        private var epoch = 0L

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult = SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = false)

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult {
            master = ByteArray(KEY_LENGTH).also(SecureRandom()::nextBytes)
            return UnlockResult.Success(AuthorizationToken(++epoch))
        }

        override fun currentKey(): ByteArray? = master

        override fun lock() {
            master?.fill(0)
            master = null
        }

        override suspend fun rewrapAfterInvalidation(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            survivingFactor: VaultKeyProvider.Factor,
        ): RewrapResult = RewrapResult.Failed("not supported in tests")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver = context.contentResolver
    private val vaultDir = File(context.cacheDir, "grant-test-${System.nanoTime()}")
    private val keyProvider = RandomKeyVaultKeyProvider()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = UnlockManager(keyProvider = keyProvider, scope = null, installShutdownHook = false)
    private val opener =
        DeviceVaultOpener(
            keyProvider = keyProvider,
            paths = VaultPaths(vaultDir = vaultDir),
            attachmentsDir = File(vaultDir, VaultServices.ATTACHMENTS_DIR),
        )
    private val bootstrap =
        VaultBootstrap(
            unlockManager = manager,
            openVault = opener::open,
            provider = DocumentsProviderPort.forContext(context),
            scope = scope,
        )
    private val prompt =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock")
            .setNegativeButtonText("Cancel")
            .build()

    @After
    fun tearDown() {
        runBlocking { manager.lockAndAwait(LockReason.SESSION_ENDED) }
        VaultDocumentsProvider.install(null)
        scope.cancel()
        vaultDir.deleteRecursively()
    }

    private fun unlockAndBringUp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking { manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC) }
            }
        }
        val result = runBlocking { bootstrap.bringUp() }
        check(result is BringUpResult.Ready) { "bring-up did not complete: $result" }
    }

    /** Seeds one NOTE document directly through the open vault's real repository. */
    private fun seedNote(title: String = "Grant test note"): us.aherrera.skein.core.model.Document {
        val session = checkNotNull(bootstrap.session.value) { "vault is not open" }
        return runBlocking {
            session.repository.createDocument(
                NewDocument(kind = DocumentKind.NOTE, title = title, bodyMd = "grant test body"),
            )
        }
    }

    // ---- discovery helpers (exactly what DocumentsUI does: walk the tree by Uri) ----

    private fun Cursor.firstString(column: String): String? =
        use { if (moveToFirst()) getString(getColumnIndexOrThrow(column)) else null }

    private fun Cursor.firstInt(column: String): Int =
        use {
            check(moveToFirst()) { "cursor is empty" }
            getInt(getColumnIndexOrThrow(column))
        }

    private fun Cursor.documentIds(): List<String> =
        use {
            val index = getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val out = ArrayList<String>(count)
            while (moveToNext()) out += getString(index)
            out
        }

    private fun rootDocumentId(): String =
        requireNotNull(
            resolver
                .query(DocumentsContract.buildRootsUri(VaultDocumentsProvider.AUTHORITY), null, null, null, null)
                ?.firstString(DocumentsContract.Root.COLUMN_DOCUMENT_ID),
        ) { "no root returned" }

    private fun rootFlags(): Int =
        requireNotNull(
            resolver.query(DocumentsContract.buildRootsUri(VaultDocumentsProvider.AUTHORITY), null, null, null, null),
        ).firstInt(DocumentsContract.Root.COLUMN_FLAGS)

    private fun childDocumentIds(parentDocumentId: String): List<String> =
        requireNotNull(
            resolver.query(
                DocumentsContract.buildChildDocumentsUri(VaultDocumentsProvider.AUTHORITY, parentDocumentId),
                null,
                null,
                null,
                null,
            ),
        ).documentIds()

    /** `root/Notes/<the one note we just seeded>` — the "open a document via the picker" document id. */
    private fun theNoteDocumentId(): String {
        val notesDir = childDocumentIds(rootDocumentId()).first { !it.contains("attachment", ignoreCase = true) }
        return childDocumentIds(notesDir).single()
    }

    private fun documentUri(documentId: String) =
        DocumentsContract.buildDocumentUri(VaultDocumentsProvider.AUTHORITY, documentId)

    // ---- 1. ACTION_OPEN_DOCUMENT round trip while unlocked --------------------------

    /**
     * The "real system-picker flow" (`E2.I6`'s acceptance criterion, the
     * plan's own words): roots → children(root) → children(notes) →
     * `openDocument`, entirely through `ContentResolver`/`DocumentsContract`
     * against the manifest-registered authority, never a direct instance.
     * Holds on every supported API level (30..current); nothing here is
     * version-gated.
     */
    @Test
    fun openDocument_roundTrips_theSeededNoteBytes_throughTheRealAuthority() {
        unlockAndBringUp()
        seedNote()

        val uri = documentUri(theNoteDocumentId())
        val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

        assertTrue(
            "expected the exported markdown body in the streamed bytes",
            String(bytes).contains("grant test body"),
        )
    }

    // ---- 2. persistable grants are always refused ------------------------------------

    /**
     * `docs/superpowers/plans/2026-09-19-skein-v1-plan.md` `E2.I6` and
     * `POST_REVIEW_RESOLUTIONS.md` §4.2/§4.4 (`PicklePersistabilityTest`):
     * no `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` is ever offered by this
     * provider, so no persistable grant record for this Uri ever exists —
     * `takePersistableUriPermission` throws regardless of caller, exactly as
     * it would for a co-installed third-party app (the check is "does a
     * persistable grant exist for this Uri", not "who is asking").
     */
    @Test
    fun takePersistableUriPermission_alwaysThrows_becauseNoPersistableGrantIsEverOffered() {
        unlockAndBringUp()
        seedNote()
        val uri = documentUri(theNoteDocumentId())

        val error =
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.exceptionOrNull()

        assertTrue(
            "expected SecurityException (no persistable grant was ever established), got $error",
            error is SecurityException,
        )
    }

    // ---- 3. lock gate through the real ContentResolver path --------------------------

    /**
     * `VaultDocumentsBackend.requireUnlocked` / the plan's "otherwise throws
     * `FileNotFoundException(\"vault locked\")`": `resolveAccess` runs before
     * any pipe is created, so `ContentResolver.openInputStream` on an
     * already-open Uri surfaces the SAF-contractual `FileNotFoundException`
     * once the vault locks — never a truncated read of stale plaintext.
     */
    @Test
    fun read_throwsFileNotFoundException_afterTheVaultLocks() {
        unlockAndBringUp()
        seedNote()
        val uri = documentUri(theNoteDocumentId())
        // Sanity: readable before lock.
        requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }

        val error = runCatching { resolver.openInputStream(uri) }.exceptionOrNull()
        assertTrue(
            "expected FileNotFoundException once locked, got $error",
            error is FileNotFoundException,
        )
    }

    /**
     * Re-unlocking re-opens the vault (`VaultBootstrap`'s "a second unlock
     * reopens the same vault file", `VaultBootstrapInstrumentedTest`) and the
     * SAME document id (a stable `note:<DocId>`, not a Uri-level grant —
     * none exists, see the persistable-grant test above) reads again. This
     * is the closest in-process analogue to "access after lock across
     * API 30..latest" the design actually supports: document ids, not OS
     * grants, survive a lock/unlock cycle.
     */
    @Test
    fun read_worksAgain_afterLockThenReUnlock_usingTheSameDocumentId() {
        unlockAndBringUp()
        val noteId = seedNote().id
        val documentId = theNoteDocumentId()
        val uri = documentUri(documentId)
        requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }
        unlockAndBringUp()

        val bytesAfterReUnlock = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        assertTrue(
            "expected the same note's body after re-unlock",
            String(bytesAfterReUnlock).contains("grant test body"),
        )
        assertNotNull(
            runBlocking {
                bootstrap.session.value!!
                    .repository
                    .getDocument(noteId)
            },
        )
    }

    // ---- 4. no tree support is ever advertised ----------------------------------------

    /**
     * `VaultDocumentsProvider`'s class header / `ProviderCursors.ROOT_FLAGS`:
     * the root deliberately withholds `Root.FLAG_SUPPORTS_IS_CHILD` so
     * DocumentsUI never offers this root to an `ACTION_OPEN_DOCUMENT_TREE`
     * picker in the first place — a tree grant (`content://…/tree/root/…`)
     * would match neither `<grant-uri-permission>` `pathPrefix` and the
     * system would refuse it anyway, so the root must not advertise
     * eligibility for one.
     */
    @Test
    fun rootRow_neverAdvertises_flagSupportsIsChild() {
        unlockAndBringUp()

        val flags = rootFlags()

        assertEquals(
            "root row must not advertise FLAG_SUPPORTS_IS_CHILD",
            0,
            flags and DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
        )
    }

    // ---- 5. cross-app-only: needs a co-installed caller (skein-sz0t) -----------------

    /**
     * `POST_REVIEW_RESOLUTIONS.md` §4.3's `<grant-uri-permission>`
     * `pathPrefix`es (`/document/note:`, `/document/att:`) are enforced by
     * `UriGrantsManagerService.checkGrantUriPermissionLocked` against a
     * DIFFERENT calling UID. A self-instrumented test in this same APK
     * cannot exercise that: AOSP short-circuits the whole check ("no
     * violation") whenever the calling UID equals the provider's own app
     * UID, so `context.grantUriPermission(context.packageName, ...)` would
     * pass trivially regardless of the requested path, proving nothing.
     * `skein-sz0t` tracks the co-installed `testing/clientapp` harness this
     * assertion actually needs.
     */
    @Test
    @Ignore("needs a co-installed second app to exercise cross-UID grant enforcement; skein-sz0t")
    fun grantUriPermission_refusesAUriOutsideTheDeclaredPathPrefixes() {
        error("blocked on skein-sz0t (testing/clientapp)")
    }

    private companion object {
        const val KEY_LENGTH = 32
    }
}
