// skein-xtov.23.20 (ML-1, docs/ux/MAC_UX_LAB_PLAN.md §2.2, §8): the
// `@SkeinXxxPreviews` multipreview annotations in `:core:designsystem` each
// hardcode a `device = "spec:width=…dp,height=…dp,dpi=…"` string next to a
// `name` that is supposed to identify a `SkeinDevice` window — annotation
// arguments must be compile-time constants, so nothing stops a copy-paste
// slip from drifting the two apart.
//
// This can't be a runtime-reflection test:
// `androidx.compose.ui.tooling.preview.Preview` is `@Retention(CLASS)` (it
// exists for tooling — Studio/Lint/Roborazzi's KSP step — never for the
// running app), so `Class.getAnnotationsByType(Preview::class.java)` comes
// back empty for every one of these annotation classes (verified: that was
// this test's first draft, and every case failed with "expected not to be
// empty"). Parsing the annotation *class* declarations back out of the
// source file is the only way to see the `@Preview` arguments without
// pulling in a bytecode library (ASM) `:testing-ui` doesn't otherwise need.
package app.skein.testing.ui

import app.skein.core.designsystem.preview.SkeinCompactPreviews
import app.skein.core.designsystem.preview.SkeinDarkPreviews
import app.skein.core.designsystem.preview.SkeinDevicePreviews
import app.skein.core.designsystem.preview.SkeinFoldPreviews
import app.skein.core.designsystem.preview.SkeinFontScalePreviews
import app.skein.core.designsystem.preview.SkeinWidePreviews
import com.google.common.truth.Truth.assertThat
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass

class SkeinPreviewAnnotationsMatchSkeinDeviceTest {
    private data class Dims(
        val widthDp: Int,
        val heightDp: Int,
        val dpi: Int,
    )

    companion object {
        // `android.util.DisplayMetrics`' generalized density buckets — the
        // only qualifier strings a `SkeinDevice` entry uses in place of a
        // raw `NNNdpi` number (`PHONE` and `LARGE_1280` both say `xhdpi`).
        private val densityBuckets =
            mapOf(
                "ldpi" to 120,
                "mdpi" to 160,
                "tvdpi" to 213,
                "hdpi" to 240,
                "xhdpi" to 320,
                "xxhdpi" to 480,
                "xxxhdpi" to 640,
            )

        private val annotationClassRegex = Regex("""annotation class (\w+)""")

        // No nested parens ever appear inside a `@Preview(...)` call in this
        // file (string literals hold none), so a non-greedy match up to the
        // first `)` safely spans the multi-line, ktlint-wrapped calls too.
        private val previewCallRegex = Regex("""@Preview\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
        private val nameArgRegex = Regex("""name\s*=\s*"([^"]*)"""")
        private val deviceArgRegex = Regex("""device\s*=\s*"([^"]*)"""")

        /** `SkeinDevice.qualifiers`, e.g. `"w1043dp-h1007dp-land-330dpi"` or `"w360dp-h800dp-port-xhdpi"`. */
        private fun parseQualifiers(qualifiers: String): Dims {
            val width = Regex("""w(\d+)dp""").find(qualifiers)!!.groupValues[1].toInt()
            val height = Regex("""h(\d+)dp""").find(qualifiers)!!.groupValues[1].toInt()
            val dpi =
                Regex("""(\d+)dpi""")
                    .find(qualifiers)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: densityBuckets.getValue(qualifiers.substringAfterLast('-'))
            return Dims(width, height, dpi)
        }

        /** A `@Preview(device = …)` spec string, e.g. `"spec:width=524dp,height=1175dp,dpi=330"`. */
        private fun parseSpec(spec: String): Dims {
            val width = Regex("""width=(\d+)dp""").find(spec)!!.groupValues[1].toInt()
            val height = Regex("""height=(\d+)dp""").find(spec)!!.groupValues[1].toInt()
            val dpi = Regex("""dpi=(\d+)""").find(spec)!!.groupValues[1].toInt()
            return Dims(width, height, dpi)
        }

        /** `"fold-outer-524 · dark"` / `"fold-outer-443 · 100%"` -> `"fold-outer-524"` / `"fold-outer-443"`. */
        private fun baseDir(name: String) = name.substringBefore(" ·").trim()

        private val deviceByDir = SkeinDevice.entries.associateBy { it.dir }

        /**
         * `SkeinFoldPreviews` etc. live in `:core:designsystem`'s `src/main`
         * (`docs/ux/MAC_UX_LAB_PLAN.md` §2.2). A Gradle unit test's working
         * directory is its own module directory (`testing-ui/`,
         * verified — not the repo root), so the repo root is one level up;
         * walk further up too, in case that ever changes, using
         * `settings.gradle.kts` as the repo-root marker.
         */
        private val repoRoot: File by lazy {
            val startDir = System.getProperty("user.dir")!!
            var dir = File(startDir).absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("settings.gradle.kts not found above $startDir")
            }
            dir
        }

        private val previewsFile: File by lazy {
            repoRoot.resolve("core/designsystem/src/main/kotlin/app/skein/core/designsystem/preview/SkeinPreviews.kt")
        }

        /** annotation class simple name -> its stacked `@Preview(name, device)` pairs, in source order. */
        private lateinit var previewsByAnnotationClass: Map<String, List<Pair<String, String>>>

        @BeforeClass
        @JvmStatic
        fun parseAnnotationsFile() {
            check(previewsFile.isFile) { "not found: $previewsFile" }
            val text = previewsFile.readText()
            val classMatches = annotationClassRegex.findAll(text).toList()
            check(classMatches.isNotEmpty()) { "no `annotation class` declarations found in $previewsFile" }
            val result = linkedMapOf<String, List<Pair<String, String>>>()
            var previousEnd = 0
            for (match in classMatches) {
                val chunk = text.substring(previousEnd, match.range.first)
                val previews =
                    previewCallRegex
                        .findAll(chunk)
                        .map { call ->
                            val args = call.groupValues[1]
                            val name = nameArgRegex.find(args)!!.groupValues[1]
                            val device = deviceArgRegex.find(args)!!.groupValues[1]
                            name to device
                        }.toList()
                result[match.groupValues[1]] = previews
                previousEnd = match.range.last + 1
            }
            previewsByAnnotationClass = result
        }
    }

    private fun assertMatchesSkeinDevice(annotationClass: KClass<out Annotation>) {
        val simpleName = annotationClass.simpleName!!
        val previews = previewsByAnnotationClass[simpleName]
        assertThat(previews).isNotNull()
        assertThat(previews).isNotEmpty()
        for ((name, device) in previews!!) {
            val dir = baseDir(name)
            val skeinDevice =
                deviceByDir[dir] ?: error("$simpleName: no SkeinDevice with dir=\"$dir\" (preview name=\"$name\")")
            assertThat(parseSpec(device)).isEqualTo(parseQualifiers(skeinDevice.qualifiers))
        }
    }

    @Test
    fun skeinFoldPreviewsMatchSkeinDevice() = assertMatchesSkeinDevice(SkeinFoldPreviews::class)

    @Test
    fun skeinDevicePreviewsMatchSkeinDevice() = assertMatchesSkeinDevice(SkeinDevicePreviews::class)

    @Test
    fun skeinDarkPreviewsMatchSkeinDevice() = assertMatchesSkeinDevice(SkeinDarkPreviews::class)

    @Test
    fun skeinCompactPreviewsMatchSkeinDevice() = assertMatchesSkeinDevice(SkeinCompactPreviews::class)

    @Test
    fun skeinWidePreviewsMatchSkeinDevice() = assertMatchesSkeinDevice(SkeinWidePreviews::class)

    @Test
    fun skeinFontScalePreviewsMatchSkeinDevice() = assertMatchesSkeinDevice(SkeinFontScalePreviews::class)
}
