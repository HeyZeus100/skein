// E2.I4: names the vault tables `VaultRepositoryImpl` mutates, so
// `ChangeBus` subscribers (the `observe*` flows on this same class today;
// a future `SearchViewModel`-style consumer later, mirroring the stream
// `IndexStoreImpl` publishes) can filter to only the changes they care
// about instead of re-querying on every write anywhere in the vault.
//
// Deliberately narrow: only the tables `VaultRepositoryImpl` itself writes
// (`documents`, `messages`, `ingest_queue`) get a case here. `chunks` /
// `chunks_fts` / `chunks_vec` / `edges` / `entities` are `IndexStore`'s
// tables (`E2.I15`) — that class publishes its own stream rather than
// sharing this one. As of bd `skein-rkxi` its once-reserved slot is wired
// up, but as `IndexStore.observeChanges(): Flow<IndexChange>` on the
// `:core:model` contract rather than as another `ChangeBus`: the feature
// modules that consume it may not depend on `:core:vault`, so its
// vocabulary cannot live next to `TableChange`.

package app.skein.core.vault.repository

import us.aherrera.skein.core.model.DocId

/** One committed write `VaultRepositoryImpl` just made, for `ChangeBus` consumers to filter on. */
public sealed interface TableChange {
    /** A row in `documents` was inserted, updated, or deleted. */
    public data class Documents(
        public val docId: DocId,
    ) : TableChange

    /** A row was appended to `messages` for the given chat document. */
    public data class Messages(
        public val chatDocId: DocId,
    ) : TableChange

    /** `ingest_queue` gained, lost, or had a row's `queued_at` bumped. */
    public data object IngestQueue : TableChange
}
