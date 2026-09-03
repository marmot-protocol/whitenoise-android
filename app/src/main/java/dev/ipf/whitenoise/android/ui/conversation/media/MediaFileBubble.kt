package dev.ipf.whitenoise.android.ui.conversation.media

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentOpenPhase
import dev.ipf.whitenoise.android.state.AttachmentOpenTrace
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.automaticAttachmentDownloadSuppressed
import dev.ipf.whitenoise.android.state.cancelAttachmentTransfer
import dev.ipf.whitenoise.android.state.hasAttachmentInstallerHandoff
import dev.ipf.whitenoise.android.state.hasCachedAttachmentInMemory
import dev.ipf.whitenoise.android.state.refreshAttachmentTransferState
import dev.ipf.whitenoise.android.state.requestAttachmentInstallerHandoff
import dev.ipf.whitenoise.android.ui.conversation.messages.RetentionIndicatorInput
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal fun Modifier.fileBubbleWidth(): Modifier = fillMaxWidth()

internal fun Modifier.fileAttachmentFirstFrameVisibility(resolved: Boolean): Modifier =
    if (resolved) {
        this
    } else {
        alpha(0f).semantics { hideFromAccessibility() }
    }

internal fun fileAttachmentCardTestTag(
    messageIdHex: String,
    attachmentIndex: Int,
): String = "file-attachment-card:$messageIdHex#$attachmentIndex"

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
    val initiallyAvailable =
        remember(controller, pillKey, mine) {
            mine || controller.hasCachedAttachmentInMemory(messageIdHex, attachmentIndex)
        }
    val transferStateFlow =
        remember(controller, pillKey, initiallyAvailable) {
            controller.attachmentTransferState(
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                initiallyAvailable = initiallyAvailable,
            )
        }
    DisposableEffect(controller, pillKey, initiallyAvailable) {
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
    val usesDurableInstallerHandoff =
        remember(mine, reference.mediaType, reference.fileName) {
            !mine && isAndroidPackageOpenCandidate(reference.mediaType, reference.fileName)
        }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)
    val couldntLoadMessage = stringResource(R.string.media_couldnt_load)
    val noInstallerMessage = stringResource(R.string.media_apk_no_installer)
    val installPermissionDeniedMessage = stringResource(R.string.media_apk_permission_denied)
    val installPermissionUnavailableMessage = stringResource(R.string.media_apk_permission_unavailable)
    val installUnsupportedMessage = stringResource(R.string.media_apk_install_unsupported)
    val invalidPackageMessage = stringResource(R.string.media_apk_invalid)
    val cacheRevision by appState.mediaCacheRevision.collectAsState()
    val firstFrameCacheResolved =
        rememberAttachmentFirstFrameCacheResolution(
            owner = controller,
            key = pillKey,
            initiallyResolved = initiallyAvailable,
        ) {
            controller.refreshAttachmentTransferState(messageIdHex, attachmentIndex)
        }
    var reconciledCacheRevision by remember(controller, pillKey) { mutableStateOf(cacheRevision) }
    // Later cache writes and evictions still reconcile the controller-owned
    // state, but they never re-hide a card that already crossed the first-frame
    // boundary. This probe never owns or cancels a running transfer.
    LaunchedEffect(pillKey, mine, cacheRevision, firstFrameCacheResolved) {
        if (!firstFrameCacheResolved || cacheRevision == reconciledCacheRevision) return@LaunchedEffect
        controller.refreshAttachmentTransferState(messageIdHex, attachmentIndex)
        reconciledCacheRevision = cacheRevision
    }
    // Auto-download gate (#407): local own sends stay available, while a
    // cache-missing own file bypasses the matrix only when the account backlog
    // has not been explicitly paused. Incoming documents honor the Documents row.
    // Recomposition re-reads the matrix, so flipping a toggle re-gates an
    // un-fetched file. A tap bypasses this gate entirely, so manual fetch/open
    // stays available regardless of the policy.
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    // A cancel is persisted per attachment, so recreating this card after
    // navigation or process death cannot let the policy restart what the user
    // stopped. Only an explicit tap clears it.
    val cancelledByUser = controller.automaticAttachmentDownloadSuppressed(messageIdHex, attachmentIndex)
    val installerHandoffPending =
        usesDurableInstallerHandoff &&
            controller.hasAttachmentInstallerHandoff(messageIdHex, attachmentIndex, reference.sourceEpoch)
    val opening = if (usesDurableInstallerHandoff) installerHandoffPending else openRequested
    val autoDownloadAllowed =
        !cancelledByUser &&
            shouldMaterializeAttachmentAutomatically(
                mine = mine,
                mediaAutoDownloadAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Document),
                automaticDownloadsPaused = automaticDownloadsPaused,
            )

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
    // is therefore re-created by rotation or process recreation within the
    // same visible destination, joins the same transfer, and atomically
    // consumes the intent immediately before the one external viewer launch.
    LaunchedEffect(
        controller,
        pillKey,
        reference.sourceEpoch,
        appState.attachmentOpens.revision,
        lifecycleOwner,
        usesDurableInstallerHandoff,
    ) {
        if (usesDurableInstallerHandoff) return@LaunchedEffect
        val request = controller.attachmentOpenRequest(messageIdHex, attachmentIndex) ?: return@LaunchedEffect
        if (!appState.attachmentOpens.hasIntent(request)) return@LaunchedEffect
        AttachmentOpenTrace.phase(request, AttachmentOpenPhase.MaterializationStarted)
        openRequested = true
        try {
            val file =
                materializePersistedAttachmentOpen(
                    materialize = {
                        materializeMediaFile(
                            context = context,
                            controller = controller,
                            messageIdHex = messageIdHex,
                            attachmentIndex = attachmentIndex,
                            reference = reference,
                            mine = mine,
                        )
                    },
                    durableAvailabilityExpected = reference.sourceEpoch != 0uL,
                    awaitNextDurableAvailability = {
                        controller.awaitNextAttachmentAvailability(messageIdHex, attachmentIndex)
                        AttachmentOpenTrace.phase(request, AttachmentOpenPhase.DurableAvailabilityObserved)
                    },
                    onWaitingForDurableAvailability = {
                        AttachmentOpenTrace.phase(request, AttachmentOpenPhase.WaitingForDurableAvailability)
                        // Keep one visible pending state. Repeated taps still
                        // ripple but remain idempotent while the durable
                        // transfer/cache publication continues independently.
                    },
                    onTerminalFailure = { appState.present(couldntLoadMessage) },
                ) ?: return@LaunchedEffect
            AttachmentOpenTrace.phase(request, AttachmentOpenPhase.CacheArtifactReady)
            openRequested = true
            val lifecycleEligible = lifecycleOwner.lifecycle.awaitResumedOrDestroyed()
            AttachmentOpenTrace.phase(
                request,
                AttachmentOpenPhase.LifecycleEligibility,
                outcome = if (lifecycleEligible) "eligible" else "destroyed",
            )
            if (!lifecycleEligible) return@LaunchedEffect
            val destinationVisible = appState.attachmentOpens.isVisible(request)
            AttachmentOpenTrace.phase(
                request,
                AttachmentOpenPhase.VisibilityEligibility,
                outcome = if (destinationVisible) "eligible" else "stale",
            )
            if (!destinationVisible) {
                appState.attachmentOpens.consume(request)
                AttachmentOpenTrace.finish(request, "destination_not_visible")
                return@LaunchedEffect
            }
            var openResult: OpenAttachmentResult? = null
            val dispatched =
                claimAndDispatchAttachmentOpenReportingFailure(
                    claim = { appState.attachmentOpens.claim(request) },
                    restore = { appState.attachmentOpens.restore(request) },
                    dispatch = { claim ->
                        openResult =
                            openAttachment(
                                file,
                                reference.mediaType,
                                reference.fileName,
                                InstallerPermissionPersistence(
                                    claim = claim,
                                    begin = { appState.attachmentOpens.beginInstallPermission(request) },
                                    finish = { appState.attachmentOpens.finishInstallPermission(request) },
                                    abandon = { appState.attachmentOpens.abandonInstallPermission(request) },
                                ),
                                AttachmentDispatchGuard(
                                    canDispatch = {
                                        appState.attachmentOpens.isVisible(request).also { visible ->
                                            AttachmentOpenTrace.phase(
                                                request,
                                                AttachmentOpenPhase.VisibilityEligibility,
                                                outcome = if (visible) "eligible" else "stale",
                                            )
                                        }
                                    },
                                    onPlatformDispatchStarted = {
                                        AttachmentOpenTrace.phase(
                                            request,
                                            AttachmentOpenPhase.PlatformDispatchStarted,
                                        )
                                    },
                                    onPlatformDispatchResult = { result ->
                                        AttachmentOpenTrace.phase(
                                            request,
                                            AttachmentOpenPhase.PlatformDispatchResult,
                                            outcome = result.name.lowercase(java.util.Locale.ROOT),
                                        )
                                    },
                                ),
                            )
                    },
                    onFailure = {
                        Log.w(MEDIA_FILE_BUBBLE_TAG, "attachment_viewer_launch_failed")
                        AttachmentOpenTrace.finish(request, "launch_failure")
                        appState.present(couldntOpenMessage, copyable = true)
                    },
                )
            if (!dispatched) return@LaunchedEffect
            val resolvedOpenResult = checkNotNull(openResult)
            if (
                resolvedOpenResult != OpenAttachmentResult.Opened &&
                resolvedOpenResult != OpenAttachmentResult.DestinationNotVisible
            ) {
                Log.w(
                    MEDIA_FILE_BUBBLE_TAG,
                    "attachment_open_outcome=${resolvedOpenResult.name}",
                )
            }
            when (resolvedOpenResult) {
                OpenAttachmentResult.Opened -> Unit
                OpenAttachmentResult.DestinationNotVisible -> Unit
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
            AttachmentOpenTrace.finish(
                request,
                resolvedOpenResult.name.lowercase(java.util.Locale.ROOT),
            )
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
                .fileAttachmentFirstFrameVisibility(firstFrameCacheResolved)
                .testTag(fileAttachmentCardTestTag(messageIdHex, attachmentIndex))
                .combinedClickable(
                    enabled =
                        firstFrameCacheResolved &&
                            canRequestAttachmentOpen(transferState, reference.sourceEpoch, mine),
                    onLongClick = onLongPress,
                    onClick = {
                        if (opening) return@combinedClickable
                        if (textCandidate != null) {
                            readerOpen = true
                            return@combinedClickable
                        }
                        if (usesDurableInstallerHandoff) {
                            controller.requestAttachmentInstallerHandoff(
                                messageIdHex,
                                attachmentIndex,
                                reference.sourceEpoch,
                                onPersistenceFailure = {
                                    appState.present(couldntOpenMessage, copyable = true)
                                },
                            )
                        } else {
                            openRequested = controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
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
            retention = retention,
            reserveRetentionSpace = reserveRetentionSpace,
            openPending = opening,
            onCancelTransfer = { controller.cancelAttachmentTransfer(messageIdHex, attachmentIndex) },
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
                openRequested = controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
            },
            onDismiss = { readerOpen = false },
        )
    }
}

/**
 * Holds a received file card behind one definitive off-main cache result.
 * Resolving remains usable by transfer orchestration, but is never committed
 * as that card's first user-visible frame.
 * [resolve] must return normally for non-cancellation probe failures so the
 * card cannot remain hidden; only lifecycle cancellation may escape.
 */
@Composable
internal fun rememberAttachmentFirstFrameCacheResolution(
    owner: Any,
    key: String,
    initiallyResolved: Boolean,
    resolve: suspend () -> Unit,
): Boolean {
    var resolved by remember(owner, key) { mutableStateOf(initiallyResolved) }
    LaunchedEffect(owner, key) {
        resolve()
        resolved = true
    }
    return resolved
}

private const val MEDIA_FILE_BUBBLE_TAG = "MediaFileBubble"

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
        AttachmentTransferState.Cancelled,
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
