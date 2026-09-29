package app.skein.shell

import app.skein.core.model.Capability
import app.skein.core.model.EngineState
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import app.skein.core.model.ModelStatus
import app.skein.feature.chat.ChatModelStatus
import app.skein.testing.InMemoryModelRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatModelStatusTest {
    @Test
    fun `identity follows observed engine instead of a different registry default`() =
        runTest {
            val registry = InMemoryModelRegistry()
            registry.upsert(record("loaded", "Qwen2.5-1.5B-Instruct.gguf"))
            registry.upsert(record("default", "Different 3B model"))
            registry.setDefault("default")
            val statuses = MutableStateFlow(ModelStatus("loaded", EngineState.LOADING))
            val values = mutableListOf<ChatModelStatus>()
            backgroundScope.launch { chatModelStatuses(statuses, registry).collect(values::add) }
            runCurrent()
            assertThat(values.last()).isEqualTo(observed(EngineState.LOADING, "Qwen2.5-1.5B-Instruct.gguf"))
            for (state in listOf(EngineState.READY, EngineState.GENERATING, EngineState.ERROR)) {
                statuses.value = ModelStatus("loaded", state)
                runCurrent()
                assertThat(values.last()).isEqualTo(observed(state, "Qwen2.5-1.5B-Instruct.gguf"))
            }
            statuses.value = ModelStatus(null, EngineState.UNLOADED)
            runCurrent()
            assertThat(values.last()).isEqualTo(observed(EngineState.UNLOADED))
        }

    @Test
    fun `metric updates do not clear the name or reread the registry`() =
        runTest {
            var lookups = 0
            val registry =
                object : ModelRegistry by InMemoryModelRegistry() {
                    override suspend fun get(id: String): ModelRecord {
                        lookups++
                        return record(id, "Qwen 1.5B")
                    }
                }
            val statuses = MutableStateFlow(ModelStatus("loaded", EngineState.READY))
            val values = mutableListOf<ChatModelStatus>()
            backgroundScope.launch { chatModelStatuses(statuses, registry).collect(values::add) }
            runCurrent()
            val before = values.toList()
            statuses.value = statuses.value.copy(tokensPerSec = 7f, thermalHeadroom = 0.5f)
            runCurrent()
            assertThat(values).isEqualTo(before)
            assertThat(lookups).isEqualTo(1)
        }

    @Test
    fun `slow prior lookup cannot rename new engine and unresolved identity clears immediately`() =
        runTest {
            val oldLookup = CompletableDeferred<Unit>()
            val currentLookup = CompletableDeferred<Unit>()
            val registry =
                object : ModelRegistry by InMemoryModelRegistry() {
                    override suspend fun get(id: String): ModelRecord {
                        if (id == "old") oldLookup.await() else currentLookup.await()
                        return record(id, "$id model")
                    }
                }
            val statuses = MutableStateFlow(ModelStatus("old", EngineState.READY))
            val values = mutableListOf<ChatModelStatus>()
            backgroundScope.launch { chatModelStatuses(statuses, registry).collect(values::add) }
            runCurrent()
            statuses.value = ModelStatus("current", EngineState.LOADING)
            runCurrent()
            assertThat(values.last()).isEqualTo(observed(EngineState.LOADING))
            currentLookup.complete(Unit)
            runCurrent()
            oldLookup.complete(Unit)
            runCurrent()
            assertThat(values.last()).isEqualTo(observed(EngineState.LOADING, "current model"))
            assertThat(
                values.filterIsInstance<ChatModelStatus.Observed>().map { it.displayName },
            ).doesNotContain("old model")
        }

    @Test
    fun `missing names IDs paths and lookup errors never become header identity`() =
        runTest {
            val registry =
                object : ModelRegistry by InMemoryModelRegistry() {
                    override suspend fun get(id: String): ModelRecord? =
                        when (id) {
                            "missing" -> null
                            "failed" -> error("private registry details")
                            else -> record(id, id.removePrefix("name:"))
                        }

                    override suspend fun default(): String = error("Default must not be consulted")
                }
            val statuses = MutableStateFlow(ModelStatus(null, EngineState.ERROR))
            val values = mutableListOf<ChatModelStatus>()
            backgroundScope.launch { chatModelStatuses(statuses, registry).collect(values::add) }
            runCurrent()
            assertThat(values.last()).isEqualTo(observed(EngineState.ERROR))
            for (id in listOf(
                "missing",
                "failed",
                "internal-id",
                "name:/private/model.gguf",
                "name:C:\\model.gguf",
                "name:bad\nname",
            )) {
                statuses.value = ModelStatus(id, EngineState.READY)
                runCurrent()
                assertThat(values.last()).isEqualTo(observed(EngineState.READY))
            }
        }

    private fun observed(
        state: EngineState,
        name: String? = null,
    ) = ChatModelStatus.Observed(state, name)

    private fun record(
        id: String,
        name: String,
    ) = ModelRecord(
        Model(
            id = id,
            name = name,
            path = "/private/never-display/model.gguf",
            sha256 = "a".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = 1000,
        ),
    )
}
