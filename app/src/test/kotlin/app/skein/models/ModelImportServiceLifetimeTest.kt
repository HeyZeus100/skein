package app.skein.models

import android.net.Uri
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ImportRefusal
import app.skein.core.inference.models.ModelCopyResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ModelImportServiceLifetimeTest {
    @get:Rule val temp = TemporaryFolder()
    private val uri = Uri.parse("content://fixture/model")
    private val refusal = ModelCopyResult.Refused(ImportRefusal.Unsupported)

    @Test
    fun `duplicate service start stops latest Android id after one copy`() =
        runTest {
            val gate = CompletableDeferred<ModelCopyResult>()
            var copies = 0
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, _ ->
                        copies++
                        gate.await()
                    },
                    startExecution = { _, _ -> },
                    scope = backgroundScope,
                )
            val owner = Any()
            imports.attach(owner) { error("Refused before registration") }
            val stops = mutableListOf<Int>()
            val completions = mutableListOf<() -> Unit>()
            val lifetime = ModelImportServiceLifetime(imports, stops::add)
            imports.startImport(uri)
            lifetime.started(1, 10, completions::add)
            lifetime.started(1, 11, completions::add)
            runCurrent()
            assertThat(copies).isEqualTo(1)
            gate.complete(refusal)
            runCurrent()
            assertThat(completions).hasSize(1)
            completions.single().invoke()
            assertThat(stops).containsExactly(11)
            lifetime.stopped()
            imports.detachOwner(owner)
        }

    @Test
    fun `old destruction and delayed completion preserve newer pending admission for replacement service`() =
        runTest {
            var copies = 0
            val gate = CompletableDeferred<ModelCopyResult>()
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, _ -> if (++copies == 1) refusal else gate.await() },
                    startExecution = { _, _ -> },
                    scope = backgroundScope,
                )
            val owner = Any()
            imports.attach(owner) { error("Refused before registration") }
            val stops = mutableListOf<Int>()
            val completions = mutableListOf<() -> Unit>()
            val old = ModelImportServiceLifetime(imports, stops::add)
            imports.startImport(uri)
            old.started(1, 10, completions::add)
            runCurrent()
            assertThat(imports.state.value).isInstanceOf(ModelImportState.Done::class.java)
            imports.startImport(uri)
            old.stopped() // Before Android delivers the new intent, the old instance is destroyed.
            completions.removeAt(0).invoke()
            assertThat(stops).isEmpty()
            assertThat(imports.isRunning(2)).isTrue()
            assertThat(imports.executePending(1) {}).isFalse()
            val replacement = ModelImportServiceLifetime(imports, stops::add)
            replacement.started(2, 12, completions::add)
            runCurrent()
            old.stopped() // Also cannot cancel the now-running new operation.
            imports.executionStopped(1)
            assertThat(imports.isRunning(2)).isTrue()
            gate.complete(refusal)
            runCurrent()
            completions.single().invoke()
            assertThat(copies).isEqualTo(2)
            assertThat(stops).containsExactly(12)
            replacement.stopped()
            imports.detachOwner(owner)
        }

    @Test
    fun `malformed intent cannot consume pending copy and timeout cancels only its admitted token`() =
        runTest {
            var copies = 0
            var cancelled = false
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, _ ->
                        copies++
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                    startExecution = { _, _ -> },
                    scope = backgroundScope,
                )
            val owner = Any()
            imports.attach(owner) { error("No complete copy") }
            val stops = mutableListOf<Int>()
            val lifetime = ModelImportServiceLifetime(imports, stops::add)
            imports.startImport(uri)
            lifetime.started(0, 10) { it() }
            runCurrent()
            assertThat(copies).isEqualTo(0)
            assertThat(imports.isRunning(1)).isTrue()
            lifetime.started(1, 11) { it() }
            runCurrent()
            imports.executionStopped(999)
            runCurrent()
            assertThat(cancelled).isFalse()
            lifetime.stopped()
            assertThat(imports.startImport(uri)).isFalse() // Cancel was requested, but cleanup has not run.
            runCurrent()
            assertThat(cancelled).isTrue()
            assertThat(imports.state.value).isEqualTo(ModelImportState.Done(ModelImportOutcome.FAILED))
            assertThat(stops).containsExactly(11)
            imports.detachOwner(owner)
        }
}
