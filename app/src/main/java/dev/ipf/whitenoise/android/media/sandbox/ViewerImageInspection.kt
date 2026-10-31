package dev.ipf.whitenoise.android.media.sandbox

/** A compromised decoder's metadata is untrusted even when its ticket and Binder are genuine. */
internal fun validatedImageInspection(
    width: Int,
    height: Int,
    mime: String,
    orientation: Int,
    svg: Boolean = false,
): ImageInspection? {
    val sourceValid =
        MediaDecodePolicy.sourceAllowed(width, height) &&
            orientation in 1..MediaDecodePolicy.MAX_EXIF_ORIENTATION
    val mimeValid =
        mime.length <= MediaDecodePolicy.MAX_MIME_CHARS &&
            (svg || !mime.substringBefore(';').trim().equals("image/svg+xml", ignoreCase = true))
    val vectorValid = !svg || validViewerVectorInspection(width, height, mime, orientation)
    return if (sourceValid && mimeValid && vectorValid) ImageInspection(width, height, mime, orientation) else null
}

private fun validViewerVectorInspection(
    width: Int,
    height: Int,
    mime: String,
    orientation: Int,
): Boolean {
    val sizeValid = width <= VIEWER_SVG_SOURCE_EDGE && height <= VIEWER_SVG_SOURCE_EDGE
    val identityValid = mime == "image/svg+xml" && orientation == 1
    return sizeValid && identityValid
}
