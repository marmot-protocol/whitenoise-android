package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.ui.common.BottomPanelSurface
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

internal enum class EmojiPickerPurpose {
    USE,
    CONFIGURE_QUICK_REACTION,
    GROUP_IMAGE,
}

internal const val EMOJI_PICKER_TEST_TAG = "emoji.picker"

/** The sheet opens straight to this share of the screen; there is no half detent. */
internal const val EMOJI_PICKER_EXPANDED_HEIGHT_FRACTION = 0.88f

internal val EmojiPickerMaximumWidth = 600.dp

private val ComposerEmojiPaneTopInset = 4.dp

/** Same side padding as the composer, so the pane is exactly as wide as the composer above it. */
internal val ComposerEmojiPaneHorizontalPadding = 16.dp

/**
 * Full-height emoji sheet: search field on top, adaptive grid, category bar along the bottom.
 * [onConfigure] adds the settings action that leads to the quick-reaction configuration sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(
    onDismissRequest: () -> Unit,
    onEmojiPicked: (String) -> Unit,
    purpose: EmojiPickerPurpose = EmojiPickerPurpose.USE,
    recentEmojis: List<String> = emptyList(),
    onEmojiUsed: (String) -> Unit = {},
    onConfigure: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            EmojiPickerContent(
                onEmojiPicked = onEmojiPicked,
                purpose = purpose,
                recentEmojis = recentEmojis,
                onEmojiUsed = onEmojiUsed,
                onConfigure = onConfigure,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(EMOJI_PICKER_EXPANDED_HEIGHT_FRACTION)
                        .widthIn(max = EmojiPickerMaximumWidth)
                        .imePadding()
                        .testTag(EMOJI_PICKER_TEST_TAG),
            )
        }
    }
}

/**
 * The composer's inline emoji pane: the same picker content in place of the keyboard, with a backspace action.
 * Its search stays behind the category bar's search icon, so the pane opens with the full grid.
 *
 * The pane always fills [height] and lines up with the composer's width. While its search has the keyboard,
 * the field and one row of matches sit at the top and the keyboard covers the rest;
 * [onSearchContentHeightChange] reports that top part so the composer can lift by exactly its height.
 */
@Composable
internal fun ComposerEmojiPickerPane(
    height: Dp,
    alpha: Float,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    onEmojiPicked: (String) -> Unit,
    onBackspace: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onSearchContentHeightChange: (Dp) -> Unit = {},
) {
    // The panel shape clips the animated height, so content never draws past the rounded corners.
    BottomPanelSurface(
        modifier = modifier.height(height).alpha(alpha),
        horizontalPadding = ComposerEmojiPaneHorizontalPadding,
    ) {
        EmojiPickerContent(
            onEmojiPicked = onEmojiPicked,
            purpose = EmojiPickerPurpose.USE,
            recentEmojis = recentEmojis,
            onEmojiUsed = onEmojiUsed,
            onBackspace = onBackspace,
            onSearchActiveChange = onSearchActiveChange,
            searchBehindIcon = true,
            onSearchContentHeightChange = { onSearchContentHeightChange(it + ComposerEmojiPaneTopInset) },
            modifier = Modifier.fillMaxSize().padding(top = ComposerEmojiPaneTopInset),
        )
    }
}

internal val ComposerEmojiPickerFallbackHeight = 320.dp

/** Search field, one row of matches and the top inset, until the pane has measured the real ones. */
internal val ComposerEmojiSearchRowFallbackHeight = 56.dp + EmojiSearchRowHeight

internal const val EMOJI_PICKER_CELL_GLYPH_FILL_FRACTION = 0.85f

/** Font and line metrics that keep a system emoji glyph inside a [cellSizeDp] square at any font scale. */
internal fun emojiPickerCellTextMetrics(
    cellSizeDp: Dp,
    baseStyle: TextStyle,
    densityFontScale: Float,
): Pair<TextUnit, TextUnit> {
    val maxGlyphDp = cellSizeDp * EMOJI_PICKER_CELL_GLYPH_FILL_FRACTION
    val fittedFontSize =
        minOf(maxGlyphDp.value, baseStyle.fontSize.value * densityFontScale).sp / densityFontScale
    val fittedLineHeight =
        minOf(maxGlyphDp.value, baseStyle.lineHeight.value * densityFontScale).sp / densityFontScale
    return fittedFontSize to fittedLineHeight
}

internal fun composerEmojiPaneTargetHeight(
    currentImeHeight: Dp,
    targetImeHeight: Dp,
    rememberedImeHeight: Dp,
): Dp {
    val knownHeight =
        when {
            targetImeHeight > 0.dp -> targetImeHeight
            rememberedImeHeight > 0.dp -> rememberedImeHeight
            else -> currentImeHeight
        }
    return if (knownHeight > 0.dp) knownHeight else ComposerEmojiPickerFallbackHeight
}

/** Browsing takes exactly the keyboard's space, so swapping one for the other leaves the composer still. */
internal fun composerEmojiBrowsePaneHeight(keyboardHeight: Dp): Dp = keyboardHeight

/**
 * Height the composer emoji pane animates toward. Browsing takes the [keyboardHeight] (or the
 * [lockedPaneHeight] set on open and on the way back to the keyboard). While the search has the keyboard
 * the pane is the keyboard plus the [searchRowHeight] above it, so the composer lifts by exactly the
 * search row while the keyboard rises over the rest of the pane, and settles back as the keyboard hides.
 */
internal fun composerEmojiPaneHeight(
    lockedPaneHeight: Dp,
    keyboardHeight: Dp,
    searchKeyboardWanted: Boolean,
    searchRowHeight: Dp = ComposerEmojiSearchRowFallbackHeight,
): Dp =
    when {
        searchKeyboardWanted -> keyboardHeight + searchRowHeight
        lockedPaneHeight > 0.dp -> lockedPaneHeight
        else -> composerEmojiBrowsePaneHeight(keyboardHeight)
    }

internal fun updatedComposerRememberedImeHeight(
    previousRememberedImeHeight: Dp,
    currentImeHeight: Dp,
    freezeUpdates: Boolean,
): Dp =
    if (!freezeUpdates && currentImeHeight > 0.dp) {
        currentImeHeight
    } else {
        previousRememberedImeHeight
    }

/**
 * Whether the IME has finished animating and is resting at a visible height.
 * Handing the bottom region to imePadding before this point moves the
 * composer twice: measured on-device, the keyboard's show animation can aim
 * at a transient overshoot target (its with-toolbar height) that it abandons
 * one frame after arriving, snapping back to the plain height. A swap made
 * mid-animation rides that overshoot up and back — a visible bounce in a
 * perfectly gentle transition. A stale full-height inset right after a hide
 * request (target 0) is rejected by the same check.
 */
internal fun composerImeHasSettled(
    currentImeHeight: Dp,
    imeTargetHeight: Dp,
): Boolean = imeTargetHeight > 0.dp && currentImeHeight == imeTargetHeight

/**
 * Whether a pending attachment-pane restore can hand the bottom region back
 * to imePadding. The attachment pane's minimum height already rides the live
 * inset, so it needs no pane-height matching — only a settled keyboard.
 */
internal fun shouldSwapComposerEmojiPaneToIme(
    keyboardRestorePending: Boolean,
    currentImeHeight: Dp,
    imeTargetHeight: Dp,
): Boolean =
    keyboardRestorePending &&
        composerImeHasSettled(currentImeHeight = currentImeHeight, imeTargetHeight = imeTargetHeight)

internal enum class ComposerPaneRestoreStep {
    /** Keep the pane exactly where it is; the IME is not resting yet. */
    HOLD,

    /** The keyboard settled at a different height; glide the pane to it. */
    MATCH_PANE_TO_KEYBOARD,

    /** Pane and settled keyboard occupy identical space; release the pane. */
    SWAP_TO_KEYBOARD,
}

/**
 * One step of the emoji-pane-to-keyboard handoff. The pane is released only
 * when the keyboard has settled AND the rendered pane occupies exactly the
 * keyboard's space — if the keyboard settles at a different height than the
 * pane reserved (a toolbar row appeared or disappeared, or the pane opened at
 * its fallback height before any keyboard was measured), the pane first
 * animates to the settled height so the swap is always seamless instead of a
 * one-frame jump.
 */
internal fun composerEmojiPaneRestoreStep(
    keyboardRestorePending: Boolean,
    currentImeHeight: Dp,
    imeTargetHeight: Dp,
    lockedPaneHeight: Dp,
    renderedPaneHeight: Dp,
): ComposerPaneRestoreStep =
    when {
        !keyboardRestorePending || !composerImeHasSettled(currentImeHeight, imeTargetHeight) ->
            ComposerPaneRestoreStep.HOLD
        lockedPaneHeight != imeTargetHeight -> ComposerPaneRestoreStep.MATCH_PANE_TO_KEYBOARD
        renderedPaneHeight != imeTargetHeight -> ComposerPaneRestoreStep.HOLD
        else -> ComposerPaneRestoreStep.SWAP_TO_KEYBOARD
    }

/**
 * When the restore window times out the pane is always released — the user
 * asked for the keyboard, so staying on (or returning to) the picker would
 * override that intent. Focus is cleared only when no IME arrived at all; if
 * a keyboard is up at a different height than the pane reserved, it keeps
 * focus and imePadding simply takes over at the keyboard's real height.
 */
internal fun composerKeyboardRestoreTimeoutClearsFocus(currentImeHeight: Dp): Boolean = currentImeHeight == 0.dp
