package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

private const val UNREAD_PULL_TRACKING_RESISTANCE = 0.8f
private const val UNREAD_PULL_EXTRA_RESISTANCE = 0.7f

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
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("chats.unread_filter")
                        .clickable(
                            onClickLabel = actionLabel,
                            role = Role.Button,
                            onClick = { onUnreadOnlyChange(false) },
                        ).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape)
                            .heightIn(min = 32.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.pull_unread_filtered),
                            Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "×",
                            Modifier.padding(start = 8.dp).clearAndSetSemantics { },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
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
    if (distance <= threshold) return distance * UNREAD_PULL_TRACKING_RESISTANCE
    val extra = distance - threshold
    return threshold * UNREAD_PULL_TRACKING_RESISTANCE +
        threshold * UNREAD_PULL_EXTRA_RESISTANCE * extra / (threshold + extra)
}
