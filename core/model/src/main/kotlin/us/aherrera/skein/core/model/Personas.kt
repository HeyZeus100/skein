// M0.5 contract file (`E0.I13`): lands the design plan §4.4 interface —
// `PersonaService` — over the `Persona` type already locked in
// `Vault.kt` (`E0.I11`).
//
// `:core:model` is pure Kotlin/JVM (no Android imports), so the same
// interface compiles for `:app`, the SQL-backed `PersonaServiceImpl`
// (`E2.I14`), and the JVM-only `InMemoryPersonaService` fake in
// `:testing`. The only runtime dependency this file needs
// (`kotlinx.coroutines.flow.Flow`) is already wired in the module's
// `build.gradle.kts` (see `Inference.kt`).
//
// `Persona`, `PersonaId`, and `ModelId` are defined in `Vault.kt` /
// `Inference.kt` and are visible here via the shared package — this file
// intentionally adds no new value types beyond the interface itself,
// matching plan §4.4 verbatim.
//
// The file name is pinned to `Personas.kt` by plan §4.4 / `E0.I13`'s file
// list even though the file's only top-level declaration is `PersonaService`
// (ktlint's `filename` rule otherwise wants `PersonaService.kt`).
@file:Suppress("ktlint:standard:filename")

package us.aherrera.skein.core.model

import kotlinx.coroutines.flow.Flow

/**
 * Plan §4.4 / design spec §4.4. Owns the `personas` table (spec §5,
 * `CREATE TABLE personas`). Every vault always has at least one persona —
 * `default()` guarantees this, and [delete] refuses to remove the last one.
 */
public interface PersonaService {
    /** Emits the current persona list on every change (create/update/delete). */
    public fun observeAll(): Flow<List<Persona>>

    public suspend fun get(id: PersonaId): Persona?

    public suspend fun create(
        name: String,
        systemPrompt: String?,
        defaultModel: ModelId?,
    ): Persona

    public suspend fun update(persona: Persona): Persona

    /** Documents referencing the persona keep their rows; `persona_id` is set to NULL. Refuses to delete the last persona. */
    public suspend fun delete(id: PersonaId)

    /** Returns the first-created persona, creating "Default" (system prompt = null) if none exist. */
    public suspend fun default(): Persona
}
