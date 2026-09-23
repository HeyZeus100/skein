// JVM regression for skein-x0ro.
//
// `IndexStoreImpl` cannot run its real SQL against sqlite-vec/FTS5 on the
// host JVM — the instrumented `IndexStoreContractTest.
// replaceChunks_deletes_old_chunk_ids` case (run against the real
// libskein_sqlite.so) is what actually exercises SQLite's rowid-reuse
// behaviour that caused the bug. `FakeSkeinSQLiteNative` models only a
// handful of PRAGMA scalars, never real table contents (see its file
// header note by skein-p8rn), so it cannot reproduce "the table went
// empty, so the next auto-assigned rowid collided with a just-deleted
// chunk's id".
//
// What IS checkable here, using `SkeinSQLiteDriver`'s fake-native test
// constructor (the same one `SkeinSQLiteDriverTest` uses) to build a real
// `IndexStoreImpl` on the JVM: `replaceChunks` hands out ids from its own
// `nextChunkId` counter rather than reading a value back off the
// statement (the old `INSERT ... RETURNING id` shape) — so two
// `replaceChunks` calls against the same doc never produce overlapping
// ids, regardless of what the underlying table thinks it currently holds.

package app.skein.core.vault.index

import app.skein.core.model.NewChunk
import app.skein.core.vault.db.FakeSkeinSQLiteNative
import app.skein.core.vault.db.SkeinSQLiteDriver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

public class IndexStoreImplChunkIdTest {
    @Test
    public fun `replaceChunks never reissues an id already handed out for the same doc`(): Unit =
        runTest {
            val connection = SkeinSQLiteDriver(FakeSkeinSQLiteNative()).open("test.db")
            val store = IndexStoreImpl(connection)

            val firstIds =
                store.replaceChunks(
                    docId = "doc-1",
                    chunks =
                        listOf(
                            NewChunk(ord = 0, text = "alpha", tokenCount = 1),
                            NewChunk(ord = 1, text = "beta", tokenCount = 1),
                        ),
                    embedderId = "fake",
                    embedderVersion = 1,
                )
            // A real DELETE would empty `chunks` for this doc here — the
            // exact moment SQLite's default rowid rule ("largest existing
            // ROWID + 1, or 1 if empty") would hand the very next INSERT
            // an id from `firstIds`. `nextChunkId` must not care.
            val secondIds =
                store.replaceChunks(
                    docId = "doc-1",
                    chunks = listOf(NewChunk(ord = 0, text = "gamma", tokenCount = 1)),
                    embedderId = "fake",
                    embedderVersion = 1,
                )

            assertThat(firstIds).containsNoneIn(secondIds)
        }
}
