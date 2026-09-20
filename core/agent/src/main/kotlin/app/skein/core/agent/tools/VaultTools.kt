package app.skein.core.agent.tools

/**
 * Vault tool primitives — the stable, versioned surface every Skein AI-facing
 * caller reaches through when it needs to read, write, patch, or search vault
 * content. Interfaces only; no implementation lives in this module (skein-fvne).
 *
 * Design overview and rationale: `docs/design/VAULT_TOOL_PRIMITIVES.md`.
 *
 * Design invariants (see the design doc for the full argument):
 *   1. Every write takes an [AuthorizationToken]. v1 mints tokens from user
 *      gestures (the inline-AI menu button tap, an explicit slash command,
 *      the editor save button). v2 mints them from an outer skill scheduler
 *      that consumes the user's Capabilities-panel grants (skein-2chp). The
 *      tool signatures do not care which mints them; they only care that one
 *      is supplied per call.
 *   2. Return types are typed `Result<T, VaultToolError>` values, not
 *      exceptions, so callers (a skill runtime, a UI, an eval harness) can
 *      route failures without a stack unwind.
 *   3. Read primitives never mutate. Write primitives are documented as
 *      idempotent-by-content or explicitly non-idempotent; the caller is
 *      expected to check the doc string on each.
 *   4. Retrieval-shaped results reference documents by
 *      `(document_id, revision_hash)`, never by ephemeral chunk id — this is
 *      the citation-record-v1 stability discipline from skein-uo5n /
 *      `docs/design/POST_REVIEW_RESOLUTIONS.md §1`.
 *
 * This file is interface-only. Implementations land in `:core:vault` and
 * `:core:rag` in later issues (E2/E5). Nothing here should have behavior.
 */

// ---------------------------------------------------------------------------
// Identifiers and value types
// ---------------------------------------------------------------------------

/** UUIDv7 string identifier for a vault document. */
@JvmInline
value class DocId(val value: String)

/**
 * Content-addressed revision identifier: BLAKE3-256(canonicalized body_md +
 * frontmatter) hex, matching `document_revisions.revision_hash` per
 * POST_REVIEW_RESOLUTIONS §1.3.
 */
@JvmInline
value class RevisionHash(val value: String)

/** Vault-normalized tag string (no leading `#`, lowercased, no whitespace). */
@JvmInline
value class Tag(val value: String)

/** A vault persona id (row PK in `personas`). */
@JvmInline
value class PersonaId(val value: String)

/** Vault document kind. Mirrors `documents.kind`. */
enum class DocKind { NOTE, CHAT, ATTACHMENT, AIOUT }

/** Retrieval source kind for [Hit.sourceKind]; matches citation-record-v1. */
enum class SourceKind { VECTOR, LEXICAL, GRAPH, RERANK }

/**
 * A note (or note-shaped document) at a specific revision. Read primitives
 * always return one of these — never a bare string — so callers carry the
 * revision hash alongside the body for stable downstream citation.
 */
data class Note(
    val id: DocId,
    val kind: DocKind,
    val title: String,
    val bodyMd: String,
    val frontmatter: Map<String, Any?>,
    val revisionHash: RevisionHash,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val personaId: PersonaId?,
)

/**
 * A caller-supplied draft of a new (or replacement) note. `id` is null on
 * create; on replace the caller passes the existing DocId. Frontmatter is a
 * loose map here — the implementation is responsible for canonicalization
 * (LF line endings, sorted keys) before hashing.
 */
data class NoteDraft(
    val id: DocId? = null,
    val kind: DocKind = DocKind.NOTE,
    val title: String,
    val bodyMd: String,
    val frontmatter: Map<String, Any?> = emptyMap(),
    val personaId: PersonaId? = null,
)

/** A locator into a note's body_md at a specific revision. */
data class Locator(
    val byteStart: Int,
    val byteEnd: Int,
    val chunkOrd: Int? = null,
)

/**
 * A retrieval hit. Carries `(documentId, revisionHash, locator, excerpt)`
 * so a caller can build a `citation-record-v1` entry directly without a
 * second round-trip. Field shape matches the JSON schema in POST_REVIEW §1.3.
 */
data class Hit(
    val documentId: DocId,
    val revisionHash: RevisionHash,
    val documentTitle: String,
    val locator: Locator,
    val excerpt: String,
    val score: Double,
    val sourceKind: SourceKind,
)

/** Filters that narrow a [VaultSearch.search] call. */
data class SearchFilters(
    val personaId: PersonaId? = null,
    val kinds: Set<DocKind> = emptySet(),
    val tags: Set<Tag> = emptySet(),
    val updatedSinceMillis: Long? = null,
)

// ---------------------------------------------------------------------------
// Patch operations
// ---------------------------------------------------------------------------

/**
 * A structured edit to a note's body_md. All ops are addressed by
 * `(revisionHash, locator)` so a patch built against a specific revision
 * cannot be silently applied to a later one — the implementation must fail
 * with [VaultToolError.RevisionConflict] if the target moved.
 *
 * The op set is deliberately minimal; the Artifact Engine (skein-bnwi) adds
 * a superset in v2. This is the interface v1 write primitives need.
 */
sealed interface PatchOp {
    val baseRevision: RevisionHash

    /** Replace the byte range at [locator] with [replacement]. */
    data class Replace(
        override val baseRevision: RevisionHash,
        val locator: Locator,
        val replacement: String,
    ) : PatchOp

    /** Insert [text] at [locator.byteStart]. `byteEnd` must equal `byteStart`. */
    data class Insert(
        override val baseRevision: RevisionHash,
        val locator: Locator,
        val text: String,
    ) : PatchOp

    /** Delete the byte range at [locator]. */
    data class Delete(
        override val baseRevision: RevisionHash,
        val locator: Locator,
    ) : PatchOp

    /** Merge frontmatter keys; null values delete the key. */
    data class FrontmatterMerge(
        override val baseRevision: RevisionHash,
        val fields: Map<String, Any?>,
    ) : PatchOp
}

// ---------------------------------------------------------------------------
// Personas
// ---------------------------------------------------------------------------

/** A persona record surfaced to a tool caller. */
data class Persona(
    val id: PersonaId,
    val name: String,
    val systemPrompt: String,
    val defaultModelId: String?,
    val createdAtMillis: Long,
)

// ---------------------------------------------------------------------------
// Authorization
// ---------------------------------------------------------------------------

/**
 * A per-call authorization capability. Minted by the surface layer that has
 * the user's intent — the inline-AI menu button, a slash command handler, the
 * editor's save button (v1) — or by the skill dispatcher against the user's
 * Capabilities-panel grants (v2, skein-2chp). The primitives never inspect
 * the token's contents; they only require that a valid one accompany every
 * write, and they emit an audit record (v2) or debug log (v1) referencing
 * [gestureId].
 *
 * The token is opaque to the tool implementation. Its shape is a v2 concern;
 * v1 implementations may treat it as a simple presence check.
 */
data class AuthorizationToken(
    /** Correlates the token with the user gesture (or skill run) that minted it. */
    val gestureId: String,
    /** Human-readable label for the audit surface (e.g. "inline-AI: Improve"). */
    val label: String,
    /** Epoch millis after which the token cannot be used. */
    val notAfterMillis: Long,
)

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Sealed error hierarchy for every primitive. Callers pattern-match on the
 * subtype; the tool never throws for a modeled failure.
 */
sealed interface VaultToolError {
    val message: String

    /** The referenced [DocId] does not exist (or was hard-deleted). */
    data class NotFound(val id: DocId, override val message: String) : VaultToolError

    /** [PatchOp.baseRevision] no longer matches the document's head revision. */
    data class RevisionConflict(
        val docId: DocId,
        val expected: RevisionHash,
        val actual: RevisionHash,
        override val message: String,
    ) : VaultToolError

    /**
     * [AuthorizationToken] was absent, expired, or (v2) lacked the capability
     * for the requested op. v1 implementations return this only for
     * absent/expired tokens.
     */
    data class Unauthorized(override val message: String) : VaultToolError

    /** A wikilink text failed to resolve to any doc, and [VaultLinks.resolveWikilink] was called strict. */
    data class UnresolvedLink(val text: String, override val message: String) : VaultToolError

    /** Draft failed schema/canonicalization checks (empty title, oversized body, etc.). */
    data class InvalidInput(val field: String, override val message: String) : VaultToolError

    /** SQLCipher or index is currently locked (biometric session ended). */
    data class VaultLocked(override val message: String) : VaultToolError

    /** Underlying storage or index reported a fault. Non-modeled; retry may work. */
    data class StorageFailure(override val message: String, val cause: Throwable? = null) : VaultToolError

    /** Retrieval budget (time/candidates) exceeded before k results were assembled. */
    data class RetrievalBudgetExceeded(override val message: String) : VaultToolError
}

/**
 * Loose result type. Not [kotlin.Result] because we want the error to be a
 * sealed hierarchy the caller can exhaust, not a `Throwable`.
 */
sealed interface ToolResult<out T> {
    data class Ok<T>(val value: T) : ToolResult<T>
    data class Err(val error: VaultToolError) : ToolResult<Nothing>
}

// ---------------------------------------------------------------------------
// The primitive interfaces
// ---------------------------------------------------------------------------

/**
 * Read a note by id. Pure; never throws for a modeled failure.
 *
 * Semantics:
 *   Precondition: none (the vault may be locked; [VaultToolError.VaultLocked]
 *   is returned in that case).
 *   Postcondition: the returned [Note] reflects the head revision at read
 *   time. Concurrent writes may produce a later revision; callers that need a
 *   snapshot pin it via [Note.revisionHash].
 *
 * Idempotency: trivially idempotent (read).
 * Concurrency: safe from any thread; the impl is expected to be non-blocking
 * on the caller's dispatcher (suspending).
 *
 * Backing interface: `VaultRepository.getNote(id)` (plan `E2.I2`).
 *
 * Capability tier (skein-2chp): `vault.read` — v1 inline surfaces get this
 * unconditionally; v2 skills must be granted it.
 */
interface VaultReader {
    suspend fun readNote(id: DocId): ToolResult<Note>
}

/**
 * Create or replace a note. Returns the new [DocId] and head [RevisionHash].
 *
 * Semantics:
 *   - If [NoteDraft.id] is null → create a fresh document (fresh UUIDv7). The
 *     draft's title must be non-empty ([VaultToolError.InvalidInput] on empty).
 *   - If [NoteDraft.id] is set → replace the entire body + frontmatter at
 *     that id, producing a new revision. **Not** a merge — use [VaultPatcher]
 *     for partial edits.
 *   - The impl canonicalizes body + frontmatter and computes the revision
 *     hash before insert; identical content is a no-op (returns the existing
 *     head revision with no new row).
 *
 * Idempotency: **content-idempotent** — repeated calls with byte-identical
 * canonicalized content produce no new revision. Two callers writing distinct
 * content race; last writer wins at head, but both revisions persist in
 * `document_revisions` (POST_REVIEW §1.3) so no citation is lost.
 *
 * Concurrency: safe from any thread; the impl serializes writes on the
 * vault's single writer (spec §4.2).
 *
 * Failure modes: [VaultToolError.Unauthorized], [VaultToolError.InvalidInput],
 * [VaultToolError.VaultLocked], [VaultToolError.StorageFailure].
 *
 * Backing interface: `VaultRepository.upsertNote(...)` and `newRevision(...)`
 * (POST_REVIEW §1.5, plan `E2.I2` / `E2.I4`).
 *
 * Capability tier: `vault.write` — v1 gated by user gesture (inline-AI menu,
 * editor save); v2 gated by skill grant.
 */
interface VaultWriter {
    suspend fun writeNote(
        draft: NoteDraft,
        token: AuthorizationToken,
    ): ToolResult<WriteResult>

    data class WriteResult(
        val id: DocId,
        val revisionHash: RevisionHash,
        val wasNoOp: Boolean,
    )
}

/**
 * Apply a sequence of [PatchOp]s to a note atomically.
 *
 * Semantics:
 *   - All ops in the list must share the same [PatchOp.baseRevision] (the
 *     impl asserts; [VaultToolError.InvalidInput] otherwise).
 *   - Ops apply in order; byte offsets in later ops refer to the *original*
 *     revision, not the intermediate state. The impl is responsible for
 *     resolving overlapping ops or rejecting them.
 *   - If [PatchOp.baseRevision] no longer matches the head at write time
 *     the whole batch fails with [VaultToolError.RevisionConflict] — the
 *     caller retries by re-reading, rebuilding ops against the new
 *     revision, and re-invoking.
 *
 * Idempotency: **not** idempotent (the same patch applied twice moves the
 * head twice unless the second call trips a revision conflict, which it
 * will if the head advanced). Callers must not blindly retry on success.
 *
 * Concurrency: serialized behind the same writer lock as [VaultWriter].
 *
 * Backing interface: `VaultRepository.applyPatch(docId, ops)` — new method
 * to be added at plan `E2.I4` alongside `newRevision`.
 *
 * Capability tier: `vault.write`.
 */
interface VaultPatcher {
    suspend fun patchNote(
        id: DocId,
        ops: List<PatchOp>,
        token: AuthorizationToken,
    ): ToolResult<VaultWriter.WriteResult>
}

/**
 * Full-text + vector + graph retrieval over the vault. Wraps the plan's
 * `RetrievalService.retrieveContext(query, k, personaId)` and shapes it into
 * the tool primitive the AI-facing surfaces call directly.
 *
 * Semantics:
 *   - Returns up to `k` hits, each with `(document_id, revision_hash,
 *     locator, excerpt)` per citation-record-v1.
 *   - [SearchFilters.personaId] scopes retrieval to that persona's ACL
 *     (v2); v1 passes null.
 *   - Ranking uses the plan's vector + BM25 + PPR pipeline (spec §7.2).
 *
 * Idempotency: read-only. Deterministic given the same vault state, `k`,
 * filters, and query.
 *
 * Concurrency: safe from any thread. The impl may share a small connection
 * pool with the ingest pipeline (POST_REVIEW §5 mentions serialization on
 * writes only).
 *
 * Failure modes: [VaultToolError.VaultLocked],
 * [VaultToolError.RetrievalBudgetExceeded], [VaultToolError.StorageFailure].
 *
 * Backing interface: `RetrievalService` (plan `E5.I13`) — with `Hit` mapping
 * onto `Retrieved` per POST_REVIEW §1.3.
 *
 * Capability tier: `vault.search` — freely available to inline surfaces;
 * skills need explicit grant so retrieved data cannot cross persona ACLs
 * without an audit event (skein-2chp).
 */
interface VaultSearch {
    suspend fun search(
        query: String,
        k: Int = 8,
        filters: SearchFilters = SearchFilters(),
    ): ToolResult<List<Hit>>
}

/**
 * Wikilink resolution and backlink enumeration.
 *
 * Semantics:
 *   - [resolveWikilink] takes the raw text inside `[[...]]` and an optional
 *     `contextId` (the doc doing the linking). Resolution honors title
 *     aliases and anchor suffixes. If [strict] is true, an unresolvable link
 *     returns [VaultToolError.UnresolvedLink]; else the result is `Ok(null)`.
 *   - [listBacklinks] returns doc ids whose `edges` row targets [id] with
 *     kind `wikilink`.
 *
 * Backing interface: `VaultRepository.resolveWikilink(...)`, `edges` table
 * query — both surfaces the ingest pipeline (spec §7.1 steps 5–6) already
 * populates.
 *
 * Capability tier: `vault.read` — link resolution is a read.
 */
interface VaultLinks {
    suspend fun resolveWikilink(
        text: String,
        contextId: DocId? = null,
        strict: Boolean = false,
    ): ToolResult<DocId?>

    suspend fun listBacklinks(id: DocId): ToolResult<List<DocId>>
}

/**
 * Tag-based enumeration. Tags come from frontmatter `tags:` arrays,
 * canonicalized by the ingest pipeline.
 *
 * Idempotency / concurrency: read-only.
 *
 * Backing interface: `VaultRepository.listByTag(tag)` (plan `E2.I2`, needs
 * to be added if not already present).
 */
interface VaultTags {
    suspend fun listByTag(tag: Tag): ToolResult<List<DocId>>
}

/**
 * Persona enumeration for AI-facing surfaces (chat model picker, skill
 * dispatcher scoping).
 *
 * Backing interface: `PersonaService.get(id)` (spec §16 locked at M0.5).
 *
 * Capability tier: `personas.read`. Personas are not secrets, but skills
 * that enumerate them get a distinct grant so an audit trail exists for
 * anything that fans out across the whole persona set.
 */
interface VaultPersonas {
    suspend fun enumeratePersona(id: PersonaId): ToolResult<Persona>
}

// ---------------------------------------------------------------------------
// Aggregate view
// ---------------------------------------------------------------------------

/**
 * Bundle every primitive together for callers that need the whole surface
 * (e.g. the inline-AI menu handler, a skill dispatcher). Deliberately not a
 * god-object interface — each primitive stays its own interface so a test
 * fake can implement one without stubbing the rest.
 */
interface VaultTools :
    VaultReader,
    VaultWriter,
    VaultPatcher,
    VaultSearch,
    VaultLinks,
    VaultTags,
    VaultPersonas
