package app.skein.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build (spec §2.2) if the resolved dependency groups of a runtime
 * classpath include Google Play Services, Firebase, Google Play (Core), or
 * ML Kit — directly or transitively.
 *
 * [resolvedGroups] is computed by the wiring plugin from
 * `configuration.incoming.resolutionResult`, but is a plain [SetProperty] here
 * so the check logic itself can be unit-tested without resolving a real
 * dependency graph.
 */
abstract class DependencyGuardTask : DefaultTask() {

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    abstract val resolvedGroups: SetProperty<String>

    @TaskAction
    fun checkDependencies() {
        val configuration = configurationName.get()
        val violations = resolvedGroups.get()
            .filter { group -> BANNED_GROUPS.any { banned -> group == banned || group.startsWith("$banned.") } }
            .sorted()
            .map { group ->
                "GUARD VIOLATION: banned dependency group '$group' resolved on classpath " +
                    "'$configuration'. Spec §2.2 forbids Google Play Services / Firebase / ML Kit."
            }

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString("\n"))
        }
    }

    companion object {
        val BANNED_GROUPS = listOf(
            "com.google.android.gms",
            "com.google.firebase",
            "com.google.android.play",
            "com.google.mlkit",
        )
    }
}
