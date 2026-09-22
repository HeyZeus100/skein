// skein-st1r (POST_REVIEW_RESOLUTIONS §2.4) — the device half of the model
// verification suite: a real `filesDir`, a real `MappedByteBuffer`, and
// `Os.stat`/`Os.open` for the permission claims that a JVM temp directory can
// only approximate.
//
// Compiled unconditionally so the store's Android surface stays type-safe
// under refactors; a full on-device run is gated by skein-k3b2 (emulator
// provisioning), the same pattern as ThermalGovernorInstrumentedTest and
// core/vault's VaultKeyProviderInstrumentedTest.
//
// Why these assertions need a device. §2.2 claims the imported model lives in
// app-private storage with `0400`/`0500` on it. `Files.setPosixFilePermissions`
// succeeding on a developer's APFS temp directory says nothing about the
// ext4/f2fs `/data/user/0/<pkg>/files` that ships; only `Os.stat` on the real
// path proves the mode bits survived, and only `Os.open(O_WRONLY)` proves the
// kernel refuses our own UID a write handle (EACCES).

package app.skein.core.inference.models

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.verify.LoadPhaseHook
import app.skein.core.verify.LoadVerification
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ImmutableModelStoreInstrumentedTest {
    private lateinit var modelsRoot: File
    private lateinit var store: ImmutableModelStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        modelsRoot = File(context.filesDir, "models-instrumented")
        modelsRoot.deleteRecursively()
        modelsRoot.mkdirs()
        store = ImmutableModelStore(modelsRoot)
    }

    @After
    fun tearDown() {
        store.delete(MODEL_ID)
        modelsRoot.deleteRecursively()
    }

    @Test
    fun importSealsTheModelFileTo0400OnRealFilesDir() {
        val model = importFixture()

        val mode = Os.stat(model.main.path.absolutePath).st_mode and PERMISSION_MASK

        assertThat(Integer.toOctalString(mode)).isEqualTo("400")
    }

    @Test
    fun importSealsTheModelDirectoryTo0500OnRealFilesDir() {
        val model = importFixture()

        val mode = Os.stat(model.directory.absolutePath).st_mode and PERMISSION_MASK

        assertThat(Integer.toOctalString(mode)).isEqualTo("500")
    }

    @Test
    fun ourOwnUidCannotOpenTheSealedModelForWriting() {
        val model = importFixture()

        val errno =
            try {
                Os.open(model.main.path.absolutePath, OsConstants.O_WRONLY, 0)
                0
            } catch (e: ErrnoException) {
                e.errno
            }

        assertThat(errno).isEqualTo(OsConstants.EACCES)
    }

    @Test
    fun loadGatePassesOverARealMappedByteBuffer() {
        importFixture()
        val handle = (store.open(MODEL_ID) as OpenResult.Opened).handle

        val result = handle.use { ModelVerifier.verifyForLoad(it, binding()) }

        assertThat(result).isInstanceOf(LoadVerification.Ready::class.java)
    }

    @Test
    fun inPlaceModificationBetweenTheGatesIsDetectedOnDevice() {
        val model = importFixture()
        val handle = (store.open(MODEL_ID) as OpenResult.Opened).handle
        val attacker =
            object : LoadPhaseHook {
                override fun afterPreMmapVerify(binding: ManifestBinding) {
                    // The actor §2.2 says `0400` cannot exclude: same UID,
                    // chmod back, pwrite. On device this is the root/recovery
                    // case made reproducible.
                    Os.chmod(model.main.path.absolutePath, OWNER_READ_WRITE)
                    java.io.RandomAccessFile(model.main.path, "rw").use {
                        it.seek(1_024L)
                        it.writeByte(0x5A)
                        it.fd.sync()
                    }
                }
            }

        val result = handle.use { ModelVerifier.verifyForLoad(it, binding(), attacker) }

        assertThat((result as LoadVerification.Refused).refusal)
            .isEqualTo(ModelVerification.Tampered(ModelFileRole.MAIN))
    }

    @Test
    fun reImportOverALoadedModelIsRefused() {
        importFixture()
        val handle = (store.open(MODEL_ID) as OpenResult.Opened).handle

        val result = handle.use { store.import(manifest(), source()) }

        assertThat((result as ImportResult.Refused).refusal).isEqualTo(ModelVerification.InUse(MODEL_ID))
    }

    // ---------------------------------------------------------------- fixtures

    private fun importFixture(): StoredModel = (store.import(manifest(), source()) as ImportResult.Imported).model

    private fun binding(): ManifestBinding {
        val stored = checkNotNull(store.stored(MODEL_ID))
        return (ManifestBinding.bind(manifest(), stored) as BindResult.Bound).binding
    }

    private fun manifest(): ModelManifest {
        val document =
            """
            {
              "manifest_version": 2,
              "id": "$MODEL_ID",
              "name": "Instrumented store fixture",
              "format": "gguf",
              "file": "$MAIN_FILE",
              "sha256": "${hex(MAIN_BYTES)}",
              "size_bytes": ${MAIN_BYTES.size},
              "capabilities": ["text"],
              "license": { "spdx": "Apache-2.0" },
              "companions": [
                {
                  "role": "tokenizer",
                  "file": "$TOKENIZER_FILE",
                  "sha256": "${hex(TOKENIZER_BYTES)}",
                  "size_bytes": ${TOKENIZER_BYTES.size}
                }
              ]
            }
            """.trimIndent()
        return (ModelManifest.parse(document) as ManifestParse.Parsed).manifest
    }

    private fun source(): ModelBytesSource =
        ModelBytesSource { entry ->
            when (entry.file) {
                MAIN_FILE -> ByteArrayInputStream(MAIN_BYTES)
                TOKENIZER_FILE -> ByteArrayInputStream(TOKENIZER_BYTES)
                else -> null
            }
        }

    private fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODEL_ID = "instrumented-model"
        const val MAIN_FILE = "model.gguf"
        const val TOKENIZER_FILE = "tokenizer.json"
        const val PERMISSION_MASK = 0b111_111_111
        const val OWNER_READ_WRITE = 384 // 0600

        val MAIN_BYTES = ByteArray(8_192) { (it % 251).toByte() }
        val TOKENIZER_BYTES = "{\"model\":\"test\"}".toByteArray()
    }
}
