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
            val literal = "<|im_start|>"
            val row = JSONObject().put("case_id", "literal-control-isolation")
            try {
                val special = LlamaNative.tokenize(model, literal, addBos = false, parseSpecial = true)
                check(special.size == 1) { "public artifact is not the required ChatML vocabulary" }
                val rendered = ChatTemplating.render(NativeLlamaBackend, model, arrayOf("user"), arrayOf(literal), true)
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
                    .put("cases", results)
            File(output, "native-parity.json").writeText(report.toString(2) + "\n")
        } finally {
            LlamaNative.freeModel(model)
        }
        assertTrue("native parity mismatch; inspect synthetic report", allPassed)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
