package dev.ipf.whitenoise.android.media.sandbox

/** A compromised decoder's metadata is untrusted even when its ticket and Binder are genuine. */
internal fun validatedImageInspection(
    width: Int,
    height: Int,
    mime: String,
    orientation: Int,
    svg: Boolean = false,
): ImageInspection? {
    if (
        !MediaDecodePolicy.sourceAllowed(width, height) ||
        orientation !in 1..MediaDecodePolicy.MAX_EXIF_ORIENTATION
    ) {
        return null
    }
    if (mime.length > MediaDecodePolicy.MAX_MIME_CHARS ||
        (!svg && mime.substringBefore(';').trim().equals("image/svg+xml", ignoreCase = true))
    ) {
        return null
    }
    if (svg && (width > 8192 || height > 8192 || mime != "image/svg+xml" || orientation != 1)) return null
    return ImageInspection(width, height, mime, orientation)
}
