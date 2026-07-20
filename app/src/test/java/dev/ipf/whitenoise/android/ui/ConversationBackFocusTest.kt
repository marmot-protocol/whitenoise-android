package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.composer.shouldDismissComposerOnBack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBackFocusTest {
    @Test
    fun focusedComposerConsumesBackToDismissKeyboard() {
        assertTrue(
            shouldDismissComposerOnBack(
                composerFocused = true,
                imeIsOpen = false,
            ),
        )
    }

    @Test
    fun visibleImeConsumesBackWhenFocusEdgeLags() {
        assertTrue(
            shouldDismissComposerOnBack(
                composerFocused = false,
                imeIsOpen = true,
            ),
        )
    }

    @Test
    fun closedUnfocusedComposerLetsBackLeaveConversation() {
        assertFalse(
            shouldDismissComposerOnBack(
                composerFocused = false,
                imeIsOpen = false,
            ),
        )
    }
}
