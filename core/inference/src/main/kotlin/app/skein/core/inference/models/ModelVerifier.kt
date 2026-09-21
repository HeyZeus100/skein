// skein-st1r (POST_REVIEW_RESOLUTIONS §2.2, rule 2): the dual-hash gate.
//
// Plan `E3.I5` hashed the fd with SHA-256 and handed it to llama.cpp. The
// review's point is that the hash and the mapping are two separate reads of a
// mutable inode: anything with write access in between wins, and a
// `MAP_PRIVATE` mapping is a copy-on-write snapshot of *our writes*, not a
// frozen snapshot of the file. So this file runs two gates over the same
// model, deliberately with two different algorithms:
//
//   pre-mmap   SHA-256, streamed over the exact open channel (not a re-open of
//              the path — re-opening reintroduces the swap-the-file race that
//              `/proc/self/fd/<n>` exists to close).
//   post-mmap  BLAKE3-256 over the `MappedByteBuffer` itself, i.e. over the
//              very bytes about to be handed to the native engine.
//
// Two different algorithms rather than SHA-256 twice, because a second pass
// with the same `MessageDigest` shares every bug the first one has; and
// BLAKE3 is faster than SHA-256 on aarch64, so the second pass is close to
// free at load time.
//
// A post-mmap mismatch is reported as `Tampered`, not `HashMismatch`: the same
// path passed SHA-256 seconds earlier, so the bytes changed *between the
// gates*. That distinction is the whole reason the second gate exists.

package app.skein.core.inference.models

import app.skein.core.model.SkeinLog
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

private const val TAG = "ModelVerifier"

/** Both digests of §2's dual-hash discipline over one file, plus its length. */
data class FileDigests(
    val sha256: String,
    val blake3: String,
    val sizeBytes: Long,
)

/** Streams bytes through both digests in a single pass. */
internal class DigestAccumulator {
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private val blake3 = Blake3.Hasher()
    private var size = 0L

    fun update(
        buffer: ByteArray,
        length: Int,
    ) {
        sha256.update(buffer, 0, length)
        blake3.update(buffer, 0, length)
        size += length
    }

    fun finish(): FileDigests = FileDigests(Hex.encode(sha256.digest()), Hex.encode(blake3.digest()), size)
}

/**
 * Test-only seam for §2.4's in-place-modification attack.
 *
 * The attack the review asked us to test — "attacker modifies file after hash
 * check, before mmap" — has no natural trigger point from outside, so the two
 * phase boundaries are exposed as overridable no-ops. Production always passes
 * [None]; the hook cannot influence the verdict, only the timing, so leaving it
 * in the main source set costs nothing and keeps the attack test honest (it
 * drives the real verifier, not a copy of it).
 */
interface LoadPhaseHook {
    /** Runs after the pre-mmap SHA-256 gate has passed and before the mapping is created. */
    fun afterPreMmapVerify(binding: ManifestBinding) = Unit

    /** Runs after the mapping is created and before the post-mmap BLAKE3 gate reads it. */
    fun afterMap(mapped: MappedByteBuffer) = Unit

    companion object {
        val None: LoadPhaseHook = object : LoadPhaseHook {}
    }
}

/** Outcome of the full §2 load gate. */
sealed interface LoadVerification {
    /** Both gates passed. [mapped] is the read-only mapping whose bytes were digested. */
    data class Ready(
        val mapped: MappedByteBuffer,
    ) : LoadVerification

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : LoadVerification
}

object ModelVerifier {
    /** 4 MiB reads, per plan `E3.I5` (a 2.5 GB model hashes in ~10 s on the Fold). */
    const val READ_CHUNK_BYTES: Int = 4 * 1024 * 1024

    /** Both digests of [input] in one pass. Does not close [input]. */
    fun digests(input: InputStream): FileDigests {
        val accumulator = DigestAccumulator()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            accumulator.update(buffer, read)
        }
        return accumulator.finish()
    }

    /** Streaming SHA-256 of [input], lowercase hex. */
    fun sha256Hex(input: InputStream): String = digests(input).sha256

    /**
     * Streaming SHA-256 over [channel] from position 0, using absolute reads so
     * the channel's own position — and therefore any concurrent mapping of the
     * same channel — is untouched.
     */
    fun sha256Hex(channel: FileChannel): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(READ_CHUNK_BYTES)
        var position = 0L
        val size = channel.size()
        while (position < size) {
            buffer.clear()
            val read = channel.read(buffer, position)
            if (read <= 0) break
            buffer.flip()
            digest.update(buffer)
            position += read
        }
        return Hex.encode(digest.digest())
    }

    /** BLAKE3-256 over every remaining byte of [mapped], lowercase hex. */
    fun blake3Hex(mapped: ByteBuffer): String = Blake3.hexDigest(mapped)

    /**
     * Constant-time digest comparison.
     *
     * Compares the decoded bytes with `MessageDigest.isEqual` rather than
     * `String.equals`, per §2.4's `ConstantTimeCompareTest`. A non-hex or
     * wrong-length input compares unequal instead of throwing — a malformed
     * expectation must refuse the load, not crash it.
     */
    fun constantTimeEquals(
        expectedHex: String,
        actualHex: String,
    ): Boolean {
        val expected = Hex.decodeOrNull(expectedHex) ?: return false
        val actual = Hex.decodeOrNull(actualHex) ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    /**
     * Gate 1: every bound file's SHA-256, checked before anything is mapped.
     *
     * The main file is hashed through [mainChannel] — the channel the shared
     * lock is held on — so the bytes hashed are the bytes of the fd we will
     * map, not of whatever the path resolves to a moment later. Companions are
     * hashed by path on every load (§2.2: "verify each file's hash before use,
     * not just at import").
     */
    @Suppress("ReturnCount")
    fun verifyBeforeMmap(
        binding: ManifestBinding,
        mainChannel: FileChannel,
    ): ModelVerification {
        for (file in binding.files) {
            val actual =
                try {
                    if (file.role == ModelFileRole.MAIN) {
                        if (mainChannel.size() != file.expectedSizeBytes) {
                            return refuse(ModelVerification.SizeMismatch(file.role))
                        }
                        sha256Hex(mainChannel)
                    } else {
                        if (!file.path.isFile) return refuse(ModelVerification.FileMissing(file.role))
                        if (file.path.length() != file.expectedSizeBytes) {
                            return refuse(ModelVerification.SizeMismatch(file.role))
                        }
                        FileInputStream(file.path).use { sha256Hex(it) }
                    }
                } catch (e: IOException) {
                    SkeinLog.w(TAG, "pre-mmap read failed role=${file.role.wire}", e)
                    return refuse(ModelVerification.IoFailure(file.role, "read"))
                }
            if (!constantTimeEquals(file.expectedSha256, actual)) {
                return refuse(ModelVerification.HashMismatch(file.role, DigestAlgorithm.SHA256))
            }
        }
        return ModelVerification.Verified
    }

    /**
     * Gate 2: BLAKE3-256 over the mapped region.
     *
     * A mismatch here means the bytes changed after gate 1 passed — the
     * in-place-write attack — so it is reported as [ModelVerification.Tampered].
     */
    fun verifyAfterMmap(
        binding: ManifestBinding,
        mapped: ByteBuffer,
    ): ModelVerification {
        val expected = binding.main.expectedBlake3
        val actual = blake3Hex(mapped)
        return if (constantTimeEquals(expected, actual)) {
            ModelVerification.Verified
        } else {
            refuse(ModelVerification.Tampered(ModelFileRole.MAIN))
        }
    }

    /**
     * The full §2 load gate: verify, map, re-verify.
     *
     * On [LoadVerification.Ready] the caller may hand the main fd to the native
     * engine via `/proc/self/fd/<dup>`; on [LoadVerification.Refused] it must
     * unload without doing so. [hook] is the test seam described on
     * [LoadPhaseHook] and defaults to a no-op.
     */
    @Suppress("ReturnCount")
    fun verifyForLoad(
        handle: ModelHandle,
        binding: ManifestBinding,
        hook: LoadPhaseHook = LoadPhaseHook.None,
    ): LoadVerification {
        val channel = handle.mainChannel
        val preMmap = verifyBeforeMmap(binding, channel)
        if (preMmap is ModelVerification.Refusal) return LoadVerification.Refused(preMmap)

        hook.afterPreMmapVerify(binding)

        val mapped =
            try {
                channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
            } catch (e: IOException) {
                SkeinLog.w(TAG, "mmap failed", e)
                return LoadVerification.Refused(ModelVerification.IoFailure(ModelFileRole.MAIN, "mmap"))
            }

        hook.afterMap(mapped)

        val postMmap = verifyAfterMmap(binding, mapped)
        if (postMmap is ModelVerification.Refusal) return LoadVerification.Refused(postMmap)

        SkeinLog.i(TAG, "model verified: both digests matched files=${binding.files.size}")
        return LoadVerification.Ready(mapped)
    }

    /** Logs the refusal's log-safe summary — never a path, never a digest — and returns it. */
    private fun refuse(refusal: ModelVerification.Refusal): ModelVerification.Refusal {
        SkeinLog.w(TAG, "model verification refused: ${refusal.summary}")
        return refusal
    }
}
