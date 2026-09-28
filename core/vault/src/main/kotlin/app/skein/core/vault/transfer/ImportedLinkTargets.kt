package app.skein.core.vault.transfer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import java.util.Locale

/** Persistent guards for unresolved links from a particular imported tree, never global aliases. */
public object ImportedLinkTargets {
    public const val UNRESOLVED: String = "_skein_unresolved_import_links"
    public const val AMBIGUOUS: String = "_skein_ambiguous_import_links"

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    public fun isDocumentId(target: String): Boolean = uuid.matches(target)

    public fun isUnresolved(
        frontmatter: JsonObject,
        target: String,
    ): Boolean = contains(frontmatter, UNRESOLVED, target)

    public fun isAmbiguous(
        frontmatter: JsonObject,
        target: String,
    ): Boolean = contains(frontmatter, AMBIGUOUS, target)

    /** Distinct from `title:`: creating or renaming a note must not arbitrarily resolve an imported link. */
    public fun unresolvedTarget(
        sourceId: String,
        target: String,
    ): String = "import:$sourceId:${key(target)}"

    internal fun key(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun contains(
        frontmatter: JsonObject,
        field: String,
        target: String,
    ): Boolean =
        (frontmatter[field] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.content?.let(::key) == key(target) }
}
