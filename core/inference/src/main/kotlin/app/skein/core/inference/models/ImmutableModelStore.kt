// skein-st1r (POST_REVIEW_RESOLUTIONS §2.2, rule 1): the immutable model
// store.
//
// The review's first finding was that holding an open fd preserves file
// *identity*, not file *content* — any process with write access to the same
// inode can still change the bytes under a mapping. The mitigation is layered,
// and this file owns the bottom three layers:
//
//   1. Location. Model bytes are copied out of the user-writable SAF source
//      into `filesDir/models/<id>/`. On Android that directory is app-private:
//      no other app and no other user can open it at all without root
//      (https://developer.android.com/training/data-storage/app-specific).
//   2. Permissions. After a successful import each file is set to `0400` and
//      the directory to `0500`, so even code inside our own app fails an
//      `openat(O_WRONLY)` with `EACCES`.
//   3. Locking. While a model is open, `ImmutableModelStore` holds a shared
//      `FileChannel` lock on the main file for the model's whole lifetime, and
//      refuses re-import or delete over that id with `ModelVerification.InUse`.
//
// What each layer does NOT do, stated plainly because the review asked for it:
//
//   * `filesDir` keeps out other apps and other Android users. It does not
//     keep out root, a custom recovery, or a physically-extracted image.
//   * `0400` keeps out our own code paths, and it is owner-settable: the same
//     UID can `chmod` it back. It raises the bar and makes accidental
//     in-place writes impossible; it is not a capability boundary.
//   * `FileChannel` locks on Linux are ADVISORY. They stop cooperating
//     writers (every writer in this codebase) and coordinate our own
//     processes; a hostile writer that ignores them is unaffected.
//
// Precisely because none of the three is airtight, §2.2 adds the post-mmap
// BLAKE3 pass in `ModelVerifier`: detection of an in-place write does not
// depend on any of these preventions holding.

package app.skein.core.inference.models

import app.skein.core.model.SkeinLog
import app.skein.core.verify.DigestAccumulator
import app.skein.core.verify.DigestAlgorithm
import app.skein.core.verify.FileDigests
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

private const val TAG = "ImmutableModelStore"

/** How much of the read-only contract the filesystem underneath the store actually enforced. */
enum class PermissionEnforcement {
    /** `Files.setPosixFilePermissions` applied `0400`/`0500` — the Android `filesDir` case. */
    POSIX,

    /**
     * The filesystem has no POSIX attribute view, so the store fell back to
     * `File.setWritable(false)`. Coarser (no distinction between owner and
     * group) but still removes the write bit. Seen on some JVM test
     * filesystems; not expected on a device.
     */
    FILE_API_FALLBACK,
}

/** One imported, digest-verified file inside the store. */
data class StoredFile(
    val role: ModelFileRole,
    val path: File,
    val sha256: String,
    /**
     * BLAKE3-256 of the same bytes, computed during the import stream.
     *
     * Persisted by `:app` into `models.post_mmap_blake3` (migration 004); it
     * is the expectation the post-mmap gate compares against for a
     * user-imported model whose manifest declares no `blake3`.
     */
    val blake3: String,
    val sizeBytes: Long,
)

/** An imported model: its directory, its verified files, and what the filesystem enforced. */
data class StoredModel(
    val id: String,
    val directory: File,
    val files: Map<ModelFileRole, StoredFile>,
    val permissionEnforcement: PermissionEnforcement,
) {
    val main: StoredFile get() = files.getValue(ModelFileRole.MAIN)
}

/** Supplies the bytes for one manifest entry. Returns null when the caller has no such file. */
fun interface ModelBytesSource {
    @Throws(IOException::class)
    fun open(file: ManifestFile): InputStream?
}

/** Result of [ImmutableModelStore.import]. */
sealed interface ImportResult {
    data class Imported(
        val model: StoredModel,
    ) : ImportResult

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : ImportResult
}

/** Result of [ImmutableModelStore.open]. */
sealed interface OpenResult {
    data class Opened(
        val handle: ModelHandle,
    ) : OpenResult

    data class Refused(
        val refusal: ModelVerification.Refusal,
    ) : OpenResult
}

/**
 * A model held open for loading.
 *
 * Holding a handle means the shared read lock on the main file is held. The
 * handle is reference-counted per model id: two loaders in the same process
 * share one lock (a second `tryLock` on the same file from the same JVM would
 * raise `OverlappingFileLockException`, since NIO file locks are per-JVM, not
 * per-channel), and the lock is released when the last handle closes.
 */
class ModelHandle internal constructor(
    val modelId: String,
    val model: StoredModel,
    private val entry: LockedModel,
    private val onRelease: (ModelHandle) -> Unit,
) : Closeable {
    private var released = false

    val main: StoredFile get() = model.main

    /** The channel the shared lock is held on. The post-mmap gate maps this exact channel. */
    val mainChannel: FileChannel get() = entry.channel

    /** True while this handle still participates in the shared lock. */
    val isOpen: Boolean get() = !released

    fun release() {
        if (released) return
        released = true
        onRelease(this)
    }

    override fun close() = release()
}

/** Internal per-id lock state: one channel + one shared lock, reference-counted across handles. */
internal class LockedModel(
    val stream: FileInputStream,
    val channel: FileChannel,
    val lock: FileLock,
) {
    var handles: Int = 0
}

/**
 * The immutable model store rooted at [modelsRoot] (in production,
 * `context.filesDir/models`).
 *
 * The in-memory [registry] of imported models stands in for the `models`
 * table: `:app` rehydrates it from the database at start-up and the store
 * itself never persists metadata into the model directory — a metadata file
 * living beside the model would be a file the manifest does not cover, which
 * `ManifestBinding` would (correctly) refuse.
 */
class ImmutableModelStore(
    private val modelsRoot: File,
) {
    private val monitor = Any()
    private val registry = mutableMapOf<String, StoredModel>()
    private val openModels = mutableMapOf<String, LockedModel>()

    /** Registers a model that was imported in an earlier process lifetime (rehydration from `models`). */
    fun register(model: StoredModel) {
        synchronized(monitor) { registry[model.id] = model }
    }

    fun stored(id: String): StoredModel? = synchronized(monitor) { registry[id] }

    /**
     * Directories under the store root that hold a promoted `[mainFileName]`
     * and no `*.tmp` leftovers, yet are unknown to this instance — a finished
     * import whose registration never landed (the Fold: the import coroutine
     * was cancelled by a vault lock after the copy). `ModelManager.adoptOrphans`
     * turns these back into registry rows; nothing else reads them.
     */
    fun orphanedDirectories(mainFileName: String): List<File> =
        synchronized(monitor) {
            (modelsRoot.listFiles() ?: emptyArray())
                .filter { it.isDirectory && isSafeId(it.name) && !registry.containsKey(it.name) }
                .filter { dir -> File(dir, mainFileName).isFile && !isStaleStaging(dir) }
                .filter { dir -> dir.listFiles()?.none { it.name.endsWith(TEMP_SUFFIX) } ?: false }
                .sortedBy { it.name }
        }

    /**
     * Registers a sealed, already-copied directory as if it had just been
     * imported: the caller has re-hashed `[mainFileName]` (SHA-256) and
     * passes what it measured; BLAKE3 is left empty (no post-mmap
     * expectation, exactly like a rehydrated row without one). Sealing is
     * re-applied — it is idempotent on an already-sealed directory.
     */
    fun adoptSealed(
        id: String,
        mainFileName: String,
        sha256: String,
        sizeBytes: Long,
    ): AdoptResult {
        if (!isSafeId(
                id,
            )
        ) {
            return AdoptResult.Refused(
                ModelVerification.MalformedManifest("model id is not a plain directory name"),
            )
        }
        val directory = File(modelsRoot, id)
        val main = File(directory, mainFileName)
        if (!main.isFile) return AdoptResult.Refused(ModelVerification.FileMissing(ModelFileRole.MAIN))
        synchronized(monitor) {
            if (registry.containsKey(id)) return AdoptResult.Refused(ModelVerification.AlreadyImported(id))
        }
        var enforcement = seal(main, FILE_MODE_0400)
        enforcement = weakest(enforcement, seal(directory, DIRECTORY_MODE_0500))
        val stored = StoredFile(ModelFileRole.MAIN, main, sha256, blake3 = "", sizeBytes)
        val model = StoredModel(id, directory, mapOf(ModelFileRole.MAIN to stored), enforcement)
        synchronized(monitor) { registry[id] = model }
        SkeinLog.i(TAG, "adopted a sealed model directory enforcement=$enforcement")
        return AdoptResult.Adopted(model)
    }

    sealed interface AdoptResult {
        data class Adopted(
            val model: StoredModel,
        ) : AdoptResult

        data class Refused(
            val refusal: ModelVerification.Refusal,
        ) : AdoptResult
    }

    fun isOpen(id: String): Boolean = synchronized(monitor) { openModels.containsKey(id) }

    /** True when [directory] holds no promoted file — only `*.tmp` leftovers, or nothing at all. */
    private fun isStaleStaging(directory: File): Boolean =
        directory.listFiles()?.none { it.isFile && !it.name.endsWith(TEMP_SUFFIX) } ?: true

    /**
     * Imports every file [manifest] declares into `modelsRoot/<id>/`,
     * verifying SHA-256 (and BLAKE3, when the manifest declares it) while
     * streaming, then sealing the directory read-only.
     *
     * Nothing is left behind on refusal: the whole `<id>` directory is removed,
     * so a failed import cannot leave a half-written file that a later code
     * path mistakes for a verified one.
     */
    @Suppress("ReturnCount")
    fun import(
        manifest: ModelManifest,
        source: ModelBytesSource,
    ): ImportResult {
        val id = manifest.id
        if (!isSafeId(id)) {
            return ImportResult.Refused(ModelVerification.MalformedManifest("model id is not a plain directory name"))
        }
        val directory = File(modelsRoot, id)
        synchronized(monitor) {
            // §2.2: a fresh id is mandatory while the old one is loaded, and
            // re-import over an idle id is refused too — delete first.
            if (openModels.containsKey(id)) return ImportResult.Refused(ModelVerification.InUse(id))
            if (registry.containsKey(id)) return ImportResult.Refused(ModelVerification.AlreadyImported(id))
            if (directory.exists()) {
                // A directory this instance does not know is either a finished
                // import from an earlier process (a promoted file is present:
                // refuse, exactly as before) or the staging directory of an
                // import the process died in the middle of - only `*.tmp`
                // leftovers, or nothing at all. The Fold produced the second
                // kind when the app was swiped away mid-copy, and every later
                // import of the same file was then refused AlreadyImported.
                // Stale staging is removed here and the import proceeds.
                if (!isStaleStaging(directory)) return ImportResult.Refused(ModelVerification.AlreadyImported(id))
                SkeinLog.w(TAG, "removing a staging directory left by an interrupted import")
                deleteTree(directory)
            }
        }

        if (!directory.mkdirs()) {
            return ImportResult.Refused(ModelVerification.IoFailure(null, "mkdir"))
        }
        val staged = mutableMapOf<ModelFileRole, StagedFile>()
        val refusal = stageAll(manifest, source, directory, staged)
        if (refusal != null) {
            deleteTree(directory)
            SkeinLog.w(TAG, "import refused: ${refusal.summary}")
            return ImportResult.Refused(refusal)
        }

        val promoted = mutableMapOf<ModelFileRole, StoredFile>()
        try {
            for ((role, stage) in staged) {
                Files.move(stage.temporary.toPath(), stage.target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                promoted[role] = StoredFile(role, stage.target, stage.sha256, stage.blake3, stage.sizeBytes)
            }
        } catch (e: IOException) {
            deleteTree(directory)
            SkeinLog.w(TAG, "import refused: atomic rename failed", e)
            return ImportResult.Refused(ModelVerification.IoFailure(null, "rename"))
        }

        // Seal files first, directory last: sealing the directory to 0500
        // removes the write bit needed to rename into it.
        var enforcement = PermissionEnforcement.POSIX
        for (file in promoted.values) {
            enforcement = weakest(enforcement, seal(file.path, FILE_MODE_0400))
        }
        enforcement = weakest(enforcement, seal(directory, DIRECTORY_MODE_0500))

        val model = StoredModel(id, directory, promoted.toMap(), enforcement)
        synchronized(monitor) { registry[id] = model }
        SkeinLog.i(TAG, "imported model files=${promoted.size} enforcement=$enforcement")
        return ImportResult.Imported(model)
    }

    /**
     * Takes (or joins) the shared read lock on [id]'s main file and returns a
     * handle for the loader.
     *
     * The lock is held for the model's lifetime, which is what makes
     * [ModelVerification.InUse] answerable for re-import and delete.
     */
    @Suppress("ReturnCount")
    fun open(id: String): OpenResult {
        synchronized(monitor) {
            val model = registry[id] ?: return OpenResult.Refused(ModelVerification.FileMissing(ModelFileRole.MAIN))
            val existing = openModels[id]
            if (existing != null) {
                existing.handles++
                return OpenResult.Opened(ModelHandle(id, model, existing, ::releaseHandle))
            }
            val stream =
                try {
                    FileInputStream(model.main.path)
                } catch (e: IOException) {
                    SkeinLog.w(TAG, "open refused: cannot read main file", e)
                    return OpenResult.Refused(ModelVerification.IoFailure(ModelFileRole.MAIN, "open"))
                }
            val channel = stream.channel
            val lock =
                try {
                    channel.tryLock(0L, Long.MAX_VALUE, true)
                } catch (e: OverlappingFileLockException) {
                    SkeinLog.w(TAG, "open refused: lock already held in this JVM", e)
                    null
                } catch (e: IOException) {
                    SkeinLog.w(TAG, "open refused: lock failed", e)
                    null
                }
            if (lock == null) {
                stream.close()
                return OpenResult.Refused(ModelVerification.InUse(id))
            }
            val locked = LockedModel(stream, channel, lock)
            locked.handles = 1
            openModels[id] = locked
            return OpenResult.Opened(ModelHandle(id, model, locked, ::releaseHandle))
        }
    }

    /** Deletes an imported model. Refuses with [ModelVerification.InUse] while any handle is open. */
    fun delete(id: String): ModelVerification {
        synchronized(monitor) {
            if (openModels.containsKey(id)) return ModelVerification.InUse(id)
            val model = registry.remove(id) ?: return ModelVerification.FileMissing(ModelFileRole.MAIN)
            deleteTree(model.directory)
            return ModelVerification.Verified
        }
    }

    private fun releaseHandle(handle: ModelHandle) {
        synchronized(monitor) {
            val locked = openModels[handle.modelId] ?: return
            locked.handles--
            if (locked.handles > 0) return
            openModels.remove(handle.modelId)
            runCatching { locked.lock.release() }
            runCatching { locked.channel.close() }
            runCatching { locked.stream.close() }
        }
    }

    // --------------------------------------------------------------- staging

    private class StagedFile(
        val temporary: File,
        val target: File,
        val sha256: String,
        val blake3: String,
        val sizeBytes: Long,
    )

    @Suppress("ReturnCount")
    private fun stageAll(
        manifest: ModelManifest,
        source: ModelBytesSource,
        directory: File,
        staged: MutableMap<ModelFileRole, StagedFile>,
    ): ModelVerification.Refusal? {
        for (entry in manifest.files) {
            val input =
                try {
                    source.open(entry)
                } catch (e: IOException) {
                    SkeinLog.w(TAG, "import: source failed role=${entry.role.wire}", e)
                    return ModelVerification.IoFailure(entry.role, "source")
                }
            if (input == null) {
                // A missing optional companion is simply not imported; a
                // missing required one is the review's companion-coverage hole.
                if (entry.required) return ModelVerification.CompanionMissing(entry.role, entry.file)
                continue
            }
            val temporary = File(directory, entry.file + TEMP_SUFFIX)
            val digests =
                try {
                    input.use { streamInto(it, temporary) }
                } catch (e: IOException) {
                    SkeinLog.w(TAG, "import: write failed role=${entry.role.wire}", e)
                    return ModelVerification.IoFailure(entry.role, "write")
                }
            if (digests.sizeBytes != entry.sizeBytes) return ModelVerification.SizeMismatch(entry.role)
            if (!ModelVerifier.constantTimeEquals(entry.sha256, digests.sha256)) {
                return ModelVerification.HashMismatch(entry.role, DigestAlgorithm.SHA256)
            }
            val declaredBlake3 = entry.blake3
            if (declaredBlake3 != null && !ModelVerifier.constantTimeEquals(declaredBlake3, digests.blake3)) {
                return ModelVerification.HashMismatch(entry.role, DigestAlgorithm.BLAKE3)
            }
            staged[entry.role] =
                StagedFile(temporary, File(directory, entry.file), digests.sha256, digests.blake3, digests.sizeBytes)
        }
        return null
    }

    /** Streams [input] to [target] once, computing both digests of §2's dual-hash discipline in the same pass. */
    private fun streamInto(
        input: InputStream,
        target: File,
    ): FileDigests {
        val accumulator = DigestAccumulator()
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(ModelVerifier.READ_CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                accumulator.update(buffer, read)
            }
            out.fd.sync()
        }
        return accumulator.finish()
    }

    // ----------------------------------------------------------- permissions

    private fun seal(
        target: File,
        mode: Set<PosixFilePermission>,
    ): PermissionEnforcement {
        val path = target.toPath()
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        return if (view != null) {
            view.setPermissions(mode)
            PermissionEnforcement.POSIX
        } else {
            target.setWritable(false, false)
            PermissionEnforcement.FILE_API_FALLBACK
        }
    }

    private fun weakest(
        a: PermissionEnforcement,
        b: PermissionEnforcement,
    ): PermissionEnforcement =
        if (a == PermissionEnforcement.FILE_API_FALLBACK || b == PermissionEnforcement.FILE_API_FALLBACK) {
            PermissionEnforcement.FILE_API_FALLBACK
        } else {
            PermissionEnforcement.POSIX
        }

    /** Restores the write bit before removing a sealed tree — `0500` forbids unlinking children. */
    private fun deleteTree(directory: File) {
        if (!directory.exists()) return
        runCatching {
            val view = Files.getFileAttributeView(directory.toPath(), PosixFileAttributeView::class.java)
            if (view != null) view.setPermissions(DIRECTORY_MODE_0700) else directory.setWritable(true, true)
        }
        directory.listFiles()?.forEach { child ->
            runCatching {
                val view = Files.getFileAttributeView(child.toPath(), PosixFileAttributeView::class.java)
                if (view != null) view.setPermissions(FILE_MODE_0600) else child.setWritable(true, true)
            }
            if (child.isDirectory) deleteTree(child) else child.delete()
        }
        directory.delete()
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() &&
            id != "." &&
            id != ".." &&
            !id.contains('/') &&
            !id.contains('\\') &&
            !id.contains('\u0000')

    private companion object {
        const val TEMP_SUFFIX = ".tmp"

        val FILE_MODE_0400: Set<PosixFilePermission> = setOf(PosixFilePermission.OWNER_READ)

        val FILE_MODE_0600: Set<PosixFilePermission> =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        val DIRECTORY_MODE_0500: Set<PosixFilePermission> =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)

        val DIRECTORY_MODE_0700: Set<PosixFilePermission> =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
    }
}
