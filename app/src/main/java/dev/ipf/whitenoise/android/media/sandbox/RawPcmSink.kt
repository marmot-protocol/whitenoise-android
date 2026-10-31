package dev.ipf.whitenoise.android.media.sandbox

/** The bounded PCM device boundary also permits deterministic hardware-refusal fixtures. */
internal interface RawPcmSink : AutoCloseable {
    val sampleRate: Int
    val channels: Int

    fun write(
        bytes: ByteArray,
        offset: Int,
    ): Int

    fun play()

    fun pause()

    fun speed(value: Float)

    fun volume(value: Float)

    fun drained(): Boolean

    fun writtenPositionUs(): Long

    fun positionUs(): Long
}
