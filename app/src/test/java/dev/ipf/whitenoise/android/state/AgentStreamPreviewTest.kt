package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStreamPreviewTest {
    @Test
    fun appendCappedAgentStreamPreview_keepsShortTranscript() {
        val text = StringBuilder()

        appendCappedAgentStreamPreview(text, "hello ", maxChars = 16)
        appendCappedAgentStreamPreview(text, "world", maxChars = 16)

        assertEquals("hello world", text.toString())
    }

    @Test
    fun appendCappedAgentStreamPreview_keepsTailWhenTranscriptExceedsCap() {
        val text = StringBuilder("abcdef")

        appendCappedAgentStreamPreview(text, "ghijkl", maxChars = 8)

        assertEquals("efghijkl", text.toString())
    }

    @Test
    fun appendCappedAgentStreamPreview_keepsTailOfOversizedSingleChunk() {
        val text = StringBuilder("old")

        appendCappedAgentStreamPreview(text, "0123456789", maxChars = 4)

        assertEquals("6789", text.toString())
    }
}
