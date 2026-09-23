// Proves `FakeImportService` satisfies `ImportServiceContractTest` on the
// JVM (`E0.I14`).

package app.skein.testing

import app.skein.core.model.ImportService

public class FakeImportServiceTest : ImportServiceContractTest() {
    override fun service(): ImportService = FakeImportService()
}
