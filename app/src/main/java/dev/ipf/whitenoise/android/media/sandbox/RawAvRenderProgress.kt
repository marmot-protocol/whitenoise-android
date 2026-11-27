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
    private var followingVideo: AvStreamFrame.Video? = null
    var audio: RawAvPendingPcm? = null

    /** Keep the nearest preceding frame until the next timestamp confirms its display interval. */
    fun replacePreroll(frame: AvStreamFrame.Video?) {
        preroll?.bitmap?.recycle()
        preroll = frame
    }

    /** A frame exactly at the target replaces preroll. A later frame establishes coverage. */
    fun queueVideo(frame: AvStreamFrame.Video) {
        val covering = if (frame.timestampUs > targetUs) takePreroll() else null
        replacePreroll(null)
        video = covering ?: frame
        followingVideo = frame.takeIf { covering != null }
    }

    fun advanceVideo() {
        video = followingVideo
        followingVideo = null
    }

    fun clearVideo() {
        video?.bitmap?.recycle()
        followingVideo?.bitmap?.recycle()
        video = null
        followingVideo = null
        replacePreroll(null)
    }

    fun takePreroll(): AvStreamFrame.Video? = preroll.also { preroll = null }
}
