package app.skein.testing.eval

import app.skein.core.model.Document
import app.skein.core.model.DocumentKind
import app.skein.core.model.NewDocument
import app.skein.core.model.PersonaId
import app.skein.core.model.TimelineFilter
import app.skein.core.model.VaultRepository
import app.skein.testing.fixtures.SyntheticVault
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A coherent evaluation overlay on the existing deterministic 1,000-document
 * fixture. Replaces 72 filler notes, keeping the size/kind mix unchanged.
 * Source IDs are fixed; persona aliases resolve to the real test vault's IDs.
 * This development corpus is never a held-out generation-quality benchmark.
 */
public object RetrievalEvaluationVault {
    public val personaAliases: Set<String> = setOf("default", "work", "research", "personal")

    public fun resource(name: String): JsonObject {
        require(name == "corpus.json" || name == "gold.json")
        val text =
            checkNotNull(javaClass.getResourceAsStream("/eval/$name")) {
                "Missing evaluation resource"
            }.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    public fun queries(): JsonArray = resource("gold.json").getValue("queries").jsonArray

    /** Call on an empty test vault with its four personas already created. */
    public suspend fun seed(
        repository: VaultRepository,
        personaIds: Map<String, PersonaId>,
    ) {
        require(personaIds.keys.containsAll(personaAliases)) { "Missing evaluation persona mapping" }
        require(personaAliases.map { personaIds.getValue(it) }.toSet().size == personaAliases.size) {
            "Evaluation personas must be distinct"
        }
        check(
            repository
                .observeTimeline(
                    TimelineFilter(kinds = DocumentKind.entries.toSet()),
                    limit = 1,
                ).first()
                .isEmpty(),
        ) { "Evaluation vault must be empty" }

        // SyntheticVault uses symbolic persona IDs; resolve them before writes
        // so the same seed works with real SQLite foreign keys, not only fakes.
        val mappedRepository =
            object : VaultRepository by repository {
                override suspend fun createDocument(new: NewDocument): Document =
                    repository.createDocument(
                        new.copy(personaId = new.personaId?.let { personaIds.getValue(it) }),
                    )
            }
        SyntheticVault.seed(mappedRepository)
        val originals =
            repository
                .observeTimeline(TimelineFilter(kinds = setOf(DocumentKind.NOTE)), limit = 1_000)
                .first()
                .associateBy { it.title }
        val documents = resource("corpus.json").getValue("documents").jsonArray
        for ((index, value) in documents.withIndex()) {
            val source = value.jsonObject
            val persona =
                source
                    .getValue("persona_id")
                    .takeUnless { it == JsonNull }
                    ?.jsonPrimitive
                    ?.content
            repository.deleteDocument(originals.getValue("Note ${index + 1}").id)
            repository.createDocument(
                NewDocument(
                    id = source.getValue("id").jsonPrimitive.content,
                    kind = DocumentKind.NOTE,
                    title = source.getValue("title").jsonPrimitive.content,
                    bodyMd = source.getValue("body_md").jsonPrimitive.content,
                    personaId = persona?.let { personaIds.getValue(it) },
                ),
            )
        }
    }
}
