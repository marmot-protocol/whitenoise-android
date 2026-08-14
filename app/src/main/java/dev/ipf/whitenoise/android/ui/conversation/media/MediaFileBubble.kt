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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.semantics.onLongClick
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
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Receive-side bubble for any attachment whose MIME isn't an image. Renders
 * as a tappable pill: icon (chosen by MIME family), filename, size + status.
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
) {
    val context = LocalContext.current
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
                .widthIn(max = 360.dp)
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
                                    openAttachmentExternally(
                                        context,
                                        file,
                                        reference.mediaType,
                                    )
                                when (outcome) {
                                    OpenAttachmentResult.Opened -> Unit
                                    OpenAttachmentResult.NoHandler -> {
                                        appState.present(noOpenAppMessage)
                                    }
                                    OpenAttachmentResult.Error -> {
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = fileIconFor(presentation.iconCategory),
                contentDescription = attachmentTypeDescription(presentation.iconCategory),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    MediaPipeline.safeDisplayName(reference.fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    attachmentTypeLabel(presentation),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (transferState == AttachmentTransferState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            attachmentTransferIndicator(transferState)
        }
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
                when (openAttachmentExternally(context, file, reference.mediaType)) {
                    OpenAttachmentResult.Opened -> Unit
                    OpenAttachmentResult.NoHandler -> appState.present(noOpenAppMessage)
                    OpenAttachmentResult.Error -> appState.present(couldntOpenMessage, copyable = true)
                }
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

/** Stable-width trailing status avoids a pill-size jump when loading finishes. */
@Composable
internal fun attachmentTransferIndicator(transferState: AttachmentTransferState) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(20.dp),
    ) {
        when (transferState) {
            AttachmentTransferState.Downloading ->
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            AttachmentTransferState.Failed ->
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.media_tap_to_retry),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            AttachmentTransferState.Remote,
            AttachmentTransferState.NotRetained,
            ->
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.media_tap_to_download),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            AttachmentTransferState.Resolving,
            AttachmentTransferState.Available,
            -> Unit
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
) {
    val presentation = remember(mediaType, fileName) { resolveAttachmentPresentation(mediaType, fileName) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (attachedToCaption) RectangleShape else RoundedCornerShape(12.dp),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (failed && onRetry != null) {
                        Modifier.clickable(onClick = onRetry)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = fileIconFor(presentation.iconCategory),
                contentDescription = attachmentTypeDescription(presentation.iconCategory),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    MediaPipeline.safeDisplayName(fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatFileSize(sizeBytes)} · $statusLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
