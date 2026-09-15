@file:Suppress("FunctionNaming") // Compose UI entry points use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlin.math.roundToInt

/** Everything the card needs to render one kept message without reaching into the timeline. */
@Immutable
internal data class KeptMessagePresentation(
    val authorName: String,
    val body: String,
    val chatTitle: String,
    val timeLabel: String,
)

/** The kept-message stack this overlay is drawing, and who owns it. */
@Immutable
internal data class KeptMessagesOverlayState(
    val entries: List<KeptMessageEntry>,
    val controller: KeptMessagesController,
    val accountRef: String,
)

/**
 * The floating card that keeps chosen messages above the transcript. It is
 * draggable, snaps to whichever side edge it is released nearer, and toggles
 * between a four-line preview and a scrollable expanded view on long-press.
 * An empty stack draws nothing.
 */
@Composable
internal fun KeptMessagesOverlay(
    state: KeptMessagesOverlayState,
    composerHeight: Dp,
    presentation: (KeptMessageEntry) -> KeptMessagePresentation,
    onOpenMessage: (KeptMessageKey) -> Unit,
) {
    if (state.entries.isEmpty()) return
    val placement = rememberKeptMessagePlacement(state.accountRef)
    var expanded by rememberSaveable(state.accountRef) { mutableStateOf(false) }
    // A host without a back dispatcher — a preview or a test harness — simply
    // has no back gesture for the expanded card to intercept.
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = expanded) { expanded = false }
    }
    val bottomInset = composerHeight.coerceAtLeast(KeptMessageMetrics.MinimumBottomInset)
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(
                    start = KeptMessageMetrics.SideMargin,
                    end = KeptMessageMetrics.SideMargin,
                    top = KeptMessageMetrics.TopInset,
                    bottom = bottomInset + KeptMessageMetrics.BottomGap,
                ),
        contentAlignment = Alignment.TopStart,
    ) {
        KeptMessageCard(
            state = state,
            placement = placement,
            display =
                KeptMessageDisplay(
                    expanded = expanded,
                    showPreview = maxHeight >= KeptMessageMetrics.MinimumPreviewHeight,
                    onChange = { expanded = it },
                ),
            bounds = KeptMessageBounds(constraints.maxWidth, constraints.maxHeight, maxHeight),
            content = KeptMessageCardContent(presentation, onOpenMessage),
        )
    }
}

/**
 * How the card is displayed: whether it is expanded, whether there is even room
 * for the collapsed preview, and the one way to change the expansion.
 */
@Immutable
internal data class KeptMessageDisplay(
    val expanded: Boolean,
    val showPreview: Boolean,
    val onChange: (Boolean) -> Unit,
)

/** The space the card may occupy, in pixels for placement and dp for the height cap. */
@Immutable
internal data class KeptMessageBounds(
    val widthPx: Int,
    val heightPx: Int,
    val availableHeight: Dp,
)

/** How the card turns an entry into text, and what a jump-to-message tap does. */
@Immutable
internal data class KeptMessageCardContent(
    val presentation: (KeptMessageEntry) -> KeptMessagePresentation,
    val onOpenMessage: (KeptMessageKey) -> Unit,
)

/** The card surface: shape, elevation, placement offset, and the long-press expansion toggle. */
@Composable
private fun KeptMessageCard(
    state: KeptMessagesOverlayState,
    placement: KeptMessagePlacement,
    display: KeptMessageDisplay,
    bounds: KeptMessageBounds,
    content: KeptMessageCardContent,
) {
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val availableHeight = bounds.heightPx.toFloat()
    val maxX = (bounds.widthPx - cardSize.width).coerceAtLeast(0).toFloat()
    val maxY = (bounds.heightPx - cardSize.height).coerceAtLeast(0).toFloat()
    val top = (placement.verticalFraction * availableHeight).coerceIn(0f, maxY)
    val expandedHeight = bounds.availableHeight.coerceAtMost(KeptMessageMetrics.MaximumExpandedHeight)
    val toggle = { display.onChange(!display.expanded) }
    val toggleLabel =
        stringResource(if (display.expanded) R.string.collapse_message else R.string.floating_expand)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = amoledOutlineBorder(),
        shadowElevation = KeptMessageMetrics.Elevation,
        modifier =
            Modifier
                .absoluteOffset { IntOffset((placement.horizontalFraction * maxX).roundToInt(), top.roundToInt()) }
                .fillMaxWidth()
                .then(if (display.expanded) Modifier.heightIn(max = expandedHeight) else Modifier)
                .onSizeChanged { cardSize = it }
                .testTag(KEPT_MESSAGE_CARD_TAG),
    ) {
        Box(
            modifier =
                Modifier
                    .pointerInput(toggle) { detectTapGestures(onLongPress = { toggle() }) }
                    .semantics {
                        onLongClick(toggleLabel) {
                            toggle()
                            true
                        }
                    },
        ) {
            KeptMessageCardColumn(
                state = state,
                placement = placement,
                display = display,
                drag = Modifier.keptMessageDragHandle(placement, maxX, availableHeight, maxY),
                content = content,
            )
        }
    }
}

/** Header plus body, with the body dropped when the card has nowhere to show it. */
@Composable
private fun KeptMessageCardColumn(
    state: KeptMessagesOverlayState,
    placement: KeptMessagePlacement,
    display: KeptMessageDisplay,
    drag: Modifier,
    content: KeptMessageCardContent,
) {
    val entries = state.entries
    val index = entries.indexOfFirst { it.key == state.controller.selected(state.accountRef) }.coerceAtLeast(0)
    Column(
        modifier =
            if (display.expanded) {
                Modifier.fillMaxWidth().testTag(KEPT_MESSAGE_EXPANDED_TAG)
            } else {
                Modifier
            },
    ) {
        KeptMessageCardHeader(
            state = state,
            placement = placement,
            display = display,
            drag = drag,
            onOpenMessage = content.onOpenMessage,
        )
        if (!display.expanded && !display.showPreview) return@Column
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (display.expanded) Modifier.weight(1f, fill = false) else Modifier)
                    .padding(
                        start = KeptMessageMetrics.ContentInset,
                        end = KeptMessageMetrics.ContentInset,
                        bottom = KeptMessageMetrics.ContentInset,
                    ),
            verticalAlignment = Alignment.Top,
        ) {
            if (entries.size > 1) {
                Box(Modifier.padding(end = KeptMessageMetrics.PaginationGap)) {
                    KeptMessagePagination(index, entries.size)
                }
            }
            KeptMessagePager(
                entries = entries,
                selectedIndex = index,
                modifier = Modifier.weight(1f),
                onSelect = state.controller::select,
            ) { entry ->
                KeptMessageBody(content.presentation(entry), display.expanded)
            }
        }
    }
}

/** The 48.dp drag handle carrying the title, the overflow menu and the collapse control. */
@Composable
private fun KeptMessageCardHeader(
    state: KeptMessagesOverlayState,
    placement: KeptMessagePlacement,
    display: KeptMessageDisplay,
    drag: Modifier,
    onOpenMessage: (KeptMessageKey) -> Unit,
) {
    val entries = state.entries
    val index = entries.indexOfFirst { it.key == state.controller.selected(state.accountRef) }.coerceAtLeast(0)
    val position = stringResource(R.string.floating_position, index + 1, entries.size)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(drag)
                .heightIn(min = KeptMessageMetrics.HeaderMinimumHeight)
                .padding(start = KeptMessageMetrics.ContentInset)
                .semantics { if (entries.size > 1) stateDescription = position }
                .testTag(KEPT_MESSAGE_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.floating_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        KeptMessageMenu(
            onOpenMessage = { state.controller.selected(state.accountRef)?.let(onOpenMessage) },
            onRemove = { state.controller.selected(state.accountRef)?.let(state.controller::remove) },
            onClear = { state.controller.clear(state.accountRef) },
            onMoveSide = { placement.horizontalFraction = 1f - placement.horizontalFraction },
            onMovePlacement = { top -> placement.verticalFraction = if (top) 0f else 1f },
        )
        if (display.expanded) {
            IconButton(
                onClick = { display.onChange(false) },
                modifier = Modifier.testTag(KEPT_MESSAGE_COLLAPSE_TAG),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_fullscreen),
                    contentDescription = stringResource(R.string.collapse_message),
                )
            }
        }
    }
}

/**
 * One page of the card. Collapsed it is a four-line plain preview; expanded it
 * names the author, scrolls the full body, and closes with where the message
 * came from and when.
 */
@Composable
private fun KeptMessageBody(
    presentation: KeptMessagePresentation,
    expanded: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .testTag(KEPT_MESSAGE_PREVIEW_TAG),
    ) {
        if (expanded) {
            Text(
                text = presentation.authorName,
                modifier = Modifier.testTag(KEPT_MESSAGE_AUTHOR_TAG),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.padding(top = KEPT_MESSAGE_BODY_GAP)) {
                Text(text = presentation.body, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.floating_source, presentation.chatTitle, presentation.timeLabel),
                modifier = Modifier.padding(top = KEPT_MESSAGE_BODY_GAP).testTag(KEPT_MESSAGE_SOURCE_TAG),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = presentation.body,
                modifier = Modifier.testTag(KEPT_MESSAGE_TEXT_TAG),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = KeptMessageMetrics.COLLAPSED_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal const val KEPT_MESSAGE_CARD_TAG = "kept-message.card"
internal const val KEPT_MESSAGE_HEADER_TAG = "kept-message.header"
internal const val KEPT_MESSAGE_EXPANDED_TAG = "kept-message.expanded"
internal const val KEPT_MESSAGE_COLLAPSE_TAG = "kept-message.collapse"
internal const val KEPT_MESSAGE_PREVIEW_TAG = "kept-message.preview"
internal const val KEPT_MESSAGE_AUTHOR_TAG = "kept-message.author"
internal const val KEPT_MESSAGE_SOURCE_TAG = "kept-message.source"
internal const val KEPT_MESSAGE_TEXT_TAG = "kept-message.text"
private val KEPT_MESSAGE_BODY_GAP = 8.dp
