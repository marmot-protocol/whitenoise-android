@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaPipeline

private const val MEDIA_STORE_INSERT_ATTEMPTS = 2
private const val MEDIA_STORE_INSERT_RETRY_DELAY_MILLIS = 150L

internal enum class AttachmentSaveStage {
    MEDIASTORE_INSERT,
    MEDIASTORE_OPEN,
    MEDIASTORE_WRITE,
    MEDIASTORE_FINALIZE,
    DOCUMENT_DESTINATION_OPEN,
    DOCUMENT_DESTINATION_WRITE,
}

internal class AttachmentSaveException(
    val stage: AttachmentSaveStage,
    cause: Throwable? = null,
) : java.io.IOException("attachment save failed at ${stage.name}", cause),
    DiagnosticErrorMetadata {
    override val diagnosticErrorCode: String = "IO"
    override val diagnosticTechnicalDetail: String = "stage=${stage.name}"
}

/**
 * Persist [bytes] to the device gallery (Pictures/White Noise). Returns success.
 * Uses the IS_PENDING dance so other apps never see a half-written entry, and
 * sanitizes the remote-supplied [fileName] to a basename.
 */
internal fun saveImageToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean = saveImageToGallery(context, fileName, mediaType) { output -> output.write(bytes) }

/**
 * Streams a received image's verified plaintext into the gallery, so an explicit Save never holds the whole image
 * on the heap. The caller owns and closes [source].
 */
internal fun saveImageToGallery(
    context: android.content.Context,
    source: AttachmentPlaintext,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
): Boolean = saveImageToGallery(context, fileName, mediaType, beforePublish, source::copyTo)

/** Publishes one gallery image whose bytes [write] produces, with the same pending-entry and naming rules. */
@Suppress("MaxLineLength")
private fun saveImageToGallery(
    context: android.content.Context,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
    write: (java.io.OutputStream) -> Unit,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            // Preserve the attachment's real MIME (a peer may send PNG/WebP/HEIC),
            // so gallery indexing matches the actual bytes.
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME })
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/White Noise")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
    return publishMediaStoreEntry(
        resolver = resolver,
        collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values = values,
        beforePublish = beforePublish,
        write = write,
    )
}

/** Persist a decrypted video to the public Movies/White Noise folder via the
 *  Video MediaStore so it shows up in the system gallery. Mirrors the image
 *  save flow's IS_PENDING dance. */
internal fun saveVideoToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean = saveVideoToGallery(context, bytes.inputStream(), fileName, mediaType)

/**
 * Streams an existing video file to the gallery, retaining the caller's final-publication hook and closing the
 * input.
 */
internal fun saveVideoToGallery(
    context: android.content.Context,
    source: java.io.File,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
): Boolean = source.inputStream().use { saveVideoToGallery(context, it, fileName, mediaType, beforePublish) }

/**
 * Streams a decrypted attachment into the public collection appropriate for its MIME type without reading the whole
 * file into memory. The caller owns and closes [source]; a pending own send passes its retained bytes as a source.
 */
internal fun saveAttachmentToMediaStore(
    context: Context,
    source: AttachmentPlaintext,
    fileName: String,
    mediaType: String,
): Boolean {
    val normalizedMime = normalizeDocumentMime(mediaType)
    return when {
        normalizedMime.startsWith("image/") -> saveImageToGallery(context, source, fileName, normalizedMime)
        normalizedMime.startsWith("video/") ->
            publishVideoToGallery(
                context,
                fileName,
                normalizedMime,
                write = source::copyTo,
            )
        else -> publishFileToDownloads(context, fileName, normalizedMime, write = source::copyTo)
    }
}

/**
 * Stream a reusable general-file artifact into public Downloads. [beforePublish] runs at the finalize boundary and
 * may throw to keep the entry pending and delete it, as the gallery saves do.
 */
internal fun saveDocumentToDownloads(
    context: Context,
    source: java.io.File,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
): Boolean = source.inputStream().use { saveFileToDownloads(context, it, fileName, mediaType, beforePublish) }

/** Streams [source] into Download/White Noise without reading it whole. */
private fun saveFileToDownloads(
    context: Context,
    source: java.io.InputStream,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
): Boolean =
    publishFileToDownloads(context, fileName, mediaType, beforePublish) { output ->
        source.copyTo(output, DEFAULT_BUFFER_SIZE)
    }

/** Publishes one Downloads entry whose bytes [write] produces, with the same pending-entry and naming rules. */
private fun publishFileToDownloads(
    context: Context,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
    write: (java.io.OutputStream) -> Unit,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeDocumentDisplayName(fileName))
            put(android.provider.MediaStore.Downloads.MIME_TYPE, normalizeDocumentMime(mediaType))
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/White Noise")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
    return publishMediaStoreEntry(
        resolver = resolver,
        collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        values = values,
        beforePublish = beforePublish,
        write = write,
    )
}

/** Streams [source] into Movies/White Noise without reading it whole. */
private fun saveVideoToGallery(
    context: android.content.Context,
    source: java.io.InputStream,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
): Boolean =
    publishVideoToGallery(context, fileName, mediaType, beforePublish) { output ->
        source.copyTo(output, DEFAULT_BUFFER_SIZE)
    }

/** Publishes one gallery video whose bytes [write] produces, with the same pending-entry and naming rules. */
private fun publishVideoToGallery(
    context: android.content.Context,
    fileName: String,
    mediaType: String,
    beforePublish: () -> Unit = {},
    write: (java.io.OutputStream) -> Unit,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, mediaType.ifBlank { "video/mp4" })
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/White Noise")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
    return publishMediaStoreEntry(
        resolver = resolver,
        collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        values = values,
        beforePublish = beforePublish,
        write = write,
    )
}

/**
 * Clears the pending MediaStore flag after the publication hook succeeds, deleting the row if any write stage
 * fails.
 */
@Suppress("TooGenericExceptionCaught")
private inline fun publishMediaStoreEntry(
    resolver: android.content.ContentResolver,
    collection: Uri,
    values: android.content.ContentValues,
    beforePublish: () -> Unit = {},
    write: (java.io.OutputStream) -> Unit,
): Boolean {
    val uri =
        retryNullableMediaStoreInsert {
            resolver.insert(collection, values)
        }
    return try {
        val output =
            attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_OPEN) {
                requireAttachmentOutputStream(resolver.openOutputStream(uri))
            }
        attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_WRITE) {
            output.use(write)
        }
        attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_FINALIZE) {
            beforePublish()
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            requireMediaStoreFinalized(resolver.update(uri, values, null, null))
        }
        true
    } catch (failure: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw failure
    }
}

private fun requireAttachmentOutputStream(output: java.io.OutputStream?): java.io.OutputStream =
    output ?: throw java.io.IOException("MediaStore returned no output stream")

private fun requireMediaStoreFinalized(updatedRows: Int) {
    if (updatedRows <= 0) throw java.io.IOException("MediaStore finalization failed")
}

@VisibleForTesting
internal fun <T : Any> retryNullableMediaStoreInsert(
    attempts: Int = MEDIA_STORE_INSERT_ATTEMPTS,
    onRetry: () -> Unit = { Thread.sleep(MEDIA_STORE_INSERT_RETRY_DELAY_MILLIS) },
    insert: () -> T?,
): T {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        val value =
            attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_INSERT) {
                insert()
            }
        if (value != null) return value
        if (attempt < attempts - 1) {
            attachmentSaveStage(AttachmentSaveStage.MEDIASTORE_INSERT, onRetry)
        }
    }
    throw AttachmentSaveException(AttachmentSaveStage.MEDIASTORE_INSERT)
}

@Suppress("TooGenericExceptionCaught")
private inline fun <T> attachmentSaveStage(
    stage: AttachmentSaveStage,
    block: () -> T,
): T =
    try {
        block()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (failure: AttachmentSaveException) {
        throw failure
    } catch (failure: Throwable) {
        throw AttachmentSaveException(stage, failure)
    }

internal fun copyDocumentToDestination(
    context: Context,
    source: java.io.File,
    destination: Uri,
) {
    val output =
        attachmentSaveStage(AttachmentSaveStage.DOCUMENT_DESTINATION_OPEN) {
            context.contentResolver.openOutputStream(destination, "w")
                ?: throw java.io.IOException("document provider returned no output stream")
        }
    attachmentSaveStage(AttachmentSaveStage.DOCUMENT_DESTINATION_WRITE) {
        output.use { destinationStream ->
            source.inputStream().use { input -> input.copyTo(destinationStream, DEFAULT_BUFFER_SIZE) }
        }
    }
}
