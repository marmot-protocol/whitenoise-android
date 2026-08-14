@file:Suppress("FunctionNaming") // Compose UI functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

@Composable
internal fun TextAttachmentUnavailable(
    reason: TextAttachmentUnavailableReason,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = stringResource(reason.messageRes()), style = MaterialTheme.typography.bodyLarge)
        if (reason == TextAttachmentUnavailableReason.DownloadFailed) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag(TEXT_ATTACHMENT_READER_RETRY_TAG),
            ) {
                Text(stringResource(R.string.retry))
            }
        }
        TextButton(onClick = onOpenExternal) {
            Text(stringResource(R.string.text_attachment_open_external))
        }
    }
}

private fun TextAttachmentUnavailableReason.messageRes(): Int =
    when (this) {
        TextAttachmentUnavailableReason.DownloadFailed -> R.string.text_attachment_download_failed
        TextAttachmentUnavailableReason.TooLarge -> R.string.text_attachment_preview_too_large
        TextAttachmentUnavailableReason.InvalidEncoding -> R.string.text_attachment_preview_invalid_encoding
        TextAttachmentUnavailableReason.Binary -> R.string.text_attachment_preview_binary
    }
