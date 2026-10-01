package dev.ipf.whitenoise.android.media

import android.content.ContentResolver
import android.net.Uri
import dev.ipf.whitenoise.android.core.MAX_ANIMATED_PROFILE_AVATAR_EDGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Media type MDK requires for, and validates against, an animated profile picture. */
internal const val PROFILE_GIF_MEDIA_TYPE = "image/gif"

/** Bytes needed to prove a GIF signature. */
private const val GIF_SIGNATURE_BYTES = 6

/** GIF logical-screen width and height follow the signature as little-endian u16 values. */
private const val GIF_LOGICAL_WIDTH_OFFSET = 6
private const val GIF_LOGICAL_HEIGHT_OFFSET = 8
private const val GIF_LOGICAL_SCREEN_END = 10

/**
 * Prepares the user's own profile picture. A GIF is kept as a GIF — metadata stripped, frames and loop
 * data intact — under the same 2 MiB ceiling and a canvas no larger than the app will animate; anything
 * else takes the unchanged JPEG path that group images also use. Group-image preparation is untouched.
 *
 * The only decode is the existing static first-frame decode, which validates the file and yields the
 * placeholder hash; no new decoder runs over picked bytes.
 */
internal object ProfilePictureDraftProcessor {
    /** Fetches a remote pick through the shared bounded policy and prepares it as a profile picture. */
    suspend fun fromRemoteUrl(rawUrl: String): ImageUploadDraft =
        withContext(Dispatchers.IO) {
            val (url, source) = GroupImageDraftProcessor.fetchRemoteImage(rawUrl)
            fromBytes(source, url)
        }

    /** Prepares a photo-picker pick, reading a GIF with a hard byte bound and anything else as before. */
    suspend fun fromContentUri(
        contentResolver: ContentResolver,
        uri: Uri,
    ): ImageUploadDraft =
        withContext(Dispatchers.IO) {
            if (!isGifSource(contentResolver, uri)) {
                return@withContext GroupImageDraftProcessor.fromContentUri(contentResolver, uri)
            }
            val bytes =
                readProvider(contentResolver, uri) {
                    MediaPipeline.readBoundedBytes(it, REMOTE_PROFILE_IMAGE_MAX_BYTES)
                        ?: throw ImageUploadPreparationException.PreparedImageTooLarge
                } ?: throw ImageUploadPreparationException.UnsupportedImage
            animatedGifDraft(bytes, sourceUrl = null)
        }

    /** True when [uri]'s first bytes carry a GIF signature; unreadable sources are not GIFs. */
    suspend fun isGifSource(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            readProvider(contentResolver, uri) { stream ->
                val header = ByteArray(GIF_SIGNATURE_BYTES)
                var read = 0
                while (read < header.size) {
                    val count = stream.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
                read == header.size && isGif(header)
            } == true
        }

    /** Runs [block] over [uri]'s stream; a missing, unreadable or forbidden provider yields null. */
    private inline fun <T> readProvider(
        contentResolver: ContentResolver,
        uri: Uri,
        block: (java.io.InputStream) -> T,
    ): T? =
        try {
            contentResolver.openInputStream(uri)?.use(block)
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    /** GIF bytes keep their animation; every other format is flattened to JPEG exactly as before. */
    internal fun fromBytes(
        source: ByteArray,
        sourceUrl: String?,
    ): ImageUploadDraft =
        if (isGif(source)) {
            animatedGifDraft(source, sourceUrl)
        } else {
            GroupImageDraftProcessor.fromBytes(source, sourceUrl)
        }

    /**
     * Keeps [source] as an `image/gif` draft. Oversized bytes or canvas fail as too large, and a GIF that
     * cannot be sanitized or whose first frame does not decode fails as unsupported — neither is ever
     * silently flattened to JPEG.
     */
    internal fun animatedGifDraft(
        source: ByteArray,
        sourceUrl: String?,
    ): ImageUploadDraft {
        val (width, height) = boundedGifCanvas(source)
        val (sanitized, firstFrame) =
            MediaPipeline.sanitizeAnimatedImageMetadata(source)?.let { stripped ->
                MediaPipeline.readDownscaledJpeg(stripped)?.let { stripped to it }
            } ?: throw ImageUploadPreparationException.UnsupportedImage
        return ImageUploadDraft(
            plaintext = sanitized,
            mediaType = PROFILE_GIF_MEDIA_TYPE,
            sourceUrl = sourceUrl,
            dim = "${width}x$height",
            thumbhash = firstFrame.thumbhash,
        )
    }

    /** The GIF canvas, failing as too large past the byte or edge bound and as unsupported when empty. */
    private fun boundedGifCanvas(source: ByteArray): Pair<Int, Int> {
        val canvas = gifCanvas(source)
        val tooLarge =
            source.size > REMOTE_PROFILE_IMAGE_MAX_BYTES ||
                canvas != null &&
                (canvas.first > MAX_ANIMATED_PROFILE_AVATAR_EDGE || canvas.second > MAX_ANIMATED_PROFILE_AVATAR_EDGE)
        if (tooLarge) throw ImageUploadPreparationException.PreparedImageTooLarge
        return canvas ?: throw ImageUploadPreparationException.UnsupportedImage
    }

    /** The GIF logical screen, or null when it is truncated or empty. */
    private fun gifCanvas(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < GIF_LOGICAL_SCREEN_END) return null
        val width = u16le(bytes, GIF_LOGICAL_WIDTH_OFFSET)
        val height = u16le(bytes, GIF_LOGICAL_HEIGHT_OFFSET)
        return (width to height).takeIf { width > 0 && height > 0 }
    }
}
