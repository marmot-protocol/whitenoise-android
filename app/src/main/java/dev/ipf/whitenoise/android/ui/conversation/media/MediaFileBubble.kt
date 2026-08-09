package dev.ipf.whitenoise.android.ui.conversation.media

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.launch

/**
 * Receive-side bubble for any attachment whose MIME isn't an image. Renders
 * as a tappable pill: icon (chosen by MIME family), filename, size + status.
 * Tapping fetches the bytes (cached after first tap), writes a temp file
 * routed through the app's FileProvider, and fires `ACTION_VIEW` so the
 * system picks an external app (PDF reader, etc.) to open it.
 */
@Composable
internal fun MediaFileBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    attachedToCaption: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    var inFlight by remember(pillKey) { mutableStateOf(false) }
    var failed by remember(pillKey) { mutableStateOf(false) }
    val presentation =
        remember(reference.mediaType, reference.fileName) {
            resolveAttachmentPresentation(reference.mediaType, reference.fileName)
        }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)
    // Cached bytes (own send, or downloaded earlier) mean the chevron is
    // misleading — there's nothing to fetch. Probe on first composition,
    // then flip after a successful in-bubble download. Outgoing sends are
    // implicitly cached, so `mine` short-circuits to true.
    var cacheState by remember(pillKey) {
        mutableStateOf(if (mine) AttachmentCacheState.Cached else AttachmentCacheState.Resolving)
    }
    val cachedElsewhere = controller.isAttachmentCachedForCompose(messageIdHex, attachmentIndex)
    LaunchedEffect(cachedElsewhere, mine) {
        cacheState =
            when {
                mine || cachedElsewhere -> AttachmentCacheState.Cached
                cacheState == AttachmentCacheState.Cached -> AttachmentCacheState.Missing
                else -> cacheState
            }
    }
    LaunchedEffect(pillKey, mine) {
        if (mine) return@LaunchedEffect
        val isCached =
            runCatching {
                controller.hasCachedAttachmentAfterHydration(messageIdHex, attachmentIndex)
            }.onFailure(Throwable::rethrowIfCancellation)
                .getOrDefault(false)
        if (cacheState == AttachmentCacheState.Resolving) {
            cacheState = if (isCached) AttachmentCacheState.Cached else AttachmentCacheState.Missing
        }
    }
    // Auto-download gate (#407): own sends are already cached; incoming
    // documents honor the Documents matrix row for the active connection.
    // Re-keyed on the matrix so flipping a toggle re-gates an un-fetched
    // file. A tap flips this to true so manual fetch/open is always
    // available regardless of the policy.
    var startDownload by remember(pillKey, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Document))
    }

    // When the Documents policy allows auto-download, prefetch the bytes into
    // the attachment cache so the file is ready to open without a tap. We
    // only materialize (warm the L1/L2 cache); opening still happens on tap
    // via openAttachmentExternally below. Mirrors the audio/video bubbles.
    LaunchedEffect(pillKey, reference.sourceEpoch, startDownload, cacheState) {
        if (!shouldStartAttachmentDownload(cacheState, startDownload, reference.sourceEpoch, mine)) {
            return@LaunchedEffect
        }
        inFlight = true
        runCatching {
            controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
        }.onSuccess {
            cacheState = AttachmentCacheState.Cached
            failed = false
        }.onFailure {
            it.rethrowIfCancellation()
            failed = true
            Log.w("MediaFileBubble", "auto-download failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
        }
        inFlight = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (attachedToCaption) RectangleShape else RoundedCornerShape(12.dp),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier =
            Modifier
                .widthIn(max = 360.dp)
                .combinedClickable(
                    enabled = !inFlight,
                    onLongClick = onLongPress,
                    onClick = {
                        failed = false
                        // Tap is an explicit opt-in: ensure the gate is open so a
                        // policy-gated file still fetches on demand.
                        startDownload = true
                        inFlight = true
                        scope.launch {
                            val outcome =
                                runCatching {
                                    // For own sends, the retained-uploads LRU still
                                    // holds the source plaintext during the upload
                                    // window. Prefer those bytes — the FFI download
                                    // path is mid-flight (the blob may not have
                                    // fully propagated through the Blossom server
                                    // yet) and would otherwise return invalid bytes
                                    // that the system reader rejects.
                                    val retained =
                                        if (mine) {
                                            controller
                                                .pendingAttachmentsList(messageIdHex)
                                                .getOrNull(attachmentIndex)
                                                ?.plaintextBytes
                                        } else {
                                            null
                                        }
                                    val data =
                                        retained
                                            ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
                                    cacheState = AttachmentCacheState.Cached
                                    openAttachmentExternally(context, data, reference.fileName, reference.mediaType)
                                }.onFailure {
                                    // Swipe-up / screen-dispose cancels this
                                    // coroutine. The download itself continues on
                                    // `mutationsScope` and lands in the cache —
                                    // rethrow so the launch dies quietly instead
                                    // of misreporting cancellation as a generic
                                    // "couldn't open file" toast.
                                    it.rethrowIfCancellation()
                                }.getOrDefault(OpenAttachmentResult.Error)
                            when (outcome) {
                                OpenAttachmentResult.Opened -> Unit
                                OpenAttachmentResult.NoHandler -> {
                                    failed = true
                                    appState.present(noOpenAppMessage)
                                }
                                OpenAttachmentResult.Error -> {
                                    failed = true
                                    appState.present(couldntOpenMessage, copyable = true)
                                }
                            }
                            inFlight = false
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
                        if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (failed) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.media_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            } else if (cacheState == AttachmentCacheState.Missing) {
                // Bytes aren't local yet — show the chevron so the user
                // knows the tap will fetch. Once cached (own send, or after
                // first tap-and-download) the chevron disappears: nothing
                // to fetch, and the row is just "tap to open".
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.media_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

internal fun shouldStartAttachmentDownload(
    cacheState: AttachmentCacheState,
    policyAllowsDownload: Boolean,
    sourceEpoch: ULong,
    mine: Boolean,
): Boolean =
    cacheState == AttachmentCacheState.Missing &&
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
