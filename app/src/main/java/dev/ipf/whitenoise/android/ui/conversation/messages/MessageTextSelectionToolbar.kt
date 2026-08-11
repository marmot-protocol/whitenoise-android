package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.ipf.whitenoise.android.R
import kotlin.math.roundToInt

/** Speak aloud action shown alongside the platform text-selection toolbar. */
@Suppress("FunctionNaming")
@Composable
internal fun MessageTextSelectionToolbar(
    visible: Boolean,
    canSpeak: Boolean,
    selectionBoundsInWindow: Rect?,
    onSpeak: () -> Unit,
) {
    if (!visible || !canSpeak || selectionBoundsInWindow == null) return
    val speakLabel = stringResource(R.string.speak_aloud)
    val estimatedHeightPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    val anchorBounds =
        remember(selectionBoundsInWindow) {
            IntRect(
                left = selectionBoundsInWindow.left.roundToInt(),
                top = selectionBoundsInWindow.top.roundToInt(),
                right = selectionBoundsInWindow.right.roundToInt(),
                bottom = selectionBoundsInWindow.bottom.roundToInt(),
            )
        }
    val positionProvider =
        remember(anchorBounds, estimatedHeightPx) {
            MessageTextSelectionToolbarPositionProvider(
                selectionBoundsInWindow = anchorBounds,
                estimatedHeightPx = estimatedHeightPx,
            )
        }
    var measured by remember(anchorBounds) { mutableStateOf(false) }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier =
                Modifier
                    .onSizeChanged { measured = it.width > 0 && it.height > 0 }
                    .graphicsLayer { alpha = if (measured) 1f else 0f }
                    .testTag("message_text_selection_toolbar"),
        ) {
            TextButton(
                onClick = onSpeak,
                modifier =
                    Modifier
                        .semantics { contentDescription = speakLabel }
                        .testTag("message_text_selection_speak"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(speakLabel, modifier = Modifier.padding(end = 4.dp))
                }
            }
        }
    }
}

private class MessageTextSelectionToolbarPositionProvider(
    private val selectionBoundsInWindow: IntRect,
    private val estimatedHeightPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val effectiveHeight = maxOf(popupContentSize.height, estimatedHeightPx)
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - effectiveHeight).coerceAtLeast(0)
        val x = selectionBoundsInWindow.left.coerceIn(0, maxX)
        val above = selectionBoundsInWindow.top - effectiveHeight
        val y =
            if (above >= 0) {
                above
            } else {
                selectionBoundsInWindow.bottom.coerceIn(0, maxY)
            }
        return IntOffset(x, y)
    }
}
