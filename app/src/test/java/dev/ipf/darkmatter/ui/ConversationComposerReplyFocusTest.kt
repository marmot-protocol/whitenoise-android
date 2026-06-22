package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationComposerReplyFocusTest {
    @Test
    fun firstReplyTargetFocusesComposerAndRecordsTarget() {
        val update =
            nextComposerReplyFocusUpdate(
                replyMessageId = "reply-a",
                focusedReplyMessageId = null,
                editingMessageId = null,
            )

        assertTrue(update.shouldFocusComposer)
        assertEquals("reply-a", update.focusedReplyMessageId)
    }

    @Test
    fun sameReplyTargetDoesNotRefocusOnRecomposition() {
        val update =
            nextComposerReplyFocusUpdate(
                replyMessageId = "reply-a",
                focusedReplyMessageId = "reply-a",
                editingMessageId = null,
            )

        assertFalse(update.shouldFocusComposer)
        assertEquals("reply-a", update.focusedReplyMessageId)
    }

    @Test
    fun dismissingReplyClearsTargetSoSameMessageCanFocusAgain() {
        val dismissed =
            nextComposerReplyFocusUpdate(
                replyMessageId = null,
                focusedReplyMessageId = "reply-a",
                editingMessageId = null,
            )
        val selectedAgain =
            nextComposerReplyFocusUpdate(
                replyMessageId = "reply-a",
                focusedReplyMessageId = dismissed.focusedReplyMessageId,
                editingMessageId = null,
            )

        assertFalse(dismissed.shouldFocusComposer)
        assertEquals(null, dismissed.focusedReplyMessageId)
        assertTrue(selectedAgain.shouldFocusComposer)
        assertEquals("reply-a", selectedAgain.focusedReplyMessageId)
    }

    @Test
    fun editingDefersReplyFocusUntilEditingEnds() {
        val whileEditing =
            nextComposerReplyFocusUpdate(
                replyMessageId = "reply-a",
                focusedReplyMessageId = null,
                editingMessageId = "edit-a",
            )
        val afterEditing =
            nextComposerReplyFocusUpdate(
                replyMessageId = "reply-a",
                focusedReplyMessageId = whileEditing.focusedReplyMessageId,
                editingMessageId = null,
            )

        assertFalse(whileEditing.shouldFocusComposer)
        assertEquals(null, whileEditing.focusedReplyMessageId)
        assertTrue(afterEditing.shouldFocusComposer)
        assertEquals("reply-a", afterEditing.focusedReplyMessageId)
    }

    @Test
    fun switchingReplyTargetsFocusesNewTarget() {
        val update =
            nextComposerReplyFocusUpdate(
                replyMessageId = "reply-b",
                focusedReplyMessageId = "reply-a",
                editingMessageId = null,
            )

        assertTrue(update.shouldFocusComposer)
        assertEquals("reply-b", update.focusedReplyMessageId)
    }
}
