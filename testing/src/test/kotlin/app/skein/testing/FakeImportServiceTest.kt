// Proves `FakeImportService` satisfies `ImportServiceContractTest` on the
// JVM (`E0.I14`).

package app.skein.testing

import app.skein.core.model.DocId
import app.skein.core.model.ImportService
import app.skein.core.model.PersonaId

public class FakeImportServiceTest : ImportServiceContractTest() {
    private val fake = FakeImportService()

    override fun service(): ImportService = fake

    override suspend fun personaOf(id: DocId): PersonaId? = fake.personaOf(id)
}
