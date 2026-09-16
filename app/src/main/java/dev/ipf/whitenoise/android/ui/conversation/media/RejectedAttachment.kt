package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentRejectionKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape

/**
 * Placeholder for an `imeta` attachment MarmotKit rejected while parsing the message.
 *
 * It occupies the attachment's slot so the message keeps its layout and text, and it explains the
 * rejection through the typed [kind] rather than the engine's diagnostic detail string.
 */
@Suppress("FunctionNaming")
@Composable
internal fun RejectedAttachmentPlaceholder(
    kind: MediaAttachmentRejectionKindFfi,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = ConversationRichContentShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.media_attachment_rejected_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(rejectedAttachmentDetail(kind)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Maps MarmotKit's rejection categories to the two explanations the placeholder can show. */
internal fun rejectedAttachmentDetail(kind: MediaAttachmentRejectionKindFfi): Int =
    when (kind) {
        MediaAttachmentRejectionKindFfi.UNSUPPORTED_FORMAT -> R.string.media_attachment_rejected_unsupported
        MediaAttachmentRejectionKindFfi.INVALID_STRUCTURE,
        MediaAttachmentRejectionKindFfi.MISSING_FIELD,
        MediaAttachmentRejectionKindFfi.DUPLICATE_FIELD,
        MediaAttachmentRejectionKindFfi.MALFORMED_FIELD,
        -> R.string.media_attachment_rejected_malformed
    }
