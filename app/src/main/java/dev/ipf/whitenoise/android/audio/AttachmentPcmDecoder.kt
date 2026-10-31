@file:Suppress("TooManyFunctions", "MatchingDeclarationName") // The decoder owns its chunk type and native phases.

package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.media.sandbox.AvStreamFrame
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MAX_MEDIA_SOURCE_BYTES
import dev.ipf.whitenoise.android.media.sandbox.hasAudio
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
private const val MICROS_PER_SECOND = 1_000_000L
private const val MAX_PCM_DURATION_SECONDS = 60L * 60
private const val MAX_PCM_BYTES = 512L * 1024 * 1024
private const val MAX_CODEC_BUFFER_BYTES = 8 * 1024 * 1024
private const val MAX_DECODE_ITERATIONS = 500_000
private const val MAX_DECODE_NANOS = 60_000_000_000L

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
                AttachmentAudioDecoder(file).decode(consume)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                throw IOException("Attachment audio decoding failed", error)
            }
        }
    }

/** Receives bounded PCM16 copies; the compressed source never enters an app-UID parser. */
private class AttachmentAudioDecoder(
    private val file: File,
) {
    private val budget = AttachmentDecodeBudget()

    suspend fun decode(consume: suspend (AttachmentPcmChunk) -> Unit) {
        IsolatedMediaDecoder.openAudioStream(file).use { stream ->
            val info = stream.info
            if (!info.hasAudio || info.durationUs > MAX_PCM_DURATION_SECONDS * MICROS_PER_SECOND) {
                throw IOException("Attachment contains no supported audio within the duration limit")
            }
            val pcm = AttachmentPcmBuffer(info.sampleRate, info.channels)
            var iterations = 0
            while (true) {
                budget.check()
                if (++iterations > MAX_DECODE_ITERATIONS) {
                    throw IOException("Attachment audio exceeded decode iteration limit")
                }
                when (val frame = stream.read()) {
                    is AvStreamFrame.Audio -> {
                        if (frame.sampleRate != pcm.sampleRateHz || frame.channels != pcm.channelCount) {
                            throw IOException("Attachment PCM format changed during decoding")
                        }
                        for (chunk in pcm.append(ByteBuffer.wrap(frame.bytes), 0, frame.bytes.size)) {
                            budget.deliver(chunk, consume)
                        }
                    }
                    is AvStreamFrame.Video -> {
                        frame.bitmap.recycle()
                        throw IOException("Unexpected video from an audio-only worker")
                    }
                    AvStreamFrame.End -> break
                }
            }
            budget.check()
            if (pcm.totalFrames == 0L) throw IOException("Attachment contains empty audio")
            for (chunk in pcm.finish()) budget.deliver(chunk, consume)
        }
    }
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
