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
import android.security.keystore.UserNotAuthenticatedException
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

    /**
     * skein-9psb: aliases whose next [decryptCipher] throws
     * [UserNotAuthenticatedException] — simulating the real Keystore's
     * "device is locked" `Cipher.init` failure (`isDeviceLockedFailure` in
     * `VaultKeyProviderImpl`) without needing a real `AndroidKeyStore`.
     * Unlike `android.security.KeyStoreException` (package-private
     * constructor, cannot be constructed outside `android.security`),
     * `UserNotAuthenticatedException` is public and directly constructible,
     * and matches by type only — the message here is never asserted on.
     */
    val deviceLockedAliases: MutableSet<String> = mutableSetOf()

    /**
     * skein-f9ls: aliases whose next [encryptCipher] returns a Cipher that
     * throws on `doFinal` — simulating a keystore rejection of the wrap
     * (e.g. `UserNotAuthenticatedException`) without needing a real
     * `AndroidKeyStore`. The JVM has no such exception to throw naturally,
     * so this pre-consumes the returned `Cipher` with a throwaway `doFinal`
     * call: `AES/GCM/NoPadding` refuses a second `doFinal` without a fresh
     * IV, so `VaultKeyProviderImpl.wrapUnder`'s real `doFinal(masterBytes)`
     * call throws `IllegalStateException` — a stand-in exception class, but
     * `wrapUnder` only ever surfaces `t.javaClass.simpleName`, so the
     * behaviour under test (cleanup + a typed `Failed`) is exercised
     * identically to the real rejection.
     */
    val rejectWrapForAlias: MutableSet<String> = mutableSetOf()

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
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        if (alias in rejectWrapForAlias) {
            // Consume this cipher instance so the caller's own `doFinal`
            // throws — see `rejectWrapForAlias`'s KDoc.
            cipher.doFinal(ByteArray(0))
        }
        return cipher
    }

    override fun decryptCipher(
        alias: String,
        iv: ByteArray,
    ): Cipher {
        checkNotInvalidated(alias)
        if (alias in deviceLockedAliases) {
            // skein-9psb: simulate the platform's behaviour on a locked
            // device — Cipher.init throws before any authentication step.
            throw UserNotAuthenticatedException("simulated: device locked")
        }
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
