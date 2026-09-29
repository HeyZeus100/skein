package app.skein.core.vault.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

public class SkeinSQLiteTextBindingTest {
    @Test
    public fun `text binding forwards exact utf8 and clears the temporary buffer on success and failure`() {
        for (fail in listOf(false, true)) {
            var retained: ByteArray? = null
            var copied: ByteArray? = null
            val native =
                object : SkeinSQLiteNative by FakeSkeinSQLiteNative() {
                    override fun nativeBindText(
                        stmtHandle: Long,
                        index: Int,
                        value: ByteArray,
                    ) {
                        retained = value
                        copied = value.copyOf()
                        if (fail) throw IllegalStateException("test binding failure")
                    }
                }
            val text = "\uFEFF研究𐐀\u0000終"
            val result = runCatching { SkeinSQLiteStatement(native, 100).bindText(1, text) }
            assertThat(result.isFailure).isEqualTo(fail)
            assertThat(copied).isEqualTo(text.toByteArray(Charsets.UTF_8))
            assertThat(retained!!.all { it == 0.toByte() }).isTrue()
        }
    }
}
