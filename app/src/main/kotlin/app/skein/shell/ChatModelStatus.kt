package app.skein.shell

import app.skein.core.model.EngineState
import app.skein.core.model.ModelRegistry
import app.skein.core.model.ModelStatus
import app.skein.feature.chat.ChatModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.transformLatest

/** Resolve the observed engine identity, never the registry default or a model's storage path. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun chatModelStatuses(
    statuses: Flow<ModelStatus>,
    registry: ModelRegistry,
): Flow<ChatModelStatus> =
    statuses.distinctUntilChangedBy { it.modelId to it.state }.transformLatest { status ->
        // Clear the previous identity immediately, even if the new registry lookup is slow or fails.
        emit(ChatModelStatus.Observed(status.state))
        val id = status.modelId?.takeUnless { status.state == EngineState.UNLOADED } ?: return@transformLatest
        val name =
            try {
                registry.get(id)?.model?.name?.trim()?.takeUnless { it == id }?.removeSuffix(".gguf")?.trim()?.takeIf {
                    it.isNotEmpty() &&
                        it != id &&
                        it.none { char -> char.isISOControl() || char == '/' || char == '\\' }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (name != null) emit(ChatModelStatus.Observed(status.state, name))
    }
