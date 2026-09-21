// `E2.I7` (bd `skein-ad5`): pins the detection precedence `MimeSniffer`
// applies — filename extension first, then the caller-supplied MIME type,
// then a content sniff — and the prose-vs-code boundary each step draws.

package app.skein.core.vault.transfer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class MimeSnifferTest {
    @Test
    public fun `extension wins over a conflicting mime type`() {
        assertThat(MimeSniffer.sourceLanguage("notes.md", "text/x-python", "print(1)")).isNull()
        assertThat(MimeSniffer.sourceLanguage("main.kt", "text/plain", "fun main() {}")).isEqualTo("kotlin")
        assertThat(MimeSniffer.sourceLanguage("Main.JAVA", "application/octet-stream", "")).isEqualTo("java")
    }

    @Test
    public fun `markdown and plain text are prose whichever way they are labelled`() {
        assertThat(MimeSniffer.sourceLanguage("a.txt", "application/octet-stream", "x")).isNull()
        assertThat(MimeSniffer.sourceLanguage("a.markdown", "application/octet-stream", "x")).isNull()
        assertThat(MimeSniffer.sourceLanguage("shared", "text/markdown", "x")).isNull()
        assertThat(MimeSniffer.sourceLanguage("shared", "text/x-markdown", "x")).isNull()
        assertThat(MimeSniffer.sourceLanguage("shared", "text/plain; charset=UTF-8", "x")).isNull()
    }

    @Test
    public fun `mime type is the fallback when the extension says nothing`() {
        assertThat(MimeSniffer.sourceLanguage("snippet", "text/x-python", "")).isEqualTo("python")
        assertThat(MimeSniffer.sourceLanguage("snippet", "text/x-rustsrc", "")).isEqualTo("rust")
        assertThat(MimeSniffer.sourceLanguage("snippet", "text/x-c++src", "")).isEqualTo("cpp")
        assertThat(MimeSniffer.sourceLanguage("snippet", "text/x-java-source", "")).isEqualTo("java")
        assertThat(MimeSniffer.sourceLanguage("snippet", "text/x-shellscript", "")).isEqualTo("bash")
        assertThat(MimeSniffer.sourceLanguage("snippet", "text/x-lua", "")).isEqualTo("lua")
        assertThat(MimeSniffer.sourceLanguage("snippet", "application/json", "")).isEqualTo("json")
        assertThat(MimeSniffer.sourceLanguage("snippet", "Text/JavaScript", "")).isEqualTo("javascript")
        assertThat(MimeSniffer.sourceLanguage("Makefile", "text/x-makefile", "")).isEqualTo("makefile")
    }

    @Test
    public fun `a shebang line is sniffed when neither extension nor mime type help`() {
        assertThat(
            MimeSniffer.sourceLanguage("run", "application/octet-stream", "#!/bin/sh\necho hi"),
        ).isEqualTo("bash")
        assertThat(MimeSniffer.sourceLanguage("run", "", "#!/usr/bin/env python3\nprint(1)")).isEqualTo("python")
        assertThat(MimeSniffer.sourceLanguage("run", "", "#!/usr/bin/env -S node --harmony\n")).isEqualTo("javascript")
        assertThat(MimeSniffer.sourceLanguage("run", "", "#!/usr/local/bin/lua5.4\n")).isEqualTo("lua")
    }

    @Test
    public fun `unrecognized input is prose`() {
        assertThat(MimeSniffer.sourceLanguage("", "", "")).isNull()
        assertThat(MimeSniffer.sourceLanguage("notes.unknownext", "application/octet-stream", "hello")).isNull()
        assertThat(MimeSniffer.sourceLanguage("hash-but-no-bang", "", "# heading\n")).isNull()
        assertThat(MimeSniffer.sourceLanguage("bang-only", "", "#!\n")).isNull()
    }
}
