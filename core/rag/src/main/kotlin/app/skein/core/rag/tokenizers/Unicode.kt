package app.skein.core.rag.tokenizers

/**
 * The Unicode character predicates the Hugging Face pipeline relies on.
 *
 * These deliberately re-implement Rust's definitions rather than reaching for
 * the closest-looking `java.lang.Character` method, because the closest-looking
 * one is usually subtly different (`Character.isWhitespace` excludes U+00A0,
 * which Rust's `char::is_whitespace` includes, for instance).
 */
internal object Unicode {
    /** Rust `char::is_whitespace` — the Unicode `White_Space` property. */
    fun isWhitespace(cp: Int): Boolean =
        when (cp) {
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
            -> true
            else -> cp in 0x2000..0x200A
        }

    /**
     * `BertNormalizer::is_whitespace` — the `White_Space` property, with tab,
     * newline and carriage return forced in (they are control characters that
     * BERT counts as whitespace).
     */
    fun isBertWhitespace(cp: Int): Boolean =
        when (cp) {
            0x09, 0x0A, 0x0D -> true
            else -> isWhitespace(cp)
        }

    /**
     * `BertNormalizer::is_control` — general category group `C`
     * (Cc, Cf, Co, Cs, Cn), minus tab/newline/carriage return.
     */
    fun isBertControl(cp: Int): Boolean {
        if (cp == 0x09 || cp == 0x0A || cp == 0x0D) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL,
            Character.FORMAT,
            Character.PRIVATE_USE,
            Character.SURROGATE,
            Character.UNASSIGNED,
            -> true
            else -> false
        }
    }

    /** `BertNormalizer::is_chinese_char` — the CJK Unified Ideographs blocks. */
    fun isChineseChar(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2B73F ||
            cp in 0x2B740..0x2B81F ||
            cp in 0x2B920..0x2CEAF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x2F800..0x2FA1F

    /** `unicode_categories::is_mark_nonspacing` — general category `Mn`. */
    fun isNonSpacingMark(cp: Int): Boolean = Character.getType(cp).toByte() == Character.NON_SPACING_MARK

    /** `BertPreTokenizer::is_bert_punc` — ASCII punctuation plus category group `P`. */
    fun isBertPunctuation(cp: Int): Boolean {
        if (cp < 0x80) {
            return (cp in 0x21..0x2F) || (cp in 0x3A..0x40) || (cp in 0x5B..0x60) || (cp in 0x7B..0x7E)
        }
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }
    }
}
