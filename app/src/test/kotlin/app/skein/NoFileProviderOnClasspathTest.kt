package app.skein

import androidx.core.content.FileProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

/**
 * `E10.I9` (bd `skein-fubu`; `docs/design/POST_REVIEW_RESOLUTIONS.md` §4.2:
 * "Pick DocumentsProvider as the single canonical export provider in v1. No
 * `FileProvider` in the shipped app.") -- two independent, complementary
 * checks that no `androidx.core.content.FileProvider` subclass ever ships:
 *
 *  1. A source grep: no `.kt` file anywhere in the repo references the fully
 *     qualified `androidx.core.content.FileProvider` name (an `import` line
 *     or a fully-qualified supertype declaration). `ManifestPolicyTest`
 *     already asserts no *declared* `<provider>` element's class name ends
 *     in `FileProvider`; this catches the case that check cannot -- a
 *     `FileProvider` subclass that compiles into the APK but was never
 *     wired into the manifest (dead code today, a one-line manifest edit
 *     away from shipping tomorrow).
 *  2. A runtime `Class.forName` classpath scan: every `.class` file under
 *     the `app.skein` package prefix on the test JVM's classpath is loaded
 *     and checked for assignability to `androidx.core.content.FileProvider`.
 *     This is the belt to the grep's suspenders -- it would also catch a
 *     Java (non-Kotlin) source file, a subclass built through code
 *     generation, or a `FileProvider` reference obfuscated across string
 *     concatenation that a plain grep could miss.
 *
 * Both checks are scoped to `app.skein.*` (this app's own code): androidx
 * itself ships `androidx.core.content.FileProvider` as a class other
 * libraries may legitimately reference or subclass, so scanning the whole
 * classpath would flag androidx's own artifacts as false positives.
 */
class NoFileProviderOnClasspathTest {
    // --- 1. source grep ------------------------------------------------------

    @Test
    fun `no source file references androidx-core-content-FileProvider`() {
        val hits =
            repoRoot()
                .walkTopDown()
                .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
                .filter { it.isFile && it.extension == "kt" }
                // This test's own file legitimately spells out the FQN (the
                // `import` above and the runtime classpath guard below), and
                // ManifestPolicyTest's KDoc discusses FileProvider by its
                // simple name only (never the FQN) -- exclude just this file.
                .filterNot { it.name == "NoFileProviderOnClasspathTest.kt" }
                .filter { it.readText().contains(FULLY_QUALIFIED_FILE_PROVIDER) }
                .map { it.relativeTo(repoRoot()).path }
                .toList()

        assertTrue(
            "POST_REVIEW_RESOLUTIONS.md §4.2 forbids FileProvider in v1; " +
                "found a reference to $FULLY_QUALIFIED_FILE_PROVIDER in: $hits",
            hits.isEmpty(),
        )
    }

    // --- 2. runtime classpath scan -------------------------------------------

    @Test
    fun `no class under app-skein on the runtime classpath extends FileProvider`() {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val offenders =
            appSkeinClassNames()
                .mapNotNull { name -> loadQuietly(name, loader) }
                .filter { FileProvider::class.java.isAssignableFrom(it) && it != FileProvider::class.java }
                .map { it.name }

        assertTrue(
            "POST_REVIEW_RESOLUTIONS.md §4.2 forbids FileProvider in v1; " +
                "found a FileProvider subclass on the classpath: $offenders",
            offenders.isEmpty(),
        )
    }

    /** `Class.forName` under `try/catch`: unrelated classpath noise (missing native deps, multi-release jars) must not fail this test. */
    private fun loadQuietly(
        name: String,
        loader: ClassLoader,
    ): Class<*>? =
        try {
            Class.forName(name, false, loader)
        } catch (_: Throwable) {
            null
        }

    /** Every class name under `app/skein/` on `java.class.path`, from both exploded `.class` dirs and jars. */
    private fun appSkeinClassNames(): Set<String> {
        val names = LinkedHashSet<String>()
        for (entry in classpathEntries()) {
            when {
                entry.isDirectory -> names += classNamesUnderDirectory(entry)
                entry.extension == "jar" -> names += classNamesUnderJar(entry)
            }
        }
        return names
    }

    /**
     * `java.class.path` entries, plus one level of indirection: some Gradle
     * test workers (notably on Windows, to dodge command-line length limits)
     * launch with a single "classpath jar" whose manifest `Class-Path`
     * attribute lists the real entries as relative paths instead of passing
     * them all on `-cp`. Following that attribute keeps this test correct
     * under either launch style instead of silently scanning zero classes.
     */
    private fun classpathEntries(): List<File> {
        val direct =
            System
                .getProperty("java.class.path")
                .orEmpty()
                .split(File.pathSeparator)
                .map(::File)
                .filter { it.exists() }

        val indirect =
            direct
                .filter { it.extension == "jar" }
                .flatMap { jar -> manifestClassPathOf(jar) }

        return (direct + indirect).distinct()
    }

    private fun manifestClassPathOf(jar: File): List<File> =
        runCatching {
            JarFile(jar).use { file ->
                val classPathAttr = file.manifest?.mainAttributes?.getValue("Class-Path") ?: return emptyList()
                classPathAttr
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .map { File(jar.parentFile, it) }
                    .filter { it.exists() }
            }
        }.getOrDefault(emptyList())

    private fun classNamesUnderDirectory(dir: File): List<String> =
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(dir).path }
            .filter { it.startsWith(PACKAGE_PATH) }
            .map(::toBinaryName)
            .toList()

    private fun classNamesUnderJar(jar: File): List<String> =
        runCatching {
            JarFile(jar).use { file ->
                file
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && it.name.startsWith(PACKAGE_PATH) }
                    .map { toBinaryName(it.name) }
                    .toList()
            }
        }.getOrDefault(emptyList())

    private fun toBinaryName(classFilePath: String): String =
        classFilePath
            .removeSuffix(".class")
            .replace('/', '.')
            .replace('\\', '.')

    /** Walks up from the working directory to the checkout root (the dir holding `settings.gradle.kts`). */
    private fun repoRoot(): File {
        var candidate = File(".").absoluteFile.normalize()
        while (true) {
            if (File(candidate, "settings.gradle.kts").exists()) return candidate
            candidate =
                candidate.parentFile ?: error("could not locate settings.gradle.kts above ${File(".").absolutePath}")
        }
    }

    private companion object {
        const val FULLY_QUALIFIED_FILE_PROVIDER = "androidx.core.content.FileProvider"
        const val PACKAGE_PATH = "app/skein/"
    }
}
