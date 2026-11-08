@file:Suppress("MagicNumber") // Default renderer polling tolerance is expressed in microseconds.

package dev.ipf.whitenoise.android.media.sandbox

/** The bounded PCM device boundary also permits deterministic hardware-refusal fixtures. */
internal interface RawPcmSink : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    /**
     * Returns the number of selected PCM bytes consumed; callers retain any unconsumed suffix for the next
     * attempt.
     */
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

    /**
     * Allows the sink to flush end-of-input and return a polling tolerance without accepting more source
     * bytes.
     */
    fun prepareForWait(endOfStream: Boolean = false): Long = 1000L

    /** Returns the source-timeline endpoint already buffered by this sink. */
    fun bufferedPositionUs(): Long = writtenPositionUs()

    /** Resumes the existing sink; it must not replace the selected compressed source. */
    fun play()

    /** Suspends output while retaining the source position and previously accepted PCM. */
    fun pause()

    /** Requests a supported source playback rate without changing the selected audio format. */
    fun speed(value: Float)

    /** Applies a finite playback gain without changing source or frame ownership. */
    fun volume(value: Float)

    /** Reports whether all accepted source media has reached its terminal output boundary. */
    fun drained(): Boolean

    /** Returns the source-timeline endpoint accepted from the decoder, excluding device-only silence. */
    fun writtenPositionUs(): Long

    /**
     * Returns the last established source playback position, without requiring the caller to access audio
     * hardware.
     */
    fun positionUs(): Long
}
