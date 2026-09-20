// skein-yrp (E2.I13) — path contract for `VaultLifecycle`.

package app.skein.core.vault.lifecycle

import java.io.File

/**
 * Where a vault's on-disk artifacts live. [vaultDir] is the app-private
 * directory `VaultLifecycle` treats as the vault's home (typically
 * `context.filesDir`, or a per-test temp directory); [databaseFileName] is
 * the expected SQLCipher database file within it — `"vault.db"` per
 * `docs/superpowers/specs/2026-09-19-skein-design.md` §3 unless a caller
 * (test harness) overrides it.
 *
 * [databaseFile] is the single source of truth [VaultLifecycle] uses for
 * both the "does a vault already exist" check ([File.exists]) and the path
 * string handed to [app.skein.core.vault.db.SkeinSQLiteDriver.open] /
 * [app.skein.core.vault.db.migrations.Migrator.migrate].
 */
public data class VaultPaths(
    public val vaultDir: File,
    public val databaseFileName: String = DEFAULT_DATABASE_FILE_NAME,
) {
    public val databaseFile: File
        get() = File(vaultDir, databaseFileName)

    public companion object {
        public const val DEFAULT_DATABASE_FILE_NAME: String = "vault.db"
    }
}
