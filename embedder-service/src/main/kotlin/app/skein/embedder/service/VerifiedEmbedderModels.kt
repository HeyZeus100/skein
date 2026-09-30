package app.skein.embedder.service

import app.skein.core.verify.DupedDescriptor
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import app.skein.core.verify.PinResult
import app.skein.core.verify.PinnedLoad
import app.skein.core.verify.PinnedModel
import app.skein.core.verify.PinnedModelFile
import app.skein.core.verify.VerifyBinding
import app.skein.core.verify.VerifyCancellation
import app.skein.core.verify.VerifyFile
import app.skein.ipc.EmbedderLoadRequest
import app.skein.ipc.ErrorCode
import app.skein.ipc.ManifestBinding
import java.io.Closeable
import java.nio.ByteBuffer

internal class EmbedderModelException(
    val code: Int,
) : RuntimeException("embedder model refused")

/** Every file, including companions, passes SHA-256 before and after mapping. */
internal class VerifiedEmbedderModels private constructor(
    val embedding: Map<ModelFileRole, VerifiedFile>,
    val entities: Map<ModelFileRole, VerifiedFile>?,
    val reranker: Map<ModelFileRole, VerifiedFile>?,
) : Closeable {
    class VerifiedFile internal constructor(
        private val ready: PinnedLoad.Ready,
    ) : Closeable {
        val enginePath: String get() = ready.enginePath

        fun bytes(): ByteBuffer = ready.mapped.asReadOnlyBuffer()

        override fun close() = ready.model.close()
    }

    override fun close() {
        listOfNotNull(embedding, entities, reranker).forEach { group -> group.values.forEach { it.close() } }
    }

    companion object {
        /** Original received descriptors are separately owned by the request worker. */
        fun verify(
            request: EmbedderLoadRequest,
            cancellation: EmbedderCancellation,
        ): VerifiedEmbedderModels {
            val owned = mutableListOf<VerifiedFile>()
            try {
                fun group(binding: ManifestBinding): Map<ModelFileRole, VerifiedFile> {
                    val roles = binding.files.map { ModelFileRole.fromWire(it.role) ?: invalid() }
                    if (roles.toSet().size != roles.size || ModelFileRole.MAIN !in roles) invalid()
                    if (binding.manifestId.isBlank() || binding.manifestVersion <= 0) invalid()
                    return binding.files
                        .mapIndexed { index, ref ->
                            cancellation.check()
                            val role = roles[index]
                            if (!ref.expectedSha256.matches(Regex("[0-9a-f]{64}")) ||
                                ref.expectedSizeBytes <= 0
                            ) {
                                invalid()
                            }
                            val pin =
                                when (
                                    val pinned =
                                        PinnedModelFile.pin {
                                            val duplicate = ref.fd.dup()
                                            DupedDescriptor(
                                                duplicate.fileDescriptor,
                                                duplicate.fd,
                                            ) { duplicate.close() }
                                        }
                                ) {
                                    is PinResult.Pinned -> pinned.file
                                    is PinResult.Refused -> throw EmbedderModelException(ErrorCode.INTERNAL)
                                }
                            // Verify each companion as its own mapping as well, rather than
                            // trusting only the pre-map companion pass in a grouped binding.
                            val ready =
                                try {
                                    ModelVerifier.verifyPinned(
                                        PinnedModel(pin),
                                        VerifyBinding(
                                            listOf(
                                                VerifyFile(
                                                    ModelFileRole.MAIN,
                                                    ref.expectedSha256,
                                                    ref.expectedSizeBytes,
                                                ),
                                            ),
                                        ),
                                        cancellation = VerifyCancellation { cancellation.isCancelled() },
                                    )
                                } catch (failure: Throwable) {
                                    pin.close()
                                    throw failure
                                }
                            when (ready) {
                                is PinnedLoad.Ready -> role to VerifiedFile(ready).also { owned += it }
                                is PinnedLoad.Refused -> throw EmbedderModelException(code(ready.refusal, role))
                            }
                        }.toMap()
                }
                return VerifiedEmbedderModels(
                    group(request.embedBinding),
                    request.nerBinding?.let(::group),
                    request.rerankBinding?.let(::group),
                )
            } catch (failure: Throwable) {
                owned.forEach { it.close() }
                throw failure
            }
        }

        fun closeReceived(request: EmbedderLoadRequest) {
            listOfNotNull(request.embedBinding, request.nerBinding, request.rerankBinding).forEach { binding ->
                binding.files.forEach { runCatching { it.fd.close() } }
                // This boundary verifies declared hashes. It does not claim attestation verification.
                binding.attestation?.let { runCatching { it.bundleFd.close() } }
            }
        }

        private fun invalid(): Nothing = throw EmbedderModelException(ErrorCode.INVALID_MODEL)

        private fun code(
            refusal: ModelVerification.Refusal,
            role: ModelFileRole,
        ): Int =
            when (refusal) {
                is ModelVerification.HashMismatch ->
                    if (role ==
                        ModelFileRole.MAIN
                    ) {
                        ErrorCode.HASH_MISMATCH
                    } else {
                        ErrorCode.COMPANION_HASH_MISMATCH
                    }
                is ModelVerification.Tampered ->
                    if (role ==
                        ModelFileRole.MAIN
                    ) {
                        ErrorCode.HASH_MISMATCH_POST_MMAP
                    } else {
                        ErrorCode.COMPANION_HASH_MISMATCH
                    }
                is ModelVerification.Cancelled -> ErrorCode.CANCELLED
                is ModelVerification.IoFailure -> ErrorCode.INTERNAL
                else -> ErrorCode.INVALID_MODEL
            }
    }
}
