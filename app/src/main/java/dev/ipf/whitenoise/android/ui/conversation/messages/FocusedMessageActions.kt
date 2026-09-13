@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
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

/** Centers the measured stack around its frozen message anchor within the keyboard-safe popup frame. */
internal class FocusedMessageActionsPositionProvider(
    private val sourceBounds: IntRect?,
    private val touchY: Float?,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val desiredY =
            (sourceBounds?.center?.y ?: touchY?.roundToInt() ?: (windowSize.height / 2)) -
                popupContentSize.height / 2
        return IntOffset(
            x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
            y = desiredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/** Prototype reaction rail, inert real-message preview and labeled command rows; preserves the host IME. */
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
    var measured by remember { mutableStateOf(false) }
    KeyboardSafePopup(
        expanded = true,
        onDismissRequest = onDismiss,
        popupPositionProvider = position,
        scrimModifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f)),
    ) {
        BoxWithConstraints {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .onSizeChanged { measured = it.width > 0 && it.height > 0 }
                        .graphicsLayer { alpha = if (measured && previewReady) 1f else 0f }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp)
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
                    Surface(
                        modifier = Modifier.widthIn(max = 392.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = amoledSurfaceBorderStroke(),
                        shadowElevation = 3.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                quickReactions.forEach { emoji ->
                                    IconButton(
                                        onClick = { onReact(emoji) },
                                        modifier =
                                            Modifier
                                                .size(48.dp)
                                                .semantics {
                                                    selected = emoji in selectedReactions
                                                    contentDescription = emoji
                                                }.testTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji"),
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color =
                                                if (emoji in selectedReactions) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    androidx.compose.ui.graphics.Color.Transparent
                                                },
                                            modifier = Modifier.size(36.dp),
                                            border =
                                                if (emoji in selectedReactions) {
                                                    amoledSurfaceBorderStroke()
                                                } else {
                                                    null
                                                },
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    emoji,
                                                    fontSize = with(LocalDensity.current) { 28.dp.toSp() },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            IconButton(onClick = onMoreReactions, modifier = Modifier.size(48.dp)) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.MoreHoriz,
                                            contentDescription = stringResource(R.string.open_emoji_picker),
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (preview != null) {
                    Box(
                        modifier =
                            Modifier
                                .clearAndSetSemantics { contentDescription = previewDescription }
                                .testTag("message-actions-preview"),
                    ) { preview() }
                }
                Surface(
                    modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = amoledSurfaceBorderStroke(),
                    shadowElevation = 3.dp,
                ) {
                    Column {
                        actions.forEach { action ->
                            MessageActionButton(
                                label = action.label,
                                supportingLabel = action.supportingLabel,
                                icon = action.icon,
                                onClick = action.onClick,
                                enabled = action.enabled,
                                isDestructive = action.destructive,
                            )
                        }
                    }
                }
            }
        }
    }
}
