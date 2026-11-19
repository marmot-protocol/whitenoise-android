package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.LocalMessageBubbleBaseColorScheme
import dev.ipf.whitenoise.android.ui.theme.amoledDirectionalAccentColor
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Draws semantic, saved-colour, or directional AMOLED borders in that precedence order. */
@Composable
internal fun messageBubbleBorder(
    highlighted: Boolean,
    mine: Boolean,
    persistedFailure: Boolean = false,
    customBorderArgb: Long? = null,
    mentionAccentArgb: Long? = null,
): BorderStroke? {
    val amoledAccent = amoledDirectionalAccentColor(mine)
    return when {
        persistedFailure -> null
        highlighted -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        mentionAccentArgb != null && amoledAccent != null -> BorderStroke(2.dp, colorFromArgb(mentionAccentArgb))
        customBorderArgb != null && amoledAccent != null -> BorderStroke(2.dp, colorFromArgb(customBorderArgb))
        amoledAccent != null -> BorderStroke(2.dp, amoledAccent)
        else -> null
    }
}

/** Accent for a reply preview, inside or beside the bubble, honouring custom bubble colours. */
internal fun replyPreviewAccentArgb(
    insideBubble: Boolean,
    customBubbleColorActive: Boolean,
    presentation: BubblePresentation,
): Long? =
    if (customBubbleColorActive) {
        presentation.borderOverrideArgb ?: presentation.contentArgb.takeIf { insideBubble }
    } else {
        null
    }

/** Bubble colours and shape for the direction, deletion state and custom colour. */
@Composable
internal fun messageBubblePresentation(
    deleted: Boolean,
    mine: Boolean,
    customArgb: Long? = null,
    persistedFailure: Boolean = false,
): BubblePresentation {
    val colorScheme = MaterialTheme.colorScheme
    val bubbleScheme = LocalMessageBubbleBaseColorScheme.current ?: colorScheme
    // Keep tombstone and AMOLED foregrounds stable; live bubbles use the prototype's paired roles.
    val preserveSemanticSurface = deleted || isAmoledSurfaceTheme()
    val surfaceBackground =
        if (preserveSemanticSurface) {
            colorScheme.surfaceVariant
        } else {
            bubbleScheme.surfaceContainerHigh
        }
    val surfaceContent = if (preserveSemanticSurface) colorScheme.onSurfaceVariant else bubbleScheme.onSurface
    return resolveBubblePresentationArgb(
        deleted = deleted,
        amoled = isAmoledSurfaceTheme(),
        mine = mine,
        customArgb = customArgb,
        tokens =
            BubblePresentationTokens(
                errorBackgroundArgb = colorScheme.errorContainer.toArgb().toLong() and 0xFFFFFFFFL,
                errorContentArgb = colorScheme.onErrorContainer.toArgb().toLong() and 0xFFFFFFFFL,
                surfaceBackgroundArgb = surfaceBackground.toArgb().toLong() and 0xFFFFFFFFL,
                surfaceContentArgb = surfaceContent.toArgb().toLong() and 0xFFFFFFFFL,
                mineBackgroundArgb = bubbleScheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
                mineContentArgb = bubbleScheme.onPrimary.toArgb().toLong() and 0xFFFFFFFFL,
                mentionAccentArgb = colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
            ),
        persistedFailure = persistedFailure,
    )
}

/** Fill colour of a bubble for the direction, deletion state and custom colour. */
@Composable
internal fun messageBubbleFillColor(
    deleted: Boolean,
    mine: Boolean,
    persistedFailure: Boolean = false,
): Color = colorFromArgb(messageBubblePresentation(deleted, mine, persistedFailure = persistedFailure).backgroundArgb)

/** Footer time colour for the direction and state. */
@Composable
internal fun messageBubbleTimestampColor(
    mine: Boolean,
    deleted: Boolean,
    persistedFailure: Boolean = false,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    val amoledAccent = amoledDirectionalAccentColor(mine)
    return when {
        persistedFailure -> colorScheme.onErrorContainer
        amoledAccent != null -> amoledAccent
        deleted -> colorScheme.onSurfaceVariant
        else -> colorFromArgb(messageBubblePresentation(deleted = false, mine = mine).contentArgb)
    }
}
