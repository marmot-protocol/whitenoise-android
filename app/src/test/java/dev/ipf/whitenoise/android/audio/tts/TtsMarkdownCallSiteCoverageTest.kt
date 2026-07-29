package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TtsMarkdownCallSiteCoverageTest {
    @Test
    fun everyConversationTtsPathUsesTheActiveMarkdownProjection() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val bubble = source("ui/conversation/messages/MessageBubble.kt")

        assertTrue(conversation.contains("projectTtsSpeakableEntry("))
        assertTrue(bubble.contains("projectTtsSpeakableEntry("))
        assertTrue(conversation.contains("controller.editsByTarget[record.messageIdHex]?.latestText"))
        assertTrue(bubble.contains("controller.editsByTarget[entryRecord.messageIdHex]?.latestText"))

        assertEquals(4, conversation.windowed("ttsEntry(".length).count { it == "ttsEntry(" })
        assertEquals(3, bubble.windowed("ttsEntry(".length).count { it == "ttsEntry(" })
        val directEntryConstructor = Regex("(?<![A-Za-z])TtsSpeakableEntry\\(")
        assertFalse(directEntryConstructor.containsMatchIn(conversation))
        assertFalse(directEntryConstructor.containsMatchIn(bubble))
    }

    private fun source(relativePath: String): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Missing source file: $relativePath")
}
