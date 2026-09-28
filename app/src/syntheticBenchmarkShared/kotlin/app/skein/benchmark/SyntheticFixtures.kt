package app.skein.benchmark

import app.skein.core.model.ChatMessage
import app.skein.core.model.DocumentKind
import app.skein.core.model.Locator
import app.skein.core.model.RecallSource
import app.skein.core.model.Retrieved
import app.skein.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class SyntheticCase(
    val id: String,
    val knowledge: Boolean,
    val query: String,
    val history: List<ChatMessage>,
    val sources: List<Retrieved>,
    val personaSystemPrompt: String?,
)

/** Only the host-exported synthetic input is accepted; gold labels never enter the model process. */
internal object SyntheticFixtures {
    fun parse(jsonl: String): List<SyntheticCase> {
        require(jsonl.toByteArray().size <= 8 * 1024 * 1024) { "fixture exceeds size bound" }
        val rows = jsonl.lineSequence().filter { it.isNotBlank() }.toList()
        require(rows.size in 1..500) { "fixture case count outside bound" }
        val cases = rows.map { parseCase(Json.parseToJsonElement(it).jsonObject) }
        require(cases.map { it.id }.toSet().size == cases.size) { "duplicate case id" }
        return cases
    }

    private fun parseCase(row: JsonObject): SyntheticCase {
        require(row.keys.all { it in CASE_KEYS }) { "unsupported fixture field" }
        require(row.getValue("schema_version").jsonPrimitive.int == 1) { "unsupported fixture schema" }
        val scope = row.string("scope")
        require(scope == "knowledge" || scope == "general") { "unsupported scope" }
        val sources = row.getValue("sources").jsonArray.map { parseSource(it.jsonObject) }
        require(sources.size <= 30) { "too many supplied sources" }
        require(sources.map { it.chunkId }.toSet().size == sources.size) { "duplicate source chunk" }
        require(scope == "knowledge" || sources.isEmpty()) { "general input must have no sources" }
        val history =
            row.getValue("history").jsonArray.map {
                val message = it.jsonObject
                require(message.keys == setOf("role", "content")) { "unsupported history field" }
                ChatMessage(Role.valueOf(message.string("role").uppercase(Locale.ROOT)), message.string("content"))
            }
        require(history.size <= 100) { "history exceeds bound" }
        return SyntheticCase(
            id = row.string("case_id").also { require(it.matches(SAFE_ID)) { "unsafe case id" } },
            knowledge = scope == "knowledge",
            query = row.string("query"),
            history = history,
            sources = sources,
            personaSystemPrompt = row["persona_system_prompt"]?.jsonPrimitive?.content,
        )
    }

    private fun parseSource(row: JsonObject): Retrieved {
        require(row.keys == SOURCE_KEYS) { "unsupported source fields" }
        val text = row.string("text")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val start = row.getValue("byte_start").jsonPrimitive.int
        val end = row.getValue("byte_end").jsonPrimitive.int
        require(start == 0 && end == bytes.size) { "source byte range does not cover supplied revision" }
        val revision = row.string("revision_hash")
        require(revision == sha256(bytes)) { "source revision hash does not match supplied text" }
        val score = row.getValue("score").jsonPrimitive.double
        require(score.isFinite()) { "source score is not finite" }
        return Retrieved(
            chunkId = row.getValue("chunk_id").jsonPrimitive.long,
            docId = row.string("doc_id"),
            docTitle = row.string("title"),
            text = text,
            score = score,
            sourceKind = DocumentKind.valueOf(row.string("source_kind")),
            // Provenance is explicitly supplied evidence, not a measured recall stage.
            recalledBy = setOf(RecallSource.LEXICAL),
            revisionHash = revision,
            locator = Locator(start, end),
        )
    }

    val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
    val SHA256 = Regex("[a-f0-9]{64}")
    private val CASE_KEYS =
        setOf("schema_version", "case_id", "scope", "query", "history", "sources", "persona_system_prompt")
    private val SOURCE_KEYS =
        setOf("chunk_id", "doc_id", "revision_hash", "title", "text", "score", "source_kind", "byte_start", "byte_end")
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Reject paths outside the dedicated benchmark directory, including symlink escapes. */
internal fun confinedFile(
    root: File,
    path: String,
): File {
    require(root.absoluteFile == root.canonicalFile) { "benchmark root cannot contain a symlink" }
    val file = File(path).canonicalFile
    val prefix = root.canonicalFile.path + File.separator
    require(file.path.startsWith(prefix) && file.isFile) { "file is outside benchmark storage or absent" }
    return file
}
