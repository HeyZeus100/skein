package app.skein.benchmark

import app.skein.core.model.Blake3
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal data class HostModelIdentity(
    val modelSha256: String,
    val modelSize: Long,
    val blake3: String,
    val evidenceSha256: String,
)

internal data class SyntheticFileHashes(
    val sha256: String,
    val blake3: String,
    val bytes: Long,
)

internal data class SyntheticSetupConfig(
    val seeds: List<Long>,
    val contextLength: Int,
    val threads: Int,
    val caseTimeoutMs: Long,
    val sampling: JsonObject,
    val modelSize: Long,
    val hostIdentity: HostModelIdentity?,
    val testBuildSha: String?,
)

/** Opt-in setup only: host BLAKE3 is metadata, never a substitute for device SHA-256 verification. */
internal object SyntheticSetup {
    fun configuration(config: JsonObject): SyntheticSetupConfig {
        val seeds = config.getValue("seeds").jsonArray.map { it.jsonPrimitive.long }
        require(
            seeds.size in 1..10 && seeds.distinct().size == seeds.size && seeds.all { it >= 0 },
        ) { "invalid fixed seeds" }
        val contextLength = config.getValue("context_length").jsonPrimitive.int
        require(contextLength in 128..16_384) { "context outside supported bound" }
        val threads = config.getValue("threads").jsonPrimitive.int
        require(threads in 1..16) { "thread count outside bound" }
        val timeout = config.getValue("case_timeout_ms").jsonPrimitive.long
        require(timeout in 1_000..600_000) { "case timeout outside bound" }
        val sampling = config.getValue("sampling").jsonObject
        require(
            sampling.getValue("max_tokens").jsonPrimitive.int in 1..contextLength,
        ) { "answer budget outside context" }
        val modelSize = config.getValue("model_size").jsonPrimitive.long
        require(modelSize > 0) { "model size outside bound" }
        return SyntheticSetupConfig(
            seeds,
            contextLength,
            threads,
            timeout,
            sampling,
            modelSize,
            hostIdentity(config),
            testBuildSha(config),
        )
    }

    fun hostIdentity(config: JsonObject): HostModelIdentity? {
        val value = config["host_model_identity"] ?: return null
        val row = value.jsonObject
        require(row.keys == setOf("schema_version", "model_sha256", "model_size", "blake3", "evidence_sha256")) {
            "unsupported host model identity fields"
        }
        val version = row.getValue("schema_version").jsonPrimitive
        require(!version.isString && version.int == 1) { "unsupported host model identity schema" }

        fun hex(key: String): String {
            val field = row.getValue(key).jsonPrimitive
            require(
                field.isString && field.content.matches(SyntheticFixtures.SHA256),
            ) { "invalid host identity digest" }
            return field.content
        }
        val size = row.getValue("model_size").jsonPrimitive
        require(!size.isString) { "invalid host model size" }
        val identity =
            HostModelIdentity(
                hex("model_sha256"),
                size.long,
                hex("blake3"),
                hex("evidence_sha256"),
            )
        require(identity.modelSize > 0 && identity.modelSize == config.getValue("model_size").jsonPrimitive.long) {
            "host model size mismatch"
        }
        require(identity.modelSha256 == config.string("model_sha256")) { "host model SHA256 mismatch" }
        return identity
    }

    fun testBuildSha(config: JsonObject): String? =
        config["test_build_sha"]?.jsonPrimitive?.let {
            require(it.isString && it.content.matches(Regex("[a-f0-9]{40}"))) { "invalid test source commit" }
            it.content
        }

    fun hashFile(
        file: File,
        includeBlake3: Boolean = false,
        progress: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): SyntheticFileHashes {
        val sha = MessageDigest.getInstance("SHA-256")
        val blake = if (includeBlake3) Blake3.Hasher() else null
        var bytes = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
                blake?.update(buffer, 0, count)
                bytes += count
                progress(bytes)
            }
        }
        checkCancelled()
        return SyntheticFileHashes(Blake3.toHex(sha.digest()), blake?.digest()?.let(Blake3::toHex) ?: "", bytes)
    }

    fun verifiedModel(
        file: File,
        expectedSha256: String,
        expectedSize: Long,
        host: HostModelIdentity?,
        progress: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): SyntheticFileHashes {
        require(expectedSize > 0 && expectedSha256.matches(SyntheticFixtures.SHA256)) { "invalid model identity" }
        require(host == null || (host.modelSha256 == expectedSha256 && host.modelSize == expectedSize)) {
            "host identity does not describe this model"
        }
        check(file.length() == expectedSize) { "model size mismatch" }
        val measured = hashFile(file, host == null, progress, checkCancelled)
        check(measured.bytes == expectedSize && measured.sha256 == expectedSha256) { "full model identity mismatch" }
        return if (host == null) measured else measured.copy(blake3 = host.blake3)
    }

    fun provenance(host: HostModelIdentity?): String =
        if (host == null) "device_computed" else "host_declared_bound_to_device_sha256"
}

internal enum class SetupPhase {
    CONFIG,
    FIXTURE,
    APP_APK_HASH,
    MODEL_HASH,
    STORE,
    TEST_APK_HASH,
    MANIFEST,
    SESSION_UNLOCK,
    ENGINE_LOAD,
}

/** Content-free, append-only progress in a fresh synthetic output directory. */
internal class SyntheticSetupProgress private constructor(
    val output: File,
    private val runId: String,
    private val clock: () -> Long,
) {
    private val file = File(output, "setup-progress.jsonl")
    private val started = clock()
    private var sequence = 0

    init {
        check(file.createNewFile()) { "setup progress already exists" }
    }

    suspend fun <T> stage(
        phase: SetupPhase,
        totalBytes: Long? = null,
        block: suspend ((Long) -> Unit) -> T,
    ): T {
        require(totalBytes == null || totalBytes >= 0) { "invalid progress size" }
        var observed = if (totalBytes == null) null else 0L
        var emitted = 0L
        val stride = maxOf(64L * 1024 * 1024, (totalBytes ?: 0) / 32 + 1)
        append(phase, "start", observed, totalBytes)
        try {
            val result =
                block { bytes ->
                    require(totalBytes != null && bytes >= checkNotNull(observed) && bytes <= totalBytes) {
                        "invalid progress byte count"
                    }
                    observed = bytes
                    if (bytes - emitted >= stride) {
                        append(phase, "progress", bytes, totalBytes)
                        emitted = bytes
                    }
                }
            append(phase, "complete", observed, totalBytes)
            return result
        } catch (failure: Exception) {
            val code =
                when (failure) {
                    is CancellationException -> "CANCELLED"
                    is IOException -> "IO_ERROR"
                    is IllegalArgumentException, is IllegalStateException -> "VALIDATION_FAILED"
                    else -> "SETUP_FAILED"
                }
            try {
                append(phase, "error", observed, totalBytes, code)
            } catch (progressFailure: Exception) {
                failure.addSuppressed(progressFailure)
            }
            throw failure
        }
    }

    private fun append(
        phase: SetupPhase,
        event: String,
        bytes: Long?,
        total: Long?,
        code: String? = null,
    ) {
        check(sequence < 256 && file == file.canonicalFile && file.isFile) { "setup progress boundary invalid" }
        val row =
            buildJsonObject {
                put("schema_version", JsonPrimitive(1))
                put("run_id", JsonPrimitive(runId))
                put("sequence", JsonPrimitive(sequence++))
                put("phase", JsonPrimitive(phase.name.lowercase()))
                put("event", JsonPrimitive(event))
                put("elapsed_ms", JsonPrimitive((clock() - started).coerceAtLeast(0) / 1_000_000))
                put("bytes_processed", bytes?.let(::JsonPrimitive) ?: JsonNull)
                put("total_bytes", total?.let(::JsonPrimitive) ?: JsonNull)
                put("error_code", code?.let(::JsonPrimitive) ?: JsonNull)
            }.toString() + "\n"
        check(row.toByteArray().size <= 1024 && file.length() + row.toByteArray().size <= 256 * 1024) {
            "setup progress exceeds bound"
        }
        FileOutputStream(file, true).use { stream ->
            stream.write(row.toByteArray())
            stream.fd.sync()
        }
    }

    companion object {
        fun create(
            root: File,
            runId: String,
            clock: () -> Long = System::nanoTime,
        ): SyntheticSetupProgress {
            require(runId.matches(SyntheticFixtures.SAFE_ID)) { "unsafe run id" }
            require(root == root.canonicalFile) { "benchmark root cannot contain a symlink" }
            val outputRoot = File(root, "output")
            require(outputRoot == outputRoot.canonicalFile) { "output root cannot contain a symlink" }
            check(outputRoot.isDirectory || outputRoot.mkdirs()) { "output root cannot be created" }
            val output = File(outputRoot, runId)
            check(!output.exists() && output.mkdir()) { "output run already exists or cannot be created" }
            return SyntheticSetupProgress(output, runId, clock)
        }
    }
}
