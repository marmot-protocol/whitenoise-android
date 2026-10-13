package dev.ipf.whitenoise.android.media.sandbox

/** Local codec resource ceilings, independent of the shared attachment transfer policy. */
internal object MediaDecodePolicy {
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    const val MAX_EDGE = 4096
    const val MAX_OUTPUT_BYTES = 64 * 1024 * 1024
    const val MAX_SOURCE_EDGE = 32768
    const val MAX_SOURCE_PIXELS = 200_000_000L
    const val DEADLINE_MS = 8000L
    const val WATCHDOG_MS = 6000L
    const val VERSION = 1
    const val DECODE = 1
    const val INSPECT = 2
    const val SVG = 3
    const val RESPONSE = 4
    const val PROBE = 5
    const val WAVEFORM = 6
    const val DEBUG_HANG = 7
    const val EXIF = 8
    const val RETIRE = 9
    const val WAVEFORM_DEADLINE_MS = 40000L
    const val REFUSAL_TTL_MS = 60000L
    const val MAX_REFUSALS = 64
    const val SVG_EDGE = 1024
    const val MAX_MIME_CHARS = 64
    const val MAX_EXIF_ORIENTATION = 8
    const val DEBUG_HANG_MS = 30000L
    const val MAX_REPLY_BYTES = 4096
    const val STAGING_CHUNK_BYTES = 16 * 1024

    fun outputCapacity(edge: Int): Int? =
        edge.takeIf { it in 1..MAX_EDGE }?.let {
            (it.toLong() * it * Int.SIZE_BYTES).takeIf { size -> size <= MAX_OUTPUT_BYTES }?.toInt()
        }

    fun sourceAllowed(
        width: Int,
        height: Int,
    ): Boolean =
        width in 1..MAX_SOURCE_EDGE &&
            height in 1..MAX_SOURCE_EDGE &&
            width.toLong() * height <= MAX_SOURCE_PIXELS

    fun pixelBytes(
        width: Int,
        height: Int,
        edge: Int,
        capacity: Int,
    ): Int? =
        if (width in 1..edge && height in 1..edge && edge in 1..MAX_EDGE) {
            (width.toLong() * height * Int.SIZE_BYTES)
                .takeIf { it <= capacity && it <= MAX_OUTPUT_BYTES }
                ?.toInt()
        } else {
            null
        }
}

internal data class ImageInspection(
    val width: Int,
    val height: Int,
    val mime: String,
    val orientation: Int,
)

/** Android rejects hyphens; unsigned generations also remain valid after the signed counter wraps. */
internal fun workerInstanceName(
    lane: String,
    generation: Int,
): String = "${lane}_${Integer.toUnsignedString(generation)}"
