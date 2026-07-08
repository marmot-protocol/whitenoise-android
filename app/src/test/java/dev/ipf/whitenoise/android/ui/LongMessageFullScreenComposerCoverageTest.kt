package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LongMessageFullScreenComposerCoverageTest {
    @Test
    fun fullScreenLongMessageReaderReservesComposerSlot() {
        val body = messageFullScreenSource().readText().functionBody("MessageFullScreenView")

        assertTrue(
            "the full-screen long-message reader must expose a Scaffold bottomBar so the composer stays docked while the body scrolls",
            "bottomBar = bottomBar" in body,
        )
    }

    @Test
    fun expandedLongMessageUsesStandardConversationComposer() {
        val body = messageBubbleSource().readText().functionBody("MessageBubble")
        val fullScreenCall =
            body
                .substringAfter("MessageFullScreenView(")
                .substringBefore("if (emojiPickerOpen")

        assertTrue(
            "expanded long-message view must render the standard ComposerBar, not a separate ad-hoc input",
            "ComposerBar(" in fullScreenCall,
        )
        assertTrue(
            "tapping Reply in the expanded reader should keep the reader open and focus the in-reader composer",
            Regex("""onReply\s*=\s*\{\s*beginReply\(\)\s*\}""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(fullScreenCall),
        )
    }

    private fun messageFullScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageFullScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageFullScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MessageFullScreen.kt source file")

    private fun messageBubbleSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MessageBubble.kt source file")
}
