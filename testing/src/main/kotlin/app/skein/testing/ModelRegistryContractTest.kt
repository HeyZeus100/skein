// skein-cyq (E4.I5) contract suite: every `ModelRegistry` implementation —
// `InMemoryModelRegistry` here, the SQL-backed `ModelRegistryImpl` on an
// instrumented device (`ModelRegistryImplContractTest`) — must satisfy the
// same semantics. Mirrors `VaultRepositoryContractTest`'s shape exactly:
// one abstract factory method, one `@Test` per behavior.

package app.skein.testing

import app.skein.core.model.Capability
import app.skein.core.model.CompanionFile
import app.skein.core.model.CompanionRole
import app.skein.core.model.Model
import app.skein.core.model.ModelFormat
import app.skein.core.model.ModelId
import app.skein.core.model.ModelOrigin
import app.skein.core.model.ModelRecord
import app.skein.core.model.ModelRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract suite for [ModelRegistry]. Concrete subclasses provide a fresh
 * registry per test method.
 */
public abstract class ModelRegistryContractTest {
    /** Fresh, empty registry per test method. */
    protected abstract fun registry(): ModelRegistry

    private fun sampleModel(
        id: ModelId = "sample-model",
        name: String = "Sample Model",
        sizeBytes: Long = 4_096L,
    ): Model =
        Model(
            id = id,
            name = name,
            path = "/data/models/$id/model.gguf",
            sha256 = "a".repeat(64),
            format = ModelFormat.GGUF,
            capabilities = setOf(Capability.TEXT),
            sizeBytes = sizeBytes,
            contextLength = 8_192,
            attestationUrl = null,
            companions =
                mapOf(
                    CompanionRole.TOKENIZER to
                        CompanionFile(path = "/data/models/$id/tokenizer.json", sha256 = "b".repeat(64)),
                ),
            importedAt = 1_700_000_000_000L,
        )

    private fun sampleRecord(
        id: ModelId = "sample-model",
        origin: ModelOrigin = ModelOrigin.PICKED,
    ): ModelRecord =
        ModelRecord(
            model = sampleModel(id = id),
            blake3 = "c".repeat(64),
            origin = origin,
            sourceUrl = null,
            sourceRevision = null,
            licenseSpdx = "UNKNOWN",
        )

    @Test
    public fun list_is_empty_for_a_fresh_registry(): Unit =
        runTest {
            assertTrue("a fresh registry must start with no rows", registry().list().isEmpty())
        }

    @Test
    public fun get_returns_null_for_an_unknown_id(): Unit =
        runTest {
            assertNull(registry().get("does-not-exist"))
        }

    @Test
    public fun upsert_then_get_round_trips_every_field(): Unit =
        runTest {
            val registry = registry()
            val record = sampleRecord()
            registry.upsert(record)

            val fetched =
                requireNotNull(registry.get(record.model.id)) { "expected the just-upserted row to be gettable" }
            assertEquals(record, fetched)
        }

    @Test
    public fun list_reflects_every_upserted_record(): Unit =
        runTest {
            val registry = registry()
            registry.upsert(sampleRecord(id = "model-a"))
            registry.upsert(sampleRecord(id = "model-b"))

            val ids = registry.list().map { it.model.id }.toSet()
            assertEquals(setOf("model-a", "model-b"), ids)
        }

    @Test
    public fun upsert_replaces_an_existing_row_with_the_same_id(): Unit =
        runTest {
            val registry = registry()
            registry.upsert(sampleRecord(id = "model-a"))
            registry.upsert(sampleRecord(id = "model-a", origin = ModelOrigin.HUB).copy(licenseSpdx = "Apache-2.0"))

            val fetched = requireNotNull(registry.get("model-a"))
            assertEquals(ModelOrigin.HUB, fetched.origin)
            assertEquals("Apache-2.0", fetched.licenseSpdx)
            assertEquals(1, registry.list().size)
        }

    @Test
    public fun delete_removes_the_row(): Unit =
        runTest {
            val registry = registry()
            registry.upsert(sampleRecord(id = "model-a"))
            registry.delete("model-a")

            assertNull(registry.get("model-a"))
            assertTrue(registry.list().isEmpty())
        }

    @Test
    public fun delete_of_an_unknown_id_is_a_no_op(): Unit =
        runTest {
            val registry = registry()
            registry.upsert(sampleRecord(id = "model-a"))
            registry.delete("never-existed")

            assertEquals(1, registry.list().size)
        }

    @Test
    public fun default_is_null_for_a_fresh_registry(): Unit =
        runTest {
            assertNull(registry().default())
        }

    @Test
    public fun setDefault_then_default_round_trips(): Unit =
        runTest {
            val registry = registry()
            registry.upsert(sampleRecord(id = "model-a"))
            registry.setDefault("model-a")

            assertEquals("model-a", registry.default())
        }

    @Test
    public fun setDefault_null_clears_the_default(): Unit =
        runTest {
            val registry = registry()
            registry.upsert(sampleRecord(id = "model-a"))
            registry.setDefault("model-a")
            registry.setDefault(null)

            assertNull(registry.default())
        }

    @Test
    public fun setDefault_accepts_an_id_with_no_backing_row(): Unit =
        runTest {
            // ModelRegistry's own contract: the default pointer is
            // independent of row existence — ModelManager, not the
            // registry, is responsible for keeping the two consistent.
            val registry = registry()
            registry.setDefault("row-does-not-exist")

            assertEquals("row-does-not-exist", registry.default())
        }
}
