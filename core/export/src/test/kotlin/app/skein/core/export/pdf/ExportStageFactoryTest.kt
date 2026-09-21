// E2.I11 (bd skein-80m): expiresAt recorded per `docs/design/
// export-plaintext-lifetime.md` / `POST_REVIEW_RESOLUTIONS.md` §4.2 (10
// minutes by default) — task brief JVM test list.

package app.skein.core.export.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportStageFactoryTest {
    @Test
    fun `expiresAt is createdAt plus the default ten minute lifetime`() {
        val stage =
            ExportStageFactory.create(
                stageId = "stage-1",
                documentId = "doc-1",
                path = "/data/user/0/app.skein/cache/staging_export/stage-1-note.pdf",
                createdAt = 1_000L,
            )

        assertThat(stage.expiresAt).isEqualTo(1_000L + 10L * 60L * 1000L)
        assertThat(stage.origin).isEqualTo("pdf_export")
        assertThat(stage.documentId).isEqualTo("doc-1")
    }

    @Test
    fun `a custom lifetime overrides the default`() {
        val stage =
            ExportStageFactory.create(
                stageId = "stage-2",
                documentId = "doc-2",
                path = "/x",
                createdAt = 0L,
                lifetimeMillis = 5_000L,
            )

        assertThat(stage.expiresAt).isEqualTo(5_000L)
    }

    @Test
    fun `NoOpExportStageRecorder accepts a record without throwing`() {
        val stage = ExportStageFactory.create("s", "d", "/x", createdAt = 0L)

        NoOpExportStageRecorder.record(stage)
    }
}
