// skein-k7e9 — `ErrorMappingTest`, required by
// `docs/design/POST_REVIEW_RESOLUTIONS.md` §3.4: "every new `ErrorCode` maps to
// a distinct `InferenceException` subclass".
//
// The test is written against reflection over [ErrorCode]'s constants rather
// than a hand-listed set, so a thirteenth code added later fails this test
// until [ErrorCodes.toException] and the table below both learn about it.
//
// Plain JUnit + Truth: neither [ErrorCodes] nor `InferenceException` touches an
// `android.os` type, so this needs no Robolectric runner (unlike
// `ParcelRoundTripTest`, which does).

package app.skein.ipc

import app.skein.core.model.InferenceException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

class ErrorMappingTest {
    /**
     * The whole contract in one table: every `ErrorCode` constant, the
     * `InferenceException` subclass it maps to (or `null` for the two codes
     * that are documented NOT to be engine faults), and the exact message the
     * mapping produces when the service supplies no diagnostic detail.
     */
    private data class Expectation(
        val type: Class<out InferenceException>?,
        val messageWithoutDetail: String?,
    )

    private val expectations: Map<Int, Expectation> =
        mapOf(
            ErrorCode.OK to Expectation(null, null),
            ErrorCode.CANCELLED to Expectation(null, null),
            ErrorCode.HASH_MISMATCH to
                Expectation(InferenceException.HashMismatch::class.java, "sha256 mismatch"),
            ErrorCode.INVALID_MODEL to
                Expectation(InferenceException.InvalidModel::class.java, "invalid model: "),
            ErrorCode.OOM to
                Expectation(InferenceException.OutOfMemory::class.java, "out of memory"),
            ErrorCode.NOT_LOADED to
                Expectation(InferenceException.ModelNotLoaded::class.java, "no model loaded"),
            ErrorCode.BUSY to
                Expectation(InferenceException.Busy::class.java, "a generation is already running"),
            ErrorCode.HASH_MISMATCH_POST_MMAP to
                Expectation(InferenceException.PostMmapHashMismatch::class.java, "sha256 mismatch after mmap"),
            ErrorCode.TX_TOO_LARGE to
                Expectation(
                    InferenceException.TransactionTooLarge::class.java,
                    "transaction exceeds the inline budget",
                ),
            ErrorCode.MODEL_IN_USE to
                Expectation(InferenceException.ModelInUse::class.java, "model file is in use"),
            ErrorCode.COMPANION_HASH_MISMATCH to
                Expectation(InferenceException.CompanionHashMismatch::class.java, "companion sha256 mismatch"),
            ErrorCode.SESSION_LOCKED to
                Expectation(InferenceException.SessionLocked::class.java, "session locked"),
            ErrorCode.INTERNAL to
                Expectation(InferenceException.Internal::class.java, "internal service failure"),
        )

    /** Every `const val Int` declared on the [ErrorCode] object, by reflection. */
    private fun declaredErrorCodes(): Map<String, Int> =
        ErrorCode::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    // ------------------------------------------------------------------
    // Totality: the table and the mapping both cover every declared code.
    // ------------------------------------------------------------------

    @Test
    fun everyDeclaredErrorCodeIsCoveredByTheExpectationTable() {
        val declared = declaredErrorCodes().values.toSet()

        val uncovered = declared - expectations.keys

        assertThat(uncovered).isEmpty()
    }

    @Test
    fun everyDeclaredErrorCodeMapsToItsTabledOutcome() {
        val declared = declaredErrorCodes()

        val mismatches =
            declared.filterNot { (_, code) ->
                ErrorCodes.toException(code, "")?.javaClass == expectations.getValue(code).type
            }

        assertThat(mismatches).isEmpty()
    }

    // ------------------------------------------------------------------
    // POST_REVIEW_RESOLUTIONS.md §3.4 — DISTINCT subclass per failing code.
    // ------------------------------------------------------------------

    @Test
    fun everyFailingErrorCodeMapsToADistinctInferenceExceptionSubclass() {
        val failingCodes = declaredErrorCodes().values.filter { ErrorCodes.toException(it, "") != null }

        val distinctTypes = failingCodes.mapNotNull { ErrorCodes.toException(it, "")?.javaClass }.toSet()

        assertThat(distinctTypes).hasSize(failingCodes.size)
    }

    // (That every mapped value IS an `InferenceException` needs no test: it is
    // `toException`'s return type, and the compiler rejects anything else.)

    // ------------------------------------------------------------------
    // The two documented NON-exception outcomes.
    // ------------------------------------------------------------------

    @Test
    fun okMapsToNoException() {
        val mapped = ErrorCodes.toException(ErrorCode.OK, "")

        assertThat(mapped).isNull()
    }

    @Test
    fun cancelledMapsToNoException() {
        val mapped = ErrorCodes.toException(ErrorCode.CANCELLED, "cancelled by client")

        assertThat(mapped).isNull()
    }

    // ------------------------------------------------------------------
    // Per-code assertions for the six subclasses this bead adds.
    // ------------------------------------------------------------------

    @Test
    fun hashMismatchPostMmapMapsToPostMmapHashMismatch() {
        val mapped = ErrorCodes.toException(ErrorCode.HASH_MISMATCH_POST_MMAP, "main")

        assertThat(mapped).isInstanceOf(InferenceException.PostMmapHashMismatch::class.java)
    }

    @Test
    fun txTooLargeMapsToTransactionTooLarge() {
        val mapped = ErrorCodes.toException(ErrorCode.TX_TOO_LARGE, "196608 bytes")

        assertThat(mapped).isInstanceOf(InferenceException.TransactionTooLarge::class.java)
    }

    @Test
    fun modelInUseMapsToModelInUse() {
        val mapped = ErrorCodes.toException(ErrorCode.MODEL_IN_USE, "tryLock returned null")

        assertThat(mapped).isInstanceOf(InferenceException.ModelInUse::class.java)
    }

    @Test
    fun companionHashMismatchMapsToCompanionHashMismatch() {
        val mapped = ErrorCodes.toException(ErrorCode.COMPANION_HASH_MISMATCH, "tokenizer")

        assertThat(mapped).isInstanceOf(InferenceException.CompanionHashMismatch::class.java)
    }

    @Test
    fun sessionLockedMapsToSessionLocked() {
        val mapped = ErrorCodes.toException(ErrorCode.SESSION_LOCKED, "epoch 7")

        assertThat(mapped).isInstanceOf(InferenceException.SessionLocked::class.java)
    }

    /**
     * `LOCK_POLICY_INDEXING.md` §5.3 and plan `E4.I4`: the service is alive and
     * refusing, not dead. A client that cannot tell `SessionLocked` from
     * `ServiceDied` would rebind and retry instead of prompting for unlock.
     */
    @Test
    fun sessionLockedIsNotServiceDied() {
        val mapped = ErrorCodes.toException(ErrorCode.SESSION_LOCKED, "epoch 7")

        assertThat(mapped).isNotInstanceOf(InferenceException.ServiceDied::class.java)
    }

    @Test
    fun internalMapsToInternal() {
        val mapped = ErrorCodes.toException(ErrorCode.INTERNAL, "unclassified")

        assertThat(mapped).isInstanceOf(InferenceException.Internal::class.java)
    }

    @Test
    fun anUnrecognisedCodeMapsToInternal() {
        val mapped = ErrorCodes.toException(4242, "from a newer service")

        assertThat(mapped).isInstanceOf(InferenceException.Internal::class.java)
    }

    // ------------------------------------------------------------------
    // The locked six still map to exactly what they always did.
    // ------------------------------------------------------------------

    @Test
    fun theLockedSixKeepTheirCodes() {
        val mapped =
            listOf(
                ErrorCode.HASH_MISMATCH,
                ErrorCode.INVALID_MODEL,
                ErrorCode.OOM,
                ErrorCode.NOT_LOADED,
                ErrorCode.BUSY,
            ).map { ErrorCodes.toException(it, "")?.javaClass }

        assertThat(mapped)
            .containsExactly(
                InferenceException.HashMismatch::class.java,
                InferenceException.InvalidModel::class.java,
                InferenceException.OutOfMemory::class.java,
                InferenceException.ModelNotLoaded::class.java,
                InferenceException.Busy::class.java,
            ).inOrder()
    }

    // ------------------------------------------------------------------
    // Message hygiene (spec non-negotiable: never surface user content).
    //
    // `toException` takes an Int and the SERVICE's diagnostic string — it is
    // handed no request object, so it structurally cannot embed a prompt, a
    // document body or an attachment. These two tests pin that down: with no
    // detail the message is a fixed, documented constant, and with a detail
    // the message is exactly that constant plus the service's own text.
    // ------------------------------------------------------------------

    @Test
    fun messagesWithoutDetailAreFixedDocumentedText() {
        val declared = declaredErrorCodes().values

        val actual = declared.associateWith { ErrorCodes.toException(it, "")?.message }

        assertThat(actual).containsExactlyEntriesIn(
            declared.associateWith { expectations.getValue(it).messageWithoutDetail },
        )
    }

    @Test
    fun aServiceDetailIsAppendedVerbatimAndNothingElseIs() {
        val detail = "role=tokenizer"
        val declared = declaredErrorCodes().values.filter { ErrorCodes.toException(it, "") != null }

        val leaked =
            declared.filterNot { code ->
                val base = expectations.getValue(code).messageWithoutDetail
                val withDetail = ErrorCodes.toException(code, detail)?.message
                withDetail == base || withDetail == "$base: $detail" || withDetail == "$base$detail"
            }

        assertThat(leaked).isEmpty()
    }

    /**
     * A prompt never reaches the mapping, so it can never reach the message.
     * Belt-and-braces against a future refactor that widens the signature.
     */
    @Test
    fun aPromptShapedStringIsNeverInventedByTheMapping() {
        val declared = declaredErrorCodes().values

        val messages = declared.mapNotNull { ErrorCodes.toException(it, "")?.message }

        assertThat(messages.none { it.contains("user:") || it.contains("assistant:") }).isTrue()
    }
}
