package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** Records actual row drawing in the uncovered viewport, never a prefetch measure or a hidden reveal frame. */
@Composable
internal fun Modifier.membershipVisibleDraw(
    viewport: ConversationTimelineViewport,
    onVisibleDraw: () -> Unit,
): Modifier {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    return onGloballyPositioned { bounds = it.boundsInWindow() }
        .drawWithContent {
            drawContent()
            val clear = viewport.readingBoundsInWindow
            if (clear != null && bounds?.overlaps(clear) == true) onVisibleDraw()
        }
}
