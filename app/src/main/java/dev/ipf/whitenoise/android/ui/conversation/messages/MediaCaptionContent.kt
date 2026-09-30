package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Places visual media inside the prototype bubble inset above a padded caption. */
@Composable
@Suppress("FunctionNaming")
internal fun MediaCaptionContent(
    alignEnd: Boolean,
    contentModifier: Modifier,
    media: @Composable ColumnScope.() -> Unit,
    caption: @Composable ColumnScope.() -> Unit,
) {
    MediaSupplementEnvelope(
        alignEnd = alignEnd,
        modifier = Modifier.padding(ConversationMessageMetrics.RichOuterInset),
        media = media,
    ) {
        Column(modifier = contentModifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier.padding(
                        start = ConversationMessageMetrics.RichTextHorizontalAdjustment,
                        end = ConversationMessageMetrics.RichTextHorizontalAdjustment,
                        top = ConversationMessageMetrics.RichContentSpacing,
                        bottom = ConversationMessageMetrics.RichTextBottomAdjustment,
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = caption,
            )
        }
    }
}
