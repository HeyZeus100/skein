// Proves `FakePromptAssembler` satisfies `PromptAssemblerContractTest` on
// the JVM (`E0.I12`) — i.e. that the §7.3 layout, the truncation rules and
// the data/instruction separation the contract mandates are jointly
// satisfiable before `E5.I15` implements them for real.

package us.aherrera.skein.testing

import us.aherrera.skein.core.model.PromptAssembler

public class FakePromptAssemblerTest : PromptAssemblerContractTest() {
    override fun assembler(): PromptAssembler = FakePromptAssembler()
}
