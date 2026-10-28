package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.ManualUnreadDot
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.common.unreadBadgeLabel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Chat rows opt into motion; account badges keep their existing behavior and authoritative count semantics. */
@Composable
@Suppress("FunctionNaming", "LongMethod") // Keep the visual and authoritative semantics in the same badge owner.
internal fun ChatUnreadBadgeMotion(
    unread: Boolean,
    count: ULong,
    actionColors: AccountActionColors?,
) {
    val current = count.takeIf { unread }
    val enabled = rememberSystemAnimationsEnabled()
    var retained by remember { mutableStateOf(current) }
    var previous by remember { mutableStateOf(current) }
    val visibility = remember { Animatable(if (unread) 1f else 0f) }
    val emphasis = remember { Animatable(1f) }
    LaunchedEffect(current, enabled) {
        val changed = current != previous
        previous = current
        if (current != null) retained = current
        animateUnreadBadge(current != null, changed, enabled, visibility, emphasis)
        if (current == null) retained = null
    }
    val visual = current ?: retained.takeIf { enabled } ?: return
    val labelWidth =
        rememberTextMeasurer()
            .measure("999+", MaterialTheme.typography.labelSmall)
            .size.width
    val slotWidth = with(LocalDensity.current) { labelWidth.toDp() + 8.dp }
    val accessibleCount = count.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    val description =
        if (count > 0uL) {
            pluralStringResource(R.plurals.unread_messages_count, accessibleCount, accessibleCount)
        } else {
            stringResource(R.string.chat_row_marked_unread)
        }
    Box(
        modifier =
            Modifier
                .width(slotWidth)
                .clearAndSetSemantics {
                    if (current != null) {
                        contentDescription = description
                        if (current > 0uL) text = AnnotatedString(unreadBadgeLabel(current))
                    }
                },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier.graphicsLayer {
                alpha = if (enabled) visibility.value else 1f
                scaleX = if (enabled) emphasis.value else 1f
                scaleY = scaleX
            },
        ) {
            if (visual > 0uL) {
                UnreadCountBadge(visual, actionColors = actionColors)
            } else {
                ManualUnreadDot(actionColors = actionColors)
            }
        }
    }
}

/** Visibility and count emphasis share cancellation, so a replacement count reverses from the current frame. */
private suspend fun animateUnreadBadge(
    visible: Boolean,
    changed: Boolean,
    enabled: Boolean,
    visibility: Animatable<Float, AnimationVector1D>,
    emphasis: Animatable<Float, AnimationVector1D>,
) {
    val target = if (visible) 1f else 0f
    if (!enabled) {
        visibility.snapTo(target)
        emphasis.snapTo(1f)
    } else {
        coroutineScope {
            launch { visibility.animateTo(target, tween(BADGE_VISIBILITY_MILLIS)) }
            launch {
                if (changed && visible) emphasis.animateTo(BADGE_EMPHASIS_SCALE, tween(BADGE_EMPHASIS_IN_MILLIS))
                emphasis.animateTo(1f, tween(BADGE_EMPHASIS_OUT_MILLIS))
            }
        }
    }
}

private const val BADGE_VISIBILITY_MILLIS = 120
private const val BADGE_EMPHASIS_IN_MILLIS = 60
private const val BADGE_EMPHASIS_OUT_MILLIS = 100
private const val BADGE_EMPHASIS_SCALE = 0.92f
