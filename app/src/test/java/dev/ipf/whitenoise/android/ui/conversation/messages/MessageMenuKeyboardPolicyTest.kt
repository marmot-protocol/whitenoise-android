package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMenuKeyboardPolicyTest {
    /** Checks that a closed keyboard creates no hide or restore intent. */
    @Test fun closedKeyboardNeverHidesOrRestores() {
        val policy = MessageMenuKeyboardPolicy()
        assertFalse(policy.open(false, null, null))
        assertNull(policy.close(null, null))
    }

    /** Checks that dismissal restores the composer once when its reply target is unchanged. */
    @Test fun dismissalRestoresOnceAndKeepsAnExistingReply() {
        val policy = MessageMenuKeyboardPolicy()
        assertTrue(policy.open(true, "existing-reply", null))
        assertEquals(MessageMenuKeyboardOwner.Composer, policy.close("existing-reply", null))
        assertNull(policy.close("existing-reply", null))
    }

    /** Checks that reopening captures an in-flight search restore before its inset becomes visible. */
    @Test fun reopeningBeforeKeyboardReturnTakesBackThePendingShow() {
        val policy = MessageMenuKeyboardPolicy()
        policy.open(true, null, null, MessageMenuKeyboardOwner.Search)
        assertEquals(MessageMenuKeyboardOwner.Search, policy.close(null, null))

        // The requested show has not produced a visible inset yet.
        assertTrue(
            policy.open(
                false,
                null,
                null,
                MessageMenuKeyboardOwner.Search,
                restoreInFlight = true,
            ),
        )
        assertEquals(MessageMenuKeyboardOwner.Search, policy.close(null, null))
        assertNull(policy.close(null, null))
    }

    /** Checks that entering selection consumes an in-flight keyboard restore. */
    @Test fun selectingDuringKeyboardReturnDiscardsThePendingShow() {
        val policy = MessageMenuKeyboardPolicy()
        assertTrue(policy.open(false, null, null, restoreInFlight = true))
        assertNull(policy.close(null, null, selectionMode = true))
        assertFalse(policy.open(false, null, null))
    }

    /** Checks that new reply or edit targets take over keyboard restoration from the menu. */
    @Test fun replyAndEditOwnTheirSingleKeyboardRaise() {
        val policy = MessageMenuKeyboardPolicy()
        policy.open(true, null, null)
        assertNull(policy.close("reply", null))
        policy.open(true, null, null)
        assertNull(policy.close(null, "edit"))
        assertFalse(policy.pendingRestore)
    }

    /** Checks explicit cancellation and a fresh controller policy leave no restore intent. */
    @Test fun leavingBackgroundingAndChangingControllerDiscardTheIntent() {
        val policy = MessageMenuKeyboardPolicy()
        repeat(3) {
            policy.open(true, null, null)
            policy.cancel()
            assertFalse(policy.pendingRestore)
            assertNull(policy.close(null, null))
        }
        assertNull(MessageMenuKeyboardPolicy().close(null, null))
    }

    /** Checks search ownership and restoration when existing reply or edit targets are cleared. */
    @Test fun searchRestoresItsOwnEditorAndClearedTargetsAreDismissals() {
        val policy = MessageMenuKeyboardPolicy()
        policy.open(true, "reply", null, MessageMenuKeyboardOwner.Search)
        assertEquals(MessageMenuKeyboardOwner.Search, policy.close(null, null))
        policy.open(true, null, "edit")
        assertEquals(MessageMenuKeyboardOwner.Composer, policy.close(null, null))
        assertNull(policy.close(null, null))
    }

    /** Checks that selection discards restoration and cannot restore on a later close. */
    @Test fun selectConsumesRestoreWithoutReopeningTheKeyboard() {
        val policy = MessageMenuKeyboardPolicy()
        policy.open(true, null, null)
        assertNull(policy.close(null, null, selectionMode = true))
        assertFalse(policy.pendingRestore)
        assertNull(policy.close(null, null))
    }

    /** Checks synchronous pause cancellation and consumption of its resume marker. */
    @Test fun pauseCancellationCanBeReadWithoutACompositionFrame() {
        val policy = MessageMenuKeyboardPolicy()
        policy.open(true, null, null)
        policy.pause()
        assertTrue(policy.resumeRestoreCancelled)
        assertNull(policy.close(null, null))
        policy.resumeConsumed()
        assertFalse(policy.resumeRestoreCancelled)
    }
}
