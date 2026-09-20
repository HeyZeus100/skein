package app.skein.feature.shell.testing

/**
 * Stable test tags for Compose UI tests in the shell layer.
 * These allow tests to locate shell elements by a stable identifier
 * rather than brittle text assertions that break when display strings change.
 */
object ShellTestTags {
    /**
     * Root container of the Skein shell ([SkeinApp]).
     * Used to verify the shell has been rendered without depending on
     * specific display text or UI layout details.
     */
    const val SKEIN_SHELL_ROOT = "skein_shell_root"
}
