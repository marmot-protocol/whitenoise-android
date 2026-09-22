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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.PopupPositionProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiGlyph
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
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
private val FocusedReactionRailMaximumWidth = 392.dp
private val FocusedReactionRailInset = 4.dp
private val FocusedReactionItemSpacing = 4.dp
private val FocusedReactionTargetSize = 48.dp
private val FocusedReactionStateLayerSize = 40.dp
private val FocusedReactionSelectedFillSize = 36.dp
private val FocusedReactionEmojiSize = 28.dp
private val FocusedMoreIconSize = 24.dp

/** Prototype reaction rail, inert real-message preview and grouped command menu; preserves the host IME. */
@Composable
@Suppress("LongMethod", "LongParameterList")
internal fun FocusedMessageActions(
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
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = FocusedOverlayShadowSafeInset)
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
                        selectedReactions = selectedReactions,
                        onReact = onReact,
                        onMoreReactions = onMoreReactions,
                    )
                }
                if (preview != null) {
                    Box(
                        modifier =
                            Modifier
                                // The tag precedes clearAndSetSemantics, which wipes semantics set after it.
                                .testTag("message-actions-preview")
                                // Where the lifted message sits inside the stack is what the stack is
                                // placed by, so the message lands on the bubble it was lifted from.
                                .onPlaced {
                                    previewCenterInStackPx =
                                        it.positionInParent().y.roundToInt() + it.size.height / 2
                                }.focusedStackDrag(travel)
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
                FocusedActionMenu(actions)
            }
        }
    }
}

/**
 * Balances the reaction rail over as few rows as the width allows, so every configured choice keeps
 * its 48dp target instead of being pushed past a scrolling edge that announces nothing.
 *
 * A width that fits everything keeps the single row the rail has always been. Otherwise the items
 * are spread evenly — six reactions plus the full picker become 4 and 3 rather than 6 and a lone
 * trailing button.
 */
internal fun focusedReactionItemsPerRow(
    itemCount: Int,
    availableWidth: Dp,
): Int {
    if (itemCount <= 1) return itemCount.coerceAtLeast(1)
    val step = FocusedReactionTargetSize + FocusedReactionItemSpacing
    val fitting = ((availableWidth + FocusedReactionItemSpacing) / step).toInt().coerceAtLeast(1)
    // A width that holds everything leaves one row, and the even split then returns every item.
    val rows = ceil(itemCount.toFloat() / fitting).toInt()
    return ceil(itemCount.toFloat() / rows).toInt()
}

/**
 * Quick reactions on the menu-group surface: 48dp targets, 40dp state layer, 36dp selected disc, 28dp emoji.
 * The rail wraps rather than scrolling horizontally, because a sixth configured reaction used to sit
 * beyond the viewport at the compact 360dp width with no affordance that it was there at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusedReactionRail(
    quickReactions: List<String>,
    selectedReactions: Set<String>,
    onReact: (String) -> Unit,
    onMoreReactions: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(max = FocusedReactionRailMaximumWidth),
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MenuDefaults.groupStandardContainerColor,
        tonalElevation = MenuDefaults.TonalElevation,
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        BoxWithConstraints(modifier = Modifier.padding(FocusedReactionRailInset)) {
            // The full picker travels with the reactions, so it wraps with them instead of
            // competing with them for one row's worth of width.
            val perRow = focusedReactionItemsPerRow(quickReactions.size + 1, maxWidth)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FocusedReactionItemSpacing),
                verticalArrangement = Arrangement.spacedBy(FocusedReactionItemSpacing),
                maxItemsInEachRow = perRow,
            ) {
                quickReactions.forEach { emoji ->
                    FocusedReactionTarget(
                        emoji = emoji,
                        selected = emoji in selectedReactions,
                        onClick = { onReact(emoji) },
                    )
                }
                FocusedMoreReactionsTarget(onClick = onMoreReactions)
            }
        }
    }
}

/** One quick-reaction emoji target. */
@Composable
private fun FocusedReactionTarget(
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier =
            Modifier
                .size(FocusedReactionTargetSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    this.selected = selected
                    contentDescription = emoji
                }.testTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(FocusedReactionStateLayerSize)
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
                modifier = Modifier.size(FocusedReactionSelectedFillSize),
                shape = CircleShape,
                color =
                    if (selected) {
                        outlineSelectionColor(MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        Color.Transparent
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    EmojiGlyph(emoji, size = FocusedReactionEmojiSize)
                }
            }
        }
    }
}

/** The target that opens the full emoji picker. */
@Composable
private fun FocusedMoreReactionsTarget(onClick: () -> Unit) {
    val moreReactions = stringResource(R.string.open_emoji_picker)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier =
            Modifier
                .size(FocusedReactionTargetSize)
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
                    .size(FocusedReactionStateLayerSize)
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
                modifier = Modifier.size(FocusedMoreIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
