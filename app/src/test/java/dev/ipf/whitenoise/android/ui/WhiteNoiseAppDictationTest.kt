package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationMode
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteNoiseAppDictationTest {
    /** Verifies the origin composer is the sole control owner while its conversation is unobscured. */
    @Test
    fun `origin composer owns controls while it is visible`() {
        assertFalse(
            shouldShowConversationDictationPersistentControl(
                state = listening,
                originVisible = true,
                appLockScreenVisible = false,
            ),
        )
    }

    /** Verifies app-root controls take ownership after navigation hides the immutable origin. */
    @Test
    fun `persistent root control owns controls on other app surfaces`() {
        assertTrue(
            shouldShowConversationDictationPersistentControl(
                state = listening,
                originVisible = false,
                appLockScreenVisible = false,
            ),
        )
        assertTrue(
            shouldShowConversationDictationPersistentControl(
                state = ConversationDictationState.Processing(1L, target),
                originVisible = false,
                appLockScreenVisible = false,
            ),
        )
        assertTrue(
            shouldShowConversationDictationPersistentControl(
                state = ConversationDictationState.ReviewRequired(1L, target, "hello"),
                originVisible = false,
                appLockScreenVisible = false,
            ),
        )
    }

    /** Verifies privacy gates never expose dictation controls above lock or preflight surfaces. */
    @Test
    fun `privacy and preflight surfaces never expose the persistent root control`() {
        assertFalse(
            shouldShowConversationDictationPersistentControl(
                state = listening,
                originVisible = false,
                appLockScreenVisible = true,
            ),
        )
        assertFalse(
            shouldShowConversationDictationPersistentControl(
                state = ConversationDictationState.DisclosureRequired(1L, target),
                originVisible = false,
                appLockScreenVisible = false,
            ),
        )
    }

    private companion object {
        val target =
            ConversationDictationTarget(
                accountRef = "account",
                groupIdHex = "group",
                capturedDraft = TextFieldValue(),
                capturedDraftRevision = 1L,
                mode = ConversationDictationMode.InApp,
            )
        val listening = ConversationDictationState.Listening(1L, target, 10L)
    }
}
