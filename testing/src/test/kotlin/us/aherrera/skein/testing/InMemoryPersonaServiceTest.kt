// Concrete `PersonaServiceContractTest` for `InMemoryPersonaService`.
// Proves the JVM fake honors every plan-`E0.I13` semantic. The SQL-backed
// impl (`E2.I14`) will subclass the same contract on an instrumented device.

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.PersonaService

public class InMemoryPersonaServiceTest : PersonaServiceContractTest() {
    override fun service(): PersonaService = InMemoryPersonaService()
}
