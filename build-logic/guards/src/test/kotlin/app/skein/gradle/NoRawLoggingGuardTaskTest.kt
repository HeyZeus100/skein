package app.skein.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class NoRawLoggingGuardTaskTest {

    private lateinit var sourceDir: File

    @Before
    fun setUp() {
        sourceDir = ProjectBuilder.builder().build().layout.buildDirectory.get().asFile
        sourceDir.mkdirs()
    }

    private fun kotlinFile(
        name: String,
        contents: String,
    ): File {
        val file = File(sourceDir, name)
        file.writeText(contents.trimIndent())
        return file
    }

    private fun taskFor(
        files: List<File>,
        allowlist: Set<String> = setOf("SkeinLog.kt"),
    ): NoRawLoggingGuardTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("checkNoRawLoggingTest", NoRawLoggingGuardTask::class.java).get()
        task.sourceFiles.setFrom(files)
        task.allowlistedFileNames.set(allowlist)
        return task
    }

    // ---- passes ------------------------------------------------------------

    @Test
    fun `passes when SkeinLog is used instead of raw logging`() {
        val file = kotlinFile(
            "Vault.kt",
            """
            package app.skein.core.vault

            import app.skein.core.model.SkeinLog

            fun open() {
                SkeinLog.d("Vault", "opened")
            }
            """,
        )
        val task = taskFor(listOf(file))

        task.checkNoRawLogging()
    }

    @Test
    fun `passes on the allowlisted SkeinLog file itself`() {
        val file = kotlinFile(
            "SkeinLog.kt",
            """
            package app.skein.core.model

            object SkeinLog {
                fun d(tag: String, message: String) = android.util.Log.d(tag, message)
            }
            """,
        )
        val task = taskFor(listOf(file))

        task.checkNoRawLogging()
    }

    @Test
    fun `passes when Log or println only appears inside a string literal`() {
        val file = kotlinFile(
            "ImportTextTest.kt",
            """
            package app.skein.core.vault.transfer

            fun fixture(): String = "fun main() {\n    println(\"hi\")\n}\n"
            """,
        )
        val task = taskFor(listOf(file))

        task.checkNoRawLogging()
    }

    @Test
    fun `passes when Log or println only appears inside a comment`() {
        val file = kotlinFile(
            "PdfExportService.kt",
            """
            package app.skein.core.export.pdf

            /**
             * `PrintManager.print("Skein", adapter, attributes)`; also see
             * android.util.Log.d for how the platform itself logs.
             */
            class PdfExportService
            """,
        )
        val task = taskFor(listOf(file))

        task.checkNoRawLogging()
    }

    @Test
    fun `passes on a call whose receiver only ends in Log, not is Log`() {
        // The word-boundary in the guard's regex must not fire on
        // "AuditLog.d(" — only a receiver that IS exactly "Log" (or
        // "android.util.Log") counts. This is fixture text scanned as raw
        // source, not compiled, so `AuditLog.d(...)` needing a companion
        // object is beside the point.
        val file = kotlinFile(
            "AuditLog.kt",
            """
            package app.skein.core.vault

            fun use() {
                AuditLog.d("tag", "message")
            }
            """,
        )
        val task = taskFor(listOf(file))

        task.checkNoRawLogging()
    }

    @Test
    fun `passes for a file allowlisted per-module, such as the Android sink`() {
        val file = kotlinFile(
            "AndroidSkeinLogSink.kt",
            """
            package app.skein.system

            import android.util.Log

            object AndroidSkeinLogSink {
                fun log(tag: String, message: String) = Log.d(tag, message)
            }
            """,
        )
        val task = taskFor(listOf(file), allowlist = setOf("SkeinLog.kt", "AndroidSkeinLogSink.kt"))

        task.checkNoRawLogging()
    }

    // ---- fails ---------------------------------------------------------------

    @Test
    fun `fails on a raw Log dot d call`() {
        val file = kotlinFile(
            "Careless.kt",
            """
            package app.skein.feature.shell

            import android.util.Log

            fun open() {
                Log.d("Shell", "opened")
            }
            """,
        )
        val task = taskFor(listOf(file))

        val error = runCatching { task.checkNoRawLogging() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for a raw Log.d call")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("Careless.kt:6"))
    }

    @Test
    fun `fails on a fully-qualified android util Log i call`() {
        val file = kotlinFile(
            "Careless2.kt",
            """
            package app.skein.feature.shell

            fun open() {
                android.util.Log.i("Shell", "opened")
            }
            """,
        )
        val task = taskFor(listOf(file))

        val error = runCatching { task.checkNoRawLogging() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for a raw Log.i call")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
    }

    @Test
    fun `fails on a raw println call`() {
        val file = kotlinFile(
            "Careless3.kt",
            """
            package app.skein.feature.shell

            fun debug() {
                println("hello")
            }
            """,
        )
        val task = taskFor(listOf(file))

        val error = runCatching { task.checkNoRawLogging() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for a raw println call")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("println"))
    }

    @Test
    fun `fails on the sink file when it is not allowlisted for this module`() {
        val file = kotlinFile(
            "AndroidSkeinLogSink.kt",
            """
            package app.skein.core.model

            import android.util.Log

            object AndroidSkeinLogSink {
                fun log(tag: String, message: String) = Log.d(tag, message)
            }
            """,
        )
        val task = taskFor(listOf(file), allowlist = setOf("SkeinLog.kt"))

        val error = runCatching { task.checkNoRawLogging() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException — this module doesn't allowlist the sink")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
    }

    @Test
    fun `reports one violation per offending line, not per file`() {
        val file = kotlinFile(
            "Careless4.kt",
            """
            package app.skein.feature.shell

            import android.util.Log

            fun a() {
                Log.d("A", "one")
                Log.e("A", "two")
            }
            """,
        )
        val task = taskFor(listOf(file))

        val error = runCatching { task.checkNoRawLogging() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException")
        assertEquals(2, error.message!!.lines().size)
    }
}
