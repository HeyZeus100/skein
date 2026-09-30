package app.skein.core.vault.db

/** Unwired, read-only proof of existing encrypted pages; returns only finite status codes. */
internal object RecoveryProofNative {
    init {
        System.loadLibrary("skein_sqlite_jni")
    }

    /** 0 verified, 1 wrong key/corrupt, 2 unsupported schema, 3 unavailable. Consumed key is wiped by caller. */
    external fun nativeVerify(
        fileName: String,
        rawKey: ByteArray,
    ): Int
}
