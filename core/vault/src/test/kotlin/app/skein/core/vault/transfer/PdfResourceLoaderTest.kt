// `E2.I8` (bd `skein-qdo`) acceptance criterion: "`PDFBoxResourceLoader.init(context)`
// called once (Robolectric test for idempotence)." `PdfResourceLoader`
// (`PdfImporter.kt`) is the guard; this asserts a second `ensureInitialized`
// call never touches the supplied `Context` again, by counting
// `getApplicationContext()` calls on a wrapper (the same call
// `PDFBoxResourceLoader.init` makes internally).

package app.skein.core.vault.transfer

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class PdfResourceLoaderTest {
    private class CountingContext(
        base: Context,
    ) : ContextWrapper(base) {
        var applicationContextRequests: Int = 0
            private set

        override fun getApplicationContext(): Context {
            applicationContextRequests += 1
            return super.getApplicationContext()
        }
    }

    @Before
    public fun resetGuard() {
        PdfResourceLoader.resetForTest()
    }

    @Test
    public fun `a second ensureInitialized call does not touch the context again`() {
        val context = CountingContext(RuntimeEnvironment.getApplication())

        PdfResourceLoader.ensureInitialized(context)
        PdfResourceLoader.ensureInitialized(context)
        PdfResourceLoader.ensureInitialized(context)

        assertThat(context.applicationContextRequests).isEqualTo(1)
    }
}
