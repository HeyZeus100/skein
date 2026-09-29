package app.skein.inference.service

import android.os.ParcelFileDescriptor
import app.skein.ipc.ChatMessageParcel
import app.skein.ipc.ErrorCode
import app.skein.ipc.GenerateRequest
import app.skein.ipc.LoadRequest
import app.skein.ipc.ManifestBinding
import app.skein.ipc.ManifestFileRef
import app.skein.ipc.SamplingParcel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class InferenceWorkerLifecycleTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `decode OOM on the real worker securely cleans up and permits the next load`() {
        val decodeOwner = AtomicReference<Thread>()
        val cleanupOwner = AtomicReference<Thread>()
        val backend =
            object : FakeLlamaBackend() {
                override fun decodePrompt(
                    ctx: Long,
                    tokens: IntArray,
                    nPast: Int,
                ): Int {
                    decodeOwner.set(Thread.currentThread())
                    throw LlamaException(LlamaErrorCode.OUT_OF_MEMORY, "decode")
                }

                override fun freeContextSecure(ctx: Long) {
                    cleanupOwner.set(Thread.currentThread())
                    super.freeContextSecure(ctx)
                }
            }
        // Thread descendants inherit daemon status. A regression must produce
        // a bounded failure, not leave a self-deadlocked non-daemon worker
        // preventing the JVM test process from exiting.
        val worker = boundedCall { InferenceWorker("skein-oom-regression") }
        val engine = InferenceEngineState(backend, worker, CallbackDispatcher(InlineTaskRunner()))
        val model = temp.newFile().apply { writeBytes(ByteArray(4_096) { (it % 251).toByte() }) }
        try {
            engine.onSessionUnlocked(EPOCH)
            assertThat(boundedCall { engine.load(loadRequest(model)) }).isEqualTo(ErrorCode.OK)
            val callback = RecordingCallback()
            val sentinel = CountDownLatch(1)

            engine.generate(generateRequest(), callback)
            worker.post { sentinel.countDown() }

            assertThat(sentinel.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(callback.errors.single().second).isEqualTo(ErrorCode.OOM)
            assertThat(engine.status().state).isEqualTo("unloaded")
            assertThat(backend.secureFrees).hasSize(1)
            assertThat(backend.liveContexts).isEmpty()
            assertThat(backend.liveModels).isEmpty()
            assertThat(backend.liveSamplers).isEmpty()
            assertThat(cleanupOwner.get()).isSameInstanceAs(decodeOwner.get())
            assertThat(decodeOwner.get()).isNotSameInstanceAs(Thread.currentThread())
            assertThat(boundedCall { engine.load(loadRequest(model)) }).isEqualTo(ErrorCode.OK)
            assertThat(engine.status().state).isEqualTo("ready")
        } finally {
            boundedCall {
                engine.unload()
                worker.shutdown()
            }
        }
    }

    private fun loadRequest(model: File): LoadRequest =
        LoadRequest(
            binding =
                ManifestBinding(
                    manifestId = "worker-oom",
                    manifestVersion = 2,
                    files =
                        listOf(
                            ManifestFileRef(
                                role = "main",
                                fd = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY),
                                expectedSha256 =
                                    MessageDigest
                                        .getInstance("SHA-256")
                                        .digest(model.readBytes())
                                        .joinToString("") { "%02x".format(it) },
                                expectedSizeBytes = model.length(),
                            ),
                        ),
                    attestation = null,
                ),
            contextLength = 2_048,
            threads = 2,
            gpuLayers = 0,
            embeddingMode = false,
            sessionEpoch = EPOCH,
        )

    private fun generateRequest(): GenerateRequest =
        GenerateRequest(
            requestId = 1,
            messages = listOf(ChatMessageParcel(role = "user", content = "hello")),
            attachmentFds = emptyList(),
            sampling =
                SamplingParcel(
                    temperature = 0.7f,
                    topK = 40,
                    topP = 0.95f,
                    minP = 0.05f,
                    repeatPenalty = 1.1f,
                    maxTokens = 4,
                    seed = 1L,
                    stop = emptyList(),
                ),
            sessionEpoch = EPOCH,
        )

    private fun <T> boundedCall(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        Thread(task).apply {
            isDaemon = true
            start()
        }
        return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val EPOCH = 42L
        const val TIMEOUT_SECONDS = 5L
    }
}
