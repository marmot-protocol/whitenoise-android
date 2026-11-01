package dev.ipf.whitenoise.android.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/** Historical debug-only platform waveform experiment; absent from release source. */
internal fun decodeHistoricalWaveformFromDescriptor(descriptor: java.io.FileDescriptor): FloatArray? =
    AudioWaveformExtractor.decodeBlocking(
        filePath = "",
        resources = AndroidAudioDecoderResources(descriptor),
    )

private class AndroidAudioDecoderResources(
    private val descriptor: java.io.FileDescriptor,
) : AudioDecoderResources<MediaFormat> {
    override fun createExtractor(): AudioDecoderExtractor<MediaFormat> {
        val extractor = MediaExtractor()
        return AndroidAudioDecoderExtractor(extractor, descriptor)
    }

    override fun createCodec(mime: String): AudioDecoderCodec<MediaFormat> {
        val codec = MediaCodec.createDecoderByType(mime)
        return AndroidAudioDecoderCodec(codec)
    }
}

private class AndroidAudioDecoderExtractor(
    private val extractor: MediaExtractor,
    private val descriptor: java.io.FileDescriptor,
) : AudioDecoderExtractor<MediaFormat> {
    override val trackCount: Int get() = extractor.trackCount
    override val sampleTime: Long get() = extractor.sampleTime

    override fun setDataSource(filePath: String) {
        extractor.setDataSource(descriptor)
    }

    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)

    override fun mime(format: MediaFormat): String? = format.getString(MediaFormat.KEY_MIME)

    override fun durationUs(format: MediaFormat): Long? =
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            null
        }

    override fun selectTrack(index: Int) {
        extractor.selectTrack(index)
    }

    override fun readSampleData(
        buffer: ByteBuffer,
        offset: Int,
    ): Int = extractor.readSampleData(buffer, offset)

    override fun advance() {
        extractor.advance()
    }

    override fun release() {
        extractor.release()
    }
}

@Suppress("TooManyFunctions") // Historical adapter preserves the complete platform codec fixture contract.
private class AndroidAudioDecoderCodec(
    private val codec: MediaCodec,
) : AudioDecoderCodec<MediaFormat> {
    private val mediaInfo = MediaCodec.BufferInfo()

    override fun configure(format: MediaFormat) {
        codec.configure(format, null, null, 0)
    }

    override fun start() {
        codec.start()
    }

    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)

    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        endOfStream: Boolean,
    ) {
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun dequeueOutputBuffer(
        info: AudioDecoderOutputInfo,
        timeoutUs: Long,
    ): Int {
        val outIdx = codec.dequeueOutputBuffer(mediaInfo, timeoutUs)
        info.offset = mediaInfo.offset
        info.size = mediaInfo.size
        info.presentationTimeUs = mediaInfo.presentationTimeUs
        info.endOfStream = mediaInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        return outIdx
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)

    override fun outputChannelCount(): Int =
        runCatching {
            codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        }.getOrDefault(1)

    override fun outputPcmEncoding(): Int =
        runCatching {
            val fmt = codec.outputFormat
            // KEY_PCM_ENCODING (API 24+) is absent when the decoder emits the
            // default signed 16-bit PCM.
            if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
        }.getOrDefault(2)

    override fun releaseOutputBuffer(
        index: Int,
        render: Boolean,
    ) {
        codec.releaseOutputBuffer(index, render)
    }

    override fun stop() {
        codec.stop()
    }

    override fun release() {
        codec.release()
    }
}
