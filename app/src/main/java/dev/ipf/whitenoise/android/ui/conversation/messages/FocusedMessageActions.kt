@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * Centers the measured stack around its frozen message anchor within the keyboard-safe popup frame.
 * The provider is remembered by the host, so the last measured content size survives the popup
 * window being torn down and re-shown on resume: the first post-resume frame lands exactly where
 * the stack was, instead of jumping once the content reports its size again.
 */
internal class FocusedMessageActionsPositionProvider(
    private val sourceBounds: IntRect?,
    private val touchY: Float?,
) : PopupPositionProvider {
    private var lastContentSize: IntSize = IntSize.Zero

    /** Positions the actions above or below the focused bubble within the window. */
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        @Suppress("NAME_SHADOWING")
        val popupContentSize = stableContentSize(popupContentSize)
        val desiredY =
            (sourceBounds?.center?.y ?: touchY?.roundToInt() ?: (windowSize.height / 2)) -
                popupContentSize.height / 2
        return IntOffset(
            x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
            y = desiredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }

    /** Remembers a measured size and substitutes it for the zero size of a not-yet-measured frame. */
    private fun stableContentSize(measured: IntSize): IntSize =
        if (measured.width > 0 && measured.height > 0) {
            measured.also { lastContentSize = it }
        } else {
            lastContentSize
        }
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
private val FocusedMenuMinimumWidth = 248.dp
private val FocusedMenuMaximumWidth = 300.dp
private val FocusedMenuMinimumCellWidth = 123.dp
private val FocusedActionCellMinimumHeight = 48.dp
private val FocusedActionCellVerticalPadding = 8.dp
private val FocusedActionStackedLabelSpacing = 4.dp
private const val FOCUSED_SUPPORTING_LABEL_MAX_LINES = 2

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
    val position = remember(sourceBounds, touchY) { FocusedMessageActionsPositionProvider(sourceBounds, touchY) }
    val title = stringResource(R.string.message_actions)
    val close = stringResource(R.string.close)
    // Saveable so a popup window re-shown after an app switch does not hide content it already
    // revealed; the value lives in the host composition, not in the popup's own.
    var measured by rememberSaveable { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    KeyboardSafePopup(
        expanded = true,
        onDismissRequest = onDismiss,
        popupPositionProvider = position,
        scrimModifier =
            Modifier.background(
                MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = FOCUSED_BACKDROP_ALPHA),
            ),
    ) {
        BoxWithConstraints {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = FocusedStackMaximumWidth)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .onSizeChanged { measured = it.width > 0 && it.height > 0 }
                        .graphicsLayer { alpha = if (measured && previewReady) 1f else 0f }
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
                                .clearAndSetSemantics { contentDescription = previewDescription }
                                // The preview is inert: it neither dismisses nor acts on a tap.
                                .pointerInput(Unit) { detectTapGestures { } },
                    ) { preview() }
                }
                FocusedActionMenu(actions)
            }
        }
    }
}

/** Quick reactions on the menu-group surface: 48dp targets, 40dp state layer, 36dp selected disc, 28dp emoji. */
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
        Row(
            modifier = Modifier.padding(FocusedReactionRailInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FocusedReactionItemSpacing),
            ) {
                quickReactions.forEach { emoji ->
                    FocusedReactionTarget(
                        emoji = emoji,
                        selected = emoji in selectedReactions,
                        onClick = { onReact(emoji) },
                    )
                }
            }
            FocusedMoreReactionsTarget(onClick = onMoreReactions)
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

/** Material's bodyLarge size before any text-size preference is applied. */
private const val BODY_LARGE_SP = 16f

/**
 * Material's grouped menu laid out as a compact grid: two cells per row when at least 248dp is
 * available and the font scale is below 1.5, one column otherwise. Delete keeps its error colours
 * and sits alone on the last row, spanning the full width. Container, group shapes and colours are
 * unchanged from the single-column menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusedActionMenu(actions: List<FocusedMessageAction>) {
    // The app's text-size preference scales the typography itself rather than the density, so the
    // effective scale is the system font scale times how far the body style has grown.
    val typographyScale = MaterialTheme.typography.bodyLarge.fontSize.value / BODY_LARGE_SP
    val fontScale = LocalDensity.current.fontScale * typographyScale
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShapes(),
        border = amoledOutlineBorder(),
        modifier = Modifier.widthIn(min = FocusedMenuMinimumWidth, max = FocusedMenuMaximumWidth),
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        BoxWithConstraints {
            val columns = messageActionColumnCount(maxWidth, FocusedMenuMinimumCellWidth, fontScale)
            val rows = messageActionGridRows(actions, columns) { it.destructive }
            Column(verticalArrangement = Arrangement.spacedBy(messageActionColumnGap)) {
                rows.forEachIndexed { rowIndex, row ->
                    val shape = MenuDefaults.itemShape(rowIndex, rows.size).shape
                    Row(horizontalArrangement = Arrangement.spacedBy(messageActionColumnGap)) {
                        row.forEach { action ->
                            FocusedActionCell(
                                action = action,
                                stacked = columns > 1 && !action.destructive,
                                shape = shape,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size < columns && row.none { it.destructive }) {
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** One grid cell: Material's menu item, its icon stacked above a one-line label when two cells share a row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusedActionCell(
    action: FocusedMessageAction,
    stacked: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val contentColor =
        if (action.destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    DropdownMenuItem(
        text = { FocusedActionLabel(action, stacked) },
        onClick = action.onClick,
        shape = shape,
        leadingIcon = if (stacked) null else action.icon,
        enabled = action.enabled,
        colors =
            MenuDefaults.itemColors(
                textColor = contentColor,
                leadingIconColor = contentColor,
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        modifier = modifier.heightIn(min = FocusedActionCellMinimumHeight),
    )
}

/** The cell's label block: icon above a centred one-line label when stacked, beside it otherwise. */
@Composable
private fun FocusedActionLabel(
    action: FocusedMessageAction,
    stacked: Boolean,
) {
    Column(
        modifier =
            if (stacked) {
                Modifier.fillMaxWidth().padding(vertical = FocusedActionCellVerticalPadding)
            } else {
                Modifier
            },
        horizontalAlignment = if (stacked) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (stacked) FocusedActionStackedLabelSpacing else 0.dp),
    ) {
        if (stacked) action.icon()
        // A grid cell keeps its label on one line; the single column is the large-text fallback
        // and must let a long label wrap rather than clip it.
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = if (stacked) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (stacked) TextAlign.Center else null,
        )
        if (action.supportingLabel != null) {
            Text(
                text = action.supportingLabel,
                style = MaterialTheme.typography.bodySmall,
                maxLines = FOCUSED_SUPPORTING_LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (stacked) TextAlign.Center else null,
            )
        }
    }
}
