// skein-nxk (E4.I3): the two failure vocabularies this service translates into
// `ErrorCode`.
//
// WHY HERE. `:core:verify` deliberately has no `:core:ipc` edge — a verifier
// that knew about Binder error codes could not be used from the import path,
// which has no Binder — and `:core:ipc` has no verifier edge. The service is
// the only module that sees both, which is why skein-v2s's closing note
// assigned this table to this bead.
//
// THE DISTINCTIONS ARE THE POINT. Collapsing these into one failure would throw
// away exactly the forensics POST_REVIEW_RESOLUTIONS.md §2 exists to produce:
// "the download is corrupt" and "something rewrote the file while we were
// mapping it" have different remedies, and a user who backgrounded the app
// during a ten-second verify must not be told their model was tampered with.

package app.skein.inference.service

import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.ipc.ErrorCode

/** `ModelVerification.Refusal` / `LlamaException` -> `ErrorCode`. */
object ServiceErrorMapping {
    /**
     * The wire code for a verification refusal.
     *
     * Role matters for the two hash outcomes: the contract has a separate
     * [ErrorCode.COMPANION_HASH_MISMATCH] so a client can say "the tokenizer
     * that came with this model is wrong" rather than condemning the weights.
     */
    fun toErrorCode(refusal: ModelVerification.Refusal): Int =
        when (refusal) {
            is ModelVerification.HashMismatch ->
                if (refusal.role == ModelFileRole.MAIN) {
                    ErrorCode.HASH_MISMATCH
                } else {
                    ErrorCode.COMPANION_HASH_MISMATCH
                }

            is ModelVerification.Tampered ->
                if (refusal.role == ModelFileRole.MAIN) {
                    // The bytes changed BETWEEN the gates; the same file passed
                    // SHA-256 moments earlier. That is an active TOCTOU attempt,
                    // not a stale download, and the contract gives it its own code.
                    ErrorCode.HASH_MISMATCH_POST_MMAP
                } else {
                    ErrorCode.COMPANION_HASH_MISMATCH
                }

            // Not a hash failure in either direction: a cancelled pass proves
            // nothing about the bytes. Reporting "tampered" because the user
            // backgrounded the app would be a false alarm the threat model
            // would then have to answer for.
            is ModelVerification.Cancelled -> ErrorCode.CANCELLED

            // Somebody else holds the file, or the id is already taken. Both are
            // "wait and retry", not "this model is bad".
            is ModelVerification.InUse, is ModelVerification.AlreadyImported -> ErrorCode.MODEL_IN_USE

            // The binding does not describe a loadable model: a file the
            // manifest does not cover, a manifest entry with no file, a length
            // that disagrees, a manifest that does not parse. The client's
            // remedy is the same in every case — re-import.
            is ModelVerification.CompanionMissing,
            is ModelVerification.FileMissing,
            is ModelVerification.SizeMismatch,
            is ModelVerification.UncoveredFile,
            is ModelVerification.MalformedManifest,
            -> ErrorCode.INVALID_MODEL

            is ModelVerification.IoFailure -> ErrorCode.INTERNAL
        }

    /** The wire code for a native failure. */
    fun toErrorCode(exception: LlamaException): Int =
        when (exception.code) {
            LlamaErrorCode.INVALID_MODEL,
            LlamaErrorCode.TOKENIZE_FAILED,
            LlamaErrorCode.TEMPLATE_UNSUPPORTED,
            LlamaErrorCode.EMBEDDINGS_UNAVAILABLE,
            -> ErrorCode.INVALID_MODEL

            // CONTEXT_FULL reaching this table means it happened during LOAD —
            // the KV cache the requested contextLength needs does not fit. In
            // generation it is a LENGTH stop, which the worker handles before
            // any error code is produced.
            LlamaErrorCode.OUT_OF_MEMORY, LlamaErrorCode.CONTEXT_FULL -> ErrorCode.OOM

            LlamaErrorCode.CANCELLED -> ErrorCode.CANCELLED

            LlamaErrorCode.DECODE_FAILED,
            LlamaErrorCode.INVALID_ARGUMENT,
            LlamaErrorCode.UNKNOWN,
            -> ErrorCode.INTERNAL
        }

    /**
     * The diagnostic that travels with a refusal.
     *
     * Always the refusal's own [ModelVerification.Refusal.summary], which names
     * roles and algorithms only — never a path, never the expected digest,
     * never the observed one (spec §9). The typed fields carry those for the
     * caller that legitimately needs them.
     */
    fun diagnostic(refusal: ModelVerification.Refusal): String = refusal.summary

    /**
     * The diagnostic that travels with a native failure.
     *
     * `LlamaException`'s text is built by `skein_jni.cpp` from fixed strings
     * and numbers only — never from caller-supplied text, a token piece, or
     * llama.cpp's own error strings, which can quote input — so it is safe to
     * forward verbatim.
     */
    fun diagnostic(exception: LlamaException): String = exception.message ?: "native failure"

    /**
     * `skein-gg11.2` (OL-19, `JNI_ANALYSIS.md` §5): [diagnostic] plus up to
     * [MAX_ATTACHED_LOAD_LOG_LINES] of the WARN/ERROR lines llama.cpp logged
     * during the failed load/inspect — llama.cpp's own errors cascade
     * specific ("missing tensor blk.0.attn_q.weight") to generic ("model
     * load failed"), and the earliest lines are the informative ones.
     *
     * [capturedLines] is UNTRUSTED — it is [LlamaBackend.drainLoadLogLines]'s
     * RAW output, sourced from the file that just failed to load — so every
     * line is run through [LlamaLogRedactor.redact] here, before it is
     * anywhere near this string, exactly as spec §9's no-raw-content rule
     * requires of everything else this process logs (the app side sanitizes
     * a second time regardless — `skein-3yal`). An empty [capturedLines] (a
     * successful load, or a failure that logged nothing) leaves the base
     * [diagnostic] unchanged — this must never be called on a success path.
     */
    fun loadFailureDetail(
        exception: LlamaException,
        capturedLines: List<String>,
    ): String {
        val base = diagnostic(exception)
        if (capturedLines.isEmpty()) return base
        val redacted = capturedLines.take(MAX_ATTACHED_LOAD_LOG_LINES).map { LlamaLogRedactor.redact(it) }
        return (listOf(base) + redacted).joinToString(" | ")
    }

    /**
     * Mirrors `LlamaNative.MAX_CAPTURED_LOAD_LOG_LINES`; defensive here too,
     * since a fake backend is not bound by that native cap.
     */
    private const val MAX_ATTACHED_LOAD_LOG_LINES = 4
}
