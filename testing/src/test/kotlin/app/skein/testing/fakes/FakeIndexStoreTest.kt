package app.skein.testing.fakes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakeIndexStoreTest {
    @Test
    fun `replaceChunks assigns ids in order and getChunks returns them`() {
        val store = FakeIndexStore()

        val ids = store.replaceChunks("doc-1", listOf("first", "second"))

        val chunks = store.getChunks(ids)
        assertThat(chunks.values.map { it.text }).containsExactly("first", "second")
    }

    @Test
    fun `replaceChunks drops the document's previous chunks`() {
        val store = FakeIndexStore()
        val firstIds = store.replaceChunks("doc-1", listOf("old"))

        store.replaceChunks("doc-1", listOf("new"))

        assertThat(store.getChunks(firstIds)).isEmpty()
    }

    @Test
    fun `bm25 ranks chunks with more term overlap first`() {
        val store = FakeIndexStore()
        store.replaceChunks("doc-1", listOf("apple banana", "apple only", "banana only"))

        val hits = store.bm25("apple banana")

        assertThat(hits.first().text).isEqualTo("apple banana")
    }

    @Test
    fun `putEmbedding stores the raw bytes for a chunk`() {
        val store = FakeIndexStore()
        val id = store.replaceChunks("doc-1", listOf("text")).single()

        store.putEmbedding(id, byteArrayOf(1, 2, 3))

        assertThat(store.embeddingFor(id)).isEqualTo(byteArrayOf(1, 2, 3))
    }
}
