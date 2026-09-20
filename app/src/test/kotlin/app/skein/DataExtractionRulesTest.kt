package app.skein

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * E3.I7 (spec §9; docs/design/POST_REVIEW_RESOLUTIONS.md §4.3-4.4, `skein-7ki2`):
 * parses the real (non-placeholder) `data_extraction_rules.xml` and its API
 * 30 fallback `backup_rules_legacy.xml`, and asserts the exclusion set
 * required by the design covers the vault DB, key material, model files,
 * attachments, and the export staging cache -- so a Seedvault device-to-
 * device pass (which the review flagged as still applying even when
 * `allowBackup="true"` scopes ordinary cloud backup) cannot exfiltrate any
 * of them.
 *
 * Plain JVM test (no Robolectric/Android framework needed): the resource
 * files are parsed directly, exactly as `ManifestPolicyTest` parses the raw
 * manifest for attributes the framework doesn't reliably surface.
 */
class DataExtractionRulesTest {
    /** A single `<exclude domain="..." path="..."/>` (`path` omitted means the whole domain). */
    private data class ExcludeRule(
        val domain: String,
        val path: String?,
    )

    // --- Required exclusions (must appear in every ruleset below) ----------

    private val requiredInAllRulesets =
        setOf(
            ExcludeRule("database", null),
            ExcludeRule("file", "vault.db"),
            ExcludeRule("file", "vault.db-wal"),
            ExcludeRule("file", "vault.db-shm"),
            ExcludeRule("file", "vault.db-journal"),
            ExcludeRule("file", "keys/"),
            ExcludeRule("file", "models/"),
            ExcludeRule("file", "attachments/"),
            ExcludeRule("root", "cache/staging_export/"),
        )

    // --- data_extraction_rules.xml (API 31+) --------------------------------

    @Test
    fun `cloud-backup excludes the vault DB, keys, models, attachments, and export staging`() {
        val excludes = excludesIn(dataExtractionRulesFile(), "cloud-backup")
        assertTrue(
            "cloud-backup is missing required excludes: ${requiredInAllRulesets - excludes}",
            excludes.containsAll(requiredInAllRulesets),
        )
    }

    @Test
    fun `device-transfer excludes the vault DB, keys, models, attachments, and export staging`() {
        val excludes = excludesIn(dataExtractionRulesFile(), "device-transfer")
        assertTrue(
            "device-transfer is missing required excludes: ${requiredInAllRulesets - excludes}",
            excludes.containsAll(requiredInAllRulesets),
        )
    }

    @Test
    fun `cloud-backup and device-transfer name the same excludes (Seedvault D2D parity)`() {
        val cloudBackup = excludesIn(dataExtractionRulesFile(), "cloud-backup")
        val deviceTransfer = excludesIn(dataExtractionRulesFile(), "device-transfer")

        // cloud-backup carries one extra, narrower rule: cloud sync must
        // never carry unlock state off-device. Device transfer (moving to a
        // new device you already control) is exempt from that one rule; the
        // rest of the set is identical.
        val cloudOnly =
            setOf(
                ExcludeRule("sharedpref", "unlock_state.xml"),
            )
        assertEquals(deviceTransfer, cloudBackup - cloudOnly)
    }

    // --- backup_rules_legacy.xml (API 30 fallback) --------------------------

    @Test
    fun `legacy full-backup-content excludes the vault DB, keys, models, attachments, and export staging`() {
        val excludes = fullBackupExcludes()
        assertTrue(
            "backup_rules_legacy.xml is missing required excludes: ${requiredInAllRulesets - excludes}",
            excludes.containsAll(requiredInAllRulesets),
        )
    }

    @Test
    fun `legacy rules mirror the device-transfer exclude set (no cloud-only sharedpref rule)`() {
        val deviceTransfer = excludesIn(dataExtractionRulesFile(), "device-transfer")
        assertEquals(deviceTransfer, fullBackupExcludes())
    }

    // --- Helpers -------------------------------------------------------------

    private fun excludesIn(
        file: File,
        sectionTag: String,
    ): Set<ExcludeRule> {
        val document = parse(file)
        val section = document.getElementsByTagName(sectionTag).item(0) as Element
        return section.childElements("exclude").map(::toExcludeRule).toSet()
    }

    private fun fullBackupExcludes(): Set<ExcludeRule> {
        val document = parse(backupRulesLegacyFile())
        val root = document.getElementsByTagName("full-backup-content").item(0) as Element
        return root.childElements("exclude").map(::toExcludeRule).toSet()
    }

    private fun toExcludeRule(element: Element): ExcludeRule =
        ExcludeRule(
            domain = element.getAttribute("domain"),
            path = element.getAttribute("path").ifEmpty { null },
        )

    private fun Element.childElements(tagName: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .filter { it.tagName == tagName }
    }

    private fun parse(file: File) =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun dataExtractionRulesFile(): File = resourceFile("data_extraction_rules.xml")

    private fun backupRulesLegacyFile(): File = resourceFile("backup_rules_legacy.xml")

    private fun resourceFile(name: String): File {
        val candidates =
            listOf(
                File("src/main/res/xml/$name"),
                File("app/src/main/res/xml/$name"),
            )
        return candidates.firstOrNull { it.exists() }
            ?: error("could not locate $name from working dir ${File(".").absolutePath}")
    }
}
