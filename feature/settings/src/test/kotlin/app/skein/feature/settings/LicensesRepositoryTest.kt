package app.skein.feature.settings

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [LicensesRepository]'s JSON parsing ([LicensesRepository.decodeLicenses])
 * directly against fixture strings shaped like `LicenseAuditTask`'s real
 * `licenses.json` output.
 *
 * [LicensesRepository.load] itself (the `Context.assets.open(...)` +
 * try/catch wrapper) is intentionally not exercised here: it needs a real
 * `AssetManager`, which this module has no Robolectric/instrumentation
 * setup for, and it has no logic of its own beyond delegating to
 * [LicensesRepository.decodeLicenses] (parsing) and swallowing
 * [java.io.IOException]/[SerializationException]/[IllegalArgumentException]
 * into an empty list — both of which are covered here without that
 * infrastructure.
 */
class LicensesRepositoryTest {
    @Test
    fun `decodeLicenses parses every field of a fixture entry`() {
        val json =
            """
            [
                {"name": "androidx.core:core-ktx", "version": "1.19.0", "license": "Apache-2.0", "url": "https://developer.android.com/jetpack/androidx"}
            ]
            """.trimIndent()

        val entries = LicensesRepository.decodeLicenses(json)

        assertEquals(
            listOf(
                LicenseEntry(
                    name = "androidx.core:core-ktx",
                    version = "1.19.0",
                    license = "Apache-2.0",
                    url = "https://developer.android.com/jetpack/androidx",
                ),
            ),
            entries,
        )
    }

    @Test
    fun `decodeLicenses returns an entry per array element`() {
        val json =
            """
            [
                {"name": "a:a", "version": "1.0", "license": "MIT", "url": ""},
                {"name": "b:b", "version": "2.0", "license": "Apache-2.0", "url": ""},
                {"name": "c:c", "version": "3.0", "license": "BSD-3-Clause", "url": ""}
            ]
            """.trimIndent()

        val entries = LicensesRepository.decodeLicenses(json)

        assertEquals(3, entries.size)
    }

    @Test
    fun `decodeLicenses preserves the native components NOTICE documents`() {
        // Mirrors NOTICE's "Native components" section (top-level `NOTICE`,
        // `skein-kf8r`) — the acceptance criterion for `skein-dun` is that
        // the screen lists every `licenses.json` entry, explicitly including
        // these native components, not just Maven/Gradle-resolved ones.
        val json =
            """
            [
                {"name": "sqlcipher:sqlcipher", "version": "4.17.0", "license": "BSD-3-Clause", "url": "https://github.com/sqlcipher/sqlcipher"},
                {"name": "asg017:sqlite-vec", "version": "0.1.9", "license": "Apache-2.0 OR MIT", "url": "https://github.com/asg017/sqlite-vec"},
                {"name": "ggerganov:llama.cpp", "version": "unpinned", "license": "MIT", "url": "https://github.com/ggerganov/llama.cpp"},
                {"name": "com.microsoft.onnxruntime:onnxruntime-android", "version": "1.27.0", "license": "MIT", "url": "https://github.com/microsoft/onnxruntime"},
                {"name": "IBM:plex-mono", "version": "latest", "license": "OFL-1.1", "url": "https://github.com/IBM/plex"}
            ]
            """.trimIndent()

        val entries = LicensesRepository.decodeLicenses(json)

        assertEquals(5, entries.size)
        assertTrue(entries.any { it.name == "sqlcipher:sqlcipher" && it.license == "BSD-3-Clause" })
        assertTrue(entries.any { it.name == "asg017:sqlite-vec" && it.license == "Apache-2.0 OR MIT" })
        assertTrue(entries.any { it.name == "ggerganov:llama.cpp" && it.license == "MIT" })
        assertTrue(entries.any { it.name == "com.microsoft.onnxruntime:onnxruntime-android" && it.license == "MIT" })
        assertTrue(entries.any { it.name == "IBM:plex-mono" && it.license == "OFL-1.1" })
    }

    @Test
    fun `decodeLicenses ignores unknown keys instead of failing`() {
        val json =
            """
            [
                {"name": "a:a", "version": "1.0", "license": "MIT", "url": "", "sha256": "deadbeef"}
            ]
            """.trimIndent()

        val entries = LicensesRepository.decodeLicenses(json)

        assertEquals(1, entries.size)
    }

    @Test
    fun `decodeLicenses throws on malformed json rather than silently returning nothing`() {
        assertThrows(SerializationException::class.java) {
            LicensesRepository.decodeLicenses("not valid json")
        }
    }

    @Test
    fun `decodeLicenses of an empty array returns an empty list`() {
        val entries = LicensesRepository.decodeLicenses("[]")

        assertEquals(emptyList<LicenseEntry>(), entries)
    }
}
