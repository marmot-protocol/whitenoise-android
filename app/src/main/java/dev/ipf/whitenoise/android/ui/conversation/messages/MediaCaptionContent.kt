package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Places full-bleed visual media above a conventionally padded caption. */
@Composable
@Suppress("FunctionNaming")
internal fun MediaCaptionContent(
    edgeToEdgeMedia: Boolean,
    alignEnd: Boolean,
    contentModifier: Modifier,
    media: @Composable ColumnScope.() -> Unit,
    caption: @Composable ColumnScope.() -> Unit,
) {
    val outerInset = ConversationMessageMetrics.RichOuterInset
    val horizontalPadding =
        ConversationMessageMetrics.RichTextHorizontalAdjustment +
            if (edgeToEdgeMedia) outerInset else 0.dp
    MediaSupplementEnvelope(
        alignEnd = alignEnd,
        modifier = if (edgeToEdgeMedia) Modifier else Modifier.padding(outerInset),
        media = media,
    ) {
        Column(modifier = contentModifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier.padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = ConversationMessageMetrics.RichContentSpacing,
                        bottom =
                            ConversationMessageMetrics.RichTextBottomAdjustment +
                                if (edgeToEdgeMedia) outerInset else 0.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = caption,
            )
        }
    }
}
