package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.composer.composerBottomClusterAppliesImePadding
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldReanchorBottomInputForReplyTargetChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the composer input-cluster invariants (#895/#1109): the reply-preview
 * chip, edit banner, mention picker, and text field share one bottom inset
 * owner, and starting a reply re-anchors the bottom transcript exactly once.
 */
class ComposerInputClusterInsetsTest {
    @Test
    fun keyboardOpenClusterAppliesImePadding() {
        assertTrue(
            composerBottomClusterAppliesImePadding(
                showEmojiPane = false,
                composerEmojiSearchActive = false,
            ),
        )
    }

    @Test
    fun emojiPaneOpenClusterSkipsImePadding() {
        assertFalse(
            composerBottomClusterAppliesImePadding(
                showEmojiPane = true,
                composerEmojiSearchActive = false,
            ),
        )
    }

    @Test
    fun emojiPaneSearchReappliesImePadding() {
        assertTrue(
            composerBottomClusterAppliesImePadding(
                showEmojiPane = true,
                composerEmojiSearchActive = true,
            ),
        )
    }

    @Test
    fun startingReplyReanchorsBottomInputCluster() {
        assertTrue(
            shouldReanchorBottomInputForReplyTargetChange(
                hadReplyTarget = false,
                hasReplyTarget = true,
            ),
        )
    }

    @Test
    fun activeReplyRecompositionDoesNotKeepReanchoring() {
        assertFalse(
            shouldReanchorBottomInputForReplyTargetChange(
                hadReplyTarget = true,
                hasReplyTarget = true,
            ),
        )
    }

    @Test
    fun clearingReplyDoesNotReanchor() {
        assertFalse(
            shouldReanchorBottomInputForReplyTargetChange(
                hadReplyTarget = true,
                hasReplyTarget = false,
            ),
        )
    }
}
