package app.skein.testing.fakes

/** A persona as tracked by [FakePersonaService] — a simplification of the real `Persona` (plan §4.4). */
data class FakePersona(
    val id: String,
    val name: String,
    val systemPrompt: String?,
)

/**
 * In-memory stand-in for the (not-yet-landed) `PersonaService` contract
 * (plan §4.4, `E0.I13`). Always seeds a "Default" persona, matching the real
 * contract's `default()` guarantee, and refuses to delete the last persona.
 *
 * `E10.I2` (skein-0j1) re-targets this against the real `PersonaService`
 * interface once `E0.I13` lands.
 */
class FakePersonaService {
    private val personas = linkedMapOf<String, FakePersona>()
    private var nextId = 1

    init {
        create("Default", systemPrompt = null)
    }

    fun observeAll(): List<FakePersona> = personas.values.toList()

    fun get(id: String): FakePersona? = personas[id]

    fun create(
        name: String,
        systemPrompt: String?,
    ): FakePersona {
        val persona = FakePersona(id = "persona-${nextId++}", name = name, systemPrompt = systemPrompt)
        personas[persona.id] = persona
        return persona
    }

    /** Refuses to delete the last remaining persona, matching the real contract's guarantee. */
    fun delete(id: String) {
        check(personas.size > 1) { "refusing to delete the last persona" }
        personas.remove(id)
    }

    fun default(): FakePersona = personas.values.first()
}
