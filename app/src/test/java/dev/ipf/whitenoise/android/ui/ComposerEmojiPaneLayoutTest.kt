package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the composer emoji-picker swap invariant (#808): when the emoji picker
 * replaces an open IME, the reserved bottom-pane height must remain identical
 * so the composer and transcript do not bounce between two inset sizes.
 */
class ComposerEmojiPaneLayoutTest {
    @Test
    fun openEmojiPaneUsesTheCurrentImeHeight() {
        assertEquals(
            312.dp,
            composerEmojiPaneHeight(
                currentImeHeight = 312.dp,
                rememberedImeHeight = 0.dp,
            ),
        )
    }

    @Test
    fun emojiPaneKeepsTheRememberedImeHeightAfterTheKeyboardHides() {
        assertEquals(
            284.dp,
            composerEmojiPaneHeight(
                currentImeHeight = 0.dp,
                rememberedImeHeight = 284.dp,
            ),
        )
    }

    @Test
    fun closedKeyboardUsesAFallbackPickerHeight() {
        assertEquals(
            ComposerEmojiPickerFallbackHeight,
            composerEmojiPaneHeight(
                currentImeHeight = 0.dp,
                rememberedImeHeight = 0.dp,
            ),
        )
    }

    @Test
    fun emojiPaneReplacesImePaddingInsteadOfStackingWithIt() {
        assertEquals(
            300.dp,
            composerBottomReservedHeight(
                emojiPickerOpen = false,
                currentImeHeight = 300.dp,
                rememberedImeHeight = 300.dp,
            ),
        )
        assertEquals(
            300.dp,
            composerBottomReservedHeight(
                emojiPickerOpen = true,
                currentImeHeight = 300.dp,
                rememberedImeHeight = 300.dp,
            ),
        )
    }
}
