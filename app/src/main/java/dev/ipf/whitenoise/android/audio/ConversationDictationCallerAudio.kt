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
import java.io.FileDescriptor
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

/** A terminal capture condition that must be surfaced to the owning recognition session. */
internal enum class ConversationDictationCallerAudioFailure {
    BufferFull,
}

/** Narrow capture-device boundary that keeps lifecycle behavior directly testable off-device. */
internal interface ConversationDictationAudioCaptureDevice {
    val initialized: Boolean
    val recording: Boolean

    /** Begins microphone acquisition; the capture owner prevents duplicate starts. */
    fun start()

    /** Reads mono PCM16 samples into the caller buffer and returns a sample count or device status. */
    fun read(target: ShortArray): Int

    /** Stops acquiring microphone samples without acknowledging any buffered audio. */
    fun stop()

    /** Releases native recorder resources after capture stops or initialization fails. */
    fun release()
}

/** Injectable pipe boundary used to exercise provider disconnects without mocked session state. */
internal fun interface ConversationDictationAudioPipeWriter {
    /** Writes at most [length] bytes from [offset], returning progress or throwing a pipe I/O failure. */
    fun write(
        descriptor: FileDescriptor,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

/** Production adapter around Android's microphone recorder. */
private class AndroidConversationDictationAudioCaptureDevice(
    private val recorder: AudioRecord,
) : ConversationDictationAudioCaptureDevice {
    override val initialized: Boolean
        get() = recorder.state == AudioRecord.STATE_INITIALIZED

    override val recording: Boolean
        get() = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING

    /** Begins native microphone recording after the owner has checked device initialization. */
    override fun start() = recorder.startRecording()

    /** Reads mono PCM16 samples into the caller buffer and returns a sample count or device status. */
    override fun read(target: ShortArray): Int = recorder.read(target, 0, target.size)

    /** Stops acquiring microphone samples without acknowledging any buffered audio. */
    override fun stop() = recorder.stop()

    /** Releases native recorder resources after capture stops or initialization fails. */
    override fun release() = recorder.release()
}

/**
 * Owns one continuous microphone capture for a logical dictation session.
 *
 * Recognition generations attach one provider stream at a time. Capture is split into immutable
 * 30-second chunks and remains active while the provider returns a final and the next recognizer is
 * created. Queued and in-flight PCM is bounded to 90 seconds; overflow stops capture instead of
 * silently dropping a read.
 */
@Suppress("TooManyFunctions")
internal class ConversationDictationCallerAudio internal constructor(
    private val device: ConversationDictationAudioCaptureDevice,
    private val buffer: ConversationDictationAudioChunkBuffer,
    private val pipeWriter: ConversationDictationAudioPipeWriter =
        ConversationDictationAudioPipeWriter { descriptor, source, offset, length ->
            Os.write(descriptor, source, offset, length)
        },
) {
    private val lastSpeechAt = AtomicLong(SystemClock.elapsedRealtime())
    private val recording = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val captureClosed = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)
    private val captureClosedCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val activeStream = AtomicReference<ConversationDictationCallerAudioStream?>(null)

    // Guarded by this capture's monitor together with stream attachment and settlement.
    private var pendingFailure: ConversationDictationCallerAudioFailure? = null

    /** Starts capture once, or permits buffered audio to drain after the recorder has stopped. */
    @Suppress("ReturnCount")
    fun start(): Boolean {
        if (discarded.get()) return false
        if (captureClosed.get() || finishing.get()) return buffer.hasPending
        if (recording.get()) return true
        if (!recording.compareAndSet(false, true)) return recording.get()
        val started =
            runCatching { device.start() }.isSuccess && device.recording
        if (!started) {
            recording.set(false)
            conversationDictationDiagnostic(
                "event=caller_audio_start_failed initialized=${device.initialized}",
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
    @Synchronized
    @Suppress("MaxLineLength", "ReturnCount")
    fun openProviderStream(onFailure: (ConversationDictationCallerAudioFailure) -> Unit = {}): ConversationDictationCallerAudioStream? {
        if (discarded.get()) return null
        val pipe = openPipe() ?: return null
        val stream = ConversationDictationCallerAudioStream(this, pipe[1], pipe[0], buffer, pipeWriter, onFailure)
        return if (activeStream.compareAndSet(null, stream)) {
            pendingFailure?.let { failure ->
                pendingFailure = null
                stream.reportFailure(failure)
            }
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
    @Synchronized
    fun discard(onClosed: () -> Unit = {}) {
        registerCaptureClosedCallback(onClosed)
        discarded.set(true)
        pendingFailure = null
        buffer.discard()
        activeStream.getAndSet(null)?.cancel(requeue = false)
        if (finishing.compareAndSet(false, true) && !recording.compareAndSet(true, false)) releaseRecorder()
    }

    /** Includes partial, queued, and in-flight audio until acknowledged or discarded. */
    fun hasPending(): Boolean = buffer.hasPending

    /** Measures quiet capture time independently of delayed provider speech callbacks. */
    fun silenceMillis(): Long = (SystemClock.elapsedRealtime() - lastSpeechAt.get()).coerceAtLeast(0L)

    /** Releases the generation lease without clearing a capture failure waiting for its successor. */
    @Synchronized
    internal fun streamSettled(stream: ConversationDictationCallerAudioStream) {
        activeStream.compareAndSet(stream, null)
    }

    /** Keeps a terminal failure until a stream can receive it, atomically with generation changes. */
    @Synchronized
    private fun reportCaptureFailure(failure: ConversationDictationCallerAudioFailure) {
        if (discarded.get()) return
        val stream = activeStream.get()
        if (stream == null) {
            pendingFailure = failure
        } else {
            stream.reportFailure(failure)
        }
    }

    /** Records continuously across provider generations and seals the last read before closure. */
    private fun capture() {
        val samples = ShortArray(FRAMES_PER_READ)
        val encoded = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val progress = CallerAudioProgress()
        try {
            while (recording.get() && progress.stopReason == null) {
                val read = device.read(samples)
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
                        reportCaptureFailure(ConversationDictationCallerAudioFailure.BufferFull)
                    } else {
                        recordCaptureActivity(samples, read, progress)
                    }
                }
            }
        } finally {
            recording.set(false)
            if (!discarded.get()) buffer.finish()
            runCatching(device::stop)
            releaseRecorder()
            progress.reportClosed(buffer.bufferedBytes)
        }
    }

    /** Tracks capture-side speech before a provider receives the next sealed chunk. */
    private fun recordCaptureActivity(
        samples: ShortArray,
        read: Int,
        progress: CallerAudioProgress,
    ) {
        val peak = conversationDictationPeak(samples, read)
        if (peak >= SPEECH_PEAK) lastSpeechAt.set(SystemClock.elapsedRealtime())
        progress.record(read, peak, buffer.bufferedBytes)
    }

    /** Runs a closure observer once, including registration racing with recorder release. */
    private fun registerCaptureClosedCallback(callback: () -> Unit) {
        if (captureClosed.get()) {
            callback()
            return
        }
        captureClosedCallbacks.add(callback)
        if (captureClosed.get() && captureClosedCallbacks.remove(callback)) callback()
    }

    /** Releases the device and delivers closure observers once across stop and cancellation races. */
    private fun releaseRecorder() {
        runCatching(device::release)
        if (!captureClosed.compareAndSet(false, true)) return
        while (true) captureClosedCallbacks.poll()?.invoke() ?: return
    }

    companion object {
        /** Opens one logical capture without allocating a provider pipe yet. */
        fun open(sessionId: Long): ConversationDictationCallerAudio? =
            openRecorder()?.let { recorder ->
                ConversationDictationCallerAudio(
                    device = AndroidConversationDictationAudioCaptureDevice(recorder),
                    buffer = ConversationDictationAudioChunkBuffer(sessionId = sessionId),
                )
            }

        /** Creates a provider pipe with a nonblocking writer; closes both ends if configuration fails. */
        internal fun openPipe(): Array<ParcelFileDescriptor>? {
            val pipe = runCatching { ParcelFileDescriptor.createPipe() }.reportPipeFailure() ?: return null
            return runCatching { markWriteEndNonBlocking(pipe[1]) }
                .onFailure { pipe.forEach { end -> runCatching(end::close) } }
                .map { pipe }
                .reportPipeFailure()
        }

        /** Returns the pipe operation result or records its failure type without logging audio. */
        private fun <T> Result<T>.reportPipeFailure(): T? =
            onFailure {
                conversationDictationDiagnostic("event=caller_audio_pipe_failed type=${it.javaClass.simpleName}")
            }.getOrNull()

        /** Allows the feeder to detect stalled providers instead of blocking indefinitely in a write. */
        private fun markWriteEndNonBlocking(writeEnd: ParcelFileDescriptor) {
            val current = Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_SETFL, current or OsConstants.O_NONBLOCK)
        }

        /** Returns only an initialized microphone recorder and releases failed allocations. */
        private fun openRecorder(): AudioRecord? {
            val recorder = runCatching(::buildRecorder).getOrNull()
            val ready = recorder?.state == AudioRecord.STATE_INITIALIZED
            if (!ready) {
                conversationDictationDiagnostic("event=caller_audio_open_failed state=${recorder?.state ?: "none"}")
                recorder?.let { runCatching(it::release) }
            }
            return recorder?.takeIf { ready }
        }

        /** Configures mono 16 kHz PCM16 capture with at least the platform minimum buffer size. */
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
@Suppress("TooManyFunctions")
internal class ConversationDictationCallerAudioStream(
    private val capture: ConversationDictationCallerAudio,
    private val writeEnd: ParcelFileDescriptor,
    val providerEnd: ParcelFileDescriptor,
    private val buffer: ConversationDictationAudioChunkBuffer,
    private val pipeWriter: ConversationDictationAudioPipeWriter,
    private val onFailure: (ConversationDictationCallerAudioFailure) -> Unit,
) {
    private val feeding = AtomicBoolean(false)
    private val settled = AtomicBoolean(false)
    private val feedClosed = AtomicBoolean(false)
    private val feedClosedCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val cancelled = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val chunk = AtomicReference<ConversationDictationAudioChunk?>(null)

    /** Starts the device or feeder once; a sealed capture may only drain retained PCM. */
    fun start(): Boolean {
        if (!capture.start() || !feeding.compareAndSet(false, true)) return false
        thread(name = "dictation-caller-audio-feed", isDaemon = true, block = ::feed)
        return true
    }

    /** Seals the logical capture while this generation continues feeding its owned chunk. */
    fun finishCapture(onClosed: () -> Unit) = capture.finish(onClosed)

    /** Registers an exactly-once observer, including when the feeder has already closed. */
    fun onFeedClosed(callback: () -> Unit) {
        if (feedClosed.get()) {
            callback()
        } else {
            feedClosedCallbacks.add(callback)
            if (feedClosed.get() && feedClosedCallbacks.remove(callback)) callback()
        }
    }

    /** Releases this generation’s chunk after a final transcript makes its audio expendable. */
    fun acknowledge(): Boolean = settle(requeue = false)

    /** Returns this generation’s chunk to the front of the queue without duplicating its byte accounting. */
    fun retry(): Boolean = settle(requeue = true)

    /** Closes the feeder and relinquishes its lease, retaining unacknowledged PCM by default. */
    fun cancel(requeue: Boolean = true) {
        cancelled.set(true)
        settle(requeue)
        closePipe()
    }

    /** Delivers one typed capture failure to the generation that owns this stream. */
    fun reportFailure(failure: ConversationDictationCallerAudioFailure) {
        if (failureReported.compareAndSet(false, true)) onFailure(failure)
    }

    /** Claims one chunk, feeds its PCM, and requeues ownership on interruption or provider disconnection. */
    @Suppress("ReturnCount")
    private fun feed() {
        try {
            var owned: ConversationDictationAudioChunk? = null
            while (!cancelled.get() && !settled.get() && owned == null) {
                owned = buffer.poll()
                if (owned != null) break
                if (buffer.isDrained) {
                    settle(requeue = false)
                    closePipe()
                    return
                }
                Thread.sleep(PIPE_RETRY_MILLIS)
            }
            if (owned == null) return
            chunk.set(owned)
            if (cancelled.get() || settled.get()) {
                if (chunk.compareAndSet(owned, null)) buffer.retry(owned.chunkId)
                return
            }
            writeChunk(owned)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            settle(requeue = true)
        } catch (failure: ErrnoException) {
            reportFeedFailure(failure)
        } catch (failure: IOException) {
            reportFeedFailure(failure)
        } finally {
            closePipe()
        }
    }

    /** Logs only the failure type and makes the unacknowledged chunk available to a replacement stream. */
    private fun reportFeedFailure(failure: Exception) {
        conversationDictationDiagnostic(
            "event=caller_audio_feed_failed type=${failure.javaClass.simpleName} action=requeue",
        )
        settle(requeue = true)
    }

    /** Feeds an owned chunk until completion, cancellation, or a bounded nonblocking-write stall. */
    private fun writeChunk(owned: ConversationDictationAudioChunk) {
        val sink = writeEnd.fileDescriptor
        var offset = 0
        var stalledAt: Long? = null
        while (!cancelled.get() && offset < owned.pcm.size) {
            try {
                val written = pipeWriter.write(sink, owned.pcm, offset, owned.pcm.size - offset)
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

    /** Releases the active-stream lease exactly once, even before a chunk has been acquired. */
    private fun settle(requeue: Boolean): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        val owned = chunk.getAndSet(null)
        val changed =
            when {
                owned == null -> false
                requeue -> buffer.retry(owned.chunkId)
                else -> buffer.acknowledge(owned.chunkId)
            }
        capture.streamSettled(this)
        return changed
    }

    /** Closes the writer and notifies feeder observers once even when cancellation races with completion. */
    private fun closePipe() {
        runCatching(writeEnd::close)
        if (!feedClosed.compareAndSet(false, true)) return
        while (true) feedClosedCallbacks.poll()?.invoke() ?: return
    }

    /** Releases the provider-facing descriptor independently of writer and capture ownership. */
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

    /** Accumulates byte counts and peak levels, emitting periodic diagnostics without storing speech content. */
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

    /** Reports the capture stop cause and retained-byte count without exposing PCM or transcript text. */
    fun reportClosed(bufferedBytes: Int) {
        conversationDictationDiagnostic(
            "event=caller_audio_closed reason=${stopReason ?: "stopped"} bytes=$totalBytes " +
                "buffered=$bufferedBytes ms=${elapsed()} heard_speech=$speechReported",
        )
    }

    /** Uses the monotonic clock to measure capture duration independently of wall-clock changes. */
    private fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt

    /** Formats diagnostic peak levels with a stable decimal separator across device locales. */
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
