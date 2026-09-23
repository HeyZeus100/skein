// skein-cyq (E4.I5, H0-amended): the SQL-backed `ModelRegistry` over the
// `models` table (`001_initial.sql` + `009_model_origin.sql`), proven by
// `app.skein.testing.ModelRegistryContractTest` on an instrumented device
// (`ModelRegistryImplContractTest`) the same way `VaultRepositoryImpl` is.
//
// `ui_prefs` note: the plan's `E3.I14` typed `Prefs` wrapper over
// `SharedPreferences` does not exist yet anywhere in this tree (confirmed:
// no `Prefs.kt`, no `SharedPreferences` usage at all before this file).
// This bead's own description says the default model id is "stored in
// `ui_prefs`" — a plain Android `SharedPreferences` file, not a SQL table
// — so this class takes a `SharedPreferences` instance directly rather
// than inventing a table column for it (the default must survive
// independently of any row: `setDefault`/`default` round-trip even when
// no row named by that id exists, per `ModelRegistry`'s own KDoc). When
// `E3.I14` lands, its typed `Prefs` can wrap the same
// `"ui_prefs"`/`"default_model_id"` key this class writes without a data
// migration.
package app.skein.core.vault.models

import android.content.SharedPreferences
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import app.skein.core.model.Capability
import app.skein.core.model.CompanionFile
import app.skein.core.model.CompanionRole
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelId
import app.skein.core.model.ModelOrigin
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * SQL-backed [ModelRegistry] over the `models` table, via a single
 * [SQLiteConnection] guarded by a [Mutex] (this table is small and
 * low-frequency; unlike [app.skein.core.vault.repository.VaultRepositoryImpl]
 * there is no reader-pool scaling concern to justify one).
 *
 * The default-model pointer lives in [prefs] (`ui_prefs`), not a column on
 * any row — see this file's header.
 */
public class ModelRegistryImpl(
    private val connection: SQLiteConnection,
    private val prefs: SharedPreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ModelRegistry {
    private val mutex: Mutex = Mutex()

    override suspend fun list(): List<ModelRecord> =
        withContext(io) {
            mutex.withLock {
                val out = mutableListOf<ModelRecord>()
                connection.prepare(SELECT_ALL).use { stmt ->
                    while (stmt.step()) out += readRow(stmt)
                }
                out
            }
        }

    override suspend fun get(id: ModelId): ModelRecord? =
        withContext(io) {
            mutex.withLock {
                connection.prepare(SELECT_BY_ID).use { stmt ->
                    stmt.bindText(1, id)
                    if (stmt.step()) readRow(stmt) else null
                }
            }
        }

    override suspend fun upsert(record: ModelRecord) {
        withContext(io) {
            mutex.withLock {
                connection.prepare(UPSERT).use { stmt ->
                    bindRow(stmt, record)
                    stmt.step()
                }
            }
        }
    }

    override suspend fun delete(id: ModelId) {
        withContext(io) {
            mutex.withLock {
                connection.prepare(DELETE_BY_ID).use { stmt ->
                    stmt.bindText(1, id)
                    stmt.step()
                }
            }
        }
    }

    override suspend fun setDefault(id: ModelId?) {
        withContext(io) {
            val editor = prefs.edit()
            if (id == null) editor.remove(KEY_DEFAULT_MODEL_ID) else editor.putString(KEY_DEFAULT_MODEL_ID, id)
            editor.apply()
        }
    }

    override suspend fun default(): ModelId? =
        withContext(io) {
            prefs.getString(KEY_DEFAULT_MODEL_ID, null)
        }

    private fun readRow(stmt: SQLiteStatement): ModelRecord {
        val id = stmt.getText(0)
        val path = stmt.getText(1)
        val sha256 = stmt.getText(2)
        val attestationUrl = textOrNull(stmt, 3)
        val format = ModelFormat.entries.firstOrNull { it.db == stmt.getText(4) } ?: ModelFormat.GGUF
        val capabilities = decodeCapabilities(stmt.getText(5))
        val sizeBytes = stmt.getLong(6)
        val importedAt = stmt.getLong(7)
        val name = stmt.getText(8)
        val contextLength = stmt.getLong(9).toInt()
        val companions = decodeCompanions(textOrNull(stmt, 10))
        val blake3 = textOrNull(stmt, 11)
        val origin = ModelOrigin.entries.firstOrNull { it.db == stmt.getText(12) } ?: ModelOrigin.PICKED
        val sourceUrl = textOrNull(stmt, 13)
        val sourceRevision = textOrNull(stmt, 14)
        val licenseSpdx = textOrNull(stmt, 15)
        return ModelRecord(
            model =
                Model(
                    id = id,
                    name = name,
                    path = path,
                    sha256 = sha256,
                    format = format,
                    capabilities = capabilities,
                    sizeBytes = sizeBytes,
                    contextLength = contextLength,
                    attestationUrl = attestationUrl,
                    companions = companions,
                    importedAt = importedAt,
                ),
            blake3 = blake3,
            origin = origin,
            sourceUrl = sourceUrl,
            sourceRevision = sourceRevision,
            licenseSpdx = licenseSpdx,
        )
    }

    private fun bindRow(
        stmt: SQLiteStatement,
        record: ModelRecord,
    ) {
        val model = record.model
        stmt.bindText(1, model.id)
        stmt.bindText(2, model.path)
        stmt.bindText(3, model.sha256)
        bindTextOrNull(stmt, 4, model.attestationUrl)
        stmt.bindText(5, model.format.db)
        stmt.bindText(6, encodeCapabilities(model.capabilities))
        stmt.bindLong(7, model.sizeBytes)
        stmt.bindLong(8, model.importedAt)
        stmt.bindText(9, model.name)
        stmt.bindLong(10, model.contextLength.toLong())
        stmt.bindText(11, encodeCompanions(model.companions))
        bindTextOrNull(stmt, 12, record.blake3)
        stmt.bindText(13, record.origin.db)
        bindTextOrNull(stmt, 14, record.sourceUrl)
        bindTextOrNull(stmt, 15, record.sourceRevision)
        bindTextOrNull(stmt, 16, record.licenseSpdx)
    }

    private fun textOrNull(
        stmt: SQLiteStatement,
        index: Int,
    ): String? = if (stmt.isNull(index)) null else stmt.getText(index)

    private fun bindTextOrNull(
        stmt: SQLiteStatement,
        index: Int,
        value: String?,
    ) {
        if (value == null) stmt.bindNull(index) else stmt.bindText(index, value)
    }

    private fun encodeCapabilities(capabilities: Set<Capability>): String =
        buildJsonArray { capabilities.forEach { add(JsonPrimitive(it.db)) } }.toString()

    private fun decodeCapabilities(text: String?): Set<Capability> {
        if (text.isNullOrBlank()) return emptySet()
        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptySet()
        val result = linkedSetOf<Capability>()
        for (element in array) {
            val wire = (element as? JsonPrimitive)?.contentOrNull ?: continue
            Capability.entries.firstOrNull { it.db == wire }?.let(result::add)
        }
        return result
    }

    private fun encodeCompanions(companions: Map<CompanionRole, CompanionFile>): String =
        buildJsonArray {
            companions.forEach { (role, file) ->
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive(role.db))
                        put("path", JsonPrimitive(file.path))
                        put("sha256", JsonPrimitive(file.sha256))
                    },
                )
            }
        }.toString()

    private fun decodeCompanions(text: String?): Map<CompanionRole, CompanionFile> {
        if (text.isNullOrBlank()) return emptyMap()
        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyMap()
        val result = linkedMapOf<CompanionRole, CompanionFile>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val roleWire = (obj["role"] as? JsonPrimitive)?.contentOrNull ?: continue
            val role = CompanionRole.entries.firstOrNull { it.db == roleWire } ?: continue
            val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: continue
            val sha256 = (obj["sha256"] as? JsonPrimitive)?.contentOrNull ?: continue
            result[role] = CompanionFile(path = path, sha256 = sha256)
        }
        return result
    }

    private companion object {
        const val KEY_DEFAULT_MODEL_ID = "default_model_id"

        const val COLUMNS =
            "id, path, sha256, attestation_url, format, capabilities, size_bytes, imported_at, " +
                "display_name, context_length, companions, post_mmap_blake3, origin, source_url, " +
                "source_revision, license_spdx"

        const val SELECT_ALL = "SELECT $COLUMNS FROM models"
        const val SELECT_BY_ID = "SELECT $COLUMNS FROM models WHERE id = ?"
        const val DELETE_BY_ID = "DELETE FROM models WHERE id = ?"
        const val UPSERT =
            """
            INSERT INTO models ($COLUMNS)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                path = excluded.path,
                sha256 = excluded.sha256,
                attestation_url = excluded.attestation_url,
                format = excluded.format,
                capabilities = excluded.capabilities,
                size_bytes = excluded.size_bytes,
                imported_at = excluded.imported_at,
                display_name = excluded.display_name,
                context_length = excluded.context_length,
                companions = excluded.companions,
                post_mmap_blake3 = excluded.post_mmap_blake3,
                origin = excluded.origin,
                source_url = excluded.source_url,
                source_revision = excluded.source_revision,
                license_spdx = excluded.license_spdx
            """
    }
}
