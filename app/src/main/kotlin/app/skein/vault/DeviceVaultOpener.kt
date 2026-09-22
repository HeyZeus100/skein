// skein-2ige — the on-device `openVault` for `VaultBootstrap`: `VaultLifecycle`
// (create on first run, open otherwise; key verification + migrations) and
// the SQL service implementations over connections drawn from it.
//
// Connections (skein-4qol): `VaultLifecycle` owns a `ConnectionPool` — one
// writer + N readers, each keyed from a single copy and carrying
// `busy_timeout` — built once `lifecycle.open`/`create` has proven the key
// and brought the schema up to date. This file no longer constructs
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
) {
    private val lifecycle =
        VaultLifecycle(
            driverFactory = { key -> SkeinSQLiteDriver(key) },
            migrator = { driver -> Migrator(driver) },
            paths = paths,
            // One reader per service that isn't the repository's own writer:
            // 2 for VaultRepositoryImpl's reader pool, 1 for IndexStoreImpl,
            // 1 for PersonaServiceImpl.
            readerCount = REPOSITORY_READER_CONNECTIONS + 2,
        )

    suspend fun open(): VaultSession =
        withContext(io) {
            try {
                createOrOpen()
                val pool = lifecycle.connectionPool()
                val writer = pool.writer()
                val readers = pool.readers()
                val repositoryReaders = readers.subList(0, REPOSITORY_READER_CONNECTIONS)
                val indexConnection = readers[REPOSITORY_READER_CONNECTIONS]
                val personaConnection = readers[REPOSITORY_READER_CONNECTIONS + 1]

                val repository =
                    VaultRepositoryImpl(
                        writer = writer,
                        attachments = FileAttachmentStore(attachmentsDir, masterKey = ::keyCopy),
                        readers = repositoryReaders,
                    )
                val indexStore = IndexStoreImpl(indexConnection)
                val personaService = PersonaServiceImpl(personaConnection)
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
                ) {
                    // Closing must run to completion even when the lock
                    // observer budget cancels the caller. `lifecycle.close()`
                    // closes every pool connection (readers, then writer) —
                    // including any the services above already closed
                    // themselves, which is a harmless no-op per connection.
                    withContext(io + NonCancellable) {
                        repository.close()
                        indexStore.close()
                        personaService.close()
                        lifecycle.close()
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

    private suspend fun createOrOpen() {
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
