package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** The prototype's rich-content geometry inside a bubble: insets, spacing, corner radius and canvas width. */
internal object ConversationMessageMetrics {
    val RichOuterInset = 6.dp
    val RichContentSpacing = 6.dp
    val RichTextHorizontalAdjustment = 6.dp
    val RichTextBottomAdjustment = 2.dp
    val RichComponentInset = 6.dp
    val GallerySpacing = 2.dp
    val RichComponentCornerRadius = 10.dp

    /** A lone photo or video keeps its own width; every other rich component shares this canvas. */
    val RichContentCanvasWidth = 256.dp
    val GifHeight = 188.dp
}

/** Rounded corners for media, cards and galleries inside a bubble. */
internal val ConversationRichContentShape = RoundedCornerShape(ConversationMessageMetrics.RichComponentCornerRadius)
