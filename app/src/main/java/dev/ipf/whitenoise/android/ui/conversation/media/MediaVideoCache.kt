@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.downloadAttachmentSource
import dev.ipf.whitenoise.android.state.evictCachedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val videoMaterializations = SingleFlight<String, java.io.File>()

/**
 * Publishes supplied video bytes through the legacy test seam into the
 * age-swept `video_attachments` cache.
 */
@VisibleForTesting
internal suspend fun materializeVideoAttachment(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    resolveBytes: suspend () -> ByteArray,
): java.io.File {
    val file =
        videoAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceEpoch = reference.sourceEpoch,
        )

    return videoMaterializations.run(file.absolutePath) {
        materializeVideoAttachmentOnce(attachmentKey, file, resolveBytes)
    }
}

/**
 * Materializes the video and runs [use] on its file while the cache holds it, so a publication elsewhere in the
 * video directory cannot delete it between materialization and [use]. A file evicted before the hold took it is
 * published again under the hold, which covers its stable path.
 */
internal suspend fun <T> withMaterializedVideoFile(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    use: suspend (java.io.File) -> T,
): T =
    withHeldMaterializedFile(
        materialize = {
            materializeVideoAttachment(context, controller, messageIdHex, attachmentIndex, reference, mine)
        },
        use = use,
    )

/**
 * Runs [use] on the file [materialize] returns while the cache holds it. A file evicted before the hold took it is
 * materialized again, under the hold that already covers its stable path.
 */
internal suspend fun <T> withHeldMaterializedFile(
    materialize: suspend () -> java.io.File,
    use: suspend (java.io.File) -> T,
): T {
    var file = materialize()
    holdCacheFileInUse(file)
    try {
        if (!withContext(Dispatchers.IO) { AttachmentPlaintextCache.isHeldFilePresent(file) }) {
            val again = materialize()
            // The path is stable today, and should it ever move, the hold moves with it before the old one ends.
            if (again != file) {
                holdCacheFileInUse(again)
                releaseCacheFileInUse(file)
                file = again
                if (!withContext(Dispatchers.IO) { AttachmentPlaintextCache.isHeldFilePresent(file) }) {
                    throw java.io.IOException("materialized video was evicted before it could be held")
                }
            }
        }
        return use(file)
    } finally {
        releaseCacheFileInUse(file)
    }
}

/** Holds [file] in the attachment cache for as long as the calling composition shows or plays it. */
@Suppress("FunctionNaming")
@Composable
internal fun HoldCacheFileWhileComposed(file: java.io.File) {
    DisposableEffect(file) {
        holdCacheFileInUse(file)
        onDispose { releaseCacheFileInUse(file) }
    }
}

/** Resolves retained or downloaded video plaintext into the stable playback cache. */
internal suspend fun materializeVideoAttachment(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
): java.io.File {
    val resolvedReference =
        authoritativeVisualMediaReference(reference, mine) {
            controller.authoritativeAttachmentReference(messageIdHex, attachmentIndex, reference)
        }
    return materializeVideoAttachmentSource(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = resolvedReference,
        resolveSource = {
            val retained =
                if (mine) {
                    controller
                        .pendingAttachmentsList(messageIdHex)
                        .getOrNull(attachmentIndex)
                        ?.inMemoryBytes
                } else {
                    null
                }
            retained?.let(AttachmentPlaintext::Bytes)
                ?: controller.downloadAttachmentSource(
                    messageIdHex,
                    attachmentIndex,
                    resolvedReference,
                    priority,
                )
        },
    )
}

/** Publishes a closeable video source and reuses a complete stable playback file. */
private suspend fun materializeVideoAttachmentSource(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    resolveSource: suspend () -> AttachmentPlaintext,
): java.io.File {
    val file = videoAttachmentCacheFile(context, messageIdHex, attachmentIndex, reference)
    val attachmentKey = AttachmentCachePublication.attachmentKey(messageIdHex, attachmentIndex, reference.sourceEpoch)
    return videoMaterializations.run(file.absolutePath) {
        withContext(Dispatchers.IO) {
            file.takeIf { it.isFile && it.length() > 0L }?.also(AttachmentPlaintextCache::touch)
        } ?: run {
            val published = AttachmentCachePublication.publishSourceAfterLoad(attachmentKey, file, resolveSource)
            if (!published) throw IOException("attachment cache publication aborted for ${file.name}")
            file
        }
    }
}

private suspend fun materializeVideoAttachmentOnce(
    attachmentKey: String,
    file: java.io.File,
    resolveBytes: suspend () -> ByteArray,
): java.io.File {
    val cachedFile =
        withContext(Dispatchers.IO) {
            file
                .takeIf { it.isFile && it.length() > 0L }
                ?.also(AttachmentPlaintextCache::touch)
        }
    if (cachedFile != null) return cachedFile
    val published =
        AttachmentCachePublication.publishAfterLoad(
            attachmentKey = attachmentKey,
            finalFile = file,
            loadBytes = resolveBytes,
        )
    if (!published) {
        throw IOException("attachment cache publication aborted for ${file.name}")
    }
    return file
}

@VisibleForTesting
internal suspend fun invalidateVideoAttachmentCacheAfterPlaybackFailure(
    attachmentKey: String,
    file: java.io.File,
    evictPlaintext: suspend () -> Unit,
) {
    withContext(NonCancellable) {
        AttachmentCachePublication.invalidateAttachmentCache(
            attachmentKey = attachmentKey,
            finalFile = file,
            evictPlaintext = evictPlaintext,
        )
    }
}

internal suspend fun clearVideoAttachmentCacheAfterPlaybackFailure(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
) {
    val resolvedReference =
        authoritativeVisualMediaReference(reference, mine) {
            controller.authoritativeAttachmentReference(messageIdHex, attachmentIndex, reference)
        }
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceEpoch = resolvedReference.sourceEpoch,
        )
    invalidateVideoAttachmentCacheAfterPlaybackFailure(
        attachmentKey = attachmentKey,
        file =
            videoAttachmentCacheFile(
                context = context,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = resolvedReference,
            ),
    ) {
        controller.evictCachedAttachment(messageIdHex, attachmentIndex)
    }
}

internal suspend fun rematerializeVideoAttachmentAfterPlaybackFailure(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): java.io.File {
    clearVideoAttachmentCacheAfterPlaybackFailure(
        context = context,
        controller = controller,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
        mine = mine,
    )
    return materializeVideoAttachment(
        context = context,
        controller = controller,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
        mine = mine,
    )
}

@VisibleForTesting
internal fun cachedVideoAttachmentFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File? =
    validatedAttachmentCacheFile(
        videoAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        ),
    )

/**
 * Retains validated bytes across metadata-only epoch changes without crossing content identity.
 * Disk validation and cache recency updates run on IO; state publication returns to Main.
 */
@Composable
internal fun rememberCachedVideoAttachmentFileState(
    context: Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    probe: VideoAttachmentCacheProbe = ::cachedVideoAttachmentFile,
): VideoCacheAvailability {
    val contentKey = videoAttachmentContentKey(messageIdHex, attachmentIndex, reference)
    val cachedFile =
        remember(contentKey) {
            newVideoAttachmentCacheFileState()
        }
    val resolved = remember(contentKey, reference.sourceEpoch) { mutableStateOf(false) }
    LaunchedEffect(contentKey, reference.sourceEpoch, probe) {
        val expectedFile = cachedFile.value
        val (file, retained) =
            withContext(Dispatchers.IO) {
                val probed = probe(context, messageIdHex, attachmentIndex, reference)
                probed to validatedAttachmentCacheFile(expectedFile)
            }
        // A metadata-only epoch change can retain validated bytes for the same content key, but an IO probe
        // must not overwrite a file the resolver published while it was suspended.
        publishVideoAttachmentCacheProbe(cachedFile, expectedFile, file ?: retained)
        resolved.value = true
    }
    return remember(cachedFile, resolved) { VideoCacheAvailability(cachedFile, resolved) }
}

/** Keeps a resolver's newly materialized file observable even when it reuses the same cache path. */
internal fun newVideoAttachmentCacheFileState(): MutableState<File?> = mutableStateOf(null, referentialEqualityPolicy())

/** Publishes a disk probe only while no resolver has replaced the state it observed. */
internal fun publishVideoAttachmentCacheProbe(
    cachedFile: MutableState<java.io.File?>,
    expectedFile: java.io.File?,
    probedFile: java.io.File?,
) {
    if (cachedFile.value === expectedFile) cachedFile.value = probedFile
}

/**
 * Whether a video tile materializes its file on its own. A video this device is still sending from its staged
 * file never does: it has no bytes in memory and nothing published, so a fetch could only fail and replace the
 * upload ring with a failed transfer.
 */
@VisibleForTesting
internal fun shouldStartVideoAttachmentDownload(
    mine: Boolean,
    videoAutoDownload: Boolean,
    automaticDownloadsPaused: Boolean = false,
    hasCachedAttachment: Boolean,
    hasCachedFile: Boolean,
    hasRetainedPlaintext: Boolean = false,
    sendingFromStagedFile: Boolean = false,
): Boolean =
    !sendingFromStagedFile &&
        shouldMaterializeAttachmentAutomatically(
            mine = mine,
            mediaAutoDownloadAllowed = videoAutoDownload,
            automaticDownloadsPaused = automaticDownloadsPaused,
            hasCachedAttachment = hasCachedAttachment,
            hasMaterializedFile = hasCachedFile,
            hasRetainedPlaintext = hasRetainedPlaintext,
        )

/**
 * Whether [attachmentIndex] of the pending message [messageIdHex] is this device's own attachment, still being sent
 * from its staged file. Only documents, audio and videos are ever staged. Once MDK confirms the message it carries its
 * published id, which has no pending attachments.
 */
internal fun ConversationController.isSendingFromStagedFile(
    messageIdHex: String,
    attachmentIndex: Int,
    mine: Boolean,
): Boolean = mine && pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex)?.isFileBacked == true

private fun videoAttachmentCacheFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File {
    val dir = java.io.File(context.cacheDir, MediaCacheDirs.VIDEO)
    return java.io.File(
        dir,
        "$messageIdHex-$attachmentIndex-${reference.sourceEpoch}.${videoAttachmentExtension(reference)}",
    )
}

@VisibleForTesting
internal fun videoAttachmentCacheFileForTests(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File = videoAttachmentCacheFile(context, messageIdHex, attachmentIndex, reference)

private fun videoAttachmentExtension(reference: MediaAttachmentReferenceFfi): String =
    when {
        reference.mediaType.contains("quicktime", ignoreCase = true) -> "mov"
        reference.mediaType.contains("webm", ignoreCase = true) -> "webm"
        else -> "mp4"
    }
