package app.skein.core.vault.key.recovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.vault.db.RecoveryProofNative
import app.skein.core.vault.db.SkeinSQLiteConnection
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Disposable encrypted files only. This does not exercise owner keys or production recovery wiring. */
@RunWith(AndroidJUnit4::class)
class ExistingVaultKeyProofInstrumentedTest {
    private val directories = mutableListOf<File>()
    private val key = ByteArray(32) { (it + 32).toByte() }

    @After
    fun cleanup() {
        directories.forEach { it.deleteRecursively() }
        key.fill(0)
    }

    @Test
    fun rawKeyProvesEncryptedPagesAndWrongKeyCannotChangeSources() {
        val root = root()
        create(root, encrypted = true)
        val before = sources(root)
        ClosedVaultRecoverySnapshot.capture(lease(root)).use { snapshot ->
            val correct = key.copyOf()
            assertThat(
                ExistingVaultKeyProof().verify(snapshot, correct),
            ).isEqualTo(ExistingVaultKeyProofResult.VERIFIED)
            assertThat(correct).isEqualTo(ByteArray(32))
            assertThat(ExistingVaultKeyProof().verify(snapshot, ByteArray(32) { 9 }))
                .isEqualTo(ExistingVaultKeyProofResult.WRONG_KEY_OR_CORRUPT)
        }
        assertThat(sources(root)).containsExactlyEntriesIn(before)
    }

    @Test
    fun plaintextEmptyMissingCorruptAndUnknownSchemaNeverProveCandidate() {
        val root = root()
        create(root, encrypted = false)
        val database = File(root, "vault.db")
        assertThat(RecoveryProofNative.nativeVerify(database.path, key)).isNotEqualTo(0)
        database.writeBytes(ByteArray(0))
        assertThat(RecoveryProofNative.nativeVerify(database.path, key)).isNotEqualTo(0)
        database.delete()
        assertThat(RecoveryProofNative.nativeVerify(database.path, key)).isNotEqualTo(0)
        assertThat(database.exists()).isFalse()
        database.writeBytes(ByteArray(4096) { 17 })
        assertThat(RecoveryProofNative.nativeVerify(database.path, key)).isNotEqualTo(0)
        database.delete()
        create(root, encrypted = true, version = 999)
        assertThat(RecoveryProofNative.nativeVerify(database.path, key)).isEqualTo(2)
    }

    @Test
    fun committedWalSchemaIsReadWithoutImmutableModeOrSourceCheckpoint() {
        val builder = root()
        val database = File(builder, "vault.db")
        SkeinSQLiteDriver(key.copyOf()).open(database.path).use { raw ->
            val connection = raw as SkeinSQLiteConnection
            connection.exec("PRAGMA user_version = 11")
            connection.exec("CREATE TABLE documents(id TEXT)")
            connection.exec("CREATE TABLE personas(id TEXT)")
            connection.exec("CREATE TABLE attachment_keys(id TEXT)")
            connection.exec("PRAGMA wal_checkpoint(TRUNCATE)")
            connection.exec("PRAGMA wal_autocheckpoint = 0")
            connection.exec("CREATE TABLE messages(id TEXT)")
            connection.exec("INSERT INTO messages VALUES('disposable wal row')")
            // Capture a separate closed test fixture while this builder has no concurrent writer.
            // The recovery lease is issued ONLY for the separate fixture, never this open builder.
            val closed = root()
            database.copyTo(File(closed, "vault.db"))
            File(builder, "vault.db-wal").copyTo(File(closed, "vault.db-wal"))
            val before = sources(closed)
            ClosedVaultRecoverySnapshot.capture(lease(closed)).use { snapshot ->
                assertThat(
                    ExistingVaultKeyProof().verify(snapshot, key.copyOf()),
                ).isEqualTo(ExistingVaultKeyProofResult.VERIFIED)
            }
            assertThat(sources(closed)).containsExactlyEntriesIn(before)
            File(closed, "vault.db-wal").delete()
            ClosedVaultRecoverySnapshot.capture(lease(closed)).use { snapshot ->
                assertThat(
                    ExistingVaultKeyProof().verify(snapshot, key.copyOf()),
                ).isEqualTo(ExistingVaultKeyProofResult.UNSUPPORTED_SCHEMA)
            }
        }
    }

    @Test
    fun proofNeverMigratesOlderSupportedSchema() {
        val root = root()
        create(root, encrypted = true, version = 1)
        val before = sources(root)
        ClosedVaultRecoverySnapshot.capture(lease(root)).use { snapshot ->
            assertThat(
                ExistingVaultKeyProof().verify(snapshot, key.copyOf()),
            ).isEqualTo(ExistingVaultKeyProofResult.VERIFIED)
        }
        assertThat(sources(root)).containsExactlyEntriesIn(before)
        SkeinSQLiteDriver(key.copyOf()).open(File(root, "vault.db").path).use { connection ->
            connection.prepare("PRAGMA user_version").use { query ->
                assertThat(query.step()).isTrue()
                assertThat(query.getLong(0)).isEqualTo(1)
            }
        }
    }

    private fun root(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return Files.createTempDirectory(cache.toPath(), "recovery-proof-test-").toFile().canonicalFile.also {
            directories += it
            File(it, "keys").mkdirs()
            File(it, "keys/key-envelope.v1").writeText("disposable envelope identity")
        }
    }

    private fun lease(root: File) =
        object : ClosedVaultRecoveryLease {
            override val vaultDirectory = root

            override fun assertExclusiveAndClosed() = Unit // This test owns its closed disposable fixture exclusively.
        }

    private fun create(
        root: File,
        encrypted: Boolean,
        version: Int = 11,
    ) {
        val driver = if (encrypted) SkeinSQLiteDriver(key.copyOf()) else SkeinSQLiteDriver()
        driver.open(File(root, "vault.db").path).use { raw ->
            val connection = raw as SkeinSQLiteConnection
            connection.exec("PRAGMA user_version = $version")
            listOf("documents", "messages", "personas", "attachment_keys").forEach { name ->
                connection.exec("CREATE TABLE $name(id TEXT)")
                connection.exec("INSERT INTO $name VALUES('disposable $name')")
            }
        }
    }

    private fun sources(root: File) =
        listOf("vault.db", "vault.db-wal", "vault.db-shm", "keys/key-envelope.v1")
            .associateWith { name -> File(root, name).takeIf { it.exists() }?.readBytes()?.toList() }
}
