package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.ui.theme.amoledDirectionalAccentColor
import dev.ipf.whitenoise.android.ui.theme.messageFooterLabelColor

/**
 * Colour for the timestamp and delivery glyph in an ordinary bubble's footer. It reads as secondary
 * metadata rather than body text: a quiet gray that still clears 4.5:1 against the bubble fill. A
 * persisted failure keeps the error pairing, and the AMOLED theme keeps its directional accent.
 * Media scrim footers stay white on black and never route through here.
 */
@Composable
internal fun messageBubbleFooterColor(
    mine: Boolean,
    persistedFailure: Boolean,
    bubbleBackgroundColor: Color,
    bubbleContentColor: Color,
): Color =
    when {
        persistedFailure -> MaterialTheme.colorScheme.onErrorContainer
        else ->
            amoledDirectionalAccentColor(mine)
                ?: messageFooterLabelColor(bubbleBackgroundColor, bubbleContentColor)
    }
