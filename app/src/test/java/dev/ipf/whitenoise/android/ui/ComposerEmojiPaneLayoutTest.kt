package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.unit.dp
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
    fun targetHeightUsesTheCurrentImeHeightAtSwapStart() {
        assertEquals(
            312.dp,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 312.dp,
                rememberedImeHeight = 0.dp,
            ),
        )
    }

    @Test
    fun targetHeightFallsBackToTheRememberedImeHeightAfterTheKeyboardHides() {
        assertEquals(
            284.dp,
            composerEmojiPaneTargetHeight(
                currentImeHeight = 0.dp,
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
    fun restoreWaitsForTheStableTargetHeightInsteadOfTheFirstNonZeroImeInset() {
        val targetHeight = 300.dp

        assertFalse(
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = true,
                currentImeHeight = 1.dp,
                targetImeHeight = targetHeight,
            ),
        )
        assertFalse(
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = true,
                currentImeHeight = 254.dp,
                targetImeHeight = targetHeight,
            ),
        )
        assertTrue(
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = true,
                currentImeHeight = 255.dp,
                targetImeHeight = targetHeight,
            ),
        )
    }

    @Test
    fun searchModeAddsRoomForTheSearchFieldAndResults() {
        assertEquals(432.dp, ComposerEmojiPickerFallbackHeight + ComposerEmojiPickerSearchExtraHeight)
    }
}
