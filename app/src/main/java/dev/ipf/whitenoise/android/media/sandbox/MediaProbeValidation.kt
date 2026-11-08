package dev.ipf.whitenoise.android.media.sandbox

/**
 * Recycles the probe-owned poster; callers must stop using that bitmap after disposal.
 */
internal fun disposeProbe(probe: MediaProbe) {
    probe.poster?.recycle()
}

/**
 * Accepts bounded source geometry, duration in milliseconds and poster allocation/shape.
 * Returns the same probe on success; refusal recycles its poster and returns null.
 */
internal fun validateMediaProbe(
    probe: MediaProbe,
    edge: Int,
): MediaProbe? {
    val dimensionsValid =
        (probe.width == 0 && probe.height == 0) || MediaDecodePolicy.sourceAllowed(probe.width, probe.height)
    val bitmap = probe.poster
    val bitmapValid =
        bitmap == null ||
            (
                MediaDecodePolicy.pixelBytes(
                    bitmap.width,
                    bitmap.height,
                    edge,
                    MediaDecodePolicy.MAX_OUTPUT_BYTES,
                ) != null &&
                    bitmap.allocationByteCount <= MediaDecodePolicy.MAX_OUTPUT_BYTES
            )
    return if (dimensionsValid && bitmapValid && probe.durationMs in 0..MAX_MEDIA_DURATION_MS) {
        probe
    } else {
        disposeProbe(probe)
        null
    }
}
