// skein-ank2 — the one place the auth screens recognise "the key envelope
// exists but cannot be read".
//
// `VaultKeyProviderImpl` (`:core:vault`, skein-txrh) reports a corrupt or
// unreadable `keys/key-envelope.v1` as `UnlockResult.Failed(reason)` /
// `SetupResult.Failed(reason)` with one of two bounded, payload-free
// phrases — deliberately NOT `NotInitialised`, so nothing ever offers a
// destructive re-setup over a vault that still exists on disk. `UnlockResult`
// gains no typed variant for this (its exhaustive `when` in `UnlockManager`
// is owned elsewhere), so the UI matches the phrases verbatim. They are the
// `reason` values of `MasterKeyStorageException.Kind.CORRUPT` / `.IO`; that
// enum's doc names this file so the two stay in step.
//
// skein-v3wb: a user-initiated, explicitly destructive "reset vault" (delete
// the envelope, `vault.db` and `attachments/`, then `setup()` again) is now
// wired from exactly this state — see `BiometricUnlockScreen`'s
// `onResetRequested` param and `VaultResetScreen`. [UNLOCK_MESSAGE] below
// drops the "not yet available in this build" clause that shipped with
// skein-ank2 now that the reset flow exists; the rest of the promise
// ("nothing has been changed and no data has been deleted" UNTIL the user
// explicitly resets) still holds verbatim.

package app.skein.feature.shell.auth

internal object EnvelopeUnreadable {
    /** `MasterKeyStorageException.Kind.CORRUPT.reason` — format / integrity check failed. */
    const val REASON_CORRUPT: String = "key envelope corrupt"

    /** `MasterKeyStorageException.Kind.IO.reason` — the file could not be read or written. */
    const val REASON_IO: String = "key envelope io failure"

    val REASONS: Set<String> = setOf(REASON_CORRUPT, REASON_IO)

    /** Distinct from the generic failure text; promises nothing was changed unless the user explicitly resets. */
    const val UNLOCK_MESSAGE: String =
        "Your vault's key file could not be read. Nothing has been changed and no data has been " +
            "deleted. Unlocking is not possible until the vault is reset."

    fun matches(reason: String): Boolean = reason in REASONS
}
