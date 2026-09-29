package app.skein.models

import android.net.Uri
import app.skein.core.inference.models.ImmutableModelStore
import app.skein.core.inference.models.ImportOutcome
import app.skein.core.inference.models.ImportRefusal
import app.skein.core.inference.models.ManifestFile
import app.skein.core.inference.models.ManifestLicense
import app.skein.core.inference.models.ModelCopyResult
import app.skein.core.inference.models.ModelManifest
import app.skein.core.inference.models.PermissionEnforcement
import app.skein.core.inference.models.StoredModel
import app.skein.core.model.ModelFormat
import app.skein.core.verify.ModelFileRole
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ModelImportCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    private val uri = Uri.parse("content://test/model")

    @Test
    fun `observer disposal and lock leave copy alive with retained progress and sealed outcome`() =
        runTest {
            val copied = CompletableDeferred<ModelCopyResult>()
            var progress: ((Long, Long) -> Unit)? = null
            var started = 0
            var finished = 0
            var registrations = 0
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, report ->
                        progress = report
                        copied.await()
                    },
                    startExecution = { _, _ -> started++ },
                    scope = backgroundScope,
                )
            val owner = Any()
            imports.attach(owner) {
                registrations++
                error("Locked registry must never be accessed")
            }
            val oldObserver = backgroundScope.launch { imports.state.collect() }
            assertThat(imports.startImport(uri)).isTrue()
            assertThat(imports.startImport(uri)).isFalse()
            assertThat(started).isEqualTo(1)
            assertThat(progress).isNull() // Merely asking Android to start is not foreground execution.
            assertThat(imports.executePending(1) { finished++ }).isTrue()
            runCurrent()
            progress!!(1, 4)
            oldObserver.cancel()
            imports.detachOwner(owner)
            assertThat(imports.state.value).isEqualTo(ModelImportState.Running(0.25f))
            copied.complete(copied())
            runCurrent()
            assertThat(registrations).isEqualTo(0)
            assertThat(finished).isEqualTo(1)
            assertThat(imports.state.value).isEqualTo(ModelImportState.Done(ModelImportOutcome.SAVED_FOR_UNLOCK))
            assertThat(imports.startImport(uri)).isFalse() // No unlocked session.
            imports.dismissResult()
            assertThat(imports.state.value).isEqualTo(ModelImportState.Idle)
        }

    @Test
    fun `lock cancels inspection and old teardown cannot revoke a new session`() =
        runTest {
            var entered = false
            var cancelled = false
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, _ -> copied() },
                    startExecution = { _, _ -> },
                    scope = backgroundScope,
                )
            val oldOwner = Any()
            imports.attach(oldOwner) {
                entered = true
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            imports.startImport(uri)
            imports.executePending(1) {}
            runCurrent()
            assertThat(entered).isTrue()
            imports.detachOwner(oldOwner)
            runCurrent()
            assertThat(cancelled).isTrue()
            assertThat(imports.state.value).isEqualTo(ModelImportState.Done(ModelImportOutcome.SAVED_FOR_UNLOCK))
            val newOwner = Any()
            var newCalls = 0
            imports.attach(newOwner) {
                newCalls++
                ImportOutcome.Refused(ImportRefusal.Unsupported)
            }
            imports.detachOwner(oldOwner)
            assertThat(imports.startImport(uri)).isTrue()
            imports.executePending(2) {}
            runCurrent()
            assertThat(newCalls).isEqualTo(1)
            imports.detachOwner(newOwner)
        }

    @Test
    fun `foreground refusal and interrupted start retain failure and do not execute copy`() =
        runTest {
            var copies = 0
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, _ ->
                        copies++
                        copied()
                    },
                    startExecution = { _, _ -> throw IllegalStateException("Background start refused") },
                    scope = backgroundScope,
                )
            val owner = Any()
            imports.attach(owner) { error("No copy") }
            assertThat(imports.startImport(uri)).isFalse()
            assertThat(imports.executePending(1) {}).isFalse()
            assertThat(copies).isEqualTo(0)
            assertThat(imports.state.value).isEqualTo(ModelImportState.Done(ModelImportOutcome.FAILED))
            imports.detachOwner(owner)
        }

    @Test
    fun `new process has no replayable provider URI or work until a fresh admitted pick`() =
        runTest {
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, _ -> error("No URI replay after process death") },
                    startExecution = { _, _ -> },
                    scope = backgroundScope,
                )
            assertThat(imports.executePending(1) {}).isFalse()
            assertThat(imports.state.value).isEqualTo(ModelImportState.Idle)
        }

    @Test
    fun `blocked Android service start cannot delay synchronous lock detach`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val scope = CoroutineScope(SupervisorJob())
        val imports =
            ModelImportCoordinator(
                ImmutableModelStore(temp.root),
                copy = { _, _ -> error("No foreground execution yet") },
                startExecution = { _, _ ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                },
                scope = scope,
            )
        val owner = Any()
        imports.attach(owner) { error("No registration") }
        try {
            val starting = threads.submit<Boolean> { imports.startImport(uri) }
            check(entered.await(2, TimeUnit.SECONDS))
            val detached = threads.submit { imports.detachOwner(owner) }
            detached.get(1, TimeUnit.SECONDS) // Independently bounded while the Android call is still blocked.
            assertThat(release.count).isEqualTo(1)
            release.countDown()
            assertThat(starting.get(2, TimeUnit.SECONDS)).isTrue()
            imports.executionStopped(1)
        } finally {
            release.countDown()
            imports.detachOwner(owner)
            scope.cancel()
            threads.shutdownNow()
        }
    }

    @Test
    fun `service stop before coroutine entry releases admission and publishes completion`() =
        runTest {
            var copies = 0
            var completions = 0
            val imports =
                ModelImportCoordinator(
                    ImmutableModelStore(temp.root),
                    copy = { _, _ ->
                        copies++
                        copied()
                    },
                    startExecution = { _, _ -> },
                    scope = backgroundScope,
                )
            val owner = Any()
            imports.attach(owner) { error("Copy never entered") }
            imports.startImport(uri)
            imports.executePending(1) { completions++ }
            imports.executionStopped(1)
            runCurrent()
            assertThat(copies).isEqualTo(0)
            assertThat(completions).isEqualTo(1)
            assertThat(imports.state.value).isEqualTo(ModelImportState.Done(ModelImportOutcome.FAILED))
            assertThat(imports.startImport(uri)).isTrue()
            imports.executionStopped(2)
            imports.detachOwner(owner)
        }

    private fun copied(): ModelCopyResult.Copied =
        ModelCopyResult.Copied(
            ModelManifest(
                id = "fixture-model",
                manifestVersion = 2,
                name = "Private name",
                format = ModelFormat.GGUF,
                capabilities = emptySet(),
                license = ManifestLicense("UNKNOWN"),
                main = ManifestFile(ModelFileRole.MAIN, "model.gguf", "0".repeat(64), 1),
                companions = emptyList(),
            ),
            StoredModel("fixture-model", temp.root, emptyMap(), PermissionEnforcement.POSIX),
        )
}
