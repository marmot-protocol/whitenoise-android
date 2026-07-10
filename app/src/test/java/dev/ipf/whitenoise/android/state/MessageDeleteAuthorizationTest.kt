package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeleteAuthorizationTest {
    @Test
    fun adminCanDeleteAnotherMembersMessageForEveryone() {
        assertTrue(
            canDeleteMessageForEveryone(
                actionsEnabled = true,
                mine = false,
                selfIsAdmin = true,
                messageIdHex = "message-id",
                deleted = false,
            ),
        )
    }

    @Test
    fun nonAdminCannotDeleteAnotherMembersMessageForEveryone() {
        assertFalse(
            canDeleteMessageForEveryone(
                actionsEnabled = true,
                mine = false,
                selfIsAdmin = false,
                messageIdHex = "message-id",
                deleted = false,
            ),
        )
    }

    @Test
    fun ownMessageDeleteForEveryoneBehaviorIsUnchanged() {
        assertTrue(
            canDeleteMessageForEveryone(
                actionsEnabled = true,
                mine = true,
                selfIsAdmin = false,
                messageIdHex = "message-id",
                deleted = false,
            ),
        )
    }

    @Test
    fun unavailableMessagesCannotBeDeletedForEveryone() {
        assertFalse(
            canDeleteMessageForEveryone(
                actionsEnabled = false,
                mine = true,
                selfIsAdmin = true,
                messageIdHex = "message-id",
                deleted = false,
            ),
        )
        assertFalse(
            canDeleteMessageForEveryone(
                actionsEnabled = true,
                mine = true,
                selfIsAdmin = true,
                messageIdHex = "",
                deleted = false,
            ),
        )
        assertFalse(
            canDeleteMessageForEveryone(
                actionsEnabled = true,
                mine = true,
                selfIsAdmin = true,
                messageIdHex = "message-id",
                deleted = true,
            ),
        )
    }
}
