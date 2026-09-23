// Concrete `VaultRepositoryContractTest` for `InMemoryVaultRepository`.
// Proves the JVM fake honors every plan-`E0.I11` semantic. The SQL-backed
// impl (`E2.I4`) will subclass the same contract on an instrumented device.

package app.skein.testing

import app.skein.core.model.VaultRepository

public class InMemoryVaultRepositoryTest : VaultRepositoryContractTest() {
    override fun repo(): VaultRepository = InMemoryVaultRepository()
}
