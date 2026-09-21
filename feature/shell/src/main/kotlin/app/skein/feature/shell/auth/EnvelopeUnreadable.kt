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
// No reset lives here or anywhere in this flow: a user-initiated, explicitly
// destructive "reset vault" (delete the envelope, `vault.db` and
// `attachments/`, then `setup()`) is tracked separately in bd — see the
// skein-ank2 close notes for the follow-up id — and is the only way past
// this state.

package app.skein.feature.shell.auth

internal object EnvelopeUnreadable {
    /** `MasterKeyStorageException.Kind.CORRUPT.reason` — format / integrity check failed. */
    const val REASON_CORRUPT: String = "key envelope corrupt"

    /** `MasterKeyStorageException.Kind.IO.reason` — the file could not be read or written. */
    const val REASON_IO: String = "key envelope io failure"

    val REASONS: Set<String> = setOf(REASON_CORRUPT, REASON_IO)

    /** Distinct from the generic failure text; promises nothing was changed and offers no reset. */
    const val UNLOCK_MESSAGE: String =
        "Your vault's key file could not be read. Nothing has been changed and no data has been " +
            "deleted. Unlocking is not possible until the vault is reset, which is not yet available in this build."

    fun matches(reason: String): Boolean = reason in REASONS
}
