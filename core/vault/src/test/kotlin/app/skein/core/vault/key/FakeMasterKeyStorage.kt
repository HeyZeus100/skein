// skein-3el (E3.I2) — in-memory `MasterKeyStorage` for unit tests.

package app.skein.core.vault.key

internal class FakeMasterKeyStorage : MasterKeyStorage {
    private var active: MasterKeyRow? = null
    val rewrapCalls: MutableList<Rewrap> = mutableListOf()

    /** When set, every [readActive] throws it — simulates a corrupt / unreadable envelope (skein-txrh). */
    var failReadsWith: MasterKeyStorageException? = null

    override fun readActive(): MasterKeyRow? {
        failReadsWith?.let { throw it }
        return active
    }

    override fun writeInitial(row: MasterKeyRow): Int {
        active = row
        return row.keyVersion
    }

    override fun rewrap(
        currentVersion: Int,
        rewrappedFactor: VaultKeyProvider.Factor,
        wrappedBytes: ByteArray,
        iv: ByteArray,
        tag: ByteArray?,
        now: Long,
    ): Int {
        val prev = active ?: error("no active row")
        check(prev.keyVersion == currentVersion) { "stale currentVersion=$currentVersion" }
        rewrapCalls += Rewrap(currentVersion, rewrappedFactor, wrappedBytes, iv, now)
        val next =
            when (rewrappedFactor) {
                VaultKeyProvider.Factor.BIOMETRIC ->
                    prev.copy(
                        keyVersion = prev.keyVersion + 1,
                        wrappedBytesBiometric = wrappedBytes,
                        wrapIvBiometric = iv,
                        wrapTagBiometric = tag,
                    )
                VaultKeyProvider.Factor.DEVICE_CREDENTIAL ->
                    prev.copy(
                        keyVersion = prev.keyVersion + 1,
                        wrappedBytesCredential = wrappedBytes,
                        wrapIvCredential = iv,
                        wrapTagCredential = tag,
                    )
            }
        active = next
        return next.keyVersion
    }

    internal data class Rewrap(
        val currentVersion: Int,
        val factor: VaultKeyProvider.Factor,
        val wrappedBytes: ByteArray,
        val iv: ByteArray,
        val now: Long,
    )
}
