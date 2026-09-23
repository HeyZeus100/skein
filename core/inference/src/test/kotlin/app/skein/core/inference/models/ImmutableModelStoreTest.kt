// skein-st1r: §2.4's `PermissionEnforcementTest` / `AdvisoryLockTest`, on the
// JVM. The store's filesystem contract — atomic import, read-only sealing, the
// shared lock and the `InUse` refusals — is plain POSIX behaviour, so it is
// provable here over a temp directory; `ImmutableModelStoreInstrumentedTest`
// re-proves it against a real `filesDir` on device.

package app.skein.core.inference.models

import app.skein.core.model.Blake3
import app.skein.core.verify.DigestAlgorithm
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImmutableModelStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ImmutableModelStore

    @Before
    fun setUp() {
        root = temp.newFolder("models")
        store = ImmutableModelStore(root)
    }

    // ------------------------------------------------------- import round-trip

    @Test
    fun `import writes every manifest file into the store`() {
        val files = defaultFixtureFiles()

        val imported = importOk(files)

        assertThat(imported.files.keys)
            .containsExactly(ModelFileRole.MAIN, ModelFileRole.TOKENIZER, ModelFileRole.LICENSE)
    }

    @Test
    fun `imported bytes are identical to the source`() {
        val files = defaultFixtureFiles()

        val imported = importOk(files)

        assertThat(imported.main.path.readBytes()).isEqualTo(files.first().content)
    }

    @Test
    fun `import records the blake3 of the same bytes`() {
        val files = defaultFixtureFiles()

        val imported = importOk(files)

        assertThat(imported.main.blake3).isEqualTo(Blake3.hex(files.first().content))
    }

    @Test
    fun `import leaves no temporary files behind`() {
        val imported = importOk(defaultFixtureFiles())

        assertThat(imported.directory.list()!!.filter { it.endsWith(".tmp") }).isEmpty()
    }

    @Test
    fun `a staging directory left by an interrupted import does not block the next import`() {
        val stale = File(root, MODEL_ID).also { it.mkdirs() }
        File(stale, "model.gguf.tmp").writeBytes(bytesOf(7, 4_096))

        val imported = importOk(defaultFixtureFiles())

        assertThat(imported.directory.list()!!.filter { it.endsWith(".tmp") }).isEmpty()
        assertThat(imported.files.keys).contains(ModelFileRole.MAIN)
    }

    @Test
    fun `a directory holding a promoted file is still refused as already imported`() {
        val finished = File(root, MODEL_ID).also { it.mkdirs() }
        File(finished, "model.gguf").writeBytes(bytesOf(7, 4_096))

        val result = store.import(parsedManifest(defaultFixtureFiles()), sourceOf(defaultFixtureFiles()))

        assertThat((result as ImportResult.Refused).refusal).isEqualTo(ModelVerification.AlreadyImported(MODEL_ID))
        assertThat(File(finished, "model.gguf").exists()).isTrue()
    }

    @Test
    fun `imported files are sealed read-only`() {
        val imported = importOk(defaultFixtureFiles())

        assertThat(posixPermissions(imported.main.path)).containsExactly(PosixFilePermission.OWNER_READ)
    }

    @Test
    fun `imported directory is sealed read and execute only`() {
        val imported = importOk(defaultFixtureFiles())

        assertThat(posixPermissions(imported.directory))
            .containsExactly(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ)
    }

    @Test
    fun `import reports posix enforcement on a posix filesystem`() {
        val imported = importOk(defaultFixtureFiles())

        assertThat(imported.permissionEnforcement).isEqualTo(PermissionEnforcement.POSIX)
    }

    @Test
    fun `sealed file cannot be opened for writing`() {
        val imported = importOk(defaultFixtureFiles())

        assertThat(imported.main.path.canWrite()).isFalse()
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `sha256 mismatch on import is refused`() {
        val files = defaultFixtureFiles().withRole(ModelFileRole.MAIN) { it.copy(declaredSha256 = FOREIGN_SHA256) }

        val result = store.import(parsedManifest(files), sourceOf(files))

        assertThat((result as ImportResult.Refused).refusal)
            .isEqualTo(ModelVerification.HashMismatch(ModelFileRole.MAIN, DigestAlgorithm.SHA256))
    }

    @Test
    fun `sha256 mismatch on import leaves no file behind`() {
        val files = defaultFixtureFiles().withRole(ModelFileRole.MAIN) { it.copy(declaredSha256 = FOREIGN_SHA256) }

        store.import(parsedManifest(files), sourceOf(files))

        assertThat(File(root, MODEL_ID).exists()).isFalse()
    }

    @Test
    fun `declared blake3 that disagrees with the bytes is refused`() {
        val files = defaultFixtureFiles()
        val manifest = parsedManifest(files.withRole(ModelFileRole.MAIN) { it.copy(declareBlake3 = true) })
        val tweaked =
            manifest.copy(main = manifest.main.copy(blake3 = Blake3.hex(bytesOf(77, 4_096))))

        val result = store.import(tweaked, sourceOf(files))

        assertThat((result as ImportResult.Refused).refusal)
            .isEqualTo(ModelVerification.HashMismatch(ModelFileRole.MAIN, DigestAlgorithm.BLAKE3))
    }

    @Test
    fun `required companion missing from the source is refused`() {
        val files = defaultFixtureFiles().withRole(ModelFileRole.TOKENIZER) { it.copy(absentFromSource = true) }

        val result = store.import(parsedManifest(files), sourceOf(files))

        assertThat((result as ImportResult.Refused).refusal)
            .isEqualTo(ModelVerification.CompanionMissing(ModelFileRole.TOKENIZER, TOKENIZER_FILE))
    }

    @Test
    fun `optional companion missing from the source is skipped`() {
        val files =
            defaultFixtureFiles().withRole(ModelFileRole.LICENSE) {
                it.copy(required = false, absentFromSource = true)
            }

        val imported = importOk(files)

        assertThat(imported.files.keys).doesNotContain(ModelFileRole.LICENSE)
    }

    @Test
    fun `size mismatch is refused before the hash is trusted`() {
        val files = defaultFixtureFiles()
        val manifest = parsedManifest(files)
        val tweaked = manifest.copy(main = manifest.main.copy(sizeBytes = manifest.main.sizeBytes + 1))

        val result = store.import(tweaked, sourceOf(files))

        assertThat(
            (result as ImportResult.Refused).refusal,
        ).isEqualTo(ModelVerification.SizeMismatch(ModelFileRole.MAIN))
    }

    @Test
    fun `re-import over an existing idle id is refused`() {
        val files = defaultFixtureFiles()
        importOk(files)

        val result = store.import(parsedManifest(files), sourceOf(files))

        assertThat((result as ImportResult.Refused).refusal).isEqualTo(ModelVerification.AlreadyImported(MODEL_ID))
    }

    @Test
    fun `re-import over an in-use id is refused as InUse`() {
        val files = defaultFixtureFiles()
        importOk(files)
        openOk().use {
            val result = store.import(parsedManifest(files), sourceOf(files))

            assertThat((result as ImportResult.Refused).refusal).isEqualTo(ModelVerification.InUse(MODEL_ID))
        }
    }

    @Test
    fun `delete of an in-use id is refused as InUse`() {
        importOk(defaultFixtureFiles())
        openOk().use {
            assertThat(store.delete(MODEL_ID)).isEqualTo(ModelVerification.InUse(MODEL_ID))
        }
    }

    @Test
    fun `delete after release removes the sealed directory`() {
        val imported = importOk(defaultFixtureFiles())
        openOk().close()

        store.delete(MODEL_ID)

        assertThat(imported.directory.exists()).isFalse()
    }

    // ----------------------------------------------------------------- locking

    @Test
    fun `lock is held while the model is open`() {
        importOk(defaultFixtureFiles())

        openOk().use {
            assertThat(store.isOpen(MODEL_ID)).isTrue()
        }
    }

    @Test
    fun `lock is released after the last handle closes`() {
        importOk(defaultFixtureFiles())

        openOk().close()

        assertThat(store.isOpen(MODEL_ID)).isFalse()
    }

    @Test
    fun `import of a fresh id succeeds while another model is loaded`() {
        val files = defaultFixtureFiles()
        importOk(files)

        openOk().use {
            val second = store.import(parsedManifest(files).copy(id = "second-model"), sourceOf(files))

            assertThat(second).isInstanceOf(ImportResult.Imported::class.java)
        }
    }

    @Test
    fun `two concurrent loaders share one lock`() {
        importOk(defaultFixtureFiles())
        val ready = CountDownLatch(2)
        val handles = java.util.Collections.synchronizedList(mutableListOf<OpenResult>())
        val threads =
            (0 until 2).map {
                Thread {
                    handles += store.open(MODEL_ID)
                    ready.countDown()
                }
            }
        threads.forEach { it.start() }
        ready.await(10, TimeUnit.SECONDS)
        threads.forEach { it.join() }

        try {
            assertThat(handles.filterIsInstance<OpenResult.Opened>()).hasSize(2)
        } finally {
            handles.filterIsInstance<OpenResult.Opened>().forEach { it.handle.release() }
        }
    }

    @Test
    fun `model stays locked until every concurrent loader releases`() {
        importOk(defaultFixtureFiles())
        val first = openOk()
        val second = openOk()

        first.release()

        try {
            assertThat(store.isOpen(MODEL_ID)).isTrue()
        } finally {
            second.release()
        }
    }

    @Test
    fun `opening an unknown id is refused`() {
        val result = store.open("never-imported")

        assertThat((result as OpenResult.Refused).refusal).isEqualTo(ModelVerification.FileMissing(ModelFileRole.MAIN))
    }

    // ---------------------------------------------------------------- helpers

    private fun importOk(files: List<FixtureFile>): StoredModel =
        (store.import(parsedManifest(files), sourceOf(files)) as ImportResult.Imported).model

    private fun openOk(): ModelHandle = (store.open(MODEL_ID) as OpenResult.Opened).handle

    private companion object {
        val FOREIGN_SHA256 = sha256Hex(bytesOf(99, 4_096))
    }
}
