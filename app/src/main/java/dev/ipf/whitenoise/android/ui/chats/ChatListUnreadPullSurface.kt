package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

private const val UnreadPullTrackingResistance = 0.8f
private const val UnreadPullExtraResistance = 0.7f

/** The pointer viewport stays stationary while the body follows the resisted pull. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun ChatListUnreadPullSurface(
    unreadOnly: Boolean,
    actionsAvailable: Boolean,
    gestureEnabled: Boolean,
    atTop: () -> Boolean,
    onUnreadOnlyChange: (Boolean) -> Unit,
    status: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var progress by remember { mutableFloatStateOf(0f) }
    var pillHeight by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val active = unreadOnly && actionsAvailable
    val bodyLabel = stringResource(R.string.chats)
    val actionLabel = stringResource(if (unreadOnly) R.string.pull_unread_show_all else R.string.pull_unread_only)
    val offset =
        animateFloatAsState(
            targetValue = (if (active) pillHeight else 0f) + unreadPullDisplacement(progress, threshold),
            animationSpec = if (progress > 0f) snap() else spring(dampingRatio = 1f, stiffness = 500f),
            label = "unread pull return",
        )
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("chats.unread_surface")
            .semantics {
                if (actionsAvailable) {
                    contentDescription = bodyLabel
                    customActions =
                        listOf(
                            CustomAccessibilityAction(actionLabel) {
                                onUnreadOnlyChange(!unreadOnly)
                                true
                            },
                        )
                }
            }.chatListUnreadPull(
                enabled = gestureEnabled,
                atTop = atTop,
                thresholdPx = threshold,
                scopeKey = unreadOnly,
                onDistance = { progress = it },
                onThreshold = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                onActivate = { onUnreadOnlyChange(!unreadOnly) },
            ),
    ) {
        // One offset controls the resting pill space and the drag, avoiding an insertion jump.
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val rest = if (active) pillHeight.toInt() else 0
                    val child =
                        measurable.measure(
                            constraints.copy(
                                minHeight = 0,
                                maxHeight = (constraints.maxHeight - rest).coerceAtLeast(0),
                            ),
                        )
                    layout(constraints.maxWidth, constraints.maxHeight) { child.place(0, 0) }
                }.graphicsLayer { translationY = offset.value.coerceAtLeast(0f) },
            content = content,
        )
        if (active) {
            Column(
                Modifier.fillMaxWidth().onSizeChanged { pillHeight = it.height.toFloat() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = { onUnreadOnlyChange(false) },
                    shape = CircleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .heightIn(min = 48.dp)
                            .testTag("chats.unread_filter")
                            .semantics { onClick(label = actionLabel, action = null) },
                ) {
                    Text(stringResource(R.string.pull_unread_filtered), Modifier.weight(1f, fill = false))
                    Text("×", Modifier.padding(start = 8.dp).clearAndSetSemantics { })
                }
                status()
            }
        }
    }
}

/** Bounded resistance; threshold recognition always uses the original finger distance. */
internal fun unreadPullDisplacement(
    progress: Float,
    threshold: Float,
): Float {
    val distance = progress.coerceAtLeast(0f) * threshold
    if (distance <= threshold) return distance * UnreadPullTrackingResistance
    val extra = distance - threshold
    return threshold * UnreadPullTrackingResistance +
        threshold * UnreadPullExtraResistance * extra / (threshold + extra)
}
