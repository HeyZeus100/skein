// Proves `FakeExportService` satisfies `ExportServiceContractTest` on the
// JVM (`E0.I14`).

package app.skein.testing

import app.skein.core.model.Document
import app.skein.core.model.ExportService

public class FakeExportServiceTest : ExportServiceContractTest() {
    override fun service(seed: List<Document>): ExportService = FakeExportService(documents = seed.toMutableList())
}
