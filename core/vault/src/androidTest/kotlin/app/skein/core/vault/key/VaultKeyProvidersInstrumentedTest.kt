// skein-txrh — instrumented coverage for [VaultKeyProviders.forDevice] over
// the REAL backends (`AndroidKeystoreFacade`, `AndroidBiometricAuthenticator`,
// `FileMasterKeyStorage` on the device filesystem). Compiled unconditionally
// so the production wiring stays type-safe under refactors; the on-device
// run is gated by skein-k3b2 (emulator provisioning), like every other
// `*InstrumentedTest` in the repo.
//
// No test here presents a `BiometricPrompt`: a per-use-auth Keystore key
// (`ATTACHMENT_ENCRYPTION.md` §2.3) cannot complete a wrap or unwrap without
// a driven prompt, so the full `setup → lock → unlock → currentKey()` round
// trip belongs to the k3b2 lane's scripted-biometric run. What CAN be proven
// deterministically is everything up to the prompt: the factory builds the
// real provider without touching disk or Keystore, a missing envelope is
// `NotInitialised`, the envelope round-trips and rewraps atomically on the
// real filesystem, a no-biometric device leaves no partial state, and a
// real auth-bound alias is reached through the envelope but yields no key
// without authentication.

package app.skein.core.vault.key

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.vault.lifecycle.VaultPaths
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultKeyProvidersInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vaultDir: File
    private lateinit var keystore: AndroidKeystoreFacade

    @Before
    fun setUp() {
        vaultDir = File(context.cacheDir, "key-providers-test-${System.nanoTime()}").apply { mkdirs() }
        keystore = AndroidKeystoreFacade(context)
        cleanUpAliases()
    }

    @After
    fun tearDown() {
        cleanUpAliases()
        vaultDir.deleteRecursively()
    }

    private fun cleanUpAliases() {
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_BIOMETRIC)
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_CREDENTIAL)
    }

    private fun envelope(): File = FileMasterKeyStorage.envelopeFileIn(vaultDir)

    private fun provider(): VaultKeyProviderImpl =
        VaultKeyProviders.forDevice(context, VaultPaths(vaultDir = vaultDir)) as VaultKeyProviderImpl

    @Test
    fun forDevice_builds_the_real_provider_locked_and_without_touching_disk() {
        // Act
        val provider = provider()
        // Assert — locked, and construction wrote nothing under the vault dir.
        assertThat(provider.currentKey()).isNull()
        assertThat(envelope().exists()).isFalse()
        assertThat(File(vaultDir, FileMasterKeyStorage.KEYS_DIR_NAME).exists()).isFalse()
    }

    @Test
    fun unlock_before_setup_reports_NotInitialised_and_creates_nothing() =
        runBlocking {
            // Act
            val result = provider().unlockNoUi(VaultKeyProvider.Factor.BIOMETRIC)
            // Assert — the envelope is consulted before any Keystore work.
            assertThat(result).isEqualTo(UnlockResult.NotInitialised)
            assertThat(envelope().exists()).isFalse()
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isFalse()
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isFalse()
        }

    @Test
    fun envelope_round_trips_and_rewraps_on_the_device_filesystem() {
        // Arrange — synthetic wrapped bytes: this exercises the file, not the cipher.
        val storage = FileMasterKeyStorage(envelope())
        val initial =
            MasterKeyRow(
                keyVersion = 1,
                wrappedBytesBiometric = ByteArray(48) { it.toByte() },
                wrapIvBiometric = ByteArray(12) { (it + 1).toByte() },
                wrapTagBiometric = null,
                wrappedBytesCredential = ByteArray(48) { (0x40 + it).toByte() },
                wrapIvCredential = ByteArray(12) { (0x20 + it).toByte() },
                wrapTagCredential = null,
                createdAt = 1L,
                strongBoxBacked = keystore.hasStrongBox(),
            )
        // Act
        storage.writeInitial(initial)
        val readBack = storage.readActive()
        val newVersion =
            storage.rewrap(1, VaultKeyProvider.Factor.BIOMETRIC, ByteArray(48) { 0x7f }, ByteArray(12), null, 2L)
        // Assert
        assertThat(readBack).isEqualTo(initial)
        assertThat(newVersion).isEqualTo(2)
        assertThat(FileMasterKeyStorage(envelope()).readActive()!!.wrappedBytesCredential)
            .isEqualTo(initial.wrappedBytesCredential)
        assertThat(envelope().parentFile!!.list()!!.toList()).containsExactly(FileMasterKeyStorage.ENVELOPE_FILE_NAME)
    }

    @Test
    fun setup_without_an_enrolled_biometric_leaves_no_envelope_and_no_aliases() =
        runBlocking {
            // Arrange — only deterministic on a device that cannot present a
            // strong biometric at all (the k3b2 lane's default emulator).
            val can = AndroidBiometricAuthenticator(context).canAuthenticate(VaultKeyProvider.Factor.BIOMETRIC)
            assumeTrue(
                "needs a device with no enrolled strong biometric (got $can)",
                can is CanAuthenticate.NoneEnrolled || can is CanAuthenticate.HardwareUnavailable,
            )
            // Act
            val result = provider().setupNoUi()
            // Assert — refused early (§3.2), nothing persisted, no partial Keystore state.
            assertThat(result).isNotInstanceOf(SetupResult.Success::class.java)
            assertThat(result).isNotInstanceOf(SetupResult.StrongBoxUnavailableFallback::class.java)
            assertThat(envelope().exists()).isFalse()
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_BIOMETRIC)).isFalse()
            assertThat(keystore.containsAlias(VaultKeyProviderImpl.ALIAS_CREDENTIAL)).isFalse()
        }

    @Test
    fun unlock_over_a_real_auth_bound_alias_yields_no_key_without_authentication() =
        runBlocking {
            // Arrange — provision the credential alias directly (needs a secure
            // lock screen; otherwise the platform refuses auth-bound keys) and a
            // synthetic envelope pointing at it.
            val created =
                runCatching {
                    keystore.createKey(
                        VaultKeyProviderImpl.ALIAS_CREDENTIAL,
                        VaultKeyProvider.Factor.DEVICE_CREDENTIAL,
                        requireStrongBox = false,
                    )
                }
            assumeTrue(
                "needs a secure lock screen: ${created.exceptionOrNull()?.javaClass?.simpleName}",
                created.isSuccess,
            )
            FileMasterKeyStorage(envelope()).writeInitial(
                MasterKeyRow(
                    keyVersion = 1,
                    wrappedBytesBiometric = null,
                    wrapIvBiometric = null,
                    wrapTagBiometric = null,
                    wrappedBytesCredential = ByteArray(48) { it.toByte() },
                    wrapIvCredential = ByteArray(12) { it.toByte() },
                    wrapTagCredential = null,
                    createdAt = 1L,
                    strongBoxBacked = false,
                ),
            )
            val provider = provider()
            // Act — no prompt is driven, so the per-use-auth cipher must refuse.
            val result = provider.unlockNoUi(VaultKeyProvider.Factor.DEVICE_CREDENTIAL)
            // Assert — the envelope was read and the Keystore path reached
            // (not NotInitialised), but the auth binding held: no key.
            assertThat(result).isInstanceOf(UnlockResult.Failed::class.java)
            assertThat(provider.currentKey()).isNull()
        }
}
