// Content-addressed document revisions — `docs/design/POST_REVIEW_RESOLUTIONS.md`
// §1 (citation stability across re-ingestion), migration 003
// (`003_document_revisions.sql`).
//
// This file owns the *algorithm* half of §1: how a [RevisionHash] is
// computed from a document's body and frontmatter, and the row shape of
// `document_revisions`. The persistence half lives in
// `VaultRepositoryImpl` / `InMemoryVaultRepository`, behind the
// `VaultRepository.currentRevision` / `getRevision` / `revisionMatches`
// methods in `Vault.kt`.
//
// It lives in `:core:model` (not `:core:vault`) because both the SQL-backed
// repository and the JVM in-memory fake in `:testing` must produce byte-for-byte
// identical hashes — a contract test that passes against one and fails
// against the other would be worse than no test at all.

package us.aherrera.skein.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why a `document_revisions` row was captured. Matches the `reason` column's
 * allowed values in `003_document_revisions.sql` (POST_REVIEW_RESOLUTIONS.md
 * §1.3).
 */
public enum class RevisionReason(
    public val db: String,
) {
    /** The ordinary write path: a document was created or its body/frontmatter edited. */
    INGEST("ingest"),

    /** A re-embed pass (`IngestReason.REEMBED`) re-captured the current content. */
    REEMBED("reembed"),

    /** Content arrived from outside the vault (paste-back, file import). */
    IMPORT("import"),

    /** A revision pinned because it was handed out through an export/share grant (§4). */
    SHARE("share"),
    ;

    public companion object {
        public fun fromDb(s: String): RevisionReason = entries.first { it.db == s }
    }
}

/**
 * One row of `document_revisions` — an immutable snapshot of the exact bytes
 * a [RevisionHash] addresses.
 *
 * `bodyMdSnapshot` and `frontmatterSnapshot` are the *canonicalized* forms
 * that [RevisionHashing.compute] hashed, not the raw column values, so
 * `RevisionHashing.compute(bodyMdSnapshot, frontmatterSnapshot)` always
 * reproduces [revisionHash]. That self-verifying property is what lets the
 * replay path (`docs/design/POST_REVIEW_RESOLUTIONS.md` §1.2 step 3) show a
 * cited excerpt beside the current text with confidence that the archived
 * side was not tampered with.
 */
public data class DocumentRevision(
    val documentId: DocId,
    val revisionHash: RevisionHash,
    /** Monotonic within [documentId]; the highest ord is the most recently captured revision. */
    val revisionOrd: Int,
    val bodyMdSnapshot: String,
    val frontmatterSnapshot: JsonObject,
    val capturedAt: Long,
    val reason: RevisionReason,
)

/**
 * Computes [RevisionHash] values exactly as `docs/design/POST_REVIEW_RESOLUTIONS.md`
 * §1.3 specifies: **BLAKE3-256, hex, over the canonicalized `body_md` plus the
 * normalized frontmatter**.
 *
 * ## The canonical form (normative — changing any of this changes every hash)
 *
 * 1. **Body.** `\r\n` and bare `\r` both become `\n`; a null body (an
 *    attachment, or a document created with no body) canonicalizes to the
 *    empty string. Nothing else is touched — leading/trailing whitespace and
 *    blank lines are content.
 * 2. **Frontmatter.** Serialized as compact JSON with object keys sorted by
 *    Kotlin's natural `String` order, recursively, and arrays left in their
 *    original order. The top-level `id` key is **excluded**: it always equals
 *    `documents.id`, and including it would make two byte-identical notes hash
 *    differently, contradicting the content-addressing property [RevisionHash]
 *    documents.
 * 3. **Framing.** The hashed bytes are
 *    `"skein/revision/v1\u0000"` ‖ `u64le(frontmatterBytes.size)` ‖
 *    `frontmatterBytes` ‖ `bodyBytes`, all UTF-8. The length prefix makes
 *    the concatenation injective, so no frontmatter can be crafted to
 *    collide with a different (frontmatter, body) split, and the
 *    domain-separation prefix keeps revision hashes from ever colliding with
 *    [excerptHash] values or with §2.2's post-mmap model digests. The body
 *    comes last, and unprefixed, precisely so it can be streamed: it is the
 *    only unbounded part, and it never has to exist twice in memory.
 *
 * Consequences the contract suite pins: a CRLF↔LF-only edit does **not**
 * change the hash; reordering frontmatter keys does **not** change it;
 * changing `persona_id`, `title`, `updated_at` or any other column does
 * **not** change it; editing one character of the body does.
 */
public object RevisionHashing {
    private const val DOMAIN: String = "skein/revision/v1\u0000"
    private const val EXCERPT_DOMAIN: String = "skein/excerpt/v1\u0000"
    private const val FRONTMATTER_ID_KEY: String = "id"

    /** Body slice size (in chars) fed to the streaming hasher. Not a tuning constant — any value gives the same hash. */
    private const val SLICE_CHARS: Int = 64 * 1024

    /** The canonical body text that [compute] hashes. */
    public fun canonicalBody(bodyMd: String?): String =
        bodyMd
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?: ""

    /** The canonical (key-sorted, `id`-free, whitespace-free) frontmatter JSON that [compute] hashes. */
    public fun canonicalFrontmatter(frontmatter: JsonObject): String {
        val sb = StringBuilder()
        writeCanonical(JsonObject(frontmatter.filterKeys { it != FRONTMATTER_ID_KEY }), sb)
        return sb.toString()
    }

    /** The §1.3 revision hash of [bodyMd] + [frontmatter]: lowercase hex, 64 characters. */
    public fun compute(
        bodyMd: String?,
        frontmatter: JsonObject,
    ): RevisionHash {
        val fm = canonicalFrontmatter(frontmatter).toByteArray(Charsets.UTF_8)
        val hasher = Blake3.Hasher()
        hasher.update(DOMAIN.toByteArray(Charsets.UTF_8))
        hasher.update(longLe(fm.size.toLong()))
        hasher.update(fm)
        // The body is fed in slices rather than as one `toByteArray()`: a
        // 10 MB note must not cost a second 10 MB array just to be addressed
        // (`ImportTextMemorySmokeTest` holds this line).
        updateWithUtf8(hasher, canonicalBody(bodyMd))
        return hasher.hex()
    }

    /**
     * Feeds [text]'s UTF-8 bytes to [hasher] in bounded slices. Slice
     * boundaries are nudged past a trailing high surrogate so no surrogate
     * pair is ever split — encoding half a pair would emit a replacement
     * character and change the hash.
     */
    private fun updateWithUtf8(
        hasher: Blake3.Hasher,
        text: String,
    ) {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + SLICE_CHARS, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end++
            hasher.update(text.substring(start, end).toByteArray(Charsets.UTF_8))
            start = end
        }
    }

    /**
     * BLAKE3-256 hex of a citation excerpt, for the `excerpt_hash` field of
     * citation-record-v1 (§1.3). Domain-separated from [compute] so an
     * excerpt can never be mistaken for a whole-document revision.
     */
    public fun excerptHash(excerpt: String): String = Blake3.hex((EXCERPT_DOMAIN + excerpt).toByteArray(Charsets.UTF_8))

    private fun longLe(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

    /**
     * Writes [element] as compact JSON with object keys sorted. Numbers and
     * strings go out exactly as `kotlinx.serialization` renders them
     * (`JsonPrimitive.toString()` already quotes and escapes strings), so no
     * escaping logic is duplicated here.
     */
    private fun writeCanonical(
        element: JsonElement,
        sb: StringBuilder,
    ) {
        when (element) {
            is JsonObject -> {
                sb.append('{')
                var first = true
                for (key in element.keys.sorted()) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(JsonPrimitive(key).toString())
                    sb.append(':')
                    writeCanonical(element.getValue(key), sb)
                }
                sb.append('}')
            }

            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeCanonical(item, sb)
                }
                sb.append(']')
            }

            else -> sb.append(element.toString())
        }
    }
}
