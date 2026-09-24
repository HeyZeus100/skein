// skein-st1r: §2.4's `PostMmapDigestTest`, `CompanionMismatchTest`,
// `ConstantTimeCompareTest` and `ToctouExtendedTest`, run on the JVM.
//
// The headline case is `in-place modification between the two gates ...`: the
// review asked for a test where the attacker modifies the file *after* the
// hash check and *before* the mmap. `LoadPhaseHook` gives that attacker a
// scheduling point inside the real verifier — the test drives
// `ModelVerifier.verifyForLoad` itself, not a reimplementation of it — and
// `overwriteByte` performs the write the way an actor with write access
// would. A `MAP_PRIVATE`-style "the mapping is a snapshot" assumption would
// make this test fail; the post-mmap BLAKE3 pass is what makes it pass.

package app.skein.core.inference.models

import app.skein.core.model.Blake3
import app.skein.core.verify.DigestAlgorithm
import app.skein.core.verify.LoadPhaseHook
import app.skein.core.verify.LoadVerification
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import app.skein.core.verify.VerifyBinding
import app.skein.core.verify.VerifyFile
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.MappedByteBuffer

class ModelVerifierTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ImmutableModelStore
    private lateinit var manifest: ModelManifest
    private lateinit var stored: StoredModel
    private var handle: ModelHandle? = null

    @Before
    fun setUp() {
        root = temp.newFolder("models")
        store = ImmutableModelStore(root)
        val files = defaultFixtureFiles()
        manifest = parsedManifest(files)
        stored = (store.import(manifest, sourceOf(files)) as ImportResult.Imported).model
    }

    @After
    fun tearDown() {
        handle?.release()
    }

    // ------------------------------------------------------- constant time

    @Test
    fun `constant time compare accepts equal digests`() {
        assertThat(ModelVerifier.constantTimeEquals(stored.main.sha256, stored.main.sha256)).isTrue()
    }

    @Test
    fun `constant time compare rejects a one-nibble difference`() {
        val flipped = stored.main.sha256.let { it.dropLast(1) + if (it.last() == 'a') 'b' else 'a' }

        assertThat(ModelVerifier.constantTimeEquals(stored.main.sha256, flipped)).isFalse()
    }

    @Test
    fun `constant time compare rejects non-hex input instead of throwing`() {
        assertThat(ModelVerifier.constantTimeEquals(stored.main.sha256, "not-a-digest")).isFalse()
    }

    // ----------------------------------------------------------- happy path

    @Test
    fun `binding a complete manifest succeeds`() {
        assertThat(ManifestBinding.bind(manifest, stored)).isInstanceOf(BindResult.Bound::class.java)
    }

    @Test
    fun `pre-mmap gate passes for an untouched import`() {
        val open = openHandle()

        assertThat(ModelVerifier.verifyBeforeMmap(open, binding())).isEqualTo(ModelVerification.Verified)
    }

    @Test
    fun `full load gate returns a mapping when both digests match`() {
        val result = ModelVerifier.verifyForLoad(openHandle(), binding())

        assertThat(result).isInstanceOf(LoadVerification.Ready::class.java)
    }

    @Test
    fun `the mapped region digests to the manifest blake3`() {
        val result = ModelVerifier.verifyForLoad(openHandle(), binding()) as LoadVerification.Ready

        assertThat(ModelVerifier.blake3Hex(result.mapped)).isEqualTo(stored.main.blake3)
    }

    // -------------------------------------------------- the §2 attack itself

    @Test
    fun `in-place modification between the two gates is detected as Tampered`() {
        val open = openHandle()
        val attacker =
            object : LoadPhaseHook {
                override fun afterPreMmapVerify(binding: VerifyBinding) {
                    overwriteByte(stored.main.path, offset = 1_024L, byte = 0x5A)
                }
            }

        val result = ModelVerifier.verifyForLoad(open, binding(), attacker)

        assertThat(
            (result as LoadVerification.Refused).refusal,
        ).isEqualTo(ModelVerification.Tampered(ModelFileRole.MAIN))
    }

    @Test
    fun `in-place modification while the region is mapped is detected as Tampered`() {
        val open = openHandle()
        val attacker =
            object : LoadPhaseHook {
                override fun afterMap(mapped: MappedByteBuffer) {
                    overwriteByte(stored.main.path, offset = 2_048L, byte = 0x17)
                }
            }

        val result = ModelVerifier.verifyForLoad(open, binding(), attacker)

        assertThat(
            (result as LoadVerification.Refused).refusal,
        ).isEqualTo(ModelVerification.Tampered(ModelFileRole.MAIN))
    }

    @Test
    fun `without a declared blake3 the post-mmap gate re-hashes the mapping with sha256 and still catches the write`() {
        // skein-gg11.18 adopted rows carry no BLAKE3; gate 2 must still be a
        // second digest over the MAPPING, taken after gate 1 read the file.
        val open = openHandle()
        val attacker =
            object : LoadPhaseHook {
                override fun afterPreMmapVerify(binding: VerifyBinding) {
                    overwriteByte(stored.main.path, offset = 1_024L, byte = 0x5A)
                }
            }

        val result = ModelVerifier.verifyForLoad(open.mainChannel, sha256OnlyBinding(), attacker)

        assertThat(
            (result as LoadVerification.Refused).refusal,
        ).isEqualTo(ModelVerification.Tampered(ModelFileRole.MAIN))
    }

    @Test
    fun `without a declared blake3 an untouched model passes both gates`() {
        val result = ModelVerifier.verifyForLoad(openHandle().mainChannel, sha256OnlyBinding())

        assertThat(result).isInstanceOf(LoadVerification.Ready::class.java)
    }

    @Test
    fun `the pre-mmap pass observes a blake3 only when asked`() {
        // Default off: the pure-Kotlin BLAKE3 is the 13-minute pass on the
        // Fold (skein-gg11.17) and gate 2 no longer depends on it.
        val open = openHandle()

        val channel = open.mainChannel

        assertThat(ModelVerifier.verifyBeforeMmapDetailed(sha256OnlyBinding(), channel).mainBlake3).isNull()
        assertThat(
            ModelVerifier.verifyBeforeMmapDetailed(sha256OnlyBinding(), channel, observeBlake3 = true).mainBlake3,
        ).isEqualTo(stored.main.blake3)
    }

    private fun sha256OnlyBinding(): VerifyBinding =
        VerifyBinding(
            listOf(
                VerifyFile(
                    role = ModelFileRole.MAIN,
                    expectedSha256 = stored.main.sha256,
                    expectedSizeBytes = stored.main.sizeBytes,
                    expectedBlake3 = null,
                    path = stored.main.path,
                ),
            ),
        )

    @Test
    fun `modification before the pre-mmap gate is caught by sha256, not by blake3`() {
        overwriteByte(stored.main.path, offset = 8L, byte = 0x01)

        val result = ModelVerifier.verifyForLoad(openHandle(), binding())

        assertThat((result as LoadVerification.Refused).refusal)
            .isEqualTo(ModelVerification.HashMismatch(ModelFileRole.MAIN, DigestAlgorithm.SHA256))
    }

    @Test
    fun `post-mmap gate refuses a blake3 expectation the mapped bytes do not meet`() {
        val open = openHandle()
        val foreign = stored.main.copy(blake3 = FOREIGN_BLAKE3)
        val wrongBlake3 = stored.copy(files = stored.files + (ModelFileRole.MAIN to foreign))

        val result = ModelVerifier.verifyForLoad(open, binding(wrongBlake3))

        assertThat(
            (result as LoadVerification.Refused).refusal,
        ).isEqualTo(ModelVerification.Tampered(ModelFileRole.MAIN))
    }

    // ---------------------------------------------------- companion coverage

    @Test
    fun `a companion tampered after import is caught on the next load`() {
        overwriteByte(File(stored.directory, TOKENIZER_FILE), offset = 4L, byte = 0x7F)

        val result = ModelVerifier.verifyForLoad(openHandle(), binding())

        assertThat((result as LoadVerification.Refused).refusal)
            .isEqualTo(ModelVerification.HashMismatch(ModelFileRole.TOKENIZER, DigestAlgorithm.SHA256))
    }

    @Test
    fun `a companion the manifest does not cover refuses the binding`() {
        val uncovered = File(stored.directory, "extra-tokenizer.json")
        writeIntoSealedDirectory(uncovered, "{}".toByteArray())

        val result = ManifestBinding.bind(manifest, stored)

        assertThat((result as BindResult.Refused).refusal)
            .isEqualTo(ModelVerification.UncoveredFile("extra-tokenizer.json"))
    }

    @Test
    fun `a manifest companion absent from the store refuses the binding`() {
        val withoutTokenizer = stored.copy(files = stored.files - ModelFileRole.TOKENIZER)

        val result = ManifestBinding.bind(manifest, withoutTokenizer)

        assertThat((result as BindResult.Refused).refusal)
            .isEqualTo(ModelVerification.CompanionMissing(ModelFileRole.TOKENIZER, TOKENIZER_FILE))
    }

    @Test
    fun `binding covers every file the loader may open`() {
        assertThat(binding().files.map { it.role })
            .containsExactly(ModelFileRole.MAIN, ModelFileRole.TOKENIZER, ModelFileRole.LICENSE)
    }

    @Test
    fun `every bound file has a post-mmap expectation`() {
        assertThat(binding().files.map { it.expectedBlake3.length }).containsExactly(64, 64, 64)
    }

    // ---------------------------------------------------------------- helpers

    private fun openHandle(): ModelHandle = ((store.open(MODEL_ID) as OpenResult.Opened).handle).also { handle = it }

    private fun binding(model: StoredModel = stored): ManifestBinding =
        (ManifestBinding.bind(manifest, model) as BindResult.Bound).binding

    /** Adds a file to a `0500` directory the way a privileged actor would. */
    private fun writeIntoSealedDirectory(
        target: File,
        content: ByteArray,
    ) {
        val parent = checkNotNull(target.parentFile)
        parent.setWritable(true, true)
        target.writeBytes(content)
        parent.setWritable(false, false)
    }

    private companion object {
        val FOREIGN_BLAKE3 = Blake3.hex("not the model".toByteArray())
    }
}
