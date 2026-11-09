package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.attachment
import dev.ipf.whitenoise.android.state.chat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a chat's Remove all confirmation takes its count and size from. */
class StoredAttachmentsFiguresTest {
    /** A fully loaded list is summed, even when the overview holds an older row for the chat. */
    @Test fun completeListIsSummed() {
        val list = StoredAttachmentsUiState(items = listOf(attachment("a", 10), attachment("b", 5)), complete = true)
        val storage = ManageStorageUiState(chats = listOf(chat("g", 999)))
        assertEquals(2L to 15L, chatFigures(storage, list, "g"))
    }

    /** While the list still pages, the overview's row answers. */
    @Test fun partialListUsesTheOverviewRow() {
        val list = StoredAttachmentsUiState(items = listOf(attachment("a", 10)), complete = false)
        val storage = ManageStorageUiState(chats = listOf(chat("g", 40)))
        assertEquals(40L to 40L, chatFigures(storage, list, "g"))
    }

    /** A chat the overview has not loaded is unknown, never zero. */
    @Test fun chatMissingFromTheOverviewIsUnknown() {
        val list = StoredAttachmentsUiState(items = listOf(attachment("a", 10)), complete = false)
        assertNull(chatFigures(ManageStorageUiState(chats = listOf(chat("other", 40))), list, "g"))
    }
}
