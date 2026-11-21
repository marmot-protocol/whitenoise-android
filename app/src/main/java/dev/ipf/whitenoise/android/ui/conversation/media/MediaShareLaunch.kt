package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.captureSharedPlaintextWrite
import dev.ipf.whitenoise.android.media.sharedPlaintextPrefix
import dev.ipf.whitenoise.android.media.sharedPlaintextSuffix
import dev.ipf.whitenoise.android.media.withSharedPlaintextWrite
import dev.ipf.whitenoise.android.share.OutboundShareStream
import dev.ipf.whitenoise.android.share.launchOutboundShare
import dev.ipf.whitenoise.android.share.outboundShareIntent
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable

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
    accountRef: String,
    beforeLaunch: () -> Unit = {},
): Result<Unit> =
    runCatchingCancellable {
        var stagedFile: java.io.File? = null
        try {
            val uri =
                withSharedPlaintextWrite(captureSharedPlaintextWrite()) {
                    val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                    val file =
                        java.io.File.createTempFile(
                            "${sharedPlaintextPrefix(context, accountRef)}share_",
                            sharedPlaintextSuffix(MediaPipeline.safeDisplayName(fileName)),
                            dir,
                        )
                    stagedFile = file
                    writeSharedMediaFile(file, source)
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                        MediaPipeline.safeDisplayName(fileName),
                    )
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
