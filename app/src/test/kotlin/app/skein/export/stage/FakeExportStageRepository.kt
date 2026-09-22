// skein-0m1z — in-memory `ExportStageRepository` for `:app`'s JVM tests.
//
// `VaultSession` gained an `exportStages` member (migration 005's table), so
// every fake session in this source set needs one. Deliberately NOT a default
// argument on `VaultSession`: a production wiring that forgot to pass the
// real repository would then silently stop sweeping staged plaintext, which
// is exactly the failure this whole bead exists to prevent. An explicit fake
// here costs one line per test and keeps the production constructor honest.
//
// `:core:vault`'s own test source set has an identical fake; the two are
// separate compilations and neither module's test classes are published.

package app.skein.export.stage

import app.skein.core.vault.export.stage.ExportStageRepository
import app.skein.core.vault.export.stage.ExportStageRow

internal class FakeExportStageRepository : ExportStageRepository {
    val rows: MutableMap<String, ExportStageRow> = linkedMapOf()

    override suspend fun insertStage(row: ExportStageRow) {
        rows[row.stageId] = row
    }

    override suspend fun getStage(stageId: String): ExportStageRow? = rows[stageId]

    override suspend fun listUnsweptStages(): List<ExportStageRow> =
        rows.values.filter { !it.swept }.sortedBy { it.expiresAt }

    override suspend fun markStageSwept(stageId: String): Boolean {
        val existing = rows[stageId] ?: return false
        if (existing.swept) return false
        rows[stageId] = existing.copy(swept = true)
        return true
    }

    override suspend fun markAllStagesSwept(): Int {
        var changed = 0
        for ((id, row) in rows.entries.toList()) {
            if (!row.swept) {
                rows[id] = row.copy(swept = true)
                changed++
            }
        }
        return changed
    }
}
