package app.skein.feature.shell.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Acceptance criterion (`E6.I1`): no `Modifier.shadow`, no
 * `Brush.linearGradient` anywhere under `feature` modules (spec §8.1: no
 * gradients, no drop shadows).
 *
 * The plan names a custom Android Lint detector
 * (`build-logic/lint/.../NoShadowGradientDetector.kt`) for this. That
 * detector needs its own `build-logic` composite build and a `lint-checks`
 * dependency wired into every feature module — infrastructure `E6.I1` does
 * not otherwise touch and no other issue has stood up yet. Until that lands,
 * this source-scan test enforces the same rule, offline and in every
 * `./gradlew :feature:shell:test` run. See `bd note skein-iru` for the
 * tracking note left for whichever issue builds the real Lint module.
 */
class NoShadowOrGradientTest {
    private val forbidden =
        listOf(
            Regex("""Modifier\s*\.\s*shadow\s*\("""),
            Regex("""\.shadow\s*\("""),
            Regex("""Brush\s*\.\s*linearGradient"""),
            Regex("""linearGradient\s*\("""),
        )

    @Test
    fun `no feature module uses Modifier-shadow or Brush-linearGradient`() {
        val featureRoot = findFeatureRoot()
        val offenders =
            featureRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { !it.path.contains("${File.separator}build${File.separator}") }
                .filter { it.name != "NoShadowOrGradientTest.kt" }
                .flatMap { file ->
                    val text = file.readText()
                    forbidden.filter { it.containsMatchIn(text) }.map { file.path to it.pattern }
                }.toList()

        assertTrue(
            "forbidden shadow/gradient usage found (spec §8.1: no gradients, no drop shadows):\n" +
                offenders.joinToString("\n") { (path, pattern) -> "  $path matched $pattern" },
            offenders.isEmpty(),
        )
    }

    /** Walks up from the test's working directory to the repo's `feature/` directory. */
    private fun findFeatureRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "feature")
            if (candidate.isDirectory && File(dir, "settings.gradle.kts").isFile) {
                return candidate
            }
            dir = dir.parentFile
        }
        error("could not locate repo root (no settings.gradle.kts found)")
    }
}
