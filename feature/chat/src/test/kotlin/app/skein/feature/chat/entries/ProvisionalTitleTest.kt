package app.skein.feature.chat.entries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/** CHAT_UX_SPEC.md §13.1, the rules [provisionalTitle] implements. */
class ProvisionalTitleTest {
    private fun title(message: String) = provisionalTitle(message, NOW, ZoneOffset.UTC, Locale.UK)

    @Test
    fun `the first sentence, continued while it is under three words`() {
        assertEquals("What blocks M2?", title("What blocks M2? The smoke test, I think."))
        assertEquals(
            "Hi. Can you summarise my notes on the Fold?",
            title("Hi. Can you summarise my notes on the Fold?"),
        )
    }

    @Test
    fun `commands, markdown, wikilinks and code are stripped`() {
        assertEquals("Explain Vault format in detail", title("/ask explain **[[Vault format]]** in detail."))
        assertEquals("Why does this fail", title("```kotlin\nval x = 1\n```\nWhy does this fail"))
    }

    @Test
    fun `cut at 48 characters on a word boundary, no trailing punctuation`() {
        val cut = title("Compare the quantisation levels for every model we tried on the Fold last week")
        assertEquals("Compare the quantisation levels for every model", cut)
        assertTrue(cut.length <= 48)
    }

    @Test
    fun `never Chat - an empty title falls back to the date`() {
        assertTrue(title("```\nonly code\n```").startsWith("Chat from "))
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
