// skein-nxk (E4.I3, coordinator decision skein-hiwb): the store-side ->
// verifier bridge.
//
// `ManifestBinding` stayed in `:core:inference` when `ModelVerifier` moved to
// the pure-JVM `:core:verify`, so the app-side loader needs one conversion to
// keep calling the verifier. This test pins that the conversion is lossless in
// the two fields the gates actually read (`expectedSha256`, `expectedBlake3`)
// and that it carries the path the app-side companion fallback depends on.

package app.skein.core.inference.models

import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import app.skein.core.verify.ModelVerifier
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyBindingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: ImmutableModelStore
    private lateinit var manifest: ModelManifest
    private lateinit var stored: StoredModel

    @Before
    fun setUp() {
        store = ImmutableModelStore(temp.newFolder("models"))
        val files = defaultFixtureFiles()
        manifest = parsedManifest(files)
        stored = (store.import(manifest, sourceOf(files)) as ImportResult.Imported).model
    }

    @Test
    fun `every bound file survives the conversion, in order`() {
        assertThat(binding().toVerifyBinding().files.map { it.role })
            .containsExactly(ModelFileRole.MAIN, ModelFileRole.TOKENIZER, ModelFileRole.LICENSE)
            .inOrder()
    }

    @Test
    fun `the main file keeps its sha256`() {
        assertThat(binding().toVerifyBinding().main.expectedSha256).isEqualTo(stored.main.sha256)
    }

    @Test
    fun `the main file keeps its blake3, so the app side never falls back`() {
        assertThat(binding().toVerifyBinding().main.expectedBlake3).isEqualTo(stored.main.blake3)
    }

    @Test
    fun `every converted file keeps its store path`() {
        assertThat(binding().toVerifyBinding().files.map { it.path })
            .containsExactlyElementsIn(binding().files.map { it.path })
    }

    @Test
    fun `every converted file keeps its expected size`() {
        assertThat(binding().toVerifyBinding().files.map { it.expectedSizeBytes })
            .containsExactlyElementsIn(binding().files.map { it.expectedSizeBytes })
    }

    @Test
    fun `the ModelHandle overload verifies a freshly imported model`() {
        val handle = (store.open(MODEL_ID) as OpenResult.Opened).handle
        try {
            assertThat(ModelVerifier.verifyBeforeMmap(handle, binding())).isEqualTo(ModelVerification.Verified)
        } finally {
            handle.release()
        }
    }

    private fun binding(): ManifestBinding = (ManifestBinding.bind(manifest, stored) as BindResult.Bound).binding
}
