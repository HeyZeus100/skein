package app.skein.embedder.service

import android.os.ParcelFileDescriptor
import app.skein.core.verify.ModelFileRole
import app.skein.ipc.EmbedderLoadRequest
import app.skein.ipc.ErrorCode
import app.skein.ipc.ManifestBinding
import app.skein.ipc.ManifestFileRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifiedEmbedderModelsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun pinnedMainAndCompanionSurviveReceivedDescriptorClosure() {
        val request = request(listOf(file("main", "weights"), file("tokenizer", "tokenizer")))
        val models = VerifiedEmbedderModels.verify(request, EmbedderCancellation())
        VerifiedEmbedderModels.closeReceived(request)
        try {
            val view = models.embedding.getValue(ModelFileRole.TOKENIZER).bytes()
            val actual = ByteArray(view.remaining()).also { view.get(it) }
            assertArrayEquals("tokenizer".toByteArray(), actual)
        } finally {
            models.close()
        }
    }

    @Test
    fun companionMismatchHasCompanionCodeAndNoBackendCanUseIt() {
        val request =
            request(
                listOf(file("main", "weights"), file("tokenizer", "tokenizer").copy(expectedSha256 = "00".repeat(32))),
            )
        try {
            assertEquals(
                ErrorCode.COMPANION_HASH_MISMATCH,
                assertThrows(EmbedderModelException::class.java) {
                    VerifiedEmbedderModels.verify(request, EmbedderCancellation())
                }.code,
            )
        } finally {
            VerifiedEmbedderModels.closeReceived(request)
        }
    }

    @Test
    fun duplicateUnknownOrMissingMainRolesRefuseBeforeLoad() {
        listOf(listOf("main", "main"), listOf("main", "unknown"), listOf("tokenizer")).forEach { roles ->
            val request = request(roles.map { file(it, "synthetic") })
            try {
                assertEquals(
                    ErrorCode.INVALID_MODEL,
                    assertThrows(EmbedderModelException::class.java) {
                        VerifiedEmbedderModels.verify(request, EmbedderCancellation())
                    }.code,
                )
            } finally {
                VerifiedEmbedderModels.closeReceived(request)
            }
        }
    }

    @Test
    fun cancellationIsNotReportedAsModelCorruption() {
        val request = request(listOf(file("main", "weights")))
        try {
            val cancellation = EmbedderCancellation().apply { cancel() }
            assertEquals(
                EmbedderRequestFailure.CANCELLED,
                assertThrows(EmbedderRequestException::class.java) {
                    VerifiedEmbedderModels.verify(request, cancellation)
                }.failure,
            )
        } finally {
            VerifiedEmbedderModels.closeReceived(request)
        }
    }

    @Test
    fun closeReceivedCoversAllThreeBindingsEvenBeforeVerification() {
        val request =
            request(listOf(file("main", "weights"))).copy(
                nerBinding = binding(listOf(file("main", "entities"))),
                rerankBinding = binding(listOf(file("main", "reranker"))),
            )
        VerifiedEmbedderModels.closeReceived(request)
        listOfNotNull(request.embedBinding, request.nerBinding, request.rerankBinding)
            .flatMap {
                it.files
            }.forEach { assertFalse(it.fd.fileDescriptor.valid()) }
    }

    private fun file(
        role: String,
        content: String,
    ): ManifestFileRef {
        val bytes = content.toByteArray()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ManifestFileRef(
            role,
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            digest,
            bytes.size.toLong(),
        )
    }

    private fun binding(files: List<ManifestFileRef>) = ManifestBinding("synthetic", 2, files, null)

    private fun request(files: List<ManifestFileRef>) = EmbedderLoadRequest(binding(files), "onnx", null, null, 1, 7, 1)
}
