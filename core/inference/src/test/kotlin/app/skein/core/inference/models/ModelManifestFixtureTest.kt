// skein-3v9 (E0.I15, coordinator decision skein-cqiu 2026-09-21): the manifest
// fixtures are the shared contract between this parser and
// `tools/ci/validate-manifests.py`.
//
// Both readers walk the SAME two directories —
// `core/inference/src/test/resources/manifests/valid` and `.../invalid` — and
// both must agree on every file in them: everything under `valid/` parses,
// everything under `invalid/` is refused. That is the whole point of putting
// the fixtures on disk instead of building them as Kotlin strings: a schema
// change that the Kotlin parser accepts but the Python checker rejects (or the
// reverse) fails one of the two suites immediately, so the `.schema.json`
// documentation cannot drift away from the code that enforces it.
//
// The digests in the fixtures are deliberately unmistakable placeholders
// (`deadbeef…`, `cafebabe…`, `f00dface…`) of the correct 64-hex shape. E0.I15
// locks the *shape*; skein-bxk (E0.I4) is the bead that downloads the real
// artifacts and replaces these with true digests when it writes the shipped
// manifests into `app/src/main/assets/models/`. Nothing placeholder is shipped:
// that asset directory does not exist yet, and the CI checker requires a real
// `blake3` for anything that ever lands there.

package app.skein.core.inference.models

import app.skein.core.model.Capability
import app.skein.core.model.CompanionRole
import app.skein.core.model.ModelFormat
import app.skein.core.verify.ModelFileRole
import app.skein.core.verify.ModelVerification
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class ModelManifestFixtureTest {
    // ----------------------------------------------------------------
    // The two directories, walked wholesale
    // ----------------------------------------------------------------

    @Test
    fun `every fixture under valid parses`() {
        val refused =
            fixtures("valid").filter { ModelManifest.parse(it.readText()) !is ManifestParse.Parsed }

        assertThat(refused.map { it.name }).isEmpty()
    }

    @Test
    fun `every fixture under invalid is refused`() {
        val accepted =
            fixtures("invalid").filter { ModelManifest.parse(it.readText()) !is ManifestParse.Refused }

        assertThat(accepted.map { it.name }).isEmpty()
    }

    @Test
    fun `both default model manifests are present as fixtures`() {
        assertThat(fixtures("valid").map { it.name })
            .containsExactly(
                "qwen-2.5-3b-abliterated-q4km.skein.json",
                "gemma-4-e4b-it-q4km.skein.json",
            )
    }

    @Test
    fun `no refusal summary leaks a digest or a path`() {
        // spec §9 / MODEL_STORE.md §5: a refusal's log-safe summary names roles,
        // algorithms and ids only. A fixture full of `deadbeef` digests and
        // `../` file names makes a leak obvious if one is ever introduced.
        val summaries =
            fixtures("invalid")
                .mapNotNull { (ModelManifest.parse(it.readText()) as? ManifestParse.Refused)?.refusal?.summary }

        assertThat(summaries.filter { it.contains("deadbeef") || it.contains("..") }).isEmpty()
    }

    // ----------------------------------------------------------------
    // One test per acceptance-criterion rejection case
    // ----------------------------------------------------------------

    @Test
    fun `wrong manifest_version is refused`() {
        assertThat(refusalOf("wrong-manifest-version"))
            .isEqualTo(ModelVerification.MalformedManifest("manifest_version 1 is not supported"))
    }

    @Test
    fun `non-hex sha256 is refused`() {
        assertThat(refusalOf("non-hex-sha256")).isInstanceOf(ModelVerification.MalformedManifest::class.java)
    }

    @Test
    fun `unknown capability is refused`() {
        assertThat(refusalOf("unknown-capability"))
            .isEqualTo(ModelVerification.MalformedManifest("unknown capability 'telepathy'"))
    }

    @Test
    fun `missing license spdx is refused`() {
        assertThat(refusalOf("missing-license-spdx"))
            .isEqualTo(ModelVerification.MalformedManifest("missing or non-string 'spdx'"))
    }

    @Test
    fun `unknown root property is refused`() {
        assertThat(refusalOf("additional-property-root"))
            .isEqualTo(ModelVerification.MalformedManifest("unknown property 'extra_field' in manifest"))
    }

    @Test
    fun `unknown companion property is refused`() {
        assertThat(refusalOf("additional-property-companion"))
            .isEqualTo(ModelVerification.MalformedManifest("unknown property 'algorithm' in companion"))
    }

    @Test
    fun `unknown companion role is refused`() {
        assertThat(refusalOf("unknown-companion-role"))
            .isEqualTo(ModelVerification.MalformedManifest("unknown companion role 'weights2'"))
    }

    @Test
    fun `companion file name that escapes the store directory is refused`() {
        assertThat(refusalOf("companion-path-escape"))
            .isEqualTo(ModelVerification.MalformedManifest("file name must be a plain name, not a path"))
    }

    @Test
    fun `main file name that escapes the store directory is refused`() {
        assertThat(refusalOf("main-file-path-escape"))
            .isEqualTo(ModelVerification.MalformedManifest("file name must be a plain name, not a path"))
    }

    @Test
    fun `companion without sha256 is refused as CompanionMissing`() {
        assertThat(refusalOf("companion-without-sha256"))
            .isEqualTo(ModelVerification.CompanionMissing(ModelFileRole.TOKENIZER, "tokenizer.json"))
    }

    @Test
    fun `main declared inside companions is refused`() {
        assertThat(refusalOf("main-declared-in-companions"))
            .isEqualTo(ModelVerification.MalformedManifest("'main' may not appear in companions"))
    }

    @Test
    fun `duplicate companion role is refused`() {
        assertThat(refusalOf("duplicate-companion-role"))
            .isEqualTo(ModelVerification.MalformedManifest("duplicate role 'tokenizer'"))
    }

    @Test
    fun `malformed id is refused`() {
        assertThat(refusalOf("malformed-id")).isInstanceOf(ModelVerification.MalformedManifest::class.java)
    }

    @Test
    fun `empty capabilities list is refused`() {
        assertThat(refusalOf("empty-capabilities"))
            .isEqualTo(ModelVerification.MalformedManifest("capabilities must list at least one entry"))
    }

    @Test
    fun `duplicate capabilities are refused`() {
        assertThat(refusalOf("duplicate-capabilities"))
            .isEqualTo(ModelVerification.MalformedManifest("duplicate capability 'text'"))
    }

    @Test
    fun `unknown format is refused`() {
        assertThat(refusalOf("unknown-format"))
            .isEqualTo(ModelVerification.MalformedManifest("unknown format 'safetensors'"))
    }

    @Test
    fun `context_length below the floor is refused`() {
        assertThat(refusalOf("context-length-too-small")).isInstanceOf(ModelVerification.MalformedManifest::class.java)
    }

    @Test
    fun `attestation without a certificate identity is refused`() {
        assertThat(refusalOf("attestation-missing-identity"))
            .isInstanceOf(ModelVerification.MalformedManifest::class.java)
    }

    @Test
    fun `unparseable document is refused rather than thrown`() {
        assertThat(refusalOf("unparseable-document")).isInstanceOf(ModelVerification.MalformedManifest::class.java)
    }

    // ----------------------------------------------------------------
    // The fields §4.8 carries beyond the load gate
    // ----------------------------------------------------------------

    @Test
    fun `license is parsed as an object with a required spdx id`() {
        assertThat(gemma().license.spdx).isEqualTo("Apache-2.0")
    }

    @Test
    fun `license url and notes survive parsing`() {
        assertThat(qwen().license.notes).contains("Qwen Research License")
    }

    @Test
    fun `capabilities map onto the locked Capability enum`() {
        assertThat(gemma().capabilities).containsExactly(Capability.TEXT, Capability.VISION)
    }

    @Test
    fun `format maps onto the locked ModelFormat enum`() {
        assertThat(gemma().format).isEqualTo(ModelFormat.GGUF)
    }

    @Test
    fun `context_length falls back to the Model default when absent`() {
        val document = qwenDocument().replace("\"context_length\": 16384,", "")

        assertThat(parsed(document).contextLength).isEqualTo(16_384)
    }

    @Test
    fun `attestation is exposed as an object and verifies nothing`() {
        val attestation = checkNotNull(gemma().attestation)

        assertThat(attestation.certificateIdentity).isEqualTo("admin@aherrera.us")
    }

    @Test
    fun `attestation covers defaults to main only when absent`() {
        val document = gemmaDocument().replace(",\n    \"covers\": [\"all\"]", "")

        assertThat(parsed(document).attestation?.covers).containsExactly(AttestationCoverage.MAIN)
    }

    @Test
    fun `attestationUrl reads through to the attestation object`() {
        // The landed v2 parser read a bare root `attestation_url` string;
        // skein-cqiu point 5 replaced it with §4.8's object. Callers that only
        // want the URL keep a one-field accessor rather than reaching in.
        assertThat(gemma().attestationUrl)
            .isEqualTo("https://aherrera.us/skein/attest/gemma-4-e4b-it-q4km.sigstore.json")
    }

    @Test
    fun `attestationUrl is null when the manifest declares no attestation`() {
        assertThat(qwen().attestationUrl).isNull()
    }

    @Test
    fun `source provenance survives parsing`() {
        assertThat(gemma().source?.url).isEqualTo("https://huggingface.co/google/gemma-4-e4b-it")
    }

    @Test
    fun `declared blake3 on the main file is preserved`() {
        assertThat(qwen().main.blake3).isEqualTo("beefdead".repeat(8))
    }

    @Test
    fun `an optional companion is marked not required`() {
        assertThat(gemma().byRole(ModelFileRole.LICENSE)?.required).isFalse()
    }

    // ----------------------------------------------------------------
    // toModel
    // ----------------------------------------------------------------

    @Test
    fun `toModel carries the manifest identity onto the Model`() {
        val model = qwen().toModel(path = "/data/models/qwen/model.gguf", companionPaths = emptyMap())

        assertThat(model.id).isEqualTo("qwen-2.5-3b-abliterated-q4km")
    }

    @Test
    fun `toModel uses the supplied absolute path for the main file`() {
        val model = qwen().toModel(path = "/data/models/qwen/model.gguf", companionPaths = emptyMap())

        assertThat(model.path).isEqualTo("/data/models/qwen/model.gguf")
    }

    @Test
    fun `toModel maps capabilities onto the locked contract`() {
        val model = gemma().toModel("/data/m.gguf", mapOf(CompanionRole.MMPROJ to "/data/mmproj.gguf"))

        assertThat(model.capabilities).containsExactly(Capability.TEXT, Capability.VISION)
    }

    @Test
    fun `toModel resolves a companion path onto its role`() {
        val model = gemma().toModel("/data/m.gguf", mapOf(CompanionRole.MMPROJ to "/data/mmproj.gguf"))

        assertThat(model.companions[CompanionRole.MMPROJ]?.path).isEqualTo("/data/mmproj.gguf")
    }

    @Test
    fun `toModel carries the companion sha256 from the manifest`() {
        val model = gemma().toModel("/data/m.gguf", mapOf(CompanionRole.MMPROJ to "/data/mmproj.gguf"))

        assertThat(model.companions[CompanionRole.MMPROJ]?.sha256).isEqualTo("0ddba11f".repeat(8))
    }

    @Test
    fun `toModel omits a companion with no supplied path`() {
        val model = gemma().toModel("/data/m.gguf", companionPaths = emptyMap())

        assertThat(model.companions).isEmpty()
    }

    @Test
    fun `toModel carries context_length`() {
        assertThat(gemma().toModel("/data/m.gguf", emptyMap()).contextLength).isEqualTo(32_768)
    }

    @Test
    fun `toModel carries size_bytes`() {
        assertThat(qwen().toModel("/data/m.gguf", emptyMap()).sizeBytes).isEqualTo(1_929_000_000L)
    }

    @Test
    fun `toModel carries the attestation url when the manifest declares one`() {
        assertThat(gemma().toModel("/data/m.gguf", emptyMap()).attestationUrl)
            .isEqualTo("https://aherrera.us/skein/attest/gemma-4-e4b-it-q4km.sigstore.json")
    }

    @Test
    fun `toModel rejects a companion path for a role the manifest does not cover`() {
        // Supplying a path for an uncovered role is the "trust the adjacent
        // file" hole in a different coat: POST_REVIEW §2.2 rule 3 says every
        // file the loader opens must be manifest-covered, so this cannot be a
        // silently-ignored argument.
        val thrown =
            runCatching {
                qwen().toModel("/data/m.gguf", mapOf(CompanionRole.MMPROJ to "/data/mmproj.gguf"))
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun fixtures(kind: String): List<File> {
        val url =
            checkNotNull(javaClass.classLoader?.getResource("manifests/$kind")) {
                "fixture directory manifests/$kind is not on the test classpath"
            }
        val files = checkNotNull(File(url.toURI()).listFiles()) { "manifests/$kind is not a directory" }
        check(files.isNotEmpty()) { "manifests/$kind is empty — the fixture contract needs at least one document" }
        return files.sortedBy { it.name }
    }

    private fun fixture(
        kind: String,
        name: String,
    ): String = fixtures(kind).first { it.name == "$name.skein.json" }.readText()

    private fun refusalOf(name: String): ModelVerification.Refusal =
        (ModelManifest.parse(fixture("invalid", name)) as ManifestParse.Refused).refusal

    private fun parsed(document: String): ModelManifest =
        (ModelManifest.parse(document) as ManifestParse.Parsed).manifest

    private fun qwenDocument(): String = fixture("valid", "qwen-2.5-3b-abliterated-q4km")

    private fun gemmaDocument(): String = fixture("valid", "gemma-4-e4b-it-q4km")

    private fun qwen(): ModelManifest = parsed(qwenDocument())

    private fun gemma(): ModelManifest = parsed(gemmaDocument())
}
