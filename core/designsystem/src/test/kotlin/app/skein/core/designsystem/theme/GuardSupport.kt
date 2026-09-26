package app.skein.core.designsystem.theme

import java.io.File

/**
 * skein-xtov.23.12 (DS14): shared plumbing for the design-system guard tests
 * (DESIGN_SYSTEM.md §14 items 9-12: shadows/gradients, hard-coded colours,
 * legacy glyph icons, ambient animation). Each guard is its own small JVM
 * source scan; this just saves every one of them from re-deriving the repo
 * root and re-filtering build/test/preview noise.
 */
internal object GuardSupport {
    /** Walks up from the JVM working directory to find the repo root. */
    fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "feature").isDirectory && File(dir, "settings.gradle.kts").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        error("could not locate repo root (no settings.gradle.kts found)")
    }

    /**
     * Kotlin sources under [relativeDirs] (repo-root-relative, e.g.
     * "feature", "core/designsystem"). Always excludes `build/`. Unless
     * [includeTests] is set, also excludes `src/test/`, `src/androidTest/`
     * and preview files (`*Preview.kt` / `*Previews.kt`, `@Preview`-only
     * scaffolding that never ships) — guards care about what actually
     * renders on a surviving surface, not test fixtures or Studio previews.
     */
    fun productionKotlinFiles(
        vararg relativeDirs: String,
        includeTests: Boolean = false,
    ): List<File> {
        val root = repoRoot()
        return relativeDirs
            .asSequence()
            .flatMap { File(root, it).walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains("${File.separator}build${File.separator}") }
            .filterNot {
                !includeTests &&
                    (
                        it.path.contains("${File.separator}test${File.separator}") ||
                            it.path.contains("${File.separator}androidTest${File.separator}") ||
                            it.name.endsWith("Preview.kt") ||
                            it.name.endsWith("Previews.kt")
                    )
            }.toList()
    }

    /** This file's path relative to the repo root, with `/` separators regardless of platform. */
    fun File.relativeToRepo(): String =
        path.removePrefix(repoRoot().path + File.separator).replace(File.separatorChar, '/')

    /**
     * Best-effort (not a real lexer): true for a line that is entirely a
     * line comment, or a line inside a KDoc or block comment (i.e. starts,
     * once trimmed, with `//`, `*` or a block-comment opener). Good enough
     * to keep guards from tripping on KDoc/comment prose that merely
     * mentions a forbidden token instead of shipping it.
     */
    fun isCommentLine(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
}
