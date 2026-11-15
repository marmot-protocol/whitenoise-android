package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardVisibleSelectionTest {
    @Test fun folderAndQueryToggleOnlyVisibleChatsPreservingOtherSelections() {
        val initial = listOf("other-folder", "visible-a")
        val selected = forwardSelectionAfterVisibleToggle(initial, listOf("VISIBLE-A", "visible-b", "visible-b"))
        assertEquals(listOf("other-folder", "visible-a", "visible-b"), selected)
        assertEquals(
            listOf("other-folder"),
            forwardSelectionAfterVisibleToggle(selected, listOf("visible-a", "visible-b")),
        )
    }

    @Test fun emptyResultDoesNotChangeSelection() {
        assertEquals(listOf("other"), forwardSelectionAfterVisibleToggle(listOf("other"), emptyList()))
    }
}
