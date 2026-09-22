// skein-0m1z (docs/design/POST_REVIEW_RESOLUTIONS.md §4.3) — the persistence
// side of the staged-plaintext lifetime machinery.
//
// One row per file spooled to `cache/staging_export/` by an export that
// cannot stream straight to its destination (today: PDF export via
// `PrintManager`, E2.I11 — see docs/design/export-plaintext-lifetime.md §3
// for why the Markdown/zip path has nothing to record here). The row is what
// lets the plaintext be swept on a timer (`StagedPlaintextSweeper`), on lock
// (the HIGH-priority `LockObserver` in `:app`), and after a crash or reboot.
//
// Deliberately NOT a `:core:model` contract: nothing outside the vault and
// its `:app` wiring consumes it, and `:core:model` is the shared
// fake-backed surface (`InMemoryVaultRepository`) rather than a home for
// vault-internal bookkeeping.

package app.skein.core.vault.export.stage

/**
 * One `export_stages` row (migration 005).
 *
 * [path] is the absolute path of the staged file. It is content-free — a
 * stage id plus a sanitized title — but is still never logged above debug
 * level (spec §9), and never at all alongside document content.
 */
public data class ExportStageRow(
    val stageId: String,
    val path: String,
    val origin: String,
    val documentId: String?,
    val revisionHash: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val swept: Boolean = false,
)

/**
 * Reads and writes `export_stages`. Implemented by `VaultRepositoryImpl`
 * (which already owns the writer connection, its mutex and the reentrant
 * transaction plumbing) so a stage row never needs a second writing
 * connection to the same database.
 *
 * Every method requires an OPEN vault: `export_stages` lives inside the
 * SQLCipher database, so none of this is reachable while locked. That is why
 * `BootReceiver` sweeps the staging directory at the file level instead of
 * consulting this repository — see its KDoc.
 */
public interface ExportStageRepository {
    /** Records a freshly staged file. Replaces any row with the same [ExportStageRow.stageId]. */
    public suspend fun insertStage(row: ExportStageRow)

    /** The row for [stageId], or `null` if it was never recorded (or has been cascaded away). */
    public suspend fun getStage(stageId: String): ExportStageRow?

    /** Every row not yet marked swept, oldest expiry first. */
    public suspend fun listUnsweptStages(): List<ExportStageRow>

    /** Marks [stageId] swept. Returns `false` if there was no such unswept row. */
    public suspend fun markStageSwept(stageId: String): Boolean

    /** Marks every unswept row swept and returns how many rows changed. */
    public suspend fun markAllStagesSwept(): Int
}
