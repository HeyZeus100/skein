// skein-v2s (plan `E3.I5`, acceptance criterion "matches `sha256sum` for a
// 100 MB random fixture"): the chunked reader is checked against a digest
// computed the naive way.
//
// The rest of the verifier's suite runs on a few kilobytes, which cannot catch
// the bugs a chunked reader actually has: a buffer handed to
// `MessageDigest.update` without being flipped, a partial `read` treated as a
// full one, an off-by-one on the last short chunk, or an absolute channel read
// whose position drifts. All of those produce a wrong digest only once the
// input crosses several chunk boundaries and ends on a short one.
//
// 100 MB is the plan's number, and 100 * 1000 * 1000 is chosen over 100 MiB
// precisely because it is *not* a multiple of the 4 MiB chunk. The fixture is
// generated in the test rather than committed — a 100 MB blob in git would be
// absurd — and the expectation is computed by a second, independent
// `MessageDigest` pass with a different buffer size, so the two readers share
// no code. `sha256sum` itself is not invoked: shelling out would make the test
// depend on the host's coreutils.

package app.skein.core.inference.models

import com.google.common.truth.Truth.assertThat
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Random

/** 100 MB, per the acceptance criterion: 23 full 4 MiB chunks plus a short one. */
private const val FIXTURE_BYTES = 100L * 1000L * 1000L

/** Deliberately not a multiple of the verifier's chunk, so the independent pass reads differently. */
private const val INDEPENDENT_BUFFER_BYTES = 64 * 1024

private const val GENERATOR_BLOCK_BYTES = 1024 * 1024

class ModelVerifierLargeFixtureTest {
    @Test
    fun `the streaming channel digest matches an independent MessageDigest pass`() {
        val actual = FileInputStream(fixture).use { ModelVerifier.sha256Hex(it.channel) }

        assertThat(actual).isEqualTo(independentSha256(fixture))
    }

    @Test
    fun `the streaming stream digest matches an independent MessageDigest pass`() {
        val actual = FileInputStream(fixture).use { ModelVerifier.sha256Hex(it) }

        assertThat(actual).isEqualTo(independentSha256(fixture))
    }

    @Test
    fun `the channel and the stream reader agree with each other`() {
        val viaChannel = FileInputStream(fixture).use { ModelVerifier.sha256Hex(it.channel) }

        assertThat(viaChannel).isEqualTo(FileInputStream(fixture).use { ModelVerifier.sha256Hex(it) })
    }

    @Test
    fun `the fixture really is 100 MB`() {
        assertThat(fixture.length()).isEqualTo(FIXTURE_BYTES)
    }

    /** A second reader sharing no code with [ModelVerifier]: different buffer size, plain relative reads. */
    private fun independentSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(INDEPENDENT_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * A class rule, not a method rule: the blob is written once for the
         * whole class. Four tests over 100 MB already means ~400 MB of
         * reading; paying for four writes as well would be gratuitous.
         */
        @get:ClassRule
        @JvmStatic
        val temp = TemporaryFolder()

        /** Streamed 1 MiB at a time — the fixture is never held in memory. */
        private val fixture: File by lazy {
            val file = temp.newFile("fixture-100mb.bin")
            val block = ByteArray(GENERATOR_BLOCK_BYTES)
            val random = Random(20260921L)
            FileOutputStream(file).use { out ->
                var written = 0L
                while (written < FIXTURE_BYTES) {
                    random.nextBytes(block)
                    val n = minOf(block.size.toLong(), FIXTURE_BYTES - written).toInt()
                    out.write(block, 0, n)
                    written += n
                }
            }
            file
        }
    }
}
