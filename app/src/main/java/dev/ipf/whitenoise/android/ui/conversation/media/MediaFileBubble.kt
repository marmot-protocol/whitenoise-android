package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.RetentionIndicatorInput
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal val FileBubbleWidth = 240.dp

internal fun Modifier.fileBubbleWidth(): Modifier = width(FileBubbleWidth)

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
    retention: RetentionIndicatorInput? = null,
    reserveRetentionSpace: Boolean = false,
) {
    val context = LocalContext.current
    val openAttachment = rememberAttachmentOpener()
    val lifecycleOwner = LocalLifecycleOwner.current
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
    val couldntLoadMessage = stringResource(R.string.media_couldnt_load)
    val noInstallerMessage = stringResource(R.string.media_apk_no_installer)
    val installPermissionDeniedMessage = stringResource(R.string.media_apk_permission_denied)
    val installPermissionUnavailableMessage = stringResource(R.string.media_apk_permission_unavailable)
    val installUnsupportedMessage = stringResource(R.string.media_apk_install_unsupported)
    val invalidPackageMessage = stringResource(R.string.media_apk_invalid)
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
        controller.requestAttachmentTransfer(
            messageIdHex,
            attachmentIndex,
            reference,
            priority = AttachmentDownloadPriority.Automatic,
        )
    }

    // A tap is persisted as scheduling identity before work starts. This effect
    // is therefore re-created by rotation, navigation return, or process
    // recreation, joins the same transfer, and atomically consumes the intent
    // immediately before the one external viewer launch.
    LaunchedEffect(pillKey, appState.attachmentOpenIntentRevision, cacheRevision) {
        if (!controller.hasAttachmentOpenIntent(messageIdHex, attachmentIndex)) return@LaunchedEffect
        openRequested = true
        try {
            val file =
                materializeMediaFileOrNotify(
                    context = context,
                    controller = controller,
                    messageIdHex = messageIdHex,
                    attachmentIndex = attachmentIndex,
                    reference = reference,
                    mine = mine,
                ) { appState.present(couldntLoadMessage) } ?: return@LaunchedEffect
            if (!lifecycleOwner.lifecycle.awaitResumedOrDestroyed()) return@LaunchedEffect
            if (!controller.consumeAttachmentOpenIntent(messageIdHex, attachmentIndex)) return@LaunchedEffect
            when (openAttachment(file, reference.mediaType, reference.fileName)) {
                OpenAttachmentResult.Opened -> Unit
                OpenAttachmentResult.NoHandler -> appState.present(noOpenAppMessage)
                OpenAttachmentResult.NoInstaller -> appState.present(noInstallerMessage)
                OpenAttachmentResult.InstallPermissionDenied,
                OpenAttachmentResult.InstallPermissionRequired,
                -> appState.present(installPermissionDeniedMessage)
                OpenAttachmentResult.InstallPermissionUnavailable -> {
                    appState.present(installPermissionUnavailableMessage, copyable = true)
                }
                OpenAttachmentResult.InstallUnsupported -> appState.present(installUnsupportedMessage)
                OpenAttachmentResult.InvalidPackage -> appState.present(invalidPackageMessage)
                OpenAttachmentResult.MissingArtifact,
                OpenAttachmentResult.SecurityFailure,
                OpenAttachmentResult.Error,
                -> appState.present(couldntOpenMessage, copyable = true)
            }
        } finally {
            openRequested = false
        }
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
                        controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
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
            retention = retention,
            reserveRetentionSpace = reserveRetentionSpace,
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
                readerOpen = false
                openRequested = true
                controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
            },
            onDismiss = { readerOpen = false },
        )
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

internal suspend fun Lifecycle.awaitResumedOrDestroyed(): Boolean =
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

internal fun shouldStartAttachmentDownload(
    transferState: AttachmentTransferState,
    policyAllowsDownload: Boolean,
    sourceEpoch: ULong,
    mine: Boolean,
): Boolean =
    transferState == AttachmentTransferState.Remote &&
        policyAllowsDownload &&
        (mine || sourceEpoch != 0uL)

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
    retention: RetentionIndicatorInput? = null,
    reserveRetentionSpace: Boolean = false,
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
            retention = retention,
            reserveRetentionSpace = reserveRetentionSpace,
            loadingDescription = statusLabel,
            transferDirection = FileTransferDirection.Upload,
        )
    }
}
