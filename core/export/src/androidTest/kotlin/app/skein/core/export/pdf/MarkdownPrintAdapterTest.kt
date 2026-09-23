// E2.I11 (bd skein-80m): instrumented test driving `MarkdownPrintAdapter`
// directly (`onLayout` -> `onWrite` into a `ParcelFileDescriptor` on a temp
// file) for a `SyntheticVault` note, per the plan's acceptance criteria and
// the task brief's androidTest scope.
//
// Follow-up (`skein-k3b2`): no API 35 emulator was available in this
// worktree, matching every other `*InstrumentedTest`/`*ContractTest` in
// this codebase (`VaultRepositoryImplContractTest`,
// `VaultKeyProviderInstrumentedTest`, `SkeinEditorInstrumentedTest`, ...).
// This class is compiled (and so any broken statement is caught) by the
// ordinary Gradle `check` path; running it for real is gated on that
// follow-up.

package app.skein.core.export.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.TestLayoutResultCallback
import android.print.TestWriteResultCallback
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.skein.core.model.DocId
import app.skein.core.model.DocumentKind
import app.skein.core.model.TimelineFilter
import app.skein.testing.InMemoryVaultRepository
import app.skein.testing.fixtures.SyntheticVault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MarkdownPrintAdapterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = InMemoryVaultRepository()
    private var tempFile: File? = null

    @After
    fun tearDown() {
        tempFile?.delete()
    }

    @Test
    fun adapterProducesANonEmptyPdfForASyntheticVaultNote() {
        SyntheticVault.seed(repository, size = SyntheticVault.Preset.SMALL)
        val docId = firstNoteId(repository)

        val adapter = PdfExportService(context, repository).printAdapter(docId)

        val info = layoutSynchronously(adapter)
        assertThat(info.pageCount).isAtLeast(1)

        val file = File.createTempFile("markdown_print_adapter_test", ".pdf", context.cacheDir)
        tempFile = file
        writeSynchronously(adapter, file)

        assertThat(file.length()).isGreaterThan(0L)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer -> assertThat(renderer.pageCount).isAtLeast(1) }
        }
    }

    private fun firstNoteId(repository: InMemoryVaultRepository): DocId =
        runBlocking {
            repository
                .observeTimeline(filter = TimelineFilter(kinds = setOf(DocumentKind.NOTE)), limit = 1)
                .first()
                .single()
                .id
        }

    private fun layoutSynchronously(adapter: PrintDocumentAdapter): PrintDocumentInfo {
        val latch = CountDownLatch(1)
        var result: PrintDocumentInfo? = null
        val attributes =
            PrintAttributes
                .Builder()
                .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
                .setResolution(PrintAttributes.Resolution("skein_test", "test", TEST_DPI, TEST_DPI))
                .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build()
        adapter.onLayout(
            null,
            attributes,
            CancellationSignal(),
            object : TestLayoutResultCallback() {
                override fun onLayoutFinished(
                    info: PrintDocumentInfo,
                    changed: Boolean,
                ) {
                    result = info
                    latch.countDown()
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    latch.countDown()
                }
            },
            null,
        )
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return requireNotNull(result) { "onLayout did not finish within ${TIMEOUT_SECONDS}s" }
    }

    private fun writeSynchronously(
        adapter: PrintDocumentAdapter,
        file: File,
    ) {
        val latch = CountDownLatch(1)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
            adapter.onWrite(
                arrayOf(PageRange.ALL_PAGES),
                pfd,
                CancellationSignal(),
                object : TestWriteResultCallback() {
                    override fun onWriteFinished(pages: Array<PageRange>) {
                        latch.countDown()
                    }

                    override fun onWriteFailed(error: CharSequence?) {
                        latch.countDown()
                    }
                },
            )
        }
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TEST_DPI = 150
        const val TIMEOUT_SECONDS = 10L
    }
}
