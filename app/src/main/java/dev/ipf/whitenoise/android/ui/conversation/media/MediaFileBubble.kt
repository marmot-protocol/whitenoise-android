package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.OutgoingMessageStatusIcon
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val FileBubbleWidth = 240.dp
private val FileTransferControlSize = 48.dp
private val FileTransferControlSurfaceSize = 40.dp
private val FileTrailingMetadataMaxWidth = 96.dp
private val FileTrailingMetadataWithStatusMaxWidth = 112.dp
private val FileTimestampWithStatusMaxWidth = 92.dp

internal fun Modifier.fileBubbleWidth(): Modifier = width(FileBubbleWidth)

internal enum class FileTransferDirection {
    Download,
    Upload,
}

/**
 * Confirmed bubble for any attachment whose MIME isn't an image. Renders
 * as a tappable card with a transfer control, filename, and compact metadata.
 * Supported text and Markdown attachments open in a bounded, read-only
 * in-app reader. Other files join any automatic/durable fetch already in
 * flight and open a reusable FileProvider artifact in an external viewer.
 */
@Composable
internal fun MediaFileBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    senderKey: String,
    senderDisplayName: String,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    attachedToCaption: Boolean = false,
    timestampText: String? = null,
    showStatus: Boolean = false,
    status: MessageStatus = MessageStatus.Received,
) {
    val context = LocalContext.current
    val openAttachment = rememberAttachmentOpener()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    var openRequested by remember(pillKey) { mutableStateOf(false) }
    var readerOpen by rememberSaveable(pillKey) { mutableStateOf(false) }
    val transferStateFlow =
        remember(controller, pillKey, mine) {
            controller.attachmentTransferState(
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                initiallyAvailable = mine,
            )
        }
    DisposableEffect(controller, pillKey, mine) {
        onDispose {
            controller.releaseAttachmentTransferState(messageIdHex, attachmentIndex)
        }
    }
    val transferState by transferStateFlow.collectAsState()
    val presentation =
        remember(reference.mediaType, reference.fileName) {
            resolveAttachmentPresentation(reference.mediaType, reference.fileName)
        }
    val textCandidate =
        remember(reference.mediaType, reference.fileName) {
            textAttachmentCandidate(reference.mediaType, reference.fileName)
        }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)
    // Reconcile the controller-owned transfer presentation against cache
    // hydration/eviction. This probe never owns or cancels a running transfer.
    val cacheRevision by appState.mediaCacheRevision.collectAsState()
    LaunchedEffect(pillKey, mine, cacheRevision) {
        controller.refreshAttachmentTransferState(messageIdHex, attachmentIndex)
    }
    // Auto-download gate (#407): own sends are already cached; incoming
    // documents honor the Documents matrix row for the active connection.
    // Recomposition re-reads the matrix, so flipping a toggle re-gates an
    // un-fetched file. A tap bypasses this gate entirely, so manual fetch/open
    // stays available regardless of the policy.
    val autoDownloadAllowed = mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Document)

    // When the Documents policy allows auto-download, prefetch into encrypted
    // L2. Notification receipt now schedules the same durable work; this
    // composition trigger is kept as an immediate foreground fast path.
    LaunchedEffect(pillKey, reference.sourceEpoch, autoDownloadAllowed, transferState) {
        if (!shouldStartAttachmentDownload(transferState, autoDownloadAllowed, reference.sourceEpoch, mine)) {
            return@LaunchedEffect
        }
        controller.requestAttachmentTransfer(messageIdHex, attachmentIndex, reference)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (attachedToCaption) RectangleShape else RoundedCornerShape(12.dp),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .fileBubbleWidth()
                .combinedClickable(
                    enabled =
                        !openRequested &&
                            canRequestAttachmentOpen(transferState, reference.sourceEpoch, mine),
                    onLongClick = onLongPress,
                    onClick = {
                        if (textCandidate != null) {
                            readerOpen = true
                            return@combinedClickable
                        }
                        openRequested = true
                        scope.launch {
                            try {
                                val file =
                                    materializeMediaFile(
                                        context = context,
                                        controller = controller,
                                        messageIdHex = messageIdHex,
                                        attachmentIndex = attachmentIndex,
                                        reference = reference,
                                        mine = mine,
                                    ) ?: return@launch
                                // If the download finishes while the app is in the
                                // background, keep this tap pending and open as soon
                                // as the Activity resumes. Process death is covered by
                                // the durable worker; the next tap then reuses L2.
                                if (!lifecycleOwner.lifecycle.awaitResumedOrDestroyed()) return@launch
                                val outcome =
                                    openAttachment(file, reference.mediaType)
                                when (outcome) {
                                    OpenAttachmentResult.Opened -> Unit
                                    OpenAttachmentResult.NoHandler -> {
                                        appState.present(noOpenAppMessage)
                                    }
                                    OpenAttachmentResult.InstallPermissionRequired,
                                    OpenAttachmentResult.Error,
                                    -> {
                                        appState.present(couldntOpenMessage, copyable = true)
                                    }
                                }
                            } finally {
                                openRequested = false
                            }
                        }
                    },
                ),
    ) {
        MediaFileBubbleContent(
            reference = reference,
            presentation = presentation,
            transferState = transferState,
            timestampText = timestampText,
            showStatus = showStatus,
            status = status,
        )
    }
    if (readerOpen && textCandidate != null) {
        TextAttachmentReaderDialog(
            candidate = textCandidate,
            appState = appState,
            senderKey = senderKey,
            senderDisplayName = senderDisplayName,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            loadBytes = {
                requireNotNull(
                    loadMediaFileBytes(
                        controller = controller,
                        messageIdHex = messageIdHex,
                        attachmentIndex = attachmentIndex,
                        reference = reference,
                        mine = mine,
                    ),
                )
            },
            onOpenExternal = openExternal@{
                val file =
                    materializeMediaFile(
                        context = context,
                        controller = controller,
                        messageIdHex = messageIdHex,
                        attachmentIndex = attachmentIndex,
                        reference = reference,
                        mine = mine,
                    ) ?: return@openExternal
                if (!lifecycleOwner.lifecycle.awaitResumedOrDestroyed()) return@openExternal
                when (openAttachment(file, reference.mediaType)) {
                    OpenAttachmentResult.Opened -> Unit
                    OpenAttachmentResult.NoHandler -> appState.present(noOpenAppMessage)
                    OpenAttachmentResult.InstallPermissionRequired,
                    OpenAttachmentResult.Error,
                    -> appState.present(couldntOpenMessage, copyable = true)
                }
            },
            onDismiss = { readerOpen = false },
        )
    }
}

/** File-card presentation kept separate from transfer/lifecycle ownership for deterministic UI coverage. */
@Composable
internal fun MediaFileBubbleContent(
    reference: MediaAttachmentReferenceFfi,
    presentation: AttachmentPresentation,
    transferState: AttachmentTransferState,
    timestampText: String? = null,
    showStatus: Boolean = false,
    status: MessageStatus = MessageStatus.Received,
) {
    FileBubbleContent(
        fileName = reference.fileName,
        presentation = presentation,
        transferState = transferState,
        metadataText = attachmentTypeLabel(presentation),
        metadataIsError = transferState == AttachmentTransferState.Failed,
        trailingMetadataText = timestampText,
        trailingMetadataIsError = false,
        trailingStatus = status.takeIf { showStatus },
        loadingDescription = stringResource(R.string.media_downloading),
        transferDirection = FileTransferDirection.Download,
    )
}

/**
 * Shared chrome for optimistic outgoing files and confirmed incoming files.
 * Keeping the control slot and both text rows identical prevents the card from
 * resizing when an upload is reconciled with its confirmed message.
 */
@Composable
private fun FileBubbleContent(
    fileName: String,
    presentation: AttachmentPresentation,
    transferState: AttachmentTransferState,
    metadataText: String,
    metadataIsError: Boolean,
    trailingMetadataText: String?,
    trailingMetadataIsError: Boolean,
    trailingStatus: MessageStatus?,
    loadingDescription: String,
    transferDirection: FileTransferDirection,
) {
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
                if (trailingMetadataText != null || trailingStatus != null) {
                    val trailingColor =
                        if (trailingMetadataIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier =
                            Modifier.widthIn(
                                max =
                                    if (trailingStatus == null) {
                                        FileTrailingMetadataMaxWidth
                                    } else {
                                        FileTrailingMetadataWithStatusMaxWidth
                                    },
                            ),
                    ) {
                        trailingMetadataText?.let { trailingText ->
                            Text(
                                text = trailingText,
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        textDirection = TextDirection.ContentOrLtr,
                                    ),
                                color = trailingColor,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier.widthIn(
                                        max =
                                            if (trailingStatus == null) {
                                                FileTrailingMetadataMaxWidth
                                            } else {
                                                FileTimestampWithStatusMaxWidth
                                            },
                                    ),
                            )
                        }
                        trailingStatus?.let { OutgoingMessageStatusIcon(it, tint = trailingColor) }
                    }
                }
            }
        }
    }
}

/** A tap during auto-download joins the existing transfer instead of being ignored. */
internal fun canRequestAttachmentOpen(
    transferState: AttachmentTransferState,
    sourceEpoch: ULong,
    mine: Boolean,
): Boolean =
    when (transferState) {
        AttachmentTransferState.Resolving,
        AttachmentTransferState.Remote,
        AttachmentTransferState.Downloading,
        AttachmentTransferState.Available,
        AttachmentTransferState.NotRetained,
        AttachmentTransferState.Failed,
        -> mine || sourceEpoch != 0uL
    }

private suspend fun Lifecycle.awaitResumedOrDestroyed(): Boolean =
    when {
        currentState == Lifecycle.State.DESTROYED -> false
        currentState.isAtLeast(Lifecycle.State.RESUMED) -> true
        else ->
            suspendCancellableCoroutine { continuation ->
                lateinit var observer: LifecycleEventObserver

                fun complete(resumed: Boolean) {
                    removeObserver(observer)
                    if (continuation.isActive) continuation.resume(resumed)
                }
                observer =
                    LifecycleEventObserver { _, _ ->
                        when {
                            currentState == Lifecycle.State.DESTROYED -> complete(false)
                            currentState.isAtLeast(Lifecycle.State.RESUMED) -> complete(true)
                        }
                    }
                addObserver(observer)
                continuation.invokeOnCancellation { removeObserver(observer) }
                when {
                    currentState == Lifecycle.State.DESTROYED -> complete(false)
                    currentState.isAtLeast(Lifecycle.State.RESUMED) -> complete(true)
                }
            }
    }

/** One fixed control slot keeps every transfer state the same size and exposes one clear affordance. */
@Composable
internal fun FileTransferControl(
    presentation: AttachmentPresentation,
    transferState: AttachmentTransferState,
    loadingDescription: String = stringResource(R.string.media_downloading),
    direction: FileTransferDirection = FileTransferDirection.Download,
) {
    val containerColor =
        when (transferState) {
            AttachmentTransferState.Remote,
            AttachmentTransferState.NotRetained,
            AttachmentTransferState.Resolving,
            AttachmentTransferState.Downloading,
            -> MaterialTheme.colorScheme.primaryContainer
            AttachmentTransferState.Failed -> MaterialTheme.colorScheme.errorContainer
            AttachmentTransferState.Available -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val contentColor =
        when (transferState) {
            AttachmentTransferState.Remote,
            AttachmentTransferState.NotRetained,
            AttachmentTransferState.Resolving,
            AttachmentTransferState.Downloading,
            -> MaterialTheme.colorScheme.onPrimaryContainer
            AttachmentTransferState.Failed -> MaterialTheme.colorScheme.onErrorContainer
            AttachmentTransferState.Available -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val stateDescription =
        when (transferState) {
            AttachmentTransferState.Resolving -> stringResource(R.string.media_preparing_download)
            AttachmentTransferState.Downloading -> loadingDescription
            AttachmentTransferState.Failed -> stringResource(R.string.media_tap_to_retry)
            AttachmentTransferState.Remote,
            AttachmentTransferState.NotRetained,
            -> stringResource(R.string.media_tap_to_download)
            AttachmentTransferState.Available -> attachmentTypeDescription(presentation.iconCategory)
        }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(FileTransferControlSize)
                .semantics(mergeDescendants = true) { contentDescription = stateDescription },
    ) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            border = if (transferState == AttachmentTransferState.Available) amoledSurfaceBorderStroke() else null,
            modifier = Modifier.size(FileTransferControlSurfaceSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (transferState) {
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
                                if (direction == FileTransferDirection.Upload) {
                                    Icons.Default.ArrowUpward
                                } else {
                                    Icons.Default.ArrowDownward
                                },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    AttachmentTransferState.Failed ->
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                    AttachmentTransferState.Remote,
                    AttachmentTransferState.NotRetained,
                    ->
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                    AttachmentTransferState.Available ->
                        Icon(
                            imageVector = fileIconFor(presentation.iconCategory),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                }
            }
        }
    }
}

internal fun shouldStartAttachmentDownload(
    transferState: AttachmentTransferState,
    policyAllowsDownload: Boolean,
    sourceEpoch: ULong,
    mine: Boolean,
): Boolean =
    transferState == AttachmentTransferState.Remote &&
        policyAllowsDownload &&
        (mine || sourceEpoch != 0uL)

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

private fun formatFileSize(bytes: Long): String {
    if (bytes < 0L) return ""
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.1f GB", gb)
}

@Composable
internal fun PendingFilePill(
    fileName: String,
    mediaType: String,
    sizeBytes: Long,
    failed: Boolean,
    statusLabel: String,
    onRetry: (() -> Unit)? = null,
    attachedToCaption: Boolean = false,
    timestampText: String? = null,
    showStatus: Boolean = false,
    status: MessageStatus = MessageStatus.Pending,
) {
    val presentation = remember(mediaType, fileName) { resolveAttachmentPresentation(mediaType, fileName) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (attachedToCaption) RectangleShape else RoundedCornerShape(12.dp),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .fileBubbleWidth()
                .then(
                    if (failed && onRetry != null) {
                        Modifier.clickable(onClick = onRetry)
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
            metadataText = if (failed && timestampText != null) statusLabel else formatFileSize(sizeBytes),
            metadataIsError = failed && timestampText != null,
            trailingMetadataText = timestampText ?: statusLabel,
            trailingMetadataIsError = failed && timestampText == null,
            trailingStatus = status.takeIf { showStatus },
            loadingDescription = statusLabel,
            transferDirection = FileTransferDirection.Upload,
        )
    }
}
