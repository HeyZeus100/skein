// skein-nxk (E4.I3): `TransportRules` — POST_REVIEW_RESOLUTIONS.md §3.2's
// numbers, made executable. §5 item 12 assigns them to E4.I3/E4.I4; the
// coordinator decision on skein-hiwb places the type in `:core:ipc` (it
// references the wire types, and `:core:ipc` is already on both services'
// isolation allowlists — `:core:inference`, §3.3's illustrative placement,
// is not reachable from an isolated service at all).
//
// The last two tests are skein-0rkg: the review's measurement that a NORMAL
// prompt does not fit the inline budget, which is why `ChatMessageParcel`
// gained `contentFd` rather than the spill being left to convention.

package app.skein.ipc

import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransportRulesTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    // ------------------------------------------------------- the §3.2 numbers

    @Test
    fun theInlineBudgetIsThirtyTwoKibibytes() {
        assertThat(TransportRules.INLINE_BUDGET_BYTES).isEqualTo(32 * 1024)
    }

    @Test
    fun theRefusalThresholdIsOneHundredAndTwentyEightKibibytes() {
        assertThat(TransportRules.INLINE_REFUSE_BYTES).isEqualTo(128 * 1024)
    }

    @Test
    fun atMostEightTokenBatchesMayBeInFlight() {
        assertThat(TransportRules.MAX_INFLIGHT_TOKEN_BATCHES).isEqualTo(8)
    }

    @Test
    fun tokenBatchesFlushEveryTwentyMilliseconds() {
        assertThat(TransportRules.TOKEN_BATCH_INTERVAL_MS).isEqualTo(20L)
    }

    @Test
    fun tokenBatchesFlushEverySixteenTokens() {
        assertThat(TransportRules.TOKEN_BATCH_MAX_TOKENS).isEqualTo(16)
    }

    @Test
    fun aTokenBatchStaysUnderSixteenKibibytes() {
        assertThat(TransportRules.TOKEN_BATCH_MAX_BYTES).isEqualTo(16 * 1024)
    }

    @Test
    fun atMostThirtyTwoTextsTravelPerEmbedCall() {
        assertThat(TransportRules.MAX_EMBED_TEXTS).isEqualTo(32)
    }

    // -------------------------------------------------------------- decisions

    @Test
    fun aPayloadInsideTheBudgetTravelsInline() {
        assertThat(TransportRules.mustSpill(TransportRules.INLINE_BUDGET_BYTES)).isFalse()
    }

    @Test
    fun aPayloadOverTheBudgetMustSpill() {
        assertThat(TransportRules.mustSpill(TransportRules.INLINE_BUDGET_BYTES + 1)).isTrue()
    }

    @Test
    fun aPayloadAtTheRefusalThresholdIsStillExpressible() {
        assertThat(TransportRules.refuses(TransportRules.INLINE_REFUSE_BYTES)).isFalse()
    }

    @Test
    fun aPayloadOverTheRefusalThresholdIsRefused() {
        assertThat(TransportRules.refuses(TransportRules.INLINE_REFUSE_BYTES + 1)).isTrue()
    }

    @Test
    fun requireWithinBudgetThrowsOverTheRefusalThreshold() {
        val thrown =
            runCatching {
                TransportRules.requireWithinBudget(TransportRules.INLINE_REFUSE_BYTES + 1, "messages")
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun requireWithinBudgetNamesThePayloadButNeverItsContent() {
        val thrown =
            runCatching {
                TransportRules.requireWithinBudget(TransportRules.INLINE_REFUSE_BYTES + 1, "messages")
            }.exceptionOrNull()

        assertThat(thrown!!.message).contains("messages")
    }

    @Test
    fun requireWithinBudgetAcceptsAnInlinePayload() {
        TransportRules.requireWithinBudget(TransportRules.INLINE_BUDGET_BYTES, "messages")
    }

    @Test
    fun droppedBatchesAreCountedAgainstTheInFlightCeiling() {
        assertThat(TransportRules.shouldDropOldestBatch(inFlight = TransportRules.MAX_INFLIGHT_TOKEN_BATCHES))
            .isTrue()
    }

    @Test
    fun aQueueUnderTheCeilingKeepsEveryBatch() {
        assertThat(TransportRules.shouldDropOldestBatch(inFlight = TransportRules.MAX_INFLIGHT_TOKEN_BATCHES - 1))
            .isFalse()
    }

    // ---------------------------------------------------------- measurement

    @Test
    fun marshalledSizeMeasuresARealParcel() {
        val message = ChatMessageParcel(role = "user", content = "hello")

        assertThat(TransportRules.marshalledSize(message)).isGreaterThan(0)
    }

    // ------------------------------------------------------------ skein-0rkg

    @Test
    fun aFullBudgetPromptDoesNotFitTheInlineBudget() {
        // TokenBudget: contextLength 16 384 minus 1 024 reserved ~= 15K prompt
        // tokens, ~4 chars/token = ~60 KB of text; Parcel strings are UTF-16,
        // so it marshals to ~120 KB. This is the review's finding: the spill
        // path is not an edge case, it is the normal case.
        val request = fullBudgetRequest()

        assertThat(TransportRules.mustSpill(TransportRules.marshalledSize(request))).isTrue()
    }

    @Test
    fun aSpilledMessageKeepsItsRole() {
        val spilled =
            ChatMessageParcel(
                role = "user",
                content = "",
                contentFd = sharedMemRef(role = "message"),
            )

        val restored = roundTrip(spilled)

        assertThat(restored.role).isEqualTo("user")
    }

    @Test
    fun aSpilledMessageCarriesItsDescriptor() {
        val spilled =
            ChatMessageParcel(role = "user", content = "", contentFd = sharedMemRef(role = "message"))

        assertThat(roundTrip(spilled).contentFd).isNotNull()
    }

    @Test
    fun aSpilledMessageSurvivesInsideAGenerateRequest() {
        val request =
            GenerateRequest(
                requestId = 7,
                messages =
                    listOf(
                        ChatMessageParcel(role = "system", content = "be terse"),
                        ChatMessageParcel(role = "user", content = "", contentFd = sharedMemRef("message")),
                    ),
                attachmentFds = emptyList(),
                sampling = sampling(),
                sessionEpoch = 3L,
            )

        val restored = roundTrip(request)

        assertThat(restored.messages.map { it.role }).containsExactly("system", "user").inOrder()
    }

    @Test
    fun aSpilledMessageKeepsItsPositionSoOrderIsTheIndex() {
        val request =
            GenerateRequest(
                requestId = 7,
                messages =
                    listOf(
                        ChatMessageParcel(role = "user", content = "", contentFd = sharedMemRef("message")),
                        ChatMessageParcel(role = "assistant", content = "sure"),
                    ),
                attachmentFds = emptyList(),
                sampling = sampling(),
                sessionEpoch = 3L,
            )

        val restored = roundTrip(request)

        assertThat(restored.messages[0].contentFd).isNotNull()
        assertThat(restored.messages[1].contentFd).isNull()
    }

    @Test
    fun anInlineMessageHasNoDescriptor() {
        assertThat(ChatMessageParcel(role = "user", content = "hello").contentFd).isNull()
    }

    // ------------------------------------------------------------- fixtures

    private fun sampling() =
        SamplingParcel(
            temperature = 0.7f,
            topK = 40,
            topP = 0.95f,
            minP = 0.05f,
            repeatPenalty = 1.1f,
            maxTokens = 512,
            seed = -1L,
            stop = listOf("</s>"),
        )

    private fun fullBudgetRequest(): GenerateRequest =
        GenerateRequest(
            requestId = 1,
            messages = listOf(ChatMessageParcel(role = "user", content = "lorem ipsum ".repeat(5_000))),
            attachmentFds = emptyList(),
            sampling = sampling(),
            sessionEpoch = 1L,
        )

    private fun sharedMemRef(role: String): SharedMemRef =
        SharedMemRef(
            fd = openDescriptor(),
            sizeBytes = 64L,
            mimeHint = "text/plain; charset=utf-8",
            role = role,
        )

    private fun openDescriptor(): ParcelFileDescriptor {
        val file = temporaryFolder.newFile()
        file.writeBytes(ByteArray(64))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private inline fun <reified T : android.os.Parcelable> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readParcelable(T::class.java.classLoader, T::class.java))
        } finally {
            parcel.recycle()
        }
    }
}
