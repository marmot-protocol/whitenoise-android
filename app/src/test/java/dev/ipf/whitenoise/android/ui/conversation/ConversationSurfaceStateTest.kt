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

    /** The chat's composer settings route survives recreation for the same conversation only. */
    @Test
    fun savedComposerSettingsRouteReturnsToItsOwnConversation() {
        val original = ConversationSurfaceState().apply { showComposerSettings.value = true }
        val saver = conversationSurfaceStateSaver(accountRef = "account-a", chatId = "chat-a", runtimeGeneration = 1)
        val saved = with(saver) { requireNotNull(SaverScope { true }.save(original)) }

        val restored = requireNotNull(saver.restore(saved))
        assertTrue(restored.showComposerSettings.value)
        assertFalse(restored.showDetails.value)
        assertFalse(
            requireNotNull(
                conversationSurfaceStateSaver(
                    accountRef = "account-a",
                    chatId = "chat-b",
                    runtimeGeneration = 1,
                ).restore(saved),
            ).showComposerSettings.value,
        )
        // Bundles written before the route existed restore to the conversation.
        assertFalse(requireNotNull(saver.restore((saved as List<*>).take(7))).showComposerSettings.value)
    }
}
