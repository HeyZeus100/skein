package app.skein.benchmark

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class SyntheticSetupTest {
    @Test
    fun default_hashes_known_bytes_and_host_mode_has_distinct_provenance() {
        withRoot { root ->
            val file = root.resolve("model.gguf").also { it.writeText("abc") }
            val default = SyntheticSetup.verifiedModel(file, ABC_SHA, 3, null)
            assertEquals(SyntheticFileHashes(ABC_SHA, ABC_B3, 3), default)
            assertEquals("device_computed", SyntheticSetup.provenance(null))
            val host = SyntheticSetup.hostIdentity(config())
            assertEquals(default, SyntheticSetup.verifiedModel(file, ABC_SHA, 3, host))
            assertEquals("host_declared_bound_to_device_sha256", SyntheticSetup.provenance(host))
            assertEquals("e".repeat(64), host?.evidenceSha256)
            assertNull(SyntheticSetup.hostIdentity(JsonObject(config() - "host_model_identity")))
        }
    }

    @Test
    fun host_metadata_never_bypasses_full_device_sha_or_size() {
        withRoot { root ->
            val file = root.resolve("model.gguf").also { it.writeText("abc") }
            val host = SyntheticSetup.hostIdentity(config())
            for (metadata in listOf(null, host)) {
                file.writeText("abd")
                assertThrows(IllegalStateException::class.java) {
                    SyntheticSetup.verifiedModel(file, ABC_SHA, 3, metadata)
                }
                file.writeText("abcd")
                assertThrows(IllegalStateException::class.java) {
                    SyntheticSetup.verifiedModel(file, ABC_SHA, 3, metadata)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                SyntheticSetup.verifiedModel(file, "0".repeat(64), 4, host)
            }
        }
    }

    @Test
    fun malformed_partial_null_or_mismatched_host_metadata_is_rejected() {
        val row = config().getValue("host_model_identity").jsonObject
        val invalid =
            listOf(
                JsonNull,
                JsonObject(row - "evidence_sha256"),
                JsonObject(row + ("unexpected" to JsonPrimitive(true))),
                JsonObject(row + ("schema_version" to JsonPrimitive("1"))),
                JsonObject(row + ("schema_version" to JsonPrimitive(2))),
                JsonObject(row + ("model_size" to JsonPrimitive("3"))),
                JsonObject(row + ("model_size" to JsonPrimitive(0))),
                JsonObject(row + ("model_size" to JsonPrimitive(4))),
                JsonObject(row + ("model_sha256" to JsonPrimitive("0".repeat(64)))),
                JsonObject(row + ("blake3" to JsonPrimitive("short"))),
                JsonObject(row + ("blake3" to JsonPrimitive("A".repeat(64)))),
                JsonObject(row + ("evidence_sha256" to JsonPrimitive(false))),
            )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SyntheticSetup.hostIdentity(JsonObject(config() + ("host_model_identity" to value)))
            }
        }
    }

    @Test
    fun test_source_is_optional_separate_and_requires_a_complete_commit() {
        val baseline = JsonObject(config() + ("build_sha" to JsonPrimitive("a".repeat(40))))
        assertNull(SyntheticSetup.testBuildSha(baseline))
        val declared = JsonObject(baseline + ("test_build_sha" to JsonPrimitive("b".repeat(40))))
        assertEquals("b".repeat(40), SyntheticSetup.testBuildSha(declared))
        assertEquals("a".repeat(40), declared.string("build_sha"))
        for (bad in listOf(JsonNull, JsonPrimitive("short"), JsonPrimitive(123))) {
            assertThrows(IllegalArgumentException::class.java) {
                SyntheticSetup.testBuildSha(JsonObject(baseline + ("test_build_sha" to bad)))
            }
        }
    }

    @Test
    fun invalid_runtime_settings_are_recorded_in_config_before_file_hashing() {
        withRoot { root ->
            val valid =
                JsonObject(
                    config() +
                        Json
                            .parseToJsonElement(
                                """{"seeds":[17],"context_length":128,"threads":1,"case_timeout_ms":1000,"sampling":{"max_tokens":32}}""",
                            ).jsonObject,
                )
            val invalid =
                listOf(
                    "threads" to JsonPrimitive(0),
                    "context_length" to JsonPrimitive(0),
                    "case_timeout_ms" to JsonPrimitive(999),
                    "seeds" to Json.parseToJsonElement("[]"),
                    "model_size" to JsonPrimitive(0),
                    "sampling" to Json.parseToJsonElement("""{"max_tokens":129}"""),
                )
            invalid.forEachIndexed { index, replacement ->
                val progress = SyntheticSetupProgress.create(root, "invalid-$index")
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking {
                        progress.stage(
                            SetupPhase.CONFIG,
                        ) { SyntheticSetup.configuration(JsonObject(valid + replacement)) }
                    }
                }
                val rows = records(progress)
                assertEquals(listOf("start", "error"), rows.map { it.string("event") })
                assertEquals(setOf("config"), rows.map { it.string("phase") }.toSet())
                assertEquals("VALIDATION_FAILED", rows.last().string("error_code"))
            }
        }
    }

    @Test
    fun fresh_progress_is_append_only_and_stale_runs_are_preserved() {
        withRoot { root ->
            val progress = SyntheticSetupProgress.create(root, "fresh")
            val file = progress.output.resolve("setup-progress.jsonl")
            runBlocking { progress.stage(SetupPhase.CONFIG) { Unit } }
            val original = file.readText()
            runBlocking { progress.stage(SetupPhase.FIXTURE) { Unit } }
            assertTrue(file.readText().startsWith(original))
            val retained = file.readText()
            assertThrows(IllegalStateException::class.java) { SyntheticSetupProgress.create(root, "fresh") }
            assertEquals(retained, file.readText())
        }
    }

    @Test
    fun unsafe_ids_and_symlink_output_roots_are_rejected() {
        withRoot { root ->
            assertThrows(IllegalArgumentException::class.java) { SyntheticSetupProgress.create(root, "../escape") }
            val destination = root.resolve("destination").also { it.mkdir() }
            val link = root.resolve("output")
            Files.createSymbolicLink(link.toPath(), destination.toPath())
            try {
                assertThrows(IllegalArgumentException::class.java) { SyntheticSetupProgress.create(root, "fresh") }
                assertTrue(destination.listFiles().orEmpty().isEmpty())
            } finally {
                Files.delete(link.toPath())
            }
            val rootAlias = root.resolve("alias")
            Files.createSymbolicLink(rootAlias.toPath(), destination.toPath())
            try {
                assertThrows(IllegalArgumentException::class.java) { SyntheticSetupProgress.create(rootAlias, "fresh") }
            } finally {
                Files.delete(rootAlias.toPath())
            }
        }
    }

    @Test
    fun progress_is_bounded_for_many_hash_callbacks_and_contains_only_allowed_fields() {
        withRoot { root ->
            val progress = SyntheticSetupProgress.create(root, "bounded", clock = { 0L })
            val total = 2L * 1024 * 1024 * 1024
            runBlocking {
                progress.stage(SetupPhase.MODEL_HASH, total) { report ->
                    repeat(2048) { report((it + 1L) * 1024 * 1024) }
                }
            }
            val rows = records(progress)
            assertTrue(rows.size <= 34)
            assertEquals(
                total,
                rows
                    .last()
                    .getValue("bytes_processed")
                    .jsonPrimitive.long,
            )
            assertEquals((rows.indices).map(Int::toLong), rows.map { it.getValue("sequence").jsonPrimitive.long })
            rows.forEach {
                assertEquals(
                    setOf(
                        "schema_version",
                        "run_id",
                        "sequence",
                        "phase",
                        "event",
                        "elapsed_ms",
                        "bytes_processed",
                        "total_bytes",
                        "error_code",
                    ),
                    it.keys,
                )
            }
            assertTrue(progress.output.resolve("setup-progress.jsonl").length() <= 256 * 1024)
        }
    }

    @Test
    fun cancellation_is_preserved_and_progress_never_contains_exception_content() {
        withRoot { root ->
            val content = ByteArray(2 * 1024 * 1024) { 7 }
            val file = root.resolve("model.gguf").also { it.writeBytes(content) }
            val progress = SyntheticSetupProgress.create(root, "cancelled")
            val cancelled = CancellationException("PRIVATE_SENTINEL")
            var checks = 0
            val thrown =
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        progress.stage(SetupPhase.MODEL_HASH, content.size.toLong()) { report ->
                            SyntheticSetup.verifiedModel(file, sha256(content), content.size.toLong(), null, report) {
                                if (++checks == 2) throw cancelled
                            }
                        }
                    }
                }
            assertSame(cancelled, thrown)
            assertEquals("CANCELLED", records(progress).last().string("error_code"))
            assertEquals(
                1024L * 1024,
                records(progress)
                    .last()
                    .getValue("bytes_processed")
                    .jsonPrimitive.long,
            )
            assertFalse(
                progress.output
                    .resolve("setup-progress.jsonl")
                    .readText()
                    .contains("PRIVATE_SENTINEL"),
            )
        }
    }

    @Test
    fun progress_write_failure_cannot_hide_the_original_setup_error() {
        withRoot { root ->
            val progress = SyntheticSetupProgress.create(root, "error")
            val original = IllegalStateException("original")
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        progress.stage(SetupPhase.CONFIG) {
                            check(progress.output.resolve("setup-progress.jsonl").delete())
                            throw original
                        }
                    }
                }
            assertSame(original, thrown)
            assertEquals(1, thrown.suppressed.size)
        }
    }

    @Test
    fun suspended_setup_stage_retains_cancellation_without_content() {
        withRoot { root ->
            val progress = SyntheticSetupProgress.create(root, "suspend-cancelled")
            val cancelled = CancellationException("PRIVATE_SUSPEND_SENTINEL")
            val thrown =
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        progress.stage(SetupPhase.ENGINE_LOAD) {
                            yield()
                            throw cancelled
                        }
                    }
                }
            assertSame(cancelled, thrown)
            assertEquals("CANCELLED", records(progress).last().string("error_code"))
            assertFalse(
                progress.output
                    .resolve("setup-progress.jsonl")
                    .readText()
                    .contains("PRIVATE_SUSPEND_SENTINEL"),
            )
        }
    }

    private fun records(progress: SyntheticSetupProgress): List<JsonObject> =
        progress.output
            .resolve("setup-progress.jsonl")
            .readLines()
            .map { Json.parseToJsonElement(it).jsonObject }

    private fun config(): JsonObject =
        Json
            .parseToJsonElement(
                """{"model_sha256":"$ABC_SHA","model_size":3,"host_model_identity":{"schema_version":1,"model_sha256":"$ABC_SHA","model_size":3,"blake3":"$ABC_B3","evidence_sha256":"${"e".repeat(
                    64,
                )}"}}""",
            ).jsonObject

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("synthetic-setup").toFile().canonicalFile
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val ABC_SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        const val ABC_B3 = "6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85"
    }
}
