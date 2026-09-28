package app.skein.feature.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImportedLinkNodeTest {
    @Test
    fun imported_unresolved_links_are_not_document_navigation_targets() {
        val id = "import:018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b:folder/ambiguous"
        assertEquals(GraphNodeKind.UNRESOLVED_TITLE, GraphNodeIds.kindOf(id))
        assertFalse(GraphNodeIds.isDocument(id))
        assertEquals("folder/ambiguous", GraphNodeIds.sentinelLabel(id))
    }

    @Test
    fun deleted_UUID_targets_show_a_readable_label_without_internal_IDs() {
        val id = "import:018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5b:018f2b6e-6c3a-7c3e-8f2a-6b1e2d3c4a5c"
        assertEquals("Unavailable note", GraphNodeIds.sentinelLabel(id))
        assertFalse(GraphNodeIds.isDocument(id))
    }
}
