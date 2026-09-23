// E10.I17 (skein-vvb0) — device-only at-rest + zeroization suite. Runs
// against the real `libskein_sqlite.so` (real SQLCipher, real WAL) via the
// same `DeviceVaultOpener` + `UnlockManager` wiring `VaultBootstrapInstrumentedTest`
// exercises, unlike `core/vault`'s `security/AtRestEncryptionTest.kt`, which
// is confined to `FakeSkeinSQLiteNative` and cannot see real bytes on disk.
//
// Compiled by `compileFossDebugAndroidTestKotlin`; the on-device run is
// gated on the emulator lane tracked by bd `skein-k3b2`, like every other
// `*InstrumentedTest` in the repo.

package app.skein.vault.security

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.MainActivity
import app.skein.core.model.AuthorizationToken
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.vault.key.RewrapResult
import app.skein.core.vault.key.SetupResult
import app.skein.core.vault.key.UnlockResult
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.session.LockReason
import app.skein.core.vault.session.UnlockManager
import app.skein.vault.DeviceVaultOpener
import app.skein.vault.VaultOpenException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class AtRestEncryptionInstrumentedTest {
    /**
     * Same test-only key provider `VaultBootstrapInstrumentedTest` uses, and
     * it models the real provider's envelope semantics (skein-7yy2): ONE
     * random master is minted at construction and kept for the test's
     * lifetime standing in for the wrapped master at rest, every [unlock]
     * unwraps it into a FRESH live buffer, and [lock] zeroes that live
     * buffer in place. Never real key material.
     */
    private class RandomKeyVaultKeyProvider : VaultKeyProvider {
        /** The "wrapped" master: what every [unlock] unwraps. Not handed to callers, never zeroed. */
        private val wrapped: ByteArray = ByteArray(KEY_LENGTH).also(SecureRandom()::nextBytes)

        @Volatile
        var master: ByteArray? = null
            private set
        private var epoch = 0L

        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
        ): SetupResult = SetupResult.Success(masterKeyVersion = 1, strongBoxBacked = false)

        /** skein-v9g: the passphrase-import overload; not exercised by this test. */
        override suspend fun setup(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            existingMaster: ByteArray,
        ): SetupResult = error("setup(existingMaster) is not exercised by this test")

        override fun isInitialised(): Boolean = true

        override suspend fun unlock(
            activity: FragmentActivity,
            prompt: BiometricPrompt.PromptInfo,
            factor: VaultKeyProvider.Factor,
        ): UnlockResult {
            master = wrapped.copyOf()
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
    private val vaultDir = File(context.cacheDir, "at-rest-test-${System.nanoTime()}")
    private val keyProvider = RandomKeyVaultKeyProvider()
    private val manager = UnlockManager(keyProvider = keyProvider, scope = null, installShutdownHook = false)
    private val prompt =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle("Unlock")
            .setNegativeButtonText("Cancel")
            .build()

    private fun newOpener() =
        DeviceVaultOpener(
            keyProvider = keyProvider,
            paths = VaultPaths(vaultDir = vaultDir),
            attachmentsDir = File(vaultDir, "attachments"),
        )

    @After
    fun tearDown() {
        runBlocking { manager.lockAndAwait(LockReason.SESSION_ENDED) }
        vaultDir.deleteRecursively()
    }

    /** `UnlockManager.unlock` needs a `FragmentActivity` host; the launcher activity is one. */
    private fun unlockThroughManager() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking { manager.unlock(activity, prompt, VaultKeyProvider.Factor.BIOMETRIC) }
            }
        }
    }

    private fun marker(): ByteArray = ByteArray(48).also(SecureRandom()::nextBytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun no_plaintext_or_key_material_on_disk_vault_db_is_not_sqlite_magic_and_lock_zeroes_the_key() {
        // Arrange — a note carrying a hex-encoded marker (safe for a TEXT
        // column) and a separate attachment carrying a raw binary marker.
        val noteMarkerHex = hex(marker())
        val attachmentMarker = marker()

        unlockThroughManager()
        // skein-7yy2 — two DIFFERENT things, previously conflated into one
        // `copyOf()`:
        //
        //  * [liveKey] is the provider's OWN buffer, held BY REFERENCE. Per
        //    `VaultKeyProvider.currentKey`'s contract ("the buffer surfaced
        //    here is the same one that will be zeroed on `lock()`") this is
        //    the array the zeroization assertion below must read, and the
        //    same shape `VaultKeyProviderImplTest`'s "lock zeroes the master
        //    ByteArray in place" uses against the real provider.
        //  * [diskScanCopy] is a copy the TEST owns, used only to search the
        //    files below for key bytes. Nothing in production ever sees it,
        //    so nothing in production can zero it — asserting that it came
        //    back zeroed (the old line 218) was unsatisfiable by
        //    construction, not a finding about the app.
        //
        // Production's only master-key seam is `DeviceVaultOpener.keyCopy()`,
        // which hands each callee a fresh copy that the callee zeroes:
        // `VaultLifecycle.open`/`create` zero the argument plus their
        // `migrationKey`/`liveKey`, `ConnectionPool.open` zeroes the array it
        // fans out and each `SkeinSQLiteDriver` zeroes its own copy after the
        // `PRAGMA key`, and `FileAttachmentStore` zeroes the `masterKey()`
        // result in a `finally` once the per-file HKDF is done.
        val liveKey = keyProvider.currentKey()!!
        val diskScanCopy = liveKey.copyOf()
        val liveKeyHex = hex(diskScanCopy)

        val session = runBlocking { newOpener().open() }
        val noteDoc =
            runBlocking {
                session.repository.createDocument(
                    NewDocument(
                        kind = DocumentKind.NOTE,
                        title = "At-rest marker note",
                        bodyMd = "# marker note\n\nmarker: $noteMarkerHex\n",
                    ),
                )
            }
        val attachmentDoc =
            runBlocking {
                session.repository.createAttachment(
                    title = "marker.bin",
                    mimeType = "application/octet-stream",
                ) { out ->
                    out.write(attachmentMarker)
                }
            }
        runBlocking { session.close() }

        // Assert — `vault.db`'s header is SQLCipher ciphertext, not the
        // plaintext SQLite magic string.
        val vaultDbFile = VaultPaths(vaultDir = vaultDir).databaseFile
        assertTrue(vaultDbFile.exists())
        val header = ByteArray(SQLITE_MAGIC.size)
        vaultDbFile.inputStream().use { input ->
            var readSoFar = 0
            while (readSoFar < header.size) {
                val n = input.read(header, readSoFar, header.size - readSoFar)
                if (n < 0) break
                readSoFar += n
            }
        }
        assertFalse(header.contentEquals(SQLITE_MAGIC))

        // Assert — scan every file under the vault dir (db, `-wal`/`-shm` if
        // present, the key envelope, and the attachment container) for
        // either marker or the live key, raw or hex-encoded.
        val filesOnDisk = vaultDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(filesOnDisk.isNotEmpty())
        for (file in filesOnDisk) {
            val bytes = file.readBytes()
            val asLatin1 = String(bytes, Charsets.ISO_8859_1)
            assertFalse("note marker leaked in ${file.name}", asLatin1.contains(noteMarkerHex))
            assertFalse("attachment marker leaked in ${file.name}", containsSubsequence(bytes, attachmentMarker))
            assertFalse("raw master key leaked in ${file.name}", containsSubsequence(bytes, diskScanCopy))
            assertFalse("hex master key leaked in ${file.name}", asLatin1.contains(liveKeyHex))
        }

        // Assert — reopen: both documents read back intact.
        val reopened = runBlocking { newOpener().open() }
        val readNote = runBlocking { reopened.repository.getDocument(noteDoc.id) }
        assertTrue(readNote?.bodyMd.orEmpty().contains(noteMarkerHex))
        val readAttachment = runBlocking { reopened.repository.openAttachment(attachmentDoc.id).use { it.readBytes() } }
        assertTrue(readAttachment.contentEquals(attachmentMarker))
        runBlocking { reopened.close() }

        // Act — lock via `UnlockManager`.
        runBlocking { manager.lockAndAwait(LockReason.USER_REQUESTED) }

        // Assert — no live key material survives the lock, at the three
        // seams this process can observe (spec §3/§9,
        // `LOCK_POLICY_INDEXING.md` §4.4): the provider reports no key, the
        // buffer it handed out while unlocked was zeroed IN PLACE rather
        // than merely dropped, and the `masterKey()` seam
        // (`DeviceVaultOpener.keyCopy()`) throws for any caller reaching for
        // it afterwards.
        assertNull(keyProvider.currentKey())
        assertTrue(
            "lock() must zero the master buffer in place; a reference taken while unlocked still reads key bytes",
            liveKey.all { it == 0.toByte() },
        )
        try {
            runBlocking { newOpener().open() }
            fail("expected VaultOpenException: vault locked")
        } catch (e: VaultOpenException) {
            assertEquals("vault locked", e.message)
        }

        // The test's own scan copy is the last key-shaped array this process
        // holds; wipe it so the suite leaves nothing behind either.
        diskScanCopy.fill(0)
    }

    private companion object {
        const val KEY_LENGTH = 32
        val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
