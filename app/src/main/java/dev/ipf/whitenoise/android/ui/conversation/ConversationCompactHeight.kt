package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Post-inset conversation viewport below which the screen runs compact-height
 * chrome. Measured geometry, not orientation: a portrait phone with the IME
 * open keeps roughly 400dp and stays regular, while a landscape phone with the
 * IME open drops well under this and needs every vertical dp back.
 */
internal val CompactConversationViewportThreshold = 240.dp

/** Reduced conversation top-bar height while the viewport is compact. */
internal val CompactConversationTopBarHeight = 48.dp

/** Composer clearance below a compact top bar for the full-screen composer. */
internal val CompactConversationTopClearance = 48.dp

/**
 * Growth cap for the compact chrome under enlarged fonts. The compact viewport
 * is a fixed budget shared with the composer: unbounded bar growth at 200%
 * text would starve the editor below one line, so the bar grows enough for its
 * single scaled title line and the title ellipsizes beyond this point.
 */
private const val COMPACT_CHROME_MAX_FONT_GROWTH = 1.5f

private fun scaleChrome(
    base: Dp,
    scale: Float,
): Dp = base * scale.coerceIn(1f, COMPACT_CHROME_MAX_FONT_GROWTH)

/**
 * [CompactConversationTopBarHeight] honoring the user's font scale: the bar
 * holds one line of title text, so at enlarged accessibility text it grows
 * with that line instead of clipping it at the 1x height — bounded by
 * [COMPACT_CHROME_MAX_FONT_GROWTH] so the composer keeps a viable share of the
 * compact viewport, and never below the 1x height.
 */
internal fun compactTopBarHeightFor(fontScale: Float): Dp = scaleChrome(CompactConversationTopBarHeight, fontScale)

/** The full-screen composer clearance matching [compactTopBarHeightFor]. */
internal fun compactTopClearanceFor(fontScale: Float): Dp = scaleChrome(CompactConversationTopClearance, fontScale)

/**
 * Smallest automatic composer allowance that keeps the active draft line, its
 * banners, and the primary actions viable; the editor scrolls internally when
 * a draft outgrows it.
 */
internal val CompactViableComposerHeight = 132.dp

/** The fraction of the post-inset remainder a regular-height automatic composer may take. */
private const val REGULAR_COMPOSER_CEILING_FRACTION = 0.5f

/**
 * Observes whether the conversation should present compact-height chrome.
 * Deriving on the IME's animation target keeps the decision to one flip per
 * keyboard transition instead of one per animation frame, mirroring the
 * conversation screen's recomposition discipline for inset reads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberConversationCompactHeight(): State<Boolean> {
    val density = LocalDensity.current
    val imeTargetInsets = WindowInsets.imeAnimationTarget
    val statusBarInsets = WindowInsets.statusBars
    val navigationBarInsets = WindowInsets.navigationBars
    val windowInfo = LocalWindowInfo.current
    val compactThresholdPx = with(density) { CompactConversationViewportThreshold.toPx() }
    return remember(imeTargetInsets, statusBarInsets, navigationBarInsets, windowInfo, density) {
        derivedStateOf {
            conversationUsesCompactHeight(
                containerHeightPx = windowInfo.containerSize.height,
                statusBarTopPx = statusBarInsets.getTop(density),
                imeTargetBottomPx = imeTargetInsets.getBottom(density),
                navigationBottomPx = navigationBarInsets.getBottom(density),
                compactThresholdPx = compactThresholdPx,
            )
        }
    }
}

/**
 * Whether the conversation should present compact-height chrome, from the
 * measured window size and resolved insets. The IME target inset keeps the
 * decision stable across the open/close animation instead of flickering
 * per-frame, and the larger of IME/navigation mirrors how the bottom cluster
 * actually pads.
 */
internal fun conversationUsesCompactHeight(
    containerHeightPx: Int,
    statusBarTopPx: Int,
    imeTargetBottomPx: Int,
    navigationBottomPx: Int,
    compactThresholdPx: Float,
): Boolean {
    if (containerHeightPx <= 0) return false
    val available = containerHeightPx - statusBarTopPx - maxOf(imeTargetBottomPx, navigationBottomPx)
    return available < compactThresholdPx
}

/**
 * Automatic-mode height ceiling for the composer inside its post-inset
 * remainder. A regular viewport keeps the long-standing half-remainder cap; a
 * compact remainder (landscape with the IME open) instead guarantees a viable
 * composer up to [CompactViableComposerHeight], because halving an already
 * tiny remainder crushed banners and the editor into an unusable strip.
 * Deliberately independent of the screen-level compact-chrome flag: the
 * composer's own post-inset remainder is the authoritative input here.
 */
internal fun resolveAutomaticComposerCeiling(
    maximumComposerHeight: Dp,
    minimumViableHeight: Dp = CompactViableComposerHeight,
): Dp =
    maxOf(
        maximumComposerHeight * REGULAR_COMPOSER_CEILING_FRACTION,
        minOf(maximumComposerHeight, minimumViableHeight),
    ).coerceAtLeast(44.dp)
        .coerceAtMost(maximumComposerHeight)

/**
 * Whether the composer must pin its inline single-row controls. Keyed on the
 * resolved ceiling itself — the same geometry that constrains the editor — so
 * the suppression can never disagree with the ceiling the way a separate
 * window-level compact flag could: whenever the ceiling is clamped down to the
 * compact viable allowance, the expanded control layout's fixed header and
 * action-row overhead would consume most or all of it.
 */
internal fun composerMultilineControlsSuppressed(ceiling: Dp): Boolean = ceiling <= CompactViableComposerHeight
