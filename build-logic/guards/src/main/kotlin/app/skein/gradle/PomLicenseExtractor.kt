package app.skein.gradle

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Extracts SPDX license identifiers for resolved artifacts (spec §10, E1.I7).
 *
 * For each artifact, [resolve] runs the following pipeline:
 * 1. Parse the artifact's POM `<licenses>` block (if a POM file was resolved).
 * 2. Normalize each declared `<name>` to an SPDX id via [normalize] (backed by
 *    `tools/licenses/spdx-aliases.json`), preferring the first one that is on
 *    the `tools/licenses/allowlist.txt` allowlist.
 * 3. If the POM is missing, unparsable, has no `<licenses>` block, or none of
 *    its declared licenses normalize to anything recognizable, fall back to
 *    a manual entry in `tools/licenses/overrides.json`.
 * 4. If nothing resolves, report `"UNKNOWN"` — this should now be the rare
 *    exception rather than the universal case.
 */
class PomLicenseExtractor(
    private val spdxAliases: Map<String, List<String>>,
    private val allowlist: Set<String>,
) {

    /** A single `<license>` entry declared in a POM's `<licenses>` block. */
    data class LicenseInfo(val name: String, val url: String)

    /** A manual override entry from `tools/licenses/overrides.json`. */
    data class Override(val license: String, val url: String)

    /** The resolved outcome for one artifact. */
    data class Resolution(
        val license: String,
        val url: String,
        /**
         * Other license names declared on a multi-license POM, beyond the
         * chosen [license]. Not written to licenses.json (that schema is
         * fixed at name/version/license/url) — surfaced for logging/reports.
         */
        val otherLicenses: List<String> = emptyList(),
    )

    // Reverse lookup: lowercase, trimmed spelling -> canonical SPDX id.
    private val aliasLookup: Map<String, String> = buildMap {
        spdxAliases.forEach { (spdxId, variants) ->
            put(spdxId.trim().lowercase(), spdxId)
            variants.forEach { variant -> put(variant.trim().lowercase(), spdxId) }
        }
    }

    /**
     * Normalizes a raw POM `<name>` license string (e.g. "The Apache
     * Software License, Version 2.0") to its SPDX identifier (e.g.
     * "Apache-2.0"). Matching is case-insensitive and whitespace-trimmed.
     * Returns null if the spelling is not recognized.
     */
    fun normalize(rawName: String): String? = aliasLookup[rawName.trim().lowercase()]

    /**
     * Parses the `<licenses>` block of a POM file. Returns an empty list if
     * the file doesn't exist, isn't well-formed XML, or declares no
     * `<licenses>` block.
     */
    fun parseLicenses(pomFile: File): List<LicenseInfo> {
        if (!pomFile.exists()) return emptyList()
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // POMs never legitimately declare a DOCTYPE; refuse it so a
                // malicious/misbehaving POM can't trigger XXE/entity expansion.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(pomFile)
            val licenseNodes = document.getElementsByTagName("license")
            (0 until licenseNodes.length).mapNotNull { index ->
                val element = licenseNodes.item(index) as? Element ?: return@mapNotNull null
                val name = element.getElementsByTagName("name").item(0)?.textContent?.trim()
                val url = element.getElementsByTagName("url").item(0)?.textContent?.trim().orEmpty()
                if (name.isNullOrBlank()) null else LicenseInfo(name, url)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolves the license for one artifact, keyed as `group:name:version`.
     *
     * @param pomFile the artifact's resolved `.pom` file, or null if Gradle
     *   couldn't resolve one (e.g. the artifact has no published POM).
     * @param overrides manual overrides loaded from
     *   `tools/licenses/overrides.json`, keyed the same way as [key].
     */
    fun resolve(key: String, pomFile: File?, overrides: Map<String, Override>): Resolution {
        val declared = pomFile?.let(::parseLicenses).orEmpty()
        val normalized = declared.map { info -> info to normalize(info.name) }

        val allowlisted = normalized.firstOrNull { (_, spdx) -> spdx != null && allowlist.contains(spdx) }
        val bestMatch = allowlisted ?: normalized.firstOrNull { (_, spdx) -> spdx != null }

        if (bestMatch != null) {
            val (chosenInfo, chosenSpdx) = bestMatch
            val others = normalized
                .filter { (info, _) -> info !== chosenInfo }
                .map { (info, spdx) -> spdx ?: info.name }
            return Resolution(license = chosenSpdx!!, url = chosenInfo.url, otherLicenses = others)
        }

        overrides[key]?.let { override ->
            return Resolution(license = override.license, url = override.url)
        }

        return Resolution(license = "UNKNOWN", url = "")
    }

    companion object {

        /** Loads `tools/licenses/spdx-aliases.json` (SPDX id -> alternate spellings). */
        fun loadAliases(file: File): Map<String, List<String>> {
            if (!file.exists()) return emptyMap()
            val tree = ObjectMapper().readTree(file)
            val result = mutableMapOf<String, List<String>>()
            tree.fields().forEach { (key, value) ->
                if (key.startsWith("_")) return@forEach
                result[key] = value.map { it.asText() }
            }
            return result
        }

        /** Loads `tools/licenses/allowlist.txt` (one SPDX id per line, `#` comments allowed). */
        fun loadAllowlist(file: File): Set<String> {
            if (!file.exists()) return emptySet()
            return file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }

        /** Loads `tools/licenses/overrides.json` (artifact key -> manual license/reason/source_url). */
        fun loadOverrides(file: File): Map<String, Override> {
            if (!file.exists()) return emptyMap()
            val tree = ObjectMapper().readTree(file)
            val result = mutableMapOf<String, Override>()
            tree.fields().forEach { (key, value) ->
                if (key.startsWith("_")) return@forEach
                val license = value.get("license")?.asText() ?: return@forEach
                val sourceUrl = value.get("source_url")?.asText().orEmpty()
                result[key] = Override(license, sourceUrl)
            }
            return result
        }
    }
}
