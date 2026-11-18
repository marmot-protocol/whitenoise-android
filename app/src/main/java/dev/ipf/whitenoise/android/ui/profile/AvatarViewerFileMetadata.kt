package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.whitenoise.android.media.MediaPipeline
import java.util.Locale

internal fun avatarViewerFileName(url: String): String {
    val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull()
    val lastSegment =
        parsed
            ?.lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?: "avatar.jpg"
    val safe = MediaPipeline.safeDisplayName(lastSegment)
    return if (safe.contains('.')) safe else "$safe.jpg"
}

// Sniff the real content type from the bytes being saved — the URL filename is
// only a fallback, since extensionless or mislabeled URLs would otherwise store
// PNG/WebP bytes under JPEG metadata in MediaStore.
internal fun avatarViewerMimeType(
    bytes: ByteArray,
    fallbackFileName: String,
): String =
    dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
        .inspect(bytes)
        ?.mime
        ?.takeIf { it.isNotEmpty() } ?: avatarViewerMimeType(fallbackFileName)

private fun avatarViewerMimeType(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> MediaPipeline.RECOMPRESSED_MIME
    }
