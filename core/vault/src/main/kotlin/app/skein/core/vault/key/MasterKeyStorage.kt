// skein-3el (E3.I2) — `attachment_master_key` row abstraction.
//
// A thin abstraction over the `attachment_master_key` table
// (`001_initial.sql`, `ATTACHMENT_ENCRYPTION.md` §3.4) so
// `VaultKeyProviderImpl` is testable without a live SQLCipher connection.
// The real backend will land alongside `VaultRepositoryImpl` (`E2.I4`);
// unit tests wire a `FakeMasterKeyStorage`.

package app.skein.core.vault.key

/**
 * A single `attachment_master_key` row. Each of the two wrapped-bytes
 * columns MAY be null while its Layer-0 factor is being provisioned or
 * has just been invalidated (`ATTACHMENT_ENCRYPTION.md` §3.4). The IV /
 * tag columns follow the same null-together / non-null-together
 * convention.
 *
 * `wrapTag*` columns are kept as their own fields on the shape even
 * though the JCE `Cipher.doFinal()` output already appends the GCM tag to
 * the ciphertext — this matches the DDL from `001_initial.sql` verbatim
 * and lets a future backend that stores tag separately (e.g. for a
 * `sqlcipher` migration that splits them) fit without shape changes.
 */
internal data class MasterKeyRow(
    val keyVersion: Int,
    val wrappedBytesBiometric: ByteArray?,
    val wrapIvBiometric: ByteArray?,
    val wrapTagBiometric: ByteArray?,
    val wrappedBytesCredential: ByteArray?,
    val wrapIvCredential: ByteArray?,
    val wrapTagCredential: ByteArray?,
    val createdAt: Long,
    val strongBoxBacked: Boolean,
) {
    // Value-class semantics with byte arrays require explicit equals/hashCode
    // for structural comparison in tests. Not used from production code — a
    // future refactor may drop these once callers switch to identity checks.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MasterKeyRow) return false
        return keyVersion == other.keyVersion &&
            wrappedBytesBiometric contentEquals other.wrappedBytesBiometric &&
            wrapIvBiometric contentEquals other.wrapIvBiometric &&
            wrapTagBiometric contentEquals other.wrapTagBiometric &&
            wrappedBytesCredential contentEquals other.wrappedBytesCredential &&
            wrapIvCredential contentEquals other.wrapIvCredential &&
            wrapTagCredential contentEquals other.wrapTagCredential &&
            createdAt == other.createdAt &&
            strongBoxBacked == other.strongBoxBacked
    }

    override fun hashCode(): Int = keyVersion
}

/**
 * Persistent store for the `attachment_master_key` row. Implementations
 * MUST perform the rewrap-write in a single logical transaction so a
 * crash between the two column updates cannot leave the row in a state
 * where neither factor decrypts back to the ORIGINAL master bytes
 * (§3.5 step 8 — "in the SAME transaction").
 */
internal interface MasterKeyStorage {
    /** The active (`superseded_at IS NULL`) row, or `null` when uninitialised. */
    fun readActive(): MasterKeyRow?

    /** Inserts the first row at `setup()`. Returns the assigned `key_version`. */
    fun writeInitial(row: MasterKeyRow): Int

    /**
     * Bumps [currentVersion] → new row, copying the surviving factor's
     * wrapped bytes forward unchanged (per §3.5 step 8, bullet 1) and
     * writing the rewrapped [rewrappedFactor]'s new [wrappedBytes] / [iv].
     * Marks the old row `superseded_at = <now>` in the same transaction.
     * Returns the newly assigned `key_version`.
     */
    fun rewrap(
        currentVersion: Int,
        rewrappedFactor: VaultKeyProvider.Factor,
        wrappedBytes: ByteArray,
        iv: ByteArray,
        tag: ByteArray?,
        now: Long,
    ): Int
}
