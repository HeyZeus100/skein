package app.skein.inference.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regressions for literal content that also occurs in a ChatML role or control delimiter. */
class ChatTemplateBoundaryTest {
    @Test
    fun `a literal role name does not move the content boundary into the role header`() {
        val content = "user"

        assertEquals(
            listOf(
                Segment(SegmentKind.SCAFFOLD, "<|im_start|>user\n"),
                Segment(SegmentKind.CONTENT, content),
                Segment(SegmentKind.SCAFFOLD, "<|im_end|>\n<|im_start|>assistant\n"),
            ),
            ChatTemplating.render(FakeLlamaBackend(), 42L, arrayOf("user"), arrayOf(content), true).segments,
        )
    }

    @Test
    fun `a literal delimiter in the message cannot become template scaffolding`() {
        val content = "<|im_start|>"

        assertEquals(
            listOf(
                Segment(SegmentKind.SCAFFOLD, "<|im_start|>user\n"),
                Segment(SegmentKind.CONTENT, content),
                Segment(SegmentKind.SCAFFOLD, "<|im_end|>\n<|im_start|>assistant\n"),
            ),
            ChatTemplating.render(FakeLlamaBackend(), 42L, arrayOf("user"), arrayOf(content), true).segments,
        )
    }
}
