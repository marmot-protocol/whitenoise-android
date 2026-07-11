package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageBubbleMediaReferenceCoverageTest {
    @Test
    fun mediaReferenceRememberIsKeyedByPerMessageEntry() {
        val source = messageBubbleSource().readText()

        assertTrue(
            "message bubble must avoid keying media parsing on the whole controller.mediaReferences map",
            "val perMessageMediaReferences = controller.mediaReferences[record.messageIdHex]" in source &&
                "remember(record.tags, record.messageIdHex, perMessageMediaReferences)" in source &&
                "remember(record.tags, record.messageIdHex, controller.mediaReferences)" !in source,
        )
    }

    private fun messageBubbleSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MessageBubble.kt source file")
}
