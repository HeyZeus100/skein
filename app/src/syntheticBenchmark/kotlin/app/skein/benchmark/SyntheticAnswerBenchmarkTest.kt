package app.skein.benchmark

import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.skein.core.inference.ContextBudget
import app.skein.core.inference.InferenceConfig
import app.skein.core.inference.engine.AndroidServiceConnector
import app.skein.core.inference.engine.LlamaCppEngine
import app.skein.core.inference.engine.StoreModelPinSource
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ManifestParse
import app.skein.core.inference.models.ModelManifest
import app.skein.core.inference.models.PermissionEnforcement
import app.skein.core.inference.models.StoredFile
import app.skein.core.inference.models.StoredModel
import app.skein.core.model.Capability
import app.skein.core.model.ChatMessage
import app.skein.core.model.CitationRecordJson
import app.skein.core.model.DocumentKind
import app.skein.core.model.InferenceEngine
import app.skein.core.model.InferenceException
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.NewDocument
import app.skein.core.model.NewMessage
import app.skein.core.model.Persona
import app.skein.core.model.Prompt
import app.skein.core.model.RetrievalService
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import app.skein.core.model.SamplingParams
import app.skein.core.model.Token
import app.skein.core.rag.prompt.PromptAssemblerImpl
import app.skein.core.verify.ModelFileRole
import app.skein.feature.chat.ChatKnowledge
import app.skein.feature.chat.SendPipeline
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in synthetic-only benchmark. No vault, model registry, Activity, or owner conversation is opened. */
@RunWith(AndroidJUnit4::class)
class SyntheticAnswerBenchmarkTest {
    @Test
    fun suppliedEvidenceThroughTheIsolatedApk(): Unit =
        runBlocking(Dispatchers.Default) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val args = InstrumentationRegistry.getArguments()
            check(args.getString("synthetic_enabled") == "true") { "synthetic benchmark requires explicit opt-in" }
            val context = instrumentation.targetContext
            val root = File(context.filesDir.canonicalFile, "synthetic-benchmark")
            require(root == root.canonicalFile) { "benchmark root cannot contain a symlink" }
            val configFile = confinedFile(File(root, "input"), checkNotNull(args.getString("synthetic_config")))
            check(configFile.length() <= 65_536) { "configuration exceeds bound" }
            val config = Json.parseToJsonElement(configFile.readText()).jsonObject
            require(config.getValue("schema_version").jsonPrimitive.int == 1) { "unsupported configuration schema" }
            listOf(
                "model_sha256",
                "template_sha256",
                "tokenizer_overlay_sha256",
                "fixture_sha256",
                "case_set_sha256",
                "expected_apk_sha256",
            ).forEach {
                require(config.string(it).matches(SyntheticFixtures.SHA256)) { "full SHA256 required" }
            }
            listOf("build_sha", "llama_sha").forEach {
                require(config.string(it).matches(Regex("[a-f0-9]{40}"))) { "full source commit required" }
            }
            val runId = config.string("run_id")
            require(runId.matches(SyntheticFixtures.SAFE_ID)) { "unsafe run id" }
            val progress = SyntheticSetupProgress.create(root, runId)
            val output = progress.output
            val checkSetupCancelled = { coroutineContext.ensureActive() }
            val (settings, params) =
                progress.stage(SetupPhase.CONFIG) {
                    val settings = SyntheticSetup.configuration(config)
                    settings to samplingParams(settings.sampling, settings.seeds.first())
                }
            val seeds = settings.seeds
            val contextLength = settings.contextLength
            val threads = settings.threads
            val timeout = settings.caseTimeoutMs
            val sampling = settings.sampling
            val modelSize = settings.modelSize
            val hostIdentity = settings.hostIdentity
            val testBuildSha = settings.testBuildSha
            val cases =
                progress.stage(SetupPhase.FIXTURE) {
                    checkSetupCancelled()
                    val fixture = confinedFile(File(root, "input"), config.string("fixture_file"))
                    check(fixture.length() <= 8 * 1024 * 1024) { "fixture exceeds bound" }
                    val fixtureBytes = fixture.readBytes()
                    check(sha256(fixtureBytes) == config.string("fixture_sha256")) { "fixture hash mismatch" }
                    SyntheticFixtures.parse(fixtureBytes.toString(Charsets.UTF_8))
                }
            val appApk = File(context.applicationInfo.sourceDir)
            val apkHash =
                progress.stage(SetupPhase.APP_APK_HASH, appApk.length()) { report ->
                    SyntheticSetup
                        .hashFile(
                            appApk,
                            progress = report,
                            checkCancelled = checkSetupCancelled,
                        ).sha256
                        .also {
                            check(it == config.string("expected_apk_sha256")) { "installed APK hash mismatch" }
                        }
                }
            val (modelFile, modelDigests) =
                progress.stage(SetupPhase.MODEL_HASH, modelSize) { report ->
                    val file = confinedFile(File(root, "models"), config.string("model_file"))
                    require(
                        file.name == "model.gguf" &&
                            file.parentFile?.name == config.string("model_sha256") &&
                            file.parentFile?.parentFile == File(root, "models").canonicalFile,
                    ) { "model must use dedicated hash-named benchmark directory" }
                    file to
                        SyntheticSetup.verifiedModel(
                            file,
                            config.string("model_sha256"),
                            modelSize,
                            hostIdentity,
                            report,
                            checkSetupCancelled,
                        )
                }
            val (modelHash, blake3) = modelDigests
            val model =
                Model(
                    id = "synthetic-${modelHash.take(16)}",
                    name = "Public synthetic benchmark model",
                    path = modelFile.path,
                    sha256 = modelHash,
                    format = ModelFormat.GGUF,
                    capabilities = setOf(Capability.TEXT),
                    sizeBytes = modelFile.length(),
                    contextLength = contextLength,
                )
            val store = ImmutableModelStore(File(root, "models"))
            progress.stage(SetupPhase.STORE) {
                // Only dedicated staged public model bytes are sealed. Owner models are never touched.
                Os.chmod(modelFile.path, 0x100) // 0400
                Os.chmod(checkNotNull(modelFile.parentFile).path, 0x140) // 0500
                store.register(
                    StoredModel(
                        model.id,
                        checkNotNull(modelFile.parentFile),
                        mapOf(
                            ModelFileRole.MAIN to
                                StoredFile(ModelFileRole.MAIN, modelFile, modelHash, blake3, model.sizeBytes),
                        ),
                        PermissionEnforcement.POSIX,
                    ),
                )
            }
            val manifest = modelManifest(model, config.string("model_license"))
            val engine =
                LlamaCppEngine(
                    connector = AndroidServiceConnector(context),
                    pins = StoreModelPinSource(store) { manifest },
                    spillDir = File(output, "spill").also { check(it.mkdir()) },
                    sessionEpoch = { EPOCH },
                    config = InferenceConfig(contextLengthCap = contextLength, threads = threads, gpuLayers = 0),
                )
            val testApk = File(instrumentation.context.applicationInfo.sourceDir)
            val testApkHash =
                progress.stage(SetupPhase.TEST_APK_HASH, testApk.length()) { report ->
                    SyntheticSetup.hashFile(testApk, progress = report, checkCancelled = checkSetupCancelled).sha256
                }
            val provenance =
                buildJsonObject {
                    put("schema_version", JsonPrimitive(1))
                    put("engine_path", JsonPrimitive("isolated-apk"))
                    put("mode", JsonPrimitive("supplied_evidence"))
                    put("run_id", JsonPrimitive(runId))
                    listOf("case_set_sha256", "fixture_sha256", "template_sha256", "build_sha", "llama_sha").forEach {
                        put(it, config.getValue(it))
                    }
                    put("tokenizer_overlay_sha256", config.getValue("tokenizer_overlay_sha256"))
                    put("model_sha256", JsonPrimitive(modelHash))
                    put("model_blake3", JsonPrimitive(blake3))
                    put("model_blake3_provenance", JsonPrimitive(SyntheticSetup.provenance(hostIdentity)))
                    put(
                        "host_model_identity_evidence_sha256",
                        hostIdentity?.evidenceSha256?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put("apk_sha256", JsonPrimitive(apkHash))
                    put("test_apk_sha256", JsonPrimitive(testApkHash))
                    put("test_build_sha", testBuildSha?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "test_build_sha_provenance",
                        JsonPrimitive(
                            if (testBuildSha == null) {
                                "not supplied; installed test APK digest measured"
                            } else {
                                "declared host opt-in test source; installed test APK digest measured"
                            },
                        ),
                    )
                    put("build_sha_provenance", JsonPrimitive("declared host build; installed APK digest verified"))
                    put("llama_sha_provenance", JsonPrimitive("declared host source pin"))
                    put(
                        "tokenizer_overlay_sha256_provenance",
                        JsonPrimitive("declared host SHA256 of native/llama/tokenizer-patches/PINS.txt"),
                    )
                    put(
                        "template_sha256_provenance",
                        JsonPrimitive("host GGUF raw tokenizer.chat_template bound by model_sha256"),
                    )
                    put("sampling", sampling)
                    put("seeds", JsonArray(seeds.map(::JsonPrimitive)))
                    put("context", buildJsonObject { put("requested", JsonPrimitive(contextLength)) })
                    put(
                        "device",
                        buildJsonObject {
                            put("model", JsonPrimitive(Build.MODEL))
                            put("fingerprint", JsonPrimitive(Build.FINGERPRINT))
                            put("sdk", JsonPrimitive(Build.VERSION.SDK_INT))
                            put("security_patch", JsonPrimitive(Build.VERSION.SECURITY_PATCH))
                        },
                    )
                    put("threads", JsonPrimitive(threads))
                    put("case_timeout_ms", JsonPrimitive(timeout))
                    put("memory", JsonNull)
                    put("retrieval_validation", JsonPrimitive("not measured; host supplied current evidence"))
                }
            val manifestFile = File(output, "run_manifest.json")
            progress.stage(SetupPhase.MANIFEST) { manifestFile.writeText(provenance.toString() + "\n") }
            progress.stage(SetupPhase.SESSION_UNLOCK) { engine.onSessionUnlocked(EPOCH) }
            var reload = true
            var loadProgressRecorded = false
            try {
                for (case in cases) {
                    for (seed in seeds) {
                        val observed = ObservedEngine(engine)
                        val started = System.nanoTime()
                        var pipeline: SendPipeline? = null
                        var status = "ok"
                        var code: String? = null
                        try {
                            withTimeout(timeout) {
                                if (reload) {
                                    if (!loadProgressRecorded) {
                                        loadProgressRecorded = true
                                        progress.stage(SetupPhase.ENGINE_LOAD) { engine.load(model).getOrThrow() }
                                    } else {
                                        engine.load(model).getOrThrow()
                                    }
                                    reload = false
                                    val measured =
                                        engine.measurePrompt(
                                            Prompt(listOf(ChatMessage(Role.USER, "Synthetic context probe"))),
                                            params,
                                        )
                                    check(measured.modelSha256 == modelHash) { "loaded model identity differs" }
                                    val updated =
                                        provenance + (
                                            "context" to
                                                buildJsonObject {
                                                    put("requested", JsonPrimitive(contextLength))
                                                    put("allocated", JsonPrimitive(measured.contextLength))
                                                }
                                        )
                                    manifestFile.writeText(JsonObject(updated).toString() + "\n")
                                }
                                val repository = InMemoryVaultRepository()
                                val persona =
                                    Persona("synthetic-space", "Synthetic", case.personaSystemPrompt, model.id, 0L)
                                val chat =
                                    repository.createDocument(
                                        NewDocument(DocumentKind.CHAT, "Synthetic", null, personaId = persona.id),
                                    )
                                ChatKnowledge.setEnabled(repository, chat.id, case.knowledge)
                                case.history.forEach {
                                    repository.appendMessage(
                                        chat.id,
                                        NewMessage(it.role, it.content),
                                    )
                                }
                                val budget = ContextBudget(engine, InferenceConfig(contextLengthCap = contextLength))
                                budget.useModel(model)
                                val retrieval =
                                    object : RetrievalService {
                                        override suspend fun retrieveContext(
                                            query: String,
                                            k: Int,
                                            personaId: String?,
                                        ): List<Retrieved> = case.sources
                                    }
                                pipeline =
                                    SendPipeline(
                                        vaultRepository = repository,
                                        retrievalService = retrieval,
                                        promptAssembler = PromptAssemblerImpl(),
                                        engine = observed,
                                        personaProvider = { persona },
                                        personaById = { persona },
                                        prepareModel = { model.id },
                                        budgetFor = budget::computeBudget,
                                        countTokens = { runBlocking { budget.countTokens(it) } },
                                        samplingParams = { samplingParams(sampling, seed) },
                                        measurePrompt = engine::measurePrompt,
                                    )
                                checkNotNull(pipeline).send(chat.id, case.query).collect { }
                                val completed =
                                    checkNotNull(
                                        checkNotNull(pipeline).lastOutcome.value,
                                    ) { "turn completed without outcome" }
                                check(
                                    completed.generationSkipped || observed.stats != null,
                                ) { "generation ended without terminal stats" }
                            }
                        } catch (_: TimeoutCancellationException) {
                            status = "timeout"
                            code = "CASE_TIMEOUT"
                        } catch (_: InferenceException.OutOfMemory) {
                            status = "oom"
                            code = "OUT_OF_MEMORY"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            status = "error"
                            code = error.javaClass.simpleName
                        }
                        val outcome = pipeline?.lastOutcome?.value
                        val usedSourcesStatus =
                            if (outcome == null) "unavailable_before_finalization" else "finalized_prompt"
                        val record = outcome?.assistantMessage?.citations
                        val cited = record?.retrieved?.filter { it.marker in record.cited }.orEmpty()
                        val row =
                            buildJsonObject {
                                put("run_id", JsonPrimitive(runId))
                                put("case_id", JsonPrimitive(case.id))
                                put("seed", JsonPrimitive(seed))
                                put("status", JsonPrimitive(status))
                                put(
                                    "answer",
                                    JsonPrimitive(
                                        outcome?.assistantMessage?.contentMd ?: observed.answer.toString(),
                                    ),
                                )
                                put("scope", JsonPrimitive(if (case.knowledge) "knowledge" else "general"))
                                put("generation_skipped", JsonPrimitive(outcome?.generationSkipped ?: false))
                                put("provided_sources", sourcesJson(case.sources))
                                put(
                                    "used_sources",
                                    sourcesJson(
                                        outcome
                                            ?.assembled
                                            ?.citations
                                            ?.values
                                            ?.toList()
                                            .orEmpty(),
                                    ),
                                )
                                put("used_sources_status", JsonPrimitive(usedSourcesStatus))
                                put("citations", JsonArray(cited.map { sourceJson(it.documentId, it.revisionHash) }))
                                if (record !=
                                    null
                                ) {
                                    put("citation_records", Json.parseToJsonElement(CitationRecordJson.encode(record)))
                                }
                                put("elapsed_ms", JsonPrimitive((System.nanoTime() - started) / 1_000_000))
                                put("error_code", code?.let(::JsonPrimitive) ?: JsonNull)
                                put("memory", JsonNull)
                                put("measurement", observed.measurement ?: JsonNull)
                                put("generation", observed.stats ?: JsonNull)
                            }
                        File(output, "answers.jsonl").appendText(row.toString() + "\n")
                        if (status != "ok") {
                            engine.cancel()
                            engine.unload()
                            reload = true
                        }
                    }
                }
            } finally {
                engine.cancel()
                engine.unload()
                engine.onSessionLocked(EPOCH)
            }
        }

    private class ObservedEngine(
        private val engine: LlamaCppEngine,
    ) : InferenceEngine by engine {
        val answer = StringBuilder()
        var stats: JsonObject? = null
        var measurement: JsonObject? = null

        override fun stream(
            prompt: Prompt,
            params: SamplingParams,
        ): Flow<Token> =
            flow {
                val measured = engine.measurePrompt(prompt, params)
                measurement =
                    buildJsonObject {
                        put("prompt_tokens", JsonPrimitive(measured.promptTokens))
                        put("allocated_context", JsonPrimitive(measured.contextLength))
                        put("model_sha256", JsonPrimitive(measured.modelSha256))
                    }
                engine.stream(prompt, params).collect { token ->
                    when (token) {
                        is Token.Text -> answer.append(token.text)
                        is Token.Done -> {
                            check(
                                token.promptTokens == measured.promptTokens,
                            ) { "isolated prompt measurement differs from generation" }
                            stats =
                                buildJsonObject {
                                    put("prompt_tokens", JsonPrimitive(token.promptTokens))
                                    put("generated_tokens", JsonPrimitive(token.generatedTokens))
                                    put("ttft_ms", JsonPrimitive(token.ttftMs))
                                    put("tokens_per_second", JsonPrimitive(token.tokensPerSec))
                                    put("stop_reason", JsonPrimitive(token.reason.name))
                                    put("isolated_count_consistency", JsonPrimitive(true))
                                }
                        }
                    }
                    emit(token)
                }
            }
    }

    private fun samplingParams(
        json: JsonObject,
        seed: Long,
    ): SamplingParams =
        SamplingParams(
            temperature = json.getValue("temperature").jsonPrimitive.float,
            topK = json.getValue("top_k").jsonPrimitive.int,
            topP = json.getValue("top_p").jsonPrimitive.float,
            minP = json.getValue("min_p").jsonPrimitive.float,
            repeatPenalty = json.getValue("repeat_penalty").jsonPrimitive.float,
            maxTokens = json.getValue("max_tokens").jsonPrimitive.int,
            seed = seed,
            stop = json["stop"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )

    private fun modelManifest(
        model: Model,
        license: String,
    ): ModelManifest {
        val json =
            buildJsonObject {
                put("manifest_version", JsonPrimitive(2))
                put("id", JsonPrimitive(model.id))
                put("name", JsonPrimitive(model.name))
                put("format", JsonPrimitive("gguf"))
                put("file", JsonPrimitive("model.gguf"))
                put("sha256", JsonPrimitive(model.sha256))
                put("size_bytes", JsonPrimitive(model.sizeBytes))
                put("capabilities", JsonArray(listOf(JsonPrimitive("text"))))
                put("context_length", JsonPrimitive(model.contextLength))
                put("license", buildJsonObject { put("spdx", JsonPrimitive(license)) })
                put("companions", JsonArray(emptyList()))
            }
        return (ModelManifest.parse(json.toString()) as ManifestParse.Parsed).manifest
    }

    private fun sourcesJson(sources: List<Retrieved>): JsonArray =
        JsonArray(sources.map { sourceJson(it.docId, checkNotNull(it.revisionHash)) })

    private fun sourceJson(
        id: String,
        revision: String,
    ): JsonObject =
        buildJsonObject {
            put("doc_id", JsonPrimitive(id))
            put("revision_hash", JsonPrimitive(revision))
        }

    private companion object {
        const val EPOCH = 1L
    }
}
