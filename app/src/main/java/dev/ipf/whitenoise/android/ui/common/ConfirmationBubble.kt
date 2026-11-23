package dev.ipf.whitenoise.android.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

private const val BUBBLE_ENTER_MILLIS = 180
private const val BUBBLE_EXIT_MILLIS = 140

/** Anchors a visual-only bubble below the slot without giving its height to the content below it. */
@Composable
@Suppress("FunctionNaming")
internal fun AnchoredConfirmationBubble(
    notice: TransientNotice?,
    modifier: Modifier = Modifier,
) {
    val motion = rememberSystemAnimationsEnabled()
    Layout(
        content = {
            Box(modifier, contentAlignment = Alignment.TopCenter) {
                if (motion) {
                    AnimatedContent(
                        targetState = notice,
                        contentKey = { it != null },
                        contentAlignment = Alignment.TopCenter,
                        transitionSpec = {
                            (
                                fadeIn(tween(BUBBLE_ENTER_MILLIS, easing = AppMotionOpenEasing)) +
                                    scaleIn(
                                        tween(BUBBLE_ENTER_MILLIS, easing = AppMotionOpenEasing),
                                        initialScale = 0.94f,
                                    )
                            ).togetherWith(
                                fadeOut(tween(BUBBLE_EXIT_MILLIS, easing = AppMotionCloseEasing)) +
                                    scaleOut(
                                        tween(BUBBLE_EXIT_MILLIS, easing = AppMotionCloseEasing),
                                        targetScale = 0.97f,
                                    ),
                            ).using(null)
                        },
                        label = "confirmation bubble",
                    ) { shown ->
                        if (shown != null) key(shown.id) { BubbleNotice(shown, current = shown === notice) }
                    }
                } else if (notice != null) {
                    key(notice.id) { BubbleNotice(notice, current = true) }
                }
            }
        },
    ) { measurables, constraints ->
        val bubble = measurables.singleOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else bubble?.width ?: 0
        layout(width, 0) {
            bubble?.placeRelative((width - bubble.width) / 2, 0)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun BubbleNotice(
    notice: TransientNotice,
    current: Boolean,
) {
    val context = LocalContext.current
    val tag = if (notice.conversation == null) GLOBAL_TRANSIENT_NOTICE_TAG else CONVERSATION_TRANSIENT_NOTICE_TAG
    ConfirmationBubble(
        title = notice.title.resolve(context),
        detail = notice.detail?.resolve(context),
        modifier =
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).then(
                if (current) {
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(tag)
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
    )
}

/** The same compact surface is used by the active app screen and full-screen media hosts. */
@Composable
@Suppress("FunctionNaming")
internal fun ConfirmationBubble(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val outline = amoledOutlineBorder()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.inverseOnSurface) {
        // Keep the overlay visual only. Material Surface itself participates in touch hit testing.
        Row(
            modifier =
                modifier
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .then(if (outline != null) Modifier.border(outline, shape) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
