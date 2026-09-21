// BLAKE3-256, pure Kotlin/JVM.
//
// Why a hand-rolled implementation rather than a dependency: `:core:model`
// is the one module every process (`:app`, `:inference-service`,
// `:embedder-service`, `:testing`) can depend on, it must stay pure
// Kotlin/JVM (see the module's `app.skein.guard.isolation` plugin), and
// the project pins every artifact in `gradle/verification-metadata.xml`
// with no network fetch during a build. BLAKE3 is also not in
// `java.security.MessageDigest`. The algorithm itself is small and fully
// specified, so it is transcribed here from the BLAKE3 spec's reference
// implementation and pinned by the official test vectors in
// `Blake3Test` (empty, "abc", and the 0/251-pattern vectors at the 1 KiB
// chunk and multi-chunk tree boundaries: 1023, 1024, 1025, 2048, 3072,
// 4096, 8192, 102400).
//
// Scope: unkeyed hashing with the default 32-byte output only. Keyed
// hashing and `derive_key` are deliberately absent — nothing in Skein
// needs them, and an unused mode is an untested mode. `docs/design/
// POST_REVIEW_RESOLUTIONS.md` §2.2's post-mmap BLAKE3 digest is a
// separate (migration 004) concern with its own streaming requirements;
// this object hashes an in-memory `ByteArray`, which is all the revision
// hash (§1.3) needs.
//
// Security note: BLAKE3 here is used for *content addressing* (which
// revision of a note a citation points at), never for authentication of
// untrusted input. No key material passes through this file.

package us.aherrera.skein.core.model

/**
 * BLAKE3-256 over an in-memory byte array.
 *
 * See `docs/design/POST_REVIEW_RESOLUTIONS.md` §1.3, which specifies
 * BLAKE3-256 as the algorithm behind [RevisionHash].
 */
public object Blake3 {
    private const val BLOCK_LEN: Int = 64
    private const val CHUNK_LEN: Int = 1024

    private const val CHUNK_START: Int = 1
    private const val CHUNK_END: Int = 1 shl 1
    private const val PARENT: Int = 1 shl 2
    private const val ROOT: Int = 1 shl 3

    // The SHA-256 IV, as BLAKE3 specifies. Written as Long literals with an
    // explicit `.toInt()` because Kotlin has no unsigned 32-bit hex literal
    // for Int and the two-complement spellings are easy to typo.
    private val IV: IntArray =
        intArrayOf(
            0x6A09E667L.toInt(),
            0xBB67AE85L.toInt(),
            0x3C6EF372L.toInt(),
            0xA54FF53AL.toInt(),
            0x510E527FL.toInt(),
            0x9B05688CL.toInt(),
            0x1F83D9ABL.toInt(),
            0x5BE0CD19L.toInt(),
        )

    private val MSG_PERMUTATION: IntArray =
        intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    private val HEX: CharArray = "0123456789abcdef".toCharArray()

    /** 32-byte BLAKE3 digest of [input]. */
    public fun hash(input: ByteArray): ByteArray = Hasher().update(input).digest()

    /** Lowercase hex of [hash] — 64 characters. */
    public fun hex(input: ByteArray): String = toHex(hash(input))

    /** Lowercase hex BLAKE3-256 of [input]'s UTF-8 bytes. */
    public fun hexUtf8(input: String): String = hex(input.toByteArray(Charsets.UTF_8))

    /** Lowercase hex encoding of [bytes]. */
    public fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Core compression function
    // ------------------------------------------------------------------

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
        state[d] = (state[d] xor state[a]).rotateRight(16)
        state[c] = state[c] + state[d]
        state[b] = (state[b] xor state[c]).rotateRight(12)
        state[a] = state[a] + state[b] + my
        state[d] = (state[d] xor state[a]).rotateRight(8)
        state[c] = state[c] + state[d]
        state[b] = (state[b] xor state[c]).rotateRight(7)
    }

    private fun round(
        state: IntArray,
        m: IntArray,
    ) {
        g(state, 0, 4, 8, 12, m[0], m[1])
        g(state, 1, 5, 9, 13, m[2], m[3])
        g(state, 2, 6, 10, 14, m[4], m[5])
        g(state, 3, 7, 11, 15, m[6], m[7])
        g(state, 0, 5, 10, 15, m[8], m[9])
        g(state, 1, 6, 11, 12, m[10], m[11])
        g(state, 2, 7, 8, 13, m[12], m[13])
        g(state, 3, 4, 9, 14, m[14], m[15])
    }

    private fun permute(m: IntArray): IntArray = IntArray(16) { m[MSG_PERMUTATION[it]] }

    /** Returns the full 16-word compression output (the first 8 words are the chaining value). */
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
        var m = blockWords
        for (i in 0 until 7) {
            round(state, m)
            if (i < 6) m = permute(m)
        }
        for (i in 0 until 8) {
            state[i] = state[i] xor state[i + 8]
            state[i + 8] = state[i + 8] xor chainingValue[i]
        }
        return state
    }

    private fun wordsFromLittleEndian(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): IntArray {
        // The caller always supplies a 64-byte block; a short final block is
        // zero-padded here rather than by the caller.
        val words = IntArray(16)
        for (i in 0 until 16) {
            var w = 0
            for (j in 0 until 4) {
                val index = i * 4 + j
                val byte = if (index < length) bytes[offset + index].toInt() and 0xFF else 0
                w = w or (byte shl (8 * j))
            }
            words[i] = w
        }
        return words
    }

    // ------------------------------------------------------------------
    // Chunk / parent / root outputs
    // ------------------------------------------------------------------

    private class Output(
        val inputChainingValue: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int,
    ) {
        fun chainingValue(): IntArray = compress(inputChainingValue, blockWords, counter, blockLen, flags).copyOf(8)

        /** The 32-byte root digest (the first output block, little-endian). */
        fun rootBytes(): ByteArray {
            val words = compress(inputChainingValue, blockWords, 0L, blockLen, flags or ROOT)
            val out = ByteArray(32)
            for (i in 0 until 8) {
                val w = words[i]
                out[i * 4] = (w and 0xFF).toByte()
                out[i * 4 + 1] = ((w ushr 8) and 0xFF).toByte()
                out[i * 4 + 2] = ((w ushr 16) and 0xFF).toByte()
                out[i * 4 + 3] = ((w ushr 24) and 0xFF).toByte()
            }
            return out
        }
    }

    private fun parentOutput(
        leftCv: IntArray,
        rightCv: IntArray,
    ): Output {
        val blockWords = IntArray(16)
        leftCv.copyInto(blockWords, 0)
        rightCv.copyInto(blockWords, 8)
        return Output(
            inputChainingValue = IV,
            blockWords = blockWords,
            counter = 0L,
            blockLen = BLOCK_LEN,
            flags = PARENT,
        )
    }

    // ------------------------------------------------------------------
    // Streaming hasher
    // ------------------------------------------------------------------

    /** The 1 KiB chunk currently being filled. */
    private class ChunkState(
        val chunkCounter: Long,
    ) {
        var cv: IntArray = IV
        val block: ByteArray = ByteArray(BLOCK_LEN)
        var blockLen: Int = 0
        var blocksCompressed: Int = 0

        fun length(): Int = BLOCK_LEN * blocksCompressed + blockLen

        private fun startFlag(): Int = if (blocksCompressed == 0) CHUNK_START else 0

        fun update(
            input: ByteArray,
            from: Int,
            count: Int,
        ) {
            var offset = from
            var remaining = count
            while (remaining > 0) {
                if (blockLen == BLOCK_LEN) {
                    cv =
                        compress(
                            cv,
                            wordsFromLittleEndian(block, 0, BLOCK_LEN),
                            chunkCounter,
                            BLOCK_LEN,
                            startFlag(),
                        ).copyOf(8)
                    blocksCompressed++
                    blockLen = 0
                    block.fill(0)
                }
                val take = minOf(BLOCK_LEN - blockLen, remaining)
                input.copyInto(block, blockLen, offset, offset + take)
                blockLen += take
                offset += take
                remaining -= take
            }
        }

        fun output(): Output =
            Output(
                inputChainingValue = cv,
                blockWords = wordsFromLittleEndian(block, 0, blockLen),
                counter = chunkCounter,
                blockLen = blockLen,
                flags = startFlag() or CHUNK_END,
            )
    }

    /**
     * Incremental BLAKE3-256. Feed bytes with [update] in any number of
     * pieces, then take the digest with [digest] / [hex]; the result depends
     * only on the concatenated bytes, never on how they were split.
     *
     * This exists so a document's revision hash can be computed without ever
     * holding a second copy of its body in memory (see [RevisionHashing]) —
     * importing a 10 MB text file must not allocate 10 MB extra just to
     * address it. A hasher is not thread-safe.
     */
    public class Hasher {
        private var chunk: ChunkState = ChunkState(0L)

        // BLAKE3's chaining-value stack. 54 entries is the spec's bound:
        // one per bit of the maximum 2^54-chunk input.
        private val cvStack: Array<IntArray?> = arrayOfNulls(54)
        private var cvStackLen: Int = 0

        public fun update(input: ByteArray): Hasher = update(input, 0, input.size)

        public fun update(
            input: ByteArray,
            from: Int,
            count: Int,
        ): Hasher {
            var offset = from
            var remaining = count
            while (remaining > 0) {
                if (chunk.length() == CHUNK_LEN) {
                    val cv = chunk.output().chainingValue()
                    val totalChunks = chunk.chunkCounter + 1
                    addChunkChainingValue(cv, totalChunks)
                    chunk = ChunkState(totalChunks)
                }
                val take = minOf(CHUNK_LEN - chunk.length(), remaining)
                chunk.update(input, offset, take)
                offset += take
                remaining -= take
            }
            return this
        }

        /** The 32-byte digest of everything fed so far. */
        public fun digest(): ByteArray {
            var output = chunk.output()
            var remaining = cvStackLen
            while (remaining > 0) {
                remaining--
                output = parentOutput(requireNotNull(cvStack[remaining]), output.chainingValue())
            }
            return output.rootBytes()
        }

        /** Lowercase hex of [digest] — 64 characters. */
        public fun hex(): String = toHex(digest())

        /**
         * Merges [newCv] into the stack. The trailing zero bits of
         * [totalChunks] say how many completed left subtrees this new chunk
         * finishes off, so the loop pops and combines exactly that many
         * nodes — the spec's left-complete tree, built bottom-up without
         * ever holding the whole input.
         */
        private fun addChunkChainingValue(
            newCv: IntArray,
            totalChunks: Long,
        ) {
            var cv = newCv
            var total = totalChunks
            while (total and 1L == 0L) {
                cvStackLen--
                cv = parentOutput(requireNotNull(cvStack[cvStackLen]), cv).chainingValue()
                total = total shr 1
            }
            cvStack[cvStackLen] = cv
            cvStackLen++
        }
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}
