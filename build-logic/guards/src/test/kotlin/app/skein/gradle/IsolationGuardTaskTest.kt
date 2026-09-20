package app.skein.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class IsolationGuardTaskTest {

    private fun newTask(): IsolationGuardTask {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("checkIsolationGuardsTest", IsolationGuardTask::class.java).get()
    }

    @Test
    fun `passes for an allowed project dependency`() {
        val task = newTask()
        task.modulePath.set(":inference-service")
        task.allowedProjectPaths.set(setOf(":core:ipc", ":core:model"))
        task.allowedExternalGroups.set(setOf("org.jetbrains.kotlin"))
        task.declaredProjectDependencies.set(setOf(":core:ipc", ":core:model"))
        task.declaredExternalDependencies.set(setOf("org.jetbrains.kotlin:kotlin-stdlib"))

        task.checkIsolation()
    }

    @Test
    fun `fails when inference-service depends on core vault`() {
        val task = newTask()
        task.modulePath.set(":inference-service")
        task.allowedProjectPaths.set(setOf(":core:ipc", ":core:model"))
        task.allowedExternalGroups.set(setOf("org.jetbrains.kotlin"))
        task.declaredProjectDependencies.set(setOf(":core:ipc", ":core:vault"))
        task.declaredExternalDependencies.set(emptySet())

        val error = runCatching { task.checkIsolation() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for a :core:vault dependency")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains(":core:vault"))
    }

    @Test
    fun `fails when inference-service depends on app`() {
        val task = newTask()
        task.modulePath.set(":inference-service")
        task.allowedProjectPaths.set(setOf(":core:ipc", ":core:model"))
        task.declaredProjectDependencies.set(setOf(":app"))

        val error = runCatching { task.checkIsolation() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for an :app dependency")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains(":app"))
    }

    @Test
    fun `fails when an external dependency is outside the allowlist`() {
        val task = newTask()
        task.modulePath.set(":embedder-service")
        task.allowedExternalGroups.set(setOf("org.jetbrains.kotlin", "com.microsoft.onnxruntime"))
        task.declaredExternalDependencies.set(setOf("androidx.core:core-ktx"))

        val error = runCatching { task.checkIsolation() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for androidx.core:core-ktx")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("androidx.core:core-ktx"))
    }

    @Test
    fun `passes for core model with no Android plugin`() {
        val task = newTask()
        task.modulePath.set(":core:model")
        task.forbidAndroidPlugin.set(true)
        task.hasAndroidPlugin.set(false)

        task.checkIsolation()
    }

    @Test
    fun `fails when core model applies an Android plugin`() {
        val task = newTask()
        task.modulePath.set(":core:model")
        task.forbidAndroidPlugin.set(true)
        task.hasAndroidPlugin.set(true)

        val error = runCatching { task.checkIsolation() }.exceptionOrNull()
            ?: throw AssertionError("expected a GradleException for an Android plugin on :core:model")
        assertTrue(error.message!!.contains("GUARD VIOLATION"))
        assertTrue(error.message!!.contains("pure Kotlin/JVM"))
    }
}
