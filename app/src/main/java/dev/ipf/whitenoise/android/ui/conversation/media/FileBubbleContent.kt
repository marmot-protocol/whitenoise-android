@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.isTransferInProgress
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageRetentionIndicatorSlot
import dev.ipf.whitenoise.android.ui.conversation.messages.OutgoingMessageStatusIcon
import dev.ipf.whitenoise.android.ui.conversation.messages.RetentionIndicatorInput
import dev.ipf.whitenoise.android.ui.conversation.messages.RetentionIndicatorPresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberRetentionIndicatorPresentation
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

private val FileTransferControlSize = 48.dp
private val FileTransferControlSurfaceSize = 40.dp
private val FileTrailingMetadataMaxWidth = 96.dp
private val FileTrailingMetadataWithStatusMaxWidth = 112.dp
private val FileTrailingMetadataWithRetentionMaxWidth = 113.dp
private val FileTrailingMetadataWithRetentionAndStatusMaxWidth = 132.dp
private val FileTimestampWithStatusMaxWidth = 92.dp

internal enum class FileTransferDirection {
    Download,
    Upload,
}

/** File-card presentation kept separate from transfer/lifecycle ownership for deterministic UI coverage. */
@Composable
internal fun MediaFileBubbleContent(
    reference: MediaAttachmentReferenceFfi,
    presentation: AttachmentPresentation,
    transferState: AttachmentTransferState,
    openPending: Boolean = false,
    timestampText: String? = null,
    showStatus: Boolean = false,
    status: MessageStatus = MessageStatus.Received,
    retention: RetentionIndicatorInput? = null,
    reserveRetentionSpace: Boolean = false,
    footerWarningText: String? = null,
    retentionClockMillis: () -> Long = System::currentTimeMillis,
    onCancelTransfer: (() -> Unit)? = null,
) {
    FileBubbleContent(
        fileName = reference.fileName,
        presentation = presentation,
        transferState = transferState,
        openPending = openPending,
        metadataText = attachmentTypeLabel(presentation),
        metadataIsError = transferState == AttachmentTransferState.Failed,
        trailingMetadataText = timestampText,
        trailingMetadataIsError = false,
        trailingStatus = status.takeIf { showStatus },
        retention = retention,
        reserveRetentionSpace = reserveRetentionSpace,
        footerWarningText = footerWarningText,
        retentionClockMillis = retentionClockMillis,
        loadingDescription = stringResource(R.string.media_downloading),
        openingDescription = stringResource(R.string.media_opening),
        transferDirection = FileTransferDirection.Download,
        onCancelTransfer = onCancelTransfer,
    )
}

/** Shared chrome whose fixed control and text rows do not resize after transfer reconciliation. */
@Composable
internal fun FileBubbleContent(
    fileName: String,
    presentation: AttachmentPresentation,
    transferState: AttachmentTransferState,
    openPending: Boolean = false,
    metadataText: String,
    metadataIsError: Boolean,
    trailingMetadataText: String?,
    trailingMetadataIsError: Boolean,
    trailingStatus: MessageStatus?,
    retention: RetentionIndicatorInput? = null,
    reserveRetentionSpace: Boolean = false,
    footerWarningText: String? = null,
    retentionClockMillis: () -> Long = System::currentTimeMillis,
    loadingDescription: String,
    openingDescription: String = stringResource(R.string.media_opening),
    transferDirection: FileTransferDirection,
    onCancelTransfer: (() -> Unit)? = null,
) {
    val retentionPresentation = rememberRetentionIndicatorPresentation(retention, retentionClockMillis)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        FileTransferControl(
            presentation = presentation,
            transferState = transferState,
            loadingDescription = loadingDescription,
            direction = transferDirection,
            openPending = openPending,
            openingDescription = openingDescription,
            onCancelTransfer = onCancelTransfer,
        )
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.weight(1f).heightIn(min = FileTransferControlSize),
        ) {
            Text(
                text = MediaPipeline.safeDisplayName(fileName),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrLtr),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FileMetadataRow(
                metadataText = metadataText,
                metadataIsError = metadataIsError,
                trailingMetadataText = trailingMetadataText,
                trailingMetadataIsError = trailingMetadataIsError,
                trailingStatus = trailingStatus,
                retentionPresentation = retentionPresentation,
                reserveRetentionSpace = reserveRetentionSpace,
                footerWarningText = footerWarningText,
            )
        }
    }
}

/** Keeps an optional warning and the trailing timestamp/status block in one bounded file footer. */
@Composable
private fun FileMetadataRow(
    metadataText: String,
    metadataIsError: Boolean,
    trailingMetadataText: String?,
    trailingMetadataIsError: Boolean,
    trailingStatus: MessageStatus?,
    retentionPresentation: RetentionIndicatorPresentation,
    reserveRetentionSpace: Boolean,
    footerWarningText: String?,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        footerWarningText?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = metadataText,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color =
                    if (metadataIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val hasRetentionMetadata =
                reserveRetentionSpace || retentionPresentation !is RetentionIndicatorPresentation.Hidden
            val hasTrailingMetadata =
                trailingMetadataText != null || trailingStatus != null || hasRetentionMetadata
            if (hasTrailingMetadata) {
                FileTrailingMetadata(
                    text = trailingMetadataText,
                    isError = trailingMetadataIsError,
                    status = trailingStatus,
                    retentionPresentation = retentionPresentation,
                    reserveRetentionSpace = reserveRetentionSpace,
                )
            }
        }
    }
}

@Composable
private fun FileTrailingMetadata(
    text: String?,
    isError: Boolean,
    status: MessageStatus?,
    retentionPresentation: RetentionIndicatorPresentation,
    reserveRetentionSpace: Boolean,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val showRetention =
        reserveRetentionSpace || retentionPresentation !is RetentionIndicatorPresentation.Hidden
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            Modifier.widthIn(
                max =
                    when {
                        showRetention && status != null -> FileTrailingMetadataWithRetentionAndStatusMaxWidth
                        showRetention -> FileTrailingMetadataWithRetentionMaxWidth
                        status != null -> FileTrailingMetadataWithStatusMaxWidth
                        else -> FileTrailingMetadataMaxWidth
                    },
            ),
    ) {
        if (showRetention) {
            MessageRetentionIndicatorSlot(
                presentation = retentionPresentation,
                color = color,
                reserveSpace = reserveRetentionSpace,
            )
        }
        text?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color = color,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.widthIn(
                        max = if (status == null) FileTrailingMetadataMaxWidth else FileTimestampWithStatusMaxWidth,
                    ),
            )
        }
        status?.let { OutgoingMessageStatusIcon(it, tint = color) }
    }
}

/**
 * One fixed control slot keeps every transfer state the same size and exposes
 * one clear affordance.
 *
 * While a download is queued or running and [onCancelTransfer] is supplied, the
 * slot becomes the cancel target: the whole 48 dp box takes the click, so a tap
 * on the control cancels while a tap anywhere else on the card keeps its own
 * open/download behavior. The node still describes the transfer state, and the
 * click label names the cancel action for TalkBack.
 */
@Composable
internal fun FileTransferControl(
    presentation: AttachmentPresentation,
    transferState: AttachmentTransferState,
    loadingDescription: String = stringResource(R.string.media_downloading),
    direction: FileTransferDirection = FileTransferDirection.Download,
    openPending: Boolean = false,
    openingDescription: String = stringResource(R.string.media_opening),
    onCancelTransfer: (() -> Unit)? = null,
) {
    val cancelAction =
        onCancelTransfer.takeIf {
            direction == FileTransferDirection.Download && transferState.isTransferInProgress()
        }
    val colors = fileTransferControlColors(transferState)
    val cancelDescription = stringResource(R.string.media_cancel_download)
    val stateDescription =
        if (openPending) {
            openingDescription
        } else {
            fileTransferStateDescription(transferState, loadingDescription, presentation.iconCategory)
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(FileTransferControlSize)
                .then(
                    if (cancelAction != null) {
                        Modifier.clickable(
                            onClickLabel = cancelDescription,
                            role = Role.Button,
                            onClick = cancelAction,
                        )
                    } else {
                        Modifier
                    },
                ).semantics(mergeDescendants = true) { contentDescription = stateDescription },
    ) {
        Surface(
            shape = CircleShape,
            color = colors.container,
            contentColor = colors.content,
            border = if (transferState == AttachmentTransferState.Available) amoledSurfaceBorderStroke() else null,
            modifier = Modifier.size(FileTransferControlSurfaceSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                FileTransferIcon(
                    presentation = presentation,
                    state = transferState,
                    direction = direction,
                    contentColor = colors.content,
                    openPending = openPending,
                    showCancelGlyph = cancelAction != null,
                )
            }
        }
    }
}

private data class FileTransferControlColors(
    val container: Color,
    val content: Color,
)

@Composable
private fun fileTransferControlColors(state: AttachmentTransferState): FileTransferControlColors =
    when (state) {
        AttachmentTransferState.Failed ->
            FileTransferControlColors(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        AttachmentTransferState.Available ->
            FileTransferControlColors(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else ->
            FileTransferControlColors(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
    }

@Composable
private fun fileTransferStateDescription(
    state: AttachmentTransferState,
    loadingDescription: String,
    category: AttachmentIconCategory,
): String =
    when (state) {
        AttachmentTransferState.Resolving -> stringResource(R.string.media_preparing_download)
        AttachmentTransferState.Downloading -> loadingDescription
        AttachmentTransferState.Failed -> stringResource(R.string.media_tap_to_retry)
        AttachmentTransferState.Cancelled -> stringResource(R.string.media_download_cancelled)
        AttachmentTransferState.Remote,
        AttachmentTransferState.NotRetained,
        -> stringResource(R.string.media_tap_to_download)
        AttachmentTransferState.Available -> attachmentTypeDescription(category)
    }

@Composable
private fun FileTransferIcon(
    presentation: AttachmentPresentation,
    state: AttachmentTransferState,
    direction: FileTransferDirection,
    contentColor: Color,
    openPending: Boolean,
    showCancelGlyph: Boolean,
) {
    // A tap-to-open download is the commonest cancellable case, so the cancel
    // glyph must win over the generic opening chrome; without this the only
    // discoverable affordance would be the TalkBack click label.
    if (openPending && !showCancelGlyph) {
        CircularProgressIndicator(
            modifier = Modifier.size(FileTransferControlSurfaceSize),
            strokeWidth = 2.5.dp,
            color = contentColor,
        )
        Icon(
            imageVector = fileIconFor(presentation.iconCategory),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        return
    }
    when (state) {
        AttachmentTransferState.Resolving,
        AttachmentTransferState.Downloading,
        -> {
            CircularProgressIndicator(
                modifier = Modifier.size(FileTransferControlSurfaceSize),
                strokeWidth = 2.5.dp,
                color = contentColor,
            )
            Icon(
                imageVector =
                    when {
                        showCancelGlyph -> Icons.Default.Close
                        direction == FileTransferDirection.Upload -> Icons.Default.ArrowUpward
                        else -> Icons.Default.ArrowDownward
                    },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        AttachmentTransferState.Failed ->
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(21.dp))
        AttachmentTransferState.Remote,
        AttachmentTransferState.NotRetained,
        AttachmentTransferState.Cancelled,
        -> Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(21.dp))
        AttachmentTransferState.Available ->
            Icon(fileIconFor(presentation.iconCategory), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

/** Localizes category fallbacks while stable format abbreviations stay concise. */
@Composable
internal fun attachmentTypeLabel(presentation: AttachmentPresentation): String =
    presentation.formatLabel
        ?: when (presentation.iconCategory) {
            AttachmentIconCategory.AndroidPackage -> stringResource(R.string.attachment_type_android_package)
            AttachmentIconCategory.Pdf -> stringResource(R.string.attachment_type_pdf)
            AttachmentIconCategory.Archive -> stringResource(R.string.attachment_type_archive)
            AttachmentIconCategory.Document -> stringResource(R.string.attachment_type_document)
            AttachmentIconCategory.Spreadsheet -> stringResource(R.string.attachment_type_spreadsheet)
            AttachmentIconCategory.Presentation -> stringResource(R.string.attachment_type_presentation)
            AttachmentIconCategory.Text -> stringResource(R.string.attachment_type_text)
            AttachmentIconCategory.Code -> stringResource(R.string.attachment_type_code)
            AttachmentIconCategory.Audio -> stringResource(R.string.attachment_type_audio)
            AttachmentIconCategory.Video -> stringResource(R.string.attachment_type_video)
            AttachmentIconCategory.Image -> stringResource(R.string.attachment_type_image)
            AttachmentIconCategory.Generic -> stringResource(R.string.attachment_type_file)
        }

@Composable
internal fun attachmentTypeDescription(category: AttachmentIconCategory): String =
    when (category) {
        AttachmentIconCategory.AndroidPackage -> stringResource(R.string.attachment_type_android_package_description)
        AttachmentIconCategory.Pdf -> stringResource(R.string.attachment_type_pdf_description)
        AttachmentIconCategory.Archive -> stringResource(R.string.attachment_type_archive)
        AttachmentIconCategory.Document -> stringResource(R.string.attachment_type_document)
        AttachmentIconCategory.Spreadsheet -> stringResource(R.string.attachment_type_spreadsheet)
        AttachmentIconCategory.Presentation -> stringResource(R.string.attachment_type_presentation)
        AttachmentIconCategory.Text -> stringResource(R.string.attachment_type_text)
        AttachmentIconCategory.Code -> stringResource(R.string.attachment_type_code_description)
        AttachmentIconCategory.Audio -> stringResource(R.string.attachment_type_audio)
        AttachmentIconCategory.Video -> stringResource(R.string.attachment_type_video)
        AttachmentIconCategory.Image -> stringResource(R.string.attachment_type_image)
        AttachmentIconCategory.Generic -> stringResource(R.string.attachment_type_file)
    }

internal fun fileIconFor(category: AttachmentIconCategory): ImageVector =
    when (category) {
        AttachmentIconCategory.AndroidPackage -> Icons.Default.Android
        AttachmentIconCategory.Pdf -> Icons.Default.PictureAsPdf
        AttachmentIconCategory.Archive -> Icons.Default.Archive
        AttachmentIconCategory.Document -> Icons.Default.Description
        AttachmentIconCategory.Spreadsheet -> Icons.Default.TableChart
        AttachmentIconCategory.Presentation -> Icons.Default.Slideshow
        AttachmentIconCategory.Text -> Icons.AutoMirrored.Filled.TextSnippet
        AttachmentIconCategory.Code -> Icons.Default.Code
        AttachmentIconCategory.Audio -> Icons.Default.Audiotrack
        AttachmentIconCategory.Video -> Icons.Default.Movie
        AttachmentIconCategory.Image -> Icons.Default.Image
        AttachmentIconCategory.Generic -> Icons.Default.Description
    }
