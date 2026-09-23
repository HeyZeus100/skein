// skein-cyq (E4.I5, H0-amended per docs/design/SKEIN_HUB.md §3.6/§9).
//
// `ModelManager` is the one entry point through which a model's bytes ever
// reach `filesDir/models/<id>/<file>` (`MODEL_STORE.md` §1). It never
// parses a GGUF itself: with a manifest (`ImportSource.Bundled`), every
// load-bearing field is already declared and Core only re-verifies digests
// (`ImmutableModelStore.import`, EXISTS); without one (`ImportSource.Picked`,
// and — once H5 lands — `HubOffer`), it runs the bounded, allowlisted
// `GgufPreCheck` (skein-hewz) as a cheap early refusal, then defers to
// `ModelInspector.inspect`, which runs entirely inside the isolated
// `:inference` process (`docs/design/SKEIN_HUB.md` §3.3), and only THEN
// synthesises a manifest from what that inspection reported.
//
// TWO-PASS DEVIATION (judgment call, recorded here because no design
// document specifies the mechanics of Core-assigning an id derived from a
// file's own content hash before that file has been copied anywhere): for
// `ImportSource.Picked`, `SKEIN_HUB.md` §3.4 says `id` is
// `<slug-of-name>-<first-12-hex-of-sha256>` — but the sha256 is only known
// once the bytes have been read, and `ImmutableModelStore.import` needs a
// complete `ModelManifest` (id included) before it stages a single byte,
// since the id names the destination directory. Duplicating
// `ImmutableModelStore`'s staging/hashing/atomic-rename/sealing logic here
// to avoid a second read was rejected (Ponytail rung 2: that logic already
// exists, tested, in `ImmutableModelStore`; re-deriving it would be a
// second, divergent copy of load-bearing security logic). Instead: a
// read-only pre-pass hashes the picked stream (no disk write — the
// `InsufficientSpace`-before-any-write acceptance criterion is unaffected)
// to learn its true sha256/size, from which the id and a manifest are
// built; a second pass — `ImmutableModelStore.import`'s own, unchanged,
// single hashing pass — performs the real copy. A picked file's bytes are
// therefore read from its `content://` grant twice, not once as the
// pre-amendment bead text's "one pass" described; the disk-write pass
// stays exactly one pass, and any inconsistency between the two reads
// (a shape-shifting provider) is caught as an ordinary `HashMismatch`/
// `SizeMismatch` by the second pass, which is the right outcome for that
// case regardless.

package app.skein.core.inference.models

import android.net.Uri
import app.skein.core.inference.InferenceConfig
import app.skein.core.model.Capability
import app.skein.core.model.Hex
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelId
import app.skein.core.model.ModelOrigin
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import app.skein.core.model.SkeinLog
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.ipc.ErrorCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Imports, verifies, registers and defaults models — the H0-amended
 * `skein-cyq`. See this file's header for the pipeline each [ImportSource]
 * runs.
 *
 * @param registry the CRUD + default-pointer store (`:core:vault`'s
 *   `ModelRegistryImpl` in production, `InMemoryModelRegistry` in tests).
 * @param store the immutable, hash-verified on-disk store (EXISTS,
 *   unchanged by this bead).
 * @param modelInspector the `:inference`-backed seam `ImportSource.Picked`/
 *   `HubOffer` use to learn what a GGUF is, without this module ever
 *   parsing one (`docs/design/SKEIN_HUB.md` §3.3).
 * @param pickedFileReader the `ContentResolver`-backed source for
 *   `ImportSource.Picked` (`ContentResolverPickedFileReader` in
 *   production).
 * @param bundledSource reads a shipped default model's bytes for
 *   `ImportSource.Bundled` (an `AssetManager`-backed `ModelBytesSource` in
 *   production — construction is the app-composition-root's job, out of
 *   this bead's scope).
 * @param freeBytes bytes free on the store's volume, checked before any
 *   write. Injected so tests can simulate low disk space without touching
 *   a real filesystem.
 * @param isLoaded whether the inference engine currently has [ModelId]
 *   loaded. Injected because `ModelManager` has no dependency on the
 *   engine (`:core:inference`'s own module boundary; the engine lives in
 *   `skein-1uw`'s scope) — `delete` consults this to refuse deleting the
 *   loaded model, per this bead's fifth acceptance criterion.
 * @param config supplies [InferenceConfig.contextLengthCap], the clamp a
 *   generated manifest's `context_length` never exceeds.
 * @param clock `Model.importedAt`'s source. Injectable for tests.
 */
public class ModelManager(
    private val registry: ModelRegistry,
    private val store: ImmutableModelStore,
    private val modelInspector: ModelInspector,
    private val pickedFileReader: PickedFileReader,
    private val bundledSource: ModelBytesSource,
    private val freeBytes: () -> Long,
    private val isLoaded: (ModelId) -> Boolean,
    private val config: InferenceConfig = InferenceConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Where [import] does its work. Both passes are blocking reads of the
     * whole artifact (a SHA-256 pass, then the hashed copy into the store);
     * on the Fold the caller was a `rememberCoroutineScope` on Main, so a
     * 1.6 GB import froze rendering for minutes (the owner saw a black
     * screen after the picker closed) and was swiped away as "stuck".
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Imports [source], reporting progress as it goes and terminating with
     * exactly one [ImportProgress.Done].
     *
     * PROGRESS: continuous, without touching [ImmutableModelStore.import]
     * (EXISTS/unchanged per `docs/design/SKEIN_HUB.md` §3.1 step 3) — the
     * bytes are counted on the way *in*, by a counting stream wrapped
     * around the [ModelBytesSource] the store reads from, plus the hash
     * pass's own loop. A picked import's total is twice its size (hash
     * pass, then copy pass); a bundled import's is its size. Ticks are
     * throttled to 1 % and the tick emitted immediately before
     * [ImportProgress.Done] on every successful import reads
     * `bytesProcessed == totalBytes`. The whole flow runs on [io].
     */
    public fun import(source: ImportSource): Flow<ImportProgress> =
        channelFlow {
            send(ImportProgress.InProgress(bytesProcessed = 0L, totalBytes = declaredSizeHintOf(source)))
            val reporter = ProgressReporter(this)
            val outcome =
                when (source) {
                    is ImportSource.Bundled -> importBundled(source.manifest, reporter)
                    is ImportSource.Picked -> importPicked(source.uri, reporter)
                    is ImportSource.HubOffer -> ImportOutcome.Refused(ImportRefusal.Unsupported)
                }
            if (outcome is ImportOutcome.Imported) {
                // Whole-import total: for a picked file that is two passes
                // over the artifact (hash, then copy), so the final tick is
                // (2·size, 2·size); a bundled import has only the copy.
                val total = reporter.totalBytes.takeIf { it > 0 } ?: outcome.record.model.sizeBytes
                send(ImportProgress.InProgress(bytesProcessed = total, totalBytes = total))
            }
            if (outcome is ImportOutcome.Refused) {
                // Kind and reason only — no path, no model content (spec §9).
                // The Fold's first refused import left nothing in logcat at
                // all, which is why this line exists.
                SkeinLog.w(TAG, "import refused: ${outcome.refusal.describe()}")
            }
            send(ImportProgress.Done(outcome))
        }.buffer(PROGRESS_BUFFER_TICKS).flowOn(io)

    private fun declaredSizeHintOf(source: ImportSource): Long =
        when (source) {
            is ImportSource.Bundled -> source.manifest.main.sizeBytes
            is ImportSource.Picked -> UNKNOWN_TOTAL_BYTES
            is ImportSource.HubOffer -> source.offer.declaredSizeBytes
        }

    public suspend fun delete(id: ModelId): DeleteOutcome {
        if (isLoaded(id)) return DeleteOutcome.Refused(DeleteRefusalReason.Loaded)
        if (registry.get(id) == null) return DeleteOutcome.NotFound

        val storeResult = store.delete(id)
        if (storeResult is ModelVerification.Refusal) {
            return DeleteOutcome.Refused(DeleteRefusalReason.FromStore(storeResult))
        }

        registry.delete(id)
        if (registry.default() == id) registry.setDefault(null)
        return DeleteOutcome.Deleted
    }

    public suspend fun setDefault(id: ModelId?) {
        registry.setDefault(id)
    }

    public suspend fun default(): ModelId? = registry.default()

    /**
     * skein-r8ah's fourth assertion (OL-35/PP-73): a registry row whose
     * recorded [app.skein.core.model.Model.path] no longer resolves is
     * re-anchored against the store's own current location for that id,
     * or reported — never silently deregistered. [ImmutableModelStore.open]
     * finds a model purely from `<modelsRoot>/<id>/`, independent of any
     * in-memory bookkeeping, so it is the right source of truth to
     * re-anchor against; it is released immediately after, since this call
     * only needs the path, not a held lock.
     *
     * Call this wherever a caller lists models for display (a model-manager
     * screen, onboarding) — this bead does not itself call it on a
     * schedule.
     */
    public suspend fun reconcilePaths(): List<PathReconciliation> {
        val results = mutableListOf<PathReconciliation>()
        for (record in registry.list()) {
            val id = record.model.id
            if (File(record.model.path).exists()) continue
            when (val opened = store.open(id)) {
                is OpenResult.Opened -> {
                    try {
                        val canonicalPath = opened.handle.main.path.absolutePath
                        registry.upsert(record.copy(model = record.model.copy(path = canonicalPath)))
                        results += PathReconciliation.ReAnchored(id, canonicalPath)
                    } finally {
                        opened.handle.release()
                    }
                }
                is OpenResult.Refused -> results += PathReconciliation.Missing(id)
            }
        }
        return results
    }

    // ------------------------------------------------------------------
    // ImportSource.Bundled — manifest already fully declared.
    // ------------------------------------------------------------------

    private suspend fun importBundled(
        manifest: ModelManifest,
        reporter: ProgressReporter,
    ): ImportOutcome {
        sizeRefusal(manifest.main.sizeBytes)?.let { return ImportOutcome.Refused(it) }
        spaceRefusal(manifest.main.sizeBytes)?.let { return ImportOutcome.Refused(it) }
        reporter.totalBytes = manifest.main.sizeBytes

        val storedModel =
            when (val result = store.import(manifest, countingSource(bundledSource, reporter))) {
                is ImportResult.Refused -> return ImportOutcome.Refused(ImportRefusal.FromStore(result.refusal))
                is ImportResult.Imported -> result.model
            }

        val mainPath = storedModel.main.path.absolutePath
        val companionPaths =
            storedModel.files
                .filterKeys { it != ModelFileRole.MAIN }
                .mapNotNull { (role, file) -> role.companionRole?.let { it to file.path.absolutePath } }
                .toMap()

        val model = manifest.toModel(mainPath, companionPaths).copy(importedAt = clock())
        val record =
            ModelRecord(
                model = model,
                blake3 = storedModel.main.blake3,
                origin = ModelOrigin.BUNDLED,
                sourceUrl = manifest.source?.url,
                sourceRevision = manifest.source?.revision,
                licenseSpdx = manifest.license.spdx,
            )
        registry.upsert(record)
        return ImportOutcome.Imported(record)
    }

    // ------------------------------------------------------------------
    // ImportSource.Picked — no manifest; generated from a real inspection.
    // ------------------------------------------------------------------

    private suspend fun importPicked(
        uri: Uri,
        reporter: ProgressReporter,
    ): ImportOutcome {
        val handle =
            try {
                pickedFileReader.open(uri)
            } catch (e: IOException) {
                return ImportOutcome.Refused(ImportRefusal.SourceUnavailable(e.message ?: "I/O error"))
            } catch (e: SecurityException) {
                return ImportOutcome.Refused(
                    ImportRefusal.SourceUnavailable(e.message ?: "permission grant unavailable"),
                )
            }

        val declaredSize = handle.sizeBytes
        val displayName = handle.displayName
        if (declaredSize == null) {
            handle.close()
            return ImportOutcome.Refused(ImportRefusal.SourceMetadataUnavailable)
        }

        // Fast bounds check on the claimed size — before any real work, let
        // alone any write.
        sizeRefusal(declaredSize)?.let {
            handle.close()
            return ImportOutcome.Refused(it)
        }
        spaceRefusal(declaredSize)?.let {
            handle.close()
            return ImportOutcome.Refused(it)
        }

        // Pass 1: hash-only, read-only. No disk write happens here — see
        // this file's header for why a second read is the right trade-off.
        // Progress counts both passes against twice the declared size.
        reporter.totalBytes = declaredSize * 2
        val (sha256, observedSize) = hashOnly(handle, reporter)

        // The provider's declared size may have lied; re-check the size
        // this pass actually observed, still before any write.
        sizeRefusal(observedSize)?.let { return ImportOutcome.Refused(it) }

        val id = deriveId(displayName, sha256)
        val manifest =
            ModelManifest(
                id = id,
                manifestVersion = ModelManifest.SUPPORTED_VERSION,
                name = displayName?.takeIf { it.isNotBlank() } ?: id,
                format = ModelFormat.GGUF,
                capabilities = setOf(Capability.TEXT),
                license = ManifestLicense(spdx = UNKNOWN_LICENSE),
                main =
                    ManifestFile(
                        role = ModelFileRole.MAIN,
                        file = GENERATED_MAIN_FILE_NAME,
                        sha256 = sha256,
                        sizeBytes = observedSize,
                    ),
                companions = emptyList(),
            )

        // Pass 2: the real, hashed, single-pass copy into the store —
        // ImmutableModelStore.import, unchanged.
        val storedModel =
            when (val result = store.import(manifest, pickedBytesSource(uri, reporter))) {
                is ImportResult.Refused -> return ImportOutcome.Refused(ImportRefusal.FromStore(result.refusal))
                is ImportResult.Imported -> result.model
            }

        return finishGeneratedImport(
            manifest = manifest,
            storedModel = storedModel,
            displayName = displayName,
            origin = ModelOrigin.PICKED,
            sourceUrl = null,
            sourceRevision = null,
        )
    }

    /** The bounded pre-check, then the real inspection, then registration. Shared by every "no manifest" source. */
    private suspend fun finishGeneratedImport(
        manifest: ModelManifest,
        storedModel: StoredModel,
        displayName: String?,
        origin: ModelOrigin,
        sourceUrl: String?,
        sourceRevision: String?,
    ): ImportOutcome {
        val preCheckRefusal = runPreCheck(manifest.id)
        if (preCheckRefusal != null) {
            store.delete(manifest.id)
            return ImportOutcome.Refused(ImportRefusal.StructurallyInvalid(preCheckRefusal))
        }

        val binding =
            when (val bound = ManifestBinding.bind(manifest, storedModel)) {
                is BindResult.Refused -> {
                    store.delete(manifest.id)
                    return ImportOutcome.Refused(ImportRefusal.FromStore(bound.refusal))
                }
                is BindResult.Bound -> bound.binding
            }

        val inspection = modelInspector.inspect(binding)
        if (inspection.errorCode != ErrorCode.OK) {
            store.delete(manifest.id)
            return ImportOutcome.Refused(ImportRefusal.InspectionFailed(inspection.errorCode))
        }

        val capabilities =
            buildSet {
                add(Capability.TEXT)
                // "mmproj companion -> vision": no picker in this bead's
                // scope hands ModelManager a companion alongside a Picked
                // main file (no multi-file SAF precedent exists in this
                // codebase to build one against; see the class header's
                // Picked section). hasVision alone — a property the GGUF
                // itself declares — still gates the capability; actually
                // using it without the companion is `skein-m6v`'s (E4.I11)
                // concern, at load time, not at import time.
                //
                // "pooling/embedding width -> embedding": NOT implemented.
                // The landed `ModelInspection` (skein-91yy, H1, locked —
                // this bead may not touch `:core:ipc`) carries no
                // pooling-type field, only `embeddingWidth`, which every
                // GGUF has regardless of role and so cannot discriminate
                // "this is an embedding model." Deviation recorded here
                // rather than a heuristic fabricated over a field that
                // means something else.
                if (inspection.hasVision) add(Capability.VISION)
            }
        val contextLength =
            (inspection.contextLength ?: manifest.contextLength).coerceAtMost(config.contextLengthCap)

        val model =
            Model(
                id = manifest.id,
                name = manifest.name,
                path = storedModel.main.path.absolutePath,
                sha256 = storedModel.main.sha256,
                format = ModelFormat.GGUF,
                capabilities = capabilities,
                sizeBytes = storedModel.main.sizeBytes,
                contextLength = contextLength,
                attestationUrl = null,
                companions = emptyMap(),
                importedAt = clock(),
            )
        val record =
            ModelRecord(
                model = model,
                blake3 = storedModel.main.blake3,
                origin = origin,
                sourceUrl = sourceUrl,
                sourceRevision = sourceRevision,
                licenseSpdx = UNKNOWN_LICENSE,
            )
        registry.upsert(record)
        return ImportOutcome.Imported(record)
    }

    private fun runPreCheck(id: ModelId): GgufPreCheckResult.Reason? {
        val handle =
            when (val opened = store.open(id)) {
                is OpenResult.Opened -> opened.handle
                // The store itself refused to open what it just imported —
                // not this check's failure to report; let the caller's
                // subsequent steps (which also touch the store) surface it.
                is OpenResult.Refused -> return null
            }
        return try {
            when (val result = GgufPreCheck.check(handle.mainChannel)) {
                is GgufPreCheckResult.Plausible -> null
                is GgufPreCheckResult.Refused -> result.reason
            }
        } finally {
            handle.release()
        }
    }

    private fun pickedBytesSource(
        uri: Uri,
        reporter: ProgressReporter,
    ): ModelBytesSource =
        ModelBytesSource { _ ->
            CountingInputStream(pickedFileReader.open(uri).stream, reporter::advance)
        }

    private fun countingSource(
        inner: ModelBytesSource,
        reporter: ProgressReporter,
    ): ModelBytesSource =
        ModelBytesSource { file ->
            inner.open(file)?.let { CountingInputStream(it, reporter::advance) }
        }

    private fun hashOnly(
        handle: PickedFileHandle,
        reporter: ProgressReporter,
    ): Pair<String, Long> =
        handle.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = it.stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
                reporter.advance(read)
            }
            Hex.encode(digest.digest()) to total
        }

    /**
     * Throttled progress from the blocking read loops into the flow: at most
     * one tick per 1 % of [totalBytes] (or per [REPORT_STEP_BYTES] while the
     * total is unknown). Single producer — every read loop runs on the flow's
     * own [io] coroutine — so no synchronisation is needed; `trySend` never
     * blocks a read, and a dropped tick only costs a little smoothness.
     */
    private class ProgressReporter(
        private val scope: ProducerScope<ImportProgress>,
    ) {
        var totalBytes: Long = UNKNOWN_TOTAL_BYTES
        private var processed = 0L
        private var lastReported = 0L

        fun advance(bytes: Int) {
            processed += bytes
            val step = if (totalBytes > 0) maxOf(totalBytes / 100, 1L) else REPORT_STEP_BYTES
            if (processed - lastReported >= step) {
                lastReported = processed
                scope.trySend(ImportProgress.InProgress(bytesProcessed = processed, totalBytes = totalBytes))
            }
        }
    }

    /** Wraps [inner] and reports every byte that passes through to [onBytes]. */
    private class CountingInputStream(
        private val inner: InputStream,
        private val onBytes: (Int) -> Unit,
    ) : InputStream() {
        override fun read(): Int = inner.read().also { if (it >= 0) onBytes(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = inner.read(b, off, len).also { if (it > 0) onBytes(it) }

        override fun available(): Int = inner.available()

        override fun close(): Unit = inner.close()
    }

    private fun sizeRefusal(sizeBytes: Long): ImportRefusal.ArtifactTooLarge? =
        if (sizeBytes < 1 || sizeBytes > MAX_ARTIFACT_BYTES) ImportRefusal.ArtifactTooLarge(sizeBytes) else null

    private fun spaceRefusal(sizeBytes: Long): ImportRefusal.InsufficientSpace? {
        val required = requiredFreeBytes(sizeBytes)
        val available = freeBytes()
        return if (available < required) ImportRefusal.InsufficientSpace(required, available) else null
    }

    private fun deriveId(
        displayName: String?,
        sha256: String,
    ): String {
        val suffix = sha256.take(ID_HASH_SUFFIX_LENGTH)
        val slug = slugify(displayName ?: "model")
        val candidate = "$slug-$suffix".take(MAX_ID_LENGTH)
        return if (ID_PATTERN.matches(candidate)) candidate else "model-$suffix"
    }

    public companion object {
        private const val TAG = "ModelManager"

        /** `docs/design/SKEIN_HUB.md` §3.2: a declared size outside this range refuses before any allocation. */
        public const val MAX_ARTIFACT_BYTES: Long = 16L * 1024 * 1024 * 1024

        /** The fixed on-disk name for a generated (no-manifest) import's main file — see the class header. */
        public const val GENERATED_MAIN_FILE_NAME: String = "model.gguf"

        public const val UNKNOWN_LICENSE: String = "UNKNOWN"

        /** [ImportProgress.InProgress.totalBytes] when the total is not yet known (`ImportSource.Picked`'s first tick). */
        public const val UNKNOWN_TOTAL_BYTES: Long = -1L

        private const val HASH_BUFFER_BYTES = 1 shl 16
        private const val REPORT_STEP_BYTES = 8L shl 20

        /**
         * Room for every 1 % tick of an import plus its milestones, so a
         * collector that only runs when the producer suspends (a test's
         * `toList()`, a busy main thread) never sees `trySend` drop the
         * later ticks: the throttle bounds an import to ≤ 101 ticks.
         */
        private const val PROGRESS_BUFFER_TICKS = 128
        private const val ID_HASH_SUFFIX_LENGTH = 12
        private const val MAX_ID_LENGTH = 64
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9.-]{2,63}$")

        /** `ceil(sizeBytes * 1.05)`, via integer arithmetic to avoid floating-point drift on the boundary. */
        internal fun requiredFreeBytes(sizeBytes: Long): Long {
            val margin = (sizeBytes + 19) / 20 // ceil(sizeBytes * 0.05)
            return sizeBytes + margin
        }

        /** Lossy, deliberately simple: lowercase, extension dropped, anything outside `[a-z0-9.-]` becomes `-`, runs collapsed. */
        internal fun slugify(raw: String): String {
            val withoutExtension = raw.substringBeforeLast('.', raw)
            val mapped =
                withoutExtension
                    .lowercase()
                    .map { c ->
                        if (c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-') c else '-'
                    }.joinToString("")
            val collapsed = Regex("-+").replace(mapped, "-").trim('-')
            return collapsed.ifBlank { "model" }
        }
    }
}

/**
 * One event of [ModelManager.import]'s progress stream — see that
 * function's KDoc for the granularity this actually reports.
 */
public sealed interface ImportProgress {
    public data class InProgress(
        val bytesProcessed: Long,
        val totalBytes: Long,
    ) : ImportProgress

    /** Terminal. Exactly one per [ModelManager.import] call, always the flow's last element. */
    public data class Done(
        val outcome: ImportOutcome,
    ) : ImportProgress
}

/** Outcome of [ModelManager.import]. */
public sealed interface ImportOutcome {
    public data class Imported(
        val record: ModelRecord,
    ) : ImportOutcome

    public data class Refused(
        val refusal: ImportRefusal,
    ) : ImportOutcome
}

/** Every way [ModelManager.import] can refuse. */
public sealed interface ImportRefusal {
    /** `freeBytes() < size * 1.05`, checked before any write. */
    public data class InsufficientSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : ImportRefusal

    /** A declared size outside `1..MAX_ARTIFACT_BYTES` (`docs/design/SKEIN_HUB.md` §3.2, H0 amendment 4). */
    public data class ArtifactTooLarge(
        val declaredSizeBytes: Long,
    ) : ImportRefusal

    /** Hub's declared transfer digest disagreed with what Core copied (H0 amendment 4; not reachable until H5). */
    public data class TransferDigestMismatch(
        val expected: String,
        val actual: String,
    ) : ImportRefusal

    /** [GgufPreCheck] refused before `ModelInspector.inspect` was ever called. */
    public data class StructurallyInvalid(
        val reason: GgufPreCheckResult.Reason,
    ) : ImportRefusal

    /** `ModelInspector.inspect` returned a non-OK `ErrorCode` (`app.skein.ipc.ErrorCode`). */
    public data class InspectionFailed(
        val errorCode: Int,
    ) : ImportRefusal

    /** The picked file's stream could not be opened (a revoked grant surfaces here, never as a crash). */
    public data class SourceUnavailable(
        val message: String,
    ) : ImportRefusal

    /** The picked file's provider reported no size — nothing to check the free-space/ceiling rules against. */
    public data object SourceMetadataUnavailable : ImportRefusal

    /** `ImportSource.HubOffer` before `skein-twn1` (H5) implements it. */
    public data object Unsupported : ImportRefusal

    /** [ImmutableModelStore]/[ManifestBinding] itself refused — carries the exact [ModelVerification.Refusal], e.g. `HashMismatch`. */
    public data class FromStore(
        val refusal: ModelVerification.Refusal,
    ) : ImportRefusal
}

/**
 * A one-line, content-free description of an [ImportRefusal] for logs and
 * the status row: the refusal kind plus the reason it carries (a pre-check
 * reason name, a store refusal summary, an inspection error code) — never a
 * path, never bytes of the file.
 */
public fun ImportRefusal.describe(): String =
    when (this) {
        is ImportRefusal.StructurallyInvalid -> "StructurallyInvalid(${reason.name})"
        is ImportRefusal.FromStore -> "FromStore(${refusal.summary})"
        is ImportRefusal.InspectionFailed -> "InspectionFailed($errorCode)"
        else -> javaClass.simpleName
    }

/** Outcome of [ModelManager.delete]. */
public sealed interface DeleteOutcome {
    public data object Deleted : DeleteOutcome

    public data object NotFound : DeleteOutcome

    public data class Refused(
        val reason: DeleteRefusalReason,
    ) : DeleteOutcome
}

public sealed interface DeleteRefusalReason {
    /** `isLoaded(id)` was true — this bead's fifth acceptance criterion. */
    public data object Loaded : DeleteRefusalReason

    public data class FromStore(
        val refusal: ModelVerification.Refusal,
    ) : DeleteRefusalReason
}

/** Outcome of [ModelManager.reconcilePaths], one per stale row it examined. */
public sealed interface PathReconciliation {
    public data class ReAnchored(
        val id: ModelId,
        val newPath: String,
    ) : PathReconciliation

    /** The store has no model at this id either — reported, never deregistered. */
    public data class Missing(
        val id: ModelId,
    ) : PathReconciliation
}
