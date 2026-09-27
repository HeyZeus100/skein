// bd `skein-a0mm`: runs the locked `ExportServiceContractTest` suite
// (`:testing`, `E0.I14`) against the real `ExportServiceImpl` over the JVM
// `InMemoryVaultRepository` — until now only `FakeExportService` ran it.
// The seed documents are created through the repository under their own
// ids and personas, so the contract's id/persona assertions hold unchanged.

package app.skein.core.vault.export

import app.skein.core.model.Document
import app.skein.core.model.ExportService
import app.skein.core.model.NewDocument
import app.skein.testing.ExportServiceContractTest
import app.skein.testing.InMemoryVaultRepository
import kotlinx.coroutines.runBlocking

public class ExportServiceImplContractTest : ExportServiceContractTest() {
    override fun service(seed: List<Document>): ExportService {
        val repository = InMemoryVaultRepository()
        runBlocking {
            for (document in seed) {
                repository.createDocument(
                    NewDocument(
                        kind = document.kind,
                        title = document.title,
                        bodyMd = document.bodyMd,
                        personaId = document.personaId,
                        frontmatter = document.frontmatter,
                        id = document.id,
                    ),
                )
            }
        }
        return ExportServiceImpl(repository)
    }
}
