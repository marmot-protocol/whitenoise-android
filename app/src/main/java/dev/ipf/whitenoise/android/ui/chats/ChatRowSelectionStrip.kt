package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlin.math.roundToInt

/** Only the measured leading strip moves; the real row owns selection semantics from the first frame. */
@Composable
@Suppress("FunctionNaming")
internal fun ChatRowSelectionStrip(
    selectionMode: Boolean,
    selected: Boolean,
) {
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val target = if (selectionMode) 1f else 0f
    val progress =
        animateFloatAsState(
            target,
            if (animationsEnabled) tween(160) else snap(),
            label = "chat selection strip",
        )
    // Ordinary rows compose no checkbox; an exiting strip stays until its width reaches zero.
    val exiting by remember(progress) { derivedStateOf { progress.value > 0f } }
    if (!selectionMode && !exiting) return
    Layout(
        content = { ChatRowSelectionIndicator(selected) },
        modifier = Modifier.clipToBounds(),
    ) { measurables, constraints ->
        val indicatorSize = 24.dp.roundToPx()
        val fullWidth = indicatorSize + 8.dp.roundToPx()
        val indicator = measurables.single().measure(Constraints.fixed(indicatorSize, indicatorSize))
        val fraction = if (animationsEnabled) progress.value else target
        val width = (fullWidth * fraction).roundToInt().coerceIn(0, constraints.maxWidth)
        layout(width, indicator.height) {
            indicator.placeRelative(width - fullWidth, 0)
        }
    }
}
