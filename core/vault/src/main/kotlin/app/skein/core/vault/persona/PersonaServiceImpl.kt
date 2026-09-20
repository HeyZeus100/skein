// SQL-backed `PersonaService` implementation (`E2.I14`, plan §4.4, bd
// `skein-bkp`).
//
// This is the on-device implementation that backs the JVM
// `us.aherrera.skein.testing.InMemoryPersonaService` fake — the
// "specification by fake" both this class and `PersonaServiceContractTest`
// (shared, in `:testing`) are written against. Its behaviour must stay
// behaviorally equivalent to that fake, modulo the one difference its own
// header calls out: nulling `documents.persona_id` on `delete` is an
// observable effect only this SQL-backed class can produce (the fake has
// no document store to null out).
//
// Design summary (mirrors `VaultRepositoryImpl`, `E2.I4`/skein-2my):
//   • One `SQLiteConnection`, guarded by a single coroutine `Mutex`
//     ([writerMutex]) — every operation (reads included) serializes through
//     it, matching `IndexStoreImpl`'s M1 shape: a reader pool is an
//     eventual optimisation once `ConnectionPool` lands, not a v1 blocker
//     for a table this small.
//   • Every operation dispatches onto `io` (`Dispatchers.IO` by default).
//   • `delete` nulls out every `documents.persona_id` row referencing the
//     doomed persona and removes the persona row inside one
//     `BEGIN IMMEDIATE ... COMMIT` — the DDL's `documents.persona_id
//     REFERENCES personas(id)` carries no `ON DELETE` clause (spec §5,
//     verbatim), so this class owns that invariant instead of the schema.
//   • `delete` refuses to remove the last surviving persona
//     (`IllegalStateException`, matching `InMemoryPersonaService`'s
//     `check(...)` — this is the plan `E0.I13`/`E2.I14` acceptance
//     criterion the contract test asserts) and refuses to remove a persona
//     that does not exist (`IllegalArgumentException`, matching that same
//     fake's `require(...)`), checked in that order so the two failure
//     modes never mix up which exception type callers see.
//   • Every SQL string is centralised in `PersonaSql` (E2.I4/E2.I15 review
//     pattern this module already follows for `VaultSql`/`IndexSql`).
//   • `observeAll` uses a dedicated in-process change signal — a private
//     `MutableSharedFlow<Unit>` scoped to this instance, not
//     `VaultRepositoryImpl`'s `ChangeBus`/`TableChange`. That type is
//     deliberately narrow to the tables `VaultRepositoryImpl` itself writes
//     (see `TableChange`'s header) and `IndexStoreImpl` already reserves
//     its own separate slot rather than sharing it; `personas` gets the
//     same treatment here. A collector always sees the state as of
//     subscription time (`onStart { emit(Unit) }`) merged with every
//     subsequent committed write, matching `VaultRepositoryImpl.changeTicks`.
//   • "First-created" (for `default()`) is SQLite's implicit `rowid`
//     insertion order, not `created_at` order — see `PersonaSql`'s header
//     for why this sidesteps millisecond clock ties the same way
//     `InMemoryPersonaService`'s `LinkedHashMap` does.
//
// Not implemented here (deliberately, matching `IndexStoreImpl`'s own
// "not implemented yet" list):
//   • A reader-pool + single-writer split, or a shared `ConnectionPool` —
//     neither exists in-tree yet; this constructor takes a single
//     `SQLiteConnection`, mirroring `IndexStoreImpl`.
//   • Coroutine-reentrant transactions (`VaultRepositoryImpl`'s
//     `TxContext`) — `PersonaServiceImpl` never calls another
//     `PersonaService` method from inside its own transaction, so a plain
//     `BEGIN IMMEDIATE`/`COMMIT` per write (à la `IndexStoreImpl.transaction`)
//     is sufficient.

package app.skein.core.vault.persona

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.skein.core.vault.id.Uuid7
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.aherrera.skein.core.model.ModelId
import us.aherrera.skein.core.model.Persona
import us.aherrera.skein.core.model.PersonaId
import us.aherrera.skein.core.model.PersonaService

public class PersonaServiceImpl(
    private val connection: SQLiteConnection,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PersonaService,
    AutoCloseable {
    private val writerMutex: Mutex = Mutex()

    // Dedicated change signal for this instance — see file header on why
    // this does not share `VaultRepositoryImpl`'s `ChangeBus`/`TableChange`.
    private val changeSignal: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = CHANGE_SIGNAL_BUFFER)

    override fun observeAll(): Flow<List<Persona>> =
        changeSignal
            .onStart { emit(Unit) }
            .map { queryAll() }
            .distinctUntilChanged()

    override suspend fun get(id: PersonaId): Persona? =
        withContext(io) {
            writerMutex.withLock {
                connection.prepare(PersonaSql.SELECT_BY_ID).use { stmt ->
                    stmt.bindText(1, id)
                    if (stmt.step()) readPersona(stmt) else null
                }
            }
        }

    override suspend fun create(
        name: String,
        systemPrompt: String?,
        defaultModel: ModelId?,
    ): Persona =
        withContext(io) {
            writerMutex.withLock {
                writeTx {
                    val now = clock()
                    val id = Uuid7.generate()
                    insertPersona(id, name, systemPrompt, defaultModel, now)
                    Persona(
                        id = id,
                        name = name,
                        systemPrompt = systemPrompt,
                        defaultModel = defaultModel,
                        createdAt = now,
                    )
                }
            }
        }

    override suspend fun update(persona: Persona): Persona =
        withContext(io) {
            writerMutex.withLock {
                writeTx {
                    require(existsPersona(persona.id)) { "no persona with id=${persona.id}" }
                    connection.prepare(PersonaSql.UPDATE_PERSONA).use { stmt ->
                        stmt.bindText(1, persona.name)
                        bindNullableText(stmt, 2, persona.systemPrompt)
                        bindNullableText(stmt, 3, persona.defaultModel)
                        stmt.bindText(4, persona.id)
                        stmt.step()
                    }
                    persona
                }
            }
        }

    override suspend fun delete(id: PersonaId) {
        withContext(io) {
            writerMutex.withLock {
                writeTx {
                    require(existsPersona(id)) { "no persona with id=$id" }
                    check(countPersonas() > 1) { "refusing to delete the last persona (id=$id)" }
                    // Documents referencing this persona keep their rows;
                    // only `persona_id` is nulled (spec §5 / plan E2.I14 —
                    // the DDL's FK carries no ON DELETE clause).
                    connection.prepare(PersonaSql.NULL_DOCUMENTS_PERSONA).use { stmt ->
                        stmt.bindText(1, id)
                        stmt.step()
                    }
                    connection.prepare(PersonaSql.DELETE_PERSONA).use { stmt ->
                        stmt.bindText(1, id)
                        stmt.step()
                    }
                }
            }
        }
    }

    override suspend fun default(): Persona =
        withContext(io) {
            writerMutex.withLock {
                val existing =
                    connection.prepare(PersonaSql.SELECT_FIRST_CREATED).use { stmt ->
                        if (stmt.step()) readPersona(stmt) else null
                    }
                existing
                    ?: writeTx {
                        val now = clock()
                        val id = Uuid7.generate()
                        insertPersona(
                            id = id,
                            name = DEFAULT_PERSONA_NAME,
                            systemPrompt = null,
                            defaultModel = null,
                            createdAt = now,
                        )
                        Persona(
                            id = id,
                            name = DEFAULT_PERSONA_NAME,
                            systemPrompt = null,
                            defaultModel = null,
                            createdAt = now,
                        )
                    }
            }
        }

    override fun close() {
        connection.close()
    }

    // ------------------------------------------------------------------
    // Internals — writer transaction + change signal
    // ------------------------------------------------------------------

    /**
     * Runs [block] inside `BEGIN IMMEDIATE ... COMMIT` (rollback on throw),
     * emitting a change tick to every [observeAll] collector only after a
     * successful commit. Callers must already hold [writerMutex].
     */
    private inline fun <T> writeTx(block: () -> T): T {
        connection.prepare(PersonaSql.BEGIN_IMMEDIATE).use { it.step() }
        try {
            val result = block()
            connection.prepare(PersonaSql.COMMIT).use { it.step() }
            changeSignal.tryEmit(Unit)
            return result
        } catch (t: Throwable) {
            runCatching { connection.prepare(PersonaSql.ROLLBACK).use { it.step() } }
            throw t
        }
    }

    private suspend fun queryAll(): List<Persona> =
        withContext(io) {
            writerMutex.withLock {
                connection.prepare(PersonaSql.SELECT_ALL_ORDERED).use { stmt ->
                    val out = ArrayList<Persona>()
                    while (stmt.step()) out += readPersona(stmt)
                    out
                }
            }
        }

    // ------------------------------------------------------------------
    // Internals — raw statement helpers (caller already holds writerMutex)
    // ------------------------------------------------------------------

    private fun insertPersona(
        id: PersonaId,
        name: String,
        systemPrompt: String?,
        defaultModel: ModelId?,
        createdAt: Long,
    ) {
        connection.prepare(PersonaSql.INSERT_PERSONA).use { stmt ->
            stmt.bindText(1, id)
            stmt.bindText(2, name)
            bindNullableText(stmt, 3, systemPrompt)
            bindNullableText(stmt, 4, defaultModel)
            stmt.bindLong(5, createdAt)
            stmt.step()
        }
    }

    private fun existsPersona(id: PersonaId): Boolean =
        connection.prepare(PersonaSql.EXISTS_PERSONA).use { stmt ->
            stmt.bindText(1, id)
            stmt.step()
        }

    private fun countPersonas(): Long =
        connection.prepare(PersonaSql.COUNT_PERSONAS).use { stmt ->
            check(stmt.step()) { "SELECT COUNT(*) FROM personas yielded no row" }
            stmt.getLong(0)
        }

    private fun readPersona(stmt: SQLiteStatement): Persona =
        Persona(
            id = stmt.getText(0),
            name = stmt.getText(1),
            systemPrompt = if (stmt.isNull(2)) null else stmt.getText(2),
            defaultModel = if (stmt.isNull(3)) null else stmt.getText(3),
            createdAt = stmt.getLong(4),
        )

    private fun bindNullableText(
        stmt: SQLiteStatement,
        index: Int,
        value: String?,
    ) {
        if (value == null) stmt.bindNull(index) else stmt.bindText(index, value)
    }

    private companion object {
        const val CHANGE_SIGNAL_BUFFER: Int = 64
        const val DEFAULT_PERSONA_NAME: String = "Default"
    }
}
