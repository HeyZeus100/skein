// skein-nxk (E4.I3): the `ModelVerification.Refusal -> ErrorCode` and
// `LlamaException -> ErrorCode` tables.
//
// This mapping is the service's, deliberately: `:core:verify` has no
// `:core:ipc` edge (a verifier that knew about Binder error codes would be
// unusable from the import path), and `:core:ipc` has no verifier edge. The
// service is the only module that sees both, which is why skein-v2s's closing
// note assigned the table here.
//
// The distinctions this table preserves are the point of the refusal
// vocabulary: "the file was already wrong" (HASH_MISMATCH) and "the file
// changed under us" (HASH_MISMATCH_POST_MMAP) have different remedies, and a
// user who cancelled a 10-second verify must not be told their model is
// tampered.

package app.skein.inference.service

import app.skein.core.verify.DigestAlgorithm
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.ipc.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceErrorMappingTest {
    // ------------------------------------------------ verification refusals

    @Test
    fun `a main-file sha256 mismatch maps to HASH_MISMATCH`() {
        assertEquals(
            ErrorCode.HASH_MISMATCH,
            ServiceErrorMapping.toErrorCode(
                ModelVerification.HashMismatch(ModelFileRole.MAIN, DigestAlgorithm.SHA256),
            ),
        )
    }

    @Test
    fun `a companion sha256 mismatch maps to COMPANION_HASH_MISMATCH`() {
        assertEquals(
            ErrorCode.COMPANION_HASH_MISMATCH,
            ServiceErrorMapping.toErrorCode(
                ModelVerification.HashMismatch(ModelFileRole.TOKENIZER, DigestAlgorithm.SHA256),
            ),
        )
    }

    @Test
    fun `a main-file post-mmap mismatch maps to HASH_MISMATCH_POST_MMAP`() {
        assertEquals(
            ErrorCode.HASH_MISMATCH_POST_MMAP,
            ServiceErrorMapping.toErrorCode(ModelVerification.Tampered(ModelFileRole.MAIN)),
        )
    }

    @Test
    fun `a companion post-mmap mismatch maps to COMPANION_HASH_MISMATCH`() {
        assertEquals(
            ErrorCode.COMPANION_HASH_MISMATCH,
            ServiceErrorMapping.toErrorCode(ModelVerification.Tampered(ModelFileRole.MMPROJ)),
        )
    }

    @Test
    fun `InUse maps to MODEL_IN_USE`() {
        assertEquals(
            ErrorCode.MODEL_IN_USE,
            ServiceErrorMapping.toErrorCode(ModelVerification.InUse("qwen-2-5-3b")),
        )
    }

    @Test
    fun `Cancelled maps to CANCELLED`() {
        assertEquals(
            ErrorCode.CANCELLED,
            ServiceErrorMapping.toErrorCode(ModelVerification.Cancelled(ModelFileRole.MAIN)),
        )
    }

    @Test
    fun `Cancelled with no role still maps to CANCELLED`() {
        assertEquals(ErrorCode.CANCELLED, ServiceErrorMapping.toErrorCode(ModelVerification.Cancelled(null)))
    }

    @Test
    fun `a missing companion maps to INVALID_MODEL`() {
        assertEquals(
            ErrorCode.INVALID_MODEL,
            ServiceErrorMapping.toErrorCode(ModelVerification.CompanionMissing(ModelFileRole.TOKENIZER, "t.json")),
        )
    }

    @Test
    fun `a missing file maps to INVALID_MODEL`() {
        assertEquals(
            ErrorCode.INVALID_MODEL,
            ServiceErrorMapping.toErrorCode(ModelVerification.FileMissing(ModelFileRole.MAIN)),
        )
    }

    @Test
    fun `a size mismatch maps to INVALID_MODEL`() {
        assertEquals(
            ErrorCode.INVALID_MODEL,
            ServiceErrorMapping.toErrorCode(ModelVerification.SizeMismatch(ModelFileRole.MAIN)),
        )
    }

    @Test
    fun `an uncovered file maps to INVALID_MODEL`() {
        assertEquals(
            ErrorCode.INVALID_MODEL,
            ServiceErrorMapping.toErrorCode(ModelVerification.UncoveredFile("stray.json")),
        )
    }

    @Test
    fun `a malformed manifest maps to INVALID_MODEL`() {
        assertEquals(
            ErrorCode.INVALID_MODEL,
            ServiceErrorMapping.toErrorCode(ModelVerification.MalformedManifest("bad version")),
        )
    }

    @Test
    fun `an already-imported refusal maps to MODEL_IN_USE`() {
        assertEquals(
            ErrorCode.MODEL_IN_USE,
            ServiceErrorMapping.toErrorCode(ModelVerification.AlreadyImported("qwen")),
        )
    }

    @Test
    fun `an io failure maps to INTERNAL`() {
        assertEquals(
            ErrorCode.INTERNAL,
            ServiceErrorMapping.toErrorCode(ModelVerification.IoFailure(ModelFileRole.MAIN, "read")),
        )
    }

    // ------------------------------------------------------ native failures

    @Test
    fun `an invalid model maps to INVALID_MODEL`() {
        assertEquals(ErrorCode.INVALID_MODEL, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.INVALID_MODEL)))
    }

    @Test
    fun `out of memory maps to OOM`() {
        assertEquals(ErrorCode.OOM, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.OUT_OF_MEMORY)))
    }

    @Test
    fun `a native cancellation maps to CANCELLED`() {
        assertEquals(ErrorCode.CANCELLED, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.CANCELLED)))
    }

    @Test
    fun `a full context maps to OOM`() {
        // CONTEXT_FULL during a LOAD is an allocation the device cannot honour.
        // During generation it is a LENGTH stop, which the worker handles before
        // this table is consulted.
        assertEquals(ErrorCode.OOM, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.CONTEXT_FULL)))
    }

    @Test
    fun `a decode failure maps to INTERNAL`() {
        assertEquals(ErrorCode.INTERNAL, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.DECODE_FAILED)))
    }

    @Test
    fun `a tokenize failure maps to INVALID_MODEL`() {
        assertEquals(ErrorCode.INVALID_MODEL, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.TOKENIZE_FAILED)))
    }

    @Test
    fun `an unsupported template maps to INVALID_MODEL`() {
        assertEquals(
            ErrorCode.INVALID_MODEL,
            ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.TEMPLATE_UNSUPPORTED)),
        )
    }

    @Test
    fun `unavailable embeddings map to INVALID_MODEL`() {
        assertEquals(
            ErrorCode.INVALID_MODEL,
            ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.EMBEDDINGS_UNAVAILABLE)),
        )
    }

    @Test
    fun `an invalid argument maps to INTERNAL`() {
        assertEquals(ErrorCode.INTERNAL, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.INVALID_ARGUMENT)))
    }

    @Test
    fun `an unknown native code maps to INTERNAL`() {
        assertEquals(ErrorCode.INTERNAL, ServiceErrorMapping.toErrorCode(llama(LlamaErrorCode.UNKNOWN)))
    }

    @Test
    fun `every native error code is mapped`() {
        for (code in LlamaErrorCode.entries) {
            ServiceErrorMapping.toErrorCode(llama(code))
        }
    }

    // ------------------------------------------------------------- messages

    @Test
    fun `the message for a refusal is the log-safe summary`() {
        val refusal = ModelVerification.HashMismatch(ModelFileRole.TOKENIZER, DigestAlgorithm.SHA256)

        assertEquals(refusal.summary, ServiceErrorMapping.diagnostic(refusal))
    }

    @Test
    fun `the message for a refusal never contains a digest`() {
        val message = ServiceErrorMapping.diagnostic(ModelVerification.Tampered(ModelFileRole.MAIN))

        assertEquals(false, message.contains(Regex("[0-9a-f]{32}")))
    }

    private fun llama(code: LlamaErrorCode) = LlamaException(code, "native failure")
}
