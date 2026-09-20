package app.skein.feature.settings

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Reads and parses `licenses.json` — `E1.I7`'s `LicenseAuditTask` output,
 * packaged into every APK's assets root by `LicenseAuditPlugin`
 * (`build-generated/licenses/<variant>/assets/licenses.json`, registered as
 * a generated asset source so it ends up at the assets root, i.e.
 * `context.assets.open("licenses.json")` — not under a subdirectory) — for
 * [AboutScreen]'s license list (plan `E9.I8`).
 *
 * No Android [Context] is retained on the instance: [load] takes it as a
 * parameter, and the actual parsing ([decodeLicenses]) is a pure function of
 * a JSON string with no Android dependency at all, so it — and therefore the
 * data this repository produces — is unit-testable against a fixture string
 * with no `AssetManager`, Robolectric, or instrumentation required (see
 * `LicensesRepositoryTest`).
 */
class LicensesRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Loads `licenses.json` from [context]'s assets and parses it into
     * [LicenseEntry] rows.
     *
     * Returns an empty list — rather than throwing — if the asset is
     * missing ([IOException], e.g. an older/dev build without the guard
     * applied) or fails to parse ([SerializationException] /
     * [IllegalArgumentException], e.g. a malformed or truncated file).
     * This is user-facing attribution content, not something that should
     * crash [AboutScreen]; callers render the empty list as an empty-state
     * fallback instead.
     */
    suspend fun load(context: Context): List<LicenseEntry> =
        withContext(ioDispatcher) {
            try {
                val text = context.assets.open(ASSET_FILE_NAME).use { it.readBytes().decodeToString() }
                decodeLicenses(text)
            } catch (e: IOException) {
                emptyList()
            } catch (e: SerializationException) {
                emptyList()
            } catch (e: IllegalArgumentException) {
                emptyList()
            }
        }

    companion object {
        /** Filename `LicenseAuditPlugin` generates at the assets root — see this class's KDoc. */
        const val ASSET_FILE_NAME = "licenses.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses raw `licenses.json` text into [LicenseEntry] rows. Pulled out of
         * [load] so tests can exercise the parsing logic directly against a
         * fixture string, without touching `Context`/`AssetManager` at all.
         *
         * Throws [SerializationException]/[IllegalArgumentException] on malformed
         * input; [load] is the only caller expected to catch those (a direct test
         * of this function asserts the happy path, plus that it surfaces failures
         * rather than silently swallowing them itself).
         */
        internal fun decodeLicenses(jsonText: String): List<LicenseEntry> = json.decodeFromString(jsonText)
    }
}
