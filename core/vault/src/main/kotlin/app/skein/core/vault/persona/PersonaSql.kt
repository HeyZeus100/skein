// Every SQL string used by `PersonaServiceImpl` lives here so a reviewer
// can audit the query surface in one file (`E2.I4`/`E2.I15` review
// pattern this module already follows for `VaultSql`/`IndexSql`).
// Column and table names must match the DDL in
// `core/vault/src/main/resources/migrations/001_initial.sql` verbatim.
//
// Design notes:
//   • `personas` has no explicit `WITHOUT ROWID` clause, so every row keeps
//     SQLite's implicit monotonically-increasing `rowid`, which is never
//     renumbered by an `UPDATE`. Ordering by it gives true insertion order
//     ("first-created") the same way `InMemoryPersonaService`'s
//     `LinkedHashMap` does, sidestepping millisecond `created_at` ties —
//     see that class's header for the same reasoning.
//   • `documents.persona_id REFERENCES personas(id)` carries no `ON DELETE`
//     clause (spec §5 DDL, verbatim), so nulling referencing rows before
//     the `DELETE FROM personas` is `PersonaServiceImpl`'s job, done inside
//     the same `BEGIN IMMEDIATE ... COMMIT` as the delete itself (plan
//     `E2.I14` acceptance criterion).
//   • Placeholders are always positional (`?`); the only text ever
//     concatenated into a query here is this object's own literal SQL.
package app.skein.core.vault.persona

internal object PersonaSql {
    const val PERSONA_SELECT_COLUMNS: String =
        "id, name, system_prompt, default_model, created_at"

    const val SELECT_ALL_ORDERED: String =
        "SELECT $PERSONA_SELECT_COLUMNS FROM personas ORDER BY rowid ASC"

    const val SELECT_BY_ID: String =
        "SELECT $PERSONA_SELECT_COLUMNS FROM personas WHERE id = ?"

    /** First-created surviving persona — see file header on why `rowid` (not `created_at`) is the ordering key. */
    const val SELECT_FIRST_CREATED: String =
        "SELECT $PERSONA_SELECT_COLUMNS FROM personas ORDER BY rowid ASC LIMIT 1"

    const val COUNT_PERSONAS: String =
        "SELECT COUNT(*) FROM personas"

    const val EXISTS_PERSONA: String =
        "SELECT 1 FROM personas WHERE id = ?"

    const val INSERT_PERSONA: String =
        "INSERT INTO personas(id, name, system_prompt, default_model, created_at) VALUES (?, ?, ?, ?, ?)"

    const val UPDATE_PERSONA: String =
        "UPDATE personas SET name = ?, system_prompt = ?, default_model = ? WHERE id = ?"

    /** Documents referencing a deleted persona keep their rows; only `persona_id` is nulled (plan `E2.I14`). */
    const val NULL_DOCUMENTS_PERSONA: String =
        "UPDATE documents SET persona_id = NULL WHERE persona_id = ?"

    const val DELETE_PERSONA: String =
        "DELETE FROM personas WHERE id = ?"

    const val BEGIN_IMMEDIATE: String = "BEGIN IMMEDIATE"
    const val COMMIT: String = "COMMIT"
    const val ROLLBACK: String = "ROLLBACK"
}
