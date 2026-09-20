package app.skein.testing.fakes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakePersonaServiceTest {
    @Test
    fun `a Default persona exists from construction`() {
        val service = FakePersonaService()

        assertThat(service.default().name).isEqualTo("Default")
    }

    @Test
    fun `create adds a persona retrievable by id`() {
        val service = FakePersonaService()

        val persona = service.create("Research", systemPrompt = "Be terse.")

        assertThat(service.get(persona.id)).isEqualTo(persona)
    }

    @Test
    fun `delete refuses to remove the last persona`() {
        val service = FakePersonaService()

        assertThrows(IllegalStateException::class.java) { service.delete(service.default().id) }
    }

    @Test
    fun `delete removes a non-last persona`() {
        val service = FakePersonaService()
        val extra = service.create("Research", systemPrompt = null)

        service.delete(extra.id)

        assertThat(service.get(extra.id)).isNull()
    }

    private fun assertThrows(
        type: Class<out Throwable>,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            if (type.isInstance(t)) return
            throw t
        }
        throw AssertionError("expected ${type.name} to be thrown, but nothing was")
    }
}
