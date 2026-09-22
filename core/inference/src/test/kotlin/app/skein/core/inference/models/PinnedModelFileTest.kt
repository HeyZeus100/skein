// skein-v2s (plan `E3.I5` + `E10.I16`): the fd-based load discipline, on the
// JVM.
//
// The acceptance criterion is "after verification, the original path is
// replaced by another file; load still uses the verified content". That is
// only true if every read after the `dup` goes through *that descriptor's*
// channel. A single `FileInputStream(path)` anywhere after the digest — even
// one that looks like a harmless re-read — reintroduces the swap-the-file race
// that handing `/proc/self/fd/<n>` to llama.cpp exists to close, and the
// `renames the path out from under the pin` test below is what catches it.
//
// `E10.I16` (`skein-d4o3`) owns the device-lane version of the same scenario,
// where the descriptor is a real `ParcelFileDescriptor.dup()` and the engine
// really does open `/proc/self/fd/<n>`. This file proves the discipline on the
// JVM, where `/proc` does not exist: the path is asserted as a *string*, and
// the anti-swap property is asserted through the channel.

package app.skein.core.inference.models

import app.skein.core.verify.DigestAlgorithm
import app.skein.core.verify.DigestOutcome
import app.skein.core.verify.DupedDescriptor
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import app.skein.core.verify.PinResult
import app.skein.core.verify.PinnedLoad
import app.skein.core.verify.PinnedModel
import app.skein.core.verify.PinnedModelFile
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/** An arbitrary descriptor number: on the JVM nothing resolves `/proc/self/fd`, so only the formatting is checked. */
private const val FD_NUMBER = 42

class PinnedModelFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: ImmutableModelStore
    private lateinit var manifest: ModelManifest
    private lateinit var stored: StoredModel

    /** Descriptors the fake `dup` handed out; closed in [tearDown] so no test leaks an fd. */
    private val origins = mutableListOf<FileInputStream>()
    private val pins = mutableListOf<PinnedModelFile>()

    @Before
    fun setUp() {
        store = ImmutableModelStore(temp.newFolder("models"))
        val files = defaultFixtureFiles()
        manifest = parsedManifest(files)
        stored = (store.import(manifest, sourceOf(files)) as ImportResult.Imported).model
    }

    @After
    fun tearDown() {
        pins.forEach { it.close() }
        origins.forEach { runCatching { it.close() } }
    }

    // ------------------------------------------------------- the engine path

    @Test
    fun `the engine path names the duplicated descriptor`() {
        val pinned = pin(stored.main.path)

        assertThat(pinned.enginePath).isEqualTo("/proc/self/fd/$FD_NUMBER")
    }

    @Test
    fun `a pinned file reports the size of the descriptor it holds`() {
        val pinned = pin(stored.main.path)

        assertThat(pinned.sizeBytes()).isEqualTo(stored.main.sizeBytes)
    }

    // ------------------------------------------------------------ TOCTOU

    @Test
    fun `renaming the path out from under the pin does not change what the pin reads`() {
        val pinned = pin(stored.main.path)
        val verified = digestOf(pinned)

        replacePath(stored.main.path, bytesOf(99, 4_096))

        assertThat(digestOf(pinned)).isEqualTo(verified)
    }

    @Test
    fun `the pinned digest is still the digest the manifest declares after the swap`() {
        val pinned = pin(stored.main.path)

        replacePath(stored.main.path, bytesOf(99, 4_096))

        assertThat(digestOf(pinned)).isEqualTo(binding().main.expectedSha256)
    }

    @Test
    fun `the path itself really does resolve to the replacement`() {
        val replacement = bytesOf(99, 4_096)
        pin(stored.main.path)

        replacePath(stored.main.path, replacement)

        assertThat(sha256Hex(stored.main.path.readBytes())).isEqualTo(sha256Hex(replacement))
    }

    @Test
    fun `a load verified through the pin survives the path being swapped`() {
        val pinned = pin(stored.main.path)
        val binding = binding().toVerifyBinding()

        replacePath(stored.main.path, bytesOf(99, 4_096))
        val result = ModelVerifier.verifyPinned(pinned, binding)

        assertThat(result).isInstanceOf(PinnedLoad.Ready::class.java)
    }

    // ------------------------------------------------------------ companions

    @Test
    fun `a companion swapped on disk defeats a load that reads it by path`() {
        val model = PinnedModel(pin(stored.main.path))
        val tokenizer = File(stored.directory, TOKENIZER_FILE)

        replacePath(tokenizer, bytesOf(98, 512))
        val result = ModelVerifier.verifyPinned(model, binding().toVerifyBinding())

        assertThat(result)
            .isEqualTo(
                PinnedLoad.Refused(ModelVerification.HashMismatch(ModelFileRole.TOKENIZER, DigestAlgorithm.SHA256)),
            )
    }

    @Test
    fun `the same swap is harmless once the companion arrived as a descriptor`() {
        val tokenizer = File(stored.directory, TOKENIZER_FILE)
        val model = PinnedModel(pin(stored.main.path), mapOf(ModelFileRole.TOKENIZER to pin(tokenizer)))

        replacePath(tokenizer, bytesOf(98, 512))
        val result = ModelVerifier.verifyPinned(model, binding().toVerifyBinding())

        assertThat(result).isInstanceOf(PinnedLoad.Ready::class.java)
    }

    @Test
    fun `unload closes every descriptor the load pinned`() {
        val companion = pin(File(stored.directory, TOKENIZER_FILE))
        val model = PinnedModel(pin(stored.main.path), mapOf(ModelFileRole.TOKENIZER to companion))

        model.close()

        assertThat(companion.isOpen).isFalse()
    }

    // ------------------------------------------------------ lifetime and refusal

    @Test
    fun `a verified pin stays open for the model's lifetime`() {
        val pinned = pin(stored.main.path)

        ModelVerifier.verifyPinned(pinned, binding().toVerifyBinding())

        assertThat(pinned.isOpen).isTrue()
    }

    @Test
    fun `unload closes the descriptor`() {
        val pinned = pin(stored.main.path)

        pinned.close()

        assertThat(pinned.isOpen).isFalse()
    }

    @Test
    fun `closing twice is a no-op`() {
        val pinned = pin(stored.main.path)

        pinned.close()
        pinned.close()

        assertThat(pinned.isOpen).isFalse()
    }

    @Test
    fun `a refused verification closes the descriptor so there is none to hand to the engine`() {
        overwriteByte(stored.main.path, 0L, 0x7f)
        val pinned = pin(stored.main.path)

        ModelVerifier.verifyPinned(pinned, binding().toVerifyBinding())

        assertThat(pinned.isOpen).isFalse()
    }

    @Test
    fun `a tampered main file refuses the pinned load with a hash mismatch`() {
        overwriteByte(stored.main.path, 0L, 0x7f)
        val pinned = pin(stored.main.path)

        val result = ModelVerifier.verifyPinned(pinned, binding().toVerifyBinding())

        assertThat(result)
            .isEqualTo(PinnedLoad.Refused(ModelVerification.HashMismatch(ModelFileRole.MAIN, DigestAlgorithm.SHA256)))
    }

    @Test
    fun `a dup that fails refuses instead of throwing`() {
        val result = PinnedModelFile.pin { throw IOException("no descriptors") }

        assertThat(result).isEqualTo(PinResult.Refused(ModelVerification.IoFailure(ModelFileRole.MAIN, "dup")))
    }

    // ------------------------------------------------------------- utilities

    /**
     * Stands in for `ParcelFileDescriptor.dup()`.
     *
     * A JVM `FileDescriptor` cannot report its own number and `/proc` does not
     * exist here, so the fake hands out [FD_NUMBER] and shares the descriptor
     * with [origins] rather than really duplicating it. What matters for these
     * tests is the same thing that matters in production: the returned
     * [PinnedModelFile] reads through a descriptor obtained once, never through
     * the path.
     */
    private fun pin(file: File): PinnedModelFile {
        val origin = FileInputStream(file).also { origins += it }
        val result = PinnedModelFile.pin { DupedDescriptor(origin.fd, FD_NUMBER) { origin.close() } }
        return (result as PinResult.Pinned).file.also { pins += it }
    }

    private fun digestOf(pinned: PinnedModelFile): String =
        (ModelVerifier.sha256(pinned.channel) as DigestOutcome.Digested).hex

    /**
     * The swap `E10.I16` describes: a different file is renamed over the
     * verified path.
     *
     * The store sealed its directory to `0500`, which forbids renaming into
     * it, so the write bit is re-granted first — this is the actor §2.2 says
     * the mode bits cannot exclude (root, a recovery image, or our own UID
     * choosing to `chmod` back), not a hole in the store.
     */
    private fun replacePath(
        target: File,
        content: ByteArray,
    ) {
        val decoy = temp.newFile("decoy-${System.nanoTime()}")
        decoy.writeBytes(content)
        unseal(stored.directory)
        Files.move(decoy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun unseal(directory: File) {
        val view = Files.getFileAttributeView(directory.toPath(), PosixFileAttributeView::class.java)
        if (view != null) {
            view.setPermissions(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } else {
            directory.setWritable(true, true)
        }
    }

    private fun binding(): ManifestBinding = (ManifestBinding.bind(manifest, stored) as BindResult.Bound).binding
}
