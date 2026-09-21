// `E2.I8` (bd `skein-qdo`) acceptance criterion: "Memory: a 50-page fixture
// processes page-by-page (peak heap delta < 64 MB, smoke)."
//
// Same methodology as `ImportTextMemorySmokeTest` (see that file's header
// for why the delta is measured this way and why it is a smoke bound, not a
// pinned measurement): `PdfImporter.extract` appends each page's text
// directly into one `StringBuilder` rather than building a per-page string
// list or a whole-document string via a single `PDFTextStripper.getText`
// call and then re-copying it to insert page breaks — a regression to
// either shape on a real-world (much larger) PDF would multiply peak memory
// with page count instead of holding it roughly constant. This fixture is
// small, so the bound mostly guards against that regression shape rather
// than measuring an absolute ceiling.

package app.skein.core.vault.transfer

import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class ImportPdfMemorySmokeTest {
    @Before
    public fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    @Test
    public fun `a 50-page pdf import stays within the allocation smoke bound`(): Unit =
        runTest {
            val bytes =
                buildPdf(
                    pages = (1..PAGE_COUNT).map { page -> textPage("Page $page body text for the memory smoke test.") },
                )
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo, context = RuntimeEnvironment.getApplication())
            val runtime = Runtime.getRuntime()

            settleHeap(runtime)
            val before = usedHeap(runtime)
            val result = service.importPdf("big.pdf", ByteArrayInputStream(bytes), personaId = null)
            val after = usedHeap(runtime)

            val delta = after - before
            assertThat(delta).isAtMost(HEAP_DELTA_BOUND)

            val sections = repo.getDocument(result.documentId)!!.bodyMd!!.split("\n\n---\n\n")
            assertThat(sections).hasSize(PAGE_COUNT)
            assertThat(sections.first()).isEqualTo("Page 1 body text for the memory smoke test.")
            assertThat(sections.last()).isEqualTo("Page $PAGE_COUNT body text for the memory smoke test.")
        }

    private fun usedHeap(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    private fun settleHeap(runtime: Runtime) {
        repeat(3) {
            runtime.gc()
            Thread.sleep(20)
        }
    }

    private companion object {
        const val PAGE_COUNT: Int = 50
        const val HEAP_DELTA_BOUND: Long = 64L * 1024 * 1024
    }
}
