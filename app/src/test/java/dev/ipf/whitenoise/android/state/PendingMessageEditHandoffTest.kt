package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMessageEditHandoffTest {
    @Test
    fun submittedBeforeConfirmationWaitsAndPublishesOnlyOnceAgainstCanonicalId() {
        val handoff = PendingMessageEditHandoff()
        handoff.begin("local-token")

        assertEquals(PendingMessageEditHandoff.Submission.Deferred, handoff.submit("local-token", "local-token", "revision"))
        assertNull(handoff.confirm("local-token", "local-token", ready = true))
        assertNull(handoff.confirm("local-token", "event-id", ready = false))
        assertEquals("revision", handoff.confirm("local-token", "event-id", ready = true))
        assertNull(handoff.confirm("local-token", "event-id", ready = true))
        assertFalse(handoff.hasSession("local-token"))
    }

    @Test
    fun confirmationWhileTypingRebindsWithoutReplacingTheComposerSession() {
        val handoff = PendingMessageEditHandoff()
        handoff.begin("local-token")

        assertNull(handoff.confirm("local-token", "event-id", ready = true))
        assertTrue(handoff.hasSession("local-token"))
        assertEquals(
            PendingMessageEditHandoff.Submission.Publish("event-id"),
            handoff.submit("local-token", "local-token", "typed after confirmation"),
        )
        assertFalse(handoff.hasSession("local-token"))
    }

    @Test
    fun acceptedPendingAndFailedOriginalRetainLatestRevisionForRetryHandoff() {
        val handoff = PendingMessageEditHandoff()
        handoff.begin("local-token")
        handoff.submit("local-token", "local-token", "first")
        assertNull(handoff.confirm("local-token", "event-id", ready = false))
        // The original may fail before durable acceptance. A retry still owns
        // the same client token, and the latest revision wins at handoff.
        handoff.begin("local-token")
        handoff.submit("local-token", "local-token", "second")

        assertEquals("second", handoff.confirm("local-token", "event-id", ready = true))
    }

    @Test
    fun cancelBeforeSubmissionPreservesTheOriginalDraftAndLeavesNoQueuedEdit() {
        val handoff = PendingMessageEditHandoff()
        handoff.begin("local-token")
        handoff.cancel("local-token")

        assertFalse(handoff.hasSession("local-token"))
        assertNull(handoff.confirm("local-token", "event-id", ready = true))
    }

    @Test
    fun sameTokenInAnotherConversationCannotConsumeTheRevision() {
        val handoff = PendingMessageEditHandoff()
        handoff.begin("account-a|group-a|local-token")
        handoff.submit("account-a|group-a|local-token", "local-token", "private revision")

        assertNull(handoff.confirm("account-b|group-b|local-token", "other-event", ready = true))
        assertEquals(
            "private revision",
            handoff.confirm("account-a|group-a|local-token", "own-event", ready = true),
        )
    }

    @Test
    fun signOutDropsOnlyThatAccountsPendingRevision() {
        val handoff = PendingMessageEditHandoff()
        handoff.begin("account-a|group|token-a")
        handoff.submit("account-a|group|token-a", "token-a", "private a")
        handoff.begin("account-b|group|token-b")
        handoff.submit("account-b|group|token-b", "token-b", "private b")

        handoff.removeAccount("account-a")

        assertNull(handoff.confirm("account-a|group|token-a", "event-a", ready = true))
        assertEquals("private b", handoff.confirm("account-b|group|token-b", "event-b", ready = true))
    }
}
