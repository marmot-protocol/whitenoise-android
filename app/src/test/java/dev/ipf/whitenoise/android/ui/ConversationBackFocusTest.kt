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
                textSelectionActive = false,
                selectionMode = false,
                searchOpen = false,
                composerFocused = true,
                imeIsOpen = false,
            ),
        )
    }

    @Test
    fun visibleImeConsumesBackWhenFocusEdgeLags() {
        assertTrue(
            shouldDismissComposerOnBack(
                textSelectionActive = false,
                selectionMode = false,
                searchOpen = false,
                composerFocused = false,
                imeIsOpen = true,
            ),
        )
    }

    @Test
    fun textSelectionKeepsExistingBackPriority() {
        assertFalse(
            shouldDismissComposerOnBack(
                textSelectionActive = true,
                selectionMode = false,
                searchOpen = false,
                composerFocused = true,
                imeIsOpen = true,
            ),
        )
    }

    @Test
    fun messageSelectionKeepsExistingBackPriority() {
        assertFalse(
            shouldDismissComposerOnBack(
                textSelectionActive = false,
                selectionMode = true,
                searchOpen = false,
                composerFocused = true,
                imeIsOpen = true,
            ),
        )
    }

    @Test
    fun searchKeepsExistingBackPriority() {
        assertFalse(
            shouldDismissComposerOnBack(
                textSelectionActive = false,
                selectionMode = false,
                searchOpen = true,
                composerFocused = false,
                imeIsOpen = true,
            ),
        )
    }

    @Test
    fun closedUnfocusedComposerLetsBackLeaveConversation() {
        assertFalse(
            shouldDismissComposerOnBack(
                textSelectionActive = false,
                selectionMode = false,
                searchOpen = false,
                composerFocused = false,
                imeIsOpen = false,
            ),
        )
    }
}
