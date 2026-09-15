package dev.ipf.whitenoise.android.ui.conversation.composer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDismissCollapseTest {
    /** A dismiss in progress drops lingering focus so the compact row animates with the keyboard. */
    @Test
    fun dismissInProgressIgnoresLingeringFocus() {
        assertTrue(editingRequested(focused = true, dismissInProgress = false))
        assertFalse(editingRequested(focused = true, dismissInProgress = true))
        assertFalse(editingRequested(focused = false, dismissInProgress = false))
    }

    /** Draft text, a forced layout or a sized composer keep the editing row even while dismissing. */
    @Test
    fun textForcedLayoutAndSizedComposersKeepTheEditingRow() {
        assertTrue(editingRequested(focused = true, dismissInProgress = true, hasText = true))
        assertTrue(editingRequested(focused = true, dismissInProgress = true, forceEditingLayout = true))
        assertTrue(editingRequested(focused = false, dismissInProgress = true, mode = ComposerExpansionMode.Manual))
    }

    /** Calls the production rule with the quiet defaults of an empty automatic composer. */
    private fun editingRequested(
        focused: Boolean,
        dismissInProgress: Boolean,
        hasText: Boolean = false,
        forceEditingLayout: Boolean = false,
        mode: ComposerExpansionMode = ComposerExpansionMode.Automatic,
    ): Boolean =
        composerEditingRequested(
            focused = focused,
            hasText = hasText,
            forceEditingLayout = forceEditingLayout,
            mode = mode,
            dismissInProgress = dismissInProgress,
        )
}
