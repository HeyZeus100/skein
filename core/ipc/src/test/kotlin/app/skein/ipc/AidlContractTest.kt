// E0.I16 (bd `skein-mfw`) acceptance criteria 1 and 5: the AIDL compiler must
// generate `IInferenceService`, `IInferenceCallback` and `IEmbedderService`
// stubs, and the LOCK_POLICY_INDEXING.md §7.6/§5.2 additions
// (`onSessionLocking`, `onSessionLocked`, `IEmbedderService.cancel`) must be
// present and invokable alongside the POST_REVIEW_RESOLUTIONS.md §3.3 v2
// shapes (`EmbedResult`, `onTokens(..., dropped)`).
//
// The fakes below are `Stub` subclasses, so this file only compiles if every
// method of the merged contract exists with the merged signature. Calls are
// dispatched through `Stub.asInterface(fake.asBinder())` — i.e. through the
// generated `IBinder` plumbing — rather than on the fake directly.
//
// Behaviour is deliberately trivial: `:core:ipc` is the contract, not an
// implementation. `IsolatedSessionGate` (LOCK_POLICY_INDEXING.md §5.3), which
// reads `sessionEpoch` off each request and refuses with
// `ErrorCode.SESSION_LOCKED`, lives service-side in `:inference-service` /
// `:embedder-service` (E4.I3 / E5.I1) and is not exercised here.

package app.skein.ipc

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AidlContractTest {
    @Test
    fun inferenceServiceStubIsGeneratedWithTheContractDescriptor() {
        assertThat(IInferenceService.Stub.DESCRIPTOR).isEqualTo("app.skein.ipc.IInferenceService")
    }

    @Test
    fun inferenceCallbackStubIsGeneratedWithTheContractDescriptor() {
        assertThat(IInferenceCallback.Stub.DESCRIPTOR).isEqualTo("app.skein.ipc.IInferenceCallback")
    }

    @Test
    fun embedderServiceStubIsGeneratedWithTheContractDescriptor() {
        assertThat(IEmbedderService.Stub.DESCRIPTOR).isEqualTo("app.skein.ipc.IEmbedderService")
    }

    @Test
    fun inferenceServiceOnSessionLockingIsInvokableOverBinder() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        proxy.onSessionLocking(11L, 2000L)

        assertThat(fake.lockingCalls).containsExactly(11L to 2000L)
    }

    @Test
    fun inferenceServiceOnSessionLockedIsInvokableOverBinder() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        proxy.onSessionLocked(11L)

        assertThat(fake.lockedCalls).containsExactly(11L)
    }

    // skein-nxk, judgment call J6. LOCK_POLICY_INDEXING.md §5.3 says ":app
    // re-sends onUnlocked on every fresh bind" and §6.1 invariant I6 says the
    // service refuses everything until it arrives — but §5.2's AIDL delta added
    // only the two LOCKING pushes, so there was no method to send it on and a
    // service could never be authorized at all.
    @Test
    fun inferenceServiceOnSessionUnlockedIsInvokableOverBinder() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        proxy.onSessionUnlocked(11L)

        assertThat(fake.unlockedCalls).containsExactly(11L)
    }

    @Test
    fun inferenceServiceOnSessionUnlockedIsIndependentOfTheLockPushes() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        proxy.onSessionUnlocked(11L)

        assertThat(fake.lockedCalls).isEmpty()
        assertThat(fake.lockingCalls).isEmpty()
    }

    @Test
    fun inferenceServiceCancelIsInvokableOverBinder() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        proxy.cancel(3)

        assertThat(fake.cancelledRequestIds).containsExactly(3)
    }

    // H1 (skein-91yy), SKEIN_HUB.md §3.3. The additive `inspect` method: the
    // `FakeInferenceService` below only compiles if it exists with this
    // signature, and these two prove a `ModelInspection` survives the
    // generated Binder plumbing in both directions.
    @Test
    fun inferenceServiceInspectIsInvokableOverBinder() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        proxy.inspect(InspectRequest(binding = binding(), sessionEpoch = 9L))

        assertThat(fake.inspectRequests.single().sessionEpoch).isEqualTo(9L)
    }

    @Test
    fun inferenceServiceInspectReturnsAModelInspectionAcrossBinder() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        val inspection = proxy.inspect(InspectRequest(binding = binding(), sessionEpoch = 9L))

        assertThat(inspection.architecture).isEqualTo("gemma3")
    }

    @Test
    fun inferenceServiceEmbedAcceptsTheWrappingEmbedRequest() {
        val fake = FakeInferenceService()
        val proxy = IInferenceService.Stub.asInterface(fake.asBinder())

        proxy.embed(EmbedRequest(texts = listOf("a", "b"), sessionEpoch = 3L))

        assertThat(fake.embedRequests.single().sessionEpoch).isEqualTo(3L)
    }

    @Test
    fun embedderServiceCancelIsInvokableOverBinder() {
        val fake = FakeEmbedderService()
        val proxy = IEmbedderService.Stub.asInterface(fake.asBinder())

        proxy.cancel(77)

        assertThat(fake.cancelledRequestIds).containsExactly(77)
    }

    @Test
    fun embedderServiceOnSessionLockingIsInvokableOverBinder() {
        val fake = FakeEmbedderService()
        val proxy = IEmbedderService.Stub.asInterface(fake.asBinder())

        proxy.onSessionLocking(4L, 1500L)

        assertThat(fake.lockingCalls).containsExactly(4L to 1500L)
    }

    @Test
    fun embedderServiceOnSessionLockedIsIdempotent() {
        val fake = FakeEmbedderService()
        val proxy = IEmbedderService.Stub.asInterface(fake.asBinder())

        proxy.onSessionLocked(4L)
        proxy.onSessionLocked(4L)

        assertThat(fake.lockedCalls).containsExactly(4L, 4L)
    }

    @Test
    fun embedderServiceEmbedReturnsAnEmbedResult() {
        val fake = FakeEmbedderService()
        val proxy = IEmbedderService.Stub.asInterface(fake.asBinder())

        val result = proxy.embed(EmbedRequest(texts = listOf("a", "b"), sessionEpoch = 1L))

        assertThat(result.flat).hasLength(2 * 256)
    }

    @Test
    fun embedderServiceRerankAcceptsTheWrappingRerankRequest() {
        val fake = FakeEmbedderService()
        val proxy = IEmbedderService.Stub.asInterface(fake.asBinder())

        val scores = proxy.rerank(RerankRequest(query = "q", candidates = listOf("a", "b"), sessionEpoch = 1L))

        assertThat(scores).hasLength(2)
    }

    @Test
    fun embedderServiceExtractEntitiesAcceptsTheWrappingRequest() {
        val fake = FakeEmbedderService()
        val proxy = IEmbedderService.Stub.asInterface(fake.asBinder())

        val spans = proxy.extractEntities(ExtractEntitiesRequest("Ada", listOf("PERSON"), sessionEpoch = 1L))

        assertThat(spans).isEmpty()
    }

    @Test
    fun inferenceCallbackDeliversTokensOverBinder() {
        val fake = FakeInferenceCallback()
        val proxy = IInferenceCallback.Stub.asInterface(fake.asBinder())

        proxy.onTokens(5, arrayOf("he", "llo"), intArrayOf(1, 2), 0)

        assertThat(fake.pieces).containsExactly("he", "llo").inOrder()
    }

    @Test
    fun inferenceCallbackCarriesTheBackpressureDropCount() {
        val fake = FakeInferenceCallback()
        val proxy = IInferenceCallback.Stub.asInterface(fake.asBinder())

        proxy.onTokens(5, arrayOf("x"), intArrayOf(1), 4)

        assertThat(fake.dropped).isEqualTo(4)
    }

    /** A binding with no descriptors: `inspect`'s marshalling, not its verifier. */
    private fun binding(): ManifestBinding =
        ManifestBinding(
            manifestId = "fixture",
            manifestVersion = 2,
            files = emptyList(),
            attestation = null,
        )

    private class FakeInferenceService : IInferenceService.Stub() {
        val lockingCalls = mutableListOf<Pair<Long, Long>>()
        val lockedCalls = mutableListOf<Long>()
        val unlockedCalls = mutableListOf<Long>()
        val cancelledRequestIds = mutableListOf<Int>()
        val embedRequests = mutableListOf<EmbedRequest>()
        val inspectRequests = mutableListOf<InspectRequest>()

        override fun load(req: LoadRequest): Int = ErrorCode.OK

        override fun inspect(req: InspectRequest): ModelInspection {
            inspectRequests += req
            return ModelInspection(
                errorCode = ErrorCode.OK,
                architecture = "gemma3",
                quantization = "Q4_K_M",
                parameterCount = null,
                contextLength = 8192,
                embeddingWidth = 2560,
                hasVision = false,
                hasChatTemplate = true,
                chatTemplateOk = true,
                tokenizerModel = "llama",
            )
        }

        override fun generate(
            req: GenerateRequest,
            cb: IInferenceCallback,
        ) = Unit

        override fun cancel(requestId: Int) {
            cancelledRequestIds += requestId
        }

        override fun unload() = Unit

        override fun embed(req: EmbedRequest): FloatArray {
            embedRequests += req
            return FloatArray(req.texts.size)
        }

        override fun tokenCount(text: String): Int = text.length

        override fun status(): EngineStatus = EngineStatus("IDLE", null, 0, 0f)

        override fun onSessionLocking(
            epoch: Long,
            budgetMillis: Long,
        ) {
            lockingCalls += epoch to budgetMillis
        }

        override fun onSessionLocked(epoch: Long) {
            lockedCalls += epoch
        }

        // skein-nxk, judgment call J6: the unlock push §5.3 requires and §5.2
        // forgot. Without it `IsolatedSessionGate` can never leave
        // `SessionEpoch.NONE` and every call refuses with SESSION_LOCKED.
        override fun onSessionUnlocked(epoch: Long) {
            unlockedCalls += epoch
        }
    }

    private class FakeEmbedderService : IEmbedderService.Stub() {
        val lockingCalls = mutableListOf<Pair<Long, Long>>()
        val lockedCalls = mutableListOf<Long>()
        val cancelledRequestIds = mutableListOf<Int>()

        override fun load(req: EmbedderLoadRequest): Int = ErrorCode.OK

        override fun embed(req: EmbedRequest): EmbedResult =
            EmbedResult(flat = ByteArray(req.texts.size * 256), droppedInputs = 0)

        override fun extractEntities(req: ExtractEntitiesRequest): List<EntitySpanParcel> = emptyList()

        override fun rerank(req: RerankRequest): FloatArray = FloatArray(req.candidates.size)

        override fun tokenCount(text: String): Int = text.length

        override fun unload() = Unit

        override fun cancel(requestId: Int) {
            cancelledRequestIds += requestId
        }

        override fun onSessionLocking(
            epoch: Long,
            budgetMillis: Long,
        ) {
            lockingCalls += epoch to budgetMillis
        }

        override fun onSessionLocked(epoch: Long) {
            lockedCalls += epoch
        }
    }

    private class FakeInferenceCallback : IInferenceCallback.Stub() {
        val pieces = mutableListOf<String>()
        var dropped: Int = -1
            private set

        override fun onTokens(
            requestId: Int,
            pieces: Array<String>,
            ids: IntArray,
            dropped: Int,
        ) {
            this.pieces += pieces
            this.dropped = dropped
        }

        override fun onDone(
            requestId: Int,
            stats: GenStats,
        ) = Unit

        override fun onError(
            requestId: Int,
            code: Int,
            message: String,
        ) = Unit
    }
}
