package app.skein.core.vault.blob

import app.skein.testing.TempDirRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real SKAT files; no database or Android security claim comes from these host tests. */
class AttachmentOrphanSweepTest {
    @get:Rule
    val temp = TempDirRule()

    @Test
    fun `sweep removes only unreferenced UUID blobs and old recognized temporary files`() =
        runTest {
            val dir = temp.newDir("attachments")
            val store = store(dir)
            store.write(LIVE) { it.write(byteArrayOf(1, 2, 3)) }
            store.write(ORPHAN) { it.write(byteArrayOf(4, 5, 6)) }
            val old =
                File(dir, ".$LIVE.100.tmp").apply {
                    writeText("synthetic temp")
                    setLastModified(1)
                }
            val recent =
                File(dir, ".$ORPHAN.101.tmp").apply {
                    writeText("synthetic temp")
                    setLastModified(NOW)
                }
            val unknown = File(dir, "archive-supplied-id").apply { writeText("synthetic retained") }
            val directory = File(dir, OTHER).apply { mkdir() }
            val outside = temp.newFile("outside").apply { writeText("synthetic outside") }
            val symlink = File(dir, LINK)
            Files.createSymbolicLink(symlink.toPath(), outside.toPath())

            val result = store.sweepOrphans(setOf(LIVE), NOW)

            assertThat(result.removedBlobs).isEqualTo(1)
            assertThat(result.removedTemporaryFiles).isEqualTo(1)
            assertThat(result.failedFiles).isEqualTo(0)
            assertThat(store.open(LIVE).use { it.readBytes() }).isEqualTo(byteArrayOf(1, 2, 3))
            assertThat(File(dir, ORPHAN).exists()).isFalse()
            assertThat(old.exists()).isFalse()
            assertThat(listOf(recent, unknown, directory, symlink, outside).all { it.exists() }).isTrue()
            assertThat(store.sweepOrphans(setOf(LIVE), NOW).removedBlobs).isEqualTo(0)
        }

    @Test
    fun `a new store instance cannot sweep a blob awaiting metadata commit`() =
        runTest {
            val dir = temp.newDir("attachments")
            val writer = store(dir)
            val nextSession = store(dir)
            val landed = CompletableDeferred<Unit>()
            val commit = CompletableDeferred<Unit>()
            val job =
                launch {
                    writer.withWriteReservation(LIVE) {
                        writer.write(LIVE) { it.write(byteArrayOf(7)) }
                        landed.complete(Unit)
                        commit.await()
                    }
                }
            landed.await()
            assertThat(nextSession.sweepOrphans(emptySet(), NOW).retainedActiveFiles).isEqualTo(1)
            assertThat(nextSession.open(LIVE).use { it.read() }).isEqualTo(7)
            commit.complete(Unit)
            job.join()
            assertThat(nextSession.sweepOrphans(setOf(LIVE), NOW).removedBlobs).isEqualTo(0)
            assertThat(nextSession.sweepOrphans(emptySet(), NOW).removedBlobs).isEqualTo(1)
        }

    @Test
    fun `active old temporary write is retained and cancellation releases its reservation`() =
        runTest {
            val dir = temp.newDir("attachments")
            val writer = store(dir)
            val started = CompletableDeferred<Unit>()
            val job =
                launch {
                    writer.write(LIVE) {
                        it.write(byteArrayOf(8))
                        dir.listFiles()!!.single().setLastModified(1)
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            started.await()
            assertThat(store(dir).sweepOrphans(emptySet(), NOW).retainedActiveFiles).isEqualTo(1)
            job.cancelAndJoin()
            assertThat(dir.listFiles()).isEmpty()
            writer.write(LIVE) { it.write(byteArrayOf(9)) }
            assertThat(store(dir).sweepOrphans(emptySet(), NOW).removedBlobs).isEqualTo(1)
        }

    @Test
    fun `failed explicit unlink throws instead of silently claiming success`() =
        runTest {
            val dir = temp.newDir("attachments")
            File(dir, LIVE).mkdir()
            File(File(dir, LIVE), "synthetic-child").writeText("retained")
            val failure = runCatching { store(dir).delete(LIVE) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        }

    private fun store(dir: File) = FileAttachmentStore(dir) { ByteArray(32) { 4 } }

    private companion object {
        const val LIVE = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b"
        const val ORPHAN = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5c"
        const val OTHER = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5d"
        const val LINK = "018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5e"
        const val NOW = 2_000_000L
    }
}
