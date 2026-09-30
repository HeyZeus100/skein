package app.skein.embedder.service

import android.os.ParcelFileDescriptor
import app.skein.ipc.EmbedRequest
import app.skein.ipc.EmbedderLoadRequest
import app.skein.ipc.EmbedderTokenCountRequest
import app.skein.ipc.EntitySpanParcel
import app.skein.ipc.ErrorCode
import app.skein.ipc.ErrorCodes
import app.skein.ipc.ManifestBinding
import app.skein.ipc.ManifestFileRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbedderEndpointTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun coldLoadClosesDescriptorsWithoutAuthorizingOrOpeningBackend() {
        val creates = AtomicInteger()
        EmbedderEndpoint(
            EmbedderBackendFactory {
                _,
                _,
                _,
                _,
                ->
                creates.incrementAndGet()
                TestBackend()
            },
            {},
        ).use { endpoint ->
            val request = request()
            assertEquals(ErrorCode.SESSION_LOCKED, endpoint.load(request))
            assertEquals(0, creates.get())
            assertFalse(
                request.embedBinding.files
                    .single()
                    .fd.fileDescriptor
                    .valid(),
            )
            assertCode(ErrorCode.SESSION_LOCKED) {
                endpoint.embed(EmbedRequest(listOf("synthetic"), sessionEpoch = 7, requestId = 2))
            }
        }
    }

    @Test
    fun unavailableProductionFactoryNeverReportsSyntheticSuccess() {
        EmbedderEndpoint(null, {}).use { endpoint ->
            endpoint.onSessionUnlocked(7)
            assertEquals(ErrorCode.NOT_LOADED, endpoint.load(request()))
            assertCode(
                ErrorCode.NOT_LOADED,
            ) { endpoint.embed(EmbedRequest(listOf("synthetic"), sessionEpoch = 7, requestId = 2)) }
        }
    }

    @Test
    fun epochlessAndStaleUnloadCannotCloseNewSessionBackend() {
        val backend = TestBackend()
        EmbedderEndpoint(EmbedderBackendFactory { _, _, _, _ -> backend }, {}).use { endpoint ->
            endpoint.onSessionUnlocked(7)
            assertEquals(ErrorCode.OK, endpoint.load(request()))
            endpoint.unload()
            endpoint.unloadForSession(6)
            endpoint.onSessionLocked(6)
            assertEquals(0, backend.closes.get())
            assertEquals(3, endpoint.tokenCountForSession(EmbedderTokenCountRequest("synthetic", 7, 2)))
            endpoint.unloadForSession(7)
            assertEquals(1, backend.closes.get())
            assertCode(
                ErrorCode.NOT_LOADED,
            ) { endpoint.tokenCountForSession(EmbedderTokenCountRequest("synthetic", 7, 3)) }
        }
        assertEquals(1, backend.closes.get())
    }

    @Test
    fun revokedEpochCannotBeReauthorizedByDelayedUnlock() {
        val backend = TestBackend()
        EmbedderEndpoint(EmbedderBackendFactory { _, _, _, _ -> backend }, {}).use { endpoint ->
            endpoint.onSessionUnlocked(7)
            assertEquals(ErrorCode.OK, endpoint.load(request()))
            endpoint.onSessionLocking(7, 500)
            endpoint.onSessionLocked(7)
            endpoint.onSessionUnlocked(7)
            assertCode(ErrorCode.SESSION_LOCKED) {
                endpoint.embed(EmbedRequest(listOf("synthetic"), sessionEpoch = 7, requestId = 2))
            }
            assertEquals(1, backend.closes.get())
        }
    }

    @Test
    fun rejectsLegacyIdsOversizedInlineAndMoreThan32TextsBeforeBackend() {
        val backend = TestBackend()
        EmbedderEndpoint(EmbedderBackendFactory { _, _, _, _ -> backend }, {}).use { endpoint ->
            endpoint.onSessionUnlocked(7)
            assertEquals(ErrorCode.OK, endpoint.load(request()))
            assertCode(ErrorCode.TX_TOO_LARGE) { endpoint.embed(EmbedRequest(listOf("synthetic"), sessionEpoch = 7)) }
            assertCode(ErrorCode.TX_TOO_LARGE) {
                endpoint.embed(EmbedRequest(List(33) { "synthetic" }, sessionEpoch = 7, requestId = 2))
            }
            assertCode(ErrorCode.TX_TOO_LARGE) {
                endpoint.embed(EmbedRequest(listOf("x".repeat(40_000)), sessionEpoch = 7, requestId = 3))
            }
            assertEquals(0, backend.embeds.get())
            assertCode(ErrorCode.SESSION_LOCKED) { endpoint.tokenCount("synthetic") }
        }
    }

    @Test
    fun actualBindingAndRequestFlagsReachTestOnlyBackend() {
        val backend = TestBackend()
        EmbedderEndpoint(
            EmbedderBackendFactory { models, format, threads, _ ->
                assertTrue(models.embedding.isNotEmpty())
                assertEquals("onnx", format)
                assertEquals(1, threads)
                backend
            },
            {},
        ).use { endpoint ->
            endpoint.onSessionUnlocked(7)
            assertEquals(ErrorCode.OK, endpoint.load(request()))
            val result =
                endpoint.embed(
                    EmbedRequest(listOf("synthetic", "other"), isQuery = true, sessionEpoch = 7, requestId = 2),
                )
            assertEquals(512, result.flat.size)
            assertEquals(0, result.droppedInputs)
            assertTrue(backend.lastQuery)
        }
    }

    @Test
    fun backendExceptionTextDoesNotCrossBinderBoundary() {
        val backend =
            object : TestBackend() {
                override fun tokenCount(
                    text: String,
                    cancellation: EmbedderCancellation,
                ): Int = error("PRIVATE $text")
            }
        EmbedderEndpoint(EmbedderBackendFactory { _, _, _, _ -> backend }, {}).use { endpoint ->
            endpoint.onSessionUnlocked(7)
            assertEquals(ErrorCode.OK, endpoint.load(request()))
            val error =
                assertThrows(IllegalStateException::class.java) {
                    endpoint.tokenCountForSession(EmbedderTokenCountRequest("secret", 7, 2))
                }
            assertEquals(ErrorCode.INTERNAL, ErrorCodes.codeOf(error))
            assertFalse(error.message.orEmpty().contains("secret"))
            assertFalse(error.message.orEmpty().contains("PRIVATE"))
        }
    }

    @Test
    fun hardLockUsesBackstopInsteadOfFreeingBackendWhileNativeCallStillRuns() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val hardStops = AtomicInteger()
        val backend =
            object : TestBackend() {
                override fun embed(
                    texts: List<String>,
                    isQuery: Boolean,
                    cancellation: EmbedderCancellation,
                ): ByteArray {
                    entered.countDown()
                    while (true) {
                        try {
                            check(finish.await(5, TimeUnit.SECONDS))
                            break
                        } catch (_: InterruptedException) {
                            // Deliberately uncooperative; the production hook kills only :embedder.
                        }
                    }
                    return ByteArray(256) { 1 }
                }

                override fun close() {
                    super.close()
                    exited.countDown()
                }
            }
        EmbedderEndpoint(
            EmbedderBackendFactory {
                _,
                _,
                _,
                _,
                ->
                backend
            },
            { hardStops.incrementAndGet() },
        ).use { endpoint ->
            endpoint.onSessionUnlocked(7)
            assertEquals(ErrorCode.OK, endpoint.load(request()))
            val caller =
                CompletableFuture.supplyAsync {
                    runCatching {
                        endpoint.embed(
                            EmbedRequest(listOf("synthetic"), sessionEpoch = 7, requestId = 2),
                        )
                    }.exceptionOrNull()
                }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                endpoint.onSessionLocking(7, 500)
                assertEquals(ErrorCode.SESSION_LOCKED, ErrorCodes.codeOf(caller.get(2, TimeUnit.SECONDS)!!))
                assertEquals(0, backend.closes.get())
                endpoint.onSessionLocked(7)
                assertEquals(1, hardStops.get())
                assertCode(ErrorCode.BUSY) { endpoint.onSessionUnlocked(8) }
                assertEquals(0, backend.closes.get())
            } finally {
                finish.countDown()
            }
            assertTrue(exited.await(2, TimeUnit.SECONDS))
        }
    }

    private fun assertCode(
        code: Int,
        action: () -> Unit,
    ) {
        assertEquals(code, ErrorCodes.codeOf(assertThrows(IllegalStateException::class.java, action)))
    }

    private fun request(): EmbedderLoadRequest {
        val bytes = "synthetic verified bytes, not model acceptance".toByteArray()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ref =
            ManifestFileRef(
                "main",
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                digest,
                bytes.size.toLong(),
            )
        return EmbedderLoadRequest(ManifestBinding("synthetic", 2, listOf(ref), null), "onnx", null, null, 1, 7, 1)
    }

    private open class TestBackend : EmbedderBackend {
        val closes = AtomicInteger()
        val embeds = AtomicInteger()
        var lastQuery = false

        override fun embed(
            texts: List<String>,
            isQuery: Boolean,
            cancellation: EmbedderCancellation,
        ): ByteArray {
            embeds.incrementAndGet()
            lastQuery = isQuery
            return ByteArray(texts.size * 256) { 1 }
        }

        override fun extractEntities(
            text: String,
            labels: List<String>,
            cancellation: EmbedderCancellation,
        ): List<EntitySpanParcel> = emptyList()

        override fun rerank(
            query: String,
            candidates: List<String>,
            cancellation: EmbedderCancellation,
        ): FloatArray = FloatArray(candidates.size)

        override fun tokenCount(
            text: String,
            cancellation: EmbedderCancellation,
        ): Int = 3

        override fun close() {
            closes.incrementAndGet()
        }
    }
}
