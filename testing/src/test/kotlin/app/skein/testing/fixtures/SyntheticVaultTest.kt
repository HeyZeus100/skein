// `E10.I4`: determinism and shape tests for `SyntheticVault`. Seeds
// `InMemoryVaultRepository` fakes and asserts the generator's replay
// guarantees rather than the exact prose it produces (the vocabulary is
// free to evolve without breaking these tests).

package app.skein.testing.fixtures

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class SyntheticVaultTest {
    // ------------------------------------------------------------------
    // Shape / counts
    // ------------------------------------------------------------------

    @Test
    public fun seed_with_large_preset_produces_1000_documents_in_the_documented_split(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()

            SyntheticVault.seed(repo, seed = SyntheticVault.DEFAULT_SEED, size = SyntheticVault.Preset.LARGE)

            val docs = allDocuments(repo)
            assertEquals(1_000, docs.size)
            assertEquals(800, docs.count { it.kind == DocumentKind.NOTE })
            assertEquals(50, docs.count { it.kind == DocumentKind.CHAT })
            assertEquals(150, docs.count { it.kind == DocumentKind.ATTACHMENT })
        }

    @Test
    public fun small_and_medium_presets_match_their_documented_totals(): Unit =
        runBlocking {
            val smallRepo = InMemoryVaultRepository()
            SyntheticVault.seed(smallRepo, size = SyntheticVault.Preset.SMALL)
            assertEquals(50, allDocuments(smallRepo).size)

            val mediumRepo = InMemoryVaultRepository()
            SyntheticVault.seed(mediumRepo, size = SyntheticVault.Preset.MEDIUM)
            assertEquals(500, allDocuments(mediumRepo).size)
        }

    @Test
    public fun chats_get_3_to_5_messages_and_notes_are_nonempty_markdown(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            SyntheticVault.seed(repo, size = SyntheticVault.Preset.SMALL)

            val chats = allDocuments(repo).filter { it.kind == DocumentKind.CHAT }
            for (chat in chats) {
                val messages = repo.listMessages(chat.id)
                assertTrue(
                    "expected 3-5 messages, was ${messages.size}",
                    messages.size in 3..5,
                )
            }

            val notes = allDocuments(repo).filter { it.kind == DocumentKind.NOTE }
            assertTrue(notes.isNotEmpty())
            for (note in notes) {
                assertTrue("note body should be non-blank markdown", !note.bodyMd.isNullOrBlank())
                assertTrue("note frontmatter should carry canonical `id`", note.frontmatter["id"] != null)
                assertTrue("note frontmatter should carry canonical `tags`", note.frontmatter["tags"] != null)
            }
        }

    @Test
    public fun some_note_bodies_contain_wikilinks_in_the_documented_format(): Unit =
        runBlocking {
            val repo = InMemoryVaultRepository()
            SyntheticVault.seed(repo, size = SyntheticVault.Preset.MEDIUM)

            val notes = allDocuments(repo).filter { it.kind == DocumentKind.NOTE }
            val withWikilinks = notes.count { it.bodyMd?.contains("[[Note ") == true }
            assertTrue("expected at least one wikilink across the note set", withWikilinks > 0)
        }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    public fun same_seed_replay_produces_identical_note_and_chat_documents(): Unit =
        runBlocking {
            val repoA = InMemoryVaultRepository()
            val repoB = InMemoryVaultRepository()

            SyntheticVault.seed(repoA, seed = 7L, size = SyntheticVault.Preset.SMALL)
            SyntheticVault.seed(repoB, seed = 7L, size = SyntheticVault.Preset.SMALL)

            val a = allDocuments(repoA).filter { it.kind != DocumentKind.ATTACHMENT }
            val b = allDocuments(repoB).filter { it.kind != DocumentKind.ATTACHMENT }

            assertEquals(a.size, b.size)
            assertEquals(idAndHashSet(a), idAndHashSet(b))
        }

    @Test
    public fun same_seed_replay_produces_identical_attachment_content_hashes(): Unit =
        runBlocking {
            // `InMemoryVaultRepository.createAttachment` mints its own
            // `UUID.randomUUID()` id (no override hook), so — unlike notes
            // and chats — attachment *ids* are not expected to replay
            // identically. Their *content* (and therefore `contentHash`)
            // still must.
            val repoA = InMemoryVaultRepository()
            val repoB = InMemoryVaultRepository()

            SyntheticVault.seed(repoA, seed = 7L, size = SyntheticVault.Preset.SMALL)
            SyntheticVault.seed(repoB, seed = 7L, size = SyntheticVault.Preset.SMALL)

            val a = allDocuments(repoA).filter { it.kind == DocumentKind.ATTACHMENT }
            val b = allDocuments(repoB).filter { it.kind == DocumentKind.ATTACHMENT }

            assertEquals(a.size, b.size)
            assertTrue("attachment contentHash should never be null", a.all { it.contentHash != null })
            assertEquals(a.map { it.contentHash }.sortedBy { it }, b.map { it.contentHash }.sortedBy { it })
        }

    @Test
    public fun different_seeds_produce_different_content_but_the_same_shape(): Unit =
        runBlocking {
            val repoA = InMemoryVaultRepository()
            val repoB = InMemoryVaultRepository()

            SyntheticVault.seed(repoA, seed = 7L, size = SyntheticVault.Preset.SMALL)
            SyntheticVault.seed(repoB, seed = 8L, size = SyntheticVault.Preset.SMALL)

            val a = allDocuments(repoA)
            val b = allDocuments(repoB)

            // Same shape: identical counts per kind.
            for (kind in DocumentKind.entries) {
                assertEquals(
                    "kind=$kind count should match across seeds",
                    a.count { it.kind == kind },
                    b.count { it.kind == kind },
                )
            }

            // Different content: the (id, contentHash) sets differ.
            val notesAndChatsA = idAndHashSet(a.filter { it.kind != DocumentKind.ATTACHMENT })
            val notesAndChatsB = idAndHashSet(b.filter { it.kind != DocumentKind.ATTACHMENT })
            assertNotEquals(notesAndChatsA, notesAndChatsB)
        }

    private suspend fun allDocuments(repo: VaultRepository): List<Document> =
        repo
            .observeTimeline(
                filter = TimelineFilter(kinds = DocumentKind.entries.toSet()),
                limit = SyntheticVault.Preset.LARGE.total + 1,
            ).first()

    private fun idAndHashSet(docs: List<Document>): Set<Pair<String, String?>> =
        docs.map { it.id to it.contentHash }.toSet()
}
