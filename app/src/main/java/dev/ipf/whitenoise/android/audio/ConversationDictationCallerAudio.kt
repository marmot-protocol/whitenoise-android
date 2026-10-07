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
import java.util.concurrent.atomic.AtomicInteger
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
private const val MIN_SPEECH_EVIDENCE_PEAK = 1f / SHORT_FULL_SCALE
private const val BUFFER_READS = 4
private const val BYTE_MASK = 0xFF
private const val HIGH_BYTE_SHIFT = 8
private const val PIPE_RETRY_MILLIS = 10L

/** Allows an offline provider to finish binding/loading before declaring its caller-audio pipe dead. */
private const val PIPE_STALL_TIMEOUT_MILLIS = 10_000L

/** Bounds the in-progress read plus immediately available native-buffer reads after completion. */
private const val POST_ACTION_CAPTURE_DRAIN_READS = 6

/** Bounds the tail drain even when recorder reads take longer than their nominal 100 ms. */
private const val POST_ACTION_CAPTURE_DRAIN_MILLIS = 750L
private const val FORCED_CAPTURE_SEAL_GRACE_MILLIS = 750L

/** A caller-audio failure that must be surfaced to the owning recognition session. */
internal enum class ConversationDictationCallerAudioFailure {
    BufferFull,
    CaptureFailed,
    PipeFailed,
}

/** Narrow capture-device boundary that keeps lifecycle behavior directly testable off-device. */
internal interface ConversationDictationAudioCaptureDevice {
    val initialized: Boolean
    val recording: Boolean

    /** Begins microphone acquisition; the capture owner prevents duplicate starts. */
    fun start()

    /** Reads PCM16, optionally waiting for samples; completion drains only immediately available audio. */
    fun read(
        target: ShortArray,
        waitForSamples: Boolean = true,
    ): Int

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

    /** Reads PCM16, optionally waiting for samples; completion drains only immediately available audio. */
    override fun read(
        target: ShortArray,
        waitForSamples: Boolean,
    ): Int =
        recorder.read(
            target,
            0,
            target.size,
            if (waitForSamples) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING,
        )

    /** Stops acquiring microphone samples without acknowledging any buffered audio. */
    override fun stop() = recorder.stop()

    /** Releases native recorder resources after capture stops or initialization fails. */
    override fun release() = recorder.release()
}

/**
 * Owns one continuous microphone capture for a logical dictation session.
 *
 * Recognition generations attach one provider stream at a time. Capture is split into immutable
 * sentence-aware chunks bounded at 30 seconds. It remains active while the provider returns a
 * final and the next recognizer is created. Queued and in-flight PCM is bounded to 90 seconds;
 * overflow stops capture instead of silently dropping a read.
 */
@Suppress("TooManyFunctions")
internal class ConversationDictationCallerAudio internal constructor(
    private val device: ConversationDictationAudioCaptureDevice,
    private val buffer: ConversationDictationAudioChunkBuffer,
    private val pipeWriter: ConversationDictationAudioPipeWriter =
        ConversationDictationAudioPipeWriter { descriptor, source, offset, length ->
            Os.write(descriptor, source, offset, length)
        },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lastSpeechAt = AtomicLong(NO_SPEECH_AT)
    private val recording = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val postActionReadsRemaining = AtomicInteger(0)
    private val postActionDrainDeadline = AtomicLong(Long.MAX_VALUE)
    private val captureClosed = AtomicBoolean(false)
    private val recorderReleasing = AtomicBoolean(false)
    private val recorderReleased = AtomicBoolean(false)
    private val captureSealed = AtomicBoolean(false)
    private val captureThreadStarted = AtomicBoolean(false)
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
                "channels=$CALLER_AUDIO_CHANNEL_COUNT encoding=pcm16 chunk_seconds=10-30 buffer_seconds=90",
        )
        captureThreadStarted.set(true)
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
                stream.reportFailure(failure)
            }
            stream
        } else {
            pipe.forEach { runCatching(it::close) }
            null
        }
    }

    /** Drains available recorder audio without waiting for fresh samples, then seals the final chunk. */
    fun finish(onClosed: () -> Unit) {
        onCaptureClosed(onClosed)
        synchronized(this) {
            if (!finishing.get()) {
                postActionReadsRemaining.set(POST_ACTION_CAPTURE_DRAIN_READS)
                postActionDrainDeadline.set(elapsedRealtime() + POST_ACTION_CAPTURE_DRAIN_MILLIS)
                finishing.set(true)
                conversationDictationDiagnostic("event=caller_audio_finish reason=stop")
                if (!recording.get()) {
                    buffer.finish()
                    releaseRecorder()
                }
            }
        }
    }

    /** Interrupts a recorder that exceeded its tail deadline, preserving all completed reads. */
    fun forceFinish(onClosed: () -> Unit) {
        onCaptureClosed(onClosed)
        finishing.set(true)
        postActionReadsRemaining.set(0)
        postActionDrainDeadline.set(0L)
        recording.set(false)
        thread(name = "dictation-caller-audio-close", isDaemon = true) {
            runCatching(device::stop)
            val noProducer = !captureThreadStarted.get()
            if (noProducer) buffer.finish()
            releaseRecorder(sealed = noProducer)
            sealReleasedRecorderAfterGrace()
        }
    }

    /** Native release proves microphone closure even if a broken driver never returns its read. */
    private fun sealReleasedRecorderAfterGrace() {
        if (captureSealed.get() || !recorderReleased.get()) return
        if (runCatching { Thread.sleep(FORCED_CAPTURE_SEAL_GRACE_MILLIS) }.isFailure) return
        synchronized(this) {
            if (!captureSealed.get() && recorderReleased.get()) {
                // Preserve every read completed during the grace period. Later driver completions
                // are outside the bounded tail and cannot mutate the sealed recovery buffer.
                if (!discarded.get()) buffer.finish()
                conversationDictationDiagnostic("event=caller_audio_forced_seal reason=read_timeout")
                releaseRecorder()
            }
        }
    }

    /** Destroys volatile PCM after cancellation or logical-session completion. */
    @Synchronized
    fun discard(onClosed: () -> Unit = {}) {
        onCaptureClosed(onClosed)
        discarded.set(true)
        pendingFailure = null
        buffer.discard()
        activeStream.getAndSet(null)?.cancel(requeue = false)
        finishing.set(true)
        postActionReadsRemaining.set(0)
        postActionDrainDeadline.set(0L)
        // Native stop/release can block; discard must never block the controller's main looper.
        forceFinish {}
    }

    /** Includes partial, queued, and in-flight audio until acknowledged or discarded. */
    fun hasPending(): Boolean = buffer.hasPending

    /** Measures quiet capture time only after actual PCM speech, independently of provider callbacks. */
    fun silenceMillis(): Long? =
        lastSpeechAt
            .get()
            .takeUnless { it == NO_SPEECH_AT }
            ?.let { (elapsedRealtime() - it).coerceAtLeast(0L) }

    /** Releases the generation lease without clearing a capture failure waiting for its successor. */
    @Synchronized
    internal fun streamSettled(stream: ConversationDictationCallerAudioStream) {
        activeStream.compareAndSet(stream, null)
    }

    /** Consumes a capture failure only after the controller accepts its owning generation. */
    @Synchronized
    internal fun acknowledgeFailure(failure: ConversationDictationCallerAudioFailure): Boolean {
        return when {
            discarded.get() -> false
            failure == ConversationDictationCallerAudioFailure.PipeFailed -> true
            pendingFailure == failure -> {
                pendingFailure = null
                true
            }
            else -> false
        }
    }

    /** The feeder shares the capture's monotonic clock, including deterministic stall tests. */
    internal fun elapsedRealtimeMillis(): Long = elapsedRealtime()

    /** Keeps a terminal failure until a current generation accepts it, rather than merely posting it. */
    @Synchronized
    private fun reportCaptureFailure(failure: ConversationDictationCallerAudioFailure) {
        if (discarded.get()) return
        if (pendingFailure == null) pendingFailure = failure
        pendingFailure?.let { activeStream.get()?.reportFailure(it) }
    }

    /** Records continuously across provider generations and seals the last read before closure. */
    private fun capture() {
        val samples = ShortArray(FRAMES_PER_READ)
        val encoded = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val progress = CallerAudioProgress(elapsedRealtime)
        var currentChunkHasSpeech = false
        var failure: ConversationDictationCallerAudioFailure? = null
        try {
            while (recording.get() && progress.stopReason == null) {
                // Keep an outstanding read, then drain the native buffer without recording a fresh tail.
                val blocking = !finishing.get()
                val read = device.read(samples, waitForSamples = blocking)
                if (read <= 0) {
                    progress.stopReason = "read=$read"
                    if (read < 0 || blocking) failure = unexpectedCaptureFailure()
                } else {
                    synchronized(this) {
                        if (captureSealed.get()) return@synchronized
                        // A native read already in progress can return samples after stop().
                        // Preserve completed reads until normal sealing or the forced-close grace expires.
                        currentChunkHasSpeech =
                            appendCapturedAudio(samples, read, encoded, progress, currentChunkHasSpeech)
                        if (
                            finishing.get() &&
                            (
                                postActionReadsRemaining.decrementAndGet() <= 0 ||
                                    elapsedRealtime() >= postActionDrainDeadline.get()
                            )
                        ) {
                            recording.set(false)
                        }
                    }
                }
            }
        } catch (error: RuntimeException) {
            progress.stopReason = "exception=${error.javaClass.simpleName}"
            failure = unexpectedCaptureFailure()
        } finally {
            samples.fill(0)
            encoded.fill(0)
            finishCaptureThread(progress, failure)
        }
    }

    /** Seals retained PCM and reports a failure before potentially blocking native cleanup. */
    private fun finishCaptureThread(
        progress: CallerAudioProgress,
        failure: ConversationDictationCallerAudioFailure?,
    ) {
        synchronized(this) {
            // A terminated recorder can replay PCM while native release is still pending.
            finishing.set(true)
            recording.set(false)
            if (!discarded.get()) buffer.finish()
            val terminalFailure =
                if (progress.stopReason == "buffer_full") {
                    ConversationDictationCallerAudioFailure.BufferFull
                } else {
                    failure
                }
            terminalFailure?.let(::reportCaptureFailure)
        }
        runCatching(device::stop)
        releaseRecorder()
        progress.reportClosed(buffer.bufferedBytes)
    }

    /** A requested tail drain still records; only force-close/discard/sealing explains read errors. */
    @Synchronized
    private fun unexpectedCaptureFailure(): ConversationDictationCallerAudioFailure? =
        if (recording.get() && !discarded.get() && !captureSealed.get()) {
            ConversationDictationCallerAudioFailure.CaptureFailed
        } else {
            null
        }

    /** Appends one recorder read and seals a speech-bearing chunk at a natural sentence boundary. */
    private fun appendCapturedAudio(
        samples: ShortArray,
        read: Int,
        encoded: ByteArray,
        progress: CallerAudioProgress,
        currentChunkHasSpeech: Boolean,
    ): Boolean {
        conversationDictationEncodePcm16(samples, read, encoded)
        val bytes = read * BYTES_PER_FRAME
        val peak = conversationDictationPeak(samples, read)
        val readHasSpeech = peak >= SPEECH_PEAK
        val readHasSpeechEvidence = peak >= MIN_SPEECH_EVIDENCE_PEAK
        if (!buffer.append(encoded, bytes, hasSpeech = readHasSpeechEvidence)) {
            progress.stopReason = "buffer_full"
            conversationDictationDiagnostic(
                "event=caller_audio_backpressure bytes=${buffer.bufferedBytes} action=stop_capture",
            )
            return currentChunkHasSpeech
        }

        if (readHasSpeech) lastSpeechAt.set(elapsedRealtime())
        progress.record(read, peak, buffer.bufferedBytes)
        val chunkHasSpeech = currentChunkHasSpeech || readHasSpeech
        val quietMillis = elapsedRealtime() - lastSpeechAt.get()
        val atSentenceBoundary = chunkHasSpeech && quietMillis >= SENTENCE_BOUNDARY_SILENCE_MILLIS
        val sealed =
            synchronized(this) {
                !finishing.get() && atSentenceBoundary && buffer.sealCurrentIfAtLeast(MIN_SENTENCE_CHUNK_BYTES)
            }
        if (sealed) {
            conversationDictationDiagnostic("event=caller_audio_chunk_sealed reason=silence")
        }
        return chunkHasSpeech && !sealed
    }

    /** Runs a closure observer once, including registration racing with recorder release. */
    internal fun onCaptureClosed(callback: () -> Unit) {
        if (captureClosed.get()) {
            callback()
            return
        }
        captureClosedCallbacks.add(callback)
        if (captureClosed.get() && captureClosedCallbacks.remove(callback)) callback()
    }

    /** Releases the device and delivers closure observers once across stop and cancellation races. */
    private fun releaseRecorder(sealed: Boolean = true) {
        if (sealed) captureSealed.set(true)
        if (recorderReleasing.compareAndSet(false, true)) {
            val released = runCatching(device::release).isSuccess
            if (!released) {
                recorderReleasing.set(false)
                conversationDictationDiagnostic("event=caller_audio_release_failed")
                return
            }
            recorderReleased.set(true)
        }
        // The last read must be sealed, and native release must have returned, before
        // completion can drop microphone ownership or deliver the final transcript.
        if (!captureSealed.get() || !recorderReleased.get() || !captureClosed.compareAndSet(false, true)) return
        while (captureClosedCallbacks.isNotEmpty()) captureClosedCallbacks.poll()?.invoke()
    }

    companion object {
        private const val NO_SPEECH_AT = -1L

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

private const val MIN_SENTENCE_CHUNK_SECONDS = 10
private const val MIN_SENTENCE_CHUNK_BYTES =
    CALLER_AUDIO_SAMPLE_RATE_HZ * BYTES_PER_FRAME * MIN_SENTENCE_CHUNK_SECONDS
private const val SENTENCE_BOUNDARY_SILENCE_MILLIS = 500L

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
    private val fullyFed = AtomicBoolean(false)
    private val feedClosedCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val cancelled = AtomicBoolean(false)
    private val captureFailureReported = AtomicBoolean(false)
    private val pipeFailureReported = AtomicBoolean(false)
    private val chunk = AtomicReference<ConversationDictationAudioChunk?>(null)

    /** Starts the device or feeder once; a sealed capture may only drain retained PCM. */
    fun start(): Boolean {
        if (!capture.start() || !feeding.compareAndSet(false, true)) return false
        thread(name = "dictation-caller-audio-feed", isDaemon = true, block = ::feed)
        return true
    }

    /** Seals the logical capture while this generation continues feeding its owned chunk. */
    fun finishCapture(onClosed: () -> Unit) = capture.finish(onClosed)

    /** Observes closure of the shared microphone capture, including its final partial chunk. */
    fun onCaptureClosed(callback: () -> Unit) = capture.onCaptureClosed(callback)

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

    /** Returns capture-side speech evidence for this generation's exact chunk, when claimed. */
    fun containsSpeech(): Boolean? = chunk.get()?.hasSpeech

    /** True only after every byte of this generation's exact chunk was supplied successfully. */
    fun fullyFed(): Boolean = fullyFed.get()

    /** Returns the stable identity of this generation's exact claimed chunk. */
    fun chunkId(): Long? = chunk.get()?.chunkId

    /** Checks sealed final ownership without acknowledging or changing retained PCM. */
    fun isFinalChunk(): Boolean = chunk.get()?.let { buffer.isFinalChunk(it.chunkId) } == true

    /** Returns this generation’s chunk to the front of the queue without duplicating its byte accounting. */
    fun retry(): Boolean = settle(requeue = true)

    /** Extends a no-speech chunk with following audio before returning it to the queue. */
    fun retryWithFollowingAudio(): Boolean = settle(requeue = true, coalesceFollowingAudio = true)

    /** Closes the feeder and relinquishes its lease, retaining unacknowledged PCM by default. */
    fun cancel(requeue: Boolean = true) {
        cancelled.set(true)
        settle(requeue)
        closePipe()
    }

    /** Delivers one typed capture failure to the generation that owns this stream. */
    fun reportFailure(failure: ConversationDictationCallerAudioFailure) {
        val reported =
            if (failure == ConversationDictationCallerAudioFailure.PipeFailed) {
                pipeFailureReported
            } else {
                captureFailureReported
            }
        if (!cancelled.get() && reported.compareAndSet(false, true)) onFailure(failure)
    }

    /** Claims one chunk, feeds its PCM, and requeues ownership on interruption or provider disconnection. */
    private fun feed() {
        try {
            var owned: ConversationDictationAudioChunk? = null
            while (!cancelled.get() && !settled.get() && owned == null) {
                owned = buffer.poll()
                if (owned == null) Thread.sleep(PIPE_RETRY_MILLIS)
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
        } catch (failure: RuntimeException) {
            reportFeedFailure(failure)
        } finally {
            closePipe()
        }
    }

    /** Requeues exact PCM before notifying the controller, even if settlement changed no buffer bytes. */
    private fun reportFeedFailure(failure: Exception) {
        conversationDictationDiagnostic(
            "event=caller_audio_feed_failed type=${failure.javaClass.simpleName} action=requeue",
        )
        settle(requeue = true) {
            reportFailure(ConversationDictationCallerAudioFailure.PipeFailed)
        }
    }

    /** Feeds an owned chunk until completion, cancellation, or a bounded nonblocking-write stall. */
    private fun writeChunk(owned: ConversationDictationAudioChunk) {
        val sink = writeEnd.fileDescriptor
        var offset = 0
        var stalledAt: Long? = null
        while (!cancelled.get() && !settled.get() && offset < owned.pcm.size) {
            val written =
                try {
                    pipeWriter.write(sink, owned.pcm, offset, owned.pcm.size - offset)
                } catch (failure: ErrnoException) {
                    if (failure.errno != OsConstants.EAGAIN) throw failure
                    0
                }
            if (written > 0) {
                offset += written
                stalledAt = null
            } else {
                val now = capture.elapsedRealtimeMillis()
                val since = stalledAt ?: now.also { stalledAt = it }
                if (now - since >= PIPE_STALL_TIMEOUT_MILLIS) {
                    conversationDictationDiagnostic(
                        "event=caller_audio_write_stalled chunk=${owned.chunkId} bytes=$offset",
                    )
                    reportFeedFailure(IOException("provider audio pipe stalled"))
                    return
                }
                Thread.sleep(PIPE_RETRY_MILLIS)
            }
        }
        conversationDictationDiagnostic(
            "event=caller_audio_chunk_fed chunk=${owned.chunkId} first_sample=${owned.firstSample} " +
                "last_sample=${owned.lastSampleExclusive} bytes=$offset",
        )
        fullyFed.set(offset == owned.pcm.size && !cancelled.get() && !settled.get())
    }

    /** Releases the active-stream lease exactly once, even before a chunk has been acquired. */
    private fun settle(
        requeue: Boolean,
        coalesceFollowingAudio: Boolean = false,
        onSettled: () -> Unit = {},
    ): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        val owned = chunk.getAndSet(null)
        val changed =
            when {
                owned == null -> false
                requeue && coalesceFollowingAudio -> buffer.retryWithFollowingAudio(owned.chunkId)
                requeue -> buffer.retry(owned.chunkId)
                else -> buffer.acknowledge(owned.chunkId)
            }
        capture.streamSettled(this)
        onSettled()
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
private class CallerAudioProgress(
    private val elapsedRealtime: () -> Long,
) {
    private val startedAt = elapsedRealtime()
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
        val now = elapsedRealtime()
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
    private fun elapsed(): Long = elapsedRealtime() - startedAt

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
