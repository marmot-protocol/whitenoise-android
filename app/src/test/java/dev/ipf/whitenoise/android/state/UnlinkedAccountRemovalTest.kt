package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlinkedAccountRemovalTest {
    /** A failed removal that moved the app off the account in view puts that account back. */
    @Test
    fun aFailedRemovalRestoresTheAccountThatWasInView() {
        assertTrue(shouldRestoreAccountInView("main", activeNow = "unlinked", saved = listOf("main", "unlinked")))
        assertTrue(shouldRestoreAccountInView(inView = "main", activeNow = null, saved = listOf("main")))
    }

    /** Nothing is restored from the sign-in screen, when the account is still in view, or once it is gone. */
    @Test
    fun nothingIsRestoredWithoutAnotherAccountToReturnTo() {
        assertFalse(shouldRestoreAccountInView(inView = null, activeNow = "unlinked", saved = listOf("unlinked")))
        assertFalse(shouldRestoreAccountInView(inView = "main", activeNow = "main", saved = listOf("main")))
        assertFalse(shouldRestoreAccountInView(inView = "main", activeNow = "unlinked", saved = listOf("unlinked")))
    }

    /** With linked devices the account's own chat message can carry attachments to fetch; without them it cannot. */
    @Test
    fun anOwnMessageMayCarryAttachmentsOnlyWithLinkedDevices() {
        assertTrue(receivedChatMayCarryAttachments(MARMOT_CHAT_EVENT_KIND, fromSelf = false, linkedDevices = false))
        assertTrue(receivedChatMayCarryAttachments(MARMOT_CHAT_EVENT_KIND, fromSelf = true, linkedDevices = true))
        assertFalse(receivedChatMayCarryAttachments(MARMOT_CHAT_EVENT_KIND, fromSelf = true, linkedDevices = false))
        assertFalse(receivedChatMayCarryAttachments(7uL, fromSelf = false, linkedDevices = true))
    }
}
