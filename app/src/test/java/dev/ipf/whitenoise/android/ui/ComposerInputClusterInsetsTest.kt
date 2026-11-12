package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.composer.composerBottomClusterAppliesImePadding
import dev.ipf.whitenoise.android.ui.conversation.composer.resolveRenderedEmojiPaneHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldReanchorBottomInputForReplyTargetChange
import org.junit.Assert.assertEquals
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
            composerBottomClusterAppliesImePadding(showEmojiPane = false),
        )
    }

    @Test
    fun emojiPaneOpenClusterSkipsImePadding() {
        assertFalse(
            composerBottomClusterAppliesImePadding(showEmojiPane = true),
        )
    }

    @Test
    fun openingEmojiFromKeyboardHoldsKeyboardHeightOnFirstFrame() {
        // The IME padding stops on this frame while the animated height is still 0.
        assertEquals(
            300.dp,
            resolveRenderedEmojiPaneHeight(showEmojiPane = true, animatedHeight = 0.dp, imePaneHeight = 300.dp),
        )
    }

    @Test
    fun closingEmojiToKeyboardKeepsOnlyUncoveredPart() {
        assertEquals(
            120.dp,
            resolveRenderedEmojiPaneHeight(showEmojiPane = false, animatedHeight = 300.dp, imePaneHeight = 180.dp),
        )
        assertEquals(
            0.dp,
            resolveRenderedEmojiPaneHeight(showEmojiPane = false, animatedHeight = 300.dp, imePaneHeight = 320.dp),
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
