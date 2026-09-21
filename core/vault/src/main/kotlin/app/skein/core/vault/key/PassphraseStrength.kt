// skein-v9g (E3.I11) — the passphrase strength floor and meter for the
// recovery-key export.
//
// Deliberately zxcvbn-free (bd `skein-v9g`: "≥ 12 chars, zxcvbn-free
// strength meter using length + class count"). No dictionary, no wordlist,
// no third-party dependency — a pure function of the passphrase's length
// and how many character classes it draws from. The floor
// ([MINIMUM_LENGTH]) is the only hard gate; the [Score] above it is
// feedback for the user, never an enforcement point.
//
// Lives in `:core:vault` beside `PassphraseKeyExport` — which enforces the
// same floor on its own — rather than in the UI module, so the floor has
// exactly one definition and the UI cannot drift below it.
//
// This file never retains, copies, logs or hashes the passphrase: it reads
// the caller's `CharArray` in place and returns an enum.

package app.skein.core.vault.key

/** Length + character-class strength feedback for a recovery passphrase. */
public object PassphraseStrength {
    /**
     * The hard floor, in characters. `PassphraseKeyExport.export` refuses
     * anything shorter with [PassphraseTooShortException]; the UI must not
     * offer a way past it.
     */
    public const val MINIMUM_LENGTH: Int = 12

    /**
     * How much confidence to show the user. [TOO_SHORT] is the only value
     * that blocks the export — it means the passphrase is below
     * [MINIMUM_LENGTH].
     */
    public enum class Score {
        /** Below [MINIMUM_LENGTH]. Export is refused. */
        TOO_SHORT,

        /** Long enough, but short and drawn from few character classes. */
        WEAK,

        /** Reasonable length or a reasonable mix of character classes. */
        FAIR,

        /** Long and mixed. */
        STRONG,
    }

    /**
     * Scores [passphrase] from its length and the number of character
     * classes it uses (lowercase, uppercase, digit, everything else).
     *
     *  - `points = lengthPoints + (classes - 1)`, where `lengthPoints` is
     *    4 at ≥ 24 characters, 3 at ≥ 20, 2 at ≥ 16, 1 at ≥ 13 and 0 below
     *    that;
     *  - `points ≥ 4` → [Score.STRONG], `points ≥ 2` → [Score.FAIR],
     *    otherwise [Score.WEAK];
     *  - below [MINIMUM_LENGTH] → [Score.TOO_SHORT], whatever the mix.
     *
     * A long single-class passphrase (a memorised sentence) therefore
     * reaches [Score.STRONG] on length alone at 24 characters, which is the
     * point of allowing a passphrase rather than demanding a password.
     */
    public fun evaluate(passphrase: CharArray): Score {
        val length = passphrase.size
        if (length < MINIMUM_LENGTH) return Score.TOO_SHORT
        val points = lengthPoints(length) + (classCount(passphrase) - 1)
        return when {
            points >= STRONG_POINTS -> Score.STRONG
            points >= FAIR_POINTS -> Score.FAIR
            else -> Score.WEAK
        }
    }

    /** Whether [score] clears the floor — i.e. an export may proceed. */
    public fun isAcceptable(score: Score): Boolean = score != Score.TOO_SHORT

    /** How many of {lowercase, uppercase, digit, other} appear at least once. Always ≥ 1 for a non-empty input. */
    public fun classCount(passphrase: CharArray): Int {
        var lower = false
        var upper = false
        var digit = false
        var other = false
        for (c in passphrase) {
            when {
                c in 'a'..'z' -> lower = true
                c in 'A'..'Z' -> upper = true
                c in '0'..'9' -> digit = true
                else -> other = true
            }
        }
        return listOf(lower, upper, digit, other).count { it }
    }

    private fun lengthPoints(length: Int): Int =
        when {
            length >= 24 -> 4
            length >= 20 -> 3
            length >= 16 -> 2
            length >= 13 -> 1
            else -> 0
        }

    private const val STRONG_POINTS = 4
    private const val FAIR_POINTS = 2
}

/**
 * `PassphraseKeyExport.export` was called with a passphrase shorter than
 * [PassphraseStrength.MINIMUM_LENGTH]. Carries no passphrase material — not
 * its content, not its length, only the floor it failed to clear.
 */
public class PassphraseTooShortException internal constructor(
    public val minimumLength: Int,
) : IllegalArgumentException("passphrase is shorter than the $minimumLength-character minimum")
