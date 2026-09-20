package app.skein.testing.fakes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakeEmbedderServiceTest {
    @Test
    fun `embedQuery is deterministic for the same input`() {
        val service = FakeEmbedderService()

        val first = service.embedQuery("hello")
        val second = service.embedQuery("hello")

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `embedQuery returns 256 bytes`() {
        val service = FakeEmbedderService()

        assertThat(service.embedQuery("hello")).hasLength(256)
    }

    @Test
    fun `different inputs produce different vectors`() {
        val service = FakeEmbedderService()

        val a = service.embedQuery("alpha")
        val b = service.embedQuery("beta")

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `embedDocuments embeds each text independently`() {
        val service = FakeEmbedderService()

        val vectors = service.embedDocuments(listOf("one", "two"))

        assertThat(vectors).hasSize(2)
        assertThat(vectors[0]).isNotEqualTo(vectors[1])
    }

    @Test
    fun `countTokens counts whitespace-separated words`() {
        val service = FakeEmbedderService()

        assertThat(service.countTokens("a b  c")).isEqualTo(3)
    }
}
