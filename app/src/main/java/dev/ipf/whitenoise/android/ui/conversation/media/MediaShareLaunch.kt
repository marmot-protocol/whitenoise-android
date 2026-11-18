package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.share.OutboundShareStream
import dev.ipf.whitenoise.android.share.launchOutboundShare
import dev.ipf.whitenoise.android.share.outboundShareIntent
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stream an already-materialized video into a share-safe FileProvider temp and
 * open the outbound chooser, which excludes White Noise and throws
 * [ActivityNotFoundException] when no external target exists. A blank type
 * keeps the `video/mp4` fallback; other types are normalized by the outbound
 * contract.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun shareVideo(
    context: android.content.Context,
    source: java.io.File,
    fileName: String,
    mediaType: String,
    chooserTitle: String,
    beforeLaunch: () -> Unit = {},
): Result<Unit> =
    runCatchingCancellable {
        var stagedFile: java.io.File? = null
        try {
            val uri =
                withContext(Dispatchers.IO) {
                    val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                    val file = java.io.File.createTempFile("share_", "_" + MediaPipeline.safeDisplayName(fileName), dir)
                    stagedFile = file
                    writeSharedMediaFile(file, source)
                    fileProviderUri(context, file)
                }
            beforeLaunch()
            launchOutboundShare(
                context,
                outboundShareIntent(null, listOf(OutboundShareStream(uri, mediaType.ifBlank { "video/mp4" }))),
                chooserTitle,
            ).getOrThrow()
        } catch (failure: Throwable) {
            runCatching { stagedFile?.delete() }
            throw failure
        }
    }

/**
 * Share [bytes] via a FileProvider Uri using the system share sheet.
 *
 * Suspends because the temp-file write is multi-megabyte for any non-trivial
 * attachment; doing it on the main dispatcher would stall the UI for the
 * write. The `startActivity` call has to run on Main, so the I/O is hopped
 * to `Dispatchers.IO` and the chooser is fired back on Main. Failures are
 * returned so each owning surface can apply its own user-visible contract.
 */
internal suspend fun shareImage(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Result<Unit> =
    runCatchingCancellable {
        val uri =
            withContext(Dispatchers.IO) {
                val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                // Unique temp keyed off a sanitized basename — avoids
                // collisions and path traversal from a remote-supplied
                // filename.
                val file = java.io.File.createTempFile("share_", "_" + MediaPipeline.safeDisplayName(fileName), dir)
                writeSharedMediaFile(file, bytes)
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }
        launchImageShare(context, uri, mediaType).getOrThrow()
    }

internal fun launchImageShare(
    context: android.content.Context,
    uri: Uri,
    mediaType: String,
): Result<Unit> =
    runCatchingCancellable {
        val shareIntent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME }
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            android.content.Intent.createChooser(shareIntent, null).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

/** Expected missing-handler outcomes stay non-diagnostic; operational failures retain their cause. */
internal fun WhiteNoiseAppState.presentMediaLaunchFailure(
    @StringRes titleRes: Int,
    operationCode: String,
    throwable: Throwable,
) {
    if (throwable is ActivityNotFoundException) {
        present(titleRes)
    } else {
        presentFailure(titleRes, operationCode, throwable)
    }
}

@Throws(java.io.IOException::class)
@Suppress("TooGenericExceptionCaught")
private fun writeSharedMediaFile(
    file: java.io.File,
    bytes: ByteArray,
) {
    var protected = false
    try {
        AttachmentPlaintextCache.requireEntryWithinLimit(file, bytes.size.toLong())
        AttachmentPlaintextCache.protectPublicationFile(file)
        protected = true
        file.outputStream().use { it.write(bytes) }
        AttachmentPlaintextCache.finishPublication(file)
        protected = false
    } catch (failure: Throwable) {
        if (protected) AttachmentPlaintextCache.unprotectPublicationFile(file)
        runCatching { file.delete() }
        throw failure
    }
}

@Throws(java.io.IOException::class)
@Suppress("MaxLineLength", "TooGenericExceptionCaught")
private fun writeSharedMediaFile(
    file: java.io.File,
    source: java.io.File,
) {
    var sourceProtected = false
    var destinationProtected = false
    try {
        AttachmentPlaintextCache.protectPublicationFile(source)
        sourceProtected = true
        val sourceBytes = source.takeIf { it.isFile }?.length()?.takeIf { it > 0L } ?: throw java.io.IOException("missing video source")
        AttachmentPlaintextCache.requireEntryWithinLimit(file, sourceBytes)
        AttachmentPlaintextCache.protectPublicationFile(file)
        destinationProtected = true
        source.inputStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        AttachmentPlaintextCache.finishPublication(file)
        destinationProtected = false
    } catch (failure: Throwable) {
        if (destinationProtected) AttachmentPlaintextCache.unprotectPublicationFile(file)
        runCatching { file.delete() }
        throw failure
    } finally {
        if (sourceProtected) AttachmentPlaintextCache.unprotectPublicationFile(source)
    }
}
