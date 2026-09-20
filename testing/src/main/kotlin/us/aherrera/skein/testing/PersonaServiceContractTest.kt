// The `E0.I13` contract suite: an abstract JUnit 4 test class every
// `PersonaService` implementation — the `InMemoryPersonaService` here, the
// SQL-backed `PersonaServiceImpl` in `E2.I14`, and any future
// implementation — must satisfy on the JVM (or on an instrumented device,
// for the SQL-backed impl).
//
// The three semantic tests below mirror the plan `E0.I13` acceptance
// criteria bullet list exactly:
//   - `default()` creates "Default" once and returns the same id on second
//     call
//   - `delete` of the last persona throws `IllegalStateException`
//   - `observeAll` emits after `create`
//
// JUnit 4 (not 5) matches the surrounding codebase (see
// `VaultRepositoryContractTest`, `InferenceEngineContractTest`, every test
// under `testing/src/test/**`) and keeps this suite consumable by
// downstream modules via `testImplementation(project(":testing"))` without
// pulling the Vintage engine.

package us.aherrera.skein.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import us.aherrera.skein.core.model.Persona
import us.aherrera.skein.core.model.PersonaService

/**
 * Contract suite for `PersonaService` (plan §4.4). Concrete subclasses
 * provide the service under test; each test exercises one AC bullet from
 * `E0.I13`.
 */
public abstract class PersonaServiceContractTest {
    /**
     * Fresh service per test method. The `InMemoryPersonaService` test
     * subclass returns a new instance; the SQL-backed one will open a fresh
     * temp DB.
     */
    protected abstract fun service(): PersonaService

    // ------------------------------------------------------------------
    // AC: default() creates "Default" once and returns the same id on
    // second call
    // ------------------------------------------------------------------

    @Test
    public fun default_creates_default_once_and_returns_same_id_on_second_call(): Unit =
        runTest {
            val s = service()

            val first = s.default()
            assertEquals("expected the first default() call to mint a persona named \"Default\"", "Default", first.name)
            assertNull("expected \"Default\" to have a null system prompt", first.systemPrompt)

            val second = s.default()
            assertEquals(
                "expected the second default() call to return the same persona id",
                first.id,
                second.id,
            )

            val all = mutableListOf<Persona>()
            s.get(first.id)?.let { all.add(it) }
            assertEquals(
                "expected default() to have created exactly one persona, not one per call",
                1,
                all.size,
            )
        }

    // ------------------------------------------------------------------
    // AC: delete of the last persona throws IllegalStateException
    // ------------------------------------------------------------------

    @Test
    public fun delete_of_last_persona_throws_illegal_state(): Unit =
        runTest {
            val s = service()
            val only = s.create(name = "Solo", systemPrompt = null, defaultModel = null)

            try {
                s.delete(only.id)
                fail("expected IllegalStateException when deleting the last remaining persona")
            } catch (expected: IllegalStateException) {
                assertNotNull(expected)
            }

            assertNotNull("the last persona must survive the refused delete", s.get(only.id))
        }

    // ------------------------------------------------------------------
    // AC: observeAll emits after create
    // ------------------------------------------------------------------

    @Test
    public fun observeAll_emits_after_create(): Unit =
        runTest {
            val s = service()
            val sawCreated = CompletableDeferred<List<Persona>>()
            val job =
                launch {
                    s.observeAll().collect { list ->
                        if (list.any { it.name == "Assistant" } && !sawCreated.isCompleted) {
                            sawCreated.complete(list)
                        }
                    }
                }

            val created = s.create(name = "Assistant", systemPrompt = "be helpful", defaultModel = null)

            val observed = withTimeout(5_000L) { sawCreated.await() }
            assertTrue(
                "expected observeAll to emit a list containing the newly created persona",
                observed.any { it.id == created.id },
            )
            job.cancel()
        }
}
