package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.FileUploadProgress
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMediaTileShape

private const val BYTES_PER_UNIT = 1024.0
private val FILE_SIZE_UNITS = listOf("KB", "MB", "GB")

/** [bytes] as a short size: whole bytes below 1 KB, then KB, MB or GB with one decimal, and blank when unknown. */
private fun formatFileSize(bytes: Long): String {
    if (bytes < BYTES_PER_UNIT) return if (bytes < 0L) "" else "$bytes B"
    var size = bytes / BYTES_PER_UNIT
    var unit = 0
    while (size >= BYTES_PER_UNIT && unit < FILE_SIZE_UNITS.lastIndex) {
        size /= BYTES_PER_UNIT
        unit++
    }
    return String.format(java.util.Locale.US, "%.1f %s", size, FILE_SIZE_UNITS[unit])
}

/**
 * Pill for a file still being sent. While [uploadProgress] is known, the ring fills with the whole send's
 * progress, the size line shows the step's short label and TalkBack reads the step with its bytes.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun PendingFilePill(
    fileName: String,
    mediaType: String,
    sizeBytes: Long,
    failed: Boolean,
    statusLabel: String,
    onRetry: (() -> Unit)? = null,
    timestampText: String? = null,
    showStatus: Boolean = false,
    status: MessageStatus = MessageStatus.Pending,
    uploadProgress: FileUploadProgress? = null,
) {
    val presentation = remember(mediaType, fileName) { resolveAttachmentPresentation(mediaType, fileName) }
    val progress = uploadProgress?.takeIf { !failed }
    val progressText = progress?.let { uploadProgressLabel(it) }
    val progressDescription = progress?.let { uploadProgressDescription(it) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = ConversationMediaTileShape,
        modifier =
            Modifier
                .fileBubbleWidth()
                .testTag(PENDING_FILE_CARD_TEST_TAG)
                .then(
                    if (failed && onRetry != null) {
                        Modifier.clip(ConversationRichContentShape).clickable(onClick = onRetry)
                    } else {
                        Modifier
                    },
                ),
    ) {
        FileBubbleContent(
            fileName = fileName,
            presentation = presentation,
            transferState =
                if (failed) {
                    AttachmentTransferState.Failed
                } else {
                    AttachmentTransferState.Downloading
                },
            metadataText =
                when {
                    failed && timestampText != null -> statusLabel
                    progressText != null -> progressText
                    else -> formatFileSize(sizeBytes)
                },
            metadataIsError = failed && timestampText != null,
            // The size line already names the upload step while bytes are known, so it is not repeated here.
            trailingMetadataText = timestampText ?: statusLabel.takeIf { progressText == null },
            trailingMetadataIsError = failed && timestampText == null,
            trailingStatus = status.takeIf { showStatus },
            loadingDescription = statusLabel,
            transferDirection = FileTransferDirection.Upload,
            progressDescription = progressDescription,
            progressFraction = progress?.ringFraction,
        )
    }
}
