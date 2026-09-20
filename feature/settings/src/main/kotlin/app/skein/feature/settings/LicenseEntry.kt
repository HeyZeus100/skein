package app.skein.feature.settings

import kotlinx.serialization.Serializable

/**
 * One row of `licenses.json` (plan `E1.I7`'s `LicenseAuditTask` output,
 * packaged into every APK's assets root by `LicenseAuditPlugin` — see
 * `build-logic/guards/src/main/kotlin/app/skein/gradle/LicenseAuditTask.kt`'s
 * own `LicenseEntry`, which this mirrors field-for-field).
 *
 * `@Serializable` rather than hand-rolled parsing so [LicensesRepository]
 * can deserialize the whole `List<LicenseEntry>` array in one
 * `Json.decodeFromString` call; unknown/extra keys are ignored by the
 * [kotlinx.serialization.json.Json] instance [LicensesRepository] configures,
 * so this data class doesn't need to change in lockstep with every field the
 * Gradle-side task ever adds.
 *
 * @param name the artifact coordinate, e.g. `"androidx.core:core-ktx"`
 *   (`"$group:$name"` — see the Gradle task's `parseArtifactKey`).
 * @param version the artifact's resolved version, or `""` if unknown.
 * @param license the SPDX identifier (e.g. `"Apache-2.0"`, `"MIT"`,
 *   `"BSD-3-Clause"`, `"OFL-1.1"`), or the literal string `"UNKNOWN"` when
 *   the audit couldn't resolve one from Gradle metadata or
 *   `tools/licenses/overrides.json`.
 * @param url a link to the license text or project page, or `""` when the
 *   audit had no artifact file to derive one from. Never opened in a
 *   browser or shared — this is an offline app; [AboutScreen] only ever
 *   displays it as text (with copy-to-clipboard) per the threat model.
 */
@Serializable
data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
)
