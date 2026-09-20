package app.skein.testing.fakes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FakeVaultRepositoryTest {
    @Test
    fun `fakeVault builder creates notes retrievable by id`() {
        val vault = fakeVault { note("Title", "body text") }

        val doc = vault.listAll().single()

        assertThat(doc.title).isEqualTo("Title")
        assertThat(vault.getDocument(doc.id)).isEqualTo(doc)
    }

    @Test
    fun `updateBody bumps updatedAt and rewrites content`() {
        val repo = FakeVaultRepository()
        val doc = repo.createDocument("Title", "v1")

        val updated = repo.updateBody(doc.id, "Title", "v2")

        assertThat(updated.bodyMd).isEqualTo("v2")
        assertThat(updated.updatedAt).isAtLeast(doc.createdAt)
    }

    @Test
    fun `deleteDocument removes it from listAll`() {
        val repo = FakeVaultRepository()
        val doc = repo.createDocument("Title", "body")

        repo.deleteDocument(doc.id)

        assertThat(repo.listAll()).isEmpty()
    }

    @Test
    fun `searchBodies is a case-insensitive substring match`() {
        val repo = FakeVaultRepository()
        repo.createDocument("Note", "contains a MARKER term")

        val hits = repo.searchBodies("marker")

        assertThat(hits).hasSize(1)
    }
}
