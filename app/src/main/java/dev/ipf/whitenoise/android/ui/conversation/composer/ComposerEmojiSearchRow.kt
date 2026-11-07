package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimum height of the emoji pane in search-row mode: whatever part of the
 * browsing pane's [paneHeight] the keyboard does not cover yet. The bottom
 * region is then max(keyboard + row, browsing pane), so entering and leaving
 * search moves the composer only as far as the keyboard itself moves.
 */
internal fun composerEmojiSearchRowMinHeight(
    paneHeight: Dp,
    currentImeHeight: Dp,
): Dp = (paneHeight - currentImeHeight).coerceAtLeast(0.dp)

/**
 * The search row starts once a focused search field brings up the on-screen
 * keyboard; a hardware keyboard keeps the searchable browsing pane. The row then
 * stays until the keyboard is fully gone, so the browsing pane only returns once
 * it can take the same space.
 */
internal fun composerEmojiSearchRowShown(
    searchRowShown: Boolean,
    searchActive: Boolean,
    currentImeHeight: Dp,
    targetImeHeight: Dp,
): Boolean = (searchActive && targetImeHeight > 0.dp) || (searchRowShown && currentImeHeight > 0.dp)
