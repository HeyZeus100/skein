package app.skein.core.verify

import com.google.common.truth.Truth.assertThat
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelVerifierPhaseCancellationTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `cancellation after pre-verification prevents mapping`() {
        val file = temp.newFile().apply { writeBytes(BYTES) }
        var cancelled = false
        var didMap = false
        val hook =
            object : LoadPhaseHook {
                override fun afterPreMmapVerify(binding: VerifyBinding) {
                    cancelled = true
                }

                override fun afterMap(mapped: MappedByteBuffer) {
                    didMap = true
                }
            }

        val result =
            FileInputStream(file).use {
                ModelVerifier.verifyForLoad(it.channel, binding(), hook, VerifyCancellation { cancelled })
            }

        assertThat(result).isEqualTo(LoadVerification.Refused(ModelVerification.Cancelled(ModelFileRole.MAIN)))
        assertThat(didMap).isFalse()
    }

    @Test
    fun `cancellation on final mapped chunk still refuses the verified model`() {
        val file = temp.newFile().apply { writeBytes(BYTES) }
        var cancelled = false
        var passes = 0
        val result =
            FileInputStream(file).use {
                ModelVerifier.verifyForLoad(
                    it.channel,
                    binding(),
                    cancellation = VerifyCancellation { cancelled },
                    progress = VerifyProgress {
                        passes++
                        if (passes == 2) cancelled = true
                    },
                )
            }

        assertThat(passes).isEqualTo(2)
        assertThat(result).isEqualTo(LoadVerification.Refused(ModelVerification.Cancelled(ModelFileRole.MAIN)))
    }

    private fun binding(): VerifyBinding =
        VerifyBinding(
            listOf(
                VerifyFile(
                    role = ModelFileRole.MAIN,
                    expectedSha256 = MessageDigest.getInstance("SHA-256").digest(BYTES).joinToString("") { "%02x".format(it) },
                    expectedSizeBytes = BYTES.size.toLong(),
                ),
            ),
        )

    private companion object {
        val BYTES = ByteArray(4_096) { (it % 251).toByte() }
    }
}
