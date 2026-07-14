package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerFallbackHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerSearchExtraHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPaneRestoreStep
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheetMaxHeightFraction
import dev.ipf.whitenoise.android.ui.conversation.composer.composerAttachmentPaneMinimumHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.composerEmojiPaneHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.composerEmojiPaneRestoreStep
import dev.ipf.whitenoise.android.ui.conversation.composer.composerEmojiPaneTargetHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.composerKeyboardRestoreTimeoutClearsFocus
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerSheetVisibleContentFraction
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldStartComposerKeyboardRestore
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldSwapComposerEmojiPaneToIme
import dev.ipf.whitenoise.android.ui.conversation.composer.updatedComposerRememberedImeHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the composer emoji-picker swap invariant (#808): when the emoji picker
 * replaces an open IME, the reserved bottom-pane height must remain identical
 * so the composer and transcript do not bounce between two inset sizes.
 */
class ComposerEmojiPaneLayoutTest {
    @Test
    fun keyboardRestoreStartsOnlyOnceForAnOpenPane() {
        assertTrue(
            shouldStartComposerKeyboardRestore(
                paneOpen = true,
                keyboardRestorePending = false,
            ),
        )
        assertFalse(
            shouldStartComposerKeyboardRestore(
                paneOpen = true,
                keyboardRestorePending = true,
            ),
        )
        assertFalse(
            shouldStartComposerKeyboardRestore(
                paneOpen = false,
                keyboardRestorePending = false,
            ),
        )
    }

    @Test
    fun attachmentPaneTracksTheAnimatedImeHeightDuringTheHandoff() {
        listOf(900.dp, 700.dp, 400.dp, 0.dp).forEach { animatedImeHeight ->
            assertEquals(
                animatedImeHeight,
                composerAttachmentPaneMinimumHeight(
                    showAttachmentPane = true,
                    currentImeHeight = animatedImeHeight,
                ),
            )
        }
    }

    @Test
    fun hiddenAttachmentPaneDoesNotReserveImeHeight() {
        assertEquals(
            0.dp,
            composerAttachmentPaneMinimumHeight(
                showAttachmentPane = false,
                currentImeHeight = 900.dp,
            ),
        )
    }

    @Test
    fun targetHeightUsesTheCurrentImeHeightAtSwapStart() {
        assertEquals(
            312.dp,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 312.dp,
                targetImeHeight = 312.dp,
                rememberedImeHeight = 0.dp,
            ),
        )
    }

    @Test
    fun targetHeightUsesTheFinalKeyboardInsteadOfAStaleTallerHeight() {
        assertEquals(
            120.dp,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 120.dp,
                targetImeHeight = 120.dp,
                rememberedImeHeight = 300.dp,
            ),
        )
    }

    @Test
    fun targetHeightIgnoresAPartialImeFrameDuringRapidSwitching() {
        assertEquals(
            300.dp,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 120.dp,
                targetImeHeight = 300.dp,
                rememberedImeHeight = 300.dp,
            ),
        )
    }

    @Test
    fun targetHeightKeepsTheStableHeightWhenTheKeyboardIsStillHiding() {
        assertEquals(
            300.dp,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 120.dp,
                targetImeHeight = 0.dp,
                rememberedImeHeight = 300.dp,
            ),
        )
    }

    @Test
    fun targetHeightFallsBackToTheRememberedImeHeightAfterTheKeyboardHides() {
        assertEquals(
            284.dp,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 0.dp,
                targetImeHeight = 0.dp,
                rememberedImeHeight = 284.dp,
            ),
        )
    }

    @Test
    fun closedKeyboardWithoutHistoryUsesAFallbackPickerHeight() {
        assertEquals(
            ComposerEmojiPickerFallbackHeight,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 0.dp,
                targetImeHeight = 0.dp,
                rememberedImeHeight = 0.dp,
            ),
        )
    }

    @Test
    fun openEmojiPaneKeepsTheLockedHeightWhileImeInsetsAnimateDown() {
        val lockedHeight = 300.dp

        listOf(300.dp, 200.dp, 100.dp, 0.dp).forEach { animatedImeHeight ->
            assertEquals(
                lockedHeight,
                composerEmojiPaneHeight(
                    lockedPaneHeight = lockedHeight,
                    currentImeHeight = animatedImeHeight,
                    targetImeHeight = 0.dp,
                    rememberedImeHeight = lockedHeight,
                ),
            )
        }
    }

    @Test
    fun rememberedImeHeightDoesNotTrackInsetsWhileEmojiPaneOwnsBottomRegion() {
        var rememberedImeHeight = 300.dp

        listOf(200.dp, 100.dp, 0.dp).forEach { animatedImeHeight ->
            rememberedImeHeight =
                updatedComposerRememberedImeHeight(
                    previousRememberedImeHeight = rememberedImeHeight,
                    currentImeHeight = animatedImeHeight,
                    freezeUpdates = true,
                )
            assertEquals(300.dp, rememberedImeHeight)
        }
    }

    @Test
    fun rememberedImeHeightTracksTheKeyboardWhenTheEmojiPaneIsNotOpen() {
        assertEquals(
            276.dp,
            updatedComposerRememberedImeHeight(
                previousRememberedImeHeight = 0.dp,
                currentImeHeight = 276.dp,
                freezeUpdates = false,
            ),
        )
    }

    @Test
    fun rememberedImeHeightTracksTheLatestKeyboardHeight() {
        assertEquals(
            120.dp,
            updatedComposerRememberedImeHeight(
                previousRememberedImeHeight = 300.dp,
                currentImeHeight = 120.dp,
                freezeUpdates = false,
            ),
        )
    }

    @Test
    fun restoreWaitsWhileTheImeIsStillRisingTowardItsTarget() {
        listOf(1.dp, 150.dp, 299.dp).forEach { risingImeHeight ->
            assertFalse(
                shouldSwapComposerEmojiPaneToIme(
                    keyboardRestorePending = true,
                    currentImeHeight = risingImeHeight,
                    imeTargetHeight = 300.dp,
                ),
            )
        }
    }

    @Test
    fun restoreSwapsWhenTheImeSettlesAtItsTarget() {
        assertTrue(
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = true,
                currentImeHeight = 300.dp,
                imeTargetHeight = 300.dp,
            ),
        )
    }

    // Regression for the rapid keyboard→emoji→keyboard reversal: right after a
    // hide request the current inset still reads the previous keyboard's full
    // height for a few frames while the animation target is already 0. Swapping
    // on that stale inset released the pane mid-hide, so the composer rode the
    // hide animation down and the bottom region was left empty.
    @Test
    fun restoreIgnoresAStaleFullHeightInsetWhileTheImeIsHiding() {
        assertFalse(
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = true,
                currentImeHeight = 300.dp,
                imeTargetHeight = 0.dp,
            ),
        )
    }

    @Test
    fun restoreSwapRequiresAPendingRestore() {
        assertFalse(
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = false,
                currentImeHeight = 300.dp,
                imeTargetHeight = 300.dp,
            ),
        )
    }

    // Regression for the gentle emoji→keyboard bounce: the keyboard's show
    // animation aims at a transient overshoot target (its with-toolbar
    // height), crosses the reserved pane height mid-flight, and then abandons
    // the overshoot one frame after arriving. Releasing the pane the moment
    // the inset crossed the pane height rode the composer up the overshoot
    // and snapped it back down. The pane must hold until the IME settles.
    @Test
    fun paneHoldsThroughATransientImeOvershootTarget() {
        // Inset already past the 300dp pane, still animating toward 340dp.
        assertEquals(
            ComposerPaneRestoreStep.HOLD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = true,
                currentImeHeight = 310.dp,
                imeTargetHeight = 340.dp,
                lockedPaneHeight = 300.dp,
                renderedPaneHeight = 300.dp,
            ),
        )
        // The overshoot collapsed; the keyboard settled exactly where the
        // pane already is — the swap is pixel-identical.
        assertEquals(
            ComposerPaneRestoreStep.SWAP_TO_KEYBOARD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = true,
                currentImeHeight = 300.dp,
                imeTargetHeight = 300.dp,
                lockedPaneHeight = 300.dp,
                renderedPaneHeight = 300.dp,
            ),
        )
    }

    @Test
    fun paneGlidesToAKeyboardThatSettledAtADifferentHeight() {
        // Settled taller than the reserved pane: retarget the pane first.
        assertEquals(
            ComposerPaneRestoreStep.MATCH_PANE_TO_KEYBOARD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = true,
                currentImeHeight = 340.dp,
                imeTargetHeight = 340.dp,
                lockedPaneHeight = 300.dp,
                renderedPaneHeight = 300.dp,
            ),
        )
        // Mid-glide: hold until the rendered pane occupies the exact space.
        assertEquals(
            ComposerPaneRestoreStep.HOLD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = true,
                currentImeHeight = 340.dp,
                imeTargetHeight = 340.dp,
                lockedPaneHeight = 340.dp,
                renderedPaneHeight = 312.dp,
            ),
        )
        // Arrived: pane and keyboard occupy identical space, release.
        assertEquals(
            ComposerPaneRestoreStep.SWAP_TO_KEYBOARD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = true,
                currentImeHeight = 340.dp,
                imeTargetHeight = 340.dp,
                lockedPaneHeight = 340.dp,
                renderedPaneHeight = 340.dp,
            ),
        )
    }

    @Test
    fun paneGlidesDownToAKeyboardThatSettledShorterThanThePane() {
        assertEquals(
            ComposerPaneRestoreStep.MATCH_PANE_TO_KEYBOARD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = true,
                currentImeHeight = 280.dp,
                imeTargetHeight = 280.dp,
                lockedPaneHeight = 300.dp,
                renderedPaneHeight = 300.dp,
            ),
        )
    }

    @Test
    fun paneHoldsWhileTheImeIsStillAnimating() {
        assertEquals(
            ComposerPaneRestoreStep.HOLD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = true,
                currentImeHeight = 200.dp,
                imeTargetHeight = 300.dp,
                lockedPaneHeight = 300.dp,
                renderedPaneHeight = 300.dp,
            ),
        )
    }

    @Test
    fun paneRestoreStepIsHoldWithoutAPendingRestore() {
        assertEquals(
            ComposerPaneRestoreStep.HOLD,
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = false,
                currentImeHeight = 300.dp,
                imeTargetHeight = 300.dp,
                lockedPaneHeight = 300.dp,
                renderedPaneHeight = 300.dp,
            ),
        )
    }

    @Test
    fun restoreTimeoutClearsFocusOnlyWhenNoKeyboardEverArrived() {
        assertTrue(composerKeyboardRestoreTimeoutClearsFocus(currentImeHeight = 0.dp))
        assertFalse(composerKeyboardRestoreTimeoutClearsFocus(currentImeHeight = 264.dp))
    }

    @Test
    fun searchModeAddsRoomForTheSearchFieldAndResults() {
        assertEquals(432.dp, ComposerEmojiPickerFallbackHeight + ComposerEmojiPickerSearchExtraHeight)
    }

    @Test
    fun emojiPickerSheetUsesOnlyTheVisiblePartialViewportBeforeExpansion() {
        val partialFraction = emojiPickerSheetVisibleContentFraction(expanded = false)

        assertEquals(0.48f / EmojiPickerSheetMaxHeightFraction, partialFraction, 0.0001f)
        assertTrue(partialFraction < 1f)
    }

    @Test
    fun emojiPickerSheetUsesTheFullSheetViewportWhenExpanded() {
        assertEquals(1f, emojiPickerSheetVisibleContentFraction(expanded = true), 0.0001f)
    }
}
