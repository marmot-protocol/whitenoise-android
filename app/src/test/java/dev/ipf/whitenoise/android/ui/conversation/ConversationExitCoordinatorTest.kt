package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationExitCoordinatorTest {
    @Test
    fun exitReleasesComposerInputBeforeRoutingExactlyOnce() {
        val events = mutableListOf<String>()
        val draft = TextFieldValue("unsent draft", TextRange(3, 8))
        val session = ComposerSession(replyMessageId = "reply-1", editMessageId = "edit-1")
        val coordinator =
            ConversationExitCoordinator(
                clearFocus = { events += "focus-cleared" },
                hideIme = { events += "ime-hidden" },
                routeToChatList = { events += "routed" },
            )

        coordinator.exit()
        coordinator.exit()

        assertEquals(listOf("focus-cleared", "ime-hidden", "routed"), events)
        assertEquals(TextFieldValue("unsent draft", TextRange(3, 8)), draft)
        assertEquals(ComposerSession(replyMessageId = "reply-1", editMessageId = "edit-1"), session)
    }

    private data class ComposerSession(
        val replyMessageId: String?,
        val editMessageId: String?,
    )
}
