// skein-nxk (E4.I3): an `IInferenceCallback` that records what the client saw.
//
// A local `Stub` rather than a mock: the tests assert on the CONTRACT — at most
// one terminal callback per request, the stop reason, the `dropped` count — and
// a recorded call list is the direct expression of that.

package app.skein.inference.service

import android.os.IBinder
import app.skein.ipc.GenStats
import app.skein.ipc.IInferenceCallback

class RecordingCallback : IInferenceCallback.Stub() {
    val tokenBatches = mutableListOf<Array<String>>()
    val droppedCounts = mutableListOf<Int>()
    val done = mutableListOf<Pair<Int, GenStats>>()
    val errors = mutableListOf<Triple<Int, Int, String?>>()

    override fun onTokens(
        requestId: Int,
        pieces: Array<out String>?,
        ids: IntArray?,
        dropped: Int,
    ) {
        tokenBatches += (pieces ?: emptyArray()).map { it }.toTypedArray()
        droppedCounts += dropped
    }

    override fun onDone(
        requestId: Int,
        stats: GenStats,
    ) {
        done += requestId to stats
    }

    override fun onError(
        requestId: Int,
        code: Int,
        message: String?,
    ) {
        errors += Triple(requestId, code, message)
    }

    override fun asBinder(): IBinder = this
}
