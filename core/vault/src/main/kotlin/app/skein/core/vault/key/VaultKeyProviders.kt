// skein-txrh — the one public entry point that builds the on-device
// `VaultKeyProvider`. Everything it wires stays `internal`
// (`VaultKeyProviderImpl`, `AndroidKeystoreFacade`,
// `AndroidBiometricAuthenticator`, `FileMasterKeyStorage`); `:app`'s
// composition root (`VaultServices.forDevice`) calls this and nothing else.

package app.skein.core.vault.key

import android.content.Context
import app.skein.core.vault.lifecycle.VaultPaths

/** Factories for [VaultKeyProvider]. */
public object VaultKeyProviders {
    /**
     * The production provider: real `AndroidKeyStore` Layer-0 aliases
     * (StrongBox where available, `ATTACHMENT_ENCRYPTION.md` §2.3), the
     * real `BiometricPrompt` for the biometric / device-credential factors,
     * and the Keystore-wrapped master persisted in the key-envelope file
     * at `<paths.vaultDir>/keys/key-envelope.v1` — OUTSIDE the SQLCipher
     * database that master keys (`VAULT_FORMAT.md` §1; §3.4 amendment).
     * `keys/` is excluded from cloud backup and device transfer by
     * `app/src/main/res/xml/{data_extraction_rules,backup_rules_legacy}.xml`.
     *
     * Pass the same [paths] the vault lifecycle uses so the envelope sits
     * beside the `vault.db` it unlocks. Construction performs no I/O and
     * does not load the Keystore; the first [VaultKeyProvider.unlock] on a
     * device with no envelope reports [UnlockResult.NotInitialised] until
     * [VaultKeyProvider.setup] has run once.
     */
    public fun forDevice(
        context: Context,
        paths: VaultPaths,
    ): VaultKeyProvider {
        val app = context.applicationContext
        return VaultKeyProviderImpl(
            keystore = AndroidKeystoreFacade(app),
            biometric = AndroidBiometricAuthenticator(app),
            storage = FileMasterKeyStorage(FileMasterKeyStorage.envelopeFileIn(paths.vaultDir)),
        )
    }
}
