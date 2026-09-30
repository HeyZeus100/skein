package app.skein.ipc

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbedderRequestParcelTest {
    @Test
    fun embeddingIdentitySurvivesParcelAndLegacyDefaultIsZero() {
        val legacy = EmbedRequest(listOf("synthetic"), sessionEpoch = 19)
        assertEquals(0, roundTrip(legacy).requestId)
        assertEquals(
            legacy.copy(requestId = 42, isQuery = true),
            roundTrip(legacy.copy(requestId = 42, isQuery = true)),
        )
    }

    @Test
    fun otherRequestKindsPreserveEpochAndId() {
        val binding = ManifestBinding("synthetic", 2, emptyList(), null)
        val load = EmbedderLoadRequest(binding, "onnx", null, null, 1, 19, 42)
        val entities = ExtractEntitiesRequest("synthetic", listOf("thing"), sessionEpoch = 19, requestId = 43)
        val rerank = RerankRequest("query", listOf("candidate"), sessionEpoch = 19, requestId = 44)
        val count = EmbedderTokenCountRequest("synthetic", 19, 45)
        assertEquals(load, roundTrip(load))
        assertEquals(entities, roundTrip(entities))
        assertEquals(rerank, roundTrip(rerank))
        assertEquals(count, roundTrip(count))
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            parcel.readParcelable<T>(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }
}
