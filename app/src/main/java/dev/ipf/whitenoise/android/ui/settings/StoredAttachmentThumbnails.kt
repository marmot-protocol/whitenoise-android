package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
import dev.ipf.whitenoise.android.state.ATTACHMENT_PRESENTATION_MAX_BYTES
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.findNativeAttachment
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.state.openNativeAttachment
import dev.ipf.whitenoise.android.state.readRetainedAttachmentBytes
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.conversation.media.cachedVideoAttachmentFile
import dev.ipf.whitenoise.android.ui.conversation.media.rememberThumbhashImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Longest edge a row thumbnail decodes to: a 40 dp tile at the densest screens, with headroom for cropping. */
private const val STORED_THUMBNAIL_EDGE_PX = 192

/** Rows decode a few at a time, so opening a long list never queues dozens of reads at once. */
private val StoredThumbnailLoads = Semaphore(permits = 2)

/** What a row has to draw so far: the sender's thumbhash first, then pixels from the copy on this device. */
private data class StoredThumbnail(
    val thumbhash: String? = null,
    val bitmap: ImageBitmap? = null,
)

/**
 * A photo or video row's thumbnail. A decode this session already made shows at once; otherwise the row looks up the
 * attachment's message reference, paints its thumbhash, and then decodes the copy on this device in the isolated media
 * decoder: a photo within the presentation budget from its bytes, a video from the app's cached file when there is
 * one. Nothing is downloaded, and a result for another account is never drawn. Null keeps the kind glyph.
 */
@Composable
internal fun rememberStoredAttachmentThumbnail(
    appState: WhiteNoiseAppState,
    attachment: StoredAttachment,
    kind: StoredAttachmentKind,
): ImageBitmap? {
    val account = appState.activeAccountRef
    val context = LocalContext.current
    val visual = kind == StoredAttachmentKind.Photo || kind == StoredAttachmentKind.Video
    val loaded by produceState(StoredThumbnail(), account, attachment.key, visual) {
        if (account == null || !visual) return@produceState
        val key = mediaCacheKey(account, attachment.groupIdHex, attachment.messageIdHex, attachment.attachmentIndex)
        appState.cachedMediaThumbnail(key)?.let {
            value = StoredThumbnail(bitmap = it.asImageBitmap())
            return@produceState
        }
        StoredThumbnailLoads.withPermit {
            val reference = storedAttachmentReference(appState, account, attachment) ?: return@withPermit
            value = StoredThumbnail(thumbhash = reference.thumbhash)
            val video = kind == StoredAttachmentKind.Video
            val bitmap =
                runCatchingCancellable {
                    decodeStoredThumbnail(appState, context, account, attachment, reference, video)
                }.getOrNull()
            bitmap?.takeIf { appState.activeAccountRef == account }?.let {
                value = value.copy(bitmap = it.asImageBitmap())
            }
        }
    }
    val hash = rememberThumbhashImage(loaded.thumbhash)
    return loaded.bitmap ?: hash
}

/** The message reference MarmotKit holds for [attachment], or null when its message is gone. */
private suspend fun storedAttachmentReference(
    appState: WhiteNoiseAppState,
    account: String,
    attachment: StoredAttachment,
): MediaAttachmentReferenceFfi? =
    runCatchingCancellable { appState.findNativeAttachment(attachment.transferRequest(account))?.reference }.getOrNull()

/** Decodes the row's pixels from the device copy, or null when none can be read within the budgets. */
private suspend fun decodeStoredThumbnail(
    appState: WhiteNoiseAppState,
    context: Context,
    account: String,
    attachment: StoredAttachment,
    reference: MediaAttachmentReferenceFfi,
    video: Boolean,
): Bitmap? {
    val domain = MediaTrustDomain.conversation(account, attachment.groupIdHex)
    return withContext(MediaTrustScope.element(domain)) {
        if (video) {
            // Only a video the app already holds as a file has a poster here; copying a large video out just for a
            // thumbnail would cost more than the row is worth.
            cachedVideoAttachmentFile(context, attachment.messageIdHex, attachment.attachmentIndex, reference)
                ?.let { decodeStoredVideoPoster(it) }
        } else {
            decodeStoredPhoto(appState, account, attachment)
        }
    }
}

/** The video's poster frame from the app's cached file, held against cache trimming while it is read. */
private suspend fun decodeStoredVideoPoster(file: File): Bitmap? =
    withContext(Dispatchers.IO) {
        holdCacheFileInUse(file)
        try {
            IsolatedMediaDecoder.probe(file, STORED_THUMBNAIL_EDGE_PX, poster = true)?.poster
        } finally {
            releaseCacheFileInUse(file)
        }
    }

/** A photo within the presentation budget, read from MarmotKit's copy and decoded small. */
private suspend fun decodeStoredPhoto(
    appState: WhiteNoiseAppState,
    account: String,
    attachment: StoredAttachment,
): Bitmap? {
    val request = attachment.transferRequest(account)
    val bytes =
        attachment.takeIf { it.bytes <= ATTACHMENT_PRESENTATION_MAX_BYTES }?.let {
            appState.readRetainedAttachmentBytes(account, ATTACHMENT_PRESENTATION_MAX_BYTES) {
                appState.openNativeAttachment(request, ATTACHMENT_PRESENTATION_MAX_BYTES)
            }
        }
    return bytes?.let {
        withContext(Dispatchers.Default) { MediaPipeline.decodeSampledBitmap(it, STORED_THUMBNAIL_EDGE_PX) }
    }
}

/** The exact native identity of [this] attachment for [account]. */
internal fun StoredAttachment.transferRequest(account: String): AttachmentTransferRequest =
    AttachmentTransferRequest(
        accountRef = account,
        groupIdHex = groupIdHex,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        sourceMessageIdHex = sourceMessageIdHex,
    )
