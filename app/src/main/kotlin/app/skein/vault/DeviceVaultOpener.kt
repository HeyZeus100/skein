// skein-2ige — the on-device `openVault` for `VaultBootstrap`: `VaultLifecycle`
// (create on first run, open otherwise; key verification + migrations) and
// the SQL service implementations over connections drawn from it.
//
// Connections (skein-4qol): `VaultLifecycle` owns a `ConnectionPool` — one
// writer + N readers, each keyed from a single copy and carrying
// `busy_timeout` — built once `lifecycle.open`/`create` has proven the key
// and brought the schema up to date. Every `open()` call builds its OWN
// `VaultLifecycle` (skein-1bx4 — see `newLifecycle`), so the pool a session
// closes is always the pool that session opened. This file no longer constructs
// `SkeinSQLiteDriver` itself; it draws every service connection from
// `lifecycle.connectionPool()` instead. One connection per service (writer
// + readers for the repository, one each for the index store and the
// persona service, all distinct reader-pool slots): two services sharing a
// connection would interleave statements and transactions across two
// independent mutexes.
//
// Key discipline: `keyCopy()` is the only touch point — a fresh
// `currentKey().copyOf()` per `lifecycle.open`/`create` call, handed to a
// lifecycle that zeroes it (the pool itself further fans that one copy out
// per-connection and zeroes it once every connection has its own copy —
// see `ConnectionPool.open`'s KDoc); nothing here retains key bytes.
// `FileAttachmentStore` gets the same lambda (its contract: a fresh copy
// per call, wiped after the per-file HKDF).

package app.skein.vault

import android.content.Context
import app.skein.core.vault.blob.FileAttachmentStore
import app.skein.core.vault.db.SkeinSQLiteDriver
import app.skein.core.vault.db.migrations.Migrator
import app.skein.core.vault.export.ExportServiceImpl
import app.skein.core.vault.index.IndexStoreImpl
import app.skein.core.vault.key.VaultKeyProvider
import app.skein.core.vault.lifecycle.CreateResult
import app.skein.core.vault.lifecycle.OpenResult
import app.skein.core.vault.lifecycle.VaultLifecycle
import app.skein.core.vault.lifecycle.VaultPaths
import app.skein.core.vault.persona.PersonaServiceImpl
import app.skein.core.vault.repository.VaultRepositoryImpl
import app.skein.core.vault.transfer.ImportServiceImpl
import app.skein.models.ModelServices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opens the vault under [paths] with the key [keyProvider] currently holds
 * and builds a [VaultSession] over it. Every failure surfaces as a
 * [VaultOpenException] whose message is free of key material.
 */
class DeviceVaultOpener(
    private val keyProvider: VaultKeyProvider,
    private val paths: VaultPaths,
    private val attachmentsDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // E2.I8 (skein-qdo): threaded into `ImportServiceImpl` so `importPdf`'s
    // `PDFBoxResourceLoader.init` can see the real app `AssetManager`.
    // Optional/defaulted so existing call sites (and androidTest fixtures)
    // that construct this class without a context keep compiling — without
    // one, PDF import still works, just always falls back to the "no text
    // layer" notice (see `PdfImporter.extract`'s KDoc).
    private val context: Context? = null,
    // skein-whg8: the vault's live `SessionEpoch`, read fresh by
    // `LlamaCppEngine` on every request (see that class's own ctor doc).
    // Defaulted so every pre-existing call site (every test fixture in this
    // source set, none of which builds a [ModelServices]) keeps compiling.
    private val sessionEpoch: () -> Long = { 0L },
) {
    /**
     * A [VaultLifecycle] — and therefore a `ConnectionPool` — for ONE
     * [open] call (skein-1bx4).
     *
     * This used to be a single instance held for the life of the process,
     * shared by every session the process ever opened. `VaultSession.close`
     * ends in `lifecycle.close()`, so any late or leftover close (the one
     * `VaultBootstrap.LockHandler.onLocked` retries as a backstop, or a
     * close that overran the lock observer budget) acted on whatever pool
     * that shared instance happened to hold at the time — after a quick
     * re-unlock, the pool belonging to the NEW session. One lifecycle per
     * open makes that structurally impossible: a session's close can only
     * ever reach the pool its own open created.
     */
    private fun newLifecycle(): VaultLifecycle =
        VaultLifecycle(
            driverFactory = { key -> SkeinSQLiteDriver(key) },
            migrator = { driver -> Migrator(driver) },
            paths = paths,
            // One reader per service that isn't the repository's own writer:
            // 2 for VaultRepositoryImpl's reader pool, 1 for IndexStoreImpl,
            // 1 for PersonaServiceImpl, 1 for ModelRegistryImpl (skein-whg8 —
            // "ModelRegistryImpl over the session's pool").
            readerCount = REPOSITORY_READER_CONNECTIONS + 3,
        )

    suspend fun open(): VaultSession =
        withContext(io) {
            val lifecycle = newLifecycle()
            try {
                createOrOpen(lifecycle)
                val pool = lifecycle.connectionPool()
                val writer = pool.writer()
                val readers = pool.readers()
                val repositoryReaders = readers.subList(0, REPOSITORY_READER_CONNECTIONS)
                val indexConnection = readers[REPOSITORY_READER_CONNECTIONS]
                val personaConnection = readers[REPOSITORY_READER_CONNECTIONS + 1]
                val modelsConnection = readers[REPOSITORY_READER_CONNECTIONS + 2]

                val repository =
                    VaultRepositoryImpl(
                        writer = writer,
                        attachments = FileAttachmentStore(attachmentsDir, masterKey = ::keyCopy),
                        readers = repositoryReaders,
                    )
                val indexStore = IndexStoreImpl(indexConnection)
                val personaService = PersonaServiceImpl(personaConnection)
                // skein-whg8: the ask-path composition root, built only when
                // there is a real Context to build it from (every existing
                // test fixture in this source set calls `open()` with none —
                // see this class's `context` param doc). `null` here means
                // `VaultSession.models` is `null` too; `/chat` degrades to
                // "no default model, try /import model" rather than crash.
                val models =
                    context?.let {
                        ModelServices.forSession(
                            context = it,
                            connection = modelsConnection,
                            // "ui_prefs" — `ModelRegistryImpl`'s own file
                            // header names this exact SharedPreferences file.
                            prefs = it.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE),
                            sessionEpoch = sessionEpoch,
                            vaultRepository = repository,
                            indexStore = indexStore,
                            personaProvider = { personaService.default() },
                        )
                    }
                VaultSession(
                    repository = repository,
                    indexStore = indexStore,
                    personaService = personaService,
                    exportService = ExportServiceImpl(repository),
                    importService = ImportServiceImpl(repository, context = context),
                    // skein-0m1z: `VaultRepositoryImpl` is also the
                    // `ExportStageRepository` (migration 005), so a stage row
                    // shares the writer connection and transaction plumbing
                    // above rather than opening a second writing connection.
                    exportStages = repository,
                    models = models,
                ) {
                    // Closing must run to completion even when the lock
                    // observer budget cancels the caller.
                    //
                    // skein-1bx4 — `lifecycle.close()` runs FIRST, not last.
                    // It checkpoints the WAL with TRUNCATE against the pool's
                    // writer and then closes every pool connection (readers
                    // in reverse-open order, writer last). Every connection
                    // the three services below hold IS a pool connection, so
                    // running them first only guaranteed that the checkpoint
                    // found its writer already closed — `prepare()` on a
                    // closed `SkeinSQLiteConnection` throws
                    // `IllegalStateException`, the TRUNCATE never once ran on
                    // device, and the throw escaped this lambda into
                    // `VaultBootstrap.LockHandler.onLocking`. In this order
                    // the checkpoint sees a live writer, and the services'
                    // own `close()` calls afterwards are the harmless
                    // per-connection no-ops the old comment claimed they
                    // were (`SkeinSQLiteConnection.close` is idempotent).
                    // They are kept so each service still releases anything
                    // it owns beyond a pool connection.
                    withContext(io + NonCancellable) {
                        lifecycle.close()
                        repository.close()
                        indexStore.close()
                        personaService.close()
                    }
                }
            } catch (t: Throwable) {
                runCatching { lifecycle.close() }
                when (t) {
                    is CancellationException, is VaultOpenException -> throw t
                    else -> throw VaultOpenException("vault services failed to start: ${t.javaClass.simpleName}")
                }
            }
        }

    private suspend fun createOrOpen(lifecycle: VaultLifecycle) {
        val dbFile = paths.databaseFile
        if (dbFile.exists()) {
            when (val result = lifecycle.open(keyCopy())) {
                is OpenResult.Success -> Unit
                OpenResult.NotFound -> throw VaultOpenException("vault file disappeared before open")
                OpenResult.WrongKey -> throw VaultOpenException("the unlocked key does not open this vault")
                is OpenResult.Failed -> throw VaultOpenException(result.reason)
            }
        } else {
            dbFile.parentFile?.mkdirs()
            when (val result = lifecycle.create(keyCopy())) {
                is CreateResult.Success -> Unit
                CreateResult.AlreadyExists -> throw VaultOpenException("vault file appeared during create")
                is CreateResult.Failed -> throw VaultOpenException(result.reason)
            }
        }
    }

    /** A fresh copy of the live master key; the callee zeroes it. Throws when locked. */
    private fun keyCopy(): ByteArray = keyProvider.currentKey()?.copyOf() ?: throw VaultOpenException("vault locked")

    private companion object {
        /** Reader connections for `VaultRepositoryImpl` ("typically 2-3", its header). */
        const val REPOSITORY_READER_CONNECTIONS = 2
    }
}
