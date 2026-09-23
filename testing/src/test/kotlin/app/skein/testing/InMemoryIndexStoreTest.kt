// Concrete `IndexStoreContractTest` for `InMemoryIndexStore`. The SQL-
// backed impl (`E2.I15`) will subclass the same contract on an instrumented
// device.

package app.skein.testing

import app.skein.core.model.DocId
import app.skein.core.model.IndexStore

public class InMemoryIndexStoreTest : IndexStoreContractTest() {
    override fun index(): IndexStore = InMemoryIndexStore()

    /**
     * skein-ci54: `InMemoryIndexStore` has no `documents` table of its own
     * (that lives in `InMemoryVaultRepository`) and never enforced
     * `chunks.doc_id`'s foreign key, so there is nothing to seed here — a
     * documented no-op, not an oversight. See `IndexStoreContractTest.
     * seedDocument`'s KDoc.
     */
    override fun seedDocument(docId: DocId): Unit = Unit
}
