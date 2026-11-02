@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiGlyph
import dev.ipf.whitenoise.android.ui.conversation.reactions.reactionSourceBounds
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.emojiPlainLabel
import dev.ipf.whitenoise.android.ui.theme.Typography
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.outlineSelectionColor
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One visible native capability; dispatch remains in the owning message. */
internal data class FocusedMessageAction(
    val label: String,
    val supportingLabel: String?,
    val enabled: Boolean,
    val destructive: Boolean,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
)

/**
 * Gives the overlay the whole popup window rather than a window cut to its own content.
 *
 * The stack used to be its own window, sized to what it drew, so where it sat was a window
 * position and nothing inside the overlay could change it. Handing it the window instead makes
 * the resting place a layout decision, which is what lets the stack travel through the frame.
 */
internal object FocusedMessageOverlayFrameProvider : PopupPositionProvider {
    /** The overlay always starts at the window origin; the stack positions itself within it. */
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * The offsets the lifted stack may occupy inside a frame [frameHeightPx] tall.
 *
 * A stack shorter than its frame travels between the frame's edges; one that fills the frame has
 * nowhere to go and keeps the scrolling it already had. Both are the same range, so there is no
 * pair of models that have to agree about where the stack is.
 */
internal fun focusedStackTravelRange(
    frameHeightPx: Int,
    stackHeightPx: Int,
): ClosedFloatingPointRange<Float> {
    val slack = (frameHeightPx - stackHeightPx).toFloat()
    return minOf(0f, slack)..maxOf(0f, slack)
}

/**
 * Where the stack rests before anyone moves it: centred on the message it lifted, held in the frame.
 *
 * [anchorCenterPx] is a window coordinate, because that is what the lifted bubble reports, while the
 * offset is applied inside a frame that starts below the top inset. [topInsetPx] converts between
 * them; without it the stack rests a status bar too low.
 *
 * The stack is placed by its preview, not by its own middle: a short reaction rail sits above the
 * lifted message and a tall menu below it, so centring the stack would put the lifted message well
 * above the bubble it came from and the message would appear to jump as the overlay opened.
 * [previewCenterInStackPx] defaults to the stack's middle for a lift that draws no preview.
 *
 * A null [anchorCenterPx] means the lift reported no bubble and no touch point, so the stack has
 * nothing to sit beside and centres on the frame instead.
 */
internal fun focusedStackRestingOffset(
    frameHeightPx: Int,
    stackHeightPx: Int,
    anchorCenterPx: Int?,
    topInsetPx: Int = 0,
    previewCenterInStackPx: Int = stackHeightPx / 2,
): Float {
    val range = focusedStackTravelRange(frameHeightPx, stackHeightPx)
    val centre = anchorCenterPx?.minus(topInsetPx) ?: (frameHeightPx / 2)
    return (centre - previewCenterInStackPx).toFloat().coerceIn(range.start, range.endInclusive)
}

/** The lifted stack's vertical travel: where it sits now, and whether a gesture has claimed it. */
@Stable
private class FocusedStackTravel {
    val offset: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** Once a gesture moves the stack, re-measuring must not drag it back to where it started. */
    var moved by mutableStateOf(false)

    /** False until the stack has a measured height and an offset to match it. */
    var placed by mutableStateOf(false)
}

/**
 * Tracks the stack's travel, keeping its bounds and its untouched resting place in step with layout.
 *
 * Measurement arrives a frame late and changes again with the font scale, a reaction being added or
 * the frame itself resizing, so bounds are recomputed each time; the resting snap is skipped once a
 * gesture has moved the stack, which would otherwise undo the move on the next measurement.
 */
@Composable
private fun rememberFocusedStackTravel(
    frameHeightPx: Int,
    stackHeightPx: Int,
    anchorCenterPx: Int?,
    topInsetPx: Int,
    previewCenterInStackPx: Int?,
): FocusedStackTravel {
    val travel = remember { FocusedStackTravel() }
    LaunchedEffect(frameHeightPx, stackHeightPx, anchorCenterPx, topInsetPx, previewCenterInStackPx) {
        // A popup re-shown after an app switch reports a zero height on its first frame. Placing
        // the stack on that would put it at the frame's top and then jump it to where it belongs.
        if (stackHeightPx <= 0) return@LaunchedEffect
        val range = focusedStackTravelRange(frameHeightPx, stackHeightPx)
        travel.offset.updateBounds(range.start, range.endInclusive)
        if (!travel.moved) {
            travel.offset.snapTo(
                focusedStackRestingOffset(
                    frameHeightPx = frameHeightPx,
                    stackHeightPx = stackHeightPx,
                    anchorCenterPx = anchorCenterPx,
                    topInsetPx = topInsetPx,
                    previewCenterInStackPx = previewCenterInStackPx ?: (stackHeightPx / 2),
                ),
            )
        }
        travel.placed = true
    }
    return travel
}

/**
 * The drag that carries the stack, hung on the lifted message so the gesture starts on the message.
 *
 * A drag only wins after touch slop, so the tap that dismisses from the same message survives it.
 * The fling decays into the travel bounds and stops there, and the whole thing is scoped to the
 * overlay's composition, so dismissal, Back, navigation or a stopped lifecycle end motion with it
 * rather than leaving it to land on an overlay that is no longer there.
 */
@Composable
private fun Modifier.focusedStackDrag(travel: FocusedStackTravel): Modifier {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    return draggable(
        orientation = Orientation.Vertical,
        state =
            rememberDraggableState { delta ->
                travel.moved = true
                scope.launch { travel.offset.snapTo(travel.offset.value + delta) }
            },
        onDragStopped = { velocity -> travel.offset.animateDecay(velocity, decay) },
    )
}

private const val FOCUSED_BACKDROP_ALPHA = 0.88f
private const val FOCUSED_PRESSED_STATE_ALPHA = 0.12f
private const val FOCUSED_MORE_DISC_ALPHA = 0.08f
private val FocusedStackMaximumWidth = 560.dp
private val FocusedOverlayShadowSafeInset = 8.dp
private val FocusedReactionRailInset = 4.dp
private val FocusedStackHorizontalPadding = 16.dp

// Two rows of 48dp targets: the rail height the overlay's 200dp preview budget was sized around.
private val FocusedReactionRailBudget = 108.dp

// A frame shorter than this, such as one the keyboard has shortened, keeps the 100% rail.
private val FocusedReactionGrowthFrameHeight = 480.dp
private val FocusedReactionSizingStep = 2.dp

// The requested quick-reaction layout: at most two rows of buttons (#3279).
private const val FOCUSED_REACTION_MAX_ROWS = 2
private val FocusedReactionItemSpacing = 4.dp
private val FocusedReactionTargetSize = 48.dp
private val FocusedReactionStateLayerSize = 40.dp
private val FocusedReactionSelectedFillSize = 36.dp
private val FocusedReactionEmojiSize = 28.dp
private val FocusedMoreIconSize = 24.dp
internal const val FOCUSED_ACTION_MENU_SCROLL_TEST_TAG = "focused-action-menu-scroll"
internal const val FOCUSED_REACTION_RAIL_TEST_TAG = "focused-reaction-rail"

/** Prototype reaction rail, inert real-message preview and grouped command menu; preserves the host IME. */
@Composable
@Suppress("LongMethod", "LongParameterList")
internal fun FocusedMessageActions(
    initiatingHoldActive: Boolean = false,
    sourceBounds: IntRect?,
    touchY: Float?,
    mine: Boolean,
    actions: List<FocusedMessageAction>,
    quickReactions: List<String>,
    canReact: Boolean,
    selectedReactions: Set<String>,
    previewDescription: String,
    previewReady: Boolean,
    preview: (@Composable () -> Unit)?,
    onReact: (String) -> Unit,
    onReactionOrigin: ((String, androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    onMoreReactions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.message_actions)
    val close = stringResource(R.string.close)
    // Saveable so a popup window re-shown after an app switch does not hide content it already
    // revealed; the value lives in the host composition, not in the popup's own.
    var measured by rememberSaveable { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    KeyboardSafePopup(
        expanded = true,
        onDismissRequest = onDismiss,
        popupPositionProvider = FocusedMessageOverlayFrameProvider,
        scrimModifier =
            Modifier.background(
                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = FOCUSED_BACKDROP_ALPHA),
            ),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    // The overlay owns the whole window now, so the travel range is the part of it
                    // the stack may actually occupy rather than the part the system bars cover.
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    // A tap reaching the frame landed beside the stack, on nothing, and dismisses
                    // exactly as the scrim underneath it would have before the frame covered it.
                    .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
                    .testTag(FOCUSED_OVERLAY_FRAME_TEST_TAG),
        ) {
            // The lifted bubble reports a window coordinate; the frame below starts under the top
            // inset. The stack would rest a status bar too low without converting between them.
            val topInsetPx = WindowInsets.safeDrawing.getTop(LocalDensity.current)
            // The theme scales its typography by the app's text-size setting; sp also carries the system scale.
            val textScale = MaterialTheme.typography.bodyLarge.fontSize.value / Typography.bodyLarge.fontSize.value
            val emojiSize = with(LocalDensity.current) { (FocusedReactionEmojiSize.value * textScale).sp.toDp() }
            // The rail, inter-item gaps and a tappable scroll viewport need roughly 200dp with two 48dp reaction
            // rows. A larger text size grows the rail (#3279), but only into height a frame taller than
            // FocusedReactionGrowthFrameHeight can spare, and that growth comes out of the preview instead of the
            // action viewport. On an ordinary full-height frame, the preview keeps its original footprint.
            val railWidth = minOf(maxWidth, FocusedStackMaximumWidth) - FocusedStackHorizontalPadding * 2
            val railAllowance =
                FocusedReactionRailBudget + (maxHeight - FocusedReactionGrowthFrameHeight).coerceAtLeast(0.dp)
            val reactionCount = quickReactions.size + 1
            val reactionSizing =
                remember(emojiSize, reactionCount, railWidth, railAllowance) {
                    FocusedReactionSizing.fitting(emojiSize, reactionCount, railWidth, railAllowance)
                }
            val railGrowth =
                if (canReact) {
                    reactionSizing.railHeight(reactionCount, railWidth) - FocusedReactionRailBudget
                } else {
                    0.dp
                }
            val previewHeightLimit = (maxHeight - 200.dp - railGrowth.coerceAtLeast(0.dp)).coerceAtLeast(48.dp)
            var stackHeightPx by remember { mutableIntStateOf(0) }
            var previewCenterInStackPx by remember { mutableStateOf<Int?>(null) }
            val travel =
                rememberFocusedStackTravel(
                    frameHeightPx = constraints.maxHeight,
                    stackHeightPx = stackHeightPx,
                    anchorCenterPx = sourceBounds?.center?.y ?: touchY?.roundToInt(),
                    topInsetPx = topInsetPx,
                    previewCenterInStackPx = previewCenterInStackPx,
                )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = FocusedStackMaximumWidth)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .offset { IntOffset(0, travel.offset.value.roundToInt()) }
                        .onSizeChanged {
                            measured = it.width > 0 && it.height > 0
                            stackHeightPx = it.height
                        }.graphicsLayer { alpha = if (measured && previewReady && travel.placed) 1f else 0f }
                        // Children consume their own taps first, so a tap that reaches the column
                        // landed on empty stack space or its padding and dismisses like the scrim.
                        .pointerInput(Unit) { detectTapGestures { currentOnDismiss() } }
                        .padding(horizontal = FocusedStackHorizontalPadding, vertical = FocusedOverlayShadowSafeInset)
                        .semantics {
                            paneTitle = title
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(close) {
                                        onDismiss()
                                        true
                                    },
                                )
                        }.testTag(MESSAGE_ACTION_MENU_TEST_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                if (canReact) {
                    FocusedReactionRail(
                        quickReactions = quickReactions,
                        sizing = reactionSizing,
                        selectedReactions = selectedReactions,
                        onReact = onReact,
                        onReactionOrigin = onReactionOrigin,
                        onMoreReactions = onMoreReactions,
                    )
                }
                if (preview != null) {
                    Box(
                        modifier =
                            Modifier
                                // The tag precedes clearAndSetSemantics, which wipes semantics set after it.
                                .testTag("message-actions-preview")
                                // A five-line text excerpt with a reply/footer can be as tall as
                                // media at large font sizes. Bound either preview so the action
                                // scroller retains a tappable viewport above the keyboard.
                                .heightIn(max = previewHeightLimit)
                                .clipToBounds()
                                // Where the lifted message sits inside the stack is what the stack is
                                // placed by, so the message lands on the bubble it was lifted from.
                                .onPlaced {
                                    previewCenterInStackPx =
                                        it.positionInParent().y.roundToInt() + it.size.height / 2
                                }.then(if (initiatingHoldActive) Modifier else Modifier.focusedStackDrag(travel))
                                .clearAndSetSemantics {
                                    contentDescription = previewDescription
                                    // The same dismissal the tap performs, reachable without one: a
                                    // screen reader could describe the lifted message but not leave it.
                                    onClick(close) {
                                        currentOnDismiss()
                                        true
                                    }
                                }
                                // The lifted message is a picture of what is being acted on, not a
                                // control. Tapping it used to be swallowed, which made the overlay feel
                                // as though it dismissed only in some places; it dismisses like the
                                // scrim, and the actions beside it consume their own taps first.
                                .pointerInput(Unit) {
                                    // The overlay opens under the finger that long-pressed, so the
                                    // release of that same press would otherwise land here as a tap and
                                    // dismiss what it just opened. Wait for the gesture to end first.
                                    awaitPointerEventScope {
                                        while (currentEvent.changes.any { it.pressed }) awaitPointerEvent()
                                    }
                                    detectTapGestures { currentOnDismiss() }
                                },
                    ) { preview() }
                }
                // When the IME shortens the safe frame, only the actions overflow. Scrolling the
                // whole stack would move the lifted message and reactions out of sight, even though
                // the user is still choosing an action for that message.
                Box(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .fadingVerticalScroll(rememberScrollState())
                            .testTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG),
                ) {
                    FocusedActionMenu(actions)
                }
            }
        }
    }
}

/**
 * Balances the reaction rail over as few rows as the width allows, so every configured choice keeps
 * at least its [targetSize] instead of being pushed past a scrolling edge that announces nothing.
 *
 * A width that fits everything keeps the single row the rail has always been. Otherwise the items
 * are spread evenly — six reactions plus the full picker become 4 and 3 rather than 6 and a lone
 * trailing button. A larger app text size grows [targetSize], so fewer fit on each row.
 */
internal fun focusedReactionItemsPerRow(
    itemCount: Int,
    availableWidth: Dp,
    targetSize: Dp = FocusedReactionTargetSize,
): Int {
    if (itemCount <= 1) return itemCount.coerceAtLeast(1)
    val step = targetSize + FocusedReactionItemSpacing
    val fitting = ((availableWidth + FocusedReactionItemSpacing) / step).toInt().coerceAtLeast(1)
    // A width that holds everything leaves one row, and the even split then returns every item.
    val rows = ceil(itemCount.toFloat() / fitting).toInt()
    return ceil(itemCount.toFloat() / rows).toInt()
}

/**
 * Sizes for the reaction rail at the current text size. At 100% they are the 28dp emoji, 48dp target, 40dp
 * state layer, 36dp selected disc and 24dp picker icon, and a larger app or system text size grows each of
 * them by the same amount as the emoji, so the space around every glyph stays compact (#3279).
 */
@Immutable
internal data class FocusedReactionSizing(
    val emoji: Dp,
    val target: Dp,
    val stateLayer: Dp,
    val selectedFill: Dp,
    val moreIcon: Dp,
) {
    /** The rail's height for [itemCount] targets wrapped across [availableWidth], including its inset. */
    fun railHeight(
        itemCount: Int,
        availableWidth: Dp,
    ): Dp {
        val rows = rows(itemCount, availableWidth)
        return target * rows + FocusedReactionItemSpacing * (rows - 1) + FocusedReactionRailInset * 2
    }

    /** How many rows [itemCount] targets wrap into across a rail [availableWidth] wide, including its inset. */
    fun rows(
        itemCount: Int,
        availableWidth: Dp,
    ): Int {
        val perRow = focusedReactionItemsPerRow(itemCount, availableWidth - FocusedReactionRailInset * 2, target)
        return ceil(itemCount.toFloat() / perRow).toInt().coerceAtLeast(1)
    }

    /** Whether [itemCount] targets fit [railWidth] in at most two rows and within [maxRailHeight]. */
    fun fits(
        itemCount: Int,
        railWidth: Dp,
        maxRailHeight: Dp,
    ): Boolean =
        rows(itemCount, railWidth) <= FOCUSED_REACTION_MAX_ROWS &&
            railHeight(itemCount, railWidth) <= maxRailHeight

    companion object {
        /** Sizing for an emoji drawn at [emoji], never smaller than the 100% sizes or larger than double them. */
        fun forEmoji(emoji: Dp): FocusedReactionSizing {
            val clamped = emoji.coerceIn(FocusedReactionEmojiSize, FocusedReactionEmojiSize * 2)
            val growth = clamped - FocusedReactionEmojiSize
            return FocusedReactionSizing(
                emoji = clamped,
                target = FocusedReactionTargetSize + growth,
                stateLayer = FocusedReactionStateLayerSize + growth,
                selectedFill = FocusedReactionSelectedFillSize + growth,
                moreIcon = FocusedMoreIconSize + growth,
            )
        }

        /**
         * The largest sizing up to [emoji] whose rail of [itemCount] targets across [railWidth] keeps to two rows
         * and stays within [maxRailHeight] (#3279). A larger text size grows the targets only while every choice
         * still fits on two rows. It never goes below the 100% sizes, so every target keeps at least 48dp.
         */
        fun fitting(
            emoji: Dp,
            itemCount: Int,
            railWidth: Dp,
            maxRailHeight: Dp,
        ): FocusedReactionSizing {
            var sizing = forEmoji(emoji)
            while (sizing.emoji > FocusedReactionEmojiSize && !sizing.fits(itemCount, railWidth, maxRailHeight)) {
                sizing = forEmoji(sizing.emoji - FocusedReactionSizingStep)
            }
            return sizing
        }
    }
}

/**
 * Quick reactions on the menu-group surface, sized by [FocusedReactionSizing] from the app's text size.
 * The rail wraps rather than scrolling horizontally, because a sixth configured reaction used to sit
 * beyond the viewport at the compact 360dp width with no affordance that it was there at all.
 * It spans the stack's full width (#3279). Every row, the last included, divides that width into equal
 * targets with compact gaps, so each button is wider than its glyph instead of floating in empty space.
 * A rail that fits on one row caps each button at twice its height, so a sparse rail stays a centered
 * cluster of compact buttons rather than two far-apart glyphs. Once the choices wrap, every row fills.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun FocusedReactionRail(
    quickReactions: List<String>,
    sizing: FocusedReactionSizing,
    selectedReactions: Set<String>,
    onReact: (String) -> Unit,
    onMoreReactions: () -> Unit,
    onReactionOrigin: ((String, androidx.compose.ui.geometry.Rect) -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(FOCUSED_REACTION_RAIL_TEST_TAG),
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MenuDefaults.groupStandardContainerColor,
        tonalElevation = MenuDefaults.TonalElevation,
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(FocusedReactionRailInset)) {
            // The full picker travels with the reactions, so it wraps with them instead of
            // competing with them for one row's worth of width.
            val perRow = focusedReactionItemsPerRow(quickReactions.size + 1, maxWidth, sizing.target)
            val wraps = quickReactions.size + 1 > perRow
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FocusedReactionItemSpacing, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(FocusedReactionItemSpacing),
                maxItemsInEachRow = perRow,
            ) {
                // A fixed weighted width would ignore the cap, so a single-row target fills its share up to it.
                val cell =
                    if (wraps) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.weight(1f, fill = false).widthIn(max = sizing.target * 2).fillMaxWidth()
                    }
                quickReactions.forEach { emoji ->
                    var reactionBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                    FocusedReactionTarget(
                        emoji = emoji,
                        selected = emoji in selectedReactions,
                        sizing = sizing,
                        modifier = cell,
                        onGlyphBounds = { reactionBounds = it },
                        onClick = {
                            reactionBounds?.let { onReactionOrigin?.invoke(emoji, it) }
                            onReact(emoji)
                        },
                    )
                }
                FocusedMoreReactionsTarget(sizing = sizing, modifier = cell, onClick = onMoreReactions)
            }
        }
    }
}

/** One quick-reaction emoji target, as wide as its share of the row and [sizing]'s target tall. */
@Composable
@Suppress("LongParameterList")
private fun FocusedReactionTarget(
    emoji: String,
    selected: Boolean,
    sizing: FocusedReactionSizing,
    modifier: Modifier,
    onGlyphBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val description = emojiPlainLabel(emoji)
    Box(
        modifier =
            modifier
                .height(sizing.target)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    this.selected = selected
                    contentDescription = description
                }.testTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(sizing.stateLayer)
                    .clip(CircleShape)
                    .background(
                        if (pressed) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = FOCUSED_PRESSED_STATE_ALPHA)
                        } else {
                            Color.Transparent
                        },
                    ).indication(interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(sizing.selectedFill),
                shape = CircleShape,
                color =
                    if (selected) {
                        outlineSelectionColor(MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        Color.Transparent
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MeasuredReactionGlyph(emoji, sizing.emoji, onGlyphBounds)
                }
            }
        }
    }
}

/** Bundled/custom artwork keeps existing feedback; only already-rendered system glyphs fly. */
@Composable
private fun MeasuredReactionGlyph(
    emoji: String,
    size: Dp,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    val systemGlyph =
        dev.ipf.whitenoise.android.ui.EmojiShortcodes.art(
            emoji,
            dev.ipf.whitenoise.android.ui.LocalCustomEmoji.current,
            emptyMap(),
        ) == null
    EmojiGlyph(
        emoji,
        size = size,
        modifier =
            Modifier.reactionSourceBounds { bounds ->
                onBounds(if (systemGlyph) bounds else androidx.compose.ui.geometry.Rect.Zero)
            },
    )
}

/** Opens the full emoji picker, sized like the reaction targets beside it. */
@Composable
private fun FocusedMoreReactionsTarget(
    sizing: FocusedReactionSizing,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val moreReactions = stringResource(R.string.open_emoji_picker)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier =
            modifier
                .height(sizing.target)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { contentDescription = moreReactions },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(sizing.stateLayer)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = FOCUSED_MORE_DISC_ALPHA))
                    .background(
                        if (pressed) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = FOCUSED_PRESSED_STATE_ALPHA)
                        } else {
                            Color.Transparent
                        },
                    ).indication(interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more_horiz),
                contentDescription = null,
                modifier = Modifier.size(sizing.moreIcon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
