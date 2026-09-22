package app.skein.notify

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * E6.I18: Basic tests for [IndexingNotifier].
 *
 * AC: worker progress emits a notification with VISIBILITY_SECRET and
 * contentText == "12 of 40 documents".
 */
@RunWith(RobolectricTestRunner::class)
class IndexingNotifierTest {
    @Test
    fun channelIdConstants() {
        // Verify channel IDs are properly defined
        assert(Channels.INDEXING_CHANNEL_ID == "indexing")
        assert(Channels.INDEXING_TAG == "indexing_progress")
        assert(Channels.INDEXING_ID == 1)

        assert(Channels.MODELS_CHANNEL_ID == "models")
        assert(Channels.MODELS_TAG == "models_progress")
        assert(Channels.MODELS_ID == 2)
    }
}
