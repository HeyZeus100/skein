package app.skein.inference.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Separate library test app: only a staged public model and built-in synthetic prompts are accessible. */
@RunWith(AndroidJUnit4::class)
class SyntheticTemplateParityTest {
    @Test
    fun publicModelNativeTokenParity() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("synthetic_enabled") == "true") { "synthetic parity requires explicit opt-in" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir.canonicalFile, "synthetic-benchmark")
        require(root == root.canonicalFile) { "benchmark root cannot contain a symlink" }
        val modelRoot = File(root, "models")
        require(modelRoot == modelRoot.canonicalFile) { "model root cannot contain a symlink" }
        val file = File(checkNotNull(args.getString("synthetic_model_file"))).canonicalFile
        require(file.path.startsWith(modelRoot.path + File.separator) && file.isFile) {
            "model must be inside dedicated benchmark storage"
        }
        val expectedHash = checkNotNull(args.getString("synthetic_model_sha256"))
        require(expectedHash.matches(Regex("[a-f0-9]{64}"))) { "invalid full model hash" }
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        check(hex(sha.digest()) == expectedHash) { "full model hash mismatch" }
        val manifest =
            readControls(
                root,
                args.getString("synthetic_control_manifest"),
                args.getString("synthetic_control_manifest_sha256"),
            )
        val runId = checkNotNull(args.getString("synthetic_run_id"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "unsafe run id" }
        val outputRoot = File(root, "output")
        require(outputRoot == outputRoot.canonicalFile) { "output root cannot contain a symlink" }
        val output = File(outputRoot, runId)
        check(!output.exists() && output.mkdirs()) { "parity run already exists" }
        LlamaNative.backendInit()
        LlamaNative.setLogCallback()
        val model = LlamaNative.loadModel(file.path, nGpuLayers = 0, useMmap = true)
        check(model != 0L) { "model did not load" }
        val results = JSONArray()
        var allPassed = true
        try {
            val template = checkNotNull(LlamaNative.modelMeta(model, "tokenizer.chat_template"))
            manifest?.first?.let { controls ->
                try {
                    controls.validate(
                        expectedHash,
                        file.length(),
                        template,
                        LlamaNative.modelMeta(model, "general.architecture").orEmpty(),
                        LlamaNative.modelMeta(model, "general.name").orEmpty(),
                        LlamaNative.modelMeta(model, "tokenizer.ggml.eos_token_id")?.trim()?.toIntOrNull(),
                    )
                } catch (failure: Exception) {
                    File(output, "native-parity.json").writeText(
                        JSONObject()
                            .put("run_id", runId)
                            .put("model_sha256", expectedHash)
                            .put("control_manifest_sha256", manifest.second)
                            .put("qualification_status", "error")
                            .put("error_code", failure.javaClass.simpleName)
                            .put("cases", results)
                            .toString(2) + "\n",
                    )
                    throw failure
                }
            }
            val cases =
                listOf(
                    "empty-system" to listOf("system" to "", "user" to "Hello world"),
                    "role-word" to listOf("user" to "user"),
                    "unicode-whitespace" to listOf("system" to "Be precise.", "user" to "  Café 日本語 🧶\n\n"),
                    "repeated-turns" to listOf("user" to "same", "assistant" to "same", "user" to "same"),
                )
            for ((id, turns) in cases) {
                val row = JSONObject().put("case_id", id)
                try {
                    val roles = turns.map { it.first }.toTypedArray()
                    val content = turns.map { it.second }.toTypedArray()
                    val rendered = ChatTemplating.render(NativeLlamaBackend, model, roles, content, true)
                    val nativeText = LlamaNative.applyChatTemplate(model, roles, content, true)
                    val actual = ChatTemplating.tokenize(NativeLlamaBackend, model, rendered.segments)
                    // This all-special reference is allowed ONLY for these fixed benign synthetic cases.
                    val reference = LlamaNative.tokenize(model, nativeText, addBos = true, parseSpecial = true)
                    val equal = actual.contentEquals(reference) && rendered.text == nativeText
                    allPassed = allPassed && equal
                    row.put("status", if (equal) "ok" else "mismatch")
                    row.put("native_render_equal", rendered.text == nativeText)
                    row.put("actual_ids", JSONArray(actual.toList()))
                    row.put("reference_ids", JSONArray(reference.toList()))
                } catch (failure: Exception) {
                    allPassed = false
                    row.put("status", "error").put("error_code", failure.javaClass.simpleName)
                }
                results.put(row)
            }
            val literal = manifest?.first?.literal?.spelling ?: "<|im_start|>"
            val row = JSONObject().put("case_id", "literal-control-isolation")
            try {
                val special = LlamaNative.tokenize(model, literal, addBos = false, parseSpecial = true)
                check(special.size == 1) { "public artifact lacks the required singleton control" }
                manifest?.first?.let { controls ->
                    controls.verifyNativeControl(controls.literal, special, { LlamaNative.isEog(model, it) }, false)
                }
                val rendered = ChatTemplating.render(NativeLlamaBackend, model, arrayOf("user"), arrayOf(literal), true)
                check(rendered.segments.filter { it.kind == SegmentKind.CONTENT }.map { it.text } == listOf(literal)) {
                    "literal content bytes changed"
                }
                val actual = ChatTemplating.tokenize(NativeLlamaBackend, model, rendered.segments)
                val unsafe = LlamaNative.tokenize(model, rendered.text, addBos = true, parseSpecial = true)
                val isolated = actual.count { it == special.single() } == unsafe.count { it == special.single() } - 1
                allPassed = allPassed && isolated
                row.put("status", if (isolated) "ok" else "mismatch")
                row.put("actual_ids", JSONArray(actual.toList()))
                row.put("unsafe_reference_ids", JSONArray(unsafe.toList()))
                row.put("control_token_id", special.single())
            } catch (failure: Exception) {
                allPassed = false
                row.put("status", "error").put("error_code", failure.javaClass.simpleName)
            }
            results.put(row)
            val eog = nativeEog(model, manifest?.first)
            if (manifest != null) allPassed = allPassed && eog.getBoolean("required_controls_passed")
            val report =
                JSONObject()
                    .put("run_id", runId)
                    .put("engine_path", "native-library-test-app")
                    .put("model_sha256", expectedHash)
                    .put("model_size", file.length())
                    .put(
                        "template_sha256",
                        hex(MessageDigest.getInstance("SHA-256").digest(template.toByteArray(Charsets.UTF_8))),
                    ).put("template_sha256_provenance", "native modelMeta of the verified public model")
                    .put("native_eog", eog)
                    .put("cases", results)
            manifest?.let { (controls, digest) ->
                report
                    .put("control_manifest_sha256", digest)
                    .put("control_profile", controls.profile)
                    .put("tokenizer_metadata_sha256", controls.tokenizerMetadataSha256)
                    .put(
                        "tokenizer_metadata_sha256_provenance",
                        "host metadata receipt bound to the full verified model hash",
                    ).put("qualification_status", "metadata-bound-native-checks-attempted")
            }
            File(output, "native-parity.json").writeText(report.toString(2) + "\n")
        } finally {
            LlamaNative.freeModel(model)
        }
        assertTrue("native parity mismatch; inspect synthetic report", allPassed)
    }

    /** Classification of real vocabulary IDs; this does not observe a generated EOS. */
    private fun nativeEog(
        model: Long,
        contract: SyntheticParityControls?,
    ): JSONObject {
        val controls = JSONArray()
        var allPassed = true
        val spellings = contract?.eog?.map { it.spelling } ?: listOf("<|im_end|>", "<|endoftext|>", "</s>")
        for (spelling in spellings) {
            val ids = LlamaNative.tokenize(model, spelling, addBos = false, parseSpecial = true)
            val classifications = JSONArray()
            ids.forEach { id ->
                classifications.put(JSONObject().put("token_id", id).put("is_eog", LlamaNative.isEog(model, id)))
            }
            val row =
                JSONObject()
                    .put("spelling", spelling)
                    .put("token_ids", JSONArray(ids.toList()))
                    .put("singleton", ids.size == 1)
                    .put("classifications", classifications)
            contract?.let {
                try {
                    it.verifyNativeControl(
                        it.eog.single { control ->
                            control.spelling == spelling
                        },
                        ids,
                        { id -> LlamaNative.isEog(model, id) },
                        true,
                    )
                    row.put("status", "ok")
                } catch (failure: Exception) {
                    allPassed = false
                    row.put("status", "error").put("error_code", failure.javaClass.simpleName)
                }
            }
            controls.put(row)
        }
        val declaredEos = LlamaNative.modelMeta(model, "tokenizer.ggml.eos_token_id")?.trim()?.toIntOrNull()
        val eos =
            declaredEos?.let { id ->
                JSONObject().put("token_id", id).put("is_eog", LlamaNative.isEog(model, id))
            }
        val result =
            JSONObject()
                .put("scope", "classification-only")
                .put("declared_eos", eos ?: JSONObject.NULL)
                .put("controls", controls)
                .put("generated_stop_behavior", "unmeasured")
        if (contract != null) result.put("required_controls_passed", allPassed)
        return result
    }

    /** Explicit metadata receipt only; no arbitrary token spellings or default-profile inference. */
    private fun readControls(
        root: File,
        path: String?,
        expectedHash: String?,
    ): Pair<SyntheticParityControls, String>? {
        if (path == null && expectedHash == null) return null
        require(path != null && expectedHash != null && expectedHash.matches(Regex("[a-f0-9]{64}")))
        val inputRoot = File(root, "input")
        require(inputRoot == inputRoot.canonicalFile)
        val file = File(path).canonicalFile
        require(file.path.startsWith(inputRoot.path + File.separator) && file.isFile)
        val bytes =
            file.inputStream().use { input ->
                val buffer = ByteArray(16_385)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count in 1..16_384) { "control manifest exceeds bound" }
                buffer.copyOf(count)
            }
        require(
            hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == expectedHash,
        ) { "control manifest hash mismatch" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))

        fun integer(
            value: JSONObject,
            key: String,
        ): Int {
            val number = value.get(key)
            require(number is Int || number is Long)
            val long = (number as Number).toLong()
            require(long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            return long.toInt()
        }
        require(
            integer(json, "schema_version") == 1 &&
                json.getString("qualification") == "metadata-only-native-parity-controls",
        )

        fun control(value: JSONObject) =
            SyntheticParityControls.Control(value.getString("spelling"), integer(value, "id"), integer(value, "type"))
        val array = json.getJSONArray("eog_controls")
        require(array.length() in 1..2)
        val size = json.get("model_size_bytes")
        require(size is Int || size is Long)
        return SyntheticParityControls(
            json.getString("profile"),
            json.getString("model_sha256"),
            (size as Number).toLong(),
            json.getString("template_sha256"),
            json.getString("tokenizer_metadata_sha256"),
            integer(json, "vocabulary_size"),
            json.getString("architecture"),
            json.getString("model_name"),
            control(json.getJSONObject("literal_control")),
            (0 until array.length()).map { control(array.getJSONObject(it)) },
            integer(json, "declared_eos_id"),
        ) to expectedHash
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
