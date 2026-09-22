package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSurfaceStateTest {
    /** Restores the details destination only when the saved owner tuple still identifies the active conversation. */
    @Test
    fun savedDetailsRouteCannotCrossConversationIdentity() {
        val original = ConversationSurfaceState().apply { showDetails.value = true }
        val saver = conversationSurfaceStateSaver(accountRef = "account-a", chatId = "chat-a", runtimeGeneration = 1)
        val saved = with(saver) { requireNotNull(SaverScope { true }.save(original)) }

        assertTrue(requireNotNull(saver.restore(saved)).showDetails.value)
        assertFalse(
            requireNotNull(
                conversationSurfaceStateSaver(
                    accountRef = "account-a",
                    chatId = "chat-b",
                    runtimeGeneration = 1,
                ).restore(saved),
            ).showDetails.value,
        )
        assertFalse(
            requireNotNull(
                conversationSurfaceStateSaver(
                    accountRef = "account-b",
                    chatId = "chat-a",
                    runtimeGeneration = 1,
                ).restore(saved),
            ).showDetails.value,
        )
        assertFalse(
            requireNotNull(
                conversationSurfaceStateSaver(
                    accountRef = "account-a",
                    chatId = "chat-a",
                    runtimeGeneration = 2,
                ).restore(saved),
            ).showDetails.value,
        )
    }
}
