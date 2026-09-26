package app.skein.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoTestDoublesInMainGuardTaskTest {

    private fun newTask(
        edges: Set<String> = emptySet(),
        sources: List<File> = emptyList(),
    ): NoTestDoublesInMainGuardTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("checkNoTestDoublesInMainTest", NoTestDoublesInMainGuardTask::class.java).get()
        task.testDoubleEdges.set(edges)
        task.sourceFiles.setFrom(sources)
        return task
    }

    private fun kotlinFile(contents: String): File {
        val dir = ProjectBuilder.builder().build().layout.buildDirectory.get().asFile.apply { mkdirs() }
        return File(dir, "Screen.kt").apply { writeText(contents.trimIndent()) }
    }

    private fun failureOf(task: NoTestDoublesInMainGuardTask): String =
        runCatching { task.checkNoTestDoublesInMain() }.exceptionOrNull()?.message
            ?: throw AssertionError("expected a GradleException")

    @Test
    fun `passes for test-only and debug-only edges`() {
        newTask(
            edges = setOf(
                "testImplementation -> :testing",
                "androidTestImplementation -> :testing",
                "debugImplementation -> :testing-fakes",
                "fossDebugImplementation -> :testing-fakes",
            ),
        ).checkNoTestDoublesInMain()
    }

    @Test
    fun `fails for a production edge onto testing-fakes`() {
        val message = failureOf(newTask(edges = setOf("implementation -> :testing-fakes")))
        assertTrue(message, message.contains("GUARD VIOLATION") && message.contains("implementation"))
    }

    @Test
    fun `fails for a release edge`() {
        val message = failureOf(newTask(edges = setOf("releaseImplementation -> :testing-fakes")))
        assertTrue(message, message.contains("releaseImplementation"))
    }

    @Test
    fun `testing stays test-only even in debug`() {
        val message = failureOf(newTask(edges = setOf("debugCompileOnly -> :testing")))
        assertTrue(message, message.contains(":testing"))
    }

    @Test
    fun `fails when src main imports a test double`() {
        val file = kotlinFile(
            """
            package app.skein.feature.chat

            import app.skein.testing.FakeInferenceEngine
            """,
        )
        val message = failureOf(newTask(sources = listOf(file)))
        assertTrue(message, message.contains("Screen.kt:3"))
    }

    @Test
    fun `ignores the package name in comments and strings`() {
        val file = kotlinFile(
            """
            // Previews use app.skein.testing fakes from src/debug.
            val note = "app.skein.testing"
            """,
        )
        newTask(sources = listOf(file)).checkNoTestDoublesInMain()
    }

    @Test
    fun `plugin collects declared edges and skips the test-double modules`() {
        val root = ProjectBuilder.builder().build()
        ProjectBuilder.builder().withName("testing-fakes").withParent(root).build()
        val feature = ProjectBuilder.builder().withName("feature").withParent(root).build()
        feature.pluginManager.apply("java-library")
        feature.dependencies.add("implementation", feature.dependencies.project(mapOf("path" to ":testing-fakes")))
        feature.pluginManager.apply(NoTestDoublesInMainGuardPlugin::class.java)
        val testing = ProjectBuilder.builder().withName("testing").withParent(root).build()
        testing.pluginManager.apply(NoTestDoublesInMainGuardPlugin::class.java)

        val task = feature.tasks.getByName("checkNoTestDoublesInMain") as NoTestDoublesInMainGuardTask
        assertEquals(setOf("implementation -> :testing-fakes"), task.testDoubleEdges.get())
        assertTrue(runCatching { task.checkNoTestDoublesInMain() }.exceptionOrNull() is GradleException)
        assertEquals(null, testing.tasks.findByName("checkNoTestDoublesInMain"))
    }
}
