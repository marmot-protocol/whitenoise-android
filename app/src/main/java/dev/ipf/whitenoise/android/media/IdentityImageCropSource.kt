package dev.ipf.whitenoise.android.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditorInspectResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import kotlin.coroutines.cancellation.CancellationException

/**
 * A picked picture held open long enough for someone to choose its crop.
 *
 * The encoded [bytes] are kept so the final render works from the original pixels, while [preview]
 * is a bounded decode for the screen. Keeping both is what lets a zoomed crop stay sharp: rendering
 * from the preview would publish whatever the screen happened to be showing.
 */
internal class IdentityImageCropSource(
    val bytes: ByteArray,
    val preview: Bitmap,
    val orientedSize: EditorPixelSize,
)

/**
 * Reads [uri] and prepares it for cropping, or returns null when it cannot be shown.
 *
 * A null means the caller should fall through to its existing unsupported-image handling rather
 * than open a crop surface over a picture nobody can see.
 */
@Suppress("TooGenericExceptionCaught") // A picked picture can fail to read in ways the platform does not enumerate.
private suspend fun readSourceOrNull(
    contentResolver: ContentResolver,
    uri: Uri,
    prepareGroupImage: Boolean,
): ByteArray? =
    try {
        if (prepareGroupImage) {
            readGroupIdentityImageSource(contentResolver, uri)
        } else {
            readIdentityImageSource(contentResolver, uri)
        }
    } catch (cancelled: CancellationException) {
        // Leaving the surface cancels this read. Reporting it as unreadable would send the caller
        // down its fallback path and upload a picture nobody is still choosing.
        throw cancelled
    } catch (_: Exception) {
        null
    }

/**
 * Keeps the original encoded pixels and prepares an orientation-aware bounded preview for cropping.
 * Unreadable or undecodable input returns null; cancellation propagates so a departed picker cannot upload.
 */
internal suspend fun loadIdentityImageCropSource(
    contentResolver: ContentResolver,
    uri: Uri,
    renderer: PhotoEditorRenderer = PhotoEditorRenderer(),
    prepareGroupImage: Boolean = false,
): IdentityImageCropSource? {
    val bytes = readSourceOrNull(contentResolver, uri, prepareGroupImage) ?: return null
    val oriented = (renderer.inspect(bytes) as? PhotoEditorInspectResult.Success)?.source?.orientedSize
    val preview = oriented?.let { renderer.decodePreview(bytes) }
    return if (oriented != null && preview != null) {
        IdentityImageCropSource(bytes = bytes, preview = preview, orientedSize = oriented)
    } else {
        null
    }
}

/** Prepares a verified private attachment with the same group SVG and bounded crop policies as the picker. */
internal suspend fun loadGroupAttachmentCropSource(
    bytes: ByteArray,
    mediaType: String,
): IdentityImageCropSource {
    if (bytes.size > IDENTITY_IMAGE_SOURCE_MAX_BYTES) throw ImageUploadPreparationException.PreparedImageTooLarge
    val source = prepareGroupIdentityImageSource(bytes, mediaType)
    val renderer = PhotoEditorRenderer()
    val oriented = (renderer.inspect(source) as? PhotoEditorInspectResult.Success)?.source?.orientedSize
    val preview = oriented?.let { renderer.decodePreview(source) }
    return if (oriented != null && preview != null) {
        IdentityImageCropSource(source, preview, oriented)
    } else {
        throw ImageUploadPreparationException.UnsupportedImage
    }
}
