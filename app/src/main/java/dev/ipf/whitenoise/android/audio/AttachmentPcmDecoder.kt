@file:Suppress("TooManyFunctions", "MatchingDeclarationName") // The decoder owns its chunk type and native phases.

package dev.ipf.whitenoise.android.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import dev.ipf.whitenoise.android.media.sandbox.MAX_MEDIA_SOURCE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

internal data class AttachmentPcmChunk(
    val bytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
)

private const val PCM_CHUNK_SECONDS = 15
private const val TARGET_SAMPLE_RATE_HZ = 16_000
private const val MIN_SOURCE_SAMPLE_RATE_HZ = 8_000
private const val MAX_SOURCE_SAMPLE_RATE_HZ = 96_000
private const val MICROS_PER_SECOND = 1_000_000L
private const val MAX_PCM_DURATION_SECONDS = 60L * 60
private const val MAX_PCM_BYTES = 512L * 1024 * 1024
private const val MAX_CODEC_BUFFER_BYTES = 8 * 1024 * 1024
private const val MAX_DECODE_ITERATIONS = 500_000
private const val MAX_DECODE_NANOS = 60_000_000_000L
private const val DEQUEUE_TIMEOUT_US = 10_000L

// Process-owned admission remains held through cleanup, including a blocked native call.
private val attachmentDecodeGate = Semaphore(1)

@Suppress("TooGenericExceptionCaught") // Android exposes heterogeneous runtime failures for invalid media.
internal suspend fun decodeAttachmentPcm(
    file: File,
    consume: suspend (AttachmentPcmChunk) -> Unit,
): Unit =
    withContext(Dispatchers.IO) {
        attachmentDecodeGate.withPermit {
            currentCoroutineContext().ensureActive()
            if (!file.isFile || file.length() !in 1..MAX_MEDIA_SOURCE_BYTES) {
                throw IOException("Invalid attachment audio source size")
            }
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                    if (input.statSize !in 1..MAX_MEDIA_SOURCE_BYTES) {
                        throw IOException("Invalid attachment audio descriptor size")
                    }
                    if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
                        throw IOException("Unsupported native PCM byte order")
                    }
                    AttachmentAudioDecoder(input).decode(consume)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                throw IOException("Attachment audio decoding failed", error)
            }
        }
    }

@Suppress("ThrowsCount") // Keep each native input/output rejection explicit and fail closed.
private class AttachmentAudioDecoder(
    private val input: ParcelFileDescriptor,
) {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var codecStarted = false
    private var inputEnded = false
    private var pcm: AttachmentPcmBuffer? = null
    private val budget = AttachmentDecodeBudget()

    suspend fun decode(consume: suspend (AttachmentPcmChunk) -> Unit) {
        try {
            extractor.setDataSource(input.fileDescriptor, 0, input.statSize)
            val format = selectAudioTrack()
            requestPcm16(format)
            val activeCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = activeCodec
            activeCodec.configure(format, null, null, 0)
            activeCodec.start()
            codecStarted = true
            val info = MediaCodec.BufferInfo()
            var outputEnded = false
            var iterations = 0
            while (!outputEnded) {
                budget.check()
                iterations++
                if (iterations > MAX_DECODE_ITERATIONS) {
                    throw IOException("Attachment audio exceeded codec iteration limit")
                }
                feedInput(activeCodec)
                outputEnded = drainOutput(activeCodec, info, consume)
            }
            budget.check()
            if (!inputEnded) {
                throw IOException("Attachment codec ended before input was exhausted")
            }
            val finalPcm = pcm ?: throw IOException("Attachment contains no decoded audio")
            if (finalPcm.totalFrames == 0L) {
                throw IOException("Attachment contains empty audio")
            }
            for (chunk in finalPcm.finish()) {
                budget.deliver(chunk, consume)
            }
        } finally {
            val activeCodec = codec
            if (activeCodec != null) {
                if (codecStarted) {
                    runCatching { activeCodec.stop() }
                }
                runCatching { activeCodec.release() }
            }
            runCatching { extractor.release() }
        }
    }

    private suspend fun drainOutput(
        activeCodec: MediaCodec,
        info: MediaCodec.BufferInfo,
        consume: suspend (AttachmentPcmChunk) -> Unit,
    ): Boolean {
        budget.check()
        val index = activeCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
        return when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                pcm = resolvePcm(pcm, activeCodec.outputFormat)
                false
            }
            index >= 0 -> {
                val completed = readPcm(activeCodec, index, info)
                for (chunk in completed) {
                    budget.deliver(chunk, consume)
                }
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            }
            else -> false
        }
    }

    private suspend fun readPcm(
        activeCodec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ): List<AttachmentPcmChunk> {
        try {
            budget.check()
            if (info.size < 0) {
                throw IOException("Invalid attachment PCM output size")
            }
            return if (info.size == 0) {
                emptyList()
            } else {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    throw IOException("Unexpected attachment PCM codec configuration")
                }
                val activePcm = resolvePcm(pcm, activeCodec.getOutputFormat(index))
                pcm = activePcm
                val output =
                    activeCodec.getOutputBuffer(index)
                        ?: throw IOException("Missing attachment PCM output buffer")
                activePcm.append(output, info.offset, info.size)
            }
        } finally {
            activeCodec.releaseOutputBuffer(index, false)
        }
    }

    private suspend fun selectAudioTrack(): MediaFormat {
        for (index in 0 until extractor.trackCount) {
            budget.check()
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    if (durationUs < 0 || durationUs > MAX_PCM_DURATION_SECONDS * MICROS_PER_SECOND) {
                        throw IOException("Attachment audio exceeds duration limit")
                    }
                }
                extractor.selectTrack(index)
                return format
            }
        }
        throw IOException("Attachment contains no audio track")
    }

    private fun feedInput(activeCodec: MediaCodec) {
        if (inputEnded) {
            return
        }
        val index = activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) {
            return
        }
        val buffer =
            activeCodec.getInputBuffer(index)
                ?: throw IOException("Missing attachment codec input buffer")
        buffer.clear()
        if (extractor.sampleSize > buffer.remaining()) {
            throw IOException("Attachment audio sample exceeds codec input capacity")
        }
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            activeCodec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEnded = true
        } else {
            // At EOF getSampleFlags() is -1, not a bitset describing an encrypted sample.
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                throw IOException("Encrypted attachment audio is unsupported")
            }
            if (size > buffer.capacity()) {
                throw IOException("Invalid attachment audio sample")
            }
            val flags =
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                } else {
                    0
                }
            activeCodec.queueInputBuffer(index, 0, size, extractor.sampleTime, flags)
            extractor.advance()
        }
    }
}

@Suppress("ThrowsCount") // Reject raw input before output negotiation can overwrite its metadata.
private fun requestPcm16(format: MediaFormat) {
    if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
        val hasEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
        val hasBitDepth = format.containsKey("bits-per-sample")
        if (!hasEncoding && !hasBitDepth) {
            throw IOException("Attachment raw PCM encoding is unknown")
        }
        if (hasEncoding && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
            throw IOException("Attachment raw PCM encoding is unsupported")
        }
        if (hasBitDepth && format.getInteger("bits-per-sample") != Short.SIZE_BITS) {
            throw IOException("Attachment raw PCM bit depth is unsupported")
        }
    }
    // This requests compressed decoder output; it cannot convert an audio/raw input.
    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
}

@Suppress("ThrowsCount") // Encoding, layout and midstream changes are distinct rejection boundaries.
private fun resolvePcm(
    previous: AttachmentPcmBuffer?,
    format: MediaFormat,
): AttachmentPcmBuffer {
    val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    // Android's documented decoder default is signed PCM16 when this key is absent.
    val encoding =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    if (format.getString(MediaFormat.KEY_MIME) != "audio/raw" || encoding != AudioFormat.ENCODING_PCM_16BIT) {
        throw IOException("Unsupported attachment PCM output encoding")
    }
    if (rate !in MIN_SOURCE_SAMPLE_RATE_HZ..MAX_SOURCE_SAMPLE_RATE_HZ || channels !in 1..2) {
        throw IOException("Unsupported attachment PCM output format")
    }
    if (previous != null) {
        if (previous.sampleRateHz != rate || previous.channelCount != channels) {
            throw IOException("Attachment PCM format changed during decoding")
        }
        return previous
    }
    return AttachmentPcmBuffer(rate, channels)
}

private class AttachmentPcmBuffer(
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    private val frameBytes = channelCount * 2
    private val chunkBytes = TARGET_SAMPLE_RATE_HZ * 2 * PCM_CHUNK_SECONDS
    private var pending: ByteArray? = null
    private var used = 0
    private var totalBytes = 0L
    private var previousSample = 0

    // Output sample k is at source position k * sourceRate / 16000. Keep the
    // integer numerator globally, so codec buffers and output chunks cannot reset phase.
    private var nextOutputNumerator = 0L
    private var completed: MutableList<AttachmentPcmChunk>? = null
    var totalFrames = 0L
        private set

    @Suppress("ThrowsCount") // Validate native byte ranges and resource budgets before reading samples.
    fun append(
        output: ByteBuffer,
        offset: Int,
        size: Int,
    ): List<AttachmentPcmChunk> {
        if (size > MAX_CODEC_BUFFER_BYTES || size % frameBytes != 0) {
            throw IOException("Invalid attachment PCM buffer size or alignment")
        }
        if (offset < 0 || size > output.capacity() || offset > output.capacity() - size) {
            throw IOException("Invalid attachment PCM buffer range")
        }
        totalBytes += size
        val incomingFrames = size / frameBytes
        if (totalBytes > MAX_PCM_BYTES || totalFrames + incomingFrames > sampleRateHz * MAX_PCM_DURATION_SECONDS) {
            throw IOException("Attachment decoded audio exceeds output limit")
        }
        output.clear()
        output.limit(offset + size)
        output.position(offset)
        output.order(ByteOrder.LITTLE_ENDIAN)
        while (output.hasRemaining()) {
            var mono = output.short.toInt()
            if (channelCount == 2) {
                mono = (mono + output.short.toInt()) / 2
            }
            val position = totalFrames * TARGET_SAMPLE_RATE_HZ
            // Emit only positions bracketed by this source frame and its predecessor.
            while (nextOutputNumerator <= position) {
                val fraction = nextOutputNumerator - position + TARGET_SAMPLE_RATE_HZ
                val interpolated =
                    if (totalFrames == 0L) {
                        mono
                    } else {
                        (
                            (previousSample * (TARGET_SAMPLE_RATE_HZ - fraction) + mono * fraction) /
                                TARGET_SAMPLE_RATE_HZ
                        ).toInt()
                    }
                emit(interpolated)
                nextOutputNumerator += sampleRateHz
            }
            previousSample = mono
            totalFrames++
        }
        return takeCompleted()
    }

    fun finish(): List<AttachmentPcmChunk> {
        // Preserve the final fractional source-frame duration by holding the last sample.
        while (nextOutputNumerator < totalFrames * TARGET_SAMPLE_RATE_HZ) {
            emit(previousSample)
            nextOutputNumerator += sampleRateHz
        }
        pending?.let { bytes ->
            addCompleted(bytes.copyOf(used))
            pending = null
            used = 0
        }
        return takeCompleted()
    }

    private fun emit(sample: Int) {
        val bytes = pending ?: ByteArray(chunkBytes).also { pending = it }
        bytes[used++] = sample.toByte()
        bytes[used++] = (sample shr Byte.SIZE_BITS).toByte()
        if (used == bytes.size) {
            addCompleted(bytes)
            pending = null
            used = 0
        }
    }

    private fun addCompleted(bytes: ByteArray) {
        val ready = completed ?: ArrayList<AttachmentPcmChunk>().also { completed = it }
        ready.add(AttachmentPcmChunk(bytes, TARGET_SAMPLE_RATE_HZ, 1))
    }

    private fun takeCompleted(): List<AttachmentPcmChunk> {
        val ready = completed ?: emptyList()
        completed = null
        return ready
    }
}

private class AttachmentDecodeBudget {
    private var lastCheckNanos = System.nanoTime()
    private var workNanos = 0L

    suspend fun check() {
        currentCoroutineContext().ensureActive()
        val now = System.nanoTime()
        workNanos += now - lastCheckNanos
        lastCheckNanos = now
        if (workNanos > MAX_DECODE_NANOS) {
            throw IOException("Attachment audio exceeded decode time limit")
        }
    }

    suspend fun deliver(
        chunk: AttachmentPcmChunk,
        consume: suspend (AttachmentPcmChunk) -> Unit,
    ) {
        check()
        try {
            consume(chunk)
        } finally {
            // Recognition may suspend for much longer than decoding; it does not spend this budget.
            lastCheckNanos = System.nanoTime()
        }
        currentCoroutineContext().ensureActive()
    }
}
