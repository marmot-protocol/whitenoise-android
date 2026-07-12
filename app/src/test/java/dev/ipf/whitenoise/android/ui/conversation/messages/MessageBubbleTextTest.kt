package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun longMessageMeasurementsResetWhenBubbleWidthChanges() {
        val source = messageBubbleSource().readText().replace(Regex("\\s+"), " ")

        assertTrue(
            "plain-text full layout must be invalidated when the bubble width changes",
            "remember(record.messageIdHex, bodyTextToRender, bubbleColumnMaxWidth) { mutableStateOf<TextLayoutResult?>(null) }" in source,
        )
        assertTrue(
            "markdown overflow must be invalidated when the bubble width changes",
            "remember(record.messageIdHex, bodyTextToRender, bubbleColumnMaxWidth) { mutableStateOf(false) }" in source,
        )
    }

    private fun messageBubbleSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MessageBubble.kt source file")
}
