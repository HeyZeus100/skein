// skein-hewz — bounded GGUF structural pre-check (recon B-5, PP-11/PP-16;
// docs/design/SKEIN_HUB.md §3.3's structural-check table).
//
// PLACEMENT DECISION (SKEIN_HUB.md §3.3 assigns "GGUF magic, version,
// tensor counts, metadata lengths" to `:inference`, via
// `llama_model_load_from_file_ptr` refusing — "we do not re-implement any
// of it" — which is the FULL parse. This is a narrower, cheaper thing: a
// plausibility gate over a BOUNDED PREFIX (<= MAX_PREFIX_BYTES, 1 MiB) of
// the already-pinned, already-hash-verified copy, extracting exactly two
// allowlisted scalars (`general.architecture`, `general.file_type`) and
// refusing on any structural anomaly — never materialising an array's
// content or the tokenizer vocabulary, never touching the tensor
// directory, never calling into llama.cpp. It runs entirely within
// `:core:inference` (linked into `:app`), which is permitted for exactly
// the reason the bead's own instructions state: it reads a bounded prefix
// and extracts two allowlisted scalars — it is not a second GGUF parser
// standing in for llama.cpp (the thing `GgufMetadataProbe` was and
// SKEIN_HUB.md §3.3/§3.6/§8 deletes). The FULL inspection — tensor
// directory, quantisation support, chat-template application, everything
// this class deliberately does not attempt — stays inside `:inference`
// via `ModelInspector.inspect`/`IInferenceService.inspect`, which
// `ModelManager` always still calls afterward. This class exists only to
// refuse the obviously-bad case (bad magic, truncated header, an absurd
// count) before paying for that IPC round trip, matching PP-11/PP-16's
// upstream precedent: PocketPal's bounded header reader runs before its
// (in-process, rejected-by-this-design) full parse; this class runs
// before `:inference`'s full parse, and unlike PocketPal's, nothing here
// is ever handed to a native parser — the pinned copy is untouched by
// this class beyond these length-checked, allowlisted reads.
//
// Neither reviewed upstream (PocketPal, OfflineLLM) has a bounded
// pre-check at all (OL-32/OL-41), so there is no reference implementation
// to port; the seven constants and version/width rules below are this
// design's own translation of PP-11's checklist (recon §7, the
// `ggufHeader.ts` table) into Kotlin. `skein-wt92`'s twelve fixtures are a
// follow-up (broader adversarial corpus); this bead's own tests cover the
// six cases its own acceptance criteria name.

package app.skein.core.inference.models

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Answers "is this plausibly a GGUF we can load?" from a bounded prefix of
 * [channel], without mapping the file and without materialising any array
 * or string value's content.
 *
 * Reads at most [MAX_PREFIX_BYTES] from the start of [channel] in one bulk
 * read, then parses entirely out of that in-memory buffer by cursor
 * arithmetic — no further I/O. A value's content (a string's bytes, an
 * array's elements) is never copied out of the buffer except for the two
 * allowlisted keys; every other value is *skipped* by advancing the
 * cursor past its declared length, after that length has been checked
 * against both the buffer's own remaining bytes and the real file's total
 * size (`channel.size()`) — so neither a truncated prefix nor a length
 * claiming bytes beyond the actual file can be read as if it were valid.
 *
 * The prefix is a *bound on work*, not a bound on the file: a genuine
 * model's key/value section is several MiB (a 150k-entry tokenizer alone
 * is ~5 MB of strings), so the walk routinely runs out of prefix inside
 * it. Once the header has validated, that is inconclusive rather than
 * bad — the result is [GgufPreCheckResult.Plausible] carrying whatever the
 * walk read before the prefix ended, and the sandboxed inspection in
 * `:inference` remains the authority. Only a header that does not fit,
 * a bad magic/version/count, an absurd key, or a length pointing past
 * the real file refuses.
 *
 * Every failure mode is a typed [GgufPreCheckResult.Refused] value, never
 * an exception: a hostile or corrupt header is data to be rejected, not a
 * condition allowed to crash the caller.
 */
public object GgufPreCheck {
    /** `"GGUF"` read as a little-endian `u32` — `docs/research/POCKETPAL_RECON.md` §7. */
    public const val GGUF_MAGIC: Long = 0x4655_4747L

    public const val MIN_VERSION: Long = 1L
    public const val MAX_VERSION: Long = 3L

    /** A metadata-count field this large would drive an unbounded loop. */
    public const val MAX_KV_COUNT: Long = 4_096L

    /** Bound on the tensor directory's own count field (never walked by this class). */
    public const val MAX_TENSOR_COUNT: Long = 65_536L

    /** An absurd key or string-value length allocating unbounded memory. */
    public const val MAX_KEY_LENGTH: Long = 4_096L

    /** An array length larger than any real vocabulary. */
    public const val MAX_ARRAY_COUNT: Long = 33_554_432L

    /** The bounded prefix this check ever reads, regardless of the real file's size. */
    public const val MAX_PREFIX_BYTES: Int = 1 shl 20 // 1 MiB

    private const val KEY_ARCHITECTURE = "general.architecture"
    private const val KEY_FILE_TYPE = "general.file_type"

    // GGUF value-type ordinals (gguf-py `GGUFValueType`).
    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    public fun check(channel: FileChannel): GgufPreCheckResult {
        val totalSizeBytes = channel.size()
        val prefixLength = minOf(totalSizeBytes, MAX_PREFIX_BYTES.toLong()).toInt()
        val buffer = ByteBuffer.allocate(prefixLength).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) break
        }
        buffer.flip()
        return check(buffer, totalSizeBytes)
    }

    /** JVM-testable overload: parses an in-memory prefix directly. */
    public fun check(
        prefix: ByteBuffer,
        totalSizeBytes: Long,
    ): GgufPreCheckResult {
        val cursor = Cursor(prefix.order(ByteOrder.LITTLE_ENDIAN), totalSizeBytes)

        val magic = cursor.readU32() ?: return refuse(GgufPreCheckResult.Reason.TRUNCATED_HEADER)
        if (magic != GGUF_MAGIC) return refuse(GgufPreCheckResult.Reason.BAD_MAGIC)

        val version = cursor.readU32() ?: return refuse(GgufPreCheckResult.Reason.TRUNCATED_HEADER)
        if (version < MIN_VERSION || version > MAX_VERSION) {
            return refuse(GgufPreCheckResult.Reason.UNSUPPORTED_VERSION)
        }
        val wide = version != 1L

        val tensorCount = cursor.readCount(wide) ?: return refuse(GgufPreCheckResult.Reason.TRUNCATED_HEADER)
        if (tensorCount < 0 || tensorCount > MAX_TENSOR_COUNT) {
            return refuse(GgufPreCheckResult.Reason.TENSOR_COUNT_OUT_OF_BOUNDS)
        }

        val kvCount = cursor.readCount(wide) ?: return refuse(GgufPreCheckResult.Reason.TRUNCATED_HEADER)
        if (kvCount < 0 || kvCount > MAX_KV_COUNT) {
            return refuse(GgufPreCheckResult.Reason.KV_COUNT_OUT_OF_BOUNDS)
        }

        var architecture: String? = null
        var fileType: Long? = null

        // Running out of the bounded prefix INSIDE the key/value section is
        // not a verdict on the file. Every real LLM's metadata is far larger
        // than the prefix: the smoke model's `tokenizer.ggml.tokens` and
        // `tokenizer.ggml.merges` arrays are 2.6 MB and 2.7 MB, and its
        // key/value section ends at 5.66 MiB - so refusing on
        // PREFIX_EXHAUSTED rejected every genuine model after the copy and
        // only the lane's tiny fixture ever passed. The header (magic,
        // version, both counts) has already been validated by this point;
        // whatever the walk managed to read is reported, and the real,
        // sandboxed inspection in `:inference` remains the authority. Any
        // OTHER reason found before the prefix ran out (a length past the
        // file's end, an absurd key, a bad array) still refuses.
        fun exhaustedOrRefuse(reason: GgufPreCheckResult.Reason): GgufPreCheckResult =
            if (reason == GgufPreCheckResult.Reason.PREFIX_EXHAUSTED) {
                GgufPreCheckResult.Plausible(architecture = architecture, fileType = fileType)
            } else {
                refuse(reason)
            }

        for (i in 0 until kvCount) {
            val keyLength =
                cursor.readCount(wide) ?: return exhaustedOrRefuse(GgufPreCheckResult.Reason.PREFIX_EXHAUSTED)
            if (keyLength < 0 || keyLength > MAX_KEY_LENGTH) {
                return refuse(GgufPreCheckResult.Reason.KEY_TOO_LONG)
            }
            val key =
                cursor.readAsciiOrRefuse(keyLength)
                    ?: return exhaustedOrRefuse(cursor.lastRefusal ?: GgufPreCheckResult.Reason.PREFIX_EXHAUSTED)

            val valueType =
                cursor.readU32() ?: return exhaustedOrRefuse(GgufPreCheckResult.Reason.PREFIX_EXHAUSTED)

            when (key) {
                KEY_ARCHITECTURE -> {
                    val (value, refusal) = cursor.readValueCapturingString(valueType.toInt(), wide)
                    if (refusal != null) return exhaustedOrRefuse(refusal)
                    architecture = value
                }
                KEY_FILE_TYPE -> {
                    val (value, refusal) = cursor.readValueCapturingLong(valueType.toInt(), wide)
                    if (refusal != null) return exhaustedOrRefuse(refusal)
                    fileType = value
                }
                else -> {
                    val refusal = cursor.skipValue(valueType.toInt(), wide)
                    if (refusal != null) return exhaustedOrRefuse(refusal)
                }
            }
        }

        return GgufPreCheckResult.Plausible(architecture = architecture, fileType = fileType)
    }

    private fun refuse(reason: GgufPreCheckResult.Reason): GgufPreCheckResult.Refused =
        GgufPreCheckResult.Refused(reason)

    /**
     * Cursor over the loaded prefix buffer. Every "would read past the
     * buffer" check is [PREFIX_EXHAUSTED][GgufPreCheckResult.Reason.PREFIX_EXHAUSTED]
     * (we ran out of the bounded prefix, not necessarily a bad file);
     * every "would read past the real file" check is
     * [LENGTH_EXCEEDS_FILE][GgufPreCheckResult.Reason.LENGTH_EXCEEDS_FILE]
     * (the file itself cannot contain what this length claims — a
     * structural anomaly regardless of our own budget).
     */
    private class Cursor(
        private val buffer: ByteBuffer,
        private val totalSizeBytes: Long,
    ) {
        private var absolutePosition: Long = 0
        var lastRefusal: GgufPreCheckResult.Reason? = null

        fun readU32(): Long? {
            if (buffer.remaining() < 4) return null
            val value = buffer.int.toLong() and 0xFFFF_FFFFL
            absolutePosition += 4
            return value
        }

        fun readU64(): Long? {
            if (buffer.remaining() < 8) return null
            val raw = buffer.long
            absolutePosition += 8
            // A real GGUF's length/count fields never legitimately set the
            // sign bit (that would mean an exabyte-scale value); treat it
            // as certainly larger than any real file rather than let it
            // read as a negative Kotlin Long.
            return if (raw < 0) null else raw
        }

        /** Reads a count/length field at the version-appropriate width. */
        fun readCount(wide: Boolean): Long? = if (wide) readU64() else readU32()

        /** Reads exactly [length] bytes as UTF-8, or records why it could not. */
        fun readAsciiOrRefuse(length: Long): String? {
            val refusal = checkSpan(length)
            if (refusal != null) {
                lastRefusal = refusal
                return null
            }
            val bytes = ByteArray(length.toInt())
            buffer.get(bytes)
            absolutePosition += length
            return String(bytes, Charsets.UTF_8)
        }

        /** Skips exactly [length] bytes (never copies them out). */
        fun skipSpan(length: Long): GgufPreCheckResult.Reason? {
            val refusal = checkSpan(length)
            if (refusal != null) return refusal
            buffer.position(buffer.position() + length.toInt())
            absolutePosition += length
            return null
        }

        /** `null` when [length] bytes may safely be consumed from here. */
        private fun checkSpan(length: Long): GgufPreCheckResult.Reason? {
            if (length < 0) return GgufPreCheckResult.Reason.LENGTH_EXCEEDS_FILE
            if (absolutePosition + length > totalSizeBytes) return GgufPreCheckResult.Reason.LENGTH_EXCEEDS_FILE
            if (length > buffer.remaining()) return GgufPreCheckResult.Reason.PREFIX_EXHAUSTED
            return null
        }

        /** Reads a scalar/string/array value, capturing a STRING value's content. */
        fun readValueCapturingString(
            valueType: Int,
            wide: Boolean,
        ): Pair<String?, GgufPreCheckResult.Reason?> {
            if (valueType != TYPE_STRING) {
                val refusal = skipValue(valueType, wide)
                return null to refusal
            }
            val length = readCount(wide) ?: return null to GgufPreCheckResult.Reason.PREFIX_EXHAUSTED
            if (length > MAX_KEY_LENGTH) {
                val refusal = skipSpan(length) ?: GgufPreCheckResult.Reason.KEY_TOO_LONG
                return null to refusal
            }
            val text =
                readAsciiOrRefuse(length) ?: return null to (lastRefusal ?: GgufPreCheckResult.Reason.PREFIX_EXHAUSTED)
            return text to null
        }

        /** Reads a scalar value as a `Long`, capturing only fixed-width integer types. */
        fun readValueCapturingLong(
            valueType: Int,
            wide: Boolean,
        ): Pair<Long?, GgufPreCheckResult.Reason?> {
            val width = fixedWidthOf(valueType)
            if (width == null) {
                val refusal = skipValue(valueType, wide)
                return null to refusal
            }
            if (width > buffer.remaining()) return null to GgufPreCheckResult.Reason.PREFIX_EXHAUSTED
            if (absolutePosition + width > totalSizeBytes) return null to GgufPreCheckResult.Reason.LENGTH_EXCEEDS_FILE
            val value =
                when (width) {
                    1 -> buffer.get().toLong() and 0xFFL
                    2 -> buffer.short.toLong() and 0xFFFFL
                    4 -> buffer.int.toLong() and 0xFFFF_FFFFL
                    else -> buffer.long
                }
            absolutePosition += width
            return value to null
        }

        /** Skips (never materialises) a value of any GGUF type, one array level deep. */
        fun skipValue(
            valueType: Int,
            wide: Boolean,
        ): GgufPreCheckResult.Reason? {
            val width = fixedWidthOf(valueType)
            if (width != null) return skipSpan(width.toLong())

            return when (valueType) {
                TYPE_STRING -> {
                    val length = readCount(wide) ?: return GgufPreCheckResult.Reason.PREFIX_EXHAUSTED
                    skipSpan(length)
                }
                TYPE_ARRAY -> skipArray(wide)
                else -> GgufPreCheckResult.Reason.INVALID_VALUE_TYPE
            }
        }

        private fun skipArray(wide: Boolean): GgufPreCheckResult.Reason? {
            val elementType = readU32()?.toInt() ?: return GgufPreCheckResult.Reason.PREFIX_EXHAUSTED
            if (elementType == TYPE_ARRAY) return GgufPreCheckResult.Reason.NESTED_ARRAY

            val arrayLength = readCount(wide) ?: return GgufPreCheckResult.Reason.PREFIX_EXHAUSTED
            if (arrayLength < 0 || arrayLength > MAX_ARRAY_COUNT) {
                return GgufPreCheckResult.Reason.ARRAY_COUNT_OUT_OF_BOUNDS
            }

            val elementWidth = fixedWidthOf(elementType)
            if (elementWidth != null) {
                // Fixed-width elements: one jump, no per-element work.
                return skipSpan(arrayLength * elementWidth.toLong())
            }
            if (elementType != TYPE_STRING) return GgufPreCheckResult.Reason.INVALID_VALUE_TYPE

            // Variable-width (string) elements: walked one length-prefix at
            // a time, never materialising an element's bytes — this is the
            // "seek-past-values" technique PP-11 documents for exactly this
            // case (a large tokenizer vocabulary). Every element's length
            // is still bounds-checked, so this loop cannot run past the
            // buffer it is already reading from.
            for (i in 0 until arrayLength) {
                val elementLength = readCount(wide) ?: return GgufPreCheckResult.Reason.PREFIX_EXHAUSTED
                val refusal = skipSpan(elementLength)
                if (refusal != null) return refusal
            }
            return null
        }

        private fun fixedWidthOf(valueType: Int): Int? =
            when (valueType) {
                TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
                TYPE_UINT16, TYPE_INT16 -> 2
                TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
                TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
                else -> null
            }
    }
}

/** The bounded pre-check's verdict — see [GgufPreCheck]. */
public sealed interface GgufPreCheckResult {
    /**
     * Structurally plausible. Neither field is a promise: a hostile file
     * can still declare any string it likes for [architecture] (this is
     * not a trust boundary — `ModelInspector.inspect`, inside
     * `:inference`, is), and either can be `null` when the file declares
     * none.
     */
    public data class Plausible(
        val architecture: String?,
        val fileType: Long?,
    ) : GgufPreCheckResult

    public data class Refused(
        val reason: Reason,
    ) : GgufPreCheckResult

    public enum class Reason {
        TRUNCATED_HEADER,
        BAD_MAGIC,
        UNSUPPORTED_VERSION,
        KV_COUNT_OUT_OF_BOUNDS,
        TENSOR_COUNT_OUT_OF_BOUNDS,
        KEY_TOO_LONG,
        ARRAY_COUNT_OUT_OF_BOUNDS,
        NESTED_ARRAY,
        INVALID_VALUE_TYPE,
        LENGTH_EXCEEDS_FILE,
        PREFIX_EXHAUSTED,
    }
}
