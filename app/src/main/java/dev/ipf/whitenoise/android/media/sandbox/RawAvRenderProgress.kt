package dev.ipf.whitenoise.android.media.sandbox

internal class RawAvPendingPcm(
    val frame: AvStreamFrame.Audio,
    var offset: Int,
    val limit: Int,
    val timestampUs: Long,
    var gapFrames: Long,
    var silence: ByteArray? = null,
) {
    val initialOffset = offset
}

internal class RawAvRenderProgress(
    var targetUs: Long,
    var videoEnded: Boolean,
    var audioEnded: Boolean,
) {
    var lastVideoPts = -1L
    var lastVideoDelta = 33_333L
    var audioEndUs = targetUs
    var prerollAudioUs = Long.MIN_VALUE
    var video: AvStreamFrame.Video? = null
    private var preroll: AvStreamFrame.Video? = null
    var audio: RawAvPendingPcm? = null

    /** Keep the nearest preceding frame until a matching frame or true video EOF. */
    fun replacePreroll(frame: AvStreamFrame.Video?) {
        preroll?.bitmap?.recycle()
        preroll = frame
    }

    fun takePreroll(): AvStreamFrame.Video? = preroll.also { preroll = null }
}
