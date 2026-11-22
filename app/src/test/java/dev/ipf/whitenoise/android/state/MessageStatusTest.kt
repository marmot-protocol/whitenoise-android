package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageStatusTest {
    /** Pending shows sending, sent and received show sent, and failed shows failed. */
    @Test
    fun outgoingIndicatorTracksPendingConfirmedAndFailedStates() {
        assertEquals(OutgoingMessageIndicator.Sending, MessageStatus.Pending.outgoingIndicator())
        assertEquals(OutgoingMessageIndicator.Sent, MessageStatus.Sent.outgoingIndicator())
        assertEquals(OutgoingMessageIndicator.Sent, MessageStatus.Received.outgoingIndicator())
        assertEquals(OutgoingMessageIndicator.Failed, MessageStatus.Failed.outgoingIndicator())
    }
}
