// The `E0.I13` in-memory `PersonaService` fake: a JVM-only stand-in for the
// contract locked in `core/model/.../Personas.kt`. It powers
// `PersonaServiceContractTest` on the JVM and any feature/RAG unit test
// that needs a working persona store without SQLCipher / native libraries.
//
// Not modelled here (deliberately):
//   • nulling `documents.persona_id` on delete — that is an observable
//     effect of the SQL-backed `PersonaServiceImpl` (`E2.I14`) acting on the
//     `documents` table in the same transaction; this fake has no document
//     store to null out, so it only needs to satisfy the persona-list
//     semantics ("refuses to delete the last persona").
//   • transactional isolation between concurrent writers — one `Mutex`
//     around every write serializes access, matching
//     `InMemoryVaultRepository`'s approach.
//
// "First-created" (for `default()`) is insertion order, not `createdAt`
// order: `personas` is a `LinkedHashMap`, so re-`put`ting an existing key
// (via `update`) never moves it, and the earliest-inserted entry is always
// `personas.values.first()`. This sidesteps millisecond clock ties.
//
// Cancellation: every public function is a plain `suspend` call guarded by
// `Mutex.withLock` — no operation swallows `CancellationException`, so a
// cancelled caller (e.g. a collector of `observeAll()`) unwinds normally,
// same as `InMemoryVaultRepository`.
//
// UUIDv7 minting: `java.util.UUID.randomUUID()` (a UUIDv4) is used because
// (a) `:testing` is pre-`E2.I3` (the real UUIDv7 codec) and (b) uniqueness
// is all any contract test cares about.

package us.aherrera.skein.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import us.aherrera.skein.core.model.ModelId
import us.aherrera.skein.core.model.Persona
import us.aherrera.skein.core.model.PersonaId
import us.aherrera.skein.core.model.PersonaService
import java.util.UUID

/**
 * Coroutine-safe in-memory `PersonaService`. See file header for what is
 * intentionally simplified vs. `PersonaServiceImpl` (`E2.I14`).
 *
 * @param clock monotonic-enough `now()` for `created_at`. Tests inject a
 *   `FakeClock` to make timestamps deterministic.
 */
public class InMemoryPersonaService(
    private val clock: () -> Long = System::currentTimeMillis,
) : PersonaService {
    private val writeLock: Mutex = Mutex()

    // Insertion order == "first-created" order (see file header).
    private val personas: MutableMap<PersonaId, Persona> = linkedMapOf()

    // Emits after every committed write. Observers re-query on tick.
    private val changeBus: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 16)
    private val ready: MutableStateFlow<Unit> = MutableStateFlow(Unit)

    override fun observeAll(): Flow<List<Persona>> = changeTicks().map { snapshot() }.distinctUntilChanged()

    override suspend fun get(id: PersonaId): Persona? = personas[id]

    override suspend fun create(
        name: String,
        systemPrompt: String?,
        defaultModel: ModelId?,
    ): Persona =
        writeLock.withLock {
            val persona =
                Persona(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    systemPrompt = systemPrompt,
                    defaultModel = defaultModel,
                    createdAt = clock(),
                )
            personas[persona.id] = persona
            emitChange()
            persona
        }

    override suspend fun update(persona: Persona): Persona =
        writeLock.withLock {
            require(personas.containsKey(persona.id)) { "no persona with id=${persona.id}" }
            personas[persona.id] = persona
            emitChange()
            persona
        }

    override suspend fun delete(id: PersonaId) {
        writeLock.withLock {
            require(personas.containsKey(id)) { "no persona with id=$id" }
            check(personas.size > 1) { "refusing to delete the last persona (id=$id)" }
            personas.remove(id)
            emitChange()
        }
    }

    override suspend fun default(): Persona =
        writeLock.withLock {
            personas.values.firstOrNull() ?: run {
                val persona =
                    Persona(
                        id = UUID.randomUUID().toString(),
                        name = "Default",
                        systemPrompt = null,
                        defaultModel = null,
                        createdAt = clock(),
                    )
                personas[persona.id] = persona
                emitChange()
                persona
            }
        }

    private fun snapshot(): List<Persona> = personas.values.toList()

    private fun changeTicks(): Flow<Unit> =
        merge(
            changeBus.asSharedFlow(),
            ready,
        ).onStart { emit(Unit) }

    private fun emitChange() {
        changeBus.tryEmit(Unit)
        ready.value = Unit
    }
}
