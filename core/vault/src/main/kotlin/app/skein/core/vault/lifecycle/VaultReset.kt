// skein-v3wb — the app's ONE sanctioned destructive flow: recovering from a
// corrupt/unreadable `keys/key-envelope.v1` (skein-ank2's `EnvelopeUnreadable`
// state) by deleting everything the vault owns and starting `setup()` over.
//
// Deletion set, in order:
//   1. `keys/key-envelope.v1` (`FileMasterKeyStorage.envelopeFileIn`) — the
//      wrapped master. Deleted first so a reset interrupted after this line
//      has already destroyed the one thing that makes the rest of the vault
//      readable; every step after this one is just cleaning up ciphertext
//      and plaintext-adjacent bytes that are already unrecoverable.
//   2. `vault.db` + `-wal` + `-shm` (`VaultPaths.databaseFile`) — the
//      SQLCipher file and its journal siblings.
//   3. `attachments/` — the write-once attachment store, recursively.
//   4. Export staging, when the caller supplies one (skein-80m's
//      `cache/staging_export/`, OUTSIDE `filesDir` — see [stagingDir]'s doc
//      for why this is the one deliberate exception to "vault-owned means
//      under `filesDir`").
//   5. Both Layer-0 Keystore aliases (`skein_master_bio_v1` /
//      `skein_master_cred_v1`, `VaultKeyProviderImpl`'s `ALIAS_BIOMETRIC` /
//      `ALIAS_CREDENTIAL`) via [KeystoreAliasDeleter] — last, since by this
//      point nothing on disk references them any more.
//
// Crash-safety: [reset] writes [MARKER_FILE_NAME] under [vaultDir] BEFORE
// touching anything else, then runs the deletion sequence, then removes the
// marker. Every step in the sequence is independently idempotent (`File.delete()`
// on an absent file / `File.deleteRecursively()` on an absent directory are
// both no-ops; [KeystoreAliasDeleter.deleteEntry] is documented idempotent by
// `KeystoreFacade.deleteEntry`), so re-running the WHOLE sequence from a
// half-done state converges on the same end state a from-scratch run would.
// [resumeIfPending] is that re-run: call it once, at process start, BEFORE
// anything probes `VaultKeyProvider.isInitialised()` — a marker found there
// means the process died mid-reset last time, and finishing the sequence is
// the only way to reach a consistent state (an envelope-less, db-less vault
// ready for `setup()`) rather than leaving some owned paths deleted and
// others not.
//
// Never callable while unlocked (non-negotiable, skein-v3wb): [reset] checks
// [isUnlocked] first and refuses with [VaultResetResult.RefusedUnlocked]
// without writing the marker or deleting anything — the caller (the UI state
// holder) is expected to route through `UnlockManager.lockAndAwait` first.
// [resumeIfPending] does NOT check [isUnlocked] — it runs at process start,
// before any unlock is possible, to finish a reset that was already
// in-flight and already passed that check on the run that started it.
//
// No key material or paths are ever logged from this file — every result is
// a closed enum the caller maps onto a bounded `SkeinLog` event (counts/
// states only, per the bd non-negotiable).

package app.skein.core.vault.lifecycle

import android.content.Context
import app.skein.core.model.SkeinLog
import app.skein.core.vault.key.AndroidKeystoreFacade
import app.skein.core.vault.key.FileMasterKeyStorage
import app.skein.core.vault.key.VaultKeyProviderImpl
import java.io.File
import java.io.IOException

/**
 * Deletes a Keystore entry by alias. The narrow, PUBLIC slice of the
 * internal `KeystoreFacade` contract [VaultReset] needs — kept separate (and
 * a `fun interface`) so callers outside `:core:vault` (`:app`'s composition
 * root, and every module's JVM tests) can supply a real or fake deleter
 * without `KeystoreFacade` itself becoming public API. Implementations MUST
 * be idempotent: deleting an alias that does not exist is a no-op, never a
 * throw (`KeystoreFacade.deleteEntry`'s existing contract; `AndroidKeystoreFacade`
 * already satisfies it).
 */
public fun interface KeystoreAliasDeleter {
    public fun deleteEntry(alias: String)
}

/** Outcome of [VaultReset.reset]. */
public sealed class VaultResetResult {
    /** Every path this reset owns was deleted (or already absent) and both Keystore aliases were removed. */
    public object Success : VaultResetResult()

    /**
     * Refused: the vault is currently unlocked. Nothing was touched — no
     * marker was written and no path was deleted. The caller must lock the
     * vault first (`UnlockManager.lockAndAwait`) and retry.
     */
    public object RefusedUnlocked : VaultResetResult()

    /**
     * An I/O failure interrupted the deletion sequence partway through.
     * [reason] is a short diagnostic, never a path or key-derived value. The
     * marker is left in place, so [VaultReset.resumeIfPending] finishes the
     * job on the next process start; the caller may also retry [reset]
     * immediately (it is refused only by [isUnlocked], not by a prior
     * partial failure).
     */
    public data class Failed(
        public val reason: String,
    ) : VaultResetResult()
}

/**
 * See the file header for the deletion set, order, and crash-safety design.
 *
 * @param vaultDir the vault's app-private home (`VaultPaths.vaultDir`,
 *   typically `context.filesDir`) — where the marker and the key envelope
 *   live, and the root every owned path except [stagingDir] must resolve
 *   inside (enforced at construction: a misconfigured [attachmentsDir]
 *   outside [vaultDir] throws rather than silently reaching outside the
 *   vault's own storage).
 * @param databaseFile `VaultPaths.databaseFile` — `vault.db`, whose `-wal`/
 *   `-shm` siblings are derived from its path.
 * @param attachmentsDir the write-once attachment store
 *   (`VaultServices.ATTACHMENTS_DIR` beside `vault.db`).
 * @param stagingDir export staging (`cache/staging_export/`,
 *   `core/export/pdf/PdfStaging.STAGING_DIR_NAME`), or `null` when the
 *   caller has none to clean up. Deliberately allowed OUTSIDE [vaultDir]:
 *   it lives under `context.cacheDir`, not `filesDir` (E2.I11 / skein-80m),
 *   because a rendered PDF spooled there for printing is app-private,
 *   OS-reclaimable storage the reset must not leave live plaintext in
 *   regardless of which side of the `filesDir`/`cacheDir` line it sits on —
 *   but it is still an EXPLICIT, caller-supplied path, never inferred or
 *   reached via a wildcard walk, so the "never delete outside what was
 *   explicitly handed in" property holds even though "handed in" here isn't
 *   [vaultDir]-rooted.
 * @param keystore deletes the two Layer-0 aliases; production wiring is
 *   [forDevice], tests supply a fake [KeystoreAliasDeleter].
 * @param isUnlocked polled once at the top of [reset]; production wiring
 *   passes `{ unlockManager.state.value is UnlockState.Unlocked }`.
 */
public class VaultReset(
    private val vaultDir: File,
    private val databaseFile: File,
    private val attachmentsDir: File,
    private val stagingDir: File?,
    private val keystore: KeystoreAliasDeleter,
    private val isUnlocked: () -> Boolean,
) {
    private val markerFile = File(vaultDir, MARKER_FILE_NAME)
    private val envelopeFile = FileMasterKeyStorage.envelopeFileIn(vaultDir)

    init {
        requireOwned(databaseFile, "database file")
        requireOwned(attachmentsDir, "attachments directory")
    }

    /**
     * Runs the destructive reset. See the file header for the full
     * behaviour; in short: refuses while [isUnlocked], otherwise writes the
     * marker, deletes every owned path plus both Keystore aliases (each
     * step idempotent), then removes the marker.
     */
    public fun reset(): VaultResetResult {
        if (isUnlocked()) {
            SkeinLog.w(LOG_TAG, "reset refused: vault is unlocked")
            return VaultResetResult.RefusedUnlocked
        }
        SkeinLog.i(LOG_TAG, "reset started")
        return try {
            vaultDir.mkdirs()
            markerFile.writeBytes(ByteArray(0))
            deleteOwnedPaths()
            markerFile.delete()
            SkeinLog.i(LOG_TAG, "reset completed")
            VaultResetResult.Success
        } catch (e: IOException) {
            // Class name only — an IOException's own message/stack trace can
            // carry a path, and this non-negotiable never logs one.
            SkeinLog.e(LOG_TAG, "reset failed: io failure (${e.javaClass.simpleName})")
            VaultResetResult.Failed("vault reset io failure: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Finishes a reset interrupted by process death (the marker from a
     * previous [reset] call is still on disk). Call once, at process start,
     * before anything else touches the vault. Returns `true` iff a pending
     * reset was found and (re-)run to completion; a `false` means [reset]
     * has never been interrupted and nothing was done.
     *
     * Deliberately does NOT consult [isUnlocked] — see the file header.
     */
    public fun resumeIfPending(): Boolean {
        if (!markerFile.exists()) return false
        SkeinLog.w(LOG_TAG, "resuming a reset interrupted by a previous process death")
        deleteOwnedPaths()
        markerFile.delete()
        SkeinLog.i(LOG_TAG, "resumed reset completed")
        return true
    }

    private fun deleteOwnedPaths() {
        envelopeFile.delete()
        databaseFile.delete()
        File(databaseFile.path + WAL_SUFFIX).delete()
        File(databaseFile.path + SHM_SUFFIX).delete()
        attachmentsDir.deleteRecursively()
        stagingDir?.deleteRecursively()
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_BIOMETRIC)
        keystore.deleteEntry(VaultKeyProviderImpl.ALIAS_CREDENTIAL)
    }

    /** Defence in depth: every vault-owned target must resolve inside [vaultDir]. [stagingDir] is exempt — see its doc. */
    private fun requireOwned(
        target: File,
        what: String,
    ) {
        val root = vaultDir.toPath().toAbsolutePath().normalize()
        val resolved = target.toPath().toAbsolutePath().normalize()
        require(resolved.startsWith(root)) { "$what must be inside the vault directory" }
    }

    public companion object {
        /** `<vaultDir>/.vault_reset_in_progress` — presence means a reset was interrupted mid-way. */
        public const val MARKER_FILE_NAME: String = ".vault_reset_in_progress"

        private const val WAL_SUFFIX = "-wal"
        private const val SHM_SUFFIX = "-shm"
        private const val LOG_TAG = "VaultReset"

        /**
         * Production wiring: the real `AndroidKeyStore` facade for alias
         * deletion, over [paths] and [attachmentsDir]. Pass the SAME
         * [VaultPaths] and attachments directory `VaultServices.forDevice`
         * wires into [DeviceVaultOpener] so this reset's deletion set
         * exactly matches what the vault actually wrote.
         */
        public fun forDevice(
            context: Context,
            paths: VaultPaths,
            attachmentsDir: File,
            stagingDir: File? = null,
            isUnlocked: () -> Boolean,
        ): VaultReset {
            val facade = AndroidKeystoreFacade(context.applicationContext)
            return VaultReset(
                vaultDir = paths.vaultDir,
                databaseFile = paths.databaseFile,
                attachmentsDir = attachmentsDir,
                stagingDir = stagingDir,
                keystore = facade::deleteEntry,
                isUnlocked = isUnlocked,
            )
        }
    }
}
