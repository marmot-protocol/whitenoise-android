package dev.ipf.darkmatter.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
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
    private const val FLOOR = 0.05f

    // Cap on cached waveforms. Each value is a 64-float array (~256 B of
    // payload plus object overhead) keyed by absolute file path; without a
    // bound the map held one entry per distinct clip ever decoded for the
    // process lifetime (see #230). 256 covers any realistic scroll window
    // while keeping worst-case memory flat. android.util.LruCache is
    // internally synchronized, preserving the prior ConcurrentHashMap's
    // thread-safety guarantee.
    private const val CACHE_MAX_ENTRIES = 256

    private val cache = LruCache<String, FloatArray>(CACHE_MAX_ENTRIES)

    suspend fun decode(file: File): FloatArray? {
        cache.get(file.absolutePath)?.let { return it }
        val result =
            withContext(Dispatchers.IO) {
                runCatching { decodeBlocking(file) }
                    .onFailure { Log.w(TAG, "decode failed", it) }
                    .getOrNull()
            }
        if (result != null) cache.put(file.absolutePath, result)
        return result
    }

    private fun decodeBlocking(file: File): FloatArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIdx = i
                format = f
                break
            }
        }
        if (trackIdx < 0 || format == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIdx)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs =
            (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 1L)
                .coerceAtLeast(1L)
        val sliceDurationUs = (durationUs / BARS).coerceAtLeast(1L)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()

        // Track BOTH the peak per bucket and the running sum. Peak amplitude
        // produces visually punchier bars than mean-abs (real speech has
        // many quiet samples between syllables; mean averages them down).
        val peaks = IntArray(BARS)
        val sums = LongArray(BARS)
        val counts = IntArray(BARS)
        var endOfStream = false

        try {
            while (true) {
                if (!endOfStream) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            endOfStream = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuf = outBuf.asShortBuffer()
                        val bucket = (info.presentationTimeUs / sliceDurationUs).toInt().coerceIn(0, BARS - 1)
                        while (shortBuf.hasRemaining()) {
                            val v = abs(shortBuf.get().toInt())
                            sums[bucket] += v.toLong()
                            counts[bucket]++
                            if (v > peaks[bucket]) peaks[bucket] = v
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        // Hybrid signal: peak per bucket biased by mean (peak * 0.7 +
        // mean * 0.3) — peaks give visual punch, mean prevents single-spike
        // outliers from flattening neighbours. sqrt() applied at the end
        // perceptually lifts quiet sections so the shape reads as audio
        // dynamics, not raw linear power.
        val hybrid =
            FloatArray(BARS) { i ->
                val mean = if (counts[i] > 0) sums[i].toFloat() / counts[i].toFloat() else 0f
                peaks[i].toFloat() * 0.7f + mean * 0.3f
            }
        val maxV = hybrid.maxOrNull()?.takeIf { it > 0f } ?: return null
        val result =
            FloatArray(BARS) { i ->
                val norm = (hybrid[i] / maxV).coerceIn(0f, 1f)
                FLOOR + (1f - FLOOR) * kotlin.math.sqrt(norm)
            }
        return result
    }
}
