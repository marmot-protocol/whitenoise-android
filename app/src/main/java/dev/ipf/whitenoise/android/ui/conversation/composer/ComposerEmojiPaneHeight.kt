package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The pane height drawn this frame while it replaces, or is replaced by, the keyboard. */
internal fun resolveRenderedEmojiPaneHeight(
    showEmojiPane: Boolean,
    animatedHeight: Dp,
    imePaneHeight: Dp,
): Dp =
    if (showEmojiPane) {
        maxOf(animatedHeight, imePaneHeight)
    } else {
        (animatedHeight - imePaneHeight).coerceAtLeast(0.dp)
    }
