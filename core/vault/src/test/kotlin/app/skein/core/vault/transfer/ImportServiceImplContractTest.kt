// `E2.I7` (bd `skein-ad5`): runs the locked `ImportServiceContractTest`
// suite (`:testing`, `E0.I14`) against the real `ImportServiceImpl` backed
// by the JVM `InMemoryVaultRepository` fake — the same contract
// `FakeImportService` satisfies, now exercised against production code.
//
// The suite's `importImage` test is assumption-skipped here (reported by
// JUnit as *skipped*, never as passed) because that entry point is owned by
// `E2.I9` (bd `skein-rni`) and `ImportServiceImpl` throws
// `UnsupportedOperationException` for it until then, mirroring how
// `ExportServiceImpl.exportDocx` defers to `E2.I11`/`E2.I12`. The suite's
// test methods are Kotlin-final, so they cannot be overridden; a `TestRule`
// keyed on the method name is the least invasive way to park them. When the
// owning bead lands, delete its entry from [PENDING_ENTRY_POINTS] and the
// real contract test runs again unchanged.
//
// `importPdf_creates_both_an_attachment_and_a_note` (`E2.I8`, bd
// `skein-qdo`) is no longer parked: `ImportServiceImpl.importPdf` is
// implemented (`PdfImporter.kt`). The suite's fixture bytes
// (`"%PDF-1.4 fake bytes"`) are not a structurally valid PDF, so this run
// also doubles as the "malformed PDF" case — `PdfImporter.extract` catches
// the parse failure and falls back to the "no text layer" notice rather
// than throwing, and an attachment is still stored either way, which is all
// this contract test asserts (ids, not body content).

package app.skein.core.vault.transfer

import app.skein.core.model.ImportService
import app.skein.testing.ImportServiceContractTest
import app.skein.testing.InMemoryVaultRepository
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

public class ImportServiceImplContractTest : ImportServiceContractTest() {
    override fun service(): ImportService = ImportServiceImpl(InMemoryVaultRepository())

    /** See the file header: parks the inherited tests for entry points another bead owns. */
    @get:Rule
    public val pendingEntryPoints: TestRule =
        TestRule { base: Statement, description: Description ->
            val owner = PENDING_ENTRY_POINTS[description.methodName]
            if (owner == null) {
                base
            } else {
                object : Statement() {
                    override fun evaluate(): Unit =
                        throw AssumptionViolatedException(
                            "${description.methodName} is owned by $owner; ImportServiceImpl throws " +
                                "UnsupportedOperationException for it until that bead lands",
                        )
                }
            }
        }

    private companion object {
        val PENDING_ENTRY_POINTS: Map<String, String> =
            mapOf(
                "importImage_without_a_vision_model_creates_only_the_attachment" to "E2.I9 (bd skein-rni)",
            )
    }
}
