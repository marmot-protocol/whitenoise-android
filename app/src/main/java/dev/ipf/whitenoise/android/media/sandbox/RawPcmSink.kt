@file:Suppress("MagicNumber") // Default renderer polling tolerance is expressed in microseconds.

package dev.ipf.whitenoise.android.media.sandbox

/** The bounded PCM device boundary also permits deterministic hardware-refusal fixtures. */
internal interface RawPcmSink : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    fun write(
        bytes: ByteArray,
        offset: Int,
    ): Int

    /** Default keeps already-consumed fixture sinks compatible; the real sink retains partial DSP output. */
    fun write(
        bytes: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int {
        require(offset >= 0 && byteCount >= 0 && offset <= bytes.size - byteCount)
        if (byteCount == bytes.size - offset) return write(bytes, offset)
        val selected = bytes.copyOfRange(offset, offset + byteCount)
        return write(selected, 0)
    }

    fun prepareForWait(endOfStream: Boolean = false): Long = 1000L

    fun bufferedPositionUs(): Long = writtenPositionUs()

    fun play()

    fun pause()

    fun speed(value: Float)

    fun volume(value: Float)

    fun drained(): Boolean

    fun writtenPositionUs(): Long

    fun positionUs(): Long
}
