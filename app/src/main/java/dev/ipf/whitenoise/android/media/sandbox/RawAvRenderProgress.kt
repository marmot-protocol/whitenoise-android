package dev.ipf.whitenoise.android.media.sandbox

/** One frozen reserve through the presentation horizon, with extra lead for the mixer when available. */
internal class RawAvAudioReserve(
    presentationTargetUs: Long,
    mixerTargetUs: Long,
) {
    private val minimumUs = presentationTargetUs.coerceAtMost(AvStreamProtocol.MAX_DURATION_US)
    val targetUs = maxOf(minimumUs, mixerTargetUs).coerceAtMost(AvStreamProtocol.MAX_DURATION_US)

    fun trackFor(bufferedUs: Long): Int =
        if (bufferedUs < minimumUs) {
            AvStreamProtocol.AUDIO_TRACK
        } else {
            AvStreamProtocol.AUDIO_RESERVE_TRACK
        }
}

/** Holds one bounded PCM write until the audio device accepts its frames and any preceding gap. */
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

/** Tracks decoded output owned by one render pass, including the frames around its seek target. */
internal class RawAvRenderProgress(
    var targetUs: Long,
    var videoEnded: Boolean,
    var audioEnded: Boolean,
) {
    var lastVideoPts = -1L
    var lastVideoDelta = 33_333L
    var audioEndUs = targetUs
    var audioReserveDeferred = false
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

    /** Promotes the following frame after the covering frame is presented. */
    fun advanceVideo() {
        video = followingVideo
        followingVideo = null
    }

    /** Releases every owned bitmap when the render pass advances to a new seek or ends. */
    fun clearVideo() {
        video?.bitmap?.recycle()
        followingVideo?.bitmap?.recycle()
        video = null
        followingVideo = null
        replacePreroll(null)
    }

    /** Transfers ownership of the retained covering frame to the caller. */
    fun takePreroll(): AvStreamFrame.Video? = preroll.also { preroll = null }
}
