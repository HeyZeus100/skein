// skein-st1r: shared fixtures for the §2 model-verification tests.
//
// Everything is deterministic and tiny: the point of these tests is the
// verification logic and the filesystem contract, not throughput, so the
// "model" is a few kilobytes of pseudo-random bytes rather than a real GGUF.

package app.skein.core.inference.models

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

internal const val MODEL_ID = "qwen-2-5-3b-test"
internal const val MAIN_FILE = "model.gguf"
internal const val TOKENIZER_FILE = "tokenizer.json"
internal const val LICENSE_FILE = "LICENSE"

/** Deterministic pseudo-random bytes; a different [seed] gives different content of the same length. */
internal fun bytesOf(
    seed: Int,
    length: Int,
): ByteArray {
    val random = java.util.Random(seed.toLong())
    return ByteArray(length).also { random.nextBytes(it) }
}

internal fun sha256Hex(bytes: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

/** A file the fixture manifest declares, plus the bytes an import source would hand over for it. */
internal data class FixtureFile(
    val role: ModelFileRole,
    val name: String,
    val content: ByteArray,
    val required: Boolean = true,
    /** Overrides the sha256 written into the manifest, to fake a mismatch. */
    val declaredSha256: String? = null,
    /** When true, the manifest also declares the BLAKE3 of [content]. */
    val declareBlake3: Boolean = false,
    /** When true, the import source returns null for this entry. */
    val absentFromSource: Boolean = false,
) {
    val manifestSha256: String get() = declaredSha256 ?: sha256Hex(content)
}

internal fun defaultFixtureFiles(): List<FixtureFile> =
    listOf(
        FixtureFile(ModelFileRole.MAIN, MAIN_FILE, bytesOf(1, 4_096)),
        FixtureFile(ModelFileRole.TOKENIZER, TOKENIZER_FILE, bytesOf(2, 512)),
        FixtureFile(ModelFileRole.LICENSE, LICENSE_FILE, "Apache-2.0\n".toByteArray()),
    )

/** Replaces the entry with [role], leaving the rest of the fixture set alone. */
internal fun List<FixtureFile>.withRole(
    role: ModelFileRole,
    transform: (FixtureFile) -> FixtureFile,
): List<FixtureFile> = map { if (it.role == role) transform(it) else it }

/** Renders [files] as a v2 `*.skein.json` manifest document. */
internal fun manifestJson(
    files: List<FixtureFile>,
    id: String = MODEL_ID,
    manifestVersion: Int = ModelManifest.SUPPORTED_VERSION,
    license: String = "apache-2.0",
): String {
    val main = files.first { it.role == ModelFileRole.MAIN }
    val companions =
        files.filter { it.role != ModelFileRole.MAIN }.joinToString(",\n") { entry ->
            """
            |    {
            |      "role": "${entry.role.wire}",
            |      "file": "${entry.name}",
            |      "sha256": "${entry.manifestSha256}",
            |      "size_bytes": ${entry.content.size},
            |      "required": ${entry.required}${blake3Field(entry)}
            |    }
            """.trimMargin()
        }
    return """
        |{
        |  "manifest_version": $manifestVersion,
        |  "id": "$id",
        |  "file": "${main.name}",
        |  "sha256": "${main.manifestSha256}",
        |  "size_bytes": ${main.content.size},
        |  "license": "$license"${blake3Field(main)},
        |  "companions": [
        |$companions
        |  ]
        |}
        """.trimMargin()
}

private fun blake3Field(entry: FixtureFile): String =
    if (entry.declareBlake3) ",\n      \"blake3\": \"${Blake3.hexDigest(entry.content)}\"" else ""

internal fun parsedManifest(files: List<FixtureFile> = defaultFixtureFiles()): ModelManifest =
    (ModelManifest.parse(manifestJson(files)) as ManifestParse.Parsed).manifest

/** An import source backed by the fixture's in-memory bytes. */
internal fun sourceOf(files: List<FixtureFile>): ModelBytesSource =
    ModelBytesSource { entry ->
        val fixture = files.firstOrNull { it.name == entry.file }
        when {
            fixture == null || fixture.absentFromSource -> null
            else -> ByteArrayInputStream(fixture.content)
        }
    }

/**
 * Overwrites [offset] in [target] with [byte], re-granting the write bit first.
 *
 * This is the hostile actor §2.2 says the store's `0400` cannot exclude (root,
 * a recovery image, or our own UID choosing to `chmod` back). Tests use it to
 * make the in-place-modification attack concrete.
 */
internal fun overwriteByte(
    target: File,
    offset: Long,
    byte: Byte,
) {
    val path = target.toPath()
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
    if (view != null) {
        view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
    } else {
        target.setWritable(true, true)
    }
    RandomAccessFile(target, "rw").use {
        it.seek(offset)
        it.writeByte(byte.toInt())
        it.fd.sync()
    }
}

/** POSIX permission bits of [target], or null when the filesystem has no POSIX view. */
internal fun posixPermissions(target: File): Set<PosixFilePermission>? =
    Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java)?.readAttributes()?.permissions()
