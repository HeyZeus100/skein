package app.skein.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyGuardTaskTest {

    private fun taskWithGroups(groups: Set<String>): DependencyGuardTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("checkDependencyGuardsTest", DependencyGuardTask::class.java).get()
        task.configurationName.set("fossDebugRuntimeClasspath")
        task.resolvedGroups.set(groups)
        return task
    }

    @Test
    fun `passes when no banned group is resolved`() {
        taskWithGroups(setOf("org.jetbrains.kotlin", "androidx.core", "org.jetbrains.kotlinx")).checkDependencies()
    }

    @Test
    fun `fails when com google android gms is resolved`() {
        val task = taskWithGroups(setOf("androidx.core", "com.google.android.gms"))

        val error = runCatching { task.checkDependencies() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for com.google.android.gms")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("com.google.android.gms"))
    }

    @Test
    fun `fails when a gms subgroup is resolved`() {
        // e.g. com.google.android.gms.play-services-basement resolves with group
        // "com.google.android.gms" exactly, but a play-services split module can
        // publish under a dotted sub-group too; both must be caught.
        val task = taskWithGroups(setOf("com.google.android.gms.internal"))

        val error = runCatching { task.checkDependencies() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for a GMS sub-group")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
    }

    @Test
    fun `fails when com google firebase is resolved`() {
        val task = taskWithGroups(setOf("com.google.firebase"))

        val error = runCatching { task.checkDependencies() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for com.google.firebase")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
    }

    @Test
    fun `fails when com google mlkit is resolved`() {
        val task = taskWithGroups(setOf("com.google.mlkit"))

        val error = runCatching { task.checkDependencies() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for com.google.mlkit")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
    }

    @Test
    fun `does not false-positive on an unrelated google group`() {
        // com.google.code.gson, for example, must not be caught by a naive
        // 'contains "google"' check.
        taskWithGroups(setOf("com.google.code.gson")).checkDependencies()
    }
}
