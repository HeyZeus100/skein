package app.skein.feature.settings

/** Display label for entries whose license the audit couldn't resolve (see [normalizedLicenseGroup]). */
internal const val UNKNOWN_LICENSE_GROUP = "Unknown"

/** One SPDX-identifier section of [AboutScreen]'s license list, and the entries filed under it. */
internal data class LicenseGroup(
    val spdxId: String,
    val entries: List<LicenseEntry>,
)

/**
 * Groups [LicenseEntry] rows by SPDX license identifier for [AboutScreen]'s
 * sectioned list (plan `E9.I8`'s acceptance criterion: "Screen lists every
 * entry in `licenses.json`" — grouping must never drop a row).
 *
 * Groups are sorted alphabetically (case-insensitive) by [LicenseGroup.spdxId],
 * and entries within a group are sorted by [LicenseEntry.name] (also
 * case-insensitive), so the resulting order is stable and reproducible
 * regardless of `licenses.json`'s own (build-generated) row order.
 */
internal fun List<LicenseEntry>.groupedBySpdx(): List<LicenseGroup> =
    groupBy { it.license.normalizedLicenseGroup() }
        .toSortedMap(compareBy { it.lowercase() })
        .map { (spdxId, entries) -> LicenseGroup(spdxId, entries.sortedBy { it.name.lowercase() }) }

/**
 * Maps a raw `licenses.json` `license` value to its display group: the
 * literal string unless it's blank or the audit's `"UNKNOWN"` sentinel (see
 * `LicenseAuditTask`), in which case it's filed under [UNKNOWN_LICENSE_GROUP]
 * rather than dropped or crashing the grouping.
 */
private fun String.normalizedLicenseGroup(): String =
    if (isBlank() || equals("UNKNOWN", ignoreCase = true)) UNKNOWN_LICENSE_GROUP else this
