// E2.I12 (bd skein-jq8): reads an optional user-supplied `.docx` template so
// `DocxWriter` can reuse its `styles.xml`/section properties instead of
// this writer's own defaults, per the plan's "With a user .docx template,
// its styles.xml ... and section properties (w:sectPr) are copied and the
// body is replaced." `word/settings.xml` is carried over too, when present.
// `word/numbering.xml` is deliberately NOT read from the template — see
// `DocxParts.kt`'s file header ("Known scope limits"): this writer's
// `document.xml` body always references its own `numId` 1/2, so reusing a
// template's numbering.xml (whose `numId`s it doesn't control) risks
// numbering that silently doesn't render, which is worse than a template
// with a plain (if less on-brand) bullet/decimal format.

package app.skein.core.vault.export.docx

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringWriter
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Thrown by [DocxTemplate.read] when [template] is not a readable zip, or is a zip but not a `.docx` (missing `word/document.xml`/`word/styles.xml`). */
public class DocxTemplateException(
    message: String,
) : Exception(message)

/** The parts of a user template [DocxWriter] reuses. [sectPrXml] is `null` when the template's `word/document.xml` has no body-level `w:sectPr` (rare, but not itself invalid) or isn't parseable — [DocxWriter] falls back to [DocxParts.defaultSectPrXml] in that case rather than failing the whole export over a decorative detail. */
internal data class DocxTemplateParts(
    val stylesXmlBytes: ByteArray,
    val settingsXmlBytes: ByteArray?,
    val sectPrXml: String?,
)

internal object DocxTemplate {
    private const val DOCUMENT_ENTRY = "word/document.xml"
    private const val STYLES_ENTRY = "word/styles.xml"
    private const val SETTINGS_ENTRY = "word/settings.xml"
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun read(template: InputStream): DocxTemplateParts {
        val entries = readZipEntries(template)
        val documentXmlBytes =
            entries[DOCUMENT_ENTRY]
                ?: throw DocxTemplateException("template is missing $DOCUMENT_ENTRY (not a valid .docx)")
        val stylesXmlBytes =
            entries[STYLES_ENTRY]
                ?: throw DocxTemplateException("template is missing $STYLES_ENTRY (not a valid .docx)")
        return DocxTemplateParts(
            stylesXmlBytes = stylesXmlBytes,
            settingsXmlBytes = entries[SETTINGS_ENTRY],
            sectPrXml = extractSectPr(documentXmlBytes),
        )
    }

    private fun readZipEntries(template: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        try {
            ZipInputStream(template).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (error: ZipException) {
            throw DocxTemplateException("template is not a valid zip: ${error.message}")
        } catch (error: IOException) {
            throw DocxTemplateException("template could not be read: ${error.message}")
        }
        if (entries.isEmpty()) throw DocxTemplateException("template is an empty zip (not a valid .docx)")
        return entries
    }

    /** Best-effort: the body's last direct `w:sectPr` child, serialized back to an XML fragment. Never throws — a template whose `document.xml` doesn't parse still has a usable `styles.xml`, so this returns `null` instead of failing the whole template. */
    private fun extractSectPr(documentXmlBytes: ByteArray): String? =
        try {
            val doc = secureDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(documentXmlBytes))
            val body = doc.getElementsByTagNameNS(W_NS, "body").item(0) as? Element
            val bodyChildren = body?.childNodes
            var lastSectPr: Element? = null
            if (bodyChildren != null) {
                for (i in 0 until bodyChildren.length) {
                    val node = bodyChildren.item(i)
                    if (node is Element && node.localName == "sectPr" && node.namespaceURI == W_NS) {
                        lastSectPr = node
                    }
                }
            }
            lastSectPr?.let(::serializeElement)
        } catch (_: Exception) {
            null
        }

    /**
     * Serializes an already-parsed DOM [Element] back to an XML fragment.
     * This does not itself parse any external input (the [DOMSource] is an
     * in-memory node from [extractSectPr]'s securely-configured parse), but
     * the factory is still hardened against XXE/external-DTD resolution —
     * belt and suspenders for anything touching `javax.xml.transform` with
     * caller-supplied content upstream.
     */
    private fun serializeElement(element: Element): String {
        val writer = StringWriter()
        // `ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_STYLESHEET` aren't set here:
        // this module targets Android's javax.xml stub (via compileSdk),
        // whose `TransformerFactory` doesn't recognize those JDK-only
        // attribute names and would throw `IllegalArgumentException` at
        // runtime. `FEATURE_SECURE_PROCESSING` (below) is the
        // cross-platform-safe hardening; it's also moot in practice here —
        // the `DOMSource` is an in-memory node this file already parsed
        // with a hardened `DocumentBuilderFactory` in `extractSectPr`, not
        // externally-supplied XML this transform step reads itself.
        val factory =
            TransformerFactory.newInstance().apply {
                setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING,
                    true,
                )
            }
        val transformer = factory.newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        transformer.transform(DOMSource(element), StreamResult(writer))
        return writer.toString()
    }

    /**
     * Hardened against XXE (external entity expansion / SSRF via a DTD) —
     * a template is caller-supplied content this writer must parse to look
     * for `w:sectPr`, so it is treated the same as any other untrusted
     * input, not just a trusted local file.
     */
    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isExpandEntityReferences = false
        }
}
