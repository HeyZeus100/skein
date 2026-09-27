// Concrete `VaultRepositoryContractTest` for `InMemoryVaultRepository`.
// Proves the JVM fake honors every plan-`E0.I11` semantic. The SQL-backed
// impl (`E2.I4`) will subclass the same contract on an instrumented device.

package app.skein.testing

import app.skein.core.model.Edge
import app.skein.core.model.EdgeKind
import app.skein.core.model.IndexStore
import app.skein.core.model.PersonaId
import app.skein.core.model.VaultRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class InMemoryVaultRepositoryTest : VaultRepositoryContractTest() {
    private var lastIndex: InMemoryIndexStore? = null

    /** A linked vault (LC-02): the repository and [index] share chunks and edges. */
    override fun repo(): VaultRepository {
        val index = InMemoryIndexStore()
        lastIndex = index
        return InMemoryVaultRepository(index = index)
    }

    override fun index(): IndexStore = checkNotNull(lastIndex) { "call repo() first" }

    /**
     * skein-ci54: `InMemoryVaultRepository` never tracked a `personas`
     * table and never validated `NewDocument.personaId` against one, so
     * there is nothing to seed here — a documented no-op, not an
     * oversight. See `VaultRepositoryContractTest.seedPersona`'s KDoc.
     */
    override fun seedPersona(id: PersonaId): Unit = Unit

    /**
     * §11.4 (LC-06). Edges from a missing source cannot be written through a
     * linked index (it skips them), so they are planted before the index is
     * linked — the fake's stand-in for residue a pre-LC-04 delete left. The
     * device variant lives in `VaultRepositoryImplContractTest`.
     */
    @Test
    public fun orphan_edge_sweep_deletes_edges_from_missing_sources(): Unit =
        runTest {
            val index = InMemoryIndexStore()
            val ghost = "01924a4b-4d29-7000-8000-000000000057"
            index.replaceEdges(ghost, setOf(EdgeKind.TAG), listOf(Edge(ghost, "tag:x", EdgeKind.TAG, createdAt = 1L)))
            val repo = InMemoryVaultRepository(index = index)

            assertEquals(1, repo.sweepIndexOrphans())
            assertTrue(index.edgesFrom(ghost).isEmpty())
        }
}
