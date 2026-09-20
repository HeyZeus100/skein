// Proves `FakeExportService` satisfies `ExportServiceContractTest` on the
// JVM (`E0.I14`).

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.Document
import us.aherrera.skein.core.model.ExportService

public class FakeExportServiceTest : ExportServiceContractTest() {
    override fun service(seed: List<Document>): ExportService = FakeExportService(documents = seed.toMutableList())
}
