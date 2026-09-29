package app.skein.core.inference.models

import android.net.Uri
import app.skein.core.model.Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Opaque picked-file copy. Owns no vault, registry or inference authorization. The process may keep
 * copying after the vault locks; only [ModelManager.registerCopied] may inspect/register the result.
 * Uses the existing store's hashed staging, atomic promotion and sealing, including crash recovery.
 */
public class ModelImportStager(
    private val store: ImmutableModelStore,
    private val reader: PickedFileReader,
    private val freeBytes: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    public suspend fun copy(
        uri: Uri,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ModelCopyResult =
        withContext(io) {
            val context = currentCoroutineContext()
            var processed = 0L
            var total = ModelManager.UNKNOWN_TOTAL_BYTES

            fun advance(bytes: Int) {
                context.ensureActive()
                processed += bytes
                onProgress(processed, total)
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var observed = 0L
                val name: String?
                reader.open(uri).use { handle ->
                    name = handle.displayName
                    val declared =
                        handle.sizeBytes
                            ?: return@withContext ModelCopyResult.Refused(ImportRefusal.SourceMetadataUnavailable)
                    sizeRefusal(declared)?.let { return@withContext ModelCopyResult.Refused(it) }
                    val required = ModelManager.requiredFreeBytes(declared)
                    val available = freeBytes()
                    if (available < required) {
                        return@withContext ModelCopyResult.Refused(ImportRefusal.InsufficientSpace(required, available))
                    }
                    total = declared * 2
                    onProgress(0, total)
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        context.ensureActive()
                        val count = handle.stream.read(buffer)
                        if (count < 0) break
                        observed += count
                        sizeRefusal(observed)?.let { return@withContext ModelCopyResult.Refused(it) }
                        digest.update(buffer, 0, count)
                        advance(count)
                    }
                }
                sizeRefusal(observed)?.let { return@withContext ModelCopyResult.Refused(it) }
                val sha256 = Hex.encode(digest.digest())
                val manifest =
                    ModelManager.generatedManifest(
                        ModelManager.deriveId(name, sha256),
                        name,
                        sha256,
                        observed,
                    )
                context.ensureActive()
                val result =
                    store.import(manifest) {
                        val handle = reader.open(uri)
                        object : InputStream() {
                            override fun read(): Int {
                                context.ensureActive()
                                return handle.stream.read().also { if (it >= 0) advance(1) }
                            }

                            override fun read(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ): Int {
                                context.ensureActive()
                                return handle.stream.read(b, off, len).also { if (it > 0) advance(it) }
                            }

                            override fun close() = handle.close()
                        }
                    }
                when (result) {
                    is ImportResult.Imported -> ModelCopyResult.Copied(manifest, result.model)
                    is ImportResult.Refused -> ModelCopyResult.Refused(ImportRefusal.FromStore(result.refusal))
                }
            } catch (_: IOException) {
                ModelCopyResult.Refused(ImportRefusal.SourceUnavailable("I/O error"))
            } catch (_: SecurityException) {
                ModelCopyResult.Refused(ImportRefusal.SourceUnavailable("permission grant unavailable"))
            }
        }

    private fun sizeRefusal(size: Long): ImportRefusal.ArtifactTooLarge? =
        if (size !in 1..ModelManager.MAX_ARTIFACT_BYTES) ImportRefusal.ArtifactTooLarge(size) else null
}

public sealed interface ModelCopyResult {
    public data class Copied(
        val manifest: ModelManifest,
        val model: StoredModel,
    ) : ModelCopyResult

    public data class Refused(
        val refusal: ImportRefusal,
    ) : ModelCopyResult
}
