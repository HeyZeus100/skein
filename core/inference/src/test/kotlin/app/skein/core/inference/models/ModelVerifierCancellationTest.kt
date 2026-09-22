// skein-v2s (plan `E3.I5`, acceptance criterion "verification is
// cancellable"): an `unload` that arrives while a 2.5 GB model is streaming
// through SHA-256 must not have to wait ~10 s for the digest to finish.
//
// Two properties are asserted here, and they are separate:
//
//   1. *Promptness* — the cancellation signal is polled once per
//      `READ_CHUNK_BYTES` chunk, so at most the read already in flight
//      completes. The tests measure that through the progress callback rather
//      than by wall clock, so they are deterministic.
//   2. *A distinct outcome* — a cancelled verification is
//      `ModelVerification.Cancelled`, never `HashMismatch`. Nothing was proven
//      about the bytes either way, and reporting "this model is tampered"
//      because the user backgrounded the app would be a false alarm the threat
//      model (`E3.I12`) would then have to answer for.

package app.skein.core.inference.models

import app.skein.core.verify.DigestOutcome
import app.skein.core.verify.LoadVerification
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import app.skein.core.verify.VerifyCancellation
import app.skein.core.verify.VerifyProgress
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Three 4 MiB chunks, so "stopped after one chunk" is distinguishable from "stopped at the end". */
private const val CHUNKS = 3

class ModelVerifierCancellationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: ImmutableModelStore
    private lateinit var manifest: ModelManifest
    private lateinit var stored: StoredModel
    private lateinit var handle: ModelHandle

    private val hashed = AtomicLong(0)
    private val cancelled = AtomicBoolean(false)

    /** Records progress, then fires the cancellation — so the first chunk is always the last one. */
    private val cancelAfterFirstChunk =
        VerifyProgress { bytes ->
            hashed.set(bytes)
            cancelled.set(true)
        }
    private val signal = VerifyCancellation { cancelled.get() }

    @Before
    fun setUp() {
        store = ImmutableModelStore(temp.newFolder("models"))
        val files =
            defaultFixtureFiles().withRole(ModelFileRole.MAIN) {
                it.copy(content = bytesOf(11, ModelVerifier.READ_CHUNK_BYTES * CHUNKS))
            }
        manifest = parsedManifest(files)
        stored = (store.import(manifest, sourceOf(files)) as ImportResult.Imported).model
        handle = (store.open(MODEL_ID) as OpenResult.Opened).handle
    }

    @After
    fun tearDown() {
        handle.release()
    }

    // ------------------------------------------------------------ promptness

    @Test
    fun `a cancelled channel digest stops after the read already in flight`() {
        ModelVerifier.sha256(handle.mainChannel, signal, cancelAfterFirstChunk)

        assertThat(hashed.get()).isEqualTo(oneChunk)
    }

    @Test
    fun `a cancelled channel digest reports Cancelled rather than a digest`() {
        val outcome = ModelVerifier.sha256(handle.mainChannel, signal, cancelAfterFirstChunk)

        assertThat(outcome).isEqualTo(DigestOutcome.Cancelled)
    }

    @Test
    fun `a cancelled stream digest stops after the read already in flight`() {
        FileInputStream(stored.main.path).use { ModelVerifier.sha256(it, signal, cancelAfterFirstChunk) }

        assertThat(hashed.get()).isEqualTo(oneChunk)
    }

    @Test
    fun `a cancelled post-mmap digest stops after the chunk already in flight`() {
        ModelVerifier.blake3(mappedMain(), signal, cancelAfterFirstChunk)

        assertThat(hashed.get()).isEqualTo(oneChunk)
    }

    // ------------------------------------------------------ distinct outcome

    @Test
    fun `a cancelled pre-mmap gate refuses with Cancelled, not HashMismatch`() {
        val result =
            ModelVerifier.verifyBeforeMmap(
                binding().toVerifyBinding(),
                handle.mainChannel,
                signal,
                cancelAfterFirstChunk,
            )

        assertThat(result).isEqualTo(ModelVerification.Cancelled(ModelFileRole.MAIN))
    }

    @Test
    fun `a cancelled load gate produces no mapping`() {
        val result =
            ModelVerifier.verifyForLoad(
                handle,
                binding(),
                cancellation = signal,
                progress = cancelAfterFirstChunk,
            )

        assertThat(result).isEqualTo(LoadVerification.Refused(ModelVerification.Cancelled(ModelFileRole.MAIN)))
    }

    // -------------------------------------------------- the uncancelled path

    @Test
    fun `an uncancelled verification still passes both gates`() {
        val result = ModelVerifier.verifyForLoad(handle, binding())

        assertThat(result).isInstanceOf(LoadVerification.Ready::class.java)
    }

    @Test
    fun `progress covers every byte of the binding when nothing cancels`() {
        val seen = AtomicLong(0)

        ModelVerifier.verifyBeforeMmap(binding().toVerifyBinding(), handle.mainChannel, progress = { seen.set(it) })

        assertThat(seen.get()).isEqualTo(stored.files.values.sumOf { it.sizeBytes })
    }

    // ------------------------------------------------------------- utilities

    private val oneChunk: Long get() = ModelVerifier.READ_CHUNK_BYTES.toLong()

    private fun mappedMain(): MappedByteBuffer =
        handle.mainChannel.map(FileChannel.MapMode.READ_ONLY, 0L, handle.mainChannel.size())

    private fun binding(): ManifestBinding = (ManifestBinding.bind(manifest, stored) as BindResult.Bound).binding
}
