// Concrete `VaultRepositoryContractTest` for `InMemoryVaultRepository`.
// Proves the JVM fake honors every plan-`E0.I11` semantic. The SQL-backed
// impl (`E2.I4`) will subclass the same contract on an instrumented device.

package app.skein.testing

import app.skein.core.model.PersonaId
import app.skein.core.model.VaultRepository

public class InMemoryVaultRepositoryTest : VaultRepositoryContractTest() {
    override fun repo(): VaultRepository = InMemoryVaultRepository()

    /**
     * skein-ci54: `InMemoryVaultRepository` never tracked a `personas`
     * table and never validated `NewDocument.personaId` against one, so
     * there is nothing to seed here — a documented no-op, not an
     * oversight. See `VaultRepositoryContractTest.seedPersona`'s KDoc.
     */
    override fun seedPersona(id: PersonaId): Unit = Unit
}
