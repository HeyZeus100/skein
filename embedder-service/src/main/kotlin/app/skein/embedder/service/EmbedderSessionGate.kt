package app.skein.embedder.service

/** Explicit unlock pushes alone authorize plaintext work in a fresh isolated process. */
internal class EmbedderSessionGate {
    private var authorizedEpoch = 0L
    private var revokedThrough = 0L

    @Synchronized
    fun authorize(
        epoch: Long,
        beforeAuthorize: () -> Unit = {},
    ): Boolean {
        if (epoch <= revokedThrough || epoch < authorizedEpoch) return false
        beforeAuthorize()
        authorizedEpoch = epoch
        return true
    }

    @Synchronized
    fun admits(epoch: Long): Boolean = epoch > 0L && epoch == authorizedEpoch

    /** Admission/publication and revocation have the same linearization point. */
    @Synchronized
    fun whileAuthorized(
        epoch: Long,
        action: () -> Unit,
    ): Boolean {
        if (!admits(epoch)) return false
        action()
        return true
    }

    /** Stale one-way lock pushes cannot revoke a later explicitly unlocked session. */
    @Synchronized
    fun revoke(epoch: Long): Boolean {
        if (epoch <= 0L) return false
        if (authorizedEpoch != epoch && !(authorizedEpoch == 0L && epoch >= revokedThrough)) return false
        revokedThrough = maxOf(revokedThrough, epoch)
        authorizedEpoch = 0L
        return true
    }
}
