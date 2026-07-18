package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.selectionRowIcon
import dev.ipf.whitenoise.android.ui.theme.amoledDirectionalAccentColor

internal val messageBubbleSelectionGutterWidth = 40.dp

internal fun messageBubbleSelectionIcon(selected: Boolean): ImageVector = selectionRowIcon(selected)

@Composable
internal fun messageBubbleSelectionRowTint(selected: Boolean): Color {
    if (!selected) return Color.Transparent
    // Use the shared AMOLED message-accent resolver so bubble chrome and row
    // selection cannot disagree about whether the pure-black theme is active.
    val amoledPrimary = amoledDirectionalAccentColor(mine = true)
    return (amoledPrimary ?: MaterialTheme.colorScheme.primary).copy(
        alpha =
            if (amoledPrimary != null) {
                MESSAGE_BUBBLE_SELECTION_TINT_AMOLED_ALPHA
            } else {
                MESSAGE_BUBBLE_SELECTION_TINT_ALPHA
            },
    )
}

@Composable
internal fun Modifier.messageBubbleSelectionRow(
    selectionMode: Boolean,
    selected: Boolean,
): Modifier =
    if (selectionMode) {
        background(messageBubbleSelectionRowTint(selected))
    } else {
        this
    }

@Composable
internal fun BoxScope.MessageBubbleSelectionTapTarget(
    selected: Boolean,
    batchSelectable: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .matchParentSize()
                .semantics { this.selected = selected }
                .clickable(enabled = batchSelectable, onClick = onToggleSelection),
    )
}

@Composable
internal fun MessageBubbleSelectionGutter(
    batchSelectable: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(messageBubbleSelectionGutterWidth),
        contentAlignment = Alignment.Center,
    ) {
        if (batchSelectable) {
            Icon(
                imageVector = messageBubbleSelectionIcon(selected),
                contentDescription = null,
                tint =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private const val MESSAGE_BUBBLE_SELECTION_TINT_ALPHA = 0.24f
private const val MESSAGE_BUBBLE_SELECTION_TINT_AMOLED_ALPHA = 0.32f
