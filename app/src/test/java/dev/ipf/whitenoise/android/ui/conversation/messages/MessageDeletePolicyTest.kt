package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeletePolicyTest {
    @Test
    fun deletingAnotherMembersMessageRequiresModeratorConfirmation() {
        assertTrue(requiresModeratorDeleteConfirmation(mine = false, selfIsAdmin = true))
        assertFalse(requiresModeratorDeleteConfirmation(mine = true, selfIsAdmin = true))
        assertFalse(requiresModeratorDeleteConfirmation(mine = false, selfIsAdmin = false))
    }
}
