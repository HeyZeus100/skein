// Concrete `ModelRegistryContractTest` for `InMemoryModelRegistry`. Proves
// the JVM fake honors every `skein-cyq` semantic. The SQL-backed impl
// (`ModelRegistryImpl`, `:core:vault`) subclasses the same contract on an
// instrumented device (`ModelRegistryImplContractTest`).

package app.skein.testing

import app.skein.core.model.ModelRegistry

public class InMemoryModelRegistryTest : ModelRegistryContractTest() {
    override fun registry(): ModelRegistry = InMemoryModelRegistry()
}
