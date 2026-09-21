// E10.I17 (skein-vvb0) — JVM at-rest + zeroization suite tying together
// `VaultKeyProviderImpl` (`E3.I2`, skein-3el), `FileMasterKeyStorage`
// (skein-txrh) and `FileAttachmentStore` (`E2.I5`, skein-1nr) under one
// shared vault directory and one live master key — the same components
// `app/src/main/kotlin/app/skein/vault/DeviceVaultOpener.kt` wires
// together on-device, minus the real SQLCipher connection.
//
// One behaviour per test, AAA structure, matching the sibling suites this
// extends (`FileAttachmentStoreTest`, `FileMasterKeyStorageTest`,
// `SkeinSQLiteDriverTest`, `VaultKeyProviderImplTest`) rather than
// duplicating their per-component assertions.
//
// What this file can and cannot prove on the host JVM:
//   - CAN: no plaintext marker, no raw master-key bytes and no hex of the
//     master key ever land in the key-envelope file or an attachment
//     container on disk, using the REAL `FileMasterKeyStorage` +
//     `FileAttachmentStore` crypto (`javax.crypto` needs no Android
//     runtime for either).
//   - CANNOT: scan `vault.db` itself or assert its header is not the
//     SQLite magic bytes — `SkeinSQLiteDriverTest` (and this file's own
//     driver-zeroization test) run against `FakeSkeinSQLiteNative`, which
//     never writes real bytes to disk (see `VaultLifecycleTest`'s header
//     for the same caveat). That half of the AC is covered by the
//     device-only `AtRestEncryptionInstrumentedTest`
//     (`app/src/androidTest/kotlin/app/skein/vault/security/`, gated on
//     bd `skein-k3b2` like every other instrumented test in the repo)
//     using the real `libskein_sqlite.so`.
//
// Content realism: the carrier text embedding each plaintext marker is a
// note body drawn from `SyntheticVault` (`E10.I4`) rather than a
// hand-written string, so the at-rest scan runs over the kind of markdown
// (headings, wikilinks, code fences) a real vault actually stores.

package app.skein.core.vault.security

import app.skein.core.vault.blob.FileAttachmentStore
import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.key.FakeBiometricAuthenticator
import app.skein.core.vault.key.FakeKeystoreFacade
import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.key.VaultKeyProviderImpl
import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import us.aherrera.skein.testing.InMemoryVaultRepository
import us.aherrera.skein.testing.fixtures.SyntheticVault
import java.io.File
import java.security.SecureRandom

class AtRestEncryptionTest {
    @get:Rule
    val tempDir = TempDirRule()

    private fun newProvider(envelopeFile: File): VaultKeyProviderImpl =
        VaultKeyProviderImpl(
            keystore = FakeKeystoreFacade(strongBoxAvailable = true),
            biometric = FakeBiometricAuthenticator(),
            storage = FileMasterKeyStorage(envelopeFile),
        )

    /** A realistic note body drawn from `SyntheticVault` (`E10.I4`), not a hand-written string. */
    private fun realisticCarrierText(title: String): String {
        val repo = InMemoryVaultRepository()
        SyntheticVault.seed(repo, seed = 7L, size = SyntheticVault.Preset.SMALL)
        val note = runBlocking { repo.findByTitle(title) }
        return requireNotNull(note?.bodyMd) { "SyntheticVault fixture drift: '$title' missing a body" }
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

    // ---- at-rest scan ---------------------------------------------------

    @Test
    fun `no plaintext marker, raw master key, or master key hex lands on disk under the vault directory`() =
        runTest {
            // Arrange — a live master key, a note-shaped carrier with a marker,
            // and a second attachment-shaped carrier with a distinct marker.
            val vaultDir = tempDir.newDir("vault")
            val provider = newProvider(FileMasterKeyStorage.envelopeFileIn(vaultDir))
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val liveKeySnapshot = provider.currentKey()!!.copyOf()
            val liveKeyHex = hex(liveKeySnapshot)

            val noteMarker = marker()
            val attachmentMarker = marker()
            val noteContent = (realisticCarrierText("Note 3").toByteArray(Charsets.UTF_8) + noteMarker)
            val attachmentContent = (realisticCarrierText("Note 9").toByteArray(Charsets.UTF_8) + attachmentMarker)

            val store = FileAttachmentStore(File(vaultDir, "attachments")) { provider.currentKey()!!.copyOf() }

            // Act — write both containers through the real crypto stack.
            store.write("doc-note") { out -> out.write(noteContent) }
            store.write("doc-attachment") { out -> out.write(attachmentContent) }

            // Assert — scan every file under the vault dir (key envelope +
            // attachment containers) for either marker or the live key, raw
            // or hex-encoded.
            val filesOnDisk = vaultDir.walkTopDown().filter { it.isFile }.toList()
            assertThat(filesOnDisk).isNotEmpty()
            for (file in filesOnDisk) {
                val bytes = file.readBytes()
                val asLatin1 = String(bytes, Charsets.ISO_8859_1)
                assertWithMessage("note marker leaked in ${file.name}")
                    .that(containsSubsequence(bytes, noteMarker))
                    .isFalse()
                assertWithMessage("attachment marker leaked in ${file.name}")
                    .that(containsSubsequence(bytes, attachmentMarker))
                    .isFalse()
                assertWithMessage("raw master key leaked in ${file.name}")
                    .that(containsSubsequence(bytes, liveKeySnapshot))
                    .isFalse()
                assertWithMessage("hex master key leaked in ${file.name}")
                    .that(asLatin1)
                    .doesNotContain(liveKeyHex)
            }

            // Assert — the plaintext IS recoverable through the same stack.
            assertThat(store.open("doc-note").use { it.readBytes() }).isEqualTo(noteContent)
            assertThat(store.open("doc-attachment").use { it.readBytes() }).isEqualTo(attachmentContent)

            // Reopen: a fresh unlock (simulating close+reopen of the vault)
            // still reads both back.
            provider.lock()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val reopenedStore = FileAttachmentStore(File(vaultDir, "attachments")) { provider.currentKey()!!.copyOf() }
            assertThat(reopenedStore.open("doc-note").use { it.readBytes() }).isEqualTo(noteContent)
            assertThat(reopenedStore.open("doc-attachment").use { it.readBytes() }).isEqualTo(attachmentContent)
        }

    // ---- zeroization ------------------------------------------------------

    @Test
    fun `lock zeroes the live key and the masterKey seam throws for any caller reading it afterward`() =
        runTest {
            // Arrange
            val vaultDir = tempDir.newDir("vault-lock")
            val provider = newProvider(FileMasterKeyStorage.envelopeFileIn(vaultDir))
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val before = provider.currentKey()!!
            assertThat(before.any { it != 0.toByte() }).isTrue()

            // The exact seam `DeviceVaultOpener.keyCopy()` and
            // `FileAttachmentStore`'s `masterKey` parameter use in
            // production: a fresh copy, or a typed failure when there is
            // nothing to copy.
            val masterKeySeam: () -> ByteArray = { provider.currentKey()?.copyOf() ?: error("vault locked") }

            // Act
            provider.lock()

            // Assert — the SAME array instance is zeroed in place, and the
            // provider no longer hands out a key.
            assertThat(provider.currentKey()).isNull()
            assertThat(before.all { it == 0.toByte() }).isTrue()
            val thrown = runCatching(masterKeySeam).exceptionOrNull()
            assertThat(thrown).isNotNull()
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(thrown!!.message).isEqualTo("vault locked")

            // A FileAttachmentStore built over the now-throwing seam must not
            // write anything to disk either.
            val attachmentsDir = File(vaultDir, "attachments")
            val store = FileAttachmentStore(attachmentsDir, masterKey = masterKeySeam)
            val writeFailure =
                runCatching {
                    store.write("doc-after-lock") { out -> out.write(marker()) }
                }.exceptionOrNull()
            assertThat(writeFailure).isInstanceOf(IllegalStateException::class.java)
            assertThat(File(attachmentsDir, "doc-after-lock").exists()).isFalse()
        }

    @Test
    fun `a live master key copy handed to SkeinSQLiteDriver is zeroed after open`() =
        runTest {
            // Arrange — the same orchestration DeviceVaultOpener uses: a
            // fresh copy of the provider's live key per connection.
            val vaultDir = tempDir.newDir("vault-driver")
            val provider = newProvider(FileMasterKeyStorage.envelopeFileIn(vaultDir))
            provider.setupNoUi()
            provider.unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            val liveKeyCopy = provider.currentKey()!!.copyOf()
            val fake =
                FakeSkeinSQLiteNative().apply {
                    fakeCipherVersion = "4.17.0 community"
                    fakeVecVersion = "v0.1.9"
                }
            val driver = SkeinSQLiteDriver(fake, liveKeyCopy)

            // Act
            driver.open("vault.db").close()

            // Assert — the copy handed to the driver is zeroed; the
            // provider's own retained key is untouched (it's a copy that
            // was wiped, never the master itself).
            assertThat(liveKeyCopy.all { it == 0.toByte() }).isTrue()
            assertThat(provider.currentKey()!!.any { it != 0.toByte() }).isTrue()
        }
}
