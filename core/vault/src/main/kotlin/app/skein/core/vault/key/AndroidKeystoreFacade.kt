// skein-3el (E3.I2) — real `AndroidKeyStore` backend for [KeystoreFacade].
//
// See [KeystoreFacade] for the abstraction contract. This class wires it
// to `AndroidKeyStore` using the exact `KeyGenParameterSpec` from
// `ATTACHMENT_ENCRYPTION.md` §2.3 for both Layer-0 aliases. On StrongBox
// unavailability the caller retries with `requireStrongBox=false`; this
// class does NOT swallow `StrongBoxUnavailableException` — the retry
// decision belongs to `VaultKeyProviderImpl` which also updates the
// persisted `strong_box_backed` flag.

package app.skein.core.vault.key

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystoreFacade(
    private val context: Context,
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) },
) : KeystoreFacade {
    override fun hasStrongBox(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    override fun createKey(
        alias: String,
        factor: VaultKeyProvider.Factor,
        requireStrongBox: Boolean,
    ) {
        val authType =
            when (factor) {
                VaultKeyProvider.Factor.BIOMETRIC -> KeyProperties.AUTH_BIOMETRIC_STRONG
                VaultKeyProvider.Factor.DEVICE_CREDENTIAL -> KeyProperties.AUTH_DEVICE_CREDENTIAL
            }
        val spec =
            KeyGenParameterSpec
                .Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).apply {
                    setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    setKeySize(KEY_SIZE_BITS)
                    setUserAuthenticationRequired(true)
                    // 0-second validity = per-use authentication (§2.3).
                    setUserAuthenticationParameters(0, authType)
                    setUnlockedDeviceRequired(true)
                    if (factor == VaultKeyProvider.Factor.BIOMETRIC) {
                        setInvalidatedByBiometricEnrollment(true)
                    }
                    if (requireStrongBox) {
                        setIsStrongBoxBacked(true)
                    }
                }.build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        gen.init(spec)
        gen.generateKey()
    }

    override fun deleteEntry(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    override fun containsAlias(alias: String): Boolean = keyStore.containsAlias(alias)

    override fun encryptCipher(alias: String): Cipher {
        val key = keyStore.getKey(alias, null) as SecretKey
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(
        alias: String,
        iv: ByteArray,
    ): Cipher {
        val key = keyStore.getKey(alias, null) as SecretKey
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    override fun rawKeyStoreOrNull(): KeyStore = keyStore

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
    }
}
