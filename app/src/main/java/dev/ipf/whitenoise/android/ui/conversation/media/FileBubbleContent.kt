@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentCancellationState
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.isTransferInProgress
import dev.ipf.whitenoise.android.ui.conversation.messages.OutgoingMessageStatusIcon
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ArrowDownward
import dev.ipf.whitenoise.android.ui.icons.filled.ArrowUpward
import dev.ipf.whitenoise.android.ui.icons.filled.Close
import dev.ipf.whitenoise.android.ui.icons.filled.Refresh
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

private val FileTransferControlSize = 48.dp
private val FileTransferControlSurfaceSize = 40.dp
private val FileTrailingMetadataMaxWidth = 96.dp
private val FileTrailingMetadataWithStatusMaxWidth = 112.dp
private val FileTimestampWithStatusMaxWidth = 92.dp

/** The prototype's file card: 6dp padding, 8dp between slots, and a 20dp trailing glyph at 72% opacity. */
private val FileCardPadding = 6.dp
private val FileCardSlotSpacing = 8.dp
private val FileCardTrailingGlyph = 20.dp
private const val FILE_CARD_SECONDARY_ALPHA = 0.72f
private const val WAITING_TRACK_ALPHA = 0.24f

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
    footerWarningText: String? = null,
    onCancelTransfer: (() -> Unit)? = null,
    nativeProgress: NativeAttachmentProgress? = null,
    cancellationState: AttachmentCancellationState = AttachmentCancellationState.None,
) {
    val progressDescription =
        when (cancellationState) {
            AttachmentCancellationState.Pending -> stringResource(R.string.media_cancelling_download)
            AttachmentCancellationState.Unconfirmed -> stringResource(R.string.media_cancel_unconfirmed)
            AttachmentCancellationState.None ->
                if (openPending && transferState == AttachmentTransferState.Available) {
                    stringResource(R.string.media_preparing_attachment)
                } else {
                    nativeProgressDescription(nativeProgress, transferState)
                }
        }
    FileBubbleContent(
        fileName = reference.fileName,
        presentation = presentation,
        transferState = transferState,
        openPending = openPending,
        metadataText = progressDescription ?: attachmentTypeLabel(presentation),
        metadataIsError = transferState == AttachmentTransferState.Failed,
        trailingMetadataText = timestampText,
        trailingMetadataIsError = false,
        trailingStatus = status.takeIf { showStatus },
        footerWarningText = footerWarningText,
        loadingDescription = stringResource(R.string.media_downloading),
        openingDescription = stringResource(R.string.media_opening),
        transferDirection = FileTransferDirection.Download,
        onCancelTransfer = onCancelTransfer.takeUnless { cancellationState == AttachmentCancellationState.Pending },
        progressDescription = progressDescription,
        progressFraction = nativeProgress?.fraction.takeIf { cancellationState == AttachmentCancellationState.None },
        animateIndeterminate =
            nativeProgress?.phase !in
                setOf(
                    AttachmentTransferStateFfi.QUEUED,
                    AttachmentTransferStateFfi.RETRY_SCHEDULED,
                    AttachmentTransferStateFfi.PAUSED,
                ),
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
    footerWarningText: String? = null,
    loadingDescription: String,
    openingDescription: String = stringResource(R.string.media_opening),
    transferDirection: FileTransferDirection,
    onCancelTransfer: (() -> Unit)? = null,
    progressDescription: String? = null,
    progressFraction: Float? = null,
    animateIndeterminate: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FileCardSlotSpacing),
        modifier = Modifier.fillMaxWidth().padding(FileCardPadding),
    ) {
        FileTransferControl(
            presentation = presentation,
            transferState = transferState,
            loadingDescription = loadingDescription,
            direction = transferDirection,
            openPending = openPending,
            openingDescription = openingDescription,
            onCancelTransfer = onCancelTransfer,
            progressDescription = progressDescription,
            progressFraction = progressFraction,
            animateIndeterminate = animateIndeterminate,
        )
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.weight(1f).heightIn(min = FileTransferControlSize),
        ) {
            Text(
                text = safeDocumentDisplayName(fileName),
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrLtr),
                fontWeight = FontWeight.SemiBold,
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
                footerWarningText = footerWarningText,
            )
        }
        FileCardTrailingAffordance(transferState)
    }
}

/** The prototype's trailing glyph: a chevron into the file, or a warning when it cannot be opened. */
@Composable
private fun FileCardTrailingAffordance(transferState: AttachmentTransferState) {
    val unavailable =
        transferState == AttachmentTransferState.Failed || transferState == AttachmentTransferState.NotRetained
    Icon(
        painter =
            painterResource(if (unavailable) R.drawable.ic_warning else R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(FileCardTrailingGlyph),
        tint = LocalContentColor.current.copy(alpha = FILE_CARD_SECONDARY_ALPHA),
    )
}

/** Keeps an optional warning and the trailing timestamp/status block in one bounded file footer. */
@Composable
private fun FileMetadataRow(
    metadataText: String,
    metadataIsError: Boolean,
    trailingMetadataText: String?,
    trailingMetadataIsError: Boolean,
    trailingStatus: MessageStatus?,
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
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.ContentOrLtr),
                color =
                    if (metadataIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FILE_CARD_SECONDARY_ALPHA)
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val hasTrailingMetadata = trailingMetadataText != null || trailingStatus != null
            if (hasTrailingMetadata) {
                FileTrailingMetadata(
                    text = trailingMetadataText,
                    isError = trailingMetadataIsError,
                    status = trailingStatus,
                )
            }
        }
    }
}

/** Trailing metadata of a file card: size or error, then the delivery status. */
@Composable
private fun FileTrailingMetadata(
    text: String?,
    isError: Boolean,
    status: MessageStatus?,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            Modifier.widthIn(
                max =
                    if (status != null) FileTrailingMetadataWithStatusMaxWidth else FileTrailingMetadataMaxWidth,
            ),
    ) {
        status?.let { OutgoingMessageStatusIcon(it, tint = color) }
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
    progressDescription: String? = null,
    progressFraction: Float? = null,
    animateIndeterminate: Boolean = true,
) {
    val cancelAction =
        onCancelTransfer.takeIf {
            direction == FileTransferDirection.Download && transferState.isTransferInProgress()
        }
    val colors = fileTransferControlColors(transferState)
    val cancelDescription = stringResource(R.string.media_cancel_download)
    val stateDescription =
        progressDescription
            ?: if (openPending) {
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
                    progressFraction = progressFraction,
                    animateIndeterminate = animateIndeterminate,
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
    progressFraction: Float?,
    animateIndeterminate: Boolean,
) {
    // A tap-to-open download is the commonest cancellable case, so the cancel
    // glyph must win over the generic opening chrome; without this the only
    // discoverable affordance would be the TalkBack click label.
    if (openPending && !showCancelGlyph && progressFraction == null) {
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
            FileBodyProgressIndicator(progressFraction, contentColor, animateIndeterminate)
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

/** Body percentage remains determinate only while native supplies a valid size; other phases stay indeterminate. */
@Composable
private fun FileBodyProgressIndicator(
    fraction: Float?,
    color: Color,
    animateIndeterminate: Boolean,
) {
    if (fraction == null && animateIndeterminate) {
        CircularProgressIndicator(
            modifier = Modifier.size(FileTransferControlSurfaceSize),
            strokeWidth = 2.5.dp,
            color = color,
        )
    } else if (fraction != null) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(FileTransferControlSurfaceSize),
            strokeWidth = 2.5.dp,
            color = color,
        )
    } else {
        Box(
            modifier =
                Modifier.size(FileTransferControlSurfaceSize).border(
                    2.5.dp,
                    color.copy(alpha = WAITING_TRACK_ALPHA),
                    CircleShape,
                ),
        )
    }
}
