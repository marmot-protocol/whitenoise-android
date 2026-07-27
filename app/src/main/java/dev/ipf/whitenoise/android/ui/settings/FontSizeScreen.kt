package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleBorder
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleFillColor

// The font-size step itself is picked inline on the Appearance screen; this
// preview bubble remains for the message-chrome screenshot baselines.
// Mirrors the conversation bubble treatment (color roles, 18dp radius, AMOLED
// border) so the preview tracks what real chat text looks like at the selected
// step. Body text uses the same bodyLarge style as message bubbles.
@Composable
internal fun FontSizePreviewBubble(
    text: String,
    mine: Boolean,
) {
    val bubbleColor = messageBubbleFillColor(invalidated = false, deleted = false, mine = mine)
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart),
            color = bubbleColor,
            shape = RoundedCornerShape(18.dp),
            border = messageBubbleBorder(highlighted = false, mine = mine),
            tonalElevation = if (mine) 1.dp else 0.dp,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
