package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for issue #264: a short text message could be silently
 * dropped — the input cleared but no optimistic bubble appeared and no error
 * was shown.
 *
 * Root cause: the UI cleared the composer synchronously the instant it
 * dispatched the (async) send coroutine, while `ConversationController.send()`
 * could bail at a guard (no account bound, or membership not yet verified
 * during the `refreshMembers()` load window) before committing the optimistic
 * record. The clear and the commit disagreed, so the text vanished.
 *
 * The fix makes the SAME predicate gate both decisions: [canAcceptTextSend] is
 * the single answer to "will this send commit an optimistic record?", and the
 * UI only clears its input after the controller confirms acceptance. These
 * tests pin the predicate so the two paths can never drift apart again.
 */
class CanAcceptTextSendTest {
    @Test
    fun acceptsWhenAccountBoundTextPresentAndCanSend() {
        assertTrue(canAcceptTextSend(accountRef = "acct", trimmed = "hi", canSend = true))
    }

    @Test
    fun rejectsWhenNoAccountBound() {
        // No active account ref yet → send() returns early. Input must be kept.
        assertFalse(canAcceptTextSend(accountRef = null, trimmed = "hi", canSend = true))
    }

    @Test
    fun rejectsWhenTextBlank() {
        // Caller passes already-trimmed text; empty means nothing to send.
        assertFalse(canAcceptTextSend(accountRef = "acct", trimmed = "", canSend = true))
    }

    @Test
    fun rejectsWhenMembershipNotYetVerified() {
        // The composer is intentionally shown during the membership load
        // window; a send fired before `canSendMessages` flips true was the
        // intermittent silent-loss path. It must be rejected (so the toast
        // fires and the input is preserved), never silently accepted.
        assertFalse(canAcceptTextSend(accountRef = "acct", trimmed = "hi", canSend = false))
    }

    @Test
    fun rejectsWhenEverythingFails() {
        assertFalse(canAcceptTextSend(accountRef = null, trimmed = "", canSend = false))
    }
}
