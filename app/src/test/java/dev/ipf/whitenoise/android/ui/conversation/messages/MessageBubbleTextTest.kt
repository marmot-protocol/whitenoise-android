package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBubbleTextTest {
    @Test
    fun clippedMessageBodyTextTrimsAtLineEnd() {
        assertEquals("hello", clippedMessageBodyText("hello   world", 8))
    }

    @Test
    fun clippedMessageBodyTextClampsPastCurrentBodyLength() {
        assertEquals("short", clippedMessageBodyText("short", 99))
    }

    @Test
    fun clippedMessageBodyTextClampsNegativeLineEnd() {
        assertEquals("", clippedMessageBodyText("short", -1))
    }
}
