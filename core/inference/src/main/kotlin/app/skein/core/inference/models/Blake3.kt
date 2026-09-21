// skein-st1r (POST_REVIEW_RESOLUTIONS §2): hand-rolled BLAKE3-256.
//
// §2.2 requires the post-mmap pass to use a *different* algorithm from the
// pre-mmap pass, so that a bug in one `MessageDigest` implementation cannot
// hide a bit-flip from both gates. The JDK/Android `MessageDigest` registry
// has no BLAKE3 provider, and the project's non-negotiables forbid pulling in
// a third-party crypto library (same rule that produced the hand-rolled
// HKDF in `core/vault/.../blob/Hkdf.kt`), so the reference algorithm is
// implemented here directly from the BLAKE3 specification.
//
// Scope: unkeyed `hash` mode only. Keyed hashing and `derive_key` are not
// implemented because nothing in §2 needs them; adding them later is a matter
// of seeding `key`/`flags` differently in [Blake3.Hasher] (the tree logic
// below is already parameterised on both).
//
// Correctness is pinned by `Blake3Test`, which replays all 35 official
// vectors from the BLAKE3 reference repository
// (`core/inference/src/test/resources/blake3/official_test_vectors.txt`),
// including the extended (XOF) output, so a silent algorithm change fails the
// build (§2.4 `Blake3VectorTest`).

package app.skein.core.inference.models

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLAKE3-256 over bytes, byte buffers and streams.
 *
 * The tree structure is the reference one: the input is split into 1 KiB
 * chunks, each chunk is compressed into an 8-word chaining value, and the
 * chunk chaining values are combined pairwise by parent nodes until a single
 * root node remains. The root node's compression is re-run with an
 * incrementing output-block counter to produce output of any length, which is
 * what makes BLAKE3 an XOF; [digest] simply takes the first 32 bytes.
 */
object Blake3 {
    /** Digest size this codebase uses, in bytes (BLAKE3-256). */
    const val OUT_LEN: Int = 32

    private const val BLOCK_LEN = 64
    private const val CHUNK_LEN = 1024

    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8

    /** BLAKE3's IV is the SHA-256 IV: the fractional parts of the square roots of the first eight primes. */
    private val IV =
        intArrayOf(
            0x6A09E667,
            -0x4498517B,
            0x3C6EF372,
            -0x5AB00AC6,
            0x510E527F,
            -0x64FA9774,
            0x1F83D9AB,
            0x5BE0CD19,
        )

    private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    /** BLAKE3-256 of [input], as 32 raw bytes. */
    fun digest(input: ByteArray): ByteArray = Hasher().also { it.update(input, 0, input.size) }.digest()

    /** BLAKE3-256 of [input], lowercase hex. */
    fun hexDigest(input: ByteArray): String = Hex.encode(digest(input))

    /**
     * BLAKE3-256 of every remaining byte of [buffer], lowercase hex.
     *
     * This is the post-mmap entry point: [buffer] is the `MappedByteBuffer`
     * itself, so the digest covers the bytes the loader is about to hand to
     * the native engine rather than a second read of the path. The buffer's
     * position is restored before returning, so callers may hand the same
     * buffer on unchanged.
     */
    fun hexDigest(buffer: ByteBuffer): String {
        val hasher = Hasher()
        val view = buffer.duplicate()
        val scratch = ByteArray(CHUNK_LEN)
        while (view.hasRemaining()) {
            val n = minOf(scratch.size, view.remaining())
            view.get(scratch, 0, n)
            hasher.update(scratch, 0, n)
        }
        return Hex.encode(hasher.digest())
    }

    /** Incremental BLAKE3 state. Not thread-safe; one hasher per file. */
    class Hasher internal constructor(
        private val key: IntArray,
        private val flags: Int,
    ) {
        constructor() : this(IV.copyOf(), 0)

        private var chunk = ChunkState(key, 0L, flags)
        private val cvStack = arrayOfNulls<IntArray>(MAX_DEPTH)
        private var cvStackLen = 0

        fun update(
            input: ByteArray,
            offset: Int = 0,
            length: Int = input.size - offset,
        ) {
            var i = offset
            val end = offset + length
            while (i < end) {
                if (chunk.length() == CHUNK_LEN) {
                    val chunkCv = chunk.output().chainingValue()
                    val totalChunks = chunk.chunkCounter + 1
                    addChunkChainingValue(chunkCv, totalChunks)
                    chunk = ChunkState(key, totalChunks, flags)
                }
                val take = minOf(CHUNK_LEN - chunk.length(), end - i)
                chunk.update(input, i, take)
                i += take
            }
        }

        /** Fills [out] with root output bytes (BLAKE3 is an XOF; any length is valid). */
        fun digestInto(out: ByteArray) {
            var output = chunk.output()
            var remaining = cvStackLen
            while (remaining > 0) {
                remaining--
                output = parentOutput(cvStack[remaining]!!, output.chainingValue(), key, flags)
            }
            output.rootBytes(out)
        }

        /** The 32-byte BLAKE3-256 digest of everything fed to [update] so far. */
        fun digest(): ByteArray = ByteArray(OUT_LEN).also { digestInto(it) }

        /**
         * Merges [newCv] into the subtree stack. A chunk chaining value is
         * merged with its left sibling exactly as many times as there are
         * trailing zero bits in [totalChunks] — that count is the number of
         * subtrees that are now complete (reference implementation's
         * "number of 1 bits in the total" argument, expressed as a shift).
         */
        private fun addChunkChainingValue(
            newCv: IntArray,
            totalChunks: Long,
        ) {
            var cv = newCv
            var chunks = totalChunks
            while (chunks and 1L == 0L) {
                cvStackLen--
                cv = parentOutput(cvStack[cvStackLen]!!, cv, key, flags).chainingValue()
                chunks = chunks shr 1
            }
            cvStack[cvStackLen] = cv
            cvStackLen++
        }

        private companion object {
            /** 2^54 chunks is 2^64 bytes, the BLAKE3 input ceiling, so the stack can never exceed this. */
            const val MAX_DEPTH = 54
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * One node's compression input, kept intact until the tree shape is known:
     * the same node is either an interior chaining value or, if it turns out
     * to be the root, the seed for the extendable output.
     */
    private class Output(
        val inputChainingValue: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int,
    ) {
        fun chainingValue(): IntArray = compress(inputChainingValue, blockWords, counter, blockLen, flags).copyOf(8)

        fun rootBytes(out: ByteArray) {
            var blockCounter = 0L
            var written = 0
            while (written < out.size) {
                val words = compress(inputChainingValue, blockWords, blockCounter, blockLen, flags or ROOT)
                var w = 0
                while (w < words.size && written < out.size) {
                    val word = words[w]
                    var b = 0
                    while (b < 4 && written < out.size) {
                        out[written] = (word ushr (8 * b)).toByte()
                        written++
                        b++
                    }
                    w++
                }
                blockCounter++
            }
        }
    }

    private class ChunkState(
        chainingValue: IntArray,
        val chunkCounter: Long,
        private val flags: Int,
    ) {
        private var cv = chainingValue.copyOf()
        private val block = ByteArray(BLOCK_LEN)
        private var blockLen = 0
        private var blocksCompressed = 0

        fun length(): Int = BLOCK_LEN * blocksCompressed + blockLen

        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(
            input: ByteArray,
            offset: Int,
            length: Int,
        ) {
            var i = offset
            val end = offset + length
            while (i < end) {
                if (blockLen == BLOCK_LEN) {
                    cv = compress(cv, words(block), chunkCounter, BLOCK_LEN, flags or startFlag()).copyOf(8)
                    blocksCompressed++
                    block.fill(0)
                    blockLen = 0
                }
                val take = minOf(BLOCK_LEN - blockLen, end - i)
                System.arraycopy(input, i, block, blockLen, take)
                blockLen += take
                i += take
            }
        }

        fun output(): Output = Output(cv, words(block), chunkCounter, blockLen, flags or startFlag() or CHUNK_END)
    }

    private fun parentOutput(
        leftChildCv: IntArray,
        rightChildCv: IntArray,
        key: IntArray,
        flags: Int,
    ): Output {
        val blockWords = IntArray(16)
        leftChildCv.copyInto(blockWords, 0)
        rightChildCv.copyInto(blockWords, 8)
        return Output(key, blockWords, 0L, BLOCK_LEN, PARENT or flags)
    }

    /** Reads [block] as 16 little-endian 32-bit words. */
    private fun words(block: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(16) { buffer.getInt(it * 4) }
    }

    /**
     * The BLAKE3 compression function: seven rounds of the BLAKE2s-derived
     * quarter-round `g` over a 16-word state, with the message words permuted
     * between rounds. Returns all 16 words — the first 8 are the chaining
     * value, and the full 16 feed the extendable output.
     */
    private fun compress(
        chainingValue: IntArray,
        blockWords: IntArray,
        counter: Long,
        blockLen: Int,
        flags: Int,
    ): IntArray {
        val state =
            intArrayOf(
                chainingValue[0],
                chainingValue[1],
                chainingValue[2],
                chainingValue[3],
                chainingValue[4],
                chainingValue[5],
                chainingValue[6],
                chainingValue[7],
                IV[0],
                IV[1],
                IV[2],
                IV[3],
                counter.toInt(),
                (counter ushr 32).toInt(),
                blockLen,
                flags,
            )
        var block = blockWords
        for (roundIndex in 0 until 7) {
            applyRound(state, block)
            if (roundIndex < 6) block = permute(block)
        }
        for (i in 0 until 8) {
            state[i] = state[i] xor state[i + 8]
            state[i + 8] = state[i + 8] xor chainingValue[i]
        }
        return state
    }

    private fun permute(block: IntArray): IntArray = IntArray(16) { block[MSG_PERMUTATION[it]] }

    private fun applyRound(
        state: IntArray,
        m: IntArray,
    ) {
        // Columns.
        g(state, 0, 4, 8, 12, m[0], m[1])
        g(state, 1, 5, 9, 13, m[2], m[3])
        g(state, 2, 6, 10, 14, m[4], m[5])
        g(state, 3, 7, 11, 15, m[6], m[7])
        // Diagonals.
        g(state, 0, 5, 10, 15, m[8], m[9])
        g(state, 1, 6, 11, 12, m[10], m[11])
        g(state, 2, 7, 8, 13, m[12], m[13])
        g(state, 3, 4, 9, 14, m[14], m[15])
    }

    @Suppress("LongParameterList")
    private fun g(
        state: IntArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        mx: Int,
        my: Int,
    ) {
        state[a] = state[a] + state[b] + mx
        state[d] = Integer.rotateRight(state[d] xor state[a], 16)
        state[c] = state[c] + state[d]
        state[b] = Integer.rotateRight(state[b] xor state[c], 12)
        state[a] = state[a] + state[b] + my
        state[d] = Integer.rotateRight(state[d] xor state[a], 8)
        state[c] = state[c] + state[d]
        state[b] = Integer.rotateRight(state[b] xor state[c], 7)
    }
}

/** Lowercase hex, shared by [Blake3] and [ModelVerifier] so both gates print digests the same way. */
internal object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = DIGITS[v ushr 4]
            chars[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(chars)
    }

    /** Decodes lowercase-or-uppercase hex, or null when [value] is not well-formed hex. */
    fun decodeOrNull(value: String): ByteArray? {
        if (value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(value[i * 2], 16)
            val lo = Character.digit(value[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
