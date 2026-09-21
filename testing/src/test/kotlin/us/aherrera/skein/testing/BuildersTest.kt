// `E10.I2` (skein-0j1): proves the `fakeVault { ... }` / `scriptedEngine(...)`
// builder DSL compiles and produces the expected documents/scripted engine —
// the bead's "Builders compile in a sample test and produce the expected
// documents" acceptance criterion.

package us.aherrera.skein.testing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.aherrera.skein.core.model.DocumentKind
import us.aherrera.skein.core.model.Role

public class BuildersTest {
    @Test
    public fun fakeVault_note_creates_a_note_document_with_the_given_title_and_body(): Unit =
        runTest {
            val vault = fakeVault { note("Title", "body") }

            val doc = vault.findByTitle("Title")

            assertEquals("body", doc?.bodyMd)
            assertEquals(DocumentKind.NOTE, doc?.kind)
        }

    @Test
    public fun fakeVault_chat_creates_a_chat_document_whose_transcript_contains_every_message(): Unit =
        runTest {
            val vault =
                fakeVault {
                    chat("Chat", Role.USER to "hi", Role.ASSISTANT to "hello")
                }

            val doc = vault.findByTitle("Chat")

            assertEquals(DocumentKind.CHAT, doc?.kind)
            assertTrue(doc?.bodyMd.orEmpty().contains("hi"))
            assertTrue(doc?.bodyMd.orEmpty().contains("hello"))
            assertEquals(2, vault.listMessages(doc!!.id).size)
        }

    @Test
    public fun fakeVault_composes_multiple_notes_and_chats_in_one_builder_body(): Unit =
        runTest {
            val vault =
                fakeVault {
                    note("First", "one")
                    note("Second", "two")
                    chat("Chat", Role.USER to "hi")
                }

            assertEquals("one", vault.findByTitle("First")?.bodyMd)
            assertEquals("two", vault.findByTitle("Second")?.bodyMd)
            assertEquals(DocumentKind.CHAT, vault.findByTitle("Chat")?.kind)
        }

    @Test
    public fun scripted_engine_builds_a_fake_inference_engine_scripted_with_the_given_pairs() {
        val engine = scriptedEngine("q" to listOf("a", "b"))

        assertEquals(listOf("a", "b"), engine.script["q"])
        assertNull(engine.script["unscripted"])
    }
}
