// skein-3el (E3.I2) — JVM-side fake for [KeystoreFacade].
//
// Backs each Layer-0 alias with a plain JCE `AES/GCM/NoPadding` key so
// wrap and unwrap round-trip correctly on the host JVM. Simulated
// invalidation is done by writing a sentinel into [invalidatedAliases];
// [encryptCipher] and [decryptCipher] then throw
// `KeyPermanentlyInvalidatedException` for those aliases exactly like
// the real Keystore.

package app.skein.core.vault.key

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class FakeKeystoreFacade(
    private val strongBoxAvailable: Boolean = true,
) : KeystoreFacade {
    private val keys = mutableMapOf<String, SecretKey>()
    val invalidatedAliases: MutableSet<String> = mutableSetOf()
    val createCalls: MutableList<Triple<String, VaultKeyProvider.Factor, Boolean>> = mutableListOf()
    var strongBoxProbed: Boolean = false
        private set

    override fun hasStrongBox(): Boolean {
        strongBoxProbed = true
        return strongBoxAvailable
    }

    override fun createKey(
        alias: String,
        factor: VaultKeyProvider.Factor,
        requireStrongBox: Boolean,
    ) {
        createCalls += Triple(alias, factor, requireStrongBox)
        // Simulated: a fresh alias also clears its invalidation flag.
        invalidatedAliases.remove(alias)
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256, SecureRandom())
        keys[alias] = gen.generateKey()
    }

    override fun deleteEntry(alias: String) {
        keys.remove(alias)
        invalidatedAliases.remove(alias)
    }

    override fun containsAlias(alias: String): Boolean = keys.containsKey(alias)

    override fun encryptCipher(alias: String): Cipher {
        checkNotInvalidated(alias)
        val key = keys[alias] ?: error("no key at alias '$alias'")
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptCipher(
        alias: String,
        iv: ByteArray,
    ): Cipher {
        checkNotInvalidated(alias)
        val key = keys[alias] ?: error("no key at alias '$alias'")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    private fun checkNotInvalidated(alias: String) {
        if (alias in invalidatedAliases) {
            // Simulate the platform's behaviour: Cipher.init throws
            // KeyPermanentlyInvalidatedException on an invalidated alias.
            throw KeyPermanentlyInvalidatedException("simulated invalidation: $alias")
        }
    }
}
