package dev.ipf.whitenoise.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/** The PCM layout White Noise captures in and declares to the provider. */
internal const val CALLER_AUDIO_SAMPLE_RATE_HZ = 16_000
internal const val CALLER_AUDIO_CHANNEL_COUNT = 1

/** One read per 100 ms of mono 16 kHz audio. */
private const val FRAMES_PER_READ = 1_600
private const val BYTES_PER_FRAME = 2
private const val PROGRESS_INTERVAL_MILLIS = 1_000L
private const val SPEECH_PEAK = 0.02f
private const val SHORT_FULL_SCALE = 32_768f
private const val BUFFER_READS = 4
private const val BYTE_MASK = 0xFF
private const val HIGH_BYTE_SHIFT = 8
private const val PIPE_RETRY_MILLIS = 10L
private const val PIPE_STALL_TIMEOUT_MILLIS = 2_000L

/**
 * Owns one continuous microphone capture for a logical dictation session.
 *
 * Recognition generations attach one provider stream at a time. Capture is split into immutable
 * 30-second chunks and remains active while the provider returns a final and the next recognizer is
 * created. Queued and in-flight PCM is bounded to 90 seconds; overflow stops capture instead of
 * silently dropping a read.
 */
internal class ConversationDictationCallerAudio private constructor(
    private val recorder: AudioRecord,
    private val buffer: ConversationDictationAudioChunkBuffer,
) {
    private val recording = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val captureClosed = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)
    private val captureClosedCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val activeStream = AtomicReference<ConversationDictationCallerAudioStream?>(null)

    /** Starts the recorder once; later recognition generations reuse the same capture. */
    @Suppress("ReturnCount")
    fun start(): Boolean {
        if (captureClosed.get() || finishing.get()) return false
        if (recording.get()) return true
        if (!recording.compareAndSet(false, true)) return recording.get()
        val started =
            runCatching { recorder.startRecording() }.isSuccess &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            recording.set(false)
            conversationDictationDiagnostic(
                "event=caller_audio_start_failed recording_state=${recorder.recordingState}",
            )
            releaseRecorder()
            return false
        }
        conversationDictationDiagnostic(
            "event=caller_audio_started sample_rate=$CALLER_AUDIO_SAMPLE_RATE_HZ " +
                "channels=$CALLER_AUDIO_CHANNEL_COUNT encoding=pcm16 chunk_seconds=30 buffer_seconds=90",
        )
        thread(name = "dictation-caller-audio-capture", isDaemon = true, block = ::capture)
        return true
    }

    /** Opens the only serial provider stream. The recorder itself is not restarted. */
    @Suppress("ReturnCount")
    fun openProviderStream(): ConversationDictationCallerAudioStream? {
        if (captureClosed.get() || discarded.get()) return null
        val pipe = openPipe() ?: return null
        val stream = ConversationDictationCallerAudioStream(this, pipe[1], pipe[0], buffer)
        return if (activeStream.compareAndSet(null, stream)) {
            stream
        } else {
            pipe.forEach { runCatching(it::close) }
            null
        }
    }

    /** Stops microphone capture and seals the final short chunk, while queued audio keeps draining. */
    fun finish(onClosed: () -> Unit) {
        registerCaptureClosedCallback(onClosed)
        if (finishing.compareAndSet(false, true)) {
            conversationDictationDiagnostic("event=caller_audio_finish reason=stop")
            if (!recording.compareAndSet(true, false)) {
                buffer.finish()
                releaseRecorder()
            }
        }
    }

    /** Destroys volatile PCM after cancellation or logical-session completion. */
    fun discard(onClosed: () -> Unit = {}) {
        registerCaptureClosedCallback(onClosed)
        discarded.set(true)
        buffer.discard()
        activeStream.getAndSet(null)?.cancel(requeue = false)
        if (finishing.compareAndSet(false, true) && !recording.compareAndSet(true, false)) releaseRecorder()
    }

    fun hasPending(): Boolean = buffer.hasPending

    internal fun streamSettled(stream: ConversationDictationCallerAudioStream) {
        activeStream.compareAndSet(stream, null)
    }

    private fun capture() {
        val samples = ShortArray(FRAMES_PER_READ)
        val encoded = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val progress = CallerAudioProgress()
        try {
            while (recording.get() && progress.stopReason == null) {
                val read = recorder.read(samples, 0, samples.size)
                if (read <= 0) {
                    progress.stopReason = "read=$read"
                } else {
                    conversationDictationEncodePcm16(samples, read, encoded)
                    val bytes = read * BYTES_PER_FRAME
                    if (!buffer.append(encoded, bytes)) {
                        progress.stopReason = "buffer_full"
                        conversationDictationDiagnostic(
                            "event=caller_audio_backpressure bytes=${buffer.bufferedBytes} action=stop_capture",
                        )
                    } else {
                        progress.record(read, conversationDictationPeak(samples, read), buffer.bufferedBytes)
                    }
                }
            }
        } finally {
            recording.set(false)
            if (!discarded.get()) buffer.finish()
            runCatching(recorder::stop)
            releaseRecorder()
            progress.reportClosed(buffer.bufferedBytes)
        }
    }

    private fun registerCaptureClosedCallback(callback: () -> Unit) {
        if (captureClosed.get()) {
            callback()
            return
        }
        captureClosedCallbacks.add(callback)
        if (captureClosed.get() && captureClosedCallbacks.remove(callback)) callback()
    }

    private fun releaseRecorder() {
        runCatching(recorder::release)
        if (!captureClosed.compareAndSet(false, true)) return
        while (true) captureClosedCallbacks.poll()?.invoke() ?: return
    }

    companion object {
        /** Opens one logical capture without allocating a provider pipe yet. */
        fun open(sessionId: Long): ConversationDictationCallerAudio? =
            openRecorder()?.let { recorder ->
                ConversationDictationCallerAudio(
                    recorder = recorder,
                    buffer = ConversationDictationAudioChunkBuffer(sessionId = sessionId),
                )
            }

        internal fun openPipe(): Array<ParcelFileDescriptor>? {
            val pipe = runCatching { ParcelFileDescriptor.createPipe() }.reportPipeFailure() ?: return null
            return runCatching { markWriteEndNonBlocking(pipe[1]) }
                .onFailure { pipe.forEach { end -> runCatching(end::close) } }
                .map { pipe }
                .reportPipeFailure()
        }

        private fun <T> Result<T>.reportPipeFailure(): T? =
            onFailure {
                conversationDictationDiagnostic("event=caller_audio_pipe_failed type=${it.javaClass.simpleName}")
            }.getOrNull()

        private fun markWriteEndNonBlocking(writeEnd: ParcelFileDescriptor) {
            val current = Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_SETFL, current or OsConstants.O_NONBLOCK)
        }

        private fun openRecorder(): AudioRecord? {
            val recorder = runCatching(::buildRecorder).getOrNull()
            val ready = recorder?.state == AudioRecord.STATE_INITIALIZED
            if (!ready) {
                conversationDictationDiagnostic("event=caller_audio_open_failed state=${recorder?.state ?: "none"}")
                recorder?.let { runCatching(it::release) }
            }
            return recorder?.takeIf { ready }
        }

        @SuppressLint("MissingPermission")
        private fun buildRecorder(): AudioRecord {
            val minimum =
                AudioRecord.getMinBufferSize(
                    CALLER_AUDIO_SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            val requested = FRAMES_PER_READ * BYTES_PER_FRAME * BUFFER_READS
            return AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                CALLER_AUDIO_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minimum, requested),
            )
        }
    }
}

/** One provider request backed by one exact, retryable caller-audio chunk. */
internal class ConversationDictationCallerAudioStream(
    private val capture: ConversationDictationCallerAudio,
    private val writeEnd: ParcelFileDescriptor,
    val providerEnd: ParcelFileDescriptor,
    private val buffer: ConversationDictationAudioChunkBuffer,
) {
    private val feeding = AtomicBoolean(false)
    private val settled = AtomicBoolean(false)
    private val feedClosed = AtomicBoolean(false)
    private val feedClosedCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val cancelled = AtomicBoolean(false)
    private val chunk = AtomicReference<ConversationDictationAudioChunk?>(null)

    fun start(): Boolean {
        if (!capture.start() || !feeding.compareAndSet(false, true)) return false
        thread(name = "dictation-caller-audio-feed", isDaemon = true, block = ::feed)
        return true
    }

    fun finishCapture(onClosed: () -> Unit) = capture.finish(onClosed)

    fun onFeedClosed(callback: () -> Unit) {
        if (feedClosed.get()) {
            callback()
        } else {
            feedClosedCallbacks.add(callback)
            if (feedClosed.get() && feedClosedCallbacks.remove(callback)) callback()
        }
    }

    @Suppress("ReturnCount")
    fun acknowledge(): Boolean {
        val owned = chunk.get() ?: return false
        if (!settled.compareAndSet(false, true)) return false
        val acknowledged = buffer.acknowledge(owned.chunkId)
        capture.streamSettled(this)
        return acknowledged
    }

    @Suppress("ReturnCount")
    fun retry(): Boolean {
        val owned = chunk.get() ?: return false
        if (!settled.compareAndSet(false, true)) return false
        val retried = buffer.retry(owned.chunkId)
        capture.streamSettled(this)
        return retried
    }

    fun cancel(requeue: Boolean = true) {
        cancelled.set(true)
        if (requeue) retry() else capture.streamSettled(this)
        closePipe()
    }

    private fun feed() {
        try {
            var owned: ConversationDictationAudioChunk? = null
            while (!cancelled.get() && owned == null) {
                owned = buffer.poll()
                if (owned == null) Thread.sleep(PIPE_RETRY_MILLIS)
            }
            if (owned == null || cancelled.get()) return
            chunk.set(owned)
            writeChunk(owned)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            closePipe()
        }
    }

    private fun writeChunk(owned: ConversationDictationAudioChunk) {
        val sink = writeEnd.fileDescriptor
        var offset = 0
        var stalledAt: Long? = null
        while (!cancelled.get() && offset < owned.pcm.size) {
            try {
                val written = Os.write(sink, owned.pcm, offset, owned.pcm.size - offset)
                if (written > 0) {
                    offset += written
                    stalledAt = null
                }
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EAGAIN) throw failure
                val now = SystemClock.elapsedRealtime()
                val since = stalledAt ?: now.also { stalledAt = it }
                if (now - since >= PIPE_STALL_TIMEOUT_MILLIS) {
                    conversationDictationDiagnostic(
                        "event=caller_audio_write_stalled chunk=${owned.chunkId} bytes=$offset",
                    )
                    retry()
                    return
                }
                Thread.sleep(PIPE_RETRY_MILLIS)
            }
        }
        conversationDictationDiagnostic(
            "event=caller_audio_chunk_fed chunk=${owned.chunkId} first_sample=${owned.firstSample} " +
                "last_sample=${owned.lastSampleExclusive} bytes=$offset",
        )
    }

    private fun closePipe() {
        runCatching(writeEnd::close)
        if (!feedClosed.compareAndSet(false, true)) return
        while (true) feedClosedCallbacks.poll()?.invoke() ?: return
    }

    fun closeProviderEnd() = runCatching(providerEnd::close)
}

/** Privacy-safe running totals for one logical capture. */
private class CallerAudioProgress {
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastReport = startedAt
    private var totalBytes = 0L
    private var intervalPeak = 0f
    private var speechReported = false
    var stopReason: String? = null

    fun record(
        frames: Int,
        peak: Float,
        bufferedBytes: Int,
    ) {
        totalBytes += frames.toLong() * BYTES_PER_FRAME
        intervalPeak = max(intervalPeak, peak)
        if (!speechReported && intervalPeak >= SPEECH_PEAK) {
            speechReported = true
            conversationDictationDiagnostic(
                "event=caller_audio_speech_detected ms=${elapsed()} " +
                    "peak=${format(intervalPeak)}",
            )
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport >= PROGRESS_INTERVAL_MILLIS) {
            lastReport = now
            conversationDictationDiagnostic(
                "event=caller_audio_progress ms=${elapsed()} bytes=$totalBytes buffered=$bufferedBytes " +
                    "peak=${format(intervalPeak)}",
            )
            intervalPeak = 0f
        }
    }

    fun reportClosed(bufferedBytes: Int) {
        conversationDictationDiagnostic(
            "event=caller_audio_closed reason=${stopReason ?: "stopped"} bytes=$totalBytes " +
                "buffered=$bufferedBytes ms=${elapsed()} heard_speech=$speechReported",
        )
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt

    private fun format(peak: Float): String = String.format(Locale.US, "%.3f", peak)
}

/** Writes `count` samples as little-endian PCM 16-bit, the encoding declared to the provider. */
internal fun conversationDictationEncodePcm16(
    samples: ShortArray,
    count: Int,
    destination: ByteArray,
) {
    for (index in 0 until count) {
        val sample = samples[index].toInt()
        destination[index * BYTES_PER_FRAME] = (sample and BYTE_MASK).toByte()
        destination[index * BYTES_PER_FRAME + 1] = ((sample shr HIGH_BYTE_SHIFT) and BYTE_MASK).toByte()
    }
}

/** Loudest sample in the range, normalised to 0..1, so logs can distinguish silence from speech. */
internal fun conversationDictationPeak(
    samples: ShortArray,
    count: Int,
): Float {
    var peak = 0
    for (index in 0 until count) peak = max(peak, abs(samples[index].toInt()))
    return peak / SHORT_FULL_SCALE
}
