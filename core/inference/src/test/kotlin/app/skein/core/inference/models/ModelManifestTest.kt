// skein-st1r: §2.4's `ManifestValidatorTest` — the manifest refuses at parse
// time whatever the load flow would otherwise have to trust at run time.

package app.skein.core.inference.models

import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import us.aherrera.skein.core.model.Blake3

class ModelManifestTest {
    @Test
    fun `complete manifest parses`() {
        val manifest = parsedManifest()

        assertThat(manifest.id).isEqualTo(MODEL_ID)
    }

    @Test
    fun `parsed manifest covers main plus every companion`() {
        val manifest = parsedManifest()

        assertThat(manifest.files.map { it.role })
            .containsExactly(ModelFileRole.MAIN, ModelFileRole.TOKENIZER, ModelFileRole.LICENSE)
    }

    @Test
    fun `every parsed file carries a sha256`() {
        val manifest = parsedManifest()

        assertThat(manifest.files.map { it.sha256.length }).containsExactly(64, 64, 64)
    }

    @Test
    fun `declared blake3 is preserved`() {
        val files = defaultFixtureFiles().withRole(ModelFileRole.MAIN) { it.copy(declareBlake3 = true) }

        assertThat(parsedManifest(files).main.blake3).isEqualTo(Blake3.hex(files.first().content))
    }

    @Test
    fun `companion without sha256 is refused as CompanionMissing`() {
        val document =
            manifestJson(defaultFixtureFiles())
                .replace(Regex("\"sha256\": \"[a-f0-9]{64}\",\n      \"size_bytes\": 512"), "\"size_bytes\": 512")

        val result = ModelManifest.parse(document)

        assertThat((result as ManifestParse.Refused).refusal)
            .isEqualTo(ModelVerification.CompanionMissing(ModelFileRole.TOKENIZER, TOKENIZER_FILE))
    }

    @Test
    fun `unknown companion role is refused`() {
        val document = manifestJson(defaultFixtureFiles()).replace("\"role\": \"tokenizer\"", "\"role\": \"weights2\"")

        val result = ModelManifest.parse(document)

        assertThat(
            (result as ManifestParse.Refused).refusal,
        ).isInstanceOf(ModelVerification.MalformedManifest::class.java)
    }

    @Test
    fun `manifest version 1 is refused`() {
        val result = ModelManifest.parse(manifestJson(defaultFixtureFiles(), manifestVersion = 1))

        assertThat((result as ManifestParse.Refused).refusal)
            .isEqualTo(ModelVerification.MalformedManifest("manifest_version 1 is not supported"))
    }

    @Test
    fun `file name that escapes the store directory is refused`() {
        val files = defaultFixtureFiles().withRole(ModelFileRole.TOKENIZER) { it.copy(name = "../tokenizer.json") }

        val result = ModelManifest.parse(manifestJson(files))

        assertThat((result as ManifestParse.Refused).refusal)
            .isEqualTo(ModelVerification.MalformedManifest("file name must be a plain name, not a path"))
    }

    @Test
    fun `duplicate companion role is refused`() {
        val files = defaultFixtureFiles() + FixtureFile(ModelFileRole.TOKENIZER, "other.json", bytesOf(9, 8))

        val result = ModelManifest.parse(manifestJson(files))

        assertThat((result as ManifestParse.Refused).refusal)
            .isEqualTo(ModelVerification.MalformedManifest("duplicate role 'tokenizer'"))
    }

    @Test
    fun `main declared inside companions is refused`() {
        val document = manifestJson(defaultFixtureFiles()).replace("\"role\": \"license\"", "\"role\": \"main\"")

        val result = ModelManifest.parse(document)

        assertThat((result as ManifestParse.Refused).refusal)
            .isEqualTo(ModelVerification.MalformedManifest("'main' may not appear in companions"))
    }

    @Test
    fun `unparseable document is refused rather than thrown`() {
        val result = ModelManifest.parse("{ not json")

        assertThat(result).isInstanceOf(ManifestParse.Refused::class.java)
    }
}
