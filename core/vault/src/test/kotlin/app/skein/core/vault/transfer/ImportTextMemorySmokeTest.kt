// `E2.I7` (bd `skein-ad5`) acceptance criterion: "A 10 MB text file imports
// without loading more than the file size + 25 % into memory (assert via
// `Runtime` delta with a generous bound — documented as a smoke test)."
//
// This is a *smoke* test, not a measurement: it exists to catch a gross
// regression (an `input.readBytes()` + `String.lines()` + `joinToString`
// pipeline, say, which allocates ~8x the file size) rather than to pin an
// exact figure.
//
// Methodology and why the bound is what it is:
//   - The delta is `usedHeap(after) - usedHeap(before)` with a `System.gc()`
//     before the baseline and *no* GC afterwards, so it approximates total
//     bytes *allocated* during the import — an upper bound on peak live
//     memory. A collection that happens to run mid-import only lowers the
//     measured delta, so noise can only make the test pass, never fail.
//   - JVM strings are immutable, so the text must exist at least twice at
//     one instant (the decode buffer and the `String` handed to the
//     repository), and both `InMemoryVaultRepository` and
//     `VaultRepositoryImpl` then re-encode the body to hash it. With the
//     JVM's compact Latin-1 strings that is ~3x the file size for ASCII
//     content; without compaction (`-XX:-CompactStrings`, or ART's
//     `char[]`-backed builders) ~5x. The literal "+25 %" in the criterion is
//     therefore not reachable by any implementation that hands the
//     repository a `String`; [ALLOCATION_BOUND_MULTIPLIER] is the "generous
//     bound" the criterion allows for, sized so a 5x implementation passes
//     and the 8x-and-up shape it guards against fails.

package app.skein.core.vault.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import us.aherrera.skein.testing.InMemoryVaultRepository
import java.io.ByteArrayInputStream

public class ImportTextMemorySmokeTest {
    @Test
    public fun `a 10 MB plain text import stays within the allocation smoke bound`() =
        runTest {
            val bytes =
                ByteArray(FILE_SIZE) { i ->
                    if (i % 80 ==
                        79
                    ) {
                        '\n'.code.toByte()
                    } else {
                        ('a' + (i % 26)).code.toByte()
                    }
                }
            val repo = InMemoryVaultRepository()
            val service = ImportServiceImpl(repo)
            val runtime = Runtime.getRuntime()

            settleHeap(runtime)
            val before = usedHeap(runtime)
            val result = service.importText("big.txt", "text/plain", ByteArrayInputStream(bytes), personaId = null)
            val after = usedHeap(runtime)

            val delta = after - before
            assertThat(delta).isAtMost(FILE_SIZE.toLong() * ALLOCATION_BOUND_MULTIPLIER)
            assertThat(repo.getDocument(result.documentId)!!.bodyMd!!.length).isEqualTo(FILE_SIZE)
        }

    private fun usedHeap(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    private fun settleHeap(runtime: Runtime) {
        repeat(3) {
            runtime.gc()
            Thread.sleep(20)
        }
    }

    private companion object {
        const val FILE_SIZE: Int = 10 * 1024 * 1024
        const val ALLOCATION_BOUND_MULTIPLIER: Long = 6
    }
}
