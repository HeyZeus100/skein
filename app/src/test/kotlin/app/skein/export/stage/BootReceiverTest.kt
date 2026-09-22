// skein-0m1z — POST_REVIEW_RESOLUTIONS.md §4.4's `BootReceiverTest`:
// "a receiver invoked with LOCKED_BOOT_COMPLETED and then BOOT_COMPLETED
// triggers exactly one sweep pass".
//
// The receiver is driven directly rather than through the system, which is
// also the only way to exercise the LOCKED_BOOT_COMPLETED branch at all:
// with directBootAware="false" (§4.3) the platform never delivers that action
// to this receiver on a real device. See BootReceiver.kt's header.

package app.skein.export.stage

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val stagingDir: File
        get() = File(context.cacheDir, "staging_export").apply { mkdirs() }

    @Before
    fun resetProcessState() {
        // Each test stands in for a fresh boot in a fresh process.
        BootReceiver.resetForTest()
        stagingDir.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun stage(name: String): File = File(stagingDir, name).apply { writeText("PLAINTEXT") }

    private fun broadcast(action: String) {
        BootReceiver().onReceive(context, Intent(action))
    }

    @Test
    fun `BOOT_COMPLETED deletes staged plaintext left behind by a crash`() {
        val staged = stage("stage-1-note.pdf")

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertThat(staged.exists()).isFalse()
    }

    @Test
    fun `LOCKED_BOOT_COMPLETED sweeps as well, for a direct-boot-aware future`() {
        val staged = stage("stage-1-note.pdf")

        broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED)

        assertThat(staged.exists()).isFalse()
    }

    @Test
    fun `LOCKED_BOOT_COMPLETED then BOOT_COMPLETED triggers exactly one sweep pass`() {
        // §4.4's requirement, observed through its only visible effect: a
        // file created AFTER the first broadcast survives the second, which
        // it could not if the second broadcast ran a sweep of its own.
        stage("stage-1-note.pdf")
        broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED)

        val afterFirstPass = stage("stage-2-note.pdf")
        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertThat(afterFirstPass.exists()).isTrue()
    }

    @Test
    fun `the first of the two broadcasts is the one that sweeps`() {
        val staged = stage("stage-1-note.pdf")

        broadcast(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertThat(staged.exists()).isFalse()
    }

    @Test
    fun `an unrelated broadcast never sweeps`() {
        val staged = stage("stage-1-note.pdf")

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertThat(staged.exists()).isTrue()
    }

    @Test
    fun `an unrelated broadcast does not consume the one-shot sweep`() {
        val staged = stage("stage-1-note.pdf")
        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertThat(staged.exists()).isFalse()
    }

    @Test
    fun `a boot with nothing staged is harmless`() {
        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertThat(stagingDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `the sweep never touches files outside the staging directory`() {
        val unrelated = File(context.cacheDir, "not-staged.txt").apply { writeText("keep me") }
        stage("stage-1-note.pdf")

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertThat(unrelated.exists()).isTrue()
    }
}
