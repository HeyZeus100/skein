package app.skein.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class TempDirRuleTest {
    @get:Rule
    val tempDir = TempDirRule()

    @Test
    fun `root exists and is empty at the start of a test`() {
        assertThat(tempDir.root.exists()).isTrue()
        assertThat(tempDir.root.listFiles()).isEmpty()
    }

    @Test
    fun `newFile writes content under root`() {
        val file = tempDir.newFile("note.md", "hello")

        assertThat(file.readText()).isEqualTo("hello")
        assertThat(file.parentFile).isEqualTo(tempDir.root)
    }

    @Test
    fun `newDir creates a subdirectory under root`() {
        val dir = tempDir.newDir("attachments")

        assertThat(dir.exists()).isTrue()
        assertThat(dir.isDirectory).isTrue()
    }
}
