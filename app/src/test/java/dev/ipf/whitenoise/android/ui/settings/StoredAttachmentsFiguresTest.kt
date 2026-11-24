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
    /** The overview's row answers even for a fully loaded list, since MarmotKit counts a shared file once. */
    @Test fun overviewRowIsPreferred() {
        val list = StoredAttachmentsUiState(items = listOf(attachment("a", 10), attachment("b", 5)), complete = true)
        val storage = ManageStorageUiState(chats = listOf(chat("g", 12)))
        assertEquals(12L to 12L, chatFigures(storage, list, "g"))
    }

    /** A fully loaded list is summed when the overview has not loaded the chat. */
    @Test fun completeListIsSummedWithoutAnOverviewRow() {
        val list = StoredAttachmentsUiState(items = listOf(attachment("a", 10), attachment("b", 5)), complete = true)
        assertEquals(2L to 15L, chatFigures(ManageStorageUiState(chats = listOf(chat("other", 40))), list, "g"))
    }

    /** While the list still pages, the overview's row answers. */
    @Test fun partialListUsesTheOverviewRow() {
        val list = StoredAttachmentsUiState(items = listOf(attachment("a", 10)), complete = false)
        val storage = ManageStorageUiState(chats = listOf(chat("g", 40)))
        assertEquals(40L to 40L, chatFigures(storage, list, "g"))
    }

    /** A list emptied under an open confirmation is unknown, never zero. */
    @Test fun emptiedListIsUnknown() {
        assertNull(chatFigures(ManageStorageUiState(), StoredAttachmentsUiState(complete = true), "g"))
    }

    /** A chat the overview has not loaded is unknown, never zero. */
    @Test fun chatMissingFromTheOverviewIsUnknown() {
        val list = StoredAttachmentsUiState(items = listOf(attachment("a", 10)), complete = false)
        assertNull(chatFigures(ManageStorageUiState(chats = listOf(chat("other", 40))), list, "g"))
    }
}
