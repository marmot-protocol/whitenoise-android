package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

class AudioWaveformExtractorTest {
    @Test
    fun waveformCacheKeyIncludesFileShapeForRetryAfterWrites() {
        val file = File.createTempFile("waveform-cache", ".m4a")
        try {
            file.writeText("a")
            file.setLastModified(1_000L)
            val first = AudioWaveformExtractor.waveformCacheKey(file)
            file.writeText("ab")
            file.setLastModified(2_000L)

            val second = AudioWaveformExtractor.waveformCacheKey(file)

            assertFalse(first == second)
        } finally {
            file.delete()
        }
    }

    @Test
    fun decodeDoesNotCacheTransientFailures() {
        val source =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/audio/AudioWaveformExtractor.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/audio/AudioWaveformExtractor.kt"),
            ).firstOrNull { it.exists() }?.readText()
                ?: error("Missing AudioWaveformExtractor.kt source file")
        val decode = source.kotlinFunctionBody("decode")

        assertTrue("decode must gate scarce MediaCodec work", Regex("""decodeGate\s*\.\s*withPermit""").containsMatchIn(decode))
        assertTrue(
            "decode should cache null/empty only on successful decode, not after the transient-exception catch",
            Regex("""cache\.put\s*\(\s*cacheKey,\s*it\s*\?:\s*UNAVAILABLE_WAVEFORM\s*\)""").find(decode)!!.range.first <
                decode.indexOf("catch (throwable: Throwable)") &&
                "cache.put(cacheKey, result ?: UNAVAILABLE_WAVEFORM)" !in decode,
        )
    }

    @Test
    fun dataSourceFailure_releasesExtractor() {
        val extractor = FakeExtractor(setDataSourceFailure = IllegalStateException("bad source"))
        val resources = FakeResources(extractor = extractor)

        assertThrows(IllegalStateException::class.java) {
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/bad.m4a",
                resources = resources,
            )
        }

        assertTrue(extractor.released)
        assertFalse(resources.codec.created)
    }

    @Test
    fun codecCreationFailure_releasesExtractor() {
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val codec = FakeCodec(createFailure = IllegalStateException("no decoders"))
        val resources = FakeResources(extractor = extractor, codec = codec)

        assertThrows(IllegalStateException::class.java) {
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/clip.m4a",
                resources = resources,
            )
        }

        assertTrue(extractor.released)
        assertTrue(codec.created)
        assertFalse(codec.released)
    }

    @Test
    fun configureFailure_releasesExtractorAndCodec() {
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val codec = FakeCodec(configureFailure = IllegalStateException("bad format"))
        val resources = FakeResources(extractor = extractor, codec = codec)

        assertThrows(IllegalStateException::class.java) {
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/clip.m4a",
                resources = resources,
            )
        }

        assertTrue(extractor.released)
        assertTrue(codec.released)
    }

    @Test
    fun startFailure_releasesExtractorAndCodec() {
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val codec = FakeCodec(startFailure = IllegalStateException("codec wedged"))
        val resources = FakeResources(extractor = extractor, codec = codec)

        assertThrows(IllegalStateException::class.java) {
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/clip.m4a",
                resources = resources,
            )
        }

        assertTrue(extractor.released)
        assertTrue(codec.released)
    }

    @Test
    fun noAudioTrack_returnsNullAndReleasesExtractor() {
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "video/avc")))
        val resources = FakeResources(extractor = extractor)

        val result =
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/video.mp4",
                resources = resources,
            )

        assertNull(result)
        assertTrue(extractor.released)
        assertFalse(resources.codec.created)
    }

    @Test
    fun singleOutputBuffer_producesWaveformAndReleasesResources() {
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val pcm = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putShort(1_000)
        pcm.putShort((-2_000).toShort())
        pcm.flip()
        val codec =
            FakeCodec(
                outputBuffer = pcm,
                outputInfo = AudioDecoderOutputInfo(offset = 0, size = 4, presentationTimeUs = 0, endOfStream = true),
            )
        val resources = FakeResources(extractor = extractor, codec = codec)

        val result =
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/clip.m4a",
                resources = resources,
            )

        assertNotNull(result)
        result!!
        assertEquals(AudioWaveformExtractor.BARS, result.size)
        assertEquals(1.0f, result[0], 0.0001f)
        assertEquals(1.0f, result[1], 0.0001f)
        assertTrue(extractor.released)
        assertTrue(codec.released)
    }

    @Test
    fun shortClipWithFewerChunksThanBars_doesNotLeaveSparseFloorBars() {
        // ~2s mono @ 16 kHz: ~31 codec buffers of ~1024 frames. First-frame-only
        // bucketing maps chunk i to bar floor(i * 1024 * 64 / totalFrames), so
        // only every ~2nd bar fills and the rest stay at FLOOR (comb). See #1161.
        val framesPerChunk = 1024
        val chunkCount = 31
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val outputs =
            (0 until chunkCount).map { index ->
                val pcm = shortPcmFrames(framesPerChunk, sampleAmplitude = 2_000 + index % 50)
                pcm to
                    AudioDecoderOutputInfo(
                        offset = 0,
                        size = framesPerChunk * 2,
                        presentationTimeUs = 0,
                        endOfStream = index == chunkCount - 1,
                    )
            }
        val codec = FakeCodec(outputs = outputs)
        val resources = FakeResources(extractor = extractor, codec = codec)

        val result =
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/short-voice.m4a",
                resources = resources,
            )

        assertNotNull(result)
        result!!
        val floor = 0.05f
        val floorBarCount = result.count { it <= floor + 0.0001f }
        assertTrue(
            "uniform short clip should not leave most bars at floor (got $floorBarCount/${AudioWaveformExtractor.BARS})",
            floorBarCount < AudioWaveformExtractor.BARS / 4,
        )
        assertTrue(codec.released)
    }

    @Test
    fun missingDuration_spreadsEnergyAcrossBarsByFrameIndex() {
        // Two equal-length chunks: a loud one first, a quiet one second. The
        // extractor buckets by decoded-frame index, so the loud first chunk spans
        // the first half and the quiet second chunk spans the second half — energy
        // must NOT collapse into the final bar (the pre-fix behavior when
        // KEY_DURATION was absent). See #277.
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val loud = shortPcm(3_000, 3_000)
        val quiet = shortPcm(1_000, 1_000)
        val codec =
            FakeCodec(
                outputs =
                    listOf(
                        loud to AudioDecoderOutputInfo(offset = 0, size = 4, presentationTimeUs = 0, endOfStream = false),
                        quiet to AudioDecoderOutputInfo(offset = 0, size = 4, presentationTimeUs = 0, endOfStream = true),
                    ),
            )
        val resources = FakeResources(extractor = extractor, codec = codec)

        val result =
            AudioWaveformExtractor.decodeBlocking(filePath = "/tmp/noduration.aac", resources = resources)

        assertNotNull(result)
        result!!
        assertEquals(1.0f, result[0], 0.0001f) // loud chunk, normalized to the max
        assertTrue("middle bar should carry the quieter second chunk", result[32] > 0.05f)
        assertTrue("last bar should carry the quieter second chunk", result[63] > 0.05f)
        assertTrue(codec.released)
    }

    @Test
    fun floatPcmOutput_interpretedAsFloatsNotShorts() {
        // ENCODING_PCM_FLOAT == 4. Samples are normalized floats in [-1, 1];
        // reading them as 16-bit shorts would yield nonsense amplitudes.
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putFloat(0.5f)
        pcm.putFloat(-1.0f)
        pcm.flip()
        val codec =
            FakeCodec(
                outputBuffer = pcm,
                outputInfo = AudioDecoderOutputInfo(offset = 0, size = 8, presentationTimeUs = 0, endOfStream = true),
                encoding = 4,
            )
        val resources = FakeResources(extractor = extractor, codec = codec)

        val result =
            AudioWaveformExtractor.decodeBlocking(filePath = "/tmp/float.aac", resources = resources)

        assertNotNull(result)
        result!!
        assertEquals(AudioWaveformExtractor.BARS, result.size)
        assertEquals(1.0f, result[0], 0.0001f) // peak |−1.0| dominates the single buffer
        assertEquals(1.0f, result[1], 0.0001f)
        assertTrue(codec.released)
    }

    @Test
    fun decodeLoopGuard_releasesExtractorAndCodec() {
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val codec = FakeCodec()
        val resources = FakeResources(extractor = extractor, codec = codec)

        assertThrows(IllegalStateException::class.java) {
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/never-eos.m4a",
                resources = resources,
                maxLoopIterations = 3,
                maxElapsedNanos = Long.MAX_VALUE,
            )
        }

        assertTrue(extractor.released)
        assertTrue(codec.released)
    }

    @Test
    fun cancellationCheck_releasesExtractorAndCodec() {
        val extractor = FakeExtractor(formats = listOf(FakeFormat(mime = "audio/mp4a-latm")))
        val codec = FakeCodec()
        val resources = FakeResources(extractor = extractor, codec = codec)
        var checks = 0

        assertThrows(CancellationException::class.java) {
            AudioWaveformExtractor.decodeBlocking(
                filePath = "/tmp/cancelled.m4a",
                resources = resources,
                cancellationCheck = {
                    checks++
                    if (checks == 2) throw CancellationException("cancelled")
                },
                maxLoopIterations = 100,
                maxElapsedNanos = Long.MAX_VALUE,
            )
        }

        assertTrue(extractor.released)
        assertTrue(codec.released)
    }
}

private fun shortPcm(vararg samples: Int): ByteBuffer {
    val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { buf.putShort(it.toShort()) }
    buf.flip()
    return buf
}

private fun shortPcmFrames(
    frameCount: Int,
    sampleAmplitude: Int,
): ByteBuffer {
    val buf = ByteBuffer.allocate(frameCount * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frameCount) { buf.putShort(sampleAmplitude.toShort()) }
    buf.flip()
    return buf
}

private fun String.kotlinFunctionBody(functionName: String): String {
    val start =
        Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
            .find(this)
            ?.range
            ?.first
            ?: error("Missing function $functionName")
    val braceStart = indexOf('{', start)
    require(braceStart >= 0) { "Missing body for $functionName" }
    var depth = 0
    var index = braceStart
    while (index < length) {
        when (this[index]) {
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                index += 1
                if (depth == 0) return substring(braceStart, index)
                continue
            }
        }
        index += 1
    }
    error("Unterminated function $functionName")
}

private data class FakeFormat(
    val mime: String?,
    val durationUs: Long = 64_000L,
)

private class FakeResources(
    val extractor: FakeExtractor = FakeExtractor(),
    val codec: FakeCodec = FakeCodec(),
) : AudioDecoderResources<FakeFormat> {
    override fun createExtractor(): AudioDecoderExtractor<FakeFormat> = extractor

    override fun createCodec(mime: String): AudioDecoderCodec<FakeFormat> {
        codec.created = true
        codec.createFailure?.let { throw it }
        return codec
    }
}

private class FakeExtractor(
    private val formats: List<FakeFormat> = emptyList(),
    private val setDataSourceFailure: RuntimeException? = null,
) : AudioDecoderExtractor<FakeFormat> {
    var released = false
    override val trackCount: Int get() = formats.size
    override val sampleTime: Long = 0L

    override fun setDataSource(filePath: String) {
        setDataSourceFailure?.let { throw it }
    }

    override fun getTrackFormat(index: Int): FakeFormat = formats[index]

    override fun mime(format: FakeFormat): String? = format.mime

    override fun durationUs(format: FakeFormat): Long? = format.durationUs

    override fun selectTrack(index: Int) = Unit

    override fun readSampleData(
        buffer: ByteBuffer,
        offset: Int,
    ): Int = -1

    override fun advance() = Unit

    override fun release() {
        released = true
    }
}

private class FakeCodec(
    val createFailure: RuntimeException? = null,
    private val configureFailure: RuntimeException? = null,
    private val startFailure: RuntimeException? = null,
    outputBuffer: ByteBuffer? = null,
    outputInfo: AudioDecoderOutputInfo? = null,
    outputs: List<Pair<ByteBuffer, AudioDecoderOutputInfo>> = emptyList(),
    private val channels: Int = 1,
    private val encoding: Int = 2,
) : AudioDecoderCodec<FakeFormat> {
    var created = false
    var released = false

    // Either a sequence of output buffers, or the single legacy buffer.
    private val effectiveOutputs: List<Pair<ByteBuffer, AudioDecoderOutputInfo>> =
        when {
            outputs.isNotEmpty() -> outputs
            outputBuffer != null && outputInfo != null -> listOf(outputBuffer to outputInfo)
            else -> emptyList()
        }
    private var outIndex = 0
    private var lastReturned = -1

    override fun configure(format: FakeFormat) {
        configureFailure?.let { throw it }
    }

    override fun start() {
        startFailure?.let { throw it }
    }

    override fun dequeueInputBuffer(timeoutUs: Long): Int = -1

    override fun getInputBuffer(index: Int): ByteBuffer? = null

    override fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        endOfStream: Boolean,
    ) = Unit

    override fun dequeueOutputBuffer(
        info: AudioDecoderOutputInfo,
        timeoutUs: Long,
    ): Int {
        if (outIndex >= effectiveOutputs.size) return -1
        val nextInfo = effectiveOutputs[outIndex].second
        lastReturned = outIndex
        outIndex++
        info.offset = nextInfo.offset
        info.size = nextInfo.size
        info.presentationTimeUs = nextInfo.presentationTimeUs
        info.endOfStream = nextInfo.endOfStream
        return 0
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? = effectiveOutputs.getOrNull(lastReturned)?.first?.duplicate()

    override fun outputChannelCount(): Int = channels

    override fun outputPcmEncoding(): Int = encoding

    override fun releaseOutputBuffer(
        index: Int,
        render: Boolean,
    ) = Unit

    override fun stop() = Unit

    override fun release() {
        released = true
    }
}
