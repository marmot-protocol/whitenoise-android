@file:Suppress("FunctionNaming") // Compose UI entry points use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.KeptAttachmentKind
import dev.ipf.whitenoise.android.ui.conversation.KeptAttachmentPresentation

/** Bounded collapsed metadata, with every attachment available in the expanded scrolling card. */
@Composable
internal fun KeptAttachmentSummary(
    attachments: List<KeptAttachmentPresentation>,
    expanded: Boolean,
) {
    val visible = if (expanded) attachments else attachments.take(1)
    visible.forEach { attachment ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.thumbnail != null) {
                Image(attachment.thumbnail, null, Modifier.size(40.dp), contentScale = ContentScale.Crop)
            } else {
                val icon =
                    when (attachment.kind) {
                        KeptAttachmentKind.Image -> Icons.Default.Image
                        KeptAttachmentKind.Audio -> Icons.Default.Audiotrack
                        KeptAttachmentKind.Video -> Icons.Default.Videocam
                        KeptAttachmentKind.File -> Icons.AutoMirrored.Filled.InsertDriveFile
                    }
                Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    attachment.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${attachment.typeLabel} · ${attachment.statusLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (!expanded && attachments.size > visible.size) {
        val remaining = attachments.size - visible.size
        Text(
            pluralStringResource(R.plurals.floating_attachment_more, remaining, remaining),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
