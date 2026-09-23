// `E10.I2` (skein-0j1): proves `RecordingTabController` records every
// `TabController` call, in order, with a deterministic minted id when no
// delegate is supplied — so a shell/tab-driving test can assert "what did
// the surface under test ask the tab controller to do" without a real,
// Compose-backed `TabsState`.

package app.skein.testing

import org.junit.Assert.assertEquals
import org.junit.Test

public class RecordingTabControllerTest {
    @Test
    public fun records_actions_in_call_order() {
        val controller = RecordingTabController()

        val tabId = controller.openPreview(docId = "doc-1", title = "Note", kind = RecordedTabKind.NOTE)
        controller.pin(tabId)
        controller.close(tabId)

        assertEquals(
            listOf(
                TabAction.OpenPreview("doc-1", "Note", RecordedTabKind.NOTE),
                TabAction.Pin(tabId),
                TabAction.Close(tabId),
            ),
            controller.actions,
        )
    }

    @Test
    public fun mints_deterministic_ids_when_no_delegate_is_supplied() {
        val controller = RecordingTabController()

        val first = controller.openPreview(docId = "doc-1", title = "Note", kind = RecordedTabKind.NOTE)
        val second = controller.openPinned(docId = "doc-2", title = "Chat", kind = RecordedTabKind.CHAT)

        assertEquals("tab-1", first)
        assertEquals("tab-2", second)
    }

    @Test
    public fun forwards_every_call_to_a_supplied_delegate() {
        val delegate = RecordingTabController()
        val controller = RecordingTabController(delegate = delegate)

        controller.openPreview(docId = "doc-1", title = "Note", kind = RecordedTabKind.NOTE)

        assertEquals(1, delegate.actions.size)
    }
}
