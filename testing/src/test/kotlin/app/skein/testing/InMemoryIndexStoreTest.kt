// Concrete `IndexStoreContractTest` for `InMemoryIndexStore`. The SQL-
// backed impl (`E2.I15`) will subclass the same contract on an instrumented
// device.

package app.skein.testing

import app.skein.core.model.IndexStore

public class InMemoryIndexStoreTest : IndexStoreContractTest() {
    override fun index(): IndexStore = InMemoryIndexStore()
}
