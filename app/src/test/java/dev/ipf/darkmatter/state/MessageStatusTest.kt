package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageStatusTest {
    @Test
    fun outgoingIndicatorTracksPendingConfirmedAndFailedStates() {
        assertEquals(OutgoingMessageIndicator.Sending, MessageStatus.Pending.outgoingIndicator())
        assertEquals(OutgoingMessageIndicator.Sent, MessageStatus.Sent.outgoingIndicator())
        assertEquals(OutgoingMessageIndicator.Sent, MessageStatus.Received.outgoingIndicator())
        assertEquals(OutgoingMessageIndicator.Failed, MessageStatus.Failed.outgoingIndicator())
        assertNull(MessageStatus.Streaming.outgoingIndicator())
    }
}
