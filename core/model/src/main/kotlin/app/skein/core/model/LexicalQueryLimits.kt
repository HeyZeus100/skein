package app.skein.core.model

/** Bounds shared by literal FTS queries and post-ingest row probes. */
public object LexicalQueryLimits {
    public const val MAX_TERMS: Int = 128
    public const val MAX_TERM_UTF8_BYTES: Int = 512
    public const val MAX_TEXT_UTF8_BYTES: Int = 65_536
}
