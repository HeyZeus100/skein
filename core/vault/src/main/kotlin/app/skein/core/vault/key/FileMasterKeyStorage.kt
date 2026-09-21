// skein-txrh — production [MasterKeyStorage]: the key-envelope file.
//
// Why a file and not a table. `VaultLifecycle` keys `vault.db` with the
// very master this generation wraps (E2.I13 / E2.I1), so the
// `attachment_master_key` table `ATTACHMENT_ENCRYPTION.md` §3.4 placed
// INSIDE `vault.db` could never be read before the database it lives in is
// opened. The wrapped material therefore lives beside the database, at
// `<vaultDir>/keys/key-envelope.v1`: a small binary file with a fixed
// header, versioned fields and a trailing SHA-256, under the `keys/`
// directory the backup rules already exclude (`data_extraction_rules.xml`,
// `backup_rules_legacy.xml`, plan E3.I2).
//
// Why not SharedPreferences / DataStore / a second SQLite file. The
// envelope is < 256 bytes of opaque ciphertext with one hard requirement —
// atomic replace-in-place under a mid-write kill — and it must be readable
// before anything else in the process. SharedPreferences stores byte
// arrays Base64-encoded in XML, commits through its own write-then-rename
// with no fsync we control, and is a `sharedpref` backup domain of its own
// to keep excluded; DataStore adds a serialisation dependency and a
// coroutine-only API to the first read on the unlock path; a second SQLite
// file brings a journal, WAL and connection lifecycle for one row. A file
// under `keys/` is the smallest thing that satisfies the requirement and
// the existing backup posture.
//
// Integrity. The two wrapped blobs are AES-GCM outputs (auth tag
// appended), so any change to wrapped bytes or IV is detected at unwrap by
// the Keystore cipher itself. The header fields (`key_version`, flags,
// aliases) are NOT covered by GCM — the wrap uses no AAD — so a SHA-256
// over the whole body covers them against accidental corruption (torn
// write, bit-rot). It is a checksum, not a MAC: an adversary who can
// rewrite an app-private file already holds the app's UID or root, which
// spec §9 puts out of scope, and the worst a header rewrite achieves is a
// flipped `strongBoxBacked` / `key_version` — neither reveals nor alters
// the master. Binding the header as GCM AAD is a candidate follow-up that
// needs the crypto review §1 of that document calls for (bd skein-oz4v).
//
// Confidentiality. None beyond Keystore wrapping: the file holds only
// Keystore-wrapped bytes, never the plaintext master (§6;
// `FileMasterKeyStorageTest` asserts no window of the master appears).
//
// Byte layout (big-endian; `FORMAT_VERSION = 1`):
//
//   off    len  field
//   0      8    magic          "SKEINKEY"
//   8      2    format         u16 = 1
//   10     4    key_version    u32 (>= 1)
//   14     8    created_at     i64, Unix ms
//   22     1    flags          bit0 = strongBoxBacked; other bits reserved (must be 0)
//   23     1    factor_count   = 2
//   24     ..   factor record × 2, in enum order (BIOMETRIC, DEVICE_CREDENTIAL):
//                 1    factor_id     0x01 BIOMETRIC, 0x02 DEVICE_CREDENTIAL
//                 2+n  alias         u16 length + UTF-8. Informational in v1
//                                    (the aliases are fixed constants); kept so
//                                    an alias rotation (§3.5 step 5) needs no
//                                    format bump.
//                 2+n  wrapped_bytes u16 length; 0 = factor absent / dead
//                 2+n  wrap_iv       u16 length; 0 = absent (null-together with wrapped_bytes)
//                 2+n  wrap_tag      u16 length; 0 = absent (the GCM tag rides inside wrapped_bytes in v1)
//   end-32 32   sha256         over bytes [0, end-32)
//
// A field length above `MAX_FIELD_LEN`, a missing / duplicated / unknown
// factor, trailing bytes, a bad magic or format, unknown flag bits or a
// digest mismatch all decode as `CORRUPT`.
//
// Writes: serialise → write `key-envelope.v1.tmp` in the same directory →
// fsync the file → atomic rename over the target → best-effort fsync of
// `keys/`. A kill at any point leaves either the previous envelope or the
// new one on disk, never a torn file; a stale `.tmp` is never read and is
// overwritten by the next write. That rename IS the "single logical
// transaction" [MasterKeyStorage.rewrap] demands (§3.5 step 8): the
// surviving factor's bytes and the rewrapped factor's bytes land together.
// No superseded generation is retained — `VaultKeyProviderImpl.rewrapWith`
// reuses the dead factor's alias (delete + create), so the old wrapped
// bytes are unrecoverable the moment a rewrap begins and keeping them
// would be dead weight.

package app.skein.core.vault.key

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/**
 * [MasterKeyStorage] over the key-envelope file at [file] (normally
 * [envelopeFileIn] of the vault directory). Constructing it touches
 * nothing on disk; every method is serialised on an internal lock and
 * re-reads the file (no caching), so two instances over one path see the
 * same generation.
 */
internal class FileMasterKeyStorage(
    private val file: File,
) : MasterKeyStorage {
    private val lock = Any()

    override fun readActive(): MasterKeyRow? =
        synchronized(lock) {
            when (val envelope = readEnvelope()) {
                Envelope.Absent -> null
                is Envelope.Present -> envelope.row
            }
        }

    override fun writeInitial(row: MasterKeyRow): Int =
        synchronized(lock) {
            if (readEnvelope() is Envelope.Present) {
                throw MasterKeyStorageException(
                    MasterKeyStorageException.Kind.ALREADY_INITIALISED,
                    MasterKeyStorageException.Kind.ALREADY_INITIALISED.reason,
                )
            }
            writeAtomically(row)
            row.keyVersion
        }

    override fun rewrap(
        currentVersion: Int,
        rewrappedFactor: VaultKeyProvider.Factor,
        wrappedBytes: ByteArray,
        iv: ByteArray,
        tag: ByteArray?,
        now: Long,
    ): Int =
        synchronized(lock) {
            val prev = readActive() ?: error("no active master key envelope")
            check(prev.keyVersion == currentVersion) {
                "stale currentVersion=$currentVersion (active=${prev.keyVersion})"
            }
            val next =
                when (rewrappedFactor) {
                    VaultKeyProvider.Factor.BIOMETRIC ->
                        prev.copy(
                            keyVersion = prev.keyVersion + 1,
                            wrappedBytesBiometric = wrappedBytes,
                            wrapIvBiometric = iv,
                            wrapTagBiometric = tag,
                            createdAt = now,
                        )
                    VaultKeyProvider.Factor.DEVICE_CREDENTIAL ->
                        prev.copy(
                            keyVersion = prev.keyVersion + 1,
                            wrappedBytesCredential = wrappedBytes,
                            wrapIvCredential = iv,
                            wrapTagCredential = tag,
                            createdAt = now,
                        )
                }
            writeAtomically(next)
            next.keyVersion
        }

    // ---- read ------------------------------------------------------------

    private sealed class Envelope {
        object Absent : Envelope()

        class Present(
            val row: MasterKeyRow,
        ) : Envelope()
    }

    private fun readEnvelope(): Envelope {
        if (!file.exists()) return Envelope.Absent
        val bytes =
            try {
                file.readBytes()
            } catch (e: IOException) {
                throw MasterKeyStorageException(
                    MasterKeyStorageException.Kind.IO,
                    "key envelope read failed: ${e.javaClass.simpleName}",
                    e,
                )
            }
        return Envelope.Present(decode(bytes))
    }

    // ---- write -----------------------------------------------------------

    private fun writeAtomically(row: MasterKeyRow) {
        val bytes = encode(row)
        val target = file.absoluteFile
        val dir = target.parentFile ?: throw ioFailure(IOException("envelope path has no parent directory"))
        val tmp = File(dir, target.name + TMP_SUFFIX)
        try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("could not create the keys directory")
            }
            FileOutputStream(tmp, false).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            restrictToOwner(tmp)
            // ATOMIC_MOVE is rename(2) on every platform this runs on: the
            // target is replaced in one step or not at all.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(dir)
        } catch (e: IOException) {
            tmp.delete()
            throw ioFailure(e)
        }
    }

    private fun ioFailure(e: IOException): MasterKeyStorageException =
        MasterKeyStorageException(
            MasterKeyStorageException.Kind.IO,
            "key envelope write failed: ${e.javaClass.simpleName}",
            e,
        )

    /** Best effort: app-private storage is already owner-only on Android; the host JVM may not be. */
    private fun restrictToOwner(f: File) {
        try {
            Files.setPosixFilePermissions(
                f.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem: nothing to restrict.
        } catch (_: IOException) {
            // Permission change failed; the rename still goes ahead.
        }
    }

    /** Best effort: makes the rename itself durable. Some filesystems refuse to fsync a directory fd. */
    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // The data file was already fsynced; only the rename's durability is best effort.
        }
    }

    internal companion object {
        const val KEYS_DIR_NAME: String = "keys"
        const val ENVELOPE_FILE_NAME: String = "key-envelope.v1"
        const val TMP_SUFFIX: String = ".tmp"

        const val FORMAT_VERSION: Int = 1
        val MAGIC: ByteArray = "SKEINKEY".toByteArray(Charsets.US_ASCII)
        const val HEADER_LEN: Int = 24
        const val DIGEST_LEN: Int = 32
        const val DIGEST_ALGORITHM: String = "SHA-256"
        const val MAX_FIELD_LEN: Int = 4096
        const val FACTOR_COUNT: Int = 2
        const val FACTOR_ID_BIOMETRIC: Int = 0x01
        const val FACTOR_ID_CREDENTIAL: Int = 0x02
        const val FLAG_STRONGBOX: Int = 0x01
        const val FLAGS_KNOWN: Int = FLAG_STRONGBOX

        /** `<vaultDir>/keys/key-envelope.v1` — the `keys/` prefix is what the backup exclusion rules name. */
        fun envelopeFileIn(vaultDir: File): File = File(File(vaultDir, KEYS_DIR_NAME), ENVELOPE_FILE_NAME)

        fun digestOf(body: ByteArray): ByteArray = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(body)

        fun encode(row: MasterKeyRow): ByteArray {
            val body = ByteArrayOutputStream(HEADER_LEN + 2 * 128)
            DataOutputStream(body).use { out ->
                out.write(MAGIC)
                out.writeShort(FORMAT_VERSION)
                out.writeInt(row.keyVersion)
                out.writeLong(row.createdAt)
                out.writeByte(if (row.strongBoxBacked) FLAG_STRONGBOX else 0)
                out.writeByte(FACTOR_COUNT)
                writeFactor(
                    out,
                    FACTOR_ID_BIOMETRIC,
                    VaultKeyProviderImpl.ALIAS_BIOMETRIC,
                    row.wrappedBytesBiometric,
                    row.wrapIvBiometric,
                    row.wrapTagBiometric,
                )
                writeFactor(
                    out,
                    FACTOR_ID_CREDENTIAL,
                    VaultKeyProviderImpl.ALIAS_CREDENTIAL,
                    row.wrappedBytesCredential,
                    row.wrapIvCredential,
                    row.wrapTagCredential,
                )
            }
            val bodyBytes = body.toByteArray()
            return bodyBytes + digestOf(bodyBytes)
        }

        private fun writeFactor(
            out: DataOutputStream,
            id: Int,
            alias: String,
            wrapped: ByteArray?,
            iv: ByteArray?,
            tag: ByteArray?,
        ) {
            require((wrapped == null) == (iv == null)) { "wrapped bytes and IV must be null together" }
            out.writeByte(id)
            writeField(out, alias.toByteArray(Charsets.UTF_8))
            writeField(out, wrapped)
            writeField(out, iv)
            writeField(out, tag)
        }

        private fun writeField(
            out: DataOutputStream,
            value: ByteArray?,
        ) {
            val bytes = value ?: ByteArray(0)
            require(bytes.size <= MAX_FIELD_LEN) { "envelope field too long" }
            out.writeShort(bytes.size)
            out.write(bytes)
        }

        fun decode(bytes: ByteArray): MasterKeyRow {
            if (bytes.size < HEADER_LEN + DIGEST_LEN) corrupt("too short")
            val bodyLen = bytes.size - DIGEST_LEN
            val expected = digestOf(bytes.copyOf(bodyLen))
            val actual = bytes.copyOfRange(bodyLen, bytes.size)
            if (!MessageDigest.isEqual(expected, actual)) corrupt("integrity digest mismatch")

            val input = DataInputStream(ByteArrayInputStream(bytes, 0, bodyLen))
            try {
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) corrupt("bad magic")
                val format = input.readUnsignedShort()
                if (format != FORMAT_VERSION) corrupt("unsupported format $format")
                val keyVersion = input.readInt()
                if (keyVersion < 1) corrupt("bad key_version")
                val createdAt = input.readLong()
                val flags = input.readUnsignedByte()
                if (flags and FLAGS_KNOWN.inv() != 0) corrupt("unknown flags")
                val factorCount = input.readUnsignedByte()
                if (factorCount != FACTOR_COUNT) corrupt("bad factor count")

                var biometric: FactorRecord? = null
                var credential: FactorRecord? = null
                for (i in 0 until FACTOR_COUNT) {
                    val id = input.readUnsignedByte()
                    val alias = readField(input)
                    if (alias.isEmpty()) corrupt("empty alias")
                    val wrapped = readField(input).orNull()
                    val iv = readField(input).orNull()
                    val tag = readField(input).orNull()
                    val record = FactorRecord(wrapped, iv, tag)
                    if ((wrapped == null) != (iv == null)) corrupt("wrapped bytes and IV not null-together")
                    when (id) {
                        FACTOR_ID_BIOMETRIC -> {
                            if (biometric != null) corrupt("duplicate factor")
                            biometric = record
                        }
                        FACTOR_ID_CREDENTIAL -> {
                            if (credential != null) corrupt("duplicate factor")
                            credential = record
                        }
                        else -> corrupt("unknown factor id")
                    }
                }
                if (input.available() != 0) corrupt("trailing bytes")
                val bio = biometric ?: corrupt("missing biometric factor")
                val cred = credential ?: corrupt("missing credential factor")
                return MasterKeyRow(
                    keyVersion = keyVersion,
                    wrappedBytesBiometric = bio.wrapped,
                    wrapIvBiometric = bio.iv,
                    wrapTagBiometric = bio.tag,
                    wrappedBytesCredential = cred.wrapped,
                    wrapIvCredential = cred.iv,
                    wrapTagCredential = cred.tag,
                    createdAt = createdAt,
                    strongBoxBacked = flags and FLAG_STRONGBOX != 0,
                )
            } catch (_: EOFException) {
                corrupt("truncated")
            }
        }

        private fun readField(input: DataInputStream): ByteArray {
            val len = input.readUnsignedShort()
            if (len > MAX_FIELD_LEN) corrupt("field too long")
            return ByteArray(len).also(input::readFully)
        }

        private fun ByteArray.orNull(): ByteArray? = if (isEmpty()) null else this

        /** Messages name the check that failed and nothing from the file. */
        private fun corrupt(what: String): Nothing =
            throw MasterKeyStorageException(
                MasterKeyStorageException.Kind.CORRUPT,
                "${MasterKeyStorageException.Kind.CORRUPT.reason}: $what",
            )

        private class FactorRecord(
            val wrapped: ByteArray?,
            val iv: ByteArray?,
            val tag: ByteArray?,
        )
    }
}
