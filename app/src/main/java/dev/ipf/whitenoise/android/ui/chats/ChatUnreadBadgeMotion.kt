package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.Animatable
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
        if (!enabled) {
            visibility.snapTo(if (current == null) 0f else 1f)
            emphasis.snapTo(1f)
        } else {
            coroutineScope {
                launch { visibility.animateTo(if (current == null) 0f else 1f, tween(120)) }
                launch {
                    if (changed && current != null) emphasis.animateTo(0.92f, tween(60))
                    emphasis.animateTo(1f, tween(100))
                }
            }
        }
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
