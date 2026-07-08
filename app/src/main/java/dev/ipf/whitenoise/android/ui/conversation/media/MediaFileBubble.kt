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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    var inFlight by remember(pillKey) { mutableStateOf(false) }
    var failed by remember(pillKey) { mutableStateOf(false) }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)
    // Cached bytes (own send, or downloaded earlier) mean the chevron is
    // misleading — there's nothing to fetch. Probe on first composition,
    // then flip after a successful in-bubble download. Outgoing sends are
    // implicitly cached, so `mine` short-circuits to true.
    var cached by remember(pillKey) {
        mutableStateOf(mine || controller.hasCachedAttachment(messageIdHex, attachmentIndex))
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
    LaunchedEffect(pillKey, reference.sourceEpoch, startDownload) {
        if (cached) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        // Receive-side imeta-parsed refs start with sourceEpoch=0 until the
        // controller's listMedia FFI lands the real epoch; the FFI download
        // path errors with "missing encrypted media secret for epoch 0".
        // Skip + retry once the projection rebinds the bubble with a real
        // epoch. Own sends keep epoch 0 valid (retained bytes short-circuit).
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        inFlight = true
        runCatching {
            controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
        }.onSuccess {
            cached = true
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaFileBubble", "auto-download failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
        }
        inFlight = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
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
                                    cached = true
                                    openAttachmentExternally(context, data, reference.fileName, reference.mediaType)
                                }.onFailure {
                                    // Swipe-up / screen-dispose cancels this
                                    // coroutine. The download itself continues on
                                    // `mutationsScope` and lands in the cache —
                                    // rethrow so the launch dies quietly instead
                                    // of misreporting cancellation as a generic
                                    // "couldn't open file" toast.
                                    if (it is kotlinx.coroutines.CancellationException) throw it
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
                imageVector = fileIconFor(reference.mediaType),
                contentDescription = null,
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
                    shortMediaTypeLabel(reference.mediaType),
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
            } else if (!cached) {
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

/**
 * Compact uppercase label for the file-bubble's MIME line: `application/pdf`
 * becomes "PDF", `image/jpeg` becomes "JPG", `application/vnd.…` falls back
 * to the lowercase MIME so the bubble never goes blank.
 */
internal fun shortMediaTypeLabel(mediaType: String): String {
    val trimmed = mediaType.trim()
    if (trimmed.isEmpty()) return ""
    val tail = trimmed.substringAfterLast('/', missingDelimiterValue = trimmed)
    return when (val canonical = tail.substringBefore('+').substringBefore(';').lowercase()) {
        "jpeg" -> "JPG"
        "vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
        "vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX"
        "vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX"
        "msword" -> "DOC"
        "vnd.ms-excel" -> "XLS"
        "vnd.ms-powerpoint" -> "PPT"
        "" -> trimmed
        else -> canonical.uppercase()
    }
}

internal fun fileIconFor(mediaType: String): androidx.compose.ui.graphics.vector.ImageVector =
    when {
        mediaType.startsWith("audio/", ignoreCase = true) -> Icons.Default.Audiotrack
        mediaType.startsWith("video/", ignoreCase = true) -> Icons.Default.Movie
        mediaType.startsWith("image/", ignoreCase = true) -> Icons.Default.Image
        else -> Icons.Default.Description
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
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
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
                imageVector = fileIconFor(mediaType),
                contentDescription = null,
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
