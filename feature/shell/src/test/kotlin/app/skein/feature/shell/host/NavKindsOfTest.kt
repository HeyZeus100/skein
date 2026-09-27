package app.skein.feature.shell.host

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.VaultRepository
import app.skein.core.navigation.ObjectKind
import app.skein.core.navigation.SkeinId
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NavKindsOfTest {
    @Test
    fun `restore does one content-free batch and retains space ids`() =
        runTest {
            val note = SkeinId.of("01924a4b-4d29-7000-8000-000000000001")
            val space = SkeinId.of("01924a4b-4d29-7000-8000-000000000002")
            val deleted = SkeinId.of("01924a4b-4d29-7000-8000-000000000003")
            val calls = mutableListOf<Set<DocId>>()
            val repository =
                object : VaultRepository by InMemoryVaultRepository() {
                    override suspend fun getDocument(id: DocId): Document? = error("restore must not read content")

                    override suspend fun kindsOf(ids: Set<DocId>): Map<DocId, DocumentKind> {
                        calls += ids
                        return mapOf(note.value to DocumentKind.NOTE)
                    }
                }
            val result = navKindsOf(repository) { listOf(space.value) }(setOf(note, space, deleted))
            assertEquals(mapOf(note to ObjectKind.NOTE, space to ObjectKind.SPACE), result)
            assertEquals(listOf(setOf(note.value, space.value, deleted.value)), calls)
        }
}
