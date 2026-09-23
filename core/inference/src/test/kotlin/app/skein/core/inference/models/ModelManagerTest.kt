// skein-cyq's five acceptance criteria (verbatim) + skein-r8ah's four
// integrity assertions (PocketPal/OfflineLLM field failures, PP-14/PP-29/
// PP-30/PP-73). Uses the same fixture infrastructure
// (`ModelStoreFixtures.kt`) `ImmutableModelStoreTest` uses, over a real
// `ImmutableModelStore` on a `TemporaryFolder` — only the picker/inspector
// boundary is faked, since those are exactly the seams this bead adds.
//
// Robolectric: `ImportSource.Picked` carries a real `android.net.Uri`, and
// this module carries no `testOptions.unitTests.isReturnDefaultValues`
// (unlike `:core:vault`) — `android.net.Uri.parse` needs a real Android
// runtime, which is what `WireBindingsTest` already reaches for the same
// reason (`android.os.ParcelFileDescriptor`).

package app.skein.core.inference.models

import android.net.Uri
import app.skein.core.model.ModelId
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.ipc.ErrorCode
import app.skein.ipc.ModelInspection
import app.skein.testing.InMemoryModelRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ModelManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ImmutableModelStore
    private lateinit var registry: InMemoryModelRegistry
    private val loaded: MutableSet<ModelId> = mutableSetOf()
    private var free: Long = Long.MAX_VALUE / 2

    @Before
    fun setUp() {
        root = temp.newFolder("models")
        store = ImmutableModelStore(root)
        registry = InMemoryModelRegistry()
    }

    private fun manager(
        inspector: ModelInspector = fakeInspector(),
        pickedReader: PickedFileReader = FakePickedFileReader(),
        bundled: ModelBytesSource = sourceOf(defaultFixtureFiles()),
    ): ModelManager =
        ModelManager(
            registry = registry,
            store = store,
            modelInspector = inspector,
            pickedFileReader = pickedReader,
            bundledSource = bundled,
            freeBytes = { free },
            isLoaded = { loaded.contains(it) },
        )

    private fun fakeInspector(
        errorCode: Int = ErrorCode.OK,
        architecture: String? = "llama",
        hasVision: Boolean = false,
        contextLength: Int? = 8_192,
    ): ModelInspector =
        ModelInspector {
            ModelInspection(
                errorCode = errorCode,
                architecture = architecture,
                quantization = "Q4_K_M",
                parameterCount = null,
                contextLength = contextLength,
                embeddingWidth = 4_096,
                hasVision = hasVision,
                hasChatTemplate = true,
                chatTemplateOk = true,
                tokenizerModel = "llama",
            )
        }

    private class FakePickedFileReader(
        private val name: String? = "picked-model.gguf",
        private val bytes: ByteArray = bytesOf(7, 8_192),
        private val reportedSize: Long? = null,
    ) : PickedFileReader {
        var openCount: Int = 0

        override fun open(uri: Uri): PickedFileHandle {
            openCount++
            return PickedFileHandle(
                displayName = name,
                sizeBytes = reportedSize ?: bytes.size.toLong(),
                stream = ByteArrayInputStream(bytes),
            )
        }
    }

    private fun outcomeOf(events: List<ImportProgress>): ImportOutcome = (events.last() as ImportProgress.Done).outcome

    /**
     * A minimal, [GgufPreCheck]-plausible header (magic, version 3, zero
     * tensors, zero KV pairs) followed by [payload]. `ImportSource.Picked`
     * always runs the bounded pre-check before registering, so any fixture
     * exercising that path needs a real GGUF-shaped prefix — plain random
     * bytes are correctly refused as [ImportRefusal.StructurallyInvalid],
     * which is the pre-check doing its job, not a bug to route around with
     * anything less than a valid header.
     */
    private fun validGguf(payload: ByteArray): ByteArray {
        val header =
            java.nio.ByteBuffer
                .allocate(24)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt(GgufPreCheck.GGUF_MAGIC.toInt())
        header.putInt(3) // version
        header.putLong(0L) // tensorCount
        header.putLong(0L) // kvCount
        return header.array() + payload
    }

    // ==================================================================
    // skein-cyq acceptance criteria (verbatim)
    // ==================================================================

    @Test
    fun `AC1 import of a fixture file with a matching bundled manifest`(): Unit =
        runTest {
            val files = defaultFixtureFiles()
            val manifest = parsedManifest(files)
            val events = manager().import(ImportSource.Bundled(manifest)).toList()

            val outcome = outcomeOf(events) as ImportOutcome.Imported
            assertThat(registry.get(manifest.id)).isEqualTo(outcome.record)
            assertThat(File(outcome.record.model.path).exists()).isTrue()

            val lastProgress = events.filterIsInstance<ImportProgress.InProgress>().last()
            assertThat(lastProgress.bytesProcessed).isEqualTo(lastProgress.totalBytes)
        }

    @Test
    fun `AC2 import with a wrong-hash manifest leaves no file and returns HashMismatch`(): Unit =
        runTest {
            val wrongHash = sha256Hex(bytesOf(99, 4_096))
            val files = defaultFixtureFiles().withRole(ModelFileRole.MAIN) { it.copy(declaredSha256 = wrongHash) }
            val manifest = parsedManifest(files)
            val outcome = outcomeOf(manager(bundled = sourceOf(files)).import(ImportSource.Bundled(manifest)).toList())

            val refusal = ((outcome as ImportOutcome.Refused).refusal as ImportRefusal.FromStore).refusal
            assertThat(refusal).isInstanceOf(ModelVerification.HashMismatch::class.java)
            assertThat(File(root, manifest.id).exists()).isFalse()
        }

    @Test
    fun `AC3 import without manifest generates UNKNOWN license and correct size_bytes`(): Unit =
        runTest {
            val bytes = validGguf(bytesOf(42, 16_384))
            val uri = Uri.parse("content://fake.authority/picked/1")
            val events =
                manager(pickedReader = FakePickedFileReader(bytes = bytes))
                    .import(ImportSource.Picked(uri))
                    .toList()

            val record = (outcomeOf(events) as ImportOutcome.Imported).record
            assertThat(record.licenseSpdx).isEqualTo(ModelManager.UNKNOWN_LICENSE)
            assertThat(record.model.sizeBytes).isEqualTo(bytes.size.toLong())
        }

    @Test
    fun `AC4 insufficient space refuses before any write`(): Unit =
        runTest {
            free = 0L
            val manifest = parsedManifest(defaultFixtureFiles())
            val outcome = outcomeOf(manager().import(ImportSource.Bundled(manifest)).toList())

            assertThat(
                (outcome as ImportOutcome.Refused).refusal,
            ).isInstanceOf(ImportRefusal.InsufficientSpace::class.java)
            assertThat(File(root, manifest.id).exists()).isFalse()
        }

    @Test
    fun `AC5 setDefault then default round trips`(): Unit =
        runTest {
            val manifest = parsedManifest(defaultFixtureFiles())
            val mgr = manager()
            mgr.import(ImportSource.Bundled(manifest)).toList()

            mgr.setDefault(manifest.id)

            assertThat(mgr.default()).isEqualTo(manifest.id)
        }

    @Test
    fun `AC5 default is null then clears back to null`(): Unit =
        runTest {
            val mgr = manager()
            assertThat(mgr.default()).isNull()

            val manifest = parsedManifest(defaultFixtureFiles())
            mgr.import(ImportSource.Bundled(manifest)).toList()
            mgr.setDefault(manifest.id)
            mgr.setDefault(null)

            assertThat(mgr.default()).isNull()
        }

    @Test
    fun `AC5 delete of the loaded model is refused`(): Unit =
        runTest {
            val manifest = parsedManifest(defaultFixtureFiles())
            val mgr = manager()
            mgr.import(ImportSource.Bundled(manifest)).toList()
            loaded += manifest.id

            val result = mgr.delete(manifest.id)

            assertThat(result).isEqualTo(DeleteOutcome.Refused(DeleteRefusalReason.Loaded))
            assertThat(registry.get(manifest.id)).isNotNull()
        }

    @Test
    fun `delete of an idle model removes the row and clears the default`(): Unit =
        runTest {
            val manifest = parsedManifest(defaultFixtureFiles())
            val mgr = manager()
            mgr.import(ImportSource.Bundled(manifest)).toList()
            mgr.setDefault(manifest.id)

            val result = mgr.delete(manifest.id)

            assertThat(result).isEqualTo(DeleteOutcome.Deleted)
            assertThat(registry.get(manifest.id)).isNull()
            assertThat(mgr.default()).isNull()
        }

    // ==================================================================
    // skein-r8ah — integrity assertions
    // ==================================================================

    @Test
    fun `r8ah 1 a killed import leaves nothing loadable`(): Unit =
        runTest {
            val files = defaultFixtureFiles()
            val manifest = parsedManifest(files)
            // "Killed mid-copy": the source hands over fewer bytes than the
            // manifest declares — PocketPal #163/#172's field failure.
            val truncating =
                ModelBytesSource { file ->
                    val fixture = files.first { it.name == file.file }
                    ByteArrayInputStream(fixture.content.copyOf(fixture.content.size / 2))
                }

            val outcome = outcomeOf(manager(bundled = truncating).import(ImportSource.Bundled(manifest)).toList())

            assertThat(outcome).isInstanceOf(ImportOutcome.Refused::class.java)
            assertThat(registry.get(manifest.id)).isNull()
            val opened = store.open(manifest.id)
            try {
                assertThat(opened).isInstanceOf(OpenResult.Refused::class.java)
            } finally {
                if (opened is OpenResult.Opened) opened.handle.release()
            }
            assertThat(File(root, manifest.id).exists()).isFalse()
        }

    @Test
    fun `r8ah 2 a digest that no longer matches is refused not rescued by a stale hash`(): Unit =
        runTest {
            // The manifest's declared digest was computed over OLD content;
            // the bytes actually handed to the store are NEW content. A
            // hash recorded once must never excuse bytes that disagree with
            // it now.
            val staleFiles = defaultFixtureFiles()
            val staleManifest = parsedManifest(staleFiles) // records the sha256 of `staleFiles`' content
            val changedContent = staleFiles.withRole(ModelFileRole.MAIN) { it.copy(content = bytesOf(123, 4_096)) }

            val outcome =
                outcomeOf(
                    manager(bundled = sourceOf(changedContent)).import(ImportSource.Bundled(staleManifest)).toList(),
                )

            val refusal = ((outcome as ImportOutcome.Refused).refusal as ImportRefusal.FromStore).refusal
            assertThat(refusal).isInstanceOf(ModelVerification.HashMismatch::class.java)
            assertThat(registry.get(staleManifest.id)).isNull()
        }

    @Test
    fun `r8ah 3 a model is never marked installed on file existence alone`(): Unit =
        runTest {
            // Bytes placed directly on disk, entirely bypassing
            // ModelManager.import — PocketPal #163's "file exists implies
            // downloaded" bug, inverted as an assertion.
            val sneakedId = "sneaked-in-model"
            val modelDir = File(root, sneakedId).apply { mkdirs() }
            File(modelDir, "model.gguf").writeBytes(bytesOf(5, 1_024))

            assertThat(registry.list()).isEmpty()
            assertThat(registry.get(sneakedId)).isNull()
        }

    @Test
    fun `r8ah 4 a record whose path no longer resolves is re-anchored`(): Unit =
        runTest {
            val manifest = parsedManifest(defaultFixtureFiles())
            val mgr = manager()
            val imported =
                (
                    outcomeOf(
                        mgr.import(ImportSource.Bundled(manifest)).toList(),
                    ) as ImportOutcome.Imported
                ).record

            // Simulate the recorded path going stale (e.g. a container-path
            // change across an upgrade, PP-73 / OL-35) while the store's own
            // <modelsRoot>/<id>/ directory is untouched and still resolves.
            val staleRecord = imported.copy(model = imported.model.copy(path = "/no/longer/valid/model.gguf"))
            registry.upsert(staleRecord)

            val results = mgr.reconcilePaths()

            assertThat(results).containsExactly(PathReconciliation.ReAnchored(manifest.id, imported.model.path))
            assertThat(registry.get(manifest.id)?.model?.path).isEqualTo(imported.model.path)
        }

    @Test
    fun `r8ah 4 a record whose model is genuinely missing is reported not deregistered`(): Unit =
        runTest {
            val manifest = parsedManifest(defaultFixtureFiles())
            val mgr = manager()
            val imported =
                (
                    outcomeOf(
                        mgr.import(ImportSource.Bundled(manifest)).toList(),
                    ) as ImportOutcome.Imported
                ).record

            // The store directory is gone AND the recorded path is stale —
            // genuinely unrecoverable. `store.delete` (not a raw
            // `File.deleteRecursively`) because the model directory is
            // sealed `0500`: removing an entry from it needs the store's
            // own permission dance, not just a caller with a `File` handle.
            store.delete(manifest.id)
            val staleRecord = imported.copy(model = imported.model.copy(path = "/no/longer/valid/model.gguf"))
            registry.upsert(staleRecord)

            val results = mgr.reconcilePaths()

            assertThat(results).containsExactly(PathReconciliation.Missing(manifest.id))
            // Never silently deregistered: the row is still there, just
            // reported as missing.
            assertThat(registry.get(manifest.id)).isNotNull()
        }
}
