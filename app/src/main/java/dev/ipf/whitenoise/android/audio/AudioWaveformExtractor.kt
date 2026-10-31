package dev.ipf.whitenoise.android.audio

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * Decodes a small voice clip to PCM and produces a 64-bar normalized
 * mean-absolute-amplitude waveform — the same signal the big-3 chat clients
 * ship in their proto. Computed lazily on first display per file and cached
 * so re-renders are free. Receiver-side workaround until the imeta wire
 * format can carry a sender-baked waveform.
 */
object AudioWaveformExtractor {
    const val BARS = 64
    private const val TAG = "WaveformExtractor"
    private const val TIMEOUT_US = 10_000L

    // Pure decoder-fixture budgets retain the waveform algorithm regression coverage.
    // The elapsed cap is the primary runaway guard; the iteration ceiling is a
    // secondary backstop sized to clear the ~5-minute maximum recording (one
    // in/one out buffer per pass is tens of thousands of passes) with wide
    // margin, so a legitimately long clip still produces a waveform.
    private const val MAX_DECODE_LOOP_ITERATIONS = 500_000
    private const val MAX_DECODE_ELAPSED_NANOS = 30_000_000_000L

    // Cap on cached waveforms. Each value is a 64-float array (~256 B of
    // payload plus object overhead) keyed by absolute file path; without a
    // bound the map held one entry per distinct clip ever decoded for the
    // process lifetime (see #230). 256 covers any realistic scroll window
    // while keeping worst-case memory flat. android.util.LruCache is
    // internally synchronized, preserving the prior ConcurrentHashMap's
    // thread-safety guarantee.
    private const val CACHE_MAX_ENTRIES = 256
    private val decodeGate = Semaphore(2)

    // android.media.AudioFormat.ENCODING_PCM_* values, redeclared so the pure
    // decode logic stays free of the Android framework (keeps decodeBlocking
    // unit-testable). Production receives bounded PCM16 from the isolated native worker.
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_8BIT = 3
    private const val ENCODING_PCM_FLOAT = 4
    private const val ENCODING_PCM_32BIT = 22

    private val cache = LruCache<String, FloatArray>(CACHE_MAX_ENTRIES)

    suspend fun decode(file: File): FloatArray? {
        val result =
            withContext(Dispatchers.IO) {
                val cacheKey = waveformCacheKey(file)
                cache.get(cacheKey)?.let { return@withContext it }
                try {
                    decodeGate
                        .withPermit {
                            dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
                                .waveform(file)
                        }.also { waveform -> waveform?.let { cache.put(cacheKey, it) } }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    Log.w(TAG, "waveform_decode_failed")
                    null
                }
            }
        return result
    }

    internal fun waveformCacheKey(file: File): String = "${file.absolutePath}:${file.length()}:${file.lastModified()}"

    internal fun <FormatT> decodeBlocking(
        filePath: String,
        resources: AudioDecoderResources<FormatT>,
        cancellationCheck: () -> Unit = {},
        nanoTime: () -> Long = System::nanoTime,
        maxLoopIterations: Int = MAX_DECODE_LOOP_ITERATIONS,
        maxElapsedNanos: Long = MAX_DECODE_ELAPSED_NANOS,
    ): FloatArray? {
        var extractor: AudioDecoderExtractor<FormatT>? = null
        var codec: AudioDecoderCodec<FormatT>? = null
        var codecStarted = false

        try {
            val activeExtractor = resources.createExtractor().also { extractor = it }
            activeExtractor.setDataSource(filePath)

            var trackIdx = -1
            var format: FormatT? = null
            for (i in 0 until activeExtractor.trackCount) {
                val f = activeExtractor.getTrackFormat(i)
                val mime = activeExtractor.mime(f) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIdx = i
                    format = f
                    break
                }
            }
            if (trackIdx < 0 || format == null) {
                return null
            }
            activeExtractor.selectTrack(trackIdx)
            val mime = activeExtractor.mime(format)!!

            val activeCodec = resources.createCodec(mime).also { codec = it }
            activeCodec.configure(format)
            activeCodec.start()
            codecStarted = true
            val info = AudioDecoderOutputInfo()

            // One folded-amplitude summary per output buffer ("chunk"). We bucket
            // by decoded-frame index, not presentationTimeUs: decoded PCM is
            // uniform in time, so a frame's global index is proportional to its
            // timestamp. That keeps the waveform correct even when the track omits
            // KEY_DURATION — the old pts/duration math collapsed every sample into
            // the final bar in that case. The total frame count is only known once
            // decoding finishes, so bucketing is deferred to the end. Chunk count
            // is bounded by the codec loop guard. See #277.
            val summary = WaveformAccumulator()

            // Output PCM layout, resolved once the codec reports its output
            // format. A decoder may emit float or 8-bit PCM and may be
            // multichannel; we interpret samples accordingly and fold channels to
            // a single per-frame amplitude instead of assuming interleaved 16-bit.
            var channelCount = 1
            var pcmEncoding = ENCODING_PCM_16BIT
            var formatResolved = false

            var endOfStream = false
            var loopIterations = 0
            val startedAtNanos = nanoTime()

            while (true) {
                cancellationCheck()
                loopIterations++
                if (loopIterations > maxLoopIterations) {
                    throw IllegalStateException(
                        "audio waveform decode exceeded $maxLoopIterations codec loop iterations",
                    )
                }
                if (nanoTime() - startedAtNanos > maxElapsedNanos) {
                    throw IllegalStateException("audio waveform decode exceeded ${maxElapsedNanos / 1_000_000L}ms")
                }

                if (!endOfStream) {
                    val inIdx = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = activeCodec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) activeExtractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            activeCodec.queueInputBuffer(inIdx, 0, 0, 0, endOfStream = true)
                            endOfStream = true
                        } else {
                            activeCodec.queueInputBuffer(
                                inIdx,
                                0,
                                sampleSize,
                                activeExtractor.sampleTime,
                                endOfStream = false,
                            )
                            activeExtractor.advance()
                        }
                    }
                }

                val outIdx = activeCodec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (!formatResolved) {
                        channelCount = activeCodec.outputChannelCount().coerceAtLeast(1)
                        pcmEncoding = activeCodec.outputPcmEncoding()
                        formatResolved = true
                    }
                    val outBuf = activeCodec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        var sum = 0.0
                        var peak = 0f
                        var frames = 0
                        forEachFrameAmplitude(outBuf, info.size, channelCount, pcmEncoding) { amp ->
                            sum += amp
                            if (amp > peak) peak = amp
                            frames++
                        }
                        if (frames > 0) {
                            summary.addChunk(sum, peak, frames)
                        }
                    }
                    activeCodec.releaseOutputBuffer(outIdx, false)
                    if (info.endOfStream) break
                }
            }

            return summary.finish()
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /**
     * Walk [buf] (positioned/limited to one output buffer of [byteCount] bytes)
     * frame by frame, folding [channelCount] interleaved channels into a single
     * mean amplitude in `[0, 1]` per frame. Samples are read according to
     * [pcmEncoding] so float / 8-bit / 32-bit output isn't misread as 16-bit.
     * Unknown encodings fall back to signed 16-bit, the decoder default.
     */
    private inline fun forEachFrameAmplitude(
        buf: ByteBuffer,
        byteCount: Int,
        channelCount: Int,
        pcmEncoding: Int,
        onFrame: (Float) -> Unit,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        when (pcmEncoding) {
            ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                val frames = (byteCount / 4) / channels
                repeat(frames) {
                    var acc = 0f
                    repeat(channels) { acc += abs(fb.get()) }
                    onFrame((acc / channels).coerceIn(0f, 1f))
                }
            }
            ENCODING_PCM_8BIT -> {
                val frames = byteCount / channels
                repeat(frames) {
                    var acc = 0f
                    // 8-bit PCM is unsigned, centred at 128.
                    repeat(channels) { acc += abs((buf.get().toInt() and 0xFF) - 128) / 128f }
                    onFrame((acc / channels).coerceIn(0f, 1f))
                }
            }
            ENCODING_PCM_32BIT -> {
                val ib = buf.asIntBuffer()
                val frames = (byteCount / 4) / channels
                repeat(frames) {
                    var acc = 0f
                    repeat(channels) { acc += (abs(ib.get().toLong()) / 2_147_483_648.0).toFloat() }
                    onFrame((acc / channels).coerceIn(0f, 1f))
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                val frames = (byteCount / 2) / channels
                repeat(frames) {
                    var acc = 0f
                    repeat(channels) { acc += abs(sb.get().toInt()) / 32768f }
                    onFrame((acc / channels).coerceIn(0f, 1f))
                }
            }
        }
    }
}

internal data class AudioDecoderOutputInfo(
    var offset: Int = 0,
    var size: Int = 0,
    var presentationTimeUs: Long = 0,
    var endOfStream: Boolean = false,
)

internal interface AudioDecoderResources<FormatT> {
    fun createExtractor(): AudioDecoderExtractor<FormatT>

    fun createCodec(mime: String): AudioDecoderCodec<FormatT>
}

internal interface AudioDecoderExtractor<FormatT> {
    val trackCount: Int
    val sampleTime: Long

    fun setDataSource(filePath: String)

    fun getTrackFormat(index: Int): FormatT

    fun mime(format: FormatT): String?

    fun durationUs(format: FormatT): Long?

    fun selectTrack(index: Int)

    fun readSampleData(
        buffer: ByteBuffer,
        offset: Int,
    ): Int

    fun advance()

    fun release()
}

internal interface AudioDecoderCodec<FormatT> {
    fun configure(format: FormatT)

    fun start()

    fun dequeueInputBuffer(timeoutUs: Long): Int

    fun getInputBuffer(index: Int): ByteBuffer?

    fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        endOfStream: Boolean,
    )

    fun dequeueOutputBuffer(
        info: AudioDecoderOutputInfo,
        timeoutUs: Long,
    ): Int

    fun getOutputBuffer(index: Int): ByteBuffer?

    fun releaseOutputBuffer(
        index: Int,
        render: Boolean,
    )

    // Output PCM layout, read after decoding has started. Defaults match the
    // decoder default (mono, signed 16-bit) for resources that don't expose it.
    fun outputChannelCount(): Int = 1

    fun outputPcmEncoding(): Int = 2

    fun stop()

    fun release()
}
