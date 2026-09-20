package app.skein.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Acceptance criterion (`skein-7uwy`, following on from `skein-qiu`'s
 * `SecureTextField`): threat model §9 requires `SecureTextField`
 * (`feature/shell/src/main/kotlin/app/skein/feature/shell/input/
 * SecureTextField.kt`) to be the ONLY text-input Composable used for
 * vault-sensitive content (chat prompt, editor, search, persona system
 * prompts, model import passphrases) — its `autoCorrect`-off +
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` + `TYPE_TEXT_FLAG_NO_SUGGESTIONS`
 * hardening only helps if nothing bypasses it by calling Compose Material
 * 3's raw `TextField`/`OutlinedTextField` (or Compose Foundation's
 * `BasicTextField`, which those wrap) directly.
 *
 * The plan named a custom Android Lint detector
 * (`RawTextFieldDetector`, `build-logic/lint/...`) for this. Following the
 * precedent set by `NoShadowOrGradientTest`
 * (`feature/shell/src/test/kotlin/app/skein/feature/shell/theme/
 * NoShadowOrGradientTest.kt`) for `skein-iru` — a full Lint `Detector`
 * needs its own `build-logic/lint` module and a `lint-checks` dependency
 * wired into every module, infrastructure no issue has stood up yet — this
 * source-scan test enforces the same rule offline, in the same JVM lane,
 * with no new build-logic scaffolding. It lives centrally in `:testing`
 * (rather than duplicated into every `:feature:*` module's own
 * `src/test/`) so [modulesToScan] is the one place that grows as new
 * `:feature:*`/`:app` modules land.
 *
 * If a real `RawTextFieldDetector` Lint module is ever built, this test can
 * be retired in its favor — see `bd show skein-7uwy`.
 *
 * ## Pre-existing violation found while landing this test
 *
 * Standing this test up caught a real, already-shipped violation:
 * `feature/shell/.../nav/CommandBar.kt` (`E6.I3`'s persistent command bar)
 * calls raw `TextField` for its `$`-prompt search/slash-command field —
 * exactly the "search" surface threat model §9 names. Per `skein-7uwy`'s
 * guardrails ("do NOT replace or modify existing Compose text-input call
 * sites ... that's a followup, not this issue's problem"), that call site
 * is not touched here. It is tracked in [pendingMigrations] (distinct from
 * [allowlistedFile], which is the permanent, intentional exception for the
 * reference implementation) so this test's own `check` run stays green
 * without silently dropping the violation — see `bd show skein-yb3m` for
 * the followup that migrates `CommandBar.kt` to `SecureTextField` and
 * removes its `pendingMigrations` entry.
 */
class RawTextFieldTest {
    /**
     * Repo-root-relative `src/main/kotlin` roots to scan. Update this list
     * whenever a new `:feature:*` or `:app` module lands (see
     * `settings.gradle.kts`); `:core:*`, `:inference-service`, and
     * `:embedder-service` are out of scope for this rule (they're not
     * Compose UI modules).
     */
    private val modulesToScan =
        listOf(
            "app",
            "feature/shell",
            "feature/timeline",
            "feature/chat",
            "feature/editor",
            "feature/graph",
            "feature/personas",
            "feature/settings",
            "feature/onboarding",
            "feature/models",
        )

    /**
     * The files allowed to call the raw Compose primitives directly —
     * both WRAP a Compose text-input primitive (`TextField` for
     * `SecureTextField.kt`, `BasicTextField` for `SecureBasicTextField.kt`)
     * to apply the IME hardening every other call site must get by going
     * through one of them. `SecureBasicTextField` exists specifically for
     * `TextFieldValue`-based callers (the live-preview editor in
     * `:feature:editor`, `E7.I1`), which need the cursor/selection carried
     * by that state — [SecureTextField]'s String-only Material 3 overload
     * doesn't expose that. Paths are relative to the repo root.
     */
    private val allowlistedFiles =
        setOf(
            "feature/shell/src/main/kotlin/app/skein/feature/shell/input/SecureTextField.kt",
            "feature/shell/src/main/kotlin/app/skein/feature/shell/input/SecureBasicTextField.kt",
        )

    /**
     * Known, already-shipped violations not fixed by this test-adding
     * issue (see the class KDoc's "Pre-existing violation" section).
     * Each entry MUST link a tracking bd issue and be removed once that
     * issue migrates the file to [SecureTextField]. This is NOT a place to
     * add new exceptions for new code — only [allowlistedFile] (the
     * reference implementation) is allowed to call the raw primitives
     * going forward.
     *
     * Empty: `CommandBar.kt`'s entry was removed once `skein-yb3m` migrated
     * it to `SecureTextField`. Keep this map (rather than deleting it and
     * the filter below) so future pre-existing violations have a place to
     * land without re-deriving this pattern.
     */
    private val pendingMigrations = emptyMap<String, String>()

    private val forbidden =
        listOf(
            // Negative lookbehind excludes "BasicTextField(", "OutlinedTextField(",
            // and "SecureTextField(" from also matching the bare "TextField(" pattern.
            Regex("""(?<![A-Za-z0-9_])TextField\s*\("""),
            Regex("""(?<![A-Za-z0-9_])BasicTextField\s*\("""),
            Regex("""(?<![A-Za-z0-9_])OutlinedTextField\s*\("""),
        )

    @Test
    fun `no feature or app module calls raw BasicTextField, TextField, or OutlinedTextField`() {
        val repoRoot = findRepoRoot()
        val offenders =
            modulesToScan
                .flatMap { module ->
                    val srcRoot = File(repoRoot, "$module/src/main/kotlin")
                    if (!srcRoot.isDirectory) return@flatMap emptyList<Pair<String, String>>()
                    srcRoot
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .filter { !it.path.contains("${File.separator}build${File.separator}") }
                        .filter { it.relativeToRepoRoot(repoRoot) !in allowlistedFiles }
                        .filter { it.relativeToRepoRoot(repoRoot) !in pendingMigrations }
                        .flatMap { file ->
                            val text = file.readText()
                            forbidden
                                .filter { it.containsMatchIn(text) }
                                .map { file.relativeToRepoRoot(repoRoot) to it.pattern }
                        }.toList()
                }

        assertTrue(
            "forbidden raw text-input Composable usage found (threat model §9: " +
                "SecureTextField is the ONLY allowed text-input Composable for " +
                "vault-sensitive content — use it instead):\n" +
                offenders.joinToString("\n") { (path, pattern) -> "  $path matched $pattern" },
            offenders.isEmpty(),
        )
    }

    private fun File.relativeToRepoRoot(repoRoot: File): String =
        this.relativeTo(repoRoot).path.replace(File.separatorChar, '/')

    /** Walks up from the test's working directory to find the repo root (has `settings.gradle.kts`). */
    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        error("could not locate repo root (no settings.gradle.kts found)")
    }
}
