// skein-v9g (E3.I11) — the passphrase floor and the length + class-count
// meter. One behaviour per test, AAA structure.

package app.skein.core.vault.key

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PassphraseStrengthTest {
    @Test
    fun `a passphrase below the minimum length scores TOO_SHORT`() {
        // Arrange
        val passphrase = "Ab3!Ab3!Ab3".toCharArray() // 11 characters, all four classes
        // Act
        val score = PassphraseStrength.evaluate(passphrase)
        // Assert
        assertThat(score).isEqualTo(PassphraseStrength.Score.TOO_SHORT)
    }

    @Test
    fun `exactly the minimum length clears the floor`() {
        // Arrange
        val passphrase = "aaaaaaaaaaaa".toCharArray() // 12 characters
        // Act
        val score = PassphraseStrength.evaluate(passphrase)
        // Assert
        assertThat(score).isNotEqualTo(PassphraseStrength.Score.TOO_SHORT)
    }

    @Test
    fun `minimum length with a single character class scores WEAK`() {
        // Arrange
        val passphrase = "aaaaaaaaaaaa".toCharArray()
        // Act
        val score = PassphraseStrength.evaluate(passphrase)
        // Assert
        assertThat(score).isEqualTo(PassphraseStrength.Score.WEAK)
    }

    @Test
    fun `minimum length with three character classes scores FAIR`() {
        // Arrange
        val passphrase = "aaaaaaaaaA9x".toCharArray() // 12 chars: lower + upper + digit
        // Act
        val score = PassphraseStrength.evaluate(passphrase)
        // Assert
        assertThat(score).isEqualTo(PassphraseStrength.Score.FAIR)
    }

    @Test
    fun `a long single-class passphrase reaches STRONG on length alone`() {
        // Arrange — 24 lowercase characters, the memorised-sentence case
        val passphrase = "correcthorsebatterystapl".toCharArray()
        // Act
        val score = PassphraseStrength.evaluate(passphrase)
        // Assert
        assertThat(score).isEqualTo(PassphraseStrength.Score.STRONG)
    }

    @Test
    fun `a mixed twenty-character passphrase scores STRONG`() {
        // Arrange — 20 chars, lower + upper + digit
        val passphrase = "aaaaaaaaaaaaaaaaA9xQ".toCharArray()
        // Act
        val score = PassphraseStrength.evaluate(passphrase)
        // Assert
        assertThat(score).isEqualTo(PassphraseStrength.Score.STRONG)
    }

    @Test
    fun `class count sees all four classes`() {
        // Arrange
        val passphrase = "aA1!".toCharArray()
        // Act
        val classes = PassphraseStrength.classCount(passphrase)
        // Assert
        assertThat(classes).isEqualTo(4)
    }

    @Test
    fun `class count sees a single class`() {
        // Arrange
        val passphrase = "aaaa".toCharArray()
        // Act
        val classes = PassphraseStrength.classCount(passphrase)
        // Assert
        assertThat(classes).isEqualTo(1)
    }

    @Test
    fun `only TOO_SHORT is unacceptable`() {
        // Arrange
        val scores = PassphraseStrength.Score.entries
        // Act
        val unacceptable = scores.filterNot(PassphraseStrength::isAcceptable)
        // Assert
        assertThat(unacceptable).containsExactly(PassphraseStrength.Score.TOO_SHORT)
    }

    @Test
    fun `evaluate does not mutate the caller's passphrase`() {
        // Arrange
        val passphrase = "correcthorsebattery".toCharArray()
        val before = passphrase.copyOf()
        // Act
        PassphraseStrength.evaluate(passphrase)
        // Assert
        assertThat(passphrase).isEqualTo(before)
    }
}
