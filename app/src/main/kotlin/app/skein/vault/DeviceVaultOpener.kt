// skein-2ige — the on-device `openVault` for `VaultBootstrap`: `VaultLifecycle`
// (create on first run, open otherwise; key verification + migrations) and
// the SQL service implementations over their own keyed connections.
//
// Connections: `VaultLifecycle` keeps its live connection private (it
// exposes only create/open/close/integrityCheck), so every service
// connection is opened here through the public `SkeinSQLiteDriver(key)`
// — exactly how the lifecycle opens its own — after `lifecycle.open`/
// `create` has proven the key and brought the schema up to date. One
// connection per service (writer + readers for the repository, one each
// for the index store and the persona service): two services sharing a
// connection would interleave statements and transactions across two
// independent mutexes. The follow-up that gives `VaultLifecycle` a keyed
// connection factory / the plan's `ConnectionPool` is filed on the bd
// (see the skein-2ige close notes).
//
// Key discipline: `keyCopy()` is the only touch point — a fresh
// `currentKey().copyOf()` per connection, handed to a driver/lifecycle
// that zeroes it; nothing here retains key bytes. `FileAttachmentStore`
// gets the same lambda (its contract: a fresh copy per call, wiped after
// the per-file HKDF).

package app.skein.vault

import androidx.sqlite.SQLiteConnection
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
) {
    private val lifecycle =
        VaultLifecycle(
            driverFactory = { key -> SkeinSQLiteDriver(key) },
            migrator = { driver -> Migrator(driver) },
            paths = paths,
        )

    suspend fun open(): VaultSession =
        withContext(io) {
            val connections = ArrayList<SQLiteConnection>()
            try {
                createOrOpen()
                val path = paths.databaseFile.absolutePath
                val writer = connect(path, connections)
                val readers = List(READER_CONNECTIONS) { connect(path, connections) }
                val indexConnection = connect(path, connections)
                val personaConnection = connect(path, connections)

                val repository =
                    VaultRepositoryImpl(
                        writer = writer,
                        attachments = FileAttachmentStore(attachmentsDir, masterKey = ::keyCopy),
                        readers = readers,
                    )
                val indexStore = IndexStoreImpl(indexConnection)
                val personaService = PersonaServiceImpl(personaConnection)
                VaultSession(
                    repository = repository,
                    indexStore = indexStore,
                    personaService = personaService,
                    exportService = ExportServiceImpl(repository),
                    importService = ImportServiceImpl(repository),
                ) {
                    // Closing must run to completion even when the lock
                    // observer budget cancels the caller.
                    withContext(io + NonCancellable) {
                        repository.close()
                        indexStore.close()
                        personaService.close()
                        lifecycle.close()
                    }
                }
            } catch (t: Throwable) {
                connections.asReversed().forEach { runCatching { it.close() } }
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

    private fun connect(
        path: String,
        opened: MutableList<SQLiteConnection>,
    ): SQLiteConnection = SkeinSQLiteDriver(keyCopy()).open(path).also(opened::add)

    /** A fresh copy of the live master key; the callee zeroes it. Throws when locked. */
    private fun keyCopy(): ByteArray = keyProvider.currentKey()?.copyOf() ?: throw VaultOpenException("vault locked")

    private companion object {
        /** Reader connections for `VaultRepositoryImpl` ("typically 2-3", its header). */
        const val READER_CONNECTIONS = 2
    }
}
