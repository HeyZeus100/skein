package app.skein.core.vault.transfer

import app.skein.core.model.DocId
import app.skein.core.model.Document
import app.skein.core.model.ImportResult
import app.skein.core.model.ImportService
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultRepository
import app.skein.core.vault.extract.WikilinkExtractor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream

/**
 * One folder or manifest-less Markdown archive. Only successfully imported files participate.
 * Titles stay intact; unique filename/path/title/alias matches become UUID links with their display
 * text preserved. Missing and ambiguous links retain their spelling and an explicit frontmatter
 * guard, so neither ingest nor a click can silently choose an unrelated same-title document.
 *
 * Import and initial guards commit together. Cancellation leaves committed notes guarded, even
 * when [finish] never runs. This is intentionally not a persistent, vault-wide alias registry.
 */
public class MarkdownImportLinker(
    private val repository: VaultRepository,
) {
    private data class Entry(
        val id: DocId,
        val path: String,
        val names: Set<String>,
        val originalGuards: JsonObject,
    )

    private val entries = ArrayList<Entry>()
    private val guardKeys = setOf(ImportedLinkTargets.UNRESOLVED, ImportedLinkTargets.AMBIGUOUS)

    public suspend fun importText(
        service: ImportService,
        relativePath: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
        personaId: PersonaId?,
    ): ImportResult =
        capture(relativePath, {
            it.documentId.takeIf { _ -> it.created }
        }) { service.importText(displayName, mimeType, input, personaId) }

    internal suspend fun importArchived(
        relativePath: String,
        importer: suspend () -> ArchivedDocument,
    ): ArchivedDocument = capture(relativePath, { it.created?.id }, importer)

    private suspend fun <T> capture(
        path: String,
        idOf: (T) -> DocId?,
        importer: suspend () -> T,
    ): T {
        val normalizedPath = requireNotNull(pathKey(path)) { "Invalid import path" }
        var entry: Entry? = null
        val result =
            repository.transaction {
                val imported = importer()
                val document = idOf(imported)?.let { repository.getDocument(it) }
                if (document != null) {
                    val targets =
                        WikilinkExtractor
                            .extract(document.bodyMd.orEmpty())
                            .map {
                                it.target
                            }.filterNot(ImportedLinkTargets::isDocumentId)
                    updateGuards(document, targets, emptyList())
                    entry =
                        Entry(
                            document.id,
                            normalizedPath,
                            names(document, normalizedPath),
                            JsonObject(document.frontmatter.filterKeys { it in guardKeys }),
                        )
                }
                imported
            }
        entry?.let(entries::add)
        return result
    }

    /** A late archive manifest identifies a restoration; put its original metadata back untouched. */
    internal suspend fun restoreOriginalGuards() {
        for (entry in entries) {
            repository.transaction {
                val document = repository.getDocument(entry.id) ?: return@transaction
                val restored = JsonObject(document.frontmatter.filterKeys { it !in guardKeys } + entry.originalGuards)
                if (restored != document.frontmatter) repository.updateFrontmatter(document.id, restored)
            }
        }
    }

    /** Second pass after all files were attempted. Bounded to one document body in memory at a time. */
    public suspend fun finish() {
        val byPath = entries.groupBy { it.path }
        val byName = HashMap<String, MutableSet<DocId>>()
        entries.forEach { entry -> entry.names.forEach { byName.getOrPut(it) { LinkedHashSet() }.add(entry.id) } }
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            repository.transaction {
                val document = repository.getDocument(entry.id) ?: return@transaction
                val body = document.bodyMd ?: return@transaction
                val unresolved = ArrayList<String>()
                val ambiguous = ArrayList<String>()
                val rewritten = StringBuilder(body)
                for (link in WikilinkExtractor.extract(body).asReversed()) {
                    if (ImportedLinkTargets.isDocumentId(link.target)) continue
                    val target = link.target.replace('\\', '/')
                    val candidates =
                        when {
                            '/' in target -> {
                                val path =
                                    if (target.startsWith("./") || target.startsWith("../")) {
                                        val parent = entry.path.substringBeforeLast('/', "")
                                        pathKey(if (parent.isEmpty()) target else "$parent/$target")
                                    } else {
                                        pathKey(target)
                                    }
                                byPath[path].orEmpty().map { it.id }.toSet()
                            }
                            pathKey(target) == null -> emptySet()
                            else ->
                                byName[ImportedLinkTargets.key(target)].orEmpty() +
                                    byName[ImportedLinkTargets.key(stripExtension(target))].orEmpty()
                        }
                    // A file could be deleted while an import runs. Never rewrite to a nonexistent target.
                    val resolved =
                        candidates.singleOrNull()?.takeIf {
                            ImportedLinkTargets.isDocumentId(it) && repository.getDocument(it) != null
                        }
                    if (resolved == null) {
                        unresolved += link.target
                        if (candidates.size > 1) ambiguous += link.target
                        continue
                    }
                    val heading = link.heading?.let { "#$it" }.orEmpty()
                    val label = link.alias ?: (link.target + heading)
                    rewritten.replace(link.start, link.end, "[[$resolved$heading|$label]]")
                }
                updateGuards(document, unresolved, ambiguous)
                if (rewritten.toString() != body) repository.replaceBody(document.id, rewritten.toString())
            }
        }
    }

    private suspend fun updateGuards(
        document: Document,
        unresolved: List<String>,
        ambiguous: List<String>,
    ) {
        val fields = document.frontmatter.toMutableMap()
        fields.remove(ImportedLinkTargets.UNRESOLVED)
        fields.remove(ImportedLinkTargets.AMBIGUOUS)
        if (unresolved.isNotEmpty()) {
            fields[ImportedLinkTargets.UNRESOLVED] =
                JsonArray(unresolved.distinct().map(::JsonPrimitive))
        }
        if (ambiguous.isNotEmpty()) {
            fields[ImportedLinkTargets.AMBIGUOUS] =
                JsonArray(ambiguous.distinct().map(::JsonPrimitive))
        }
        val updated = JsonObject(fields)
        if (updated != document.frontmatter) repository.updateFrontmatter(document.id, updated)
    }

    private fun names(
        document: Document,
        path: String,
    ): Set<String> =
        buildSet {
            add(path.substringAfterLast('/'))
            add(ImportedLinkTargets.key(document.title))
            for (field in listOf("aliases", "alias")) {
                val value = document.frontmatter[field]
                val aliases = if (value is JsonArray) value else listOfNotNull(value)
                aliases.filterIsInstance<JsonPrimitive>().filter { it.isString }.forEach {
                    add(
                        ImportedLinkTargets.key(it.content),
                    )
                }
            }
        }

    private fun pathKey(value: String): String? {
        val path = value.trim().replace('\\', '/')
        if (path.isBlank() ||
            '\u0000' in path ||
            ':' in path ||
            path.startsWith('/')
        ) {
            return null
        }
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        if (parts.isEmpty()) return null
        return ImportedLinkTargets.key(stripExtension(parts.joinToString("/")))
    }

    private fun stripExtension(path: String): String =
        if (path.substringAfterLast('.', "").lowercase() in
            setOf("md", "markdown", "txt")
        ) {
            path.substringBeforeLast('.')
        } else {
            path
        }
}
