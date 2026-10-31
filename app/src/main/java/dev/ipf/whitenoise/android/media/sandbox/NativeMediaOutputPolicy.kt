@file:Suppress("MagicNumber") // Fixed primitive layout and media format/resource ceilings.

package dev.ipf.whitenoise.android.media.sandbox

/** Validate all native scalar and buffer shapes before allocating or copying outputs. */
internal object NativeMediaOutputPolicy {
    const val PCM_CAPACITY = 65536 * 2 * 2

    fun info(values: LongArray): NativeMediaInfo {
        require(values.size == 6)
        val width = scalar(values[0], 0, MediaDecodePolicy.MAX_SOURCE_EDGE)
        val height = scalar(values[1], 0, MediaDecodePolicy.MAX_SOURCE_EDGE)
        require((width == 0 && height == 0) || MediaDecodePolicy.sourceAllowed(width, height))
        require(values[2] in 0..MAX_MEDIA_DURATION_MS * 1000)
        val rotation = scalar(values[3], 0, 270)
        require(rotation % 90 == 0)
        val rate = scalar(values[4], 0, 96000)
        val channels = scalar(values[5], 0, 2)
        require((rate == 0 && channels == 0) || (rate in 8000..96000 && channels in 1..2))
        return NativeMediaInfo(width, height, values[2], rotation, rate, channels)
    }

    fun frame(
        values: LongArray,
        edge: Int,
    ): NativeMediaFrame {
        require(values.size == 7)
        val kind = scalar(values[0], 0, 2)
        val width = scalar(values[1], 0, MediaDecodePolicy.MAX_EDGE)
        val height = scalar(values[2], 0, MediaDecodePolicy.MAX_EDGE)
        val rate = scalar(values[3], 0, 96000)
        val channels = scalar(values[4], 0, 2)
        require(values[5] in -10_000_000L..MAX_MEDIA_DURATION_MS * 1000)
        val bytes = scalar(values[6], 0, MediaDecodePolicy.MAX_OUTPUT_BYTES)
        when (kind) {
            0 -> require(values.drop(1).all { it == 0L })
            1 ->
                require(
                    rate == 0 &&
                        channels == 0 &&
                        MediaDecodePolicy.pixelBytes(
                            width,
                            height,
                            edge,
                            MediaDecodePolicy.MAX_OUTPUT_BYTES,
                        ) == bytes &&
                        bytes > 0,
                )
            2 ->
                require(
                    width == 0 &&
                        height == 0 &&
                        rate in 8000..96000 &&
                        channels in 1..2 &&
                        bytes in 1..PCM_CAPACITY &&
                        bytes % (channels * 2) == 0,
                )
        }
        return NativeMediaFrame(kind, width, height, rate, channels, values[5], bytes)
    }

    private fun scalar(
        value: Long,
        minimum: Int,
        maximum: Int,
    ): Int {
        require(value in minimum.toLong()..maximum.toLong())
        return value.toInt()
    }
}

internal data class NativeMediaInfo(
    val width: Int,
    val height: Int,
    val durationUs: Long,
    val rotationDegrees: Int,
    val sampleRate: Int,
    val channels: Int,
)

internal data class NativeMediaFrame(
    val kind: Int,
    val width: Int,
    val height: Int,
    val sampleRate: Int,
    val channels: Int,
    val ptsUs: Long,
    val bytes: Int,
)
