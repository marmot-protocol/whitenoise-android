package dev.ipf.whitenoise.android.media

import android.content.ContentResolver
import android.net.Uri
import dev.ipf.marmotkit.InitialGroupImageFfi
import dev.ipf.whitenoise.android.core.SafeHttpsGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal const val REMOTE_PROFILE_IMAGE_MAX_BYTES = 2 * 1024 * 1024

internal data class ImageUploadDraft(
    val plaintext: ByteArray,
    val mediaType: String,
    val sourceUrl: String?,
    val dim: String?,
    val thumbhash: String?,
) {
    fun initialGroupImage(): InitialGroupImageFfi =
        InitialGroupImageFfi(
            plaintext = plaintext,
            mediaType = mediaType,
            // A URL avatar takes precedence over the encrypted image in MDK.
            // Keep the web origin as local draft context only; publishing it
            // here would accidentally select the legacy URL component.
            sourceUrl = null,
            dim = dim,
            thumbhash = thumbhash,
        )
}

internal const val REMOVE_GROUP_IMAGE_MUTATION_KEY = "remove"

/**
 * Stable identity for retrying the two-commit legacy-avatar migration. The
 * digest lets the controller distinguish a retry of the same prepared image
 * from a newly selected replacement without retaining another copy of the
 * image bytes.
 */
internal fun ImageUploadDraft.mutationKey(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(mediaType.toByteArray(Charsets.UTF_8))
    digest.update(0)
    val hash = digest.digest(plaintext)
    return buildString(7 + hash.size * 2) {
        append("upload:")
        hash.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
    }
}

internal fun shouldCommitPrimaryGroupImageMutation(
    requestedMutationKey: String,
    pendingLegacyClearMutationKey: String?,
    hasProjectedEncryptedImage: Boolean,
): Boolean {
    if (pendingLegacyClearMutationKey == requestedMutationKey) return false
    return requestedMutationKey != REMOVE_GROUP_IMAGE_MUTATION_KEY ||
        hasProjectedEncryptedImage ||
        pendingLegacyClearMutationKey != null
}

internal sealed class ImageUploadPreparationException : Exception() {
    data object InvalidUrl : ImageUploadPreparationException()

    data object DownloadFailed : ImageUploadPreparationException()

    data object UnsupportedImage : ImageUploadPreparationException()

    data object PreparedImageTooLarge : ImageUploadPreparationException()
}

/**
 * Converts a user-selected public image URL into bounded, metadata-stripped
 * JPEG bytes suitable for MDK's encrypted group-image or public profile-image
 * upload APIs.
 */
internal object GroupImageDraftProcessor {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val ACCEPT_HEADER = "image/avif,image/webp,image/apng,image/png,image/jpeg,image/gif,*/*;q=0.8"

    suspend fun fromRemoteUrl(rawUrl: String): ImageUploadDraft =
        withContext(Dispatchers.IO) {
            val url = sanitizeHttpsAvatarUrl(rawUrl) ?: throw ImageUploadPreparationException.InvalidUrl
            val source =
                SafeHttpsGet.get(
                    url = url,
                    maxBodyBytes = REMOTE_PROFILE_IMAGE_MAX_BYTES,
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS,
                    readTimeoutMillis = READ_TIMEOUT_MS,
                    requestHeaders =
                        mapOf(
                            "Accept" to ACCEPT_HEADER,
                            "Cache-Control" to "no-store",
                        ),
                ) ?: throw ImageUploadPreparationException.DownloadFailed
            fromBytes(source, url)
        }

    suspend fun fromContentUri(
        contentResolver: ContentResolver,
        uri: Uri,
    ): ImageUploadDraft =
        withContext(Dispatchers.IO) {
            val prepared =
                MediaPipeline.readDownscaledJpeg(contentResolver, uri)
                    ?: throw ImageUploadPreparationException.UnsupportedImage
            if (prepared.bytes.size > REMOTE_PROFILE_IMAGE_MAX_BYTES) {
                throw ImageUploadPreparationException.PreparedImageTooLarge
            }
            ImageUploadDraft(
                plaintext = prepared.bytes,
                mediaType = MediaPipeline.RECOMPRESSED_MIME,
                sourceUrl = null,
                dim = "${prepared.width}x${prepared.height}",
                thumbhash = prepared.thumbhash,
            )
        }

    internal fun fromBytes(
        source: ByteArray,
        sourceUrl: String?,
    ): ImageUploadDraft {
        val prepared =
            MediaPipeline.readDownscaledJpeg(source)
                ?: throw ImageUploadPreparationException.UnsupportedImage
        if (prepared.bytes.size > REMOTE_PROFILE_IMAGE_MAX_BYTES) {
            throw ImageUploadPreparationException.PreparedImageTooLarge
        }
        return ImageUploadDraft(
            plaintext = prepared.bytes,
            mediaType = MediaPipeline.RECOMPRESSED_MIME,
            sourceUrl = sourceUrl,
            dim = "${prepared.width}x${prepared.height}",
            thumbhash = prepared.thumbhash,
        )
    }
}
