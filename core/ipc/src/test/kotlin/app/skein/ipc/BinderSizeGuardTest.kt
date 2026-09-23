// E0.I16 (bd `skein-mfw`), plan §4.7 step 3: the Binder size guard that
// settles how images and other large payloads travel.
//
// Android's Binder driver gives each process a ~1 MiB transaction buffer,
// shared across every in-flight transaction
// (`docs/design/POST_REVIEW_RESOLUTIONS.md` §3.1), so "each request is
// individually legal" is not enough. The bead's guard was: a
// `GenerateRequest` carrying 16K tokens of text plus one 4 MiB image must be
// under 1 MiB *after excluding the image*.
//
// These tests measure real `Parcel.dataSize()` values and pin the decision
// recorded in `Parcels.kt`'s header:
//
//  - the text and metadata of a full-context request are far under 1 MiB, so
//    the "after excluding the image" half of the guard passes;
//  - a 4 MiB image marshalled inline — plan §4.7 v1's
//    `images: List<ByteArray>` — blows the whole buffer on its own, so the
//    guard's "if it fails, images travel as a shared-memory fd" branch is the
//    one that applies. v2 therefore has no inline attachment field at all;
//  - the same 4 MiB image carried as a `SharedMemRef` adds well under 1 KiB
//    to the transaction, which is what makes the aggregate budget work.

package app.skein.ipc

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BinderSizeGuardTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun generateRequestWithSixteenThousandTokensOfTextFitsTheBinderBuffer() {
        val request = generateRequest(text = SIXTEEN_K_TOKENS_OF_TEXT, attachments = emptyList())

        val parcelBytes = parcelSize(request)

        assertThat(parcelBytes).isLessThan(BINDER_TRANSACTION_LIMIT_BYTES)
    }

    @Test
    fun aFourMebibyteImageMarshalledInlineExceedsTheBinderBuffer() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeByteArray(ByteArray(FOUR_MEBIBYTES))

            assertThat(parcel.dataSize()).isGreaterThan(BINDER_TRANSACTION_LIMIT_BYTES)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun aFourMebibyteImageCarriedAsASharedMemRefCostsUnderOneKibibyte() {
        val withoutImage = generateRequest(text = SHORT_TEXT, attachments = emptyList())
        val withImage = generateRequest(text = SHORT_TEXT, attachments = listOf(fourMebibyteImageRef()))

        val attachmentCost = parcelSize(withImage) - parcelSize(withoutImage)

        assertThat(attachmentCost).isLessThan(1024)
    }

    @Test
    fun aGenerateRequestCarryingAFourMebibyteImageStaysInsideTheInlineBudget() {
        val request = generateRequest(text = SHORT_TEXT, attachments = listOf(fourMebibyteImageRef()))

        val parcelBytes = parcelSize(request)

        assertThat(parcelBytes).isLessThan(INLINE_HARD_LIMIT_BYTES)
    }

    @Test
    fun anEmbedResultForTheThirtyTwoTextMaximumStaysInsideTheInlineBudget() {
        val result = EmbedResult(flat = ByteArray(MAX_TEXTS_PER_EMBED * EMBED_DIMENSIONS), droppedInputs = 0)

        val parcelBytes = parcelSize(result)

        assertThat(parcelBytes).isLessThan(INLINE_HARD_LIMIT_BYTES)
    }

    // H1 (skein-91yy). SKEIN_HUB.md §3.3: "Total inline payload is a few
    // hundred bytes — two orders of magnitude inside
    // TransportRules.INLINE_BUDGET_BYTES (32 KiB)." `inspect` is a synchronous
    // call whose ARGUMENT is a handful of descriptors and whose RESPONSE is
    // this, so both halves have to stay small for the aggregate budget the
    // §3.2 rules are about; measuring against 1 KiB rather than 32 KiB is what
    // makes this a regression test for a field added carelessly later.
    @Test
    fun aModelInspectionStaysTwoOrdersOfMagnitudeInsideTheInlineBudget() {
        val parcelBytes = parcelSize(modelInspection())

        assertThat(parcelBytes).isLessThan(ONE_KIBIBYTE)
    }

    @Test
    fun aModelInspectionWithHostileLengthMetadataStillFitsTheInlineBudget() {
        // Every string field comes out of the GGUF, which is attacker-
        // controlled: llama.cpp will hand back a 4 KiB `general.architecture`
        // if the file says so. Four such fields must still not approach the
        // budget, or `inspect` becomes a way to pressure the shared buffer.
        val hostile =
            modelInspection().copy(
                architecture = "a".repeat(FOUR_KIBIBYTES),
                quantization = "q".repeat(FOUR_KIBIBYTES),
                tokenizerModel = "t".repeat(FOUR_KIBIBYTES),
            )

        assertThat(parcelSize(hostile)).isLessThan(INLINE_HARD_LIMIT_BYTES)
    }

    @Test
    fun anInspectRequestForAModelAndFourCompanionsStaysInsideTheInlineBudget() {
        val request = InspectRequest(binding = binding(companions = 4), sessionEpoch = 1L)

        assertThat(parcelSize(request)).isLessThan(INLINE_HARD_LIMIT_BYTES)
    }

    private fun modelInspection(): ModelInspection =
        ModelInspection(
            errorCode = ErrorCode.OK,
            architecture = "gemma3",
            quantization = "Q4_K_M",
            parameterCount = 4_300_000_000L,
            contextLength = 131_072,
            embeddingWidth = 2560,
            hasVision = true,
            hasChatTemplate = true,
            chatTemplateOk = true,
            tokenizerModel = "llama",
        )

    private fun binding(companions: Int): ManifestBinding =
        ManifestBinding(
            manifestId = "gemma-3n-e4b-it",
            manifestVersion = 2,
            files =
                (listOf("main") + List(companions) { "companion-$it" }).map { role ->
                    val file = temporaryFolder.newFile("$role.bin")
                    file.writeBytes(ByteArray(16))
                    ManifestFileRef(
                        role = role,
                        fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                        expectedSha256 = "ab".repeat(32),
                        expectedSizeBytes = 16L,
                    )
                },
            attestation = null,
        )

    private fun fourMebibyteImageRef(): SharedMemRef {
        val file = temporaryFolder.newFile("image.png")
        file.writeBytes(ByteArray(FOUR_MEBIBYTES))
        return SharedMemRef(
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            sizeBytes = FOUR_MEBIBYTES.toLong(),
            mimeHint = "image/png",
            role = "image",
        )
    }

    private fun generateRequest(
        text: String,
        attachments: List<SharedMemRef>,
    ): GenerateRequest =
        GenerateRequest(
            requestId = 1,
            messages = listOf(ChatMessageParcel(role = "user", content = text)),
            attachmentFds = attachments,
            sampling =
                SamplingParcel(
                    temperature = 0.7f,
                    topK = 40,
                    topP = 0.95f,
                    minP = 0.05f,
                    repeatPenalty = 1.1f,
                    maxTokens = 512,
                    seed = 0L,
                    stop = emptyList(),
                ),
            sessionEpoch = 1L,
        )

    private fun parcelSize(value: Parcelable): Int {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        /** Android's per-process Binder transaction buffer (`TransactionTooLargeException`). */
        const val BINDER_TRANSACTION_LIMIT_BYTES = 1024 * 1024

        /** POST_REVIEW_RESOLUTIONS.md §3.2 rule 1, enforced client-side by `TransportRules` (E4.I3/E4.I4). */
        const val INLINE_HARD_LIMIT_BYTES = 32 * 1024

        const val FOUR_MEBIBYTES = 4 * 1024 * 1024

        /** SKEIN_HUB.md §3.3's "a few hundred bytes", rounded up. */
        const val ONE_KIBIBYTE = 1024

        const val FOUR_KIBIBYTES = 4 * 1024

        const val MAX_TEXTS_PER_EMBED = 32

        /** int8 embedding width, plan §4.6. */
        const val EMBED_DIMENSIONS = 256

        const val SHORT_TEXT = "hello"

        /** ~16K tokens at the conventional four-characters-per-token estimate. */
        val SIXTEEN_K_TOKENS_OF_TEXT = "tok ".repeat(16 * 1024)
    }
}
