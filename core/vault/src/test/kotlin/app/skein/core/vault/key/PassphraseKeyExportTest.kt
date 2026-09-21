// skein-v9g (E3.I11) — JVM tests for the passphrase recovery envelope.
//
// Most tests drive `exportWith`/`importWith` at a low iteration count: at
// the production 600 000 a single round trip costs roughly a second, and
// these assertions are about the envelope and the failure taxonomy, not
// about the cost of the KDF. `productionParametersRoundTrip` covers the
// real constants once, and `Pbkdf2VectorTest` pins the KDF itself against
// published vectors.
//
// One behaviour per test, AAA structure.

package app.skein.core.vault.key

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class PassphraseKeyExportTest {
    private val master = ByteArray(32) { (it * 7 + 3).toByte() }
    private val passphrase = "correct-horse-battery-staple".toCharArray()
    private val testIterations = 1_000

    private fun export(
        key: ByteArray = master,
        pass: CharArray = passphrase,
        iterations: Int = testIterations,
    ): ByteArray = PassphraseKeyExport.exportWith(key, pass, iterations, SecureRandom())

    private fun import(
        bytes: ByteArray,
        pass: CharArray = passphrase,
    ): ByteArray = PassphraseKeyExport.importWith(bytes, pass, minimumIterations = 1)

    // ---- round trip ----------------------------------------------------

    @Test
    fun `export then import with the same passphrase recovers the master key`() {
        // Arrange
        val file = export()
        // Act
        val recovered = import(file)
        // Assert
        assertThat(recovered).isEqualTo(master)
    }

    @Test
    fun `round trip at the production parameters recovers the master key`() {
        // Arrange
        val file = PassphraseKeyExport.export(master, passphrase)
        // Act
        val recovered = PassphraseKeyExport.import(file, passphrase)
        // Assert
        assertThat(recovered).isEqualTo(master)
    }

    @Test
    fun `the exported file declares the pinned KDF and iteration count`() {
        // Arrange
        val file = PassphraseKeyExport.export(master, passphrase)
        // Act
        val text = String(file, Charsets.US_ASCII)
        // Assert
        assertThat(text).contains("\"kdf\":\"PBKDF2WithHmacSHA256\"")
        assertThat(text).contains("\"iterations\":600000")
    }

    @Test
    fun `two exports of the same key under the same passphrase differ`() {
        // Arrange — a fresh salt and IV per export is what keeps (key, nonce) unique
        val first = export()
        // Act
        val second = export()
        // Assert
        assertThat(first).isNotEqualTo(second)
    }

    // ---- confidentiality -----------------------------------------------

    @Test
    fun `the exported file contains no window of the raw master key`() {
        // Arrange
        val file = export()
        // Act
        val containsRaw = indexOfSubsequence(file, master) >= 0
        // Assert
        assertThat(containsRaw).isFalse()
    }

    @Test
    fun `the exported file contains no hex rendering of the master key`() {
        // Arrange
        val file = export()
        val hex = master.joinToString("") { "%02x".format(it) }
        // Act
        val text = String(file, Charsets.US_ASCII).lowercase()
        // Assert
        assertThat(text).doesNotContain(hex)
    }

    @Test
    fun `the exported file contains no window of the passphrase`() {
        // Arrange
        val file = export()
        // Act
        val text = String(file, Charsets.US_ASCII)
        // Assert
        assertThat(text).doesNotContain(String(passphrase))
    }

    // ---- failure taxonomy ----------------------------------------------

    @Test
    fun `a wrong passphrase fails with WRONG_PASSPHRASE`() {
        // Arrange
        val file = export()
        // Act
        val thrown =
            assertThrows(RecoveryFailedException::class.java) {
                import(file, "wrong-passphrase-here".toCharArray())
            }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.WRONG_PASSPHRASE)
    }

    @Test
    fun `a wrong passphrase never yields a different key instead of failing`() {
        // Arrange
        val file = export()
        // Act
        val recovered = runCatching { import(file, "another-wrong-passphrase".toCharArray()) }
        // Assert
        assertThat(recovered.isFailure).isTrue()
    }

    @Test
    fun `a tampered ciphertext fails with the same reason as a wrong passphrase`() {
        // Arrange — flip a bit inside the base64 ciphertext blob
        val file = export()
        val tampered = flipOneCiphertextChar(file)
        // Act
        val thrown = assertThrows(RecoveryFailedException::class.java) { import(tampered) }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.WRONG_PASSPHRASE)
    }

    @Test
    fun `a rewritten iteration count fails the AAD-bound tag check`() {
        // Arrange — same digit count so the document stays canonical
        val file = String(export(iterations = 1_000), Charsets.US_ASCII)
        val tampered = file.replace("\"iterations\":1000", "\"iterations\":2000")
        // Act
        val thrown =
            assertThrows(RecoveryFailedException::class.java) {
                PassphraseKeyExport.importWith(tampered.toByteArray(Charsets.US_ASCII), passphrase, 1)
            }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.WRONG_PASSPHRASE)
    }

    @Test
    fun `a file that is not a recovery export fails with MALFORMED`() {
        // Arrange
        val file = "{\"hello\":\"world\"}".toByteArray(Charsets.US_ASCII)
        // Act
        val thrown = assertThrows(RecoveryFailedException::class.java) { import(file) }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.MALFORMED)
    }

    @Test
    fun `trailing bytes after the document fail with MALFORMED`() {
        // Arrange
        val file = String(export(), Charsets.US_ASCII) + "\n"
        // Act
        val thrown =
            assertThrows(RecoveryFailedException::class.java) {
                PassphraseKeyExport.importWith(file.toByteArray(Charsets.US_ASCII), passphrase, 1)
            }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.MALFORMED)
    }

    @Test
    fun `a newer format version fails with UNSUPPORTED_VERSION`() {
        // Arrange
        val file = String(export(), Charsets.US_ASCII).replace("\"version\":1", "\"version\":2")
        // Act
        val thrown =
            assertThrows(RecoveryFailedException::class.java) {
                PassphraseKeyExport.importWith(file.toByteArray(Charsets.US_ASCII), passphrase, 1)
            }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.UNSUPPORTED_VERSION)
    }

    @Test
    fun `a downgraded iteration count is refused before any derivation`() {
        // Arrange — a file that asks for cheap stretching, imported at the production floor
        val file = export(iterations = 1_000)
        // Act
        val thrown =
            assertThrows(RecoveryFailedException::class.java) {
                PassphraseKeyExport.import(file, passphrase)
            }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.WEAK_PARAMETERS)
    }

    @Test
    fun `an absurd iteration count is refused rather than run`() {
        // Arrange
        val file =
            String(export(), Charsets.US_ASCII)
                .replace("\"iterations\":$testIterations", "\"iterations\":999999999")
        // Act
        val thrown =
            assertThrows(RecoveryFailedException::class.java) {
                PassphraseKeyExport.importWith(file.toByteArray(Charsets.US_ASCII), passphrase, 1)
            }
        // Assert
        assertThat(thrown.reason).isEqualTo(RecoveryFailedException.Reason.WEAK_PARAMETERS)
    }

    @Test
    fun `failure messages carry no key or passphrase material`() {
        // Arrange
        val file = export()
        // Act
        val thrown =
            assertThrows(RecoveryFailedException::class.java) {
                import(file, "still-the-wrong-one".toCharArray())
            }
        // Assert
        assertThat(thrown.message).doesNotContain(String(passphrase))
    }

    // ---- floor and buffer hygiene --------------------------------------

    @Test
    fun `a passphrase below the floor is refused`() {
        // Arrange
        val short = "short".toCharArray()
        // Act
        val thrown = assertThrows(PassphraseTooShortException::class.java) { export(pass = short) }
        // Assert
        assertThat(thrown.minimumLength).isEqualTo(PassphraseStrength.MINIMUM_LENGTH)
    }

    @Test
    fun `a master key of the wrong length is refused`() {
        // Arrange
        val tooShort = ByteArray(16)
        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) { export(key = tooShort) }
    }

    @Test
    fun `export does not mutate the caller's master key`() {
        // Arrange
        val before = master.copyOf()
        // Act
        export()
        // Assert
        assertThat(master).isEqualTo(before)
    }

    @Test
    fun `export does not mutate the caller's passphrase`() {
        // Arrange
        val pass = "correct-horse-battery-staple".toCharArray()
        val before = pass.copyOf()
        // Act
        PassphraseKeyExport.exportWith(master, pass, testIterations, SecureRandom())
        // Assert
        assertThat(pass).isEqualTo(before)
    }

    @Test
    fun `import does not mutate the caller's passphrase`() {
        // Arrange
        val file = export()
        val pass = "correct-horse-battery-staple".toCharArray()
        val before = pass.copyOf()
        // Act
        import(file, pass)
        // Assert
        assertThat(pass).isEqualTo(before)
    }

    @Test
    fun `a failed import leaves no recovered plaintext behind to return`() {
        // Arrange — the only observable proof available to a unit test is that
        // nothing is handed back on the failing path (the internal buffers are
        // zeroed in `finally`; see PassphraseKeyExport's header).
        val file = export()
        // Act
        val outcome = runCatching { import(file, "definitely-not-the-one".toCharArray()) }
        // Assert
        assertThat(outcome.getOrNull()).isNull()
    }

    // ---- helpers -------------------------------------------------------

    private fun indexOfSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    /** Replaces one character of the base64 ciphertext with a different base64 character. */
    private fun flipOneCiphertextChar(file: ByteArray): ByteArray {
        val text = String(file, Charsets.US_ASCII)
        val marker = "\"ciphertext\":\""
        val start = text.indexOf(marker) + marker.length
        val original = text[start]
        val replacement = if (original == 'A') 'B' else 'A'
        return (text.substring(0, start) + replacement + text.substring(start + 1))
            .toByteArray(Charsets.US_ASCII)
    }
}
