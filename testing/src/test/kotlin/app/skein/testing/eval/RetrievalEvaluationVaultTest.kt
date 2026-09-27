package app.skein.testing.eval

import app.skein.core.model.DocumentKind
import app.skein.core.model.TimelineFilter
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class RetrievalEvaluationVaultTest {
    private val personaIds = RetrievalEvaluationVault.personaAliases.associateWith { "mapped-$it" }

    @Test
    public fun overlay_preserves_size_and_every_gold_evidence_span_exists_in_its_scoped_source(): Unit =
        runBlocking {
            val repository = InMemoryVaultRepository()
            RetrievalEvaluationVault.seed(repository, personaIds)
            val all = repository.observeTimeline(TimelineFilter(kinds = DocumentKind.entries.toSet()), 1_001).first()
            assertEquals(1_000, all.size)
            assertEquals(800, all.count { it.kind == DocumentKind.NOTE })
            assertEquals(50, all.count { it.kind == DocumentKind.CHAT })
            assertEquals(150, all.count { it.kind == DocumentKind.ATTACHMENT })
            assertTrue(all.all { it.personaId == null || it.personaId in personaIds.values })
            val docs = all.associateBy { it.id }

            for (value in RetrievalEvaluationVault.queries()) {
                val query = value.jsonObject
                val persona =
                    query
                        .getValue("persona_id")
                        .takeUnless { it == JsonNull }
                        ?.jsonPrimitive
                        ?.content
                for (entry in query.getValue("relevant").jsonArray) {
                    val relevance = entry.jsonObject
                    val doc = docs.getValue(relevance.getValue("doc_id").jsonPrimitive.content)
                    val evidence = relevance.getValue("evidence").jsonPrimitive.content
                    assertTrue("Missing gold evidence for ${query.getValue("id")}", doc.bodyMd!!.contains(evidence))
                    assertTrue(relevance.getValue("grade").jsonPrimitive.int in 1..3)
                    if (persona != null) assertEquals(personaIds.getValue(persona), doc.personaId)
                }
                for (id in query["forbidden_doc_ids"]?.jsonArray.orEmpty()) {
                    assertTrue(docs.getValue(id.jsonPrimitive.content).personaId != personaIds[persona])
                }
            }
        }

    @Test
    public fun each_category_has_coverage_and_absence_cases_do_not_enter_recall_denominators() {
        val queries = RetrievalEvaluationVault.queries().map { it.jsonObject }
        assertEquals(queries.size, queries.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        for (category in listOf("lexical", "semantic", "graph", "persona", "adversarial")) {
            val cases = queries.filter { it.getValue("category").jsonPrimitive.content == category }
            assertTrue(category, cases.size >= if (category == "adversarial") 10 else 8)
            assertTrue(
                cases.all { q ->
                    q.getValue("relevant").jsonArray.any {
                        it.jsonObject
                            .getValue("grade")
                            .jsonPrimitive.int ==
                            3
                    }
                },
            )
        }
        for (category in listOf("no_match", "weak_only")) {
            val cases = queries.filter { it.getValue("category").jsonPrimitive.content == category }
            assertEquals(8, cases.size)
            assertTrue(cases.all { it.getValue("relevant").jsonArray.isEmpty() })
            assertTrue(cases.all { it.getValue("answer") == JsonNull })
        }
    }

    @Test
    public fun graph_gold_has_a_real_link_from_the_seed_to_the_answer_without_project_name_in_target() {
        val documents =
            RetrievalEvaluationVault
                .resource("corpus.json")
                .getValue("documents")
                .jsonArray
                .map { it.jsonObject }
                .associateBy { it.getValue("id").jsonPrimitive.content }
        for (value in RetrievalEvaluationVault.queries()) {
            val query = value.jsonObject
            if (query.getValue("category").jsonPrimitive.content != "graph") continue
            val seed = documents.getValue(query.getValue("seed_doc_id").jsonPrimitive.content)
            val answerId =
                query
                    .getValue(
                        "relevant",
                    ).jsonArray
                    .first()
                    .jsonObject
                    .getValue("doc_id")
                    .jsonPrimitive.content
            val target = documents.getValue(answerId)
            assertTrue(
                seed
                    .getValue(
                        "body_md",
                    ).jsonPrimitive.content
                    .contains("[[${target.getValue("title").jsonPrimitive.content}]]"),
            )
            val project =
                seed
                    .getValue("title")
                    .jsonPrimitive.content
                    .removePrefix("Project ")
                    .removeSuffix(" brief")
            assertFalse(
                target
                    .getValue("body_md")
                    .jsonPrimitive.content
                    .contains(project),
            )
        }
    }

    @Test
    public fun corpus_notes_are_identical_across_replays(): Unit =
        runBlocking {
            val first = InMemoryVaultRepository()
            val second = InMemoryVaultRepository()
            RetrievalEvaluationVault.seed(first, personaIds)
            RetrievalEvaluationVault.seed(second, personaIds)
            for (value in RetrievalEvaluationVault.resource("corpus.json").getValue("documents").jsonArray) {
                val id =
                    value.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                val a = checkNotNull(first.getDocument(id))
                val b = checkNotNull(second.getDocument(id))
                assertEquals(a.bodyMd, b.bodyMd)
                assertEquals(a.contentHash, b.contentHash)
                assertEquals(a.personaId, b.personaId)
            }
        }
}
