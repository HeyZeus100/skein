package app.skein.core.navigation

import java.util.UUID

/**
 * An id that may become saved navigation state: a lowercase canonical RFC 9562
 * UUID (versions 1–8, the RFC variant), validated at construction
 * (`SECURITY_REVIEW_D7.md` M1, M2).
 *
 * Graph `tag:`/`title:` node ids, model filename slugs and foreign frontmatter
 * ids (`project-falcon-notes`) never pass, so they can never become a key; the
 * [Navigator] opens such objects as [TransientKey]s instead (M2a).
 *
 * A regular class rather than a value class, so that reflection sees the
 * `SkeinId` type on every key field (a value class compiles to a bare `String`).
 */
class SkeinId private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is SkeinId && other.value == value

    override fun hashCode(): Int = value.hashCode()

    /** Redacted (M13): a key or state interpolated into a log line or exception message carries no id. */
    override fun toString(): String = "SkeinId(redacted)"

    companion object {
        private const val LENGTH = 36
        private val CANONICAL = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        fun isCanonical(raw: String): Boolean = raw.length == LENGTH && CANONICAL.matches(raw)

        /** Null for anything that is not canonical: the only way to turn a raw id into a saveable one (M2a). */
        fun parse(raw: String?): SkeinId? = raw?.takeIf(::isCanonical)?.let(::SkeinId)

        /** For ids known to be canonical (fixtures, freshly minted ids). The message never carries the value (M13). */
        fun of(raw: String): SkeinId = parse(raw) ?: throw IllegalArgumentException("not a canonical id")

        /** A random UUIDv4, for transient handles (and draft ids when the caller has no UUIDv7 to hand). */
        fun random(): SkeinId = SkeinId(UUID.randomUUID().toString())
    }
}
