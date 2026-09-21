// skein-3el (E3.I2) — wrapped-master generation abstraction.
// skein-txrh — the production backend is the key-envelope FILE, not a table.
//
// A thin abstraction over the persisted, Keystore-wrapped master so
// `VaultKeyProviderImpl` is testable without a filesystem. `001_initial.sql`
// still carries an `attachment_master_key` table of exactly this shape
// (`ATTACHMENT_ENCRYPTION.md` §3.4), but nothing writes to it any more: the
// same 32-byte master keys `vault.db` itself (`VaultLifecycle` /
// `SkeinSQLiteDriver`, E2.I13 / E2.I1), so its wrapped form MUST live
// outside the SQLCipher file — see the dated amendment under §3.4 and
// `VAULT_FORMAT.md` §1 (`keys/key-envelope.v1`). The table's removal is
// bd skein-7d0l. Production backend: `FileMasterKeyStorage`; unit tests
// wire a `FakeMasterKeyStorage`.

package app.skein.core.vault.key

/**
 * One wrapped-master generation. Each of the two wrapped-bytes fields MAY
 * be null while its Layer-0 factor is being provisioned or has just been
 * invalidated (`ATTACHMENT_ENCRYPTION.md` §3.4). The IV / tag fields
 * follow the same null-together / non-null-together convention.
 *
 * `wrapTag*` are kept as their own fields even though the JCE
 * `Cipher.doFinal()` output already appends the GCM tag to the ciphertext
 * — this matches the §3.4 shape verbatim and lets a backend that stores
 * the tag separately fit without shape changes.
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
 * Persistent store for the wrapped-master generation. Implementations
 * MUST make [rewrap] a single logical transaction so a crash mid-write
 * cannot leave a state where neither factor decrypts back to the ORIGINAL
 * master bytes (§3.5 step 8 — "in the SAME transaction"): the previous
 * generation stays readable until the new one is fully in place.
 *
 * Every backend failure surfaces as a [MasterKeyStorageException] whose
 * [MasterKeyStorageException.kind] the provider maps onto its typed
 * results; messages never carry envelope or key bytes.
 */
internal interface MasterKeyStorage {
    /**
     * The active generation, or `null` when uninitialised (no envelope at
     * all — the provider reports `UnlockResult.NotInitialised`).
     *
     * @throws MasterKeyStorageException `CORRUPT` when an envelope exists
     *   but fails its format/integrity checks; `IO` when it cannot be read.
     */
    fun readActive(): MasterKeyRow?

    /**
     * Persists the first generation at `setup()`. Returns the assigned
     * `keyVersion`.
     *
     * @throws MasterKeyStorageException `ALREADY_INITIALISED` when a valid
     *   generation is already persisted (a second `setup()` would strand
     *   the vault that master keys — the provider refuses earlier, this is
     *   defence in depth); `CORRUPT` when an unreadable envelope is in the
     *   way (a user-initiated reset is the only way past it); `IO` on a
     *   write failure.
     */
    fun writeInitial(row: MasterKeyRow): Int

    /**
     * Bumps [currentVersion] → new generation, copying the surviving
     * factor's wrapped bytes forward unchanged (per §3.5 step 8, bullet 1)
     * and writing the rewrapped [rewrappedFactor]'s new [wrappedBytes] /
     * [iv] / [tag]. Atomic: the previous generation is readable until the
     * new one is fully persisted. Returns the newly assigned `keyVersion`.
     *
     * @throws IllegalStateException when there is no active generation or
     *   [currentVersion] is stale.
     * @throws MasterKeyStorageException `CORRUPT` / `IO` as for [readActive]
     *   and [writeInitial].
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

/**
 * Typed failure from a [MasterKeyStorage] backend. [kind] is the only
 * thing callers switch on; the message is a short diagnostic that never
 * carries envelope or key bytes (`VaultKeyProviderImplTest`,
 * `FileMasterKeyStorageTest` assert this).
 */
internal class MasterKeyStorageException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** [reason] is the bounded, payload-free phrase the provider surfaces in its `Failed` results. */
    enum class Kind(
        val reason: String,
    ) {
        /** An envelope exists but fails its format or integrity checks. */
        CORRUPT("key envelope corrupt"),

        /** [MasterKeyStorage.writeInitial] found a valid envelope already in place. */
        ALREADY_INITIALISED("key envelope already initialised"),

        /** The backing store could not be read or written. */
        IO("key envelope io failure"),
    }
}
