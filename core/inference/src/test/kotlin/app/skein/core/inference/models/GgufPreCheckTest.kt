// skein-hewz. The six cases this bead's own instructions name verbatim:
// truncated header, bad magic, v1 width, v2 width, kvCount past the bound,
// zero-length file, and a valid tiny header accepted — plus a couple of
// cheap extras (tensor-count bound, nested array) since they fall out of
// the same fixture builder for free. `skein-wt92`'s full twelve-case
// PocketPal-derived corpus is a follow-up, not this bead's scope.

package app.skein.core.inference.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufPreCheckTest {
    private fun check(bytes: ByteArray): GgufPreCheckResult =
        GgufPreCheck.check(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), bytes.size.toLong())

    private fun reasonOf(result: GgufPreCheckResult): GgufPreCheckResult.Reason {
        assertTrue("expected a refusal, got $result", result is GgufPreCheckResult.Refused)
        return (result as GgufPreCheckResult.Refused).reason
    }

    // ------------------------------------------------------------------
    // Fixture builder — a minimal GGUF byte stream, version-aware widths.
    // ------------------------------------------------------------------

    private class Builder(
        private val wide: Boolean,
    ) {
        private val out = java.io.ByteArrayOutputStream()

        fun u32(v: Long) {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(v.toInt())
            out.write(buf.array())
        }

        fun u64(v: Long) {
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(v)
            out.write(buf.array())
        }

        fun count(v: Long) = if (wide) u64(v) else u32(v)

        fun string(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            count(bytes.size.toLong())
            out.write(bytes)
        }

        fun stringKv(
            key: String,
            value: String,
        ) {
            string(key)
            u32(8L) // GGUFValueType.STRING
            string(value)
        }

        fun u32Kv(
            key: String,
            value: Long,
        ) {
            string(key)
            u32(4L) // GGUFValueType.UINT32
            u32(value)
        }

        fun arrayOfStringsKv(
            key: String,
            values: List<String>,
        ) {
            string(key)
            u32(9L) // GGUFValueType.ARRAY
            u32(8L) // element type: STRING
            count(values.size.toLong())
            values.forEach { string(it) }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun header(
        version: Long,
        tensorCount: Long,
        kvCount: Long,
        build: Builder.() -> Unit = {},
    ): ByteArray {
        val wide = version != 1L
        val out = java.io.ByteArrayOutputStream()
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(GgufPreCheck.GGUF_MAGIC.toInt())
        head.putInt(version.toInt())
        out.write(head.array())
        val builder = Builder(wide)
        builder.count(tensorCount)
        builder.count(kvCount)
        builder.build()
        out.write(builder.bytes())
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Cases named by the bead itself.
    // ------------------------------------------------------------------

    @Test
    fun truncated_header_is_refused_not_misread() {
        val bytes = byteArrayOf(0x47, 0x47, 0x55) // three bytes: not even the full magic
        assertEquals(GgufPreCheckResult.Reason.TRUNCATED_HEADER, reasonOf(check(bytes)))
    }

    @Test
    fun bad_magic_is_refused() {
        val bytes = header(version = 2, tensorCount = 0, kvCount = 0)
        // Corrupt the first byte of the magic in place.
        bytes[0] = 0x00
        assertEquals(GgufPreCheckResult.Reason.BAD_MAGIC, reasonOf(check(bytes)))
    }

    @Test
    fun v1_width_is_32_bit_and_parses() {
        val bytes =
            header(version = 1, tensorCount = 0, kvCount = 1) {
                stringKv("general.architecture", "llama")
            }
        val result = check(bytes)
        assertTrue("expected Plausible, got $result", result is GgufPreCheckResult.Plausible)
        assertEquals("llama", (result as GgufPreCheckResult.Plausible).architecture)
    }

    @Test
    fun v2_width_is_64_bit_and_parses() {
        val bytes =
            header(version = 2, tensorCount = 0, kvCount = 2) {
                stringKv("general.architecture", "gemma3")
                u32Kv("general.file_type", 15L)
            }
        val result = check(bytes)
        assertTrue("expected Plausible, got $result", result is GgufPreCheckResult.Plausible)
        val plausible = result as GgufPreCheckResult.Plausible
        assertEquals("gemma3", plausible.architecture)
        assertEquals(15L, plausible.fileType)
    }

    @Test
    fun kv_count_past_the_bound_is_refused() {
        val bytes = header(version = 2, tensorCount = 0, kvCount = GgufPreCheck.MAX_KV_COUNT + 1)
        assertEquals(GgufPreCheckResult.Reason.KV_COUNT_OUT_OF_BOUNDS, reasonOf(check(bytes)))
    }

    @Test
    fun zero_length_file_is_refused() {
        assertEquals(GgufPreCheckResult.Reason.TRUNCATED_HEADER, reasonOf(check(ByteArray(0))))
    }

    @Test
    fun a_valid_tiny_header_with_no_kv_pairs_is_accepted() {
        val bytes = header(version = 3, tensorCount = 0, kvCount = 0)
        val result = check(bytes)
        assertTrue("expected Plausible, got $result", result is GgufPreCheckResult.Plausible)
        val plausible = result as GgufPreCheckResult.Plausible
        assertNull(plausible.architecture)
        assertNull(plausible.fileType)
    }

    // ------------------------------------------------------------------
    // Cheap extras the same builder gives for free.
    // ------------------------------------------------------------------

    @Test
    fun tensor_count_past_the_bound_is_refused() {
        val bytes = header(version = 2, tensorCount = GgufPreCheck.MAX_TENSOR_COUNT + 1, kvCount = 0)
        assertEquals(GgufPreCheckResult.Reason.TENSOR_COUNT_OUT_OF_BOUNDS, reasonOf(check(bytes)))
    }

    @Test
    fun unsupported_version_is_refused() {
        val bytes = header(version = 4, tensorCount = 0, kvCount = 0)
        assertEquals(GgufPreCheckResult.Reason.UNSUPPORTED_VERSION, reasonOf(check(bytes)))
    }

    @Test
    fun a_large_string_array_is_skipped_without_materialising_its_content() {
        // The tokenizer-vocabulary case PP-11 names explicitly: many string
        // elements walked by length-prefix only, then the real keys found
        // after it.
        val vocab = (0 until 5_000).map { "tok$it" }
        val bytes =
            header(version = 2, tensorCount = 0, kvCount = 2) {
                arrayOfStringsKv("tokenizer.ggml.tokens", vocab)
                stringKv("general.architecture", "llama")
            }
        val result = check(bytes)
        assertTrue("expected Plausible, got $result", result is GgufPreCheckResult.Plausible)
        assertEquals("llama", (result as GgufPreCheckResult.Plausible).architecture)
    }

    @Test
    fun a_length_claiming_bytes_beyond_the_real_file_is_refused() {
        // A string value's length header is kept intact, but the file is
        // truncated inside the value's own declared span — a structural
        // anomaly (the length points past this file's real size), distinct
        // from simply running out of the bounded prefix.
        val bytes =
            header(version = 2, tensorCount = 0, kvCount = 1) {
                stringKv("general.architecture", "llama")
            }
        val truncated = bytes.copyOf(bytes.size - 2) // chop the last two bytes of "llama"'s own content
        val result = check(truncated)
        assertTrue("expected a refusal, got $result", result is GgufPreCheckResult.Refused)
    }

    @Test
    fun the_FileChannel_overload_reads_a_real_pinned_descriptor() {
        val bytes =
            header(version = 2, tensorCount = 0, kvCount = 1) {
                stringKv("general.architecture", "llama")
            }
        val file = java.io.File.createTempFile("gguf-precheck-", ".gguf")
        try {
            file.writeBytes(bytes)
            java.io.RandomAccessFile(file, "r").channel.use { channel ->
                val result = GgufPreCheck.check(channel)
                assertTrue("expected Plausible, got $result", result is GgufPreCheckResult.Plausible)
                assertEquals("llama", (result as GgufPreCheckResult.Plausible).architecture)
            }
        } finally {
            file.delete()
        }
    }
}
