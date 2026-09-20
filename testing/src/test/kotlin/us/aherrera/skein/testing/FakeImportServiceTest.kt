// Proves `FakeImportService` satisfies `ImportServiceContractTest` on the
// JVM (`E0.I14`).

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.ImportService

public class FakeImportServiceTest : ImportServiceContractTest() {
    override fun service(): ImportService = FakeImportService()
}
