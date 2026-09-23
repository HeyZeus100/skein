// `skein-cyq` (E4.I5, H0-amended). The registry surface the bead's
// description names: list/get/upsert/delete/setDefault/default. Two
// implementations: `ModelRegistryImpl` (`:core:vault`, over the `models`
// table via the SQLCipher pool) and `InMemoryModelRegistry` (`:testing`),
// both proven against `ModelRegistryContractTest` (`:testing`) — the same
// shape `VaultRepository`/`IndexStore` already use in this codebase.

package app.skein.core.model

/**
 * The registry of imported models.
 *
 * A dumb CRUD surface, deliberately: [delete] removes a row and nothing
 * else — it has no notion of "loaded". The rule "the default model cannot
 * be deleted while loaded" is `ModelManager`'s business logic, layered on
 * top of this interface (it is injected an `isLoaded: (ModelId) -> Boolean`
 * and consults it before ever calling [delete]), not enforced inside it.
 * Keeping the refusal out of the registry is what lets `InMemoryModelRegistry`
 * stay a plain map with no engine-status dependency of its own.
 *
 * The default model id is a separate, single pointer — not a column on the
 * row it points at, and not required to name a row that still exists — so
 * [setDefault]/[default] round-trip independently of [upsert]/[delete].
 * `ModelRegistryImpl` persists it in `ui_prefs`, per the bead's own
 * description; `InMemoryModelRegistry` persists it in memory.
 */
public interface ModelRegistry {
    /** Every imported model, in no particular guaranteed order. */
    public suspend fun list(): List<ModelRecord>

    /** `null` when no row with this id exists. */
    public suspend fun get(id: ModelId): ModelRecord?

    /** Insert, or replace an existing row with the same [ModelRecord.model]'s id. */
    public suspend fun upsert(record: ModelRecord)

    /**
     * Removes the row unconditionally. Does not touch the model's files —
     * that is `ImmutableModelStore.delete`'s job — and does not check
     * whether the model is loaded or is the current default; see the
     * class doc.
     */
    public suspend fun delete(id: ModelId)

    /** `null` clears the default; a non-null id need not currently [get] to a row. */
    public suspend fun setDefault(id: ModelId?)

    /** The current default model id, or `null` if none is set. */
    public suspend fun default(): ModelId?
}
