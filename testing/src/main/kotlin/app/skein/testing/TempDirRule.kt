package app.skein.testing

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Files

/**
 * Creates a fresh, empty temp directory before each test and deletes it
 * (recursively) after, regardless of test outcome. Use this instead of
 * `java.io.File.createTempFile` directly so vault/import/export tests never
 * leak fixture files onto the real filesystem between runs.
 *
 * ```kotlin
 * @get:Rule val tempDir = TempDirRule()
 * // tempDir.root: File
 * ```
 */
class TempDirRule : TestRule {
    lateinit var root: File
        private set

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                root = Files.createTempDirectory("skein-test-${description.methodName}-").toFile()
                try {
                    base.evaluate()
                } finally {
                    root.deleteRecursively()
                }
            }
        }

    /** Creates (and returns) a new file named [name] under [root] with [contents]. */
    fun newFile(
        name: String,
        contents: String = "",
    ): File =
        File(root, name).apply {
            parentFile?.mkdirs()
            writeText(contents)
        }

    /** Creates (and returns) a new subdirectory named [name] under [root]. */
    fun newDir(name: String): File = File(root, name).apply { mkdirs() }
}
