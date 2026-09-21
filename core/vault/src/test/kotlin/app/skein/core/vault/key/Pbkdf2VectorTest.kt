// skein-v9g (E3.I11) — pins the KDF `PassphraseKeyExport` names.
//
// RFC 6070's vectors are PBKDF2-HMAC-**SHA1**, so they cannot validate the
// SHA-256 variant bd `skein-v9g` pins. The vectors below are the widely
// published PBKDF2-HMAC-SHA256 counterparts of RFC 6070's cases (the same
// password/salt pairs at 1, 2 and 4096 iterations, 32-byte output), used
// across implementations as the de-facto SHA-256 test set. If the platform
// ever resolved `PBKDF2WithHmacSHA256` to something other than PBKDF2 over
// HMAC-SHA-256 — a provider substitution, a Robolectric shim, a future
// Android release — these fail loudly rather than silently producing
// recovery files this build cannot read back.
//
// The vectors are checked through `javax.crypto` directly, not through
// `PassphraseKeyExport`, because the export's own passphrase floor would
// reject the 8-character test password. The export uses exactly this
// algorithm name and output length, so pinning the primitive here pins it
// there too.

package app.skein.core.vault.key

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Pbkdf2VectorTest {
    private fun derive(
        password: String,
        salt: String,
        iterations: Int,
        lengthBytes: Int = 32,
    ): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.US_ASCII), iterations, lengthBytes * 8)
        val bytes = SecretKeyFactory.getInstance(PassphraseKeyExport.KDF_ALGORITHM).generateSecret(spec).encoded
        spec.clearPassword()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches the published vector at one iteration`() {
        // Arrange & Act
        val derived = derive(password = "password", salt = "salt", iterations = 1)
        // Assert
        assertThat(derived).isEqualTo("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches the published vector at two iterations`() {
        // Arrange & Act
        val derived = derive(password = "password", salt = "salt", iterations = 2)
        // Assert
        assertThat(derived).isEqualTo("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43")
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches the published vector at 4096 iterations`() {
        // Arrange & Act
        val derived = derive(password = "password", salt = "salt", iterations = 4096)
        // Assert
        assertThat(derived).isEqualTo("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a")
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches the published multi-part vector`() {
        // Arrange & Act — RFC 6070's fourth case, 40-byte output
        val derived =
            derive(
                password = "passwordPASSWORDpassword",
                salt = "saltSALTsaltSALTsaltSALTsaltSALTsalt",
                iterations = 4096,
                lengthBytes = 40,
            )
        // Assert
        assertThat(derived)
            .isEqualTo("348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9")
    }

    @Test
    fun `the export pins the 600 000-iteration floor for imports`() {
        // Arrange & Act
        val floor = PassphraseKeyExport.MINIMUM_ACCEPTED_ITERATIONS
        // Assert
        assertThat(floor).isEqualTo(PassphraseKeyExport.ITERATIONS)
    }
}
