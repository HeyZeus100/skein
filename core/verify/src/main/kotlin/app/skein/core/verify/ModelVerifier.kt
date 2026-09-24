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
//
// skein-nxk (E4.I3, coordinator decision skein-hiwb) moved this file into the
// pure-JVM `:core:verify` so `:inference-service` can run it, and re-typed it
// from `:core:inference`'s store-side `ManifestBinding` onto [VerifyBinding].
// Two consequences worth stating here rather than leaving to the diff:
//
//   * the pre-mmap pass now computes BOTH digests in its single read, because
//     a service-side binding has no declared BLAKE3 to compare gate 2 against
//     (`ManifestFileRef` does not carry one) and the observed pre-mmap BLAKE3
//     is the correct fallback expectation — see [VerifyFile.expectedBlake3];
//   * `verifyForLoad(handle: ModelHandle, …)` is gone from here. `ModelHandle`
//     is `ImmutableModelStore`'s and stays in `:core:inference`, which now
//     supplies that overload as an extension (`VerifyBindings.kt`).

package app.skein.core.verify

import app.skein.core.model.Blake3
import app.skein.core.model.Hex
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

/**
 * Cooperative cancellation for a streaming digest (plan `E3.I5`: "verification
 * is cancellable — unload during verify aborts within one read").
 *
 * Polled once per [ModelVerifier.READ_CHUNK_BYTES] chunk, at the top of the
 * read loop, so an `unload` arriving mid-verify costs at most the read already
 * in flight rather than the ~10 s a 2.5 GB model takes to hash. Implementations
 * are called from the hashing thread and must not block.
 */
fun interface VerifyCancellation {
    fun isCancelled(): Boolean

    companion object {
        /** The production default for a load nobody can cancel (import, tests). */
        val Never: VerifyCancellation = VerifyCancellation { false }
    }
}

/**
 * Progress for one verification call, feeding `EngineStatus.state = "verifying"`.
 *
 * [bytesHashed] is cumulative *within the call it was passed to*: over one
 * file for the single-file digests, over every file of the binding for
 * [ModelVerifier.verifyBeforeMmap], and restarting at zero for the post-mmap
 * pass — the two gates read the same bytes twice, and pretending otherwise
 * would make a progress bar run to 200 %.
 */
fun interface VerifyProgress {
    fun onBytesHashed(bytesHashed: Long)

    companion object {
        val None: VerifyProgress = VerifyProgress { }
    }
}

/** Outcome of a cancellable streaming digest. */
sealed interface DigestOutcome {
    /** The digest ran to the end of the input. [hex] is lowercase hex. */
    data class Digested(
        val hex: String,
    ) : DigestOutcome

    /** The cancellation signal fired between chunks; no digest exists, and none is implied about the bytes. */
    data object Cancelled : DigestOutcome
}

/** Outcome of a cancellable streaming pass that computes both digests at once. */
sealed interface DigestsOutcome {
    data class Digested(
        val digests: FileDigests,
    ) : DigestsOutcome

    data object Cancelled : DigestsOutcome
}

/**
 * Streams bytes through both digests in a single pass — or, with
 * [withBlake3] false, through SHA-256 alone and reports an empty BLAKE3.
 *
 * The pure-Kotlin [Blake3] runs at ~2 MB/s on a Pixel 9 Pro Fold (measured,
 * skein-gg11.17: 13 minutes per pass over a 1.6 GB model) while the
 * hardware-backed SHA-256 `MessageDigest` runs at hundreds of MB/s, so a
 * caller that has no use for the BLAKE3 must be able to skip it.
 */
class DigestAccumulator(
    private val withBlake3: Boolean = true,
) {
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private val blake3 = if (withBlake3) Blake3.Hasher() else null
    private var size = 0L

    fun update(
        buffer: ByteArray,
        length: Int,
    ) {
        sha256.update(buffer, 0, length)
        blake3?.update(buffer, 0, length)
        size += length
    }

    fun finish(): FileDigests =
        FileDigests(Hex.encode(sha256.digest()), blake3?.let { Hex.encode(it.digest()) } ?: "", size)
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
    fun afterPreMmapVerify(binding: VerifyBinding) = Unit

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

/**
 * Gate 1's verdict plus the BLAKE3 the same single read observed for the main
 * file.
 *
 * [mainBlake3] is what gate 2 compares against when the binding declares no
 * [VerifyFile.expectedBlake3] — the service-side case. It is null whenever the
 * pass did not get as far as digesting the main file (a refusal on an earlier
 * companion, or a cancellation).
 */
data class PreMmapVerification(
    val verification: ModelVerification,
    val mainBlake3: String?,
)

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

    /** Streaming SHA-256 of [input], lowercase hex. Uncancellable; see [sha256]. */
    fun sha256Hex(input: InputStream): String = digests(input).sha256

    /**
     * Streaming SHA-256 over [channel] from position 0, lowercase hex.
     * Uncancellable; see [sha256].
     */
    fun sha256Hex(channel: FileChannel): String = hexOf(sha256(channel))

    /**
     * Cancellable streaming SHA-256 of [input].
     *
     * Does not close [input]. The cancellation signal is checked before each
     * chunk, so a cancel costs at most one outstanding [READ_CHUNK_BYTES] read.
     */
    fun sha256(
        input: InputStream,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): DigestOutcome =
        when (val outcome = bothDigests(input, cancellation, progress)) {
            DigestsOutcome.Cancelled -> DigestOutcome.Cancelled
            is DigestsOutcome.Digested -> DigestOutcome.Digested(outcome.digests.sha256)
        }

    /**
     * Cancellable streaming SHA-256 over [channel] from position 0.
     *
     * Uses absolute reads, so the channel's own position — and therefore any
     * concurrent mapping of the same channel — is untouched. [channel] is the
     * channel of the descriptor the loader was handed; hashing a *path* here
     * instead would reintroduce the swap-the-file race (MODEL_STORE.md §3).
     */
    fun sha256(
        channel: FileChannel,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): DigestOutcome =
        when (val outcome = bothDigests(channel, cancellation, progress)) {
            DigestsOutcome.Cancelled -> DigestOutcome.Cancelled
            is DigestsOutcome.Digested -> DigestOutcome.Digested(outcome.digests.sha256)
        }

    /**
     * Cancellable single-pass SHA-256 **and** (unless [withBlake3] is false)
     * BLAKE3 of [input].
     *
     * One read, two digests when both are wanted: the pre-mmap gate needs the
     * SHA-256 to compare against the manifest; a caller that also wants the
     * BLAKE3 of the same read (an import recording gate 2's future
     * expectation) gets it from the same pass rather than a second read,
     * which would open exactly the window §2.2 is about.
     */
    fun bothDigests(
        input: InputStream,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
        withBlake3: Boolean = true,
    ): DigestsOutcome {
        val accumulator = DigestAccumulator(withBlake3)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var hashed = 0L
        while (true) {
            if (cancellation.isCancelled()) return DigestsOutcome.Cancelled
            val read = input.read(buffer)
            if (read < 0) break
            accumulator.update(buffer, read)
            hashed += read
            progress.onBytesHashed(hashed)
        }
        return DigestsOutcome.Digested(accumulator.finish())
    }

    /** [bothDigests] over [channel] from position 0, by absolute reads. */
    fun bothDigests(
        channel: FileChannel,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
        withBlake3: Boolean = true,
    ): DigestsOutcome {
        val accumulator = DigestAccumulator(withBlake3)
        val buffer = ByteBuffer.allocate(READ_CHUNK_BYTES)
        val scratch = buffer.array()
        var position = 0L
        val size = channel.size()
        while (position < size) {
            if (cancellation.isCancelled()) return DigestsOutcome.Cancelled
            buffer.clear()
            val read = channel.read(buffer, position)
            if (read <= 0) break
            accumulator.update(scratch, read)
            position += read
            progress.onBytesHashed(position)
        }
        return DigestsOutcome.Digested(accumulator.finish())
    }

    /** BLAKE3-256 over every remaining byte of [mapped], lowercase hex. Uncancellable; see [blake3]. */
    fun blake3Hex(mapped: ByteBuffer): String = Blake3.hexDigest(mapped)

    /**
     * Cancellable SHA-256 over every remaining byte of [mapped] — gate 2's
     * digest when the binding declares no BLAKE3 (see [verifyForLoad]).
     * [mapped]'s position is untouched — a duplicate view is consumed.
     */
    fun sha256(
        mapped: ByteBuffer,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): DigestOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        val view = mapped.duplicate()
        val scratch = ByteArray(READ_CHUNK_BYTES)
        var hashed = 0L
        while (view.hasRemaining()) {
            if (cancellation.isCancelled()) return DigestOutcome.Cancelled
            val n = minOf(scratch.size, view.remaining())
            view.get(scratch, 0, n)
            digest.update(scratch, 0, n)
            hashed += n
            progress.onBytesHashed(hashed)
        }
        return DigestOutcome.Digested(Hex.encode(digest.digest()))
    }

    /**
     * Cancellable BLAKE3-256 over every remaining byte of [mapped].
     *
     * The post-mmap gate reads the whole model a second time, so it needs the
     * same abort-within-one-chunk property the pre-mmap gate has. [mapped]'s
     * position is untouched — a duplicate view is consumed.
     */
    fun blake3(
        mapped: ByteBuffer,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): DigestOutcome {
        val hasher = Blake3.Hasher()
        val view = mapped.duplicate()
        val scratch = ByteArray(READ_CHUNK_BYTES)
        var hashed = 0L
        while (view.hasRemaining()) {
            if (cancellation.isCancelled()) return DigestOutcome.Cancelled
            val n = minOf(scratch.size, view.remaining())
            view.get(scratch, 0, n)
            hasher.update(scratch, 0, n)
            hashed += n
            progress.onBytesHashed(hashed)
        }
        return DigestOutcome.Digested(Hex.encode(hasher.digest()))
    }

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
     * lock is held on, or the pinned descriptor's — so the bytes hashed are
     * the bytes of the fd we will map, not of whatever the path resolves to a
     * moment later.
     *
     * Companions are hashed on every load, not only at import (§2.2: "verify
     * each file's hash before use"). A companion listed in [companionChannels]
     * is hashed through its own descriptor, with the same anti-swap guarantee
     * the main file gets; one that is not falls back to its
     * [VerifyFile.path], which is what the app-side loader wants (it holds the
     * shared lock and the directory is `0500`) and what a service handed
     * descriptors should not rely on — a service-side binding carries no path,
     * so a companion missing from [companionChannels] there is refused as
     * [ModelVerification.FileMissing] rather than silently skipped.
     */
    fun verifyBeforeMmap(
        binding: VerifyBinding,
        mainChannel: FileChannel,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
        companionChannels: Map<ModelFileRole, FileChannel> = emptyMap(),
    ): ModelVerification =
        verifyBeforeMmapDetailed(binding, mainChannel, cancellation, progress, companionChannels).verification

    /**
     * [verifyBeforeMmap] plus, when [observeBlake3] is true, the BLAKE3 the
     * same pass observed for the main file.
     *
     * Default false: gate 1 is SHA-256, and gate 2 no longer needs an
     * observed BLAKE3 (see [verifyForLoad]) — the pure-Kotlin BLAKE3 cost
     * 13 minutes per pass on the Fold (skein-gg11.17), so it is only computed
     * when a caller asks for it.
     */
    @Suppress("ReturnCount")
    fun verifyBeforeMmapDetailed(
        binding: VerifyBinding,
        mainChannel: FileChannel,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
        companionChannels: Map<ModelFileRole, FileChannel> = emptyMap(),
        observeBlake3: Boolean = false,
    ): PreMmapVerification {
        var done = 0L
        var mainBlake3: String? = null
        for (file in binding.files) {
            val base = done
            val step = VerifyProgress { bytes -> progress.onBytesHashed(base + bytes) }
            val channel = if (file.role == ModelFileRole.MAIN) mainChannel else companionChannels[file.role]
            val path = file.path
            val outcome =
                try {
                    if (channel != null) {
                        if (channel.size() != file.expectedSizeBytes) {
                            return refused(ModelVerification.SizeMismatch(file.role))
                        }
                        bothDigests(channel, cancellation, step, withBlake3 = observeBlake3)
                    } else {
                        if (path == null || !path.isFile) return refused(ModelVerification.FileMissing(file.role))
                        if (path.length() != file.expectedSizeBytes) {
                            return refused(ModelVerification.SizeMismatch(file.role))
                        }
                        FileInputStream(path).use { bothDigests(it, cancellation, step, withBlake3 = observeBlake3) }
                    }
                } catch (e: IOException) {
                    SkeinLog.w(TAG, "pre-mmap read failed role=${file.role.wire}", e)
                    return refused(ModelVerification.IoFailure(file.role, "read"))
                }
            val digests =
                when (outcome) {
                    DigestsOutcome.Cancelled -> return refused(ModelVerification.Cancelled(file.role))
                    is DigestsOutcome.Digested -> outcome.digests
                }
            if (!constantTimeEquals(file.expectedSha256, digests.sha256)) {
                return refused(ModelVerification.HashMismatch(file.role, DigestAlgorithm.SHA256))
            }
            if (file.role == ModelFileRole.MAIN && observeBlake3) mainBlake3 = digests.blake3
            done += file.expectedSizeBytes
        }
        return PreMmapVerification(ModelVerification.Verified, mainBlake3)
    }

    /**
     * Gate 2: BLAKE3-256 over the mapped region, against [expectedBlake3].
     *
     * A mismatch here means the bytes changed after gate 1 passed — the
     * in-place-write attack — so it is reported as [ModelVerification.Tampered].
     */
    fun verifyAfterMmap(
        expectedBlake3: String,
        mapped: ByteBuffer,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): ModelVerification =
        when (val outcome = blake3(mapped, cancellation, progress)) {
            DigestOutcome.Cancelled -> refuse(ModelVerification.Cancelled(ModelFileRole.MAIN))
            is DigestOutcome.Digested ->
                if (constantTimeEquals(expectedBlake3, outcome.hex)) {
                    ModelVerification.Verified
                } else {
                    refuse(ModelVerification.Tampered(ModelFileRole.MAIN))
                }
        }

    /**
     * The full §2 load gate over a bare channel: verify, map, re-verify.
     *
     * [mainChannel] must be the channel of the descriptor the loader was
     * handed — [PinnedModelFile.channel] on the service side, the shared-lock
     * channel on the store side. It is never re-derived from a path here: that
     * is the whole point (MODEL_STORE.md §3).
     *
     * On [LoadVerification.Ready] the caller may hand the main fd to the native
     * engine via `/proc/self/fd/<dup>`; on [LoadVerification.Refused] it must
     * unload without doing so. [hook] is the test seam described on
     * [LoadPhaseHook] and defaults to a no-op.
     */
    @Suppress("ReturnCount")
    fun verifyForLoad(
        mainChannel: FileChannel,
        binding: VerifyBinding,
        hook: LoadPhaseHook = LoadPhaseHook.None,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
        companionChannels: Map<ModelFileRole, FileChannel> = emptyMap(),
    ): LoadVerification {
        val preMmap =
            verifyBeforeMmapDetailed(binding, mainChannel, cancellation, progress, companionChannels)
        (preMmap.verification as? ModelVerification.Refusal)?.let { return LoadVerification.Refused(it) }

        // Gate 2's expectation: the BLAKE3 the binding declares (an import
        // recorded it from the bytes it copied), else the same SHA-256 gate 1
        // just checked — re-hashed over the MAPPING, which is what detects a
        // write between gate 1 and the map (§2.2/§2.4). Until skein-gg11.17
        // lands a native BLAKE3, the second algorithm is a luxury this device
        // cannot afford: the pure-Kotlin BLAKE3 cost 13 minutes per pass on
        // the Fold, and gate 1 used to observe one only to feed gate 2 here.
        // A row without a declared BLAKE3 therefore gets the same
        // hash-map-rehash discipline with one algorithm, and every row skips
        // the observation pass. See VerifyBinding.kt's header.
        val expectedBlake3 = binding.main.expectedBlake3

        hook.afterPreMmapVerify(binding)

        val mapped =
            try {
                mainChannel.map(FileChannel.MapMode.READ_ONLY, 0L, mainChannel.size())
            } catch (e: IOException) {
                SkeinLog.w(TAG, "mmap failed", e)
                return LoadVerification.Refused(ModelVerification.IoFailure(ModelFileRole.MAIN, "mmap"))
            }

        hook.afterMap(mapped)

        val postMmap =
            if (expectedBlake3 != null) {
                verifyAfterMmap(expectedBlake3, mapped, cancellation, progress)
            } else {
                verifyAfterMmapSha256(binding.main.expectedSha256, mapped, cancellation, progress)
            }
        if (postMmap is ModelVerification.Refusal) return LoadVerification.Refused(postMmap)

        val gate2 = if (expectedBlake3 != null) "blake3" else "sha256"
        SkeinLog.i(TAG, "model verified: both gates passed files=${binding.files.size} gate2=$gate2")
        return LoadVerification.Ready(mapped)
    }

    /**
     * Gate 2 without a declared BLAKE3: SHA-256 over the mapped region against
     * the expectation gate 1 already checked. Any difference means the bytes
     * changed between the read and the map — [ModelVerification.Tampered].
     */
    fun verifyAfterMmapSha256(
        expectedSha256: String,
        mapped: ByteBuffer,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): ModelVerification =
        when (val outcome = sha256(mapped, cancellation, progress)) {
            DigestOutcome.Cancelled -> refuse(ModelVerification.Cancelled(ModelFileRole.MAIN))
            is DigestOutcome.Digested ->
                if (constantTimeEquals(expectedSha256, outcome.hex)) {
                    ModelVerification.Verified
                } else {
                    refuse(ModelVerification.Tampered(ModelFileRole.MAIN))
                }
        }

    /**
     * The fd load discipline end to end: verify the pinned descriptor's own
     * channel, map that channel, re-verify the mapping, and report the
     * `/proc/self/fd/<n>` path the native engine is to be given.
     *
     * On refusal the pin is closed before returning, so a model that failed
     * either gate has no descriptor left to hand to
     * `llama_model_load_from_file` even if the caller ignores the result. On
     * success the pin stays open — the engine's path only resolves while it
     * is, and the caller closes it on unload.
     */
    fun verifyPinned(
        model: PinnedModel,
        binding: VerifyBinding,
        hook: LoadPhaseHook = LoadPhaseHook.None,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): PinnedLoad {
        val companions = model.companions.mapValues { (_, pin) -> pin.channel }
        return when (
            val result =
                verifyForLoad(model.main.channel, binding, hook, cancellation, progress, companions)
        ) {
            is LoadVerification.Ready -> PinnedLoad.Ready(model, result.mapped)
            is LoadVerification.Refused -> {
                model.close()
                PinnedLoad.Refused(result.refusal)
            }
        }
    }

    /** [verifyPinned] for a load whose companions stay in the store rather than arriving as descriptors. */
    fun verifyPinned(
        pinned: PinnedModelFile,
        binding: VerifyBinding,
        hook: LoadPhaseHook = LoadPhaseHook.None,
        cancellation: VerifyCancellation = VerifyCancellation.Never,
        progress: VerifyProgress = VerifyProgress.None,
    ): PinnedLoad = verifyPinned(PinnedModel(pinned), binding, hook, cancellation, progress)

    /** Unwraps an outcome produced with [VerifyCancellation.Never], which cannot be [DigestOutcome.Cancelled]. */
    private fun hexOf(outcome: DigestOutcome): String =
        when (outcome) {
            is DigestOutcome.Digested -> outcome.hex
            DigestOutcome.Cancelled -> error("an uncancellable digest reported cancellation")
        }

    /** Logs the refusal's log-safe summary — never a path, never a digest — and returns it. */
    private fun refuse(refusal: ModelVerification.Refusal): ModelVerification.Refusal {
        SkeinLog.w(TAG, "model verification refused: ${refusal.summary}")
        return refusal
    }

    private fun refused(refusal: ModelVerification.Refusal): PreMmapVerification =
        PreMmapVerification(refuse(refusal), mainBlake3 = null)
}
