// E0.I16 (bd `skein-mfw`), plan §4.7 step 1: every `@Parcelize` class in the
// merged v2 AIDL contract must survive a real `Parcel` write/read cycle.
// Robolectric supplies a working `android.os.Parcel` (AGP's mockable
// `android.jar` would only throw `Stub!`), and every descriptor is a real
// `ParcelFileDescriptor` opened on a temp file, so the fd-passing path that
// makes the load TOCTOU-safe and keeps large payloads off the Binder buffer
// is exercised rather than simulated.
//
// Parcelables holding a `ParcelFileDescriptor` or a `ByteArray` are compared
// field-by-field rather than with `isEqualTo`: those members give the
// generated `data class` `equals` reference semantics, so structural
// equality is the only meaningful assertion for them.

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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParcelRoundTripTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun sharedMemRefRoundTripPreservesPayloadBytes() {
        val original = sharedMemRef("image.png", "png-bytes")

        val restored = roundTrip(original)

        assertThat(readAll(restored.fd)).isEqualTo("png-bytes")
    }

    @Test
    fun sharedMemRefRoundTripPreservesSizeAndHints() {
        val original = sharedMemRef("image.png", "png-bytes")

        val restored = roundTrip(original)

        assertThat(listOf(restored.sizeBytes, restored.mimeHint, restored.role))
            .isEqualTo(listOf(original.sizeBytes, "image/png", "image"))
    }

    @Test
    fun manifestFileRefRoundTripPreservesFdContents() {
        val original = manifestFileRef("main", "model.gguf", "model-bytes")

        val restored = roundTrip(original)

        assertThat(readAll(restored.fd)).isEqualTo("model-bytes")
    }

    @Test
    fun manifestFileRefRoundTripPreservesExpectedSha256() {
        val original = manifestFileRef("main", "model.gguf", "model-bytes")

        val restored = roundTrip(original)

        assertThat(restored.expectedSha256).isEqualTo(original.expectedSha256)
    }

    @Test
    fun manifestFileRefRoundTripPreservesRoleAndSize() {
        val original = manifestFileRef("tokenizer", "tokenizer.json", "{}")

        val restored = roundTrip(original)

        assertThat(listOf(restored.role, restored.expectedSizeBytes)).isEqualTo(listOf("tokenizer", 2L))
    }

    @Test
    fun attestationRefParcelRoundTripPreservesCovers() {
        val original =
            AttestationRefParcel(
                bundleFd = readOnlyFd("model.sigstore.json", "{}"),
                covers = listOf("main", "companions"),
            )

        val restored = roundTrip(original)

        assertThat(restored.covers).containsExactly("main", "companions").inOrder()
    }

    @Test
    fun manifestBindingRoundTripPreservesEveryFile() {
        val original = manifestBinding()

        val restored = roundTrip(original)

        assertThat(restored.files.map { it.role }).containsExactly("main", "mmproj").inOrder()
    }

    @Test
    fun manifestBindingRoundTripPreservesIdentity() {
        val original = manifestBinding()

        val restored = roundTrip(original)

        assertThat(listOf(restored.manifestId, restored.manifestVersion))
            .isEqualTo(listOf("qwen2.5-3b-instruct-abliterated", 2))
    }

    @Test
    fun manifestBindingRoundTripPreservesNullAttestation() {
        val original = manifestBinding().copy(attestation = null)

        val restored = roundTrip(original)

        assertThat(restored.attestation).isNull()
    }

    @Test
    fun loadRequestRoundTripPreservesMainModelFdContents() {
        val original = loadRequest()

        val restored = roundTrip(original)

        assertThat(
            readAll(
                restored.binding.files
                    .first { it.role == "main" }
                    .fd,
            ),
        ).isEqualTo("model-bytes")
    }

    @Test
    fun loadRequestRoundTripPreservesSessionEpoch() {
        val original = loadRequest().copy(sessionEpoch = 42L)

        val restored = roundTrip(original)

        assertThat(restored.sessionEpoch).isEqualTo(42L)
    }

    @Test
    fun loadRequestRoundTripPreservesScalars() {
        val original = loadRequest()

        val restored = roundTrip(original)

        assertThat(listOf(restored.contextLength, restored.threads, restored.gpuLayers, restored.embeddingMode))
            .isEqualTo(listOf(8192, 4, 0, false))
    }

    // ------------------------------------------- H1 (skein-91yy), §3.3

    @Test
    fun inspectRequestRoundTripPreservesMainModelFdContents() {
        val original = InspectRequest(binding = manifestBinding(), sessionEpoch = 5L)

        val restored = roundTrip(original)

        assertThat(
            readAll(
                restored.binding.files
                    .first { it.role == "main" }
                    .fd,
            ),
        ).isEqualTo("model-bytes")
    }

    @Test
    fun inspectRequestRoundTripPreservesSessionEpoch() {
        val original = InspectRequest(binding = manifestBinding(), sessionEpoch = 5L)

        val restored = roundTrip(original)

        assertThat(restored.sessionEpoch).isEqualTo(5L)
    }

    @Test
    fun modelInspectionRoundTripsToAnEqualValue() {
        val original = modelInspection()

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun modelInspectionRoundTripPreservesEveryNullableField() {
        val original = ModelInspection.refused(ErrorCode.HASH_MISMATCH)

        val restored = roundTrip(original)

        assertThat(
            listOf(
                restored.architecture,
                restored.quantization,
                restored.parameterCount,
                restored.contextLength,
                restored.embeddingWidth,
                restored.tokenizerModel,
            ),
        ).containsExactly(null, null, null, null, null, null)
    }

    @Test
    fun aRefusedInspectionCarriesOnlyItsErrorCode() {
        val refused = ModelInspection.refused(ErrorCode.SESSION_LOCKED)

        assertThat(refused.errorCode).isEqualTo(ErrorCode.SESSION_LOCKED)
    }

    @Test
    fun aRefusedInspectionClaimsNoCapability() {
        val refused = ModelInspection.refused(ErrorCode.INVALID_MODEL)

        assertThat(listOf(refused.hasVision, refused.hasChatTemplate, refused.chatTemplateOk))
            .containsExactly(false, false, false)
    }

    @Test
    fun chatMessageParcelRoundTripsToAnEqualValue() {
        val original = ChatMessageParcel(role = "user", content = "hello")

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun samplingParcelRoundTripsToAnEqualValue() {
        val original = sampling()

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun generateRequestRoundTripPreservesMessages() {
        val original = generateRequest()

        val restored = roundTrip(original)

        assertThat(restored.messages).isEqualTo(original.messages)
    }

    @Test
    fun generateRequestRoundTripPreservesAttachmentPayload() {
        val original = generateRequest()

        val restored = roundTrip(original)

        assertThat(readAll(restored.attachmentFds.single().fd)).isEqualTo("png-bytes")
    }

    @Test
    fun generateRequestRoundTripPreservesSampling() {
        val original = generateRequest()

        val restored = roundTrip(original)

        assertThat(restored.sampling).isEqualTo(original.sampling)
    }

    @Test
    fun generateRequestRoundTripPreservesSessionEpoch() {
        val original = generateRequest().copy(sessionEpoch = 7L)

        val restored = roundTrip(original)

        assertThat(restored.sessionEpoch).isEqualTo(7L)
    }

    @Test
    fun genStatsRoundTripsToAnEqualValue() {
        val original =
            GenStats(
                stopReason = "EOS",
                promptTokens = 128,
                generatedTokens = 64,
                ttftMs = 310L,
                tokensPerSec = 12.5f,
            )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun engineStatusRoundTripsToAnEqualValue() {
        val original =
            EngineStatus(
                state = "READY",
                modelSha256 = "a".repeat(64),
                contextLength = 4096,
                tokensPerSec = 9.25f,
                usedChatTemplateFallback = true,
            )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun engineStatusRoundTripDefaultsTheFallbackWarningToFalse() {
        val original = EngineStatus(state = "READY", modelSha256 = null, contextLength = 4096, tokensPerSec = 0f)

        val restored = roundTrip(original)

        assertThat(restored.usedChatTemplateFallback).isFalse()
    }

    // -------------------------------------------------------------- gg11.2

    @Test
    fun backendReportRequestRoundTripPreservesSessionEpoch() {
        val original = BackendReportRequest(sessionEpoch = 5L)

        val restored = roundTrip(original)

        assertThat(restored.sessionEpoch).isEqualTo(5L)
    }

    @Test
    fun backendReportRoundTripsToAnEqualValue() {
        val original = backendReport()

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun backendReportRoundTripPreservesDeviceOrder() {
        val original = backendReport()

        val restored = roundTrip(original)

        assertThat(restored.devices).containsExactlyElementsIn(original.devices).inOrder()
    }

    @Test
    fun aRefusedBackendReportCarriesOnlyItsErrorCode() {
        val refused = BackendReport.refused(ErrorCode.SESSION_LOCKED)

        val restored = roundTrip(refused)

        assertThat(
            listOf(restored.errorCode, restored.devices, restored.cpuFeatures, restored.nOutputsMax),
        ).isEqualTo(listOf(ErrorCode.SESSION_LOCKED, emptyList<BackendDeviceParcel>(), emptyList<String>(), null))
    }

    private fun backendReport(): BackendReport =
        BackendReport(
            errorCode = ErrorCode.OK,
            devices = listOf(BackendDeviceParcel(type = BackendDeviceType.CPU, name = "CPU")),
            cpuFeatures = listOf("NEON", "DOTPROD"),
            gpuLayersOffloaded = 0,
            nOutputsMax = 1,
            nBatch = 512,
            nUbatch = 512,
        )

    @Test
    fun engineStatusRoundTripPreservesNullModelSha256() {
        val original = EngineStatus(state = "IDLE", modelSha256 = null, contextLength = 0, tokensPerSec = 0f)

        val restored = roundTrip(original)

        assertThat(restored.modelSha256).isNull()
    }

    @Test
    fun embedderLoadRequestRoundTripPreservesEmbedModelFdContents() {
        val original = embedderLoadRequest()

        val restored = roundTrip(original)

        assertThat(
            readAll(
                restored.embedBinding.files
                    .first { it.role == "main" }
                    .fd,
            ),
        ).isEqualTo("embed-model-bytes")
    }

    @Test
    fun embedderLoadRequestRoundTripPreservesEmbedFormat() {
        val original = embedderLoadRequest()

        val restored = roundTrip(original)

        assertThat(restored.embedFormat).isEqualTo("onnx")
    }

    @Test
    fun embedderLoadRequestRoundTripPreservesNullableCompanionBindings() {
        val original = embedderLoadRequest()

        val restored = roundTrip(original)

        assertThat(listOf(restored.nerBinding, restored.rerankBinding)).containsExactly(null, null)
    }

    @Test
    fun embedderLoadRequestRoundTripPreservesPresentRerankBinding() {
        val original = embedderLoadRequest().copy(rerankBinding = manifestBinding().copy(manifestId = "bge-rerank"))

        val restored = roundTrip(original)

        assertThat(restored.rerankBinding?.manifestId).isEqualTo("bge-rerank")
    }

    @Test
    fun embedderLoadRequestRoundTripPreservesSessionEpoch() {
        val original = embedderLoadRequest().copy(sessionEpoch = 99L)

        val restored = roundTrip(original)

        assertThat(restored.sessionEpoch).isEqualTo(99L)
    }

    @Test
    fun entitySpanParcelRoundTripsToAnEqualValue() {
        val original = EntitySpanParcel(start = 3, end = 11, text = "Ada Lovelace", label = "PERSON", score = 0.97f)

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun embedRequestRoundTripsToAnEqualValueWhenInline() {
        val original = EmbedRequest(texts = listOf("alpha", "beta"), isQuery = true, sessionEpoch = 5L)

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun embedRequestRoundTripPreservesSharedMemoryInput() {
        val original =
            EmbedRequest(
                texts = emptyList(),
                inputFd = sharedMemRef("texts.bin", "length-prefixed", mimeHint = "text/plain; charset=utf-8"),
                sessionEpoch = 5L,
            )

        val restored = roundTrip(original)

        assertThat(readAll(requireNotNull(restored.inputFd).fd)).isEqualTo("length-prefixed")
    }

    @Test
    fun embedRequestIsQueryDefaultsToFalse() {
        val original = EmbedRequest(texts = listOf("alpha"), sessionEpoch = 5L)

        val restored = roundTrip(original)

        assertThat(restored.isQuery).isFalse()
    }

    @Test
    fun embedRequestInputFdDefaultsToNull() {
        val original = EmbedRequest(texts = listOf("alpha"), sessionEpoch = 5L)

        val restored = roundTrip(original)

        assertThat(restored.inputFd).isNull()
    }

    @Test
    fun embedResultRoundTripPreservesFlatVectors() {
        val original = EmbedResult(flat = ByteArray(512) { (it % 127).toByte() }, droppedInputs = 0)

        val restored = roundTrip(original)

        assertThat(restored.flat.toList()).isEqualTo(original.flat.toList())
    }

    @Test
    fun embedResultRoundTripPreservesDroppedInputs() {
        val original = EmbedResult(flat = ByteArray(0), droppedInputs = 3)

        val restored = roundTrip(original)

        assertThat(restored.droppedInputs).isEqualTo(3)
    }

    @Test
    fun extractEntitiesRequestRoundTripsToAnEqualValueWhenInline() {
        val original =
            ExtractEntitiesRequest(
                text = "Ada met Charles in London.",
                labels = listOf("PERSON", "PLACE"),
                sessionEpoch = 12L,
            )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun extractEntitiesRequestRoundTripPreservesSharedMemoryInput() {
        val original =
            ExtractEntitiesRequest(
                text = "",
                labels = listOf("PERSON"),
                inputFd = sharedMemRef("doc.txt", "a long document", mimeHint = "text/plain; charset=utf-8"),
                sessionEpoch = 12L,
            )

        val restored = roundTrip(original)

        assertThat(readAll(requireNotNull(restored.inputFd).fd)).isEqualTo("a long document")
    }

    @Test
    fun rerankRequestRoundTripsToAnEqualValueWhenInline() {
        val original =
            RerankRequest(
                query = "analytical engine",
                candidates = listOf("note one", "note two"),
                sessionEpoch = 13L,
            )

        val restored = roundTrip(original)

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun rerankRequestRoundTripPreservesSharedMemoryInput() {
        val original =
            RerankRequest(
                query = "analytical engine",
                candidates = emptyList(),
                inputFd = sharedMemRef("cands.bin", "many candidates", mimeHint = "text/plain; charset=utf-8"),
                sessionEpoch = 13L,
            )

        val restored = roundTrip(original)

        assertThat(readAll(requireNotNull(restored.inputFd).fd)).isEqualTo("many candidates")
    }

    private fun loadRequest(): LoadRequest =
        LoadRequest(
            binding = manifestBinding(),
            contextLength = 8192,
            threads = 4,
            gpuLayers = 0,
            embeddingMode = false,
            sessionEpoch = 1L,
        )

    private fun modelInspection(): ModelInspection =
        ModelInspection(
            errorCode = ErrorCode.OK,
            architecture = "gemma3",
            quantization = "Q4_K_M",
            parameterCount = null,
            contextLength = 8192,
            embeddingWidth = 2560,
            hasVision = true,
            hasChatTemplate = true,
            chatTemplateOk = true,
            tokenizerModel = "llama",
        )

    private fun manifestBinding(): ManifestBinding =
        ManifestBinding(
            manifestId = "qwen2.5-3b-instruct-abliterated",
            manifestVersion = 2,
            files =
                listOf(
                    manifestFileRef("main", "model.gguf", "model-bytes"),
                    manifestFileRef("mmproj", "mmproj.gguf", "mmproj-bytes"),
                ),
            attestation =
                AttestationRefParcel(
                    bundleFd = readOnlyFd("model.sigstore.json", "{}"),
                    covers = listOf("all"),
                ),
        )

    private fun generateRequest(): GenerateRequest =
        GenerateRequest(
            requestId = 17,
            messages =
                listOf(
                    ChatMessageParcel(role = "system", content = "be terse"),
                    ChatMessageParcel(role = "user", content = "hello"),
                ),
            attachmentFds = listOf(sharedMemRef("image.png", "png-bytes")),
            sampling = sampling(),
            sessionEpoch = 1L,
        )

    private fun embedderLoadRequest(): EmbedderLoadRequest =
        EmbedderLoadRequest(
            embedBinding =
                ManifestBinding(
                    manifestId = "nomic-embed-text-v1.5",
                    manifestVersion = 2,
                    files =
                        listOf(
                            manifestFileRef("main", "embed.onnx", "embed-model-bytes"),
                            manifestFileRef("tokenizer", "tokenizer.json", "{}"),
                        ),
                    attestation = null,
                ),
            embedFormat = "onnx",
            nerBinding = null,
            rerankBinding = null,
            threads = 2,
            sessionEpoch = 1L,
        )

    private fun sampling(): SamplingParcel =
        SamplingParcel(
            temperature = 0.7f,
            topK = 40,
            topP = 0.95f,
            minP = 0.05f,
            repeatPenalty = 1.1f,
            maxTokens = 512,
            seed = 1234L,
            stop = listOf("</s>"),
        )

    private fun manifestFileRef(
        role: String,
        name: String,
        contents: String,
    ): ManifestFileRef =
        ManifestFileRef(
            role = role,
            fd = readOnlyFd(name, contents),
            expectedSha256 = role.first().toString().repeat(64),
            expectedSizeBytes = contents.length.toLong(),
        )

    private fun sharedMemRef(
        name: String,
        contents: String,
        mimeHint: String = "image/png",
    ): SharedMemRef =
        SharedMemRef(
            fd = readOnlyFd(name, contents),
            sizeBytes = contents.length.toLong(),
            mimeHint = mimeHint,
            role = if (mimeHint.startsWith("image/")) "image" else "input-texts",
        )

    private fun readOnlyFd(
        name: String,
        contents: String,
    ): ParcelFileDescriptor {
        val file: File = temporaryFolder.newFile(name)
        file.writeText(contents)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun readAll(fd: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }

    /**
     * `PARCELABLE_WRITE_RETURN_VALUE` is deliberate: it makes each
     * [ParcelFileDescriptor] close itself as it is written, which is how a
     * descriptor is handed over. Robolectric models exactly that — a PFD read
     * back out of a Parcel resolves to the sender's file only once the
     * sender's PFD has been closed, and otherwise throws
     * `FileDescriptorFromParcelUnavailableException`.
     */
    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readParcelable<T>(T::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
