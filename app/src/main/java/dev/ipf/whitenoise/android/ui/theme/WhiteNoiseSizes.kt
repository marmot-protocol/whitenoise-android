package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Component sizes shared by several screens. Values match what the app drew before they were named. */
object WhiteNoiseSizes {
    /** Spinner inside a button, chip, field or line of text. */
    val ProgressInline: Dp = 18.dp
    val ProgressInlineStroke: Dp = 2.dp

    /** Spinner in the leading slot of a settings row; equals the row's leading icon size. */
    val ProgressRowLeading: Dp = 24.dp
    val ProgressRowLeadingStroke: Dp = 2.dp

    /** Spinner that stands for a whole page or panel (the Material default indicator). */
    val ProgressPage: Dp = 48.dp
    val ProgressPageStroke: Dp = 4.dp

    /** Avatar in dense rows, chips and overlapping stacks. */
    val AvatarSmall: Dp = 32.dp

    /** Avatar in search results and compact lists. */
    val AvatarCompact: Dp = 40.dp

    /** Avatar in pickers and selectable rows. */
    val AvatarPicker: Dp = 48.dp

    /** Avatar in the chat list. */
    val AvatarChatList: Dp = 52.dp
}
